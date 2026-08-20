<template>
  <!-- 📰 재료 이력 (stock_catalyst 최근 30일 재사용, read-only — 신규 분류 안 함) — n=0 이면 섹션 자체 미렌더.
       DetailSection 접기 패턴(심화 영역) + 요약은 제목에 병기(접힌 상태에서도 보임). -->
  <DetailSection v-if="history && history.items && history.items.length" :title="sectionTitle">
    <div class="ch-body">
      <div class="ch-note">
        뉴스 기반 자동 분류(수주/실적/M&amp;A 등 + 호재/악재/중립) 이력 — 표시 전용, 점수 산식 미편입.
        각 날짜의 등락률은 그날 일봉 실측(없으면 생략).
      </div>
      <div class="ch-list">
        <div v-for="(it, i) in history.items" :key="'ch-' + i" class="ch-row">
          <span class="ch-date">{{ fmtDate(it.catalystDate) }}</span>
          <span class="ch-badge" :class="dirClass(it.direction)">{{ dirBadge(it.direction) }}</span>
          <span class="ch-type">{{ it.typeLabel }}</span>
          <span class="ch-pct" v-if="it.changeRate != null" :class="pctClass(it.changeRate)">{{ signedPct(it.changeRate) }}</span>
          <!-- 근거 기사 링크 (V53) — newsLink 없으면 생략(§4c). headline 은 툴팁으로 -->
          <a v-if="it.newsLink" class="ch-link" :href="it.newsLink" target="_blank"
             rel="noopener noreferrer" :title="it.headline || '근거 기사 보기'">📰 기사</a>
          <span class="ch-summary" v-if="it.summary">{{ it.summary }}</span>
        </div>
      </div>
    </div>
  </DetailSection>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import apiClient from '../../utils/api';
import DetailSection from './DetailSection.vue';

const props = defineProps({
  stockCode: { type: String, required: true }
});

const history = ref(null);

// 요청 시퀀스 토큰 — 종목 전환 시 늦게 온 이전 종목 응답이 현재 종목 이력을 덮어쓰는 경합 방지
let reqSeq = 0;

// heavy(2단) 계열 — quick 경로와 독립 fetch(지연 없음), 실패 시 조용히 미렌더
const load = async (code) => {
  history.value = null;
  if (!code) return;
  const seq = ++reqSeq;
  try {
    const { data } = await apiClient.get(`/stock/${code}/catalyst-history`);
    if (seq !== reqSeq) return;   // 종목 전환됨 — 폐기
    if (data?.success) history.value = data.data;
  } catch (e) { /* 섹션 미렌더 */ }
};

watch(() => props.stockCode, load, { immediate: true });

// 요약 배지를 제목에 병기 — "최근 30일 3건 · 호재 2 · 악재 1" (접힌 상태에서도 보임)
const sectionTitle = computed(() => {
  const items = history.value?.items;
  if (!items || !items.length) return '📰 재료 이력';
  const pos = items.filter(it => it.direction === 'POSITIVE').length;
  const neg = items.filter(it => it.direction === 'NEGATIVE').length;
  const neu = items.filter(it => it.direction === 'NEUTRAL').length;
  const parts = [`최근 ${history.value.windowDays}일 ${items.length}건`];
  if (pos) parts.push(`호재 ${pos}`);
  if (neg) parts.push(`악재 ${neg}`);
  if (neu) parts.push(`중립 ${neu}`);
  return `📰 재료 이력 — ${parts.join(' · ')}`;
});

const dirBadge = (d) => ({ POSITIVE: '🔥 호재', NEGATIVE: '⚠️ 악재', NEUTRAL: '➖ 중립' }[d] || '재료');
const dirClass = (d) => ({ POSITIVE: 'pos', NEGATIVE: 'neg', NEUTRAL: 'neu' }[d] || '');
const pctClass = (v) => {
  if (v == null) return '';
  const n = Number(v);
  return n > 0 ? 'positive' : n < 0 ? 'negative' : '';
};
const signedPct = (v) => (v == null ? '—' : `${Number(v) > 0 ? '+' : ''}${Number(v).toFixed(1)}%`);
const fmtDate = (d) => {
  const m = String(d || '').match(/^\d{4}-(\d{2})-(\d{2})/);
  return m ? `${Number(m[1])}/${Number(m[2])}` : d;
};
</script>

<style scoped>
.ch-body { padding: 4px 16px 14px; color: #e2e8f0; }
.ch-note {
  font-size: 11px; line-height: 1.5; opacity: 0.6; margin-bottom: 8px;
  border-left: 2px solid rgba(148, 163, 184, 0.4); padding-left: 8px;
}
.ch-list { display: flex; flex-direction: column; gap: 4px; }
.ch-row {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  font-size: 12.5px; padding: 6px 10px; border-radius: 6px;
  background: rgba(255, 255, 255, 0.03);
}
.ch-date { font-variant-numeric: tabular-nums; opacity: 0.7; min-width: 38px; }
.ch-badge { font-size: 12px; font-weight: 600; }
.ch-badge.pos { color: #4ade80; }
.ch-badge.neg { color: #f87171; }
.ch-badge.neu { color: #cbd5e1; }
.ch-type { font-weight: 600; }
.ch-pct { font-variant-numeric: tabular-nums; font-size: 12px; }
.ch-link {
  font-size: 11.5px;
  color: #93c5fd;
  text-decoration: underline;
  text-underline-offset: 2px;
  opacity: 0.9;
}
.ch-link:hover { opacity: 1; }
.ch-summary { font-size: 12px; opacity: 0.72; flex: 1 1 100%; }
/* 등락색은 한국 관례(상승=빨강/하락=파랑) — 종목상세 나머지 등락 표시·디자인 토큰과 통일 */
.positive { color: var(--stock-up, #f87171); }
.negative { color: var(--stock-down, #60a5fa); }
</style>
