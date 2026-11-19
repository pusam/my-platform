<template>
  <!-- 👑 주도주 목록(2026-11-19) — 시가총액 1조 원 이상, 20거래일 평균 거래대금 순. 표시 전용: 추천 점수·봇·시그널과 무관하고
       '사라'는 신호가 아니다(성과 검증 전). 저장된 결과만 읽는다 — 60초마다 다시 읽지 않는다(하루 한 번 바뀐다). -->
  <div id="briefing-section-leaders" class="top-rec section-card leaders-card">
    <div class="section-title-row">
      <h2><span class="section-icon">👑</span> 주도주 TOP {{ items.length || limit }}</h2>
      <span v-if="basisLabel" class="leaders-basis-date" :class="{ stale: isStale }">{{ basisLabel }}</span>
    </div>
    <p class="leaders-basis">{{ LEADERS_LIST_BASIS }}</p>

    <div v-if="loading && !items.length" class="leaders-state">불러오는 중…</div>

    <template v-else-if="items.length">
      <div class="leaders-row leaders-head" aria-hidden="true">
        <span class="c-rank">#</span>
        <span class="c-name">종목</span>
        <span class="c-num">시가총액</span>
        <span class="c-num">20일 평균 거래대금</span>
        <span class="c-num">20거래일</span>
        <span class="c-num">60거래일</span>
        <span class="c-num c-net">{{ netHeader }}</span>
      </div>
      <ol class="leaders-list">
        <li v-for="it in items" :key="it.stockCode">
          <button type="button" class="leaders-row leaders-item" @click="open(it.stockCode)">
            <span class="c-rank">{{ it.rank }}</span>
            <span class="c-name">
              <span class="leaders-name">{{ it.stockName }}</span>
              <span v-if="it.market" class="leaders-market">{{ it.market }}</span>
            </span>
            <span class="c-num" data-label="시가총액">{{ eokLabel(it.marketCapEok) }}</span>
            <span class="c-num c-value" data-label="20일 평균 거래대금">{{ eokLabel(it.avgTradedValueEok) }}</span>
            <span class="c-num" data-label="20거래일" :class="signClass(it.return20d)">{{ signedPctLabel(it.return20d) }}</span>
            <span class="c-num" data-label="60거래일" :class="signClass(it.return60d)">{{ signedPctLabel(it.return60d) }}</span>
            <span class="c-num c-net" :data-label="netHeader" :class="signClass(it.netBuyEok)">{{ netCell(it) }}</span>
          </button>
        </li>
      </ol>
      <p class="leaders-foot">
        순위를 매긴 {{ rankedCount }}종목 중 위 {{ items.length }}개 · 등락은 기준일 종가를 20·60거래일 전 종가와 견준 값(수정주가) ·
        외국인+기관은 수급 확정치 표의 순매수 합이며 표에 없는 종목은 '-' 입니다
      </p>
    </template>

    <!-- 조회 실패를 '아직 계산 전'으로 보이지 않는다(§4c) -->
    <div v-else-if="unavailable" class="leaders-state">주도주 목록을 불러오지 못했습니다<br><small>잠시 뒤 다시 열어 주세요</small></div>
    <div v-else class="leaders-state">주도주 목록을 아직 계산하지 않았습니다<br><small>매일 아침 8시 20분쯤 전 거래일 종가 기준으로 갱신됩니다</small></div>
  </div>
</template>

<script>
import { recommendationAPI } from '../../utils/api'
import {
  LEADERS_LIST_BASIS, leadersBasisLabel, leadersNetHeader, leadersNetCell, eokLabel, signedPctLabel, signClass
} from '../../utils/marketDataLabels'

