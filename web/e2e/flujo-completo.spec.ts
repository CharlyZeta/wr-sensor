import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

/**
 * e2e del SPA (FEAT-0009..0016) contra el **build real** servido por el stub con las mismas reglas
 * del gateway: es el único lugar donde se ejercitan juntos el hosting mismo-origen, la CSP, el
 * enrutamiento del cliente, la sesión, los WebSocket y el CRUD.
 *
 * Cada escenario cubre el "camino feliz" que un operador haría a mano, y los tres casos que la
 * documentación promete: 404 de la API sin HTML, degradación por `code` y sesión que se cierra.
 */

const ADMIN = { email: 'admin@wrsensor.local', password: 'Admin123!' }
const VIEWER = { email: 'viewer@wrsensor.local', password: 'Viewer123!' }

async function entrar(page: Page, usuario = ADMIN): Promise<void> {
  await page.getByLabel('Usuario').fill(usuario.email)
  await page.getByLabel('Contraseña').fill(usuario.password)
  await page.getByRole('button', { name: 'Entrar' }).click()
}

test.describe('hosting y seguridad del punto de entrada', () => {
  test('el SPA se sirve desde el mismo origen y la API no devuelve HTML', async ({ page, request }) => {
    const raiz = await request.get('/')
    expect(raiz.status()).toBe(200)
    expect(raiz.headers()['content-type']).toContain('text/html')
    expect(raiz.headers()['cache-control']).toBe('no-store')
    // headers de seguridad del gateway (FIX-0008)
    expect(raiz.headers()['content-security-policy']).toContain("script-src 'self'")
    expect(raiz.headers()['x-frame-options']).toBe('DENY')
    expect(raiz.headers()['x-content-type-options']).toBe('nosniff')

    // ruta de cliente ⇒ índice; ruta de API desconocida ⇒ 404 JSON (nunca HTML)
    const ruta = await request.get('/sensores/00000000-0000-4000-8000-00000000000a')
    expect(ruta.status()).toBe(200)
    expect(await ruta.text()).toContain('id="root"')

    const api = await request.get('/api/loquesea')
    expect(api.status()).toBe(404)
    expect(api.headers()['content-type']).toContain('json')
    expect((await api.json()).code).toBe('ROUTE_NOT_FOUND')

    const asset = await request.get('/assets/noexiste-abc12345.js')
    expect(asset.status()).toBe(404)

    // y el token nunca aparece en la URL del panel
    await page.goto('/')
    expect(page.url()).not.toContain('token')
  })
})

test.describe('sesión y mapa', () => {
  test('login, mapa con sensores y cierre de sesión', async ({ page }) => {
    await page.goto('/mapa')
    await expect(page.getByRole('heading', { name: 'WR-Sensor' })).toBeVisible()

    await entrar(page)
    await expect(page.getByRole('heading', { name: 'Mapa de sensores' })).toBeVisible()

    // lista del mapa con los tres sensores del stub
    const lista = page.getByRole('list', { name: 'Sensores' })
    await expect(lista.getByRole('listitem')).toHaveCount(3)
    await expect(lista.getByText('Norte')).toBeVisible()
    await expect(lista.getByText(/Crítico/)).toBeVisible()

    // el token vive en sessionStorage y en ningún otro lado
    const storage = await page.evaluate(() => ({
      sesion: window.sessionStorage.getItem('wrsensor.sesion'),
      local: window.localStorage.length,
      cookies: document.cookie,
    }))
    expect(storage.sesion).toContain('e2e-token')
    expect(storage.local).toBe(0)
    expect(storage.cookies).toBe('')

    await page.getByRole('button', { name: 'Salir' }).click()
    await expect(page.getByRole('heading', { name: 'WR-Sensor' })).toBeVisible()
    expect(await page.evaluate(() => window.sessionStorage.getItem('wrsensor.sesion'))).toBeNull()
  })

  test('credenciales inválidas muestran el mensaje del code', async ({ page }) => {
    await page.goto('/login')
    await entrar(page, { email: 'nadie@wrsensor.local', password: 'mal' })
    const alerta = page.getByRole('alert')
    await expect(alerta).toContainText(/usuario o contraseña incorrectos/i)
    await expect(alerta).toContainText('INVALID_CREDENTIALS')
    await expect(page.getByRole('heading', { name: 'Mapa de sensores' })).toBeHidden()
  })

  test('el resumen caído se informa por code sin mapa a medias', async ({ page }) => {
    await page.goto('/login')
    // el stub devuelve 502 REGISTRY_UNAVAILABLE para el resumen cuando se pide con ese forzado
    await page.route('**/api/sensores/resumen*', async (ruta) => {
      await ruta.fulfill({
        status: 502,
        contentType: 'application/json',
        headers: { 'X-Correlation-Id': 'e2e-corr-502' },
        body: JSON.stringify({ code: 'REGISTRY_UNAVAILABLE', message: 'registry no disponible' }),
      })
    })
    await entrar(page)

    const alerta = page.getByRole('alert')
    await expect(alerta).toContainText(/catálogo de sensores/i)
    await expect(alerta).toContainText('REGISTRY_UNAVAILABLE')
    await expect(alerta).toContainText('correlacion=e2e-corr-502')
    await expect(page.getByRole('button', { name: 'Reintentar' })).toBeVisible()
    await expect(page.getByRole('list', { name: 'Sensores' })).toBeHidden()
  })
})

