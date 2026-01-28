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

      <!-- 데이터 관리 탭 -->
      <div v-if="selectedTab === 'data-management'" class="tab-content">
        <div class="info-box warning">
          <strong>데이터 수집 안내</strong>
          <p>스크리너를 사용하려면 먼저 재무 데이터를 수집해야 합니다. 수집 순서: 1) 기본 재무 데이터 수집 → 2) 영업이익률 크롤링</p>
        </div>

        <!-- 수집 상태 카드 -->
        <div class="status-card">
          <div class="status-header">
            <h3>📊 수집 현황</h3>
            <button @click="fetchCollectStatus" class="refresh-btn small">새로고침</button>
          </div>
          <div v-if="collectStatus" class="status-grid">
            <div class="status-item">
              <span class="status-label">전체 데이터</span>
              <span class="status-value">{{ collectStatus.totalRecords?.toLocaleString() || 0 }}건</span>
            </div>
            <div class="status-item">
              <span class="status-label">영업이익률 있음</span>
              <span class="status-value positive">{{ collectStatus.withOperatingMargin?.toLocaleString() || 0 }}건</span>
            </div>
            <div class="status-item">
              <span class="status-label">영업이익률 없음</span>
              <span class="status-value warning-text">{{ collectStatus.missingOperatingMargin?.toLocaleString() || 0 }}건</span>
            </div>
          </div>
          <div v-else class="status-loading">
            상태 조회 중...
          </div>
        </div>

        <!-- 수집 버튼 영역 -->
        <div class="collect-actions">
          <!-- 기본 재무 데이터 수집 -->
          <div class="action-card">
            <div class="action-header">
              <span class="action-icon">📥</span>
              <h4>기본 재무 데이터 수집</h4>
            </div>
            <p class="action-desc">
              KIS API를 통해 전 종목의 PER, PBR, EPS, ROE 등 기본 재무 지표를 수집합니다.
            </p>
            <div class="action-info">
              <span class="info-tag">⏱️ 약 10-15분 소요</span>
              <span class="info-tag">📈 2,000+ 종목</span>
            </div>
            <button
              @click="collectAllFinancialData"
              class="action-btn primary"
              :disabled="isCollecting || isCrawling"
            >
              <span v-if="isCollecting" class="spinner"></span>
              {{ isCollecting ? '수집 중...' : '기본 데이터 수집 시작' }}
            </button>
          </div>

          <!-- 영업이익률 크롤링 -->
          <div class="action-card">
            <div class="action-header">
              <span class="action-icon">🕷️</span>
              <h4>영업이익률 크롤링</h4>
            </div>
            <p class="action-desc">
              네이버 금융에서 영업이익률, 순이익률 등을 크롤링합니다. 마법의 공식에 필수!
            </p>
            <div class="action-info">
              <span class="info-tag">⏱️ 약 15-20분 소요</span>
              <span class="info-tag">🌐 네이버 금융</span>
            </div>
            <div class="action-options">
              <label class="checkbox-label">
                <input type="checkbox" v-model="crawlForceUpdate">
                기존 데이터도 강제 업데이트
              </label>
            </div>
            <button
              @click="crawlOperatingMargin"
              class="action-btn secondary"
              :disabled="isCollecting || isCrawling"
            >
              <span v-if="isCrawling" class="spinner"></span>
              {{ isCrawling ? '크롤링 중...' : '영업이익률 크롤링 시작' }}
            </button>
          </div>

          <!-- 분기별 재무제표 수집 (PEG, 턴어라운드용) -->
          <div class="action-card highlight">
            <div class="action-header">
              <span class="action-icon">📊</span>
              <h4>분기별 재무제표 수집</h4>
              <span class="new-badge">NEW</span>
            </div>
            <p class="action-desc">
              네이버 금융에서 최근 4개 분기의 매출액, 영업이익, 당기순이익, EPS를 크롤링합니다.
              <strong>PEG 스크리너</strong>와 <strong>턴어라운드 스크리너</strong>에 필수!
            </p>
            <div class="action-info">
              <span class="info-tag">⏱️ 약 20-25분 소요</span>
              <span class="info-tag">📈 EPS 성장률 계산</span>
              <span class="info-tag">🔄 턴어라운드 분석</span>
            </div>
            <button
              @click="collectQuarterlyFinance"
              class="action-btn primary"
              :disabled="isCollecting || isCrawling || isCollectingQuarterly"
            >
              <span v-if="isCollectingQuarterly" class="spinner"></span>
              {{ isCollectingQuarterly ? '수집 중...' : '분기별 재무제표 수집 시작' }}
            </button>
          </div>

          <!-- 단일 종목 테스트 -->
          <div class="action-card">
            <div class="action-header">
              <span class="action-icon">🔍</span>
              <h4>단일 종목 테스트</h4>
            </div>
            <p class="action-desc">
              특정 종목의 크롤링 결과를 미리 확인합니다. (저장하지 않음)
            </p>
            <div class="test-input-group">
              <input
                v-model="testStockCode"
                placeholder="종목코드 (예: 005930)"
                class="test-input"
                @keyup.enter="previewCrawl"
              >
              <button @click="previewCrawl" class="action-btn small" :disabled="!testStockCode">
                미리보기
              </button>
            </div>
            <div v-if="crawlPreview" class="preview-result">
              <div class="preview-header">
                <span>{{ crawlPreview.stockCode }} 크롤링 결과</span>
              </div>
              <div class="preview-data" v-if="crawlPreview.data">
                <div class="preview-item" v-if="crawlPreview.data.operatingMargin">
                  <span>영업이익률:</span>
                  <span class="value">{{ crawlPreview.data.operatingMargin }}%</span>
                </div>
                <div class="preview-item" v-if="crawlPreview.data.netMargin">
                  <span>순이익률:</span>
                  <span class="value">{{ crawlPreview.data.netMargin }}%</span>
                </div>
                <div class="preview-item" v-if="crawlPreview.data.roe">
                  <span>ROE:</span>
                  <span class="value">{{ crawlPreview.data.roe }}%</span>
                </div>
                <div class="preview-item" v-if="crawlPreview.data.debtRatio">
                  <span>부채비율:</span>
                  <span class="value">{{ crawlPreview.data.debtRatio }}%</span>
                </div>
              </div>
              <div v-else class="preview-empty">
                데이터를 찾을 수 없습니다.
              </div>
            </div>
          </div>

          <!-- 종목명 수정 -->
          <div class="action-card">
            <div class="action-header">
              <span class="action-icon">🏷️</span>
              <h4>종목명 일괄 수정</h4>
            </div>
            <p class="action-desc">
              종목코드가 종목명으로 잘못 저장된 데이터를 수정합니다. (예: "005930" → "삼성전자")
            </p>
            <div class="action-info">
              <span class="info-tag">📂 StockShortData 참조</span>
              <span class="info-tag">🌐 네이버 금융 크롤링</span>
            </div>
            <button
              @click="fixStockNames"
              class="action-btn warning"
              :disabled="isCollecting || isCrawling || isFixingNames"
            >
              <span v-if="isFixingNames" class="spinner"></span>
              {{ isFixingNames ? '수정 중...' : '종목명 일괄 수정' }}
            </button>
          </div>
        </div>

        <!-- 진행 상황 -->
        <div v-if="collectProgress" class="progress-log">
          <div class="progress-header">
            <span>📋 진행 상황</span>
          </div>
          <div class="progress-content">
            {{ collectProgress }}
          </div>
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
  { value: 'turnaround', label: '턴어라운드', icon: '🔄' },
  { value: 'data-management', label: '데이터 관리', icon: '⚙️' }
];

