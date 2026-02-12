<template>
  <div class="v2-dashboard">
    <div class="v2-content">
      <!-- 헤더 -->
      <DashboardHeader @open-search="showSearch = true" />

      <!-- 2x2 그리드 -->
      <div class="dashboard-grid">
        <!-- A. AI 전략 -->
        <SectionAiStrategy
          :data="aiStrategyData"
          :loading="sections.aiStrategy.loading"
          :error="sections.aiStrategy.error"
        />

        <!-- B. 시장 지도 -->
        <SectionMarketMap
          :sectorData="sectorData"
          :marketData="marketData"
          :globalData="globalData"
          :loading="sections.marketMap.loading"
          :error="sections.marketMap.error"
        />

        <!-- C. 스마트 머니 -->
        <SectionSmartMoney
          :tradesData="tradesData"
          :consecutiveData="consecutiveData"
          :surgeData="surgeData"
          :loading="sections.smartMoney.loading"
          :error="sections.smartMoney.error"
        />

        <!-- D. AI 리서치 -->
        <SectionResearch
          :screenerData="screenerData"
          :newsData="newsData"
          :loading="sections.research.loading"
          :error="sections.research.error"
        />
      </div>

      <!-- 종목 검색 모달 -->
      <StockSearchModal
        :visible="showSearch"
        @close="showSearch = false"
        @select="onStockSelect"
      />
    </div>
  </div>
</template>

<script>
import DashboardHeader from '../components/v2/DashboardHeader.vue'
import SectionAiStrategy from '../components/v2/SectionAiStrategy.vue'
import SectionMarketMap from '../components/v2/SectionMarketMap.vue'
import SectionSmartMoney from '../components/v2/SectionSmartMoney.vue'
import SectionResearch from '../components/v2/SectionResearch.vue'
import StockSearchModal from '../components/v2/StockSearchModal.vue'
import {
  aiStrategyAPI, sectorAPI, marketAPI, tradingIndicatorAPI,
  investorAPI, screenerAPI, newsAPI
} from '../utils/api'

// ===================== 실제 종목 기반 폴백 데이터 =====================

