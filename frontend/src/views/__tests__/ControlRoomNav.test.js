import { describe, it, expect } from 'vitest'
import Hub from '../StockTradingDashboardV2.vue'
import { CONTROL_ROOM_NAV } from '../../utils/controlRoomFormat'

/**
 * 관제실 → 허브 이동 링크가 실제로 그 탭을 여는지.
 *
 * **왜 테스트가 필요한가**: 허브의 `mapLegacyTab` 은 모르는 키를 예외로 만들지 않고
 * `'discover'` 로 흘려보낸다(`map[tab] || 'discover'`). 그래서 관제실 링크에 오타가 있으면
 * **'오늘'을 눌렀는데 발굴이 열리고 아무도 모른다** — 에러 없이 잘못 가는 부류다.
 * 두 화면의 계약을 여기서 직접 대조한다.
 */
describe('관제실 화면 이동 링크', () => {
  const mapLegacyTab = Hub.methods.mapLegacyTab

  it('허브 탭 링크는 자기 자신으로 왕복한다 — 오타가 discover 로 흘러가지 않게', () => {
    const tabLinks = CONTROL_ROOM_NAV.filter((l) => l.tab)
    expect(tabLinks.length).toBeGreaterThan(0)
    for (const link of tabLinks) {
      expect(mapLegacyTab.call({}, link.tab)).toBe(link.tab)
    }
  })

  it('링크는 tab 또는 path 중 정확히 하나를 가진다 — 둘 다면 어디로 갈지 화면이 모른다', () => {
    for (const link of CONTROL_ROOM_NAV) {
      expect(Boolean(link.tab) !== Boolean(link.path)).toBe(true)
    }
  })

  it('key 는 고유하다 — v-for 키 충돌 방지', () => {
    const keys = CONTROL_ROOM_NAV.map((l) => l.key)
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('모든 링크에 label 과 why(툴팁)가 있다 — 왜 그 화면인지 설명 없는 링크는 넣지 않는다', () => {
    for (const link of CONTROL_ROOM_NAV) {
      expect(link.label).toBeTruthy()
      expect(link.why).toBeTruthy()
    }
  })

  it('제2의 GNB 가 되지 않게 목적지 수를 묶어둔다', () => {
    // 관제실은 운영 콘솔이지 내비게이션 화면이 아니다. 늘리려면 이 테스트를 보고
    // "이 화면이 어느 KPI 의 원본인가"를 먼저 답할 것.
    expect(CONTROL_ROOM_NAV.length).toBeLessThanOrEqual(5)
  })
})
