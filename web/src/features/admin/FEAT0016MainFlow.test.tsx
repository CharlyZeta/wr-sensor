import { beforeEach, describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { HttpResponse } from 'msw'
import { App } from '../../App'
import {
  conAlta,
  conBaja,
  conListadoAdmin,
  restaurarAlta,
  restaurarBaja,
  restaurarListadoAdmin,
  sensorAdminDeEjemplo,
} from '../../test/servidor'
import { instalarWebSocketFalso } from '../../test/servidor'
import { construirAlta, construirEdicion, VALORES_INICIALES } from './FormularioSensor'
import type { ValoresFormulario } from './FormularioSensor'
import { validarConfig } from '../../api/sensores'

/**
 * FEAT-0016 — Main Flow + AF-01..AF-08 + BR-001..BR-010: CRUD de sensores y panel de demo.
 * Cubre el listado keyset, el alta con errores de dominio por `code`, la edición con el subset de
 * configuración, la baja lógica con confirmación, el rol `VIEWER` sin acciones y la validación local.
 */

function sesion(rol: 'ADMIN' | 'VIEWER'): void {
  window.sessionStorage.setItem(
    'wrsensor.sesion',
    JSON.stringify({
      token: 'token-de-prueba',
      rol,
      expiraEn: Date.now() + 600_000,
      email: rol === 'ADMIN' ? 'admin@wrsensor.local' : 'viewer@wrsensor.local',
    }),
  )
}

function renderApp(ruta: string, rol: 'ADMIN' | 'VIEWER' = 'ADMIN') {
  sesion(rol)
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <App />
    </MemoryRouter>,
  )
}

const valoresValidos: ValoresFormulario = {
  ...VALORES_INICIALES,
  codigo: 'S-NUEVO',
  nombre: 'Sensor nuevo',
  latitud: '-31.6',
  longitud: '-60.7',
  rangoNormalMin: '0',
  rangoNormalMax: '10',
  rangoWarningMin: '0',
  rangoWarningMax: '20',
  rangoCriticalMin: '0',
  rangoCriticalMax: '30',
}

beforeEach(() => {
  instalarWebSocketFalso()
})

describe('FEAT-0016 · listado y roles', () => {
  it('AC-001: el listado keyset muestra los sensores con estado y unidad', async () => {
    renderApp('/admin/sensores')
    const tabla = await screen.findByRole('table')
    expect(within(tabla).getByText('S-ADM')).toBeInTheDocument()
    expect(within(tabla).getByText('ACTIVO')).toBeInTheDocument()
    expect(within(tabla).getByText('METROS')).toBeInTheDocument()
    expect(within(tabla).getByRole('button', { name: 'Dar de baja' })).toBeInTheDocument()
  })

  it('AC-001/BR-008: la paginación keyset encadena el cursor', async () => {
    let llamadas = 0
    conListadoAdmin((url) => {
      llamadas += 1
      return url.searchParams.get('cursor') === null
        ? HttpResponse.json({ items: [sensorAdminDeEjemplo], nextCursor: 'c1' })
        : HttpResponse.json({ items: [{ ...sensorAdminDeEjemplo, id: 'x2', codigo: 'S-2' }], nextCursor: null })
    })
    const usuario = userEvent.setup()
    renderApp('/admin/sensores')
    await screen.findByRole('table')

    await usuario.click(await screen.findByRole('button', { name: 'Cargar más' }))
    expect(await screen.findByText('S-2')).toBeInTheDocument()
    expect(llamadas).toBe(2)
    restaurarListadoAdmin()
  })

  it('AC-007/AF-05/BR-001: VIEWER no ve acciones de escritura ni el enlace de administración', async () => {
    renderApp('/admin/sensores', 'VIEWER')

    expect(await screen.findByText('Sección sólo para administradores')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Nuevo sensor' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Sensores' })).not.toBeInTheDocument()
  })
})

