import { pedir } from './cliente'
import type { SensorAdmin } from './tipos'

/**
 * CRUD de sensores contra `sensor-registry` (FEAT-0016 BR-004/BR-008).
 *
 * Los valores de los enums salen del dominio del backend (`TipoSensor`, `UnidadMedida`,
 * `EstadoSensor`) y del contrato de cada endpoint: `POST` exige todos los campos, `PUT` acepta
 * **sólo** el subset de configuración (el backend rechaza campos fuera de él con
 * `SENSOR_INVALID_REQUEST`) y `DELETE` es baja lógica.
 */

export const TIPOS = ['RIO', 'ARROYO', 'BANADO'] as const
export const UNIDADES = ['METROS', 'CENTIMETROS'] as const
export const ESTADOS = ['ACTIVO', 'INACTIVO', 'MANTENIMIENTO'] as const

export type Tipo = (typeof TIPOS)[number]
export type Unidad = (typeof UNIDADES)[number]
export type Estado = (typeof ESTADOS)[number]

export type Rango = { min: number; max: number }

/** Body de `POST /api/sensores` (todos los campos obligatorios). */
export type AltaSensor = {
  codigo: string
  nombre: string
  tipo: Tipo
  latitud: number
  longitud: number
  unidadMedida: Unidad
  estado: Estado
  histeresis: number
  frecuenciaReporteSegundos: number
  rangoNormal: Rango
  rangoWarning: Rango
  rangoCritical: Rango
}

/** Body de `PUT /api/sensores/{id}`: **sólo** el subset de configuración. */
export type EdicionSensor = {
  estado: Estado
  histeresis: number
  frecuenciaReporteSegundos: number
  rangoNormal: Rango
  rangoWarning: Rango
  rangoCritical: Rango
}

export const LIMITE_PAGINA = 50

/** Listado keyset (`GET /api/sensores`), el mismo que usa el registry. */
export async function listarSensores(
  token: string,
  cursor: string | null,
  signal?: AbortSignal,
): Promise<{ items: SensorAdmin[]; nextCursor: string | null }> {
  const params = new URLSearchParams({ limit: String(LIMITE_PAGINA) })
  if (cursor !== null) {
    params.set('cursor', cursor)
  }
  const crudo = await pedir<{ items?: unknown; nextCursor?: unknown }>(
    `/api/sensores?${params.toString()}`,
    { token, ...(signal ? { signal } : {}) },
  )
  const items = Array.isArray(crudo.items)
    ? crudo.items.filter((fila): fila is SensorAdmin =>
        typeof fila === 'object' && fila !== null && typeof (fila as SensorAdmin).id === 'string')
    : []
  return { items, nextCursor: typeof crudo.nextCursor === 'string' ? crudo.nextCursor : null }
}

/** Detalle del sensor con su configuración completa (`GET /api/sensores/{id}`). */
export async function obtenerSensor(id: string, token: string, signal?: AbortSignal): Promise<SensorAdmin> {
  return pedir<SensorAdmin>(`/api/sensores/${encodeURIComponent(id)}`, {
    token,
    ...(signal ? { signal } : {}),
  })
}

export async function crearSensor(datos: AltaSensor, token: string): Promise<SensorAdmin> {
  return pedir<SensorAdmin>('/api/sensores', { metodo: 'POST', cuerpo: datos, token })
}

export async function editarSensor(id: string, datos: EdicionSensor, token: string): Promise<SensorAdmin> {
  return pedir<SensorAdmin>(`/api/sensores/${encodeURIComponent(id)}`, {
    metodo: 'PUT',
    cuerpo: datos,
    token,
  })
}

/** Baja **lógica**: el sensor pasa a `INACTIVO` (no se borra físicamente). */
export async function darDeBajaSensor(id: string, token: string): Promise<void> {
  await pedir<void>(`/api/sensores/${encodeURIComponent(id)}`, { metodo: 'DELETE', token })
}

/**
 * Validación de cliente (FEAT-0016 BR-003): coherente con el backend pero **no** lo reemplaza.
 * Devuelve un mapa `campo → mensaje` (vacío = válido).
 */
