import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { ErrorDeApi, detalleTecnico, mensajeDe } from '../../api/errores'
import { useSesion } from '../../sesion/SesionContext'
import { Aviso, EstadoError } from '../../componentes/Estados'

/**
 * Login (FEAT-0009 Main Flow 1/2, AF-07).
 *
 * - Al entrar guarda la sesión y **vuelve a la ruta que el usuario había pedido** (AF-07), sin
 *   dejar la ruta en el historial.
 * - El error se muestra por `code` (`INVALID_CREDENTIALS`, `429`, red) con el detalle técnico.
 * - No muestra ni sugiere credenciales de demo (A12 de la revisión de seguridad).
 */

type UbicacionEstado = { desde?: string } | null

export function LoginPage(): React.JSX.Element {
  const { entrar } = useSesion()
  const navegar = useNavigate()
  const ubicacion = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [error, setError] = useState<ErrorDeApi | null>(null)

  const destino = ((ubicacion.state ?? null) as UbicacionEstado)?.desde ?? '/mapa'

  async function enviar(evento: React.FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (enviando) {
      return
    }
    setEnviando(true)
    setError(null)
    try {
      await entrar(email.trim(), password)
      navegar(destino, { replace: true })
    } catch (e) {
      setError(e instanceof ErrorDeApi ? e : null)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <main className="pantalla-login">
      <form className="tarjeta formulario" onSubmit={(e) => void enviar(e)} aria-labelledby="titulo-login">
        <h1 id="titulo-login">WR-Sensor</h1>
        <p className="estado-detalle">Panel de monitoreo de sensores fluviales.</p>

        {error !== null ? <EstadoError error={error} /> : null}

        <label className="campo">
          <span>Usuario</span>
          <input
            type="email"
            name="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            aria-invalid={error?.code === 'INVALID_CREDENTIALS'}
          />
        </label>

        <label className="campo">
          <span>Contraseña</span>
          <input
            type="password"
            name="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            aria-invalid={error?.code === 'INVALID_CREDENTIALS'}
          />
        </label>

        <button type="submit" className="boton boton-primario" disabled={enviando}>
          {enviando ? 'Entrando…' : 'Entrar'}
        </button>

        {error !== null && error.esCupoAgotado ? (
          <Aviso tono="alerta">
            {mensajeDe(error)}
            {error.retryAfterSegundos !== null
              ? ` Reintentá en ${error.retryAfterSegundos} s.`
              : ''}
          </Aviso>
        ) : null}

        {error !== null ? <p className="estado-detalle tecnico">{detalleTecnico(error)}</p> : null}
      </form>
    </main>
  )
}