import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { App } from '../../App'
import { conResumen, restaurarResumen, sensoresDeEjemplo } from '../../test/servidor'
import { HttpResponse } from 'msw'

/**
 * FEAT-0015 — Main Flow + AF-01..AF-08 + BR-001..BR-009: feed de alertas confirmadas.
 *
 * El WS se reemplaza por un doble controlable (mismo patrón que FEAT-0014) para poder afirmar
 * cuántas conexiones se abren, qué URL llevan y qué pasa con ráfagas, duplicados y normalizaciones.
 */
class FakeWebSocket {
  static instancias: FakeWebSocket[] = []
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 3

  readonly url: string
  readyState = FakeWebSocket.CONNECTING
  onopen: ((e: Event) => void) | null = null
  onmessage: ((e: MessageEvent) => void) | null = null
  onclose: ((e: CloseEvent) => void) | null = null
  onerror: ((e: Event) => void) | null = null

  constructor(url: string) {
    this.url = url
    FakeWebSocket.instancias.push(this)
  }

  abrir(): void {
    this.readyState = FakeWebSocket.OPEN
    this.onopen?.(new Event('open'))
  }

  emitir(payload: unknown): void {
    this.onmessage?.({ data: JSON.stringify(payload) } as MessageEvent)
  }

  emitirCrudo(texto: string): void {
    this.onmessage?.({ data: texto } as MessageEvent)
  }

  cerrar(code = 1006): void {
    this.readyState = FakeWebSocket.CLOSED
    this.onclose?.({ code } as CloseEvent)
  }

  close(): void {
    this.readyState = FakeWebSocket.CLOSED
  }

  static get ultima(): FakeWebSocket {
    const ultima = FakeWebSocket.instancias[FakeWebSocket.instancias.length - 1]
    if (ultima === undefined) {
      throw new Error('no hay WebSocket abierto')
    }
    return ultima
  }

  static get deAlertas(): FakeWebSocket[] {
    return FakeWebSocket.instancias.filter((s) => s.url.includes('/ws/alertas'))
  }
}

function sesionActiva(): void {
  window.sessionStorage.setItem(
    'wrsensor.sesion',
    JSON.stringify({
      token: 'token-de-prueba',
      rol: 'VIEWER',
      expiraEn: Date.now() + 600_000,
      email: 'viewer@wrsensor.local',
    }),
  )
}

function renderApp(ruta = '/mapa') {
  sesionActiva()
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <App />
    </MemoryRouter>,
  )
}

const ALERTA = {
  sensorId: '00000000-0000-4000-8000-00000000000b',
  severidadNueva: 'CRITICAL',
  severidadAnterior: 'NORMAL',
  confirmada: true,
  timestamp: '2026-09-15T12:10:00Z',
}

beforeEach(() => {
  FakeWebSocket.instancias = []
  vi.stubGlobal('WebSocket', FakeWebSocket as unknown as typeof WebSocket)
})

describe('FEAT-0015 · Main Flow', () => {
  it('AC-001: al entrar hay exactamente una conexión a /ws/alertas con el token', async () => {
    renderApp('/mapa')
    await screen.findByRole('heading', { name: 'Mapa de sensores' })

    await waitFor(() => expect(FakeWebSocket.deAlertas).toHaveLength(1))
    const socket = FakeWebSocket.deAlertas[0]
    expect(socket?.url).toContain('/ws/alertas')
    expect(socket?.url).toContain('token=token-de-prueba')
    // el token no se muestra en el DOM
    expect(document.body.textContent ?? '').not.toContain('token=')
  })

  it('AC-002: una alerta CRITICAL aparece en el feed con el nombre del sensor y su transición', async () => {
    renderApp('/alertas')
    await screen.findByRole('heading', { name: 'Alertas' })
    FakeWebSocket.ultima.abrir()

    FakeWebSocket.ultima.emitir(ALERTA)

    const feed = await screen.findByRole('list', { name: 'Alertas confirmadas' })
    expect(within(feed).getByText(/Centro/)).toBeInTheDocument()
    expect(within(feed).getAllByText(/Crítico/).length).toBeGreaterThanOrEqual(1)
    expect(within(feed).getByText(/antes NORMAL/)).toBeInTheDocument()
    expect(within(feed).getByRole('link', { name: 'Ver detalle del sensor' })).toBeInTheDocument()
  })

  it('BR-001: navegar entre vista y detalle no abre conexiones nuevas de alertas', async () => {
    const usuario = userEvent.setup()
    renderApp('/mapa')
    await screen.findByRole('heading', { name: 'Mapa de sensores' })
    await waitFor(() => expect(FakeWebSocket.deAlertas).toHaveLength(1))

    await usuario.click(screen.getByRole('link', { name: 'Alertas' }))
    await screen.findByRole('heading', { name: 'Alertas' })
    await usuario.click(screen.getByRole('link', { name: 'Mapa' }))
    await screen.findByRole('heading', { name: 'Mapa de sensores' })

    expect(FakeWebSocket.deAlertas).toHaveLength(1)
  })
})

