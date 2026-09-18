import { useEffect, useRef } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { config } from '../../config'
import type { PuntoMapa } from '../../dominio/severidad'
import { estilo, etiquetaSensor } from '../../dominio/severidad'
import { antiguedad, numero, recortar } from '../../dominio/formato'
import type { SensorResumen } from '../../api/tipos'

/**
 * Mapa de sensores con Leaflet (FEAT-0009 BR-006/BR-007, AF-09).
 *
 * **Seguridad (A2/A3 de la revisión de seguridad):** el contenido de los popups se construye con
 * **nodos del DOM** (`textContent`), nunca con HTML armado con datos del backend — Leaflet hace
 * `innerHTML` con los strings que recibe, y `nombre`/`codigo` son texto libre del registry. Los
 * íconos salen de un **catálogo propio indexado por severidad** (`divIcon` con un nodo creado a
 * mano), jamás de una URL provista por los datos.
 *
 * El mapa se inicializa una sola vez (ref) y se limpia en el cleanup: sin marcadores duplicados ni
 * "Map container is already initialized" (con StrictMode se monta dos veces en desarrollo).
 */

const CENTRO_POR_DEFECTO: [number, number] = [-31.6, -60.7] // Paraná/Salado (Santa Fe)

function icono(estado: PuntoMapa['estado']): L.DivIcon {
  const e = estilo(estado)
  const nodo = document.createElement('span')
  nodo.className = `marcador ${e.clase}`
  nodo.textContent = e.simbolo
  nodo.setAttribute('aria-hidden', 'true')
  return L.divIcon({
    className: 'marcador-contenedor',
    html: nodo.outerHTML, // nodo propio con textContent ya escapado: no hay datos del backend acá
    iconSize: [22, 22],
    iconAnchor: [11, 11],
  })
}

/** Popup construido con nodos: `nombre`/`codigo` van como texto, nunca como HTML. */
function popup(punto: PuntoMapa, alAbrirDetalle: (sensor: SensorResumen) => void): HTMLElement {
  const contenedor = document.createElement('div')
  contenedor.className = 'popup-sensor'

  const titulo = document.createElement('strong')
  titulo.textContent = recortar(etiquetaSensor(punto.sensor), 60)
  contenedor.appendChild(titulo)

  const e = estilo(punto.estado)
  const severidad = document.createElement('p')
  severidad.className = `popup-severidad ${e.clase}`
  severidad.textContent = `${e.simbolo} ${e.etiqueta}`
  contenedor.appendChild(severidad)

  const ultima = punto.sensor.ultimaLectura
  const valor = document.createElement('p')
  valor.textContent =
    ultima === null
      ? 'Sin lecturas registradas'
      : `${numero(ultima.valor)} ${punto.sensor.unidadMedida ?? ''} · ${antiguedad(ultima.timestamp)}`
  contenedor.appendChild(valor)

  const boton = document.createElement('button')
  boton.type = 'button'
  boton.className = 'boton boton-chico'
  boton.textContent = 'Ver detalle'
  boton.addEventListener('click', () => alAbrirDetalle(punto.sensor))
  contenedor.appendChild(boton)

  return contenedor
}

export function MapaSensores({
  puntos,
  alAbrirDetalle,
}: {
  puntos: PuntoMapa[]
  alAbrirDetalle: (sensor: SensorResumen) => void
}): React.JSX.Element {
  const contenedorRef = useRef<HTMLDivElement | null>(null)
  const mapaRef = useRef<L.Map | null>(null)
  const capaRef = useRef<L.LayerGroup | null>(null)
  const tilesRef = useRef<L.TileLayer | null>(null)

  // Inicialización única del mapa y de la capa de marcadores.
  useEffect(() => {
    const contenedor = contenedorRef.current
    if (contenedor === null || mapaRef.current !== null) {
      return
    }
    const mapa = L.map(contenedor, { center: CENTRO_POR_DEFECTO, zoom: 8, zoomControl: true })
    mapaRef.current = mapa
    capaRef.current = L.layerGroup().addTo(mapa)

    const tiles = L.tileLayer(config.tilesUrl, {
      attribution: config.tilesAtribucion,
      maxZoom: 19,
      crossOrigin: true,
    })
    tilesRef.current = tiles
    // AF-09: si los tiles no cargan, la app sigue usable (marcadores y lista sobre fondo neutro).
    tiles.on('tileerror', () => {
      contenedor.classList.add('sin-tiles')
    })
    tiles.addTo(mapa)

    const invalidar = (): void => {
      mapa.invalidateSize()
    }
    window.addEventListener('resize', invalidar)

    return () => {
      window.removeEventListener('resize', invalidar)
      mapa.remove()
      mapaRef.current = null
      capaRef.current = null
      tilesRef.current = null
    }
  }, [])

  // Repintado de marcadores: se limpia la capa antes de agregar (sin duplicados).
  useEffect(() => {
    const mapa = mapaRef.current
    const capa = capaRef.current
    if (mapa === null || capa === null) {
      return
    }
    capa.clearLayers()
    for (const punto of puntos) {
      const marcador = L.marker([punto.latitud, punto.longitud], {
        icon: icono(punto.estado),
        title: etiquetaSensor(punto.sensor),
        alt: `${etiquetaSensor(punto.sensor)}: ${estilo(punto.estado).etiqueta}`,
      })
      marcador.bindPopup(() => popup(punto, alAbrirDetalle))
      marcador.addTo(capa)
    }
    if (puntos.length > 0) {
      const limites = L.latLngBounds(puntos.map((p) => [p.latitud, p.longitud] as [number, number]))
      mapa.fitBounds(limites.pad(0.25), { maxZoom: 12 })
    }
  }, [puntos, alAbrirDetalle])

  return (
    <div className="mapa-envoltorio">
      <div ref={contenedorRef} className="mapa" role="application" aria-label="Mapa de sensores" />
      <ul className="leyenda" aria-label="Leyenda de severidad">
        {(['NORMAL', 'WARNING', 'CRITICAL', 'INVALIDO', 'SIN_DATOS'] as const).map((estado) => {
          const e = estilo(estado)
          return (
            <li key={estado} className={e.clase}>
              <span aria-hidden="true">{e.simbolo}</span> {e.etiqueta}
            </li>
          )
        })}
      </ul>
    </div>
  )
}