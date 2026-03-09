<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>대시보드</h1>
        <div class="header-actions">
          <button @click="showWidgetSettings = true" class="btn-widget-settings" title="위젯 설정">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="3"/>
              <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-2 2 2 2 0 01-2-2v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83 0 2 2 0 010-2.83l.06-.06a1.65 1.65 0 00.33-1.82 1.65 1.65 0 00-1.51-1H3a2 2 0 01-2-2 2 2 0 012-2h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 010-2.83 2 2 0 012.83 0l.06.06a1.65 1.65 0 001.82.33H9a1.65 1.65 0 001-1.51V3a2 2 0 012-2 2 2 0 012 2v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 0 2 2 0 010 2.83l-.06.06a1.65 1.65 0 00-.33 1.82V9a1.65 1.65 0 001.51 1H21a2 2 0 012 2 2 2 0 01-2 2h-.09a1.65 1.65 0 00-1.51 1z"/>
            </svg>
          </button>
          <div class="header-user">
            <div class="user-avatar">{{ username.charAt(0) }}</div>
            <span>{{ username }}</span>
          </div>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

      <!-- 환영 메시지 -->
      <section class="welcome-card">
        <div class="welcome-content">
          <h2>환영합니다, <span class="highlight">{{ username }}</span>님!</h2>
          <p>플랫폼에 접속하셨습니다. 아래 메뉴에서 원하는 기능을 선택하세요.</p>
        </div>
        <div class="welcome-decoration">
          <svg viewBox="0 0 200 200" xmlns="http://www.w3.org/2000/svg">
            <path fill="rgba(102, 126, 234, 0.1)" d="M45.7,-51.9C59.1,-41.5,69.7,-26.2,71.8,-9.8C73.9,6.6,67.5,24.2,56.4,37.2C45.3,50.3,29.5,58.8,12.4,62.8C-4.7,66.8,-23.1,66.3,-38.4,58.5C-53.7,50.7,-65.9,35.6,-70.3,18.5C-74.7,1.4,-71.3,-17.7,-61.5,-32.1C-51.7,-46.5,-35.5,-56.2,-19.1,-65.1C-2.7,-74,14,-82.1,28.6,-77.3C43.2,-72.5,55.7,-54.8,45.7,-51.9Z" transform="translate(100 100)" />
          </svg>
        </div>
      </section>

      <!-- 시장 정보 위젯 -->
      <section class="market-info-section">
        <MarketInfoWidget />
      </section>

      <!-- 주식 섹션 -->
      <section class="menu-section">
        <div class="section-header">
          <span class="section-icon">📈</span>
          <h2>주식</h2>
        </div>
        <div class="menu-grid">
          <article class="menu-card stock-dashboard-v2" @click="goToStockDashboard">
            <div class="card-icon stock-dashboard-icon">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M12 2L2 7l10 5 10-5-10-5z"/>
                <path d="M2 17l10 5 10-5"/>
                <path d="M2 12l10 5 10-5"/>
              </svg>
            </div>
            <h3>주식 트레이딩 대시보드 <span class="menu-ai-badge gold">V2</span></h3>
            <p>AI 전략, 시장 지도, 스마트 머니, 리서치 - 한눈에 보기</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>
        </div>
      </section>

      <!-- 시세 섹션 -->
      <section class="menu-section">
        <div class="section-header">
          <span class="section-icon">💰</span>
          <h2>시세</h2>
        </div>
        <div class="menu-grid">
          <article v-if="widgetSettings.goldPrice" class="menu-card gold" @click="goToGold">
            <div class="card-icon gold-icon">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="12" cy="12" r="10"/>
                <path d="M12 6v12"/>
                <path d="M15 9.5c0-1.5-1.5-2.5-3-2.5s-3 1-3 2.5c0 2 6 1 6 4 0 1.5-1.5 2.5-3 2.5s-3-1-3-2.5"/>
              </svg>
            </div>
            <h3>금 시세</h3>
            <p v-if="goldPrice">{{ formatCurrency(goldPrice.price) }}/g <span :class="goldPrice.changeRate >= 0 ? 'text-positive' : 'text-negative'">({{ goldPrice.changeRate >= 0 ? '+' : '' }}{{ goldPrice.changeRate?.toFixed(2) || 0 }}%)</span></p>
            <p v-else>금 시세를 확인합니다.</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>

          <article v-if="widgetSettings.silverPrice" class="menu-card silver" @click="goToSilver">
            <div class="card-icon silver-icon">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="12" cy="12" r="10"/>
                <path d="M12 6v12"/>
                <path d="M15 9.5c0-1.5-1.5-2.5-3-2.5s-3 1-3 2.5c0 2 6 1 6 4 0 1.5-1.5 2.5-3 2.5s-3-1-3-2.5"/>
              </svg>
            </div>
            <h3>은 시세</h3>
            <p v-if="silverPrice">{{ formatCurrency(silverPrice.price) }}/g <span :class="silverPrice.changeRate >= 0 ? 'text-positive' : 'text-negative'">({{ silverPrice.changeRate >= 0 ? '+' : '' }}{{ silverPrice.changeRate?.toFixed(2) || 0 }}%)</span></p>
            <p v-else>은 시세를 확인합니다.</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>

          <article class="menu-card oil" @click="goToOil">
            <div class="card-icon oil-icon">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M6 20V10a6 6 0 0 1 12 0v10"/>
                <path d="M6 20h12"/>
                <path d="M12 4V2"/>
                <circle cx="12" cy="10" r="2"/>
              </svg>
            </div>
            <h3>원유 시세</h3>
            <p>WTI 원유 선물 시세</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>

          <article class="menu-card futures" @click="goToGlobalFutures">
            <div class="card-icon futures-icon">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <polyline points="22,7 13.5,15.5 8.5,10.5 2,17"/>
                <polyline points="16,7 22,7 22,13"/>
              </svg>
            </div>
            <h3>글로벌 선물</h3>
            <p>야간선물 / 해외선물 시세</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>
        </div>
      </section>

      <!-- 관리 섹션 -->
      <section class="menu-section">
        <div class="section-header">
          <span class="section-icon">⚙️</span>
          <h2>관리</h2>
        </div>
        <div class="menu-grid">
          <article class="menu-card" @click="goToMyContent">
            <div class="card-icon content">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7"/>
                <path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z"/>
              </svg>
            </div>
            <h3>내 콘텐츠</h3>
            <p>작성한 글과 파일을 확인하고 관리합니다.</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>

          <article class="menu-card" @click="goToSettings">
            <div class="card-icon settings">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="12" cy="12" r="3"/>
                <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-2 2 2 2 0 01-2-2v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83 0 2 2 0 010-2.83l.06-.06a1.65 1.65 0 00.33-1.82 1.65 1.65 0 00-1.51-1H3a2 2 0 01-2-2 2 2 0 012-2h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 010-2.83 2 2 0 012.83 0l.06.06a1.65 1.65 0 001.82.33H9a1.65 1.65 0 001-1.51V3a2 2 0 012-2 2 2 0 012 2v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 0 2 2 0 010 2.83l-.06.06a1.65 1.65 0 00-.33 1.82V9a1.65 1.65 0 001.51 1H21a2 2 0 012 2 2 2 0 01-2 2h-.09a1.65 1.65 0 00-1.51 1z"/>
              </svg>
            </div>
            <h3>내 설정</h3>
            <p>개인 정보 및 비밀번호를 관리합니다.</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>

          <article class="menu-card" @click="goToFiles">
            <div class="card-icon files">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/>
              </svg>
            </div>
            <h3>내 파일</h3>
            <p>개인 파일과 폴더를 관리합니다.</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>

          <article class="menu-card car" @click="goToCar">
            <div class="card-icon car-icon">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M19 17h2c.6 0 1-.4 1-1v-3c0-.9-.7-1.7-1.5-1.9L18 10l-2.7-3.6c-.4-.5-1-.9-1.6-.9H10c-.7 0-1.3.4-1.6.9L5.5 10l-2 1.1C2.7 11.3 2 12.1 2 13v3c0 .6.4 1 1 1h2"/>
                <circle cx="7" cy="17" r="2"/>
                <circle cx="17" cy="17" r="2"/>
              </svg>
            </div>
            <h3>자동차 관리</h3>
            <p>정비 기록과 주행거리를 관리합니다.</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>

          <article v-if="widgetSettings.assetSummary" class="menu-card asset" @click="goToAsset">
            <div class="card-icon asset-icon">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <line x1="12" y1="1" x2="12" y2="23"/>
                <path d="M17 5H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6"/>
              </svg>
            </div>
            <h3>자산 관리</h3>
            <p>총 자산 {{ formatCurrency(assetSummary.totalAssets) }} <span :class="assetSummary.totalProfit >= 0 ? 'text-positive' : 'text-negative'">({{ assetSummary.totalProfit >= 0 ? '+' : '' }}{{ assetSummary.profitRate?.toFixed(2) || 0 }}%)</span></p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>

          <article v-if="widgetSettings.financeSummary" class="menu-card finance" @click="goToFinance">
            <div class="card-icon finance-icon">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <rect x="3" y="4" width="18" height="18" rx="2" ry="2"/>
                <line x1="16" y1="2" x2="16" y2="6"/>
                <line x1="8" y1="2" x2="8" y2="6"/>
                <line x1="3" y1="10" x2="21" y2="10"/>
              </svg>
            </div>
            <h3>가계부</h3>
            <p>이번 달 <span :class="financeSummary.balance >= 0 ? 'text-positive' : 'text-negative'">{{ formatCurrency(financeSummary.balance) }}</span></p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>
        </div>
      </section>

      <!-- 기타 섹션 -->
      <section class="menu-section">
        <div class="section-header">
          <span class="section-icon">📋</span>
          <h2>기타</h2>
        </div>
        <div class="menu-grid">
          <article class="menu-card" @click="goToBoard">
            <div class="card-icon board">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
                <polyline points="14,2 14,8 20,8"/>
                <line x1="16" y1="13" x2="8" y2="13"/>
                <line x1="16" y1="17" x2="8" y2="17"/>
                <polyline points="10,9 9,9 8,9"/>
              </svg>
            </div>
            <h3>게시판</h3>
            <p>자유롭게 글을 작성하고 파일을 공유할 수 있습니다.</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>

          <article class="menu-card lotto" @click="goToLotto">
            <div class="card-icon lotto-icon">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="12" cy="12" r="10"/>
                <circle cx="12" cy="12" r="3"/>
                <path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.93 4.93l2.12 2.12M16.95 16.95l2.12 2.12M4.93 19.07l2.12-2.12M16.95 7.05l2.12-2.12"/>
              </svg>
            </div>
            <h3>로또 분석기</h3>
            <p>통계 기반 로또 번호 추천</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>

          <article class="menu-card pension" @click="goToPensionLottery">
            <div class="card-icon pension-icon">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <rect x="3" y="4" width="18" height="16" rx="2"/>
                <path d="M7 8h2M11 8h2M15 8h2"/>
                <path d="M7 12h2M11 12h2M15 12h2"/>
                <path d="M7 16h10"/>
              </svg>
            </div>
            <h3>연금복권 분석기</h3>
            <p>통계 기반 연금복권 720+ 추천</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>
        </div>
      </section>

      <!-- 위젯 설정 모달 -->
      <WidgetSettingsModal
        :visible="showWidgetSettings"
        :settings="widgetSettings"
        @close="showWidgetSettings = false"
        @update:settings="updateWidgetSettings"
      />
    </div>
  </div>
