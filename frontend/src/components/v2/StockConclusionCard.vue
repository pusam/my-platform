<template>
  <div v-if="conclusion" class="conclusion-card" :class="levelClass">
    <div class="conclusion-main">
      <div class="conclusion-level">
        <span class="level-icon">{{ levelIcon }}</span>
        <span class="level-label">{{ levelLabel }}</span>
      </div>
      <div class="conclusion-text">
        <!-- 진입 위치 (표시 전용 — overheatPenalty 신호 + 지지선 거리 조립, null=미렌더 §4c) -->
        <p v-if="conclusion.entryPosition" class="entry-position"
           :class="'ep-' + (conclusion.entryPosition.zone || '').toLowerCase()">
          {{ entryPositionIcon }} {{ conclusion.entryPosition.text }}
        </p>
        <p class="headline">{{ conclusion.headline }}</p>
        <p class="source-caption" title="이 결론은 종합추천 스냅샷(전 종목 비교 랭킹, 5카테고리)을 기준으로 합니다. 상단 헤더의 '단기 트레이딩'·'중장기 펀더멘털' 점수와는 산출 기준·시점이 달라 결론이 다를 수 있습니다.">
          ⓘ 종합추천 스냅샷 기준 — 상단 단기/중장기 점수와 산출 기준이 다릅니다
        </p>
        <p v-if="catalyst" class="catalyst-line" :class="'cat-' + catalyst.direction.toLowerCase()">
          🔥 재료: {{ catalyst.typeLabel }}({{ catalystDirectionLabel }})<template v-if="catalyst.summary"> — {{ catalyst.summary }}</template>
        </p>
        <p v-if="conclusion.guidance" class="guidance">{{ conclusion.guidance }}</p>
        <p v-if="conclusion.conflictNote" class="conflict-note">{{ conclusion.conflictNote }}</p>
      </div>
      <div class="card-actions">
        <button class="checklist-btn" @click="openChecklist" title="매수 체크리스트">
          ✅ 매수 체크리스트
        </button>
        <button class="checklist-btn journal-btn" @click="showJournal = true"
                title="수동 매수 기록 (실주문 아님 — 신호 스냅샷 + 3거래일 평가)">
          📔 매수 기록
        </button>
      </div>
    </div>

    <div v-if="conclusion.factors && conclusion.factors.length" class="conclusion-factors">
      <div v-for="f in conclusion.factors" :key="f.key" class="factor"
           :class="'factor-' + (f.verdict || 'neutral').toLowerCase()">
        <div class="factor-header">
          <span class="factor-label">{{ f.label }}</span>
          <span class="factor-dim" :class="'dim-' + (f.dimension || 'mid').toLowerCase()">
            {{ dimensionLabel(f.dimension) }}
          </span>
        </div>
        <div class="factor-score">{{ displayScore(f.score) }}</div>
        <div class="factor-note">{{ f.note }}</div>
      </div>
    </div>

    <div v-if="conclusion.tradePlan" class="trade-plan">
      <div class="tp-row">
        <span class="tp-title">📐 매매 계획 <span class="tp-basis">스윙 기준{{ isTightPlan ? ' · 타이트(밸류 약)' : '' }}</span></span>
        <span v-if="conclusion.tradePlan.basePrice" class="tp-item">진입 <b>{{ formatPrice(conclusion.tradePlan.basePrice) }}원</b></span>
        <span class="tp-item tp-stop">손절 <b>{{ planPriceLabel(conclusion.tradePlan.stopLossPrice, conclusion.tradePlan.stopLossPct) }}</b></span>
        <span class="tp-item tp-target">목표 <b>{{ planPriceLabel(conclusion.tradePlan.targetPrice, conclusion.tradePlan.targetPct) }}</b></span>
      </div>
      <!-- ATR 참고치(검증 전) — 기본 계획(-3/+5)과 별개 병기. null=미산출(§4c) 줄 미렌더.
           차트 타이밍 '관찰' 배너 톤(amber) 재사용 — 기본 계획과 시각적으로 구분. -->
      <div v-if="conclusion.tradePlan.atrStopPct != null" class="tp-atr">
        📏 변동성(ATR) 기준: <b>{{ signedPct(conclusion.tradePlan.atrStopPct) }}</b> /
        <b>{{ signedPct(conclusion.tradePlan.atrTargetPct) }}</b>
        <span class="badge-unverified tp-atr-badge">백테스트 참고치 · 검증 전 — 기본 계획 아님</span>
      </div>
      <div v-if="conclusion.tradePlan.mfeMaeSampleCount > 0" class="tp-mfe">
        과거 {{ levelLabel }} 시그널 {{ conclusion.tradePlan.mfeMaeSampleCount }}건 실측 — 3거래일 내 평균 최고
        <b class="tp-pos">{{ signedPct(conclusion.tradePlan.avgMfePct) }}</b> / 최저
        <b class="tp-neg">{{ signedPct(conclusion.tradePlan.avgMaePct) }}</b>
      </div>
    </div>

    <div v-if="accuracyStat" class="accuracy-line">
      <span class="acc-label">📊 {{ accuracyStat.signalType }} 시그널 지난 30일 적중률</span>
      <span class="acc-rate" :class="accuracyClass">{{ accuracyStat.hitRate }}%</span>
      <span class="acc-detail">({{ accuracyStat.hitCount }}/{{ accuracyStat.totalSignals }}건, 평균 {{ accuracyStat.avgPctChange }}%)</span>
      <span v-if="bandStat" class="acc-detail band">· 이 점수대({{ bandStat.band }}) {{ bandStat.hitRate }}% ({{ bandStat.totalSignals }}건, 보드{{ bandSinceSuffix }})</span>
    </div>
    <div v-else-if="accuracyEmpty" class="accuracy-line empty">
      <span class="acc-label">📊 적중률 데이터 누적 중 — 3일 후 첫 평가 결과 확보</span>
    </div>

    <div v-if="conclusion.dataAt" class="conclusion-meta">
      <span class="freshness-dot" :class="freshnessClass"
            :title="`데이터 ${minutesAgo}분 경과 — ${freshnessLabel}`"></span>
      <span>스냅샷: {{ formatDataAt(conclusion.dataAt) }} · {{ minutesAgo }}분 전</span>
    </div>

    <BuyChecklistModal v-if="showChecklist" :stock-code="stockCode" @close="showChecklist = false" />
    <ManualJournalModal v-if="showJournal" :stock-code="stockCode"
                        :current-price="conclusion.tradePlan?.basePrice"
                        @close="showJournal = false" />
  </div>
  <div v-else-if="loading" class="conclusion-card loading">결론 분석 중...</div>
  <div v-else-if="error" class="conclusion-card error">결론을 불러오지 못했습니다.</div>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import apiClient from '../../utils/api';
