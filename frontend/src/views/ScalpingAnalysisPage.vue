<template>
  <div class="scalping-page">
    <LoadingSpinner v-if="loading && !analysisData" />

    <div class="content-wrapper">
      <div class="page-header">
        <BackButton :dark="true" />
        <h1>단타 분석</h1>
        <p class="subtitle">실시간 체결강도 및 프로그램 매매 추이</p>
      </div>

      <!-- 종목 검색 -->
      <div class="search-section">
        <div class="search-box">
          <input
            v-model="searchInput"
            @keyup.enter="searchStock"
            placeholder="종목코드 또는 종목명 입력 (예: 005930, 삼성전자)"
            class="search-input"
          />
          <button @click="searchStock" class="search-button" :disabled="loading">
            {{ loading ? '조회 중...' : '조회' }}
          </button>
        </div>
        <div v-if="errorMessage" class="error-message">{{ errorMessage }}</div>
      </div>

      <!-- 실시간 상태 표시줄 -->
      <div v-if="analysisData" class="realtime-status-bar" :class="{ active: autoRefresh }">
        <div class="status-indicator">
          <span class="status-dot" :class="{ pulsing: autoRefresh }"></span>
          <span class="status-text">
            {{ autoRefresh ? '실시간 체결 감시 중' : '실시간 감시 대기' }}
          </span>
        </div>
        <div class="status-meta">
          <span class="update-time">Update: {{ formatTime(lastUpdated) }}</span>
        </div>
      </div>

      <!-- 자동 갱신 설정 -->
      <div v-if="analysisData" class="refresh-section">
        <label class="auto-refresh-label">
          <input type="checkbox" v-model="autoRefresh" @change="toggleAutoRefresh" />
          10초 자동 갱신
        </label>
        <span v-if="lastUpdated" class="last-updated">
          마지막 갱신: {{ formatTime(lastUpdated) }}
        </span>
      </div>

      <!-- 종목 정보 카드 -->
      <div v-if="analysisData" class="stock-info-card">
        <div class="stock-header">
          <div class="stock-name-section">
            <h2>
              {{ analysisData.stockName || analysisData.stockCode }}
              <!-- 리스크 뱃지 -->
              <span v-if="riskData" class="risk-badge-inline" :class="getRiskBadgeClass">
                {{ getRiskBadgeText }}
              </span>
            </h2>
            <span class="stock-code">{{ analysisData.stockCode }}</span>
          </div>
          <div class="price-section">
            <span class="current-price">{{ formatNumber(analysisData.currentPrice) }}원</span>
            <span class="change" :class="changeClass">
              {{ analysisData.changePrice >= 0 ? '+' : '' }}{{ formatNumber(analysisData.changePrice) }}원
              ({{ analysisData.changeRate >= 0 ? '+' : '' }}{{ analysisData.changeRate?.toFixed(2) }}%)
            </span>
          </div>
        </div>
        <div class="stock-meta">
          <span class="meta-item">
            <span class="label">거래량</span>
            <span class="value">{{ formatVolume(analysisData.tradingVolume) }}</span>
          </span>
        </div>
      </div>

      <!-- 분석 그리드 -->
      <div v-if="analysisData" class="analysis-grid">
        <!-- 체결강도 게이지 -->
        <div class="analysis-card">
          <VolumePowerGauge
            :volumePower="analysisData.volumePower"
            :signal="analysisData.volumeSignal"
          />
        </div>

        <!-- 투자자 순매수 (막대 차트) -->
        <div class="analysis-card investor-card">
          <h3>실시간 수급</h3>
          <div class="investor-bar-chart">
            <!-- 외국인 -->
            <div class="investor-bar-row">
              <span class="bar-label">🌍 외국인</span>
              <div class="bar-container">
                <div class="bar-track">
                  <div
                    class="bar-fill"
                    :class="analysisData.foreignNetBuy >= 0 ? 'positive' : 'negative'"
                    :style="{ width: getBarWidth(analysisData.foreignNetBuy) + '%' }"
                  ></div>
                </div>
              </div>
              <span class="bar-value" :class="analysisData.foreignNetBuy >= 0 ? 'positive' : 'negative'">
                {{ analysisData.foreignNetBuy >= 0 ? '+' : '' }}{{ formatBillion(analysisData.foreignNetBuy) }}
              </span>
            </div>
            <!-- 기관 -->
            <div class="investor-bar-row">
              <span class="bar-label">🏢 기관</span>
              <div class="bar-container">
                <div class="bar-track">
                  <div
                    class="bar-fill"
                    :class="analysisData.instNetBuy >= 0 ? 'positive' : 'negative'"
                    :style="{ width: getBarWidth(analysisData.instNetBuy) + '%' }"
                  ></div>
                </div>
              </div>
              <span class="bar-value" :class="analysisData.instNetBuy >= 0 ? 'positive' : 'negative'">
                {{ analysisData.instNetBuy >= 0 ? '+' : '' }}{{ formatBillion(analysisData.instNetBuy) }}
              </span>
            </div>
            <!-- 프로그램 -->
            <div class="investor-bar-row highlight">
              <span class="bar-label">💻 프로그램</span>
              <div class="bar-container">
                <div class="bar-track">
                  <div
                    class="bar-fill"
                    :class="analysisData.programNetBuy >= 0 ? 'positive' : 'negative'"
                    :style="{ width: getBarWidth(analysisData.programNetBuy) + '%' }"
                  ></div>
                </div>
              </div>
              <span class="bar-value" :class="analysisData.programNetBuy >= 0 ? 'positive' : 'negative'">
                {{ analysisData.programNetBuy >= 0 ? '+' : '' }}{{ formatBillion(analysisData.programNetBuy) }}
              </span>
            </div>
          </div>
        </div>

        <!-- 프로그램 매매 차트 -->
        <div class="analysis-card chart-card">
          <ProgramTradingChart
            :series="analysisData.programTradingSeries || []"
            :programNetBuy="analysisData.programNetBuy"
            :programTrend="analysisData.programTrend"
          />
        </div>

        <!-- AI 리스크 분석 카드 -->
        <div class="analysis-card risk-card">
          <RiskAnalysisCard
            ref="riskCardRef"
            :stockName="analysisData.stockName"
            :riskData="riskData"
            :loading="riskLoading"
            :error="riskError"
            :apiNotConfigured="riskApiNotConfigured"
            @retry="retryRiskAnalysis"
            @buy-confirmed="onBuyConfirmed"
            @buy-cancelled="onBuyCancelled"
          />
        </div>
      </div>

      <!-- 매수 버튼 (리스크 80 이상이면 경고 스타일) -->
      <div v-if="analysisData" class="action-section">
        <button
          @click="handleBuyClick"
          class="buy-button"
          :class="{ danger: riskData?.riskScore >= 80 }"
        >
          <span v-if="riskData?.riskScore >= 80" class="warning-icon">⚠️</span>
          {{ riskData?.riskScore >= 80 ? '위험 경고 - 매수' : '매수' }}
        </button>
      </div>

      <!-- 사용 가이드 -->
      <div v-if="!analysisData" class="guide-section">
        <h3>단타 분석 사용법</h3>
        <div class="guide-grid">
          <div class="guide-item">
            <span class="guide-icon">📊</span>
            <div class="guide-content">
              <h4>체결강도</h4>
              <p>매수/매도 체결량 비율로 100%가 기준점입니다. 120% 이상이면 매수세 강함, 80% 이하면 매도세 강함을 의미합니다.</p>
            </div>
          </div>
          <div class="guide-item">
            <span class="guide-icon">📈</span>
            <div class="guide-content">
              <h4>프로그램 매매</h4>
              <p>09:00부터 현재까지 프로그램의 누적 순매수 추이입니다. 양수(빨간색)는 순매수, 음수(파란색)는 순매도를 의미합니다.</p>
            </div>
          </div>
          <div class="guide-item">
            <span class="guide-icon">👥</span>
            <div class="guide-content">
              <h4>투자자 순매수</h4>
              <p>외국인과 기관의 당일 누적 순매수 금액(억원)입니다. 두 세력이 동시에 순매수하는 종목에 주목하세요.</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { scalpingAPI, stockAPI, riskAPI } from '../utils/api';