function getFallbackAiStrategy() {
  return {
    strategies: {
      SCALPING: [
        { stockCode: '005930', stockName: '삼성전자', currentPrice: 83400, changeRate: 2.45, score: 82, aiScore: 88, aiComment: '반도체 수급 쏠림, 외인 매집 가속', aiThemes: 'AI반도체,수급우량,HBM', originalScore: 78, rankNum: 1, reason: '거래량 450% 급증, +2.45% 상승', volumeRatio: 450 },
        { stockCode: '000660', stockName: 'SK하이닉스', currentPrice: 178500, changeRate: 3.12, score: 79, aiScore: 85, aiComment: 'HBM3E 양산 확대 수혜', aiThemes: 'HBM,AI서버,실적호조', originalScore: 74, rankNum: 2, reason: '거래량 380% 급증', volumeRatio: 380 },
        { stockCode: '035420', stockName: 'NAVER', currentPrice: 214000, changeRate: 1.67, score: 74, aiScore: 76, aiComment: 'AI 검색 리뉴얼 기대감', aiThemes: 'AI플랫폼,광고매출', originalScore: 72, rankNum: 3, reason: '거래량 290% 급증', volumeRatio: 290 },
        { stockCode: '006400', stockName: '삼성SDI', currentPrice: 412000, changeRate: -0.48, score: 68, aiScore: 71, aiComment: '전고체 배터리 양산 임박', aiThemes: '2차전지,전고체', originalScore: 66, rankNum: 4, reason: '거래량 220% 급증', volumeRatio: 220 },
        { stockCode: '035720', stockName: '카카오', currentPrice: 42550, changeRate: 0.95, score: 65, aiScore: 68, aiComment: 'AI 카카오톡 리뉴얼 모멘텀', aiThemes: 'AI플랫폼,카카오톡', originalScore: 63, rankNum: 5, reason: '거래량 180% 급증', volumeRatio: 180 }
      ],
      SWING: [
        { stockCode: '005380', stockName: '현대차', currentPrice: 248000, changeRate: 1.22, score: 85, aiScore: 90, aiComment: '글로벌 EV 판매 1위 질주', aiThemes: '전기차,글로벌수출,저PER', originalScore: 81, rankNum: 1, reason: 'ROE 18.2%, 영업이익률 10.5%, PER 5.8배', per: 5.8, roe: 18.2, operatingMargin: 10.5 },
        { stockCode: '068270', stockName: '셀트리온', currentPrice: 198500, changeRate: 0.76, score: 78, aiScore: 82, aiComment: '바이오시밀러 매출 호조', aiThemes: '바이오시밀러,미국진출', originalScore: 75, rankNum: 2, reason: 'ROE 15.8%, PER 7.2배', per: 7.2, roe: 15.8, operatingMargin: 28.3 },
        { stockCode: '055550', stockName: '신한지주', currentPrice: 51200, changeRate: 0.39, score: 76, aiScore: 78, aiComment: '밸류업 프로그램 수혜', aiThemes: '금융밸류업,고배당', originalScore: 74, rankNum: 3, reason: 'ROE 10.2%, PER 4.8배', per: 4.8, roe: 10.2, operatingMargin: 35.1 },
        { stockCode: '003550', stockName: 'LG', currentPrice: 78900, changeRate: -0.25, score: 72, aiScore: 74, aiComment: 'LG엔솔 지분가치 재평가', aiThemes: '지주사할인,2차전지', originalScore: 70, rankNum: 4, reason: 'ROE 12.5%, PER 6.1배', per: 6.1, roe: 12.5, operatingMargin: 8.7 },
        { stockCode: '105560', stockName: 'KB금융', currentPrice: 82400, changeRate: 1.11, score: 70, aiScore: 72, aiComment: '자사주 매입 확대', aiThemes: '금융밸류업,자사주', originalScore: 68, rankNum: 5, reason: 'ROE 11.8%, PER 5.2배', per: 5.2, roe: 11.8, operatingMargin: 32.0 }
      ],
      TURNAROUND: [
        { stockCode: '003670', stockName: '포스코퓨처엠', currentPrice: 264500, changeRate: 2.33, score: 80, aiScore: 84, aiComment: '양극재 수주 급증, 흑자전환 확정', aiThemes: '실적턴어라운드,2차전지', originalScore: 77, rankNum: 1, reason: '적자→흑자 전환 성공', turnaroundType: 'LOSS_TO_PROFIT', netIncomeChangeRate: 999.99 },
        { stockCode: '247540', stockName: '에코프로비엠', currentPrice: 156000, changeRate: 1.85, score: 76, aiScore: 80, aiComment: '하이니켈 양극재 흑자 전환', aiThemes: '흑자전환,2차전지소재', originalScore: 73, rankNum: 2, reason: '적자→흑자 전환 성공', turnaroundType: 'LOSS_TO_PROFIT', netIncomeChangeRate: 999.99 },
        { stockCode: '034220', stockName: 'LG디스플레이', currentPrice: 13500, changeRate: 4.25, score: 73, aiScore: 77, aiComment: 'OLED 수요 급증, 이익 급성장', aiThemes: 'OLED,이익급증', originalScore: 70, rankNum: 3, reason: '순이익 320% 급증', turnaroundType: 'PROFIT_GROWTH', netIncomeChangeRate: 320 },
        { stockCode: '010120', stockName: 'LS일렉트릭', currentPrice: 185000, changeRate: 0.82, score: 70, aiScore: 73, aiComment: '전력인프라 수주 호조', aiThemes: '전력인프라,수주급증', originalScore: 68, rankNum: 4, reason: '순이익 185% 급증', turnaroundType: 'PROFIT_GROWTH', netIncomeChangeRate: 185 },
        { stockCode: '009150', stockName: '삼성전기', currentPrice: 158000, changeRate: -0.63, score: 67, aiScore: 70, aiComment: 'MLCC 사이클 반등', aiThemes: 'MLCC,실적반등', originalScore: 65, rankNum: 5, reason: '순이익 142% 급증', turnaroundType: 'PROFIT_GROWTH', netIncomeChangeRate: 142 }
      ],
      VALUE: [
        { stockCode: '032830', stockName: '삼성생명', currentPrice: 96800, changeRate: 0.52, score: 83, aiScore: 86, aiComment: '금리 안정화 수혜, PEG 극저평가', aiThemes: '저PEG,고배당,보험', originalScore: 81, rankNum: 1, reason: 'PEG 0.35, EPS성장 42%, ROE 12.8%', peg: 0.35, epsGrowth: 42, roe: 12.8, per: 4.5 },
        { stockCode: '086790', stockName: '하나금융지주', currentPrice: 64300, changeRate: 0.78, score: 79, aiScore: 82, aiComment: '밸류업 + 주주환원 확대', aiThemes: '저PEG,밸류업,자사주', originalScore: 77, rankNum: 2, reason: 'PEG 0.41, EPS성장 38%, ROE 11.5%', peg: 0.41, epsGrowth: 38, roe: 11.5, per: 4.2 },
        { stockCode: '034730', stockName: 'SK', currentPrice: 165000, changeRate: -0.30, score: 75, aiScore: 78, aiComment: 'AI 인프라 + SK하이닉스 지분가치', aiThemes: '지주사할인,AI인프라', originalScore: 73, rankNum: 3, reason: 'PEG 0.52, EPS성장 28%, ROE 9.5%', peg: 0.52, epsGrowth: 28, roe: 9.5, per: 7.8 },
        { stockCode: '012330', stockName: '현대모비스', currentPrice: 242000, changeRate: 1.46, score: 72, aiScore: 75, aiComment: '전장부품 성장 + 저평가', aiThemes: '전장부품,저PER', originalScore: 70, rankNum: 4, reason: 'PEG 0.58, EPS성장 25%', peg: 0.58, epsGrowth: 25, roe: 8.7, per: 6.5 },
        { stockCode: '316140', stockName: '우리금융지주', currentPrice: 16850, changeRate: 0.90, score: 69, aiScore: 72, aiComment: '배당수익률 7%+, 안정적 성장', aiThemes: '고배당,저PEG', originalScore: 67, rankNum: 5, reason: 'PEG 0.62, ROE 10.2%', peg: 0.62, epsGrowth: 22, roe: 10.2, per: 3.8 }
      ]
    },
    lastUpdated: {
      SCALPING: new Date().toISOString(),
      SWING: new Date().toISOString(),
      TURNAROUND: new Date().toISOString(),
      VALUE: new Date().toISOString()
    }
  }
}

