import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { config, urlWebSocket } from '../config'
import { normalizarSeveridad, peso } from '../dominio/severidad'
import type { EstadoVisual } from '../dominio/severidad'
import { useSesion } from '../sesion/SesionContext'

/**
 * Alertas en vivo (FEAT-0015 BR-001/BR-002/BR-003/BR-005/BR-006, AF-01/AF-02/AF-07).
 *
 * - **Una sola conexión por pestaña**: el proveedor vive por encima de las rutas del shell, así que
 *   navegar entre el mapa y el detalle no abre conexiones nuevas ni pierde el feed (BR-001).
 * - **Backoff exponencial con jitter** y estado visible; un cierre por logout no reconecta (BR-002).
 * - **Feed en memoria** con deduplicación por (`sensorId`, `timestamp`, `severidadNueva`) y tope
 *   configurable; la UI declara que se pierde al recargar (BR-003/BR-005).
 * - **Contador de críticas no leídas** que sólo suma empeoramientos de severidad (BR-006).
 * - El payload se **valida** antes de entrar al feed (A13) y el token no se loguea (A5).
 */

export type Alerta = {
  sensorId: string
  severidadNueva: string
  severidadAnterior: string | null
  timestamp: string
  /** Estado visual derivado del catálogo (nunca se interpola el valor crudo en una clase CSS). */
  estado: EstadoVisual
  /** `true` si la severidad empeoró respecto de la anterior (cuenta como no leída). */
  empeora: boolean
}

export type EstadoAlertas = 'conectando' | 'conectado' | 'reconectando' | 'pausado'

export type ContextoAlertas = {
  alertas: Alerta[]
  estado: EstadoAlertas
  intento: number
  noLeidas: number
  marcarLeidas: () => void
  /** Última alerta recibida (el mapa reacciona a ella). */
  ultima: Alerta | null
  /**
   * Cambia **una vez por ráfaga** de alertas (BR-004): las vistas que muestran datos agregados
   * (el mapa) se refrescan al cambiar este contador, no una vez por alerta.
   */
  rafagas: number
}

const PESO: Record<string, number> = { NORMAL: 0, WARNING: 1, CRITICAL: 2 }

/** Valida y normaliza el mensaje del WS de alertas (A13); devuelve `null` si no es utilizable. */
export function parseAlerta(crudo: unknown): Alerta | null {
  if (typeof crudo !== 'object' || crudo === null) {
    return null
  }
  const m = crudo as Record<string, unknown>
  if (typeof m.sensorId !== 'string' || typeof m.timestamp !== 'string') {
    return null
  }
  const severidad = typeof m.severidadNueva === 'string' ? m.severidadNueva : null
  if (severidad === null || PESO[severidad] === undefined) {
    return null
  }
  const anterior = typeof m.severidadAnterior === 'string' ? m.severidadAnterior : null
  const estado = normalizarSeveridad(severidad)
  const pesoAnterior = anterior !== null ? PESO[anterior] : undefined
  // Sin severidad anterior conocida, una alerta WARNING/CRITICAL se considera empeoramiento
  // (es la primera transición que ve el SPA); una normalización nunca cuenta (AF-07/BR-006).
  const empeora = pesoAnterior === undefined
    ? estado === 'WARNING' || estado === 'CRITICAL'
    : (PESO[severidad] ?? 0) > pesoAnterior
  return {
    sensorId: m.sensorId,
    severidadNueva: severidad,
    severidadAnterior: anterior,
    timestamp: m.timestamp,
    estado,
    empeora,
  }
}

/** Clave de deduplicación: la misma alerta repetida por el broker no debe duplicarse (BR-003). */
export function claveAlerta(a: Alerta): string {
  return `${a.sensorId}|${a.timestamp}|${a.severidadNueva}`
}

/** Inserta una alerta en el feed: deduplica, ordena por timestamp desc y aplica el tope. */
export function insertarAlerta(previas: Alerta[], alerta: Alerta, tope: number): Alerta[] {
  if (previas.some((a) => claveAlerta(a) === claveAlerta(alerta))) {
    return previas
  }
  return [alerta, ...previas]
    .sort((a, b) => Date.parse(b.timestamp) - Date.parse(a.timestamp))
    .slice(0, tope)
}

const ContextoAlertas = createContext<ContextoAlertas | null>(null)