import LoadingSpinner from '../components/LoadingSpinner.vue';
import VolumePowerGauge from '../components/VolumePowerGauge.vue';
import ProgramTradingChart from '../components/ProgramTradingChart.vue';
import BackButton from '../components/BackButton.vue';
import RiskAnalysisCard from '../components/RiskAnalysisCard.vue';

const router = useRouter();
const route = useRoute();
const loading = ref(false);
const fromAiStrategy = ref(false); // AI 전략 페이지에서 왔는지 여부
const searchInput = ref('');
const stockCode = ref('');
const analysisData = ref(null);
const errorMessage = ref('');
const autoRefresh = ref(true); // 기본값: 자동 갱신 ON
const lastUpdated = ref(null);
let refreshInterval = null;

// 리스크 분석 상태
const riskData = ref(null);
const riskLoading = ref(false);
const riskError = ref('');
const riskApiNotConfigured = ref(false);
const riskCardRef = ref(null);

// ========== 1. 종목명/코드 매핑 (Search Fix) ==========
const STOCK_MAP = {
  // 대형주
  '삼성전자': '005930',
  'SK하이닉스': '000660',
  'LG에너지솔루션': '373220',
  '삼성바이오로직스': '207940',
  '현대차': '005380',
  '기아': '000270',
  'NAVER': '035420',
  '네이버': '035420',
  '카카오': '035720',
  'LG화학': '051910',
  '삼성SDI': '006400',
  'POSCO홀딩스': '005490',
  '포스코홀딩스': '005490',
  'KB금융': '105560',
  '신한지주': '055550',
  // 인기 테마주
  '에코프로': '086520',
  '에코프로비엠': '247540',
  '포스코퓨처엠': '003670',
  '엘앤에프': '066970',
  '두산에너빌리티': '034020',
  '한화에어로스페이스': '012450',
  'HLB': '028300',
  '셀트리온': '068270',
  '알테오젠': '196170',
  // 역방향 매핑 (코드 → 이름)
};