describe('FEAT-0016 · alta', () => {
  it('AC-002: un formulario válido crea el sensor y vuelve al listado con aviso', async () => {
    const usuario = userEvent.setup()
    let enviado: unknown = null
    conAlta((cuerpo) => {
      enviado = cuerpo
      return HttpResponse.json(sensorAdminDeEjemplo, { status: 201 })
    })
    renderApp('/sensores/nuevo')

    await usuario.type(await screen.findByLabelText('Código'), 'S-NUEVO')
    await usuario.type(screen.getByLabelText('Nombre'), 'Sensor nuevo')
    await usuario.type(screen.getByLabelText('Latitud'), '-31.6')
    await usuario.type(screen.getByLabelText('Longitud'), '-60.7')
    const minimos = screen.getAllByLabelText('Mínimo')
    const maximos = screen.getAllByLabelText('Máximo')
    await usuario.type(minimos[0] as HTMLElement, '0')
    await usuario.type(maximos[0] as HTMLElement, '10')
    await usuario.type(minimos[1] as HTMLElement, '0')
    await usuario.type(maximos[1] as HTMLElement, '20')
    await usuario.type(minimos[2] as HTMLElement, '0')
    await usuario.type(maximos[2] as HTMLElement, '30')

    await usuario.click(screen.getByRole('button', { name: 'Crear sensor' }))

    await waitFor(() => expect(screen.getByRole('table')).toBeInTheDocument())
    expect(screen.getByText(/Sensor S-ADM creado/)).toBeInTheDocument()
    expect(enviado).toMatchObject({ codigo: 'S-NUEVO', nombre: 'Sensor nuevo', tipo: 'RIO' })
    restaurarAlta()
  })

  it('AC-003/AF-01/BR-002: un código duplicado se marca en el campo y no pierde lo cargado', async () => {
    const usuario = userEvent.setup()
    conAlta(() =>
      HttpResponse.json(
        { code: 'SENSOR_CODE_DUPLICATED', message: 'codigo duplicado: S-NUEVO' },
        { status: 409 },
      ),
    )
    renderApp('/sensores/nuevo')

    await usuario.type(await screen.findByLabelText('Código'), 'S-NUEVO')
    await usuario.type(screen.getByLabelText('Nombre'), 'Sensor nuevo')
    await usuario.type(screen.getByLabelText('Latitud'), '-31.6')
    await usuario.type(screen.getByLabelText('Longitud'), '-60.7')
    const minimos = screen.getAllByLabelText('Mínimo')
    const maximos = screen.getAllByLabelText('Máximo')
    for (const [i, v] of [['0', '10'], ['0', '20'], ['0', '30']].entries()) {
      await usuario.type(minimos[i] as HTMLElement, v[0] as string)
      await usuario.type(maximos[i] as HTMLElement, v[1] as string)
    }
    await usuario.click(screen.getByRole('button', { name: 'Crear sensor' }))

    expect(await screen.findByText(/SENSOR_CODE_DUPLICATED/)).toBeInTheDocument()
    // el resto del formulario queda intacto
    expect(screen.getByLabelText('Nombre')).toHaveValue('Sensor nuevo')
    expect(screen.getByLabelText('Código')).toHaveValue('S-NUEVO')
    restaurarAlta()
  })

  it('AC-004/BR-003: la validación local bloquea el envío con rangos incoherentes', async () => {
    const usuario = userEvent.setup()
    let llamado = false
    conAlta(() => {
      llamado = true
      return HttpResponse.json(sensorAdminDeEjemplo, { status: 201 })
    })
    renderApp('/sensores/nuevo')

    await usuario.type(await screen.findByLabelText('Código'), 'S-X')
    await usuario.type(screen.getByLabelText('Nombre'), 'Sensor X')
    await usuario.type(screen.getByLabelText('Latitud'), '-31.6')
    await usuario.type(screen.getByLabelText('Longitud'), '-60.7')
    const minimos = screen.getAllByLabelText('Mínimo')
    const maximos = screen.getAllByLabelText('Máximo')
    // normal fuera del warning: 0..50 vs 0..20
    for (const [i, v] of [['0', '50'], ['0', '20'], ['0', '30']].entries()) {
      await usuario.type(minimos[i] as HTMLElement, v[0] as string)
      await usuario.type(maximos[i] as HTMLElement, v[1] as string)
    }
    await usuario.click(screen.getByRole('button', { name: 'Crear sensor' }))

    expect(await screen.findByText(/debe contener al normal/i)).toBeInTheDocument()
    expect(llamado).toBe(false)
    restaurarAlta()
  })

  it('AC-008/AF-07/BR-006: un 429 avisa, conserva los datos y NO reenvía la operación', async () => {
    const usuario = userEvent.setup()
    let llamadas = 0
    conAlta(() => {
      llamadas += 1
      return HttpResponse.json(
        { code: 'RATE_LIMIT_EXCEEDED', message: 'cupo' },
        { status: 429, headers: { 'Retry-After': '3' } },
      )
    })
    renderApp('/sensores/nuevo')

    await usuario.type(await screen.findByLabelText('Código'), 'S-NUEVO')
    await usuario.type(screen.getByLabelText('Nombre'), 'Sensor nuevo')
    await usuario.type(screen.getByLabelText('Latitud'), '-31.6')
    await usuario.type(screen.getByLabelText('Longitud'), '-60.7')
    const minimos = screen.getAllByLabelText('Mínimo')
    const maximos = screen.getAllByLabelText('Máximo')
    for (const [i, v] of [['0', '10'], ['0', '20'], ['0', '30']].entries()) {
      await usuario.type(minimos[i] as HTMLElement, v[0] as string)
      await usuario.type(maximos[i] as HTMLElement, v[1] as string)
    }
    await usuario.click(screen.getByRole('button', { name: 'Crear sensor' }))

    expect(await screen.findByText(/NO se reenvió automáticamente/)).toBeInTheDocument()
    expect(screen.getByLabelText('Código')).toHaveValue('S-NUEVO')
    await new Promise((r) => setTimeout(r, 300))
    expect(llamadas).toBe(1)
    restaurarAlta()
  })
})

