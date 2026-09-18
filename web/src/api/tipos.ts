/**
 * Tipos del contrato del backend, escritos a mano desde el código Java verificado
 * (`ResumenController`, `LecturasController`, `SensorController`, `LoginResponse`).
 * Reflejan exactamente lo que el backend devuelve: los campos nulables están marcados como tales y
 * nada se asume (A13 de la revisión de seguridad: la metadata del backend es **no confiable** hasta
 * que se valida).
 */

/** Rol del usuario. Viaja en el claim `rol` del JWT. */
export type Rol = 'ADMIN' | 'VIEWER'

/** Respuesta de `POST /api/auth/login`. */
export type LoginResponse = {
  token: string
  rol: string
  expiraEnSegundos: number
}

/** Severidad de una lectura (catálogo cerrado del backend). */
export type Severidad = 'NORMAL' | 'WARNING' | 'CRITICAL'

/** Calidad del dato: `ERROR_SENSOR` marca una lectura físicamente imposible (FIX-0004). */
export type Calidad = string

/**
 * Última lectura de un sensor en el resumen. `severidad` puede venir `null` cuando la lectura es
 * `ERROR_SENSOR` (nullable en la base desde FIX-0004).
 */
export type UltimaLectura = {
  valor: number | null
  timestamp: string
  severidad: string | null
  calidad: string | null
}

/** Fila de `GET /api/sensores/resumen` (frontend: `GET /api/sensores/resumen`). */
export type SensorResumen = {
  id: string
  codigo: string | null
  nombre: string | null
  tipo: string | null
  latitud: number | null
  longitud: number | null
  estado: string | null
  unidadMedida: string | null
  ultimaLectura: UltimaLectura | null
}

/** Lectura del histórico (`GET /api/sensores/{id}/lecturas`). */
export type Lectura = {
  timestamp: string
  valor: number | null
  unidadMedida: string | null
  severidad: string | null
  calidad: string | null
}

/** Página keyset del histórico. */
export type PaginaLecturas = {
  items: Lectura[] | null
  nextCursor: string | null
}

/** Mensaje de error del backend: `{"code","message"}` (convención del proyecto). */
export type ErrorApi = {
  code: string
  message: string
}

/**
 * Códigos de error que el SPA trata de forma específica. Los que no están acá se muestran igual,
 * con el `code` crudo (BR-004: nunca se inventa un mensaje para un código desconocido).
 */
export const CODIGOS = {
  unauthenticated: 'UNAUTHENTICATED',
  insufficientRole: 'INSUFFICIENT_ROLE',
  rateLimit: 'RATE_LIMIT_EXCEEDED',
  originNotAllowed: 'ORIGIN_NOT_ALLOWED',
  routeNotFound: 'ROUTE_NOT_FOUND',
  upstreamUnavailable: 'UPSTREAM_UNAVAILABLE',
  upstreamTimeout: 'UPSTREAM_TIMEOUT',
  internalError: 'INTERNAL_ERROR',
  registryUnavailable: 'REGISTRY_UNAVAILABLE',
  sensorNotFound: 'SENSOR_NOT_FOUND',
  invalidRange: 'INVALID_RANGE',
  invalidCredentials: 'INVALID_CREDENTIALS',
  sensorInvalidLimit: 'SENSOR_INVALID_LIMIT',
  sensorInvalidCursor: 'SENSOR_INVALID_CURSOR',
  sensorInvalidId: 'SENSOR_INVALID_ID',
  sensorCodeDuplicated: 'SENSOR_CODE_DUPLICATED',
  sensorInvalidRequest: 'SENSOR_INVALID_REQUEST',
} as const