// 데이터
const magicFormulaStocks = ref([]);
const pegStocks = ref([]);
const turnaroundStocks = ref([]);

// 데이터 수집 상태
const collectStatus = ref(null);
const isCollecting = ref(false);
const isCrawling = ref(false);
const isFixingNames = ref(false);
const isCollectingQuarterly = ref(false);
const collectProgress = ref('');

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

// 크롤링 옵션
const crawlForceUpdate = ref(false);
const testStockCode = ref('');
const crawlPreview = ref(null);

const changeTab = (tab) => {
  selectedTab.value = tab;
  if (tab === 'magic-formula' && magicFormulaStocks.value.length === 0) {
    fetchMagicFormula();
  } else if (tab === 'peg' && pegStocks.value.length === 0) {
    fetchPegStocks();
  } else if (tab === 'turnaround' && turnaroundStocks.value.length === 0) {
    fetchTurnaroundStocks();
  } else if (tab === 'data-management') {
    fetchCollectStatus();
  }
};

// ========== 데이터 수집 관련 함수 ==========

const fetchCollectStatus = async () => {
  try {
    const response = await api.get('/screener/collect-status');
    if (response.data.success) {
      collectStatus.value = response.data;
    }
  } catch (error) {
    console.error('수집 상태 조회 오류:', error);
  }
};

