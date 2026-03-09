<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <button class="back-btn" @click="$router.back()">←</button>
        <h1>글로벌 선물 대시보드</h1>
      </div>
      <div class="header-right">
        <div v-if="dataTimestamp" class="data-time-info">
          <span class="data-time-label">데이터 기준</span>
          <span class="data-time-value">{{ dataTimestamp }}</span>
          <span v-if="marketStatusText" class="market-status-badge" :class="marketStatusClass">{{ marketStatusText }}</span>
        </div>
        <span v-if="lastUpdated" class="update-time">{{ formatTime(lastUpdated) }} 갱신</span>
        <button class="refresh-btn" :class="{ spinning: loading }" @click="fetchData">↻</button>
        <label class="auto-toggle">
          <input type="checkbox" v-model="autoRefresh" @change="toggleAutoRefresh" />
          <span>자동 (30s)</span>
        </label>
      </div>
    </div>

    <!-- 코스피 영향 분석 배너 -->
    <div v-if="impactData" class="impact-banner" :class="[impactClass, alertClass]">
      <div class="impact-left">
        <span class="impact-label">종합 시장 방향성</span>
        <span class="impact-badge" :class="impactClass">{{ impactText }}</span>
        <span v-if="isExtremeAlert" class="extreme-alert-badge" :class="alertClass">{{ alertLabel }}</span>
      </div>
      <div class="impact-score-bar">
        <div class="score-track">
          <div class="score-fill" :style="{ width: impactData.impactScore + '%' }"></div>
          <div class="score-needle" :style="{ left: impactData.impactScore + '%' }"></div>
        </div>
        <div class="score-labels">
          <span class="neg">약세</span>
          <span class="mid">중립</span>
          <span class="pos">강세</span>
        </div>
      </div>
      <div class="impact-comment">{{ impactData.comment }}</div>

      <!-- 리스크 팩터 분석 -->
      <div v-if="impactData.riskFactors && impactData.riskFactors.length" class="risk-factors">
        <div class="factor-title">복합 분석 지표</div>
        <div class="factor-grid">
          <div v-for="f in impactData.riskFactors" :key="f.name" class="factor-item">
            <div class="factor-name">
              {{ f.name }}
              <span class="factor-weight">({{ f.weight }}%)</span>
            </div>
            <div class="factor-rate" :class="getFactorClass(f)">
              {{ f.changeRate > 0 ? '+' : '' }}{{ f.changeRate.toFixed(2) }}%
            </div>
            <div class="factor-signal" :class="getFactorClass(f)">
              {{ getFactorSignalText(f.signal) }}
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- VIX 공포지수 패널 -->
    <div v-if="vixQuote" class="vix-panel" :class="vixLevelClass">
      <div class="vix-header">
        <div class="vix-title">
          <span class="vix-icon">{{ vixEmoji }}</span>
          <h3>VIX 공포지수</h3>
          <span class="vix-exchange">CBOE</span>
        </div>
        <span class="vix-level-badge" :class="vixLevelClass">{{ vixLevelText }}</span>
      </div>
      <div class="vix-body">
        <div class="vix-price">{{ formatPrice(vixQuote.currentPrice) }}</div>
        <div class="vix-change" :class="getChangeClass(vixQuote)">
          {{ formatChange(vixQuote.changePrice) }} ({{ formatRate(vixQuote.changeRate) }}%)
        </div>
      </div>
      <div class="vix-meter">
        <div class="vix-meter-track">
          <div class="vix-meter-fill" :style="{ width: vixMeterWidth + '%' }"></div>
        </div>
        <div class="vix-meter-labels">
          <span>0</span>
          <span class="vix-zone-low">안정 (&lt;15)</span>
          <span class="vix-zone-mid">경계 (15~25)</span>
          <span class="vix-zone-high">공포 (&ge;25)</span>
          <span>50+</span>
        </div>
      </div>
    </div>

    <!-- 로딩 -->
    <div v-if="loading && !quotes.length" class="loading-state">
      <div class="spinner"></div>
      <span>해외선물 시세 조회 중...</span>
    </div>

    <!-- 메인 카드 (코스피200 야간선물) -->
    <div v-if="kospiQuote" class="main-card" :class="getCardClass(kospiQuote)">
      <div class="main-header">
        <div class="main-title">
          <span class="exchange-badge">{{ kospiQuote.exchange }}</span>
          <h2>{{ kospiQuote.name }}</h2>
        </div>
        <span class="main-status" :class="getSignClass(kospiQuote)">
          {{ getSignText(kospiQuote) }}
        </span>
      </div>
      <div class="main-price">
        <span class="price">{{ formatPrice(kospiQuote.currentPrice) }}</span>
        <div class="change" :class="getChangeClass(kospiQuote)">
          <span class="change-price">{{ formatChange(kospiQuote.changePrice) }}</span>
          <span class="change-rate">({{ formatRate(kospiQuote.changeRate) }}%)</span>
        </div>
      </div>
      <div class="main-details">
        <div class="detail">
          <span class="label">고가</span>
          <span class="value">{{ formatPrice(kospiQuote.highPrice) }}</span>
        </div>
        <div class="detail">
          <span class="label">저가</span>
          <span class="value">{{ formatPrice(kospiQuote.lowPrice) }}</span>
        </div>
        <div class="detail">
          <span class="label">거래량</span>
          <span class="value">{{ formatVolume(kospiQuote.volume) }}</span>
        </div>
        <div class="detail">
          <span class="label">데이터 기준</span>
          <span class="value">{{ kospiQuote.tradingTime || '-' }}</span>
        </div>
      </div>
    </div>

    <!-- 카테고리별 선물 그리드 -->
    <div v-if="quotes.length" class="category-sections">
      <!-- 미국 지수 선물 -->
      <div class="section">
        <h3 class="section-title">미국 지수 선물</h3>
        <div class="futures-grid">
          <div v-for="q in usIndexQuotes" :key="q.symbol"
               class="futures-card" :class="getCardClass(q)">
            <div class="card-top">
              <span class="symbol">{{ q.shortName }}</span>
              <span class="exchange-tag">{{ q.exchange }}</span>
            </div>
            <div class="card-price" :class="getChangeClass(q)">
              {{ formatPrice(q.currentPrice) }}
            </div>
            <div class="card-change" :class="getChangeClass(q)">
              {{ formatChange(q.changePrice) }} ({{ formatRate(q.changeRate) }}%)
            </div>
            <div class="card-range">
              <span>L {{ formatPrice(q.lowPrice) }}</span>
              <span>H {{ formatPrice(q.highPrice) }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 원자재 선물 -->
      <div class="section">
        <h3 class="section-title">
          원자재 선물
          <button class="oil-detail-btn" @click="toggleOilDetail">🛢️ 원유 상세</button>
        </h3>
        <div class="futures-grid">
          <div v-for="q in commodityQuotes" :key="q.symbol"
               class="futures-card" :class="getCardClass(q)">
            <div class="card-top">
              <span class="symbol">{{ q.shortName }}</span>
              <span class="exchange-tag">{{ q.exchange }}</span>
            </div>
            <div class="card-price" :class="getChangeClass(q)">
              {{ formatPrice(q.currentPrice) }}
            </div>
            <div class="card-change" :class="getChangeClass(q)">
              {{ formatChange(q.changePrice) }} ({{ formatRate(q.changeRate) }}%)
            </div>
            <div class="card-range">
              <span>L {{ formatPrice(q.lowPrice) }}</span>
              <span>H {{ formatPrice(q.highPrice) }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 통화 선물 -->
      <div class="section">
        <h3 class="section-title">통화 선물</h3>
        <div class="futures-grid">
          <div v-for="q in currencyQuotes" :key="q.symbol"
               class="futures-card" :class="getCardClass(q)">
            <div class="card-top">
              <span class="symbol">{{ q.shortName }}</span>
              <span class="exchange-tag">{{ q.exchange }}</span>
            </div>
            <div class="card-price" :class="getChangeClass(q)">
              {{ formatPrice(q.currentPrice) }}
            </div>
            <div class="card-change" :class="getChangeClass(q)">
              {{ formatChange(q.changePrice) }} ({{ formatRate(q.changeRate) }}%)
            </div>
            <div class="card-range">
              <span>L {{ formatPrice(q.lowPrice) }}</span>
              <span>H {{ formatPrice(q.highPrice) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 전체 시세표 -->
    <div v-if="quotes.length" class="table-section">
      <h3 class="section-title">전체 시세표</h3>
      <div class="table-wrapper">
        <table class="futures-table">
          <thead>
            <tr>
              <th>종목</th>
              <th>거래소</th>
              <th class="right">현재가</th>
              <th class="right">전일비</th>
              <th class="right">등락률</th>
              <th class="right">고가</th>
              <th class="right">저가</th>
              <th class="right">거래량</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="q in quotes" :key="q.symbol + '-table'" :class="{ error: !q.success }">
              <td>
                <span class="table-name">{{ q.name }}</span>
                <span class="table-symbol">{{ q.symbol }}</span>
              </td>
              <td>{{ q.exchange }}</td>
              <td class="right">{{ q.success ? formatPrice(q.currentPrice) : '-' }}</td>
              <td class="right" :class="getChangeClass(q)">
                {{ q.success ? formatChange(q.changePrice) : '-' }}
              </td>
              <td class="right" :class="getChangeClass(q)">
                {{ q.success ? formatRate(q.changeRate) + '%' : '-' }}
              </td>
              <td class="right">{{ q.success ? formatPrice(q.highPrice) : '-' }}</td>
              <td class="right">{{ q.success ? formatPrice(q.lowPrice) : '-' }}</td>
              <td class="right">{{ q.success ? formatVolume(q.volume) : '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 에러 -->
    <div v-if="error" class="error-state">
      <p>{{ error }}</p>
      <button @click="fetchData" class="retry-btn">다시 시도</button>
    </div>

    <!-- 원유 상세 패널 (인라인 확장) -->
    <div v-if="showOilDetail" class="oil-detail-panel">
      <div class="oil-detail-header">
        <h3>🛢️ WTI 원유 상세</h3>
        <button class="close-detail-btn" @click="showOilDetail = false">✕</button>
      </div>

      <div v-if="oilLoading" class="oil-loading">
        <div class="spinner"></div>
        <span>원유 상세 데이터 로딩 중...</span>
      </div>

      <div v-else-if="oilPrice" class="oil-detail-body">
        <div class="oil-price-main">
          <div class="oil-price-big">${{ formatOilUsd(oilPrice.pricePerBarrel) }}</div>
          <div class="oil-price-sub">1배럴 (USD)</div>
          <div class="oil-price-krw" v-if="oilPrice.priceKrw">≈ {{ formatOilKrw(oilPrice.priceKrw) }}원</div>
        </div>

        <div class="oil-info-grid">
          <div class="oil-info-item">
            <span class="label">기준일</span>
            <span class="value">{{ formatOilDate(oilPrice.baseDate) }}</span>
          </div>
          <div class="oil-info-item">
            <span class="label">등락률</span>
            <span class="value" :class="oilChangeClass">
              {{ oilPrice.changeRate > 0 ? '+' : '' }}{{ oilPrice.changeRate }}%
            </span>
          </div>
          <div class="oil-info-item">
            <span class="label">전일 대비</span>
            <span class="value" :class="oilChangeClass">
              {{ oilPrice.changePrice > 0 ? '+' : '' }}{{ formatOilUsd(oilPrice.changePrice) }}
            </span>
          </div>
          <div class="oil-info-item">
            <span class="label">시가</span>
            <span class="value">${{ formatOilUsd(oilPrice.openPrice) }}</span>
          </div>
          <div class="oil-info-item">
            <span class="label">고가</span>
            <span class="value high">${{ formatOilUsd(oilPrice.highPrice) }}</span>
          </div>
          <div class="oil-info-item">
            <span class="label">저가</span>
            <span class="value low">${{ formatOilUsd(oilPrice.lowPrice) }}</span>
          </div>
          <div class="oil-info-item">
            <span class="label">종가</span>
            <span class="value">${{ formatOilUsd(oilPrice.closePrice) }}</span>
          </div>
          <div class="oil-info-item" v-if="oilPrice.volume">
            <span class="label">거래량</span>
            <span class="value">{{ formatVolume(oilPrice.volume) }}</span>
          </div>
        </div>

        <!-- 최근 한 달 차트 -->
        <div class="oil-chart-section">
          <h4>📊 최근 한 달 WTI 시세 추이</h4>
          <div class="oil-chart-container">
            <canvas ref="oilChartCanvas"></canvas>
          </div>
        </div>
      </div>
    </div>

    <div class="disclaimer">
      해외선물 시세는 Yahoo Finance를 통해 제공됩니다. 주말·공휴일에는 마지막 거래일 종가 기준이며, 장중에는 약간의 지연이 있을 수 있습니다. 투자 판단의 참고자료로만 활용하세요.
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue';
import { globalFuturesAPI, oilAPI } from '../utils/api';
import { getVixStatus, getVixMeterWidth } from '../composables/useMarketStatus';
import { Chart, registerables } from 'chart.js';

Chart.register(...registerables);

const loading = ref(false);
const error = ref('');
const quotes = ref([]);
const impactData = ref(null);
const lastUpdated = ref(null);
const autoRefresh = ref(true);
const dataTimestamp = ref('');
const currentMarketStatus = ref('');
let refreshTimer = null;

// 원유 상세
const showOilDetail = ref(false);
const oilPrice = ref(null);
const oilLoading = ref(false);
const oilChartCanvas = ref(null);
let oilChartInstance = null;

// 카테고리 필터
const kospiQuote = computed(() =>
  quotes.value.find(q => q.symbol === 'KM' && q.success)
);

const usIndexQuotes = computed(() =>
  quotes.value.filter(q => ['NQ', 'ES', 'YM'].includes(q.symbol) && q.success)
);

const commodityQuotes = computed(() =>
  quotes.value.filter(q => q.category === 'commodity' && q.success)
);

const currencyQuotes = computed(() =>
  quotes.value.filter(q => q.category === 'currency' && q.success)
);

// VIX
const vixQuote = computed(() =>
  quotes.value.find(q => q.symbol === 'VIX' && q.success)
);

const vixLevel = computed(() => {
  if (!vixQuote.value) return 0;
  return Number(vixQuote.value.currentPrice) || 0;
});

// VIX 구간 분류 (공통 컴포저블 사용)
const vixStatus = computed(() => getVixStatus(vixLevel.value));
const vixLevelClass = computed(() => vixStatus.value.cssClass);
const vixLevelText = computed(() => vixStatus.value.text);
const vixEmoji = computed(() => vixStatus.value.emoji);
const vixMeterWidth = computed(() => getVixMeterWidth(vixLevel.value));

// 코스피 영향 분석
const impactClass = computed(() => {
  if (!impactData.value) return '';
  const al = impactData.value.alertLevel;
  if (al === 'CRISIS') return 'negative';
  const impact = impactData.value.impact;
  if (impact === 'POSITIVE') return 'positive';
  if (impact === 'NEGATIVE') return 'negative';
  return 'neutral';
});

const impactText = computed(() => {
  if (!impactData.value) return '';
  const al = impactData.value.alertLevel;
  if (al === 'CRISIS') return '폭락 경계';
  if (al === 'EXTREME_NEGATIVE') return '극심한 약세';
  if (al === 'NEGATIVE') return '약세 주의';
  if (al === 'WEAK_NEGATIVE') return '소폭 약세';
  if (al === 'WEAK_POSITIVE') return '소폭 강세';
  if (al === 'POSITIVE') return '강세 예상';
  if (al === 'EXTREME_POSITIVE') return '강한 강세';
  return '보합 예상';
});

const alertClass = computed(() => {
  if (!impactData.value) return '';
  const al = impactData.value.alertLevel;
  if (al === 'CRISIS') return 'alert-crisis';
  if (al === 'EXTREME_NEGATIVE') return 'alert-extreme-neg';
  if (al === 'EXTREME_POSITIVE') return 'alert-extreme-pos';
  return '';
});

const isExtremeAlert = computed(() => {
  if (!impactData.value) return false;
  const al = impactData.value.alertLevel;
  return al === 'CRISIS' || al === 'EXTREME_NEGATIVE' || al === 'EXTREME_POSITIVE';
});

const alertLabel = computed(() => {
  if (!impactData.value) return '';
  const al = impactData.value.alertLevel;
  if (al === 'CRISIS') return '극심한 하방 압력';
  if (al === 'EXTREME_NEGATIVE') return '하방 변동성 경고';
  if (al === 'EXTREME_POSITIVE') return '강세 모멘텀';
  return '';
});

// 마켓 상태
const marketStatusText = computed(() => {
  const s = currentMarketStatus.value;
  if (s === 'REGULAR') return '장중';
  if (s === 'PRE') return '프리마켓';
  if (s === 'POST' || s === 'POSTPOST') return '애프터마켓';
  if (s === 'CLOSED') return '장마감';
  return s || '';
});

const marketStatusClass = computed(() => {
  const s = currentMarketStatus.value;
  if (s === 'REGULAR') return 'status-open';
  if (s === 'PRE' || s === 'POST' || s === 'POSTPOST') return 'status-pre';
  return 'status-closed';
});

// 데이터 fetch
const fetchData = async () => {
  loading.value = true;
  error.value = '';

  try {
    const res = await globalFuturesAPI.getKospiImpact();
    if (res.data.success) {
      const data = res.data.data;
      quotes.value = data.quotes || [];
      impactData.value = {
        impact: data.impact,
        alertLevel: data.alertLevel,
        impactScore: data.impactScore,
        comment: data.comment,
        riskFactors: data.riskFactors || []
      };
      lastUpdated.value = new Date();

      // 데이터 기준 시간 추출 (첫 번째 성공 종목의 tradingTime)
      const firstSuccess = (data.quotes || []).find(q => q.success && q.tradingTime);
      dataTimestamp.value = firstSuccess ? firstSuccess.tradingTime : '';
      const firstStatus = (data.quotes || []).find(q => q.success && q.marketStatus);
      currentMarketStatus.value = firstStatus ? firstStatus.marketStatus : '';
    }
  } catch (e) {
    error.value = '해외선물 데이터 조회에 실패했습니다.';
    console.error('GlobalFutures fetch error:', e);
  } finally {
    loading.value = false;
  }
};

// 자동 갱신
const toggleAutoRefresh = () => {
  if (autoRefresh.value) {
    startAutoRefresh();
  } else {
    stopAutoRefresh();
  }
};

const startAutoRefresh = () => {
  stopAutoRefresh();
  refreshTimer = setInterval(fetchData, 30000);
};

const stopAutoRefresh = () => {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
};

// 포맷 함수
const formatPrice = (price) => {
  if (price == null) return '-';
  return Number(price).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
};

const formatChange = (change) => {
  if (change == null) return '-';
  const num = Number(change);
  const prefix = num > 0 ? '+' : '';
  return prefix + num.toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
};

const formatRate = (rate) => {
  if (rate == null) return '0.00';
  const num = Number(rate);
  const prefix = num > 0 ? '+' : '';
  return prefix + num.toFixed(2);
};

const formatVolume = (vol) => {
  if (vol == null) return '-';
  const num = Number(vol);
  if (num >= 10000) return (num / 10000).toFixed(1) + '만';
  return num.toLocaleString();
};

const formatTime = (date) => {
  if (!date) return '';
  return date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
};

// 스타일 헬퍼
const getChangeClass = (q) => {
  if (!q || !q.success || q.changeRate == null) return '';
  const rate = Number(q.changeRate);
  if (rate > 0) return 'up';
  if (rate < 0) return 'down';
  return 'flat';
};

const getCardClass = (q) => {
  if (!q || !q.success) return 'card-error';
  return getChangeClass(q) + '-card';
};

const getSignClass = (q) => {
  if (!q || !q.sign) return '';
  if (['1', '2'].includes(q.sign)) return 'up';
  if (['4', '5'].includes(q.sign)) return 'down';
  return 'flat';
};

const getSignText = (q) => {
  if (!q || !q.sign) return '';
  const map = { '1': '상한', '2': '상승', '3': '보합', '4': '하한', '5': '하락' };
  return map[q.sign] || '';
};

const getFactorClass = (f) => {
  if (f.signal === 'POSITIVE') return 'factor-pos';
  if (f.signal === 'NEGATIVE') return 'factor-neg';
  return 'factor-neutral';
};

const getFactorSignalText = (signal) => {
  if (signal === 'POSITIVE') return '긍정';
  if (signal === 'NEGATIVE') return '부정';
  return '중립';
};

// 원유 상세
const oilChangeClass = computed(() => {
  if (!oilPrice.value) return '';
  return oilPrice.value.changeRate > 0 ? 'up' : oilPrice.value.changeRate < 0 ? 'down' : '';
});

const formatOilUsd = (price) => {
  if (price == null) return '-';
  return Number(price).toFixed(2);
};

const formatOilKrw = (price) => {
  if (price == null) return '-';
  return new Intl.NumberFormat('ko-KR').format(price);
};

const formatOilDate = (dateStr) => {
  if (!dateStr || dateStr.length !== 8) return dateStr || '-';
  return `${dateStr.substring(0, 4)}.${dateStr.substring(4, 6)}.${dateStr.substring(6, 8)}`;
};

const toggleOilDetail = async () => {
  showOilDetail.value = !showOilDetail.value;
  if (showOilDetail.value && !oilPrice.value) {
    await fetchOilDetail();
  }
};

const fetchOilDetail = async () => {
  oilLoading.value = true;
  try {
    const response = await oilAPI.getPrice();
    if (response.data.success) {
      oilPrice.value = response.data.data;
      await fetchOilChart();
    }
  } catch (err) {
    console.error('Oil detail fetch error:', err);
  } finally {
    oilLoading.value = false;
  }
};

const fetchOilChart = async () => {
  try {
    const response = await oilAPI.getMonthlyHistory();
    if (response.data.success && response.data.data) {
      await nextTick();
      createOilChart(response.data.data);
    }
  } catch (err) {
    console.error('Oil chart fetch error:', err);
  }
};

const createOilChart = (historyData) => {
  if (!oilChartCanvas.value || !historyData?.length) return;
  if (oilChartInstance) oilChartInstance.destroy();

  const labels = historyData.map(item => {
    const date = new Date(item.fetchedAt);
    return `${date.getMonth() + 1}/${date.getDate()}`;
  });
  const prices = historyData.map(item => item.pricePerBarrel);

  oilChartInstance = new Chart(oilChartCanvas.value.getContext('2d'), {
    type: 'bar',
    data: {
      labels,
      datasets: [{
        label: 'WTI 원유 ($/배럴)',
        data: prices,
        backgroundColor: 'rgba(251, 146, 60, 0.6)',
        borderColor: 'rgba(251, 146, 60, 1)',
        borderWidth: 2,
        borderRadius: 4
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false },
        tooltip: {
          backgroundColor: 'rgba(15, 15, 35, 0.95)',
          titleColor: '#fff',
          bodyColor: '#ccc',
          callbacks: {
            label: (ctx) => '시세: $' + ctx.parsed.y.toFixed(2) + '/배럴'
          }
        }
      },
      scales: {
        y: {
          beginAtZero: false,
          ticks: {
            color: '#888',
            callback: (v) => '$' + v.toFixed(1)
          },
          grid: { color: 'rgba(255,255,255,0.05)' }
        },
        x: {
          ticks: { color: '#888' },
          grid: { display: false }
        }
      }
    }
  });
};

onMounted(() => {
  fetchData();
  if (autoRefresh.value) startAutoRefresh();
});

onUnmounted(() => {
  stopAutoRefresh();
  if (oilChartInstance) oilChartInstance.destroy();
});
</script>

<style scoped>
.page-container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px;
  color: #e0e0e0;
  min-height: 100vh;
  background: linear-gradient(135deg, #0a0a1a 0%, #111128 50%, #0d0d20 100%);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  flex-wrap: wrap;
  gap: 12px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-left h1 {
  margin: 0;
  font-size: 1.5rem;
  color: #fff;
}

.back-btn {
  background: rgba(255,255,255,0.1);
  border: 1px solid rgba(255,255,255,0.2);
  color: #fff;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1.1rem;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.data-time-info {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 10px;
  padding: 6px 14px;
}

.data-time-label {
  font-size: 0.75rem;
  color: #888;
}

.data-time-value {
  font-size: 0.8rem;
  color: #ddd;
  font-weight: 600;
}

.market-status-badge {
  font-size: 0.7rem;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 6px;
}

.market-status-badge.status-open {
  background: rgba(34,197,94,0.2);
  color: #22c55e;
}

.market-status-badge.status-pre {
  background: rgba(251,191,36,0.2);
  color: #fbbf24;
}

.market-status-badge.status-closed {
  background: rgba(156,163,175,0.2);
  color: #9ca3af;
}

.update-time {
  font-size: 0.8rem;
  color: #888;
}

.refresh-btn {
  background: rgba(255,255,255,0.1);
  border: 1px solid rgba(255,255,255,0.2);
  color: #fff;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1.2rem;
  transition: all 0.3s;
}

.refresh-btn:hover { background: rgba(255,255,255,0.2); }

.refresh-btn.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.auto-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.8rem;
  color: #888;
  cursor: pointer;
}

.auto-toggle input { accent-color: #667eea; }

/* 코스피 영향 배너 */
.impact-banner {
  background: rgba(30, 30, 60, 0.7);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 16px;
  padding: 20px 24px;
  margin-bottom: 24px;
}

.impact-banner.positive { border-color: rgba(239, 68, 68, 0.4); }
.impact-banner.negative { border-color: rgba(59, 130, 246, 0.4); }
.impact-banner.neutral { border-color: rgba(255,255,255,0.15); }

.impact-left {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.impact-label {
  font-size: 0.9rem;
  color: #aaa;
}

.impact-badge {
  padding: 4px 14px;
  border-radius: 20px;
  font-size: 0.85rem;
  font-weight: 700;
}

.impact-badge.positive { background: rgba(239,68,68,0.2); color: #ef4444; }
.impact-badge.negative { background: rgba(59,130,246,0.2); color: #3b82f6; }
.impact-badge.neutral { background: rgba(255,255,255,0.1); color: #9ca3af; }

.score-track {
  height: 8px;
  background: linear-gradient(90deg, #3b82f6 0%, #9ca3af 50%, #ef4444 100%);
  border-radius: 4px;
  position: relative;
  margin-bottom: 4px;
}

.score-fill {
  height: 100%;
  border-radius: 4px;
  background: transparent;
}

.score-needle {
  position: absolute;
  top: -4px;
  width: 3px;
  height: 16px;
  background: #fff;
  border-radius: 2px;
  transform: translateX(-50%);
  box-shadow: 0 0 8px rgba(255,255,255,0.5);
  transition: left 0.8s ease;
}

.score-labels {
  display: flex;
  justify-content: space-between;
  font-size: 0.7rem;
  color: #666;
}

.score-labels .neg { color: #3b82f6; }
.score-labels .pos { color: #ef4444; }

.impact-comment {
  margin-top: 10px;
  font-size: 0.9rem;
  color: #ccc;
  line-height: 1.5;
}

/* 극심한 경고 */
.impact-banner.alert-crisis {
  border-color: rgba(220, 38, 38, 0.8);
  background: linear-gradient(135deg, rgba(80, 5, 5, 0.9) 0%, rgba(50, 0, 0, 0.9) 100%);
  box-shadow: 0 0 30px rgba(239, 68, 68, 0.25), inset 0 0 30px rgba(239, 68, 68, 0.05);
  animation: crisisPulse 1.5s ease-in-out infinite;
}

@keyframes crisisPulse {
  0%, 100% { box-shadow: 0 0 30px rgba(239, 68, 68, 0.25); }
  50% { box-shadow: 0 0 50px rgba(239, 68, 68, 0.4); }
}

.impact-banner.alert-extreme-neg {
  border-color: rgba(239, 68, 68, 0.6);
  background: rgba(60, 10, 10, 0.7);
  box-shadow: 0 0 20px rgba(239, 68, 68, 0.15);
}

.impact-banner.alert-extreme-pos {
  border-color: rgba(34, 197, 94, 0.6);
  background: rgba(10, 60, 30, 0.7);
  box-shadow: 0 0 20px rgba(34, 197, 94, 0.15);
}

.extreme-alert-badge {
  padding: 3px 12px;
  border-radius: 6px;
  font-size: 0.75rem;
  font-weight: 700;
  animation: alertPulse 2s ease-in-out infinite;
}

.extreme-alert-badge.alert-crisis {
  background: rgba(220, 38, 38, 0.4);
  color: #fca5a5;
  animation: alertPulse 1s ease-in-out infinite;
}

.extreme-alert-badge.alert-extreme-neg {
  background: rgba(239, 68, 68, 0.3);
  color: #f87171;
}

.extreme-alert-badge.alert-extreme-pos {
  background: rgba(34, 197, 94, 0.3);
  color: #4ade80;
}

@keyframes alertPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

/* 리스크 팩터 */
.risk-factors {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid rgba(255,255,255,0.08);
}

.factor-title {
  font-size: 0.8rem;
  color: #888;
  margin-bottom: 10px;
}

.factor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 10px;
}

.factor-item {
  background: rgba(255,255,255,0.04);
  border-radius: 10px;
  padding: 10px 12px;
  text-align: center;
}

.factor-name {
  font-size: 0.8rem;
  color: #bbb;
  margin-bottom: 4px;
}

.factor-weight {
  font-size: 0.65rem;
  color: #666;
}

.factor-rate {
  font-size: 1rem;
  font-weight: 700;
  margin-bottom: 2px;
}

.factor-signal {
  font-size: 0.7rem;
  font-weight: 600;
}

.factor-pos { color: #ef4444; }
.factor-neg { color: #3b82f6; }
.factor-neutral { color: #9ca3af; }

/* VIX 공포지수 패널 */
.vix-panel {
  background: rgba(30, 30, 60, 0.7);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 16px;
  padding: 20px 24px;
  margin-bottom: 24px;
}

.vix-panel.vix-extreme {
  border-color: rgba(239, 68, 68, 0.5);
  background: rgba(60, 15, 15, 0.7);
}

.vix-panel.vix-fear {
  border-color: rgba(251, 146, 60, 0.4);
}

.vix-panel.vix-caution {
  border-color: rgba(251, 191, 36, 0.3);
}

.vix-panel.vix-calm {
  border-color: rgba(34, 197, 94, 0.3);
}

.vix-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}

.vix-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.vix-title h3 {
  margin: 0;
  font-size: 1.1rem;
  color: #fff;
}

.vix-icon {
  font-size: 1rem;
  color: #f87171;
  font-weight: 700;
}

.vix-exchange {
  font-size: 0.7rem;
  color: #888;
  padding: 2px 8px;
  background: rgba(255,255,255,0.05);
  border-radius: 4px;
}

.vix-level-badge {
  padding: 4px 14px;
  border-radius: 20px;
  font-size: 0.85rem;
  font-weight: 700;
}

.vix-level-badge.vix-extreme { background: rgba(239,68,68,0.2); color: #f87171; }
.vix-level-badge.vix-fear { background: rgba(251,146,60,0.2); color: #fb923c; }
.vix-level-badge.vix-caution { background: rgba(251,191,36,0.2); color: #fbbf24; }
.vix-level-badge.vix-calm { background: rgba(34,197,94,0.2); color: #4ade80; }

.vix-body {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 14px;
}

.vix-price {
  font-size: 2rem;
  font-weight: 700;
  color: #fff;
}

.vix-change {
  font-size: 1rem;
  font-weight: 600;
}

.vix-meter { margin-top: 8px; }

.vix-meter-track {
  height: 8px;
  /* 0=안정(녹색) → 15=보통(노랑,30%) → 25=공포(빨강,50%) → 50+=극심(어두운빨강) */
  background: linear-gradient(90deg, #22c55e 0%, #4ade80 20%, #fbbf24 30%, #fb923c 42%, #ef4444 50%, #dc2626 70%, #991b1b 100%);
  border-radius: 4px;
  position: relative;
  overflow: hidden;
}

.vix-meter-fill {
  position: absolute;
  left: 0;
  top: 0;
  height: 100%;
  background: transparent;
  border-right: 3px solid #fff;
  box-shadow: 2px 0 8px rgba(255,255,255,0.5);
  transition: width 0.8s ease;
}

.vix-meter-labels {
  display: flex;
  justify-content: space-between;
  font-size: 0.65rem;
  color: #666;
  margin-top: 4px;
}

.vix-zone-low { color: #4ade80; }
.vix-zone-mid { color: #fbbf24; }
.vix-zone-high { color: #f87171; }

/* 메인 카드 (코스피200 야간선물) */
.main-card {
  background: linear-gradient(135deg, #1a1a3a 0%, #252550 100%);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 20px;
  padding: 28px;
  margin-bottom: 28px;
}

.main-card.up-card { border-color: rgba(239,68,68,0.3); box-shadow: 0 0 30px rgba(239,68,68,0.1); }
.main-card.down-card { border-color: rgba(59,130,246,0.3); box-shadow: 0 0 30px rgba(59,130,246,0.1); }

.main-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.main-title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.main-title h2 { margin: 0; font-size: 1.3rem; color: #fff; }

.exchange-badge {
  padding: 3px 10px;
  background: rgba(102,126,234,0.2);
  color: #667eea;
  border-radius: 6px;
  font-size: 0.75rem;
  font-weight: 600;
}

.main-status {
  padding: 4px 14px;
  border-radius: 20px;
  font-size: 0.85rem;
  font-weight: 700;
}

.main-status.up { background: rgba(239,68,68,0.2); color: #ef4444; }
.main-status.down { background: rgba(59,130,246,0.2); color: #3b82f6; }
.main-status.flat { background: rgba(255,255,255,0.1); color: #9ca3af; }

.main-price {
  display: flex;
  align-items: baseline;
  gap: 16px;
  margin-bottom: 20px;
}

.main-price .price {
  font-size: 2.5rem;
  font-weight: 700;
  color: #fff;
}

.change { display: flex; gap: 6px; font-size: 1.1rem; font-weight: 600; }
.change.up { color: #ef4444; }
.change.down { color: #3b82f6; }
.change.flat { color: #9ca3af; }

.main-details {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.main-details .detail {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.main-details .label { font-size: 0.8rem; color: #888; }
.main-details .value { font-size: 1rem; color: #ddd; font-weight: 500; }

/* 카테고리 섹션 */
.category-sections { display: flex; flex-direction: column; gap: 24px; margin-bottom: 28px; }

.section-title {
  margin: 0 0 14px;
  font-size: 1.1rem;
  color: #fff;
  padding-left: 4px;
}

.futures-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 14px;
}

.futures-card {
  background: rgba(30, 30, 60, 0.6);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 14px;
  padding: 18px;
  transition: all 0.3s;
}

.futures-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0,0,0,0.3);
}

.futures-card.up-card { border-color: rgba(239,68,68,0.2); }
.futures-card.down-card { border-color: rgba(59,130,246,0.2); }

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.card-top .symbol { font-weight: 700; color: #fff; font-size: 1rem; }

.exchange-tag {
  font-size: 0.7rem;
  color: #888;
  padding: 2px 8px;
  background: rgba(255,255,255,0.05);
  border-radius: 4px;
}

.card-price {
  font-size: 1.4rem;
  font-weight: 700;
  margin-bottom: 4px;
}

.card-price.up { color: #ef4444; }
.card-price.down { color: #3b82f6; }
.card-price.flat { color: #e0e0e0; }

.card-change {
  font-size: 0.9rem;
  font-weight: 600;
  margin-bottom: 10px;
}

.card-change.up { color: #ef4444; }
.card-change.down { color: #3b82f6; }
.card-change.flat { color: #9ca3af; }

.card-range {
  display: flex;
  justify-content: space-between;
  font-size: 0.75rem;
  color: #666;
  padding-top: 8px;
  border-top: 1px solid rgba(255,255,255,0.05);
}

/* 테이블 */
.table-section { margin-bottom: 28px; }

.table-wrapper { overflow-x: auto; }

.futures-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9rem;
}

.futures-table th {
  padding: 12px 14px;
  text-align: left;
  color: #888;
  font-weight: 600;
  border-bottom: 1px solid rgba(255,255,255,0.1);
  font-size: 0.8rem;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.futures-table td {
  padding: 14px;
  border-bottom: 1px solid rgba(255,255,255,0.05);
  color: #ddd;
}

.futures-table .right { text-align: right; }

.futures-table tr:hover { background: rgba(255,255,255,0.03); }
.futures-table tr.error td { color: #666; }

.table-name { font-weight: 600; color: #fff; }
.table-symbol { font-size: 0.75rem; color: #666; margin-left: 6px; }

.futures-table td.up { color: #ef4444; font-weight: 600; }
.futures-table td.down { color: #3b82f6; font-weight: 600; }

/* 로딩/에러 */
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 60px;
  color: #888;
}

.spinner {
  width: 40px;
  height: 40px;
  border: 3px solid #2a2a4a;
  border-top-color: #667eea;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

.error-state {
  text-align: center;
  padding: 40px;
  color: #f87171;
}

.retry-btn {
  margin-top: 12px;
  padding: 10px 24px;
  background: #4a4a8a;
  color: #fff;
  border: none;
  border-radius: 8px;
  cursor: pointer;
}

.retry-btn:hover { background: #5a5a9a; }

.disclaimer {
  text-align: center;
  font-size: 0.75rem;
  color: #555;
  padding: 20px 0;
  border-top: 1px solid rgba(255,255,255,0.05);
}

/* 원유 상세 버튼 */
.oil-detail-btn {
  float: right;
  background: rgba(251, 146, 60, 0.15);
  border: 1px solid rgba(251, 146, 60, 0.3);
  color: #fb923c;
  padding: 4px 14px;
  border-radius: 8px;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s;
}

.oil-detail-btn:hover {
  background: rgba(251, 146, 60, 0.25);
}

/* 원유 상세 패널 */
.oil-detail-panel {
  background: rgba(30, 30, 60, 0.7);
  border: 1px solid rgba(251, 146, 60, 0.3);
  border-radius: 16px;
  padding: 24px;
  margin-bottom: 24px;
  animation: slideDown 0.3s ease;
}

@keyframes slideDown {
  from { opacity: 0; transform: translateY(-10px); }
  to { opacity: 1; transform: translateY(0); }
}

.oil-detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.oil-detail-header h3 {
  margin: 0;
  color: #fb923c;
  font-size: 1.2rem;
}

.close-detail-btn {
  background: rgba(255,255,255,0.1);
  border: 1px solid rgba(255,255,255,0.2);
  color: #888;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 1rem;
}

.close-detail-btn:hover { color: #fff; }

.oil-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 40px;
  color: #888;
}

.oil-price-main {
  text-align: center;
  margin-bottom: 24px;
}

.oil-price-big {
  font-size: 2.5rem;
  font-weight: 700;
  color: #fff;
}

.oil-price-sub {
  font-size: 0.9rem;
  color: #888;
  margin-top: 4px;
}

.oil-price-krw {
  font-size: 1rem;
  color: #9ca3af;
  margin-top: 4px;
}

.oil-info-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 24px;
}

.oil-info-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  background: rgba(255,255,255,0.04);
  border-radius: 10px;
  padding: 12px;
}

.oil-info-item .label {
  font-size: 0.75rem;
  color: #888;
  margin-bottom: 4px;
}

.oil-info-item .value {
  font-size: 0.95rem;
  font-weight: 600;
  color: #ddd;
}

.oil-info-item .value.up { color: #ef4444; }
.oil-info-item .value.down { color: #3b82f6; }
.oil-info-item .value.high { color: #ef4444; }
.oil-info-item .value.low { color: #3b82f6; }

.oil-chart-section {
  margin-top: 20px;
}

.oil-chart-section h4 {
  margin: 0 0 16px;
  color: #ddd;
  font-size: 1rem;
}

.oil-chart-container {
  height: 300px;
  background: rgba(255,255,255,0.02);
  border-radius: 12px;
  padding: 12px;
}

/* 반응형 */
@media (max-width: 768px) {
  .page-container { padding: 12px; }
  .main-price .price { font-size: 1.8rem; }
  .main-details { grid-template-columns: repeat(2, 1fr); }
  .futures-grid { grid-template-columns: 1fr; }
  .impact-banner { padding: 16px; }
  .page-header { flex-direction: column; align-items: flex-start; }
  .oil-info-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
