import { useMemo, useState } from 'react'
import type { AltaSensor, EdicionSensor, Estado, Rango, Tipo, Unidad } from '../../api/sensores'
import { ESTADOS, TIPOS, UNIDADES, validarAlta, validarConfig } from '../../api/sensores'
import { detalleTecnico, mensajeDe } from '../../api/errores'
import type { ErrorDeApi } from '../../api/errores'

/**
 * Formulario de sensor (FEAT-0016 Main Flow 2/4, BR-002/BR-003/BR-009).
 *
 * - **Validación en cliente** coherente con el backend (campos obligatorios, rangos coherentes
 *   `normal ⊂ warning ⊂ critical`, histéresis ≥ 0, frecuencia ≥ 1) que **no reemplaza** la del
 *   servidor: los errores del backend siempre se muestran (por `code`, en el campo cuando se puede
 *   ubicarlos) y nunca se pierde lo cargado.
 * - Los campos se editan como texto y se convierten a número al enviar, para no perder lo que el
 *   usuario escribió mientras el valor todavía no es numérico.
 * - Modo alta (todos los campos) y modo edición (sólo el subset de configuración, porque el `PUT` del
 *   backend rechaza cualquier otro campo).
 */

export type ValoresFormulario = {
  codigo: string
  nombre: string
  tipo: Tipo
  latitud: string
  longitud: string
  unidadMedida: Unidad
  estado: Estado
  histeresis: string
  frecuenciaReporteSegundos: string
  rangoNormalMin: string
  rangoNormalMax: string
  rangoWarningMin: string
  rangoWarningMax: string
  rangoCriticalMin: string
  rangoCriticalMax: string
}

export const VALORES_INICIALES: ValoresFormulario = {
  codigo: '',
  nombre: '',
  tipo: 'RIO',
  latitud: '',
  longitud: '',
  unidadMedida: 'METROS',
  estado: 'ACTIVO',
  histeresis: '0',
  frecuenciaReporteSegundos: '30',
  rangoNormalMin: '',
  rangoNormalMax: '',
  rangoWarningMin: '',
  rangoWarningMax: '',
  rangoCriticalMin: '',
  rangoCriticalMax: '',
}

function aNumero(valor: string): number {
  return Number.parseFloat(valor.replace(',', '.'))
}

function rango(min: string, max: string): Rango {
  return { min: aNumero(min), max: aNumero(max) }
}

/** Valida y convierte a los DTOs del backend. Devuelve `null` si hay errores locales. */
export function construirAlta(valores: ValoresFormulario): { datos?: AltaSensor; errores: Record<string, string> } {
  const candidato: Partial<AltaSensor> = {
    codigo: valores.codigo.trim(),
    nombre: valores.nombre.trim(),
    tipo: valores.tipo,
    latitud: aNumero(valores.latitud),
    longitud: aNumero(valores.longitud),
    unidadMedida: valores.unidadMedida,
    estado: valores.estado,
    histeresis: aNumero(valores.histeresis),
    frecuenciaReporteSegundos: Number.parseInt(valores.frecuenciaReporteSegundos, 10),
    rangoNormal: rango(valores.rangoNormalMin, valores.rangoNormalMax),
    rangoWarning: rango(valores.rangoWarningMin, valores.rangoWarningMax),
    rangoCritical: rango(valores.rangoCriticalMin, valores.rangoCriticalMax),
  }
  const errores = validarAlta(candidato)
  // Las coordenadas vacías llegan como NaN: `validarAlta` ya las reporta como inválidas.
  return Object.keys(errores).length > 0 ? { errores } : { datos: candidato as AltaSensor, errores }
}

/** En modo edición sólo se envía el subset de configuración que acepta el `PUT`. */
export function construirEdicion(
  valores: ValoresFormulario,
): { datos?: EdicionSensor; errores: Record<string, string> } {
  const candidato: EdicionSensor = {
    estado: valores.estado,
    histeresis: aNumero(valores.histeresis),
    frecuenciaReporteSegundos: Number.parseInt(valores.frecuenciaReporteSegundos, 10),
    rangoNormal: rango(valores.rangoNormalMin, valores.rangoNormalMax),
    rangoWarning: rango(valores.rangoWarningMin, valores.rangoWarningMax),
    rangoCritical: rango(valores.rangoCriticalMin, valores.rangoCriticalMax),
  }
  const errores = validarConfig(candidato)
  return Object.keys(errores).length > 0 ? { errores } : { datos: candidato, errores }
}

