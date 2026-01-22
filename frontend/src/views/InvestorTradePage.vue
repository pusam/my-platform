<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>투자자별 상위 종목</h1>
        <div class="header-actions">
          <button @click="collectData" class="btn btn-collect" :disabled="collecting">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
              <polyline points="7 10 12 15 17 10"/>
              <line x1="12" y1="15" x2="12" y2="3"/>
            </svg>
            {{ collecting ? '수집 중...' : '데이터 수집' }}
          </button>
          <button @click="refreshData" class="btn btn-refresh" :disabled="loading">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" :class="{ spinning: loading }">
              <path d="M21 12a9 9 0 11-9-9"/>
              <polyline points="21 3 21 9 15 9"/>
            </svg>
            새로고침
          </button>
          <button @click="goBack" class="btn btn-back">돌아가기</button>
        </div>
      </header>

      <!-- 설명 -->
      <div class="info-banner">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10"/>
          <line x1="12" y1="16" x2="12" y2="12"/>
          <line x1="12" y1="8" x2="12.01" y2="8"/>
        </svg>
        <span>외국인, 기관, 연기금 등 투자자별 매수/매도 상위 20종목을 일자별로 확인하세요.</span>
      </div>

      <!-- 날짜 선택 -->
      <div class="date-selector">
        <button @click="changeDate(-1)" class="date-nav-btn">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="15 18 9 12 15 6"/>
          </svg>
        </button>
        <input type="date" v-model="selectedDate" @change="loadData" class="date-input" />
        <button @click="changeDate(1)" class="date-nav-btn" :disabled="isToday">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="9 18 15 12 9 6"/>
          </svg>
        </button>
        <button @click="goToToday" class="btn btn-today" :disabled="isToday">오늘</button>
      </div>

      <!-- 시장 선택 탭 -->
      <div class="market-tabs">
        <button
          v-for="tab in marketTabs"
          :key="tab.value"
          :class="['market-tab', { active: selectedMarket === tab.value }]"
          @click="selectMarket(tab.value)"
        >
          <span class="tab-icon">{{ tab.icon }}</span>
          <span class="tab-label">{{ tab.label }}</span>
        </button>
      </div>

      <!-- 투자자 유형 탭 -->
      <div class="investor-tabs">
        <button
          v-for="tab in investorTabs"
          :key="tab.value"
          :class="['investor-tab', { active: selectedInvestor === tab.value }]"
          @click="selectInvestor(tab.value)"
        >
          <span class="investor-tab-icon" :class="tab.value.toLowerCase()">{{ tab.icon }}</span>
          <span>{{ tab.label }}</span>
        </button>
      </div>

      <!-- 로딩 상태 -->
      <div v-if="loading" class="loading-container">
        <div class="loading-spinner"></div>
        <p>데이터를 불러오는 중...</p>
      </div>

      <!-- 데이터 없음 -->
      <div v-else-if="!hasData" class="no-data-container">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
          <polyline points="14,2 14,8 20,8"/>
          <line x1="9" y1="15" x2="15" y2="15"/>
        </svg>
        <h3>데이터가 없습니다</h3>
        <p>{{ selectedDate }} 날짜의 {{ getInvestorLabel(selectedInvestor) }} 거래 데이터가 없습니다.</p>
        <button @click="collectData" class="btn btn-collect-large">
          데이터 수집하기
        </button>
      </div>

      <!-- 데이터 표시 -->
      <div v-else class="trade-content">
        <!-- 매수/매도 섹션 -->
        <div class="trade-sections">
          <!-- 매수 상위 종목 -->
          <div class="trade-section buy-section">
            <div class="section-header">
              <h3>
                <span class="section-icon buy">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="23 6 13.5 15.5 8.5 10.5 1 18"/>
                    <polyline points="17 6 23 6 23 12"/>
                  </svg>
                </span>
                매수 상위 {{ buyTrades.length }}종목
              </h3>
            </div>
            <div class="stock-table">
              <div class="table-header">
                <span class="col-rank">순위</span>
                <span class="col-name">종목명</span>
                <span class="col-amount">순매수(억)</span>
                <span class="col-price">현재가</span>
                <span class="col-change">등락률</span>
              </div>
              <div class="table-body">
                <div v-for="trade in buyTrades" :key="trade.id" class="stock-row">
                  <span class="col-rank">{{ trade.rankNum }}</span>
                  <span class="col-name">
                    <span class="stock-name">{{ trade.stockName }}</span>
                    <span class="stock-code">{{ trade.stockCode }}</span>
                  </span>
                  <span class="col-amount buy-amount">+{{ formatAmount(trade.netBuyAmount) }}</span>
                  <span class="col-price">{{ formatPrice(trade.currentPrice) }}</span>
                  <span class="col-change" :class="getChangeClass(trade.changeRate)">
                    {{ formatChangeRate(trade.changeRate) }}
                  </span>
                </div>
              </div>
            </div>
          </div>

          <!-- 매도 상위 종목 -->
          <div class="trade-section sell-section">
            <div class="section-header">
              <h3>
                <span class="section-icon sell">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="23 18 13.5 8.5 8.5 13.5 1 6"/>
                    <polyline points="17 18 23 18 23 12"/>
                  </svg>
                </span>
                매도 상위 {{ sellTrades.length }}종목
              </h3>
            </div>
            <div class="stock-table">
              <div class="table-header">
                <span class="col-rank">순위</span>
                <span class="col-name">종목명</span>
                <span class="col-amount">순매도(억)</span>
                <span class="col-price">현재가</span>
                <span class="col-change">등락률</span>
              </div>
              <div class="table-body">
                <div v-for="trade in sellTrades" :key="trade.id" class="stock-row">
                  <span class="col-rank">{{ trade.rankNum }}</span>
                  <span class="col-name">
                    <span class="stock-name">{{ trade.stockName }}</span>
                    <span class="stock-code">{{ trade.stockCode }}</span>
                  </span>
                  <span class="col-amount sell-amount">-{{ formatAmount(trade.netBuyAmount) }}</span>
                  <span class="col-price">{{ formatPrice(trade.currentPrice) }}</span>
                  <span class="col-change" :class="getChangeClass(trade.changeRate)">
                    {{ formatChangeRate(trade.changeRate) }}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 수집 결과 모달 -->
      <div v-if="showCollectResult" class="modal-overlay" @click="showCollectResult = false">
        <div class="modal-content" @click.stop>
          <h3>데이터 수집 결과</h3>
          <div class="collect-result">
            <div v-for="(count, key) in collectResult" :key="key" class="result-item">
              <span class="result-key">{{ formatResultKey(key) }}</span>
              <span class="result-value">{{ count }}건</span>
            </div>
          </div>
          <button @click="showCollectResult = false" class="btn btn-close">닫기</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { investorTradeAPI } from '../utils/api'