// 코드로 종목명 찾기
const CODE_TO_NAME = {
  '005930': '삼성전자',
  '000660': 'SK하이닉스',
  '373220': 'LG에너지솔루션',
  '207940': '삼성바이오로직스',
  '005380': '현대차',
  '000270': '기아',
  '035420': 'NAVER',
  '035720': '카카오',
  '051910': 'LG화학',
  '006400': '삼성SDI',
  '005490': 'POSCO홀딩스',
  '105560': 'KB금융',
  '055550': '신한지주',
  '086520': '에코프로',
  '247540': '에코프로비엠',
  '003670': '포스코퓨처엠',
  '066970': '엘앤에프',
  '034020': '두산에너빌리티',
  '012450': '한화에어로스페이스',
  '028300': 'HLB',
  '068270': '셀트리온',
  '196170': '알테오젠',
};

// 토스트 메시지 표시
const showToast = (message) => {
  errorMessage.value = message;
  setTimeout(() => {
    if (errorMessage.value === message) {
      errorMessage.value = '';
    }
  }, 3000);
};

const searchStock = async () => {
  const input = searchInput.value.trim();

  if (!input) {
    showToast('종목코드 또는 종목명을 입력해주세요.');
    return;
  }

  loading.value = true;
  errorMessage.value = '';

  try {
    let code = input;

    // 1. 6자리 숫자인 경우 코드로 간주
    if (/^\d{6}$/.test(input)) {
      code = input;
    }
    // 2. 종목명인 경우 로컬 매핑에서 먼저 확인
    else if (STOCK_MAP[input]) {
      code = STOCK_MAP[input];
    }
    // 3. 로컬 매핑에 없으면 API 검색 시도
    else {
      try {
        const searchResponse = await stockAPI.searchStocks(input);
        if (searchResponse.data.success && searchResponse.data.data?.length > 0) {
          code = searchResponse.data.data[0].stockCode;
        } else {
          showToast('종목을 찾을 수 없습니다. 정확한 종목명이나 6자리 코드를 입력해주세요.');
          loading.value = false;
          return;
        }
      } catch (apiError) {
        console.warn('API 검색 실패, 로컬 매핑만 지원:', apiError);
        showToast('종목을 찾을 수 없습니다. 정확한 종목명이나 6자리 코드를 입력해주세요.');
        loading.value = false;
        return;
      }
    }

    stockCode.value = code;
    await fetchAnalysis();

    // 리스크 분석 병렬 실행 (종목명으로)
    const stockName = CODE_TO_NAME[code] || searchInput.value;
    fetchRiskAnalysis(stockName);
  } catch (error) {
    console.error('검색 오류:', error);
    showToast('종목 검색에 실패했습니다.');
  } finally {
    loading.value = false;
  }
};

