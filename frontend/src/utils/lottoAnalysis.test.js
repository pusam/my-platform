import { describe, it, expect } from 'vitest'
import {
  numberFrequency,
  chiSquareUniformity,
  purchaseMethodStats,
  sumBandAnalysis,
  combinationFeatures,
  crowdIndex,
  randomCombination,
  generateRecommendations,
  CHI2_CRITICAL_DF44,
  CROWD_RULES
} from './lottoAnalysis'
import draws from '../data/lottoDraws.json'

const draw = (n, b, extra = {}) => ({ n, d: '2020-01-01', b, bn: 1, w1: null, p1: null, s: null, ...extra })

/** 결정적 rng — 생성기 동작을 재현 가능하게. */
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
    expect(r.stdev).toBeLessThan(r.theoreticalStdev * 1.5)
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

describe('sumBandAnalysis', () => {
  it('당첨자수·판매액 결측 회차는 집계에서 제외한다', () => {
    const r = sumBandAnalysis([draw(1, [1, 2, 3, 4, 5, 6])])
    expect(r.buckets).toHaveLength(0)
    expect(r.average).toBeNull()
  })

  it('실제 데이터에서 번호합이 높을수록 1등 당첨자가 적다', () => {
    const r = sumBandAnalysis(draws)
    expect(r.buckets).toHaveLength(3)
    const low = r.buckets[0]
    const high = r.buckets[r.buckets.length - 1]
    expect(low.vsAverage).toBeGreaterThan(0)     // 합 낮음 → 분배 인원 많음
    expect(high.vsAverage).toBeLessThan(0)       // 합 높음 → 분배 인원 적음
    expect(low.avgWinners).toBeGreaterThan(high.avgWinners)
  })
})

describe('combinationFeatures', () => {
  it('합·홀짝·저고·최대연속을 계산한다', () => {
    const f = combinationFeatures([40, 10, 33, 23, 37, 29])
    expect(f.numbers).toEqual([10, 23, 29, 33, 37, 40])
    expect(f.sum).toBe(172)
    expect(f.odd).toBe(4)
    expect(f.even).toBe(2)
    expect(f.low).toBe(3)
    expect(f.high).toBe(3)
    expect(f.maxRun).toBe(1)
  })

  it('연속 구간 길이를 잡아낸다', () => {
    expect(combinationFeatures([1, 2, 3, 10, 20, 30]).maxRun).toBe(3)
    expect(combinationFeatures([5, 6, 20, 21, 40, 44]).maxRun).toBe(2)
  })
})

describe('crowdIndex', () => {
  it('검증된 규칙만 사용한다 (등차수열·용지패턴 등은 제외)', () => {
    expect(CROWD_RULES.map((r) => r.key).sort()).toEqual(['consecutive', 'sumHigh', 'sumLow'])
    for (const r of CROWD_RULES) expect(r.p).toBeLessThan(0.05)
  })

  it('합이 낮은 조합은 양수 지수 (분배 인원 많음 = 피할 대상)', () => {
    const r = crowdIndex([1, 3, 5, 7, 9, 11])    // 합 36
    expect(r.index).toBeGreaterThan(0)
    expect(r.factors.map((f) => f.key)).toContain('sumLow')
  })

  it('합이 높으면 음수 지수', () => {
    const r = crowdIndex([30, 33, 36, 39, 42, 45])  // 합 225
    expect(r.index).toBeLessThan(0)
    expect(r.factors.map((f) => f.key)).toContain('sumHigh')
  })

  it('연속수는 감점이 아니라 가점이다 (실측 방향 — 통념과 반대)', () => {
    const rule = CROWD_RULES.find((r) => r.key === 'consecutive')
    expect(rule.effect).toBeLessThan(0)
    const withRun = crowdIndex([20, 21, 33, 37, 41, 44])
    expect(withRun.factors.map((f) => f.key)).toContain('consecutive')
  })

  it('번호가 6개가 아니면 지수를 매기지 않는다', () => {
    expect(crowdIndex([1, 2, 3]).index).toBe(0)
    expect(crowdIndex([]).factors).toEqual([])
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

describe('generateRecommendations', () => {
  it('요청한 개수만큼 서로 다른 조합을 낸다', () => {
    const out = generateRecommendations(5, { rng: seededRng(42) })
    expect(out).toHaveLength(5)
    expect(new Set(out.map((o) => o.numbers.join()))).toHaveLength(5)
  })

  it('혼잡도 오름차순으로 정렬되어 있다', () => {
    const out = generateRecommendations(5, { rng: seededRng(7) })
    for (let i = 1; i < out.length; i++) {
      expect(out[i].index).toBeGreaterThanOrEqual(out[i - 1].index)
    }
  })

  it('후보를 늘리면 순수 무작위보다 혼잡도가 낮아진다', () => {
    const avg = (list) => list.reduce((a, o) => a + o.index, 0) / list.length
    const plain = avg(generateRecommendations(20, { pool: 1, rng: seededRng(3) }))
    const picked = avg(generateRecommendations(20, { pool: 80, rng: seededRng(3) }))
    expect(picked).toBeLessThan(plain)
  })

  it('결과는 항상 나온다 (실패 상태 없음)', () => {
    expect(generateRecommendations(3, { pool: 1, rng: seededRng(9) })).toHaveLength(3)
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
