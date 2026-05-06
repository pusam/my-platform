<template>
  <div class="section-card live-tracker">
    <div class="section-title-row">
      <h2><span class="section-icon">🎯</span> 매수 후보 트래커</h2>
      <span class="meta">
        <span class="live-dot"></span>
        장중 실시간 · {{ updateTime }}
      </span>
    </div>

    <div v-if="!recommendations.length" class="empty-msg">
      장 시작 전 또는 추천 데이터 수집 중…
    </div>

    <div v-else class="tracker-list">
      <div
        v-for="(rec, i) in trackedList"
        :key="rec.stockCode"
        class="tracker-row"
        :class="{ surge: rec._isSurge, drop: rec._isDrop }"
        @click="goStock(rec.stockCode)"
      >
        <span class="rank">#{{ i + 1 }}</span>
        <div class="info">
          <div class="name-line">
            <span class="stock-name">{{ rec.stockName || rec.stockCode }}</span>
            <span class="score-pill" title="추천 종합 점수 (6카테고리 정규화)">{{ rec.totalScore }}점</span>
            <span
              v-if="scoreMap && scoreMap[rec.stockCode]"
              class="score-detail"
              title="상세 페이지 단기 트레이딩 / 중장기 펀더멘털 점수"
            >
              단기 {{ scoreMap[rec.stockCode].tradingScore }} · 중장기 {{ scoreMap[rec.stockCode].fundamentalScore }}
            </span>
          </div>
          <div class="code-line">{{ rec.stockCode }}</div>
        </div>
        <div class="price-area">
          <div class="price" v-if="rec.currentPrice">
            {{ Number(rec.currentPrice).toLocaleString() }}원
          </div>
          <div class="change" :class="changeColor(rec.changeRate)">
            <span class="trend-arrow">{{ trendArrow(rec.changeRate) }}</span>
            {{ formatChange(rec.changeRate) }}
          </div>
        </div>
      </div>
    </div>

    <!-- 요약 푸터 -->
    <div v-if="trackedList.length" class="tracker-summary">
      <span class="sum-item up">
        🔼 상승 {{ summary.up }}
      </span>
      <span class="sum-item flat">
        → 보합 {{ summary.flat }}
      </span>
      <span class="sum-item down">
        🔽 하락 {{ summary.down }}
      </span>
      <span class="sum-item avg" :class="changeColor(summary.avgChange)">
        평균 {{ formatChange(summary.avgChange) }}
      </span>
    </div>
  </div>
</template>

<script>
const SURGE_THRESHOLD = 2.0  // |%| 이상 변동시 하이라이트

export default {
  name: 'SectionLiveTracker',
  props: {
    recommendations: { type: Array, default: () => [] },
    // { stockCode: { tradingScore, fundamentalScore, ... } } — 부가 표시용. 비어 있어도 트래커는 정상 동작.
    scoreMap: { type: Object, default: () => ({}) }
  },
  data() {
    return {
      lastRefresh: Date.now()
    }
  },
  computed: {
    updateTime() {
      return new Date(this.lastRefresh).toLocaleTimeString('ko-KR', {
        hour: '2-digit', minute: '2-digit', second: '2-digit'
      })
    },
    trackedList() {
      return (this.recommendations || []).slice(0, 5).map(r => {
        const rate = Number(r.changeRate)
        return {
          ...r,
          _isSurge: Number.isFinite(rate) && rate >= SURGE_THRESHOLD,
          _isDrop: Number.isFinite(rate) && rate <= -SURGE_THRESHOLD
        }
      })
    },
    summary() {
      const list = this.trackedList
      if (!list.length) return { up: 0, flat: 0, down: 0, avgChange: 0 }
      let up = 0, flat = 0, down = 0
      let sum = 0, count = 0
      list.forEach(r => {
        const v = Number(r.changeRate)
        if (!Number.isFinite(v)) return
        sum += v; count++
        if (v >= 0.3) up++
        else if (v <= -0.3) down++
        else flat++
      })
      return { up, flat, down, avgChange: count ? sum / count : 0 }
    }
  },
  watch: {
    recommendations() {
      this.lastRefresh = Date.now()
    }
  },
  methods: {
    goStock(code) {
      if (code) this.$router.push(`/stock/${code}`)
    },
    formatChange(rate) {
      if (rate == null || !Number.isFinite(Number(rate))) return '-'
      const n = Number(rate)
      return (n >= 0 ? '+' : '') + n.toFixed(2) + '%'
    },
    changeColor(rate) {
      const n = Number(rate)
      if (!Number.isFinite(n)) return ''
      if (n > 0.05) return 'positive'
      if (n < -0.05) return 'negative'
      return ''
    },
    trendArrow(rate) {
      const n = Number(rate)
      if (!Number.isFinite(n)) return '·'
      if (n >= SURGE_THRESHOLD) return '🔼'
      if (n >= 0.3) return '↗'
      if (n <= -SURGE_THRESHOLD) return '🔽'
      if (n <= -0.3) return '↘'
      return '→'
    }
  }
}
</script>

