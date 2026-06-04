import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// vite.config.js 와 분리한 전용 테스트 설정.
// 운영 vite.config 의 VitePWA/workbox 플러그인은 테스트에 불필요(+느림)하므로 vue() 만 로드.
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.{test,spec}.{js,mjs,ts}'],
    setupFiles: ['./vitest.setup.js'],
    css: false,
    clearMocks: true,
    restoreMocks: true
  }
})
