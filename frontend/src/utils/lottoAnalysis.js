/**
 * 로또 6/45 분석 — 순수 함수 모음.
 *
 * 설계 전제(중요): **당첨 확률을 높이는 방법은 없다.** 매 회차는 독립·균등이므로
 * 과거 출현 빈도는 다음 회차 예측에 아무 정보도 주지 않는다. 어떤 조합이든 1등 확률은
 * 1/8,145,060 으로 동일하다. 따라서 이 모듈은 "잘 나올 번호"를 뽑지 않는다.
 *
 * 대신 로또가 1등 상금을 당첨자 수로 나누는 **pari-mutuel** 이라는 점을 이용한다.
 * 남들이 덜 고르는 조합을 고르면 확률은 그대로지만 **당첨 시 분배 인원이 줄어든다.**
 *
 * <b>규칙은 전부 실측으로 검증된 것만 쓴다</b>({@link CROWD_RULES}). 1,221회차(1등 당첨자수·
 * 판매액이 집계된 회차)를 대상으로 순열검정(20,000회)을 돌려 p<0.05 인 특성만 남겼다.
 * "연속수는 인기 조합"처럼 그럴듯하지만 데이터와 반대인 가정이 실제로 있었기 때문에,
 * 검증 없이 규칙을 추가하지 말 것.
 */

/** 로또 번호 범위. */
export const MIN_NUM = 1
export const MAX_NUM = 45
export const PICK = 6

/** 생일로 고를 수 있는 최대 번호. */
export const BIRTHDAY_MAX = 31

/** 자유도 44(=45-1), 유의수준 5% 카이제곱 임계값. 균등성 검정 전용이라 상수로 둔다. */
export const CHI2_CRITICAL_DF44 = 60.4809

/** 1등 조합 수 C(45,6). */
export const TOTAL_COMBINATIONS = 8145060

/**
 * 실측 검증된 혼잡도 규칙.
 *
 * effect = 해당 특성을 가진 회차의 1등 당첨자 수가 그렇지 않은 회차 대비 몇 % 많았는지
 * (판매액 정규화). **양수 = 사람이 많이 고름 = 분배 인원 증가 = 피할 대상.**
 * p 는 순열검정 p-value, n 은 해당 특성 회차 수.
 *
 * 번호합과 저번호 개수는 서로 강하게 상관(합이 낮으면 저번호가 많음)이라 <b>중복 계상을 피해
 * 번호합만 점수에 쓴다</b>. 저번호 개수는 표시용으로만 둔다.
 */
export const CROWD_RULES = [
  {
    key: 'sumLow',
    label: '번호합 110 이하',
    detail: '합이 작으면 낮은 번호 위주 — 생일 범위와 겹쳐 선택이 몰린다',
    effect: 8.4,
    p: 0.017,
    n: 226,
    test: (f) => f.sum <= 110
  },
  {
    key: 'sumHigh',
    label: '번호합 151 이상',
    detail: '32~45 를 써야 나오는 구간이라 상대적으로 덜 고른다',
    effect: -8.8,
    p: 0.001,
    n: 419,
    test: (f) => f.sum >= 151
  },
  {
    key: 'consecutive',
    label: '연속수 포함',
    detail: '사람들이 "우연 같지 않다"며 기피 — 통념과 반대로 당첨자가 적다',
    effect: -7.6,
    p: 0.003,
    n: 630,
    test: (f) => f.maxRun >= 2
  }
]

/**
 * 최적 조합의 조건.
 *
 * {@link CROWD_RULES} 를 모두 유리하게 만족시키는 지점(혼잡도 {@link OPTIMAL_INDEX})이며,
 * <b>측정 범위 밖으로 나가지 않도록 상한을 둔다.</b> 상한이 없으면 [40,41,42,43,44,45](합 255,
 * 6연속) 같은 조합이 "최적"으로 계산되는데, 이건 규칙이 검증된 구간을 한참 벗어난 외삽이고
 * 실제로는 누가 봐도 눈에 띄어 오히려 많이 고를 조합이다. 모델이 못 보는 영역이라 아예 뺀다.
 *
 * 상한 근거는 실제 당첨 조합의 관측 분포다 — 번호합 p99 = 208, 최대연속 길이는 4가 6회뿐이라
 * 표본이 있는 3까지만 인정한다.
 */
export const OPTIMAL = { sumMin: 151, sumMax: 208, runMin: 2, runMax: 3 }