describe('FEAT-0015 · Alternative Flows', () => {
  it('AC-003/AF-03/BR-003: deduplica la alerta repetida y ordena por timestamp descendente', async () => {
    renderApp('/alertas')
    await screen.findByRole('heading', { name: 'Alertas' })
    FakeWebSocket.ultima.abrir()

    FakeWebSocket.ultima.emitir(ALERTA)
    FakeWebSocket.ultima.emitir(ALERTA) // duplicada exacta
    FakeWebSocket.ultima.emitir({ ...ALERTA, timestamp: '2026-09-15T11:00:00Z', severidadNueva: 'WARNING', severidadAnterior: 'NORMAL' })

    const feed = await screen.findByRole('list', { name: 'Alertas confirmadas' })
    await waitFor(() => expect(within(feed).getAllByRole('listitem')).toHaveLength(2))
    const items = within(feed).getAllByRole('listitem')
    expect(items[0]?.textContent).toContain('Crítico')
    expect(items[1]?.textContent).toContain('Advertencia')
  })

  it('AC-004/AF-08/BR-005: el feed nace vacío y avisa que el historial no se persiste', async () => {
    renderApp('/alertas')
    await screen.findByRole('heading', { name: 'Alertas' })

    expect(await screen.findByText('Sin alertas en esta sesión')).toBeInTheDocument()
    expect(screen.getByText(/el historial se pierde \(no se guarda en el servidor\)/i)).toBeInTheDocument()
  })

  it('AC-006/AF-07/BR-006: una normalización no cuenta como crítica no leída', async () => {
    renderApp('/alertas')
    await screen.findByRole('heading', { name: 'Alertas' })
    FakeWebSocket.ultima.abrir()

    // subida (cuenta) y bajada (no cuenta)
    FakeWebSocket.ultima.emitir(ALERTA)
    FakeWebSocket.ultima.emitir({
      ...ALERTA,
      timestamp: '2026-09-15T12:20:00Z',
      severidadNueva: 'NORMAL',
      severidadAnterior: 'CRITICAL',
    })

    const feed = await screen.findByRole('list', { name: 'Alertas confirmadas' })
    await waitFor(() => expect(within(feed).getAllByRole('listitem')).toHaveLength(2))
    expect(within(feed).getByText(/normalización/)).toBeInTheDocument()
  })

  it('AC-009/AF-05/BR-007: una alerta de un sensor sin metadata muestra el id y ofrece recargar', async () => {
    conResumen(() => HttpResponse.json([sensoresDeEjemplo[0]]))
    renderApp('/alertas')
    await screen.findByRole('heading', { name: 'Alertas' })
    FakeWebSocket.ultima.abrir()

    FakeWebSocket.ultima.emitir({
      ...ALERTA,
      sensorId: '00000000-0000-4000-8000-0000000000ff',
    })

    const feed = await screen.findByRole('list', { name: 'Alertas confirmadas' })
    expect(await within(feed).findByText(/Sensor fuera del resumen cargado/)).toBeInTheDocument()
    expect(within(feed).getByText('00000000-0000-4000-8000-0000000000ff')).toBeInTheDocument()
    expect(within(feed).getByRole('button', { name: 'Recargar sensores' })).toBeInTheDocument()
    restaurarResumen()
  })

  it('AC-007/AF-01/BR-002: sin conexión reconecta con estados visibles y conserva el feed', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      renderApp('/alertas')
      await screen.findByRole('heading', { name: 'Alertas' })
      FakeWebSocket.ultima.abrir()
      FakeWebSocket.ultima.emitir(ALERTA)
      const feed = await screen.findByRole('list', { name: 'Alertas confirmadas' })

      FakeWebSocket.ultima.cerrar()
      expect(await screen.findByText(/Reconectando alertas \(intento 1\)/)).toBeInTheDocument()
      // el feed previo no se borra
      expect(within(feed).getAllByRole('listitem')).toHaveLength(1)
      await vi.advanceTimersByTimeAsync(5_000)
      expect(FakeWebSocket.deAlertas.length).toBeGreaterThanOrEqual(2)
    } finally {
      vi.useRealTimers()
    }
  })

  it('AC-008/AF-02: un cierre por autenticación cierra la sesión', async () => {
    renderApp('/alertas')
    await screen.findByRole('heading', { name: 'Alertas' })
    FakeWebSocket.ultima.cerrar(1008)

    expect(await screen.findByRole('heading', { name: 'WR-Sensor' })).toBeInTheDocument()
    expect(window.sessionStorage.getItem('wrsensor.sesion')).toBeNull()
  })

  it('AF-07/A13: payloads inválidos se descartan sin romper el feed', async () => {
    renderApp('/alertas')
    await screen.findByRole('heading', { name: 'Alertas' })
    FakeWebSocket.ultima.abrir()

    FakeWebSocket.ultima.emitirCrudo('no es json')
    FakeWebSocket.ultima.emitir({ sinSensor: true })
    FakeWebSocket.ultima.emitir({ sensorId: 'x', timestamp: 'y', severidadNueva: 'INVENTADA' })

    expect(await screen.findByText('Sin alertas en esta sesión')).toBeInTheDocument()
    expect(document.body.textContent ?? '').not.toContain('undefined')
  })
})