</template>

<script>
import { newsAPI, financeAPI, goldAPI, silverAPI, assetAPI } from '../utils/api';
import WidgetSettingsModal from '../components/WidgetSettingsModal.vue';
import MarketInfoWidget from '../components/MarketInfoWidget.vue';

export default {
  name: 'UserDashboard',
  components: {
    WidgetSettingsModal,
    MarketInfoWidget
  },
  data() {
    return {
      username: '',
      newsList: [],
      showWidgetSettings: false,
      widgetSettings: {
        goldPrice: true,
        silverPrice: true,
        assetSummary: true,
        news: true,
        financeSummary: true,
        investorTrades: true,
        earningsScreener: true
      },
      financeSummary: {
        totalIncome: 0,
        totalExpense: 0,
        balance: 0
      },
      goldPrice: null,
      silverPrice: null,
      assetSummary: {
        totalAssets: 0,
        totalProfit: 0,
        profitRate: 0
      },
      loadingPrices: false
    }
  },
  mounted() {
    this.username = localStorage.getItem('username') || 'User'
    this.loadWidgetSettings()
    this.loadNews()
    this.loadFinanceSummary()
    this.loadPrices()
    this.loadAssetSummary()
  },
  methods: {
    loadWidgetSettings() {
      const saved = localStorage.getItem('dashboardWidgets')
      if (saved) {
        this.widgetSettings = { ...this.widgetSettings, ...JSON.parse(saved) }
      }
    },
    updateWidgetSettings(settings) {
      this.widgetSettings = { ...this.widgetSettings, ...settings }
      // 위젯 데이터 다시 로드
      if (settings.goldPrice || settings.silverPrice) {
        this.loadPrices()
      }
      if (settings.assetSummary) {
        this.loadAssetSummary()
      }
      if (settings.financeSummary) {
        this.loadFinanceSummary()
      }
      if (settings.news) {
        this.loadNews()
      }
    },
    async loadFinanceSummary() {
      try {
        const now = new Date()
        const response = await financeAPI.getMonthlyTransactions(now.getFullYear(), now.getMonth() + 1)
        if (response.data.success) {
          const data = response.data.data
          this.financeSummary = {
            totalIncome: data.totalIncome || 0,
            totalExpense: data.totalExpense || 0,
            balance: data.balance || 0
          }
        }
      } catch (error) {
        console.error('가계부 요약 로드 실패:', error)
      }
    },
    async loadPrices() {
      this.loadingPrices = true
      try {
        const [goldRes, silverRes] = await Promise.all([
          goldAPI.getPrice(),
          silverAPI.getPrice()
        ])
        if (goldRes.data.success) {
          this.goldPrice = goldRes.data.data
        }
        if (silverRes.data.success) {
          this.silverPrice = silverRes.data.data
        }
      } catch (error) {
        console.error('시세 로드 실패:', error)
      } finally {
        this.loadingPrices = false
      }
    },
    async loadAssetSummary() {
      try {
        const response = await assetAPI.getAssetSummary()
        if (response.data.success) {
          const data = response.data.data
          this.assetSummary = {
            totalAssets: data.totalCurrentValue || 0,
            totalProfit: data.totalProfit || 0,
            profitRate: data.profitRate || 0
          }
        }
      } catch (error) {
        console.error('자산 요약 로드 실패:', error)
      }
    },
    formatCurrency(value) {
      if (!value) return '0원'
      return new Intl.NumberFormat('ko-KR').format(value) + '원'
    },
    async loadNews() {
      try {
        // 오늘 뉴스가 없으면 최근 뉴스 조회
        let response = await newsAPI.getTodayNews()
        if (response.data.data && response.data.data.length > 0) {
          this.newsList = response.data.data.slice(0, 5)
        } else {
          response = await newsAPI.getRecentNews()
          this.newsList = response.data.data ? response.data.data.slice(0, 5) : []
        }
      } catch (error) {
        console.error('뉴스 로드 실패:', error)
        this.newsList = []
      }
    },
    goToBoard() {
      this.$router.push('/board')
    },
    goToMyContent() {
      this.$router.push('/my-content')
    },
    goToAsset() {
      this.$router.push('/asset')
    },
    goToSettings() {
      this.$router.push('/settings')
    },
    goToGold() {
      this.$router.push('/gold')
    },
    goToSilver() {
      this.$router.push('/silver')
    },
    goToOil() {
      this.$router.push('/global-futures')
    },
    goToGlobalFutures() {
      this.$router.push('/global-futures')
    },
    goToFiles() {
      this.$router.push('/files')
    },
    goToFinance() {
      this.$router.push('/finance')
    },
    goToCar() {
      this.$router.push('/car')
    },
    goToStockDashboard() {
      this.$router.push('/stock-dashboard')
    },
    goToLotto() {
      this.$router.push('/lotto')
    },
    goToPensionLottery() {
      this.$router.push('/pension-lottery')
    },
    logout() {
      localStorage.removeItem('jwt_token')
      localStorage.removeItem('username')
      localStorage.removeItem('role')
      this.$router.push('/login')
    }
  }
}
</script>