const collectAllFinancialData = async () => {
  if (isCollecting.value) return;

  if (!confirm('전 종목 재무 데이터 수집을 시작하시겠습니까?\n약 10-15분 소요됩니다.')) {
    return;
  }

  isCollecting.value = true;
  collectProgress.value = '재무 데이터 수집 시작...';

  try {
    const response = await api.post('/screener/collect-all');
    if (response.data.success) {
      const data = response.data.data;
      collectProgress.value = `수집 완료! 총 ${data.total}개 종목 중 성공: ${data.successCount}, 실패: ${data.failCount} (소요시간: ${data.elapsedSeconds}초)`;
      await fetchCollectStatus();
    } else {
      collectProgress.value = '수집 실패: ' + response.data.message;
    }
  } catch (error) {
    console.error('재무 데이터 수집 오류:', error);
    collectProgress.value = '수집 중 오류 발생: ' + (error.response?.data?.message || error.message);
  } finally {
    isCollecting.value = false;
  }
};

const crawlOperatingMargin = async () => {
  if (isCrawling.value) return;

  if (!confirm('영업이익률 크롤링을 시작하시겠습니까?\n약 15-20분 소요됩니다.')) {
    return;
  }

  isCrawling.value = true;
  collectProgress.value = '영업이익률 크롤링 시작...';

  try {
    const response = await api.post('/screener/crawl-operating-margin', null, {
      params: { forceUpdate: crawlForceUpdate.value }
    });
    if (response.data.success) {
      const data = response.data.data;
      collectProgress.value = `크롤링 완료! 성공: ${data.successCount}, 실패: ${data.failCount}, 스킵: ${data.skipCount} (소요시간: ${data.elapsedSeconds}초)`;
      await fetchCollectStatus();
    } else {
      collectProgress.value = '크롤링 실패: ' + response.data.message;
    }
  } catch (error) {
    console.error('영업이익률 크롤링 오류:', error);
    collectProgress.value = '크롤링 중 오류 발생: ' + (error.response?.data?.message || error.message);
  } finally {
    isCrawling.value = false;
  }
};

// 분기별 재무제표 수집 (PEG, 턴어라운드용)
const collectQuarterlyFinance = async () => {
  if (isCollectingQuarterly.value) return;

  if (!confirm('분기별 재무제표 수집을 시작하시겠습니까?\n\n' +
    '• 네이버 금융에서 최근 4개 분기 데이터를 크롤링합니다.\n' +
    '• EPS 성장률이 계산되어 PEG 스크리너가 작동합니다.\n' +
    '• 과거 분기 데이터로 턴어라운드 분석이 가능해집니다.\n\n' +
    '약 20-25분 소요됩니다.')) {
    return;
  }

  isCollectingQuarterly.value = true;
  collectProgress.value = '분기별 재무제표 수집 시작...';

  try {
    const response = await api.post('/screener/collect/finance');
    if (response.data.success) {
      const data = response.data.data;
      collectProgress.value = `분기별 재무제표 수집 완료! 성공: ${data.successCount}, 실패: ${data.failCount} (소요시간: ${data.elapsedSeconds}초)`;
      await fetchCollectStatus();
    } else {
      collectProgress.value = '분기별 재무제표 수집 실패: ' + response.data.message;
    }
  } catch (error) {
    console.error('분기별 재무제표 수집 오류:', error);
    collectProgress.value = '분기별 재무제표 수집 중 오류 발생: ' + (error.response?.data?.message || error.message);
  } finally {
    isCollectingQuarterly.value = false;
  }
};