import BuyChecklistModal from './BuyChecklistModal.vue';
import ManualJournalModal from './ManualJournalModal.vue';

const props = defineProps({
  stockCode: { type: String, required: true }
});

const conclusion = ref(null);
const loading = ref(false);
const error = ref(false);
const showChecklist = ref(false);
const showJournal = ref(false);   // 📔 수동 매수 기록 모달
const accuracyStats = ref([]);   // 전체 시그널 타입별 통계 (배열)
const accuracyEmpty = ref(false); // 데이터 누적 중 표시 플래그
const bandStats = ref([]);        // 점수 구간별 적중률 (V30) — 보드 종합점수(STRONG_BUY/BUY) 격리, phase-38 컷오프 이후
const bandSince = ref(null);      // 실제 집계 시작일 (phase-38 컷오프 2026-06-25 or 요청창 중 늦은 쪽)
const catalyst = ref(null);       // 재료 태그 (V31) — NONE/실패 시 null (배지 생략)

const fetchConclusion = async (code) => {
  if (!code) return;
  loading.value = true;
  error.value = false;
  try {
    const { data } = await apiClient.get(`/stock/${code}/conclusion`);
    if (data?.success) {
      conclusion.value = data.data;
      fetchCatalyst(code, data.data?.stockName);
    } else {
      error.value = true;
    }
  } catch (e) {
    error.value = true;
  } finally {
    loading.value = false;
  }
};

