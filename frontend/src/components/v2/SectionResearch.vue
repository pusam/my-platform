<template>
  <div class="section-card">
    <div class="section-title-row">
      <h2><span class="section-icon">🔬</span> AI 리서치</h2>
      <router-link to="/earnings-screener" class="more-link">더 보기 →</router-link>
    </div>

    <SkeletonLoader v-if="loading" type="card" />

    <div v-else-if="error" class="section-error">데이터를 불러올 수 없습니다.</div>

    <template v-else>
      <!-- 내부 탭 -->
      <div class="inner-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          :class="['tab-btn', { active: activeTab === tab.key }]"
          @click="activeTab = tab.key"
        >{{ tab.label }}</button>
      </div>

      <!-- 실적 스크리너 -->
      <div v-if="activeTab === 'screener'" class="screener-area">
        <!-- 마법의 공식 TOP 3 -->
        <div class="screener-group" v-if="screenerData.magicFormula && screenerData.magicFormula.length > 0">
          <h4>마법의 공식</h4>
          <div
            v-for="(item, i) in screenerData.magicFormula.slice(0, 3)"
            :key="'mf-' + i"
            class="screener-row"
            @click="goToStock(item.stockCode)"
          >
            <span class="s-rank">{{ i + 1 }}</span>
            <span class="s-name">{{ item.stockName }}</span>
            <div class="s-metrics">
              <span v-if="item.per">PER {{ Number(item.per).toFixed(1) }}</span>
              <span v-if="item.roe">ROE {{ Number(item.roe).toFixed(1) }}%</span>
            </div>
          </div>
        </div>

        <!-- PEG TOP 3 -->
        <div class="screener-group" v-if="screenerData.lowPeg && screenerData.lowPeg.length > 0">
          <h4>저PEG 성장주</h4>
          <div
            v-for="(item, i) in screenerData.lowPeg.slice(0, 3)"
            :key="'peg-' + i"
            class="screener-row"
            @click="goToStock(item.stockCode)"
          >
            <span class="s-rank">{{ i + 1 }}</span>
            <span class="s-name">{{ item.stockName }}</span>
            <div class="s-metrics">
              <span v-if="item.peg">PEG {{ Number(item.peg).toFixed(2) }}</span>
            </div>
          </div>
        </div>

        <!-- 턴어라운드 TOP 3 -->
        <div class="screener-group" v-if="screenerData.turnaround && screenerData.turnaround.length > 0">
          <h4>턴어라운드</h4>
          <div
            v-for="(item, i) in screenerData.turnaround.slice(0, 3)"
            :key="'ta-' + i"
            class="screener-row"
            @click="goToStock(item.stockCode)"
          >
            <span class="s-rank">{{ i + 1 }}</span>
            <span class="s-name">{{ item.stockName }}</span>
            <div class="s-metrics">
              <span class="turnaround-type">
                {{ item.turnaroundType === 'LOSS_TO_PROFIT' ? '흑자전환' : '이익급증' }}
              </span>
            </div>
          </div>
        </div>

        <div
          v-if="!screenerData.magicFormula?.length && !screenerData.lowPeg?.length && !screenerData.turnaround?.length"
          class="empty-msg"
        >스크리너 데이터 없음</div>
      </div>

      <!-- 경제 뉴스 -->
      <div v-if="activeTab === 'news'" class="news-area">
        <div
          v-for="(item, i) in newsData.slice(0, 5)"
          :key="'news-' + i"
          class="news-item"
        >
          <div class="news-title">{{ item.title }}</div>
          <div class="news-summary" v-if="item.summary">{{ item.summary }}</div>
          <div class="news-meta">
            <span class="news-source" v-if="item.source">{{ item.source }}</span>
            <span class="news-time" v-if="item.publishedAt">{{ formatNewsTime(item.publishedAt) }}</span>
            <span class="news-sentiment" v-if="item.sentiment" :class="getSentimentClass(item.sentiment)">
              {{ item.sentiment }}
            </span>
          </div>
        </div>
        <div v-if="newsData.length === 0" class="empty-msg">오늘 뉴스 없음</div>
        <div class="more-links">
          <router-link to="/news">전체 뉴스 →</router-link>
        </div>
      </div>
    </template>
  </div>
</template>

<script>
import SkeletonLoader from './SkeletonLoader.vue'

