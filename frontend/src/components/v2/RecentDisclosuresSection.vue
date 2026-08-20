<template>
  <!-- 📄 최근 공시 (DART 3개월, 표시 전용 — 산식 미편입) — 위험 키워드 매칭뿐 아니라 일반 공시
       (수주/계약/실적발표 등)까지 원문 링크로 확인. 조회 실패는 '공시 없음'과 구분(§4c). -->
  <DetailSection v-if="disclosures" :title="sectionTitle">
    <div class="rd-body">
      <div v-if="!disclosures.dataAvailable" class="rd-unavailable">
        ⚠ 공시 조회 불가(DART 미가용/기업코드 미해결) — '공시 없음'이 아니라 <b>확인 불가</b>입니다.
      </div>
      <template v-else>
        <div class="rd-note">
          DART 최근 3개월 공시 원문 목록 — 위험 키워드 매칭(붉은 표시)은 자동 판정, 나머지는 직접 확인용.
        </div>
        <div v-if="!disclosures.items.length" class="rd-empty">최근 3개월 공시 없음</div>
        <div v-else class="rd-list">
          <div v-for="(d, i) in disclosures.items" :key="'rd-' + i" class="rd-row" :class="{ 'rd-danger': d.dangerous }">
            <span class="rd-date">{{ d.rceptDt }}</span>
            <a v-if="d.viewerUrl" class="rd-title" :href="d.viewerUrl" target="_blank" rel="noopener noreferrer">
              {{ d.reportNm }}
            </a>
            <span v-else class="rd-title">{{ d.reportNm }}</span>
            <span v-if="d.dangerous" class="rd-danger-badge">⚠ {{ d.matchedKeyword }}</span>
            <span v-if="d.flrNm" class="rd-flr">{{ d.flrNm }}</span>
          </div>
          <div v-if="disclosures.totalCount > disclosures.items.length" class="rd-more">
            외 {{ disclosures.totalCount - disclosures.items.length }}건 — 전체는 DART 에서 확인
          </div>
        </div>
      </template>
    </div>
  </DetailSection>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import apiClient from '../../utils/api';
import DetailSection from './DetailSection.vue';

const props = defineProps({
  stockCode: { type: String, required: true },
  stockName: { type: String, default: null }
});

const disclosures = ref(null);

// 요청 시퀀스 토큰 — 종목 전환 시 늦게 온 이전 종목 응답이 현재 종목 목록을 덮는 경합 방지
let reqSeq = 0;

// heavy 계열 — 자체 fetch(quick 지연 없음). HTTP 실패 시 미렌더(백엔드 dataAvailable=false 와 별개).
const load = async (code) => {
  disclosures.value = null;
  if (!code) return;
  const seq = ++reqSeq;
  try {
    const { data } = await apiClient.get(`/stock/${code}/disclosures`, {
      params: props.stockName ? { stockName: props.stockName } : {}
    });
    if (seq !== reqSeq) return;   // 종목 전환됨 — 폐기
    if (data?.success) disclosures.value = data.data;
  } catch (e) { /* 섹션 미렌더 */ }
};

watch(() => props.stockCode, load, { immediate: true });

const sectionTitle = computed(() => {
  const d = disclosures.value;
  if (!d || !d.dataAvailable) return '📄 최근 공시 (DART)';
  const dangers = (d.items || []).filter(it => it.dangerous).length;
  const parts = [`3개월 ${d.totalCount}건`];
  if (dangers) parts.push(`⚠ 위험 ${dangers}`);
  return `📄 최근 공시 (DART) — ${parts.join(' · ')}`;
});
</script>

<style scoped>
.rd-body { padding: 4px 16px 14px; color: #e2e8f0; }
.rd-note {
  font-size: 11px; line-height: 1.5; opacity: 0.6; margin-bottom: 8px;
  border-left: 2px solid rgba(148, 163, 184, 0.4); padding-left: 8px;
}
.rd-unavailable {
  font-size: 12.5px; padding: 8px 10px; border-radius: 6px;
  color: #fcd34d; background: rgba(245, 158, 11, 0.08);
  border-left: 2px solid rgba(245, 158, 11, 0.45);
}
.rd-empty { font-size: 12.5px; opacity: 0.65; padding: 6px 2px; }
.rd-list { display: flex; flex-direction: column; gap: 4px; }
.rd-row {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  font-size: 12.5px; padding: 6px 10px; border-radius: 6px;
  background: rgba(255, 255, 255, 0.03);
}
.rd-row.rd-danger { background: rgba(239, 68, 68, 0.08); border-left: 2px solid rgba(239, 68, 68, 0.5); }
.rd-date { font-variant-numeric: tabular-nums; opacity: 0.7; min-width: 78px; }
a.rd-title { color: #93c5fd; text-decoration: underline; text-underline-offset: 2px; }
a.rd-title:hover { color: #bfdbfe; }
span.rd-title { opacity: 0.9; }
.rd-danger-badge { font-size: 11.5px; font-weight: 700; color: #f87171; }
.rd-flr { font-size: 11px; opacity: 0.55; margin-left: auto; }
.rd-more { font-size: 11.5px; opacity: 0.6; padding: 4px 2px; }
</style>
