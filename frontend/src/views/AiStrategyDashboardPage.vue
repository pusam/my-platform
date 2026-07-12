<template>
  <div class="ai-dashboard">
    <div class="content-wrapper">
      <!-- 헤더 (embedded 모드에서 숨김) -->
      <div v-if="!embedded" class="page-header-unified">
        <BackButton :dark="true" />
        <div class="header-title">
          <h1>AI 트레이딩 전략</h1>
          <p class="subtitle">4분할 투자 전략 | 기간별 맞춤 추천</p>
        </div>
        <div class="header-actions">
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

              <!-- AI 스코어 배지 + 진단 점수 + 한줄 코멘트 말풍선 -->
              <div v-if="stock.aiScore != null" class="ai-score-section">
                <div class="ai-score-badge" :class="getAiScoreClass(stock.aiScore)">
                  <span class="ai-badge-icon">🤖</span>
                  <span class="ai-badge-label">AI</span>
                  <span class="ai-badge-score">{{ stock.aiScore }}</span>
                </div>
                <!-- 실시간 진단 점수 (괴리 경고) -->
                <div v-if="diagnosisCache[stock.stockCode]" class="diagnosis-badge"
                     :class="getDiagnosisClass(diagnosisCache[stock.stockCode])">
                  <span class="diag-label">진단</span>
                  <span class="diag-score">{{ diagnosisCache[stock.stockCode].score }}</span>
                  <span class="diag-verdict">{{ diagnosisCache[stock.stockCode].verdict }}</span>
                </div>
                <div v-if="stock.aiComment" class="ai-comment-bubble">
                  <div class="bubble-tail"></div>
                  <span class="bubble-text">{{ stock.aiComment }}</span>
                </div>
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

            <!-- 기간별 수익률 -->
            <div v-if="stock.return1Week !== null || stock.return1Month !== null" class="return-metrics">
              <div class="return-item" :class="getReturnClass(stock.return1Week)">
                <span class="return-label">1주</span>
                <span class="return-value">{{ formatReturn(stock.return1Week) }}</span>
              </div>
              <div class="return-item" :class="getReturnClass(stock.return1Month)">
                <span class="return-label">1개월</span>
                <span class="return-value">{{ formatReturn(stock.return1Month) }}</span>
              </div>
              <div class="return-item" :class="getReturnClass(stock.return3Month)">
                <span class="return-label">3개월</span>
                <span class="return-value">{{ formatReturn(stock.return3Month) }}</span>
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
            <span class="summary-icon">📈</span>
            <div class="summary-content">
              <span class="summary-label">KOSPI</span>
              <span class="summary-value" :class="kospiInfo.change >= 0 ? 'positive' : 'negative'">
                {{ kospiInfo.index.toLocaleString() }} ({{ kospiInfo.change >= 0 ? '+' : '' }}{{ kospiInfo.change.toFixed(2) }}%)
              </span>
            </div>
          </div>
          <div class="summary-card">
            <span class="summary-icon">📉</span>
            <div class="summary-content">
              <span class="summary-label">KOSDAQ</span>
              <span class="summary-value" :class="kosdaqInfo.change >= 0 ? 'positive' : 'negative'">
                {{ kosdaqInfo.index.toLocaleString() }} ({{ kosdaqInfo.change >= 0 ? '+' : '' }}{{ kosdaqInfo.change.toFixed(2) }}%)
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

      <!-- 추천 트랙레코드 (백테스트) -->
      <BacktestPerformancePanel />

      <!-- 면책 조항 -->
      <div class="disclaimer">
        <p>본 추천은 AI 알고리즘에 의한 참고 자료이며, 투자 결정은 본인의 판단과 책임 하에 이루어져야 합니다.</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, defineProps } from 'vue';
import { useRouter } from 'vue-router';
import BackButton from '../components/BackButton.vue';
import BacktestPerformancePanel from '../components/v2/BacktestPerformancePanel.vue';

const props = defineProps({
  embedded: { type: Boolean, default: false }
});
import { aiStrategyAPI, marketAPI, tradingIndicatorAPI, stockDetailAPI } from '../utils/api';

const router = useRouter();
const activeTab = ref('scalping');
const lastUpdated = ref(new Date());
const loading = ref(false);