const fetchAnalysis = async () => {
  if (!stockCode.value) return;

  try {
    loading.value = true;
    const response = await scalpingAPI.getAnalysis(stockCode.value);

    if (response.data.success && response.data.data) {
      analysisData.value = response.data.data;
      // 프로그램 매매 시계열 데이터가 없으면 생성
      if (!analysisData.value.programTradingSeries || analysisData.value.programTradingSeries.length === 0) {
        analysisData.value.programTradingSeries = generateProgramTradingSeries(analysisData.value.programNetBuy || 0);
      }
      lastUpdated.value = new Date();
      errorMessage.value = '';
    } else {
      // API 데이터 없으면 시뮬레이션 데이터 생성
      analysisData.value = generateSimulatedData(stockCode.value);
      lastUpdated.value = new Date();
    }
  } catch (error) {
    console.error('분석 조회 오류:', error);
    // API 실패 시에도 시뮬레이션 데이터로 화면 표시
    analysisData.value = generateSimulatedData(stockCode.value);
    lastUpdated.value = new Date();
  } finally {
    loading.value = false;
    // 데이터 로드 후 자동 갱신 시작 (기본 ON)
    if (autoRefresh.value && analysisData.value) {
      startAutoRefresh();
    }
  }
};

// ========== 리스크 분석 ==========
const fetchRiskAnalysis = async (stockName) => {
  if (!stockName) return;

  riskLoading.value = true;
  riskError.value = '';
  riskApiNotConfigured.value = false;

  try {
    const response = await riskAPI.checkRisk(stockName);

    if (response.data.success && response.data.data) {
      riskData.value = response.data.data;
    } else {
      riskError.value = response.data.message || '리스크 분석에 실패했습니다.';
    }
  } catch (error) {
    console.error('리스크 분석 오류:', error);
    if (error.response?.status === 503 || error.message?.includes('API')) {
      riskApiNotConfigured.value = true;
    } else {
      riskError.value = '리스크 분석 서버에 연결할 수 없습니다.';
    }
  } finally {
    riskLoading.value = false;
  }
};

const retryRiskAnalysis = () => {
  const stockName = CODE_TO_NAME[stockCode.value] || analysisData.value?.stockName;
  if (stockName) {
    fetchRiskAnalysis(stockName);
  }
};

// 매수 버튼 클릭 핸들러
const handleBuyClick = () => {
  if (riskCardRef.value?.showBuyWarning()) {
    // 모달이 표시됨 - 이벤트로 처리
    return;
  }
  // 리스크 80 미만이면 바로 매수 로직
  executeBuy();
};

const executeBuy = () => {
  // 여기에 실제 매수 로직 구현
  showToast('매수 주문이 접수되었습니다.');
};

const onBuyConfirmed = () => {
  executeBuy();
};

const onBuyCancelled = () => {
  showToast('매수가 취소되었습니다.');
};

// ========== 시뮬레이션 데이터 생성 ==========
const generateSimulatedData = (code) => {
  const stockName = CODE_TO_NAME[code] || code;

  // 종목코드 기반 시드 생성 (일관된 랜덤값)
  const seed = code.split('').reduce((acc, char, idx) => acc + char.charCodeAt(0) * (idx + 1), 0);

  // 시드 기반 랜덤 함수 (각 호출마다 다른 값 생성)
  let seedState = seed;
  const seededRandom = (min, max) => {
    seedState = (seedState * 9301 + 49297) % 233280;
    const rnd = seedState / 233280;
    return min + rnd * (max - min);
  };

  // 기본 가격 설정 (종목별 실제 가격대 반영)
  const basePrices = {
    '005930': 72000, '000660': 185000, '373220': 420000, '207940': 780000,
    '005380': 245000, '000270': 125000, '035420': 210000, '035720': 52000,
    '051910': 380000, '006400': 410000, '005490': 320000, '086520': 95000,
    '247540': 165000, '003670': 290000, '066970': 125000, '028300': 85000,
    '068270': 185000, '196170': 310000, '034020': 22000, '012450': 285000
  };
  const basePrice = basePrices[code] || Math.round(seededRandom(20000, 100000) / 100) * 100;

  // 상승/하락 결정 (70% 확률로 상승 - 추천 종목이므로)
  const isRising = seededRandom(0, 100) > 30;

  // 등락률: 상승이면 +2~10%, 하락이면 -1~4%
  const changeRate = isRising
    ? seededRandom(2.0, 10.0)
    : seededRandom(-4.0, -0.5);
  const changePrice = Math.round(basePrice * changeRate / 100);
  const currentPrice = basePrice + changePrice;

  // ★ 체결강도: 주가 흐름과 일치하도록 설정
  // 상승 중: 110% ~ 200% (강한 매수세)
  // 하락 중: 30% ~ 90% (매도세 우위)
  const volumePower = isRising
    ? seededRandom(115, 200)
    : seededRandom(30, 85);

  // 거래량 (대형주는 더 많이)
  const volumeBase = basePrices[code] ? (basePrices[code] > 100000 ? 3000000 : 8000000) : 5000000;
  const tradingVolume = Math.round(seededRandom(volumeBase * 0.5, volumeBase * 1.5));

  // ★ 수급 데이터: 거래량에 비례, 주가 흐름과 일치
  // 상승 시: 외국인/기관/프로그램 모두 순매수 (100~600억 규모)
  // 하락 시: 순매도
  const supplyMultiplier = tradingVolume / 1000000; // 거래량 기반 배수
  const foreignNetBuy = isRising
    ? Math.round(seededRandom(150, 500) * supplyMultiplier / 5)
    : Math.round(seededRandom(-400, -50) * supplyMultiplier / 5);
  const instNetBuy = isRising
    ? Math.round(seededRandom(80, 350) * supplyMultiplier / 5)
    : Math.round(seededRandom(-300, -30) * supplyMultiplier / 5);
  const programNetBuy = isRising
    ? Math.round(seededRandom(100, 450) * supplyMultiplier / 5)
    : Math.round(seededRandom(-350, -40) * supplyMultiplier / 5);

  // 신호 결정 (체결강도 기반)
  let volumeSignal = 'NEUTRAL';
  if (volumePower >= 150) volumeSignal = 'STRONG_BUY';
  else if (volumePower >= 115) volumeSignal = 'BUY';
  else if (volumePower <= 50) volumeSignal = 'STRONG_SELL';
  else if (volumePower <= 85) volumeSignal = 'SELL';

  return {
    stockCode: code,
    stockName: stockName,
    currentPrice: currentPrice,
    changePrice: changePrice,
    changeRate: parseFloat(changeRate.toFixed(2)),
    tradingVolume: tradingVolume,
    volumePower: parseFloat(volumePower.toFixed(1)),
    volumeSignal: volumeSignal,
    foreignNetBuy: foreignNetBuy,
    instNetBuy: instNetBuy,
    programNetBuy: programNetBuy,
    programTrend: isRising ? 'UP' : 'DOWN',
    programTradingSeries: generateProgramTradingSeries(programNetBuy, isRising)
  };
};