const previewCrawl = async () => {
  if (!testStockCode.value) return;

  crawlPreview.value = null;

  try {
    const response = await api.get(`/screener/crawl-preview/${testStockCode.value}`);
    crawlPreview.value = response.data;
  } catch (error) {
    console.error('크롤링 미리보기 오류:', error);
    crawlPreview.value = { success: false, message: '오류 발생' };
  }
};

const fixStockNames = async () => {
  if (isFixingNames.value) return;

  if (!confirm('종목명 일괄 수정을 시작하시겠습니까?\n종목코드가 종목명으로 저장된 데이터를 수정합니다.')) {
    return;
  }

  isFixingNames.value = true;
  collectProgress.value = '종목명 수정 시작...';

  try {
    const response = await api.post('/screener/fix-stock-names');
    if (response.data.success) {
      const data = response.data.data;
      collectProgress.value = `종목명 수정 완료! 총 ${data.total}개 중 수정: ${data.fixedCount}, 실패: ${data.failCount}, 스킵: ${data.skipCount} (소요시간: ${data.elapsedSeconds}초)`;
      await fetchCollectStatus();
    } else {
      collectProgress.value = '종목명 수정 실패: ' + response.data.message;
    }
  } catch (error) {
    console.error('종목명 수정 오류:', error);
    collectProgress.value = '종목명 수정 중 오류 발생: ' + (error.response?.data?.message || error.message);
  } finally {
    isFixingNames.value = false;
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
/* ========== 라이트 모드 (기본) ========== */
.screener-page {
  min-height: 100vh;
  background: var(--bg-gradient);
  padding: 2rem;
}

.content-wrapper {
  max-width: 1400px;
  margin: 0 auto;
  background: var(--card-bg);
  border-radius: 20px;
  padding: 2rem;
  box-shadow: var(--card-shadow);
  border: 1px solid var(--border-color);
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
  background: var(--primary-gradient);
  color: white;
  border: none;
  padding: 0.75rem 1.5rem;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1rem;
  transition: all 0.3s;
}

.back-button:hover {
  transform: translateX(-5px);
  opacity: 0.9;
}

.page-header h1 {
  color: var(--text-primary);
  margin-bottom: 0.5rem;
}

.subtitle {
  color: var(--text-muted);
  font-size: 1.1rem;
}

.screener-tabs {
  display: flex;
  justify-content: center;
  gap: 1rem;
  margin-bottom: 2rem;
  border-bottom: 2px solid var(--border-color);
}

.tab-btn {
  padding: 1rem 2rem;
  background: none;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 1.1rem;
  font-weight: 600;
  transition: all 0.3s;
  border-bottom: 3px solid transparent;
  margin-bottom: -2px;
}

.tab-btn.active {
  color: var(--success);
  border-bottom-color: var(--success);
}

.tab-btn:hover:not(.active) {
  color: var(--text-secondary);
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
  background: var(--border-light);
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
  color: var(--text-secondary);
  white-space: nowrap;
}

.filter-item select {
  padding: 0.5rem 1rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 1rem;
  cursor: pointer;
  background: var(--card-bg);
  color: var(--text-primary);
}

.refresh-btn {
  padding: 0.5rem 1rem;
  background: var(--primary-gradient);
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
  opacity: 0.9;
}

.info-box {
  background: var(--border-light);
  border-left: 4px solid var(--success);
  padding: 1rem 1.5rem;
  margin-bottom: 1.5rem;
  border-radius: 0 10px 10px 0;
}

.info-box strong {
  color: var(--success);
  display: block;
  margin-bottom: 0.5rem;
}

.info-box p {
  color: var(--text-secondary);
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
  border-bottom: 1px solid var(--border-color);
  color: var(--text-primary);
}

.stocks-table th {
  background: var(--border-light);
  color: var(--text-secondary);
  font-weight: 600;
  white-space: nowrap;
}

.stocks-table tbody tr:hover {
  background: var(--border-light);
}

.stocks-table .rank {
  font-weight: 700;
  color: var(--success);
  text-align: center;
}

.stocks-table .stock-info {
  display: flex;
  flex-direction: column;
}

.stocks-table .stock-name {
  font-weight: 600;
  color: var(--text-primary);
}

.stocks-table .stock-code {
  font-size: 0.85rem;
  color: var(--text-muted);
  font-family: monospace;
}

.stocks-table .score {
  font-weight: 700;
  color: var(--text-primary);
  text-align: center;
}

.positive {
  color: var(--success) !important;
}

.very-positive {
  color: #22c55e !important;
  font-weight: 700;
}

.negative {
  color: var(--danger) !important;
}

/* 턴어라운드 카드 그리드 */
.stocks-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 1.5rem;
}

.stock-card {
  background: var(--card-bg);
  border-radius: 15px;
  padding: 1.5rem;
  border: 2px solid var(--border-color);
  transition: all 0.3s;
  position: relative;
}

.stock-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.15);
}