function getFallbackSectorData() {
  return [
    { sectorName: '반도체', changeRate: 2.35, totalTradingValue: 4800000000000 },
    { sectorName: '2차전지', changeRate: 1.82, totalTradingValue: 3200000000000 },
    { sectorName: '제약/바이오', changeRate: -1.45, totalTradingValue: 2800000000000 },
    { sectorName: '자동차', changeRate: 0.95, totalTradingValue: 2500000000000 },
    { sectorName: '금융', changeRate: 0.67, totalTradingValue: 2200000000000 },
    { sectorName: '철강/소재', changeRate: -0.38, totalTradingValue: 1800000000000 },
    { sectorName: '조선', changeRate: 2.88, totalTradingValue: 1600000000000 },
    { sectorName: '건설', changeRate: -2.12, totalTradingValue: 1200000000000 },
    { sectorName: '유틸리티', changeRate: 0.22, totalTradingValue: 900000000000 },
    { sectorName: '로봇/AI', changeRate: 3.15, totalTradingValue: 3500000000000 }
  ]
}

function getFallbackMarketData() {
  return {
    kospiIndex: '2,687.45',
    kospiChangeRate: 0.82,
    kosdaqIndex: '843.21',
    kosdaqChangeRate: -0.35,
    adr: 94.5,
    marketStatus: '코스피 상승 · 코스닥 소폭 하락 · 시장 심리 보통'
  }
}

function getFallbackGlobalData() {
  return {
    nasdaqFutures: { price: '21,345.50', changeRate: 0.42 },
    leadingSectors: [
      { sectorName: '반도체/AI', changeRate: 2.1 },
      { sectorName: '조선/방산', changeRate: 1.8 }
    ]
  }
}

