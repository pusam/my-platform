import { test, expect } from '@playwright/test'
import { bootstrap } from './helpers.js'

// GNB 탭 버튼 로케이터 (DashboardHeader)
const gnb = (page, label) => page.getByRole('button', { name: label, exact: false })
// 서브탭 strip 버튼 (탭별 고유 마커)
const subtab = (page, label) => page.locator('.sub-tab-btn', { hasText: label })

test.describe('P-IA 대시보드 GNB / 딥링크 / phase 강조', () => {
  test('GNB 3탭(시장/발굴/매매) 노출 + 클릭 전환', async ({ page }) => {
    await bootstrap(page, 'USER')
    await page.goto('/stock-dashboard?tab=market')

    await expect(gnb(page, '시장')).toBeVisible()
    await expect(gnb(page, '발굴')).toBeVisible()
    await expect(gnb(page, '매매')).toBeVisible()

    // 시장 탭: 시장 서브탭(시장타이밍) 노출, 발굴 서브탭(백테스트) 미노출
    await expect(subtab(page, '시장타이밍')).toBeVisible()
    await expect(subtab(page, '백테스트')).toHaveCount(0)

    // 발굴 클릭 → 발굴 서브탭(백테스트) 노출, 시장 서브탭 사라짐
    await gnb(page, '발굴').click()
    await expect(subtab(page, '백테스트')).toBeVisible()
    await expect(subtab(page, '시장타이밍')).toHaveCount(0)
  })

  test('phase 강조 배너가 라이브 탭에 노출', async ({ page }) => {
    await bootstrap(page, 'USER')
    await page.goto('/stock-dashboard?tab=market')
    const strip = page.locator('.phase-strip')
    await expect(strip).toBeVisible()
    // phase-{pre|during|post} 중 하나의 클래스 보유
    await expect(strip).toHaveClass(/phase-(pre|during|post)/)
  })

  test('딥링크 ?tab=trade → 매매 탭 (USER: 관리자 전용 안내)', async ({ page }) => {
    await bootstrap(page, 'USER')
    await page.goto('/stock-dashboard?tab=trade')
    await expect(page.getByText('관리자 전용')).toBeVisible()
    // 라이브 전용 freshness 바는 매매 탭에서 미노출
    await expect(page.locator('.freshness-bar')).toHaveCount(0)
  })

  test('딥링크 ?tab=trade → 매매 탭 (ADMIN: 안내문 없음 = PaperTrading 렌더)', async ({ page }) => {
    await bootstrap(page, 'ADMIN')
    await page.goto('/stock-dashboard?tab=trade')
    await expect(page.getByText('관리자 전용')).toHaveCount(0)
  })

  test('레거시 /sector → 시장 탭으로 redirect', async ({ page }) => {
    await bootstrap(page, 'USER')
    await page.goto('/sector')
    await expect(page).toHaveURL(/tab=market/)
    await expect(subtab(page, '시장타이밍')).toBeVisible()
  })

  test('레거시 /paper-trading → 매매 탭 (ADMIN)', async ({ page }) => {
    await bootstrap(page, 'ADMIN')
    await page.goto('/paper-trading')
    await expect(page).toHaveURL(/tab=trade/)
  })
})
