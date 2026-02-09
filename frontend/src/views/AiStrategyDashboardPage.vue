<template>
  <div class="ai-dashboard">
    <div class="content-wrapper">
      <!-- 헤더 -->
      <div class="page-header">
        <BackButton />
        <div class="header-content">
          <h1>AI 트레이딩 전략</h1>
          <p class="subtitle">4분할 투자 전략 | 기간별 맞춤 추천</p>
        </div>
        <div class="header-meta">
          <span class="update-badge">
            <span class="pulse-dot"></span>
            {{ formatTime(lastUpdated) }} 기준
          </span>
        </div>
      </div>

      <!-- 4분할 전략 점수 패널 -->
      <div class="score-panel">
        <div class="score-panel-header">
          <span class="panel-icon">📊</span>
          <h2>4분할 투자 전략 점수</h2>
        </div>
        <div class="score-panel-content">
          <div class="strategy-scores">
            <div class="score-item" @click="activeTab = 'scalping'">
              <span class="score-label">⚡ 스캘핑</span>
              <div class="score-bar-wrapper">
                <div class="score-bar">
                  <div class="score-fill scalping" :style="{ width: strategyScores.scalping + '%' }"></div>
                </div>
              </div>
              <span class="score-number">{{ strategyScores.scalping }}</span>
            </div>
            <div class="score-item" @click="activeTab = 'swing'">
              <span class="score-label">📈 스윙</span>
              <div class="score-bar-wrapper">
                <div class="score-bar">
                  <div class="score-fill swing" :style="{ width: strategyScores.swing + '%' }"></div>
                </div>
              </div>
              <span class="score-number">{{ strategyScores.swing }}</span>
            </div>
            <div class="score-item" @click="activeTab = 'trend'">
              <span class="score-label">🔄 턴어라운드</span>
              <div class="score-bar-wrapper">
                <div class="score-bar">
                  <div class="score-fill turnaround" :style="{ width: strategyScores.turnaround + '%' }"></div>
                </div>
              </div>
              <span class="score-number">{{ strategyScores.turnaround }}</span>
            </div>
            <div class="score-item" @click="activeTab = 'value'">
              <span class="score-label">💎 가치투자</span>
              <div class="score-bar-wrapper">
                <div class="score-bar">
                  <div class="score-fill value" :style="{ width: strategyScores.value + '%' }"></div>
                </div>
              </div>
              <span class="score-number">{{ strategyScores.value }}</span>
            </div>
          </div>
          <div class="total-score-section">
            <div class="total-score-box">
              <span class="total-label">AI 종합 투자 매력도</span>
              <span class="total-value">{{ totalScore }}</span>
            </div>
            <div class="total-opinion-box" :class="opinionClass">
              {{ totalOpinion }}
            </div>
          </div>
        </div>
      </div>

      <!-- 전략 탭 -->
      <div class="strategy-tabs">
        <button
          v-for="tab in strategyTabs"
          :key="tab.key"
          :class="['tab-btn', { active: activeTab === tab.key }]"
          @click="activeTab = tab.key"
        >
          <span class="tab-icon">{{ tab.icon }}</span>
          <span class="tab-label">{{ tab.label }}</span>
          <span class="tab-period">{{ tab.period }}</span>
        </button>
      </div>

      <!-- 전략 설명 -->
      <div class="strategy-description">
        <div class="strategy-info">
          <span class="strategy-icon">{{ currentStrategy.icon }}</span>
          <div class="strategy-text">
            <h3>{{ currentStrategy.title }}</h3>
            <p>{{ currentStrategy.description }}</p>
          </div>
        </div>
        <div class="strategy-criteria">
          <span class="criteria-label">선정 기준:</span>
          <span class="criteria-value">{{ currentStrategy.criteria }}</span>
        </div>
      </div>

      <!-- 추천 종목 리스트 -->
      <div class="recommendations-section">
        <h2>오늘의 추천 TOP 5</h2>

        <!-- 로딩 상태 -->
        <div v-if="loading" class="loading-state">
          <div class="loading-spinner"></div>
          <p>실시간 데이터 분석 중...</p>
        </div>

        <!-- 데이터 없음 상태 -->
        <div v-else-if="currentRecommendations.length === 0" class="empty-state">
          <span class="empty-icon">📊</span>
          <p>현재 조건에 맞는 추천 종목이 없습니다.</p>
          <p class="empty-hint">데이터 수집 시간 또는 시장 상황에 따라 종목이 표시됩니다.</p>
        </div>

        <div v-else class="recommendations-grid">
          <div
            v-for="(stock, index) in currentRecommendations"
            :key="stock.stockCode"
            :class="['recommendation-card', { 'top-pick': index === 0 }]"
          >
            <!-- 순위 배지 -->
            <div class="rank-badge" :class="getRankClass(index)">
              {{ index === 0 ? '👑' : '#' + (index + 1) }}
            </div>

            <!-- 종목 정보 -->
            <div class="stock-info">
              <div class="stock-header">
                <h3 class="stock-name">{{ stock.stockName }}</h3>
                <span class="stock-code">{{ stock.stockCode }}</span>
              </div>

              <!-- AI 추천 사유 뱃지들 -->
              <div class="reason-badges">
                <span
                  v-for="reason in stock.reasons"
                  :key="reason"
                  class="reason-badge"
                  :class="getReasonClass(reason)"
                >
                  {{ reason }}
                </span>
              </div>

              <!-- 현재가 정보 -->
              <div class="price-info" :class="{ 'flash-up': stock.priceFlash === 'up', 'flash-down': stock.priceFlash === 'down' }">
                <span class="current-price">{{ formatNumber(stock.currentPrice) }}원</span>
                <span class="change-rate" :class="stock.changeRate >= 0 ? 'positive' : 'negative'">
                  {{ stock.changeRate >= 0 ? '+' : '' }}{{ formatChangeRate(stock.changeRate) }}%
                </span>
              </div>
            </div>

            <!-- AI 제안 영역 -->
            <div class="ai-suggestion">
              <div class="suggestion-row target">
                <span class="suggestion-label">기대 수익률</span>
                <span class="suggestion-value positive">+{{ stock.expectedReturn }}%</span>
              </div>
              <div class="suggestion-row stoploss">
                <span class="suggestion-label">손절가</span>
                <span class="suggestion-value negative">{{ formatNumber(stock.stopLoss) }}원 (-{{ stock.stopLossPercent }}%)</span>
              </div>
              <div class="suggestion-row holding">
                <span class="suggestion-label">예상 보유</span>
                <span class="suggestion-value">{{ stock.holdingPeriod }}</span>
              </div>
            </div>

            <!-- 핵심 지표 -->
            <div class="key-metrics">
              <div v-for="metric in stock.keyMetrics" :key="metric.label" class="metric-item">
                <span class="metric-label">{{ metric.label }}</span>
                <span class="metric-value" :class="metric.class">{{ metric.value }}</span>
              </div>
            </div>

            <!-- 상세보기 버튼 -->
            <button class="detail-btn" @click="goToDetail(stock.stockCode)">
              상세 분석 보기
            </button>
          </div>
        </div>
      </div>

      <!-- 시장 요약 -->
      <div class="market-summary">
        <h2>오늘의 시장 요약</h2>
        <div class="summary-cards">
          <div class="summary-card">
            <span class="summary-icon">📊</span>
            <div class="summary-content">
              <span class="summary-label">시장 분위기</span>
              <span class="summary-value" :class="marketSentiment.class">{{ marketSentiment.text }}</span>
            </div>
          </div>
          <div class="summary-card">
            <span class="summary-icon">🌍</span>
            <div class="summary-content">
              <span class="summary-label">외국인 동향</span>
              <span class="summary-value" :class="foreignFlow > 0 ? 'positive' : 'negative'">
                {{ foreignFlow > 0 ? '순매수' : '순매도' }} {{ Math.abs(foreignFlow).toLocaleString() }}억
              </span>
            </div>
          </div>
          <div class="summary-card">
            <span class="summary-icon">🏢</span>
            <div class="summary-content">
              <span class="summary-label">기관 동향</span>
              <span class="summary-value" :class="institutionFlow > 0 ? 'positive' : 'negative'">
                {{ institutionFlow > 0 ? '순매수' : '순매도' }} {{ Math.abs(institutionFlow).toLocaleString() }}억
              </span>
            </div>
          </div>
          <div class="summary-card">
            <span class="summary-icon">🔥</span>
            <div class="summary-content">
              <span class="summary-label">주도 섹터</span>
              <span class="summary-value">{{ leadingSector }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 면책 조항 -->
      <div class="disclaimer">
        <p>본 추천은 AI 알고리즘에 의한 참고 자료이며, 투자 결정은 본인의 판단과 책임 하에 이루어져야 합니다.</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import BackButton from '../components/BackButton.vue';
import { investorAPI, screenerAPI, marketAPI, tradingIndicatorAPI, stockAPI } from '../utils/api';

const router = useRouter();
const activeTab = ref('scalping');
const lastUpdated = ref(new Date());
const loading = ref(false);

// 4분할 전략 점수 (API 데이터 기반 계산)
const strategyScores = ref({
  scalping: 0,
  swing: 0,
  turnaround: 0,
  value: 0
});

// 종합 점수 계산
const totalScore = computed(() => {
  const scores = strategyScores.value;
  return Math.round((scores.scalping + scores.swing + scores.turnaround + scores.value) / 4);
});

// 종합 의견
const totalOpinion = computed(() => {
  const score = totalScore.value;
  if (score >= 80) return '적극 매수';
  if (score >= 70) return '매수';
  if (score >= 55) return '관망';
  return '매도';
});

// 의견 클래스
const opinionClass = computed(() => {
  const score = totalScore.value;
  if (score >= 80) return 'strong-buy';
  if (score >= 70) return 'buy';
  if (score >= 55) return 'hold';
  return 'sell';
});

// 전략 탭 정의
const strategyTabs = [
  { key: 'scalping', icon: '⚡', label: '초단타', period: '분~시간' },
  { key: 'swing', icon: '📈', label: '단기스윙', period: '1~5일' },
  { key: 'trend', icon: '🔄', label: '중기추세', period: '1~4주' },
  { key: 'value', icon: '💎', label: '장기투자', period: '1개월+' }
];

// 전략별 설명
const strategyDescriptions = {
  scalping: {
    icon: '⚡',
    title: '초단타 전략 (Scalping)',
    description: '실시간 체결강도와 수급 급증을 포착하여 단기 모멘텀을 노립니다.',
    criteria: '체결강도 120%+ & 실시간 수급 급증(Hot) 종목'
  },
  swing: {
    icon: '📈',
    title: '단기스윙 전략 (Swing Trading)',
    description: '기관/외국인의 연속 매수 종목 중 눌림목 구간을 포착합니다.',
    criteria: '3일+ 연속 매수 & 단기 조정(눌림목) 구간'
  },
  trend: {
    icon: '🔄',
    title: '중기추세 전략 (Trend Following)',
    description: '펀더멘털 개선(흑자전환)과 기술적 지지를 동시에 충족하는 종목입니다.',
    criteria: '턴어라운드(흑자전환) & VWAP 상단 위치'
  },
  value: {
    icon: '💎',
    title: '장기가치 전략 (Value Investing)',
    description: '저평가 우량주 중 배당과 수익성이 뛰어난 종목에 투자합니다.',
    criteria: 'PEG 상위(저평가) & 고배당/고ROE'
  }
};

const currentStrategy = computed(() => strategyDescriptions[activeTab.value]);

// ========== 실제 추천 데이터 저장소 ==========
const recommendations = ref({
  scalping: [],
  swing: [],
  trend: [],
  value: []
});

const currentRecommendations = computed(() => recommendations.value[activeTab.value] || []);

// 시장 요약 데이터
const marketSentiment = ref({ text: '조회 중...', class: '' });
const foreignFlow = ref(0);
const institutionFlow = ref(0);
const leadingSector = ref('-');

// ========== API 데이터 로드 ==========
const loadScalpingData = async () => {
  try {
    // 수급 급증 종목 (외국인+기관 공통)
    const response = await investorAPI.getCommonSurgeStocks();
    if (response.data.success && response.data.data) {
      const surgeStocks = response.data.data.slice(0, 5);

      // 각 종목별 스캘핑 점수 계산
      const stocksWithScore = surgeStocks.map(stock => {
        // 수급강도 점수: 외국인+기관 순매수 합계 기반 (억원 단위)
        const supplyScore = Math.min(50, ((stock.foreignNetBuy || 0) + (stock.institutionNetBuy || 0)) / 10);
        // 모멘텀 점수: 등락률 기반
        const momentumScore = Math.min(30, Math.max(0, (stock.changeRate || 0) * 5));
        // 체결강도 가정 점수: 수급 급증이면 체결강도 높을 것으로 추정
        const volumeScore = 20;
        const score = Math.min(100, Math.round(supplyScore + momentumScore + volumeScore));

        return {
          stockCode: stock.stockCode,
          stockName: stock.stockName,
          currentPrice: stock.currentPrice || 0,
          previousClose: stock.previousClose || stock.currentPrice || 0,
          changeRate: stock.changeRate || 0,
          priceFlash: null,
          reasons: buildScalpingReasons(stock),
          expectedReturn: 3.0,
          stopLoss: Math.round((stock.currentPrice || 0) * 0.97),
          stopLossPercent: 3.0,
          holdingPeriod: '30분~2시간',
          score: score,
          keyMetrics: [
            { label: '외국인', value: formatAmount(stock.foreignNetBuy), class: stock.foreignNetBuy > 0 ? 'positive' : 'negative' },
            { label: '기관', value: formatAmount(stock.institutionNetBuy), class: stock.institutionNetBuy > 0 ? 'positive' : 'negative' },
            { label: '점수', value: `${score}점`, class: score >= 70 ? 'positive' : (score >= 50 ? 'neutral' : 'negative') }
          ]
        };
      });

      recommendations.value.scalping = stocksWithScore;

      // 전략 점수: 상위 종목들의 평균 점수
      if (stocksWithScore.length > 0) {
        const avgScore = stocksWithScore.reduce((sum, s) => sum + s.score, 0) / stocksWithScore.length;
        strategyScores.value.scalping = Math.round(avgScore);
      } else {
        strategyScores.value.scalping = 0;
      }

      // 실시간 시세 데이터 병합 (비동기)
      fetchRealTimeQuotes('scalping');
    }
  } catch (error) {
    console.error('스캘핑 데이터 로드 오류:', error);
    strategyScores.value.scalping = 0;
  }
};

const loadSwingData = async () => {
  try {
    // 연속 매수 종목
    const response = await investorAPI.getAllConsecutiveBuy(3);
    if (response.data.success && response.data.data) {
      const foreignStocks = response.data.data.FOREIGN || [];
      const institutionStocks = response.data.data.INSTITUTION || [];

      // 외국인/기관 합쳐서 누적 순매수 상위 5종목
      const allStocks = [...foreignStocks, ...institutionStocks]
        .sort((a, b) => (b.totalNetBuyAmount || 0) - (a.totalNetBuyAmount || 0))
        .slice(0, 5);

      // 각 종목별 스윙 점수 계산: (연속매수일수 × 10) + (등락률 보정)
      const stocksWithScore = allStocks.map(stock => {
        // 연속매수일수 점수 (최대 50점)
        const consecutiveScore = Math.min(50, (stock.consecutiveDays || 0) * 10);
        // 눌림목일 때 가산점 (조정 구간이면 매수 기회)
        const pullbackBonus = stock.changeRate < 0 ? Math.min(20, Math.abs(stock.changeRate) * 5) : 0;
        // 누적 매수금액 점수 (최대 30점)
        const amountScore = Math.min(30, (stock.totalNetBuyAmount || 0) / 50);
        const score = Math.min(100, Math.round(consecutiveScore + pullbackBonus + amountScore));

        return {
          stockCode: stock.stockCode,
          stockName: stock.stockName,
          currentPrice: stock.currentPrice || 0,
          previousClose: stock.previousClose || stock.currentPrice || 0,
          changeRate: stock.changeRate || 0,
          priceFlash: null,
          reasons: buildSwingReasons(stock),
          expectedReturn: 7.0,
          stopLoss: Math.round((stock.currentPrice || 0) * 0.95),
          stopLossPercent: 5.0,
          holdingPeriod: '3~5일',
          score: score,
          keyMetrics: [
            { label: '연속매수', value: `${stock.consecutiveDays}일`, class: 'positive' },
            { label: '누적금액', value: formatAmount(stock.totalNetBuyAmount), class: 'positive' },
            { label: '점수', value: `${score}점`, class: score >= 70 ? 'positive' : (score >= 50 ? 'neutral' : 'negative') }
          ]
        };
      });

      recommendations.value.swing = stocksWithScore;

      // 전략 점수: 상위 종목들의 평균 점수
      if (stocksWithScore.length > 0) {
        const avgScore = stocksWithScore.reduce((sum, s) => sum + s.score, 0) / stocksWithScore.length;
        strategyScores.value.swing = Math.round(avgScore);
      } else {
        strategyScores.value.swing = 0;
      }

      // 실시간 시세 데이터 병합 (비동기)
      fetchRealTimeQuotes('swing');
    }
  } catch (error) {
    console.error('스윙 데이터 로드 오류:', error);
    strategyScores.value.swing = 0;
  }
};

const loadTrendData = async () => {
  try {
    // 턴어라운드 종목
    const response = await screenerAPI.getTurnaroundStocks(5);
    if (response.data.success && response.data.data) {
      const turnaroundStocks = response.data.data.slice(0, 5);

      // 각 종목별 턴어라운드 점수 계산: 흑자전환이면 기본 80점 + 기술적 점수
      const stocksWithScore = turnaroundStocks.map(stock => {
        // 흑자전환 기본 점수
        const profitBase = stock.netIncome > 0 ? 80 : 50;
        // 영업이익률 가산점 (최대 10점)
        const marginBonus = Math.min(10, Math.max(0, (stock.operatingMargin || 0)));
        // ROE 가산점 (최대 10점)
        const roeBonus = Math.min(10, Math.max(0, (stock.roe || 0) / 2));
        const score = Math.min(100, Math.round(profitBase + marginBonus + roeBonus));

        return {
          stockCode: stock.stockCode,
          stockName: stock.stockName,
          currentPrice: stock.currentPrice || 0,
          previousClose: stock.previousClose || stock.currentPrice || 0,
          changeRate: stock.changeRate || 0,
          priceFlash: null,
          reasons: buildTrendReasons(stock),
          expectedReturn: 20.0,
          stopLoss: Math.round((stock.currentPrice || 0) * 0.88),
          stopLossPercent: 12.0,
          holdingPeriod: '2~4주',
          score: score,
          keyMetrics: [
            { label: '순이익', value: stock.netIncome > 0 ? '흑자전환' : '개선중', class: stock.netIncome > 0 ? 'positive' : 'neutral' },
            { label: '영업이익률', value: stock.operatingMargin ? `${stock.operatingMargin.toFixed(1)}%` : '-', class: stock.operatingMargin > 0 ? 'positive' : 'neutral' },
            { label: '점수', value: `${score}점`, class: score >= 70 ? 'positive' : (score >= 50 ? 'neutral' : 'negative') }
          ]
        };
      });

      recommendations.value.trend = stocksWithScore;

      // 전략 점수: 상위 종목들의 평균 점수
      if (stocksWithScore.length > 0) {
        const avgScore = stocksWithScore.reduce((sum, s) => sum + s.score, 0) / stocksWithScore.length;
        strategyScores.value.turnaround = Math.round(avgScore);
      } else {
        strategyScores.value.turnaround = 0;
      }

      // 실시간 시세 데이터 병합 (비동기)
      fetchRealTimeQuotes('trend');
    }
  } catch (error) {
    console.error('턴어라운드 데이터 로드 오류:', error);
    strategyScores.value.turnaround = 0;
  }
};

const loadValueData = async () => {
  try {
    // PEG 저평가 종목
    const response = await screenerAPI.getLowPegStocks(5, 1.0, 10);
    if (response.data.success && response.data.data) {
      const pegStocks = response.data.data.slice(0, 5);

      // 각 종목별 가치투자 점수 계산: (1 / PEG) * 50 + ROE 보정
      const stocksWithScore = pegStocks.map(stock => {
        // PEG 점수: (1 / PEG) * 50 (PEG가 낮을수록 고득점, 최대 70점)
        const peg = stock.peg || 1;
        const pegScore = Math.min(70, Math.round((1 / Math.max(0.1, peg)) * 50));
        // ROE 가산점 (최대 20점)
        const roeBonus = Math.min(20, Math.max(0, (stock.roe || 0)));
        // 저PER 가산점 (최대 10점)
        const perBonus = stock.per && stock.per < 10 ? Math.min(10, 10 - stock.per) : 0;
        const score = Math.min(100, Math.round(pegScore + roeBonus + perBonus));

        return {
          stockCode: stock.stockCode,
          stockName: stock.stockName,
          currentPrice: stock.currentPrice || 0,
          previousClose: stock.previousClose || stock.currentPrice || 0,
          changeRate: stock.changeRate || 0,
          priceFlash: null,
          reasons: buildValueReasons(stock),
          expectedReturn: 25.0,
          stopLoss: Math.round((stock.currentPrice || 0) * 0.87),
          stopLossPercent: 13.0,
          holdingPeriod: '1~3개월',
          score: score,
          keyMetrics: [
            { label: 'PEG', value: stock.peg ? stock.peg.toFixed(2) : '-', class: stock.peg < 1 ? 'positive' : 'neutral' },
            { label: 'PER', value: stock.per ? `${stock.per.toFixed(1)}배` : '-', class: stock.per < 15 ? 'positive' : 'neutral' },
            { label: '점수', value: `${score}점`, class: score >= 70 ? 'positive' : (score >= 50 ? 'neutral' : 'negative') }
          ]
        };
      });

      recommendations.value.value = stocksWithScore;

      // 전략 점수: 상위 종목들의 평균 점수
      if (stocksWithScore.length > 0) {
        const avgScore = stocksWithScore.reduce((sum, s) => sum + s.score, 0) / stocksWithScore.length;
        strategyScores.value.value = Math.round(avgScore);
      } else {
        strategyScores.value.value = 0;
      }

      // 실시간 시세 데이터 병합 (비동기)
      fetchRealTimeQuotes('value');
    }
  } catch (error) {
    console.error('가치투자 데이터 로드 오류:', error);
    strategyScores.value.value = 0;
  }
};

// ========== 실시간 시세 데이터 조회 및 병합 ==========
const fetchRealTimeQuotes = async (strategyType) => {
  const stocks = recommendations.value[strategyType];
  if (!stocks || stocks.length === 0) return;

  for (const stock of stocks) {
    try {
      const response = await stockAPI.getStockPrice(stock.stockCode);
      if (response.data.success && response.data.data) {
        const quote = response.data.data;
        const oldPrice = stock.currentPrice;
        const newPrice = quote.currentPrice || quote.price || oldPrice;
        const previousClose = quote.previousClose || quote.basePrice || stock.previousClose;

        // 등락률 계산: (현재가 - 전일종가) / 전일종가 * 100
        let changeRate = 0;
        if (previousClose && previousClose > 0) {
          changeRate = ((newPrice - previousClose) / previousClose) * 100;
        }

        // 가격 변동 시 플래시 효과
        if (newPrice !== oldPrice) {
          stock.priceFlash = newPrice > oldPrice ? 'up' : 'down';
          setTimeout(() => {
            stock.priceFlash = null;
          }, 500);
        }

        // 데이터 업데이트
        stock.currentPrice = newPrice;
        stock.previousClose = previousClose;
        stock.changeRate = changeRate;
        stock.stopLoss = Math.round(newPrice * (1 - stock.stopLossPercent / 100));
      }
    } catch (error) {
      // API 실패 시 기존 데이터 유지 (가짜 데이터 생성 안함)
      console.warn(`시세 조회 실패 (${stock.stockCode}):`, error.message);
    }
  }
};

const loadMarketSummary = async () => {
  try {
    // 시장 상태
    const statusResponse = await marketAPI.getStatus();
    if (statusResponse.data.success && statusResponse.data.data) {
      const data = statusResponse.data.data;
      // ADR 기반 시장 분위기 판단
      const adr = data.adr || 100;
      if (adr >= 120) {
        marketSentiment.value = { text: '강세 (Risk-On)', class: 'positive' };
      } else if (adr >= 100) {
        marketSentiment.value = { text: '보합', class: '' };
      } else if (adr >= 80) {
        marketSentiment.value = { text: '약세', class: 'negative' };
      } else {
        marketSentiment.value = { text: '약세 (Risk-Off)', class: 'negative' };
      }

      foreignFlow.value = data.foreignNetBuy || 0;
      institutionFlow.value = data.institutionNetBuy || 0;
    }
  } catch (error) {
    console.error('시장 상태 로드 오류:', error);
    marketSentiment.value = { text: '조회 실패', class: '' };
  }

  try {
    // 주도 섹터
    const sectorResponse = await tradingIndicatorAPI.getLeadingSectors();
    if (sectorResponse.data.success && sectorResponse.data.data && sectorResponse.data.data.length > 0) {
      leadingSector.value = sectorResponse.data.data[0].sectorName || '-';
    }
  } catch (error) {
    console.error('주도 섹터 로드 오류:', error);
    leadingSector.value = '-';
  }
};

// ========== 추천 사유 빌더 ==========
const buildScalpingReasons = (stock) => {
  const reasons = [];
  if (stock.foreignNetBuy > 0 && stock.institutionNetBuy > 0) reasons.push('외국인+기관 동시 매수');
  if (stock.foreignNetBuy > 100) reasons.push('외국인 폭발 매수');
  if (stock.institutionNetBuy > 100) reasons.push('기관 폭발 매수');
  if (stock.changeRate > 3) reasons.push('급등 모멘텀');
  if (reasons.length === 0) reasons.push('수급 급증');
  return reasons.slice(0, 3);
};

const buildSwingReasons = (stock) => {
  const reasons = [];
  reasons.push(`${stock.consecutiveDays}일 연속 매수`);
  if (stock.changeRate < 0) reasons.push('눌림목 구간');
  if (stock.totalNetBuyAmount > 500) reasons.push('대량 매집');
  return reasons.slice(0, 3);
};

const buildTrendReasons = (stock) => {
  const reasons = [];
  if (stock.netIncome > 0) reasons.push('흑자전환');
  if (stock.operatingMargin > 0) reasons.push(`영업이익률 ${stock.operatingMargin?.toFixed(1) || 0}%`);
  if (stock.revenueGrowth > 20) reasons.push('매출 급성장');
  if (reasons.length === 0) reasons.push('실적 개선');
  return reasons.slice(0, 3);
};

const buildValueReasons = (stock) => {
  const reasons = [];
  if (stock.peg && stock.peg < 1) reasons.push(`PEG ${stock.peg.toFixed(2)}`);
  if (stock.per && stock.per < 10) reasons.push(`저PER ${stock.per.toFixed(1)}배`);
  if (stock.pbr && stock.pbr < 1) reasons.push(`저PBR ${stock.pbr.toFixed(2)}배`);
  if (stock.roe && stock.roe > 15) reasons.push(`고ROE ${stock.roe.toFixed(1)}%`);
  if (stock.dividendYield && stock.dividendYield > 3) reasons.push(`배당률 ${stock.dividendYield.toFixed(1)}%`);
  if (reasons.length === 0) reasons.push('저평가 성장주');
  return reasons.slice(0, 3);
};

// ========== 유틸리티 함수 ==========
const formatNumber = (value) => {
  if (!value) return '0';
  return Number(value).toLocaleString('ko-KR');
};

const formatAmount = (value) => {
  if (!value) return '0억';
  const num = Number(value);
  if (Math.abs(num) >= 10000) return `${(num / 10000).toFixed(1)}조`;
  return `${num.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}억`;
};

const formatTime = (date) => {
  if (!date) return '';
  return date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
};

const formatChangeRate = (rate) => {
  if (rate == null || isNaN(rate)) return '0.00';
  return rate.toFixed(2);
};

const getRankClass = (index) => {
  if (index === 0) return 'gold';
  if (index === 1) return 'silver';
  if (index === 2) return 'bronze';
  return '';
};

const getReasonClass = (reason) => {
  if (reason.includes('연속') || reason.includes('매수') || reason.includes('매집')) return 'supply';
  if (reason.includes('체결강도') || reason.includes('거래량') || reason.includes('급등') || reason.includes('폭발')) return 'momentum';
  if (reason.includes('흑자') || reason.includes('이익') || reason.includes('성장')) return 'fundamental';
  if (reason.includes('PEG') || reason.includes('PBR') || reason.includes('PER') || reason.includes('배당') || reason.includes('ROE')) return 'value';
  if (reason.includes('VWAP') || reason.includes('RSI') || reason.includes('눌림')) return 'technical';
  return 'theme';
};

const goToDetail = (stockCode) => {
  router.push(`/scalping-analysis?code=${stockCode}&from=ai-strategy`);
};

// ========== 초기화 ==========
onMounted(async () => {
  loading.value = true;
  try {
    await Promise.all([
      loadScalpingData(),
      loadSwingData(),
      loadTrendData(),
      loadValueData(),
      loadMarketSummary()
    ]);
    lastUpdated.value = new Date();
  } catch (error) {
    console.error('데이터 로드 오류:', error);
  } finally {
    loading.value = false;
  }
});
</script>

<style scoped>
.ai-dashboard {
  min-height: 100vh;
  background: linear-gradient(135deg, #0f0f1a 0%, #1a1a2e 50%, #16213e 100%);
  padding: 2rem;
}

.content-wrapper {
  max-width: 1400px;
  margin: 0 auto;
}

/* 헤더 */
.page-header {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-bottom: 2rem;
  flex-wrap: wrap;
}

.header-content {
  flex: 1;
}

.page-header h1 {
  color: #fff;
  font-size: 2rem;
  margin: 0 0 8px 0;
  background: linear-gradient(135deg, #ffd700 0%, #ffaa00 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.subtitle {
  color: #888;
  margin: 0;
  font-size: 1rem;
}

.header-meta {
  display: flex;
  align-items: center;
}

.update-badge {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(34, 197, 94, 0.1);
  border: 1px solid rgba(34, 197, 94, 0.3);
  padding: 8px 16px;
  border-radius: 20px;
  color: #22c55e;
  font-size: 0.9rem;
}

.pulse-dot {
  width: 8px;
  height: 8px;
  background: #22c55e;
  border-radius: 50%;
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(1.2); }
}

/* 4분할 전략 점수 패널 */
.score-panel {
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.05) 0%, rgba(255, 255, 255, 0.02) 100%);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 20px;
  padding: 24px;
  margin-bottom: 2rem;
}

