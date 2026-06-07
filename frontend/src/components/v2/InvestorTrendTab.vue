<!--
  투자자 동향 탭 — StockDetailDashboard 에서 분리 (P-IA 후속, 동작 변화 0).
  부모는 mainTab==='investor' 일 때만 마운트(v-else-if) + :key="stockCode" 로 종목 변경 시 새로 마운트.
  → 마운트 시 1회 fetch. (트레이딩지표 탭의 TradingIndicatorsPage 와 동일한 lazy-mount 패턴)
-->
<template>
  <div class="investor-tab-content">
    <div v-if="investorLoading" class="loading-overlay">
      <div class="loading-spinner"></div>
      <p>투자자 매매 동향 조회 중...</p>
    </div>

    <template v-else>
      <!-- 안내 문구 -->
      <div class="inv-info-banner">
        <span>💡</span>
        <span>이 데이터는 당일 순매수/매도 상위 20위 내에 진입한 날의 기록만 조회됩니다.</span>
      </div>

      <!-- 주가 vs 누적 순매수 추이 차트 -->
      <div v-if="invHasChartData" class="inv-section">
        <h2 class="inv-section-title">📊 주가 vs 누적 순매수 추이</h2>
        <div class="inv-period-selector">
          <button v-for="period in invChartPeriods" :key="period.value"
                  :class="['inv-period-btn', { active: invSelectedPeriod === period.value }]"
                  @click="invSelectedPeriod = period.value">
            {{ period.label }}
          </button>
        </div>
        <div class="inv-chart-wrapper">
          <Line :data="invChartData" :options="invChartOptions" />
        </div>
        <div class="inv-chart-legend">
          <span class="inv-legend-item"><span class="inv-legend-color" style="background:#888"></span>주가</span>
          <span class="inv-legend-item"><span class="inv-legend-color" style="background:#e53e3e"></span>외국인</span>
          <span class="inv-legend-item"><span class="inv-legend-color" style="background:#48bb78"></span>기관</span>
          <span class="inv-legend-item"><span class="inv-legend-color" style="background:#9f7aea"></span>연기금</span>
        </div>
      </div>

      <!-- 장중 수급 추이 -->
      <div v-if="invHasSurgeData" class="inv-section">
        <h2 class="inv-section-title">📈 장중 수급 추이 (오늘)</h2>
        <div class="inv-investor-tabs">
          <button v-for="type in invInvestorTypes" :key="type.value"
                  :class="['inv-tab-btn', { active: invSelectedInvestor === type.value }]"
                  @click="invSelectedInvestor = type.value">
            {{ type.icon }} {{ type.label }}
          </button>
        </div>
        <div v-if="invCurrentSurgeTrend.length > 0" class="inv-surge-grid">
          <div v-for="item in invCurrentSurgeTrend" :key="item.snapshotTime" class="inv-surge-item">
            <div class="inv-time-badge">{{ invFormatTime(item.snapshotTime) }}</div>
            <div class="inv-surge-details">
              <div class="inv-detail-row">
                <span class="label">순위</span>
                <span class="value">#{{ item.currentRank }}</span>
              </div>
              <div class="inv-detail-row highlight">
                <span class="label">누적 순매수</span>
                <span class="value" :class="invGetAmountClass(item.netBuyAmount)">
                  {{ invFormatAmount(item.netBuyAmount) }}
                </span>
              </div>
              <div class="inv-detail-row" v-if="invHasAmountChange(item.amountChange)">
                <span class="label">변화량</span>
                <span class="value" :class="invGetAmountClass(item.amountChange)">
                  {{ invFormatAmountWithSign(item.amountChange) }}
                </span>
              </div>
              <div class="inv-detail-row" v-if="item.currentPrice">
                <span class="label">현재가</span>
                <span class="value">{{ formatPrice(item.currentPrice) }}원</span>
              </div>
              <div class="inv-detail-row" v-if="item.changeRate">
                <span class="label">등락률</span>
                <span class="value" :class="invGetAmountClass(item.changeRate)">
                  {{ invFormatRate(item.changeRate) }}
                </span>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="inv-no-data">선택한 투자자의 장중 데이터가 없습니다.</div>
      </div>

      <!-- 일별 매매 동향 -->
      <div v-if="invRecentDailyTrades.length > 0" class="inv-section">
        <h2 class="inv-section-title">📅 일별 매매 동향 (최근 30일)</h2>
        <div class="inv-daily-trades">
          <div v-for="day in invRecentDailyTrades" :key="day.tradeDate" class="inv-daily-card">
            <div class="inv-date-header">{{ invFormatDate(day.tradeDate) }}</div>
            <div class="inv-investor-grid">
              <div v-for="inv in [
                { key: 'foreign', label: '외국인', icon: '🌍', color: '#e53e3e' },
                { key: 'institution', label: '기관', icon: '🏢', color: '#48bb78' },
                { key: 'pension', label: '연기금', icon: '💎', color: '#9f7aea' }
              ]" :key="inv.key" class="inv-investor-item" :style="{ borderLeftColor: inv.color }">
                <div class="inv-investor-label">{{ inv.icon }} {{ inv.label }}</div>
                <div class="inv-amounts">
                  <div class="inv-amount-row">
                    <span class="label">매수:</span>
                    <span class="value">{{ invFormatAmount(day[inv.key]?.buyAmount) }}</span>
                  </div>
                  <div class="inv-amount-row">
                    <span class="label">매도:</span>
                    <span class="value">{{ invFormatAmount(day[inv.key]?.sellAmount) }}</span>
                  </div>
                  <div class="inv-amount-row net">
                    <span class="label">순매수:</span>
                    <span class="value" :class="invGetAmountClass(day[inv.key]?.netBuyAmount)">
                      {{ invFormatAmount(day[inv.key]?.netBuyAmount) }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="!invHasSurgeData && !invHasChartData && !investorLoading" class="inv-no-data">
        <p>💡 해당 종목의 투자자 매매 데이터가 없습니다.</p>
        <p class="hint">상위 50개 종목에 포함된 경우에만 데이터가 수집됩니다.</p>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import api from '../../utils/api';
import { Line } from 'vue-chartjs';
import {
  Chart as ChartJS,
  CategoryScale, LinearScale, PointElement, LineElement, Title, Tooltip, Legend
} from 'chart.js';

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Title, Tooltip, Legend);

