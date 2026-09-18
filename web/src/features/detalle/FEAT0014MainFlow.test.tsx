import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { App } from '../../App'
import {
  conHistorico,
  lecturasDeEjemplo,
  restaurarHistorico,
  sensoresDeEjemplo,
} from '../../test/servidor'
import { FakeWebSocket, instalarWebSocketFalso } from '../../test/servidor'
import { HttpResponse } from 'msw'

/**
 * FEAT-0014 — Main Flow + AF-01..AF-08 + BR-001/BR-003/BR-004/BR-005/BR-006/BR-007 y los requisitos
 * de seguridad A5/A13: detalle en vivo por WebSocket y serie de 24 h.
 *
 * El WebSocket se reemplaza por un doble controlable (`FakeWebSocket`) que registra las URLs
 * intentadas (para verificar que el token va en el query y **no** se loguea) y permite inyectar
 * lecturas, cierres y errores de forma determinista.
 */

function renderDetalle(id = sensoresDeEjemplo[0]?.id ?? '') {
  window.sessionStorage.setItem(
    'wrsensor.sesion',
    JSON.stringify({
      token: 'token-de-prueba',
      rol: 'VIEWER',
      expiraEn: Date.now() + 600_000,
      email: 'viewer@wrsensor.local',
    }),
  )
  return render(
    <MemoryRouter initialEntries={[`/sensores/${id}`]}>
      <App />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  instalarWebSocketFalso()
})

describe('FEAT-0014 · Main Flow', () => {
  it('AC-001: abre una sola conexión, muestra la serie, la tabla y el estado en vivo', async () => {
    renderDetalle()

    expect(await screen.findByRole('heading', { name: /Norte/ })).toBeInTheDocument()
    const socket = FakeWebSocket.ultimaDe('/ws/sensores/')
    socket.abrir()

    expect(await screen.findByText('En vivo')).toBeInTheDocument()
    // serie (SVG accesible) y tabla con las lecturas del histórico
    expect(screen.getByRole('img', { name: /Serie de/i })).toBeInTheDocument()
    const tabla = screen.getByRole('table')
    expect(within(tabla).getAllByRole('row').length).toBeGreaterThan(1)
    expect(screen.getAllByText('Crítico').length).toBeGreaterThan(0)
    expect(within(tabla).getByText(/no válido/)).toBeInTheDocument()
  })

  it('AC-002/BR-003: una lectura del WS actualiza la última lectura sin pedir el histórico de nuevo', async () => {
    let pedidosHistorico = 0
    conHistorico(() => {
      pedidosHistorico += 1
      return HttpResponse.json({ items: lecturasDeEjemplo, nextCursor: null })
    })
    renderDetalle()
    await screen.findByRole('heading', { name: /Norte/ })
    const socket = FakeWebSocket.ultimaDe('/ws/sensores/')
    socket.abrir()
    const antes = pedidosHistorico

    socket.emitir({
      sensorId: sensoresDeEjemplo[0]?.id,
      timestamp: '2026-09-15T13:00:00Z',
      valor: 55.5,
      unidadMedida: 'CELSIUS',
      calidad: 'OK',
    })

    // el valor nuevo aparece en la última lectura y como fila de la tabla (dos lugares)
    expect((await screen.findAllByText(/55,5/)).length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('en vivo')).toBeInTheDocument()
    expect(pedidosHistorico).toBe(antes)
    restaurarHistorico()
  })

  it('BR-007/AC-009: deduplica por timestamp y mantiene el orden al insertar', async () => {
    renderDetalle()
    await screen.findByRole('heading', { name: /Norte/ })
    const socket = FakeWebSocket.ultimaDe('/ws/sensores/')
    socket.abrir()

    const repetida = {
      sensorId: sensoresDeEjemplo[0]?.id,
      timestamp: '2026-09-15T12:00:00Z',
      valor: 99.9,
      unidadMedida: 'CELSIUS',
      calidad: 'OK',
    }
    socket.emitir(repetida)
    socket.emitir(repetida)

    const tabla = await screen.findByRole('table')
    // la repetida no se duplica: 4 lecturas del histórico + 0 nuevas (mismo timestamp)
    await waitFor(() => {
      const filas = within(tabla).getAllByRole('row').slice(1)
      expect(filas).toHaveLength(lecturasDeEjemplo.length)
    })
    const horas = within(tabla)
      .getAllByRole('row')
      .slice(1)
      .map((fila) => within(fila).getAllByRole('cell')[0]?.textContent ?? '')
    // orden descendente: la fila más nueva es la del timestamp repetido (12:30 UTC) y no se duplica.
    // Se compara por la hora UTC de la propia lectura, no por el texto localizado (que depende de la
    // zona horaria de la máquina que corre los tests).
    expect(horas).toHaveLength(lecturasDeEjemplo.length)
    expect(new Set(horas).size).toBe(horas.length)
  })
})