describe('FEAT-0015 · ráfagas y contador', () => {
  it('AC-005/BR-004: cinco alertas en menos de un segundo disparan UNA sola recarga del resumen', async () => {
    let pedidosResumen = 0
    conResumen(() => {
      pedidosResumen += 1
      return HttpResponse.json(sensoresDeEjemplo)
    })
    renderApp('/mapa')
    await screen.findByRole('heading', { name: 'Mapa de sensores' })
    await waitFor(() => expect(pedidosResumen).toBe(1))
    FakeWebSocket.ultima.abrir()

    for (let i = 0; i < 5; i += 1) {
      FakeWebSocket.ultima.emitir({
        ...ALERTA,
        timestamp: `2026-09-15T12:1${i}:00Z`,
      })
    }

    // el debounce (2 s por default) agrupa la ráfaga en una única recarga
    await waitFor(() => expect(pedidosResumen).toBe(2), { timeout: 6_000 })
    await new Promise((r) => setTimeout(r, 500))
    expect(pedidosResumen).toBe(2)
    restaurarResumen()
  })

  it('BR-006/AC-006: el contador de no leídas aparece en la barra y se limpia al abrir el feed', async () => {
    const usuario = userEvent.setup()
    renderApp('/mapa')
    await screen.findByRole('heading', { name: 'Mapa de sensores' })
    FakeWebSocket.ultima.abrir()

    FakeWebSocket.ultima.emitir(ALERTA)

    const enlace = await screen.findByRole('link', { name: /Alertas/ })
    await waitFor(() => expect(enlace.textContent).toMatch(/1/))

    await usuario.click(enlace)
    await screen.findByRole('heading', { name: 'Alertas' })
    await waitFor(() => expect(screen.getByRole('link', { name: 'Alertas' }).textContent).not.toMatch(/1/))
  })

  it('BR-008/AC-010: la alerta nueva se anuncia por una región aria-live sin mover el foco', async () => {
    renderApp('/alertas')
    await screen.findByRole('heading', { name: 'Alertas' })
    FakeWebSocket.ultima.abrir()

    const vivo = document.querySelector('[aria-live="polite"]')
    expect(vivo).not.toBeNull()
    FakeWebSocket.ultima.emitir(ALERTA)
    await waitFor(() => expect(vivo?.textContent).toMatch(/Alerta CRITICAL/))
    // el foco sigue en el body (no se robó)
    expect(document.activeElement).toBe(document.body)
  })

  it('BR-009/AC-011: los parámetros del feed son configurables', async () => {
    const { config } = await import('../../config')
    expect(config.alertasMax).toBeGreaterThanOrEqual(10)
    expect(config.alertasDebounceMs).toBeGreaterThanOrEqual(100)
  })
})