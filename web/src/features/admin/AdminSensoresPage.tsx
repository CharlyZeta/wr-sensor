import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { ErrorDeApi } from '../../api/errores'
import type { SensorAdmin } from '../../api/tipos'
import { darDeBajaSensor, listarSensores } from '../../api/sensores'
import { Aviso, EstadoCargando, EstadoError, EstadoVacio } from '../../componentes/Estados'
import { recortar } from '../../dominio/formato'
import { estadoOperativo, estilo, estadoVisual } from '../../dominio/severidad'
import { useSesion } from '../../sesion/SesionContext'
import { PanelDemo } from '../demo/PanelDemo'

/**
 * Listado administrativo (FEAT-0016 Main Flow 1, BR-001/BR-005/BR-008, AF-03/AF-04/AF-05).
 *
 * - Sólo `ADMIN`: a `VIEWER` no se le ofrecen acciones de escritura (AF-05). El backend sigue siendo
 *   la autoridad (el guard es UX, no control).
 * - Paginación **keyset** del backend (nunca traer todo) y sin N+1.
 * - La baja es **lógica** y con confirmación explícita; la UI dice "queda INACTIVO", no "se elimina".
 * - Un sensor `INACTIVO` se muestra como tal y no ofrece edición (el backend la rechazaría), con la
 *   explicación de cómo reactivarlo.
 */
export function AdminSensoresPage(): React.JSX.Element {
  const { sesion, esAdmin } = useSesion()
  const navegar = useNavigate()
  const ubicacion = useLocation()
  const token = sesion?.token ?? ''

  const [sensores, setSensores] = useState<SensorAdmin[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [hayMas, setHayMas] = useState(false)
  const [cargando, setCargando] = useState(true)
  const [error, setError] = useState<ErrorDeApi | null>(null)
  // El alta/edición vuelve con el resultado en el estado de navegación (una sola vez).
  const [aviso, setAviso] = useState<string | null>(
    ((ubicacion.state ?? null) as { aviso?: string } | null)?.aviso ?? null,
  )
  const [aBorrar, setABorrar] = useState<SensorAdmin | null>(null)
  const [borrando, setBorrando] = useState(false)

  const cargar = useCallback(
    async (desde: string | null) => {
      setCargando(true)
      try {
        const pagina = await listarSensores(token, desde)
        setSensores((previos) => (desde === null ? pagina.items : [...previos, ...pagina.items]))
        setCursor(pagina.nextCursor)
        setHayMas(pagina.nextCursor !== null)
        setError(null)
      } catch (e) {
        if (e instanceof ErrorDeApi) {
          setError(e)
          return
        }
        setError(e as ErrorDeApi)
      } finally {
        setCargando(false)
      }
    },
    [token],
  )

  useEffect(() => {
    const inicial = window.setTimeout(() => void cargar(null), 0)
    return () => window.clearTimeout(inicial)
  }, [cargar])

  async function confirmarBaja(): Promise<void> {
    if (aBorrar === null) {
      return
    }
    setBorrando(true)
    try {
      await darDeBajaSensor(aBorrar.id, token)
      setAviso(`El sensor ${aBorrar.codigo} quedó INACTIVO (baja lógica, no se elimina).`)
      setABorrar(null)
      await cargar(null)
    } catch (e) {
      setError(e instanceof ErrorDeApi ? e : null)
      setABorrar(null)
    } finally {
      setBorrando(false)
    }
  }

  if (!esAdmin) {
    return (
      <EstadoVacio
        titulo="Sección sólo para administradores"
        detalle="Tu usuario tiene rol de sólo lectura: no puede crear, editar ni dar de baja sensores."
      >
        <Link className="boton" to="/mapa">
          Volver al mapa
        </Link>
      </EstadoVacio>
    )
  }

  return (
    <section className="pantalla-admin" aria-labelledby="titulo-admin">
      <header className="barra-titulo">
        <h1 id="titulo-admin">Sensores</h1>
        <button type="button" className="boton boton-primario" onClick={() => navegar('/sensores/nuevo')}>
          Nuevo sensor
        </button>
        <p className="estado-detalle">{sensores.length} sensores cargados</p>
      </header>

      {aviso !== null ? (
        <Aviso onCerrar={() => setAviso(null)}>{aviso}</Aviso>
      ) : null}
      {error !== null ? <EstadoError error={error} onReintentar={() => void cargar(null)} /> : null}

      {cargando && sensores.length === 0 ? (
        <EstadoCargando texto="Cargando sensores…" />
      ) : sensores.length === 0 && error === null ? (
        <EstadoVacio
          titulo="No hay sensores en el catálogo"
          detalle="Creá el primero con «Nuevo sensor»; aparecerá también en el mapa."
        />
      ) : (
        <>
          <table className="tabla-admin">
            <caption className="sr-solo">Sensores del catálogo, con sus acciones</caption>
            <thead>
              <tr>
                <th scope="col">Código</th>
                <th scope="col">Nombre</th>
                <th scope="col">Tipo</th>
                <th scope="col">Estado</th>
                <th scope="col">Unidad</th>
                <th scope="col">Acciones</th>
              </tr>
            </thead>
            <tbody>
              {sensores.map((sensor) => {
                const operativo = estadoOperativo(sensor.estado)
                const e = estilo(estadoVisual(null))
                return (
                  <tr key={sensor.id}>
                    <td>{recortar(sensor.codigo, 30)}</td>
                    <td>{recortar(sensor.nombre, 60)}</td>
                    <td>{recortar(sensor.tipo, 20)}</td>
                    <td>{recortar(sensor.estado, 20)}</td>
                    <td>{recortar(sensor.unidadMedida, 20)}</td>
                    <td className="celda-acciones">
                      {operativo ? (
                        <>
                          <Link className="boton boton-chico" to={`/sensores/${sensor.id}/editar`}>
                            Editar
                          </Link>
                          <button
                            type="button"
                            className="boton boton-chico"
                            onClick={() => setABorrar(sensor)}
                          >
                            Dar de baja
                          </button>
                        </>
                      ) : (
                        <span className={`estado-detalle ${e.clase}`}>
                          {recortar(sensor.estado, 20)}: para reactivarlo, editá su estado a ACTIVO (no se
                          ofrece edición directa acá)
                        </span>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>

          {hayMas ? (
            <p className="acciones-tabla">
              <button
                type="button"
                className="boton"
                onClick={() => void cargar(cursor)}
                disabled={cargando}
              >
                {cargando ? 'Cargando…' : 'Cargar más'}
              </button>
            </p>
          ) : null}
        </>
      )}

      {aBorrar !== null ? (
        <div className="dialogo-fondo" role="dialog" aria-modal="true" aria-labelledby="titulo-baja">
          <div className="dialogo">
            <h2 id="titulo-baja">Dar de baja {recortar(aBorrar.codigo, 30)}</h2>
            <p>
              El sensor queda <strong>INACTIVO</strong> (baja lógica): no se elimina de la base y sus
              lecturas históricas se conservan. Podés reactivarlo editando su estado a ACTIVO.
            </p>
            <div className="acciones-formulario">
              <button
                type="button"
                className="boton boton-primario"
                onClick={() => void confirmarBaja()}
                disabled={borrando}
              >
                {borrando ? 'Procesando…' : 'Confirmar baja'}
              </button>
              <button type="button" className="boton" onClick={() => setABorrar(null)} disabled={borrando}>
                Cancelar
              </button>
            </div>
          </div>
        </div>
      ) : null}

      <PanelDemo />
    </section>
  )
}