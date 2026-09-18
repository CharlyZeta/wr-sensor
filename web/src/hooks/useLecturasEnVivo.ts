import { useCallback, useEffect, useRef, useState } from 'react'
import { urlWebSocket } from '../config'
import type { Lectura } from '../api/tipos'
import { normalizarSeveridad } from '../dominio/severidad'

/**
 * Suscripción a las lecturas en vivo de un sensor (FEAT-0014 BR-001/BR-002/BR-007, AF-01/AF-02/AF-08).
 *
 * Reglas que implementa:
 * - **Una sola conexión** por vista: se abre al montar y se cierra al desmontar o al cambiar de
 *   sensor; un cierre por desmontaje/logout **no** reconecta.
 * - **Backoff exponencial con jitter** y tope de intentos configurables, con estado visible.
 * - **El token viaja en la query string** (el navegador no puede mandar `Authorization` en el
 *   upgrade) y **nunca** se loguea ni se muestra: los errores que se exponen son genéricos (A5).
 * - Los mensajes se **validan** (forma y tipos) antes de usarse y se **deduplican** por
 *   (`sensorId`, `timestamp`) en el punto de inserción que hace el llamador.
 */

export type EstadoConexion = 'conectando' | 'conectado' | 'reconectando' | 'pausado'

export type LecturaEnVivo = {
  estado: EstadoConexion
  intento: number
  /** Última lectura válida recibida por WS. */
  ultima: Lectura | null
  /** Momento en que se recibió la última lectura (para mostrar antigüedad). */
  ultimaEn: Date | null
}

/** Mensaje del WS de `query-api`: `{sensorId, timestamp, valor, unidadMedida, calidad?}`. */
function parseLectura(crudo: unknown): Lectura | null {
  if (typeof crudo !== 'object' || crudo === null) {
    return null
  }
  const m = crudo as Record<string, unknown>
  if (typeof m.timestamp !== 'string') {
    return null
  }
  const valor = typeof m.valor === 'number' ? m.valor : Number(m.valor)
  return {
    timestamp: m.timestamp,
    valor: Number.isFinite(valor) ? valor : null,
    unidadMedida: typeof m.unidadMedida === 'string' ? m.unidadMedida : null,
    // el payload del WS no trae severidad (BR-004): se completa con el último dato conocido
    severidad: typeof m.severidad === 'string' ? m.severidad : null,
    calidad: typeof m.calidad === 'string' ? m.calidad : 'OK',
  }
}

export function useLecturasEnVivo(
  sensorId: string,
  token: string,
  opciones: { baseMs: number; factor: number; topeMs: number; maxIntentos: number },
  alFallarAutenticacion: () => void,
  /** Se invoca por cada lectura válida (evento externo: el llamador decide cómo acumularla). */
  alRecibir?: (lectura: Lectura) => void,
): LecturaEnVivo {
  const [estado, setEstado] = useState<EstadoConexion>('conectando')
  const [intento, setIntento] = useState(0)
  const [ultima, setUltima] = useState<Lectura | null>(null)
  const [ultimaEn, setUltimaEn] = useState<Date | null>(null)

  const cerradoPorDesmontaje = useRef(false)
  const socketRef = useRef<WebSocket | null>(null)
  const timerRef = useRef<number | null>(null)

  const limpiarTimer = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }, [])

  useEffect(() => {
    cerradoPorDesmontaje.current = false
    let intentos = 0

    const conectar = (): void => {
      if (cerradoPorDesmontaje.current) {
        return
      }
      setEstado(intentos === 0 ? 'conectando' : 'reconectando')
      let socket: WebSocket
      try {
        socket = new WebSocket(urlWebSocket(`/ws/sensores/${encodeURIComponent(sensorId)}`, token))
      } catch {
        programarReintento()
        return
      }
      socketRef.current = socket

      socket.onopen = () => {
        intentos = 0
        setIntento(0)
        setEstado('conectado')
      }

      socket.onmessage = (evento) => {
        if (typeof evento.data !== 'string') {
          return
        }
        let crudo: unknown
        try {
          crudo = JSON.parse(evento.data)
        } catch {
          return // payload no-JSON: se descarta sin romper la vista (A13)
        }
        const lectura = parseLectura(crudo)
        if (lectura === null) {
          return
        }
        setUltima(lectura)
        setUltimaEn(new Date())
        // El llamador acumula la lectura desde acá (evento del sistema externo), no desde un efecto:
        // así se evita el setState sincrónico dentro de un efecto.
        alRecibir?.(lectura)
      }

      socket.onclose = (evento) => {
        socketRef.current = null
        if (cerradoPorDesmontaje.current) {
          return
        }
        // El gateway responde 401 antes del upgrade: no tiene sentido reintentar con el mismo token.
        if (evento.code === 1008 || evento.code === 4401 || evento.code === 4403) {
          setEstado('pausado')
          alFallarAutenticacion()
          return
        }
        programarReintento()
      }

      socket.onerror = () => {
        // Sin detalle del error a propósito: el mensaje del navegador incluiría la URL con el token.
        socket.close()
      }
    }

    const programarReintento = (): void => {
      if (cerradoPorDesmontaje.current) {
        return
      }
      intentos += 1
      setIntento(intentos)
      if (intentos > opciones.maxIntentos) {
        setEstado('pausado')
        return
      }
      setEstado('reconectando')
      const espera = Math.min(opciones.baseMs * Math.pow(opciones.factor, intentos - 1), opciones.topeMs)
      const jitter = espera * (0.5 + Math.random() * 0.5)
      limpiarTimer()
      timerRef.current = window.setTimeout(conectar, jitter)
    }

    conectar()

    return () => {
      cerradoPorDesmontaje.current = true
      limpiarTimer()
      const socket = socketRef.current
      socketRef.current = null
      if (socket !== null && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
        socket.close(1000, 'desmontaje')
      }
    }
  }, [sensorId, token, opciones.baseMs, opciones.factor, opciones.topeMs, opciones.maxIntentos,
      alFallarAutenticacion, alRecibir, limpiarTimer])

  return { estado, intento, ultima, ultimaEn }
}

/** Estados con etiqueta legible y si están "en vivo" (para la UI). */
export function describirConexion(estado: EstadoConexion, intento: number): string {
  switch (estado) {
    case 'conectado':
      return 'En vivo'
    case 'conectando':
      return 'Conectando…'
    case 'reconectando':
      return `Reconectando (intento ${intento})`
    case 'pausado':
      return 'Datos en vivo pausados'
  }
}

export { normalizarSeveridad }