.score-panel-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
}

.panel-icon {
  font-size: 1.5rem;
}

.score-panel-header h2 {
  color: #fff;
  font-size: 1.3rem;
  margin: 0;
}

.score-panel-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 24px;
}

.strategy-scores {
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
  flex: 1;
}

.score-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.3s;
  min-width: 180px;
}

.score-item:hover {
  background: rgba(255, 255, 255, 0.1);
  transform: translateY(-2px);
}

.score-label {
  font-size: 0.9rem;
  font-weight: 600;
  color: #ccc;
  white-space: nowrap;
}

.score-bar-wrapper {
  flex: 1;
  min-width: 80px;
}

.score-bar {
  width: 100%;
  height: 8px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 4px;
  overflow: hidden;
}

.score-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.6s ease;
}

.score-fill.scalping {
  background: linear-gradient(90deg, #f59e0b 0%, #f97316 100%);
  box-shadow: 0 0 10px rgba(245, 158, 11, 0.5);
}

.score-fill.swing {
  background: linear-gradient(90deg, #22c55e 0%, #16a34a 100%);
  box-shadow: 0 0 10px rgba(34, 197, 94, 0.5);
}

.score-fill.turnaround {
  background: linear-gradient(90deg, #3b82f6 0%, #2563eb 100%);
  box-shadow: 0 0 10px rgba(59, 130, 246, 0.5);
}

.score-fill.value {
  background: linear-gradient(90deg, #a855f7 0%, #7c3aed 100%);
  box-shadow: 0 0 10px rgba(168, 85, 247, 0.5);
}

.score-number {
  font-size: 1.4rem;
  font-weight: 800;
  color: #fff;
  min-width: 40px;
  text-align: right;
}

.total-score-section {
  display: flex;
  align-items: center;
  gap: 16px;
}

.total-score-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px 24px;
  background: rgba(0, 0, 0, 0.3);
  border-radius: 16px;
}

.total-label {
  font-size: 0.8rem;
  color: #888;
  margin-bottom: 4px;
}

.total-value {
  font-size: 3rem;
  font-weight: 800;
  background: linear-gradient(135deg, #ffd700 0%, #ffaa00 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.total-opinion-box {
  padding: 12px 24px;
  border-radius: 24px;
  font-size: 1rem;
  font-weight: 700;
}

.total-opinion-box.strong-buy {
  background: linear-gradient(135deg, #ef4444 0%, #f87171 100%);
  color: white;
  box-shadow: 0 0 20px rgba(239, 68, 68, 0.4);
}

.total-opinion-box.buy {
  background: rgba(239, 68, 68, 0.2);
  color: #f87171;
  border: 1px solid rgba(239, 68, 68, 0.3);
}

.total-opinion-box.hold {
  background: rgba(156, 163, 175, 0.2);
  color: #9ca3af;
  border: 1px solid rgba(156, 163, 175, 0.3);
}

.total-opinion-box.sell {
  background: rgba(59, 130, 246, 0.2);
  color: #60a5fa;
  border: 1px solid rgba(59, 130, 246, 0.3);
}

/* 전략 탭 */
.strategy-tabs {
  display: flex;
  gap: 12px;
  margin-bottom: 1.5rem;
  flex-wrap: wrap;
}

.tab-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 16px 24px;
  background: rgba(255, 255, 255, 0.05);
  border: 2px solid rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  color: #888;
  cursor: pointer;
  transition: all 0.3s;
  flex: 1;
  min-width: 140px;
}

.tab-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.2);
}

.tab-btn.active {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.2) 0%, rgba(118, 75, 162, 0.2) 100%);
  border-color: #667eea;
  color: #fff;
  box-shadow: 0 0 20px rgba(102, 126, 234, 0.3);
}

