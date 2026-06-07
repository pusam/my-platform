import { test } from '@playwright/test'
import { bootstrap } from './helpers.js'

test('debug: dashboard render', async ({ page }) => {
  const errors = []
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
  page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message))
  await bootstrap(page, 'USER')
  await page.goto('/stock-dashboard?tab=market')
  await page.waitForTimeout(3000)
  console.log('URL:', page.url())
  const txt = await page.evaluate(() => document.body.innerText.slice(0, 400))
  console.log('BODY:', JSON.stringify(txt))
  const btns = await page.locator('button').allInnerTexts()
  console.log('BUTTONS:', JSON.stringify(btns.slice(0, 20)))
  console.log('ERRORS:', JSON.stringify(errors.slice(0, 8)))
})
