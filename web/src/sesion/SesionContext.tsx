import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { login as loginApi, rolConocido } from '../api/auth'
import type { Rol } from '../api/tipos'

/**
 * Sesión del SPA (FEAT-0009 BR-002, AF-01, AF-07).
 *
 * - El token vive **sólo** en `sessionStorage` (sobrevive al F5, muere al cerrar la pestaña). Nunca
 *   `localStorage` (persistiría demasiado), nunca cookies (el backend no las emite) y nunca en la URL
 *   (A9).
 * - Un temporizador cierra la sesión al vencer `expiraEnSegundos` (con margen), así que la UI nunca
 *   queda mostrando datos de una sesión muerta.
 * - `cerrarSesion()` es **única y atómica**: la usan el logout manual, el `401` y la expiración
 *   (A7/A8). Los suscriptores (WS, timers) se desmontan porque el árbol deja de renderizarse.
 */

const CLAVE = 'wrsensor.sesion'

export type Sesion = {
  token: string
  rol: Rol
  /** Epoch ms en que vence la sesión. */
  expiraEn: number
  email: string
}

type EstadoSesion = {
  sesion: Sesion | null
  entrar: (email: string, password: string) => Promise<void>
  cerrarSesion: (motivo?: string) => void
  autenticado: boolean
  esAdmin: boolean
}

const ContextoSesion = createContext<EstadoSesion | null>(null)

type Guardada = { token: string; rol: string; expiraEn: number; email: string }

/** Lee la sesión guardada descartando cualquier cosa vencida o malformada. */
function leerGuardada(): Sesion | null {
  try {
    const crudo = window.sessionStorage.getItem(CLAVE)
    if (crudo === null) {
      return null
    }
    const datos = JSON.parse(crudo) as Partial<Guardada>
    if (
      typeof datos.token !== 'string' ||
      datos.token === '' ||
      typeof datos.expiraEn !== 'number' ||
      Number.isNaN(datos.expiraEn) ||
      datos.expiraEn <= Date.now()
    ) {
      window.sessionStorage.removeItem(CLAVE)
      return null
    }
    return {
      token: datos.token,
      rol: rolConocido(typeof datos.rol === 'string' ? datos.rol : 'VIEWER'),
      expiraEn: datos.expiraEn,
      email: typeof datos.email === 'string' ? datos.email : '',
    }
  } catch {
    window.sessionStorage.removeItem(CLAVE)
    return null
  }
}

const MARGEN_MS = 5_000

export function ProveedorSesion({
  children,
  onSesionCerrada,
}: {
  children: ReactNode
  /** Notifica el motivo del cierre (para mostrar el aviso correspondiente). */
  onSesionCerrada?: (motivo: string) => void
}): React.JSX.Element {
  const [sesion, setSesion] = useState<Sesion | null>(() => leerGuardada())
  const motivoRef = useRef<string>('')

  const cerrarSesion = useCallback(
    (motivo = 'sesion-cerrada') => {
      window.sessionStorage.removeItem(CLAVE)
      setSesion(null)
      motivoRef.current = motivo
      onSesionCerrada?.(motivo)
    },
    [onSesionCerrada],
  )

  // Expiración programada: cierra la sesión antes de que el backend la rechace.
  useEffect(() => {
    if (sesion === null) {
      return
    }
    const restante = Math.max(0, sesion.expiraEn - Date.now() - MARGEN_MS)
    // Se agenda siempre (aunque ya haya vencido) para no actualizar estado de forma sincrónica
    // dentro del efecto; el cierre efectivo ocurre en el callback del timer.
    const timer = window.setTimeout(() => cerrarSesion('expirada'), restante)
    return () => window.clearTimeout(timer)
  }, [sesion, cerrarSesion])

  const entrar = useCallback(async (email: string, password: string) => {
    const respuesta = await loginApi(email, password)
    const nueva: Sesion = {
      token: respuesta.token,
      rol: rolConocido(respuesta.rol),
      expiraEn: Date.now() + respuesta.expiraEnSegundos * 1000,
      email,
    }
    window.sessionStorage.setItem(
      CLAVE,
      JSON.stringify({ token: nueva.token, rol: nueva.rol, expiraEn: nueva.expiraEn, email }),
    )
    setSesion(nueva)
  }, [])

  const valor = useMemo<EstadoSesion>(
    () => ({
      sesion,
      entrar,
      cerrarSesion,
      autenticado: sesion !== null,
      esAdmin: sesion?.rol === 'ADMIN',
    }),
    [sesion, entrar, cerrarSesion],
  )

  return <ContextoSesion.Provider value={valor}>{children}</ContextoSesion.Provider>
}

export function useSesion(): EstadoSesion {
  const ctx = useContext(ContextoSesion)
  if (ctx === null) {
    throw new Error('useSesion() requiere <ProveedorSesion>')
  }
  return ctx
}