export default {
  name: 'SectionLeaders',
  emits: ['open-stock'],
  props: {
    limit: { type: Number, default: 20 }
  },
  data() {
    return {
      list: null,
      loading: false,
      // 조회 실패 — 보여 줄 목록이 없을 때만 켠다(받아 둔 목록이 있으면 그대로 보인다)
      unavailable: false,
      LEADERS_LIST_BASIS
    }
  },
  computed: {
    items() {
      return (this.list && Array.isArray(this.list.items)) ? this.list.items : []
    },
    isStale() {
      return !!(this.list && this.list.stale === true)
    },
    basisLabel() {
      return leadersBasisLabel(this.list && this.list.basisDate, this.isStale)
    },
    netHeader() {
      return leadersNetHeader(this.list)
    },
    windowDays() {
      return Number(this.list && this.list.windowDays) || 20
    },
    rankedCount() {
      return Number(this.list && this.list.rankedCount) || this.items.length
    }
  },
  mounted() {
    this.load()
  },
  methods: {
    eokLabel,
    signedPctLabel,
    signClass,
    netCell(item) {
      return leadersNetCell(item, this.windowDays)
    },
    /** 목록을 읽는다. 받았으면 true. 실패해도 받아 둔 목록은 지우지 않는다. */
    async load() {
      if (this.loading) return false
      this.loading = true
      try {
        const res = await recommendationAPI.getLeaders(this.limit)
        const body = (res && res.data) || res
        // 서버가 조회 실패라고 말하면(dataAvailable=false) 빈 목록을 '아직 계산 전'으로 보이지 않는다(§4c)
        if (!body || body.dataAvailable === false) {
          if (!this.items.length) this.unavailable = true
          return false
        }
        this.list = body.data || null
        this.unavailable = false
        return true
      } catch {
        if (!this.items.length) this.unavailable = true
        return false
      } finally {
        this.loading = false
      }
    },
    open(code) {
      if (code) this.$emit('open-stock', code)
    }
  }
}
</script>

<style scoped>
.section-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}
.section-title-row h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: rgba(255, 255, 255, 0.95);
}
.section-icon { margin-right: 6px; }

.leaders-basis-date {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 4px;
  color: rgba(255, 255, 255, 0.6);
  background: rgba(255, 255, 255, 0.06);
  white-space: nowrap;
}
.leaders-basis-date.stale {
  color: #f59e0b;
  background: rgba(245, 158, 11, 0.1);
}

.leaders-basis,
.leaders-foot {
  margin: 0 0 10px;
  font-size: 0.75rem;
  line-height: 1.5;
  color: var(--text-muted, #8a95a3);
  word-break: keep-all;
  overflow-wrap: break-word;
}
.leaders-foot { margin: 10px 0 0; }

.leaders-state {
  text-align: center;
  padding: 32px 16px;
  color: rgba(255, 255, 255, 0.55);
  font-size: 13px;
  line-height: 1.6;
}
.leaders-state small { color: var(--text-muted, #8a95a3); }

.leaders-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.leaders-row {
  display: grid;
  grid-template-columns: 32px minmax(0, 1.7fr) repeat(4, minmax(0, 1fr)) minmax(0, 1.4fr);
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 9px 8px;
  text-align: left;
}
.leaders-head {
  padding-top: 4px;
  padding-bottom: 6px;
  font-size: 11px;
  font-weight: 600;
  color: var(--text-muted, #8a95a3);
  border-bottom: 1px solid var(--border-light, #202832);
}
.leaders-item {
  border: 0;
  border-bottom: 1px solid var(--border-light, #202832);
  border-radius: 5px;
  background: transparent;
  color: var(--text-primary, #edf1f5);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s;
}
.leaders-list li:last-child .leaders-item { border-bottom-color: transparent; }
.leaders-item:hover { background: rgba(255, 255, 255, 0.05); }
.leaders-item:focus-visible {
  outline: 2px solid var(--primary-start, #8b93ff);
  outline-offset: -2px;
}

.c-rank {
  font-weight: 800;
  color: #f59e0b;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.leaders-list li:first-child .c-rank { color: #ef4444; }
.c-name {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}
.leaders-name {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.leaders-market {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-muted, #8a95a3);
}
.c-num {
  text-align: right;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.c-value { font-weight: 700; }
.c-num.positive { color: var(--stock-up, #f87171); }
.c-num.negative { color: var(--stock-down, #60a5fa); }

/* 좁은 화면 — 머리 줄을 숨기고 종목마다 이름 한 줄 + 이름표를 단 값들로 접는다(가로 스크롤 없음) */
@media (max-width: 760px) {
  .leaders-head { display: none; }
  .leaders-item {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 4px 12px;
    padding: 10px 6px;
  }
  .leaders-item .c-rank { flex: 0 0 24px; text-align: left; }
  .leaders-item .c-name { flex: 1 1 calc(100% - 40px); }
  .leaders-item .c-num {
    text-align: left;
    font-size: 12px;
  }
  .leaders-item .c-num::before {
    content: attr(data-label) ' ';
    color: var(--text-muted, #8a95a3);
    font-weight: 400;
  }
}
</style>