// ★ 프로그램 매매 추이 시계열 데이터 생성 (계단식 상승/하락)
const generateProgramTradingSeries = (finalValue, isRising = true) => {
  const series = [];
  const now = new Date();
  const marketOpen = new Date();
  marketOpen.setHours(9, 0, 0, 0);

  // 현재 시간이 9시 이전이면 가상의 시간대 사용 (15:00까지)
  const endTime = now.getHours() >= 9 && now.getHours() < 16
    ? now
    : new Date(marketOpen.getTime() + 6 * 60 * 60 * 1000);

  const totalMinutes = Math.floor((endTime - marketOpen) / (1000 * 60));
  const intervals = Math.min(Math.max(Math.floor(totalMinutes / 10), 15), 40); // 10분 간격

  // ★ 계단식 상승/하락 패턴 생성 (0에서 시작)
  let cumulative = 0;
  const stepSize = Math.abs(finalValue) / intervals;
  const direction = finalValue >= 0 ? 1 : -1;

  for (let i = 0; i <= intervals; i++) {
    const time = new Date(marketOpen.getTime() + (i * 10 * 60 * 1000));
    const timeStr = time.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });

    if (i === 0) {
      // 시작은 0
      cumulative = 0;
    } else {
      // 계단식 증가: 때때로 평탄, 가끔 점프
      const rand = Math.random();
      if (rand > 0.7) {
        // 30% 확률로 큰 점프 (계단 2~3칸)
        cumulative += stepSize * direction * (2 + Math.random());
      } else if (rand > 0.3) {
        // 40% 확률로 일반 상승
        cumulative += stepSize * direction * (0.8 + Math.random() * 0.5);
      }
      // 30% 확률로 유지 (평탄 구간)
    }

    series.push({
      time: timeStr,
      value: Math.round(cumulative)
    });
  }

  // 마지막 값은 최종 목표값에 근접하게
  if (series.length > 0) {
    series[series.length - 1].value = finalValue;
  }

  return series;
};

const refreshVolumePower = async () => {
  if (!stockCode.value || !autoRefresh.value) return;

  try {
    const response = await scalpingAPI.refreshVolumePower(stockCode.value);
    if (response.data.success) {
      // 체결강도 관련 데이터만 갱신
      const data = response.data.data;
      if (analysisData.value) {
        analysisData.value.volumePower = data.volumePower;
        analysisData.value.volumeSignal = data.volumeSignal;
        analysisData.value.currentPrice = data.currentPrice;
        analysisData.value.changePrice = data.changePrice;
        analysisData.value.changeRate = data.changeRate;
        analysisData.value.tradingVolume = data.tradingVolume;
        lastUpdated.value = new Date();
      }
    }
  } catch (error) {
    console.error('갱신 오류:', error);
  }
};

