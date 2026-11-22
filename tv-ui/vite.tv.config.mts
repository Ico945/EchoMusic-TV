import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'node:path';

export default defineConfig({
  root: __dirname,
  base: '/assets/tv-ui/',
  plugins: [vue()],
  build: {
    outDir: 'dist-tv',
    emptyOutDir: true,
    rollupOptions: { input: resolve(__dirname, 'tv.html') },
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src/renderer'),
      '@tv': resolve(__dirname, 'src/tv'),
    },
  },
});