.stock-card.loss-to-profit {
  border-color: var(--success);
  background: linear-gradient(135deg, var(--card-bg) 0%, rgba(74, 222, 128, 0.1) 100%);
}

.stock-card.profit-growth {
  border-color: var(--info);
  background: linear-gradient(135deg, var(--card-bg) 0%, rgba(59, 130, 246, 0.1) 100%);
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
  border-bottom: 1px solid var(--border-color);
}

.stock-info-col {
  display: flex;
  flex-direction: column;
}

.stock-info-col .stock-name {
  font-size: 1.2rem;
  font-weight: 700;
  color: var(--text-primary);
}

.stock-info-col .stock-code {
  font-size: 0.9rem;
  color: var(--text-muted);
  font-family: monospace;
}

.rank-badge {
  background: var(--primary-gradient);
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
  background: var(--border-light);
  padding: 0.5rem;
  border-radius: 8px;
}

.detail-row .label {
  color: var(--text-muted);
  font-size: 0.9rem;
}

.detail-row .value {
  font-weight: 600;
  color: var(--text-primary);
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
  background: var(--primary-gradient);
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
  background: var(--card-bg);
  border: 1px solid var(--primary-start);
  border-radius: 15px;
  overflow: hidden;
  animation: fadeIn 0.3s ease;
}

.ai-result-header {
  padding: 0.75rem 1.5rem;
  background: var(--primary-gradient);
}

.ai-badge {
  color: white;
  font-weight: 700;
  font-size: 0.9rem;
}

.ai-result-content {
  padding: 1.5rem;
  color: var(--text-secondary);
  line-height: 1.8;
  font-size: 0.95rem;
}

.ai-result-content strong {
  color: var(--success);
}

.no-data {
  text-align: center;
  padding: 3rem;
  color: var(--text-muted);
}

.no-data p {
  font-size: 1.2rem;
}

/* 데이터 관리 탭 스타일 */
.info-box.warning {
  border-left-color: var(--warning);
}

.info-box.warning strong {
  color: var(--warning);
}

.status-card {
  background: var(--card-bg);
  border-radius: 15px;
  padding: 1.5rem;
  margin-bottom: 2rem;
  border: 1px solid var(--border-color);
}

.status-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.status-header h3 {
  color: var(--text-primary);
  margin: 0;
  font-size: 1.1rem;
}

.refresh-btn.small {
  padding: 0.4rem 0.8rem;
  font-size: 0.85rem;
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1rem;
}

.status-item {
  background: var(--border-light);
  padding: 1rem;
  border-radius: 10px;
  text-align: center;
}

.status-label {
  display: block;
  color: var(--text-muted);
  font-size: 0.9rem;
  margin-bottom: 0.5rem;
}

.status-value {
  display: block;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-primary);
}

.status-value.positive {
  color: var(--success);
}

.status-value.warning-text {
  color: var(--warning);
}

.status-loading {
  text-align: center;
  color: var(--text-muted);
  padding: 1rem;
}

.collect-actions {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 1.5rem;
  margin-bottom: 2rem;
}

