import { useMemo } from 'react'
import { Link } from 'react-router-dom'
import { describirEstadoAlertas, useAlertas } from '../../alertas/ProveedorAlertas'
import type { Alerta } from '../../alertas/ProveedorAlertas'
import type { SensorResumen } from '../../api/tipos'
import { EstadoVacio } from '../../componentes/Estados'
import { antiguedad, fechaHora, recortar } from '../../dominio/formato'
import { estilo, etiquetaSensor, esUuid } from '../../dominio/severidad'

/**
 * Feed de alertas confirmadas (FEAT-0015 Main Flow 2/5, BR-005/BR-006/BR-007, AF-03/AF-05/AF-07/AF-08).
 *
 * - Es **efímero y lo declara**: las alertas viven en memoria, así que la UI avisa que se pierden al
 *   recargar (BR-005) en lugar de insinuar persistencia.
 * - El nombre del sensor se resuelve contra la metadata ya cargada (sin N+1): si no está, se muestra
 *   el `sensorId` y se ofrece recargar el resumen (BR-007/AF-05).
 * - Las **normalizaciones** se distinguen y no cuentan como críticas no leídas (BR-006/AF-07).
 * - Accesibilidad: lista navegable por teclado y anuncio de alertas nuevas por `aria-live` sin robar
 *   el foco (BR-008).
 */
export function FeedAlertas({
  sensores,
  alRecargarResumen,
}: {
  sensores: SensorResumen[]
  alRecargarResumen?: () => void
}): React.JSX.Element {
  const { alertas, estado, intento, noLeidas, marcarLeidas } = useAlertas()

  const porId = useMemo(() => {
    const mapa = new Map<string, SensorResumen>()
    for (const s of sensores) {
      mapa.set(s.id, s)
    }
    return mapa
  }, [sensores])

  const ultima = alertas[0]

  return (
    <section className="panel-alertas" aria-labelledby="titulo-alertas">
      <header className="panel-alertas-encabezado">
        <h2 id="titulo-alertas">Alertas confirmadas</h2>
        <span className={`chip-conexion conexion-${estado}`}>{describirEstadoAlertas(estado, intento)}</span>
        {noLeidas > 0 ? (
          <button type="button" className="boton boton-chico" onClick={marcarLeidas}>
            {noLeidas} sin leer · marcar como leídas
          </button>
        ) : null}
      </header>

      {/* Anuncio accesible: sólo el texto de la última alerta, sin mover el foco (BR-008). */}
      <p className="sr-solo" role="status" aria-live="polite">
        {ultima === undefined
          ? ''
          : `Alerta ${ultima.severidadNueva} en ${nombreDe(ultima, porId)} a las ${fechaHora(ultima.timestamp)}`}
      </p>

      {alertas.length === 0 ? (
        <EstadoVacio
          titulo="Sin alertas en esta sesión"
          detalle="El feed se alimenta de los cambios de severidad confirmados mientras la pestaña está abierta: al recargar la página, el historial se pierde (no se guarda en el servidor)."
        >
          {estado !== 'conectado' ? (
            <p className="estado-detalle tecnico">El feed todavía no está conectado.</p>
          ) : null}
        </EstadoVacio>
      ) : (
        <ul className="lista-alertas" aria-label="Alertas confirmadas">
          {alertas.map((alerta) => {
            const e = estilo(alerta.estado)
            const sensor = porId.get(alerta.sensorId)
            return (
              <li key={`${alerta.sensorId}-${alerta.timestamp}-${alerta.severidadNueva}`} className={`item-alerta ${e.clase}`}>
                <span className="item-severidad" aria-hidden="true">
                  {e.simbolo}
                </span>
                <span className="item-cuerpo">
                  <span className="item-titulo">
                    {nombreDe(alerta, porId)}
                    {alerta.empeora ? null : <span className="marca-normaliza"> · normalización</span>}
                  </span>
                  <span className="item-detalle">
                    {e.etiqueta}
                    {alerta.severidadAnterior !== null ? ` (antes ${alerta.severidadAnterior})` : ''} ·{' '}
                    {fechaHora(alerta.timestamp)} ({antiguedad(alerta.timestamp)})
                  </span>
                  {sensor === undefined ? (
                    <span className="item-detalle">
                      Sensor fuera del resumen cargado.{' '}
                      {alRecargarResumen !== undefined ? (
                        <button type="button" className="boton boton-chico" onClick={alRecargarResumen}>
                          Recargar sensores
                        </button>
                      ) : null}
                    </span>
                  ) : esUuid(alerta.sensorId) ? (
                    <Link className="item-detalle" to={`/sensores/${encodeURIComponent(alerta.sensorId)}`}>
                      Ver detalle del sensor
                    </Link>
                  ) : null}
                </span>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}

/** Nombre del sensor resuelto contra la metadata; si no está, se muestra el id crudo (BR-007). */
function nombreDe(alerta: Alerta, porId: Map<string, SensorResumen>): string {
  const sensor = porId.get(alerta.sensorId)
  return sensor === undefined ? recortar(alerta.sensorId, 40) : recortar(etiquetaSensor(sensor), 60)
}