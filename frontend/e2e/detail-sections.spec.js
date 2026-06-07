import { test, expect } from '@playwright/test'
import { bootstrap } from './helpers.js'

test.describe('P-IA 종목 상세 — 심화 접기', () => {
  test('심화 섹션 기본 접힘 → 토글 펼침 (자식 마운트 유지)', async ({ page }) => {
    await bootstrap(page, 'USER')
    await page.goto('/stock/005930')

    // hasData(quick mock) → 요약/근거/심화 렌더
    const toggle = page.locator('.detail-section-toggle')
    await expect(toggle).toBeVisible()
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')

    // 기본 접힘: body 는 DOM 에 있으나 display:none (v-show = 마운트 유지)
    const body = page.locator('.detail-section-body')
    await expect(body).toHaveCount(1)        // 언마운트 X
    await expect(body).toBeHidden()

    // 토글 → 펼침
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await expect(body).toBeVisible()

    // 다시 토글 → 접힘
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await expect(body).toBeHidden()
  })

  test('요약존: 결론 + 핵심요약 바가 상단에 노출', async ({ page }) => {
    await bootstrap(page, 'USER')
    await page.goto('/stock/005930')
    // QuickSummaryBar(요약존)
    await expect(page.locator('.quick-summary-bar')).toBeVisible()
    // 메인 탭 바(종합/투자자/지표) — hasData 진입 확인
    await expect(page.locator('.main-tab-bar')).toBeVisible()
  })
})