// 종목별 진단 점수 캐시 (stockCode → { score, verdict, verdictLevel })
const diagnosisCache = ref({});
const diagnosisLoading = ref(false);

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
const kospiInfo = ref({ index: 0, change: 0 });  // KOSPI 지수 정보
const kosdaqInfo = ref({ index: 0, change: 0 }); // KOSDAQ 지수 정보
const leadingSector = ref('-');

// ========== 스냅샷 API 데이터 로드 (단일 호출) ==========
const loadSnapshotData = async () => {
  try {
    const response = await aiStrategyAPI.getLatest();
    if (response.data && response.data.strategies) {
      const { strategies, lastUpdated } = response.data;

      // 스캘핑 데이터 매핑
      if (strategies.SCALPING && strategies.SCALPING.length > 0) {
        recommendations.value.scalping = mapSnapshotToCard(strategies.SCALPING, 'scalping');
        const avgScore = strategies.SCALPING.reduce((sum, s) => sum + (s.score || 0), 0) / strategies.SCALPING.length;
        strategyScores.value.scalping = Math.round(avgScore);
      }

      // 스윙 데이터 매핑
      if (strategies.SWING && strategies.SWING.length > 0) {
        recommendations.value.swing = mapSnapshotToCard(strategies.SWING, 'swing');
        const avgScore = strategies.SWING.reduce((sum, s) => sum + (s.score || 0), 0) / strategies.SWING.length;
        strategyScores.value.swing = Math.round(avgScore);
      }

      // 턴어라운드 데이터 매핑
      if (strategies.TURNAROUND && strategies.TURNAROUND.length > 0) {
        recommendations.value.trend = mapSnapshotToCard(strategies.TURNAROUND, 'trend');
        const avgScore = strategies.TURNAROUND.reduce((sum, s) => sum + (s.score || 0), 0) / strategies.TURNAROUND.length;
        strategyScores.value.turnaround = Math.round(avgScore);
      }

      // 가치투자 데이터 매핑
      if (strategies.VALUE && strategies.VALUE.length > 0) {
        recommendations.value.value = mapSnapshotToCard(strategies.VALUE, 'value');
        const avgScore = strategies.VALUE.reduce((sum, s) => sum + (s.score || 0), 0) / strategies.VALUE.length;
        strategyScores.value.value = Math.round(avgScore);
      }

      // 최종 업데이트 시각 설정 (가장 최근 것 사용)
      if (lastUpdated) {
        const times = Object.values(lastUpdated).filter(t => t).map(t => new Date(t));
        if (times.length > 0) {
          lastUpdated.value = new Date(Math.max(...times));
        }
      }

    }
  } catch (error) {
    console.error('스냅샷 데이터 로드 오류:', error);
    // 모든 전략 점수 0으로 초기화
    strategyScores.value = { scalping: 0, swing: 0, turnaround: 0, value: 0 };
  }
};

// 스냅샷 데이터를 카드 형식으로 매핑
const mapSnapshotToCard = (snapshots, strategyType) => {
  const strategyConfig = {
    scalping: { expectedReturn: 3.0, stopLossPercent: 3.0, holdingPeriod: '30분~2시간' },
    swing: { expectedReturn: 7.0, stopLossPercent: 5.0, holdingPeriod: '3~5일' },
    trend: { expectedReturn: 20.0, stopLossPercent: 12.0, holdingPeriod: '2~4주' },
    value: { expectedReturn: 25.0, stopLossPercent: 13.0, holdingPeriod: '1~3개월' }
  };

  const config = strategyConfig[strategyType];

  return snapshots.map(snapshot => {
    const currentPrice = snapshot.currentPrice || 0;
    const score = snapshot.score || 0;

    return {
      stockCode: snapshot.stockCode,
      stockName: snapshot.stockName,
      currentPrice: currentPrice,
      previousClose: currentPrice, // 스냅샷에서는 전일종가 없음
      changeRate: snapshot.changeRate || 0,
      priceFlash: null,
      reasons: buildReasons(snapshot, strategyType),
      expectedReturn: config.expectedReturn,
      stopLoss: Math.round(currentPrice * (1 - config.stopLossPercent / 100)),
      stopLossPercent: config.stopLossPercent,
      holdingPeriod: config.holdingPeriod,
      score: score,
      aiScore: snapshot.aiScore,
      aiComment: snapshot.aiComment,
      originalScore: snapshot.originalScore,
      keyMetrics: buildKeyMetrics(snapshot, strategyType, score),
      // 기간별 수익률 (백엔드에서 계산된 값)
      return1Week: snapshot.return1Week,
      return1Month: snapshot.return1Month,
      return3Month: snapshot.return3Month
    };
  });
};

