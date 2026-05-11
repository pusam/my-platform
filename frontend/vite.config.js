import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => ({
  plugins: [
    vue(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: [
        'pwa-icon.svg', 'pwa-icon-maskable.svg',
        'pwa-icon-192.png', 'pwa-icon-512.png',
        'pwa-icon-maskable-192.png', 'pwa-icon-maskable-512.png',
        'apple-touch-icon.png',
      ],
      manifest: {
        id: '/',
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
          // PNG (Chrome/Android/iOS 호환성 1순위)
          { src: '/pwa-icon-192.png',         sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: '/pwa-icon-512.png',         sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: '/pwa-icon-maskable-192.png', sizes: '192x192', type: 'image/png', purpose: 'maskable' },
          { src: '/pwa-icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
          // SVG (가능한 환경에서는 벡터 사용 — 어떤 사이즈로도 깨짐 없음)
          { src: '/pwa-icon.svg',          sizes: 'any', type: 'image/svg+xml', purpose: 'any' },
          { src: '/pwa-icon-maskable.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'maskable' },
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
    emptyOutDir: true,
    // 큰 vendor / chart 라이브러리를 별도 chunk 로 분리 — 페이지 코드 변경 시 vendor 캐시 보존.
    // BoardPage(296KB) / V2(280KB) 등의 chunk 가 매번 무효화되는 비용 절감.
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-vue': ['vue', 'vue-router'],
          'vendor-chart': ['chart.js', 'chartjs-plugin-annotation', 'vue-chartjs'],
          'vendor-http': ['axios'],
          'vendor-editor': ['@vueup/vue-quill', 'dompurify'],
          'vendor-webauthn': ['@simplewebauthn/browser']
        }
      }
    },
    // 페이지 chunk 가 500KB 를 살짝 넘는 경우가 있어 경고 임계치 600KB 로 상향.
    chunkSizeWarningLimit: 600
  },
  esbuild: {
    drop: mode === 'production' ? ['console', 'debugger'] : []
  }
}))