const props = defineProps({
  stockCode: { type: String, required: true }
});

// 상태
const investorLoading = ref(false);
const invStockData = ref(null);
const invSurgeTrend = ref({ FOREIGN: [], INSTITUTION: [], PENSION: [] });
const invSelectedInvestor = ref('FOREIGN');
const invSelectedPeriod = ref(30);

const invInvestorTypes = [
  { value: 'FOREIGN', label: '외국인', icon: '🌍' },
  { value: 'INSTITUTION', label: '기관', icon: '🏢' },
  { value: 'PENSION', label: '연기금', icon: '💎' }
];

const invChartPeriods = [
  { value: 30, label: '1개월' },
  { value: 90, label: '3개월' },
  { value: 180, label: '6개월' }
];

// ========== 계산 속성 ==========
const invHasSurgeData = computed(() =>
  invSurgeTrend.value.FOREIGN.length > 0 ||
  invSurgeTrend.value.INSTITUTION.length > 0 ||
  invSurgeTrend.value.PENSION.length > 0
);

const invCurrentSurgeTrend = computed(() =>
  invSurgeTrend.value[invSelectedInvestor.value] || []
);

const invHasChartData = computed(() =>
  invStockData.value?.dailyTrades?.length > 0
);

const invRecentDailyTrades = computed(() => {
  if (!invStockData.value?.dailyTrades) return [];
  return invStockData.value.dailyTrades.slice(0, 30);
});

