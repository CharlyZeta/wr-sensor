import { CODIGOS } from './tipos'

/**
 * Error de la API con el `code` del backend (FEAT-0009 BR-004).
 *
 * El SPA muestra **siempre** el mensaje asociado al `code`, nunca el status ni un texto inventado;
 * para un `code` desconocido se muestra el código tal cual (no se adivina). El status, el
 * `X-Correlation-Id` y el mensaje crudo del backend quedan disponibles para el bloque de detalle
 * técnico (soporte), **como texto**.
 */
export class ErrorDeApi extends Error {
  readonly code: string
  readonly status: number
  readonly correlacion: string | null
  readonly mensajeBackend: string
  readonly retryAfterSegundos: number | null

  constructor(args: {
    code: string
    status: number
    mensajeBackend: string
    correlacion: string | null
    retryAfterSegundos?: number | null
  }) {
    super(args.mensajeBackend)
    this.name = 'ErrorDeApi'
    this.code = args.code
    this.status = args.status
    this.mensajeBackend = args.mensajeBackend
    this.correlacion = args.correlacion
    this.retryAfterSegundos = args.retryAfterSegundos ?? null
  }

  /** ¿Es un error de red (el gateway no respondió)? */
  get esDeRed(): boolean {
    return this.status === 0
  }

  get esNoAutenticado(): boolean {
    return this.status === 401 || this.code === CODIGOS.unauthenticated
  }

  get esSinPermiso(): boolean {
    return this.status === 403 || this.code === CODIGOS.insufficientRole
  }

  get esCupoAgotado(): boolean {
    return this.status === 429 || this.code === CODIGOS.rateLimit
  }
}

/** Errores de red/timeout que no llegan a tener respuesta del gateway. */
export function errorDeRed(mensaje: string): ErrorDeApi {
  return new ErrorDeApi({ code: 'NETWORK_ERROR', status: 0, mensajeBackend: mensaje, correlacion: null })
}

/**
 * Diccionario `code → mensaje es-AR`. Cubre los códigos del contrato (convención `{"code","message"}`
 * de todos los servicios) más los propios del gateway y de la red.
 */
const MENSAJES: Record<string, string> = {
  NETWORK_ERROR: 'No se pudo contactar al servidor. Verificá tu conexión y reintentá.',
  [CODIGOS.unauthenticated]: 'Tu sesión expiró. Volvé a iniciar sesión.',
  [CODIGOS.insufficientRole]: 'Tu usuario no tiene permisos para esta acción.',
  [CODIGOS.invalidCredentials]: 'Usuario o contraseña incorrectos.',
  [CODIGOS.rateLimit]: 'Se alcanzó el límite de pedidos del servidor. Esperá unos segundos.',
  [CODIGOS.originNotAllowed]: 'El origen de esta página no está autorizado a consumir la API.',
  [CODIGOS.routeNotFound]: 'La ruta pedida no existe en el gateway.',
  [CODIGOS.upstreamUnavailable]: 'Un servicio interno no está disponible. Reintentá en unos segundos.',
  [CODIGOS.upstreamTimeout]: 'Un servicio interno tardó demasiado en responder.',
  [CODIGOS.internalError]: 'Ocurrió un error interno en el servidor.',
  [CODIGOS.registryUnavailable]:
    'No se pudo leer el catálogo de sensores (registro no disponible). El mapa no se muestra a medias.',
  [CODIGOS.sensorNotFound]: 'El sensor no existe o no tiene lecturas.',
  [CODIGOS.invalidRange]: 'El rango de fechas es inválido.',
  [CODIGOS.sensorInvalidLimit]: 'El límite de resultados es inválido.',
  [CODIGOS.sensorInvalidCursor]: 'El cursor de paginación es inválido.',
  [CODIGOS.sensorInvalidId]: 'El identificador del sensor es inválido.',
  [CODIGOS.sensorCodeDuplicated]: 'Ya existe un sensor con ese código.',
  [CODIGOS.sensorInvalidRequest]: 'Los datos enviados no son válidos.',
}

/** Mensaje es-AR para un error; si el `code` es desconocido se muestra el código crudo (BR-004). */
export function mensajeDe(error: ErrorDeApi): string {
  return MENSAJES[error.code] ?? `Error del servidor (${error.code}).`
}

/** Texto del detalle técnico: status, código, correlación y mensaje crudo del backend. */
export function detalleTecnico(error: ErrorDeApi): string {
  const partes = [
    `status=${error.status === 0 ? 'sin respuesta' : String(error.status)}`,
    `code=${error.code}`,
  ]
  if (error.correlacion !== null) {
    partes.push(`correlacion=${error.correlacion}`)
  }
  if (error.mensajeBackend !== '' && error.mensajeBackend !== mensajeDe(error)) {
    partes.push(`servidor="${error.mensajeBackend}"`)
  }
  return partes.join(' · ')
}