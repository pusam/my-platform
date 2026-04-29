<template>
  <div class="section-card">
    <div class="section-title-row">
      <h2><span class="section-icon">📐</span> 퀀트 분석</h2>
      <span class="badge-offline">DB 캐시 · AI 호출 없음</span>
    </div>

    <!-- universe 상태 + 수집 -->
    <div class="universe-bar">
      <div class="universe-info">
        <span class="universe-label">스크리너 universe:</span>
        <span class="universe-value" v-if="universe">
          <strong>{{ universe.readyCount }}</strong> 종목
          <span class="universe-sub">(≥{{ universe.minHistoryDays }}일 보유 / 전체 {{ universe.anyHistoryCount }})</span>
        </span>
        <span v-else class="universe-value subtle">로딩...</span>
      </div>

      <div class="collect-controls" v-if="isAdmin">
        <select v-model.number="collectTopN" :disabled="collecting" class="collect-select">
          <option :value="50">상위 50</option>
          <option :value="100">상위 100</option>
          <option :value="200">상위 200</option>
          <option :value="500">상위 500</option>
          <option :value="1000">상위 1000</option>
        </select>
        <button class="ghost-btn" :disabled="collecting" @click="startCollect">
          {{ collecting ? '수집 중…' : '🔄 일봉 수집' }}
        </button>
      </div>
    </div>

    <!-- 진행률 바 -->
    <div v-if="collecting || collectProgress.processed > 0" class="progress-row">
      <div class="progress-meta">
        <span>{{ collectProgress.processed }} / {{ collectProgress.total }}</span>
        <span>성공 {{ collectProgress.succeeded }} · 실패 {{ collectProgress.failed }}</span>
        <span v-if="collectProgress.message" class="progress-msg">{{ collectProgress.message }}</span>
      </div>
      <div class="progress-track">
        <div class="progress-fill" :style="{ width: (collectProgress.percent || 0) + '%' }"></div>
      </div>
    </div>

    <!-- 내부 탭 -->
    <div class="inner-tabs">
      <button :class="['tab-btn', { active: activeTab === 'screener' }]"
              @click="activeTab = 'screener'">스크리너</button>
      <button :class="['tab-btn', { active: activeTab === 'correlation' }]"
              @click="activeTab = 'correlation'">상관관계</button>
    </div>

    <!-- ============= 스크리너 ============= -->
    <div v-if="activeTab === 'screener'" class="screener-area">
      <!-- 프리셋 -->
      <div class="preset-row">
        <span class="preset-label">프리셋:</span>
        <button v-for="p in presets" :key="p.key"
                :class="['preset-chip', { active: activePreset === p.key }]"
                @click="applyPreset(p)">
          {{ p.label }}
        </button>
        <button class="preset-chip clear" @click="clearFilter">초기화</button>
      </div>

      <!-- 조건 토글 -->
      <div class="filter-grid">
        <label class="filter-row">
          <input type="checkbox" v-model="filter.goldenCross" />
          <span>골든크로스 (5일선↑20일선)</span>
        </label>
        <label class="filter-row">
          <input type="checkbox" v-model="filter.arrangedUp" />
          <span>정배열 (5>20>60)</span>
        </label>
        <label class="filter-row">
          <input type="checkbox" v-model="filter.aboveMa20" />
          <span>20일선 위</span>
        </label>
        <label class="filter-row">
          <input type="checkbox" v-model="filter.belowMa20" />
          <span>20일선 아래</span>
        </label>
        <label class="filter-row">
          <input type="checkbox" v-model="filter.bollingerLowerTouch" />
          <span>볼린저 하단 터치</span>
        </label>
        <label class="filter-row">
          <input type="checkbox" v-model="filter.bollingerSqueeze" />
          <span>볼린저 스퀴즈</span>
        </label>

        <div class="filter-row range">
          <span>RSI &lt;</span>
          <input type="number" min="0" max="100" v-model.number="filter.rsiBelow" placeholder="예: 30" />
        </div>
        <div class="filter-row range">
          <span>RSI &gt;</span>
          <input type="number" min="0" max="100" v-model.number="filter.rsiAbove" placeholder="예: 70" />
        </div>
        <div class="filter-row range">
          <span>거래량 ≥ 평균 ×</span>
          <input type="number" min="1" step="0.5" v-model.number="filter.volumeRatioMin" placeholder="예: 2" />
        </div>
        <div class="filter-row range">
          <span>등락률 ≥ %</span>
          <input type="number" step="0.5" v-model.number="filter.changeRateMin" placeholder="예: 0" />
        </div>
        <div class="filter-row range">
          <span>등락률 ≤ %</span>
          <input type="number" step="0.5" v-model.number="filter.changeRateMax" placeholder="예: 5" />
        </div>
      </div>

      <div class="action-row">
        <button class="primary-btn" :disabled="loading" @click="runScreen">
          {{ loading ? '계산 중…' : '스크리닝 실행' }}
        </button>
        <span v-if="lastRun" class="meta">
          {{ lastRun.matchedCount }} / {{ lastRun.universeSize }} 종목 매칭
        </span>
      </div>

      <!-- 결과 -->
      <div v-if="results.length" class="result-table-wrap">
        <table class="result-table">
          <thead>
            <tr>
              <th>#</th>
              <th>종목</th>
              <th>종가</th>
              <th>등락률</th>
              <th>RSI</th>
              <th>거래량×</th>
              <th>매칭 조건</th>
              <th>점수</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in results" :key="r.stockCode" @click="goStock(r.stockCode)">
              <td>{{ i + 1 }}</td>
              <td class="stock-cell">
                <span class="stock-name">{{ r.stockName || r.stockCode }}</span>
                <span class="stock-code">{{ r.stockCode }}</span>
              </td>
              <td>{{ formatNum(r.closePrice) }}</td>
              <td :class="numColor(r.changeRate)">
                {{ r.changeRate != null ? (Number(r.changeRate) >= 0 ? '+' : '') + Number(r.changeRate).toFixed(2) + '%' : '-' }}
              </td>
              <td>{{ r.rsi14 != null ? Number(r.rsi14).toFixed(1) : '-' }}</td>
              <td>{{ r.volumeRatio != null ? Number(r.volumeRatio).toFixed(2) + 'x' : '-' }}</td>
              <td>
                <span v-for="t in r.matchedTags" :key="t" class="tag-chip">{{ t }}</span>
              </td>
              <td class="score-cell">{{ r.matchScore }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else-if="lastRun" class="empty-msg">
        조건에 맞는 종목이 없습니다. 조건을 완화해 보세요.
      </div>
      <div v-else class="empty-msg subtle">
        프리셋을 선택하거나 조건을 켜고 [스크리닝 실행]을 누르세요.
      </div>
    </div>

    <!-- ============= 상관관계 ============= -->
    <div v-if="activeTab === 'correlation'" class="corr-area">
      <div class="corr-input-row">
        <label>종목 코드 (쉼표로 구분, 최대 30개)</label>
        <input type="text" v-model="corrCodesInput" placeholder="005930,000660,035420 …" />

        <!-- 입력 코드 미리보기 칩 -->
        <div v-if="parsedCodes.length" class="code-preview-row">
          <span v-for="code in parsedCodes" :key="'p-' + code"
                :class="['code-chip', { unknown: !nameMap[code] || nameMap[code] === code }]">
            <span class="code-chip-code">{{ code }}</span>
            <span class="code-chip-name">{{ nameMap[code] && nameMap[code] !== code ? nameMap[code] : '미확인' }}</span>
          </span>
        </div>

        <div class="corr-controls">
          <label>기간:</label>
          <select v-model.number="corrDays">
            <option :value="30">30일</option>
            <option :value="60">60일</option>
            <option :value="120">120일</option>
            <option :value="250">1년</option>
          </select>
          <button class="ghost-btn" @click="loadDefaultCodes" :disabled="defaultLoading">
            {{ defaultLoading ? '로딩…' : 'AI 추천 TOP 채우기' }}
          </button>
          <button class="primary-btn" :disabled="corrLoading" @click="runCorrelation">
            {{ corrLoading ? '계산 중…' : '상관관계 계산' }}
          </button>
        </div>
      </div>

      <div v-if="corrWarnings.length" class="warn-row">
        <span v-for="(w, i) in corrWarnings" :key="i" class="warn-chip">{{ w }}</span>
      </div>

      <div v-if="corrMatrix.length" class="heatmap-wrap">
        <div class="heatmap-meta">{{ corrDaysUsed }}일 영업일 데이터 기반 (수익률 기준 피어슨)</div>
        <div class="heatmap-table-wrap">
          <table class="heatmap-table">
            <thead>
              <tr>
                <th></th>
                <th v-for="s in corrStocks" :key="'h-' + s.stockCode" :title="(s.stockName || s.stockCode) + ' (' + s.stockCode + ')'">
                  <div class="hm-head-name">{{ s.stockName ? s.stockName.slice(0, 5) : s.stockCode }}</div>
                  <div class="hm-head-code">{{ s.stockCode }}</div>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(s, i) in corrStocks" :key="'r-' + s.stockCode">
                <th :title="(s.stockName || s.stockCode) + ' (' + s.stockCode + ')'">
                  <div class="hm-side-name">{{ s.stockName ? s.stockName.slice(0, 7) : s.stockCode }}</div>
                  <div class="hm-side-code">{{ s.stockCode }}</div>
                </th>
                <td v-for="(val, j) in corrMatrix[i]" :key="'c-' + i + '-' + j"
                    :style="cellStyle(val, i === j)"
                    :title="`${corrStocks[i].stockName} × ${corrStocks[j].stockName}: ${val.toFixed(3)}`">
                  {{ val.toFixed(2) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="legend-row">
          <span class="legend-item"><span class="legend-swatch" style="background:#1e88e5"></span>-1 (역상관)</span>
          <span class="legend-item"><span class="legend-swatch" style="background:#888"></span>0</span>
          <span class="legend-item"><span class="legend-swatch" style="background:#e53935"></span>+1 (동조)</span>
        </div>
        <p class="hint">* 0.7↑ 동조 강함 → 분산 효과 적음. 0.3↓ 다양화 양호.</p>
      </div>
      <div v-else-if="corrLoaded" class="empty-msg">
        상관관계를 계산할 수 없었습니다. 종목 데이터가 부족합니다.
      </div>
      <div v-else class="empty-msg subtle">
        종목 코드를 입력하고 [상관관계 계산]을 누르세요.
      </div>
    </div>
  </div>
</template>

<script>
import { quantTaAPI, recommendationAPI } from '../../utils/api'

const PRESETS = [
  { key: 'oversold', label: '🟢 과매도 반등 후보',
    filter: { rsiBelow: 30, volumeRatioMin: 1.5 } },
  { key: 'breakout', label: '🚀 거래량 동반 골든크로스',
    filter: { goldenCross: true, volumeRatioMin: 2 } },
  { key: 'trend', label: '📈 정배열 (안정 상승)',
    filter: { arrangedUp: true, aboveMa20: true } },
  { key: 'overheat', label: '🔥 과열 (이탈 위험)',
    filter: { rsiAbove: 70 } },
  { key: 'bbLower', label: '⬇️ 볼린저 하단 터치',
    filter: { bollingerLowerTouch: true } },
  { key: 'squeeze', label: '🤏 변동성 압축 (스퀴즈)',
    filter: { bollingerSqueeze: true } }
]

function emptyFilter() {
  return {
    rsiBelow: null, rsiAbove: null,
    goldenCross: false, arrangedUp: false,
    aboveMa20: false, belowMa20: false,
    volumeRatioMin: null,
    bollingerLowerTouch: false, bollingerSqueeze: false,
    changeRateMin: null, changeRateMax: null
  }
}

export default {
  name: 'SectionQuantTa',
  data() {
    return {
      activeTab: 'screener',
      // 스크리너
      presets: PRESETS,
      activePreset: null,
      filter: emptyFilter(),
      results: [],
      lastRun: null,
      loading: false,
      // 상관관계
      corrCodesInput: '',
      corrDays: 60,
      corrStocks: [],
      corrMatrix: [],
      corrWarnings: [],
      corrDaysUsed: 0,
      corrLoaded: false,
      corrLoading: false,
      defaultLoading: false,
      // universe + 일괄 수집
      universe: null,
      collectTopN: 200,
      collecting: false,
      collectProgress: { processed: 0, total: 0, succeeded: 0, failed: 0, percent: 0, message: null },
      _progressTimer: null,
      // 종목명 해석 캐시
      nameMap: {},
      _resolveDebounce: null
    }
  },
  computed: {
    isAdmin() {
      return localStorage.getItem('role') === 'ADMIN'
    },
    parsedCodes() {
      return this.corrCodesInput
        .split(/[,\s]+/)
        .map(s => s.trim())
        .filter(s => /^\d{4,}$/.test(s))
        .slice(0, 30)
    }
  },
  watch: {
    parsedCodes(newCodes) {
      // 디바운스 — 입력 중에 과한 호출 방지
      if (this._resolveDebounce) clearTimeout(this._resolveDebounce)
      const missing = newCodes.filter(c => !(c in this.nameMap))
      if (missing.length === 0) return
      this._resolveDebounce = setTimeout(() => this.resolveNames(missing), 400)
    }
  },
  mounted() {
    this.loadUniverseStatus()
    // 페이지 진입 시 진행 중인 수집 작업이 있으면 폴링 재개
    this.checkProgressOnMount()
  },
  beforeUnmount() {
    if (this._progressTimer) clearInterval(this._progressTimer)
  },
  methods: {
    // ===== 종목명 해석 =====
    async resolveNames(codes) {
      if (!codes || codes.length === 0) return
      try {
        const res = await quantTaAPI.resolveNames(codes)
        if (res.data?.success && res.data.data) {
          this.nameMap = { ...this.nameMap, ...res.data.data }
        }
      } catch (e) {
        console.warn('[QuantTa] 종목명 해석 실패', e)
      }
    },

    // ===== Universe + 일괄 수집 =====
    async loadUniverseStatus() {
      try {
        const res = await quantTaAPI.universeStatus()
        if (res.data?.success) this.universe = res.data.data
      } catch (e) {
        console.warn('[QuantTa] universe 조회 실패', e)
      }
    },
    async checkProgressOnMount() {
      try {
        const res = await quantTaAPI.collectHistoryProgress()
        const p = res.data?.data
        if (p && p.running) {
          this.collecting = true
          this.collectProgress = { ...p }
          this.startProgressPolling()
        }
      } catch { /* ignore */ }
    },
    async startCollect() {
      if (this.collecting) return
      try {
        const res = await quantTaAPI.collectHistory(this.collectTopN)
        if (!res.data?.success) {
          alert(res.data?.data?.message || '수집 시작 실패')
          return
        }
        this.collecting = true
        this.collectProgress = {
          processed: 0, total: res.data.data?.total || 0,
          succeeded: 0, failed: 0, percent: 0, message: null
        }
        this.startProgressPolling()
      } catch (e) {
        console.error('[QuantTa] 수집 시작 실패', e)
        alert('수집 시작 실패: ' + (e.message || 'unknown'))
      }
    },
    startProgressPolling() {
      if (this._progressTimer) clearInterval(this._progressTimer)
      this._progressTimer = setInterval(async () => {
        try {
          const res = await quantTaAPI.collectHistoryProgress()
          const p = res.data?.data
          if (!p) return
          this.collectProgress = { ...p }
          if (!p.running) {
            this.collecting = false
            clearInterval(this._progressTimer)
            this._progressTimer = null
            // 완료 시 universe 재로딩
            this.loadUniverseStatus()
          }
        } catch { /* ignore */ }
      }, 2000)
    },
    // ===== 스크리너 =====
    applyPreset(p) {
      this.activePreset = p.key
      this.filter = { ...emptyFilter(), ...p.filter }
      this.runScreen()
    },
    clearFilter() {
      this.activePreset = null
      this.filter = emptyFilter()
      this.results = []
      this.lastRun = null
    },
    buildPayload() {
      // 빈 값 정리 (0 vs null 구분)
      const f = {}
      for (const [k, v] of Object.entries(this.filter)) {
        if (v === null || v === '' || v === false) continue
        f[k] = v
      }
      return f
    },
    async runScreen() {
      const payload = this.buildPayload()
      if (Object.keys(payload).length === 0) {
        this.results = []
        this.lastRun = null
        return
      }
      this.loading = true
      try {
        const res = await quantTaAPI.screen(payload, 50)
        if (res.data?.success) {
          const data = res.data.data || {}
          this.results = data.results || []
          this.lastRun = { matchedCount: data.matchedCount, universeSize: data.universeSize }
        } else {
          this.results = []
          this.lastRun = null
        }
      } catch (e) {
        console.error('[QuantTa] screen 실패', e)
        this.results = []
      } finally {
        this.loading = false
      }
    },
    formatNum(n) {
      if (n == null) return '-'
      return Number(n).toLocaleString('ko-KR', { maximumFractionDigits: 0 })
    },
    numColor(v) {
      if (v == null) return ''
      const n = Number(v)
      return n > 0 ? 'positive' : n < 0 ? 'negative' : ''
    },
    goStock(code) {
      if (code) this.$router.push(`/stock/${code}`)
    },

    // ===== 상관관계 =====
    async loadDefaultCodes() {
      this.defaultLoading = true
      try {
        const res = await recommendationAPI.getTop5()
        const list = res.data?.data || res.data?.recommendations || []
        const codes = list.map(r => r.stockCode).filter(Boolean)
        if (codes.length) this.corrCodesInput = codes.join(',')
      } catch (e) {
        console.warn('[QuantTa] AI 추천 로딩 실패, 기본 종목 사용', e)
        this.corrCodesInput = '005930,000660,035420,005380,051910'
      } finally {
        this.defaultLoading = false
      }
    },
    parseCodes() {
      return this.corrCodesInput.split(/[,\s]+/).map(s => s.trim()).filter(Boolean)
    },
    async runCorrelation() {
      const codes = this.parseCodes()
      if (codes.length < 2) {
        this.corrWarnings = ['최소 2개 종목이 필요합니다']
        return
      }
      this.corrLoading = true
      this.corrLoaded = false
      try {
        const res = await quantTaAPI.correlation(codes, this.corrDays)
        if (res.data?.success) {
          const d = res.data.data || {}
          this.corrStocks = d.stocks || []
          this.corrMatrix = d.matrix || []
          this.corrWarnings = d.warnings || []
          this.corrDaysUsed = d.daysUsed || 0
        } else {
          this.corrStocks = []
          this.corrMatrix = []
          this.corrWarnings = ['계산 실패']
        }
        this.corrLoaded = true
      } catch (e) {
        console.error('[QuantTa] correlation 실패', e)
        this.corrWarnings = ['요청 실패: ' + (e.message || 'unknown')]
      } finally {
        this.corrLoading = false
      }
    },
    cellStyle(val, isDiag) {
      if (isDiag) return { background: 'rgba(255,255,255,0.12)', color: '#fff', fontWeight: 700 }
      // -1 → 파랑, 0 → 회색, +1 → 빨강 (HSL 보간)
      const v = Math.max(-1, Math.min(1, val))
      let hue, sat, light
      if (v >= 0) { hue = 0; sat = Math.round(70 * v); light = 50 - Math.round(15 * v) }
      else        { hue = 210; sat = Math.round(70 * -v); light = 50 - Math.round(15 * -v) }
      return {
        background: `hsl(${hue}, ${sat}%, ${light}%)`,
        color: Math.abs(v) > 0.4 ? '#fff' : 'rgba(255,255,255,0.85)'
      }
    }
  }
}
</script>

<style scoped>
.section-card {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 20px;
  padding: 20px;
  color: #fff;
}
.section-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.section-title-row h2 {
  font-size: 18px;
  margin: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.badge-offline {
  font-size: 11px;
  padding: 4px 10px;
  border-radius: 10px;
  background: rgba(76, 175, 80, 0.18);
  color: #81c784;
  border: 1px solid rgba(76, 175, 80, 0.35);
}
/* universe 상태 + 수집 */
.universe-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  background: rgba(0,0,0,0.18);
  border: 1px solid rgba(255,255,255,0.07);
  border-radius: 10px;
  padding: 10px 14px;
  margin-bottom: 14px;
}
.universe-info { font-size: 13px; color: rgba(255,255,255,0.85); }
.universe-label { color: rgba(255,255,255,0.55); margin-right: 8px; }
.universe-value strong { color: #c084fc; font-size: 16px; margin-right: 4px; }
.universe-sub { color: rgba(255,255,255,0.45); font-size: 11px; margin-left: 4px; }
.universe-value.subtle { color: rgba(255,255,255,0.4); }
.collect-controls { display: flex; gap: 8px; align-items: center; }
.collect-select {
  padding: 5px 10px;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.15);
  border-radius: 8px;
  color: #fff;
  font-size: 12px;
}

.progress-row { margin-bottom: 14px; }
.progress-meta {
  display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px;
  font-size: 11.5px; color: rgba(255,255,255,0.65); margin-bottom: 4px;
}
.progress-msg { color: #81c784; }
.progress-track {
  height: 6px;
  background: rgba(255,255,255,0.08);
  border-radius: 3px;
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #4f46e5, #7c3aed);
  transition: width 0.4s;
}

.inner-tabs { display: flex; gap: 8px; margin-bottom: 14px; }
.tab-btn {
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.12);
  color: rgba(255,255,255,0.7);
  padding: 7px 16px;
  border-radius: 14px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}
.tab-btn:hover { background: rgba(255,255,255,0.1); color: #fff; }
.tab-btn.active {
  background: linear-gradient(135deg, #4f46e5, #7c3aed);
  color: #fff;
  border-color: transparent;
}

/* ===== 스크리너 ===== */
.preset-row {
  display: flex; flex-wrap: wrap; gap: 8px; align-items: center;
  margin-bottom: 12px;
}
.preset-label { font-size: 12px; color: rgba(255,255,255,0.55); margin-right: 4px; }
.preset-chip {
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.12);
  color: rgba(255,255,255,0.85);
  padding: 6px 12px;
  border-radius: 12px;
  font-size: 12px;
  cursor: pointer;
}
.preset-chip:hover { background: rgba(255,255,255,0.12); }
.preset-chip.active {
  background: rgba(124,58,237,0.3);
  border-color: rgba(124,58,237,0.6);
  color: #fff;
}
.preset-chip.clear { color: rgba(255,255,255,0.5); }

.filter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 8px 12px;
  background: rgba(0,0,0,0.2);
  padding: 12px;
  border-radius: 12px;
  margin-bottom: 12px;
}
.filter-row {
  display: flex; align-items: center; gap: 8px;
  font-size: 13px; color: rgba(255,255,255,0.85);
  cursor: pointer;
}
.filter-row.range { gap: 6px; }
.filter-row.range input {
  width: 80px; padding: 4px 8px;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.15);
  border-radius: 6px;
  color: #fff;
  font-size: 12px;
}
.filter-row input[type="checkbox"] { accent-color: #7c3aed; }

.action-row {
  display: flex; align-items: center; gap: 12px; margin-bottom: 14px;
}
.primary-btn {
  background: linear-gradient(135deg, #4f46e5, #7c3aed);
  border: none; color: #fff;
  padding: 8px 18px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.primary-btn:disabled { opacity: 0.5; cursor: wait; }
.ghost-btn {
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.15);
  color: rgba(255,255,255,0.85);
  padding: 7px 14px;
  border-radius: 10px;
  font-size: 12px;
  cursor: pointer;
}
.ghost-btn:hover { background: rgba(255,255,255,0.12); }
.meta { font-size: 12px; color: rgba(255,255,255,0.6); }

.result-table-wrap { overflow-x: auto; }
.result-table {
  width: 100%; border-collapse: collapse; font-size: 12.5px;
}
.result-table th, .result-table td {
  padding: 8px 10px; text-align: left;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}
.result-table th {
  font-weight: 600; color: rgba(255,255,255,0.55);
  font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px;
}
.result-table tbody tr { cursor: pointer; transition: background 0.1s; }
.result-table tbody tr:hover { background: rgba(255,255,255,0.04); }
.stock-cell { display: flex; flex-direction: column; gap: 2px; }
.stock-name { font-weight: 600; }
.stock-code { font-size: 10.5px; color: rgba(255,255,255,0.4); }
.score-cell { font-weight: 700; color: #c084fc; }
.tag-chip {
  display: inline-block;
  background: rgba(124,58,237,0.2);
  color: #c084fc;
  padding: 2px 7px;
  margin: 1px 3px 1px 0;
  border-radius: 8px;
  font-size: 10.5px;
}
.positive { color: #ef4444; }
.negative { color: #3b82f6; }

.empty-msg {
  text-align: center; padding: 30px;
  color: rgba(255,255,255,0.6); font-size: 13px;
}
.empty-msg.subtle { color: rgba(255,255,255,0.35); }

/* ===== 상관관계 ===== */
.corr-input-row {
  display: flex; flex-direction: column; gap: 8px; margin-bottom: 14px;
}
.corr-input-row label { font-size: 12px; color: rgba(255,255,255,0.65); }
.corr-input-row > input {
  padding: 8px 12px;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.15);
  border-radius: 8px;
  color: #fff; font-size: 13px;
}
.corr-controls {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
}
.corr-controls label { font-size: 12px; }
.corr-controls select {
  padding: 6px 10px;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.15);
  border-radius: 8px;
  color: #fff;
}

/* 입력 코드 미리보기 칩 */
.code-preview-row {
  display: flex; flex-wrap: wrap; gap: 6px;
  padding: 4px 0;
}
.code-chip {
  display: inline-flex; align-items: center; gap: 6px;
  background: rgba(124,58,237,0.18);
  border: 1px solid rgba(124,58,237,0.35);
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 11.5px;
  color: rgba(255,255,255,0.9);
}
.code-chip.unknown {
  background: rgba(255,255,255,0.04);
  border-color: rgba(255,193,7,0.4);
  color: rgba(255,193,7,0.85);
}
.code-chip-code { font-family: monospace; font-weight: 600; opacity: 0.7; }
.code-chip-name { font-weight: 600; }

/* 매트릭스 헤더 — 이름 + 코드 2줄 */
.hm-head-name, .hm-side-name { font-size: 11px; font-weight: 600; }
.hm-head-code, .hm-side-code {
  font-size: 9.5px; font-family: monospace;
  color: rgba(255,255,255,0.4); margin-top: 1px;
}

.warn-row { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
.warn-chip {
  background: rgba(255,193,7,0.18);
  border: 1px solid rgba(255,193,7,0.35);
  color: #ffd54f;
  padding: 3px 8px;
  border-radius: 8px;
  font-size: 11px;
}

.heatmap-meta { font-size: 12px; color: rgba(255,255,255,0.55); margin-bottom: 8px; }
.heatmap-table-wrap { overflow-x: auto; }
.heatmap-table {
  border-collapse: collapse; font-size: 12px; min-width: 100%;
}
.heatmap-table th, .heatmap-table td {
  padding: 6px 8px;
  text-align: center;
  border: 1px solid rgba(255,255,255,0.08);
  white-space: nowrap;
}
.heatmap-table thead th {
  font-size: 11px;
  color: rgba(255,255,255,0.7);
  background: rgba(255,255,255,0.04);
}
.heatmap-table tbody th {
  text-align: right;
  background: rgba(255,255,255,0.04);
  font-weight: 600;
  color: rgba(255,255,255,0.75);
}
.legend-row {
  display: flex; gap: 16px; margin-top: 10px;
  font-size: 11px; color: rgba(255,255,255,0.7);
}
.legend-item { display: flex; align-items: center; gap: 6px; }
.legend-swatch {
  width: 14px; height: 14px; border-radius: 3px;
  display: inline-block;
}
.hint {
  font-size: 11px; color: rgba(255,255,255,0.5);
  margin-top: 6px;
}
</style>
