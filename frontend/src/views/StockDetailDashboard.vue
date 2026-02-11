<template>
  <div class="stock-detail-dashboard">
    <!-- Header -->
    <header class="detail-header">
      <div class="header-left">
        <BackButton />
        <div class="stock-info">
          <h1 class="stock-name">{{ stockName || '종목 검색' }}</h1>
          <span class="stock-code">{{ stockCode }}</span>
        </div>
      </div>
      <div class="header-center">
        <div class="price-info" v-if="priceInfo">
          <span class="current-price">{{ formatPrice(priceInfo.currentPrice) }}원</span>
          <span class="change-info" :class="priceClass">
            {{ priceInfo.changePrice >= 0 ? '+' : '' }}{{ formatPrice(priceInfo.changePrice) }}
            ({{ priceInfo.changeRate >= 0 ? '+' : '' }}{{ priceInfo.changeRate?.toFixed(2) }}%)
          </span>
        </div>
      </div>
      <div class="header-right">
        <div class="ai-score-box" :class="aiScoreClass">
          <span class="score-label">AI 점수</span>
          <span class="score-value">{{ aiAnalysis?.overallScore || '-' }}</span>
          <span class="score-badge">{{ aiAnalysis?.recommendation || '-' }}</span>
        </div>
      </div>
    </header>

    <!-- 검색바 -->
    <div class="search-section">
      <div class="search-bar">
        <input
          type="text"
          v-model="searchQuery"
          @keyup.enter="searchStock"
          placeholder="종목명 또는 종목코드 입력 (예: 삼성전자, 005930)"
        />
        <button @click="searchStock" :disabled="loading">
          {{ loading ? '분석 중...' : '종합 분석' }}
        </button>
      </div>
    </div>

    <!-- 로딩 -->
    <div v-if="loading" class="loading-overlay">
      <div class="loading-spinner"></div>
      <p>종합 데이터 분석 중...</p>
    </div>

    <!-- 메인 그리드 -->
    <div v-else-if="hasData" class="main-grid">
      <!-- Left: 차트존 -->
      <div class="chart-zone">
        <div class="zone-header">
          <h2>주가 차트</h2>
          <div class="ma-legend">
            <span class="ma5">MA5: {{ chartData?.ma5?.toLocaleString() }}</span>
            <span class="ma20">MA20: {{ chartData?.ma20?.toLocaleString() }}</span>
            <span class="vwap">VWAP: {{ chartData?.vwap?.toLocaleString() }}</span>
          </div>
        </div>
        <div class="chart-container">
          <div class="candlestick-chart">
            <div
              v-for="(candle, index) in displayCandles"
              :key="index"
              class="candle"
              :class="{ up: candle.close >= candle.open, down: candle.close < candle.open }"
              :style="getCandleStyle(candle)"
            >
              <div class="wick" :style="getWickStyle(candle)"></div>
              <div class="body" :style="getBodyStyle(candle)"></div>
            </div>
          </div>
        </div>
        <div class="volume-chart">
          <div
            v-for="(vol, index) in displayVolumes"
            :key="index"
            class="volume-bar"
            :class="{ up: displayCandles[index]?.close >= displayCandles[index]?.open }"
            :style="{ height: getVolumeHeight(vol.volume) + '%' }"
          ></div>
        </div>
      </div>

      <!-- Center: 수급/재무존 -->
      <div class="supply-financial-zone">
        <!-- 실시간 수급 -->
        <div class="supply-section">
          <h2>실시간 수급</h2>
          <div class="supply-grid">
            <!-- 체결강도 -->
            <div class="supply-card">
              <div class="card-header">
                <span class="card-icon">⚡</span>
                <span class="card-title">체결강도</span>
              </div>
              <div class="card-value" :class="volumeSignalClass">
                {{ supplyDemand?.volumePower?.toFixed(1) || '-' }}%
              </div>
              <div class="card-signal" :class="volumeSignalClass">
                {{ getVolumeSignalText(supplyDemand?.volumeSignal) }}
              </div>
            </div>

            <!-- 외국인 -->
            <div class="supply-card">
              <div class="card-header">
                <span class="card-icon">🌍</span>
                <span class="card-title">외국인</span>
              </div>
              <div class="card-value" :class="supplyDemand?.foreignNetBuy >= 0 ? 'positive' : 'negative'">
                {{ supplyDemand?.foreignNetBuy >= 0 ? '+' : '' }}{{ supplyDemand?.foreignNetBuy?.toFixed(1) || '0' }}억
              </div>
              <div class="card-sub" v-if="supplyDemand?.foreignConsecDays">
                {{ supplyDemand.foreignConsecDays }}일 연속
              </div>
            </div>

            <!-- 기관 -->
            <div class="supply-card">
              <div class="card-header">
                <span class="card-icon">🏢</span>
                <span class="card-title">기관</span>
              </div>
              <div class="card-value" :class="supplyDemand?.instNetBuy >= 0 ? 'positive' : 'negative'">
                {{ supplyDemand?.instNetBuy >= 0 ? '+' : '' }}{{ supplyDemand?.instNetBuy?.toFixed(1) || '0' }}억
              </div>
              <div class="card-sub" v-if="supplyDemand?.instConsecDays">
                {{ supplyDemand.instConsecDays }}일 연속
              </div>
            </div>

            <!-- 프로그램 -->
            <div class="supply-card">
              <div class="card-header">
                <span class="card-icon">🤖</span>
                <span class="card-title">프로그램</span>
              </div>
              <div class="card-value" :class="supplyDemand?.programNetBuy >= 0 ? 'positive' : 'negative'">
                {{ supplyDemand?.programNetBuy >= 0 ? '+' : '' }}{{ supplyDemand?.programNetBuy?.toFixed(1) || '0' }}억
              </div>
              <div class="card-sub" :class="getProgramTrendClass(supplyDemand?.programTrend)">
                {{ getProgramTrendText(supplyDemand?.programTrend) }}
              </div>
            </div>
          </div>
        </div>

        <!-- 핵심 재무 -->
        <div class="financial-section">
          <h2>핵심 재무</h2>
          <div class="financial-grid">
            <div class="fin-card">
              <span class="fin-label">PER</span>
              <span class="fin-value" :class="getPERClass(financial?.per)">
                {{ financial?.per?.toFixed(1) || '-' }}배
              </span>
            </div>
            <div class="fin-card">
              <span class="fin-label">PBR</span>
              <span class="fin-value" :class="getPBRClass(financial?.pbr)">
                {{ financial?.pbr?.toFixed(2) || '-' }}배
              </span>
            </div>
            <div class="fin-card">
              <span class="fin-label">EPS</span>
              <span class="fin-value">
                {{ formatPrice(financial?.eps) || '-' }}원
              </span>
            </div>
            <div class="fin-card">
              <span class="fin-label">BPS</span>
              <span class="fin-value">
                {{ formatPrice(financial?.bps) || '-' }}원
              </span>
            </div>
            <div class="fin-card wide">
              <span class="fin-label">시가총액</span>
              <span class="fin-value">
                {{ formatMarketCap(financial?.marketCap) }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- Right: AI/리스크존 -->
      <div class="ai-risk-zone">
        <!-- 리스크 게이지 (원형) -->
        <div class="risk-gauge-section" :class="riskStatusClass">
          <div class="gauge-header">
            <h2>리스크 분석</h2>
            <span class="risk-badge" :class="riskStatusClass">
              {{ getRiskStatusText(riskInfo?.riskStatus) }}
            </span>
          </div>

          <div class="gauge-container">
            <div class="gauge">
              <svg viewBox="0 0 200 120" class="gauge-svg">
                <!-- 배경 아크 -->
                <path
                  d="M 20 100 A 80 80 0 0 1 180 100"
                  fill="none"
                  stroke="#2a2a4a"
                  stroke-width="16"
                  stroke-linecap="round"
                />
                <!-- 컬러 아크 -->
                <path
                  d="M 20 100 A 80 80 0 0 1 180 100"
                  fill="none"
                  stroke="url(#riskGaugeGradient)"
                  stroke-width="16"
                  stroke-linecap="round"
                  :stroke-dasharray="gaugeArcLength"
                  :stroke-dashoffset="gaugeDashOffset"
                  class="gauge-arc"
                />
                <defs>
                  <linearGradient id="riskGaugeGradient" x1="0%" y1="0%" x2="100%" y2="0%">
                    <stop offset="0%" stop-color="#22c55e" />
                    <stop offset="50%" stop-color="#eab308" />
                    <stop offset="100%" stop-color="#ef4444" />
                  </linearGradient>
                </defs>
              </svg>
              <div class="gauge-value">
                <span class="score" :class="riskStatusClass">{{ riskInfo?.riskScore ?? '-' }}</span>
                <span class="label">/100</span>
              </div>
            </div>
            <div class="gauge-labels">
              <span class="safe">안전</span>
              <span class="warning">주의</span>
              <span class="danger">위험</span>
            </div>
          </div>

          <div class="risk-reason-box" v-if="riskInfo?.riskReason">
            <p>{{ riskInfo.riskReason }}</p>
          </div>

          <!-- 매수 금지 경고 -->
          <div v-if="riskInfo?.riskStatus === 'DANGER'" class="danger-warning">
            <span class="warning-icon">🚨</span>
            <div class="warning-text">
              <strong>매수 주의</strong>
              <p>리스크가 높아 신중한 판단이 필요합니다.</p>
            </div>
          </div>

          <!-- 공시/뉴스 요약 -->
          <div class="risk-summary">
            <div class="summary-item">
              <span class="item-icon">📋</span>
              <span class="item-label">위험 공시</span>
              <span class="item-value" :class="riskInfo?.dangerDisclosureCount > 0 ? 'danger' : ''">
                {{ riskInfo?.dangerDisclosureCount || 0 }}건
              </span>
            </div>
            <div class="summary-item">
              <span class="item-icon">📰</span>
              <span class="item-label">관련 뉴스</span>
              <span class="item-value">{{ riskInfo?.newsCount || 0 }}건</span>
            </div>
          </div>

          <!-- 뉴스 목록 -->
          <div class="news-list" v-if="riskInfo?.news?.length">
            <div v-for="(news, index) in riskInfo.news.slice(0, 5)" :key="index" class="news-item">
              <a :href="news.link" target="_blank">{{ truncate(news.title, 40) }}</a>
              <span class="news-date">{{ formatPubDate(news.pubDate) }}</span>
            </div>
          </div>
        </div>

        <!-- AI 매매 전략 -->
        <div class="ai-strategy-section">
          <h2>AI 매매 전략</h2>
          <div class="strategy-box" :class="aiRecommendationClass">
            <div class="strategy-header">
              <span class="strategy-signal">{{ aiAnalysis?.technicalSignal || '-' }}</span>
              <span class="strategy-rec">{{ aiAnalysis?.recommendation || '-' }}</span>
            </div>
            <p class="strategy-text">{{ aiAnalysis?.strategy || '-' }}</p>

            <!-- 매수/매도 근거 -->
            <div class="reasons-section" v-if="aiAnalysis?.buyReasons?.length || aiAnalysis?.sellReasons?.length">
              <div class="buy-reasons" v-if="aiAnalysis?.buyReasons?.length">
                <h4>매수 근거</h4>
                <ul>
                  <li v-for="(reason, i) in aiAnalysis.buyReasons" :key="'buy-'+i">{{ reason }}</li>
                </ul>
              </div>
              <div class="sell-reasons" v-if="aiAnalysis?.sellReasons?.length">
                <h4>매도 근거</h4>
                <ul>
                  <li v-for="(reason, i) in aiAnalysis.sellReasons" :key="'sell-'+i">{{ reason }}</li>
                </ul>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 데이터 없음 -->
    <div v-else class="empty-state">
      <div class="empty-icon">🔍</div>
      <h2>종목을 검색하세요</h2>
      <p>종목명 또는 종목코드를 입력하면 종합 분석 결과를 보여드립니다.</p>
    </div>

    <!-- 면책조항 -->
    <footer class="disclaimer" v-if="hasData">
      <p>본 분석은 AI 알고리즘에 의한 참고 자료이며, 투자 결정은 본인의 판단과 책임 하에 이루어져야 합니다.</p>
    </footer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import BackButton from '../components/BackButton.vue';
import { stockDetailAPI, stockAPI } from '../utils/api';

const route = useRoute();

// 상태
const loading = ref(false);
const searchQuery = ref('');
const stockCode = ref('');
const stockName = ref('');
const priceInfo = ref(null);
const supplyDemand = ref(null);
const financial = ref(null);
const riskInfo = ref(null);
const aiAnalysis = ref(null);
const chartData = ref(null);

const hasData = computed(() => priceInfo.value !== null);

// 차트 표시용 (최근 30개)
const displayCandles = computed(() => {
  if (!chartData.value?.candles) return [];
  return chartData.value.candles.slice(0, 30).reverse();
});

const displayVolumes = computed(() => {
  if (!chartData.value?.volumes) return [];
  return chartData.value.volumes.slice(0, 30).reverse();
});

// 스타일 클래스
const priceClass = computed(() => {
  if (!priceInfo.value) return '';
  return priceInfo.value.changeRate >= 0 ? 'positive' : 'negative';
});

const aiScoreClass = computed(() => {
  const score = aiAnalysis.value?.overallScore;
  if (!score) return '';
  if (score >= 70) return 'high';
  if (score >= 50) return 'medium';
  return 'low';
});

const volumeSignalClass = computed(() => {
  const signal = supplyDemand.value?.volumeSignal;
  if (signal === 'STRONG_BUY' || signal === 'BUY') return 'positive';
  if (signal === 'STRONG_SELL' || signal === 'SELL') return 'negative';
  return 'neutral';
});

const riskStatusClass = computed(() => {
  const status = riskInfo.value?.riskStatus;
  if (status === 'SAFE') return 'safe';
  if (status === 'WARNING') return 'warning';
  if (status === 'DANGER') return 'danger';
  return '';
});

const aiRecommendationClass = computed(() => {
  const rec = aiAnalysis.value?.recommendation;
  if (rec === 'BUY') return 'buy';
  if (rec === 'SELL') return 'sell';
  return 'hold';
});

// 리스크 게이지 계산
const gaugeArcLength = computed(() => 251.2); // 반원의 둘레 (π * 80)

const gaugeDashOffset = computed(() => {
  if (!riskInfo.value?.riskScore) return gaugeArcLength.value;
  const progress = riskInfo.value.riskScore / 100;
  return gaugeArcLength.value * (1 - progress);
});

// 종목 검색
const searchStock = async () => {
  if (!searchQuery.value.trim()) return;

  loading.value = true;
  try {
    // 종목코드 변환 (종목명 → 코드)
    let code = searchQuery.value.trim();
    let searchedName = null;  // 검색 시 찾은 종목명 보관

    if (!/^\d{6}$/.test(code)) {
      // 종목명으로 검색
      const searchResult = await stockAPI.searchStocks(code);
      if (searchResult.data.success && searchResult.data.data?.length > 0) {
        code = searchResult.data.data[0].stockCode;
        searchedName = searchResult.data.data[0].stockName;
        stockName.value = searchedName;
        console.log('[StockDetail] 종목명 검색 성공:', searchedName, code);
      } else {
        alert('종목을 찾을 수 없습니다.');
        loading.value = false;
        return;
      }
    }

    stockCode.value = code;

    // 종합 상세 조회
    const response = await stockDetailAPI.getSummary(code);
    if (response.data.success && response.data.data) {
      const data = response.data.data;
      // 종목명: API 응답 우선, 없으면 검색 결과, 없으면 코드
      stockName.value = data.stockName || searchedName || code;
      priceInfo.value = data.price;
      supplyDemand.value = data.supplyDemand;
      financial.value = data.financial;
      riskInfo.value = data.risk;
      aiAnalysis.value = data.aiAnalysis;
      chartData.value = data.chartData;

      console.log('[StockDetail] 데이터 로드 완료:', {
        stockName: stockName.value,
        supplyDemand: supplyDemand.value,
        volumePower: supplyDemand.value?.volumePower,
        foreignNetBuy: supplyDemand.value?.foreignNetBuy
      });
    }
  } catch (error) {
    console.error('종목 조회 오류:', error);
    alert('종목 조회에 실패했습니다.');
  } finally {
    loading.value = false;
  }
};

// 차트 스타일 계산
const chartPriceRange = computed(() => {
  if (!displayCandles.value.length) return { min: 0, max: 100 };
  const prices = displayCandles.value.flatMap(c => [c.high, c.low]);
  return {
    min: Math.min(...prices) * 0.98,
    max: Math.max(...prices) * 1.02
  };
});

const maxVolume = computed(() => {
  if (!displayVolumes.value.length) return 1;
  return Math.max(...displayVolumes.value.map(v => v.volume));
});

const getCandleStyle = (candle) => {
  const range = chartPriceRange.value;
  const height = ((Math.max(candle.open, candle.close) - Math.min(candle.open, candle.close)) / (range.max - range.min)) * 100;
  const bottom = ((Math.min(candle.open, candle.close) - range.min) / (range.max - range.min)) * 100;
  return { height: Math.max(height, 1) + '%', bottom: bottom + '%' };
};

const getWickStyle = (candle) => {
  const range = chartPriceRange.value;
  const height = ((candle.high - candle.low) / (range.max - range.min)) * 100;
  const bottom = ((candle.low - range.min) / (range.max - range.min)) * 100;
  return { height: height + '%', bottom: bottom + '%' };
};

const getBodyStyle = (candle) => {
  const range = chartPriceRange.value;
  const bodyTop = Math.max(candle.open, candle.close);
  const bodyBottom = Math.min(candle.open, candle.close);
  const height = ((bodyTop - bodyBottom) / (range.max - range.min)) * 100;
  const bottom = ((bodyBottom - range.min) / (range.max - range.min)) * 100;
  return { height: Math.max(height, 1) + '%', bottom: bottom + '%' };
};

const getVolumeHeight = (volume) => {
  return (volume / maxVolume.value) * 100;
};

// 포맷터
const formatPrice = (price) => {
  if (!price) return '-';
  return Number(price).toLocaleString('ko-KR');
};

const formatMarketCap = (cap) => {
  if (!cap) return '-';
  if (cap >= 10000) return (cap / 10000).toFixed(1) + '조';
  return cap.toLocaleString() + '억';
};

const formatPubDate = (pubDate) => {
  if (!pubDate) return '';
  try {
    const date = new Date(pubDate);
    return `${date.getMonth() + 1}/${date.getDate()}`;
  } catch {
    return pubDate.substring(0, 10);
  }
};

const truncate = (str, len) => {
  if (!str) return '';
  return str.length > len ? str.substring(0, len) + '...' : str;
};

// 신호 텍스트
const getVolumeSignalText = (signal) => {
  const map = {
    'STRONG_BUY': '강한 매수세',
    'BUY': '매수 우위',
    'NEUTRAL': '중립',
    'SELL': '매도 우위',
    'STRONG_SELL': '강한 매도세'
  };
  return map[signal] || '-';
};

const getProgramTrendText = (trend) => {
  const map = { 'UP': '상승 추세', 'DOWN': '하락 추세', 'FLAT': '보합' };
  return map[trend] || '-';
};

const getProgramTrendClass = (trend) => {
  if (trend === 'UP') return 'positive';
  if (trend === 'DOWN') return 'negative';
  return 'neutral';
};

const getRiskStatusText = (status) => {
  const map = { 'SAFE': '안전', 'WARNING': '주의', 'DANGER': '위험' };
  return map[status] || '-';
};

const getPERClass = (per) => {
  if (!per) return '';
  if (per > 0 && per < 10) return 'positive';
  if (per > 30) return 'negative';
  return '';
};

const getPBRClass = (pbr) => {
  if (!pbr) return '';
  if (pbr > 0 && pbr < 1) return 'positive';
  if (pbr > 3) return 'negative';
  return '';
};

// URL 파라미터 처리
onMounted(() => {
  const code = route.query.code || route.params.stockCode;
  if (code) {
    searchQuery.value = code;
    searchStock();
  }
});
</script>

<style scoped>
.stock-detail-dashboard {
  min-height: 100vh;
  background: linear-gradient(135deg, #0f0f23 0%, #1a1a3e 100%);
  color: #fff;
  padding: 20px;
}

/* Header */
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px;
  background: rgba(30, 30, 60, 0.8);
  border-radius: 16px;
  margin-bottom: 20px;
  flex-wrap: wrap;
  gap: 16px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stock-info {
  display: flex;
  flex-direction: column;
}

.stock-name {
  font-size: 1.8rem;
  font-weight: 700;
  margin: 0;
}

.stock-code {
  color: #888;
  font-size: 0.9rem;
}

.price-info {
  text-align: center;
}

.current-price {
  font-size: 2rem;
  font-weight: 700;
  font-family: 'Monaco', monospace;
}

.change-info {
  display: block;
  font-size: 1.1rem;
  margin-top: 4px;
}

.change-info.positive { color: #ef4444; }
.change-info.negative { color: #3b82f6; }

.ai-score-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px 24px;
  background: rgba(50, 50, 80, 0.5);
  border-radius: 12px;
  border: 2px solid #4a4a7a;
}

.ai-score-box.high { border-color: #22c55e; }
.ai-score-box.medium { border-color: #eab308; }
.ai-score-box.low { border-color: #ef4444; }

.score-label {
  font-size: 0.8rem;
  color: #888;
}

.score-value {
  font-size: 2.5rem;
  font-weight: 800;
  font-family: 'Monaco', monospace;
}

.ai-score-box.high .score-value { color: #22c55e; }
.ai-score-box.medium .score-value { color: #eab308; }
.ai-score-box.low .score-value { color: #ef4444; }

.score-badge {
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 0.8rem;
  font-weight: 600;
  background: rgba(255,255,255,0.1);
}

/* Search */
.search-section {
  margin-bottom: 20px;
}

.search-bar {
  display: flex;
  gap: 12px;
  max-width: 600px;
  margin: 0 auto;
}

.search-bar input {
  flex: 1;
  padding: 14px 20px;
  background: rgba(30, 30, 60, 0.8);
  border: 1px solid #3a3a6a;
  border-radius: 12px;
  color: #fff;
  font-size: 1rem;
}

.search-bar input::placeholder {
  color: #666;
}

.search-bar button {
  padding: 14px 28px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border: none;
  border-radius: 12px;
  color: #fff;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.2s;
}

.search-bar button:hover:not(:disabled) {
  transform: scale(1.02);
}

.search-bar button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Loading */
.loading-overlay {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  color: #888;
}

.loading-spinner {
  width: 50px;
  height: 50px;
  border: 4px solid #3a3a6a;
  border-top-color: #667eea;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin-bottom: 16px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Main Grid */
.main-grid {
  display: grid;
  grid-template-columns: 1.2fr 1fr 1fr;
  gap: 20px;
}

@media (max-width: 1400px) {
  .main-grid {
    grid-template-columns: 1fr 1fr;
  }
  .chart-zone {
    grid-column: span 2;
  }
}

@media (max-width: 900px) {
  .main-grid {
    grid-template-columns: 1fr;
  }
  .chart-zone {
    grid-column: span 1;
  }
}

/* Zone Common */
.chart-zone, .supply-financial-zone, .ai-risk-zone {
  background: rgba(30, 30, 60, 0.6);
  border-radius: 16px;
  padding: 20px;
  border: 1px solid #2a2a5a;
}

.zone-header, h2 {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  font-size: 1.1rem;
  font-weight: 600;
}

/* Chart Zone */
.ma-legend {
  display: flex;
  gap: 12px;
  font-size: 0.75rem;
}

.ma5 { color: #f59e0b; }
.ma20 { color: #3b82f6; }
.vwap { color: #a855f7; }

.chart-container {
  height: 250px;
  position: relative;
  background: rgba(0,0,0,0.2);
  border-radius: 8px;
  padding: 10px;
  overflow: hidden;
}

.candlestick-chart {
  display: flex;
  justify-content: space-around;
  align-items: flex-end;
  height: 100%;
  position: relative;
}

.candle {
  width: 8px;
  position: relative;
}

.candle .wick {
  position: absolute;
  width: 1px;
  left: 50%;
  transform: translateX(-50%);
  background: currentColor;
}

.candle .body {
  position: absolute;
  width: 100%;
  border-radius: 1px;
}

.candle.up { color: #ef4444; }
.candle.up .body { background: #ef4444; }

.candle.down { color: #3b82f6; }
.candle.down .body { background: #3b82f6; }

.volume-chart {
  display: flex;
  justify-content: space-around;
  align-items: flex-end;
  height: 60px;
  margin-top: 8px;
  background: rgba(0,0,0,0.2);
  border-radius: 4px;
  padding: 4px;
}

.volume-bar {
  width: 8px;
  background: #4a4a8a;
  border-radius: 2px;
  transition: height 0.2s;
}

.volume-bar.up {
  background: rgba(239, 68, 68, 0.5);
}

/* Supply Section */
.supply-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.supply-card {
  background: rgba(50, 50, 80, 0.5);
  border-radius: 12px;
  padding: 16px;
  text-align: center;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-bottom: 8px;
}

.card-icon {
  font-size: 1.2rem;
}

.card-title {
  font-size: 0.85rem;
  color: #aaa;
}

.card-value {
  font-size: 1.4rem;
  font-weight: 700;
  font-family: 'Monaco', monospace;
}

.card-signal, .card-sub {
  font-size: 0.75rem;
  margin-top: 4px;
}

.positive { color: #ef4444; }
.negative { color: #3b82f6; }
.neutral { color: #888; }

/* Financial Section */
.financial-section {
  margin-top: 20px;
}

.financial-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.fin-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: rgba(50, 50, 80, 0.3);
  border-radius: 8px;
}

.fin-card.wide {
  grid-column: span 2;
}

.fin-label {
  color: #888;
  font-size: 0.85rem;
}

.fin-value {
  font-weight: 600;
  font-family: 'Monaco', monospace;
}

/* Risk Gauge Section */
.risk-gauge-section {
  padding: 20px;
  border-radius: 16px;
  background: linear-gradient(135deg, #1a1a3a 0%, #0f0f23 100%);
  border: 2px solid #2a2a4a;
  margin-bottom: 20px;
  transition: all 0.3s ease;
}

.risk-gauge-section.safe { border-color: #22c55e; box-shadow: 0 0 30px rgba(34, 197, 94, 0.15); }
.risk-gauge-section.warning { border-color: #eab308; box-shadow: 0 0 30px rgba(234, 179, 8, 0.15); }
.risk-gauge-section.danger { border-color: #ef4444; box-shadow: 0 0 30px rgba(239, 68, 68, 0.2); }

.gauge-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.gauge-header h2 {
  margin: 0;
  font-size: 1.1rem;
}

.risk-badge {
  padding: 6px 16px;
  border-radius: 20px;
  font-size: 0.85rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 1px;
}

.risk-badge.safe { background: linear-gradient(135deg, #22c55e 0%, #16a34a 100%); color: #fff; }
.risk-badge.warning { background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%); color: #000; }
.risk-badge.danger { background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%); color: #fff; animation: blink-danger 1s ease-in-out infinite; }

@keyframes blink-danger {
  0%, 100% { opacity: 1; box-shadow: 0 0 20px rgba(239, 68, 68, 0.8); }
  50% { opacity: 0.8; box-shadow: 0 0 30px rgba(239, 68, 68, 1); }
}

.gauge-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 20px;
}

.gauge {
  position: relative;
  width: 200px;
  height: 120px;
}

.gauge-svg {
  width: 100%;
  height: 100%;
}

.gauge-arc {
  transition: stroke-dashoffset 1s ease-out;
}

.gauge-value {
  position: absolute;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  text-align: center;
}

.gauge-value .score {
  font-size: 3rem;
  font-weight: 800;
  font-family: 'Monaco', 'Consolas', monospace;
}

.gauge-value .score.safe { color: #22c55e; }
.gauge-value .score.warning { color: #eab308; }
.gauge-value .score.danger { color: #ef4444; }

.gauge-value .label {
  font-size: 1rem;
  color: #666;
}

.gauge-labels {
  display: flex;
  justify-content: space-between;
  width: 200px;
  margin-top: 8px;
}

.gauge-labels span {
  font-size: 0.75rem;
  font-weight: 600;
}

.gauge-labels .safe { color: #22c55e; }
.gauge-labels .warning { color: #eab308; }
.gauge-labels .danger { color: #ef4444; }

.risk-reason-box {
  padding: 14px;
  background: rgba(50, 50, 80, 0.3);
  border-radius: 10px;
  margin-bottom: 16px;
}

.risk-reason-box p {
  margin: 0;
  font-size: 0.9rem;
  color: #ccc;
  line-height: 1.5;
}

.danger-warning {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px;
  background: rgba(239, 68, 68, 0.15);
  border: 1px solid rgba(239, 68, 68, 0.4);
  border-radius: 10px;
  margin-bottom: 16px;
}

.warning-icon {
  font-size: 1.5rem;
  flex-shrink: 0;
}

.warning-text strong {
  color: #ef4444;
  font-size: 1rem;
  display: block;
  margin-bottom: 4px;
}

.warning-text p {
  margin: 0;
  font-size: 0.85rem;
  color: #ff8a8a;
}

.risk-summary {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.summary-item {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px;
  background: rgba(50, 50, 80, 0.3);
  border-radius: 8px;
}

.item-value.danger { color: #ef4444; font-weight: 600; }

.news-list {
  max-height: 180px;
  overflow-y: auto;
  padding-right: 4px;
}

.news-list::-webkit-scrollbar {
  width: 4px;
}

.news-list::-webkit-scrollbar-track {
  background: rgba(50, 50, 80, 0.3);
  border-radius: 2px;
}

.news-list::-webkit-scrollbar-thumb {
  background: #4a4a8a;
  border-radius: 2px;
}

.news-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid rgba(255,255,255,0.05);
}

.news-item a {
  color: #a5b4fc;
  text-decoration: none;
  font-size: 0.85rem;
}

.news-item a:hover {
  text-decoration: underline;
}

.news-date {
  color: #666;
  font-size: 0.75rem;
}

/* AI Strategy Section */
.ai-strategy-section {
  margin-top: 20px;
}

.strategy-box {
  padding: 20px;
  border-radius: 12px;
  background: rgba(50, 50, 80, 0.5);
}

.strategy-box.buy { border: 2px solid #22c55e; }
.strategy-box.sell { border: 2px solid #ef4444; }
.strategy-box.hold { border: 2px solid #6b7280; }

.strategy-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.strategy-signal {
  padding: 4px 12px;
  background: rgba(255,255,255,0.1);
  border-radius: 8px;
  font-size: 0.85rem;
}

.strategy-rec {
  font-size: 1.2rem;
  font-weight: 700;
}

.strategy-box.buy .strategy-rec { color: #22c55e; }
.strategy-box.sell .strategy-rec { color: #ef4444; }
.strategy-box.hold .strategy-rec { color: #6b7280; }

.strategy-text {
  font-size: 0.95rem;
  color: #ccc;
  line-height: 1.6;
  margin: 0;
}

.reasons-section {
  margin-top: 16px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.buy-reasons, .sell-reasons {
  padding: 12px;
  border-radius: 8px;
  background: rgba(0,0,0,0.2);
}

.buy-reasons h4 { color: #22c55e; margin: 0 0 8px 0; font-size: 0.85rem; }
.sell-reasons h4 { color: #ef4444; margin: 0 0 8px 0; font-size: 0.85rem; }

.reasons-section ul {
  margin: 0;
  padding-left: 16px;
}

.reasons-section li {
  font-size: 0.8rem;
  color: #aaa;
  margin-bottom: 4px;
}

/* Empty State */
.empty-state {
  text-align: center;
  padding: 80px 20px;
  color: #666;
}

.empty-icon {
  font-size: 4rem;
  margin-bottom: 16px;
}

.empty-state h2 {
  display: block;
  margin-bottom: 8px;
}

/* Disclaimer */
.disclaimer {
  text-align: center;
  padding: 20px;
  margin-top: 20px;
  color: #666;
  font-size: 0.8rem;
}
</style>
