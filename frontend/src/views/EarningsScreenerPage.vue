<template>
  <div class="screener-page">
    <LoadingSpinner v-if="loading" />
    <div v-else class="content-wrapper">
      <div class="page-header">
        <button @click="goBack" class="back-button">← 돌아가기</button>
        <h1>실적 기반 저평가 스크리너</h1>
        <p class="subtitle">마법의 공식, PEG, 턴어라운드 종목 발굴</p>
      </div>

      <div class="screener-tabs">
        <button v-for="tab in tabs" :key="tab.value"
                :class="['tab-btn', { active: selectedTab === tab.value }]"
                @click="changeTab(tab.value)">
          {{ tab.icon }} {{ tab.label }}
        </button>
      </div>

      <!-- 마법의 공식 탭 -->
      <div v-if="selectedTab === 'magic-formula'" class="tab-content">
        <div class="filter-bar">
          <div class="filter-item">
            <label>최소 시가총액</label>
            <select v-model="magicFormulaFilters.minMarketCap" @change="fetchMagicFormula">
              <option :value="null">전체</option>
              <option :value="100">100억 이상</option>
              <option :value="500">500억 이상</option>
              <option :value="1000">1,000억 이상</option>
              <option :value="5000">5,000억 이상</option>
              <option :value="10000">1조 이상</option>
            </select>
          </div>
          <div class="filter-item">
            <label>조회 개수</label>
            <select v-model="magicFormulaFilters.limit" @change="fetchMagicFormula">
              <option :value="20">20개</option>
              <option :value="30">30개</option>
              <option :value="50">50개</option>
              <option :value="100">100개</option>
            </select>
          </div>
          <button @click="fetchMagicFormula" class="refresh-btn">새로고침</button>
        </div>

        <div class="info-box">
          <strong>마법의 공식이란?</strong>
          <p>영업이익률(수익성) + ROE(자기자본수익률) + 저PER(저평가) 순위를 합산하여 저평가된 우량주를 찾는 전략입니다.</p>
        </div>

        <!-- AI 분석 섹션 -->
        <div class="ai-section">
          <button @click="fetchMagicFormulaAI" class="ai-btn" :disabled="aiLoading">
            <span class="ai-icon">🤖</span>
            {{ aiLoading ? 'Gemini 분석 중...' : 'Gemini AI 추천 받기' }}
          </button>
          <div v-if="magicFormulaAI" class="ai-result">
            <div class="ai-result-header">
              <span class="ai-badge">Gemini AI 분석</span>
            </div>
            <div class="ai-result-content" v-html="formatAIResponse(magicFormulaAI)"></div>
          </div>
        </div>

        <div v-if="magicFormulaStocks.length > 0" class="stocks-table-wrapper">
          <table class="stocks-table">
            <thead>
              <tr>
                <th>순위</th>
                <th>종목</th>
                <th>시장</th>
                <th>PER</th>
                <th>PBR</th>
                <th>ROE</th>
                <th>영업이익률</th>
                <th>시가총액</th>
                <th>종합점수</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="stock in magicFormulaStocks" :key="stock.stockCode">
                <td class="rank">{{ stock.magicFormulaRank }}</td>
                <td class="stock-info">
                  <span class="stock-name">{{ stock.stockName }}</span>
                  <span class="stock-code">{{ stock.stockCode }}</span>
                </td>
                <td>{{ stock.market }}</td>
                <td :class="getValueClass(stock.per, 'per')">{{ formatNumber(stock.per, 2) }}</td>
                <td :class="getValueClass(stock.pbr, 'pbr')">{{ formatNumber(stock.pbr, 2) }}</td>
                <td :class="getValueClass(stock.roe, 'roe')">{{ formatPercent(stock.roe) }}</td>
                <td :class="getValueClass(stock.operatingMargin, 'margin')">{{ formatPercent(stock.operatingMargin) }}</td>
                <td>{{ formatMarketCap(stock.marketCap) }}</td>
                <td class="score">{{ stock.magicFormulaScore }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="no-data">
          <p>조건에 맞는 종목이 없습니다.</p>
        </div>
      </div>

      <!-- PEG 스크리너 탭 -->
      <div v-if="selectedTab === 'peg'" class="tab-content">
        <div class="filter-bar">
          <div class="filter-item">
            <label>최대 PEG</label>
            <select v-model="pegFilters.maxPeg" @change="fetchPegStocks">
              <option :value="0.5">0.5 이하</option>
              <option :value="0.7">0.7 이하</option>
              <option :value="1.0">1.0 이하</option>
              <option :value="1.5">1.5 이하</option>
              <option :value="2.0">2.0 이하</option>
            </select>
          </div>
          <div class="filter-item">
            <label>최소 EPS 성장률</label>
            <select v-model="pegFilters.minEpsGrowth" @change="fetchPegStocks">
              <option :value="5">5% 이상</option>
              <option :value="10">10% 이상</option>
              <option :value="15">15% 이상</option>
              <option :value="20">20% 이상</option>
              <option :value="30">30% 이상</option>
            </select>
          </div>
          <div class="filter-item">
            <label>조회 개수</label>
            <select v-model="pegFilters.limit" @change="fetchPegStocks">
              <option :value="20">20개</option>
              <option :value="30">30개</option>
              <option :value="50">50개</option>
            </select>
          </div>
          <button @click="fetchPegStocks" class="refresh-btn">새로고침</button>
        </div>

        <div class="info-box">
          <strong>PEG란?</strong>
          <p>PEG = PER / EPS성장률. PEG가 1 미만이면 성장률 대비 저평가된 종목으로 간주합니다. 피터 린치가 애용한 지표입니다.</p>
        </div>

        <!-- AI 분석 섹션 -->
        <div class="ai-section">
          <button @click="fetchPegAI" class="ai-btn" :disabled="aiLoading">
            <span class="ai-icon">🤖</span>
            {{ aiLoading ? 'Gemini 분석 중...' : 'Gemini AI 추천 받기' }}
          </button>
          <div v-if="pegAI" class="ai-result">
            <div class="ai-result-header">
              <span class="ai-badge">Gemini AI 분석</span>
            </div>
            <div class="ai-result-content" v-html="formatAIResponse(pegAI)"></div>
          </div>
        </div>

        <div v-if="pegStocks.length > 0" class="stocks-table-wrapper">
          <table class="stocks-table">
            <thead>
              <tr>
                <th>#</th>
                <th>종목</th>
                <th>시장</th>
                <th>PEG</th>
                <th>PER</th>
                <th>EPS 성장률</th>
                <th>ROE</th>
                <th>시가총액</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(stock, index) in pegStocks" :key="stock.stockCode">
                <td class="rank">{{ index + 1 }}</td>
                <td class="stock-info">
                  <span class="stock-name">{{ stock.stockName }}</span>
                  <span class="stock-code">{{ stock.stockCode }}</span>
                </td>
                <td>{{ stock.market }}</td>
                <td :class="getPegClass(stock.peg)">{{ formatNumber(stock.peg, 2) }}</td>
                <td>{{ formatNumber(stock.per, 2) }}</td>
                <td class="positive">{{ formatPercent(stock.epsGrowth) }}</td>
                <td>{{ formatPercent(stock.roe) }}</td>
                <td>{{ formatMarketCap(stock.marketCap) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="no-data">
          <p>조건에 맞는 종목이 없습니다.</p>
        </div>
      </div>

      <!-- 턴어라운드 탭 -->
      <div v-if="selectedTab === 'turnaround'" class="tab-content">
        <div class="filter-bar">
          <div class="filter-item">
            <label>조회 개수</label>
            <select v-model="turnaroundFilters.limit" @change="fetchTurnaroundStocks">
              <option :value="20">20개</option>
              <option :value="30">30개</option>
              <option :value="50">50개</option>
            </select>
          </div>
          <button @click="fetchTurnaroundStocks" class="refresh-btn">새로고침</button>
        </div>

        <div class="info-box">
          <strong>턴어라운드 종목이란?</strong>
          <p>적자에서 흑자로 전환되었거나, 순이익이 50% 이상 급증한 종목입니다. 실적 개선 모멘텀이 기대됩니다.</p>
        </div>

        <!-- AI 분석 섹션 -->
        <div class="ai-section">
          <button @click="fetchTurnaroundAI" class="ai-btn" :disabled="aiLoading">
            <span class="ai-icon">🤖</span>
            {{ aiLoading ? 'Gemini 분석 중...' : 'Gemini AI 추천 받기' }}
          </button>
          <div v-if="turnaroundAI" class="ai-result">
            <div class="ai-result-header">
              <span class="ai-badge">Gemini AI 분석</span>
            </div>
            <div class="ai-result-content" v-html="formatAIResponse(turnaroundAI)"></div>
          </div>
        </div>

        <div v-if="turnaroundStocks.length > 0" class="stocks-grid">
          <div v-for="(stock, index) in turnaroundStocks" :key="stock.stockCode"
               :class="['stock-card', getTurnaroundClass(stock.turnaroundType)]">
            <div class="turnaround-badge" :class="getTurnaroundClass(stock.turnaroundType)">
              {{ getTurnaroundLabel(stock.turnaroundType) }}
            </div>

            <div class="stock-header">
              <div class="stock-info-col">
                <span class="stock-name">{{ stock.stockName }}</span>
                <span class="stock-code">{{ stock.stockCode }}</span>
              </div>
              <span class="rank-badge">#{{ index + 1 }}</span>
            </div>

            <div class="stock-details">
              <div class="detail-row highlight">
                <span class="label">이전 순이익</span>
                <span class="value" :class="getAmountClass(stock.previousNetIncome)">
                  {{ formatAmount(stock.previousNetIncome) }}
                </span>
              </div>
              <div class="detail-row highlight">
                <span class="label">현재 순이익</span>
                <span class="value" :class="getAmountClass(stock.currentNetIncome)">
                  {{ formatAmount(stock.currentNetIncome) }}
                </span>
              </div>
              <div class="detail-row" v-if="stock.turnaroundType !== 'LOSS_TO_PROFIT'">
                <span class="label">변화율</span>
                <span class="value positive">+{{ formatPercent(stock.netIncomeChangeRate) }}</span>
              </div>
              <div class="detail-row">
                <span class="label">PER</span>
                <span class="value">{{ formatNumber(stock.per, 2) }}</span>
              </div>
              <div class="detail-row">
                <span class="label">시가총액</span>
                <span class="value">{{ formatMarketCap(stock.marketCap) }}</span>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="no-data">
          <p>턴어라운드 종목이 없습니다.</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import api from '../utils/api';
import LoadingSpinner from '../components/LoadingSpinner.vue';

const router = useRouter();
const loading = ref(false);
const selectedTab = ref('magic-formula');

const tabs = [
  { value: 'magic-formula', label: '마법의 공식', icon: '✨' },
  { value: 'peg', label: 'PEG 스크리너', icon: '📈' },
  { value: 'turnaround', label: '턴어라운드', icon: '🔄' }
];

// 데이터
const magicFormulaStocks = ref([]);
const pegStocks = ref([]);
const turnaroundStocks = ref([]);

// AI 분석 결과
const aiLoading = ref(false);
const magicFormulaAI = ref('');
const pegAI = ref('');
const turnaroundAI = ref('');

// 필터
const magicFormulaFilters = ref({
  limit: 30,
  minMarketCap: null
});

const pegFilters = ref({
  maxPeg: 1.0,
  minEpsGrowth: 10,
  limit: 30
});

const turnaroundFilters = ref({
  limit: 30
});

const changeTab = (tab) => {
  selectedTab.value = tab;
  if (tab === 'magic-formula' && magicFormulaStocks.value.length === 0) {
    fetchMagicFormula();
  } else if (tab === 'peg' && pegStocks.value.length === 0) {
    fetchPegStocks();
  } else if (tab === 'turnaround' && turnaroundStocks.value.length === 0) {
    fetchTurnaroundStocks();
  }
};

const fetchMagicFormula = async () => {
  loading.value = true;
  try {
    const params = {
      limit: magicFormulaFilters.value.limit
    };
    if (magicFormulaFilters.value.minMarketCap) {
      params.minMarketCap = magicFormulaFilters.value.minMarketCap;
    }
    const response = await api.get('/screener/magic-formula', { params });
    if (response.data.success) {
      magicFormulaStocks.value = response.data.data;
    }
  } catch (error) {
    console.error('마법의 공식 스크리닝 오류:', error);
  } finally {
    loading.value = false;
  }
};

const fetchPegStocks = async () => {
  loading.value = true;
  try {
    const response = await api.get('/screener/peg', {
      params: {
        maxPeg: pegFilters.value.maxPeg,
        minEpsGrowth: pegFilters.value.minEpsGrowth,
        limit: pegFilters.value.limit
      }
    });
    if (response.data.success) {
      pegStocks.value = response.data.data;
    }
  } catch (error) {
    console.error('PEG 스크리닝 오류:', error);
  } finally {
    loading.value = false;
  }
};

const fetchTurnaroundStocks = async () => {
  loading.value = true;
  try {
    const response = await api.get('/screener/turnaround', {
      params: {
        limit: turnaroundFilters.value.limit
      }
    });
    if (response.data.success) {
      turnaroundStocks.value = response.data.data;
    }
  } catch (error) {
    console.error('턴어라운드 스크리닝 오류:', error);
  } finally {
    loading.value = false;
  }
};

// AI 분석 함수들
const fetchMagicFormulaAI = async () => {
  aiLoading.value = true;
  magicFormulaAI.value = '';
  try {
    const params = { limit: 10 };
    if (magicFormulaFilters.value.minMarketCap) {
      params.minMarketCap = magicFormulaFilters.value.minMarketCap;
    }
    const response = await api.get('/screener/magic-formula/ai-analysis', { params });
    if (response.data.success) {
      magicFormulaAI.value = response.data.analysis;
    } else {
      magicFormulaAI.value = response.data.message || 'AI 분석에 실패했습니다.';
    }
  } catch (error) {
    console.error('마법의 공식 AI 분석 오류:', error);
    magicFormulaAI.value = 'AI 분석 중 오류가 발생했습니다.';
  } finally {
    aiLoading.value = false;
  }
};

const fetchPegAI = async () => {
  aiLoading.value = true;
  pegAI.value = '';
  try {
    const response = await api.get('/screener/peg/ai-analysis', {
      params: {
        maxPeg: pegFilters.value.maxPeg,
        minEpsGrowth: pegFilters.value.minEpsGrowth,
        limit: 10
      }
    });
    if (response.data.success) {
      pegAI.value = response.data.analysis;
    } else {
      pegAI.value = response.data.message || 'AI 분석에 실패했습니다.';
    }
  } catch (error) {
    console.error('PEG AI 분석 오류:', error);
    pegAI.value = 'AI 분석 중 오류가 발생했습니다.';
  } finally {
    aiLoading.value = false;
  }
};

const fetchTurnaroundAI = async () => {
  aiLoading.value = true;
  turnaroundAI.value = '';
  try {
    const response = await api.get('/screener/turnaround/ai-analysis', {
      params: { limit: 10 }
    });
    if (response.data.success) {
      turnaroundAI.value = response.data.analysis;
    } else {
      turnaroundAI.value = response.data.message || 'AI 분석에 실패했습니다.';
    }
  } catch (error) {
    console.error('턴어라운드 AI 분석 오류:', error);
    turnaroundAI.value = 'AI 분석 중 오류가 발생했습니다.';
  } finally {
    aiLoading.value = false;
  }
};

const formatAIResponse = (text) => {
  if (!text) return '';
  // 줄바꿈을 <br>로 변환하고, **text**를 <strong>으로 변환
  return text
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>');
};

const goBack = () => {
  router.back();
};

// 포맷팅 함수
const formatNumber = (value, decimals = 0) => {
  if (value === null || value === undefined) return '-';
  return Number(value).toLocaleString('ko-KR', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals
  });
};

const formatPercent = (value) => {
  if (value === null || value === undefined) return '-';
  return `${Number(value).toFixed(2)}%`;
};

const formatMarketCap = (value) => {
  if (!value) return '-';
  const num = Number(value);
  if (num >= 10000) {
    return `${(num / 10000).toFixed(1)}조`;
  }
  return `${num.toLocaleString('ko-KR')}억`;
};

const formatAmount = (value) => {
  if (value === null || value === undefined) return '-';
  const num = Number(value);
  const sign = num >= 0 ? '' : '';
  if (Math.abs(num) >= 10000) {
    return `${sign}${(num / 10000).toFixed(1)}조`;
  }
  return `${sign}${num.toLocaleString('ko-KR')}억`;
};

// 스타일 클래스 함수
const getValueClass = (value, type) => {
  if (value === null || value === undefined) return '';
  const num = Number(value);

  switch (type) {
    case 'per':
      if (num < 10) return 'positive';
      if (num > 30) return 'negative';
      return '';
    case 'pbr':
      if (num < 1) return 'positive';
      if (num > 3) return 'negative';
      return '';
    case 'roe':
    case 'margin':
      if (num >= 15) return 'positive';
      if (num < 5) return 'negative';
      return '';
    default:
      return '';
  }
};

const getPegClass = (peg) => {
  if (peg === null || peg === undefined) return '';
  const num = Number(peg);
  if (num < 0.7) return 'very-positive';
  if (num < 1.0) return 'positive';
  if (num > 1.5) return 'negative';
  return '';
};

const getAmountClass = (value) => {
  if (value === null || value === undefined) return '';
  return Number(value) >= 0 ? 'positive' : 'negative';
};

const getTurnaroundClass = (type) => {
  switch (type) {
    case 'LOSS_TO_PROFIT':
      return 'loss-to-profit';
    case 'PROFIT_GROWTH':
      return 'profit-growth';
    default:
      return '';
  }
};

const getTurnaroundLabel = (type) => {
  switch (type) {
    case 'LOSS_TO_PROFIT':
      return '흑자전환';
    case 'PROFIT_GROWTH':
      return '이익급증';
    default:
      return '';
  }
};

onMounted(() => {
  fetchMagicFormula();
});
</script>

<style scoped>
.screener-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
  padding: 2rem;
}

.content-wrapper {
  max-width: 1400px;
  margin: 0 auto;
  background: #0f0f23;
  border-radius: 20px;
  padding: 2rem;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
  border: 1px solid #2a2a4a;
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
}

.subtitle {
  color: #888;
  font-size: 1.1rem;
}

.screener-tabs {
  display: flex;
  justify-content: center;
  gap: 1rem;
  margin-bottom: 2rem;
  border-bottom: 2px solid #2a2a4a;
}

.tab-btn {
  padding: 1rem 2rem;
  background: none;
  border: none;
  color: #666;
  cursor: pointer;
  font-size: 1.1rem;
  font-weight: 600;
  transition: all 0.3s;
  border-bottom: 3px solid transparent;
  margin-bottom: -2px;
}

.tab-btn.active {
  color: #4ade80;
  border-bottom-color: #4ade80;
}

.tab-btn:hover:not(.active) {
  color: #aaa;
}

.tab-content {
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.filter-bar {
  display: flex;
  gap: 1.5rem;
  align-items: center;
  margin-bottom: 1.5rem;
  padding: 1rem;
  background: #1a1a3a;
  border-radius: 10px;
  flex-wrap: wrap;
}

.filter-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.filter-item label {
  font-weight: 600;
  color: #aaa;
  white-space: nowrap;
}

.filter-item select {
  padding: 0.5rem 1rem;
  border: 1px solid #3a3a5a;
  border-radius: 8px;
  font-size: 1rem;
  cursor: pointer;
  background: #2a2a4a;
  color: #fff;
}

.refresh-btn {
  padding: 0.5rem 1rem;
  background: #4a4a8a;
  color: white;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 0.9rem;
  font-weight: 600;
  transition: all 0.3s;
  margin-left: auto;
}

.refresh-btn:hover {
  background: #5a5a9a;
}

.info-box {
  background: #1a1a3a;
  border-left: 4px solid #4ade80;
  padding: 1rem 1.5rem;
  margin-bottom: 1.5rem;
  border-radius: 0 10px 10px 0;
}

.info-box strong {
  color: #4ade80;
  display: block;
  margin-bottom: 0.5rem;
}

.info-box p {
  color: #aaa;
  margin: 0;
  font-size: 0.95rem;
  line-height: 1.5;
}

.stocks-table-wrapper {
  overflow-x: auto;
}

.stocks-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.95rem;
}

.stocks-table th,
.stocks-table td {
  padding: 1rem;
  text-align: left;
  border-bottom: 1px solid #2a2a4a;
}

.stocks-table th {
  background: #1a1a3a;
  color: #888;
  font-weight: 600;
  white-space: nowrap;
}

.stocks-table tbody tr:hover {
  background: #1a1a3a;
}

.stocks-table .rank {
  font-weight: 700;
  color: #4ade80;
  text-align: center;
}

.stocks-table .stock-info {
  display: flex;
  flex-direction: column;
}

.stocks-table .stock-name {
  font-weight: 600;
  color: #fff;
}

.stocks-table .stock-code {
  font-size: 0.85rem;
  color: #666;
  font-family: monospace;
}

.stocks-table .score {
  font-weight: 700;
  color: #fff;
  text-align: center;
}

.positive {
  color: #4ade80 !important;
}

.very-positive {
  color: #22c55e !important;
  font-weight: 700;
}

.negative {
  color: #ef4444 !important;
}

/* 턴어라운드 카드 그리드 */
.stocks-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 1.5rem;
}

