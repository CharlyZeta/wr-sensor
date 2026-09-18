import { useCallback, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAlertas } from '../../alertas/ProveedorAlertas'
import { EstadoCargando, EstadoError, EstadoVacio } from '../../componentes/Estados'
import { useResumen } from '../../hooks/useResumen'
import { useSesion } from '../../sesion/SesionContext'
import { FeedAlertas } from '../alertas/FeedAlertas'

/**
 * Pantalla de alertas (FEAT-0015 Main Flow 5, AF-05/AF-08): el feed confirmado en vivo.
 *
 * Reutiliza el mismo `useResumen` que el mapa para resolver el nombre de los sensores del feed
 * (BR-007: sin N+1) y para ofrecer "recargar sensores" cuando una alerta llega de un sensor que no
 * está en la metadata cargada.
 */
export function AlertasPage(): React.JSX.Element {
  const { sesion, cerrarSesion } = useSesion()
  const navegar = useNavigate()
  const token = sesion?.token ?? ''
  const { noLeidas, marcarLeidas } = useAlertas()

  const alNoAutenticado = useCallback(() => cerrarSesion('expirada'), [cerrarSesion])
  const resumen = useResumen(token, alNoAutenticado)

  // Al abrir el feed, las alertas visibles dejan de contar como no leídas (BR-006). El
  // temporizador evita actualizar estado de forma sincrónica dentro del efecto.
  useEffect(() => {
    if (noLeidas === 0) {
      return
    }
    const timer = window.setTimeout(() => marcarLeidas(), 0)
    return () => window.clearTimeout(timer)
  }, [noLeidas, marcarLeidas])

  return (
    <section className="pantalla-alertas" aria-labelledby="titulo-pantalla-alertas">
      <header className="barra-titulo">
        <h1 id="titulo-pantalla-alertas">Alertas</h1>
        <p className="estado-detalle">
          Cambios de severidad confirmados por histéresis, en vivo mientras la pestaña está abierta.
        </p>
      </header>

      {resumen.error !== null ? (
        <EstadoError error={resumen.error} onReintentar={resumen.recargar} />
      ) : resumen.cargando && resumen.sensores.length === 0 ? (
        <EstadoCargando texto="Cargando sensores…" />
      ) : resumen.sensores.length === 0 ? (
        <EstadoVacio
          titulo="No se pudo resolver la metadata de los sensores"
          detalle="El feed funciona igual, pero mostrará el identificador de cada sensor."
        >
          <FeedAlertas sensores={[]} alRecargarResumen={resumen.recargar} />
        </EstadoVacio>
      ) : (
        <FeedAlertas
          sensores={resumen.sensores}
          alRecargarResumen={() => {
            resumen.recargar()
            navegar('/mapa')
          }}
        />
      )}
    </section>
  )
}