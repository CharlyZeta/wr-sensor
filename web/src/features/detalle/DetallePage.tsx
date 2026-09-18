import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ErrorDeApi } from '../../api/errores'
import { detalleSensor, lecturasDeVentana, ventanaDeHoras } from '../../api/lecturas'
import type { Lectura, SensorResumen } from '../../api/tipos'
import { Aviso, EstadoCargando, EstadoError, EstadoVacio } from '../../componentes/Estados'
import { config } from '../../config'
import { antiguedad, fechaHora, numero, recortar } from '../../dominio/formato'
import { esCalidadInvalida, esUuid, estilo, estadoOperativo, estadoVisual, etiquetaSensor } from '../../dominio/severidad'
import { describirConexion, useLecturasEnVivo } from '../../hooks/useLecturasEnVivo'
import { useSesion } from '../../sesion/SesionContext'
import { SerieTemporal } from './SerieTemporal'

/**
 * Detalle del sensor (FEAT-0014 Main Flow 1-5).
 *
 * - Metadata del registry + **última lectura en vivo** por `WS /ws/sensores/{id}?token=` (una sola
 *   conexión, backoff con jitter, estado visible) y **serie de las últimas N horas** por el histórico
 *   keyset (paginado por cursor hasta cubrir la ventana o el tope).
 * - Las lecturas del WS se **deduplican** por `timestamp` y se insertan **ordenadas** (BR-007), sin
 *   volver a pedir el histórico (BR-003).
 * - La **tabla es la fuente de verdad accesible** (BR-005): hora, valor, unidad, severidad y calidad.
 * - La antigüedad del último dato se muestra y se marca como vencida si supera el umbral (BR-006).
 */