// 재료 태그 (V31) — best-effort: 실패/재료없음(NONE)이면 배지 생략
const fetchCatalyst = async (code, stockName) => {
  catalyst.value = null;
  try {
    const { data } = await apiClient.get(`/stock/${code}/catalyst`, {
      params: stockName ? { stockName } : {}
    });
    const cat = data?.data;
    if (cat && cat.catalystType !== 'NONE') catalyst.value = cat;
  } catch (e) { /* 배지 생략 */ }
};

const fetchAccuracy = async () => {
  try {
    const { data } = await apiClient.get('/signal-outcomes/accuracy', { params: { days: 30 } });
    if (data?.success) {
      const stats = data.data?.stats || [];
      accuracyStats.value = stats;
      accuracyEmpty.value = stats.length === 0;
    }
  } catch (e) {
    // 데이터 부족 / API 오류 시 조용히 무시
  }
  try {
    const { data } = await apiClient.get('/signal-outcomes/accuracy-by-band', { params: { days: 90 } });
    if (data?.success) { bandStats.value = data.data?.bands || []; bandSince.value = data.data?.since || null; }
  } catch (e) {
    // V30 집계 미가용 시 조용히 무시 (기존 적중률 라인은 그대로 표시)
  }
};

watch(() => props.stockCode, (code) => {
  fetchConclusion(code);
  fetchAccuracy();
}, { immediate: true });

// 진입 위치 아이콘 — 과열(🔴)/주의(🟡)/눌림(🟢). zone 없으면 빈 문자열.
const entryPositionIcon = computed(() => {
  const z = conclusion.value?.entryPosition?.zone;
  return z === 'OVERHEATED' ? '🔴' : z === 'CAUTION' ? '🟡' : z === 'PULLBACK' ? '🟢' : '';
});

// 현재 결론 level 에 해당하는 시그널 타입의 적중률 통계 1건.
// STRONG_BUY/BUY level 만 노출 (HOLD/WAIT 은 시그널이 발생하지 않으므로 통계 없음).
const accuracyStat = computed(() => {
  const level = conclusion.value?.level;
  if (level !== 'STRONG_BUY' && level !== 'BUY') return null;
  return accuracyStats.value.find(s => s.signalType === level) || null;
});

// 현재 종목의 종합 점수가 속한 구간의 적중률 (표본 있을 때만).
const bandStat = computed(() => {
  const total = conclusion.value?.factors?.find(f => f.key === 'total')?.score;
  if (total == null) return null;
  const band = bandStats.value.find(b => total >= b.scoreFrom && total <= b.scoreTo);
  return band && band.totalSignals > 0 ? band : null;
});

// 적중률 집계 창 라벨 — "6/25~"(phase-38 컷오프 이후, 보드 종합점수 기준) 표기용.
const bandSinceSuffix = computed(() => {
  const m = String(bandSince.value || '').match(/^\d{4}-(\d{2})-(\d{2})/);
  return m ? ` · ${Number(m[1])}/${Number(m[2])}~` : '';
});

// 타이트 계획 여부 — 백엔드가 -2%/+3% 로 내려준 경우 (단기 강 + 밸류 매우 약 충돌).
const isTightPlan = computed(() => Number(conclusion.value?.tradePlan?.targetPct) === 3);

const catalystDirectionLabel = computed(() =>
  ({ POSITIVE: '호재', NEGATIVE: '악재', NEUTRAL: '중립' }[catalyst.value?.direction] || ''));