const invFilteredChartData = computed(() => {
  if (!invStockData.value?.dailyTrades) return [];
  const sorted = [...invStockData.value.dailyTrades]
    .sort((a, b) => new Date(a.tradeDate) - new Date(b.tradeDate));
  const filtered = sorted.slice(-invSelectedPeriod.value);
  let fCum = 0, iCum = 0, pCum = 0;
  return filtered.map(day => {
    fCum += Number(day.foreign?.netBuyAmount || 0);
    iCum += Number(day.institution?.netBuyAmount || 0);
    pCum += Number(day.pension?.netBuyAmount || 0);
    return {
      date: day.tradeDate,
      price: day.closePrice || day.foreign?.closePrice || day.institution?.closePrice,
      foreignCumulative: fCum,
      institutionCumulative: iCum,
      pensionCumulative: pCum
    };
  });
});

const invChartData = computed(() => {
  const data = invFilteredChartData.value;
  return {
    labels: data.map(d => {
      const date = new Date(d.date);
      return `${date.getMonth() + 1}/${date.getDate()}`;
    }),
    datasets: [
      { label: '주가', data: data.map(d => d.price), borderColor: '#888', backgroundColor: 'rgba(136,136,136,0.1)', yAxisID: 'y-price', tension: 0.1, pointRadius: 2, borderWidth: 2 },
      { label: '외국인', data: data.map(d => d.foreignCumulative), borderColor: '#e53e3e', backgroundColor: 'rgba(229,62,62,0.1)', yAxisID: 'y-cumulative', tension: 0.1, pointRadius: 2, borderWidth: 2 },
      { label: '기관', data: data.map(d => d.institutionCumulative), borderColor: '#48bb78', backgroundColor: 'rgba(72,187,120,0.1)', yAxisID: 'y-cumulative', tension: 0.1, pointRadius: 2, borderWidth: 2 },
      { label: '연기금', data: data.map(d => d.pensionCumulative), borderColor: '#9f7aea', backgroundColor: 'rgba(159,122,234,0.1)', yAxisID: 'y-cumulative', tension: 0.1, pointRadius: 2, borderWidth: 2 }
    ]
  };
});

const invChartOptions = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  interaction: { mode: 'index', intersect: false },
  plugins: {
    legend: { display: false },
    tooltip: {
      backgroundColor: 'rgba(15,15,35,0.95)',
      titleColor: '#fff', bodyColor: '#ccc',
      borderColor: '#4a4a8a', borderWidth: 1, padding: 12,
      callbacks: {
        label: (ctx) => {
          const label = ctx.dataset.label || '';
          const value = ctx.parsed.y;
          if (label === '주가') return `${label}: ${Number(value).toLocaleString()}원`;
          return `${label}: ${value.toFixed(2)}억`;
        }
      }
    }
  },
  scales: {
    x: { ticks: { color: '#888', maxTicksLimit: 15 }, grid: { color: 'rgba(255, 255, 255, 0.05)' } },
    'y-price': {
      type: 'linear', display: true, position: 'left',
      title: { display: true, text: '주가 (원)', color: '#888' },
      ticks: { color: '#888', callback: (v) => Number(v).toLocaleString() },
      grid: { color: 'rgba(255, 255, 255, 0.05)' }
    },
    'y-cumulative': {
      type: 'linear', display: true, position: 'right',
      title: { display: true, text: '누적 순매수 (억)', color: '#888' },
      ticks: { color: '#888', callback: (v) => v.toFixed(0) + '억' },
      grid: { drawOnChartArea: false }
    }
  }
}));

