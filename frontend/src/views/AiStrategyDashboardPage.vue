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

        <div class="recommendations-grid">
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
              <div class="price-info">
                <span class="current-price">{{ formatNumber(stock.currentPrice) }}원</span>
                <span class="change-rate" :class="stock.changeRate >= 0 ? 'positive' : 'negative'">
                  {{ stock.changeRate >= 0 ? '+' : '' }}{{ stock.changeRate.toFixed(2) }}%
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

const router = useRouter();
const activeTab = ref('scalping');
const lastUpdated = ref(new Date());

// 4분할 전략 점수 (Mock)
const strategyScores = ref({
  scalping: 78,
  swing: 82,
  turnaround: 71,
  value: 85
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

// ========== Mock 추천 데이터 ==========
const recommendations = {
  scalping: [
    {
      stockCode: '000660',
      stockName: 'SK하이닉스',
      currentPrice: 178500,
      changeRate: 5.2,
      reasons: ['체결강도 142%', '외국인 폭발 매수', 'HBM 수혜'],
      expectedReturn: 3.5,
      stopLoss: 173000,
      stopLossPercent: 3.1,
      holdingPeriod: '30분~2시간',
      keyMetrics: [
        { label: '체결강도', value: '142.3%', class: 'positive' },
        { label: '거래량비', value: '285%', class: 'positive' },
        { label: '외국인', value: '+580억', class: 'positive' }
      ]
    },
    {
      stockCode: '086520',
      stockName: '에코프로',
      currentPrice: 98500,
      changeRate: 8.7,
      reasons: ['체결강도 185%', '프로그램 급등', '거래량 폭발'],
      expectedReturn: 5.0,
      stopLoss: 94000,
      stopLossPercent: 4.6,
      holdingPeriod: '1~3시간',
      keyMetrics: [
        { label: '체결강도', value: '185.6%', class: 'positive' },
        { label: '거래량비', value: '420%', class: 'positive' },
        { label: '프로그램', value: '+320억', class: 'positive' }
      ]
    },
    {
      stockCode: '012450',
      stockName: '한화에어로스페이스',
      currentPrice: 245000,
      changeRate: 4.1,
      reasons: ['체결강도 128%', '기관 동시 매수', '방산 테마'],
      expectedReturn: 2.8,
      stopLoss: 238000,
      stopLossPercent: 2.9,
      holdingPeriod: '1~2시간',
      keyMetrics: [
        { label: '체결강도', value: '128.4%', class: 'positive' },
        { label: '기관', value: '+180억', class: 'positive' },
        { label: '외국인', value: '+95억', class: 'positive' }
      ]
    },
    {
      stockCode: '035720',
      stockName: '카카오',
      currentPrice: 42500,
      changeRate: 3.8,
      reasons: ['체결강도 124%', '저점 반등', '수급 전환'],
      expectedReturn: 2.5,
      stopLoss: 41200,
      stopLossPercent: 3.1,
      holdingPeriod: '30분~1시간',
      keyMetrics: [
        { label: '체결강도', value: '124.2%', class: 'positive' },
        { label: '거래량비', value: '195%', class: 'positive' },
        { label: '프로그램', value: '+85억', class: 'positive' }
      ]
    },
    {
      stockCode: '005930',
      stockName: '삼성전자',
      currentPrice: 74200,
      changeRate: 2.1,
      reasons: ['체결강도 121%', '대형주 안정', '외국인 유입'],
      expectedReturn: 1.8,
      stopLoss: 72500,
      stopLossPercent: 2.3,
      holdingPeriod: '1~2시간',
      keyMetrics: [
        { label: '체결강도', value: '121.5%', class: 'positive' },
        { label: '외국인', value: '+320억', class: 'positive' },
        { label: '기관', value: '+85억', class: 'positive' }
      ]
    }
  ],
  swing: [
    {
      stockCode: '005490',
      stockName: 'POSCO홀딩스',
      currentPrice: 385000,
      changeRate: -1.2,
      reasons: ['외국인 5일 연속', '눌림목 구간', '철강 수요 회복'],
      expectedReturn: 8.5,
      stopLoss: 365000,
      stopLossPercent: 5.2,
      holdingPeriod: '3~5일',
      keyMetrics: [
        { label: '연속매수', value: '5일', class: 'positive' },
        { label: '누적금액', value: '+1,250억', class: 'positive' },
        { label: 'RSI', value: '42 (과매도)', class: 'neutral' }
      ]
    },
    {
      stockCode: '105560',
      stockName: 'KB금융',
      currentPrice: 78500,
      changeRate: -0.8,
      reasons: ['기관 4일 연속', '저PBR 눌림목', '배당 매력'],
      expectedReturn: 6.2,
      stopLoss: 74500,
      stopLossPercent: 5.1,
      holdingPeriod: '2~4일',
      keyMetrics: [
        { label: '연속매수', value: '4일', class: 'positive' },
        { label: 'PBR', value: '0.42배', class: 'positive' },
        { label: '배당률', value: '5.8%', class: 'positive' }
      ]
    },
    {
      stockCode: '051910',
      stockName: 'LG화학',
      currentPrice: 298000,
      changeRate: -2.1,
      reasons: ['외국인 3일 연속', 'VWAP 근접', '배터리 회복'],
      expectedReturn: 7.8,
      stopLoss: 280000,
      stopLossPercent: 6.0,
      holdingPeriod: '3~5일',
      keyMetrics: [
        { label: '연속매수', value: '3일', class: 'positive' },
        { label: 'VWAP괴리', value: '-1.2%', class: 'neutral' },
        { label: '외국인', value: '+420억', class: 'positive' }
      ]
    },
    {
      stockCode: '035420',
      stockName: 'NAVER',
      currentPrice: 182000,
      changeRate: -1.5,
      reasons: ['기관 3일 연속', 'AI 성장 기대', '저점 매집'],
      expectedReturn: 5.5,
      stopLoss: 172000,
      stopLossPercent: 5.5,
      holdingPeriod: '2~3일',
      keyMetrics: [
        { label: '연속매수', value: '3일', class: 'positive' },
        { label: '기관', value: '+380억', class: 'positive' },
        { label: 'PER', value: '18.5배', class: 'neutral' }
      ]
    },
    {
      stockCode: '006400',
      stockName: '삼성SDI',
      currentPrice: 358000,
      changeRate: -0.5,
      reasons: ['외국인 3일 연속', '전고체 기대감', '조정 마무리'],
      expectedReturn: 6.0,
      stopLoss: 340000,
      stopLossPercent: 5.0,
      holdingPeriod: '3~4일',
      keyMetrics: [
        { label: '연속매수', value: '3일', class: 'positive' },
        { label: '누적금액', value: '+520억', class: 'positive' },
        { label: 'RSI', value: '38 (과매도)', class: 'neutral' }
      ]
    }
  ],
  trend: [
    {
      stockCode: '034020',
      stockName: '두산에너빌리티',
      currentPrice: 21500,
      changeRate: 3.2,
      reasons: ['흑자전환 확정', 'VWAP 상단', '원전 수주 기대'],
      expectedReturn: 25.0,
      stopLoss: 18500,
      stopLossPercent: 14.0,
      holdingPeriod: '2~4주',
      keyMetrics: [
        { label: '순이익', value: '흑자전환', class: 'positive' },
        { label: 'VWAP위치', value: '+3.5%', class: 'positive' },
        { label: '영업이익', value: '+180%', class: 'positive' }
      ]
    },
    {
      stockCode: '028300',
      stockName: 'HLB',
      currentPrice: 58000,
      changeRate: 2.8,
      reasons: ['실적 턴어라운드', 'VWAP 돌파', 'FDA 승인 기대'],
      expectedReturn: 35.0,
      stopLoss: 48000,
      stopLossPercent: 17.2,
      holdingPeriod: '3~6주',
      keyMetrics: [
        { label: '순이익', value: '흑자전환', class: 'positive' },
        { label: 'VWAP위치', value: '+5.2%', class: 'positive' },
        { label: '매출성장', value: '+85%', class: 'positive' }
      ]
    },
    {
      stockCode: '196170',
      stockName: '알테오젠',
      currentPrice: 185000,
      changeRate: 1.5,
      reasons: ['영업이익 급증', 'VWAP 지지', '기술수출 모멘텀'],
      expectedReturn: 20.0,
      stopLoss: 160000,
      stopLossPercent: 13.5,
      holdingPeriod: '2~4주',
      keyMetrics: [
        { label: '영업이익', value: '+320%', class: 'positive' },
        { label: 'VWAP위치', value: '+2.1%', class: 'positive' },
        { label: '외국인', value: '+850억', class: 'positive' }
      ]
    },
    {
      stockCode: '003670',
      stockName: '포스코퓨처엠',
      currentPrice: 245000,
      changeRate: 0.8,
      reasons: ['적자폭 대폭 감소', 'VWAP 근접', '양극재 수요'],
      expectedReturn: 18.0,
      stopLoss: 215000,
      stopLossPercent: 12.2,
      holdingPeriod: '2~3주',
      keyMetrics: [
        { label: '적자개선', value: '85% 감소', class: 'positive' },
        { label: 'VWAP위치', value: '+0.5%', class: 'neutral' },
        { label: '기관', value: '+320억', class: 'positive' }
      ]
    },
    {
      stockCode: '068270',
      stockName: '셀트리온',
      currentPrice: 172000,
      changeRate: 1.2,
      reasons: ['이익률 개선', 'VWAP 상단', '바이오시밀러 확대'],
      expectedReturn: 15.0,
      stopLoss: 155000,
      stopLossPercent: 9.9,
      holdingPeriod: '2~4주',
      keyMetrics: [
        { label: '영업이익률', value: '28%', class: 'positive' },
        { label: 'VWAP위치', value: '+1.8%', class: 'positive' },
        { label: 'PER', value: '22배', class: 'neutral' }
      ]
    }
  ],
  value: [
    {
      stockCode: '055550',
      stockName: '신한지주',
      currentPrice: 52000,
      changeRate: 0.5,
      reasons: ['PEG 0.35', '배당률 6.2%', 'ROE 12.5%'],
      expectedReturn: 25.0,
      stopLoss: 45000,
      stopLossPercent: 13.5,
      holdingPeriod: '1~3개월',
      keyMetrics: [
        { label: 'PEG', value: '0.35', class: 'positive' },
        { label: '배당률', value: '6.2%', class: 'positive' },
        { label: 'ROE', value: '12.5%', class: 'positive' }
      ]
    },
    {
      stockCode: '316140',
      stockName: '우리금융지주',
      currentPrice: 15800,
      changeRate: 0.3,
      reasons: ['초저PBR 0.38', '배당률 7.1%', '밸류업 수혜'],
      expectedReturn: 30.0,
      stopLoss: 13500,
      stopLossPercent: 14.6,
      holdingPeriod: '2~6개월',
      keyMetrics: [
        { label: 'PBR', value: '0.38배', class: 'positive' },
        { label: '배당률', value: '7.1%', class: 'positive' },
        { label: 'ROE', value: '10.8%', class: 'positive' }
      ]
    },
    {
      stockCode: '005380',
      stockName: '현대차',
      currentPrice: 258000,
      changeRate: 0.8,
      reasons: ['PEG 0.52', '글로벌 판매 호조', 'EV 전환 가속'],
      expectedReturn: 20.0,
      stopLoss: 225000,
      stopLossPercent: 12.8,
      holdingPeriod: '1~3개월',
      keyMetrics: [
        { label: 'PEG', value: '0.52', class: 'positive' },
        { label: 'PER', value: '5.2배', class: 'positive' },
        { label: 'ROE', value: '14.2%', class: 'positive' }
      ]
    },
    {
      stockCode: '000270',
      stockName: '기아',
      currentPrice: 128000,
      changeRate: 1.1,
      reasons: ['PEG 0.48', '배당률 4.5%', '실적 서프라이즈'],
      expectedReturn: 22.0,
      stopLoss: 112000,
      stopLossPercent: 12.5,
      holdingPeriod: '1~3개월',
      keyMetrics: [
        { label: 'PEG', value: '0.48', class: 'positive' },
        { label: '배당률', value: '4.5%', class: 'positive' },
        { label: 'ROE', value: '18.5%', class: 'positive' }
      ]
    },
    {
      stockCode: '017670',
      stockName: 'SK텔레콤',
      currentPrice: 58500,
      changeRate: 0.2,
      reasons: ['안정적 배당', 'AI 인프라 투자', '통신 독점'],
      expectedReturn: 15.0,
      stopLoss: 52000,
      stopLossPercent: 11.1,
      holdingPeriod: '3~6개월',
      keyMetrics: [
        { label: '배당률', value: '5.8%', class: 'positive' },
        { label: 'PER', value: '9.5배', class: 'positive' },
        { label: 'ROE', value: '8.2%', class: 'neutral' }
      ]
    }
  ]
};

const currentRecommendations = computed(() => recommendations[activeTab.value] || []);

// 시장 요약 데이터
const marketSentiment = ref({ text: '강세 (Risk-On)', class: 'positive' });
const foreignFlow = ref(2850);
const institutionFlow = ref(1420);
const leadingSector = ref('AI 반도체');

// 유틸리티 함수
const formatNumber = (value) => {
  if (!value) return '0';
  return Number(value).toLocaleString('ko-KR');
};

const formatTime = (date) => {
  if (!date) return '';
  return date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
};

const getRankClass = (index) => {
  if (index === 0) return 'gold';
  if (index === 1) return 'silver';
  if (index === 2) return 'bronze';
  return '';
};

const getReasonClass = (reason) => {
  if (reason.includes('연속') || reason.includes('매수')) return 'supply';
  if (reason.includes('체결강도') || reason.includes('거래량')) return 'momentum';
  if (reason.includes('흑자') || reason.includes('이익')) return 'fundamental';
  if (reason.includes('PEG') || reason.includes('PBR') || reason.includes('배당')) return 'value';
  if (reason.includes('VWAP') || reason.includes('RSI')) return 'technical';
  return 'theme';
};

const goToDetail = (stockCode) => {
  router.push(`/scalping-analysis?code=${stockCode}`);
};

onMounted(() => {
  lastUpdated.value = new Date();
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
</style>
