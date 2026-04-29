<template>
  <div class="section-card">
    <div class="section-title-row">
      <h2><span class="section-icon">📡</span> 실시간 급등/급락</h2>
      <span class="meta">
        <span class="live-dot"></span>
        {{ updateTime }}
        <button class="refresh-btn" :disabled="loading" @click="load(true)" title="수동 갱신">
          {{ loading ? '⏳' : '↻' }}
        </button>
      </span>
    </div>

    <!-- 급등/급락 토글 -->
    <div class="mover-tabs">
      <button :class="['mover-tab', { active: mode === 'rise' }]" @click="setMode('rise')">
        🔼 급등 TOP
      </button>
      <button :class="['mover-tab', { active: mode === 'fall' }]" @click="setMode('fall')">
        🔽 급락 TOP
      </button>
    </div>

    <!-- 로딩 -->
    <div v-if="loading && !rows.length" class="loading-state">
      <div v-for="i in 5" :key="'sk-'+i" class="skel-row"></div>
    </div>

    <!-- 결과 -->
    <div v-else-if="rows.length" class="mover-list">
      <div
        v-for="(r, i) in rows"
        :key="r.stockCode"
        class="mover-row"
        @click="goStock(r.stockCode)"
      >
        <span class="rank">#{{ i + 1 }}</span>
        <div class="info">
          <div class="name">{{ r.stockName || r.stockCode }}</div>
          <div class="code-row">
            <span class="code">{{ r.stockCode }}</span>
            <span class="vol" v-if="r.volume">거래량 {{ formatVolume(r.volume) }}</span>
          </div>
        </div>
        <div class="price-area">
          <div class="price">{{ formatNum(r.currentPrice) }}원</div>
          <div class="change" :class="changeColor(r.changeRate)">
            {{ formatChange(r.changeRate) }}
          </div>
        </div>
      </div>
    </div>

    <div v-else class="empty-msg">
      장이 열려 있을 때 데이터가 갱신됩니다.
    </div>
  </div>
</template>

<script>
import { marketAPI } from '../../utils/api'

const TOP_N = 8                // 표시 종목 수
const REFRESH_MS = 60000       // 60초 폴링