// ========== 데이터 조회 (마운트 시 1회) ==========
const fetchInvestorData = async () => {
  if (!props.stockCode) return;
  investorLoading.value = true;
  try {
    const [dailyRes, foreignRes, instRes, pensionRes] = await Promise.all([
      api.get(`/investor/stock/${props.stockCode}`, { params: { days: 180 } }),
      api.get(`/investor/surge/trend/${props.stockCode}`, { params: { investorType: 'FOREIGN' } }),
      api.get(`/investor/surge/trend/${props.stockCode}`, { params: { investorType: 'INSTITUTION' } }),
      api.get(`/investor/surge/trend/${props.stockCode}`, { params: { investorType: 'PENSION' } })
    ]);

    if (dailyRes.data.success && dailyRes.data.data) {
      invStockData.value = dailyRes.data.data;
    }
    if (foreignRes.data.success) invSurgeTrend.value.FOREIGN = foreignRes.data.data || [];
    if (instRes.data.success) invSurgeTrend.value.INSTITUTION = instRes.data.data || [];
    if (pensionRes.data.success) invSurgeTrend.value.PENSION = pensionRes.data.data || [];

    // 데이터 있는 탭으로 자동 전환
    if (!invSurgeTrend.value[invSelectedInvestor.value]?.length) {
      if (invSurgeTrend.value.FOREIGN.length) invSelectedInvestor.value = 'FOREIGN';
      else if (invSurgeTrend.value.INSTITUTION.length) invSelectedInvestor.value = 'INSTITUTION';
      else if (invSurgeTrend.value.PENSION.length) invSelectedInvestor.value = 'PENSION';
    }
  } catch (err) {
    console.error('투자자 데이터 조회 오류:', err);
  } finally {
    investorLoading.value = false;
  }
};

onMounted(fetchInvestorData);

// ========== 포맷터 ==========
const formatPrice = (price) => {
  if (!price) return '-';
  return Number(price).toLocaleString('ko-KR');
};

const invFormatTime = (timeStr) => {
  if (!timeStr) return '-';
  const parts = timeStr.split(':');
  return `${parts[0]}:${parts[1]}`;
};

const invFormatAmount = (value) => {
  if (!value) return '0억';
  const num = Number(value);
  return `${num.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}억`;
};

const invFormatAmountWithSign = (value) => {
  if (!value) return '0억';
  const num = Number(value);
  const sign = num > 0 ? '+' : '';
  return `${sign}${num.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}억`;
};

const invFormatRate = (value) => {
  if (!value) return '0.00%';
  const sign = value > 0 ? '+' : '';
  return `${sign}${Number(value).toFixed(2)}%`;
};

const invFormatDate = (dateStr) => {
  const date = new Date(dateStr);
  return `${date.getMonth() + 1}월 ${date.getDate()}일 (${['일','월','화','수','목','금','토'][date.getDay()]})`;
};

const invGetAmountClass = (value) => {
  if (!value) return '';
  return Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : '';
};

const invHasAmountChange = (value) => {
  if (value === null || value === undefined) return false;
  return Math.abs(Number(value)) > 0.01;
};
</script>

<style scoped>
/* 로딩 (부모와 동일 — 분리된 컴포넌트라 자체 정의) */
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
  border-top-color: var(--primary-start);
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin-bottom: 16px;
}

@keyframes spin { to { transform: rotate(360deg); } }

.investor-tab-content {
  max-width: 1200px;
  margin: 0 auto;
}

.inv-info-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(30, 30, 60, 0.6);
  border: 1px solid rgba(74, 74, 138, 0.3);
  border-radius: 10px;
  padding: 12px 16px;
  margin-bottom: 20px;
  color: #ccc;
  font-size: 0.9rem;
}

.inv-section {
  margin-bottom: 24px;
}

.inv-section-title {
  color: #fff;
  font-size: 1.2rem;
  margin-bottom: 14px;
  padding-bottom: 8px;
  border-bottom: 2px solid rgba(255,255,255,0.1);
}

.inv-period-selector {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.inv-period-btn {
  padding: 8px 16px;
  background: rgba(30, 30, 60, 0.6);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 8px;
  color: #888;
  cursor: pointer;
  font-size: 0.9rem;
  font-weight: 600;
  transition: all 0.3s;
}

.inv-period-btn.active {
  background: rgba(102,126,234,0.3);
  border-color: rgba(102,126,234,0.5);
  color: #fff;
}

.inv-chart-wrapper {
  background: rgba(30, 30, 60, 0.5);
  border-radius: 12px;
  padding: 16px;
  height: 350px;
  border: 1px solid rgba(255,255,255,0.08);
}

.inv-chart-legend {
  display: flex;
  justify-content: center;
  gap: 24px;
  margin-top: 12px;
}

.inv-legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #ccc;
  font-size: 0.85rem;
}