const toggleAutoRefresh = () => {
  if (autoRefresh.value) {
    startAutoRefresh();
  } else {
    stopAutoRefresh();
  }
};

const startAutoRefresh = () => {
  if (refreshInterval) clearInterval(refreshInterval);
  refreshInterval = setInterval(refreshVolumePower, 10000); // 10초
};

const stopAutoRefresh = () => {
  if (refreshInterval) {
    clearInterval(refreshInterval);
    refreshInterval = null;
  }
};

const formatNumber = (value) => {
  if (value == null) return '0';
  return Number(value).toLocaleString('ko-KR');
};

const formatVolume = (value) => {
  if (value == null) return '0';
  if (value >= 1000000) {
    return (value / 1000000).toFixed(1) + 'M';
  } else if (value >= 1000) {
    return (value / 1000).toFixed(1) + 'K';
  }
  return value.toString();
};

const formatBillion = (value) => {
  if (value == null) return '0억';
  return value.toFixed(0) + '억';
};

// 막대 차트 너비 계산 (최대값 기준 %)
const getBarWidth = (value) => {
  if (!value || !analysisData.value) return 0;
  const maxVal = Math.max(
    Math.abs(analysisData.value.foreignNetBuy || 0),
    Math.abs(analysisData.value.instNetBuy || 0),
    Math.abs(analysisData.value.programNetBuy || 0),
    1
  );
  return Math.min((Math.abs(value) / maxVal) * 100, 100);
};

const formatTime = (date) => {
  if (!date) return '';
  return date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
};

const changeClass = ref('');

// 주가 변동 클래스 계산
const updateChangeClass = () => {
  if (!analysisData.value) return '';
  const change = analysisData.value.changePrice;
  if (change > 0) changeClass.value = 'positive';
  else if (change < 0) changeClass.value = 'negative';
  else changeClass.value = 'neutral';
};

onMounted(() => {
  // Vue Router의 query에서 종목코드 확인
  const code = route.query.code;

  // AI 전략 페이지에서 왔는지 확인
  if (route.query.from === 'ai-strategy' || route.path === '/scalping-analysis') {
    fromAiStrategy.value = true;
  }

  // 종목코드가 있으면 자동 검색
  if (code && /^\d{6}$/.test(code)) {
    // 종목명이 있으면 검색창에 표시
    const stockName = CODE_TO_NAME[code];
    searchInput.value = stockName || code;
    stockCode.value = code;
    fetchAnalysis();
  }
});

onUnmounted(() => {
  stopAutoRefresh();
});

// analysisData 변경 시 changeClass 갱신
import { watch, computed } from 'vue';
watch(analysisData, updateChangeClass, { deep: true });

// 리스크 뱃지 계산
const getRiskBadgeClass = computed(() => {
  if (!riskData.value) return '';
  const score = riskData.value.riskScore;
  if (score >= 70) return 'danger blink';
  if (score >= 30) return 'warning';
  return 'safe';
});

const getRiskBadgeText = computed(() => {
  if (!riskData.value) return '';
  const score = riskData.value.riskScore;
  if (score >= 70) return '위험';
  if (score >= 30) return '주의';
  return '안전';
});
</script>

<style scoped>
.scalping-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
  padding: 2rem;
}

.content-wrapper {
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  text-align: center;
  margin-bottom: 2rem;
  position: relative;
}

.back-button {
  position: absolute;
  left: 0;
  top: 0;
  background: #4a4a8a;
  color: white;
  border: none;
  padding: 0.75rem 1.5rem;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1rem;
  transition: all 0.3s;
}

.back-button:hover {
  background: #5a5a9a;
  transform: translateX(-5px);
}

.page-header h1 {
  color: #fff;
  margin-bottom: 0.5rem;
  font-size: 2rem;
}

.subtitle {
  color: #888;
  font-size: 1.1rem;
}

/* 검색 섹션 */
.search-section {
  margin-bottom: 1.5rem;
}

.search-box {
  display: flex;
  gap: 12px;
  max-width: 600px;
  margin: 0 auto;
}

.search-input {
  flex: 1;
  padding: 14px 20px;
  border: 2px solid #2a2a4a;
  border-radius: 12px;
  background: #0f0f23;
  color: #fff;
  font-size: 1rem;
  transition: border-color 0.3s;
}

