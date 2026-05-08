<template>
  <div class="total-rec section-card">
    <div class="section-title-row">
      <h2><span class="section-icon">🎯</span> 종합 추천 (5개 신호)</h2>
      <div class="tr-controls">
        <div class="tr-filter">
          <button :class="['tr-filter-btn', { active: filter === 'ALL' }]" @click="filter = 'ALL'">전체</button>
          <button :class="['tr-filter-btn', { active: filter === '4PLUS' }]" @click="filter = '4PLUS'">4점+</button>
          <button :class="['tr-filter-btn', { active: filter === '3PLUS' }]" @click="filter = '3PLUS'">3점+</button>
        </div>
        <button class="tr-refresh" @click="reload" :disabled="loading">
          {{ loading ? '...' : '↻' }}
        </button>
      </div>
    </div>

    <div class="tr-explain">
      거래량 상위 종목 중 <strong>5가지 신호 (패턴/지지선/저평가/수급/AI추천)</strong> 동시 충족도 desc 정렬.
      4-5점이면 신호 강함 — 단, <em>단독 매수 신호 X</em>. 본인 분석 + 손절 규칙 필수.
    </div>

    <div v-if="loading && !items.length" class="tr-loading">로딩 중...</div>
    <div v-else-if="!filtered.length" class="tr-empty">조건에 맞는 종목이 없습니다.</div>

    <div class="tr-list">
      <div v-for="(item, idx) in filtered" :key="item.stockCode"
           class="tr-row" :class="getRowClass(item.matchedCount)"
           @click="goStock(item.stockCode)">
        <span class="tr-rank">{{ idx + 1 }}</span>
        <span class="tr-name-block">
          <span class="tr-name">{{ item.stockName }}</span>
          <span class="tr-code">{{ item.stockCode }}</span>
        </span>
        <span class="tr-score">{{ item.matchedCount }}/{{ item.totalCount }}</span>
        <div class="tr-signals">
          <span v-for="s in item.signals" :key="s.id"
                class="tr-sig-dot" :class="{ matched: s.matched }"
                :title="s.label + (s.matched && s.detail ? ' — ' + s.detail : '')">
            {{ getSignalShort(s.id) }}
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { quantTaAPI } from '../../utils/api'

export default {
  name: 'SectionTotalRecommendation',
  data() {
    return {
      items: [],
      loading: false,
      filter: 'ALL'
    }
  },
  computed: {
    filtered() {
      if (this.filter === '4PLUS') return this.items.filter(i => i.matchedCount >= 4)
      if (this.filter === '3PLUS') return this.items.filter(i => i.matchedCount >= 3)
      return this.items
    }
  },
  mounted() {
    this.reload()
  },
  methods: {
    async reload() {
      this.loading = true
      try {
        const res = await quantTaAPI.compositeRanking(30)
        if (res.data?.success) this.items = res.data.data || []
      } catch (e) {
        console.warn('종합 추천 로드 실패:', e?.message)
      } finally {
        this.loading = false
      }
    },
    goStock(code) {
      this.$router.push(`/stock/${code}`)
    },
    getRowClass(matched) {
      if (matched >= 4) return 'rank-strong'
      if (matched >= 3) return 'rank-medium'
      if (matched >= 1) return 'rank-weak'
      return 'rank-none'
    },
    // 신호 ID 짧은 표시 (5개 한눈에)
    getSignalShort(id) {
      return ({ PATTERN: '차트', SUPPORT: '지지', VALUE_AREA: '저평', SUPPLY: '수급', AI_RECOMMEND: 'AI' })[id] || id
    }
  }
}
</script>

<style scoped>
.total-rec { padding: 16px; }
.section-title-row { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.section-title-row h2 { margin: 0; font-size: 16px; font-weight: 700; color: #fff; flex: 1; }
.section-icon { margin-right: 4px; }

.tr-controls { display: flex; align-items: center; gap: 8px; }
.tr-filter { display: flex; gap: 2px; padding: 2px; background: rgba(255,255,255,0.04); border-radius: 8px; }
.tr-filter-btn {
  padding: 5px 12px; border: none; background: transparent;
  color: rgba(255,255,255,0.55); font-size: 12px; font-weight: 600;
  border-radius: 6px; cursor: pointer; transition: all 0.15s;
}
.tr-filter-btn:hover { color: #fff; }
.tr-filter-btn.active { background: rgba(99,102,241,0.25); color: #fff; }
.tr-refresh {
  width: 28px; height: 28px; border: none; border-radius: 6px;
  background: rgba(255,255,255,0.06); color: rgba(255,255,255,0.7);
  cursor: pointer; font-size: 14px;
}
.tr-refresh:hover { background: rgba(255,255,255,0.12); }
.tr-refresh:disabled { opacity: 0.5; cursor: wait; }

.tr-explain {
  font-size: 12px; color: rgba(255,255,255,0.55);
  background: rgba(255,255,255,0.03); padding: 10px 12px;
  border-radius: 8px; margin-bottom: 10px; line-height: 1.55;
}
.tr-explain strong { color: #facc15; }
.tr-explain em { color: #f87171; font-style: normal; }

.tr-loading, .tr-empty {
  padding: 40px; text-align: center; color: rgba(255,255,255,0.4);
}

.tr-list { display: flex; flex-direction: column; gap: 4px; }
.tr-row {
  display: grid;
  grid-template-columns: 28px 1fr auto auto;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: rgba(255,255,255,0.03);
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
  border-left: 3px solid transparent;
}
.tr-row:hover { background: rgba(99,102,241,0.1); }
.tr-row.rank-strong { border-left-color: #4ade80; }
.tr-row.rank-medium { border-left-color: #facc15; }
.tr-row.rank-weak { border-left-color: #fb923c; }
.tr-rank {
  font-size: 13px; font-weight: 700; color: rgba(255,255,255,0.55);
  text-align: center; font-variant-numeric: tabular-nums;
}
.tr-name-block { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.tr-name { font-size: 14px; font-weight: 600; color: rgba(255,255,255,0.95); }
.tr-code { font-size: 11px; color: rgba(255,255,255,0.4); font-variant-numeric: tabular-nums; }
.tr-score {
  font-size: 14px; font-weight: 700;
  padding: 4px 10px; border-radius: 8px;
  font-variant-numeric: tabular-nums;
}
.tr-row.rank-strong .tr-score { background: rgba(34,197,94,0.18); color: #4ade80; }
.tr-row.rank-medium .tr-score { background: rgba(234,179,8,0.18); color: #facc15; }
.tr-row.rank-weak .tr-score { background: rgba(249,115,22,0.18); color: #fb923c; }
.tr-row.rank-none .tr-score { background: rgba(156,163,175,0.18); color: rgba(255,255,255,0.5); }

.tr-signals { display: flex; gap: 3px; }
.tr-sig-dot {
  font-size: 9px; font-weight: 700;
  padding: 3px 6px; border-radius: 4px;
  background: rgba(255,255,255,0.05);
  color: rgba(255,255,255,0.3);
  white-space: nowrap;
}
.tr-sig-dot.matched {
  background: rgba(99,102,241,0.25);
  color: #c7d2fe;
}

@media (max-width: 768px) {
  .tr-row { grid-template-columns: 24px 1fr auto; gap: 8px; padding: 8px 10px; }
  .tr-signals { display: none; }  /* 모바일은 점수만, 디테일은 클릭해서 종목 상세 */
  .tr-name { font-size: 13px; }
  .tr-explain { font-size: 11px; padding: 8px 10px; }
}
</style>
