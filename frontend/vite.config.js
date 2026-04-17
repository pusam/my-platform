import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => ({
  plugins: [
    vue(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['pwa-icon.svg', 'pwa-icon-maskable.svg'],
      manifest: {
        name: 'MyPlatform',
        short_name: 'MyPlatform',
        description: '주식 매매·관심종목·가계부를 한 곳에서',
        theme_color: '#0f0f1a',
        background_color: '#0f0f1a',
        display: 'standalone',
        orientation: 'portrait',
        lang: 'ko',
        start_url: '/',
        scope: '/',
        icons: [
          {
            src: '/pwa-icon.svg',
            sizes: 'any',
            type: 'image/svg+xml',
            purpose: 'any'
          },
          {
            src: '/pwa-icon-maskable.svg',
            sizes: 'any',
            type: 'image/svg+xml',
            purpose: 'maskable'
          }
        ]
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,ico,woff,woff2}'],
        // 실시간 시세/거래 API는 절대 캐시 금지
        navigateFallbackDenylist: [/^\/api/],
        runtimeCaching: [
          {
            // API 요청: 네트워크 우선, 5초 타임아웃 후에만 캐시 폴백
            urlPattern: ({ url }) => url.pathname.startsWith('/api/'),
            handler: 'NetworkOnly',
            options: { cacheName: 'api-no-cache' }
          },
          {
            // 구글 폰트, 외부 CDN 등
            urlPattern: ({ url }) => url.origin !== self.location.origin,
            handler: 'StaleWhileRevalidate',
            options: {
              cacheName: 'external-resources',
              expiration: { maxEntries: 30, maxAgeSeconds: 60 * 60 * 24 * 7 }
            }
          }
        ]
      },
      devOptions: {
        enabled: false  // 개발 모드에선 SW 비활성 (디버깅 편의)
      }
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    emptyOutDir: true
  },
  esbuild: {
    drop: mode === 'production' ? ['console', 'debugger'] : []
  }
}))

