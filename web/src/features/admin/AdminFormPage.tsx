import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ErrorDeApi } from '../../api/errores'
import { TIPOS, campoDeCode, crearSensor, editarSensor, obtenerSensor } from '../../api/sensores'
import type { AltaSensor, EdicionSensor } from '../../api/sensores'
import type { SensorAdmin } from '../../api/tipos'
import { EstadoCargando, EstadoVacio } from '../../componentes/Estados'
import { esUuid } from '../../dominio/severidad'
import { useSesion } from '../../sesion/SesionContext'
import {
  FormularioSensor,
  VALORES_INICIALES,
  construirAlta,
  construirEdicion,
  valoresDesdeSensor,
} from './FormularioSensor'
import type { ValoresFormulario } from './FormularioSensor'

/**
 * Alta y edición de sensores (FEAT-0016 Main Flow 2/4, BR-002/BR-003/BR-004, AF-01/AF-02/AF-07/AF-08).
 *
 * - En **alta** se envían todos los campos; en **edición** sólo el subset de configuración (el `PUT`
 *   del backend rechaza cualquier otro campo con `SENSOR_INVALID_REQUEST`).
 * - Los errores del backend se muestran **por `code`** y se ubican en el campo cuando el código o el
 *   mensaje lo permiten (`SENSOR_CODE_DUPLICATED` → `codigo`); lo cargado nunca se pierde.
 * - Ante `429` se avisa, se conservan los datos y **no** se reenvía solo (una escritura no se
 *   reintenta automáticamente: podría duplicar el alta).
 * - Si el token vence a mitad del envío, se cierra la sesión y **no** se envía dos veces.
 */
export function AdminFormPage({ modo }: { modo: 'alta' | 'edicion' }): React.JSX.Element {
  const { id } = useParams<{ id: string }>()
  const { sesion, esAdmin, cerrarSesion } = useSesion()
  const navegar = useNavigate()
  const token = sesion?.token ?? ''

  const [valores, setValores] = useState<ValoresFormulario>(VALORES_INICIALES)
  const [sensor, setSensor] = useState<SensorAdmin | null>(null)
  const [cargando, setCargando] = useState(modo === 'edicion')
  const [enviando, setEnviando] = useState(false)
  const [erroresServidor, setErroresServidor] = useState<Record<string, string>>({})
  const [errorGeneral, setErrorGeneral] = useState<ErrorDeApi | null>(null)
  const [aviso, setAviso] = useState<string | null>(null)

  const idValido = id !== undefined && esUuid(id)

  const cargar = useCallback(async () => {
    if (modo !== 'edicion' || !idValido || id === undefined) {
      return
    }
    setCargando(true)
    try {
      const datos = await obtenerSensor(id, token)
      setSensor(datos as SensorAdmin)
      setValores(valoresDesdeSensor(datos as SensorAdmin))
      setErrorGeneral(null)
    } catch (e) {
      if (e instanceof ErrorDeApi) {
        if (e.esNoAutenticado) {
          cerrarSesion('expirada')
          return
        }
        setErrorGeneral(e)
      }
    } finally {
      setCargando(false)
    }
  }, [modo, id, idValido, token, cerrarSesion])

  useEffect(() => {
    const inicial = window.setTimeout(() => void cargar(), 0)
    return () => window.clearTimeout(inicial)
  }, [cargar])

  if (!esAdmin) {
    return (
      <EstadoVacio
        titulo="Sección sólo para administradores"
        detalle="Tu usuario tiene rol de sólo lectura."
      >
        <Link className="boton" to="/mapa">
          Volver al mapa
        </Link>
      </EstadoVacio>
    )
  }

  if (modo === 'edicion' && !idValido) {
    return (
      <EstadoVacio titulo="Identificador inválido" detalle="Volvé al listado y elegí un sensor.">
        <Link className="boton" to="/admin/sensores">
          Ir al listado
        </Link>
      </EstadoVacio>
    )
  }

  async function enviar(): Promise<void> {
    if (enviando) {
      return
    }
    const construido = modo === 'alta' ? construirAlta(valores) : construirEdicion(valores)
    if (construido.datos === undefined) {
      return   // el formulario ya muestra los errores locales
    }
    const datos = construido.datos
    setEnviando(true)
    setErroresServidor({})
    setErrorGeneral(null)
    setAviso(null)
    try {
      if (modo === 'alta') {
        const creado = await crearSensor(datos as AltaSensor, token)
        // El aviso viaja por el estado de navegación: el listado lo muestra al llegar.
        navegar('/admin/sensores', {
          replace: true,
          state: { aviso: `Sensor ${creado.codigo} creado. Ya aparece en el mapa.` },
        })
      } else if (id !== undefined) {
        await editarSensor(id, datos as EdicionSensor, token)
        navegar('/admin/sensores', {
          replace: true,
          state: { aviso: 'Cambios guardados.' },
        })
      }
    } catch (e) {
      if (!(e instanceof ErrorDeApi)) {
        setErrorGeneral(e as ErrorDeApi)
        return
      }
      if (e.esNoAutenticado) {
        cerrarSesion('expirada')
        return
      }
      if (e.esCupoAgotado) {
        // BR-006: no se reintenta solo; se avisa y se conservan los datos cargados.
        setAviso(
          `Se alcanzó el límite de pedidos del servidor${
            e.retryAfterSegundos !== null ? ` (reintentá en ${e.retryAfterSegundos} s)` : ''
          }. Los datos del formulario se conservan: la operación NO se reenvió automáticamente.`,
        )
        return
      }
      const campo = campoDeCode(e.code, e.mensajeBackend)
      if (campo !== null) {
        setErroresServidor({ [campo]: `${e.code}: ${e.mensajeBackend}` })
      } else {
        setErrorGeneral(e)
      }
    } finally {
      setEnviando(false)
    }
  }

  if (cargando) {
    return <EstadoCargando texto="Cargando el sensor…" />
  }

  return (
    <section className="pantalla-admin" aria-labelledby="titulo-formulario">
      <header className="barra-titulo">
        <h1 id="titulo-formulario">{modo === 'alta' ? 'Nuevo sensor' : 'Editar sensor'}</h1>
        <p className="estado-detalle">
          {modo === 'alta'
            ? 'Todos los campos son obligatorios; los rangos deben ser coherentes (normal ⊂ advertencia ⊂ crítico).'
            : 'Sólo se puede editar la configuración: el código, el nombre, el tipo y la ubicación son inmutables por contrato.'}
        </p>
      </header>

      {aviso !== null ? <div className="aviso aviso-alerta" role="status" aria-live="polite">{aviso}</div> : null}
      {errorGeneral === null && sensor !== null && sensor.estado === 'INACTIVO' ? (
        <div className="aviso" role="status">
          El sensor está INACTIVO. Cambiá su estado a ACTIVO para reactivarlo.
        </div>
      ) : null}

      <FormularioSensor
        modo={modo}
        valores={valores}
        onCambio={(nuevos) => {
          setValores(nuevos)
          setErroresServidor({})
        }}
        erroresServidor={erroresServidor}
        errorGeneral={errorGeneral}
        enviando={enviando}
        onEnviar={() => void enviar()}
        onCancelar={() => navegar('/admin/sensores')}
        {...(sensor !== null ? { codigoInmutable: sensor.codigo } : {})}
      />

      <p className="estado-detalle">
        Tipos admitidos por el backend: {TIPOS.join(', ')}.
      </p>
    </section>
  )
}