export function DetallePage(): React.JSX.Element {
  const { id } = useParams<{ id: string }>()
  const { sesion, cerrarSesion } = useSesion()
  const token = sesion?.token ?? ''
  const sensorId = id ?? ''

  const [sensor, setSensor] = useState<SensorResumen | null>(null)
  const [lecturas, setLecturas] = useState<Lectura[]>([])
  const [truncado, setTruncado] = useState(false)
  const [cargando, setCargando] = useState(true)
  const [error, setError] = useState<ErrorDeApi | null>(null)
  const [segundosPausa, setSegundosPausa] = useState<number | null>(null)
  const [ahora, setAhora] = useState(() => new Date())
  const abortRef = useRef<AbortController | null>(null)
  const pausaHastaRef = useRef(0)

  const idValido = esUuid(sensorId)

  // La lectura del WS se acumula **desde el evento del socket** (no desde un efecto): ordenada por
  // timestamp y sin duplicados (BR-003/BR-007).
  const acumularLectura = useCallback((lectura: Lectura) => {
    setLecturas((previas) => {
      if (previas.some((l) => l.timestamp === lectura.timestamp)) {
        return previas
      }
      const siguiente = [...previas, lectura]
      siguiente.sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp))
      return siguiente.slice(-config.serieMaxPuntos)
    })
  }, [])

  const alFallarAutenticacion = useCallback(() => cerrarSesion('expirada'), [cerrarSesion])

  const enVivo = useLecturasEnVivo(
    idValido ? sensorId : '',
    token,
    {
      baseMs: config.wsBackoffBaseMs,
      factor: config.wsBackoffFactor,
      topeMs: config.wsBackoffTopeMs,
      maxIntentos: config.wsMaxIntentos,
    },
    alFallarAutenticacion,
    acumularLectura,
  )

  // Histórico de la ventana (una vez por sensor; refresco de respaldo si el WS está pausado).
  const cargarHistorico = useCallback(async () => {
    if (!idValido || Date.now() < pausaHastaRef.current) {
      return
    }
    abortRef.current?.abort()
    const controlador = new AbortController()
    abortRef.current = controlador
    setCargando(true)
    try {
      // Metadata y serie se piden en paralelo pero **independientes**: si el histórico falla (429,
      // red, rango), el detalle sigue mostrando la identidad del sensor y su estado en vez de
      // desaparecer.
      const [meta, historico] = await Promise.allSettled([
        detalleSensor(sensorId, token, controlador.signal),
        lecturasDeVentana(sensorId, ventanaDeHoras(config.serieHoras), token, config.serieMaxPuntos,
            controlador.signal),
      ])

      if (meta.status === 'fulfilled') {
        setSensor(meta.value)
      }
      if (historico.status === 'fulfilled') {
        setLecturas(historico.value.lecturas)
        setTruncado(historico.value.truncado)
        setError(null)
        setSegundosPausa(null)
        return
      }

      const fallo = historico.reason
      if (fallo instanceof DOMException && fallo.name === 'AbortError') {
        return
      }
      if (fallo instanceof ErrorDeApi) {
        if (fallo.esNoAutenticado) {
          alFallarAutenticacion()
          return
        }
        setError(fallo)
        if (fallo.esCupoAgotado && fallo.retryAfterSegundos !== null) {
          pausaHastaRef.current = Date.now() + fallo.retryAfterSegundos * 1000
          setSegundosPausa(fallo.retryAfterSegundos)
        }
        return
      }
      setError(fallo as ErrorDeApi)
    } finally {
      setCargando(false)
    }
  }, [sensorId, token, idValido, alFallarAutenticacion])

  useEffect(() => {
    if (!idValido) {
      return
    }
    const inicial = window.setTimeout(() => void cargarHistorico(), 0)
    return () => {
      window.clearTimeout(inicial)
      abortRef.current?.abort()
    }
  }, [idValido, cargarHistorico])

  // Refresco de respaldo sólo mientras el WS no está entregando datos (AF-01/BR-003).
  useEffect(() => {
    if (!idValido || enVivo.estado === 'conectado') {
      return
    }
    const timer = window.setInterval(() => void cargarHistorico(), config.serieRefrescoMs)
    return () => window.clearInterval(timer)
  }, [idValido, enVivo.estado, cargarHistorico])

  // Cuenta atrás de la pausa por cupo.
  useEffect(() => {
    if (segundosPausa === null) {
      return
    }
    if (segundosPausa <= 0) {
      const reanudar = window.setTimeout(() => {
        pausaHastaRef.current = 0
        setSegundosPausa(null)
        void cargarHistorico()
      }, 0)
      return () => window.clearTimeout(reanudar)
    }
    const timer = window.setTimeout(() => setSegundosPausa((s) => (s === null ? null : s - 1)), 1000)
    return () => window.clearTimeout(timer)
  }, [segundosPausa, cargarHistorico])

  // Reloj para la antigüedad del dato (BR-006).
  useEffect(() => {
    const timer = window.setInterval(() => setAhora(new Date()), 5000)
    return () => window.clearInterval(timer)
  }, [])

  // La lectura del WS entra al histórico mediante `acumularLectura` (ver arriba), que se invoca
  // desde el evento del socket: así el efecto no hace setState sincrónico.

  if (!idValido) {
    return (
      <EstadoVacio
        titulo="Identificador de sensor inválido"
        detalle="Volvé al mapa y elegí un sensor de la lista o de un marcador."
      >
        <Link className="boton" to="/mapa">
          Ir al mapa
        </Link>
      </EstadoVacio>
    )
  }

  const ultima = enVivo.ultima ?? lecturas[lecturas.length - 1] ?? null
  const ultimaEsDeWs = enVivo.ultima !== null && ultima === enVivo.ultima
  const momentoUltima = ultimaEsDeWs && enVivo.ultimaEn !== null
    ? enVivo.ultimaEn
    : ultima !== null ? new Date(ultima.timestamp) : null
  const vencido =
    momentoUltima !== null && ahora.getTime() - momentoUltima.getTime() > config.datoVencidoMs
  const estado = ultima === null ? 'SIN_DATOS' : estadoVisual(ultima)
  const e = estilo(estado)
  const operativo = sensor === null ? true : estadoOperativo(sensor.estado)

  return (
    <section className="pantalla-detalle" aria-labelledby="titulo-detalle">
      <header className="barra-titulo">
        <h1 id="titulo-detalle">{sensor === null ? 'Sensor' : recortar(etiquetaSensor(sensor), 80)}</h1>
        <span className={`chip-severidad ${e.clase}`}>
          <span aria-hidden="true">{e.simbolo}</span> {e.etiqueta}
        </span>
        <span className={`chip-conexion conexion-${enVivo.estado}`}>
          {describirConexion(enVivo.estado, enVivo.intento)}
        </span>
        <p className="estado-detalle tecnico">id={sensorId}</p>
      </header>

      {segundosPausa !== null ? (
        <Aviso tono="alerta">
          Se alcanzó el límite de pedidos del servidor. El histórico se reintenta en {segundosPausa} s
          (los datos en vivo por WebSocket no se ven afectados).
        </Aviso>
      ) : null}

      {!operativo ? (
        <Aviso tono="alerta">
          El sensor está {recortar(sensor?.estado, 30)}: no se esperan lecturas nuevas.
        </Aviso>
      ) : null}

      {error !== null ? (
        <EstadoError error={error} onReintentar={() => void cargarHistorico()} />
      ) : cargando && lecturas.length === 0 ? (
        <EstadoCargando texto="Cargando lecturas…" />
      ) : (
        <>
          <dl className="datos-sensor">
            <div>
              <dt>Última lectura</dt>
              <dd>
                {ultima === null ? '—' : `${numero(ultima.valor)} ${sensor?.unidadMedida ?? ultima.unidadMedida ?? ''}`}
                {ultimaEsDeWs ? <span className="marca-vivo"> en vivo</span> : null}
              </dd>
            </div>
            <div>
              <dt>Momento</dt>
              <dd>
                {ultima === null ? '—' : `${fechaHora(ultima.timestamp)} (${antiguedad(ultima.timestamp, ahora)})`}
                {vencido ? <span className="marca-vencido"> dato vencido</span> : null}
              </dd>
            </div>
            <div>
              <dt>Código</dt>
              <dd>{recortar(sensor?.codigo, 40)}</dd>
            </div>
            <div>
              <dt>Tipo / unidad</dt>
              <dd>
                {recortar(sensor?.tipo, 30)} · {recortar(sensor?.unidadMedida, 20)}
              </dd>
            </div>
            <div>
              <dt>Ubicación</dt>
              <dd>
                {sensor?.latitud !== null && sensor?.latitud !== undefined && sensor?.longitud !== null &&
                sensor?.longitud !== undefined
                  ? `${numero(sensor.latitud)}, ${numero(sensor.longitud)}`
                  : '—'}
              </dd>
            </div>
          </dl>

          <SerieTemporal lecturas={lecturas} unidad={sensor?.unidadMedida ?? null} truncado={truncado} />

          <TablaLecturas lecturas={lecturas} filas={config.tablaFilas} />

          {ultima !== null && esCalidadInvalida(ultima.calidad) ? (
            <Aviso tono="alerta">
              La última lectura está marcada como <strong>no válida</strong> (calidad ERROR_SENSOR): se
              muestra para diagnóstico, pero no se usa para evaluar severidad.
            </Aviso>
          ) : null}
        </>
      )}
    </section>
  )
}