.tab-icon {
  font-size: 1.8rem;
}

.tab-label {
  font-weight: 700;
  font-size: 1rem;
}

.tab-period {
  font-size: 0.75rem;
  opacity: 0.7;
}

/* 전략 설명 */
.strategy-description {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  padding: 20px;
  margin-bottom: 2rem;
}

.strategy-info {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
}

.strategy-icon {
  font-size: 2.5rem;
}

.strategy-text h3 {
  margin: 0 0 4px 0;
  color: #fff;
  font-size: 1.2rem;
}

.strategy-text p {
  margin: 0;
  color: #888;
  font-size: 0.95rem;
}

.strategy-criteria {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px;
  background: rgba(102, 126, 234, 0.1);
  border-radius: 8px;
}

.criteria-label {
  color: #667eea;
  font-weight: 600;
  font-size: 0.9rem;
}

.criteria-value {
  color: #fff;
  font-size: 0.9rem;
}

/* 추천 섹션 */
.recommendations-section {
  margin-bottom: 2rem;
}

.recommendations-section h2 {
  color: #fff;
  font-size: 1.5rem;
  margin-bottom: 1.5rem;
}

.recommendations-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
  gap: 20px;
}

/* 추천 카드 */
.recommendation-card {
  background: linear-gradient(135deg, #1a1a3a 0%, #0f0f23 100%);
  border: 2px solid #2a2a4a;
  border-radius: 20px;
  padding: 24px;
  position: relative;
  transition: all 0.3s;
}

.recommendation-card:hover {
  border-color: #667eea;
  transform: translateY(-5px);
  box-shadow: 0 10px 40px rgba(102, 126, 234, 0.2);
}

/* 1위 카드 특별 스타일 */
.recommendation-card.top-pick {
  background: linear-gradient(135deg, #1a1a3a 0%, #2a1a3a 50%, #0f0f23 100%);
  border: 2px solid #ffd700;
  box-shadow:
    0 0 20px rgba(255, 215, 0, 0.3),
    inset 0 0 30px rgba(255, 215, 0, 0.05);
  animation: golden-glow 2s ease-in-out infinite;
}

@keyframes golden-glow {
  0%, 100% { box-shadow: 0 0 20px rgba(255, 215, 0, 0.3), inset 0 0 30px rgba(255, 215, 0, 0.05); }
  50% { box-shadow: 0 0 40px rgba(255, 215, 0, 0.5), inset 0 0 40px rgba(255, 215, 0, 0.1); }
}

/* 순위 배지 */
.rank-badge {
  position: absolute;
  top: -10px;
  right: 20px;
  padding: 8px 16px;
  border-radius: 20px;
  font-weight: 700;
  font-size: 1rem;
  background: #3a3a5a;
  color: #fff;
}

.rank-badge.gold {
  background: linear-gradient(135deg, #ffd700 0%, #ffaa00 100%);
  color: #1a1a2e;
  font-size: 1.2rem;
  box-shadow: 0 4px 15px rgba(255, 215, 0, 0.4);
}

.rank-badge.silver {
  background: linear-gradient(135deg, #c0c0c0 0%, #a0a0a0 100%);
  color: #1a1a2e;
}

.rank-badge.bronze {
  background: linear-gradient(135deg, #cd7f32 0%, #a0522d 100%);
  color: #fff;
}

/* 종목 정보 */
.stock-info {
  margin-bottom: 20px;
}

.stock-header {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 12px;
}

.stock-name {
  margin: 0;
  color: #fff;
  font-size: 1.4rem;
}

.stock-code {
  color: #666;
  font-size: 0.9rem;
  font-family: monospace;
}

/* AI 추천 사유 뱃지 */
.reason-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.reason-badge {
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 0.75rem;
  font-weight: 600;
}

.reason-badge.supply {
  background: rgba(34, 197, 94, 0.2);
  color: #22c55e;
  border: 1px solid rgba(34, 197, 94, 0.3);
}

.reason-badge.momentum {
  background: rgba(239, 68, 68, 0.2);
  color: #ef4444;
  border: 1px solid rgba(239, 68, 68, 0.3);
}

.reason-badge.fundamental {
  background: rgba(168, 85, 247, 0.2);
  color: #a855f7;
  border: 1px solid rgba(168, 85, 247, 0.3);
}

.reason-badge.value {
  background: rgba(59, 130, 246, 0.2);
  color: #3b82f6;
  border: 1px solid rgba(59, 130, 246, 0.3);
}

.reason-badge.technical {
  background: rgba(234, 179, 8, 0.2);
  color: #eab308;
  border: 1px solid rgba(234, 179, 8, 0.3);
}

.reason-badge.theme {
  background: rgba(236, 72, 153, 0.2);
  color: #ec4899;
  border: 1px solid rgba(236, 72, 153, 0.3);
}

/* 가격 정보 */
.price-info {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.current-price {
  font-size: 1.5rem;
  font-weight: 700;
  color: #fff;
  font-family: 'Monaco', 'Consolas', monospace;
}

.change-rate {
  font-size: 1.1rem;
  font-weight: 600;
}

.change-rate.positive { color: #ef4444; }
.change-rate.negative { color: #3b82f6; }

/* AI 제안 */
.ai-suggestion {
  background: rgba(0, 0, 0, 0.3);
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 16px;
}

.suggestion-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.suggestion-row:last-child {
  border-bottom: none;
}

.suggestion-label {
  color: #888;
  font-size: 0.9rem;
}

.suggestion-value {
  font-weight: 600;
  font-family: 'Monaco', 'Consolas', monospace;
}

.suggestion-value.positive { color: #22c55e; }
.suggestion-value.negative { color: #f87171; }

/* 핵심 지표 */
.key-metrics {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.metric-item {
  flex: 1;
  min-width: 80px;
  text-align: center;
  padding: 10px;
  background: rgba(255, 255, 255, 0.03);
  border-radius: 8px;
}

.metric-label {
  display: block;
  color: #666;
  font-size: 0.75rem;
  margin-bottom: 4px;
}

.metric-value {
  display: block;
  font-weight: 600;
  font-size: 0.9rem;
}

.metric-value.positive { color: #22c55e; }
.metric-value.negative { color: #ef4444; }
.metric-value.neutral { color: #eab308; }

/* 상세보기 버튼 */
.detail-btn {
  width: 100%;
  padding: 12px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
  border-radius: 10px;
  color: #fff;
  font-weight: 600;
  font-size: 0.95rem;
  cursor: pointer;
  transition: all 0.3s;
}

.detail-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 5px 20px rgba(102, 126, 234, 0.4);
}

/* 시장 요약 */
.market-summary {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 20px;
  padding: 24px;
  margin-bottom: 2rem;
}

.market-summary h2 {
  color: #fff;
  font-size: 1.3rem;
  margin: 0 0 20px 0;
}

.summary-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
}

.summary-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 12px;
}

.summary-icon {
  font-size: 1.5rem;
}

.summary-content {
  display: flex;
  flex-direction: column;
}

.summary-label {
  color: #666;
  font-size: 0.8rem;
}

.summary-value {
  font-weight: 600;
  font-size: 1rem;
}

.summary-value.positive { color: #ef4444; }
.summary-value.negative { color: #3b82f6; }

/* 면책 조항 */
.disclaimer {
  text-align: center;
  padding: 16px;
  background: rgba(255, 255, 255, 0.02);
  border-radius: 12px;
}

.disclaimer p {
  margin: 0;
  color: #555;
  font-size: 0.85rem;
}

/* 로딩 상태 */
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: #888;
}

.loading-spinner {
  width: 40px;
  height: 40px;
  border: 3px solid rgba(102, 126, 234, 0.2);
  border-top-color: #667eea;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin-bottom: 16px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 빈 상태 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: #888;
  text-align: center;
}

.empty-icon {
  font-size: 3rem;
  margin-bottom: 16px;
  opacity: 0.5;
}

.empty-state p {
  margin: 4px 0;
}

.empty-hint {
  font-size: 0.85rem;
  color: #666;
}

/* 반응형 */
@media (max-width: 768px) {
  .ai-dashboard {
    padding: 1rem;
  }

  .page-header {
    flex-direction: column;
    text-align: center;
  }

  .page-header h1 {
    font-size: 1.5rem;
  }

  .strategy-tabs {
    flex-direction: column;
  }

  .tab-btn {
    flex-direction: row;
    justify-content: center;
    gap: 12px;
    padding: 12px 16px;
  }

  .recommendations-grid {
    grid-template-columns: 1fr;
  }

  .summary-cards {
    grid-template-columns: 1fr 1fr;
  }
}

/* 가격 변동 플래시 효과 */
.price-info.flash-up {
  animation: flash-up 0.5s ease-out;
}

.price-info.flash-down {
  animation: flash-down 0.5s ease-out;
}

@keyframes flash-up {
  0% { background-color: rgba(239, 68, 68, 0.4); }
  100% { background-color: transparent; }
}

@keyframes flash-down {
  0% { background-color: rgba(59, 130, 246, 0.4); }
  100% { background-color: transparent; }
}

.price-info {
  transition: background-color 0.3s ease;
  padding: 4px 8px;
  border-radius: 6px;
  margin: -4px -8px;
}
</style>
