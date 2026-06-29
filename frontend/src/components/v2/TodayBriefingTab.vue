<template>
  <div class="today-tab">
    <!-- ① 시장 한 줄 -->
    <div v-if="hasMarketData" class="today-market">
      <span class="tm-item" :class="changeClass(marketData.kospiChangeRate)">
        KOSPI {{ marketData.kospiIndex }} ({{ signed(marketData.kospiChangeRate) }}%)
      </span>
      <span class="tm-item" :class="changeClass(marketData.kosdaqChangeRate)">
        KOSDAQ {{ marketData.kosdaqIndex }} ({{ signed(marketData.kosdaqChangeRate) }}%)
      </span>
      <span v-if="marketData.adr" class="tm-item tm-adr">ADR {{ marketData.adr }}</span>
      <span v-if="marketData.marketStatus" class="tm-status">{{ marketData.marketStatus }}</span>
    </div>

    <!-- ② 오늘의 매수 후보 -->
    <div class="today-section">
      <div class="ts-title-row">
        <h2>📌 오늘의 매수 후보</h2>
        <span class="ts-hint">종합추천 중 BUY 컷(55점) 이상만</span>
      </div>

      <div v-if="candidatesLoading" class="ts-state">후보 분석 중...</div>
      <div v-else-if="buyCandidates.length === 0" class="ts-state empty">
        오늘은 매수 컷(55점)을 넘은 후보가 없습니다 — 관망이 결론입니다.
      </div>
      <div v-else class="candidate-list">
        <div v-for="(c, i) in buyCandidates" :key="c.stockCode"
             class="candidate-card" @click="$emit('open-stock', c.stockCode)">
          <span class="cc-rank">#{{ i + 1 }}</span>
          <div class="cc-main">
            <div class="cc-head">
              <span class="cc-name">{{ c.stockName }}</span>
              <span class="cc-grade" :class="gradeClass(c.totalScore)">{{ gradeLabel(c.totalScore) }}</span>
              <span class="cc-score">{{ c.totalScore }}점</span>
            </div>
            <div class="cc-tags">
              <span v-if="catalysts[c.stockCode]" class="cc-catalyst" :class="'cat-' + catalysts[c.stockCode].direction.toLowerCase()">
                🔥 재료: {{ catalysts[c.stockCode].typeLabel }}({{ directionLabel(catalysts[c.stockCode].direction) }})
              </span>
              <span v-for="(tag, ti) in (c.tags || []).slice(0, 2)" :key="ti" class="cc-tag">{{ tag }}</span>
            </div>
          </div>
          <div class="cc-price">
            <span v-if="c.currentPrice" class="cc-price-num">{{ Number(c.currentPrice).toLocaleString('ko-KR') }}원</span>
            <span v-if="c.changeRate != null" class="cc-change" :class="changeClass(c.changeRate)">
              {{ signed(c.changeRate) }}%
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- ②-b 차트 타이밍 매수 후보 (검증 전 베타) — momentum 후보와 별도 모듈.
         정배열 안에서 눌린 자리 진입(mean-reversion 타이밍). 후보 없으면 숨김, 단 분석서버 미가용은 명시. -->
    <div class="today-section" v-if="timingCandidates.length || timingLoading || !timingAvailable">
      <div class="ts-title-row">
        <h2>🪝 차트 타이밍 매수 후보</h2>
        <span class="ts-beta">검증 전 베타</span>
      </div>
      <div class="beta-banner">
        ⚠ <strong>검증 전 베타</strong> — 차트기법(정배열·이격도·엔벨로프 눌림목) 기반 진입 타이밍.
        적중률/MDD 검증 전이라 참고용이며 실거래·봇 신호가 아닙니다.
      </div>
      <div v-if="timingLoading" class="ts-state">타이밍 분석 중...</div>
      <!-- 분석서버(python) 미가용 — '신호 없음'과 구분해 명시 -->
      <div v-else-if="!timingAvailable" class="ts-state">⚠ 분석서버 일시 미가용 — 잠시 후 다시 확인해 주세요.</div>
      <div v-else class="candidate-list">
        <div v-for="(c, i) in timingCandidates" :key="c.code"
             class="candidate-card" @click="$emit('open-stock', c.code)">
          <span class="cc-rank">#{{ i + 1 }}</span>
          <div class="cc-main">
            <div class="cc-head">
              <span class="cc-name">{{ c.name }}</span>
              <span class="cc-score">{{ c.timingScore }}/10</span>
            </div>
            <div class="cc-tags">
              <span v-for="(tag, ti) in (c.signals || []).slice(0, 4)" :key="ti" class="cc-tag">{{ tag }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ③ 신뢰도 스트립 — 이 추천을 얼마나 믿어도 되나 -->
    <div v-if="trustLine" class="today-trust">
      <span class="tt-icon">📊</span>
      <span class="tt-text">{{ trustLine }}</span>
    </div>

    <!-- ④ 내 포지션 요약 -->
    <div class="today-section" v-if="portfolio.length">
      <div class="ts-title-row">
        <h2>💼 내 포지션 {{ portfolio.length }}종목</h2>
        <span class="ts-pl" :class="totalProfitLoss >= 0 ? 'positive' : 'negative'">
          평가손익 {{ signed(totalProfitLoss, true) }}원
        </span>
      </div>
      <div class="position-list">
        <div v-for="p in portfolio.slice(0, 3)" :key="p.stockCode"
             class="position-row" @click="$emit('open-stock', p.stockCode)">
          <span class="pr-name">{{ p.stockName }}</span>
          <span class="pr-qty">{{ p.quantity }}주</span>
          <span class="pr-rate" :class="Number(p.profitRate) >= 0 ? 'positive' : 'negative'">
            {{ signed(p.profitRate) }}%
          </span>
        </div>
      </div>
      <button class="ts-more" @click="$emit('navigate', 'trade')">매매 탭에서 전체 보기 →</button>
    </div>

    <!-- ⑤ 도구 바로가기 -->
    <div class="today-tools">
      <button class="tool-btn" @click="$emit('navigate', 'market')">
        <span class="tool-icon">🌐</span><span>시장 · 수급 · 뉴스</span>
      </button>
      <button class="tool-btn" @click="$emit('navigate', 'discover')">
        <span class="tool-icon">🔍</span><span>발굴 · 전략 · 스크리너</span>
      </button>
      <button class="tool-btn" @click="$emit('navigate', 'trade')">
        <span class="tool-icon">⚡</span><span>매매 · 봇 · 성과</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import apiClient, { recommendationAPI, paperTradingAPI } from '../../utils/api';

const props = defineProps({
  marketData: { type: Object, default: null }
});

defineEmits(['open-stock', 'navigate']);

const BUY_CUT = 55;
const STRONG_BUY_CUT = 75;
const MAX_CANDIDATES = 5;

const candidatesLoading = ref(false);
const buyCandidates = ref([]);
const timingLoading = ref(false);
const timingCandidates = ref([]);    // 차트 타이밍(검증 전 베타) — momentum 과 별도
const timingAvailable = ref(true);   // dataAvailable=false → 분석서버 미가용(빈 결과와 구분)
const catalysts = ref({});           // stockCode → catalyst dto
const accuracyStats = ref([]);
const backtestOverall = ref(null);
const portfolio = ref([]);

const hasMarketData = computed(() =>
  !!(props.marketData && props.marketData.kospiIndex));

const totalProfitLoss = computed(() =>
  portfolio.value.reduce((sum, p) => sum + Number(p.profitLoss || 0), 0));

// "이 추천을 얼마나 믿어도 되나" 한 줄 — 적중률(30일) + 트랙레코드(30일) 합성.
const trustLine = computed(() => {
  const parts = [];
  const sb = accuracyStats.value.find(s => s.signalType === 'STRONG_BUY');
  const buy = accuracyStats.value.find(s => s.signalType === 'BUY');
  if (sb) parts.push(`STRONG_BUY 30일 적중률 ${sb.hitRate}% (${sb.hitCount}/${sb.totalSignals}건)`);
  if (buy) parts.push(`BUY ${buy.hitRate}%`);
  const bt = backtestOverall.value;
  if (bt && bt.totalPicks > 0) parts.push(`트랙레코드 30일 평균 ${signed(bt.avgReturn)}% (비용 차감)`);
  return parts.length ? parts.join(' · ') : null;
});

const loadCandidates = async () => {
  candidatesLoading.value = true;
  try {
    const { data } = await recommendationAPI.getTop5();
    const items = data?.data || [];
    buyCandidates.value = items
      .filter(r => Number(r.totalScore) >= BUY_CUT)
      .slice(0, MAX_CANDIDATES);
    loadCatalysts();
  } catch (e) {
    buyCandidates.value = [];
  } finally {
    candidatesLoading.value = false;
  }
};

// 후보별 재료 배지 — best-effort (실패/재료없음(NONE)이면 배지 생략)
const loadCatalysts = async () => {
  for (const c of buyCandidates.value) {
    try {
      const { data } = await apiClient.get(`/stock/${c.stockCode}/catalyst`, {
        params: { stockName: c.stockName }
      });
      const cat = data?.data;
      if (cat && cat.catalystType !== 'NONE') {
        catalysts.value = { ...catalysts.value, [c.stockCode]: cat };
      }
    } catch (e) { /* 배지 생략 */ }
  }
};

// 차트 타이밍 매수 후보 — momentum 과 정반대 objective(추세 안 눌림목). 별도 미검증 모듈.
// best-effort: 후보없음이면 숨김. dataAvailable=false(분석서버 다운)는 '신호 없음'과 구분해 표기.
const loadTimingCandidates = async () => {
  timingLoading.value = true;
  try {
    const { data } = await recommendationAPI.getTrendPullbackTop10();
    const items = data?.data || [];
    timingCandidates.value = items.slice(0, MAX_CANDIDATES);
    timingAvailable.value = data?.dataAvailable !== false;   // 명시적 false 만 미가용
  } catch (e) {
    timingCandidates.value = [];
    timingAvailable.value = false;   // 네트워크 실패 = 미가용
  } finally {
    timingLoading.value = false;
  }
};

const loadTrust = async () => {
  try {
    const { data } = await apiClient.get('/signal-outcomes/accuracy', { params: { days: 30 } });
    if (data?.success) accuracyStats.value = data.data?.stats || [];
  } catch (e) { /* 생략 */ }
  try {
    const { data } = await apiClient.get('/backtest/performance', { params: { days: 30 } });
    if (data?.success) backtestOverall.value = data.data?.overall || null;
  } catch (e) { /* 생략 */ }
};

const loadPortfolio = async () => {
  try {
    const { data } = await paperTradingAPI.getPortfolio();
    const list = data?.data;
    portfolio.value = Array.isArray(list) ? list : [];
  } catch (e) {
    portfolio.value = []; // 미보유/권한 없음 — 섹션 자체를 숨김
  }
};

const gradeLabel = (score) => (Number(score) >= STRONG_BUY_CUT ? '강력 매수' : '매수');
const gradeClass = (score) => (Number(score) >= STRONG_BUY_CUT ? 'grade-strong' : 'grade-buy');
const directionLabel = (d) => ({ POSITIVE: '호재', NEGATIVE: '악재', NEUTRAL: '중립' }[d] || d);
const changeClass = (v) => (Number(v) > 0 ? 'positive' : Number(v) < 0 ? 'negative' : '');
const signed = (v, grouping = false) => {
  if (v == null) return '—';
  const n = Number(v);
  const text = grouping ? Math.abs(n).toLocaleString('ko-KR') : Math.abs(n);
  return `${n > 0 ? '+' : n < 0 ? '-' : ''}${text}`;
};

onMounted(() => {
  loadCandidates();
  loadTimingCandidates();
  loadTrust();
  loadPortfolio();
});
</script>

<style scoped>
.today-tab { display: flex; flex-direction: column; gap: 14px; }

/* ① 시장 한 줄 */
.today-market {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  background: rgba(255, 255, 255, 0.04);
  border-radius: 10px;
  padding: 10px 16px;
  font-size: 13px;
}
.tm-item { font-weight: 600; }
.tm-adr { opacity: 0.7; font-weight: 400; }
.tm-status { margin-left: auto; font-size: 12px; opacity: 0.6; }

/* 공통 섹션 */
.today-section {
  background: rgba(20, 24, 38, 0.85);
  border-radius: 12px;
  padding: 16px 18px;
}
.ts-title-row { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.ts-title-row h2 { margin: 0; font-size: 16px; }
.ts-hint { font-size: 11px; opacity: 0.5; }
.ts-pl { margin-left: auto; font-size: 13px; font-weight: 700; }
.ts-state { padding: 18px 0; text-align: center; font-size: 13px; opacity: 0.6; }
.ts-state.empty { opacity: 0.75; }

/* 차트 타이밍(검증 전 베타) — 미검증 강조 */
.ts-beta {
  margin-left: auto; font-size: 11px; font-weight: 700; color: #fbbf24;
  background: rgba(245, 158, 11, 0.14); padding: 2px 8px; border-radius: 4px;
}
.beta-banner {
  margin: 10px 0 4px; padding: 8px 12px; border-radius: 8px;
  background: rgba(245, 158, 11, 0.12); border: 1px solid rgba(245, 158, 11, 0.4);
  color: #fbbf24; font-size: 12px; line-height: 1.5;
}

/* ② 후보 카드 */
.candidate-list { display: flex; flex-direction: column; gap: 8px; margin-top: 12px; }
.candidate-card {
  display: flex;
  align-items: center;
  gap: 12px;
  background: rgba(255, 255, 255, 0.04);
  border-radius: 10px;
  padding: 12px 14px;
  cursor: pointer;
  transition: background 0.15s;
}
.candidate-card:hover { background: rgba(255, 255, 255, 0.08); }
.cc-rank { font-size: 13px; font-weight: 700; opacity: 0.55; min-width: 26px; }
.cc-main { flex: 1; min-width: 0; }
.cc-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.cc-name { font-size: 14px; font-weight: 700; }
.cc-grade {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 4px;
}
.grade-strong { color: #4ade80; background: rgba(34, 197, 94, 0.15); }
.grade-buy { color: #60a5fa; background: rgba(59, 130, 246, 0.15); }
.cc-score { font-size: 12px; opacity: 0.7; }
.cc-tags { display: flex; gap: 6px; margin-top: 5px; flex-wrap: wrap; }
.cc-tag {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.07);
  opacity: 0.8;
}
.cc-catalyst {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: 4px;
}
.cat-positive { color: #fbbf24; background: rgba(251, 191, 36, 0.14); }
.cat-negative { color: #f87171; background: rgba(239, 68, 68, 0.14); }
.cat-neutral  { color: #cbd5e1; background: rgba(203, 213, 225, 0.12); }
.cc-price { text-align: right; }
.cc-price-num { display: block; font-size: 13px; font-weight: 600; }
.cc-change { font-size: 12px; }

/* ③ 신뢰도 스트립 */
.today-trust {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.04);
  border-radius: 10px;
  padding: 10px 16px;
  font-size: 12.5px;
}
.tt-text { opacity: 0.8; }

/* ④ 포지션 */
.position-list { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
.position-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  font-size: 13px;
  cursor: pointer;
}
.position-row:hover { background: rgba(255, 255, 255, 0.08); }
.pr-name { font-weight: 600; flex: 1; }
.pr-qty { opacity: 0.6; font-size: 12px; }
.pr-rate { font-weight: 700; min-width: 64px; text-align: right; }
.ts-more {
  margin-top: 10px;
  background: none;
  border: none;
  color: #93c5fd;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}

/* ⑤ 도구 바로가기 */
.today-tools {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px;
}
.tool-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 10px;
  color: #cbd5e1;
  font-size: 13px;
  font-weight: 600;
  padding: 14px 10px;
  cursor: pointer;
  transition: all 0.15s;
}
.tool-btn:hover {
  background: rgba(102, 126, 234, 0.15);
  border-color: rgba(102, 126, 234, 0.4);
  color: #a5b4fc;
}
.tool-icon { font-size: 16px; }

.positive { color: #4ade80; }
.negative { color: #f87171; }

@media (max-width: 600px) {
  .today-market { gap: 10px; font-size: 12px; }
  .tm-status { flex-basis: 100%; margin-left: 0; }
}
</style>
