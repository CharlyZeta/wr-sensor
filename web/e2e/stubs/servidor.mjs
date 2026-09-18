/**
 * Stub de backend para los e2e del SPA (FEAT-0016).
 *
 * Reproduce lo que el SPA espera del gateway + servicios, **sin** Docker ni base de datos:
 * - sirve `web/dist` con las mismas reglas que `ServidorSpa` (índice para las rutas de cliente,
 *   `404 ROUTE_NOT_FOUND` en JSON para `/api/**` desconocido, `404` para assets inexistentes,
 *   `Cache-Control: no-store` en el índice) y con los headers de seguridad de `FiltroSeguridad`;
 * - implementa el contrato usado por el SPA: login, resumen del mapa, listado keyset, CRUD, detalle,
 *   lecturas y los WebSocket de lecturas y alertas (el SPA les pasa `?token=`).
 *
 * Los escenarios pueden forzar respuestas de error con `?forzar=<code>` en el login o con el
 * encabezado `x-forzar-code` (para probar el manejo de `code`, los `429` y el 502 del resumen).
 */
import { createServer } from 'node:http'
import { readFile, stat } from 'node:fs/promises'
import { dirname, extname, join, normalize, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { WebSocketServer } from 'ws'

const PUERTO = Number(process.env.STUB_PORT ?? 8085)
// `web/dist`: este archivo vive en `web/e2e/stubs/`, así que se suben dos niveles.
const RAIZ = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', 'dist')

const SENSOR_ID = '00000000-0000-4000-8000-00000000000a'
const SENSOR_ID_2 = '00000000-0000-4000-8000-00000000000b'

const CSP =
  "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
  "img-src 'self' data: https://tile.openstreetmap.org https://*.tile.openstreetmap.org; " +
  "font-src 'self' data:; connect-src 'self' ws: wss:; object-src 'none'; base-uri 'none'; " +
  "form-action 'self'; frame-ancestors 'none'"

let sensoresAdmin = [
  sensor(SENSOR_ID, 'S-01', 'Norte', 'ACTIVO', 'NORMAL', 21.5),
  sensor(SENSOR_ID_2, 'S-02', 'Centro', 'ACTIVO', 'CRITICAL', 88.4),
  sensor('00000000-0000-4000-8000-00000000000c', 'S-03', 'Sur', 'INACTIVO', 'WARNING', 5),
]

function sensor(id, codigo, nombre, estado, severidad, valor) {
  return {
    id,
    codigo,
    nombre,
    tipo: 'RIO',
    latitud: -31.6,
    longitud: -60.7,
    unidadMedida: 'METROS',
    estado,
    histeresis: 0.5,
    frecuenciaReporteSegundos: 30,
    fechaInstalacion: '2026-01-01T00:00:00Z',
    rangoNormal: { min: 0, max: 10 },
    rangoWarning: { min: 0, max: 20 },
    rangoCritical: { min: 0, max: 30 },
    ultimaLectura: { valor, timestamp: '2026-09-15T12:00:00Z', severidad, calidad: 'OK' },
  }
}

const lecturas = [
  { timestamp: '2026-09-15T10:00:00Z', valor: 10.5, unidadMedida: 'METROS', severidad: 'NORMAL', calidad: 'OK' },
  { timestamp: '2026-09-15T11:00:00Z', valor: 42.25, unidadMedida: 'METROS', severidad: 'WARNING', calidad: 'OK' },
  { timestamp: '2026-09-15T12:00:00Z', valor: 99.9, unidadMedida: 'METROS', severidad: 'CRITICAL', calidad: 'OK' },
]

const TIPOS_MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.map': 'application/json',
}

function headersSeguridad(extra = {}) {
  return {
    'Content-Security-Policy': CSP,
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'X-Frame-Options': 'DENY',
    'Permissions-Policy': 'geolocation=(), camera=(), microphone=()',
    'Cross-Origin-Resource-Policy': 'same-origin',
    ...extra,
  }
}

function json(res, status, cuerpo, extra = {}) {
  const payload = JSON.stringify(cuerpo)
  res.writeHead(status, headersSeguridad({
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    ...extra,
  }))
  res.end(payload)
}