.inv-legend-color {
  width: 20px;
  height: 3px;
  border-radius: 2px;
}

.inv-investor-tabs {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.inv-tab-btn {
  padding: 10px 20px;
  background: rgba(30, 30, 60, 0.6);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 10px;
  color: #888;
  cursor: pointer;
  font-size: 0.95rem;
  font-weight: 600;
  transition: all 0.3s;
}

.inv-tab-btn.active {
  background: rgba(229, 62, 62, 0.2);
  border-color: rgba(229, 62, 62, 0.4);
  color: #e53e3e;
}

.inv-tab-btn:hover:not(.active) {
  border-color: rgba(255,255,255,0.2);
  color: #ccc;
}

.inv-surge-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.inv-surge-item {
  background: rgba(30, 30, 60, 0.5);
  border-radius: 12px;
  padding: 14px;
  border: 1px solid rgba(255,255,255,0.08);
}

.inv-time-badge {
  display: inline-block;
  background: rgba(102,126,234,0.3);
  color: #fff;
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 0.85rem;
  font-weight: 600;
  margin-bottom: 10px;
}

.inv-surge-details {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.inv-detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.inv-detail-row .label { color: #888; font-size: 0.85rem; }
.inv-detail-row .value { font-weight: 600; color: #ddd; font-family: monospace; }

.inv-detail-row.highlight {
  background: rgba(255,255,255,0.04);
  padding: 6px 8px;
  border-radius: 8px;
  margin: 2px 0;
}

.inv-no-data {
  text-align: center;
  padding: 40px;
  color: #666;
}

.inv-no-data .hint {
  font-size: 0.9rem;
  color: #555;
  margin-top: 8px;
}

.inv-daily-trades {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 60vh;
  overflow-y: auto;
  padding: 4px;
}

.inv-daily-card {
  background: rgba(30, 30, 60, 0.5);
  border-radius: 12px;
  padding: 14px;
  border: 1px solid rgba(255,255,255,0.08);
}

.inv-daily-card:hover { border-color: rgba(255,255,255,0.15); }

.inv-date-header {
  font-size: 1rem;
  font-weight: 700;
  color: #fff;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}

.inv-investor-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.inv-investor-item {
  background: rgba(0, 0, 0, 0.2);
  border-radius: 10px;
  padding: 12px;
  border: 1px solid var(--border-light);
  border-left: 3px solid;
}

.inv-investor-label {
  font-weight: 700;
  font-size: 0.9rem;
  color: #fff;
  margin-bottom: 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}

.inv-amounts {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.inv-amount-row {
  display: flex;
  justify-content: space-between;
  font-size: 0.85rem;
}

.inv-amount-row .label { color: #888; }
.inv-amount-row .value { font-weight: 600; font-family: monospace; color: #ddd; }
.inv-amount-row.net { margin-top: 4px; padding-top: 4px; border-top: 1px solid rgba(255,255,255,0.08); }
.inv-amount-row.net .label { font-weight: 700; }

.inv-amount-row .value.positive { color: #e53e3e !important; }
.inv-amount-row .value.negative { color: #3b82f6 !important; }
.inv-detail-row .value.positive { color: #e53e3e !important; }
.inv-detail-row .value.negative { color: #3b82f6 !important; }

@media (max-width: 768px) {
  .inv-investor-grid { grid-template-columns: 1fr; }
  .inv-surge-grid { grid-template-columns: 1fr; }
  .inv-investor-tabs { flex-wrap: wrap; }
  .inv-tab-btn { flex: 1; min-width: 100px; text-align: center; }
  .inv-chart-wrapper { height: 260px; padding: 12px; }
  .inv-chart-legend { gap: 14px; }
  .inv-legend-item { font-size: 0.78rem; }
}

@media (max-width: 480px) {
  .inv-chart-wrapper { height: 220px; padding: 10px; }
}
</style>