function getFallbackSmartMoneyForeign() {
  return [
    { stockCode: '005930', stockName: '삼성전자', netBuyAmount: 85200000000, rankChange: 0 },
    { stockCode: '000660', stockName: 'SK하이닉스', netBuyAmount: 62300000000, rankChange: 1 },
    { stockCode: '005380', stockName: '현대차', netBuyAmount: 34500000000, rankChange: -1 },
    { stockCode: '068270', stockName: '셀트리온', netBuyAmount: 28700000000, rankChange: 2 },
    { stockCode: '035420', stockName: 'NAVER', netBuyAmount: 21800000000, rankChange: 0 },
    { stockCode: '055550', stockName: '신한지주', netBuyAmount: 18500000000, rankChange: 3 },
    { stockCode: '105560', stockName: 'KB금융', netBuyAmount: 15200000000, rankChange: -2 },
    { stockCode: '003550', stockName: 'LG', netBuyAmount: 12800000000, rankChange: 1 },
    { stockCode: '006400', stockName: '삼성SDI', netBuyAmount: 10500000000, rankChange: -1 },
    { stockCode: '051910', stockName: 'LG화학', netBuyAmount: 8900000000, rankChange: 0 }
  ]
}

function getFallbackSmartMoneyInstitution() {
  return [
    { stockCode: '000660', stockName: 'SK하이닉스', netBuyAmount: 45600000000, rankChange: 1 },
    { stockCode: '005930', stockName: '삼성전자', netBuyAmount: 38200000000, rankChange: -1 },
    { stockCode: '035420', stockName: 'NAVER', netBuyAmount: 22100000000, rankChange: 2 },
    { stockCode: '005380', stockName: '현대차', netBuyAmount: 19800000000, rankChange: 0 },
    { stockCode: '032830', stockName: '삼성생명', netBuyAmount: 15400000000, rankChange: 3 },
    { stockCode: '003670', stockName: '포스코퓨처엠', netBuyAmount: 12700000000, rankChange: -2 },
    { stockCode: '086790', stockName: '하나금융지주', netBuyAmount: 10200000000, rankChange: 1 },
    { stockCode: '010120', stockName: 'LS일렉트릭', netBuyAmount: 8500000000, rankChange: 4 },
    { stockCode: '009150', stockName: '삼성전기', netBuyAmount: 7200000000, rankChange: -1 },
    { stockCode: '034220', stockName: 'LG디스플레이', netBuyAmount: 5800000000, rankChange: 0 }
  ]
}

function getFallbackConsecutiveBuy() {
  return [
    { stockCode: '005930', stockName: '삼성전자', consecutiveDays: 12, investorType: 'FOREIGN' },
    { stockCode: '000660', stockName: 'SK하이닉스', consecutiveDays: 8, investorType: 'FOREIGN' },
    { stockCode: '005380', stockName: '현대차', consecutiveDays: 7, investorType: 'INSTITUTION' },
    { stockCode: '055550', stockName: '신한지주', consecutiveDays: 6, investorType: 'FOREIGN' },
    { stockCode: '003670', stockName: '포스코퓨처엠', consecutiveDays: 5, investorType: 'INSTITUTION' },
    { stockCode: '068270', stockName: '셀트리온', consecutiveDays: 5, investorType: 'FOREIGN' },
    { stockCode: '105560', stockName: 'KB금융', consecutiveDays: 4, investorType: 'INSTITUTION' },
    { stockCode: '035420', stockName: 'NAVER', consecutiveDays: 4, investorType: 'FOREIGN' },
    { stockCode: '010120', stockName: 'LS일렉트릭', consecutiveDays: 3, investorType: 'INSTITUTION' },
    { stockCode: '086790', stockName: '하나금융지주', consecutiveDays: 3, investorType: 'FOREIGN' }
  ]
}

