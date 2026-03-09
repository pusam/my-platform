<template>
  <div class="smart-table-wrap">

    <!-- ═══ Header ═══ -->
    <div class="smart-header">
      <div class="smart-header-left">
        <div class="header-icon-box">✨</div>
        <div>
          <h2 class="header-title">{{ title }}</h2>
          <p class="header-sub">영업이익률 + ROE + 저PER 복합 랭킹 · AI 매력 점수 기반 추천</p>
        </div>
      </div>
      <div class="search-box">
        <span class="search-icon">🔍</span>
        <input
          v-model="query"
          placeholder="종목명 · 코드 · 섹터"
          class="search-input"
        />
      </div>
    </div>

    <!-- ═══ Top 3 Picks ═══ -->
    <div class="top-picks">
      <div
        v-for="(s, idx) in top3"
        :key="s.stockCode"
        :class="['pick-card', `grade-${s.grade.key}`]"
        @click="$emit('stock-click', s)"
      >
        <!-- 순위 뱃지 -->
        <div :class="['pick-rank-badge', `grade-${s.grade.key}`]">{{ idx + 1 }}</div>
        <!-- 글로우 -->
        <div :class="['pick-glow', `grade-${s.grade.key}`]"></div>

        <div class="pick-body">
          <!-- 스코어 + 종목명 -->
          <div class="pick-top">
            <ScoreRing :score="s.smartScore" :grade="s.grade" />
            <div class="pick-info">
              <h3 class="pick-name">{{ s.stockName }}</h3>
              <p class="pick-meta">{{ s.stockCode }} · {{ s.sector || '-' }}</p>
            </div>
          </div>

          <!-- 프로그레스 바 -->
          <div class="score-bar-track">
            <div
              :class="['score-bar-fill', `grade-${s.grade.key}`]"
              :style="{ width: s.smartScore + '%' }"
            ></div>
          </div>

          <!-- 인사이트 -->
          <p class="pick-insight">{{ s.insight }}</p>

          <!-- 뱃지 -->
          <div class="badge-row">
            <span
              v-for="(b, i) in s.badges.slice(0, 3)"
              :key="i"
              :class="['smart-badge', b.colorClass]"
              :title="b.tip"
            >
              {{ b.emoji }} {{ b.label }}
            </span>
          </div>

          <!-- 핵심 지표 3종 -->
          <div class="pick-metrics">
            <div class="pick-metric-box">
              <div class="pick-metric-label">PER</div>
              <div class="pick-metric-value">{{ s.per != null ? s.per.toFixed(1) : '-' }}</div>
            </div>
            <div class="pick-metric-box">
              <div class="pick-metric-label">ROE</div>
              <div class="pick-metric-value">{{ s.roe != null ? s.roe.toFixed(1) + '%' : '-' }}</div>
            </div>
            <div class="pick-metric-box">
              <div class="pick-metric-label">영업이익률</div>
              <div class="pick-metric-value">{{ s.operatingMargin != null ? s.operatingMargin.toFixed(1) + '%' : '-' }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ═══ 메인 테이블 ═══ -->
    <div class="smart-table-container">
      <!-- 테이블 헤더 -->
      <div class="smart-table-header">
        <div class="col-rank sortable" @click="toggleSort('rank')"># <SortArrow :active="sortKey === 'rank'" :dir="sortDir" /></div>
        <div class="col-grade sortable" @click="toggleSort('score')">등급 <SortArrow :active="sortKey === 'score'" :dir="sortDir" /></div>
        <div class="col-stock">종목</div>
        <div class="col-badges">뱃지</div>
        <div class="col-metric sortable" @click="toggleSort('per')">PER <SortArrow :active="sortKey === 'per'" :dir="sortDir" /></div>
        <div class="col-metric sortable" @click="toggleSort('pbr')">PBR <SortArrow :active="sortKey === 'pbr'" :dir="sortDir" /></div>
        <div class="col-metric sortable" @click="toggleSort('roe')">ROE <SortArrow :active="sortKey === 'roe'" :dir="sortDir" /></div>
        <div class="col-metric-wide sortable" @click="toggleSort('margin')">영업이익률 <SortArrow :active="sortKey === 'margin'" :dir="sortDir" /></div>
        <div class="col-metric sortable" @click="toggleSort('cap')">시가총액 <SortArrow :active="sortKey === 'cap'" :dir="sortDir" /></div>
        <div class="col-price">현재가</div>
      </div>

      <!-- 테이블 본문 -->
      <div v-if="sortedStocks.length === 0" class="empty-state">
        검색 결과가 없습니다.
      </div>

      <div
        v-for="s in sortedStocks"
        :key="s.stockCode"
        class="smart-table-row-group"
      >
        <!-- 행 -->
        <div
          :class="['smart-table-row', { 'row-top-tier': s.smartScore >= 80 }]"
          @click="toggleExpand(s.stockCode)"
        >
          <!-- # -->
          <div class="col-rank">
            <span :class="['rank-num', { 'rank-top': s.magicFormulaRank <= 3 }]"
                  :style="s.magicFormulaRank <= 3 ? { color: s.grade.color } : {}">
              {{ s.magicFormulaRank }}
            </span>
          </div>

          <!-- 등급 도넛 -->
          <div class="col-grade">
            <ScoreRing :score="s.smartScore" :grade="s.grade" :size="40" />
          </div>

          <!-- 종목명 + 인사이트 -->
          <div class="col-stock">
            <div class="stock-name-row">
              <span class="stock-name">{{ s.stockName }}</span>
              <span class="stock-code">{{ s.stockCode }}</span>
              <span v-if="s.market" class="stock-market">{{ s.market }}</span>
            </div>
            <p class="stock-insight">{{ s.insight }}</p>
          </div>

          <!-- 뱃지 -->
          <div class="col-badges">
            <span
              v-for="(b, i) in s.badges.slice(0, 2)"
              :key="i"
              :class="['smart-badge', 'badge-sm', b.colorClass]"
              :title="b.tip"
            >
              {{ b.emoji }} {{ b.label }}
            </span>
            <span v-if="s.badges.length > 2" class="badge-more">+{{ s.badges.length - 2 }}</span>
          </div>

          <!-- PER -->
          <div class="col-metric">
            <span :class="numClass(s.per, 'low', { excellent: 6, ok: 10, bad: 25 })">
              {{ s.per != null ? s.per.toFixed(1) : '-' }}
            </span>
          </div>

          <!-- PBR -->
          <div class="col-metric">
            <span :class="numClass(s.pbr, 'low', { excellent: 0.5, ok: 1, bad: 3 })">
              {{ s.pbr != null ? s.pbr.toFixed(2) : '-' }}
            </span>
          </div>

          <!-- ROE -->
          <div class="col-metric">
            <span :class="numClass(s.roe, 'high', { excellent: 20, ok: 15, bad: 5 })">
              {{ s.roe != null ? s.roe.toFixed(1) + '%' : '-' }}
            </span>
          </div>

          <!-- 영업이익률 -->
          <div class="col-metric-wide">
            <span :class="numClass(s.operatingMargin, 'high', { excellent: 25, ok: 15, bad: 5 })">
              {{ s.operatingMargin != null ? s.operatingMargin.toFixed(1) + '%' : '-' }}
            </span>
          </div>

          <!-- 시가총액 -->
          <div class="col-metric dim">{{ fmtCap(s.marketCap) }}</div>

          <!-- 현재가 -->
          <div class="col-price">{{ fmtPrice(s.currentPrice) }}</div>
        </div>

        <!-- 확장 디테일 -->
        <transition name="slide-down">
          <div v-if="expanded === s.stockCode" class="detail-panel">
            <div class="detail-section">
              <h4 class="detail-title">밸류에이션</h4>
              <div class="detail-row"><span>PER</span><span>{{ s.per?.toFixed(1) ?? '-' }}</span></div>
              <div class="detail-row"><span>PBR</span><span>{{ s.pbr?.toFixed(2) ?? '-' }}</span></div>
              <div class="detail-row"><span>PEG</span><span>{{ s.peg?.toFixed(2) ?? '-' }}</span></div>
              <div class="detail-row"><span>EPS</span><span>{{ s.eps != null ? s.eps.toLocaleString() + '원' : '-' }}</span></div>
              <div class="detail-row"><span>BPS</span><span>{{ s.bps != null ? s.bps.toLocaleString() + '원' : '-' }}</span></div>
            </div>
            <div class="detail-section">
              <h4 class="detail-title">수익성</h4>
              <div class="detail-row"><span>ROE</span><span>{{ s.roe?.toFixed(1) ?? '-' }}%</span></div>
              <div class="detail-row"><span>영업이익률</span><span>{{ s.operatingMargin?.toFixed(1) ?? '-' }}%</span></div>
              <div class="detail-row"><span>순이익률</span><span>{{ s.netMargin?.toFixed(1) ?? '-' }}%</span></div>
              <div class="detail-row"><span>배당수익률</span><span>{{ s.dividendYield?.toFixed(1) ?? '-' }}%</span></div>
            </div>
            <div class="detail-section">
              <h4 class="detail-title">성장성</h4>
              <div class="detail-row"><span>EPS 성장률</span><span>{{ s.epsGrowth?.toFixed(1) ?? '-' }}%</span></div>
              <div class="detail-row"><span>매출 성장률</span><span>{{ s.revenueGrowth?.toFixed(1) ?? '-' }}%</span></div>
              <div class="detail-row"><span>이익 성장률</span><span>{{ s.profitGrowth?.toFixed(1) ?? '-' }}%</span></div>
            </div>
            <div class="detail-section">
              <h4 class="detail-title">랭킹 상세</h4>
              <div class="detail-row"><span>영업이익률 순위</span><span>{{ s.operatingMarginRank ? '#' + s.operatingMarginRank : '-' }}</span></div>
              <div class="detail-row"><span>ROE 순위</span><span>{{ s.roeRank ? '#' + s.roeRank : '-' }}</span></div>
              <div class="detail-row"><span>PER 순위</span><span>{{ s.perRank ? '#' + s.perRank : '-' }}</span></div>
              <div class="detail-row"><span>복합 점수</span><span>{{ s.magicFormulaScore ?? '-' }}</span></div>
              <div class="detail-row"><span>Smart Score</span><span>{{ s.smartScore }}/100</span></div>
            </div>
          </div>
        </transition>
      </div>
    </div>

    <!-- Footer -->
    <div class="smart-footer">
      총 {{ sortedStocks.length }}개 종목 · Smart Score = 마법의 공식 순위 기반 0~100 환산
    </div>
  </div>
</template>

<script>
// ═══════════════════════════════════════════════════════════════════
//  getBadges(stock) — 스마트 뱃지 자동 생성 (외부에서도 import 가능)
// ═══════════════════════════════════════════════════════════════════
export function getBadges(stock) {
  const badges = []

  if (stock.per != null && stock.per > 0 && stock.per < 10
      && stock.pbr != null && stock.pbr > 0 && stock.pbr < 1) {
    badges.push({ emoji: '🛡️', label: '저평가', colorClass: 'badge-blue', tip: `PER ${stock.per.toFixed(1)} · PBR ${stock.pbr.toFixed(2)}` })
  }
  if (stock.operatingMargin != null && stock.operatingMargin > 20) {
    badges.push({ emoji: '💰', label: '고마진', colorClass: 'badge-green', tip: `영업이익률 ${stock.operatingMargin.toFixed(1)}%` })
  }
  if (stock.roe != null && stock.roe > 15) {
    badges.push({ emoji: '🚀', label: '성장주', colorClass: 'badge-red', tip: `ROE ${stock.roe.toFixed(1)}%` })
  }
  if (stock.institutionPick || (stock.volumeRatio != null && stock.volumeRatio > 200)) {
    badges.push({ emoji: '🏦', label: '기관PICK', colorClass: 'badge-purple', tip: '최근 기관 순매수 유입' })
  }
  if (stock.dividendYield != null && stock.dividendYield > 3) {
    badges.push({ emoji: '💎', label: '고배당', colorClass: 'badge-cyan', tip: `배당수익률 ${stock.dividendYield.toFixed(1)}%` })
  }
  if (stock.profitGrowth != null && stock.profitGrowth > 50) {
    badges.push({ emoji: '⚡', label: '턴어라운드', colorClass: 'badge-orange', tip: `순이익 +${stock.profitGrowth.toFixed(0)}%` })
  }
  if (stock.peg != null && stock.peg > 0 && stock.peg <= 1.0
      && stock.marketCap != null && stock.marketCap >= 500) {
    badges.push({ emoji: '🔥', label: '저PEG', colorClass: 'badge-pink', tip: `PEG ${stock.peg.toFixed(2)} (저평가 성장주)` })
  }

  return badges
}

// AI 한 줄 요약 생성
function generateInsight(stock) {
  if (stock.aiInsight) return stock.aiInsight
  const p = []
  if (stock.per != null && stock.per < 8) p.push('실적 대비 극심한 저평가 구간')
  else if (stock.per != null && stock.per < 12) p.push('실적 대비 저평가 구간')
  if (stock.roe != null && stock.roe > 20) p.push('높은 자본효율성 보유')
  else if (stock.roe != null && stock.roe > 15) p.push('양호한 수익성')
  if (stock.operatingMargin != null && stock.operatingMargin > 25) p.push('독보적 마진 경쟁력')
  else if (stock.operatingMargin != null && stock.operatingMargin > 15) p.push('안정적 마진 구조')
  if (stock.dividendYield != null && stock.dividendYield > 3) p.push('배당 매력 보유')
  if (stock.profitGrowth != null && stock.profitGrowth > 30) p.push('가파른 이익 성장세')
  if (stock.pbr != null && stock.pbr < 0.7) p.push('순자산 대비 할인 거래 중')
  return p.length > 0 ? p.slice(0, 2).join(', ') : '실적 대비 저평가 구간, 배당 매력 보유'
}

// Smart Score 계산
function calcScore(rank, total) {
  if (!rank || !total || total <= 1) return rank === 1 ? 100 : 0
  return Math.round(100 - ((rank - 1) / (total - 1)) * 100)
}

// 등급
function getGrade(score) {
  if (score >= 90) return { key: 's', grade: 'S', color: '#a78bfa', ring: '#8b5cf6' }
  if (score >= 80) return { key: 'a', grade: 'A', color: '#fbbf24', ring: '#f59e0b' }
  if (score >= 60) return { key: 'b', grade: 'B', color: '#38bdf8', ring: '#0ea5e9' }
  return { key: 'c', grade: 'C', color: '#94a3b8', ring: '#64748b' }
}

// ═══════════════════════════════════════════════════════════════════
//  인라인 서브 컴포넌트: ScoreRing, SortArrow
// ═══════════════════════════════════════════════════════════════════
const ScoreRing = {
  props: {
    score: { type: Number, default: 0 },
    grade: { type: Object, required: true },
    size: { type: Number, default: 52 }
  },
  computed: {
    r() { return (this.size - 8) / 2 },
    c() { return 2 * Math.PI * this.r },
    offset() { return this.c - (this.score / 100) * this.c }
  },
  template: `
    <div class="score-ring" :style="{ width: size + 'px', height: size + 'px' }">
      <svg :width="size" :height="size" class="score-ring-svg">
        <circle :cx="size/2" :cy="size/2" :r="r"
                fill="none" stroke-width="3.5" stroke="rgba(0,0,0,0.08)" />
        <circle :cx="size/2" :cy="size/2" :r="r"
                fill="none" stroke-width="3.5" stroke-linecap="round"
                :stroke="grade.ring"
                :stroke-dasharray="c" :stroke-dashoffset="offset"
                class="score-ring-progress" />
      </svg>
      <div class="score-ring-label">
        <span class="score-ring-grade" :style="{ color: grade.color }">{{ grade.grade }}</span>
        <span class="score-ring-num">{{ score }}</span>
      </div>
    </div>
  `
}

const SortArrow = {
  props: {
    active: Boolean,
    dir: String
  },
  template: `
    <span :class="['sort-arrow', { active }]">{{ active ? (dir === 'asc' ? '▲' : '▼') : '▽' }}</span>
  `
}

export default {
  name: 'MagicFormulaSmartTable',
  components: { ScoreRing, SortArrow },
  props: {
    stocks: { type: Array, default: () => [] },
    title: { type: String, default: '마법의 공식' }
  },
  emits: ['stock-click'],
  data() {
    return {
      query: '',
      sortKey: 'rank',
      sortDir: 'asc',
      expanded: null
    }
  },
  computed: {
    enriched() {
      const total = this.stocks.length
      return this.stocks.map(s => {
        const score = calcScore(s.magicFormulaRank, total)
        return { ...s, smartScore: score, grade: getGrade(score), badges: getBadges(s), insight: generateInsight(s) }
      })
    },
    top3() {
      return this.enriched.filter(s => s.magicFormulaRank <= 3).slice(0, 3)
    },
    sortedStocks() {
      let list = this.enriched
      if (this.query) {
        const q = this.query.toLowerCase()
        list = list.filter(s =>
          (s.stockName || '').toLowerCase().includes(q)
          || (s.stockCode || '').includes(q)
          || (s.sector || '').toLowerCase().includes(q))
      }
      const key = this.sortKey
      const dir = this.sortDir
      return [...list].sort((a, b) => {
        const pick = s => {
          switch (key) {
            case 'score': return s.smartScore
            case 'per': return s.per ?? 9999
            case 'pbr': return s.pbr ?? 9999
            case 'roe': return s.roe ?? -9999
            case 'margin': return s.operatingMargin ?? -9999
            case 'cap': return s.marketCap ?? 0
            default: return s.magicFormulaRank ?? 9999
          }
        }
        const diff = pick(a) - pick(b)
        return dir === 'asc' ? diff : -diff
      })
    }
  },
  methods: {
    toggleSort(key) {
      if (this.sortKey === key) {
        this.sortDir = this.sortDir === 'asc' ? 'desc' : 'asc'
      } else {
        this.sortKey = key
        this.sortDir = ['roe', 'margin', 'score', 'cap'].includes(key) ? 'desc' : 'asc'
      }
    },
    toggleExpand(code) {
      this.expanded = this.expanded === code ? null : code
    },
    numClass(value, good, thresholds) {
      if (value == null) return 'num-dim'
      const { excellent, ok, bad } = thresholds
      if (good === 'high') {
        if (excellent != null && value >= excellent) return 'num-excellent'
        if (ok != null && value >= ok) return 'num-good'
        if (bad != null && value <= bad) return 'num-bad'
      } else {
        if (excellent != null && value <= excellent) return 'num-excellent'
        if (ok != null && value <= ok) return 'num-good'
        if (bad != null && value >= bad) return 'num-bad'
      }
      return 'num-normal'
    },
    fmtCap(v) {
      if (v == null) return '-'
      if (v >= 10000) return (v / 10000).toFixed(1) + '조'
      if (v >= 1000) return (v / 1000).toFixed(0) + '천억'
      return v.toFixed(0) + '억'
    },
    fmtPrice(v) {
      if (v == null) return '-'
      return v.toLocaleString('ko-KR') + '원'
    }
  }
}
</script>

<style scoped>
/* ═══════════════════════════════════════════════════════════════════
   HIGH-CONTRAST LIGHT THEME
   ═══════════════════════════════════════════════════════════════════ */

/* ═══════════════════════════════════════════════════════════════════
   1. 레이아웃 / 헤더
   ═══════════════════════════════════════════════════════════════════ */
.smart-table-wrap {
  width: 100%;
  color: #1F2937;
}

.smart-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 24px;
  gap: 16px;
  flex-wrap: wrap;
}
.smart-header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.header-icon-box {
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(139,92,246,0.15), rgba(109,40,217,0.08));
  border: 1px solid rgba(139,92,246,0.25);
  font-size: 20px;
}
.header-title {
  font-size: 22px;
  font-weight: 800;
  color: #111827;
  margin: 0;
  letter-spacing: -0.3px;
}
.header-sub {
  font-size: 12px;
  color: #6B7280;
  margin: 2px 0 0;
}

