import { pedir } from './cliente'
import type { Lectura, PaginaLecturas, SensorResumen } from './tipos'

/**
 * Lecturas del sensor (FEAT-0014 BR-003).
 *
 * El backend exige `desde` y `hasta` y pagina con keyset (`limit` máx 1000, default 100). Como una
 * ventana de 24 h con frecuencia de 30 s supera ese máximo, acá se **encadena el cursor** hasta
 * cubrir la ventana o llegar al tope configurado: nunca se trunca en silencio.
 */

export const LIMITE_POR_PAGINA = 1000

export type Ventana = { desde: Date; hasta: Date }

/** Ventana de las últimas N horas (FEAT-0014 BR-003, `VITE_SERIE_HORAS`). */
export function ventanaDeHoras(horas: number, ahora: Date = new Date()): Ventana {
  return { desde: new Date(ahora.getTime() - horas * 3600_000), hasta: ahora }
}

function lecturaValida(fila: unknown): fila is Lectura {
  return typeof fila === 'object' && fila !== null && typeof (fila as Lectura).timestamp === 'string'
}

/**
 * Histórico de la ventana, paginando por `cursor`.
 *
 * @param maxPuntos tope de seguridad (`VITE_SERIE_MAX_PUNTOS`): corta la paginación y se informa en
 *   la UI si se alcanzó, para no dar a entender que la serie está completa.
 */
export async function lecturasDeVentana(
  sensorId: string,
  ventana: Ventana,
  token: string,
  maxPuntos: number,
  signal?: AbortSignal,
): Promise<{ lecturas: Lectura[]; truncado: boolean }> {
  const lecturas: Lectura[] = []
  let cursor: string | null = null
  let truncado = false

  for (;;) {
    const params = new URLSearchParams({
      desde: ventana.desde.toISOString(),
      hasta: ventana.hasta.toISOString(),
      limit: String(LIMITE_POR_PAGINA),
    })
    if (cursor !== null) {
      params.set('cursor', cursor)
    }
    const pagina = await pedir<PaginaLecturas>(
      `/api/sensores/${encodeURIComponent(sensorId)}/lecturas?${params.toString()}`,
      { token, ...(signal ? { signal } : {}) },
    )
    const items = Array.isArray(pagina.items) ? pagina.items.filter(lecturaValida) : []
    lecturas.push(...items)

    if (lecturas.length >= maxPuntos) {
      truncado = pagina.nextCursor !== null && pagina.nextCursor !== undefined
      break
    }
    if (pagina.nextCursor === null || pagina.nextCursor === undefined || items.length === 0) {
      break
    }
    cursor = pagina.nextCursor
  }

  // El backend devuelve DESC por ts; la serie se dibuja y se muestra ASC.
  return {
    lecturas: lecturas
      .slice(0, maxPuntos)
      .sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp)),
    truncado,
  }
}

/** `GET /api/sensores/{id}` del registry: metadata (incluye estado y unidad) del detalle. */
export async function detalleSensor(id: string, token: string, signal?: AbortSignal): Promise<SensorResumen> {
  const crudo = await pedir<unknown>(`/api/sensores/${encodeURIComponent(id)}`, {
    token,
    ...(signal ? { signal } : {}),
  })
  return crudo as SensorResumen
}