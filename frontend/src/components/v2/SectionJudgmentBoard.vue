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
      <label><input type="checkbox" v-model="techStrongOnly"> 기술 강(≥13)만</label>
      <span class="jb-count">{{ visibleRows.length }}종목</span>
    </div>
    <div class="jb-union-note" v-if="board && board.scope === 'union' && board.unionStats">
      발굴 union {{ board.unionStats.totalRows }}종목 중
      <strong>{{ board.unionStats.unscoredRows }}개 "—"</strong> = 순수 발굴주(momentum 신호 없어 4-cat 미계산 — 출처 태그로 구분).
      "기술 강(≥13)만" 필터로 momentum 밖 강종목만 좁힐 수 있음.
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
          <strong>발굴 트랙 포함</strong>을 켜면 저평가·성장·낙폭·실적·수급 트랙 종목까지 한 보드에서 비교할 수 있습니다.</p>
        <button v-if="scope !== 'union'" class="jbe-btn" @click="toggleScope">＋ 발굴 트랙 포함</button>
      </template>
    </div>

    <div class="jb-delay-note" v-else-if="visibleRows.length">
      ⏱ 현재가·거래대금은 캐시 스냅샷 — <strong>최대 30분 지연(실시간 아님)</strong>. 매매 전 실시간 시세를 반드시 확인하세요.
    </div>

    <div v-if="visibleRows.length && !loading && !error" class="jb-scroll">
      <table class="jb-table">
        <thead>
          <tr class="jb-group">
            <th class="th-name"></th>
            <th colspan="2" class="g-price">시세 · 유동성 <small title="캐시 스냅샷 · 최대 30분 지연 · 실시간 아님">⏱지연</small></th>
            <th colspan="4" class="g-score">① 점수 (검증/게이트)</th>
            <th colspan="4" class="g-ref">② 참고 (미검증·점수 미편입)</th>
            <th colspan="1" class="g-caution">③ 경고</th>
          </tr>
          <tr>
            <th class="th-name">종목</th>
            <th class="th-price">현재가 <small>/등락</small></th>
            <th class="th-price">거래대금</th>
            <th @click="setSort('totalScore')" class="th-sort">종합{{ sortMark('totalScore') }}</th>
            <th @click="setSort('technical')" class="th-sort th-tech">기술{{ sortMark('technical') }}</th>
            <th @click="setSort('earnings')" class="th-sort">실적{{ sortMark('earnings') }}</th>
            <th @click="setSort('sectorMomentum')" class="th-sort">섹터(테마){{ sortMark('sectorMomentum') }}</th>
            <th @click="setSort('timingScore')" class="th-sort"
                title="백테스트 hitRate 31% · 점수–수익 역상관 — 숫자 높다고 좋은 자리 아님(예측력 낮음 확인)">차트타이밍<small class="poor-badge">예측력↓</small>{{ sortMark('timingScore') }}</th>
            <th class="th-unv">섹터강도<small>미검증</small></th>
            <th @click="setSort('trackRecord')" class="th-sort"
                title="signal_outcome 최근 90일 실측 — 적중/평가완료 · 평균 α. n<3 은 표본부족 '—'(정렬 항상 하단).">이력<small class="unv-badge">90일 실측</small>{{ sortMark('trackRecord') }}</th>
            <th class="th-unv"
                title="외인/기관 연속 순매수일(investor_daily_trade 상위 재사용) — streak5 백테스트 약한 양(+) 신호 · 참고만(점수 미편입). 2일 이상만 표기, 데이터 5일 미만 '—'.">수급연속<small>참고</small></th>
            <th @click="setSort('supplyDemand')" class="th-sort th-caution">수급{{ sortMark('supplyDemand') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in visibleRows" :key="r.stockCode" class="jb-row"
              :class="{ 'row-unscored': !r.scored }" @click="openRow(r.stockCode)">
            <td class="td-name">
              <span class="rn">{{ r.stockName }}</span>
              <span class="rc">{{ r.stockCode }}</span>
              <span v-if="r.catalystLabel" class="cat-badge" :class="catClass(r.catalystDirection)"
                    :title="catTitle(r)">
                {{ catBadge(r.catalystDirection, r.catalystLabel)
                }}<small v-if="catAgeLabel(r.catalystAgeDays)" class="cat-age">{{ catAgeLabel(r.catalystAgeDays) }}</small></span>
              <span v-if="r.rvol != null" class="rvol-badge"
                    title="RVOL = 당일 거래대금 ÷ 직전 20일 평균 — 미검증 참고(② 계층, 점수 미편입). 캐시 스냅샷이라 장중엔 낮게 잡힘.">RVOL {{ fmtRvol(r.rvol) }}</span>
              <span v-for="s in (r.sources || [])" :key="s" class="src-tag">{{ sourceLabel(s) }}</span>
            </td>
            <td class="td-price">
              <span class="pp">{{ fmtPrice(r.currentPrice) }}</span>
              <span v-if="r.changeRate != null" class="pr" :class="changeClass(r.changeRate)">{{ fmtRate(r.changeRate) }}</span>
            </td>
            <td class="num td-tv">{{ fmtTradingValue(r.tradingValue) }}</td>
            <td class="num td-total">{{ r.scored ? r.totalScore : '—' }}</td>
            <td class="num" :class="r.scored ? strongClass(r.technical, TECH_STRONG) : ''">{{ r.scored ? cat(r.technical) : '—' }}</td>
            <td class="num">{{ r.scored ? cat(r.earnings) : '—' }}</td>
            <td class="num" :class="r.scored ? strongClass(r.sectorMomentum, 14) : ''">{{ r.scored ? cat(r.sectorMomentum) : '—' }}</td>
            <td class="num td-poor" title="차트타이밍 — 백테스트 예측력 낮음(hitRate 31%, 점수–수익 역상관). 참고만.">{{ r.timingScore != null ? r.timingScore : '—' }}</td>
            <td class="num td-unv">{{ r.sectorStrengthRel != null ? signed(r.sectorStrengthRel) : '—' }}</td>
            <td class="num td-track" :class="{ 'track-insufficient': !hasTrack(r) }"
                :title="hasTrack(r) ? '최근 90일 평가 완료 ' + r.trackCount + '회 중 ' + r.trackHitCount + '회 적중' : '표본부족(평가 완료 3회 미만) — 판단 근거로 쓰지 마세요'">
              {{ trackLabel(r) }}</td>
            <td class="num td-streak" :class="{ 'streak-on': hasStreak(r) }" :title="streakTitle(r)">{{ streakLabel(r) }}</td>
            <td class="num td-supply" :class="{ suspect: r.scored && r.supplyInverseSuspect }">
              {{ r.scored ? cat(r.supplyDemand) : '—' }}<span v-if="r.scored && r.supplyInverseSuspect" class="suspect-mark"
                title="고점일수록 적중률↓ 의심 (표본 작음 n=88, 확정 아님)">⚠</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="jb-timing-note" v-if="visibleRows.length && !loading && !error">
      🪝 <b>차트타이밍</b> 열은 <b>참고용</b> — 백테스트상 예측력 낮음(hitRate 31%, 점수와 수익 <b>역상관</b>).
      값이 높다고 좋은 자리 아니고, 순위·매수신호도 아닙니다.
    </div>
    <div class="jb-legend" v-if="board && !board.timingAvailable">⚠ 차트타이밍 분석서버 미가용 — 해당 열 '—'</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import apiClient from '../../utils/api';

const emit = defineEmits(['open-stock', 'switch-to-list']);

// 보드 → 상세 왕복 네비(순수 프론트): 현재 표시 순서의 종목코드 리스트를 sessionStorage 로 전달
// (새 라우트·쿼리 오염 금지 — window.open 새 탭은 sessionStorage 복사본을 상속).
// 상세(StockDetailDashboard)가 같은 키를 읽어 "◀ 이전 / 다음 ▶ (보드 N/M)" 표시(직접 진입 시 키 없음 = 미표시).
const BOARD_NAV_KEY = 'judgmentBoard.nav';

const openRow = (code) => {
  if (!code) return;
  try {
    sessionStorage.setItem(BOARD_NAV_KEY,
      JSON.stringify({ codes: visibleRows.value.map(r => r.stockCode).filter(Boolean) }));
  } catch { /* 저장 실패 시에도 이동은 진행(네비만 미표시) */ }
  const win = window.open(`/stock/${code}`, '_blank');
  if (!win) emit('open-stock', code);   // 팝업 차단 폴백 — 기존 같은 탭 이동
};

const board = ref(null);
const loading = ref(false);
const error = ref(false);
const sortKey = ref('totalScore');
const sortDir = ref('desc');
const hideSuspect = ref(false);
const techStrongOnly = ref(false);
// 발굴 트랙 포함 토글 상태 기억 — 마지막 선택을 localStorage 에 저장(프로젝트 표준 저장소).
// 기본 강제 union(무거움) 대신 "기억" 방식: 저장값 있으면 그걸로, 없으면 momentum(빠름).
const SCOPE_KEY = 'judgmentBoard.scope';
const loadSavedScope = () => {
  try { return localStorage.getItem(SCOPE_KEY) === 'union' ? 'union' : 'momentum'; }
  catch { return 'momentum'; }
};
const scope = ref(loadSavedScope());   // 'momentum'(빠름) | 'union'(발굴 트랙 포함)

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

// 발굴 트랙 포함 토글 — union 은 5트랙 조립이라 무거움(백엔드 캐시). 켤 때만 호출 + 상태 저장.
const toggleScope = () => {
  scope.value = scope.value === 'union' ? 'momentum' : 'union';
  try { localStorage.setItem(SCOPE_KEY, scope.value); } catch { /* 저장 실패 무시 */ }
  load();
};

const visibleRows = computed(() => {
  let rows = board.value?.rows ? [...board.value.rows] : [];
  if (hideSuspect.value) rows = rows.filter(r => !r.supplyInverseSuspect);
  if (techStrongOnly.value) rows = rows.filter(r => r.scored && Number(r.technical) >= TECH_STRONG);
  const k = sortKey.value;
  rows.sort((a, b) => {
    if (a.scored !== b.scored) return a.scored ? -1 : 1;   // 채점 종목 우선, "—"(순수 발굴주)는 하단
    if (k === 'trackRecord') {
      // 이력 적중률 정렬 — n<3(표본부족)은 방향 무관 항상 하단(§4c: 없는 근거를 순위에 안 태움)
      const ar = trackRate(a), br = trackRate(b);
      if (ar == null && br == null) return 0;
      if (ar == null) return 1;
      if (br == null) return -1;
      return sortDir.value === 'desc' ? br - ar : ar - br;
    }
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
// 기술 '강' 실용 임계 — 실데이터 max 14(효성중공업·삼성전기)라 ≥15 필터는 항상 0. UI 발견용 임계이며
// P1-6 측정 임계(≥15, signal_outcome 기준)와 별개. 필터·초록강조 공용.
const TECH_STRONG = 13;
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
// 신호 이력(② 참고, signal_outcome 90일 실측) — n<3 은 표본부족 "—"(muted). 정렬도 항상 하단.
const TRACK_MIN_N = 3;
const hasTrack = (r) => r.trackCount != null && Number(r.trackCount) >= TRACK_MIN_N;
const trackRate = (r) => (hasTrack(r) ? Number(r.trackHitCount) / Number(r.trackCount) : null);
const trackLabel = (r) => {
  if (!hasTrack(r)) return '—';
  const base = `${r.trackHitCount}/${r.trackCount}`;
  return r.trackAvgAlpha != null
    ? `${base} · α${Number(r.trackAvgAlpha) > 0 ? '+' : ''}${Number(r.trackAvgAlpha).toFixed(1)}%`
    : base;
};
// 카테고리 점수 NA sentinel(-1: 신호 미포착=0점)은 '—'로 — 결론카드와 일관. 산식 무관 표시 전용.
const cat = (v) => (v == null || Number(v) < 0 ? '—' : v);

// 연속 순매수일(② 참고, unverified) — 2일 이상만 표기(1일/0/데이터부족 null 은 '—'). 외/기 각각.
const STREAK_MIN = 2;
const streakVal = (v) => (v != null && Number(v) >= STREAK_MIN ? Number(v) : null);
const hasStreak = (r) => streakVal(r.foreignBuyStreak) != null || streakVal(r.institutionBuyStreak) != null;
const streakLabel = (r) => {
  const f = streakVal(r.foreignBuyStreak), i = streakVal(r.institutionBuyStreak);
  const parts = [];
  if (f) parts.push(`외${f}`);
  if (i) parts.push(`기${i}`);
  return parts.length ? parts.join('·') : '—';
};
const streakTitle = (r) => {
  if (!hasStreak(r)) return '연속 순매수 2일 미만 또는 데이터 부족';
  const f = streakVal(r.foreignBuyStreak), i = streakVal(r.institutionBuyStreak);
  const parts = [];
  if (f) parts.push(`외국인 ${f}일 연속 순매수`);
  if (i) parts.push(`기관 ${i}일 연속 순매수`);
  return parts.join(' · ') + ' (참고 — 산식 미편입)';
};

// 시세(캐시 스냅샷, ≤30분 지연) 표시 — 표시 전용, 산식 미편입
const fmtPrice = (v) => (v == null ? '—' : Number(v).toLocaleString('ko-KR'));
const fmtRate = (v) => { if (v == null) return ''; const n = Number(v); return `${n > 0 ? '+' : ''}${n.toFixed(2)}%`; };
const changeClass = (v) => { if (v == null) return ''; const n = Number(v); return n > 0 ? 'positive' : n < 0 ? 'negative' : ''; };
// 거래대금 축약 — 조/억/만. null=미실측(§4c) '—'.
const fmtTradingValue = (v) => {
  if (v == null) return '—';
  const n = Number(v);
  if (!isFinite(n) || n <= 0) return '—';
  if (n >= 1e12) return `${(n / 1e12).toFixed(1)}조`;
  if (n >= 1e8) return `${Math.round(n / 1e8).toLocaleString('ko-KR')}억`;
  if (n >= 1e4) return `${Math.round(n / 1e4).toLocaleString('ko-KR')}만`;
  return n.toLocaleString('ko-KR');
};
// RVOL 배지(V41 ② 참고, 미검증·점수 미편입) — null=미산출(§4c) 생략. "3.2x" 형태.
const fmtRvol = (v) => `${Number(v).toFixed(1)}x`;
// 재료 배지(§4b 표시 전용) — 호재/악재만 표시(중립/없음은 백엔드가 생략)
// 재료 방향 3분할 — 호재 🔥 / 악재 ⚠️ / 중립 아이콘無(회색). NONE/null 은 백엔드가 생략.
const catIcon = (dir) => (dir === 'POSITIVE' ? '🔥' : dir === 'NEGATIVE' ? '⚠️' : '');
const catClass = (dir) => (dir === 'POSITIVE' ? 'cat-pos' : dir === 'NEGATIVE' ? 'cat-neg' : 'cat-neu');
const catBadge = (dir, label) => { const i = catIcon(dir); return i ? `${i} ${label}` : label; };
const catDirLabel = (dir) => ({ POSITIVE: '호재', NEGATIVE: '악재', NEUTRAL: '중립' }[dir] || dir);
// 경과 표기(§4c 낡음 위장 방지) — 0=오늘(표기 없음) / 1=어제 / 그외 N일 전. 백엔드는 최근 2일만 보냄.
const catAgeLabel = (days) => (days == null || days <= 0 ? '' : days === 1 ? '어제' : `${days}일 전`);
const catTitle = (r) => {
  const age = catAgeLabel(r.catalystAgeDays);
  return `재료: ${r.catalystLabel}(${catDirLabel(r.catalystDirection)})${age ? ' · ' + age : ' · 오늘'}`;
};

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
.jb-delay-note {
  font-size: 11px; line-height: 1.5; margin: 2px 0 8px; padding: 5px 10px;
  border-radius: 6px; color: #fcd34d;
  background: rgba(245, 158, 11, 0.10); border: 1px solid rgba(245, 158, 11, 0.28);
}
.jb-delay-note strong { color: #fbbf24; }
.jb-scroll { overflow-x: auto; }
.jb-table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.jb-table th, .jb-table td { padding: 7px 9px; text-align: center; white-space: nowrap; }
.jb-group th { font-size: 11px; font-weight: 700; padding: 4px 9px; }
.g-price { color: #fcd34d; background: rgba(245, 158, 11, 0.07); }
.g-price small { font-size: 9px; opacity: 0.8; cursor: help; }
.g-score { color: #7dd3fc; background: rgba(56, 189, 248, 0.08); }
.g-ref { color: #cbd5e1; background: rgba(148, 163, 184, 0.08); }
.g-caution { color: #fca5a5; background: rgba(248, 113, 113, 0.08); }
.th-price { color: #fcd34d; }
.th-price small { font-size: 9px; opacity: 0.6; font-weight: 400; }
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
/* 차트타이밍 — 백테스트 역상관 확인(31%). '미검증'(amber, 섹터강도)과 구분되는 역상관 톤(적색). */
.poor-badge {
  display: inline-block; margin-left: 3px; font-size: 9px; color: #fca5a5;
  background: rgba(248, 113, 113, 0.16); padding: 0 4px; border-radius: 3px;
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
/* 재료 배지(§4b 표시 전용) — 호재(주황)/악재(적색) */
.cat-badge {
  font-size: 9px; margin-left: 5px; padding: 1px 5px; border-radius: 3px; font-weight: 600; cursor: help;
}
/* 호재 = 주황(🔥) — 시세 등락 초록(#4ade80)과 구분되게 일부러 초록 안 씀. */
.cat-badge.cat-pos { background: rgba(251, 146, 60, 0.22); color: #fdba74; font-weight: 700; }
/* 악재 = 적색(⚠️) — pill+아이콘 형태라 시세 하락 숫자(적색)와 혼동 낮음. */
.cat-badge.cat-neg { background: rgba(248, 113, 113, 0.22); color: #fca5a5; font-weight: 700; }
/* 중립 = 회색(아이콘無) — 방향 없음을 명확히(이전엔 🔥로 오인 표시). */
.cat-badge.cat-neu { background: rgba(148, 163, 184, 0.16); color: #cbd5e1; }
/* 경과 표기 — "어제" 등, 오늘 재료가 아님을 명확히(§4c). 배지 색보다 흐리게. */
.cat-badge .cat-age { margin-left: 3px; font-size: 8.5px; font-weight: 400; opacity: 0.75; }
/* RVOL 배지(② 참고 톤) — 미검증 청회색(td-unv 계열), 재료 배지(주황/적색)와 구분. */
.rvol-badge {
  font-size: 9px; margin-left: 5px; padding: 1px 5px; border-radius: 3px; cursor: help;
  background: rgba(148, 163, 184, 0.16); color: #cbd5e1; font-variant-numeric: tabular-nums;
}
/* 시세 셀 — 현재가 + 등락률(캐시 스냅샷) */
.td-price { text-align: right; line-height: 1.25; }
.td-price .pp { font-variant-numeric: tabular-nums; }
.td-price .pr { display: block; font-size: 10.5px; font-variant-numeric: tabular-nums; }
.td-tv { color: #cbd5e1; font-variant-numeric: tabular-nums; }
/* 종목 컬럼 가로스크롤 중 고정(모바일) */
.jb-table th.th-name, .jb-table td.td-name {
  position: sticky; left: 0; z-index: 1;
  background: #141428; box-shadow: 2px 0 5px rgba(0, 0, 0, 0.35);
}
.jb-table thead th.th-name { z-index: 3; }
.jb-row:hover .td-name { background: #1c1c38; }
.num { font-variant-numeric: tabular-nums; }
.td-total { font-weight: 700; }
.num.strong { color: #4ade80; font-weight: 700; }
.td-unv { color: #94a3b8; }
/* 이력 컬럼(② 참고) — 실측이지만 표본 작음. n<3 은 muted(표본부족 톤). */
.td-track { color: #94a3b8; cursor: help; }
.td-track.track-insufficient { color: #64748b; opacity: 0.7; }
.unv-badge {
  display: inline-block; margin-left: 3px; font-size: 9px; color: #94a3b8;
  background: rgba(148, 163, 184, 0.14); padding: 0 4px; border-radius: 3px;
}
.td-poor { color: #b08a8a; cursor: help; }   /* 차트타이밍 셀 — td-unv(청회색)와 구분되는 muted 적색-회색 */
/* 수급연속 셀(② 참고) — 기본 muted, 연속 있으면 옅은 초록(매수 지속 = 약한 양 신호, 참고 톤). */
.td-streak { color: #94a3b8; cursor: help; font-variant-numeric: tabular-nums; }
.td-streak.streak-on { color: #6ee7b7; }
.td-supply.suspect { color: #fbbf24; }
.suspect-mark { margin-left: 2px; cursor: help; }
.positive { color: #4ade80; }
.negative { color: #f87171; }
.jb-legend { margin-top: 8px; font-size: 11px; color: #fbbf24; opacity: 0.85; }
/* 차트타이밍 상시 안내 — 툴팁(hover)만으론 오해라 항상 노출. 열/셀과 같은 역상관 적색-회색 톤. */
.jb-timing-note {
  margin-top: 8px; font-size: 11px; line-height: 1.5; color: #b08a8a;
  background: rgba(248, 113, 113, 0.07); border-left: 2px solid rgba(248, 113, 113, 0.4);
  padding: 5px 9px; border-radius: 4px;
}
.jb-timing-note b { color: #fca5a5; }
</style>