/* 검색 */
.search-box {
  position: relative;
}
.search-icon {
  position: absolute;
  left: 12px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 13px;
  opacity: 0.5;
}
.search-input {
  width: 240px;
  padding: 9px 14px 9px 36px;
  border-radius: 12px;
  border: 1px solid #D1D5DB;
  background: #F9FAFB;
  color: #1F2937;
  font-size: 13px;
  outline: none;
  transition: border-color 0.2s;
}
.search-input::placeholder { color: #9CA3AF; }
.search-input:focus {
  border-color: #7C3AED;
  box-shadow: 0 0 0 2px rgba(124,58,237,0.15);
}

/* ═══════════════════════════════════════════════════════════════════
   2. Top 3 카드
   ═══════════════════════════════════════════════════════════════════ */
.top-picks {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}
.pick-card {
  position: relative;
  overflow: hidden;
  border-radius: 18px;
  padding: 20px;
  cursor: pointer;
  transition: transform 0.25s, box-shadow 0.25s;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}
.pick-card:hover {
  transform: scale(1.02);
}

/* 등급별 카드 테마 - 고대비 */
.pick-card.grade-s {
  background: rgba(139,92,246,0.08);
  border: 1.5px solid rgba(139,92,246,0.35);
}
.pick-card.grade-s:hover { box-shadow: 0 8px 24px rgba(139,92,246,0.2); }

.pick-card.grade-a {
  background: rgba(245,158,11,0.08);
  border: 1.5px solid rgba(245,158,11,0.35);
}
.pick-card.grade-a:hover { box-shadow: 0 8px 24px rgba(245,158,11,0.18); }

.pick-card.grade-b {
  background: rgba(37,99,235,0.06);
  border: 1.5px solid rgba(37,99,235,0.3);
}
.pick-card.grade-b:hover { box-shadow: 0 8px 24px rgba(37,99,235,0.15); }

.pick-card.grade-c {
  background: rgba(107,114,128,0.06);
  border: 1.5px solid rgba(107,114,128,0.25);
}
.pick-card.grade-c:hover { box-shadow: 0 4px 12px rgba(0,0,0,0.08); }

/* 순위 뱃지 (우상단) */
.pick-rank-badge {
  position: absolute;
  top: 14px;
  right: 14px;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 14px;
  font-weight: 900;
  z-index: 2;
}
.pick-rank-badge.grade-s { background: linear-gradient(135deg, #8b5cf6, #7c3aed); box-shadow: 0 4px 14px rgba(139,92,246,0.4); }
.pick-rank-badge.grade-a { background: linear-gradient(135deg, #f59e0b, #d97706); box-shadow: 0 4px 14px rgba(245,158,11,0.4); }
.pick-rank-badge.grade-b { background: linear-gradient(135deg, #2563EB, #1D4ED8); box-shadow: 0 4px 14px rgba(37,99,235,0.3); }
.pick-rank-badge.grade-c { background: linear-gradient(135deg, #6B7280, #4B5563); }

/* 글로우 */
.pick-glow {
  position: absolute;
  top: -60px;
  right: -60px;
  width: 160px;
  height: 160px;
  border-radius: 50%;
  opacity: 0.06;
  filter: blur(40px);
  pointer-events: none;
  transition: opacity 0.4s;
}
.pick-card:hover .pick-glow { opacity: 0.1; }
.pick-glow.grade-s { background: linear-gradient(135deg, #8b5cf6, #7c3aed); }
.pick-glow.grade-a { background: linear-gradient(135deg, #f59e0b, #d97706); }
.pick-glow.grade-b { background: linear-gradient(135deg, #2563EB, #1D4ED8); }
.pick-glow.grade-c { background: #6B7280; }

.pick-body { position: relative; z-index: 1; }

.pick-top {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.pick-info { min-width: 0; }
.pick-name {
  font-weight: 800;
  font-size: 15px;
  color: #111827;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pick-meta {
  font-size: 11px;
  color: #6B7280;
  margin: 1px 0 0;
}

/* 프로그레스 바 */
.score-bar-track {
  width: 100%;
  height: 5px;
  border-radius: 3px;
  background: rgba(0,0,0,0.08);
  overflow: hidden;
}
.score-bar-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.8s ease-out;
}
.score-bar-fill.grade-s { background: linear-gradient(90deg, #8b5cf6, #a78bfa); }
.score-bar-fill.grade-a { background: linear-gradient(90deg, #f59e0b, #fbbf24); }
.score-bar-fill.grade-b { background: linear-gradient(90deg, #2563EB, #60A5FA); }
.score-bar-fill.grade-c { background: linear-gradient(90deg, #6B7280, #9CA3AF); }

.pick-insight {
  font-size: 11px;
  color: #6B7280;
  margin: 10px 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pick-metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 12px;
}
.pick-metric-box {
  background: rgba(0,0,0,0.04);
  border-radius: 10px;
  padding: 7px 4px;
  text-align: center;
}
.pick-metric-label {
  font-size: 9px;
  color: #6B7280;
  margin-bottom: 2px;
  font-weight: 600;
}
.pick-metric-value {
  font-size: 13px;
  font-weight: 700;
  color: #1F2937;
  font-variant-numeric: tabular-nums;
}

/* ═══════════════════════════════════════════════════════════════════
   3. 뱃지 - 고대비 색상
   ═══════════════════════════════════════════════════════════════════ */
.badge-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.smart-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 9px;
  border-radius: 100px;
  font-size: 11px;
  font-weight: 600;
  border: 1px solid;
  white-space: nowrap;
  cursor: default;
  transition: transform 0.15s;
}
.smart-badge:hover { transform: scale(1.05); }
.smart-badge.badge-sm { font-size: 10px; padding: 1px 7px; }

.badge-blue   { background: rgba(37,99,235,0.1);  color: #1D4ED8; border-color: rgba(37,99,235,0.3); }
.badge-green  { background: rgba(5,150,105,0.1);  color: #047857; border-color: rgba(5,150,105,0.3); }
.badge-red    { background: rgba(220,38,38,0.1);  color: #B91C1C; border-color: rgba(220,38,38,0.3); }
.badge-purple { background: rgba(124,58,237,0.1); color: #6D28D9; border-color: rgba(124,58,237,0.3); }
.badge-cyan   { background: rgba(8,145,178,0.1);  color: #0E7490; border-color: rgba(8,145,178,0.3); }
.badge-orange { background: rgba(234,88,12,0.1);  color: #C2410C; border-color: rgba(234,88,12,0.3); }
.badge-pink   { background: rgba(219,39,119,0.1); color: #BE185D; border-color: rgba(219,39,119,0.3); }

.badge-more {
  font-size: 9px;
  color: #9CA3AF;
  align-self: center;
  margin-left: 2px;
}

/* ═══════════════════════════════════════════════════════════════════
   4. Score Ring
   ═══════════════════════════════════════════════════════════════════ */
.score-ring {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.score-ring-svg {
  position: absolute;
  transform: rotate(-90deg);
}
.score-ring-progress {
  transition: stroke-dashoffset 0.7s ease-out;
}
.score-ring-label {
  display: flex;
  flex-direction: column;
  align-items: center;
  line-height: 1;
  z-index: 1;
}
.score-ring-grade {
  font-size: 11px;
  font-weight: 900;
  letter-spacing: 0.5px;
}
.score-ring-num {
  font-size: 9px;
  color: #6B7280;
  margin-top: 1px;
  font-variant-numeric: tabular-nums;
}

/* ═══════════════════════════════════════════════════════════════════
   5. 메인 테이블 - 고대비
   ═══════════════════════════════════════════════════════════════════ */
.smart-table-container {
  border-radius: 18px;
  border: 1px solid #E5E7EB;
  background: #FFFFFF;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}

/* 헤더 행 */
.smart-table-header {
  display: grid;
  grid-template-columns: 48px 52px 1.4fr 180px 78px 78px 78px 88px 88px 96px;
  align-items: center;
  padding: 0 16px;
  height: 40px;
  border-bottom: 1px solid #E5E7EB;
  background: #F9FAFB;
  font-size: 11px;
  font-weight: 700;
  color: #6B7280;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  user-select: none;
}
.smart-table-header .sortable {
  cursor: pointer;
  transition: color 0.15s;
}
.smart-table-header .sortable:hover { color: #111827; }

.col-rank   { text-align: center; }
.col-grade  { text-align: center; }
.col-stock  { min-width: 0; padding-right: 8px; }
.col-badges { display: flex; flex-wrap: wrap; gap: 4px; overflow: hidden; }
.col-metric { text-align: right; font-variant-numeric: tabular-nums; }
.col-metric-wide { text-align: right; font-variant-numeric: tabular-nums; }
.col-price  { text-align: right; font-variant-numeric: tabular-nums; }

/* 데이터 행 */
.smart-table-row {
  display: grid;
  grid-template-columns: 48px 52px 1.4fr 180px 78px 78px 78px 88px 88px 96px;
  align-items: center;
  padding: 0 16px;
  height: 52px;
  cursor: pointer;
  transition: background 0.15s;
  border-bottom: 1px solid #F3F4F6;
}
.smart-table-row:hover { background: #F9FAFB; }
.smart-table-row.row-top-tier {
  background: rgba(139,92,246,0.03);
}

.rank-num {
  font-size: 14px;
  font-weight: 700;
  color: #4B5563;
  font-variant-numeric: tabular-nums;
}
.rank-num.rank-top { font-weight: 900; }

/* 종목 정보 */
.stock-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.stock-name {
  font-weight: 700;
  font-size: 13px;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.stock-code {
  font-size: 10px;
  color: #9CA3AF;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}
.stock-market {
  font-size: 9px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #F3F4F6;
  color: #6B7280;
  flex-shrink: 0;
  font-weight: 500;
}
.stock-insight {
  font-size: 10.5px;
  color: #6B7280;
  margin: 2px 0 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 숫자 색상 - 고대비 */
.num-excellent { color: #059669; font-weight: 700; }
.num-good      { color: #059669; font-weight: 500; }
.num-bad       { color: #DC2626; font-weight: 600; }
.num-normal    { color: #1F2937; font-weight: 500; }
.num-dim       { color: #9CA3AF; font-weight: 500; }
.dim           { color: #6B7280; font-weight: 500; }

/* 소트 화살표 */
.sort-arrow {
  font-size: 8px;
  color: #D1D5DB;
  margin-left: 2px;
}
.sort-arrow.active { color: #7C3AED; }

/* ═══════════════════════════════════════════════════════════════════
   6. 확장 디테일 패널
   ═══════════════════════════════════════════════════════════════════ */
.detail-panel {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
  padding: 16px 20px;
  background: #F9FAFB;
  border-top: 1px solid #E5E7EB;
  border-bottom: 1px solid #E5E7EB;
}
.detail-title {
  font-size: 10px;
  font-weight: 700;
  color: #6B7280;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 0 0 8px;
}
.detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 0;
}
.detail-row span:first-child {
  font-size: 11px;
  color: #6B7280;
}
.detail-row span:last-child {
  font-size: 11px;
  font-weight: 600;
  color: #1F2937;
  font-variant-numeric: tabular-nums;
}

/* 확장 애니메이션 */
.slide-down-enter-active { transition: all 0.2s ease-out; }
.slide-down-leave-active  { transition: all 0.15s ease-in; }
.slide-down-enter-from,
.slide-down-leave-to {
  opacity: 0;
  max-height: 0;
  padding-top: 0;
  padding-bottom: 0;
}

/* ═══════════════════════════════════════════════════════════════════
   7. 빈 상태 / 푸터
   ═══════════════════════════════════════════════════════════════════ */
.empty-state {
  padding: 60px 0;
  text-align: center;
  color: #9CA3AF;
  font-size: 14px;
  font-weight: 500;
}
.smart-footer {
  text-align: center;
  font-size: 11px;
  color: #9CA3AF;
  padding: 16px 0;
}

/* ═══════════════════════════════════════════════════════════════════
   8. 반응형
   ═══════════════════════════════════════════════════════════════════ */
@media (max-width: 1024px) {
  .top-picks { grid-template-columns: 1fr; }
  .smart-table-header,
  .smart-table-row {
    grid-template-columns: 40px 44px 1fr 120px 64px 64px 64px 72px 72px 80px;
    font-size: 11px;
  }
  .detail-panel { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 768px) {
  .smart-header { flex-direction: column; align-items: stretch; }
  .search-input { width: 100%; }
  .smart-table-header,
  .smart-table-row {
    grid-template-columns: 36px 40px 1fr 60px 60px 80px;
  }
  .col-badges,
  .col-metric-wide { display: none; }
  .smart-table-header .col-badges,
  .smart-table-header .col-metric-wide { display: none; }
  .detail-panel { grid-template-columns: 1fr 1fr; gap: 12px; }
}
</style>