/** Tabla de últimas lecturas: la fuente de verdad accesible de la serie (BR-005). */
function TablaLecturas({ lecturas, filas }: { lecturas: Lectura[]; filas: number }): React.JSX.Element {
  const ultimas = useMemo(() => [...lecturas].reverse().slice(0, filas), [lecturas, filas])

  if (ultimas.length === 0) {
    return (
      <EstadoVacio
        titulo="Sin lecturas en la ventana"
        detalle="El sensor no reportó datos en el período consultado. El detalle sigue escuchando en vivo."
      />
    )
  }

  return (
    <table className="tabla-lecturas">
      <caption className="sr-solo">Últimas {ultimas.length} lecturas del sensor</caption>
      <thead>
        <tr>
          <th scope="col">Hora</th>
          <th scope="col">Valor</th>
          <th scope="col">Unidad</th>
          <th scope="col">Severidad</th>
          <th scope="col">Calidad</th>
        </tr>
      </thead>
      <tbody>
        {ultimas.map((l) => {
          const e = estilo(estadoVisual(l))
          const invalida = esCalidadInvalida(l.calidad)
          return (
            <tr key={l.timestamp} className={invalida ? 'fila-invalida' : undefined}>
              <td>{fechaHora(l.timestamp)}</td>
              <td>{invalida ? `${numero(l.valor)} (no válido)` : numero(l.valor)}</td>
              <td>{recortar(l.unidadMedida, 20)}</td>
              <td className={e.clase}>
                <span aria-hidden="true">{e.simbolo}</span> {e.etiqueta}
              </td>
              <td>{recortar(l.calidad, 20)}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}