export function validarAlta(datos: Partial<AltaSensor>): Record<string, string> {
  const errores: Record<string, string> = {}
  if ((datos.codigo ?? '').trim() === '') {
    errores.codigo = 'El código es obligatorio.'
  }
  if ((datos.nombre ?? '').trim() === '') {
    errores.nombre = 'El nombre es obligatorio.'
  }
  if (datos.tipo === undefined) {
    errores.tipo = 'Elegí un tipo de sensor.'
  }
  if (datos.unidadMedida === undefined) {
    errores.unidadMedida = 'Elegí una unidad de medida.'
  }
  if (datos.estado === undefined) {
    errores.estado = 'Elegí un estado.'
  }
  if (!coordenadaValida(datos.latitud, -90, 90)) {
    errores.latitud = 'La latitud debe ser un número entre -90 y 90.'
  }
  if (!coordenadaValida(datos.longitud, -180, 180)) {
    errores.longitud = 'La longitud debe ser un número entre -180 y 180.'
  }
  Object.assign(errores, validarConfig(datos))
  return errores
}

/** Validación de la parte de configuración, compartida por alta y edición. */
export function validarConfig(datos: {
  histeresis?: number
  frecuenciaReporteSegundos?: number
  rangoNormal?: Rango
  rangoWarning?: Rango
  rangoCritical?: Rango
}): Record<string, string> {
  const errores: Record<string, string> = {}
  if (typeof datos.histeresis !== 'number' || !Number.isFinite(datos.histeresis) || datos.histeresis < 0) {
    errores.histeresis = 'La histéresis debe ser un número mayor o igual a 0.'
  }
  if (
    typeof datos.frecuenciaReporteSegundos !== 'number' ||
    !Number.isInteger(datos.frecuenciaReporteSegundos) ||
    datos.frecuenciaReporteSegundos < 1
  ) {
    errores.frecuenciaReporteSegundos = 'La frecuencia debe ser un entero mayor o igual a 1.'
  }
  for (const [campo, rango] of Object.entries({
    rangoNormal: datos.rangoNormal,
    rangoWarning: datos.rangoWarning,
    rangoCritical: datos.rangoCritical,
  })) {
    if (rango === undefined || !Number.isFinite(rango.min) || !Number.isFinite(rango.max)) {
      errores[campo] = 'Completá el mínimo y el máximo.'
    } else if (rango.min > rango.max) {
      errores[campo] = 'El mínimo no puede ser mayor que el máximo.'
    }
  }
  if (
    errores.rangoNormal === undefined &&
    errores.rangoWarning === undefined &&
    errores.rangoCritical === undefined &&
    datos.rangoNormal !== undefined &&
    datos.rangoWarning !== undefined &&
    datos.rangoCritical !== undefined
  ) {
    // Coherencia de bandas: normal ⊂ warning ⊂ critical (el backend lo valida igual).
    const contiene = (exterior: Rango, interior: Rango): boolean =>
      exterior.min <= interior.min && exterior.max >= interior.max
    if (!contiene(datos.rangoWarning, datos.rangoNormal)) {
      errores.rangoWarning = 'El rango de advertencia debe contener al normal.'
    }
    if (!contiene(datos.rangoCritical, datos.rangoWarning)) {
      errores.rangoCritical = 'El rango crítico debe contener al de advertencia.'
    }
  }
  return errores
}

function coordenadaValida(valor: number | undefined, min: number, max: number): boolean {
  return typeof valor === 'number' && Number.isFinite(valor) && valor >= min && valor <= max
}

/**
 * Mapeo `code` → campo del formulario (FEAT-0016 BR-002): sólo cuando el backend es inequívoco. Un
 * `code` sin campo asociado (`null`) se muestra en el resumen del formulario, tal cual.
 */
export function campoDeCode(code: string, mensajeDelBackend: string): string | null {
  if (code === 'SENSOR_CODE_DUPLICATED') {
    return 'codigo'
  }
  // El backend describe la violación en el mensaje; se usa para ubicarla en el campo correcto.
  const texto = mensajeDelBackend.toLowerCase()
  for (const campo of [
    'latitud',
    'longitud',
    'histeresis',
    'frecuenciareportesegundos',
    'rangonormal',
    'rangowarning',
    'rangocritical',
    'nombre',
    'codigo',
    'tipo',
    'unidadmedida',
    'estado',
  ]) {
    if (texto.includes(campo)) {
      return campo
    }
  }
  return null
}