.stock-card {
  background: #1a1a3a;
  border-radius: 15px;
  padding: 1.5rem;
  border: 2px solid #2a2a4a;
  transition: all 0.3s;
  position: relative;
}

.stock-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.3);
}

.stock-card.loss-to-profit {
  border-color: #4ade80;
  background: linear-gradient(135deg, #1a1a3a 0%, #1a2a1a 100%);
}

.stock-card.profit-growth {
  border-color: #3b82f6;
  background: linear-gradient(135deg, #1a1a3a 0%, #1a1a2a 100%);
}

.turnaround-badge {
  position: absolute;
  top: -10px;
  right: 15px;
  padding: 0.3rem 0.8rem;
  border-radius: 15px;
  font-size: 0.8rem;
  font-weight: 700;
  color: white;
}

.turnaround-badge.loss-to-profit {
  background: linear-gradient(135deg, #4ade80 0%, #22c55e 100%);
}

.turnaround-badge.profit-growth {
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
}

.stock-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid #2a2a4a;
}

.stock-info-col {
  display: flex;
  flex-direction: column;
}

.stock-info-col .stock-name {
  font-size: 1.2rem;
  font-weight: 700;
  color: #fff;
}

.stock-info-col .stock-code {
  font-size: 0.9rem;
  color: #666;
  font-family: monospace;
}

.rank-badge {
  background: #4a4a8a;
  color: white;
  padding: 0.3rem 0.8rem;
  border-radius: 15px;
  font-weight: 700;
  font-size: 0.9rem;
}

.stock-details {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-row.highlight {
  background: #2a2a4a;
  padding: 0.5rem;
  border-radius: 8px;
}

.detail-row .label {
  color: #888;
  font-size: 0.9rem;
}

.detail-row .value {
  font-weight: 600;
  color: #fff;
}

/* AI 분석 섹션 */
.ai-section {
  margin-bottom: 1.5rem;
}

.ai-btn {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1.5rem;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1rem;
  font-weight: 600;
  transition: all 0.3s;
}

.ai-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 5px 20px rgba(102, 126, 234, 0.4);
}

.ai-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.ai-icon {
  font-size: 1.2rem;
}

.ai-result {
  margin-top: 1rem;
  background: linear-gradient(135deg, #1a1a3a 0%, #2a1a4a 100%);
  border: 1px solid #667eea;
  border-radius: 15px;
  overflow: hidden;
  animation: fadeIn 0.3s ease;
}

.ai-result-header {
  padding: 0.75rem 1.5rem;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.ai-badge {
  color: white;
  font-weight: 700;
  font-size: 0.9rem;
}

.ai-result-content {
  padding: 1.5rem;
  color: #ddd;
  line-height: 1.8;
  font-size: 0.95rem;
}

.ai-result-content strong {
  color: #4ade80;
}

.no-data {
  text-align: center;
  padding: 3rem;
  color: #666;
}

.no-data p {
  font-size: 1.2rem;
}

@media (max-width: 768px) {
  .screener-page {
    padding: 1rem;
  }

  .content-wrapper {
    padding: 1rem;
  }

  .page-header h1 {
    margin-top: 3rem;
    font-size: 1.5rem;
  }

  .screener-tabs {
    flex-wrap: wrap;
  }

  .tab-btn {
    padding: 0.75rem 1rem;
    font-size: 0.9rem;
  }

  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-item {
    justify-content: space-between;
  }

  .refresh-btn {
    margin-left: 0;
    margin-top: 0.5rem;
  }

  .stocks-grid {
    grid-template-columns: 1fr;
  }

  .stocks-table {
    font-size: 0.85rem;
  }

  .stocks-table th,
  .stocks-table td {
    padding: 0.5rem;
  }
}
</style>
