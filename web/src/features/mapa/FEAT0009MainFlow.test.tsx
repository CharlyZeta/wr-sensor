import { beforeEach, describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { App } from '../../App'
import { conResumen, instalarWebSocketFalso, restaurarResumen, sensoresDeEjemplo } from '../../test/servidor'
import { HttpResponse } from 'msw'

/**
 * FEAT-0009 — Main Flow + AF-01/AF-04/AF-05/AF-08 + AC-003/AC-005/AC-006/AC-008:
 * sesión, mapa con el resumen y estados de la UI. Los tests usan MSW, así que ejercitan el cliente
 * HTTP real (códigos de error y correlación incluidos).
 */
beforeEach(() => {
  instalarWebSocketFalso()
})

function renderApp(ruta = '/mapa') {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <App />
    </MemoryRouter>,
  )
}

async function entrar(usuario: ReturnType<typeof userEvent.setup>) {
  await usuario.type(screen.getByLabelText('Usuario'), 'admin@wrsensor.local')
  await usuario.type(screen.getByLabelText('Contraseña'), 'Admin123!')
  await usuario.click(screen.getByRole('button', { name: 'Entrar' }))
}

describe('FEAT-0009 · Main Flow', () => {
  it('AC-003/Main Flow 2-3: login y mapa con un ítem por sensor del resumen', async () => {
    const usuario = userEvent.setup()
    renderApp('/mapa')

    // sin sesión, la ruta protegida lleva al login
    expect(await screen.findByRole('heading', { name: 'WR-Sensor' })).toBeInTheDocument()

    await entrar(usuario)

    expect(await screen.findByRole('heading', { name: 'Mapa de sensores' })).toBeInTheDocument()
    const lista = await screen.findByRole('list', { name: 'Sensores' })
    expect(within(lista).getAllByRole('listitem')).toHaveLength(sensoresDeEjemplo.length)
    expect(screen.getByText('ADMIN')).toBeInTheDocument()
  })

  it('AF-08/AC-008: sin sensores se muestra un estado vacío explícito (nunca pantalla en blanco)', async () => {
    conResumen(() => HttpResponse.json([]))
    const usuario = userEvent.setup()
    renderApp('/mapa')
    await entrar(usuario)

    expect(await screen.findByText('No hay sensores para mostrar')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Salir' })).toBeInTheDocument()
    restaurarResumen()
  })
})

describe('FEAT-0009 · Alternative Flows', () => {
  it('AF-01/AC-006: un 401 cierra la sesión y vuelve al login', async () => {
    conResumen(() =>
      HttpResponse.json({ code: 'UNAUTHENTICATED', message: 'token vencido' }, { status: 401 }),
    )
    const usuario = userEvent.setup()
    renderApp('/mapa')

    // Se hace el login a mano (no con `entrar()`, que espera el mapa): acá el resumen responde 401,
    // así que el SPA cierra la sesión y vuelve al login sin llegar a pintar el mapa.
    await usuario.type(await screen.findByLabelText('Usuario'), 'admin@wrsensor.local')
    await usuario.type(screen.getByLabelText('Contraseña'), 'Admin123!')
    await usuario.click(screen.getByRole('button', { name: 'Entrar' }))

    // El estado final observable es doble: sesión borrada y pantalla de login otra vez.
    await waitFor(() => expect(window.sessionStorage.getItem('wrsensor.sesion')).toBeNull())
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'WR-Sensor' })).toBeInTheDocument(),
    )
    expect(screen.queryByRole('heading', { name: 'Mapa de sensores' })).not.toBeInTheDocument()
    restaurarResumen()
  })

  it('AF-02: un 403 se informa por code y la sesión sigue viva', async () => {
    conResumen(() =>
      HttpResponse.json({ code: 'INSUFFICIENT_ROLE', message: 'rol insuficiente' }, { status: 403 }),
    )
    const usuario = userEvent.setup()
    renderApp('/mapa')
    await entrar(usuario)

    expect(await screen.findByText(/no tiene permisos/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Salir' })).toBeInTheDocument()
    restaurarResumen()
  })

  it('AF-04/AC-005: 502 REGISTRY_UNAVAILABLE muestra el mensaje del code, la correlación y reintento', async () => {
    conResumen(() =>
      HttpResponse.json(
        { code: 'REGISTRY_UNAVAILABLE', message: 'registry no disponible: connection refused' },
        { status: 502, headers: { 'X-Correlation-Id': 'corr-502' } },
      ),
    )
    const usuario = userEvent.setup()
    renderApp('/mapa')
    await entrar(usuario)

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent(/catálogo de sensores/i)
    expect(alerta).toHaveTextContent('code=REGISTRY_UNAVAILABLE')
    expect(alerta).toHaveTextContent('correlacion=corr-502')
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
    // AF-04: no hay marcadores ni lista parcial
    expect(screen.queryByRole('list', { name: 'Sensores' })).not.toBeInTheDocument()
    restaurarResumen()
  })

  it('AF-05: un error de red se informa sin mostrar datos viejos como frescos', async () => {
    conResumen(() => HttpResponse.error())
    const usuario = userEvent.setup()
    renderApp('/mapa')
    await entrar(usuario)

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent(/no se pudo contactar al servidor/i)
    expect(screen.queryByRole('list', { name: 'Sensores' })).not.toBeInTheDocument()
    restaurarResumen()
  })

  it('AF-07: tras iniciar sesión se vuelve a la ruta que el usuario había pedido', async () => {
    const usuario = userEvent.setup()
    renderApp('/sensores/00000000-0000-4000-8000-00000000000a')

    await entrar(usuario)

    // vuelve a la ruta pedida: el detalle del sensor (FEAT-0014 ya lo implementa de verdad)
    expect(
      await screen.findByRole('heading', { name: /Norte/ }),
    ).toBeInTheDocument()
  })

  it('AF-07: un id de sensor inválido no rompe la vista (se valida antes de usarlo)', async () => {
    window.sessionStorage.setItem(
      'wrsensor.sesion',
      JSON.stringify({
        token: 'token-de-prueba',
        rol: 'VIEWER',
        expiraEn: Date.now() + 600_000,
        email: 'viewer@wrsensor.local',
      }),
    )
    renderApp('/sensores/no-es-un-uuid')

    expect(await screen.findByText('Identificador de sensor inválido')).toBeInTheDocument()
  })
})