function getFallbackSurgeStocks() {
  return [
    { stockCode: '005930', stockName: '삼성전자', changeRate: 2.45, surgeRatio: 320 },
    { stockCode: '003670', stockName: '포스코퓨처엠', changeRate: 2.33, surgeRatio: 280 },
    { stockCode: '034220', stockName: 'LG디스플레이', changeRate: 4.25, surgeRatio: 250 },
    { stockCode: '000660', stockName: 'SK하이닉스', changeRate: 3.12, surgeRatio: 220 },
    { stockCode: '010120', stockName: 'LS일렉트릭', changeRate: 0.82, surgeRatio: 195 },
    { stockCode: '247540', stockName: '에코프로비엠', changeRate: 1.85, surgeRatio: 180 },
    { stockCode: '068270', stockName: '셀트리온', changeRate: 0.76, surgeRatio: 165 },
    { stockCode: '012330', stockName: '현대모비스', changeRate: 1.46, surgeRatio: 155 },
    { stockCode: '055550', stockName: '신한지주', changeRate: 0.39, surgeRatio: 140 },
    { stockCode: '035720', stockName: '카카오', changeRate: 0.95, surgeRatio: 130 }
  ]
}

function getFallbackScreenerData() {
  return {
    magicFormula: [
      { stockCode: '005380', stockName: '현대차', per: 5.8, pbr: 0.65, roe: 18.2, operatingMargin: 10.5, magicFormulaRank: 1 },
      { stockCode: '055550', stockName: '신한지주', per: 4.8, pbr: 0.48, roe: 10.2, operatingMargin: 35.1, magicFormulaRank: 2 },
      { stockCode: '105560', stockName: 'KB금융', per: 5.2, pbr: 0.52, roe: 11.8, operatingMargin: 32.0, magicFormulaRank: 3 }
    ],
    lowPeg: [
      { stockCode: '032830', stockName: '삼성생명', peg: 0.35, per: 4.5, epsGrowth: 42, roe: 12.8 },
      { stockCode: '086790', stockName: '하나금융지주', peg: 0.41, per: 4.2, epsGrowth: 38, roe: 11.5 },
      { stockCode: '034730', stockName: 'SK', peg: 0.52, per: 7.8, epsGrowth: 28, roe: 9.5 }
    ],
    turnaround: [
      { stockCode: '003670', stockName: '포스코퓨처엠', turnaroundType: 'LOSS_TO_PROFIT', per: 45.2, netIncomeChangeRate: 999.99 },
      { stockCode: '247540', stockName: '에코프로비엠', turnaroundType: 'LOSS_TO_PROFIT', per: 52.1, netIncomeChangeRate: 999.99 },
      { stockCode: '034220', stockName: 'LG디스플레이', turnaroundType: 'PROFIT_GROWTH', per: 8.5, netIncomeChangeRate: 320 }
    ]
  }
}

function getFallbackNewsData() {
  const today = new Date().toISOString()
  return [
    { title: '삼성전자, HBM3E 양산 본격화...엔비디아 공급 확대', summary: 'AI 반도체 수요 급증에 따라 삼성전자의 고대역폭메모리(HBM) 양산이 가속화되고 있다.', source: '한국경제', publishedAt: today, sentiment: '긍정' },
    { title: '코스피 2,690 돌파...반도체·조선주 강세', summary: '외국인 매수세가 유입되며 코스피가 이틀 연속 상승했다. 반도체와 조선 업종이 상승을 주도했다.', source: '매일경제', publishedAt: today, sentiment: '긍정' },
    { title: '미 연준 금리 동결...9월 인하 가능성 시사', summary: '제롬 파월 의장은 인플레이션 둔화 추세를 확인하며 올해 안에 금리 인하 가능성을 열어두었다.', source: '연합뉴스', publishedAt: today, sentiment: '긍정' },
    { title: '2차전지 소재주, 유럽 전기차 보조금 확대에 반등', summary: '유럽연합이 전기차 보조금을 확대하기로 결정하면서 관련 소재주들이 동반 상승했다.', source: '서울경제', publishedAt: today, sentiment: '긍정' },
    { title: '중국 경기 둔화 우려...제약바이오 업종 차익 실현', summary: '중국 PMI 지표 부진으로 아시아 증시가 혼조세를 보인 가운데 바이오 업종은 차익 실현 매물이 출회됐다.', source: '조선비즈', publishedAt: today, sentiment: '부정' }
  ]
}