// 전략별 추천 사유 생성
const buildReasons = (snapshot, strategyType) => {
  // 스냅샷의 reason이 있으면 사용
  if (snapshot.reason) {
    return snapshot.reason.split(', ').slice(0, 3);
  }

  // 없으면 전략별로 기본 사유 생성
  switch (strategyType) {
    case 'scalping':
      return buildScalpingReasons(snapshot);
    case 'swing':
      return buildSwingReasons(snapshot);
    case 'trend':
      return buildTrendReasons(snapshot);
    case 'value':
      return buildValueReasons(snapshot);
    default:
      return ['AI 추천'];
  }
};

// 전략별 핵심 지표 생성
const buildKeyMetrics = (snapshot, strategyType, score) => {
  const scoreMetric = {
    label: '점수',
    value: `${score}점`,
    class: score >= 70 ? 'positive' : (score >= 50 ? 'neutral' : 'negative')
  };

  switch (strategyType) {
    case 'scalping':
      return [
        { label: '거래량', value: snapshot.volumeRatio ? `${snapshot.volumeRatio.toFixed(0)}%` : '-', class: 'positive' },
        { label: '등락률', value: snapshot.changeRate ? `${snapshot.changeRate >= 0 ? '+' : ''}${snapshot.changeRate.toFixed(2)}%` : '-', class: snapshot.changeRate >= 0 ? 'positive' : 'negative' },
        scoreMetric
      ];
    case 'swing':
      return [
        { label: 'ROE', value: snapshot.roe ? `${snapshot.roe.toFixed(1)}%` : '-', class: snapshot.roe > 10 ? 'positive' : 'neutral' },
        { label: 'PER', value: snapshot.per ? `${snapshot.per.toFixed(1)}배` : '-', class: snapshot.per && snapshot.per < 15 ? 'positive' : 'neutral' },
        scoreMetric
      ];
    case 'trend':
      // 흑자전환의 경우 999.99%가 아닌 의미있는 텍스트 표시
      const isLossToProfit = snapshot.turnaroundType === 'LOSS_TO_PROFIT';
      const changeRateValue = isLossToProfit
        ? '흑자전환'
        : (snapshot.netIncomeChangeRate ? `+${snapshot.netIncomeChangeRate.toFixed(0)}%` : '-');
      return [
        { label: '턴어라운드', value: isLossToProfit ? '흑자전환' : '이익증가', class: 'positive' },
        { label: '이익변화', value: changeRateValue, class: 'positive' },
        scoreMetric
      ];
    case 'value':
      return [
        { label: 'PEG', value: snapshot.peg ? snapshot.peg.toFixed(2) : '-', class: snapshot.peg && snapshot.peg < 1 ? 'positive' : 'neutral' },
        { label: 'ROE', value: snapshot.roe ? `${snapshot.roe.toFixed(1)}%` : '-', class: snapshot.roe > 10 ? 'positive' : 'neutral' },
        scoreMetric
      ];
    default:
      return [scoreMetric];
  }
};

const loadMarketSummary = async () => {
  try {
    // 시장 상태
    const statusResponse = await marketAPI.getStatus();
    if (statusResponse.data.success && statusResponse.data.data) {
      const data = statusResponse.data.data;
      // ADR 기반 시장 분위기 판단 (combinedAdr 필드 사용)
      const adr = data.combinedAdr || 100;
      if (adr >= 120) {
        marketSentiment.value = { text: '과열 (Risk-On)', class: 'positive' };
      } else if (adr >= 100) {
        marketSentiment.value = { text: '보합', class: '' };
      } else if (adr >= 80) {
        marketSentiment.value = { text: '침체', class: 'negative' };
      } else {
        marketSentiment.value = { text: '공포 (Risk-Off)', class: 'negative' };
      }

      // KOSPI 지수 정보
      if (data.kospi) {
        kospiInfo.value = {
          index: data.kospi.indexClose || 0,
          change: data.kospi.indexChangeRate || 0
        };
      }

      // KOSDAQ 지수 정보
      if (data.kosdaq) {
        kosdaqInfo.value = {
          index: data.kosdaq.indexClose || 0,
          change: data.kosdaq.indexChangeRate || 0
        };
      }
    }
  } catch (error) {
    console.error('시장 상태 로드 오류:', error);
    marketSentiment.value = { text: '조회 실패', class: '' };
  }

  try {
    // 주도 섹터
    const sectorResponse = await tradingIndicatorAPI.getLeadingSectors();
    if (sectorResponse.data.success && sectorResponse.data.data) {
      // topSectors 배열에서 첫 번째 섹터 이름 가져오기
      const topSectors = sectorResponse.data.data.topSectors;
      if (topSectors && topSectors.length > 0) {
        leadingSector.value = topSectors[0].sectorName || '-';
      }
    }
  } catch (error) {
    console.error('주도 섹터 로드 오류:', error);
    leadingSector.value = '-';
  }
};