describe('FEAT-0009 · cupo del gateway', () => {
  it('AF-03/AC-007: respeta Retry-After y no dispara ráfagas de reintentos', async () => {
    let llamadas = 0
    conResumen(() => {
      llamadas += 1
      return HttpResponse.json(
        { code: 'RATE_LIMIT_EXCEEDED', message: 'cupo excedido' },
        { status: 429, headers: { 'Retry-After': '5' } },
      )
    })
    const usuario = userEvent.setup()
    renderApp('/mapa')
    await entrar(usuario)

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent(/límite de pedidos/i)
    expect(alerta).toHaveTextContent('code=RATE_LIMIT_EXCEEDED')
    const primeras = llamadas
    // el refresco automático está pausado: no debe haber más requests por un rato
    await new Promise((r) => setTimeout(r, 300))
    expect(llamadas).toBe(primeras)
    restaurarResumen()
  })

  it('BR-005: el refresco periódico no hace polling por debajo del período configurado', async () => {
    let llamadas = 0
    conResumen(() => {
      llamadas += 1
      return HttpResponse.json(sensoresDeEjemplo)
    })
    const usuario = userEvent.setup()
    renderApp('/mapa')
    await entrar(usuario)

    await waitFor(() => expect(llamadas).toBe(1))
    await new Promise((r) => setTimeout(r, 400))
    expect(llamadas).toBe(1)
    restaurarResumen()
  })
})