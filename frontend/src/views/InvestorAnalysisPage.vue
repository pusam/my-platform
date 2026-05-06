<template>
  <div :class="['investor-analysis-page', { embedded: embedded }]">
    <div class="content-wrapper">
      <div class="page-header-unified" v-if="!embedded">
        <BackButton :dark="true" />
        <div class="header-title">
          <h1>투자자 분석</h1>
          <p class="subtitle">외국인·기관·연기금의 매매 동향, 연속 매수, 수급 급증을 한눈에</p>
        </div>
      </div>

      <!-- 메인 탭 -->
      <div class="main-tabs">
        <button v-for="tab in mainTabs" :key="tab.key"
                :class="['main-tab-btn', { active: activeTab === tab.key }]"
                @click="switchTab(tab.key)">
          {{ tab.icon }} {{ tab.label }}
        </button>
      </div>

      <!-- ========== 탭1: 매매 동향 ========== -->
      <div v-if="activeTab === 'trades'" class="tab-content">
        <LoadingSpinner v-if="tradesLoading" />
        <template v-else>
          <div class="sub-header">
            <div class="trade-type-selector">
              <button :class="['type-btn', { active: tradeType === 'BUY' }]" @click="changeTradeType('BUY')">
                📈 매수 TOP 50
              </button>
              <button :class="['type-btn', { active: tradeType === 'SELL' }]" @click="changeTradeType('SELL')">
                📉 매도 TOP 50
              </button>
            </div>
            <span class="data-timestamp" :class="tradeDataStatus">
              {{ tradeDataStatusIcon }} {{ tradeTimestamp }}
            </span>
          </div>
          <p v-if="tradesCollecting" class="collecting-status">🔄 데이터 수집 중...</p>

          <div class="investor-tabs">
            <button v-for="type in tradeInvestorTypes" :key="type.value"
                    :class="['tab-btn', { active: tradeInvestor === type.value }]"
                    @click="tradeInvestor = type.value">
              {{ type.icon }} {{ type.label }}
            </button>
          </div>

          <div v-if="currentTrades.length > 0" class="trades-table">
            <table>
              <thead>
                <tr>
                  <th>순위</th>
                  <th>종목명</th>
                  <th>종목코드</th>
                  <th>{{ tradeType === 'BUY' ? '순매수' : '순매도' }} (억원)</th>
                  <th>현재가</th>
                  <th>등락률</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(trade, index) in currentTrades" :key="`${trade.stockCode}-${index}`"
                    class="trade-row" @click="goToStock(trade.stockCode)">
                  <td class="rank">{{ trade.rankNum }}</td>
                  <td class="stock-name">{{ trade.stockName }}</td>
                  <td class="stock-code">{{ trade.stockCode }}</td>
                  <td class="amount-cell">
                    <div class="amount-bar-container">
                      <div class="amount-bar" :class="tradeType === 'BUY' ? 'bar-buy' : 'bar-sell'"
                           :style="{ width: getBarWidth(trade.netBuyAmount) + '%' }"></div>
                      <span class="amount-value" :class="{ positive: trade.netBuyAmount > 0, negative: trade.netBuyAmount < 0 }">
                        {{ formatNumber(Math.abs(trade.netBuyAmount)) }}
                      </span>
                    </div>
                  </td>
                  <td class="price">{{ formatNumber(trade.currentPrice) }}</td>
                  <td class="rate" :class="{ positive: trade.changeRate > 0, negative: trade.changeRate < 0 }">
                    {{ formatRate(trade.changeRate) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="no-data">
            <p v-if="tradesCollecting">🔄 데이터를 수집하고 있습니다...</p>
            <p v-else>💡 데이터가 없습니다.</p>
          </div>
        </template>
      </div>

      <!-- ========== 탭2: 연속 매수 ========== -->
      <div v-if="activeTab === 'consecutive'" class="tab-content">
        <LoadingSpinner v-if="consecLoading" />
        <template v-else>
          <div class="sub-header">
            <div class="filter-group">
              <div class="filter-item">
                <label>최소 연속 일수</label>
                <select v-model="consecMinDays" @change="fetchConsecutive">
                  <option :value="2">2일 이상</option>
                  <option :value="3">3일 이상</option>
                  <option :value="5">5일 이상</option>
                  <option :value="7">7일 이상</option>
                  <option :value="10">10일 이상</option>
                </select>
              </div>
              <div class="filter-item">
                <label>정렬</label>
                <select v-model="consecSortBy">
                  <option value="netBuy">누적 순매수순</option>
                  <option value="days">연속 일수순</option>
                  <option value="changeRate">등락률 낮은순</option>
                </select>
              </div>
            </div>
          </div>

          <div class="investor-tabs">
            <button v-for="type in consecInvestorTypes" :key="type.value"
                    :class="['tab-btn', { active: consecInvestor === type.value }]"
                    @click="consecInvestor = type.value">
              {{ type.icon }} {{ type.label }}
            </button>
          </div>

          <div v-if="currentConsecStocks.length > 0" class="stocks-grid">
            <div v-for="stock in currentConsecStocks" :key="stock.stockCode"
                 :class="['stock-card', { 'common-card': consecInvestor === 'COMMON' }]"
                 @click="goToStock(stock.stockCode)">
              <div class="stock-header">
                <div class="stock-info">
                  <span class="card-stock-name">{{ stock.stockName }}</span>
                  <span class="card-stock-code">{{ stock.stockCode }}</span>
                </div>
                <div class="consecutive-badge">{{ stock.consecutiveDays }}일 연속</div>
              </div>
              <div v-if="stock._foreign || stock._institution" class="common-investor-info">
                <div v-if="stock._foreign" class="investor-chip foreign">
                  🌍 외국인 {{ stock._foreign.consecutiveDays }}일
                  <span class="chip-amount">{{ formatAmount(stock._foreign.totalNetBuyAmount) }}</span>
                </div>
                <div v-if="stock._institution" class="investor-chip institution">
                  🏢 기관 {{ stock._institution.consecutiveDays }}일
                  <span class="chip-amount">{{ formatAmount(stock._institution.totalNetBuyAmount) }}</span>
                </div>
              </div>
              <div class="stock-details">
                <div class="detail-row">
                  <span class="label">{{ stock._foreign ? '합산 순매수' : '누적 순매수' }}</span>
                  <span class="value amount" :class="{ positive: stock.totalNetBuyAmount > 0 }">
                    {{ formatAmount(stock.totalNetBuyAmount) }}
                  </span>
                </div>
                <div class="detail-row">
                  <span class="label">일평균</span>
                  <span class="value">{{ formatAmount(stock.avgDailyAmount) }}</span>
                </div>
                <div class="detail-row">
                  <span class="label">기간</span>
                  <span class="value date">{{ formatDateRange(stock.startDate, stock.endDate) }}</span>
                </div>
                <div class="detail-row" v-if="stock.currentPrice">
                  <span class="label">현재가</span>
                  <span class="value">{{ formatNumber(stock.currentPrice) }}원</span>
                </div>
                <div class="detail-row" v-if="stock.changeRate">
                  <span class="label">등락률</span>
                  <span class="value rate" :class="getRateClass(stock.changeRate)">
                    {{ formatRate(stock.changeRate) }}
                  </span>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="no-data">
            <p>{{ consecMinDays }}일 이상 연속 매수 중인 종목이 없습니다.</p>
            <div v-if="consecDataStatus" class="data-status-box">
              <p class="status-message">{{ consecDataStatus.message }}</p>
              <div class="status-details">
                <span v-if="consecDataStatus.foreignTradeDays !== undefined">
                  📊 외국인 데이터: {{ consecDataStatus.foreignTradeDays }}일
                </span>
                <span v-if="consecDataStatus.institutionTradeDays !== undefined">
                  📊 기관 데이터: {{ consecDataStatus.institutionTradeDays }}일
                </span>
                <span v-if="consecDataStatus.latestTradeDate">
                  📅 최신 데이터: {{ consecDataStatus.latestTradeDate }}
                </span>
              </div>
            </div>
          </div>
        </template>
      </div>

      <!-- ===== 탭4: 공매도 ===== -->
      <div v-if="activeTab === 'shortSelling'" class="tab-content">
        <div class="section-header">
          <h2>📉 공매도 비율 상위</h2>
          <div class="section-controls">
            <button class="refresh-btn" :disabled="shortLoading" @click="fetchShortSelling">
              {{ shortLoading ? '로딩...' : '🔄 갱신' }}
            </button>
          </div>
        </div>
        <p class="hint">최근 거래일 기준 공매도 비율 상위 종목. 비율이 높을수록 하락 베팅이 많은 종목.</p>

        <LoadingSpinner v-if="shortLoading && shortStocks.length === 0" />

        <template v-else>
          <div v-if="shortStocks.length > 0" class="short-table-wrap">
            <table class="short-table">
              <thead>
                <tr>
                  <th class="rank-col">#</th>
                  <th>종목</th>
                  <th class="right">공매도 비율</th>
                  <th class="right">거래량</th>
                  <th class="right">금액</th>
                  <th class="right">기준일</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(s, i) in shortStocks" :key="s.stockCode + s.tradeDate"
                    @click="goStock(s.stockCode)" class="short-row">
                  <td class="rank-col">{{ i + 1 }}</td>
                  <td>
                    <div class="stock-cell">
                      <span class="name">{{ s.stockName || s.stockCode }}</span>
                      <span class="code">{{ s.stockCode }}</span>
                    </div>
                  </td>
                  <td class="right ratio-cell" :class="getShortRatioClass(s.shortSellingRatio)">
                    {{ formatShortRatio(s.shortSellingRatio) }}%
                  </td>
                  <td class="right">{{ formatShortVolume(s.shortSellingVolume) }}</td>
                  <td class="right">{{ formatShortAmount(s.shortSellingAmount) }}</td>
                  <td class="right date-cell">{{ formatShortDate(s.tradeDate) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="no-data">
            <p>공매도 데이터가 없습니다.</p>
            <p class="hint">매일 19시 자동 수집됩니다 (네이버 금융).</p>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, defineProps } from 'vue'
import { useRouter } from 'vue-router'
import { investorAPI, shortSellingAPI } from '../utils/api'
import { toast } from '../utils/toast'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import BackButton from '../components/BackButton.vue'

const props = defineProps({
  embedded: { type: Boolean, default: false }
})

const router = useRouter()

// ===== 메인 탭 =====
const activeTab = ref('trades')
const mainTabs = [
  { key: 'trades', label: '매매 동향', icon: '📊' },
  { key: 'consecutive', label: '연속 매수', icon: '🔥' },
  { key: 'shortSelling', label: '공매도', icon: '📉' }
]

// 탭별 데이터 로드 플래그
const tradesLoaded = ref(false)
const consecLoaded = ref(false)
const shortLoaded = ref(false)

const switchTab = (key) => {
  activeTab.value = key
  if (key === 'trades' && !tradesLoaded.value) fetchTrades()
  if (key === 'consecutive' && !consecLoaded.value) fetchConsecutive()
  if (key === 'shortSelling' && !shortLoaded.value) fetchShortSelling()
}

// ===== 탭1: 매매 동향 =====
const tradesLoading = ref(false)
const tradesCollecting = ref(false)
const tradeType = ref('BUY')
const tradeInvestor = ref('FOREIGN')
const allTrades = ref({})
const tradeTimestamp = ref('-')
const tradeDataStatus = ref('status-unknown')

const tradeInvestorTypes = [
  { value: 'FOREIGN', label: '외국인', icon: '🌍' },
  { value: 'INSTITUTION', label: '기관', icon: '🏢' },
  { value: 'PENSION', label: '연기금', icon: '💎' }
]

const currentTrades = computed(() => allTrades.value[tradeInvestor.value] || [])

const maxTradeAmount = computed(() => {
  const trades = currentTrades.value
  if (!trades.length) return 1
  return Math.max(...trades.map(t => Math.abs(t.netBuyAmount || 0)))
})

const getBarWidth = (amount) => {
  if (!amount || !maxTradeAmount.value) return 0
  return (Math.abs(amount) / maxTradeAmount.value) * 100
}

const tradeDataStatusIcon = computed(() => {
  if (tradeDataStatus.value === 'status-live') return '🔴'
  if (tradeDataStatus.value === 'status-confirmed') return '✅'
  return '📊'
})

const updateTradeStatus = () => {
  const now = new Date()
  const hours = now.getHours()
  const minutes = now.getMinutes()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  const hour = String(hours).padStart(2, '0')
  const min = String(minutes).padStart(2, '0')

  if (hours >= 15 && minutes >= 30) {
    tradeTimestamp.value = `${month}.${day} 장 마감 확정`
    tradeDataStatus.value = 'status-confirmed'
  } else if (hours >= 9 && hours < 16) {
    tradeTimestamp.value = `${month}.${day} ${hour}:${min} (잠정)`
    tradeDataStatus.value = 'status-live'
  } else {
    tradeTimestamp.value = `${month}.${day} ${hour}:${min}`
    tradeDataStatus.value = 'status-unknown'
  }
}

const changeTradeType = (type) => {
  tradeType.value = type
  fetchTrades()
}

const fetchTrades = async () => {
  tradesLoading.value = true
  try {
    const response = await investorAPI.getAllTopTrades(tradeType.value, 50)
    if (response.data.success) {
      allTrades.value = response.data.data
      updateTradeStatus()
      tradesLoaded.value = true
    }
  } catch (error) {
    console.error('투자자 매매 데이터 조회 오류:', error)
  } finally {
    tradesLoading.value = false
  }
}

const autoCollectTrades = async () => {
  await fetchTrades()
  if (Object.values(allTrades.value).every(arr => arr.length === 0)) {
    tradesCollecting.value = true
    try {
      await investorAPI.collect()
      await fetchTrades()
    } catch (error) {
      console.error('데이터 수집 오류:', error)
    } finally {
      tradesCollecting.value = false
    }
  }
}

// ===== 탭2: 연속 매수 =====
const consecLoading = ref(false)
const consecMinDays = ref(3)
const consecSortBy = ref('netBuy')
const consecInvestor = ref('FOREIGN')
const allConsecStocks = ref({})
const consecDataStatus = ref(null)

const consecInvestorTypes = [
  { value: 'FOREIGN', label: '외국인', icon: '🌍' },
  { value: 'INSTITUTION', label: '기관', icon: '🏢' },
  { value: 'PENSION', label: '연기금', icon: '💎' },
  { value: 'COMMON', label: '외국인+기관 공통', icon: '🤝' }
]

const commonConsecStocks = computed(() => {
  const foreignList = allConsecStocks.value.FOREIGN || []
  const institutionList = allConsecStocks.value.INSTITUTION || []
  if (!foreignList.length || !institutionList.length) return []

  const institutionMap = {}
  institutionList.forEach(s => { institutionMap[s.stockCode] = s })

  return foreignList
    .filter(f => institutionMap[f.stockCode])
    .map(f => {
      const inst = institutionMap[f.stockCode]
      const primary = (f.consecutiveDays || 0) >= (inst.consecutiveDays || 0) ? f : inst
      return {
        stockCode: f.stockCode,
        stockName: f.stockName,
        consecutiveDays: Math.min(f.consecutiveDays || 0, inst.consecutiveDays || 0),
        totalNetBuyAmount: (f.totalNetBuyAmount || 0) + (inst.totalNetBuyAmount || 0),
        avgDailyAmount: (f.avgDailyAmount || 0) + (inst.avgDailyAmount || 0),
        startDate: primary.startDate,
        endDate: primary.endDate,
        currentPrice: f.currentPrice || inst.currentPrice,
        changeRate: f.changeRate || inst.changeRate,
        _foreign: f,
        _institution: inst
      }
    })
})

const currentConsecStocks = computed(() => {
  const stocks = consecInvestor.value === 'COMMON'
    ? commonConsecStocks.value
    : (allConsecStocks.value[consecInvestor.value] || [])
  if (!stocks.length) return []

  return [...stocks].sort((a, b) => {
    switch (consecSortBy.value) {
      case 'days': return (b.consecutiveDays || 0) - (a.consecutiveDays || 0)
      case 'changeRate': return (a.changeRate || 0) - (b.changeRate || 0)
      default: return (b.totalNetBuyAmount || 0) - (a.totalNetBuyAmount || 0)
    }
  })
})

const fetchConsecutive = async () => {
  consecLoading.value = true
  try {
    const response = await investorAPI.getAllConsecutiveBuy(consecMinDays.value)
    if (response.data.success) {
      const data = response.data.data
      allConsecStocks.value = {
        FOREIGN: data.FOREIGN || [],
        INSTITUTION: data.INSTITUTION || [],
        PENSION: data.PENSION || []
      }
      consecDataStatus.value = data.dataStatus
      consecLoaded.value = true
    }
  } catch (error) {
    console.error('연속 매수 종목 조회 오류:', error)
  } finally {
    consecLoading.value = false
  }
}

// ===== 탭4: 공매도 =====
const shortLoading = ref(false)
const shortStocks = ref([])

const fetchShortSelling = async () => {
  shortLoading.value = true
  try {
    const res = await shortSellingAPI.getTop(30)
    if (res.data?.success) {
      shortStocks.value = Array.isArray(res.data.data) ? res.data.data : []
      shortLoaded.value = true
    } else {
      toast.error(res.data?.message || '공매도 데이터 조회 실패')
    }
  } catch (e) {
    console.error('공매도 조회 실패', e)
    toast.error('공매도 데이터 조회 실패')
  } finally {
    shortLoading.value = false
  }
}

const getShortRatioClass = (ratio) => {
  const r = Number(ratio)
  if (!Number.isFinite(r)) return ''
  if (r >= 10) return 'ratio-very-high'
  if (r >= 5) return 'ratio-high'
  if (r >= 2) return 'ratio-medium'
  return ''
}

const formatShortRatio = (v) => {
  const n = Number(v)
  if (!Number.isFinite(n)) return '-'
  return n.toFixed(2)
}

const formatShortVolume = (v) => {
  const n = Number(v)
  if (!Number.isFinite(n)) return '-'
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(0) + 'K'
  return String(Math.round(n))
}

const formatShortAmount = (v) => {
  const n = Number(v)
  if (!Number.isFinite(n)) return '-'
  // 단위: 원 → 억으로 표시
  const eok = n / 100_000_000
  if (eok >= 1) return eok.toFixed(1) + '억'
  const man = n / 10_000
  return man.toFixed(0) + '만'
}

const formatShortDate = (d) => {
  if (!d) return '-'
  const date = new Date(d)
  return `${date.getMonth() + 1}/${date.getDate()}`
}

// ===== 공통 유틸 =====
const goToStock = (code) => router.push(`/stock/${code}`)

const formatNumber = (value) => {
  if (!value) return '0'
  return Number(value).toLocaleString('ko-KR', { maximumFractionDigits: 2 })
}

const formatAmount = (value) => {
  if (!value) return '0억'
  const num = Number(value)
  if (Math.abs(num) >= 10000) return `${(num / 10000).toFixed(1)}조`
  return `${num.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}억`
}

const formatRate = (value) => {
  if (!value) return '0.00%'
  const sign = value > 0 ? '+' : ''
  return `${sign}${Number(value).toFixed(2)}%`
}

const getRateClass = (value) => {
  if (!value) return ''
  return Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : ''
}

const getAmountClass = (value) => {
  if (!value) return ''
  return Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : ''
}

const formatDateRange = (start, end) => {
  if (!start || !end) return '-'
  const fmt = (d) => { const dt = new Date(d); return `${dt.getMonth() + 1}/${dt.getDate()}` }
  return `${fmt(start)} ~ ${fmt(end)}`
}

// ===== Lifecycle =====
onMounted(() => {
  autoCollectTrades()
})
</script>

<style scoped>
.investor-analysis-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #0f0f1a 0%, #1a1a2e 50%, #16213e 100%);
  padding: 2rem;
}

.investor-analysis-page.embedded {
  min-height: auto;
  background: transparent;
  padding: 0;
}

.content-wrapper {
  max-width: 1400px;
  margin: 0 auto;
}

/* 메인 탭 */
.main-tabs {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-bottom: 1.5rem;
  background: var(--border-light);
  padding: 6px;
  border-radius: 14px;
  max-width: 600px;
  margin-left: auto;
  margin-right: auto;
}

.main-tab-btn {
  flex: 1;
  padding: 12px 20px;
  border: none;
  background: transparent;
  color: rgba(255,255,255,0.5);
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  border-radius: 10px;
  transition: all 0.2s;
}

.main-tab-btn:hover {
  color: rgba(255,255,255,0.7);
}

.main-tab-btn.active {
  background: rgba(102,126,234,0.25);
  color: #8b9cf7;
  border: 1px solid rgba(102,126,234,0.4);
}

/* 탭 컨텐츠 */
.tab-content {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 20px;
  padding: 24px;
}

/* 서브 헤더 */
.sub-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.trade-type-selector {
  display: flex;
  gap: 8px;
}

.type-btn {
  padding: 8px 20px;
  border: 1px solid rgba(102,126,234,0.3);
  background: transparent;
  color: rgba(255,255,255,0.6);
  border-radius: 10px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  transition: all 0.2s;
}

.type-btn.active {
  background: rgba(102,126,234,0.2);
  border-color: var(--primary-start);
  color: #8b9cf7;
}

.data-timestamp {
  font-size: 0.85rem;
  font-weight: 600;
  padding: 6px 12px;
  border-radius: 20px;
  white-space: nowrap;
}

.data-timestamp.status-live {
  background: rgba(239,68,68,0.15);
  color: #ef4444;
  border: 1px solid rgba(239,68,68,0.3);
}

.data-timestamp.status-confirmed {
  background: rgba(34,197,94,0.15);
  color: #22c55e;
  border: 1px solid rgba(34,197,94,0.3);
}

.data-timestamp.status-unknown {
  background: rgba(255,255,255,0.08);
  color: rgba(255,255,255,0.4);
  border: 1px solid rgba(255,255,255,0.1);
}

.collecting-status {
  color: var(--primary-start);
  font-weight: 600;
  text-align: center;
  animation: pulse 1.5s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

/* 투자자 탭 */
.investor-tabs {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-bottom: 16px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
  padding-bottom: 2px;
}

.tab-btn {
  padding: 10px 20px;
  background: none;
  border: none;
  color: rgba(255,255,255,0.4);
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  transition: all 0.2s;
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
}

.tab-btn.active {
  color: var(--primary-start);
  border-bottom-color: var(--primary-start);
}

/* 필터 */
.filter-group {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.filter-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-item label {
  font-weight: 600;
  color: rgba(255,255,255,0.5);
  font-size: 13px;
}

.filter-item select {
  padding: 6px 12px;
  border: 1px solid rgba(255,255,255,0.15);
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  background: var(--border-light);
  color: #fff;
}

/* ===== 테이블 (매매 동향) ===== */
.trades-table {
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
}

thead { background: rgba(255,255,255,0.04); }

th {
  padding: 12px;
  text-align: left;
  font-weight: 600;
  color: rgba(255,255,255,0.6);
  border-bottom: 1px solid rgba(255,255,255,0.1);
  font-size: 13px;
}

td {
  padding: 12px;
  border-bottom: 1px solid rgba(255,255,255,0.04);
  color: rgba(255,255,255,0.8);
  font-size: 13px;
}

.trade-row { cursor: pointer; transition: background 0.2s; }
.trade-row:hover { background: rgba(255,255,255,0.04); }

.rank { font-weight: 700; color: var(--primary-start); text-align: center; }
.stock-name { font-weight: 600; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.stock-code { color: rgba(255,255,255,0.4); font-family: monospace; }

.amount-cell { padding: 6px 12px !important; }

.amount-bar-container {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  height: 28px;
  border-radius: 4px;
  overflow: hidden;
  background: rgba(255,255,255,0.04);
}

.amount-bar {
  position: absolute;
  left: 0;
  top: 0;
  height: 100%;
  border-radius: 4px;
  transition: width 0.3s ease;
}

.amount-bar.bar-buy { background: linear-gradient(90deg, rgba(239,68,68,0.1) 0%, rgba(239,68,68,0.2) 100%); }
.amount-bar.bar-sell { background: linear-gradient(90deg, rgba(59,130,246,0.1) 0%, rgba(59,130,246,0.2) 100%); }

.amount-value {
  position: relative;
  z-index: 1;
  font-family: monospace;
  font-weight: 600;
  padding-right: 8px;
}

.price { text-align: right; font-weight: 600; font-family: monospace; }
.rate { text-align: right; font-weight: 600; font-family: monospace; }

.positive { color: #ef4444 !important; }
.positive::before { content: '▲ '; font-size: 0.75em; }
.negative { color: #3b82f6 !important; }
.negative::before { content: '▼ '; font-size: 0.75em; }

/* ===== 카드 그리드 (연속 매수 / 수급 급증) ===== */
.stocks-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.stock-card {
  background: rgba(255,255,255,0.04);
  border-radius: 16px;
  padding: 20px;
  border: 1px solid rgba(255,255,255,0.08);
  transition: all 0.3s;
  cursor: pointer;
  position: relative;
}

.stock-card:hover {
  border-color: rgba(102,126,234,0.4);
  box-shadow: 0 8px 24px rgba(0,0,0,0.3);
  transform: translateY(-3px);
}

.stock-card.common-card {
  border-color: rgba(159,122,234,0.3);
  background: rgba(159,122,234,0.05);
}

.stock-card.common-card:hover {
  border-color: rgba(159,122,234,0.5);
}

.stock-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(255,255,255,0.06);
}

.stock-info { display: flex; flex-direction: column; }
.card-stock-name { font-size: 15px; font-weight: 700; color: rgba(255,255,255,0.9); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 160px; }
.card-stock-code { font-size: 12px; color: rgba(255,255,255,0.35); font-family: monospace; }

.consecutive-badge {
  background: linear-gradient(135deg, var(--primary-start), #764ba2);
  color: white;
  padding: 4px 12px;
  border-radius: 20px;
  font-weight: 700;
  font-size: 12px;
  white-space: nowrap;
}

.common-investor-info { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 10px; }

.investor-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: 20px;
  font-size: 11px;
  font-weight: 600;
}

.investor-chip.foreign { background: rgba(59,130,246,0.1); color: #60a5fa; border: 1px solid rgba(59,130,246,0.2); }
.investor-chip.institution { background: rgba(245,158,11,0.1); color: #fbbf24; border: 1px solid rgba(245,158,11,0.2); }
.chip-amount { font-weight: 700; margin-left: 4px; }

.stock-details { display: flex; flex-direction: column; gap: 8px; }

.detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-row .label { color: rgba(255,255,255,0.4); font-size: 12px; }
.detail-row .value { font-weight: 600; color: rgba(255,255,255,0.85); font-size: 13px; }
.detail-row .value.amount { font-family: monospace; }
.detail-row .value.date { font-size: 12px; color: rgba(255,255,255,0.5); }

.detail-row.highlight {
  background: rgba(255,255,255,0.06);
  padding: 6px 8px;
  border-radius: 8px;
}

/* 데이터 없음 */
.no-data {
  text-align: center;
  padding: 48px 20px;
  color: rgba(255,255,255,0.35);
}

.no-data p { font-size: 15px; margin-bottom: 8px; }
.no-data .hint { font-size: 13px; color: rgba(255,255,255,0.25); }

.data-status-box {
  margin-top: 16px;
  padding: 16px;
  background: rgba(102,126,234,0.08);
  border-radius: 12px;
  border: 1px solid rgba(102,126,234,0.2);
}

.status-message { color: var(--primary-start); font-weight: 600; margin-bottom: 10px; }

.status-details {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  font-size: 13px;
  color: rgba(255,255,255,0.5);
}

.status-details span {
  background: var(--border-light);
  padding: 4px 12px;
  border-radius: 20px;
  border: 1px solid rgba(255,255,255,0.08);
}

@media (max-width: 768px) {
  .investor-analysis-page { padding: 1rem; }
  .tab-content { padding: 16px; }
  .main-tabs { flex-wrap: wrap; }
  .main-tab-btn { font-size: 13px; padding: 10px 12px; }
  .sub-header { flex-direction: column; }
  .investor-tabs { flex-wrap: wrap; gap: 4px; }
  .tab-btn { padding: 8px 12px; font-size: 12px; }
  .stocks-grid { grid-template-columns: 1fr; }
  table { font-size: 12px; }
  th, td { padding: 8px; }
  .trade-type-selector { flex-direction: column; }
}

/* ===== 공매도 탭 ===== */
.short-table-wrap { overflow-x: auto; }
.short-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.short-table thead th {
  padding: 10px 12px;
  text-align: left;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.4px;
  color: #888;
  background: #f8f9fa;
  border-bottom: 1px solid rgba(0,0,0,0.08);
}
.short-table th.right, .short-table td.right { text-align: right; }
.short-table .rank-col { width: 36px; text-align: center; color: #aaa; font-weight: 600; }
.short-row {
  cursor: pointer;
  transition: background 0.1s;
}
.short-row:hover { background: rgba(102,126,234,0.04); }
.short-row td {
  padding: 10px 12px;
  border-bottom: 1px solid rgba(0,0,0,0.04);
}
.short-table .stock-cell { display: flex; flex-direction: column; gap: 2px; }
.short-table .stock-cell .name { font-weight: 600; color: #333; font-size: 13px; }
.short-table .stock-cell .code { font-family: monospace; font-size: 10.5px; color: #999; }

.ratio-cell { font-weight: 800; font-family: monospace; }
.ratio-cell.ratio-very-high { color: #ef4444; }
.ratio-cell.ratio-high { color: #f59e0b; }
.ratio-cell.ratio-medium { color: #888; }

.date-cell { font-family: monospace; color: #999; font-size: 12px; }

@media (max-width: 600px) {
  .short-table thead th, .short-row td { padding: 7px 6px; font-size: 11.5px; }
  .short-table .stock-cell .name { font-size: 12px; }
  .short-table .stock-cell .code { font-size: 9.5px; }
}
</style>
