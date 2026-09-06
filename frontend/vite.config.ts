import react from '@vitejs/plugin-react'
import { tanstackRouter } from '@tanstack/router-plugin/vite'
import { defineConfig } from 'vite'

// BACKEND_URL moves the proxy off 8080, for running a second stack beside a first one.
const toBackend = {
  target: process.env.BACKEND_URL ?? 'http://localhost:8080',
  changeOrigin: true,
  // Compression clumps the SSE stream: docs/design-decisions/31-frontend-toolchain.md
  headers: { 'Accept-Encoding': 'identity' },
}

export default defineConfig({
  plugins: [
    tanstackRouter({ target: 'react', autoCodeSplitting: true }),
    react(),
  ],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': toBackend,
      '/internal': toBackend,
    },
  },
})