export function FormularioSensor({
  modo,
  valores,
  onCambio,
  erroresServidor,
  errorGeneral,
  enviando,
  onEnviar,
  onCancelar,
  codigoInmutable,
}: {
  modo: 'alta' | 'edicion'
  valores: ValoresFormulario
  onCambio: (valores: ValoresFormulario) => void
  /** Errores del backend ya mapeados a campo (`code` → campo). */
  erroresServidor: Record<string, string>
  /** Error del backend sin campo asociado (se muestra tal cual, por `code`). */
  errorGeneral: ErrorDeApi | null
  enviando: boolean
  onEnviar: () => void
  onCancelar: () => void
  /** Código del sensor en edición (inmutable: el `PUT` no lo acepta). */
  codigoInmutable?: string
}): React.JSX.Element {
  const [erroresLocales, setErroresLocales] = useState<Record<string, string>>({})
  const [intentado, setIntentado] = useState(false)

  const errorDe = useMemo(
    () => (campo: string): string | undefined => erroresServidor[campo] ?? erroresLocales[campo],
    [erroresServidor, erroresLocales],
  )

  function enviar(evento: React.FormEvent<HTMLFormElement>): void {
    evento.preventDefault()
    setIntentado(true)
    const { errores } = modo === 'alta' ? construirAlta(valores) : construirEdicion(valores)
    setErroresLocales(errores)
    if (Object.keys(errores).length === 0) {
      onEnviar()
    }
  }

  /** Marca `aria-invalid` sólo cuando ya se intentó enviar o el servidor reportó el campo. */
  const invalido = (campo: string): boolean => errorDe(campo) !== undefined && (intentado || erroresServidor[campo] !== undefined)

  const campoTexto = (
    campo: keyof ValoresFormulario,
    etiqueta: string,
    extra: { tipo?: string; ayuda?: string; soloAlta?: boolean } = {},
  ): React.JSX.Element => {
    const deshabilitado = modo === 'edicion' && extra.soloAlta === true
    return (
      <label className="campo">
        <span>{etiqueta}</span>
        <input
          type={extra.tipo ?? 'text'}
          name={campo}
          // `aria-label` con el nombre exacto del campo: el `<small>` de ayuda no contamina el
          // nombre accesible (y los tests pueden buscar por etiqueta estable).
          aria-label={etiqueta}
          value={valores[campo]}
          onChange={(e) => onCambio({ ...valores, [campo]: e.target.value })}
          aria-invalid={invalido(campo)}
          aria-describedby={errorDe(campo) !== undefined ? `${campo}-error` : undefined}
          disabled={deshabilitado}
        />
        {extra.ayuda !== undefined ? <small className="estado-detalle">{extra.ayuda}</small> : null}
        {errorDe(campo) !== undefined ? (
          <small className="error-campo" id={`${campo}-error`} role="alert">
            {errorDe(campo)}
          </small>
        ) : null}
      </label>
    )
  }

  const campoSelect = (
    campo: 'tipo' | 'unidadMedida' | 'estado',
    etiqueta: string,
    opciones: readonly string[],
    soloAlta = false,
  ): React.JSX.Element => (
    <label className="campo">
      <span>{etiqueta}</span>
      <select
        name={campo}
        aria-label={etiqueta}
        value={valores[campo]}
        onChange={(e) => onCambio({ ...valores, [campo]: e.target.value as Tipo & Unidad & Estado })}
        aria-invalid={invalido(campo)}
        disabled={modo === 'edicion' && soloAlta}
      >
        {opciones.map((opcion) => (
          <option key={opcion} value={opcion}>
            {opcion}
          </option>
        ))}
      </select>
      {errorDe(campo) !== undefined ? (
        <small className="error-campo" role="alert">
          {errorDe(campo)}
        </small>
      ) : null}
    </label>
  )

  const filaRango = (prefijo: 'rangoNormal' | 'rangoWarning' | 'rangoCritical', etiqueta: string): React.JSX.Element => (
    <fieldset className="grupo-rango">
      <legend>{etiqueta}</legend>
      {campoTexto(`${prefijo}Min` as keyof ValoresFormulario, 'Mínimo')}
      {campoTexto(`${prefijo}Max` as keyof ValoresFormulario, 'Máximo')}
      {errorDe(prefijo) !== undefined ? (
        <small className="error-campo" role="alert">
          {errorDe(prefijo)}
        </small>
      ) : null}
    </fieldset>
  )

  return (
    <form className="formulario-sensor" onSubmit={enviar} aria-labelledby="titulo-formulario">
      {errorGeneral !== null ? (
        <div className="estado estado-error" role="alert">
          <p className="estado-titulo">{mensajeDe(errorGeneral)}</p>
          <p className="estado-detalle tecnico">{detalleTecnico(errorGeneral)}</p>
        </div>
      ) : null}

      <div className="grilla-formulario">
        {modo === 'alta' ? campoTexto('codigo', 'Código') : null}
        {modo === 'alta' ? campoTexto('nombre', 'Nombre') : null}
        {codigoInmutable !== undefined ? (
          <p className="estado-detalle">Código: {codigoInmutable} (no editable)</p>
        ) : null}
        {campoSelect('tipo', 'Tipo', TIPOS, true)}
        {modo === 'alta' ? campoTexto('latitud', 'Latitud', { ayuda: 'Entre -90 y 90' }) : null}
        {modo === 'alta' ? campoTexto('longitud', 'Longitud', { ayuda: 'Entre -180 y 180' }) : null}
        {campoSelect('unidadMedida', 'Unidad', UNIDADES, true)}
        {campoSelect('estado', 'Estado', ESTADOS)}
        {campoTexto('histeresis', 'Histéresis', { ayuda: 'Mayor o igual a 0' })}
        {campoTexto('frecuenciaReporteSegundos', 'Frecuencia de reporte (s)', { ayuda: 'Entero ≥ 1' })}

        {filaRango('rangoNormal', 'Rango normal')}
        {filaRango('rangoWarning', 'Rango de advertencia')}
        {filaRango('rangoCritical', 'Rango crítico')}
      </div>

      <div className="acciones-formulario">
        <button type="submit" className="boton boton-primario" disabled={enviando}>
          {enviando ? 'Guardando…' : modo === 'alta' ? 'Crear sensor' : 'Guardar cambios'}
        </button>
        <button type="button" className="boton" onClick={onCancelar} disabled={enviando}>
          Cancelar
        </button>
      </div>
    </form>
  )
}

