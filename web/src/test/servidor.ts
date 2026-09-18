import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import type { SensorResumen } from '../api/tipos'

/**
 * Handlers MSW del SPA. Reproducen exactamente el contrato del backend (códigos `{"code","message"}`,
 * `Retry-After` en 429, `X-Correlation-Id` en las respuestas) para poder testear el cliente real.
 */

export const TOKEN_VALIDO = 'token-de-prueba'

export function sensor(parcial: Partial<SensorResumen> & { id: string }): SensorResumen {
  return {
    codigo: 'S-01',
    nombre: 'Sensor Norte',
    tipo: 'TEMPERATURA',
    latitud: -31.6,
    longitud: -60.7,
    estado: 'ACTIVO',
    unidadMedida: 'CELSIUS',
    ultimaLectura: {
      valor: 21.5,
      timestamp: '2026-09-15T12:00:00Z',
      severidad: 'NORMAL',
      calidad: 'OK',
    },
    ...parcial,
  }
}

export const sensoresDeEjemplo: SensorResumen[] = [
  sensor({ id: '00000000-0000-4000-8000-00000000000a', codigo: 'S-01', nombre: 'Norte' }),
  sensor({
    id: '00000000-0000-4000-8000-00000000000b',
    codigo: 'S-02',
    nombre: 'Centro',
    latitud: -31.7,
    longitud: -60.8,
    ultimaLectura: {
      valor: 88.4,
      timestamp: '2026-09-15T12:05:00Z',
      severidad: 'CRITICAL',
      calidad: 'OK',
    },
  }),
  sensor({
    id: '00000000-0000-4000-8000-00000000000c',
    codigo: 'S-03',
    nombre: 'Sur',
    latitud: -31.8,
    longitud: -60.9,
    ultimaLectura: {
      valor: 5,
      timestamp: '2026-09-15T11:00:00Z',
      severidad: 'WARNING',
      calidad: 'OK',
    },
  }),
  sensor({
    id: '00000000-0000-4000-8000-00000000000d',
    codigo: 'S-04',
    nombre: 'Sin lecturas',
    latitud: -31.9,
    longitud: -61.0,
    ultimaLectura: null,
  }),
]

type HandlerLogin = (info: { request: Request }) => Response | Promise<Response>

let loginHandler: HandlerLogin = async ({ request }) => {
  const cuerpo = (await request.json()) as { email?: string; password?: string }
  if (cuerpo.email === 'admin@wrsensor.local' && cuerpo.password === 'Admin123!') {
    return HttpResponse.json({ token: TOKEN_VALIDO, rol: 'ADMIN', expiraEnSegundos: 3600 })
  }
  if (cuerpo.email === 'viewer@wrsensor.local' && cuerpo.password === 'Viewer123!') {
    return HttpResponse.json({ token: TOKEN_VALIDO, rol: 'VIEWER', expiraEnSegundos: 3600 })
  }
  return HttpResponse.json(
    { code: 'INVALID_CREDENTIALS', message: 'credenciales invalidas' },
    { status: 401 },
  )
}

/** Permite que un test cambie el comportamiento del login (p. ej. forzar 429 o 500). */
export function conLogin(handler: HandlerLogin): void {
  loginHandler = handler
}

export function restaurarLogin(): void {
  loginHandler = async ({ request }) => {
    const cuerpo = (await request.json()) as { email?: string; password?: string }
    if (cuerpo.email === 'admin@wrsensor.local' && cuerpo.password === 'Admin123!') {
      return HttpResponse.json({ token: TOKEN_VALIDO, rol: 'ADMIN', expiraEnSegundos: 3600 })
    }
    return HttpResponse.json(
      { code: 'INVALID_CREDENTIALS', message: 'credenciales invalidas' },
      { status: 401 },
    )
  }
}

let resumenHandler: () => Response | Promise<Response> = () =>
  HttpResponse.json(sensoresDeEjemplo, { headers: { 'X-Correlation-Id': 'corr-resumen' } })

export function conResumen(handler: () => Response | Promise<Response>): void {
  resumenHandler = handler
}

export function restaurarResumen(): void {
  resumenHandler = () =>
    HttpResponse.json(sensoresDeEjemplo, { headers: { 'X-Correlation-Id': 'corr-resumen' } })
}

export const servidor = setupServer(
  http.post('/api/auth/login', (info) => loginHandler(info)),
  http.get('/api/sensores/resumen', () => resumenHandler()),
)