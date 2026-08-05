<template>
  <div class="lotto-page">
    <header class="page-head">
      <BackButton />
      <div>
        <h1>로또 6/45 분석</h1>
        <p class="sub">
          {{ stats?.draws.toLocaleString() }}회차 ({{ firstDate }} ~ {{ lastDate }}) 실측 데이터
        </p>
      </div>
    </header>

    <!-- 결론 배너 — 오해를 먼저 차단한다 -->
    <section class="verdict">
      <h2>당첨 확률을 높이는 방법은 없습니다</h2>
      <p>
        매 회차는 독립이고 균등합니다. 과거 {{ stats?.draws.toLocaleString() }}회를 전부 넣어도
        다음 회차 확률은 움직이지 않습니다. 어떤 조합이든 1등 확률은
        <strong>1/{{ TOTAL_COMBINATIONS.toLocaleString() }}</strong>로 같습니다.
      </p>
      <p>
        이 화면은 번호를 예측하지 않습니다. 아래 검정으로 <strong>'핫넘버'가 착시임을 확인</strong>하고,
        1등 상금을 당첨자 수로 나누는 구조를 이용해
        <strong>당첨 시 덜 쪼개지는 조합</strong>을 만들어줄 뿐입니다.
      </p>
    </section>

    <!-- 1. 균등성 검정 -->
    <section class="card">
      <h2>1. 추첨은 공정한가 — 카이제곱 균등성 검정</h2>

      <div v-if="stats" class="chi-grid">
        <div class="stat">
          <span class="label">카이제곱 통계량</span>
          <strong class="value">{{ stats.chi2.toFixed(2) }}</strong>
          <span class="note">자유도 44</span>
        </div>
        <div class="stat">
          <span class="label">5% 임계값</span>
          <strong class="value">{{ stats.critical.toFixed(2) }}</strong>
          <span class="note">넘으면 편향 의심</span>
        </div>
        <div class="stat" :class="stats.uniform ? 'ok' : 'warn'">
          <span class="label">판정</span>
          <strong class="value">{{ stats.uniform ? '균등' : '편향 의심' }}</strong>
          <span class="note">{{ stats.uniform ? '편향 근거 없음' : '재검토 필요' }}</span>
        </div>
      </div>

      <p class="explain">
        번호당 기대 출현은 <strong>{{ stats?.expected.toFixed(1) }}회</strong>인데, 실제로는
        <strong>{{ stats?.least.number }}번 {{ stats?.least.count }}회</strong>부터
        <strong>{{ stats?.most.number }}번 {{ stats?.most.count }}회</strong>까지 흩어져 있습니다.
        이걸 표로 정렬하면 핫/콜드처럼 보이지만, 관측 표준편차 {{ stats?.stdev.toFixed(1) }}는
        완전 무작위일 때의 이론값 {{ stats?.theoreticalStdev.toFixed(1) }}와 사실상 같습니다.
        <strong>편차가 곧 노이즈라는 뜻입니다.</strong>
      </p>

      <div class="freq-chart">
        <div
          v-for="f in stats?.frequency"
          :key="f.number"
          class="bar-wrap"
          :title="`${f.number}번 — ${f.count}회`"
        >
          <div class="bar" :style="{ height: barHeight(f.count) }" />
          <span class="bar-label">{{ f.number }}</span>
        </div>
      </div>
      <p class="axis-note">점선 = 기대 출현 횟수({{ stats?.expected.toFixed(1) }}회)</p>
    </section>

    <!-- 2. 생일 편향 실측 -->
    <section class="card">
      <h2>2. 유일하게 진짜인 것 — 남들이 고르는 번호 피하기</h2>
      <p class="explain">
        로또 1등은 총 상금을 당첨자 수로 나눕니다. 확률은 못 바꿔도
        <strong>당첨됐을 때 몇 명과 나누는지는 바꿀 수 있습니다.</strong>
        아래는 당첨 조합에 1~31(생일로 고를 수 있는 범위) 번호가 몇 개 들어있었는지에 따라
        실제 1등 당첨자 수가 어떻게 달라졌는지 본 것입니다. 판매액이 20년간 크게 늘었으므로
        판매액으로 정규화했습니다.
      </p>

      <table class="bias-table">
        <thead>
          <tr><th>조합 내 1~31 개수</th><th>회차 수</th><th>평균 1등 당첨자</th><th /></tr>
        </thead>
        <tbody>
          <tr v-for="b in bias.buckets" :key="b.lowCount">
            <td>{{ b.lowCount }}개</td>
            <td>{{ b.samples }}</td>
            <td class="num">{{ b.avgWinners.toFixed(2) }}</td>
            <td class="bar-cell">
              <div class="mini-bar" :style="{ width: biasBarWidth(b.avgWinners) }" />
            </td>
          </tr>
        </tbody>
      </table>

      <p v-if="bias.ratio" class="explain">
        생일 범위에 4개 이상 몰린 조합은 그렇지 않은 조합보다 1등 당첨자가 평균
        <strong>{{ ((bias.ratio - 1) * 100).toFixed(1) }}% 많았습니다.</strong>
        즉 같은 확률로 당첨돼도 그만큼 더 잘게 쪼개집니다. 32~45를 섞어 쓰는 게
        <em>확률</em>이 아니라 <em>수령액</em> 면에서 유리한 이유입니다.
      </p>

      <p v-if="method.autoPct" class="explain sub-note">
        참고로 1등 당첨자의 <strong>{{ method.autoPct.toFixed(1) }}%가 자동</strong> 구매였습니다
        (집계된 {{ method.covered.toLocaleString() }}회차 기준 — 초기 회차는 미집계).
      </p>
    </section>

    <!-- 3. 생성기 -->
    <section class="card">
      <h2>3. 인기 조합 회피 생성기</h2>
      <p class="explain">
        위 편향들을 피해서 뽑습니다. <strong>당첨 확률은 아무 조합과도 동일합니다.</strong>
        당첨될 경우 나눠 가질 사람이 적을 가능성이 높은 조합일 뿐입니다.
      </p>

      <div class="gen-controls">
        <label>
          게임 수
          <select v-model.number="genCount">
            <option v-for="c in [1, 5, 10]" :key="c" :value="c">{{ c }}게임</option>
          </select>
        </label>
        <button class="btn-primary" @click="generate">번호 생성</button>
      </div>

      <div v-if="generated.length" class="tickets">
        <div v-for="(g, i) in generated" :key="i" class="ticket">
          <span class="ticket-no">{{ String.fromCharCode(65 + i) }}</span>
          <div class="balls">
            <span v-for="n in g.numbers" :key="n" class="ball" :class="ballClass(n)">{{ n }}</span>
          </div>
          <div class="ticket-meta">
            <span>합 {{ describe(g.numbers).sum }}</span>
            <span>홀{{ describe(g.numbers).odd }}·짝{{ describe(g.numbers).even }}</span>
            <span>저{{ describe(g.numbers).low }}·고{{ describe(g.numbers).high }}</span>
            <span v-if="g.exhausted" class="flag-warn">조건 미달(재시도 소진)</span>
            <span v-else class="flag-ok">회피 패턴 없음</span>
          </div>
        </div>
      </div>

      <p class="disclaimer">
        환급률이 50% 남짓이라 <strong>이걸 다 해도 기댓값은 여전히 마이너스입니다.</strong>
        손실을 줄여주는 게 아니라, 어차피 살 거면 당첨 시 덜 쪼개지게 사는 것뿐입니다.
      </p>
    </section>

    <footer class="src">
      데이터: 1~{{ draws.length }}회차 정적 스냅샷 ({{ lastDate }} 기준).
      동행복권 사이트 개편으로 공식 API가 폐지되어 공개 아카이브(smok95/lotto)에서 확보했습니다.
      비공식 출처라 오차 가능성이 있으며, 정확한 당첨번호는 동행복권에서 확인하세요.
    </footer>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import BackButton from '../components/BackButton.vue'
