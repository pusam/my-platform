import { describe, it, expect } from 'vitest'
import {
  numberFrequency,
  chiSquareUniformity,
  birthdayBiasAnalysis,
  purchaseMethodStats,
  popularityScore,
  randomCombination,
  generateCombinations,
  describeCombination,
  CHI2_CRITICAL_DF44
} from './lottoAnalysis'
import draws from '../data/lottoDraws.json'

const draw = (n, b, extra = {}) => ({ n, d: '2020-01-01', b, bn: 1, w1: null, p1: null, s: null, ...extra })

/** 결정적 rng — 테스트에서 생성기 동작을 재현 가능하게. */
const seededRng = (seed) => () => {
  seed = (seed * 1103515245 + 12345) % 2147483648
  return seed / 2147483648
}

describe('numberFrequency', () => {
  it('1~45 를 전부 키로 노출하고 미출현은 0 이다', () => {
    const freq = numberFrequency([draw(1, [1, 2, 3, 4, 5, 6])])
    expect(Object.keys(freq)).toHaveLength(45)
    expect(freq[1]).toBe(1)
    expect(freq[45]).toBe(0)
  })
})

describe('chiSquareUniformity', () => {
  it('표본이 없으면 null (결측을 위장하지 않음)', () => {
    expect(chiSquareUniformity([])).toBeNull()
    expect(chiSquareUniformity(null)).toBeNull()
  })

  it('극단적 편향은 임계값을 넘겨 편향으로 판정한다', () => {
    const biased = Array.from({ length: 200 }, (_, i) => draw(i + 1, [1, 2, 3, 4, 5, 6]))
    const r = chiSquareUniformity(biased)
    expect(r.uniform).toBe(false)
    expect(r.chi2).toBeGreaterThan(CHI2_CRITICAL_DF44)
  })

  it('실제 전 회차 데이터는 균등 판정이다 (핫/콜드 번호는 착시)', () => {
    const r = chiSquareUniformity(draws)
    expect(r.draws).toBeGreaterThan(1000)
    expect(r.uniform).toBe(true)
    // 관측 산포가 이론 표준편차 근처 → 편차는 노이즈
    expect(r.stdev).toBeLessThan(r.theoreticalStdev * 1.5)
  })
})

describe('birthdayBiasAnalysis', () => {
  it('당첨자수·판매액 결측 회차는 집계에서 제외한다', () => {
    const r = birthdayBiasAnalysis([draw(1, [1, 2, 3, 4, 5, 6])], 1)
    expect(r.buckets).toHaveLength(0)
    expect(r.ratio).toBeNull()
  })

  it('실제 데이터에서 저번호 편중 조합이 당첨자를 더 많이 만든다', () => {
    const r = birthdayBiasAnalysis(draws)
    expect(r.buckets.length).toBeGreaterThan(0)
    expect(r.ratio).toBeGreaterThan(1)   // 생일 범위 편중 → 분배 인원 증가
  })
})

describe('purchaseMethodStats', () => {
  it('미집계 회차는 covered 에서 빠진다', () => {
    const r = purchaseMethodStats([draw(1, [1, 2, 3, 4, 5, 6]), draw(2, [7, 8, 9, 10, 11, 12], { ac: 3, mn: 1 })])
    expect(r.covered).toBe(1)
    expect(r.auto).toBe(3)
    expect(r.autoPct).toBeCloseTo(75, 5)
  })
})

describe('popularityScore', () => {
  it('아무 패턴 없는 분산 조합은 0 점이다', () => {
    const { score } = popularityScore([3, 14, 22, 33, 39, 44])
    expect(score).toBe(0)
  })

  it('1-2-3-4-5-6 은 연속·등차·생일이 모두 걸린다', () => {
    const { score, reasons } = popularityScore([1, 2, 3, 4, 5, 6])
    expect(score).toBeGreaterThanOrEqual(6)
    expect(reasons.join()).toMatch(/연속/)
    expect(reasons.join()).toMatch(/등차/)
  })

  it('전부 1~31 이면 생일 조합으로 감점된다', () => {
    const { reasons } = popularityScore([2, 9, 14, 20, 26, 31])
    expect(reasons.join()).toMatch(/생일/)
  })

  it('과거 당첨 조합과 동일하면 사유에 표시된다', () => {
    const past = [draw(1, [10, 23, 29, 33, 37, 40])]
    const { reasons } = popularityScore([40, 10, 33, 23, 37, 29], past)
    expect(reasons.join()).toMatch(/과거 당첨/)
  })

  it('번호가 6개가 아니면 점수를 매기지 않는다', () => {
    expect(popularityScore([1, 2, 3]).score).toBe(0)
  })
})

describe('randomCombination', () => {
  it('중복 없는 6개를 오름차순으로 반환한다', () => {
    for (let i = 0; i < 50; i++) {
      const c = randomCombination()
      expect(c).toHaveLength(6)
      expect(new Set(c).size).toBe(6)
      expect([...c].sort((a, b) => a - b)).toEqual(c)
      expect(Math.min(...c)).toBeGreaterThanOrEqual(1)
      expect(Math.max(...c)).toBeLessThanOrEqual(45)
    }
  })
})

describe('generateCombinations', () => {
  it('요청한 개수만큼, 서로 다른 조합을 만든다', () => {
    const out = generateCombinations(5, { rng: seededRng(42) })
    expect(out).toHaveLength(5)
    expect(new Set(out.map((o) => o.numbers.join()))).toHaveLength(5)
  })

  it('기본 설정에서 인기 패턴이 걸리지 않은 조합만 낸다', () => {
    const out = generateCombinations(10, { rng: seededRng(7) })
    for (const o of out) {
      expect(o.score).toBe(0)
      expect(o.exhausted).toBe(false)
    }
  })

  it('시도 상한에 걸리면 exhausted 로 알린다 (실패를 숨기지 않음)', () => {
    // 달성 불가능한 조건(-1 이하)이라 반드시 소진된다
    const out = generateCombinations(1, { maxScore: -1, attempts: 5, rng: seededRng(1) })
    expect(out[0].exhausted).toBe(true)
  })
})

describe('describeCombination', () => {
  it('합계·홀짝·저고를 요약한다', () => {
    const d = describeCombination([40, 10, 33, 23, 37, 29])
    expect(d.numbers).toEqual([10, 23, 29, 33, 37, 40])
    expect(d.sum).toBe(172)
    expect(d.odd).toBe(4)    // 23,29,33,37
    expect(d.even).toBe(2)   // 10,40
    expect(d.low).toBe(3)
    expect(d.high).toBe(3)
  })
})

describe('데이터셋 무결성', () => {
  it('1회차부터 결번 없이 연속이며 번호가 유효하다', () => {
    expect(draws[0].n).toBe(1)
    expect(draws[0].b).toEqual([10, 23, 29, 33, 37, 40])
    expect(draws[0].bn).toBe(16)
    for (let i = 0; i < draws.length; i++) {
      expect(draws[i].n).toBe(i + 1)
      expect(new Set(draws[i].b).size).toBe(6)
      expect(draws[i].b.every((n) => n >= 1 && n <= 45)).toBe(true)
      expect(draws[i].b.includes(draws[i].bn)).toBe(false)
    }
  })
})
