import { pedir } from './cliente'
import type { LoginResponse, Rol, SensorResumen } from './tipos'

/** `POST /api/auth/login` (FEAT-0006): devuelve `{token, rol, expiraEnSegundos}`. */
export async function login(email: string, password: string): Promise<LoginResponse> {
  return pedir<LoginResponse>('/api/auth/login', {
    metodo: 'POST',
    cuerpo: { email, password },
  })
}

/** Convierte el `rol` del token a un rol conocido; desconocido ⇒ sin permisos de escritura. */
export function rolConocido(rol: string): Rol {
  return rol === 'ADMIN' ? 'ADMIN' : 'VIEWER'
}

/**
 * `GET /api/sensores/resumen` (FEAT-0008): una sola request con todos los sensores del mapa.
 * El backend devuelve un array; `null`/valores raros se descartan acá (la metadata es no confiable).
 */
export async function resumenSensores(token: string, signal?: AbortSignal): Promise<SensorResumen[]> {
  const crudo = await pedir<unknown>('/api/sensores/resumen', {
    token,
    ...(signal ? { signal } : {}),
  })
  if (!Array.isArray(crudo)) {
    return []
  }
  return crudo.filter((fila): fila is SensorResumen =>
    typeof fila === 'object' && fila !== null && typeof (fila as SensorResumen).id === 'string',
  )
}