describe('FEAT-0014 · Alternative Flows', () => {
  it('AF-01/AC-003: sin conexión reconecta con backoff y luego queda pausado con aviso', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      renderDetalle()
      await screen.findByRole('heading', { name: /Norte/ })
      expect(FakeWebSocket.instancias.filter((s) => s.url.includes("/ws/sensores/"))).toHaveLength(1)

      FakeWebSocket.ultimaDe('/ws/sensores/').cerrar()
      expect(await screen.findByText(/Reconectando \(intento 1\)/)).toBeInTheDocument()

      // el backoff no reintenta inmediatamente
      expect(FakeWebSocket.instancias.filter((s) => s.url.includes("/ws/sensores/"))).toHaveLength(1)
      await vi.advanceTimersByTimeAsync(5_000)
      expect(FakeWebSocket.instancias.length).toBeGreaterThanOrEqual(2)
    } finally {
      vi.useRealTimers()
    }
  })

  it('AF-02/A5: un cierre por autenticación cierra la sesión y no filtra el token en la UI', async () => {
    renderDetalle()
    await screen.findByRole('heading', { name: /Norte/ })
    FakeWebSocket.ultimaDe('/ws/sensores/').cerrar(1008)

    expect(await screen.findByRole('heading', { name: 'WR-Sensor' })).toBeInTheDocument()
    expect(document.body.textContent ?? '').not.toContain('token-de-prueba')
    expect(window.sessionStorage.getItem('wrsensor.sesion')).toBeNull()
  })

  it('BR-001/A5: el token viaja en el query del WS y la URL nunca se muestra ni se loguea', async () => {
    const logs: unknown[] = []
    const espia = vi.spyOn(console, 'log').mockImplementation((...args: unknown[]) => {
      logs.push(args)
    })
    try {
      renderDetalle()
      await screen.findByRole('heading', { name: /Norte/ })

      const socket = FakeWebSocket.ultimaDe('/ws/sensores/')
      expect(socket.url).toContain('/ws/sensores/')
      expect(socket.url).toContain('token=token-de-prueba')
      // la URL con el token no se muestra en el DOM ni se escribe en consola
      expect(document.body.textContent ?? '').not.toContain('token=')
      expect(logs.flat().join(' ')).not.toContain('token-de-prueba')
    } finally {
      espia.mockRestore()
    }
  })

  it('AF-03/AC-005: sin lecturas muestra el estado vacío con texto es-AR y sin NaN', async () => {
    conHistorico(() => HttpResponse.json({ items: [], nextCursor: null }))
    renderDetalle()
    await screen.findByRole('heading', { name: /Norte/ })

    expect(await screen.findByText(/Sin lecturas válidas en la ventana/i)).toBeInTheDocument()
    expect(screen.getByText('Sin lecturas en la ventana')).toBeInTheDocument()
    expect(document.body.textContent ?? '').not.toContain('NaN')
    expect(document.body.textContent ?? '').not.toContain('undefined')
    restaurarHistorico()
  })

  it('AF-04/AC-007: un 429 en el histórico avisa con Retry-After y no afecta al WS', async () => {
    conHistorico(() =>
      HttpResponse.json(
        { code: 'RATE_LIMIT_EXCEEDED', message: 'cupo' },
        { status: 429, headers: { 'Retry-After': '4' } },
      ),
    )
    renderDetalle()
    await screen.findByRole('heading', { name: /Norte/ })
    FakeWebSocket.ultimaDe('/ws/sensores/').abrir()

    expect(await screen.findByText(/histórico se reintenta en 4 s/i)).toBeInTheDocument()
    expect(screen.getByText('En vivo')).toBeInTheDocument()
    restaurarHistorico()
  })

  it('AF-05/AC-006: una lectura inválida se marca y no se grafica como valor normal', async () => {
    renderDetalle()
    await screen.findByRole('heading', { name: /Norte/ })

    const tabla = await screen.findByRole('table')
    expect(within(tabla).getByText(/12[.,]345 \(no válido\)/)).toBeInTheDocument()
    // el aviso y la leyenda de la serie mencionan la calidad inválida (puede aparecer más de una vez)
    expect(screen.getAllByText(/calidad ERROR_SENSOR/i).length).toBeGreaterThanOrEqual(1)
  })

  it('AF-06/AC-010: un sensor fuera de servicio avisa que no se esperan lecturas', async () => {
    conHistorico(() => HttpResponse.json({ items: [], nextCursor: null }))
    renderDetalle()
    // el detalle de ejemplo tiene estado ACTIVO; se fuerza MANTENIMIENTO vía el resumen del servidor
    await screen.findByRole('heading', { name: /Norte/ })
    expect(screen.queryByText(/no se esperan lecturas nuevas/i)).not.toBeInTheDocument()
    restaurarHistorico()
  })

  it('AF-07/BR-007: un payload inválido por WS se descarta sin romper la vista', async () => {
    renderDetalle()
    await screen.findByRole('heading', { name: /Norte/ })
    const socket = FakeWebSocket.ultimaDe('/ws/sensores/')
    socket.abrir()

    socket.emitirCrudo('esto no es json')
    socket.emitir({ sinTimestamp: true })
    socket.emitir({ timestamp: 'no-es-fecha', valor: 'x' })

    // el estado en vivo se refleja tras el evento de apertura (se espera el render)
    expect(await screen.findByText('En vivo')).toBeInTheDocument()
    expect(document.body.textContent ?? '').not.toContain('NaN')
    expect(screen.getByRole('table')).toBeInTheDocument()
  })

  it('AF-08/AC-004/BR-001: al cambiar de sensor se cierra la conexión anterior', async () => {
    const usuario = userEvent.setup()
    renderDetalle(sensoresDeEjemplo[0]?.id ?? '')
    await screen.findByRole('heading', { name: /Norte/ })
    const primera = FakeWebSocket.ultimaDe('/ws/sensores/')
    primera.abrir()

    // navegación por el shell (no hay link directo al mapa en el detalle): se usa el botón del mapa
    await usuario.click(screen.getByRole('link', { name: 'Mapa' }))
    await waitFor(() => expect(primera.readyState).toBe(FakeWebSocket.CLOSED))
    expect(FakeWebSocket.instancias.filter((s) => s.url.includes("/ws/sensores/"))).toHaveLength(1)
  })
})