export default {
  name: 'SectionResearch',
  components: { SkeletonLoader },
  props: {
    screenerData: { type: Object, default: () => ({}) },
    newsData: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    error: { type: Boolean, default: false }
  },
  data() {
    return {
      activeTab: 'screener',
      tabs: [
        { key: 'screener', label: '실적 스크리너' },
        { key: 'news', label: '경제 뉴스' }
      ]
    }
  },
  methods: {
    goToStock(code) {
      if (code) this.$router.push(`/stock/${code}`)
    },
    formatNewsTime(dateStr) {
      if (!dateStr) return ''
      try {
        const d = new Date(dateStr)
        return d.toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' })
      } catch { return '' }
    },
    getSentimentClass(sentiment) {
      if (!sentiment) return ''
      const s = sentiment.toLowerCase()
      if (s.includes('positive') || s.includes('긍정')) return 'positive'
      if (s.includes('negative') || s.includes('부정')) return 'negative'
      return 'neutral'
    }
  }
}
</script>

<style scoped>
.section-card {
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 20px;
  padding: 24px;
  min-height: 380px;
}

.section-title-row {
  display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px;
}
.section-title-row h2 { margin: 0; font-size: 16px; font-weight: 700; color: rgba(255,255,255,0.95); }
.section-icon { margin-right: 6px; }
.more-link { font-size: 13px; color: #667eea; text-decoration: none; }
.more-link:hover { color: #8b9cf7; }
.section-error { text-align: center; color: rgba(255,255,255,0.4); padding: 40px 0; font-size: 14px; }

/* Tabs */
.inner-tabs {
  display: flex; gap: 4px; margin-bottom: 14px;
  background: rgba(255,255,255,0.04); padding: 3px; border-radius: 10px;
}
.tab-btn {
  flex: 1; padding: 6px 12px; border: none; background: transparent;
  color: rgba(255,255,255,0.5); font-size: 12px; font-weight: 500;
  cursor: pointer; border-radius: 8px; transition: all 0.2s;
}
.tab-btn:hover { color: rgba(255,255,255,0.7); }
.tab-btn.active { background: rgba(255,255,255,0.1); color: white; font-weight: 600; }

/* Screener */
.screener-group { margin-bottom: 14px; }
.screener-group h4 {
  margin: 0 0 6px 0; font-size: 12px; color: rgba(255,255,255,0.4);
  text-transform: uppercase; letter-spacing: 0.5px;
}

.screener-row {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 8px; cursor: pointer; border-radius: 6px;
  transition: background 0.2s;
}
.screener-row:hover { background: rgba(255,255,255,0.04); }

.s-rank { font-size: 12px; color: rgba(255,255,255,0.35); width: 18px; text-align: center; }
.s-name { flex: 1; font-size: 13px; color: rgba(255,255,255,0.85); }
.s-metrics { display: flex; gap: 6px; }
.s-metrics span {
  font-size: 11px; padding: 2px 6px; border-radius: 4px;
  background: rgba(255,255,255,0.06); color: rgba(255,255,255,0.5);
}
.turnaround-type {
  background: rgba(16,185,129,0.15) !important;
  color: #10b981 !important;
}

/* News */
.news-item {
  padding: 10px 0;
  border-bottom: 1px solid rgba(255,255,255,0.04);
}
.news-item:last-child { border-bottom: none; }

.news-title {
  font-size: 13px; color: rgba(255,255,255,0.85); font-weight: 500;
  margin-bottom: 4px; line-height: 1.4;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}

.news-summary {
  font-size: 12px; color: rgba(255,255,255,0.4); line-height: 1.4; margin-bottom: 4px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}

.news-meta { display: flex; gap: 8px; align-items: center; }
.news-source { font-size: 11px; color: rgba(255,255,255,0.3); }
.news-time { font-size: 11px; color: rgba(255,255,255,0.25); }
.news-sentiment {
  font-size: 10px; padding: 1px 6px; border-radius: 4px; font-weight: 600;
}
.news-sentiment.positive { background: rgba(239,68,68,0.15); color: #ef4444; }
.news-sentiment.negative { background: rgba(59,130,246,0.15); color: #3b82f6; }
.news-sentiment.neutral { background: rgba(107,114,128,0.15); color: #9ca3af; }

.more-links { margin-top: 8px; }
.more-links a { font-size: 12px; color: rgba(255,255,255,0.4); text-decoration: none; }
.more-links a:hover { color: #667eea; }

.empty-msg { text-align: center; color: rgba(255,255,255,0.3); font-size: 13px; padding: 20px 0; }
</style>
