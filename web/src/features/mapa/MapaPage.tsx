import { useCallback, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAlertas } from '../../alertas/ProveedorAlertas'
import type { SensorResumen } from '../../api/tipos'
import { Aviso, EstadoCargando, EstadoError, EstadoVacio } from '../../componentes/Estados'
import { antiguedad } from '../../dominio/formato'
import { puntosValidos } from '../../dominio/severidad'
import { useResumen } from '../../hooks/useResumen'
import { useSesion } from '../../sesion/SesionContext'
import { ListaSensores } from './ListaSensores'
import { MapaSensores } from './MapaSensores'

/**
 * Pantalla del mapa (FEAT-0009 Main Flow 3/5, AF-04/AF-05/AF-08/AF-09).
 *
 * El estado del servidor (`useResumen`) se renderiza **completo**: carga, error por `code` con
 * reintento, vacío explícito y aviso no bloqueante cuando se agota el cupo. Nunca hay marcadores
 * parciales presentados como si el mapa estuviera completo (AF-04).
 */
export function MapaPage(): React.JSX.Element {
  const { sesion, cerrarSesion } = useSesion()
  const navegar = useNavigate()
  const token = sesion?.token ?? ''

  const alNoAutenticado = useCallback(() => cerrarSesion('expirada'), [cerrarSesion])
  const resumen = useResumen(token, alNoAutenticado)
  const { rafagas } = useAlertas()

  // FEAT-0015 BR-004: cada **ráfaga** de alertas (ya agrupada por el proveedor, 2 s) refresca el
  // resumen una sola vez, así el mapa refleja la severidad nueva sin una request por alerta.
  const recargar = resumen.recargar
  const rafagasPrevias = useRef(rafagas)
  useEffect(() => {
    if (rafagas !== rafagasPrevias.current) {
      rafagasPrevias.current = rafagas
      recargar()
    }
  }, [rafagas, recargar])

  const abrirDetalle = useCallback(
    (sensor: SensorResumen) => {
      navegar(`/sensores/${encodeURIComponent(sensor.id)}`)
    },
    [navegar],
  )

  const { puntos, sinCoordenadas } = puntosValidos(resumen.sensores)

  return (
    <section className="pantalla-mapa" aria-labelledby="titulo-mapa">
      <header className="barra-titulo">
        <h1 id="titulo-mapa">Mapa de sensores</h1>
        <p className="estado-detalle">
          {resumen.actualizadoEn === null
            ? 'Sin datos todavía'
            : `Actualizado ${antiguedad(resumen.actualizadoEn.toISOString())} · ${resumen.sensores.length} sensores`}
        </p>
      </header>

      {resumen.pausadoSegundos !== null ? (
        <Aviso tono="alerta">
          Se alcanzó el límite de pedidos del servidor. El refresco automático se reanuda en{' '}
          {resumen.pausadoSegundos} s.
        </Aviso>
      ) : null}

      {resumen.error !== null ? (
        <EstadoError error={resumen.error} onReintentar={resumen.recargar} />
      ) : resumen.cargando && resumen.sensores.length === 0 ? (
        <EstadoCargando texto="Cargando sensores…" />
      ) : resumen.sensores.length === 0 ? (
        <EstadoVacio
          titulo="No hay sensores para mostrar"
          detalle="El catálogo está vacío. Un administrador puede dar de alta sensores desde la API."
        />
      ) : (
        <div className="disposicion-mapa">
          <MapaSensores puntos={puntos} alAbrirDetalle={abrirDetalle} />
          <aside className="panel-lista" aria-label="Listado de sensores">
            {sinCoordenadas.length > 0 ? (
              <p className="estado-detalle">
                {sinCoordenadas.length} sensor(es) sin coordenadas válidas: aparecen sólo en la lista.
              </p>
            ) : null}
            <ListaSensores
              sensores={resumen.sensores}
              sinCoordenadas={sinCoordenadas}
              alAbrirDetalle={abrirDetalle}
            />
          </aside>
        </div>
      )}
    </section>
  )
}