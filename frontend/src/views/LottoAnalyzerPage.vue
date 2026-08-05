<template>
  <div class="lotto-page">
    <header class="page-head">
      <BackButton />
      <div>
        <h1>로또 번호 추천</h1>
        <p class="sub">1~{{ draws.length }}회차 ({{ firstDate }} ~ {{ lastDate }}) 실측 기반</p>
      </div>
    </header>

    <!-- ① 추천 번호 — 화면의 주인공 -->
    <section class="card recommend">
      <div class="rec-head">
        <div>
          <h2>추천 번호</h2>
          <p class="rec-sub">
            혼잡도 최저({{ OPTIMAL_INDEX }}%) 조합만 생성 —
            조건을 만족하는 {{ OPTIMAL_SET_SIZE.toLocaleString() }}개 중에서
          </p>
        </div>
        <div class="gen-controls">
          <select v-model.number="genCount" aria-label="게임 수">
            <option v-for="c in [1, 5, 10]" :key="c" :value="c">{{ c }}게임</option>
          </select>
          <button class="btn-primary" @click="generate">다른 조합 보기</button>
        </div>
      </div>

      <div class="tickets">
        <div v-for="(g, i) in recommended" :key="i" class="ticket">
          <span class="ticket-no">{{ String.fromCharCode(65 + i) }}</span>
          <div class="balls">
            <span v-for="n in g.numbers" :key="n" class="ball" :class="ballClass(n)">{{ n }}</span>
          </div>
          <div class="ticket-side">
            <span class="crowd" :class="g.optimal ? 'good' : 'bad'">
              분배 {{ g.index > 0 ? '+' : '' }}{{ g.index.toFixed(1) }}%
            </span>
            <span class="meta">합 {{ g.features.sum }} · {{ g.features.maxRun }}연속 · 저{{ g.features.low }}고{{ g.features.high }}</span>
          </div>
        </div>
      </div>

      <p class="rec-note">
        <strong>여기 나온 조합은 전부 동점입니다.</strong> 검증된 규칙을 모두 유리하게 만족하는
        지점이 {{ OPTIMAL_INDEX }}%이고, 거기 해당하는 조합이
        {{ OPTIMAL_SET_SIZE.toLocaleString() }}개(전체의 18.2%)나 됩니다.
        <strong>"최선의 번호" 하나는 존재하지 않습니다</strong> — 그래서 그 안에서 무작위로 고릅니다.
        버튼을 다시 눌러도 품질은 그대로이고, 같은 등급의 다른 조합이 나올 뿐입니다.
      </p>

      <p class="rec-note self-defeat">
        오히려 <strong>고정하면 안 됩니다.</strong> 이 전략의 이점은 "남들과 다르다"에서만 나옵니다.
        모두가 같은 '최선의 번호'를 쓰는 순간 그게 가장 인기 있는 조합이 되어 이점이 사라집니다.
        무작위성은 타협이 아니라 전략의 필수 조건입니다.
        당첨 확률은 여전히 어떤 조합이든 1/{{ TOTAL_COMBINATIONS.toLocaleString() }}로 같습니다.
      </p>
    </section>

    <!-- ② 왜 이 번호인가 -->
    <section class="card">
      <h2>왜 이렇게 뽑았나</h2>
      <p class="explain">
        로또 1등은 상금을 당첨자 수로 나눕니다(pari-mutuel). 확률은 못 바꿔도
        <strong>몇 명과 나누는지는 바꿀 수 있습니다.</strong> 그래서 실제 1등 당첨자 수가 조합의
        어떤 특성에 따라 달라졌는지를 {{ analyzedDraws.toLocaleString() }}회차로 측정하고,
        순열검정(20,000회)에서 <strong>p &lt; 0.05 를 통과한 규칙만</strong> 사용했습니다.
      </p>

      <table class="rules">
        <thead>
          <tr><th>특성</th><th class="r">당첨자 수</th><th class="r">p값</th><th class="r">표본</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in CROWD_RULES" :key="r.key">
            <td>
              <strong>{{ r.label }}</strong>
              <span class="rule-detail">{{ r.detail }}</span>
            </td>
            <td class="r" :class="r.effect > 0 ? 'bad' : 'good'">
              {{ r.effect > 0 ? '+' : '' }}{{ r.effect }}%
            </td>
            <td class="r mono">{{ r.p.toFixed(3) }}</td>
            <td class="r mono">{{ r.n }}</td>
          </tr>
        </tbody>
      </table>

      <p class="explain warn-note">
        <strong>연속수는 통념과 반대였습니다.</strong> 처음엔 "눈에 띄는 패턴이라 인기 있을 것"으로
        보고 감점했는데, 실측은 연속수가 있으면 당첨자가 오히려 7.6% <em>적었습니다</em>.
        사람들이 연속 번호를 우연 같지 않다며 피하기 때문으로 보입니다. 검증 없이 넣었던 규칙이
        추천을 거꾸로 몰 뻔했습니다.
      </p>

      <details class="rejected">
        <summary>검증에 실패해 제외한 규칙 {{ REJECTED_RULES.length }}개</summary>
        <ul>
          <li v-for="r in REJECTED_RULES" :key="r.label">
            <strong>{{ r.label }}</strong> — {{ r.reason }}
          </li>
        </ul>
      </details>

      <h3>번호합 구간별 실측</h3>
      <table class="bias-table">
        <thead>
          <tr><th>번호합</th><th>회차 수</th><th>평균 1등 당첨자</th><th>평균 대비</th><th /></tr>
        </thead>
        <tbody>
          <tr v-for="b in sumBands.buckets" :key="b.label">
            <td class="mono">{{ b.label }}</td>
            <td class="mono">{{ b.samples }}</td>
            <td class="mono">{{ b.avgWinners.toFixed(2) }}</td>
            <td class="mono" :class="b.vsAverage > 0 ? 'bad' : 'good'">
              {{ b.vsAverage > 0 ? '+' : '' }}{{ b.vsAverage.toFixed(1) }}%
            </td>
            <td class="bar-cell"><div class="mini-bar" :style="{ width: barWidth(b.avgWinners) }" /></td>
          </tr>
        </tbody>
      </table>
      <h3>최적 조건과 그 상한</h3>
      <p class="explain">
        위 규칙을 모두 유리하게 만족시키는 조건은
        <strong>번호합 {{ OPTIMAL.sumMin }}~{{ OPTIMAL.sumMax }} + 연속수 {{ OPTIMAL.runMin }}~{{ OPTIMAL.runMax }}개</strong>입니다.
        상한이 붙은 이유가 있습니다. 상한 없이 점수만 따르면
        <code>[40,41,42,43,44,45]</code>(합 255, 6연속)가 최적으로 계산되는데, 이건 규칙을 검증한
        구간을 한참 벗어난 외삽이고 실제로는 누가 봐도 눈에 띄어 오히려 많이 고를 조합입니다.
        모델이 못 보는 영역이라 아예 제외했습니다. 상한 근거는 실제 당첨 조합의 관측 분포입니다
        (번호합 p99 = 208, 연속 4개는 1,235회 중 6회뿐이라 표본이 있는 3까지만 인정).
      </p>

      <p class="caveat">
        효과 크기는 ±10% 수준으로 작습니다. 번호합이 당첨자 수 변동에서 설명하는 비율은
        1.2%에 불과합니다 — 나머지는 그 주의 판매량과 우연입니다. 유의하다는 건
        "방향이 우연이 아니다"는 뜻이지 "크다"는 뜻이 아닙니다.
      </p>
    </section>

    <!-- ③ 예측이 안 되는 이유 -->
    <section class="card">
      <h2>번호 자체는 예측할 수 없습니다</h2>
      <p class="explain">
        추첨이 균등한지 직접 검정했습니다. 카이제곱
        <strong>{{ stats?.chi2.toFixed(2) }}</strong>로 5% 임계값
        {{ stats?.critical.toFixed(2) }}에 한참 못 미쳐 <strong>편향 근거가 없습니다.</strong>
        번호당 기대 출현이 {{ stats?.expected.toFixed(1) }}회인데 실제로는
        {{ stats?.least.number }}번 {{ stats?.least.count }}회부터
        {{ stats?.most.number }}번 {{ stats?.most.count }}회까지 벌어져 있어 핫/콜드처럼 보이지만,
        관측 표준편차 {{ stats?.stdev.toFixed(1) }}는 완전 무작위일 때의 이론값
        {{ stats?.theoreticalStdev.toFixed(1) }}보다 오히려 <strong>좁습니다.</strong>
        그 편차가 곧 노이즈라는 뜻입니다.
      </p>

      <div class="freq-chart">
        <div v-for="f in stats?.frequency" :key="f.number" class="bar-wrap" :title="`${f.number}번 — ${f.count}회`">
          <div class="bar" :style="{ height: freqHeight(f.count) }" />
          <span class="bar-label">{{ f.number }}</span>
        </div>
      </div>
      <p class="axis-note">
        번호별 출현 횟수 · 점선 = 기대값 {{ stats?.expected.toFixed(1) }}회.
        <span v-if="method.autoPct">
          참고로 1등 당첨자의 {{ method.autoPct.toFixed(1) }}%가 자동 구매였습니다.
        </span>
      </p>
    </section>

    <p class="disclaimer">
      환급률이 50% 남짓이라 <strong>이걸 다 해도 기댓값은 여전히 마이너스입니다.</strong>
      손실을 줄여주는 게 아니라, 어차피 살 거면 당첨 시 덜 쪼개지게 사는 것뿐입니다.
    </p>

    <footer class="src">
      데이터: 1~{{ draws.length }}회차 정적 스냅샷({{ lastDate }} 기준).
      동행복권 사이트 개편으로 공식 API 가 폐지되어 공개 아카이브(smok95/lotto)에서 확보했습니다.
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
  purchaseMethodStats,
  sumBandAnalysis,
  generateRecommendations,
  CROWD_RULES,
  REJECTED_RULES,
  TOTAL_COMBINATIONS,
  OPTIMAL,
  OPTIMAL_SET_SIZE,
  OPTIMAL_INDEX
} from '../utils/lottoAnalysis'

