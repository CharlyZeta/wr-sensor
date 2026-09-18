import type { ReactNode } from 'react'
import { detalleTecnico, mensajeDe } from '../api/errores'
import type { ErrorDeApi } from '../api/errores'

/**
 * Estados de la UI (FEAT-0009 BR-008): carga, error y vacío **siempre** visibles, nunca una pantalla
 * en blanco. El error se muestra por `code` con el detalle técnico (status, código y correlación)
 * como texto plano, para poder cruzarlo con los logs del gateway (BR-004).
 */

export function EstadoCargando({ texto = 'Cargando…' }: { texto?: string }): React.JSX.Element {
  return (
    <div className="estado estado-cargando" role="status" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      {texto}
    </div>
  )
}

export function EstadoVacio({
  titulo,
  detalle,
  children,
}: {
  titulo: string
  detalle?: string
  children?: ReactNode
}): React.JSX.Element {
  return (
    <div className="estado estado-vacio" role="status">
      <p className="estado-titulo">{titulo}</p>
      {detalle !== undefined ? <p className="estado-detalle">{detalle}</p> : null}
      {children}
    </div>
  )
}

export function EstadoError({
  error,
  onReintentar,
}: {
  error: ErrorDeApi
  onReintentar?: () => void
}): React.JSX.Element {
  return (
    <div className="estado estado-error" role="alert">
      <p className="estado-titulo">{mensajeDe(error)}</p>
      <p className="estado-detalle tecnico">{detalleTecnico(error)}</p>
      {onReintentar !== undefined ? (
        <button type="button" className="boton" onClick={onReintentar}>
          Reintentar
        </button>
      ) : null}
    </div>
  )
}

/** Aviso no bloqueante (por ejemplo cupo agotado): no interrumpe el trabajo del operador. */
export function Aviso({
  tono = 'info',
  children,
  onCerrar,
}: {
  tono?: 'info' | 'alerta'
  children: ReactNode
  onCerrar?: () => void
}): React.JSX.Element {
  return (
    <div className={`aviso aviso-${tono}`} role="status" aria-live="polite">
      <span>{children}</span>
      {onCerrar !== undefined ? (
        <button type="button" className="aviso-cerrar" onClick={onCerrar} aria-label="Cerrar aviso">
          ×
        </button>
      ) : null}
    </div>
  )
}