import draws from '../data/lottoDraws.json'
import {
  chiSquareUniformity,
  birthdayBiasAnalysis,
  purchaseMethodStats,
  generateCombinations,
  describeCombination,
  TOTAL_COMBINATIONS
} from '../utils/lottoAnalysis'

const stats = computed(() => chiSquareUniformity(draws))
const bias = computed(() => birthdayBiasAnalysis(draws))
const method = computed(() => purchaseMethodStats(draws))

const firstDate = computed(() => draws[0]?.d ?? '-')
const lastDate = computed(() => draws[draws.length - 1]?.d ?? '-')

const genCount = ref(5)
const generated = ref([])

function generate() {
  generated.value = generateCombinations(genCount.value, { pastDraws: draws })
}
generate()

const describe = describeCombination

/** 최대 출현 횟수를 100% 로 둔 상대 높이 — 절대 0 이 아니라 차이를 보이게 한다. */
function barHeight(count) {
  const max = stats.value?.most.count ?? 1
  const min = stats.value?.least.count ?? 0
  const span = Math.max(max - min, 1)
  return `${20 + ((count - min) / span) * 80}%`
}

function biasBarWidth(v) {
  const max = Math.max(...bias.value.buckets.map((b) => b.avgWinners), 1)
  return `${(v / max) * 100}%`
}

/** 동행복권 공식 색 구간(1-10 노랑 / 11-20 파랑 / 21-30 빨강 / 31-40 회색 / 41-45 초록). */
function ballClass(n) {
  if (n <= 10) return 'y'
  if (n <= 20) return 'b'
  if (n <= 30) return 'r'
  if (n <= 40) return 'g'
  return 'e'
}
</script>