// ========== 추천 사유 빌더 (스냅샷 데이터 형식 대응) ==========
const buildScalpingReasons = (snapshot) => {
  const reasons = [];
  if (snapshot.volumeRatio && snapshot.volumeRatio > 200) reasons.push('거래량 급증');
  if (snapshot.changeRate && snapshot.changeRate > 5) reasons.push('급등 모멘텀');
  else if (snapshot.changeRate && snapshot.changeRate > 2) reasons.push('상승 모멘텀');
  if (reasons.length === 0) reasons.push('모멘텀 발생');
  return reasons.slice(0, 3);
};

const buildSwingReasons = (snapshot) => {
  const reasons = [];
  if (snapshot.roe && snapshot.roe > 15) reasons.push(`고ROE ${snapshot.roe.toFixed(1)}%`);
  if (snapshot.operatingMargin && snapshot.operatingMargin > 10) reasons.push(`영업이익률 ${snapshot.operatingMargin.toFixed(1)}%`);
  if (snapshot.per && snapshot.per < 10) reasons.push(`저PER ${snapshot.per.toFixed(1)}배`);
  if (snapshot.magicFormulaRank) reasons.push(`마법공식 ${snapshot.magicFormulaRank}위`);
  if (reasons.length === 0) reasons.push('우량 가치주');
  return reasons.slice(0, 3);
};

const buildTrendReasons = (snapshot) => {
  const reasons = [];
  if (snapshot.turnaroundType === 'LOSS_TO_PROFIT') reasons.push('흑자전환 성공');
  else if (snapshot.turnaroundType === 'PROFIT_GROWTH') reasons.push('이익 급증');
  if (snapshot.netIncomeChangeRate && snapshot.netIncomeChangeRate > 50) {
    reasons.push(`순이익 ${snapshot.netIncomeChangeRate.toFixed(0)}%↑`);
  }
  if (snapshot.operatingMargin && snapshot.operatingMargin > 0) {
    reasons.push(`영업이익률 ${snapshot.operatingMargin.toFixed(1)}%`);
  }
  if (reasons.length === 0) reasons.push('실적 개선');
  return reasons.slice(0, 3);
};