export default {
  name: 'InvestorTradePage',
  data() {
    return {
      selectedDate: this.formatDateForInput(new Date()),
      selectedMarket: 'KOSPI',
      selectedInvestor: 'PENSION',
      loading: false,
      collecting: false,
      buyTrades: [],
      sellTrades: [],
      showCollectResult: false,
      collectResult: {},
      marketTabs: [
        { value: 'KOSPI', label: '코스피', icon: '📈' },
        { value: 'KOSDAQ', label: '코스닥', icon: '📊' }
      ],
      investorTabs: [
        { value: 'PENSION', label: '연기금', icon: '🏛️' },
        { value: 'FOREIGN', label: '외국인', icon: '🌍' },
        { value: 'INSTITUTION', label: '기관', icon: '🏢' }
      ]
    }
  },
  computed: {
    isToday() {
      return this.selectedDate === this.formatDateForInput(new Date())
    },
    hasData() {
      return this.buyTrades.length > 0 || this.sellTrades.length > 0
    }
  },
  mounted() {
    this.loadData()
  },
  methods: {
    formatDateForInput(date) {
      const d = new Date(date)
      const year = d.getFullYear()
      const month = String(d.getMonth() + 1).padStart(2, '0')
      const day = String(d.getDate()).padStart(2, '0')
      return `${year}-${month}-${day}`
    },
    changeDate(days) {
      const date = new Date(this.selectedDate)
      date.setDate(date.getDate() + days)
      this.selectedDate = this.formatDateForInput(date)
      this.loadData()
    },
    goToToday() {
      this.selectedDate = this.formatDateForInput(new Date())
      this.loadData()
    },
    selectMarket(market) {
      this.selectedMarket = market
      this.loadData()
    },
    selectInvestor(investor) {
      this.selectedInvestor = investor
      this.loadData()
    },
    async loadData() {
      this.loading = true
      try {
        const response = await investorTradeAPI.getByMarketAndInvestor(
          this.selectedMarket,
          this.selectedInvestor,
          this.selectedDate
        )

        if (response.data.success) {
          const data = response.data.data
          this.buyTrades = data.buyTrades || []
          this.sellTrades = data.sellTrades || []
        }
      } catch (error) {
        console.error('데이터 로드 실패:', error)
        this.buyTrades = []
        this.sellTrades = []
      } finally {
        this.loading = false
      }
    },
    async collectData() {
      this.collecting = true
      try {
        const response = await investorTradeAPI.collectByDate(this.selectedDate)
        if (response.data.success) {
          this.collectResult = response.data.data
          this.showCollectResult = true
          await this.loadData()
        }
      } catch (error) {
        console.error('데이터 수집 실패:', error)
        alert('데이터 수집에 실패했습니다.')
      } finally {
        this.collecting = false
      }
    },
    refreshData() {
      this.loadData()
    },
    getInvestorLabel(type) {
      const tab = this.investorTabs.find(t => t.value === type)
      return tab ? tab.label : type
    },
    formatAmount(value) {
      if (!value) return '0'
      return Math.abs(value).toLocaleString('ko-KR', { maximumFractionDigits: 0 })
    },
    formatPrice(value) {
      if (!value) return '-'
      return Number(value).toLocaleString('ko-KR')
    },
    formatChangeRate(value) {
      if (value === null || value === undefined) return '-'
      const prefix = value >= 0 ? '+' : ''
      return `${prefix}${value.toFixed(2)}%`
    },
    getChangeClass(value) {
      if (!value) return ''
      return value >= 0 ? 'positive' : 'negative'
    },
    formatResultKey(key) {
      return key.replace('_', ' ')
        .replace('KOSPI', '코스피')
        .replace('KOSDAQ', '코스닥')
        .replace('FOREIGN', '외국인')
        .replace('INSTITUTION', '기관')
        .replace('PENSION', '연기금')
    },
    goBack() {
      this.$router.push('/user')
    }
  }
}
</script>

