import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 시장 타이밍 화면 — 시장 폭(등락 종목 수) 수집 경로가 KIS 로 바뀐 뒤(2026-10-02) 남은 약속들.
 *
 * 재현: 배포 직후 화면 점검에서 '데이터 관리'가 여전히 "기간별 데이터 수집(Backfill) — 네이버 금융 차단 방지 1초 딜레이"를
 * 보였다. 과거 날짜의 등락 수는 어떤 소스로도 못 받으므로(KRX 死, KIS 는 당일 값만) 누르면 매번 '실패 N일'만 나오는 버튼이었다.
 * 또 '📥 시장 데이터 수집'은 장 마감(15:40) 전엔 일부러 저장하지 않는데, 화면은 그 이유 대신 "실패했습니다"만 보였다.
 */
const read = (p) => readFileSync(join(process.cwd(), 'src', p), 'utf8')

describe('MarketTimingPage — 받을 수 없는 과거 수집을 약속하지 않는다', () => {
  const src = read('views/MarketTimingPage.vue')

  it('기간 수집(Backfill) UI·이동 버튼·핸들러가 없다', () => {
    expect(src).not.toMatch(/📅 기간 수집/)
    expect(src).not.toMatch(/collectBackfillData|scrollToBackfill|isBackfilling/)
    expect(src).not.toMatch(/네이버 금융 차단 방지/)
  })

  it('수집 안내는 장 마감 확정치(15:40)·16:30 자동 수집을 말한다 — 예전 "15:30 이후"는 틀린 경계', () => {
    expect(src).not.toMatch(/15:30 이후/)
    expect(src).toMatch(/15:40/)
    expect(src).toMatch(/16:30/)
  })

  it('종합 상태 카드의 필요 일수는 서버 규칙(최근 20거래일 중 15일)과 같다 — 예전 "최소 20일"은 진단 문구와 어긋났다', () => {
    expect(src).not.toMatch(/최소 20일 데이터 필요/)
    expect(src).toMatch(/최근 20거래일 중 15일 필요/)
  })

  it('수집 실패 토스트는 서버가 준 이유를 먼저 보인다', () => {
    expect(src).toMatch(/error\?\.response\?\.data\?\.message \|\| '시장 데이터 수집에 실패했습니다\.'/)
  })

  it('기간 수집 API 헬퍼도 같이 뺐다(고아 방지)', () => {
    expect(read('utils/api.js')).not.toMatch(/collectDataForPeriod\s*\(/)
  })
})

describe('USD/KRW 는 한 출처 — 같은 화면에 서로 다른 환율이 뜨지 않게', () => {
  it('재현: 원자재 카드·홈 위젯이 죽은 /exchange-rate 를 읽어 USD/KRW 가 빠지거나 "데이터 지연"이었다 — 이제 글로벌 시세 KRW', () => {
    const page = read('views/MarketTimingPage.vue')
    const widget = read('components/MarketInfoWidget.vue')
    expect(page).toMatch(/krwFromFuturesQuote\(allQuotes\.find\(a => a\.symbol === 'KRW'\)\)/)
    expect(widget).toMatch(/globalFuturesAPI\.getQuote\('KRW'\)/)
    for (const src of [page, widget, read('utils/api.js')]) {
      expect(src).not.toMatch(/exchangeRateAPI\b(?!\s*는)/)
    }
  })
})

describe('시장 상태 문구 — 백엔드 enum 은 이름 문자열로 온다(2026-10-03)', () => {
  it('재현: overallCondition.emoji 를 읽어 늘 undefined — 종합 시장 상태 칸이 비었다', () => {
    const src = read('views/MarketTimingPage.vue')
    expect(src).not.toMatch(/overallCondition\.emoji|condition\?\.emoji/)
    expect(src).toMatch(/marketConditionText\(marketData\.overallCondition\)/)
  })

  it('급락일(CRASH)도 색·아이콘이 있다 — 예전엔 빈 색·❓', () => {
    const src = read('views/MarketTimingPage.vue')
    expect(src).toMatch(/case 'CRASH': return 'condition-crash'/)
    expect(src).toMatch(/case 'CRASH': return '🚨'/)
  })
})
