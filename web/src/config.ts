/**
 * Configuración del SPA leída de `import.meta.env` (FEAT-0009 BR-003/BR-005/BR-006).
 *
 * Nada de esto puede quedar hardcodeado en los componentes: son valores de build con default
 * documentado en `.env.example` y en el RUNBOOK. `VITE_API_BASE` vacío significa **mismo origen**,
 * que es como corre en producción (el gateway sirve el SPA) y en desarrollo (el proxy de Vite manda
 * `/api` y `/ws` al gateway, así que el navegador nunca ve otro origen).
 */

function texto(valor: string | undefined, porDefecto: string): string {
  const v = valor?.trim()
  return v === undefined || v === '' ? porDefecto : v
}

function entero(valor: string | undefined, porDefecto: number, minimo: number): number {
  const n = Number.parseInt(valor ?? '', 10)
  return Number.isFinite(n) && n >= minimo ? n : porDefecto
}

export const config = {
  /** Base de la API (vacío = mismo origen). */
  apiBase: (import.meta.env.VITE_API_BASE ?? '').trim().replace(/\/$/, ''),
  /** Período de refresco del resumen (ms). */
  resumenRefrescoMs: entero(import.meta.env.VITE_RESUMEN_REFRESCO_MS, 30_000, 1_000),
  /** Plantilla de tiles y atribución del mapa. */
  tilesUrl: texto(
    import.meta.env.VITE_TILES_URL,
    'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
  ),
  tilesAtribucion: texto(import.meta.env.VITE_TILES_ATRIBUCION, '© OpenStreetMap'),
  /** Locale para formateo (números y fechas). */
  locale: texto(import.meta.env.VITE_LOCALE, 'es-AR'),

  // ---- detalle en vivo (FEAT-0014) ----

  /** Ventana de la serie temporal, en horas. */
  serieHoras: entero(import.meta.env.VITE_SERIE_HORAS, 24, 1),
  /** Tope de puntos que se piden al histórico (protege el cupo y la memoria). */
  serieMaxPuntos: entero(import.meta.env.VITE_SERIE_MAX_PUNTOS, 2_000, 10),
  /** Refresco de respaldo del histórico mientras el WS está pausado (ms). */
  serieRefrescoMs: entero(import.meta.env.VITE_SERIE_REFRESCO_MS, 60_000, 5_000),
  /** Filas visibles de la tabla de últimas lecturas. */
  tablaFilas: entero(import.meta.env.VITE_TABLA_FILAS, 25, 5),
  /** Un dato más viejo que esto se marca como vencido (ms). */
  datoVencidoMs: entero(import.meta.env.VITE_DATO_VENCIDO_MS, 120_000, 5_000),
  /** Backoff de reconexión del WS (FEAT-0014 BR-002). */
  wsBackoffBaseMs: entero(import.meta.env.VITE_WS_BACKOFF_BASE_MS, 1_000, 100),
  wsBackoffFactor: entero(import.meta.env.VITE_WS_BACKOFF_FACTOR, 2, 2),
  wsBackoffTopeMs: entero(import.meta.env.VITE_WS_BACKOFF_TOPE_MS, 30_000, 1_000),
  wsMaxIntentos: entero(import.meta.env.VITE_WS_MAX_INTENTOS, 6, 1),

  // ---- alertas en vivo (FEAT-0015) ----

  /** Tope de entradas del feed en memoria (las más antiguas se descartan). */
  alertasMax: entero(import.meta.env.VITE_ALERTAS_MAX, 200, 10),
  /** Agrupación de una ráfaga de alertas para no pedir el resumen una vez por alerta. */
  alertasDebounceMs: entero(import.meta.env.VITE_ALERTAS_DEBOUNCE_MS, 2_000, 100),

  // ---- administración y demo (FEAT-0016) ----

  /**
   * Base del `data-simulator` para el panel de demo. **Vacío por default ⇒ el panel no se muestra**:
   * el simulador no tiene ruta en el gateway (FEAT-0008 BR-010) y sólo se habilita en desarrollo
   * apuntando a su puerto publicado por `docker-compose.dev.yml` (p. ej. `http://localhost:8081`).
   */
  simuladorUrl: (import.meta.env.VITE_SIMULADOR_URL ?? '').trim().replace(/\/$/, ''),
} as const

/** URL absoluta o relativa de un path de la API, respetando `VITE_API_BASE`. */
export function urlApi(path: string): string {
  const p = path.startsWith('/') ? path : `/${path}`
  return `${config.apiBase}${p}`
}

/**
 * URL del WebSocket para un path del gateway. El protocolo se deriva del de la página
 * (`https:` → `wss:`) y el token va por query string porque el navegador no puede mandar
 * `Authorization` en el upgrade (FEAT-0008 BR-003).
 *
 * El llamador **no debe** loguear ni mostrar esta URL: contiene el token (A5 de la revisión de
 * seguridad). Se construye en el momento del handshake y no se persiste.
 */
export function urlWebSocket(path: string, token: string): string {
  const base = config.apiBase !== '' ? config.apiBase : window.location.origin
  const url = new URL(path.startsWith('/') ? path : `/${path}`, base)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.searchParams.set('token', token)
  return url.toString()
}