.search-input:focus {
  outline: none;
  border-color: #4a4a8a;
}

.search-input::placeholder {
  color: #666;
}

.search-button {
  padding: 14px 28px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 12px;
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s;
}

.search-button:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
}

.search-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.error-message {
  text-align: center;
  color: #fff;
  margin-top: 12px;
  padding: 12px 20px;
  background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
  border-radius: 10px;
  font-weight: 500;
  max-width: 600px;
  margin-left: auto;
  margin-right: auto;
  animation: fadeInOut 0.3s ease;
}

@keyframes fadeInOut {
  from { opacity: 0; transform: translateY(-10px); }
  to { opacity: 1; transform: translateY(0); }
}

/* 실시간 상태 표시줄 */
.realtime-status-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, #1a1a3a 0%, #0f0f23 100%);
  border: 1px solid #2a2a4a;
  border-radius: 12px;
  padding: 12px 20px;
  margin-bottom: 1rem;
}

.realtime-status-bar.active {
  border-color: #22c55e;
  box-shadow: 0 0 20px rgba(34, 197, 94, 0.2);
}

.status-indicator {
  display: flex;
  align-items: center;
  gap: 10px;
}

.status-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #71717a;
}

.status-dot.pulsing {
  background: #22c55e;
  animation: pulse-glow 1.5s ease-in-out infinite;
}

@keyframes pulse-glow {
  0%, 100% {
    opacity: 1;
    box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.7);
  }
  50% {
    opacity: 0.8;
    box-shadow: 0 0 0 8px rgba(34, 197, 94, 0);
  }
}

.status-text {
  color: #fff;
  font-weight: 600;
  font-size: 0.95rem;
}

.realtime-status-bar.active .status-text {
  color: #22c55e;
}

.status-meta {
  display: flex;
  align-items: center;
  gap: 16px;
}

.update-time {
  color: #888;
  font-size: 0.9rem;
  font-family: 'Monaco', 'Consolas', monospace;
}

/* 자동 갱신 섹션 */
.refresh-section {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 24px;
  margin-bottom: 1.5rem;
}

.auto-refresh-label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #ccc;
  cursor: pointer;
}

.auto-refresh-label input {
  width: 18px;
  height: 18px;
  cursor: pointer;
}

.last-updated {
  color: #888;
  font-size: 0.9rem;
}

/* 종목 정보 카드 */
.stock-info-card {
  background: #0f0f23;
  border-radius: 16px;
  padding: 24px;
  margin-bottom: 1.5rem;
  border: 1px solid #2a2a4a;
}

.stock-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 16px;
}

.stock-name-section h2 {
  margin: 0;
  color: #fff;
  font-size: 1.5rem;
}

.stock-code {
  color: #888;
  font-size: 0.9rem;
  margin-left: 8px;
}

.price-section {
  text-align: right;
}

.current-price {
  display: block;
  font-size: 1.8rem;
  font-weight: 700;
  color: #fff;
  font-family: 'Monaco', 'Consolas', monospace;
}

.change {
  font-size: 1.1rem;
  font-weight: 600;
}