/** 최적 조건을 만족하는 조합 수 — C(45,6) 전수 탐색 결과(전체 8,145,060 중 18.2%). */
export const OPTIMAL_SET_SIZE = 1483940

/** 최적 조합의 혼잡도 지수 = 번호합 상단(-8.8) + 연속수 포함(-7.6). */
export const OPTIMAL_INDEX = -16.4

/**
 * 검증에 실패해 점수에서 제외한 가정들 — 되살리지 말라는 기록.
 * 재검증 없이 다시 넣으면 추천 방향이 거꾸로 갈 수 있다(연속수가 실제 그랬다).
 */
export const REJECTED_RULES = [
  { label: '1~31 이 5개 이상', reason: '효과 -1.2%, p=0.652 — 무의미(4개 이상만 유의해 단조성 없음)' },
  { label: '등차수열', reason: '1,221회차 중 0회 — 검증 자체가 불가능' },
  { label: '용지 같은 세로줄 4개 이상', reason: '표본 18회차 — 판정 보류' },
  { label: '10단위 한 구간 쏠림', reason: '방향이 뒤집혀 노이즈로 판단' }
]

/* ────────────────────────── 기술통계 ────────────────────────── */

/** 번호별 출현 횟수 — 1~45 전부 키로 포함(미출현도 0 으로 노출). */
export function numberFrequency(draws) {
  const freq = {}
  for (let n = MIN_NUM; n <= MAX_NUM; n++) freq[n] = 0
  for (const d of draws || []) {
    for (const n of d.b || []) {
      if (freq[n] !== undefined) freq[n]++
    }
  }
  return freq
}

/**
 * 균등성 카이제곱 검정. chi2 = Σ(관측-기대)²/기대, 자유도 44.
 *
 * 임계값 미만이면 "편향이라는 근거가 없다"는 뜻이지 "완벽히 균등하다"는 증명이 아니다.
 * 표본이 없으면 null(결측을 그럴듯한 값으로 위장하지 않음).
 */
export function chiSquareUniformity(draws) {
  const list = draws || []
  if (!list.length) return null

  const freq = numberFrequency(list)
  const expected = (list.length * PICK) / (MAX_NUM - MIN_NUM + 1)
  if (expected <= 0) return null

  let chi2 = 0
  for (let n = MIN_NUM; n <= MAX_NUM; n++) {
    chi2 += Math.pow(freq[n] - expected, 2) / expected
  }

  const counts = Object.values(freq)
  const mean = counts.reduce((a, b) => a + b, 0) / counts.length
  const stdev = Math.sqrt(counts.reduce((a, c) => a + Math.pow(c - mean, 2), 0) / counts.length)

  // 이론 표준편차 — 관측 산포가 이 근처면 "핫/콜드"는 노이즈라는 직관적 근거가 된다.
  const p = PICK / (MAX_NUM - MIN_NUM + 1)
  const theoreticalStdev = Math.sqrt(list.length * p * (1 - p))

  const entries = Object.entries(freq).map(([n, c]) => ({ number: Number(n), count: c }))
  const sorted = [...entries].sort((a, b) => a.count - b.count)

  return {
    draws: list.length,
    expected,
    chi2,
    df: 44,
    critical: CHI2_CRITICAL_DF44,
    uniform: chi2 < CHI2_CRITICAL_DF44,
    stdev,
    theoreticalStdev,
    least: sorted[0],
    most: sorted[sorted.length - 1],
    frequency: entries
  }
}

/** 1등 구매방식(자동/수동/반자동) 누적 — 미집계 회차는 자동 제외. */
export function purchaseMethodStats(draws) {
  let auto = 0, manual = 0, semiAuto = 0, covered = 0
  for (const d of draws || []) {
    if (d.ac == null && d.mn == null && d.sa == null) continue
    auto += d.ac || 0
    manual += d.mn || 0
    semiAuto += d.sa || 0
    covered++
  }
  const total = auto + manual + semiAuto
  return { auto, manual, semiAuto, total, covered, autoPct: total ? (auto / total) * 100 : null }
}

/**
 * 번호합 구간별 실측 1등 당첨자 수 — 화면에 근거를 보여주기 위한 집계.
 * 판매액이 20년간 크게 늘어 당첨자도 함께 늘었으므로 판매액으로 정규화한다
 * (winnersPer100B = 1등 당첨자수 ÷ 판매액 × 1e11). 당첨자수·판매액 결측 회차는 제외.
 */
