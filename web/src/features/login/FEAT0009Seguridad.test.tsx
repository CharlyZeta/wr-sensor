import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { App } from '../../App'
import { conLogin, conResumen, restaurarLogin, restaurarResumen, sensoresDeEjemplo, TOKEN_VALIDO } from '../../test/servidor'
import { HttpResponse } from 'msw'

/**
 * FEAT-0009 — BR-002/BR-004/BR-007/BR-009 y AF-06, más los requisitos de seguridad A2/A4/A9/A10 de
 * la revisión: el token sólo en `sessionStorage`, nunca en `localStorage` ni en cookies, sin
 * `dangerouslySetInnerHTML`, datos del backend renderizados como texto y `VIEWER` sin acciones de
 * escritura.
 */
function renderApp(ruta = '/mapa') {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <App />
    </MemoryRouter>,
  )
}

async function entrar(
  usuario: ReturnType<typeof userEvent.setup>,
  email = 'admin@wrsensor.local',
  password = 'Admin123!',
) {
  await usuario.type(screen.getByLabelText('Usuario'), email)
  await usuario.type(screen.getByLabelText('Contraseña'), password)
  await usuario.click(screen.getByRole('button', { name: 'Entrar' }))
  await screen.findByRole('heading', { name: 'Mapa de sensores' })
}

describe('FEAT-0009 · sesión y almacenamiento (BR-002, A4, A9)', () => {
  it('BR-002/A9: el token queda en sessionStorage y en ningún otro lado', async () => {
    const usuario = userEvent.setup()
    renderApp()
    await entrar(usuario)

    const guardado = window.sessionStorage.getItem('wrsensor.sesion')
    expect(guardado).not.toBeNull()
    expect(guardado).toContain(TOKEN_VALIDO)
    // nunca en localStorage / cookies / URL
    const local = window.localStorage as Storage | undefined
    expect(local === undefined || local.length === 0).toBe(true)
    expect(document.cookie).toBe('')
    expect(window.location.search).not.toContain('token')
  })

  it('BR-002/A7: el logout limpia el almacenamiento y no deja datos en pantalla', async () => {
    const usuario = userEvent.setup()
    renderApp()
    await entrar(usuario)

    await usuario.click(screen.getByRole('button', { name: 'Salir' }))

    await waitFor(() => expect(window.sessionStorage.getItem('wrsensor.sesion')).toBeNull())
    expect(await screen.findByRole('heading', { name: 'WR-Sensor' })).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Sensores' })).not.toBeInTheDocument()
  })

  it('AF-01: una sesión guardada pero vencida no se reutiliza', async () => {
    window.sessionStorage.setItem(
      'wrsensor.sesion',
      JSON.stringify({
        token: 'viejo',
        rol: 'ADMIN',
        expiraEn: Date.now() - 1000,
        email: 'admin@wrsensor.local',
      }),
    )
    renderApp()

    expect(await screen.findByRole('heading', { name: 'WR-Sensor' })).toBeInTheDocument()
    expect(window.sessionStorage.getItem('wrsensor.sesion')).toBeNull()
  })

  it('AF-01: una sesión guardada malformada se descarta sin romper la app', async () => {
    window.sessionStorage.setItem('wrsensor.sesion', '{no-es-json')
    renderApp()

    expect(await screen.findByRole('heading', { name: 'WR-Sensor' })).toBeInTheDocument()
    expect(window.sessionStorage.getItem('wrsensor.sesion')).toBeNull()
  })
})

describe('FEAT-0009 · rol y autorización en la UI (AF-06, A14)', () => {
  it('AF-06: VIEWER no ve el chip de administrador ni acciones de escritura', async () => {
    const usuario = userEvent.setup()
    renderApp()
    await entrar(usuario, 'viewer@wrsensor.local', 'Viewer123!')

    expect(screen.getByText('VIEWER')).toBeInTheDocument()
    expect(screen.getByText('sólo lectura')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /admin/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nuevo|editar|borrar/i })).not.toBeInTheDocument()
  })

  it('AF-06: ADMIN ve su rol y no la marca de sólo lectura', async () => {
    const usuario = userEvent.setup()
    renderApp()
    await entrar(usuario)

    expect(screen.getByText('ADMIN')).toBeInTheDocument()
    expect(screen.queryByText('sólo lectura')).not.toBeInTheDocument()
  })
})