const formatPrice = (v) => (v == null || Number.isNaN(Number(v)) ? '-' : Number(v).toLocaleString('ko-KR'));

// 가격 있으면 "67,900원 (-3%)", 없으면 "% 만" 표시. pct 결측이면 % 블록 생략(§4c — "null%" 방지).
const planPriceLabel = (price, pct) => {
  const pctLabel = pct == null ? null : `${Number(pct) > 0 ? '+' : ''}${pct}%`;
  if (price != null) return pctLabel ? `${formatPrice(price)}원 (${pctLabel})` : `${formatPrice(price)}원`;
  return pctLabel ?? '—';
};

const signedPct = (v) => (v == null ? '—' : `${Number(v) > 0 ? '+' : ''}${v}%`);

const accuracyClass = computed(() => {
  const rate = Number(accuracyStat.value?.hitRate || 0);
  if (rate >= 60) return 'acc-good';
  if (rate >= 40) return 'acc-mid';
  return 'acc-low';
});

const levelLabel = computed(() => {
  switch (conclusion.value?.level) {
    case 'STRONG_BUY': return '강력 매수';
    case 'BUY': return '매수';
    case 'HOLD': return '보유 / 분할';
    case 'WAIT': return '관망';
    default: return '-';
  }
});

// 한국 관례(매수=빨강 계열)로 통일 — 🔴강력매수 > 🟠매수 > 🟡보유 > ⚪관망 열 스케일.
// 진입 위치의 🔴(과열)/🟢(눌림)는 위험/안전 '상태' 표기라 별개 (라벨로 구분됨).
const levelIcon = computed(() => {
  switch (conclusion.value?.level) {
    case 'STRONG_BUY': return '🔴';
    case 'BUY': return '🟠';
    case 'HOLD': return '🟡';
    case 'WAIT': return '⚪';
    default: return '⚪';
  }
});

const levelClass = computed(() => 'level-' + (conclusion.value?.level || 'wait').toLowerCase().replace('_', '-'));

// 점수 표시 — 음수(-1)는 "데이터 없음(NA)" 의미이므로 "—" 로 표기 (밸류/성장성 등 LONG factor).
const displayScore = (score) => (score == null || score < 0 ? '—' : score);

const dimensionLabel = (dim) => {
  switch (dim) {
    case 'SHORT': return '단기';
    case 'MID': return '중기';
    case 'LONG': return '장기';
    case 'META': return '필수';
    default: return '';
  }
};

const formatDataAt = (iso) => {
  try {
    const d = new Date(iso);
    return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  } catch { return ''; }
};

// 신선도 신호등 (phase 23) — dataAt 기준 경과 분
const minutesAgo = computed(() => {
  if (!conclusion.value?.dataAt) return 0;
  try {
    const ms = Date.now() - new Date(conclusion.value.dataAt).getTime();
    return Math.max(0, Math.floor(ms / 60000));
  } catch { return 0; }
});
const freshnessClass = computed(() => {
  const m = minutesAgo.value;
  if (m <= 5) return 'fresh-good';   // 녹: 5분 이내
  if (m <= 15) return 'fresh-mid';   // 노: 5~15분
  return 'fresh-stale';              // 빨: 15분 초과
});
const freshnessLabel = computed(() => {
  switch (freshnessClass.value) {
    case 'fresh-good': return '신선';
    case 'fresh-mid': return '주의 — 곧 갱신';
    case 'fresh-stale': return 'stale — 다음 스냅샷 대기 권장';
    default: return '';
  }
});

const openChecklist = () => { showChecklist.value = true; };
</script>