// ===================== 메인 컴포넌트 =====================

export default {
  name: 'StockTradingDashboardV2',
  components: {
    DashboardHeader,
    SectionAiStrategy,
    SectionMarketMap,
    SectionSmartMoney,
    SectionResearch,
    StockSearchModal
  },
  data() {
    return {
      showSearch: false,
      sections: {
        aiStrategy: { loading: true, error: false },
        marketMap: { loading: true, error: false },
        smartMoney: { loading: true, error: false },
        research: { loading: true, error: false }
      },
      aiStrategyData: null,
      sectorData: [],
      marketData: {},
      globalData: {},
      tradesData: { foreign: [], institution: [] },
      consecutiveData: [],
      surgeData: [],
      screenerData: {},
      newsData: []
    }
  },
  mounted() {
    this.loadAllSections()
    this.setupKeyboardShortcut()
  },
  beforeUnmount() {
    this.removeKeyboardShortcut()
  },
  methods: {
    async loadAllSections() {
      await Promise.allSettled([
        this.loadAiStrategy(),
        this.loadMarketMap(),
        this.loadSmartMoney(),
        this.loadResearch()
      ])
    },

    // ---- helpers ----
    extractData(res) {
      if (!res?.data) return null
      return res.data.data !== undefined ? res.data.data : res.data
    },
    hasData(d) {
      if (!d) return false
      if (Array.isArray(d)) return d.length > 0
      if (typeof d === 'object') return Object.keys(d).length > 0
      return true
    },

    // Section A
    async loadAiStrategy() {
      try {
        this.sections.aiStrategy.loading = true
        this.sections.aiStrategy.error = false
        const res = await aiStrategyAPI.getLatest()
        const d = this.extractData(res)
        // strategies 키 안에 배열이 비어있어도 폴백
        const hasStocks = d?.strategies && Object.values(d.strategies).some(arr => arr && arr.length > 0)
        this.aiStrategyData = hasStocks ? d : getFallbackAiStrategy()
      } catch {
        this.aiStrategyData = getFallbackAiStrategy()
      } finally {
        this.sections.aiStrategy.loading = false
      }
    },

    // Section B
    async loadMarketMap() {
      try {
        this.sections.marketMap.loading = true
        this.sections.marketMap.error = false
        const [sectorRes, marketRes, leadingRes, nasdaqRes] = await Promise.allSettled([
          sectorAPI.getSectorTrading('TODAY'),
          marketAPI.getStatus(),
          tradingIndicatorAPI.getLeadingSectors(),
          tradingIndicatorAPI.getNasdaqFutures()
        ])
        // Sector
        if (sectorRes.status === 'fulfilled') {
          const d = this.extractData(sectorRes.value)
          const arr = Array.isArray(d) ? d : (d?.sectors || [])
          this.sectorData = arr.length > 0 ? arr : getFallbackSectorData()
        } else {
          this.sectorData = getFallbackSectorData()
        }
        // Market
        if (marketRes.status === 'fulfilled') {
          const d = this.extractData(marketRes.value)
          this.marketData = (d && d.kospiIndex) ? d : getFallbackMarketData()
        } else {
          this.marketData = getFallbackMarketData()
        }
        // Global
        this.globalData = {}
        if (nasdaqRes.status === 'fulfilled') {
          const d = this.extractData(nasdaqRes.value)
          this.globalData.nasdaqFutures = (d && d.price) ? d : getFallbackGlobalData().nasdaqFutures
        } else {
          this.globalData.nasdaqFutures = getFallbackGlobalData().nasdaqFutures
        }
        if (leadingRes.status === 'fulfilled') {
          const d = this.extractData(leadingRes.value)
          this.globalData.leadingSectors = Array.isArray(d) && d.length > 0 ? d : getFallbackGlobalData().leadingSectors
        } else {
          this.globalData.leadingSectors = getFallbackGlobalData().leadingSectors
        }
      } catch {
        this.sectorData = getFallbackSectorData()
        this.marketData = getFallbackMarketData()
        this.globalData = getFallbackGlobalData()
      } finally {
        this.sections.marketMap.loading = false
      }
    },

    // Section C
    async loadSmartMoney() {
      try {
        this.sections.smartMoney.loading = true
        this.sections.smartMoney.error = false
        const [foreignRes, instRes, consecutiveRes, surgeRes] = await Promise.allSettled([
          investorAPI.getTopTrades('FOREIGN', 'BUY', 10),
          investorAPI.getTopTrades('INSTITUTION', 'BUY', 10),
          investorAPI.getAllConsecutiveBuy(3),
          investorAPI.getAllSurgeStocks()
        ])
        // Foreign
        const fd = foreignRes.status === 'fulfilled' ? this.extractData(foreignRes.value) : null
        this.tradesData.foreign = (Array.isArray(fd) && fd.length > 0) ? fd : getFallbackSmartMoneyForeign()
        // Institution
        const id = instRes.status === 'fulfilled' ? this.extractData(instRes.value) : null
        this.tradesData.institution = (Array.isArray(id) && id.length > 0) ? id : getFallbackSmartMoneyInstitution()
        // Consecutive
        const cd = consecutiveRes.status === 'fulfilled' ? this.extractData(consecutiveRes.value) : null
        this.consecutiveData = (Array.isArray(cd) && cd.length > 0) ? cd : getFallbackConsecutiveBuy()
        // Surge
        const sd = surgeRes.status === 'fulfilled' ? this.extractData(surgeRes.value) : null
        this.surgeData = (Array.isArray(sd) && sd.length > 0) ? sd : getFallbackSurgeStocks()
      } catch {
        this.tradesData.foreign = getFallbackSmartMoneyForeign()
        this.tradesData.institution = getFallbackSmartMoneyInstitution()
        this.consecutiveData = getFallbackConsecutiveBuy()
        this.surgeData = getFallbackSurgeStocks()
      } finally {
        this.sections.smartMoney.loading = false
      }
    },

    // Section D
    async loadResearch() {
      try {
        this.sections.research.loading = true
        this.sections.research.error = false
        const [screenerRes, newsRes] = await Promise.allSettled([
          screenerAPI.getSummary(),
          newsAPI.getTodayNews()
        ])
        // Screener
        const sd = screenerRes.status === 'fulfilled' ? this.extractData(screenerRes.value) : null
        const hasScreener = sd && (sd.magicFormula?.length || sd.lowPeg?.length || sd.turnaround?.length)
        this.screenerData = hasScreener ? sd : getFallbackScreenerData()
        // News
        const nd = newsRes.status === 'fulfilled' ? this.extractData(newsRes.value) : null
        this.newsData = (Array.isArray(nd) && nd.length > 0) ? nd : getFallbackNewsData()
      } catch {
        this.screenerData = getFallbackScreenerData()
        this.newsData = getFallbackNewsData()
      } finally {
        this.sections.research.loading = false
      }
    },

    onStockSelect(stock) {
      if (stock?.stockCode) {
        this.$router.push(`/stock/${stock.stockCode}`)
      }
    },

    setupKeyboardShortcut() {
      this._onKeydown = (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
          e.preventDefault()
          this.showSearch = true
        }
        if (e.key === 'Escape') {
          this.showSearch = false
        }
      }
      window.addEventListener('keydown', this._onKeydown)
    },

    removeKeyboardShortcut() {
      window.removeEventListener('keydown', this._onKeydown)
    }
  }
}
</script>

<style scoped>
.v2-dashboard {
  min-height: 100vh;
  background: linear-gradient(180deg, #0f0f1a 0%, #1a1a2e 40%, #16213e 100%);
  color: white;
}

.v2-content {
  max-width: 1400px;
  margin: 0 auto;
  padding: 20px 24px 60px;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

@media (max-width: 1024px) {
  .dashboard-grid { grid-template-columns: 1fr; }
}

@media (max-width: 768px) {
  .v2-content { padding: 12px 16px 40px; }
  .dashboard-grid { gap: 14px; }
}
</style>
