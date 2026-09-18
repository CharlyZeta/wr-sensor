import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { vi } from 'vitest'
import type { SensorResumen } from '../api/tipos'

/**
 * Doble controlable de `WebSocket` para los tests (FEAT-0014/0015/0016).
 *
 * El SPA abre WebSocket reales (alertas en el shell, lecturas en el detalle); en jsdom no hay
 * servidor, así que **todos** los tests que renderizan el shell instalan este doble. Registra las
 * URLs intentadas (para afirmar por ejemplo que el token va en el query) y permite inyectar
 * mensajes, cierres y errores de forma determinista.
 */
export class FakeWebSocket {
  static instancias: FakeWebSocket[] = []
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3

  readonly url: string
  readyState: number = FakeWebSocket.CONNECTING
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

  /** Sockets de un path concreto (p. ej. `/ws/alertas` o `/ws/sensores/`). */
  static de(trozo: string): FakeWebSocket[] {
    return FakeWebSocket.instancias.filter((s) => s.url.includes(trozo))
  }

  /** Última instancia de un path; falla con un mensaje claro si no hay ninguna. */
  static ultimaDe(trozo: string): FakeWebSocket {
    const propios = FakeWebSocket.de(trozo)
    const ultima = propios[propios.length - 1]
    if (ultima === undefined) {
      throw new Error(`no hay WebSocket abierto para ${trozo}`)
    }
    return ultima
  }
}

/** Instala el doble de WebSocket y limpia las instancias previas (llamar en `beforeEach`). */
export function instalarWebSocketFalso(): void {
  FakeWebSocket.instancias = []
  vi.stubGlobal('WebSocket', FakeWebSocket as unknown as typeof WebSocket)
}

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

/** Handlers del detalle (FEAT-0014): metadata del sensor y histórico keyset. */
let historicoHandler: (url: URL) => Response | Promise<Response> = (url) => {
  const limit = Number.parseInt(url.searchParams.get('limit') ?? '1000', 10)
  const items = lecturasDeEjemplo.slice(0, Math.min(limit, lecturasDeEjemplo.length))
  return HttpResponse.json({ items, nextCursor: null })
}

export function conHistorico(handler: (url: URL) => Response | Promise<Response>): void {
  historicoHandler = handler
}

export function restaurarHistorico(): void {
  historicoHandler = (url) => {
    const limit = Number.parseInt(url.searchParams.get('limit') ?? '1000', 10)
    const items = lecturasDeEjemplo.slice(0, Math.min(limit, lecturasDeEjemplo.length))
    return HttpResponse.json({ items, nextCursor: null })
  }
}

let detalleHandler: () => Response | Promise<Response> = () => HttpResponse.json(sensoresDeEjemplo[0])

export function conDetalle(handler: () => Response | Promise<Response>): void {
  detalleHandler = handler
}

export function restaurarDetalle(): void {
  detalleHandler = () => HttpResponse.json(sensoresDeEjemplo[0])
}

/** Lecturas de ejemplo: 3 válidas (una CRITICAL) y 1 con calidad ERROR_SENSOR. */
export const lecturasDeEjemplo = [
  {
    timestamp: '2026-09-15T10:00:00Z',
    valor: 10.5,
    unidadMedida: 'CELSIUS',
    severidad: 'NORMAL',
    calidad: 'OK',
  },
  {
    timestamp: '2026-09-15T11:00:00Z',
    valor: 42.25,
    unidadMedida: 'CELSIUS',
    severidad: 'WARNING',
    calidad: 'OK',
  },
  {
    timestamp: '2026-09-15T12:00:00Z',
    valor: 99.9,
    unidadMedida: 'CELSIUS',
    severidad: 'CRITICAL',
    calidad: 'OK',
  },
  {
    timestamp: '2026-09-15T12:30:00Z',
    valor: 12345,
    unidadMedida: 'CELSIUS',
    severidad: null,
    calidad: 'ERROR_SENSOR',
  },
]

/** Handlers del CRUD (FEAT-0016): listado keyset, detalle, alta, edición y baja lógica. */
export const sensorAdminDeEjemplo = {
  id: '00000000-0000-4000-8000-0000000000a1',
  codigo: 'S-ADM',
  nombre: 'Sensor administrable',
  tipo: 'RIO',
  latitud: -31.6,
  longitud: -60.7,
  unidadMedida: 'METROS',
  estado: 'ACTIVO',
  histeresis: 0.5,
  frecuenciaReporteSegundos: 30,
  fechaInstalacion: '2026-01-01T00:00:00Z',
  rangoNormal: { min: 0, max: 10 },
  rangoWarning: { min: 0, max: 20 },
  rangoCritical: { min: 0, max: 30 },
}

let listadoHandler: (url: URL) => Response | Promise<Response> = () =>
  HttpResponse.json({ items: [sensorAdminDeEjemplo], nextCursor: null })

export function conListadoAdmin(handler: (url: URL) => Response | Promise<Response>): void {
  listadoHandler = handler
}

export function restaurarListadoAdmin(): void {
  listadoHandler = () => HttpResponse.json({ items: [sensorAdminDeEjemplo], nextCursor: null })
}

let altaHandler: (cuerpo: unknown) => Response | Promise<Response> = () =>
  HttpResponse.json(sensorAdminDeEjemplo, { status: 201 })

export function conAlta(handler: (cuerpo: unknown) => Response | Promise<Response>): void {
  altaHandler = handler
}

export function restaurarAlta(): void {
  altaHandler = () => HttpResponse.json(sensorAdminDeEjemplo, { status: 201 })
}

let edicionHandler: () => Response | Promise<Response> = () => HttpResponse.json(sensorAdminDeEjemplo)

export function conEdicion(handler: () => Response | Promise<Response>): void {
  edicionHandler = handler
}

export function restaurarEdicion(): void {
  edicionHandler = () => HttpResponse.json(sensorAdminDeEjemplo)
}

let bajaHandler: () => Response | Promise<Response> = () => new HttpResponse(null, { status: 204 })

export function conBaja(handler: () => Response | Promise<Response>): void {
  bajaHandler = handler
}

export function restaurarBaja(): void {
  bajaHandler = () => new HttpResponse(null, { status: 204 })
}

export const servidor = setupServer(
  http.post('/api/auth/login', (info) => loginHandler(info)),
  http.get('/api/sensores/resumen', () => resumenHandler()),
  http.get('/api/sensores/:id/lecturas', ({ request }) => historicoHandler(new URL(request.url))),
  http.get('/api/sensores', ({ request }) => listadoHandler(new URL(request.url))),
  http.post('/api/sensores', async ({ request }) => altaHandler(await request.json())),
  http.put('/api/sensores/:id', () => edicionHandler()),
  http.delete('/api/sensores/:id', () => bajaHandler()),
  http.get('/api/sensores/:id', () => detalleHandler()),
)