<style scoped>
/* 날짜 선택 */
.date-selector {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-bottom: 20px;
  padding: 16px;
  background: rgba(255, 255, 255, 0.95);
  border-radius: 16px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.date-nav-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border: none;
  border-radius: 10px;
  background: #f1f5f9;
  color: #64748b;
  cursor: pointer;
  transition: all 0.2s;
}

.date-nav-btn:hover:not(:disabled) {
  background: #e2e8f0;
  color: #334155;
}

.date-nav-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.date-input {
  padding: 10px 16px;
  font-size: 16px;
  font-weight: 600;
  border: 2px solid #e2e8f0;
  border-radius: 10px;
  background: white;
  color: #1e293b;
  cursor: pointer;
}

.date-input:focus {
  outline: none;
  border-color: #667eea;
}

.btn-today {
  padding: 10px 16px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 10px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-today:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
}

.btn-today:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 시장 탭 */
.market-tabs {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.market-tab {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 14px 20px;
  border: 2px solid #e2e8f0;
  border-radius: 12px;
  background: white;
  font-size: 15px;
  font-weight: 600;
  color: #64748b;
  cursor: pointer;
  transition: all 0.2s;
}

.market-tab:hover {
  border-color: #667eea;
  color: #667eea;
}

.market-tab.active {
  border-color: #667eea;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.tab-icon {
  font-size: 18px;
}

/* 투자자 탭 */
.investor-tabs {
  display: flex;
  gap: 10px;
  margin-bottom: 24px;
  padding: 8px;
  background: #f1f5f9;
  border-radius: 14px;
}

.investor-tab {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px 16px;
  border: none;
  border-radius: 10px;
  background: transparent;
  font-size: 14px;
  font-weight: 600;
  color: #64748b;
  cursor: pointer;
  transition: all 0.2s;
}

.investor-tab:hover {
  background: rgba(255, 255, 255, 0.5);
}

.investor-tab.active {
  background: white;
  color: #1e293b;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.investor-tab-icon {
  font-size: 16px;
}

/* 로딩 */
.loading-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: #64748b;
}

.loading-spinner {
  width: 48px;
  height: 48px;
  border: 4px solid #e2e8f0;
  border-top-color: #667eea;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin-bottom: 16px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 데이터 없음 */
.no-data-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  background: white;
  border-radius: 20px;
  color: #94a3b8;
}

.no-data-container svg {
  margin-bottom: 16px;
  opacity: 0.5;
}

.no-data-container h3 {
  margin: 0 0 8px 0;
  color: #64748b;
}

.no-data-container p {
  margin: 0 0 24px 0;
}

.btn-collect-large {
  padding: 14px 28px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-collect-large:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(102, 126, 234, 0.4);
}

/* 거래 섹션 */
.trade-sections {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

@media (max-width: 1024px) {
  .trade-sections {
    grid-template-columns: 1fr;
  }
}

.trade-section {
  background: white;
  border-radius: 20px;
  padding: 24px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.section-header {
  margin-bottom: 20px;
}

.section-header h3 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: 18px;
  color: #1e293b;
}

.section-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
}

.section-icon.buy {
  background: rgba(239, 68, 68, 0.1);
  color: #ef4444;
}

.section-icon.sell {
  background: rgba(59, 130, 246, 0.1);
  color: #3b82f6;
}