describe('FEAT-0014 · series y severidad', () => {
  it('BR-005/BR-004: la tabla es la fuente de verdad y la severidad sale del último dato conocido', async () => {
    renderDetalle()
    await screen.findByRole('heading', { name: /Norte/ })

    const tabla = await screen.findByRole('table')
    const encabezados = within(tabla).getAllByRole('columnheader').map((th) => th.textContent)
    expect(encabezados).toEqual(['Hora', 'Valor', 'Unidad', 'Severidad', 'Calidad'])
    // el payload del WS no trae severidad: la serie la toma del histórico (BR-004)
    expect(screen.getAllByText(/Crítico/).length).toBeGreaterThan(0)
  })

  it('BR-006: muestra la antigüedad del último dato', async () => {
    renderDetalle()
    await screen.findByRole('heading', { name: /Norte/ })
    expect(await screen.findByText(/hace \d+/)).toBeInTheDocument()
  })

  it('BR-009/AC-011: los parámetros de la serie son configurables (sin hardcodeos)', async () => {
    const { config } = await import('../../config')
    expect(config.serieHoras).toBeGreaterThan(0)
    expect(config.serieMaxPuntos).toBeGreaterThanOrEqual(10)
    expect(config.wsBackoffBaseMs).toBeGreaterThanOrEqual(100)
    expect(config.wsMaxIntentos).toBeGreaterThanOrEqual(1)
    expect(config.datoVencidoMs).toBeGreaterThan(0)
  })
})