<style scoped>
.conclusion-card {
  background: var(--surface-card, rgba(20, 24, 38, 0.85));
  border-radius: var(--surface-radius, 12px);
  padding: 16px 20px;
  margin: 12px 16px;
  border-left: 4px solid #888;
  color: #fff;
}
.conclusion-card.loading,
.conclusion-card.error {
  text-align: center;
  font-size: 13px;
  opacity: 0.7;
}
/* 매수 verdict = 한국 관례 빨강 계열 (--signal-*) — VolumePowerGauge·허브 등락색과 통일 */
.level-strong-buy { border-left-color: var(--signal-strong-buy, #ef4444); background: rgba(239, 68, 68, 0.12); }
.level-buy        { border-left-color: var(--signal-buy, #f87171); background: rgba(248, 113, 113, 0.10); }
.level-hold       { border-left-color: #eab308; background: rgba(234, 179, 8, 0.10); }
.level-wait       { border-left-color: #888;    background: rgba(120, 120, 120, 0.10); }

.conclusion-main {
  display: flex;
  align-items: center;
  gap: 16px;
}
.conclusion-level {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 90px;
}
.level-icon { font-size: 28px; }
.level-label { font-size: 13px; font-weight: 700; margin-top: 4px; }
.conclusion-text { flex: 1; }
.entry-position {
  margin: 0 0 6px; font-size: 13px; font-weight: 700;
  display: inline-block; padding: 3px 10px; border-radius: 8px;
}
.entry-position.ep-overheated { background: rgba(239,68,68,0.14); color: #f87171; }
.entry-position.ep-caution { background: rgba(245,158,11,0.14); color: #fbbf24; }
.entry-position.ep-pullback { background: rgba(34,197,94,0.14); color: #4ade80; }
.headline { margin: 0; font-size: 15px; font-weight: 600; line-height: 1.4; }
.source-caption {
  margin: 4px 0 0;
  font-size: 11px;
  opacity: 0.6;
  cursor: help;
}
.guidance { margin: 6px 0 0; font-size: 13px; opacity: 0.78; }
.catalyst-line {
  margin: 7px 0 0;
  font-size: 12.5px;
  font-weight: 600;
  padding: 5px 10px;
  border-radius: 4px;
  display: inline-block;
}
.cat-positive { color: #fbbf24; background: rgba(251, 191, 36, 0.12); border-left: 3px solid #fbbf24; }
.cat-negative { color: #f87171; background: rgba(239, 68, 68, 0.12); border-left: 3px solid #f87171; }
.cat-neutral  { color: #cbd5e1; background: rgba(203, 213, 225, 0.10); border-left: 3px solid #94a3b8; }
.conflict-note {
  margin: 8px 0 0;
  font-size: 12.5px;
  padding: 6px 10px;
  background: rgba(234, 179, 8, 0.10);
  border-left: 3px solid #eab308;
  border-radius: 4px;
  line-height: 1.45;
}
.checklist-btn {
  background: rgba(59, 130, 246, 0.18);
  color: #93c5fd;
  border: 1px solid rgba(147, 197, 253, 0.35);
  border-radius: 8px;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}
.checklist-btn:hover { background: rgba(59, 130, 246, 0.30); }
.card-actions { display: flex; flex-direction: column; gap: 8px; flex-shrink: 0; }
.journal-btn {
  background: rgba(234, 179, 8, 0.14);
  color: #fde68a;
  border-color: rgba(253, 230, 138, 0.35);
}
.journal-btn:hover { background: rgba(234, 179, 8, 0.26); }

.conclusion-factors {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
  margin-top: 14px;
}
.factor {
  background: rgba(255, 255, 255, 0.04);
  border-radius: 8px;
  padding: 10px;
}
.factor-positive { border-left: 3px solid #22c55e; }
.factor-neutral  { border-left: 3px solid #eab308; }
.factor-negative { border-left: 3px solid #ef4444; }
.factor-header { display: flex; justify-content: space-between; align-items: center; }
.factor-label { font-size: 12px; font-weight: 600; opacity: 0.9; }
.factor-dim {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.08);
  opacity: 0.85;
}
.dim-short { color: #fca5a5; }
.dim-mid   { color: #93c5fd; }
.dim-long  { color: #86efac; }
.dim-meta  { color: #d1d5db; }
.factor-score { font-size: 18px; font-weight: 700; margin: 4px 0; }
.factor-note { font-size: 11px; opacity: 0.65; line-height: 1.3; }

.conclusion-meta {
  margin-top: 10px;
  font-size: 11px;
  opacity: 0.75;
  text-align: right;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 6px;
}
.freshness-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.fresh-good  { background: #22c55e; box-shadow: 0 0 6px rgba(34, 197, 94, 0.5); }
.fresh-mid   { background: #eab308; box-shadow: 0 0 6px rgba(234, 179, 8, 0.5); }
.fresh-stale { background: #ef4444; box-shadow: 0 0 6px rgba(239, 68, 68, 0.5); }

.trade-plan {
  margin-top: 12px;
  padding: 10px 12px;
  background: rgba(59, 130, 246, 0.07);
  border: 1px solid rgba(147, 197, 253, 0.18);
  border-radius: 6px;
  font-size: 12.5px;
}
.tp-row {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}
.tp-title { font-weight: 700; }
.tp-basis { font-weight: 400; font-size: 11px; opacity: 0.6; }
.tp-item b { font-weight: 700; }
/* 가격 방향색 = 등락 관례 (목표(위)=빨강, 손절(아래)=파랑) */
.tp-stop b { color: var(--stock-down, #60a5fa); }
.tp-target b { color: var(--stock-up, #f87171); }
.tp-mfe {
  margin-top: 6px;
  font-size: 11.5px;
  opacity: 0.75;
}
/* ATR 참고 줄 — 미검증 amber 톤(차트 '관찰' 배너와 동일 계열), 기본 계획(파란 박스)과 구분 */
.tp-atr {
  margin-top: 6px;
  font-size: 11.5px;
  color: #fcd34d;
  background: rgba(245, 158, 11, 0.08);
  border-left: 2px solid rgba(245, 158, 11, 0.45);
  border-radius: 4px;
  padding: 4px 8px;
}
.tp-atr b { font-variant-numeric: tabular-nums; }
/* amber 룩은 공용 .badge-unverified — 기존 렌더 보존 차이값만 로컬 */
.tp-atr-badge {
  margin-left: 6px;
  font-weight: 400;
  background: rgba(245, 158, 11, 0.16);
  padding: 1px 6px;
  border-radius: 3px;
}
.tp-pos { color: var(--stock-up, #f87171); }
.tp-neg { color: var(--stock-down, #60a5fa); }

.accuracy-line {
  margin-top: 12px;
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.04);
  border-radius: 6px;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.accuracy-line.empty {
  opacity: 0.55;
}
.acc-label { opacity: 0.8; }
.acc-rate {
  font-weight: 700;
  font-size: 14px;
  padding: 2px 8px;
  border-radius: 4px;
}
.acc-good { color: #22c55e; background: rgba(34, 197, 94, 0.18); }
.acc-mid  { color: #eab308; background: rgba(234, 179, 8, 0.18); }
.acc-low  { color: #ef4444; background: rgba(239, 68, 68, 0.18); }
.acc-detail { opacity: 0.65; font-size: 11px; }

/* 모바일 — 레벨(90px)+텍스트+버튼(nowrap) 한 줄이면 헤드라인이 ~50px 로 짓눌림 → 버튼을 아랫줄 전체폭으로 */
@media (max-width: 600px) {
  .conclusion-card { padding: 14px 14px; margin: 12px 10px; }
  .conclusion-main { flex-wrap: wrap; gap: 12px; }
  .conclusion-level { min-width: 64px; }
  .card-actions { flex-basis: 100%; }
  .checklist-btn { width: 100%; text-align: center; }
}
</style>
