import { useMemo } from 'react'
import type { SensorResumen } from '../../api/tipos'
import { antiguedad, numero, recortar } from '../../dominio/formato'
import { estilo, estadoOperativo, estadoVisual, etiquetaSensor, peso } from '../../dominio/severidad'

/**
 * Lista de sensores del mapa (FEAT-0009 Main Flow 3, BR-007/BR-008): misma fuente de severidad que
 * los marcadores, navegable por teclado y con la información también en texto (no sólo color).
 * Los sensores sin coordenadas válidas se listan igual, marcados, para que no desaparezcan.
 */
export function ListaSensores({
  sensores,
  sinCoordenadas,
  alAbrirDetalle,
}: {
  sensores: SensorResumen[]
  sinCoordenadas: SensorResumen[]
  alAbrirDetalle: (sensor: SensorResumen) => void
}): React.JSX.Element {
  const ordenados = useMemo(
    () =>
      [...sensores].sort(
        (a, b) =>
          peso(estadoVisual(a.ultimaLectura)) - peso(estadoVisual(b.ultimaLectura)) ||
          etiquetaSensor(a).localeCompare(etiquetaSensor(b)),
      ),
    [sensores],
  )

  const sinUbicacion = useMemo(
    () => new Set(sinCoordenadas.map((s) => s.id)),
    [sinCoordenadas],
  )

  if (ordenados.length === 0) {
    return (
      <p className="estado-detalle">
        El servidor no devolvió sensores en el resumen.
      </p>
    )
  }

  return (
    <ul className="lista-sensores" aria-label="Sensores">
      {ordenados.map((sensor) => {
        const e = estilo(estadoVisual(sensor.ultimaLectura))
        const ultima = sensor.ultimaLectura
        return (
          <li key={sensor.id} className={`item-sensor ${e.clase}`}>
            <button type="button" className="item-boton" onClick={() => alAbrirDetalle(sensor)}>
              <span className="item-severidad" aria-hidden="true">
                {e.simbolo}
              </span>
              <span className="item-cuerpo">
                <span className="item-titulo">{recortar(etiquetaSensor(sensor), 60)}</span>
                <span className="item-detalle">
                  <span className="sr-solo">{e.etiqueta}. </span>
                  {recortar(sensor.codigo, 30)} · {recortar(sensor.tipo, 24)}
                  {estadoOperativo(sensor.estado) ? '' : ' · fuera de servicio'}
                  {sinUbicacion.has(sensor.id) ? ' · sin coordenadas' : ''}
                </span>
                <span className="item-valor">
                  {ultima === null
                    ? 'Sin lecturas'
                    : `${numero(ultima.valor)} ${sensor.unidadMedida ?? ''} · ${antiguedad(ultima.timestamp)}`}
                </span>
              </span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}