<style scoped>
/* 환영 카드 */
.welcome-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: var(--card-padding);
  margin-bottom: var(--section-gap);
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
  display: flex;
  justify-content: space-between;
  align-items: center;
  overflow: hidden;
  position: relative;
}

.welcome-content {
  position: relative;
  z-index: 1;
}

.welcome-content h2 {
  font-size: 28px;
  color: var(--text-primary);
  margin: 0 0 12px 0;
}

.welcome-content .highlight {
  background: var(--primary-gradient);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.welcome-content p {
  font-size: 16px;
  color: var(--text-muted);
  margin: 0;
}

.welcome-decoration {
  position: absolute;
  right: -50px;
  top: -50px;
  width: 300px;
  height: 300px;
  opacity: 0.5;
}

/* 사용자 아바타 */
.user-avatar {
  width: 36px;
  height: 36px;
  background: var(--primary-gradient);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-weight: 700;
  font-size: 16px;
}

/* 메뉴 섹션 */
.menu-section {
  margin-bottom: 2rem;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 1rem;
  padding: 12px 16px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(10px);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  border-left: 4px solid;
  border-image: linear-gradient(135deg, #667eea 0%, #764ba2 100%) 1;
}

.section-icon {
  font-size: 1.5rem;
  filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.1));
}

