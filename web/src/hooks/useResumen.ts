import { useCallback, useEffect, useRef, useState } from 'react'
import { resumenSensores } from '../api/auth'
import { ErrorDeApi } from '../api/errores'
import type { SensorResumen } from '../api/tipos'
import { config } from '../config'

/**
 * Resumen del mapa (FEAT-0009 Main Flow 3/5, BR-005, AF-01/AF-04/AF-05).
 *
 * Reglas:
 * - **Un único temporizador** por vista, con el período configurado (`VITE_RESUMEN_REFRESCO_MS`):
 *   nunca hay polling por debajo de eso, así el SPA no agota el cupo de la clase `lectura`.
 * - Con `429` se **respeta `Retry-After`** y se pausa el refresco automático (un solo reintento
 *   programado), sin ráfagas.
 * - En pestaña oculta (`visibilitychange`) el refresco se **pausa**: no se consume cupo que nadie ve.
 * - Un `401` se propaga al llamador para cerrar la sesión (AF-01) y no se reintenta.
 * - Al desmontar se aborta la request en vuelo (no quedan setState sobre un componente muerto).
 */

export type EstadoResumen = {
  sensores: SensorResumen[]
  cargando: boolean
  error: ErrorDeApi | null
  /** Momento de la última carga exitosa (para mostrar antigüedad). */
  actualizadoEn: Date | null
  /** Segundos restantes de pausa por cupo agotado. */
  pausadoSegundos: number | null
  recargar: () => void
}

export function useResumen(token: string, onNoAutenticado: () => void): EstadoResumen {
  const [sensores, setSensores] = useState<SensorResumen[]>([])
  const [cargando, setCargando] = useState(true)
  const [error, setError] = useState<ErrorDeApi | null>(null)
  const [actualizadoEn, setActualizadoEn] = useState<Date | null>(null)
  const [pausadoSegundos, setPausadoSegundos] = useState<number | null>(null)

  const abortRef = useRef<AbortController | null>(null)
  const pausaHastaRef = useRef<number>(0)

  const cargar = useCallback(async () => {
    if (Date.now() < pausaHastaRef.current) {
      return
    }
    abortRef.current?.abort()
    const controlador = new AbortController()
    abortRef.current = controlador
    setCargando(true)
    try {
      const datos = await resumenSensores(token, controlador.signal)
      setSensores(datos)
      setError(null)
      setActualizadoEn(new Date())
      setPausadoSegundos(null)
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        return
      }
      if (e instanceof ErrorDeApi) {
        if (e.esNoAutenticado) {
          onNoAutenticado()
          return
        }
        setError(e)
        if (e.esCupoAgotado && e.retryAfterSegundos !== null) {
          pausaHastaRef.current = Date.now() + e.retryAfterSegundos * 1000
          setPausadoSegundos(e.retryAfterSegundos)
        }
        return
      }
      setError(e as ErrorDeApi)
    } finally {
      setCargando(false)
    }
  }, [token, onNoAutenticado])

  // Carga inicial + refresco periódico + pausa en pestaña oculta.
  useEffect(() => {
    // La carga inicial se agenda como tarea (no sincrónica dentro del efecto): así el efecto sólo
    // suscribe/desuscribe, sin provocar renders en cascada (regla react-hooks/set-state-in-effect).
    const inicial = window.setTimeout(() => void cargar(), 0)
    const intervalo = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        void cargar()
      }
    }, config.resumenRefrescoMs)

    const alVolver = (): void => {
      if (document.visibilityState === 'visible') {
        void cargar()
      }
    }
    document.addEventListener('visibilitychange', alVolver)
    return () => {
      window.clearTimeout(inicial)
      window.clearInterval(intervalo)
      document.removeEventListener('visibilitychange', alVolver)
      abortRef.current?.abort()
    }
  }, [cargar])

  // Cuenta atrás visible mientras dura la pausa por cupo.
  useEffect(() => {
    if (pausadoSegundos === null) {
      return
    }
    if (pausadoSegundos <= 0) {
      const reanudar = window.setTimeout(() => {
        pausaHastaRef.current = 0
        setPausadoSegundos(null)
        void cargar()
      }, 0)
      return () => window.clearTimeout(reanudar)
    }
    const timer = window.setTimeout(() => setPausadoSegundos((s) => (s === null ? null : s - 1)), 1000)
    return () => window.clearTimeout(timer)
  }, [pausadoSegundos, cargar])

  return { sensores, cargando, error, actualizadoEn, pausadoSegundos, recargar: () => void cargar() }
}