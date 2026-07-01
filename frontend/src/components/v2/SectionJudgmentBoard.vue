<template>
  <div class="judgment-board">
    <div class="jb-head">
      <h2>🧭 종합 판단 보드</h2>
      <span class="jb-sub">매수후보를 신뢰도 3계층 신호로 비교 · 종합점수는 ① 검증/게이트 기준(②·③은 표시만)</span>
    </div>

    <div class="jb-market" v-if="board && board.market">
      <span class="jbm-item">시장국면
        <strong :class="regimeClass(board.market.regime)">{{ regimeLabel(board.market.regime) }}</strong></span>
      <span class="jbm-item" v-if="board.market.overnightTilt">🌙 간밤 미국장
        <strong :class="tiltClass(board.market.overnightTilt)">{{ tiltLabel(board.market.overnightTilt) }}</strong>
        <small v-if="board.market.overnightDrivers && board.market.overnightDrivers.length">
          {{ board.market.overnightDrivers.join(' · ') }}</small></span>
    </div>

    <div class="jb-note" v-if="board && board.note">{{ board.note }}</div>

    <div class="jb-filters" v-if="board && !loading">
      <button class="jb-scope-btn" :class="{ active: scope === 'union' }" @click="toggleScope">
        {{ scope === 'union' ? '✓ 발굴 트랙 포함' : '+ 발굴 트랙 포함' }}
      </button>
      <label><input type="checkbox" v-model="hideSuspect"> 수급 역상관 의심 숨기기</label>
      <label><input type="checkbox" v-model="techStrongOnly"> 기술 강세(≥15)만</label>
      <span class="jb-count">{{ visibleRows.length }}종목</span>
    </div>
    <div class="jb-union-note" v-if="board && board.scope === 'union' && board.unionStats">
      발굴 union {{ board.unionStats.totalRows }}종목 중
      <strong>{{ board.unionStats.unscoredRows }}개 "—"</strong> = 순수 발굴주(momentum 신호 없어 4-cat 미계산 — 출처 태그로 구분).
      "기술 강세(≥15)만" 필터로 momentum 밖 강종목만 좁힐 수 있음.
    </div>

    <div v-if="loading" class="jb-state">불러오는 중...</div>
    <div v-else-if="error" class="jb-state">보드 조회 실패 — 잠시 후 다시 시도</div>
    <div v-else-if="!visibleRows.length" class="jb-empty">
      <template v-if="board && board.rows && board.rows.length">
        <p class="jbe-title">필터에 맞는 종목이 없습니다</p>
        <p class="jbe-desc">상단 필터(역상관 숨기기 / 기술 강세만)를 해제해 보세요.</p>
      </template>
      <template v-else>
        <p class="jbe-title">오늘 비교할 BUY 후보가 적습니다</p>
        <p class="jbe-desc">종합점수 컷(검증/게이트, validCount≥3 &amp; 55↑)을 통과한 종목이 부족합니다.
          목록 탭(💎저평가·🚀성장·📉낙폭·💰실적·🏦수급)에서 다각도로 발굴해 보세요.</p>
        <button class="jbe-btn" @click="$emit('switch-to-list')">📋 목록 탭에서 발굴</button>
      </template>
    </div>

    <div v-else class="jb-scroll">
      <table class="jb-table">
        <thead>
          <tr class="jb-group">
            <th class="th-name"></th>
            <th colspan="4" class="g-score">① 점수 (검증/게이트)</th>
            <th colspan="2" class="g-ref">② 참고 (미검증·점수 미편입)</th>
            <th colspan="1" class="g-caution">③ 경고</th>
          </tr>
          <tr>
            <th class="th-name">종목</th>
            <th @click="setSort('totalScore')" class="th-sort">종합{{ sortMark('totalScore') }}</th>
            <th @click="setSort('technical')" class="th-sort th-tech">기술{{ sortMark('technical') }}</th>
            <th @click="setSort('earnings')" class="th-sort">실적{{ sortMark('earnings') }}</th>
            <th @click="setSort('sectorMomentum')" class="th-sort">섹터(테마){{ sortMark('sectorMomentum') }}</th>
            <th @click="setSort('timingScore')" class="th-sort th-unv">차트타이밍<small>미검증</small>{{ sortMark('timingScore') }}</th>
            <th class="th-unv">섹터강도<small>미검증</small></th>
            <th @click="setSort('supplyDemand')" class="th-sort th-caution">수급{{ sortMark('supplyDemand') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in visibleRows" :key="r.stockCode" class="jb-row"
              :class="{ 'row-unscored': !r.scored }" @click="$emit('open-stock', r.stockCode)">
            <td class="td-name">
              <span class="rn">{{ r.stockName }}</span>
              <span class="rc">{{ r.stockCode }}</span>
              <span v-for="s in (r.sources || [])" :key="s" class="src-tag">{{ sourceLabel(s) }}</span>
            </td>
            <td class="num td-total">{{ r.scored ? r.totalScore : '—' }}</td>
            <td class="num" :class="r.scored ? strongClass(r.technical) : ''">{{ r.scored ? r.technical : '—' }}</td>
            <td class="num">{{ r.scored ? r.earnings : '—' }}</td>
            <td class="num" :class="r.scored ? strongClass(r.sectorMomentum, 14) : ''">{{ r.scored ? r.sectorMomentum : '—' }}</td>
            <td class="num td-unv">{{ r.timingScore != null ? r.timingScore : '—' }}</td>
            <td class="num td-unv">{{ r.sectorStrengthRel != null ? signed(r.sectorStrengthRel) : '—' }}</td>
            <td class="num td-supply" :class="{ suspect: r.scored && r.supplyInverseSuspect }">
              {{ r.scored ? r.supplyDemand : '—' }}<span v-if="r.scored && r.supplyInverseSuspect" class="suspect-mark"
                title="고점일수록 적중률↓ 의심 (표본 작음 n=88, 확정 아님)">⚠</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="jb-legend" v-if="board && !board.timingAvailable">⚠ 차트타이밍 분석서버 미가용 — 해당 열 '—'</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import apiClient from '../../utils/api';

defineEmits(['open-stock', 'switch-to-list']);

const board = ref(null);
const loading = ref(false);
const error = ref(false);
const sortKey = ref('totalScore');
const sortDir = ref('desc');
const hideSuspect = ref(false);
const techStrongOnly = ref(false);
const scope = ref('momentum');   // 'momentum'(기본, 빠름) | 'union'(발굴 트랙 포함, 토글 시 1회 호출)

const load = async () => {
  loading.value = true; error.value = false;
  try {
    const { data } = await apiClient.get('/recommendation/judgment-board', { params: { scope: scope.value } });
    board.value = data?.data || null;
  } catch (e) {
    error.value = true;
  } finally {
    loading.value = false;
  }
};

// 발굴 트랙 포함 토글 — union 은 5트랙 조립이라 무거움(백엔드 캐시). 켤 때만 호출.
const toggleScope = () => {
  scope.value = scope.value === 'union' ? 'momentum' : 'union';
  load();
};

const visibleRows = computed(() => {
  let rows = board.value?.rows ? [...board.value.rows] : [];
  if (hideSuspect.value) rows = rows.filter(r => !r.supplyInverseSuspect);
  if (techStrongOnly.value) rows = rows.filter(r => Number(r.technical) >= 15);
  const k = sortKey.value;
  rows.sort((a, b) => {
    if (a.scored !== b.scored) return a.scored ? -1 : 1;   // 채점 종목 우선, "—"(순수 발굴주)는 하단
    const av = a[k] == null ? -Infinity : Number(a[k]);
    const bv = b[k] == null ? -Infinity : Number(b[k]);
    return sortDir.value === 'desc' ? bv - av : av - bv;
  });
  return rows;
});

const setSort = (k) => {
  if (sortKey.value === k) sortDir.value = sortDir.value === 'desc' ? 'asc' : 'desc';
  else { sortKey.value = k; sortDir.value = 'desc'; }
};
const sortMark = (k) => (sortKey.value === k ? (sortDir.value === 'desc' ? ' ▼' : ' ▲') : '');
const strongClass = (v, min = 15) => (Number(v) >= min ? 'strong' : '');
const regimeLabel = (r) => ({ BULL: '상승장', BEAR: '하락장', SIDEWAYS: '횡보장' }[r] || '미수집');
const regimeClass = (r) => (r === 'BULL' ? 'positive' : r === 'BEAR' ? 'negative' : '');
const tiltLabel = (t) => ({ BULL: '강세', NEUTRAL: '중립', BEAR: '약세' }[t] || t);
const tiltClass = (t) => (t === 'BULL' ? 'positive' : t === 'BEAR' ? 'negative' : '');
const sourceLabel = (s) => ({
  momentum: '🎯모멘텀', value: '💎저평가', growth: '🚀성장',
  oversold: '📉낙폭', earnings: '💰실적', smartmoney: '🏦수급'
}[s] || s);
const signed = (v) => { const n = Number(v); return `${n > 0 ? '+' : ''}${n}`; };

onMounted(load);
</script>

<style scoped>
.judgment-board { color: #e2e8f0; }
.jb-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.jb-head h2 { margin: 0; font-size: 17px; }
.jb-sub { font-size: 11px; opacity: 0.55; }
.jb-market {
  display: flex; gap: 18px; flex-wrap: wrap; margin: 10px 0;
  background: rgba(255, 255, 255, 0.04); border-radius: 8px; padding: 8px 14px; font-size: 12px;
}
.jbm-item small { opacity: 0.6; margin-left: 6px; }
.jb-note {
  font-size: 11px; line-height: 1.5; opacity: 0.7; margin: 4px 0 10px;
  border-left: 2px solid rgba(251, 191, 36, 0.5); padding-left: 8px;
}
.jb-filters { display: flex; gap: 16px; align-items: center; font-size: 12px; margin-bottom: 8px; flex-wrap: wrap; }
.jb-filters label { cursor: pointer; opacity: 0.85; }
.jb-count { margin-left: auto; opacity: 0.6; }
.jb-scope-btn {
  font-size: 12px; font-weight: 600; color: #7dd3fc; cursor: pointer;
  background: rgba(56, 189, 248, 0.10); border: 1px solid rgba(56, 189, 248, 0.35);
  border-radius: 6px; padding: 4px 12px;
}
.jb-scope-btn.active { background: rgba(56, 189, 248, 0.22); color: #e0f2fe; }
.jb-union-note {
  font-size: 11px; line-height: 1.5; opacity: 0.75; margin: 0 0 10px;
  border-left: 2px solid rgba(56, 189, 248, 0.4); padding-left: 8px;
}
.jb-union-note strong { color: #fbbf24; }
.row-unscored { opacity: 0.62; }
.row-unscored .td-total, .row-unscored .num { color: #64748b; }
.jb-state { padding: 24px 0; text-align: center; font-size: 13px; opacity: 0.6; }
.jb-empty { padding: 32px 16px; text-align: center; }
.jbe-title { margin: 0 0 8px; font-size: 15px; font-weight: 700; }
.jbe-desc { margin: 0 auto 16px; max-width: 440px; font-size: 12.5px; line-height: 1.6; opacity: 0.7; }
.jbe-btn {
  font-size: 13px; font-weight: 600; color: #7dd3fc; cursor: pointer;
  background: rgba(56, 189, 248, 0.12); border: 1px solid rgba(56, 189, 248, 0.4);
  border-radius: 8px; padding: 8px 16px;
}
.jbe-btn:hover { background: rgba(56, 189, 248, 0.2); }
.jb-scroll { overflow-x: auto; }
.jb-table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.jb-table th, .jb-table td { padding: 7px 9px; text-align: center; white-space: nowrap; }
.jb-group th { font-size: 11px; font-weight: 700; padding: 4px 9px; }
.g-score { color: #7dd3fc; background: rgba(56, 189, 248, 0.08); }
.g-ref { color: #cbd5e1; background: rgba(148, 163, 184, 0.08); }
.g-caution { color: #fca5a5; background: rgba(248, 113, 113, 0.08); }
.jb-table thead tr:nth-child(2) th { border-bottom: 1px solid rgba(255, 255, 255, 0.12); opacity: 0.85; }
.th-sort { cursor: pointer; user-select: none; }
.th-sort:hover { color: #fff; }
.th-name { text-align: left; }
.th-tech { color: #7dd3fc; }
.th-caution { color: #fca5a5; }
.th-unv small {
  display: inline-block; margin-left: 3px; font-size: 9px; color: #fbbf24;
  background: rgba(245, 158, 11, 0.14); padding: 0 4px; border-radius: 3px;
}
.jb-row { cursor: pointer; border-bottom: 1px solid rgba(255, 255, 255, 0.05); }
.jb-row:hover { background: rgba(255, 255, 255, 0.05); }
.td-name { text-align: left; }
.rn { font-weight: 600; }
.rc { font-size: 10px; opacity: 0.45; margin-left: 5px; }
.src-tag {
  font-size: 9px; margin-left: 5px; padding: 1px 5px; border-radius: 3px;
  background: rgba(56, 189, 248, 0.14); color: #7dd3fc;
}
.num { font-variant-numeric: tabular-nums; }
.td-total { font-weight: 700; }
.num.strong { color: #4ade80; font-weight: 700; }
.td-unv { color: #94a3b8; }
.td-supply.suspect { color: #fbbf24; }
.suspect-mark { margin-left: 2px; cursor: help; }
.positive { color: #4ade80; }
.negative { color: #f87171; }
.jb-legend { margin-top: 8px; font-size: 11px; color: #fbbf24; opacity: 0.85; }
</style>
