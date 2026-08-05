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

    <!-- ①-b 간밤 미국장 (보조 tilt · 미검증 참고 · regime 산식 미편입, 독립 표시) -->
    <div v-if="overnightAvailable && overnight" class="today-overnight">
      <span class="ov-label">🌙 간밤 미국장</span>
      <span class="ov-tilt" :class="overnightTiltClass(overnight.tilt)">{{ overnightTiltLabel(overnight.tilt) }}</span>
      <span v-if="overnight.drivers && overnight.drivers.length" class="ov-drivers">{{ overnight.drivers.join(' · ') }}</span>
      <span class="badge-unverified ov-beta">미검증 참고</span>
    </div>

    <!-- ①-c 매크로 tilt (VKOSPI·국고3년·SOX 추세 · 미검증 참고 · regime 산식 미편입, 독립 표시 · P3-7) -->
    <div v-if="macroAvailable && macroTilt" class="today-macro">
      <span class="ov-label">🌐 매크로</span>
      <span class="ov-tilt" :class="macroTiltClass(macroTilt.tilt)">{{ macroTiltLabel(macroTilt.tilt) }}</span>
      <span v-if="macroTilt.drivers && macroTilt.drivers.length" class="ov-drivers">{{ macroTilt.drivers.join(' · ') }}</span>
      <span class="badge-unverified ov-beta">미검증 참고</span>
    </div>

    <!-- ② 오늘의 매수 후보 -->
    <div class="today-section">
      <div class="ts-title-row">
        <h2>📌 오늘의 매수 후보</h2>
        <span class="ts-hint">종합추천 중 BUY 컷(55점) 이상만</span>
        <span v-if="recDataTime" class="ts-asof">{{ recDataTime }}</span>
        <span v-if="!recRealtime" class="ts-stale">실시간 아님 · 마지막 계산 스냅샷</span>
      </div>

      <!-- 신뢰도(실측) — 후보 바로 위에서 "이 점수를 얼마나 믿어도 되나"를 먼저 보여준다.
           소스: accuracy-by-band(보드 신호 격리 + phase-38 컷오프 이후 forward 실측). -->
      <div v-if="trustBands.length" class="today-trust">
        <div class="tt-row">
          <span class="tt-icon">📊</span>
          <span class="tt-title">실측 적중률 <template v-if="trustSince">({{ trustSince }}~ · 3거래일)</template></span>
          <span v-for="b in trustBands" :key="b.band" class="tt-chip" :class="{ 'tt-weak': b.totalSignals < 30 }">
            {{ b.band }}점 {{ b.hitRate }}%
            <em>({{ b.totalSignals }}건{{ b.totalSignals < 30 ? '·표본부족' : '' }})</em>
          </span>
        </div>
        <div v-if="trustCaution" class="tt-caution">⚠ {{ trustCaution }}</div>
      </div>

      <div v-if="candidatesLoading" class="ts-state">후보 분석 중...</div>
      <div v-else-if="buyCandidates.length === 0" class="ts-state empty">
        오늘은 매수 컷(55점)을 넘은 후보가 없습니다 — 관망이 결론입니다.
      </div>
      <div v-else class="candidate-list">
        <div v-for="(c, i) in buyCandidates" :key="c.stockCode"
             class="candidate-card" role="button" tabindex="0"
             @click="$emit('open-stock', c.stockCode)"
             @keydown.enter="$emit('open-stock', c.stockCode)">
          <span class="cc-rank">#{{ i + 1 }}</span>
          <div class="cc-main">
            <div class="cc-head">
              <span class="cc-name">{{ c.stockName }}</span>
              <span class="cc-grade" :class="gradeClass(c.totalScore)">{{ gradeLabel(c.totalScore) }}</span>
              <span class="cc-score">{{ c.totalScore }}점</span>
            </div>
            <div class="cc-tags">
              <span v-if="catalysts[c.stockCode]" class="cc-catalyst" :class="'cat-' + catalysts[c.stockCode].direction.toLowerCase()">
                🔥 재료: {{ catalysts[c.stockCode].typeLabel }}({{ directionLabel(catalysts[c.stockCode].direction) }}){{ catalystAgeLabel(catalysts[c.stockCode]) }}
              </span>
              <span v-for="(tag, ti) in displayTags(c)" :key="ti" class="cc-tag" :class="{ 'cc-tag-warn': tag.startsWith('⚠') }">{{ tag }}</span>
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

    <!-- ③ 내 포지션 요약 — 내 돈이 걸린 정보라 후보 바로 다음(위) -->
    <div class="today-section" v-if="portfolio.length">
      <div class="ts-title-row">
        <h2>💼 내 포지션 {{ portfolio.length }}종목</h2>
        <span class="ts-pl" :class="totalProfitLoss >= 0 ? 'positive' : 'negative'">
          평가손익 {{ signed(totalProfitLoss, true) }}원
        </span>
      </div>
      <div class="position-list">
        <div v-for="p in portfolio.slice(0, 3)" :key="p.stockCode"
             class="position-row" role="button" tabindex="0"
             @click="$emit('open-stock', p.stockCode)"
             @keydown.enter="$emit('open-stock', p.stockCode)">
          <span class="pr-name">{{ p.stockName }}</span>
          <span class="pr-qty">{{ p.quantity }}주</span>
          <span class="pr-rate" :class="Number(p.profitRate) >= 0 ? 'positive' : 'negative'">
            {{ signed(p.profitRate) }}%
          </span>
        </div>
      </div>
      <button class="ts-more" @click="$emit('navigate', 'trade')">매매 탭에서 전체 보기 →</button>
    </div>

    <!-- ④ 시간대 신호(장전 주목주/장후 마감) — 발굴에서 이동(2026-07-01). hub 슬롯 주입(데이터/CSS=hub scope). -->
    <slot name="phase-signals" />

    <!-- ⑤ 관심종목(접힘 기본) — 발굴에서 이동(2026-07-01). hub 슬롯 주입. -->
    <slot name="watchlist" />

    <!-- ⑥ 차트 신호 관찰 — momentum 후보와 별도 모듈. 백테스트(P2-12) 결과 적중률 31%·점수 역상관
         (승격불가)이라 '매수 후보' 아님. 맨 아래 + 접기 기본(우선순위 최하) + 점수 미표시(역상관 오해 방지). -->
    <div class="today-section today-observe" v-if="timingCandidates.length || timingLoading || !timingAvailable">
      <div class="ts-title-row">
        <h2>🪝 차트 타이밍 관찰</h2>
        <span class="badge-unverified ts-beta ts-poor">예측력 미검증</span>
        <button class="btn-ghost ts-toggle" @click="timingExpanded = !timingExpanded">
          {{ timingExpanded ? '접기' : '펼치기' }}
        </button>
      </div>
      <!-- 접힘: 백테스트 실측 한 줄(매수 신호 아님) -->
      <div v-if="!timingExpanded" class="observe-collapsed">
        백테스트 과거 적중률 <strong>31%</strong> · 점수–수익 <strong>무관(역상관)</strong> —
        매수 신호 아님, 패턴 관찰용.
      </div>
      <template v-else>
        <div class="beta-banner beta-poor">
          ⚠ <strong>매수 신호 아님 · 관찰용</strong> — 백테스트 결과 과거 적중률 <strong>31%</strong>,
          점수와 수익이 <strong>무관(오히려 역상관)</strong>이라 점수가 높다고 좋은 자리가 아닙니다.
          정배열·눌림목 <strong>패턴이 잡힌 종목 관찰용</strong>일 뿐, 실거래·봇 신호가 아닙니다.
        </div>
        <div v-if="timingLoading" class="ts-state">타이밍 분석 중...</div>
        <!-- 분석서버(python) 미가용 — '신호 없음'과 구분해 명시 -->
        <div v-else-if="!timingAvailable" class="ts-state">⚠ 분석서버 일시 미가용 — 잠시 후 다시 확인해 주세요.</div>
        <div v-else class="candidate-list">
          <div v-for="(c, i) in timingCandidates" :key="c.code"
               class="candidate-card observe-card" role="button" tabindex="0"
               @click="$emit('open-stock', c.code)"
               @keydown.enter="$emit('open-stock', c.code)">
            <span class="cc-rank">#{{ i + 1 }}</span>
            <div class="cc-main">
              <div class="cc-head">
                <span class="cc-name">{{ c.name }}</span>
                <!-- 점수(timingScore)는 백테스트상 수익과 역상관이라 미표시 — '높을수록 좋음' 오해 방지 -->
              </div>
              <div class="cc-tags">
                <span v-for="(tag, ti) in (c.signals || []).slice(0, 4)" :key="ti" class="cc-tag">{{ tag }}</span>
              </div>
            </div>
          </div>
        </div>
      </template>
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
const timingCandidates = ref([]);    // 차트 신호 관찰 — momentum 과 별도. 백테스트 부진(승격불가)이라 매수 신호 아님
const timingAvailable = ref(true);   // dataAvailable=false → 분석서버 미가용(빈 결과와 구분)
const timingExpanded = ref(false);   // 백테스트 부진이라 기본 접힘(우선순위 낮춤). 펼쳐야 종목 표시
const catalysts = ref({});           // stockCode → catalyst dto
const bandAccuracy = ref(null);      // accuracy-by-band(보드 격리 + phase-38 컷오프 forward 실측)
const recDataTime = ref(null);       // 후보 계산 기준 시각(백엔드 dataTime) — as-of 정직 표시
const recRealtime = ref(true);       // false = 마지막 계산 스냅샷(전일 마감 등)
const portfolio = ref([]);
const overnight = ref(null);          // 간밤 미국장 tilt(미검증 참고 · regime 산식 미편입)
const overnightAvailable = ref(true); // dataAvailable=false → Yahoo 미가용
const macroTilt = ref(null);          // 매크로 tilt(P3-7 · 미검증 참고 · regime 산식 미편입)
const macroAvailable = ref(true);     // dataAvailable=false → 3축 전부 미수집