export default {
  name: 'SectionLiveMovers',
  props: {
    // 부모가 폴링 활성화 여부 결정 (장중 탭일 때만 true)
    active: { type: Boolean, default: true }
  },
  data() {
    return {
      mode: 'rise',          // 'rise' | 'fall'
      rows: [],
      loading: false,
      lastRefresh: 0,
      _timer: null
    }
  },
  computed: {
    updateTime() {
      if (!this.lastRefresh) return ''
      return new Date(this.lastRefresh).toLocaleTimeString('ko-KR', {
        hour: '2-digit', minute: '2-digit', second: '2-digit'
      })
    }
  },
  watch: {
    active: {
      immediate: true,
      handler(v) {
        if (v) {
          this.load()
          this.startPolling()
        } else {
          this.stopPolling()
        }
      }
    }
  },
  beforeUnmount() {
    this.stopPolling()
  },
  methods: {
    setMode(m) {
      if (m === this.mode) return
      this.mode = m
      this.rows = []
      this.load()
    },
    async load(force = false) {
      if (this.loading && !force) return
      this.loading = true
      try {
        const fn = this.mode === 'rise' ? marketAPI.getPriceRise : marketAPI.getPriceFall
        const res = await fn.call(marketAPI)
        const body = res?.data || res
        const list = Array.isArray(body?.data) ? body.data : []
        this.rows = list.slice(0, TOP_N)
        this.lastRefresh = Date.now()
      } catch (e) {
        // 호출 실패해도 기존 결과 유지
        if (!this.rows.length) console.warn('[LiveMovers] 로딩 실패', e?.message)
      } finally {
        this.loading = false
      }
    },
    startPolling() {
      this.stopPolling()
      this._timer = setInterval(() => this.load(), REFRESH_MS)
    },
    stopPolling() {
      if (this._timer) {
        clearInterval(this._timer)
        this._timer = null
      }
    },
    goStock(code) {
      if (code) this.$router.push(`/stock/${code}`)
    },
    formatNum(n) {
      if (n == null) return '-'
      return Number(n).toLocaleString('ko-KR', { maximumFractionDigits: 0 })
    },
    formatChange(rate) {
      if (rate == null) return '-'
      const n = Number(rate)
      if (!Number.isFinite(n)) return '-'
      return (n >= 0 ? '+' : '') + n.toFixed(2) + '%'
    },
    formatVolume(v) {
      const n = Number(v)
      if (!Number.isFinite(n)) return '-'
      if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
      if (n >= 1_000) return (n / 1_000).toFixed(0) + 'K'
      return String(Math.round(n))
    },
    changeColor(rate) {
      const n = Number(rate)
      if (!Number.isFinite(n)) return ''
      if (n > 0) return 'positive'
      if (n < 0) return 'negative'
      return ''
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
.section-title-row {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 12px;
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
.refresh-btn {
  background: rgba(255,255,255,0.08);
  border: 1px solid rgba(255,255,255,0.15);
  color: rgba(255,255,255,0.7);
  width: 22px; height: 22px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  display: inline-flex; align-items: center; justify-content: center;
  padding: 0;
  margin-left: 4px;
}
.refresh-btn:hover:not(:disabled) {
  background: rgba(255,255,255,0.14);
  color: #fff;
}
.refresh-btn:disabled { opacity: 0.5; cursor: wait; }

.mover-tabs {
  display: flex; gap: 6px;
  background: rgba(0,0,0,0.18);
  padding: 4px;
  border-radius: 10px;
  margin-bottom: 12px;
}
.mover-tab {
  flex: 1;
  padding: 6px 12px;
  background: transparent;
  border: none;
  color: rgba(255,255,255,0.55);
  font-size: 12.5px;
  font-weight: 600;
  border-radius: 7px;
  cursor: pointer;
  transition: all 0.15s;
}
.mover-tab:hover { color: rgba(255,255,255,0.85); }
.mover-tab.active {
  background: rgba(255,255,255,0.08);
  color: #fff;
}

.loading-state { display: flex; flex-direction: column; gap: 6px; }
.skel-row {
  height: 44px; border-radius: 10px;
  background: rgba(255,255,255,0.04);
  animation: skel 1.5s infinite;
}
@keyframes skel {
  0%, 100% { opacity: 0.5; }
  50% { opacity: 0.2; }
}

.mover-list { display: flex; flex-direction: column; gap: 5px; }
.mover-row {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 12px;
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 9px;
  cursor: pointer;
  transition: all 0.15s;
}
.mover-row:hover {
  background: rgba(255,255,255,0.06);
  transform: translateX(2px);
}
.rank {
  font-size: 12px; font-weight: 700;
  color: rgba(255,255,255,0.4);
  width: 26px; flex-shrink: 0;
}
.info { flex: 1; min-width: 0; }
.name {
  font-size: 13.5px; font-weight: 600;
  color: rgba(255,255,255,0.92);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.code-row { display: flex; gap: 8px; margin-top: 1px; }
.code {
  font-family: monospace; font-size: 10px;
  color: rgba(255,255,255,0.4);
}
.vol {
  font-size: 10px; color: rgba(255,255,255,0.5);
}
.price-area {
  display: flex; flex-direction: column; align-items: flex-end; gap: 1px;
  flex-shrink: 0;
}
.price {
  font-size: 12.5px;
  color: rgba(255,255,255,0.85);
  font-family: monospace;
}
.change {
  font-size: 13px; font-weight: 700;
  color: rgba(255,255,255,0.6);
}
.change.positive { color: #ef4444; }
.change.negative { color: #3b82f6; }

.empty-msg {
  text-align: center; padding: 24px;
  color: rgba(255,255,255,0.4); font-size: 12.5px;
}

@media (max-width: 600px) {
  .section-card { padding: 14px 16px; }
  .section-title-row h2 { font-size: 15px; }
  .meta { font-size: 10px; }
  .mover-tab { padding: 5px 8px; font-size: 11.5px; }
  .mover-row { padding: 8px 10px; gap: 8px; }
  .name { font-size: 12.5px; }
  .price { font-size: 11.5px; }
  .change { font-size: 12px; }
  .vol { display: none; }   /* 모바일은 거래량 숨김 */
}
</style>