/** Carga los valores del formulario a partir de un sensor existente (modo edición). */
export function valoresDesdeSensor(sensor: {
  codigo: string
  nombre: string
  tipo: string
  latitud: number
  longitud: number
  unidadMedida: string
  estado: string
  histeresis: number
  frecuenciaReporteSegundos: number
  rangoNormal: Rango
  rangoWarning: Rango
  rangoCritical: Rango
}): ValoresFormulario {
  const comoTipo = TIPOS.includes(sensor.tipo as Tipo) ? (sensor.tipo as Tipo) : 'RIO'
  const comoUnidad = UNIDADES.includes(sensor.unidadMedida as Unidad)
    ? (sensor.unidadMedida as Unidad)
    : 'METROS'
  const comoEstado = ESTADOS.includes(sensor.estado as Estado) ? (sensor.estado as Estado) : 'ACTIVO'
  return {
    codigo: sensor.codigo,
    nombre: sensor.nombre,
    tipo: comoTipo,
    latitud: String(sensor.latitud),
    longitud: String(sensor.longitud),
    unidadMedida: comoUnidad,
    estado: comoEstado,
    histeresis: String(sensor.histeresis),
    frecuenciaReporteSegundos: String(sensor.frecuenciaReporteSegundos),
    rangoNormalMin: String(sensor.rangoNormal.min),
    rangoNormalMax: String(sensor.rangoNormal.max),
    rangoWarningMin: String(sensor.rangoWarning.min),
    rangoWarningMax: String(sensor.rangoWarning.max),
    rangoCriticalMin: String(sensor.rangoCritical.min),
    rangoCriticalMax: String(sensor.rangoCritical.max),
  }
}