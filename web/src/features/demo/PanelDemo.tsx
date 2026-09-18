import { useCallback, useEffect, useState } from 'react'
import { ErrorDeApi, detalleTecnico, mensajeDe } from '../../api/errores'
import { config } from '../../config'

/**
 * Panel de demo (FEAT-0016 Main Flow 5, BR-007/BR-010, AF-06).
 *
 * El `data-simulator` **no tiene ruta en el gateway** (decisión de `FEAT-0008` BR-010), así que el
 * panel está **apagado por default**: sólo se muestra si `VITE_SIMULADOR_URL` está configurada,
 * apuntando al puerto de desarrollo (`docker-compose.dev.yml`). En producción la variable queda vacía
 * y el panel no existe.
 *
 * Los errores del simulador se muestran por `code` (`SIMULATOR_ALREADY_RUNNING`,
 * `SIMULATOR_NOT_RUNNING`, `SENSOR_NOT_FOUND`) y nunca como un error de red crudo.
 */

type EstadoSimulador = { estado?: string; lecturasPublicadas?: number; sensores?: unknown[] }

export function PanelDemo(): React.JSX.Element | null {
  const base = config.simuladorUrl
  const [estado, setEstado] = useState<EstadoSimulador | null>(null)
  const [error, setError] = useState<ErrorDeApi | null>(null)
  const [ocupado, setOcupado] = useState(false)

  const consultar = useCallback(async () => {
    if (base === '') {
      return
    }
    try {
      const respuesta = await fetch(`${base}/api/simulador/estado`, {
        headers: { Accept: 'application/json' },
        credentials: 'omit',
      })
      if (!respuesta.ok) {
        throw new ErrorDeApi({
          code: 'SIMULADOR_NO_DISPONIBLE',
          status: respuesta.status,
          mensajeBackend: `El simulador respondió ${respuesta.status}.`,
          correlacion: null,
        })
      }
      setEstado((await respuesta.json()) as EstadoSimulador)
      setError(null)
    } catch (e) {
      setError(
        e instanceof ErrorDeApi
          ? e
          : new ErrorDeApi({
              code: 'SIMULADOR_NO_DISPONIBLE',
              status: 0,
              mensajeBackend: 'No se pudo contactar al simulador.',
              correlacion: null,
            }),
      )
    }
  }, [base])

  useEffect(() => {
    if (base === '') {
      return
    }
    const inicial = window.setTimeout(() => void consultar(), 0)
    const intervalo = window.setInterval(() => void consultar(), 15_000)
    return () => {
      window.clearTimeout(inicial)
      window.clearInterval(intervalo)
    }
  }, [base, consultar])

  if (base === '') {
    // BR-007: sin configuración el panel no se muestra (no es un `if` sobre el entorno, es config).
    return null
  }

  async function accion(ruta: string): Promise<void> {
    setOcupado(true)
    try {
      const respuesta = await fetch(`${base}${ruta}`, {
        method: 'POST',
        headers: { Accept: 'application/json' },
        credentials: 'omit',
      })
      const cuerpo = (await respuesta.json().catch(() => ({}))) as { code?: string; message?: string }
      if (!respuesta.ok) {
        throw new ErrorDeApi({
          code: cuerpo.code ?? `HTTP_${respuesta.status}`,
          status: respuesta.status,
          mensajeBackend: cuerpo.message ?? `El simulador respondió ${respuesta.status}.`,
          correlacion: null,
        })
      }
      setError(null)
      await consultar()
    } catch (e) {
      setError(
        e instanceof ErrorDeApi
          ? e
          : new ErrorDeApi({
              code: 'SIMULADOR_NO_DISPONIBLE',
              status: 0,
              mensajeBackend: 'No se pudo contactar al simulador.',
              correlacion: null,
            }),
      )
    } finally {
      setOcupado(false)
    }
  }

  const corriendo = estado?.estado === 'RUNNING'

  return (
    <section className="panel-demo" aria-labelledby="titulo-demo">
      <header className="panel-alertas-encabezado">
        <h2 id="titulo-demo">Demo (simulador)</h2>
        <p className="estado-detalle">
          El simulador no pasa por el gateway: este panel sólo existe porque `VITE_SIMULADOR_URL`
          apunta a su puerto de desarrollo (`docker-compose.dev.yml`).
        </p>
      </header>

      {error !== null ? (
        <div className="estado estado-error" role="alert">
          <p className="estado-titulo">{mensajeDe(error)}</p>
          <p className="estado-detalle tecnico">{detalleTecnico(error)}</p>
        </div>
      ) : null}

      <dl className="datos-sensor">
        <div>
          <dt>Estado</dt>
          <dd>{estado?.estado ?? 'desconocido'}</dd>
        </div>
        <div>
          <dt>Lecturas publicadas</dt>
          <dd>{estado?.lecturasPublicadas ?? '—'}</dd>
        </div>
      </dl>

      <div className="acciones-formulario">
        <button
          type="button"
          className="boton boton-primario"
          onClick={() => void accion('/api/simulador/iniciar')}
          disabled={ocupado || corriendo}
        >
          Iniciar
        </button>
        <button
          type="button"
          className="boton"
          onClick={() => void accion('/api/simulador/detener')}
          disabled={ocupado || !corriendo}
        >
          Detener
        </button>
        <button type="button" className="boton" onClick={() => void consultar()} disabled={ocupado}>
          Actualizar estado
        </button>
      </div>
    </section>
  )
}