export function sumBandAnalysis(draws, bands = [110, 150]) {
  const buckets = new Map()
  let all = []

  for (const d of draws || []) {
    if (d.w1 == null || !d.s) continue
    const sum = (d.b || []).reduce((a, b) => a + b, 0)
    const bi = bands.findIndex((b) => sum <= b)
    const key = bi === -1 ? bands.length : bi
    if (!buckets.has(key)) buckets.set(key, [])
    const v = (d.w1 / d.s) * 1e11
    buckets.get(key).push(v)
    all.push(v)
  }
  if (!all.length) return { buckets: [], average: null }

  const average = all.reduce((a, b) => a + b, 0) / all.length
  const label = (k) =>
    k === 0 ? `~${bands[0]}` : k === bands.length ? `${bands[bands.length - 1] + 1}~` : `${bands[k - 1] + 1}~${bands[k]}`

  return {
    average,
    buckets: [...buckets.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([k, values]) => {
        const avg = values.reduce((a, b) => a + b, 0) / values.length
        return { label: label(k), samples: values.length, avgWinners: avg, vsAverage: (avg / average - 1) * 100 }
      })
  }
}

/* ────────────────────── 조합 특성 / 혼잡도 ────────────────────── */

/** 조합 특성 추출 — 규칙 판정과 화면 표시가 공용으로 쓴다. */
export function combinationFeatures(numbers) {
  const nums = [...(numbers || [])].sort((a, b) => a - b)
  let run = 1, maxRun = nums.length ? 1 : 0
  for (let i = 1; i < nums.length; i++) {
    run = nums[i] === nums[i - 1] + 1 ? run + 1 : 1
    maxRun = Math.max(maxRun, run)
  }
  return {
    numbers: nums,
    sum: nums.reduce((a, b) => a + b, 0),
    maxRun,
    odd: nums.filter((n) => n % 2 === 1).length,
    even: nums.filter((n) => n % 2 === 0).length,
    low: nums.filter((n) => n <= BIRTHDAY_MAX).length,
    high: nums.filter((n) => n > BIRTHDAY_MAX).length
  }
}

/**
 * 혼잡도 지수 — 검증된 규칙의 실측 효과를 합산한 값(단위: %).
 *
 * **음수일수록 좋다**(당첨 시 분배 인원이 평균보다 적을 것으로 추정). 당첨 확률과는 무관하다.
 * 효과가 서로 완전히 독립이라는 보장은 없어 합산은 근사치이며, 개별 효과가 ±10% 수준으로
 * 작다는 점(번호합의 설명력 r²≈1.2%)을 화면에서 함께 밝힌다.
 */
export function crowdIndex(numbers) {
  const f = combinationFeatures(numbers)
  if (f.numbers.length !== PICK) return { index: 0, factors: [], features: f, extrapolated: false, optimal: false }

  const factors = CROWD_RULES.filter((r) => r.test(f)).map((r) => ({
    key: r.key, label: r.label, effect: r.effect, detail: r.detail
  }))
  return {
    index: factors.reduce((a, r) => a + r.effect, 0),
    factors,
    features: f,
    // 측정 범위 밖이면 지수를 믿을 수 없다 — 숨기지 않고 플래그로 알린다
    extrapolated: f.sum > OPTIMAL.sumMax || f.maxRun > OPTIMAL.runMax,
    optimal: isOptimal(f)
  }
}

/** 최적 집합 소속 여부 — {@link OPTIMAL} 조건 전부 충족. */
export function isOptimal(features) {
  const f = features && features.numbers ? features : combinationFeatures(features)
  if (f.numbers.length !== PICK) return false
  return f.sum >= OPTIMAL.sumMin && f.sum <= OPTIMAL.sumMax
    && f.maxRun >= OPTIMAL.runMin && f.maxRun <= OPTIMAL.runMax
}

/* ────────────────────────── 생성기 ────────────────────────── */

/**
 * 결정적 난수 생성기(mulberry32) — 같은 시드는 항상 같은 수열을 낸다.
 * 주간 고정 추천이 서버 저장 없이도 한 주 내내 동일하게 재현되도록 하는 데 쓴다.
 */
