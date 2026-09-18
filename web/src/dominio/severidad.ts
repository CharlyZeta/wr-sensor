import type { Calidad, Severidad, SensorResumen, UltimaLectura } from '../api/tipos'

/**
 * **Fuente única de verdad de la severidad** (FEAT-0009 BR-007): color, etiqueta, ícono y orden.
 *
 * Se usa el mismo catálogo en el mapa, la lista, la leyenda y (más adelante) el feed de alertas, así
 * que un valor inesperado del backend **nunca** se interpola en una clase CSS o en un color: cae a
 * `SIN_DATOS`/`DESCONOCIDA` (ver A13 de la revisión de seguridad).
 */
export type EstadoVisual = 'NORMAL' | 'WARNING' | 'CRITICAL' | 'SIN_DATOS' | 'INVALIDO'

export type EstiloSeveridad = {
  etiqueta: string
  /** Color de la paleta accesible (contraste AA sobre fondo claro). */
  color: string
  /** Clase CSS propia del catálogo (nunca construida con datos). */
  clase: string
  /** Símbolo textual: la severidad no se comunica sólo por color (accesibilidad). */
  simbolo: string
}

const CATALOGO: Record<EstadoVisual, EstiloSeveridad> = {
  NORMAL: { etiqueta: 'Normal', color: '#1a7f37', clase: 'sev-normal', simbolo: '●' },
  WARNING: { etiqueta: 'Advertencia', color: '#b26a00', clase: 'sev-warning', simbolo: '▲' },
  CRITICAL: { etiqueta: 'Crítico', color: '#b3261e', clase: 'sev-critical', simbolo: '■' },
  SIN_DATOS: { etiqueta: 'Sin datos', color: '#5f6368', clase: 'sev-sin-datos', simbolo: '○' },
  INVALIDO: { etiqueta: 'Dato inválido', color: '#6a1b9a', clase: 'sev-invalido', simbolo: '✕' },
}

export function estilo(estado: EstadoVisual): EstiloSeveridad {
  return CATALOGO[estado]
}

/** Severidad del backend → estado visual, contemplando `ERROR_SENSOR` y valores desconocidos. */
export function estadoVisual(ultima: UltimaLectura | null): EstadoVisual {
  if (ultima === null) {
    return 'SIN_DATOS'
  }
  if (esCalidadInvalida(ultima.calidad)) {
    return 'INVALIDO'
  }
  return normalizarSeveridad(ultima.severidad)
}

/** `ERROR_SENSOR` (FIX-0004): la lectura se persiste pero no es un valor válido. */
export function esCalidadInvalida(calidad: Calidad | null | undefined): boolean {
  return calidad === 'ERROR_SENSOR'
}

/** Cualquier severidad fuera del catálogo se trata como `SIN_DATOS` (nunca se inventa un estilo). */
export function normalizarSeveridad(severidad: string | null | undefined): EstadoVisual {
  switch (severidad) {
    case 'NORMAL':
      return 'NORMAL'
    case 'WARNING':
      return 'WARNING'
    case 'CRITICAL':
      return 'CRITICAL'
    default:
      return 'SIN_DATOS'
  }
}

/** Orden de severidad para ordenar listas (más grave primero). */
export function peso(estado: EstadoVisual): number {
  switch (estado) {
    case 'CRITICAL':
      return 0
    case 'INVALIDO':
      return 1
    case 'WARNING':
      return 2
    case 'SIN_DATOS':
      return 3
    case 'NORMAL':
      return 4
  }
}

/** ¿El sensor está activo? Cualquier otro estado (INACTIVO/MANTENIMIENTO) se marca. */
export function estadoOperativo(estado: string | null | undefined): boolean {
  return estado === 'ACTIVO' || estado === 'ACTIVE'
}

export type PuntoMapa = {
  sensor: SensorResumen
  estado: EstadoVisual
  latitud: number
  longitud: number
}

/**
 * Sensores que se pueden dibujar: descarta los que no tienen coordenadas válidas (A12/A13) para no
 * romper Leaflet con `NaN`. Los descartados se informan aparte en la UI.
 */
export function puntosValidos(sensores: SensorResumen[]): {
  puntos: PuntoMapa[]
  sinCoordenadas: SensorResumen[]
} {
  const puntos: PuntoMapa[] = []
  const sinCoordenadas: SensorResumen[] = []
  for (const sensor of sensores) {
    if (coordenadaValida(sensor.latitud, sensor.longitud)) {
      puntos.push({
        sensor,
        estado: estadoVisual(sensor.ultimaLectura),
        latitud: sensor.latitud as number,
        longitud: sensor.longitud as number,
      })
    } else {
      sinCoordenadas.push(sensor)
    }
  }
  return { puntos, sinCoordenadas }
}

export function coordenadaValida(
  latitud: number | null | undefined,
  longitud: number | null | undefined,
): boolean {
  return (
    typeof latitud === 'number' &&
    typeof longitud === 'number' &&
    Number.isFinite(latitud) &&
    Number.isFinite(longitud) &&
    latitud >= -90 &&
    latitud <= 90 &&
    longitud >= -180 &&
    longitud <= 180
  )
}

/** Etiqueta legible del sensor: nunca se inventa un nombre si el backend no lo mandó. */
export function etiquetaSensor(sensor: SensorResumen): string {
  const nombre = sensor.nombre?.trim()
  if (nombre !== undefined && nombre !== '') {
    return nombre
  }
  const codigo = sensor.codigo?.trim()
  if (codigo !== undefined && codigo !== '') {
    return codigo
  }
  return sensor.id
}

/** Valida un id UUID antes de usarlo en una ruta o URL (A12). */
export function esUuid(valor: string): boolean {
  return /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(valor)
}

export type { Severidad }