const hasMarketData = computed(() =>
  !!(props.marketData && props.marketData.kospiIndex));

const totalProfitLoss = computed(() =>
  portfolio.value.reduce((sum, p) => sum + Number(p.profitLoss || 0), 0));

// "이 점수를 얼마나 믿어도 되나" — accuracy-by-band 실측(보드 신호 격리 + phase-38 컷오프 이후).
// 이전엔 /signal-outcomes/accuracy(전 시그널 혼합·컷오프 없음) + /backtest/performance(mark-to-market,
// 백테스트 아님)를 합성해 보여줬다 — 현재 산식 성적이 아닌 숫자라 교체(2026-07-28).
const trustBands = computed(() =>
  (bandAccuracy.value?.bands || []).filter(b => Number(b.totalSignals) > 0));
const trustSince = computed(() => bandAccuracy.value?.since || null);
// 표본 충분 밴드가 하나도 없으면 경고를 '유지'한다(2026-08-05 감사 — 폴백 극성 수정).
// 예전엔 전체 밴드로 폴백해 최대 hitRate 로 판정했는데, n=1 짜리 밴드의 우연한 100% 가
// "50% 미만" 경고를 지웠다. 표본 부족은 '안심해도 된다'가 아니라 '아직 모른다'다(§4c).
const TRUST_MIN_SAMPLE = 30;
const trustCaution = computed(() => {
  if (!trustBands.value.length) return null;
  const solid = trustBands.value.filter(b => Number(b.totalSignals) >= TRUST_MIN_SAMPLE);
  if (!solid.length) {
    return `아직 판단할 표본이 없습니다(밴드별 ${TRUST_MIN_SAMPLE}건 미만) — 점수는 참고용으로만 쓰고, 손절 계획 없는 매수는 하지 마세요.`;
  }
  const best = Math.max(...solid.map(b => Number(b.hitRate) || 0));
  return best < 50
    ? '현재까지 실측 적중률이 50% 미만입니다 — 점수는 참고용으로만 쓰고, 손절 계획 없는 매수는 하지 마세요.'
    : null;
});

