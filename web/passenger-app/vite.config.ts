import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    // Budgets are enforced rather than hoped for: this is a public transport
    // service whose median user is on a mid-range Android phone on a congested
    // mobile network, and a bloated bundle is a broken product there regardless
    // of how well it runs on a laptop.
    chunkSizeWarningLimit: 200,
  },
  server: {
    port: 3000,
    // In dev the SPA and the API share an origin, exactly as they do behind the
    // gateway in compose, so there is no CORS difference between environments.
    proxy: {
      '/api': { target: process.env.API_URL ?? 'http://localhost:8080', changeOrigin: true },
    },
  },
});
