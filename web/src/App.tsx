import { useState } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { Aviso } from './componentes/Estados'
import { AdminFormPage } from './features/admin/AdminFormPage'
import { AdminSensoresPage } from './features/admin/AdminSensoresPage'
import { AlertasPage } from './features/alertas/AlertasPage'
import { DetallePage } from './features/detalle/DetallePage'
import { LoginPage } from './features/login/LoginPage'
import { MapaPage } from './features/mapa/MapaPage'
import { Layout } from './rutas/Layout'
import { ProveedorSesion } from './sesion/SesionContext'

/**
 * Raíz del SPA: rutas y aviso global de cierre de sesión (FEAT-0009 Main Flow 1/6).
 *
 * El aviso de sesión cerrada se muestra **fuera** del shell (porque el shell ya no se renderiza sin
 * sesión) y explica el motivo: `expirada` (venció el token) o `manual` (el usuario salió).
 */
export function App(): React.JSX.Element {
  const [motivo, setMotivo] = useState<string | null>(null)

  return (
    <ProveedorSesion onSesionCerrada={setMotivo}>
      {motivo !== null && motivo !== 'manual' ? (
        <div className="aviso-global">
          <Aviso tono="alerta" onCerrar={() => setMotivo(null)}>
            {motivo === 'expirada'
              ? 'Tu sesión expiró. Volvé a iniciar sesión para seguir viendo los sensores.'
              : 'La sesión se cerró. Iniciá sesión nuevamente.'}
          </Aviso>
        </div>
      ) : null}

      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<Layout />}>
          <Route path="/mapa" element={<MapaPage />} />
          <Route path="/alertas" element={<AlertasPage />} />
          {/* Las rutas administrativas se declaran antes que el detalle para que «nuevo» no se
              interprete como un id de sensor. */}
          <Route path="/sensores/nuevo" element={<AdminFormPage modo="alta" />} />
          <Route path="/sensores/:id/editar" element={<AdminFormPage modo="edicion" />} />
          <Route path="/admin/sensores" element={<AdminSensoresPage />} />
          <Route path="/sensores/:id" element={<DetallePage />} />
        </Route>
        <Route path="/" element={<Navigate to="/mapa" replace />} />
        <Route
          path="*"
          element={
            <div className="pantalla-centrada">
              <h1>Página no encontrada</h1>
              <p className="estado-detalle">
                La ruta pedida no existe en el panel. Volvé al mapa para seguir trabajando.
              </p>
              <a className="boton" href="/mapa">
                Ir al mapa
              </a>
            </div>
          }
        />
      </Routes>
    </ProveedorSesion>
  )
}