test.describe('detalle y alertas en vivo', () => {
  test('el detalle muestra la serie, la tabla y el valor que llega por WebSocket', async ({ page }) => {
    await page.goto('/login')
    await entrar(page)
    await page.getByRole('link', { name: 'Mapa' }).click()
    await page.getByRole('list', { name: 'Sensores' }).getByText('Norte').click()

    await expect(page.getByRole('heading', { name: 'Norte' })).toBeVisible()
    await expect(page.getByRole('img', { name: /Serie de/i })).toBeVisible()
    const tabla = page.getByRole('table')
    await expect(tabla.getByRole('columnheader')).toHaveCount(5)
    // la conexión del sensor queda "En vivo" y la lectura que el stub empuja al conectar aparece
    // marcada como dato en vivo (dos elementos distintos: el chip de conexión y la marca del valor)
    await expect(page.getByText('En vivo', { exact: true })).toBeVisible({ timeout: 15_000 })
    await expect(page.getByText(/33,3/).first()).toBeVisible()
    await expect(page.locator('.marca-vivo')).toHaveText(/en vivo/)
    // y la antigüedad del dato se muestra
    await expect(page.getByText(/hace \d+/).first()).toBeVisible()
  })

  test('el feed de alertas se llena con la alerta del WS y el contador se limpia al abrirlo', async ({ page }) => {
    await page.goto('/login')
    await entrar(page)

    // el badge de no leídas aparece en la barra al llegar la alerta CRITICAL del stub
    const enlace = page.getByRole('link', { name: /Alertas/ })
    await expect(enlace).toContainText('1', { timeout: 15_000 })

    await enlace.click()
    await expect(
      page.getByRole('heading', { name: 'Alertas', exact: true }),
    ).toBeVisible({ timeout: 15_000 })
    const feed = page.getByRole('list', { name: 'Alertas confirmadas' })
    await expect(feed.getByRole('listitem')).toHaveCount(1)
    await expect(feed).toContainText('Centro')
    await expect(feed).toContainText(/Crítico|Advertencia/)

    // al abrir el feed el contador vuelve a cero
    await expect(page.getByRole('link', { name: 'Alertas' })).not.toContainText('1')
  })
})

test.describe('administración', () => {
  test('ADMIN lista, crea y da de baja (baja lógica) un sensor', async ({ page }) => {
    await page.goto('/login')
    await entrar(page)

    await page.getByRole('link', { name: 'Sensores' }).click()
    await expect(page.getByRole('heading', { name: 'Sensores' })).toBeVisible()
    const tabla = page.getByRole('table')
    await expect(tabla.getByText('S-01')).toBeVisible()

    // alta
    await page.getByRole('button', { name: 'Nuevo sensor' }).click()
    await expect(page.getByRole('heading', { name: 'Nuevo sensor' })).toBeVisible()
    await page.getByLabel('Código').fill('S-E2E')
    await page.getByLabel('Nombre').fill('Sensor e2e')
    await page.getByLabel('Latitud').fill('-31,6')
    await page.getByLabel('Longitud').fill('-60,7')
    const minimos = page.getByLabel('Mínimo')
    const maximos = page.getByLabel('Máximo')
    await minimos.nth(0).fill('0')
    await maximos.nth(0).fill('10')
    await minimos.nth(1).fill('0')
    await maximos.nth(1).fill('20')
    await minimos.nth(2).fill('0')
    await maximos.nth(2).fill('30')
    await page.getByRole('button', { name: 'Crear sensor' }).click()

    await expect(page.getByRole('heading', { name: 'Sensores' })).toBeVisible()
    await expect(page.getByText(/S-E2E creado/)).toBeVisible()
    await expect(page.getByRole('table').getByText('S-E2E')).toBeVisible()

    // baja lógica con confirmación explícita
    await page.getByRole('button', { name: 'Dar de baja' }).first().click()
    const dialogo = page.getByRole('dialog')
    await expect(dialogo).toContainText('INACTIVO')
    await expect(dialogo).toContainText('no se elimina')
    await dialogo.getByRole('button', { name: 'Confirmar baja' }).click()
    await expect(page.getByText(/quedó INACTIVO/)).toBeVisible()
  })

  test('un código duplicado se marca en el campo y no pierde lo cargado', async ({ page }) => {
    await page.goto('/login')
    await entrar(page)
    await page.getByRole('link', { name: 'Sensores' }).click()
    await page.getByRole('button', { name: 'Nuevo sensor' }).click()

    await page.getByLabel('Código').fill('S-01') // ya existe en el stub
    await page.getByLabel('Nombre').fill('Duplicado')
    await page.getByLabel('Latitud').fill('-31,6')
    await page.getByLabel('Longitud').fill('-60,7')
    const minimos = page.getByLabel('Mínimo')
    const maximos = page.getByLabel('Máximo')
    await minimos.nth(0).fill('0')
    await maximos.nth(0).fill('10')
    await minimos.nth(1).fill('0')
    await maximos.nth(1).fill('20')
    await minimos.nth(2).fill('0')
    await maximos.nth(2).fill('30')
    await page.getByRole('button', { name: 'Crear sensor' }).click()

    await expect(page.getByText(/SENSOR_CODE_DUPLICATED/)).toBeVisible()
    await expect(page.getByLabel('Nombre')).toHaveValue('Duplicado')
    await expect(page.getByLabel('Código')).toHaveValue('S-01')
  })

  test('VIEWER no tiene administración', async ({ page }) => {
    await page.goto('/login')
    await entrar(page, VIEWER)
    await expect(page.getByRole('heading', { name: 'Mapa de sensores' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Sensores' })).toBeHidden()
    await page.goto('/admin/sensores')
    await expect(page.getByText('Sección sólo para administradores')).toBeVisible()
  })
})