describe('FEAT-0016 · edición y baja', () => {
  it('AC-005/BR-004/BR-005: la edición envía el subset y la baja es lógica con confirmación', async () => {
    const usuario = userEvent.setup()
    renderApp('/admin/sensores')
    await screen.findByRole('table')

    // baja: primero confirma (dice INACTIVO, no "elimina")
    await usuario.click(screen.getByRole('button', { name: 'Dar de baja' }))
    const dialogo = await screen.findByRole('dialog')
    expect(within(dialogo).getByText(/queda/i)).toBeInTheDocument()
    expect(within(dialogo).getByText(/INACTIVO/)).toBeInTheDocument()
    expect(within(dialogo).getByText(/no se elimina/i)).toBeInTheDocument()
    await usuario.click(within(dialogo).getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('AC-005: confirmar la baja llama al DELETE y refresca el listado', async () => {
    const usuario = userEvent.setup()
    let bajas = 0
    conBaja(() => {
      bajas += 1
      return new HttpResponse(null, { status: 204 })
    })
    renderApp('/admin/sensores')
    await screen.findByRole('table')

    await usuario.click(screen.getByRole('button', { name: 'Dar de baja' }))
    const dialogo = await screen.findByRole('dialog')
    await usuario.click(within(dialogo).getByRole('button', { name: 'Confirmar baja' }))

    await waitFor(() => expect(bajas).toBe(1))
    expect(await screen.findByText(/quedó INACTIVO \(baja lógica/)).toBeInTheDocument()
    restaurarBaja()
  })

  it('AC-010/AF-04: un sensor INACTIVO no ofrece edición y explica cómo reactivarlo', async () => {
    conListadoAdmin(() =>
      HttpResponse.json({ items: [{ ...sensorAdminDeEjemplo, estado: 'INACTIVO' }], nextCursor: null }),
    )
    renderApp('/admin/sensores')
    await screen.findByRole('table')

    expect(screen.queryByRole('link', { name: 'Editar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Dar de baja' })).not.toBeInTheDocument()
    expect(screen.getByText(/reactivarlo, editá su estado a ACTIVO/)).toBeInTheDocument()
    restaurarListadoAdmin()
  })
})

describe('FEAT-0016 · demo y configuración', () => {
  it('AC-009/BR-007: el panel de demo no se muestra sin VITE_SIMULADOR_URL', async () => {
    renderApp('/admin/sensores')
    await screen.findByRole('table')
    expect(screen.queryByRole('heading', { name: 'Demo (simulador)' })).not.toBeInTheDocument()
  })

  it('AC-012/BR-010: la validación de configuración es coherente y configurable', async () => {
    // coherente: sin errores
    expect(validarConfig({ histeresis: 0, frecuenciaReporteSegundos: 30, rangoNormal: { min: 0, max: 10 }, rangoWarning: { min: 0, max: 20 }, rangoCritical: { min: 0, max: 30 } })).toEqual({})
    // incoherente: warning no contiene al normal, y crítico no contiene al warning
    const errores = validarConfig({ histeresis: -1, frecuenciaReporteSegundos: 0, rangoNormal: { min: 0, max: 50 }, rangoWarning: { min: 0, max: 20 }, rangoCritical: { min: 0, max: 10 } })
    expect(errores.histeresis).toBeDefined()
    expect(errores.frecuenciaReporteSegundos).toBeDefined()
    expect(errores.rangoWarning).toBeDefined()
    expect(errores.rangoCritical).toBeDefined()

    const { config } = await import('../../config')
    expect(config.simuladorUrl).toBe('')
    // el alta válida construye el DTO del backend
    const alta = construirAlta(valoresValidos)
    expect(alta.datos?.codigo).toBe('S-NUEVO')
    expect(alta.errores).toEqual({})
    // la edición manda sólo el subset de configuración (el PUT rechaza el resto)
    const edicion = construirEdicion(valoresValidos)
    expect(Object.keys(edicion.datos ?? {}).sort()).toEqual([
      'estado',
      'frecuenciaReporteSegundos',
      'histeresis',
      'rangoCritical',
      'rangoNormal',
      'rangoWarning',
    ])
  })
})
