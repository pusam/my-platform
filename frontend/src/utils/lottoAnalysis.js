/**
 * 로또 6/45 분석 — 순수 함수 모음.
 *
 * 설계 전제(중요): **당첨 확률을 높이는 방법은 없다.** 매 회차는 독립·균등이므로
 * 과거 출현 빈도는 다음 회차 예측에 아무 정보도 주지 않는다. 어떤 조합이든 1등 확률은
 * 1/8,145,060 으로 동일하다. 따라서 이 모듈은 "잘 나올 번호"를 뽑지 않는다.
 *
 * 대신 두 가지만 한다.
 *  1) {@link chiSquareUniformity} — 추첨이 실제로 균등한지 검정(핫/콜드 번호가 착시임을 데이터로 확인)
 *  2) {@link popularityScore} / {@link generateCombinations} — 로또는 1등 상금을 당첨자 수로
 *     나누는 pari-mutuel 이라, **남들이 많이 고르는 조합을 피하면 당첨 시 분배금이 올라간다.**
 *     확률이 아니라 기대 수령액을 다루는 것이며, 그래도 기댓값은 여전히 마이너스다(환급률 ~50%).
 *
 * 결측 처리: 초기 회차는 1등 당첨자수/구매방식이 미집계다. 이 값들은 null 로 두고 집계에서
 * 제외한다 — 0 으로 치환하지 않는다.
 */

/** 로또 번호 범위. */
export const MIN_NUM = 1
export const MAX_NUM = 45
export const PICK = 6

/** 생일로 고를 수 있는 최대 번호 — 1~31 편중이 인기 조합의 최대 원인. */
export const BIRTHDAY_MAX = 31

/** 용지 한 줄에 놓인 번호 개수(1~45 를 7열로 배열) — 세로/대각 패턴 판정용. */
const SLIP_COLS = 7

/** 자유도 44(=45-1), 유의수준 5% 카이제곱 임계값. 균등성 검정 전용이라 상수로 둔다. */
export const CHI2_CRITICAL_DF44 = 60.4809

/** 1등 조합 수 C(45,6). */
export const TOTAL_COMBINATIONS = 8145060

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
 * 표본이 없으면 null(§ 결측을 그럴듯한 값으로 위장하지 않음).
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

/**
 * 생일 편향 실측 — 당첨 조합의 1~31 번호 개수별 "1등 당첨자 수"를 비교한다.
 *
 * 판매액이 20년간 크게 늘어 당첨자 수도 함께 늘었으므로, 판매액으로 정규화해야 비교가 성립한다
 * (winnersPer100B = 1등 당첨자수 ÷ 판매액 × 1e11). 당첨자수·판매액 결측 회차는 제외.
 *
 * @returns {{buckets: Array, lowHeavy: number|null, highHeavy: number|null, ratio: number|null}}
 */