/* 테이블 */
.stock-table {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  overflow: hidden;
}

.table-header {
  display: grid;
  grid-template-columns: 50px 1fr 100px 100px 80px;
  gap: 8px;
  padding: 12px 16px;
  background: #f8fafc;
  font-size: 13px;
  font-weight: 600;
  color: #64748b;
}

.table-body {
  max-height: 600px;
  overflow-y: auto;
}

.stock-row {
  display: grid;
  grid-template-columns: 50px 1fr 100px 100px 80px;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #f1f5f9;
  font-size: 14px;
  transition: background 0.2s;
}

.stock-row:hover {
  background: #f8fafc;
}

.col-rank {
  font-weight: 700;
  color: #94a3b8;
}

.col-name {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stock-name {
  font-weight: 600;
  color: #1e293b;
}

.stock-code {
  font-size: 12px;
  color: #94a3b8;
}

.col-amount {
  text-align: right;
  font-weight: 700;
}

.buy-amount {
  color: #ef4444;
}

.sell-amount {
  color: #3b82f6;
}

.col-price {
  text-align: right;
  color: #64748b;
}

.col-change {
  text-align: right;
  font-weight: 600;
}

.col-change.positive {
  color: #ef4444;
}

.col-change.negative {
  color: #3b82f6;
}

/* 버튼 */
.btn-collect {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  background: linear-gradient(135deg, #10b981 0%, #059669 100%);
  color: white;
  border: none;
  border-radius: 10px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-collect:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(16, 185, 129, 0.4);
}

.btn-collect:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.btn-refresh {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  background: #f1f5f9;
  color: #64748b;
  border: none;
  border-radius: 10px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-refresh:hover:not(:disabled) {
  background: #e2e8f0;
}

.btn-refresh:disabled {
  opacity: 0.7;
}

.spinning {
  animation: spin 1s linear infinite;
}

.btn-back {
  padding: 10px 16px;
  background: #f1f5f9;
  color: #64748b;
  border: none;
  border-radius: 10px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-back:hover {
  background: #e2e8f0;
}

/* 모달 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: white;
  border-radius: 20px;
  padding: 28px;
  min-width: 360px;
  max-width: 90vw;
}

.modal-content h3 {
  margin: 0 0 20px 0;
  font-size: 20px;
  color: #1e293b;
}

.collect-result {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 24px;
}

.result-item {
  display: flex;
  justify-content: space-between;
  padding: 12px 16px;
  background: #f8fafc;
  border-radius: 10px;
}

.result-key {
  color: #64748b;
}

.result-value {
  font-weight: 700;
  color: #10b981;
}

.btn-close {
  width: 100%;
  padding: 14px;
  background: #f1f5f9;
  color: #64748b;
  border: none;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-close:hover {
  background: #e2e8f0;
}

/* 반응형 */
@media (max-width: 768px) {
  .date-selector {
    flex-wrap: wrap;
  }

  .market-tabs {
    flex-direction: column;
  }

  .investor-tabs {
    flex-wrap: wrap;
  }

  .table-header,
  .stock-row {
    grid-template-columns: 40px 1fr 80px 70px;
  }

  .col-price {
    display: none;
  }
}

/* 다크모드 */
[data-theme="dark"] .date-selector {
  background: var(--card-bg);
}

[data-theme="dark"] .date-input {
  background: var(--bg-secondary);
  border-color: var(--border-color);
  color: var(--text-primary);
}

[data-theme="dark"] .market-tab {
  background: var(--card-bg);
  border-color: var(--border-color);
  color: var(--text-secondary);
}

[data-theme="dark"] .investor-tabs {
  background: var(--bg-secondary);
}

[data-theme="dark"] .investor-tab.active {
  background: var(--card-bg);
  color: var(--text-primary);
}

[data-theme="dark"] .trade-section {
  background: var(--card-bg);
}

[data-theme="dark"] .section-header h3 {
  color: var(--text-primary);
}

[data-theme="dark"] .stock-table {
  border-color: var(--border-color);
}

[data-theme="dark"] .table-header {
  background: var(--bg-secondary);
}

[data-theme="dark"] .stock-row {
  border-color: var(--border-color);
}

[data-theme="dark"] .stock-row:hover {
  background: var(--bg-secondary);
}

[data-theme="dark"] .stock-name {
  color: var(--text-primary);
}

[data-theme="dark"] .no-data-container {
  background: var(--card-bg);
}

[data-theme="dark"] .modal-content {
  background: var(--card-bg);
}

[data-theme="dark"] .modal-content h3 {
  color: var(--text-primary);
}

[data-theme="dark"] .result-item {
  background: var(--bg-secondary);
}
</style>
