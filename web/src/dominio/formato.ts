import { config } from '../config'

/**
 * Formateo único de números, horas y antigüedad (FEAT-0009 BR-008, FEAT-0014 BR-008).
 * Todo lo que se muestra pasa por acá: valores no finitos se muestran como "—" (nunca `NaN`).
 */

const SIN_DATO = '—'

const formatoNumero = new Intl.NumberFormat(config.locale, {
  maximumFractionDigits: 2,
  minimumFractionDigits: 0,
})

const formatoHora = new Intl.DateTimeFormat(config.locale, {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
})

const formatoFechaHora = new Intl.DateTimeFormat(config.locale, {
  day: '2-digit',
  month: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
})

export function numero(valor: number | null | undefined): string {
  return typeof valor === 'number' && Number.isFinite(valor) ? formatoNumero.format(valor) : SIN_DATO
}

/** Fecha ISO del backend → `Date` válida, o `null` si no es parseable. */
export function fecha(valor: string | null | undefined): Date | null {
  if (typeof valor !== 'string' || valor === '') {
    return null
  }
  const d = new Date(valor)
  return Number.isNaN(d.getTime()) ? null : d
}

export function hora(valor: string | null | undefined): string {
  const d = fecha(valor)
  return d === null ? SIN_DATO : formatoHora.format(d)
}

export function fechaHora(valor: string | null | undefined): string {
  const d = fecha(valor)
  return d === null ? SIN_DATO : formatoFechaHora.format(d)
}

/** Antigüedad en texto ("hace 12 s", "hace 3 min"). */
export function antiguedad(valor: string | null | undefined, ahora: Date = new Date()): string {
  const d = fecha(valor)
  if (d === null) {
    return SIN_DATO
  }
  const segundos = Math.max(0, Math.round((ahora.getTime() - d.getTime()) / 1000))
  if (segundos < 60) {
    return `hace ${segundos} s`
  }
  const minutos = Math.round(segundos / 60)
  if (minutos < 60) {
    return `hace ${minutos} min`
  }
  const horas = Math.round(minutos / 60)
  if (horas < 24) {
    return `hace ${horas} h`
  }
  return `hace ${Math.round(horas / 24)} d`
}

/** Trunca textos del backend para la presentación (A12: metadata no confiable). */
export function recortar(valor: string | null | undefined, maximo = 80): string {
  const t = (valor ?? '').trim()
  if (t === '') {
    return SIN_DATO
  }
  return t.length > maximo ? `${t.slice(0, maximo - 1)}…` : t
}

export const SIN_DATO_TEXTO = SIN_DATO