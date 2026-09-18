import { config, urlApi } from '../config'
import { ErrorDeApi, errorDeRed } from './errores'
import { CODIGOS, type ErrorApi } from './tipos'

/**
 * Cliente HTTP del SPA (FEAT-0009 BR-003/BR-004/BR-005).
 *
 * - **Mismo origen**: las URLs salen de `urlApi()` (relativas por default, base configurable).
 * - **Sin credenciales ambientales**: `credentials: 'omit'` (el token va en `Authorization`, nunca
 *   en cookies; ver A9 de la revisión de seguridad).
 * - **`X-Correlation-Id`** generado por el cliente para poder cruzar un incidente con los logs del
 *   gateway; el servidor lo valida y lo devuelve.
 * - **Errores por `code`**: se traduce `{code,message}` a `ErrorDeApi`, respetando `Retry-After` en
 *   `429`. El token **nunca** se loguea ni se incluye en mensajes.
 */

export type OpcionesPeticion = {
  /** Token de sesión (se manda como `Authorization: Bearer`). */
  token?: string | null
  /** Cuerpo JSON a enviar. */
  cuerpo?: unknown
  /** Signal externo para cancelar (desmontaje de la vista). */
  signal?: AbortSignal
  /** Método HTTP. */
  metodo?: 'GET' | 'POST' | 'PUT' | 'DELETE'
}

/** Genera un id de correlación válido para el gateway (1-64 caracteres ASCII imprimibles). */
function correlacion(): string {
  const aleatorio =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `c-${Date.now()}-${Math.floor(Math.random() * 1e9)}`
  return aleatorio.slice(0, 64)
}

function segundosDeRetryAfter(respuesta: Response): number | null {
  const valor = respuesta.headers.get('Retry-After')
  if (valor === null) {
    return null
  }
  const n = Number.parseInt(valor, 10)
  return Number.isFinite(n) && n >= 0 ? n : null
}

/** Cuerpo de error del backend, si es parseable; si no, se arma uno con el status. */
async function errorDeRespuesta(respuesta: Response): Promise<ErrorDeApi> {
  const correlacionResp = respuesta.headers.get('X-Correlation-Id')
  let code = `HTTP_${respuesta.status}`
  let mensaje = `El servidor respondió ${respuesta.status}.`
  try {
    const texto = await respuesta.text()
    if (texto !== '') {
      const cuerpo = JSON.parse(texto) as Partial<ErrorApi>
      if (typeof cuerpo.code === 'string' && cuerpo.code !== '') {
        code = cuerpo.code
      }
      if (typeof cuerpo.message === 'string') {
        mensaje = cuerpo.message
      }
    }
  } catch {
    // cuerpo no-JSON (por ejemplo un 502 de un proxy): se conserva el mensaje por status
  }
  return new ErrorDeApi({
    code,
    status: respuesta.status,
    mensajeBackend: mensaje,
    correlacion: correlacionResp,
    retryAfterSegundos: segundosDeRetryAfter(respuesta),
  })
}

/** Petición JSON tipada. Lanza `ErrorDeApi` ante cualquier respuesta no exitosa. */
export async function pedir<T>(path: string, opciones: OpcionesPeticion = {}): Promise<T> {
  const metodo = opciones.metodo ?? 'GET'
  const cabeceras: Record<string, string> = {
    Accept: 'application/json',
    'X-Correlation-Id': correlacion(),
  }
  if (opciones.token !== undefined && opciones.token !== null && opciones.token !== '') {
    cabeceras.Authorization = `Bearer ${opciones.token}`
  }
  if (opciones.cuerpo !== undefined) {
    cabeceras['Content-Type'] = 'application/json'
  }

  let respuesta: Response
  try {
    const init: RequestInit = {
      method: metodo,
      headers: cabeceras,
      credentials: 'omit',
    }
    if (opciones.cuerpo !== undefined) {
      init.body = JSON.stringify(opciones.cuerpo)
    }
    if (opciones.signal !== undefined) {
      init.signal = opciones.signal
    }
    respuesta = await fetch(urlApi(path), init)
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw e
    }
    throw errorDeRed('No se pudo contactar al servidor.')
  }

  if (!respuesta.ok) {
    throw await errorDeRespuesta(respuesta)
  }

  if (respuesta.status === 204) {
    return undefined as T
  }

  const texto = await respuesta.text()
  if (texto === '') {
    return undefined as T
  }
  try {
    return JSON.parse(texto) as T
  } catch {
    // Un 200 con cuerpo inválido es un error de contrato, no un dato: se trata como red
    throw new ErrorDeApi({
      code: CODIGOS.internalError,
      status: respuesta.status,
      mensajeBackend: 'La respuesta del servidor no es JSON válido.',
      correlacion: respuesta.headers.get('X-Correlation-Id'),
    })
  }
}

/** Base de la API en uso (para el detalle técnico de la UI). */
export function baseApi(): string {
  return config.apiBase === '' ? 'mismo origen' : config.apiBase
}