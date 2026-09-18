import { Link, useParams } from 'react-router-dom'
import { EstadoVacio } from '../../componentes/Estados'
import { esUuid } from '../../dominio/severidad'

/**
 * Detalle del sensor (FEAT-0009 Main Flow 4).
 *
 * En este work item el detalle muestra la metadata y la última lectura **conocida**; las lecturas en
 * vivo por WebSocket y la serie de 24 h son `FEAT-0014`. El `id` se valida como UUID antes de
 * usarse (A12) y si la ruta no trae un id válido se muestra un estado vacío en lugar de una pantalla
 * rota.
 */
export function DetallePage(): React.JSX.Element {
  const { id } = useParams<{ id: string }>()

  if (id === undefined || !esUuid(id)) {
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

  return (
    <section className="pantalla-detalle" aria-labelledby="titulo-detalle">
      <header className="barra-titulo">
        <h1 id="titulo-detalle">Detalle del sensor</h1>
        <p className="estado-detalle tecnico">id={id}</p>
      </header>

      <EstadoVacio
        titulo="Detalle en vivo disponible en la próxima entrega"
        detalle="Las lecturas en vivo por WebSocket y la serie de 24 h llegan con FEAT-0014. Mientras tanto, el mapa y la lista muestran la última lectura de cada sensor."
      >
        <Link className="boton" to="/mapa">
          Volver al mapa
        </Link>
      </EstadoVacio>
    </section>
  )
}