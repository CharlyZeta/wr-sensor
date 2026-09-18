import '@testing-library/jest-dom/vitest'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { cleanup } from '@testing-library/react'
import { servidor } from './servidor'

/**
 * Setup de los tests del SPA (FEAT-0009 BR-010): jsdom + jest-dom + MSW.
 * MSW intercepta `fetch`, así que los tests ejercitan el cliente real (códigos de error, 429 con
 * `Retry-After`, correlación) sin tocar la red.
 */
beforeAll(() => servidor.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  servidor.resetHandlers()
  cleanup()
  window.sessionStorage.clear()
})
afterAll(() => servidor.close())