export function ProveedorAlertas({
  children,
  alRecibirRafaga,
}: {
  children: ReactNode
  /** Se invoca **una vez por ráfaga** de alertas (el mapa refresca el resumen con debounce). */
  alRecibirRafaga?: () => void
}): React.JSX.Element {
  const { sesion, cerrarSesion } = useSesion()
  const token = sesion?.token ?? null

  const [alertas, setAlertas] = useState<Alerta[]>([])
  const [estado, setEstado] = useState<EstadoAlertas>('conectando')
  const [intento, setIntento] = useState(0)
  const [noLeidas, setNoLeidas] = useState(0)
  const [ultima, setUltima] = useState<Alerta | null>(null)
  const [rafagas, setRafagas] = useState(0)

  const cerrado = useRef(false)
  const socketRef = useRef<WebSocket | null>(null)
  const timerRef = useRef<number | null>(null)
  const rafagaRef = useRef<number | null>(null)
  const rafagaCb = useRef(alRecibirRafaga)
  // El ref se sincroniza en un efecto (no durante el render): la regla
  // `react-hooks/refs` prohíbe leer/escribir refs en el cuerpo del render.
  useEffect(() => {
    rafagaCb.current = alRecibirRafaga
  }, [alRecibirRafaga])

  const acumular = useCallback((alerta: Alerta) => {
    setAlertas((previas) => insertarAlerta(previas, alerta, config.alertasMax))
    setUltima(alerta)
    if (alerta.empeora) {
      setNoLeidas((n) => n + 1)
    }
  }, [])

  useEffect(() => {
    if (token === null || token === '') {
      return
    }
    cerrado.current = false
    let intentos = 0

    const programarReintento = (): void => {
      if (cerrado.current) {
        return
      }
      intentos += 1
      setIntento(intentos)
      if (intentos > config.wsMaxIntentos) {
        setEstado('pausado')
        return
      }
      setEstado('reconectando')
      const espera = Math.min(
        config.wsBackoffBaseMs * Math.pow(config.wsBackoffFactor, intentos - 1),
        config.wsBackoffTopeMs,
      )
      const jitter = espera * (0.5 + Math.random() * 0.5)
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current)
      }
      timerRef.current = window.setTimeout(conectar, jitter)
    }

    function agendarRafaga(): void {
      if (rafagaRef.current !== null) {
        return
      }
      rafagaRef.current = window.setTimeout(() => {
        rafagaRef.current = null
        setRafagas((n) => n + 1)
        rafagaCb.current?.()
      }, config.alertasDebounceMs)
    }

    function conectar(): void {
      if (cerrado.current) {
        return
      }
      setEstado(intentos === 0 ? 'conectando' : 'reconectando')
      let socket: WebSocket
      try {
        socket = new WebSocket(urlWebSocket('/ws/alertas', token as string))
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
          return
        }
        const alerta = parseAlerta(crudo)
        if (alerta === null) {
          return
        }
        acumular(alerta)
        agendarRafaga()
      }
      socket.onclose = (evento) => {
        socketRef.current = null
        if (cerrado.current) {
          return
        }
        if (evento.code === 1008 || evento.code === 4401 || evento.code === 4403) {
          setEstado('pausado')
          cerrarSesion('expirada')
          return
        }
        programarReintento()
      }
      socket.onerror = () => {
        // Sin detalle: el mensaje del navegador incluiría la URL con el token (A5).
        socket.close()
      }
    }

    conectar()

    return () => {
      cerrado.current = true
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current)
      }
      if (rafagaRef.current !== null) {
        window.clearTimeout(rafagaRef.current)
        rafagaRef.current = null
      }
      const socket = socketRef.current
      socketRef.current = null
      if (socket !== null && (socket.readyState === WebSocket.OPEN
          || socket.readyState === WebSocket.CONNECTING)) {
        socket.close(1000, 'logout')
      }
    }
  }, [token, cerrarSesion, acumular])

  const marcarLeidas = useCallback(() => setNoLeidas(0), [])

  const valor = useMemo<ContextoAlertas>(
    () => ({ alertas, estado, intento, noLeidas, marcarLeidas, ultima, rafagas }),
    [alertas, estado, intento, noLeidas, marcarLeidas, ultima, rafagas],
  )

  return <ContextoAlertas.Provider value={valor}>{children}</ContextoAlertas.Provider>
}

export function useAlertas(): ContextoAlertas {
  const ctx = useContext(ContextoAlertas)
  if (ctx === null) {
    throw new Error('useAlertas() requiere <ProveedorAlertas>')
  }
  return ctx
}

/** Etiqueta legible del estado de la conexión de alertas. */
export function describirEstadoAlertas(estado: EstadoAlertas, intento: number): string {
  switch (estado) {
    case 'conectado':
      return 'Alertas en vivo'
    case 'conectando':
      return 'Conectando alertas…'
    case 'reconectando':
      return `Reconectando alertas (intento ${intento})`
    case 'pausado':
      return 'Alertas en vivo pausadas'
  }
}

/** Peso de severidad expuesto para la UI (orden del feed). */
export function pesoSeveridad(severidad: string | null): number {
  return severidad !== null ? (PESO[severidad] ?? peso(normalizarSeveridad(severidad))) : peso('SIN_DATOS')
}