.action-card {
  background: var(--card-bg);
  border-radius: 15px;
  padding: 1.5rem;
  border: 1px solid var(--border-color);
  transition: all 0.3s;
}

.action-card:hover {
  border-color: var(--primary-start);
}

.action-card.highlight {
  background: linear-gradient(135deg, rgba(74, 144, 226, 0.08), rgba(80, 227, 194, 0.08));
  border-color: var(--primary-start);
}

.new-badge {
  background: linear-gradient(135deg, #ff6b6b, #ee5a24);
  color: white;
  font-size: 0.7rem;
  font-weight: bold;
  padding: 0.2rem 0.5rem;
  border-radius: 10px;
  margin-left: auto;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.action-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1rem;
}

.action-icon {
  font-size: 1.5rem;
}

.action-header h4 {
  color: var(--text-primary);
  margin: 0;
  font-size: 1.1rem;
}

.action-desc {
  color: var(--text-secondary);
  font-size: 0.9rem;
  line-height: 1.5;
  margin-bottom: 1rem;
}

.action-info {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
  margin-bottom: 1rem;
}

.info-tag {
  background: var(--border-light);
  padding: 0.4rem 0.8rem;
  border-radius: 20px;
  font-size: 0.8rem;
  color: var(--text-muted);
}

.action-options {
  margin-bottom: 1rem;
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 0.9rem;
}

.checkbox-label input[type="checkbox"] {
  width: 18px;
  height: 18px;
  cursor: pointer;
}

.action-btn {
  width: 100%;
  padding: 0.75rem 1.5rem;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1rem;
  font-weight: 600;
  transition: all 0.3s;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
}

.action-btn.primary {
  background: linear-gradient(135deg, #4ade80 0%, #22c55e 100%);
  color: #000;
}

.action-btn.primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 5px 20px rgba(74, 222, 128, 0.3);
}

.action-btn.secondary {
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  color: #fff;
}

.action-btn.secondary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 5px 20px rgba(59, 130, 246, 0.3);
}

.action-btn.warning {
  background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%);
  color: #000;
}

.action-btn.warning:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 5px 20px rgba(245, 158, 11, 0.3);
}

.action-btn.small {
  width: auto;
  padding: 0.5rem 1rem;
  font-size: 0.9rem;
  background: var(--primary-gradient);
  color: #fff;
}

.action-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none !important;
}

.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid transparent;
  border-top-color: currentColor;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.test-input-group {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1rem;
}

.test-input {
  flex: 1;
  padding: 0.5rem 1rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--card-bg);
  color: var(--text-primary);
  font-size: 1rem;
}

.test-input::placeholder {
  color: var(--text-muted);
}

.preview-result {
  background: var(--border-light);
  border-radius: 10px;
  overflow: hidden;
  margin-top: 1rem;
}

.preview-header {
  background: var(--primary-gradient);
  padding: 0.75rem 1rem;
  color: #fff;
  font-weight: 600;
  font-size: 0.9rem;
}

.preview-data {
  padding: 1rem;
}

.preview-item {
  display: flex;
  justify-content: space-between;
  padding: 0.5rem 0;
  border-bottom: 1px solid var(--border-color);
  color: var(--text-secondary);
}

.preview-item:last-child {
  border-bottom: none;
}

.preview-item .value {
  color: var(--success);
  font-weight: 600;
}

.preview-empty {
  padding: 1rem;
  text-align: center;
  color: var(--text-muted);
}

.progress-log {
  background: var(--card-bg);
  border-radius: 15px;
  overflow: hidden;
  border: 1px solid var(--border-color);
}

.progress-header {
  background: var(--border-light);
  padding: 0.75rem 1rem;
  color: var(--text-primary);
  font-weight: 600;
}

.progress-content {
  padding: 1rem;
  color: var(--success);
  font-family: monospace;
  white-space: pre-wrap;
  line-height: 1.6;
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

  .status-grid {
    grid-template-columns: 1fr;
  }

  .collect-actions {
    grid-template-columns: 1fr;
  }

  .test-input-group {
    flex-direction: column;
  }

  .action-btn.small {
    width: 100%;
  }
}
</style>
