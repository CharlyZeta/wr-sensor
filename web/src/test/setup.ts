import '@testing-library/jest-dom/vitest'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { cleanup } from '@testing-library/react'
import { servidor } from './servidor'

/**
 * Setup de los tests del SPA (FEAT-0009 BR-010): jsdom + jest-dom + MSW.
 * MSW intercepta `fetch`, así que los tests ejercitan el cliente real (códigos de error, 429 con
 * `Retry-After`, correlación) sin tocar la red.
 *
 * Los **WebSocket** no pasan por MSW: los tests los reemplazan con un doble controlable
 * (`vi.stubGlobal('WebSocket', …)`), por eso se le dice a MSW que no se ocupe de ellos (si no,
 * `onUnhandledRequest: 'error'` convierte la conexión del feed en un error del test).
 */
beforeAll(() =>
  servidor.listen({
    onUnhandledRequest: (request, print) => {
      if (request.url.startsWith('ws:') || request.url.startsWith('wss:')) {
        return
      }
      print.error()
    },
  }),
)
afterEach(() => {
  servidor.resetHandlers()
  cleanup()
  window.sessionStorage.clear()
})
afterAll(() => servidor.close())