.section-header h2 {
  font-size: 1.25rem;
  font-weight: 700;
  margin: 0;
  color: #1a1a2e;
  text-shadow: 0 1px 2px rgba(255, 255, 255, 0.8);
}

/* 메뉴 그리드 */
.menu-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 20px;
}

.menu-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: var(--card-padding);
  cursor: pointer;
  transition: all 0.3s ease;
  border: 2px solid transparent;
  position: relative;
  overflow: hidden;
}

.menu-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.15);
  border-color: var(--primary-start);
}

.menu-card:hover .card-arrow {
  opacity: 1;
  transform: translateX(0);
}

.card-icon {
  width: 60px;
  height: 60px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
  transition: transform 0.3s ease;
}

.menu-card:hover .card-icon {
  transform: scale(1.1);
}

.card-icon.board {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.15) 0%, rgba(118, 75, 162, 0.15) 100%);
  color: var(--primary-start);
}

.card-icon.content {
  background: linear-gradient(135deg, rgba(79, 172, 254, 0.15) 0%, rgba(0, 242, 254, 0.15) 100%);
  color: #4facfe;
}

.card-icon.asset {
  background: linear-gradient(135deg, rgba(247, 183, 51, 0.15) 0%, rgba(252, 74, 26, 0.15) 100%);
  color: #f7b733;
}