export function seededRng(seed) {
  let a = seed >>> 0
  return function () {
    a = (a + 0x6d2b79f5) >>> 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/**
 * 다음 추첨 회차 정보 — 마지막 수록 회차에서 7일씩 더해 오늘 이후 첫 토요일 추첨을 찾는다.
 * 데이터가 오래돼도(스냅샷이라 갱신이 늦을 수 있음) 회차 번호가 계속 진행하도록 외삽한다.
 */
export function nextDrawInfo(draws, today = new Date()) {
  const last = (draws || [])[draws.length - 1]
  if (!last) return null

  const lastDate = new Date(`${last.d}T00:00:00`)
  let round = last.n
  let date = lastDate
  // 오늘(자정 기준) 이후 첫 추첨일까지 진행 — 추첨 당일은 아직 '이번 주'로 본다
  const cutoff = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  while (date < cutoff) {
    round += 1
    date = new Date(date.getTime() + 7 * 86400000)
  }
  return {
    round,
    date,
    dateText: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`,
    /** 스냅샷 이후 실제로 추첨이 지난 회차 수 — 데이터 노후 표시용 */
    roundsAhead: round - last.n
  }
}

/**
 * 이번 주 고정 추천 — 회차 번호를 시드로 써서 <b>한 주 내내 같은 번호</b>가 나온다.
 *
 * 저장소 없이 재현되며(같은 회차 = 같은 결과), 추첨이 지나면 회차가 올라가 자동으로 갱신된다.
 * 뽑는 방식은 {@link generateRecommendations} 와 동일해 결과는 항상 최적 집합 안에 있다.
 */
export function weeklyRecommendation(draws, today = new Date()) {
  const info = nextDrawInfo(draws, today)
  if (!info) return null
  // 회차에 큰 소수를 곱해 인접 회차끼리 시드가 붙지 않게 흩는다
  const rng = seededRng(info.round * 2654435761)
  const pick = generateRecommendations(1, { rng })[0]
  return pick ? { ...pick, ...info } : null
}

/** 균등 랜덤 6개 — 편향 없는 기본 추출(Fisher–Yates 부분 셔플). */
export function randomCombination(rng = Math.random) {
  const pool = []
  for (let n = MIN_NUM; n <= MAX_NUM; n++) pool.push(n)
  for (let i = 0; i < PICK; i++) {
    const j = i + Math.floor(rng() * (pool.length - i))
    ;[pool[i], pool[j]] = [pool[j], pool[i]]
  }
  return pool.slice(0, PICK).sort((a, b) => a - b)
}

/**
 * 최적 조합 생성 — <b>반드시 최적 집합 안에서만</b> 뽑는다.
 *
 * 이전 구현은 후보 N개 중 최선을 골랐는데, 그러면 운에 따라 최적에 못 닿는 결과가 섞였다.
 * 지금은 최적 조건을 만족할 때까지 재추첨하므로 <b>모든 결과가 혼잡도 {@link OPTIMAL_INDEX}
 * 로 동일</b>하다. 최적 집합이 전체의 18.2%(≈{@link OPTIMAL_SET_SIZE}개)라 평균 5~6회면 맞는다.
 *
 * <b>왜 매번 다른 번호가 나오는가</b>: 최적 조합은 유일하지 않다. {@link OPTIMAL_SET_SIZE}개가
 * 전부 같은 점수로 동점이라 그 안에서 고를 수밖에 없고, 애초에 <b>하나로 고정하면 안 된다</b> —
 * 이 전략의 이점은 "남들과 다르다"에서 나오므로, 모두가 같은 '최선의 번호'를 쓰면 그게 가장
 * 인기 있는 조합이 되어 이점이 사라진다. 무작위성은 결함이 아니라 전략의 필수 조건이다.
 *
 * @param optimalOnly false 면 순수 무작위(비교용)
 */
export function generateRecommendations(count = 5, { optimalOnly = true, attempts = 200, rng = Math.random } = {}) {
  const out = []
  const seen = new Set()

  for (let i = 0; i < count; i++) {
    let picked = null
    for (let a = 0; a < attempts; a++) {
      const numbers = randomCombination(rng)
      const key = numbers.join(',')
      if (seen.has(key)) continue
      const scored = crowdIndex(numbers)
      if (!optimalOnly || scored.optimal) { picked = { numbers, ...scored }; break }
      // 최적을 못 찾은 경우의 폴백 — 그때까지 본 것 중 최선을 남겨둔다
      if (!picked || scored.index < picked.index) picked = { numbers, ...scored }
    }
    if (!picked) break
    seen.add(picked.numbers.join(','))
    out.push(picked)
  }
  return out
}
