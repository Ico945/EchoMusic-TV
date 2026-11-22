import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'node:path';

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@tv': resolve(__dirname, 'src/tv') } },
  test: { css: true, environment: 'jsdom', setupFiles: ['src/tv/test/setup.ts'] },
});
