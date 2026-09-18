import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

/**
 * Configuración de Vite del SPA (FEAT-0009).
 *
 * En desarrollo el SPA corre en `:5173` y **proxya** `/api` y `/ws` al gateway (`:8084`): el
 * navegador sigue viendo un único origen, así que no hace falta habilitar CORS para desarrollar y
 * el comportamiento (URLs relativas) es idéntico al de producción, donde el gateway sirve el build.
 *
 * `server.host` queda en el default (`localhost`) a propósito: exponer el dev server en la LAN
 * dejaría la pantalla de login (y los tokens) accesibles desde la red.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8084',
        changeOrigin: false,
      },
      '/ws': {
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8084',
        ws: true,
        changeOrigin: false,
      },
    },
  },
  build: {
    // El build queda en `dist/` y el empaquetado lo copia a los recursos estáticos del gateway
    // (FEAT-0009 BR-010). Sin sourcemaps en producción: no hay nada que ocultar, pero tampoco
    // motivo para publicar el código fuente completo.
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 700,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    include: ['src/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/main.tsx', 'src/vite-env.d.ts'],
    },
  },
})