import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 수집 버튼은 관리자에게만 (2026-11-10 권한 정리).
 * 서버(SecurityConfig)가 수집 주소를 관리자만 받으므로, 일반 계정 화면에 버튼이 남으면 누를 때마다 403 이다.
 * 재현: 시장 타이밍 '📥 시장 데이터 수집'은 조건 없이 보였고, 실적 스크리너 '관리자' 탭은 🔒 를 두 번 누르면
 * 누구에게나 열려 38분짜리 재무 배치 버튼을 보였다.
 */
const read = (p) => readFileSync(join(process.cwd(), p), 'utf8')
const security = read('../backend/src/main/java/com/myplatform/backend/config/SecurityConfig.java')

describe('시장 타이밍 — 시장 데이터 수집 버튼', () => {
  const page = read('src/views/MarketTimingPage.vue')
  it('관리자 계정에만 보인다', () => {
    expect(page).toMatch(/<button v-if="isAdmin" @click="collectData"/)
    expect(page).toMatch(/const isAdmin = localStorage\.getItem\('role'\) === 'ADMIN'/)
  })
  it('서버도 관리자만 받는다', () => {
    expect(security).toMatch(/"\/api\/market\/collect"\)\.hasRole\("ADMIN"\)/)
  })
})

describe('실적 스크리너 — 관리자 탭(원버튼 전체 수집)', () => {
  const page = read('src/views/EarningsScreenerPage.vue')
  it('관리자 탭 토글이 관리자 계정에만 보인다', () => {
    expect(page).toMatch(/<button v-if="isAdmin" class="admin-toggle-btn"/)
    expect(page).toMatch(/const isAdmin = localStorage\.getItem\('role'\) === 'ADMIN'/)
  })
  it('서버도 스크리너 POST 를 관리자만 받는다', () => {
    expect(security).toMatch(/HttpMethod\.POST, "\/api\/screener\/\*\*"\)\.hasRole\("ADMIN"\)/)
  })
})