const loadCandidates = async () => {
  candidatesLoading.value = true;
  try {
    const { data } = await recommendationAPI.getTop5();
    const items = data?.data || [];
    recDataTime.value = data?.dataTime || null;
    recRealtime.value = data?.realtime !== false;
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

/** 재료 경과일 — catalystDate 없으면 null(미상). */
const catalystAgeDays = (cat) => {
  if (!cat?.catalystDate) return null;
  const d = new Date(cat.catalystDate);
  if (Number.isNaN(d.getTime())) return null;
  return Math.floor((Date.now() - d.getTime()) / 86400000);
};
const catalystAgeLabel = (cat) => {
  const age = catalystAgeDays(cat);
  if (age == null || age <= 0) return '';
  return ` · ${age}일 전`;
};

// 후보별 재료 배지 — 일캐시 read-only lookup(stockName 미전달 = 신규 Gemini 분류 트리거 안 함,
// 종합판단 보드와 동일 규약). 2일 초과 경과 재료는 배지 생략(보드 표시창과 동기), 1일+ 는 경과일 표기 —
// 옛 뉴스가 "오늘 재료"처럼 보이지 않게(§4c). 실패/재료없음(NONE)이면 배지 생략.
const loadCatalysts = async () => {
  for (const c of buyCandidates.value) {
    try {
      const { data } = await apiClient.get(`/stock/${c.stockCode}/catalyst`);
      const cat = data?.data;
      if (cat && cat.catalystType !== 'NONE') {
        const age = catalystAgeDays(cat);
        if (age != null && age > 2) continue;
        catalysts.value = { ...catalysts.value, [c.stockCode]: cat };
      }
    } catch (e) { /* 배지 생략 */ }
  }
};

/** 태그 표시 — ⚠ 경고 태그 우선(잘림 방지), 최대 3개. */
const displayTags = (c) => {
  const tags = c.tags || [];
  const warn = tags.filter(t => typeof t === 'string' && t.startsWith('⚠'));
  const rest = tags.filter(t => !warn.includes(t));
  return [...warn, ...rest].slice(0, 3);
};

// 차트 신호 관찰 — momentum 과 정반대 objective(추세 안 눌림목). 별도 모듈.
// ⚠ 백테스트(P2-12) 결과 적중률 31%·점수 역상관(승격불가)이라 '매수 후보' 아닌 '관찰용'으로만 노출(접기 기본).
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
    const { data } = await apiClient.get('/signal-outcomes/accuracy-by-band', { params: { days: 90 } });
    if (data?.success !== false && data?.data) bandAccuracy.value = data.data;
  } catch (e) { /* 생략 — 스트립 자체를 숨김(§4c: 미측정을 좋게 위장하지 않음) */ }
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

// 간밤 미국장 보조 tilt — regime 산식 미편입, 참고 표시 전용(미검증). best-effort.
const loadOvernight = async () => {
  try {
    const { data } = await apiClient.get('/global-futures/overnight-us');
    overnight.value = data?.data || null;
    overnightAvailable.value = !!(overnight.value && overnight.value.dataAvailable !== false);
  } catch (e) {
    overnight.value = null;
    overnightAvailable.value = false;
  }
};

// 매크로 보조 tilt(P3-7) — VKOSPI·국고3년·SOX 추세. regime 산식 미편입, 참고 표시 전용(미검증). best-effort.
const loadMacroTilt = async () => {
  try {
    const { data } = await apiClient.get('/macro-tilt');
    macroTilt.value = data?.data || null;
    macroAvailable.value = !!(macroTilt.value && macroTilt.value.dataAvailable !== false);
  } catch (e) {
    macroTilt.value = null;
    macroAvailable.value = false;
  }
};

const gradeLabel = (score) => (Number(score) >= STRONG_BUY_CUT ? '강력 매수' : '매수');
const gradeClass = (score) => (Number(score) >= STRONG_BUY_CUT ? 'grade-strong' : 'grade-buy');
const directionLabel = (d) => ({ POSITIVE: '호재', NEGATIVE: '악재', NEUTRAL: '중립' }[d] || d);
const overnightTiltLabel = (t) => ({ BULL: '강세', NEUTRAL: '중립', BEAR: '약세' }[t] || t);
const overnightTiltClass = (t) => (t === 'BULL' ? 'positive' : t === 'BEAR' ? 'negative' : '');
const macroTiltLabel = (t) => ({ RISK_ON: '위험선호', NEUTRAL: '중립', RISK_OFF: '위험회피' }[t] || t);
const macroTiltClass = (t) => (t === 'RISK_ON' ? 'positive' : t === 'RISK_OFF' ? 'negative' : '');
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
  loadOvernight();
  loadMacroTilt();
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

/* ①-b 간밤 미국장 (보조 tilt · 미검증 참고) */
.today-overnight {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 6px 16px;
  font-size: 12px;
  opacity: 0.92;
}
/* ①-c 매크로 — 간밤 미국장과 스타일 동일하나 독립 진화 가능하게 분리 */
.today-macro {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 6px 16px;
  font-size: 12px;
  opacity: 0.92;
}
.ov-label { font-weight: 600; opacity: 0.8; }
.ov-tilt { font-weight: 700; }
.ov-drivers { opacity: 0.7; }
.ov-beta { margin-left: auto; } /* amber 룩은 공용 .badge-unverified */

/* 공통 섹션 */
.today-section {
  background: var(--surface-card, rgba(20, 24, 38, 0.85));
  border-radius: var(--surface-radius, 12px);
  padding: 16px 18px;
}
.ts-title-row { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.ts-title-row h2 { margin: 0; font-size: 16px; }
.ts-hint { font-size: 11px; opacity: 0.5; }
.ts-asof { margin-left: auto; font-size: 11px; opacity: 0.55; }
.ts-stale {
  font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 4px;
  color: #fbbf24; background: rgba(245, 158, 11, 0.14); border: 1px solid rgba(245, 158, 11, 0.35);
}
.ts-pl { margin-left: auto; font-size: 13px; font-weight: 700; }
.ts-state { padding: 18px 0; text-align: center; font-size: 13px; opacity: 0.6; }
.ts-state.empty { opacity: 0.75; }

/* 차트 타이밍(검증 전 베타) — amber 룩은 공용 .badge-unverified, 크기만 로컬 */
.ts-beta { margin-left: auto; font-size: 11px; padding: 2px 8px; }
.beta-banner {
  margin: 10px 0 4px; padding: 8px 12px; border-radius: 8px;
  background: rgba(245, 158, 11, 0.12); border: 1px solid rgba(245, 158, 11, 0.4);
  color: #fbbf24; font-size: 12px; line-height: 1.5;
}

/* 차트 신호 관찰 — 백테스트 부진(승격불가) 톤다운(적색 계열 + 기본 접힘) */
.today-observe { opacity: 0.82; }
.ts-poor { color: #f87171; background: rgba(248, 113, 113, 0.13); }
/* 표면·hover 는 공용 .btn-ghost — 크기만 로컬 */
.ts-toggle { font-size: 11px; font-weight: 600; border-radius: 5px; padding: 2px 9px; }
.observe-collapsed { margin-top: 8px; font-size: 12px; line-height: 1.5; opacity: 0.72; }
.observe-collapsed strong { color: #f87171; }
.beta-poor {
  background: rgba(248, 113, 113, 0.10); border-color: rgba(248, 113, 113, 0.38); color: #fca5a5;
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
/* 매수 등급 = 한국 관례 빨강 계열 — 파랑(=매도색) 오독 방지, 결론카드·게이지와 통일 */
.grade-strong { color: var(--signal-strong-buy, #ef4444); background: rgba(239, 68, 68, 0.15); }
.grade-buy { color: var(--signal-buy, #f87171); background: rgba(248, 113, 113, 0.15); }
.cc-score { font-size: 12px; opacity: 0.7; }
.cc-tags { display: flex; gap: 6px; margin-top: 5px; flex-wrap: wrap; }
.cc-tag {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.07);
  opacity: 0.8;
}
/* ⚠ 경고 태그(리스크공시·신규급등 등) — 일반 태그와 구분되는 적색 톤 */
.cc-tag-warn {
  color: #fca5a5;
  background: rgba(248, 113, 113, 0.14);
  opacity: 1;
  font-weight: 600;
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

/* 신뢰도(실측) — 매수 후보 섹션 상단, 후보를 보기 전에 "얼마나 믿을 수 있나"부터 */
.today-trust {
  margin-top: 10px;
  background: rgba(255, 255, 255, 0.04);
  border-radius: 10px;
  padding: 9px 12px;
  font-size: 12px;
}
.tt-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.tt-title { font-weight: 600; opacity: 0.75; }
.tt-chip {
  padding: 1px 8px; border-radius: 4px; font-weight: 600;
  background: rgba(255, 255, 255, 0.07);
}
.tt-chip em { font-style: normal; font-weight: 400; opacity: 0.65; font-size: 11px; }
.tt-weak { opacity: 0.6; }
.tt-caution {
  margin-top: 6px; font-size: 11.5px; line-height: 1.45;
  color: #fbbf24;
}

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

/* 등락색은 한국 관례(상승=빨강/하락=파랑) — 허브(StockTradingDashboardV2)·디자인 토큰(--stock-up/down)과 통일 */
.positive { color: var(--stock-up, #f87171); }
.negative { color: var(--stock-down, #60a5fa); }

@media (max-width: 600px) {
  .today-market { gap: 10px; font-size: 12px; }
  .tm-status { flex-basis: 100%; margin-left: 0; }
}
</style>