.card-icon.settings {
  background: linear-gradient(135deg, rgba(108, 117, 125, 0.15) 0%, rgba(73, 80, 87, 0.15) 100%);
  color: #6c757d;
}

.card-icon.files {
  background: linear-gradient(135deg, rgba(93, 173, 226, 0.15) 0%, rgba(52, 152, 219, 0.15) 100%);
  color: #3498db;
}

.card-icon.car-icon {
  background: linear-gradient(135deg, rgba(52, 73, 94, 0.15) 0%, rgba(44, 62, 80, 0.15) 100%);
  color: #34495e;
}

.card-icon.sector-icon {
  background: linear-gradient(135deg, rgba(79, 70, 229, 0.15) 0%, rgba(139, 92, 246, 0.15) 100%);
  color: #4F46E5;
}

.menu-card.car {
  background: linear-gradient(135deg, rgba(245, 247, 250, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(52, 73, 94, 0.2);
}

.menu-card.car:hover {
  border-color: #34495e;
  box-shadow: 0 20px 40px rgba(52, 73, 94, 0.15);
}

.menu-card.car h3 {
  color: #2c3e50;
}

/* AI 분석 카드 */
.card-icon.ai-analysis-icon {
  background: linear-gradient(135deg, rgba(236, 72, 153, 0.15) 0%, rgba(168, 85, 247, 0.15) 100%);
  color: #a855f7;
}

.menu-card.ai-analysis {
  background: linear-gradient(135deg, rgba(253, 244, 255, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(168, 85, 247, 0.3);
}

.menu-card.ai-analysis:hover {
  border-color: #a855f7;
  box-shadow: 0 20px 40px rgba(168, 85, 247, 0.2);
}

.menu-card.ai-analysis h3 {
  color: #9333ea;
}

/* 주식 트레이딩 대시보드 V2 카드 */
.card-icon.stock-dashboard-icon {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.2) 0%, rgba(118, 75, 162, 0.2) 100%);
  color: #667eea;
}

.menu-card.stock-dashboard-v2 {
  background: linear-gradient(135deg, rgba(238, 242, 255, 0.98) 0%, rgba(245, 243, 255, 0.95) 100%);
  border: 2px solid rgba(102, 126, 234, 0.4);
  position: relative;
  overflow: hidden;
  grid-column: 1 / -1;
}

.menu-card.stock-dashboard-v2::before {
  content: '';
  position: absolute;
  top: -50%;
  left: -50%;
  width: 200%;
  height: 200%;
  background: linear-gradient(
    45deg,
    transparent 30%,
    rgba(102, 126, 234, 0.08) 50%,
    transparent 70%
  );
  animation: shine 3s infinite;
}

@keyframes shine {
  0% { transform: translateX(-100%) rotate(45deg); }
  100% { transform: translateX(100%) rotate(45deg); }
}

.menu-card.stock-dashboard-v2:hover {
  border-color: #667eea;
  box-shadow: 0 20px 50px rgba(102, 126, 234, 0.3);
  transform: translateY(-8px);
}

.menu-card.stock-dashboard-v2 h3 {
  color: #4F46E5;
}

.menu-ai-badge.gold {
  background: linear-gradient(135deg, #ffd700 0%, #ffaa00 100%);
  color: #78350f;
  font-weight: 700;
}

.menu-card.sector {
  background: linear-gradient(135deg, rgba(238, 242, 255, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(79, 70, 229, 0.2);
}

.menu-card.sector:hover {
  border-color: #4F46E5;
  box-shadow: 0 20px 40px rgba(79, 70, 229, 0.15);
}

.menu-card.sector h3 {
  color: #4F46E5;
}

/* 금 시세 카드 */
.card-icon.gold-icon {
  background: linear-gradient(135deg, rgba(255, 215, 0, 0.2) 0%, rgba(218, 165, 32, 0.2) 100%);
  color: #daa520;
}

.menu-card.gold {
  background: linear-gradient(135deg, rgba(255, 250, 230, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(255, 215, 0, 0.3);
}

.menu-card.gold:hover {
  border-color: #ffd700;
  box-shadow: 0 20px 40px rgba(255, 215, 0, 0.15);
}

.menu-card.gold h3 {
  color: #b8860b;
}

/* 원유 시세 카드 */
.card-icon.oil-icon {
  background: linear-gradient(135deg, rgba(44, 62, 80, 0.2) 0%, rgba(52, 73, 94, 0.2) 100%);
  color: #2c3e50;
}

.menu-card.oil {
  background: linear-gradient(135deg, rgba(234, 242, 248, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(41, 128, 185, 0.3);
}

.menu-card.oil:hover {
  border-color: #2980b9;
  box-shadow: 0 20px 40px rgba(41, 128, 185, 0.15);
}

.menu-card.oil h3 {
  color: #2c3e50;
}

/* 글로벌 선물 카드 */
.card-icon.futures-icon {
  background: linear-gradient(135deg, rgba(46, 204, 113, 0.15) 0%, rgba(39, 174, 96, 0.15) 100%);
  color: #27ae60;
}

.menu-card.futures {
  background: linear-gradient(135deg, rgba(232, 246, 239, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(39, 174, 96, 0.3);
}

.menu-card.futures:hover {
  border-color: #27ae60;
  box-shadow: 0 20px 40px rgba(39, 174, 96, 0.15);
}

.menu-card.futures h3 {
  color: #27ae60;
}

/* 은 시세 카드 */
.card-icon.silver-icon {
  background: linear-gradient(135deg, rgba(192, 192, 192, 0.2) 0%, rgba(169, 169, 169, 0.2) 100%);
  color: #708090;
}

.menu-card.silver {
  background: linear-gradient(135deg, rgba(248, 250, 252, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(192, 192, 192, 0.3);
}

.menu-card.silver:hover {
  border-color: #c0c0c0;
  box-shadow: 0 20px 40px rgba(128, 128, 128, 0.15);
}

.menu-card.silver h3 {
  color: #5a6a7a;
}

/* 자산 관리 카드 */
.card-icon.asset-icon {
  background: linear-gradient(135deg, rgba(247, 183, 51, 0.15) 0%, rgba(252, 74, 26, 0.15) 100%);
  color: #f7b733;
}

.menu-card.asset {
  background: linear-gradient(135deg, rgba(255, 250, 240, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(247, 183, 51, 0.3);
}

.menu-card.asset:hover {
  border-color: #f7b733;
  box-shadow: 0 20px 40px rgba(247, 183, 51, 0.15);
}

.menu-card.asset h3 {
  color: #d97706;
}

/* 가계부 카드 */
.card-icon.finance-icon {
  background: linear-gradient(135deg, rgba(46, 204, 113, 0.15) 0%, rgba(39, 174, 96, 0.15) 100%);
  color: #2ecc71;
}

.card-icon.stock-icon {
  background: linear-gradient(135deg, rgba(231, 76, 60, 0.15) 0%, rgba(192, 57, 43, 0.15) 100%);
  color: #e74c3c;
}

.menu-card.stock {
  background: linear-gradient(135deg, rgba(255, 245, 245, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(231, 76, 60, 0.3);
}

.menu-card.stock:hover {
  border-color: #e74c3c;
  box-shadow: 0 20px 40px rgba(231, 76, 60, 0.2);
}

.menu-card.stock h3 {
  color: #c0392b;
}

.menu-card.finance {
  background: linear-gradient(135deg, rgba(240, 253, 244, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(46, 204, 113, 0.3);
}

.menu-card.finance:hover {
  border-color: #2ecc71;
  box-shadow: 0 20px 40px rgba(46, 204, 113, 0.15);
}

.menu-card.finance h3 {
  color: #16a34a;
}

/* 뉴스 카드 */
.card-icon.news-icon {
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.15) 0%, rgba(99, 102, 241, 0.15) 100%);
  color: #3b82f6;
}

.menu-card.news {
  background: linear-gradient(135deg, rgba(239, 246, 255, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(59, 130, 246, 0.3);
}

.menu-card.news:hover {
  border-color: #3b82f6;
  box-shadow: 0 20px 40px rgba(59, 130, 246, 0.15);
}

.menu-card.news h3 {
  color: #2563eb;
}

/* 로또 분석기 카드 */
.card-icon.lotto-icon {
  background: linear-gradient(135deg, rgba(255, 193, 7, 0.15) 0%, rgba(255, 87, 34, 0.15) 100%);
  color: #ff9800;
}

.menu-card.lotto {
  background: linear-gradient(135deg, rgba(255, 248, 225, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(255, 193, 7, 0.3);
}

.menu-card.lotto:hover {
  border-color: #ffc107;
  box-shadow: 0 20px 40px rgba(255, 193, 7, 0.15);
}

.menu-card.lotto h3 {
  color: #f57c00;
}

/* 연금복권 분석기 카드 */
.card-icon.pension-icon {
  background: linear-gradient(135deg, rgba(0, 212, 255, 0.15) 0%, rgba(124, 58, 237, 0.15) 100%);
  color: #00d4ff;
}

.menu-card.pension {
  background: linear-gradient(135deg, rgba(240, 248, 255, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(0, 212, 255, 0.3);
}

.menu-card.pension:hover {
  border-color: #00d4ff;
  box-shadow: 0 20px 40px rgba(0, 212, 255, 0.15);
}

.menu-card.pension h3 {
  color: #1e88e5;
}

/* 투자자 매매 동향 카드 */
.card-icon.investor-icon {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.15) 0%, rgba(118, 75, 162, 0.15) 100%);
  color: #667eea;
}

.menu-card.investor {
  background: linear-gradient(135deg, rgba(243, 244, 255, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(102, 126, 234, 0.3);
}

.menu-card.investor:hover {
  border-color: #667eea;
  box-shadow: 0 20px 40px rgba(102, 126, 234, 0.15);
}

.menu-card.investor h3 {
  color: #5568d3;
}

/* 연속 매수 카드 */
.card-icon.consecutive-icon {
  background: linear-gradient(135deg, rgba(237, 137, 54, 0.15) 0%, rgba(221, 107, 32, 0.15) 100%);
  color: #ed8936;
}

.menu-card.consecutive {
  background: linear-gradient(135deg, rgba(255, 250, 240, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(237, 137, 54, 0.3);
}

.menu-card.consecutive:hover {
  border-color: #ed8936;
  box-shadow: 0 20px 40px rgba(237, 137, 54, 0.15);
}

.menu-card.consecutive h3 {
  color: #dd6b20;
}

/* 수급 급증 카드 */
.card-icon.surge-icon {
  background: linear-gradient(135deg, rgba(229, 62, 62, 0.15) 0%, rgba(197, 48, 48, 0.15) 100%);
  color: #e53e3e;
}

.menu-card.surge {
  background: linear-gradient(135deg, rgba(254, 242, 242, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(229, 62, 62, 0.3);
}

.menu-card.surge:hover {
  border-color: #e53e3e;
  box-shadow: 0 20px 40px rgba(229, 62, 62, 0.15);
}

.menu-card.surge h3 {
  color: #c53030;
}

/* 실적 스크리너 카드 */
.card-icon.screener-icon {
  background: linear-gradient(135deg, rgba(74, 222, 128, 0.15) 0%, rgba(34, 197, 94, 0.15) 100%);
  color: #4ade80;
}

.menu-card.screener {
  background: linear-gradient(135deg, rgba(240, 253, 244, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(74, 222, 128, 0.3);
}

.menu-card.screener:hover {
  border-color: #4ade80;
  box-shadow: 0 20px 40px rgba(74, 222, 128, 0.15);
}

.menu-card.screener h3 {
  color: #16a34a;
}

/* 시장 지표 카드 */
.card-icon.market-timing-icon {
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.15) 0%, rgba(217, 119, 6, 0.15) 100%);
  color: #f59e0b;
}

.menu-card.market-timing {
  background: linear-gradient(135deg, rgba(255, 251, 235, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(245, 158, 11, 0.3);
}

.menu-card.market-timing:hover {
  border-color: #f59e0b;
  box-shadow: 0 20px 40px rgba(245, 158, 11, 0.15);
}

.menu-card.market-timing h3 {
  color: #d97706;
}

/* 트레이딩 지표 카드 */
.card-icon.trading-indicators-icon {
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.15) 0%, rgba(109, 40, 217, 0.15) 100%);
  color: #8b5cf6;
}

.menu-card.trading-indicators {
  background: linear-gradient(135deg, rgba(245, 243, 255, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(139, 92, 246, 0.3);
}

.menu-card.trading-indicators:hover {
  border-color: #8b5cf6;
  box-shadow: 0 20px 40px rgba(139, 92, 246, 0.15);
}

.menu-card.trading-indicators h3 {
  color: #7c3aed;
}

/* 종목 종합상세 카드 */
.card-icon.stock-detail-icon {
  background: linear-gradient(135deg, rgba(6, 182, 212, 0.15) 0%, rgba(14, 165, 233, 0.15) 100%);
  color: #06b6d4;
}

.menu-card.stock-detail {
  background: linear-gradient(135deg, rgba(236, 254, 255, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(6, 182, 212, 0.3);
}

.menu-card.stock-detail:hover {
  border-color: #06b6d4;
  box-shadow: 0 20px 40px rgba(6, 182, 212, 0.15);
}

.menu-card.stock-detail h3 {
  color: #0891b2;
}

/* AI 뱃지 (메뉴 카드용) */
.menu-ai-badge {
  display: inline-block;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 2px 8px;
  border-radius: 8px;
  font-size: 10px;
  font-weight: 700;
  margin-left: 6px;
  vertical-align: middle;
}

/* 텍스트 색상 유틸리티 */
.text-positive {
  color: #ef4444;
}

.text-negative {
  color: #3b82f6;
}

.menu-card h3 {
  font-size: 20px;
  color: var(--text-primary);
  margin: 0 0 10px 0;
  font-weight: 600;
}

.menu-card p {
  font-size: 14px;
  color: var(--text-muted);
  margin: 0;
  line-height: 1.5;
}

.card-arrow {
  position: absolute;
  right: 24px;
  top: 50%;
  transform: translateX(10px) translateY(-50%);
  opacity: 0;
  transition: all 0.3s ease;
  color: var(--primary-start);
}


.ai-decoration::after {
  content: '';
  position: absolute;
  right: 150px;
  bottom: -50px;
  width: 200px;
  height: 200px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 50%;
}

/* 반응형 */
@media (max-width: 768px) {
  .welcome-card {
    padding: var(--card-padding);
  }

  .welcome-content h2 {
    font-size: 22px;
  }

  .welcome-decoration {
    display: none;
  }

  .menu-grid {
    grid-template-columns: 1fr;
  }

  .ai-banner {
    padding: 24px;
  }

  .ai-banner-content {
    flex-direction: column;
    text-align: center;
    gap: 16px;
  }

  .ai-icon {
    width: 64px;
    height: 64px;
  }

  .ai-icon svg {
    width: 36px;
    height: 36px;
  }

  .ai-text h3 {
    font-size: 20px;
  }

  .ai-text p {
    font-size: 14px;
  }

  .ai-arrow {
    width: 100%;
    justify-content: center;
  }

  .ai-decoration {
    display: none;
  }

  .news-section {
    padding: 20px;
  }

  .news-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .news-item {
    padding: 14px 16px;
  }

  .news-content h4 {
    font-size: 14px;
  }

  .news-content p {
    font-size: 12px;
    -webkit-line-clamp: 3;
  }
}

/* 위젯 설정 버튼 */
.btn-widget-settings {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border: none;
  border-radius: 12px;
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.3s ease;
}

.btn-widget-settings:hover {
  transform: scale(1.05);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  color: var(--primary-start);
}

/* 시장 정보 섹션 */
.market-info-section {
  margin-bottom: var(--section-gap);
}
</style>
