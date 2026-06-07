import { defineConfig, devices } from '@playwright/test'

// P-IA 핵심 런타임 E2E. vite dev 서버를 띄우고 API 는 라우트 모킹(백엔드 불필요).
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 7_000 },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'line' : [['list']],
  use: {
    baseURL: 'http://localhost:4329',
    trace: 'on-first-retry',
    actionTimeout: 7_000
  },
  // production 빌드를 vite preview 로 서빙(올바른 MIME·SPA 폴백). 충돌 회피 위해 전용 포트 + 항상 새로 기동.
  webServer: {
    command: 'npm run build && npm run preview -- --port 4329 --strictPort',
    url: 'http://localhost:4329',
    reuseExistingServer: false,
    timeout: 180_000
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } }
  ]
})
