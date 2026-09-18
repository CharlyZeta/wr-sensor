import { Link, Navigate, Outlet, useLocation } from 'react-router-dom'
import { useSesion } from '../sesion/SesionContext'

/**
 * Shell del SPA + guard de sesión (FEAT-0009 AF-01/AF-06/AF-07, BR-008).
 *
 * - Sin sesión, cualquier ruta protegida redirige al login **recordando el destino** (`desde`), así
 *   el usuario vuelve a donde quería ir después de autenticarse (AF-07).
 * - El rol se refleja en la barra: a `VIEWER` no se le ofrecen acciones de escritura (AF-06: no se
 *   muestran botones que el backend va a rechazar).
 * - El cierre de sesión es atómico (`cerrarSesion`): limpia el storage y desmonta el árbol, con lo
 *   que también se cierran los timers y (en FEAT-0014/0015) los WebSocket.
 */
export function Layout(): React.JSX.Element {
  const { sesion, autenticado, esAdmin, cerrarSesion } = useSesion()
  const ubicacion = useLocation()

  if (!autenticado || sesion === null) {
    return <Navigate to="/login" replace state={{ desde: ubicacion.pathname }} />
  }

  return (
    <div className="shell">
      <header className="shell-barra">
        <Link to="/mapa" className="marca">
          WR-Sensor
        </Link>
        <nav className="shell-nav" aria-label="Navegación principal">
          <Link to="/mapa">Mapa</Link>
        </nav>
        <div className="shell-usuario">
          <span className="chip-rol" title={`Rol: ${sesion.rol}`}>
            {sesion.rol}
          </span>
          {esAdmin ? null : <span className="estado-detalle">sólo lectura</span>}
          <span className="estado-detalle">{sesion.email}</span>
          <button type="button" className="boton boton-chico" onClick={() => cerrarSesion('manual')}>
            Salir
          </button>
        </div>
      </header>
      <main className="shell-contenido">
        <Outlet />
      </main>
    </div>
  )
}