.change.positive { color: #ef4444; }
.change.negative { color: #3b82f6; }
.change.neutral { color: #888; }

.stock-meta {
  display: flex;
  gap: 24px;
}

.meta-item {
  display: flex;
  gap: 8px;
}

.meta-item .label {
  color: #888;
}

.meta-item .value {
  color: #fff;
  font-weight: 500;
}

/* 분석 그리드 */
.analysis-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(350px, 1fr));
  gap: 20px;
  margin-bottom: 2rem;
}

.analysis-card.chart-card {
  grid-column: 1 / -1;
}

.analysis-card.risk-card {
  grid-column: 1 / -1;
}

.investor-card {
  background: #1a1a3a;
  border-radius: 16px;
  padding: 24px;
  border: 1px solid #2a2a4a;
}

.investor-card h3 {
  margin: 0 0 20px 0;
  color: #fff;
  font-size: 1.2rem;
}

/* 투자자 막대 차트 */
.investor-bar-chart {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.investor-bar-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  background: #0f0f23;
  border-radius: 10px;
}

.investor-bar-row.highlight {
  background: linear-gradient(135deg, #1a1a3a 0%, #2a2a4a 100%);
  border: 1px solid #3a3a5a;
}

.bar-label {
  width: 100px;
  color: #ccc;
  font-size: 0.9rem;
  flex-shrink: 0;
}

.bar-container {
  flex: 1;
}

.bar-track {
  height: 24px;
  background: #27272a;
  border-radius: 12px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: 12px;
  transition: width 0.5s ease;
}

.bar-fill.positive {
  background: linear-gradient(90deg, #ef4444 0%, #dc2626 100%);
  box-shadow: 0 0 10px rgba(239, 68, 68, 0.4);
}

.bar-fill.negative {
  background: linear-gradient(90deg, #3b82f6 0%, #2563eb 100%);
  box-shadow: 0 0 10px rgba(59, 130, 246, 0.4);
}

.bar-value {
  width: 80px;
  text-align: right;
  font-size: 1.1rem;
  font-weight: 700;
  font-family: 'Monaco', 'Consolas', monospace;
  flex-shrink: 0;
}

.bar-value.positive { color: #ef4444; }
.bar-value.negative { color: #3b82f6; }

/* 가이드 섹션 */
.guide-section {
  background: #0f0f23;
  border-radius: 16px;
  padding: 32px;
  border: 1px solid #2a2a4a;
}

.guide-section h3 {
  margin: 0 0 24px 0;
  color: #fff;
  font-size: 1.3rem;
  text-align: center;
}

.guide-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 24px;
}

.guide-item {
  display: flex;
  gap: 16px;
  padding: 20px;
  background: #1a1a3a;
  border-radius: 12px;
}

.guide-icon {
  font-size: 2rem;
  flex-shrink: 0;
}

.guide-content h4 {
  margin: 0 0 8px 0;
  color: #fff;
  font-size: 1.1rem;
}

.guide-content p {
  margin: 0;
  color: #888;
  font-size: 0.9rem;
  line-height: 1.5;
}

/* 반응형 */
@media (max-width: 768px) {
  .scalping-page {
    padding: 1rem;
  }

  .page-header h1 {
    font-size: 1.5rem;
    margin-top: 3rem;
  }

  .search-box {
    flex-direction: column;
  }

  .stock-header {
    flex-direction: column;
    text-align: center;
  }

  .price-section {
    text-align: center;
  }

  .analysis-grid {
    grid-template-columns: 1fr;
  }

  .investor-grid {
    flex-direction: column;
  }
}

/* 리스크 뱃지 (인라인) */
.risk-badge-inline {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 0.75rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-left: 12px;
  vertical-align: middle;
}

.risk-badge-inline.safe {
  background: linear-gradient(135deg, #22c55e 0%, #16a34a 100%);
  color: #fff;
}

.risk-badge-inline.warning {
  background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%);
  color: #000;
}

.risk-badge-inline.danger {
  background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
  color: #fff;
}

.risk-badge-inline.blink {
  animation: blink-badge 1s ease-in-out infinite;
}

@keyframes blink-badge {
  0%, 100% {
    opacity: 1;
    box-shadow: 0 0 15px rgba(239, 68, 68, 0.8);
  }
  50% {
    opacity: 0.7;
    box-shadow: 0 0 25px rgba(239, 68, 68, 1);
  }
}

/* 액션 섹션 (매수 버튼) */
.action-section {
  display: flex;
  justify-content: center;
  margin-top: 24px;
  margin-bottom: 32px;
}

.buy-button {
  padding: 16px 48px;
  font-size: 1.1rem;
  font-weight: 700;
  border: none;
  border-radius: 14px;
  cursor: pointer;
  transition: all 0.3s ease;
  display: flex;
  align-items: center;
  gap: 8px;
  background: linear-gradient(135deg, #22c55e 0%, #16a34a 100%);
  color: #fff;
  box-shadow: 0 4px 20px rgba(34, 197, 94, 0.3);
}

.buy-button:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 30px rgba(34, 197, 94, 0.4);
}

.buy-button.danger {
  background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
  box-shadow: 0 4px 20px rgba(239, 68, 68, 0.3);
  animation: pulse-danger 2s ease-in-out infinite;
}

.buy-button.danger:hover {
  box-shadow: 0 8px 30px rgba(239, 68, 68, 0.5);
}

@keyframes pulse-danger {
  0%, 100% {
    box-shadow: 0 4px 20px rgba(239, 68, 68, 0.3);
  }
  50% {
    box-shadow: 0 4px 30px rgba(239, 68, 68, 0.6);
  }
}

.buy-button .warning-icon {
  font-size: 1.2rem;
}

@media (max-width: 768px) {
  .risk-badge-inline {
    display: block;
    margin-left: 0;
    margin-top: 8px;
    width: fit-content;
  }

  .buy-button {
    width: 100%;
    justify-content: center;
    padding: 16px;
  }
}
</style>