describe('FEAT-0009 · severidad y datos no confiables (BR-007, A2, A12)', () => {
  it('BR-007/AC-004: cada severidad usa su etiqueta y símbolo, y el sensor sin lecturas se distingue', async () => {
    const usuario = userEvent.setup()
    renderApp()
    await entrar(usuario)

    const lista = within(await screen.findByRole('list', { name: 'Sensores' }))
    // se ordena por gravedad: CRITICAL primero
    const items = lista.getAllByRole('listitem')
    expect(items[0]).toHaveTextContent('Crítico')
    expect(lista.getByText(/Advertencia/)).toBeInTheDocument()
    expect(lista.getByText(/Normal/)).toBeInTheDocument()
    expect(lista.getByText(/Sin datos/)).toBeInTheDocument()
    // el sensor sin lecturas muestra "Sin lecturas" como valor (el texto de severidad es "Sin datos")
    expect(lista.getAllByText('Sin lecturas').length).toBeGreaterThanOrEqual(1)
  })

  it('A2/A12: un nombre con HTML hostil se renderiza como texto, sin nodos inyectados', async () => {
    conResumen(() =>
      HttpResponse.json([
        {
          id: '00000000-0000-4000-8000-00000000000e',
          codigo: '<script>alert(1)</script>',
          nombre: '<img src=x onerror=alert(1)>',
          tipo: 'TEMPERATURA',
          latitud: -31.6,
          longitud: -60.7,
          estado: 'ACTIVO',
          unidadMedida: 'CELSIUS',
          ultimaLectura: {
            valor: 1,
            timestamp: '2026-09-15T12:00:00Z',
            severidad: 'NORMAL',
            calidad: 'OK',
          },
        },
      ]),
    )
    const usuario = userEvent.setup()
    renderApp()
    await entrar(usuario)

    const lista = within(await screen.findByRole('list', { name: 'Sensores' }))
    expect(lista.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument()
    // el texto se muestra literal: no hay <img> ni <script> inyectados por los datos
    expect(lista.queryByRole('img')).not.toBeInTheDocument()
    expect(document.querySelector('img[src="x"]')).toBeNull()
    restaurarResumen()
  })

  it('A13: sensores sin coordenadas válidas no rompen el mapa y se informan en la lista', async () => {
    conResumen(() =>
      HttpResponse.json([
        { ...sensoresDeEjemplo[0], latitud: Number.NaN, longitud: null },
        sensoresDeEjemplo[1],
      ]),
    )
    const usuario = userEvent.setup()
    renderApp()
    await entrar(usuario)

    expect(await screen.findByText(/sin coordenadas válidas/i)).toBeInTheDocument()
    expect(within(await screen.findByRole('list', { name: 'Sensores' })).getAllByRole('listitem'))
      .toHaveLength(2)
    restaurarResumen()
  })

  it('BR-004: un code desconocido se muestra tal cual (no se inventa un mensaje)', async () => {
    conResumen(() =>
      HttpResponse.json({ code: 'CODIGO_RARO', message: 'algo' }, { status: 418 }),
    )
    const usuario = userEvent.setup()
    renderApp()
    await entrar(usuario)

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('CODIGO_RARO')
    restaurarResumen()
  })
})

describe('FEAT-0009 · login', () => {
  it('AC-002/BR-004: credenciales inválidas muestran el mensaje del code y no navegan', async () => {
    const usuario = userEvent.setup()
    renderApp()
    await usuario.type(screen.getByLabelText('Usuario'), 'nadie@wrsensor.local')
    await usuario.type(screen.getByLabelText('Contraseña'), 'mal')
    await usuario.click(screen.getByRole('button', { name: 'Entrar' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent(/usuario o contraseña incorrectos/i)
    expect(alerta).toHaveTextContent('code=INVALID_CREDENTIALS')
    expect(screen.queryByRole('heading', { name: 'Mapa de sensores' })).not.toBeInTheDocument()
  })

  it('AF-03: en el login, un 429 se informa con el tiempo de espera y no reintenta solo', async () => {
    let llamadas = 0
    conLogin(() => {
      llamadas += 1
      return HttpResponse.json(
        { code: 'RATE_LIMIT_EXCEEDED', message: 'cupo' },
        { status: 429, headers: { 'Retry-After': '7' } },
      )
    })
    const usuario = userEvent.setup()
    renderApp()
    await usuario.type(screen.getByLabelText('Usuario'), 'admin@wrsensor.local')
    await usuario.type(screen.getByLabelText('Contraseña'), 'Admin123!')
    await usuario.click(screen.getByRole('button', { name: 'Entrar' }))

    await screen.findByRole('alert')
    expect(screen.getByText(/Reintentá en 7 s/)).toBeInTheDocument()
    await new Promise((r) => setTimeout(r, 250))
    expect(llamadas).toBe(1)
    restaurarLogin()
  })
})