import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The dashboard is served by the orchestrator itself: `npm run build` writes into the jar's static folder
// (Maven runs it during generate-resources). `npm run dev` proxies the API to a local orchestrator.
const orchestrator = process.env.FLOWFORGE_URL ?? 'http://localhost:8085'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  build: {
    outDir: path.resolve(__dirname, '../../../target/classes/static'),
    emptyOutDir: true,
    chunkSizeWarningLimit: 1200,
  },
  server: {
    port: 5173,
    proxy: { '/api': orchestrator, '/actuator': orchestrator },
  },
})