export function birthdayBiasAnalysis(draws, minSample = 5) {
  const byLowCount = new Map()

  for (const d of draws || []) {
    if (d.w1 == null || !d.s) continue          // 결측 회차 제외
    const low = (d.b || []).filter((n) => n <= BIRTHDAY_MAX).length
    if (!byLowCount.has(low)) byLowCount.set(low, [])
    byLowCount.get(low).push((d.w1 / d.s) * 1e11)
  }

  const buckets = [...byLowCount.entries()]
    .map(([lowCount, values]) => ({
      lowCount,
      samples: values.length,
      avgWinners: values.reduce((a, b) => a + b, 0) / values.length
    }))
    .filter((b) => b.samples >= minSample)
    .sort((a, b) => a.lowCount - b.lowCount)

  // 저번호 편중(4개 이상) vs 고번호 분산(3개 이하) 가중평균 비교
  const weighted = (pred) => {
    const sel = buckets.filter((b) => pred(b.lowCount))
    const n = sel.reduce((a, b) => a + b.samples, 0)
    return n ? sel.reduce((a, b) => a + b.avgWinners * b.samples, 0) / n : null
  }
  const highHeavy = weighted((c) => c <= 3)     // 32~45 를 많이 쓴 조합
  const lowHeavy = weighted((c) => c >= 4)      // 생일 범위에 몰린 조합

  return {
    buckets,
    lowHeavy,
    highHeavy,
    ratio: lowHeavy && highHeavy ? lowHeavy / highHeavy : null
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
  return {
    auto, manual, semiAuto, total, covered,
    autoPct: total ? (auto / total) * 100 : null
  }
}

/* ────────────────────── 인기 조합 판정 ────────────────────── */

/**
 * 조합의 "인기도" 점수 — 높을수록 많은 사람이 고를 법한 조합이다.
 *
 * 당첨 확률과는 <b>무관</b>하다. 오직 당첨됐을 때 상금을 몇 명과 나누게 되는지에만 영향을 준다.
 * 각 규칙은 사람의 선택 편향에서 나온 것이며, 생일 편중(lowHeavy)은 실제 데이터로 확인된 항목이다.
 *
 * @returns {{score:number, reasons:string[]}} score 0 이면 회피 대상 패턴이 하나도 없음
 */
export function popularityScore(numbers, pastDraws = []) {
  const nums = [...(numbers || [])].sort((a, b) => a - b)
  const reasons = []
  let score = 0

  if (nums.length !== PICK) return { score: 0, reasons: [] }

  // 1) 생일 편중 — 실측으로 확인된 유일한 항목
  const lowCount = nums.filter((n) => n <= BIRTHDAY_MAX).length
  if (lowCount === PICK) { score += 3; reasons.push('전부 1~31 (생일 조합)') }
  else if (lowCount >= 5) { score += 2; reasons.push('1~31 에 5개 편중') }

  // 2) 연속수 — 3연속 이상이면 눈에 띄는 패턴이라 선택률이 높다
  let run = 1, maxRun = 1
  for (let i = 1; i < nums.length; i++) {
    run = nums[i] === nums[i - 1] + 1 ? run + 1 : 1
    maxRun = Math.max(maxRun, run)
  }
  if (maxRun >= 4) { score += 3; reasons.push(`${maxRun}연속 번호`) }
  else if (maxRun === 3) { score += 1; reasons.push('3연속 번호') }

  // 3) 등차수열 — 1-8-15-22-29-36 류
  const diffs = nums.slice(1).map((n, i) => n - nums[i])
  if (diffs.every((d) => d === diffs[0])) { score += 3; reasons.push('등차수열') }

  // 4) 용지 세로줄 — 같은 열(7 로 나눈 나머지 동일)에 몰림
  const colCount = {}
  for (const n of nums) {
    const col = n % SLIP_COLS
    colCount[col] = (colCount[col] || 0) + 1
  }
  const maxCol = Math.max(...Object.values(colCount))
  if (maxCol >= 4) { score += 2; reasons.push('용지 같은 세로줄에 4개 이상') }

  // 5) 과거 1등 조합 그대로 — 매회 수천 명이 고른다
  const key = nums.join(',')
  if ((pastDraws || []).some((d) => [...(d.b || [])].sort((a, b) => a - b).join(',') === key)) {
    score += 3
    reasons.push('과거 당첨 조합과 동일')
  }

  // 6) 한 구간 쏠림 — 10 단위 한 구간에 4개 이상
  const decade = {}
  for (const n of nums) {
    const k = Math.floor((n - 1) / 10)
    decade[k] = (decade[k] || 0) + 1
  }
  if (Math.max(...Object.values(decade)) >= 4) { score += 1; reasons.push('10 단위 한 구간에 4개 이상') }

  return { score, reasons }
}

/* ────────────────────────── 생성기 ────────────────────────── */

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
 * 인기 패턴을 피한 조합 생성.
 *
 * 확률적으로는 아무 조합이나 같지만, 당첨 시 분배 인원이 적을 가능성이 높은 쪽을 고른다.
 * maxScore 이하가 나올 때까지 재추첨하되, 시도 상한에 걸리면 그때까지 가장 점수가 낮은 것을
 * 반환한다(무한 루프 방지 — 실패를 숨기지 않도록 exhausted 플래그로 알린다).
 */
export function generateCombinations(count = 5, { maxScore = 0, pastDraws = [], attempts = 200, rng = Math.random } = {}) {
  const out = []
  const seen = new Set()

  for (let i = 0; i < count; i++) {
    let best = null
    let exhausted = true

    for (let a = 0; a < attempts; a++) {
      const nums = randomCombination(rng)
      const key = nums.join(',')
      if (seen.has(key)) continue

      const { score, reasons } = popularityScore(nums, pastDraws)
      if (!best || score < best.score) best = { numbers: nums, score, reasons }
      if (score <= maxScore) { exhausted = false; break }
    }

    if (!best) break
    seen.add(best.numbers.join(','))
    out.push({ ...best, exhausted })
  }
  return out
}

/** 조합 특성 요약 — 화면 표시용(합계·홀짝·저고 분포). */
export function describeCombination(numbers) {
  const nums = [...(numbers || [])].sort((a, b) => a - b)
  return {
    numbers: nums,
    sum: nums.reduce((a, b) => a + b, 0),
    odd: nums.filter((n) => n % 2 === 1).length,
    even: nums.filter((n) => n % 2 === 0).length,
    low: nums.filter((n) => n <= BIRTHDAY_MAX).length,
    high: nums.filter((n) => n > BIRTHDAY_MAX).length
  }
}