const buildValueReasons = (snapshot) => {
  const reasons = [];
  if (snapshot.peg && snapshot.peg < 1) reasons.push(`PEG ${snapshot.peg.toFixed(2)}`);
  if (snapshot.per && snapshot.per < 10) reasons.push(`저PER ${snapshot.per.toFixed(1)}배`);
  if (snapshot.pbr && snapshot.pbr < 1) reasons.push(`저PBR ${snapshot.pbr.toFixed(2)}배`);
  if (snapshot.roe && snapshot.roe > 15) reasons.push(`고ROE ${snapshot.roe.toFixed(1)}%`);
  if (snapshot.epsGrowth && snapshot.epsGrowth > 20) reasons.push(`EPS성장 ${snapshot.epsGrowth.toFixed(0)}%`);
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

// 기간별 수익률 포맷
const formatReturn = (value) => {
  if (value == null || isNaN(value)) return '-';
  const prefix = value >= 0 ? '+' : '';
  return `${prefix}${value.toFixed(1)}%`;
};

// 수익률 클래스 (양수/음수)
const getReturnClass = (value) => {
  if (value == null || isNaN(value)) return 'neutral';
  if (value > 0) return 'positive';
  if (value < 0) return 'negative';
  return 'neutral';
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

const getAiScoreClass = (score) => {
  if (score >= 71) return 'ai-high';
  if (score >= 51) return 'ai-medium';
  if (score >= 31) return 'ai-low';
  return 'ai-very-low';
};

const getDiagnosisClass = (diag) => {
  if (!diag || diag.score == null) return '';
  if (diag.score >= 70) return 'diag-good';
  if (diag.score >= 50) return 'diag-neutral';
  return 'diag-caution';
};

const goToDetail = (stockCode) => {
  router.push(`/stock/${stockCode}`);
};

// ========== 초기화 ==========
// 현재 탭 종목들의 진단 점수 비동기 로드
const loadDiagnosisScores = async () => {
  const stocks = currentRecommendations.value
  if (!stocks || stocks.length === 0) return

  // 이미 캐시된 종목 제외
  const needCodes = stocks
    .map(s => s.stockCode)
    .filter(code => !diagnosisCache.value[code])
  if (needCodes.length === 0) return

  diagnosisLoading.value = true
  try {
    const res = await stockDetailAPI.batchScores(needCodes)
    const scores = res?.data?.data || res?.data || {}
    for (const [code, data] of Object.entries(scores)) {
      diagnosisCache.value[code] = {
        score: data.overallScore || data.score || null,
        verdict: data.verdict || null,
        verdictLevel: data.verdictLevel || null
      }
    }
  } catch (e) {
    console.debug('진단 점수 로드 실패:', e.message)
  } finally {
    diagnosisLoading.value = false
  }
}

// 탭 변경 시 진단 점수 로드
watch(activeTab, () => { loadDiagnosisScores() })

onMounted(async () => {
  loading.value = true;
  try {
    await Promise.all([
      loadSnapshotData(),
      loadMarketSummary()
    ]);
    lastUpdated.value = new Date();
    // 초기 탭 진단 점수 로드
    loadDiagnosisScores()
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
  background: var(--bg-gradient, linear-gradient(135deg, #0f0f1a 0%, #1a1a2e 50%, #16213e 100%));
  padding: 2rem;
}

.content-wrapper {
  max-width: 1400px;
  margin: 0 auto;
}

/* 헤더 */
.header-actions {
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
  background: linear-gradient(135deg, var(--border-light) 0%, rgba(255, 255, 255, 0.02) 100%);
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
  background: var(--border-light);
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
  border-color: var(--primary-start);
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
  color: var(--primary-start);
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
  border: 2px solid var(--surface-panel-strong, #2a2a4a);
  border-radius: 20px;
  padding: 24px;
  position: relative;
  transition: all 0.3s;
}

.recommendation-card:hover {
  border-color: var(--primary-start);
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
  color: var(--text-on-accent, #1a1a2e);
  font-size: 1.2rem;
  box-shadow: 0 4px 15px rgba(255, 215, 0, 0.4);
}

.rank-badge.silver {
  background: linear-gradient(135deg, #c0c0c0 0%, #a0a0a0 100%);
  color: var(--text-on-accent, #1a1a2e);
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
  border-bottom: 1px solid var(--border-light);
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

/* 기간별 수익률 */
.return-metrics {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  padding: 12px;
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.1) 0%, rgba(118, 75, 162, 0.1) 100%);
  border-radius: 10px;
  border: 1px solid rgba(102, 126, 234, 0.2);
}

.return-item {
  flex: 1;
  text-align: center;
  padding: 8px 4px;
  border-radius: 6px;
  transition: all 0.3s;
}

.return-item.positive {
  background: rgba(239, 68, 68, 0.15);
}

.return-item.negative {
  background: rgba(59, 130, 246, 0.15);
}

.return-item.neutral {
  background: rgba(156, 163, 175, 0.1);
}

.return-label {
  display: block;
  font-size: 0.7rem;
  color: #888;
  margin-bottom: 4px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.return-value {
  display: block;
  font-weight: 700;
  font-size: 0.95rem;
  font-family: 'Monaco', 'Consolas', monospace;
}

.return-item.positive .return-value {
  color: #ef4444;
}

.return-item.negative .return-value {
  color: #3b82f6;
}

.return-item.neutral .return-value {
  color: #9ca3af;
}

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
  background: linear-gradient(135deg, var(--primary-start) 0%, #764ba2 100%);
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
  border-top-color: var(--primary-start);
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

/* AI 스코어 섹션 */
.ai-score-section {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.ai-score-badge {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border-radius: 20px;
  font-weight: 700;
  font-size: 0.85rem;
  white-space: nowrap;
  flex-shrink: 0;
}

.ai-badge-icon {
  font-size: 0.9rem;
}

.ai-badge-label {
  font-size: 0.7rem;
  opacity: 0.9;
}

.ai-badge-score {
  font-size: 1rem;
  font-weight: 800;
}

.ai-score-badge.ai-high {
  background: linear-gradient(135deg, rgba(239, 68, 68, 0.25) 0%, rgba(220, 38, 38, 0.35) 100%);
  color: #f87171;
  border: 1px solid rgba(239, 68, 68, 0.4);
  box-shadow: 0 0 10px rgba(239, 68, 68, 0.2);
}

.ai-score-badge.ai-medium {
  background: linear-gradient(135deg, rgba(34, 197, 94, 0.25) 0%, rgba(22, 163, 74, 0.35) 100%);
  color: #4ade80;
  border: 1px solid rgba(34, 197, 94, 0.4);
  box-shadow: 0 0 10px rgba(34, 197, 94, 0.2);
}

.ai-score-badge.ai-low {
  background: linear-gradient(135deg, rgba(234, 179, 8, 0.25) 0%, rgba(202, 138, 4, 0.35) 100%);
  color: #facc15;
  border: 1px solid rgba(234, 179, 8, 0.4);
}

.ai-score-badge.ai-very-low {
  background: rgba(156, 163, 175, 0.2);
  color: #9ca3af;
  border: 1px solid rgba(156, 163, 175, 0.3);
}

/* 진단 점수 배지 */
.diagnosis-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border-radius: 8px;
  font-size: 11px;
  font-weight: 600;
}
.diagnosis-badge .diag-label {
  opacity: 0.7;
  font-size: 10px;
}
.diagnosis-badge .diag-score {
  font-weight: 800;
}
.diagnosis-badge .diag-verdict {
  font-size: 10px;
  opacity: 0.8;
}
.diag-good {
  background: rgba(34,197,94,0.15);
  color: #22c55e;
  border: 1px solid rgba(34,197,94,0.3);
}
.diag-neutral {
  background: rgba(245,158,11,0.15);
  color: #f59e0b;
  border: 1px solid rgba(245,158,11,0.3);
}
.diag-caution {
  background: rgba(239,68,68,0.15);
  color: #ef4444;
  border: 1px solid rgba(239,68,68,0.3);
}

.ai-comment-bubble {
  position: relative;
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.2) 0%, rgba(109, 40, 217, 0.15) 100%);
  border: 1px solid rgba(139, 92, 246, 0.3);
  border-radius: 12px;
  padding: 6px 12px;
  max-width: 100%;
  flex: 1;
  min-width: 0;
}

.bubble-tail {
  position: absolute;
  left: -6px;
  top: 50%;
  transform: translateY(-50%);
  width: 0;
  height: 0;
  border-top: 6px solid transparent;
  border-bottom: 6px solid transparent;
  border-right: 6px solid rgba(139, 92, 246, 0.3);
}

.bubble-text {
  color: #c4b5fd;
  font-size: 0.8rem;
  line-height: 1.4;
  word-break: keep-all;
  overflow-wrap: break-word;
}

/* 반응형 */
@media (max-width: 768px) {
  .ai-dashboard {
    padding: 1rem;
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

  .ai-score-section {
    flex-direction: column;
    align-items: flex-start;
  }

  .bubble-tail {
    display: none;
  }

  .summary-cards {
    grid-template-columns: 1fr 1fr;
  }

  /* 모바일 아이콘 축소 — 데스크톱 1.8rem/2.5rem 은 좁은 화면에서 과함 */
  .tab-icon { font-size: 1.2rem; }
  .strategy-icon { font-size: 1.5rem; }
  .strategy-text h3 { font-size: 1rem; }
}

@media (max-width: 480px) {
  .ai-dashboard {
    padding: 0.6rem;
  }
  .summary-cards {
    grid-template-columns: 1fr;
    gap: 8px;
  }
  .tab-btn {
    padding: 10px 12px;
    font-size: 13px;
  }
  .strategy-icon { font-size: 1.3rem; }
  .strategy-text h3 { font-size: 0.95rem; }
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
