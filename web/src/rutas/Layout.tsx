import { useCallback, useEffect, useRef } from 'react'
import { Link, Navigate, Outlet, useLocation } from 'react-router-dom'
import { ProveedorAlertas, useAlertas } from '../alertas/ProveedorAlertas'
import { useSesion } from '../sesion/SesionContext'

/**
 * Shell del SPA + guard de sesión (FEAT-0009 AF-01/AF-06/AF-07, BR-008) y **proveedor único de
 * alertas** (FEAT-0015 BR-001): el WS de alertas se abre una sola vez por pestaña, por encima de las
 * rutas, así que navegar entre el mapa y el detalle no multiplica conexiones ni pierde el feed.
 *
 * - Sin sesión, cualquier ruta protegida redirige al login **recordando el destino** (`desde`).
 * - El rol se refleja en la barra: a `VIEWER` no se le ofrecen acciones de escritura (AF-06).
 * - El cierre de sesión es atómico: limpia el storage y desmonta el árbol, con lo que se cierran
 *   también el WS de alertas y los timers.
 */
export function Layout(): React.JSX.Element {
  const { sesion, autenticado, esAdmin, cerrarSesion } = useSesion()
  const ubicacion = useLocation()

  if (!autenticado || sesion === null) {
    return <Navigate to="/login" replace state={{ desde: ubicacion.pathname }} />
  }

  return (
    <ProveedorAlertas>
      <Marco cerrarSesion={cerrarSesion} email={sesion.email} rol={sesion.rol} esAdmin={esAdmin}>
        <Outlet />
      </Marco>
    </ProveedorAlertas>
  )
}

/** Barra del shell: muestra el estado del feed de alertas y el contador de no leídas (FEAT-0015). */
function Marco({
  children,
  cerrarSesion,
  email,
  rol,
  esAdmin,
}: {
  children: React.ReactNode
  cerrarSesion: (motivo?: string) => void
  email: string
  rol: string
  esAdmin: boolean
}): React.JSX.Element {
  const { noLeidas } = useAlertas()

  return (
    <div className="shell">
      <header className="shell-barra">
        <Link to="/mapa" className="marca">
          WR-Sensor
        </Link>
        <nav className="shell-nav" aria-label="Navegación principal">
          <Link to="/mapa">Mapa</Link>
          <Link to="/alertas">Alertas{noLeidas > 0 ? <span className="badge-no-leidas"> {noLeidas}</span> : null}</Link>
        </nav>
        <div className="shell-usuario">
          <span className="chip-rol" title={`Rol: ${rol}`}>
            {rol}
          </span>
          {esAdmin ? null : <span className="estado-detalle">sólo lectura</span>}
          <span className="estado-detalle">{email}</span>
          <button type="button" className="boton boton-chico" onClick={() => cerrarSesion('manual')}>
            Salir
          </button>
        </div>
      </header>
      <main className="shell-contenido">{children}</main>
    </div>
  )
}

/** Hook de conveniencia: dispara una acción agrupada cuando llegan alertas (debounce del proveedor). */
export function useRefrescoPorAlertas(accion: () => void): () => void {
  const accionRef = useRef(accion)
  useEffect(() => {
    accionRef.current = accion
  }, [accion])
  return useCallback(() => accionRef.current(), [])
}