function error(res, status, code, message) {
  json(res, status, { code, message })
}

async function cuerpoJson(req) {
  const partes = []
  for await (const parte of req) {
    partes.push(parte)
  }
  const texto = Buffer.concat(partes).toString('utf8')
  return texto === '' ? {} : JSON.parse(texto)
}

/** El SPA pasa el token por query en el handshake del WS (`?token=`), igual que contra el gateway. */
function tokenValido(url) {
  const token = url.searchParams.get('token')
  return typeof token === 'string' && token.length > 0
}

const servidor = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`)
  const forzar = req.headers['x-forzar-code']
  const ruta = url.pathname
  const metodo = req.method ?? 'GET'

  // ---- API ----
  if (ruta.startsWith('/api/')) {
    if (ruta === '/api/auth/login' && metodo === 'POST') {
      const credenciales = await cuerpoJson(req)
      const forzado = url.searchParams.get('forzar')
      if (forzado === '429') {
        return error(res, 429, 'RATE_LIMIT_EXCEEDED', 'cupo excedido')
      }
      if (forzado === '500') {
        return error(res, 500, 'INTERNAL_ERROR', 'fallo simulado')
      }
      if (credenciales.email === 'admin@wrsensor.local' && credenciales.password === 'Admin123!') {
        return json(res, 200, { token: 'e2e-token', rol: 'ADMIN', expiraEnSegundos: 3600 })
      }
      if (credenciales.email === 'viewer@wrsensor.local' && credenciales.password === 'Viewer123!') {
        return json(res, 200, { token: 'e2e-token', rol: 'VIEWER', expiraEnSegundos: 3600 })
      }
      return error(res, 401, 'INVALID_CREDENTIALS', 'credenciales invalidas')
    }

    if (ruta === '/api/sensores/resumen' && metodo === 'GET') {
      if (forzar === 'REGISTRY_UNAVAILABLE') {
        return error(res, 502, 'REGISTRY_UNAVAILABLE', 'registry no disponible')
      }
      return json(res, 200, sensoresAdmin.map(({ id, codigo, nombre, tipo, latitud, longitud, estado, unidadMedida, ultimaLectura }) =>
        ({ id, codigo, nombre, tipo, latitud, longitud, estado, unidadMedida, ultimaLectura })))
    }

    if (ruta === '/api/sensores' && metodo === 'GET') {
      return json(res, 200, { items: sensoresAdmin, nextCursor: null })
    }

    if (ruta === '/api/sensores' && metodo === 'POST') {
      const datos = await cuerpoJson(req)
      if (sensoresAdmin.some((s) => s.codigo === datos.codigo)) {
        return error(res, 409, 'SENSOR_CODE_DUPLICATED', `codigo duplicado: ${datos.codigo}`)
      }
      const creado = { ...sensor(crypto.randomUUID(), datos.codigo, datos.nombre, datos.estado, null, null), ...datos, ultimaLectura: null }
      sensoresAdmin = [...sensoresAdmin, creado]
      return json(res, 201, creado)
    }

    const conId = /^\/api\/sensores\/([^/]+)(\/.*)?$/.exec(ruta)
    if (conId !== null) {
      const id = conId[1]
      const sufijo = conId[2] ?? ''
      const actual = sensoresAdmin.find((s) => s.id === id)

      if (sufijo === '/lecturas' && metodo === 'GET') {
        return json(res, 200, { items: lecturas, nextCursor: null })
      }
      if (sufijo === '' && metodo === 'GET') {
        return actual === undefined ? error(res, 404, 'SENSOR_NOT_FOUND', 'no existe') : json(res, 200, actual)
      }
      if (sufijo === '' && metodo === 'PUT') {
        const datos = await cuerpoJson(req)
        if (actual === undefined) {
          return error(res, 404, 'SENSOR_NOT_FOUND', 'no existe')
        }
        const actualizado = { ...actual, ...datos }
        sensoresAdmin = sensoresAdmin.map((s) => (s.id === id ? actualizado : s))
        return json(res, 200, actualizado)
      }
      if (sufijo === '' && metodo === 'DELETE') {
        if (actual === undefined) {
          return error(res, 404, 'SENSOR_NOT_FOUND', 'no existe')
        }
        sensoresAdmin = sensoresAdmin.map((s) => (s.id === id ? { ...s, estado: 'INACTIVO' } : s))
        res.writeHead(204, headersSeguridad())
        return res.end()
      }
    }

    // Contrato del gateway: ruta no declarada ⇒ 404 JSON (nunca el índice del SPA).
    return error(res, 404, 'ROUTE_NOT_FOUND', `ruta no declarada: ${ruta}`)
  }

  // ---- estáticos + fallback del cliente (mismas reglas que ServidorSpa) ----
  const rutasCliente = ['/login', '/mapa', '/sensores', '/alertas', '/admin']
  const esRutaCliente = ruta === '/' || rutasCliente.some((p) => ruta === p || ruta.startsWith(`${p}/`))
  const relativo = normalize(ruta).replace(/^([/\\])+/, '')
  const tieneExtension = /\.[A-Za-z0-9]{1,10}$/.test(relativo)

  if (esRutaCliente || !tieneExtension) {
    const index = await readFile(join(RAIZ, 'index.html'))
    res.writeHead(200, headersSeguridad({
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Length': index.length,
    }))
    return res.end(index)
  }

  try {
    const contenido = await readFile(join(RAIZ, relativo))
    res.writeHead(200, headersSeguridad({
      'Content-Type': TIPOS_MIME[extname(relativo)] ?? 'application/octet-stream',
      'Cache-Control': relativo.startsWith('assets/')
        ? 'public, max-age=31536000, immutable'
        : 'public, max-age=3600',
      'Content-Length': contenido.length,
    }))
    return res.end(contenido)
  } catch {
    return error(res, 404, 'ROUTE_NOT_FOUND', 'recurso inexistente')
  }
})

// ---- WebSocket: lecturas por sensor y alertas (una conexión por pestaña) ----
// Un único manejador de `upgrade` para ambos caminos: el SPA pasa el token por query (`?token=`),
// igual que contra el gateway, y sin token se responde 401 antes de aceptar el handshake.
const wssLecturas = new WebSocketServer({ noServer: true })
const wssAlertas = new WebSocketServer({ noServer: true })

wssLecturas.on('connection', (ws) => {
  // Al conectar manda una lectura para que el detalle muestre el valor en vivo.
  ws.send(JSON.stringify({
    sensorId: SENSOR_ID,
    timestamp: new Date().toISOString(),
    valor: 33.3,
    unidadMedida: 'METROS',
    calidad: 'OK',
  }))
})

wssAlertas.on('connection', (ws) => {
  // Una alerta CRITICAL al conectar: el feed y el contador reaccionan sin intervención.
  ws.send(JSON.stringify({
    sensorId: SENSOR_ID_2,
    severidadNueva: 'CRITICAL',
    severidadAnterior: 'NORMAL',
    confirmada: true,
    timestamp: new Date().toISOString(),
  }))
})

servidor.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`)
  const esAlertas = url.pathname === '/ws/alertas' || url.pathname.startsWith('/ws/alertas')
  const esLecturas = url.pathname.startsWith('/ws/sensores')

  if (!esAlertas && !esLecturas) {
    socket.destroy()
    return
  }
  if (!tokenValido(url)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n')
    socket.destroy()
    return
  }
  if (esAlertas) {
    wssAlertas.handleUpgrade(req, socket, head, (ws) => wssAlertas.emit('connection', ws, req))
    return
  }
  wssLecturas.handleUpgrade(req, socket, head, (ws) => wssLecturas.emit('connection', ws, req))
})

servidor.listen(PUERTO, '127.0.0.1', async () => {
  try {
    await stat(join(RAIZ, 'index.html'))
    console.log(`[e2e] stub listo en http://127.0.0.1:${PUERTO} (SPA desde web/dist)`)
  } catch {
    console.error('[e2e] falta web/dist: correr `npm run build` antes de los e2e')
    process.exitCode = 1
  }
})

/** Hash de la versión de los datos servidos (el SPA no lo usa; sirve para diagnósticos). */
export const versionDatos = createHash('sha1').update(JSON.stringify(sensoresAdmin)).digest('hex').slice(0, 7)