<style scoped>
.section-card {
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 20px;
  padding: 18px 20px;
  color: #fff;
}
.live-tracker { background: linear-gradient(135deg, rgba(34,197,94,0.06), rgba(59,130,246,0.04)); }
.section-title-row {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 14px;
}
.section-title-row h2 {
  font-size: 17px; margin: 0; display: flex; align-items: center; gap: 8px;
}
.meta {
  display: flex; align-items: center; gap: 6px;
  font-size: 11px; color: rgba(255,255,255,0.5);
}
.live-dot {
  width: 6px; height: 6px; background: #4ade80; border-radius: 50%;
  animation: pulse 2s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.tracker-list { display: flex; flex-direction: column; gap: 6px; }
.tracker-row {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 12px;
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.15s;
}
.tracker-row:hover {
  background: rgba(255,255,255,0.06);
  transform: translateX(2px);
}
.tracker-row.surge {
  background: rgba(239,68,68,0.08);
  border-color: rgba(239,68,68,0.3);
}
.tracker-row.drop {
  background: rgba(59,130,246,0.08);
  border-color: rgba(59,130,246,0.3);
}

.rank {
  font-size: 12px; font-weight: 600;
  color: rgba(255,255,255,0.45);
  width: 26px; flex-shrink: 0;
}
.info { flex: 1; min-width: 0; }
.name-line {
  display: flex; align-items: center; gap: 8px;
}
.stock-name { font-size: 14px; font-weight: 600; }
.score-pill {
  background: rgba(124,58,237,0.2);
  color: #c084fc;
  padding: 1px 7px;
  border-radius: 8px;
  font-size: 10.5px;
  font-weight: 600;
}
.score-detail {
  font-size: 10px;
  color: rgba(255,255,255,0.5);
  font-weight: 500;
  margin-left: 2px;
  white-space: nowrap;
}
.code-line {
  font-family: monospace; font-size: 10px;
  color: rgba(255,255,255,0.35);
  margin-top: 2px;
}

.price-area {
  display: flex; flex-direction: column; align-items: flex-end; gap: 2px;
  flex-shrink: 0;
}
.price {
  font-size: 13px; font-weight: 600;
  color: rgba(255,255,255,0.85);
}
.change {
  display: flex; align-items: center; gap: 3px;
  font-size: 13px; font-weight: 700;
  color: rgba(255,255,255,0.6);
}
.trend-arrow { font-size: 11px; }
.change.positive { color: #ef4444; }
.change.negative { color: #3b82f6; }

.tracker-summary {
  display: flex; gap: 14px; flex-wrap: wrap;
  margin-top: 12px; padding-top: 10px;
  border-top: 1px solid rgba(255,255,255,0.06);
  font-size: 11.5px;
}
.sum-item { color: rgba(255,255,255,0.65); }
.sum-item.up { color: #fca5a5; }
.sum-item.down { color: #93c5fd; }
.sum-item.avg { font-weight: 700; margin-left: auto; }
.sum-item.avg.positive { color: #ef4444; }
.sum-item.avg.negative { color: #3b82f6; }

.empty-msg {
  text-align: center; padding: 24px;
  color: rgba(255,255,255,0.4); font-size: 12.5px;
}

@media (max-width: 600px) {
  .section-card { padding: 14px 16px; }
  .section-title-row h2 { font-size: 15px; }
  .meta { font-size: 10px; }
  .tracker-row { padding: 8px 10px; gap: 8px; }
  .rank { font-size: 11px; width: 22px; }
  .stock-name { font-size: 13px; }
  .score-pill { padding: 1px 5px; font-size: 9.5px; }
  .score-detail { font-size: 9px; }
  .price { font-size: 12px; }
  .change { font-size: 12px; }
  .tracker-summary { gap: 10px; font-size: 10.5px; }
}
</style>