<style scoped>
.lotto-page { max-width: 1000px; margin: 0 auto; padding: 24px 16px 64px; color: #e5e7eb; }

.page-head { display: flex; align-items: center; gap: 16px; margin-bottom: 24px; }
.page-head h1 { margin: 0; font-size: 24px; font-weight: 700; }
.sub { margin: 4px 0 0; font-size: 13px; color: #9ca3af; }

.verdict {
  background: linear-gradient(135deg, rgba(30,30,50,.9), rgba(20,25,40,.9));
  border: 2px solid rgba(99,102,241,.3); border-radius: 14px; padding: 20px; margin-bottom: 20px;
}
.verdict h2 { margin: 0 0 10px; font-size: 17px; color: #a5b4fc; }
.verdict p { margin: 0 0 8px; font-size: 14px; line-height: 1.7; color: #cbd5e1; }
.verdict p:last-child { margin-bottom: 0; }

.card {
  background: rgba(24,24,38,.85); border: 1px solid rgba(148,163,184,.15);
  border-radius: 14px; padding: 20px; margin-bottom: 20px;
}
.card h2 { margin: 0 0 12px; font-size: 16px; color: #e5e7eb; }
.explain { font-size: 14px; line-height: 1.75; color: #cbd5e1; margin: 12px 0; }
.explain strong { color: #f3f4f6; }
.sub-note { font-size: 13px; color: #9ca3af; }

.chi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.stat {
  background: rgba(15,15,28,.7); border: 1px solid rgba(148,163,184,.12);
  border-radius: 10px; padding: 14px; display: flex; flex-direction: column; gap: 4px;
}
.stat .label { font-size: 12px; color: #9ca3af; }
.stat .value { font-size: 22px; font-weight: 700; }
.stat .note { font-size: 11px; color: #6b7280; }
.stat.ok { border-color: rgba(34,197,94,.4); }
.stat.ok .value { color: #4ade80; }
.stat.warn { border-color: rgba(239,68,68,.4); }
.stat.warn .value { color: #f87171; }

.freq-chart {
  display: flex; align-items: flex-end; gap: 2px; height: 140px;
  padding: 10px 4px 0; margin-top: 16px; overflow-x: auto;
  border-bottom: 1px dashed rgba(148,163,184,.35);
}
.bar-wrap { flex: 1 0 14px; display: flex; flex-direction: column; align-items: center; height: 100%; justify-content: flex-end; }
.bar { width: 100%; background: linear-gradient(180deg, #6366f1, #4338ca); border-radius: 2px 2px 0 0; min-height: 3px; }
.bar-label { font-size: 8px; color: #6b7280; margin-top: 2px; }
.axis-note { font-size: 11px; color: #6b7280; margin: 6px 0 0; }

.bias-table { width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 13px; }
.bias-table th, .bias-table td { padding: 8px 10px; text-align: left; border-bottom: 1px solid rgba(148,163,184,.12); }
.bias-table th { color: #9ca3af; font-weight: 500; font-size: 12px; }
.bias-table .num { font-variant-numeric: tabular-nums; font-weight: 600; }
.bias-table .bar-cell { width: 40%; }
.mini-bar { height: 8px; border-radius: 4px; background: linear-gradient(90deg, #6366f1, #a855f7); }

.gen-controls { display: flex; align-items: center; gap: 12px; margin: 14px 0; flex-wrap: wrap; }
.gen-controls label { font-size: 13px; color: #9ca3af; display: flex; align-items: center; gap: 6px; }
.gen-controls select {
  background: rgba(15,15,28,.9); color: #e5e7eb;
  border: 1px solid rgba(148,163,184,.25); border-radius: 8px; padding: 6px 10px; font-size: 13px;
}
.btn-primary {
  background: linear-gradient(135deg, #6366f1, #4338ca); color: #fff; border: 0;
  border-radius: 8px; padding: 8px 18px; font-size: 13px; font-weight: 600; cursor: pointer;
}
.btn-primary:hover { filter: brightness(1.12); }

.tickets { display: flex; flex-direction: column; gap: 10px; margin-top: 12px; }
.ticket {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  background: rgba(15,15,28,.6); border: 1px solid rgba(148,163,184,.12);
  border-radius: 10px; padding: 12px 14px;
}
.ticket-no { font-weight: 700; color: #a5b4fc; width: 18px; }
.balls { display: flex; gap: 6px; }
.ball {
  width: 34px; height: 34px; border-radius: 50%; display: flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 700; color: #1f2937;
}
.ball.y { background: #fbbf24; }
.ball.b { background: #60a5fa; }
.ball.r { background: #f87171; }
.ball.g { background: #9ca3af; }
.ball.e { background: #4ade80; }
.ticket-meta { display: flex; gap: 10px; font-size: 11px; color: #9ca3af; flex-wrap: wrap; margin-left: auto; }
.flag-ok { color: #4ade80; }
.flag-warn { color: #fbbf24; }

.disclaimer {
  margin: 16px 0 0; padding: 12px 14px; font-size: 13px; line-height: 1.7;
  background: rgba(251,191,36,.08); border: 1px solid rgba(251,191,36,.25);
  border-radius: 8px; color: #fcd34d;
}
.src { font-size: 11px; color: #6b7280; line-height: 1.6; text-align: center; }

@media (max-width: 640px) {
  .ball { width: 28px; height: 28px; font-size: 12px; }
  .ticket-meta { margin-left: 0; width: 100%; }
}
</style>