const stats = computed(() => chiSquareUniformity(draws))
const method = computed(() => purchaseMethodStats(draws))
const sumBands = computed(() => sumBandAnalysis(draws))
const analyzedDraws = computed(() => sumBands.value.buckets.reduce((a, b) => a + b.samples, 0))

const firstDate = computed(() => draws[0]?.d ?? '-')
const lastDate = computed(() => draws[draws.length - 1]?.d ?? '-')

const genCount = ref(5)
const recommended = ref([])

function generate() {
  recommended.value = generateRecommendations(genCount.value)
}
generate()

function freqHeight(count) {
  const max = stats.value?.most.count ?? 1
  const min = stats.value?.least.count ?? 0
  return `${20 + ((count - min) / Math.max(max - min, 1)) * 80}%`
}

function barWidth(v) {
  const max = Math.max(...sumBands.value.buckets.map((b) => b.avgWinners), 1)
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

.page-head { display: flex; align-items: center; gap: 16px; margin-bottom: 20px; }
.page-head h1 { margin: 0; font-size: 24px; font-weight: 700; }
.sub { margin: 4px 0 0; font-size: 13px; color: #9ca3af; }

.card {
  background: rgba(24,24,38,.85); border: 1px solid rgba(148,163,184,.15);
  border-radius: 14px; padding: 20px; margin-bottom: 18px;
}
.card h2 { margin: 0 0 12px; font-size: 16px; color: #e5e7eb; }
.card h3 { margin: 22px 0 8px; font-size: 14px; color: #cbd5e1; }

.recommend { border-color: rgba(99,102,241,.35); background: linear-gradient(135deg, rgba(28,28,48,.95), rgba(20,22,40,.95)); }
.rec-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 14px; }
.rec-head h2 { margin: 0; font-size: 18px; color: #a5b4fc; }
.rec-sub { margin: 4px 0 0; font-size: 12px; color: #818cf8; }
.self-defeat { margin-top: 8px; background: rgba(168,85,247,.07); border-color: rgba(168,85,247,.22); color: #ddd6fe; }
code { background: rgba(15,15,28,.8); padding: 1px 5px; border-radius: 4px; font-size: 12px; color: #fbbf24; }

.gen-controls { display: flex; align-items: center; gap: 8px; }
.gen-controls select {
  background: rgba(15,15,28,.9); color: #e5e7eb;
  border: 1px solid rgba(148,163,184,.25); border-radius: 8px; padding: 7px 10px; font-size: 13px;
}
.btn-primary {
  background: linear-gradient(135deg, #6366f1, #4338ca); color: #fff; border: 0;
  border-radius: 8px; padding: 8px 18px; font-size: 13px; font-weight: 600; cursor: pointer;
}
.btn-primary:hover { filter: brightness(1.12); }

.tickets { display: flex; flex-direction: column; gap: 10px; }
.ticket {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  background: rgba(12,12,24,.6); border: 1px solid rgba(148,163,184,.12);
  border-radius: 10px; padding: 12px 14px;
}
.ticket-no { font-weight: 700; color: #a5b4fc; width: 16px; }
.balls { display: flex; gap: 6px; }
.ball {
  width: 36px; height: 36px; border-radius: 50%; display: flex; align-items: center; justify-content: center;
  font-size: 14px; font-weight: 700; color: #1f2937;
}
.ball.y { background: #fbbf24; } .ball.b { background: #60a5fa; } .ball.r { background: #f87171; }
.ball.g { background: #9ca3af; } .ball.e { background: #4ade80; }
.ticket-side { margin-left: auto; text-align: right; display: flex; flex-direction: column; gap: 2px; }
.crowd { font-size: 13px; font-weight: 700; font-variant-numeric: tabular-nums; }
.crowd.good { color: #4ade80; } .crowd.bad { color: #fbbf24; }
.ticket-side .meta { font-size: 11px; color: #6b7280; }

.rec-note {
  margin: 14px 0 0; padding: 11px 13px; font-size: 12.5px; line-height: 1.7;
  background: rgba(99,102,241,.08); border: 1px solid rgba(99,102,241,.22);
  border-radius: 8px; color: #c7d2fe;
}

.explain { font-size: 14px; line-height: 1.75; color: #cbd5e1; margin: 12px 0; }
.explain strong { color: #f3f4f6; }
.warn-note {
  padding: 12px 14px; background: rgba(251,191,36,.07);
  border-left: 3px solid rgba(251,191,36,.5); border-radius: 0 8px 8px 0; font-size: 13px;
}

.rules, .bias-table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 13px; }
.rules th, .rules td, .bias-table th, .bias-table td {
  padding: 9px 10px; text-align: left; border-bottom: 1px solid rgba(148,163,184,.12); vertical-align: top;
}
.rules th, .bias-table th { color: #9ca3af; font-weight: 500; font-size: 12px; }
.r { text-align: right; }
.mono { font-variant-numeric: tabular-nums; }
.good { color: #4ade80; } .bad { color: #fbbf24; }
.rule-detail { display: block; font-size: 11.5px; color: #6b7280; margin-top: 3px; font-weight: 400; }
.bias-table .bar-cell { width: 30%; }
.mini-bar { height: 8px; border-radius: 4px; background: linear-gradient(90deg, #6366f1, #a855f7); }

.rejected { margin-top: 14px; font-size: 12.5px; color: #9ca3af; }
.rejected summary { cursor: pointer; color: #a5b4fc; }
.rejected ul { margin: 8px 0 0; padding-left: 18px; line-height: 1.8; }
.rejected strong { color: #cbd5e1; }

.caveat { font-size: 12.5px; line-height: 1.7; color: #9ca3af; margin: 12px 0 0; }

.freq-chart {
  display: flex; align-items: flex-end; gap: 2px; height: 130px;
  padding: 10px 4px 0; margin-top: 14px; overflow-x: auto;
  border-bottom: 1px dashed rgba(148,163,184,.35);
}
.bar-wrap { flex: 1 0 14px; display: flex; flex-direction: column; align-items: center; height: 100%; justify-content: flex-end; }
.bar { width: 100%; background: linear-gradient(180deg, #6366f1, #4338ca); border-radius: 2px 2px 0 0; min-height: 3px; }
.bar-label { font-size: 8px; color: #6b7280; margin-top: 2px; }
.axis-note { font-size: 11.5px; color: #6b7280; margin: 6px 0 0; line-height: 1.6; }

.disclaimer {
  margin: 0 0 16px; padding: 13px 15px; font-size: 13px; line-height: 1.7;
  background: rgba(251,191,36,.08); border: 1px solid rgba(251,191,36,.25);
  border-radius: 10px; color: #fcd34d;
}
.src { font-size: 11px; color: #6b7280; line-height: 1.6; text-align: center; }

@media (max-width: 640px) {
  .ball { width: 30px; height: 30px; font-size: 12.5px; }
  .ticket-side { margin-left: 0; width: 100%; text-align: left; flex-direction: row; gap: 10px; align-items: baseline; }
}
</style>
