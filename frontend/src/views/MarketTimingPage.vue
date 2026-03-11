<template>
  <div class="market-timing-page">
    <div class="page-header-unified">
      <BackButton :dark="true" />
      <div class="header-title">
        <h1>시장 지표 (Market Timing)</h1>
        <p class="subtitle">ADR(등락비율) 기반 시장 상태 분석</p>
      </div>
    </div>

    <!-- 시장 상태 카드 -->
    <div class="status-overview">
      <div class="status-card main-status" :class="getConditionClass(marketData?.overallCondition)">
        <div class="status-icon">
          {{ getConditionEmoji(marketData?.overallCondition) }}
        </div>
        <div class="status-content">
          <div class="status-label">종합 시장 상태</div>
          <div class="status-value" v-if="marketData?.overallCondition">
            {{ marketData.overallCondition.emoji }}
          </div>
          <div class="status-value data-needed" v-else>
            데이터 수집 필요
          </div>
          <div class="adr-value" v-if="marketData?.combinedAdr">
            ADR(20일): <strong>{{ formatNumber(marketData.combinedAdr, 1) }}</strong>
          </div>
          <div class="adr-value data-needed-hint" v-else>
            (최소 20일 데이터 필요)
          </div>
        </div>
        <div class="status-date" v-if="marketData?.analysisDate">
          {{ formatDate(marketData.analysisDate) }} 기준
        </div>
      </div>

      <!-- 데이터 부족 알림 -->
      <div class="data-needed-alert" v-if="!marketData?.combinedAdr">
        <div class="alert-content">
          <span class="alert-icon">📊</span>
          <div class="alert-text">
            <strong>ADR 계산을 위한 데이터가 부족합니다</strong>
            <p>정확한 시장 분석을 위해 최소 20일간의 데이터가 필요합니다.</p>
          </div>
          <button @click="scrollToBackfill" class="btn-collect-now">
            📅 기간 수집하기
          </button>
        </div>
      </div>
    </div>

    <!-- ADR 히스토리 차트 -->
    <div class="adr-chart-section">
      <div class="section-header">
        <h3>ADR 추이 (60일)</h3>
        <!-- Interactive Legend -->
        <div class="chart-legend interactive">
          <button
            class="legend-item kospi"
            :class="{ inactive: !visibleDatasets.kospi }"
            @click="toggleDataset('kospi')"
          >
            <span class="legend-dot"></span>코스피
          </button>
          <button
            class="legend-item kosdaq"
            :class="{ inactive: !visibleDatasets.kosdaq }"
            @click="toggleDataset('kosdaq')"
          >
            <span class="legend-dot"></span>코스닥
          </button>
          <button
            class="legend-item combined"
            :class="{ inactive: !visibleDatasets.combined }"
            @click="toggleDataset('combined')"
          >
            <span class="legend-dot"></span>종합
          </button>
        </div>
      </div>
      <div class="chart-container" v-if="adrHistory.length >= 5">
        <Line :data="adrChartData" :options="adrChartOptions" :key="chartKey" />
      </div>
      <div v-else class="no-chart-data">
        <div class="no-data-content">
          <span class="no-data-icon">📈</span>
          <h4>데이터 수집 중입니다</h4>
          <p v-if="adrHistory.length > 0">
            현재 {{ adrHistory.length }}일 데이터 보유 (최소 5일 필요)
          </p>
          <p v-else>
            내일부터 차트가 그려집니다
          </p>
          <button @click="scrollToBackfill" class="btn-go-collect">
            📅 기간 수집으로 이동
          </button>
        </div>
      </div>
    </div>

    <!-- ADR 설명 -->
    <div class="adr-guide">
      <h3>ADR (Advance-Decline Ratio) 이란?</h3>
      <p>최근 20일간 상승 종목 수 합계 / 하락 종목 수 합계 × 100</p>
      <div class="adr-levels">
        <div class="level overheated">
          <span class="level-emoji">🔥</span>
          <span class="level-range">ADR ≥ 120</span>
          <span class="level-label">과열 - 현금 확보 필요</span>
        </div>
        <div class="level normal">
          <span class="level-emoji">☁️</span>
          <span class="level-range">80 < ADR < 120</span>
          <span class="level-label">보통 - 정상 범위</span>
        </div>
        <div class="level oversold">
          <span class="level-emoji">💧</span>
          <span class="level-range">ADR ≤ 80</span>
          <span class="level-label">침체 - 저점 매수 기회</span>
        </div>
        <div class="level extreme-fear">
          <span class="level-emoji">🥶</span>
          <span class="level-range">ADR ≤ 60</span>
          <span class="level-label">극심한 공포 - 적극 매수 검토</span>
        </div>
      </div>
    </div>

    <!-- 시장별 상세 현황 (간소화) -->
    <div class="market-details" v-if="marketData">
      <div class="market-card" v-if="marketData.kospi">
        <div class="market-header">
          <h3>KOSPI</h3>
          <span class="market-condition" :class="getConditionClass(marketData.kospi.condition)">
            {{ marketData.kospi.condition?.emoji || '-' }}
          </span>
        </div>
        <div class="market-stats">
          <!-- 등락 현황 (Stacked Bar) -->
          <div class="ratio-bar-section">
            <div class="ratio-bar-label">
              <span class="rising-count" :class="{ 'no-data': !hasValidMarketData(marketData.kospi) }">
                상승 {{ hasValidMarketData(marketData.kospi) ? marketData.kospi.advancingCount : '-' }}
              </span>
              <span class="unchanged-count" :class="{ 'no-data': !hasValidMarketData(marketData.kospi) }">
                보합 {{ hasValidMarketData(marketData.kospi) ? marketData.kospi.unchangedCount : '-' }}
              </span>
              <span class="falling-count" :class="{ 'no-data': !hasValidMarketData(marketData.kospi) }">
                하락 {{ hasValidMarketData(marketData.kospi) ? marketData.kospi.decliningCount : '-' }}
              </span>
            </div>
            <!-- Stacked Bar: 항상 렌더링하고 width로 애니메이션 -->
            <div class="stacked-bar">
              <div
                class="bar-segment rising"
                :style="{ width: (hasValidMarketData(marketData.kospi) ? getAdvanceRatio(marketData.kospi) : 0) + '%' }"
              ></div>
              <div
                class="bar-segment unchanged"
                :style="{ width: (hasValidMarketData(marketData.kospi) ? getUnchangedRatio(marketData.kospi) : 0) + '%' }"
              ></div>
              <div
                class="bar-segment falling"
                :style="{ width: (hasValidMarketData(marketData.kospi) ? getDeclineRatio(marketData.kospi) : 0) + '%' }"
              ></div>
              <!-- 데이터 없을 때 메시지 (바 위에 오버레이) -->
              <div v-if="!hasValidMarketData(marketData.kospi)" class="empty-bar-overlay">집계 중</div>
            </div>
          </div>

          <!-- 당일 등락비 (Stacked Bar) -->
          <div class="stat-row highlight">
            <span class="stat-label">당일 등락비</span>
            <span class="stat-value">{{ formatNumber(marketData.kospi.dailyRatio, 1) }}</span>
          </div>
          <div class="ratio-progress-bar">
            <div class="progress-track">
              <div
                class="progress-fill rising-fill"
                :style="{ width: getDailyRatioPercent(marketData.kospi.dailyRatio) + '%' }"
              ></div>
            </div>
            <div class="progress-labels">
              <span>0</span>
              <span>100</span>
              <span>200</span>
            </div>
          </div>

          <!-- ADR(20일) -->
          <div class="stat-row highlight adr-row">
            <span class="stat-label">ADR(20일)</span>
            <span class="stat-value" :class="getAdrClass(marketData.kospi.adr20)">
              {{ formatNumber(marketData.kospi.adr20, 1) }}
            </span>
          </div>
          <div class="adr-progress-bar">
            <div class="progress-track">
              <div
                class="progress-fill"
                :class="getAdrClass(marketData.kospi.adr20)"
                :style="{ width: getAdrPercent(marketData.kospi.adr20) + '%' }"
              ></div>
            </div>
            <div class="progress-markers">
              <span class="marker" style="left: 30%">60</span>
              <span class="marker" style="left: 40%">80</span>
              <span class="marker" style="left: 60%">120</span>
            </div>
          </div>
        </div>
      </div>

      <div class="market-card" v-if="marketData.kosdaq">
        <div class="market-header">
          <h3>KOSDAQ</h3>
          <span class="market-condition" :class="getConditionClass(marketData.kosdaq.condition)">
            {{ marketData.kosdaq.condition?.emoji || '-' }}
          </span>
        </div>
        <div class="market-stats">
          <!-- 등락 현황 (Stacked Bar) -->
          <div class="ratio-bar-section">
            <div class="ratio-bar-label">
              <span class="rising-count" :class="{ 'no-data': !hasValidMarketData(marketData.kosdaq) }">
                상승 {{ hasValidMarketData(marketData.kosdaq) ? marketData.kosdaq.advancingCount : '-' }}
              </span>
              <span class="unchanged-count" :class="{ 'no-data': !hasValidMarketData(marketData.kosdaq) }">
                보합 {{ hasValidMarketData(marketData.kosdaq) ? marketData.kosdaq.unchangedCount : '-' }}
              </span>
              <span class="falling-count" :class="{ 'no-data': !hasValidMarketData(marketData.kosdaq) }">
                하락 {{ hasValidMarketData(marketData.kosdaq) ? marketData.kosdaq.decliningCount : '-' }}
              </span>
            </div>
            <!-- Stacked Bar: 항상 렌더링하고 width로 애니메이션 -->
            <div class="stacked-bar">
              <div
                class="bar-segment rising"
                :style="{ width: (hasValidMarketData(marketData.kosdaq) ? getAdvanceRatio(marketData.kosdaq) : 0) + '%' }"
              ></div>
              <div
                class="bar-segment unchanged"
                :style="{ width: (hasValidMarketData(marketData.kosdaq) ? getUnchangedRatio(marketData.kosdaq) : 0) + '%' }"
              ></div>
              <div
                class="bar-segment falling"
                :style="{ width: (hasValidMarketData(marketData.kosdaq) ? getDeclineRatio(marketData.kosdaq) : 0) + '%' }"
              ></div>
              <!-- 데이터 없을 때 메시지 (바 위에 오버레이) -->
              <div v-if="!hasValidMarketData(marketData.kosdaq)" class="empty-bar-overlay">집계 중</div>
            </div>
          </div>

          <!-- 당일 등락비 -->
          <div class="stat-row highlight">
            <span class="stat-label">당일 등락비</span>
            <span class="stat-value">{{ formatNumber(marketData.kosdaq.dailyRatio, 1) }}</span>
          </div>
          <div class="ratio-progress-bar">
            <div class="progress-track">
              <div
                class="progress-fill rising-fill"
                :style="{ width: getDailyRatioPercent(marketData.kosdaq.dailyRatio) + '%' }"
              ></div>
            </div>
            <div class="progress-labels">
              <span>0</span>
              <span>100</span>
              <span>200</span>
            </div>
          </div>

          <!-- ADR(20일) -->
          <div class="stat-row highlight adr-row">
            <span class="stat-label">ADR(20일)</span>
            <span class="stat-value" :class="getAdrClass(marketData.kosdaq.adr20)">
              {{ formatNumber(marketData.kosdaq.adr20, 1) }}
            </span>
          </div>
          <div class="adr-progress-bar">
            <div class="progress-track">
              <div
                class="progress-fill"
                :class="getAdrClass(marketData.kosdaq.adr20)"
                :style="{ width: getAdrPercent(marketData.kosdaq.adr20) + '%' }"
              ></div>
            </div>
            <div class="progress-markers">
              <span class="marker" style="left: 30%">60</span>
              <span class="marker" style="left: 40%">80</span>
              <span class="marker" style="left: 60%">120</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 진단 및 전략 -->
    <div class="diagnosis-section" v-if="marketData">
      <div class="diagnosis-card">
        <h3>📊 시장 진단</h3>
        <p>{{ marketData.diagnosis || '데이터를 수집해주세요.' }}</p>
      </div>
      <div class="strategy-card">
        <h3>💡 투자 전략 제안</h3>
        <p>{{ marketData.strategy || '데이터 수집 후 분석을 이용해주세요.' }}</p>
      </div>
    </div>

    <!-- 데이터 관리 -->
    <div class="data-management" ref="backfillSection">
      <h3>데이터 관리</h3>
      <div class="management-actions">
        <button @click="collectData" :disabled="isCollecting" class="btn-collect">
          <span v-if="isCollecting" class="btn-loading">
            <span class="spinner-small"></span>
            수집 중...
          </span>
          <span v-else>📥 시장 데이터 수집</span>
        </button>
        <button @click="fetchData" :disabled="loading" class="btn-refresh">
          <span v-if="loading" class="btn-loading">
            <span class="spinner-small"></span>
            로딩...
          </span>
          <span v-else>🔄 새로고침</span>
        </button>
      </div>
      <p class="management-note">
        * 매일 장 마감 후(15:30 이후) 데이터를 수집하면 당일 시장 현황이 반영됩니다.
      </p>

      <!-- 기간별 수집 (Backfill) -->
      <div class="backfill-section">
        <h4>기간별 데이터 수집 (Backfill)</h4>
        <div class="backfill-form">
          <div class="date-inputs">
            <div class="input-group">
              <label>시작일</label>
              <input type="date" v-model="backfillStartDate" :max="backfillEndDate || today" />
            </div>
            <div class="input-group">
              <label>종료일</label>
              <input type="date" v-model="backfillEndDate" :min="backfillStartDate" :max="today" />
            </div>
          </div>
          <button
            @click="collectBackfillData"
            :disabled="isBackfilling || !backfillStartDate || !backfillEndDate"
            class="btn-backfill"
          >
            <span v-if="isBackfilling" class="btn-loading">
              <span class="spinner-small"></span>
              수집 중...
            </span>
            <span v-else>📅 기간 수집</span>
          </button>
        </div>
        <div v-if="backfillResult" class="backfill-result">
          <p>
            수집 완료: 성공 {{ backfillResult.successCount }}일,
            실패 {{ backfillResult.failCount }}일,
            스킵 {{ backfillResult.skipCount }}일
          </p>
        </div>
        <p class="management-note">
          * 과거 데이터 수집 시 네이버 금융 차단 방지를 위해 요청 간 1초 딜레이가 적용됩니다.
        </p>
      </div>
    </div>

    <!-- 로딩 -->
    <div v-if="loading && !isCollecting" class="loading-overlay">
      <div class="spinner"></div>
      <p>데이터 로딩 중...</p>
    </div>

    <!-- 기간 수집 로딩 -->
    <div v-if="isBackfilling" class="loading-overlay backfill-loading">
      <div class="spinner"></div>
      <div class="backfill-progress">
        <p class="progress-title">📅 기간별 데이터 수집 중...</p>
        <p class="progress-detail">{{ backfillStartDate }} ~ {{ backfillEndDate }}</p>
        <p class="progress-hint">네이버 금융에서 데이터를 가져오는 중입니다.<br>차단 방지를 위해 요청당 1초 딜레이가 적용됩니다.</p>
        <p class="progress-warning">창을 닫지 마세요. (최대 2분 소요)</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { marketAPI } from '../utils/api';
import BackButton from '../components/BackButton.vue';
import { Line } from 'vue-chartjs';
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Title,
  Tooltip,
  Legend,
  Filler
} from 'chart.js';
import annotationPlugin from 'chartjs-plugin-annotation';

// Chart.js 등록
ChartJS.register(
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Title,
  Tooltip,
  Legend,
  Filler,
  annotationPlugin
);

const router = useRouter();
const loading = ref(false);
const isCollecting = ref(false);
const marketData = ref(null);
const adrHistory = ref([]);
const backfillSection = ref(null);

// Chart Legend Toggle 상태
const visibleDatasets = reactive({
  kospi: true,
  kosdaq: true,
  combined: true
});
const chartKey = ref(0);

// Backfill 관련 상태
const isBackfilling = ref(false);
const backfillStartDate = ref('');
const backfillEndDate = ref('');
const backfillResult = ref(null);
const today = new Date().toISOString().split('T')[0];

// 데이터셋 토글
const toggleDataset = (dataset) => {
  visibleDatasets[dataset] = !visibleDatasets[dataset];
  chartKey.value++; // 차트 리렌더링
};

// 기간 수집 섹션으로 스크롤
const scrollToBackfill = () => {
  backfillSection.value?.scrollIntoView({ behavior: 'smooth' });
};

// 장 시작 전 체크 (09:00 이전)
const isBeforeMarketOpen = () => {
  const now = new Date();
  const hours = now.getHours();
  return hours < 9;
};

// 종목 수 표시 (0이면 '-' 또는 '집계 중')
const formatCount = (count, type) => {
  if (count === null || count === undefined) {
    return '-';
  }
  if (count === 0) {
    // 장 시작 전이면 '전일' 표시, 아니면 '집계 중'
    if (isBeforeMarketOpen()) {
      return '-';
    }
    return '-';
  }
  return count;
};

// 데이터가 유효한지 확인 (모두 0이면 무효)
const hasValidMarketData = (market) => {
  if (!market) return false;
  const total = (market.advancingCount || 0) + (market.decliningCount || 0) + (market.unchangedCount || 0);
  return total > 0;
};

// 등락비 계산 헬퍼 함수
const getAdvanceRatio = (market) => {
  const total = (market?.advancingCount || 0) + (market?.decliningCount || 0) + (market?.unchangedCount || 0);
  if (total === 0) return 0;
  return ((market?.advancingCount || 0) / total) * 100;
};

const getDeclineRatio = (market) => {
  const total = (market?.advancingCount || 0) + (market?.decliningCount || 0) + (market?.unchangedCount || 0);
  if (total === 0) return 0;
  return ((market?.decliningCount || 0) / total) * 100;
};

const getUnchangedRatio = (market) => {
  const total = (market?.advancingCount || 0) + (market?.decliningCount || 0) + (market?.unchangedCount || 0);
  if (total === 0) return 0;
  return ((market?.unchangedCount || 0) / total) * 100;
};

// 당일 등락비 퍼센트 (0~200 범위를 0~100%로)
const getDailyRatioPercent = (ratio) => {
  if (ratio === null || ratio === undefined) return 0;
  return Math.min(Math.max((ratio / 200) * 100, 0), 100);
};

// ADR 퍼센트 (40~160 범위를 0~100%로)
const getAdrPercent = (adr) => {
  if (adr === null || adr === undefined) return 0;
  return Math.min(Math.max(((adr - 40) / 120) * 100, 0), 100);
};

// ADR 차트 데이터
const adrChartData = computed(() => {
  if (adrHistory.value.length === 0) return { labels: [], datasets: [] };

  // 날짜순 정렬 (오래된 날짜가 먼저)
  const sortedHistory = [...adrHistory.value].reverse();

  const datasets = [];

  if (visibleDatasets.kospi) {
    datasets.push({
      label: '코스피 ADR',
      data: sortedHistory.map(d => d.kospiAdr),
      borderColor: '#ef4444',
      backgroundColor: 'rgba(239, 68, 68, 0.1)',
      borderWidth: 2,
      tension: 0.3,
      pointRadius: 2,
      pointHoverRadius: 5
    });
  }

  if (visibleDatasets.kosdaq) {
    datasets.push({
      label: '코스닥 ADR',
      data: sortedHistory.map(d => d.kosdaqAdr),
      borderColor: '#3b82f6',
      backgroundColor: 'rgba(59, 130, 246, 0.1)',
      borderWidth: 2,
      tension: 0.3,
      pointRadius: 2,
      pointHoverRadius: 5
    });
  }

  if (visibleDatasets.combined) {
    datasets.push({
      label: '종합 ADR',
      data: sortedHistory.map(d => d.combinedAdr),
      borderColor: '#a855f7',
      backgroundColor: 'rgba(168, 85, 247, 0.2)',
      borderWidth: 3,
      tension: 0.3,
      pointRadius: 3,
      pointHoverRadius: 6,
      fill: true
    });
  }

  return {
    labels: sortedHistory.map(d => {
      const date = new Date(d.date);
      return `${date.getMonth() + 1}/${date.getDate()}`;
    }),
    datasets
  };
});

// ADR 차트 옵션
const adrChartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  interaction: {
    mode: 'index',
    intersect: false
  },
  plugins: {
    legend: {
      display: false
    },
    tooltip: {
      backgroundColor: 'rgba(0, 0, 0, 0.8)',
      titleColor: '#fff',
      bodyColor: '#fff',
      callbacks: {
        label: (context) => `${context.dataset.label}: ${context.raw?.toFixed(1) || '-'}`
      }
    },
    annotation: {
      annotations: {
        overheatLine: {
          type: 'line',
          yMin: 120,
          yMax: 120,
          borderColor: '#ef4444',
          borderWidth: 2,
          borderDash: [6, 6],
          label: {
            display: true,
            content: '과열선 (120)',
            position: 'end',
            backgroundColor: 'rgba(239, 68, 68, 0.8)',
            color: '#fff',
            font: { size: 11 }
          }
        },
        oversoldLine: {
          type: 'line',
          yMin: 80,
          yMax: 80,
          borderColor: '#3b82f6',
          borderWidth: 2,
          borderDash: [6, 6],
          label: {
            display: true,
            content: '침체선 (80)',
            position: 'end',
            backgroundColor: 'rgba(59, 130, 246, 0.8)',
            color: '#fff',
            font: { size: 11 }
          }
        }
      }
    }
  },
  scales: {
    x: {
      grid: {
        color: 'rgba(255, 255, 255, 0.1)'
      },
      ticks: {
        color: '#a1a1aa',
        maxRotation: 45,
        minRotation: 0,
        autoSkip: true,
        maxTicksLimit: 8
      }
    },
    y: {
      grid: {
        color: 'rgba(255, 255, 255, 0.1)'
      },
      ticks: {
        color: '#a1a1aa'
      },
      min: 40,
      max: 160,
      suggestedMin: 50,
      suggestedMax: 150
    }
  }
};

// ADR 히스토리 조회
const fetchAdrHistory = async () => {
  try {
    const response = await marketAPI.getAdrHistory(60);
    if (response.data.success) {
      adrHistory.value = response.data.data || [];
    }
  } catch (error) {
    console.error('ADR 히스토리 조회 실패:', error);
  }
};

// 기간별 데이터 수집 (Backfill)
const collectBackfillData = async () => {
  if (!backfillStartDate.value || !backfillEndDate.value) return;

  isBackfilling.value = true;
  backfillResult.value = null;

  try {
    const response = await marketAPI.collectDataForPeriod(
      backfillStartDate.value,
      backfillEndDate.value
    );

    if (response.data.success) {
      backfillResult.value = response.data.data;
      // 수집 후 히스토리 새로고침
      await fetchAdrHistory();
      alert('기간별 데이터 수집이 완료되었습니다.');
    } else {
      alert('수집 실패: ' + response.data.message);
    }
  } catch (error) {
    console.error('기간별 데이터 수집 실패:', error);
    alert('기간별 데이터 수집에 실패했습니다.');
  } finally {
    isBackfilling.value = false;
  }
};

const fetchData = async () => {
  loading.value = true;
  try {
    const response = await marketAPI.getStatus();
    if (response.data.success) {
      marketData.value = response.data.data;
    }
  } catch (error) {
    console.error('시장 데이터 조회 실패:', error);
  } finally {
    loading.value = false;
  }
};

const collectData = async () => {
  isCollecting.value = true;
  try {
    const response = await marketAPI.collectData();
    if (response.data.success) {
      marketData.value = response.data.data;
      await fetchAdrHistory();
      alert('시장 데이터 수집이 완료되었습니다.');
    } else {
      alert('수집 실패: ' + response.data.message);
    }
  } catch (error) {
    console.error('시장 데이터 수집 실패:', error);
    alert('시장 데이터 수집에 실패했습니다.');
  } finally {
    isCollecting.value = false;
  }
};

const formatNumber = (value, decimals = 0) => {
  if (value === null || value === undefined) return '-';
  return Number(value).toLocaleString('ko-KR', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals
  });
};

const formatDate = (dateStr) => {
  if (!dateStr) return '';
  const date = new Date(dateStr);
  return date.toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric'
  });
};

const getConditionClass = (condition) => {
  if (!condition) return '';
  switch (condition) {
    case 'OVERHEATED': return 'condition-overheated';
    case 'NORMAL': return 'condition-normal';
    case 'OVERSOLD': return 'condition-oversold';
    case 'EXTREME_FEAR': return 'condition-extreme-fear';
    default: return '';
  }
};

const getConditionEmoji = (condition) => {
  if (!condition) return '❓';
  switch (condition) {
    case 'OVERHEATED': return '🔥';
    case 'NORMAL': return '☁️';
    case 'OVERSOLD': return '💧';
    case 'EXTREME_FEAR': return '🥶';
    default: return '❓';
  }
};

const getAdrClass = (adr) => {
  if (adr === null || adr === undefined) return '';
  if (adr >= 120) return 'adr-overheated';
  if (adr <= 60) return 'adr-extreme-fear';
  if (adr <= 80) return 'adr-oversold';
  return 'adr-normal';
};

onMounted(() => {
  fetchData();
  fetchAdrHistory();
});
</script>

<style scoped>
.market-timing-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 2rem;
  background: var(--bg-primary, #0f0f23);
  min-height: 100vh;
  color: var(--text-primary, #e4e4e7);
}

/* 메인 상태 카드 */
.status-overview {
  margin-bottom: 2rem;
}

.main-status {
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
  border-radius: 16px;
  padding: 2rem;
  display: flex;
  align-items: center;
  gap: 2rem;
  border: 2px solid transparent;
}

.main-status.condition-overheated {
  border-color: #ef4444;
  background: linear-gradient(135deg, #1a1a2e 0%, #2d1f1f 100%);
}

.main-status.condition-overheated .status-value {
  color: #fca5a5;
}

.main-status.condition-normal {
  border-color: #6b7280;
  background: linear-gradient(135deg, #1a1a2e 0%, #1f2937 100%);
}

.main-status.condition-normal .status-value {
  color: #d1d5db;
}

.main-status.condition-oversold {
  border-color: #3b82f6;
  background: linear-gradient(135deg, #1a1a2e 0%, #1e293b 100%);
}

.main-status.condition-oversold .status-value {
  color: #93c5fd;
}

.main-status.condition-extreme-fear {
  border-color: #06b6d4;
  background: linear-gradient(135deg, #1a1a2e 0%, #0f172a 100%);
}

.main-status.condition-extreme-fear .status-value {
  color: #67e8f9;
}

.status-icon {
  font-size: 4rem;
}

.status-content {
  flex: 1;
}

.status-label {
  font-size: 0.875rem;
  color: #a1a1aa;
  margin-bottom: 0.25rem;
}

.status-value {
  font-size: 1.5rem;
  font-weight: 700;
  margin-bottom: 0.5rem;
  color: #ffffff;
}

.adr-value {
  font-size: 1.25rem;
  color: #d4d4d8;
}

.adr-value strong {
  color: var(--accent-color, #667eea);
  font-size: 1.5rem;
}

.status-date {
  color: var(--text-muted, #71717a);
  font-size: 0.875rem;
}

/* 데이터 부족 상태 */
.status-value.data-needed {
  color: #f59e0b;
  font-size: 1.25rem;
}

.adr-value.data-needed-hint {
  color: #f59e0b;
  font-size: 0.95rem;
}

/* 데이터 부족 알림 */
.data-needed-alert {
  margin-top: 1rem;
  padding: 1rem 1.5rem;
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.1) 0%, rgba(245, 158, 11, 0.05) 100%);
  border: 1px solid rgba(245, 158, 11, 0.3);
  border-radius: 12px;
}

.alert-content {
  display: flex;
  align-items: center;
  gap: 1rem;
  flex-wrap: wrap;
}

.alert-icon {
  font-size: 2rem;
}

.alert-text {
  flex: 1;
  min-width: 200px;
}

.alert-text strong {
  display: block;
  color: #f59e0b;
  margin-bottom: 0.25rem;
}

.alert-text p {
  margin: 0;
  color: var(--text-muted, #71717a);
  font-size: 0.9rem;
}

.btn-collect-now {
  padding: 0.75rem 1.25rem;
  background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  animation: pulse-glow 2s infinite;
}

.btn-collect-now:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(245, 158, 11, 0.4);
}

@keyframes pulse-glow {
  0%, 100% {
    box-shadow: 0 0 0 0 rgba(245, 158, 11, 0.4);
  }
  50% {
    box-shadow: 0 0 0 8px rgba(245, 158, 11, 0);
  }
}

/* 차트 데이터 없음 */
.no-chart-data {
  height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted, #71717a);
  background: var(--bg-secondary, #27272a);
  border-radius: 8px;
}

.no-data-content {
  text-align: center;
  padding: 2rem;
}

.no-data-icon {
  font-size: 3rem;
  display: block;
  margin-bottom: 1rem;
}

.no-data-content h4 {
  margin: 0 0 0.5rem 0;
  color: var(--text-primary, #e4e4e7);
}

.no-data-content p {
  margin: 0 0 1rem 0;
  color: var(--text-muted, #71717a);
  font-size: 0.9rem;
}

.btn-go-collect {
  padding: 0.5rem 1rem;
  background: transparent;
  color: #f59e0b;
  border: 1px solid #f59e0b;
  border-radius: 8px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-go-collect:hover {
  background: #f59e0b;
  color: white;
}

/* ADR 가이드 */
.adr-guide {
  background: var(--card-bg, #18181b);
  border-radius: 12px;
  padding: 1.5rem;
  margin-bottom: 2rem;
  border: 1px solid var(--border-color, #27272a);
}

.adr-guide h3 {
  margin: 0 0 0.5rem 0;
  color: var(--text-primary, #e4e4e7);
}

.adr-guide > p {
  color: var(--text-muted, #71717a);
  margin: 0 0 1rem 0;
  font-size: 0.9rem;
}

.adr-levels {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1rem;
}

.level {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  border-radius: 8px;
  background: #27272a;
}

.level-emoji {
  font-size: 1.5rem;
}

.level-range {
  font-weight: 600;
  color: #ffffff;
  font-size: 0.85rem;
}

.level-label {
  font-size: 0.8rem;
  color: #a1a1aa;
}

.level.overheated { border-left: 3px solid #ef4444; }
.level.normal { border-left: 3px solid #6b7280; }
.level.oversold { border-left: 3px solid #3b82f6; }
.level.extreme-fear { border-left: 3px solid #06b6d4; }

/* 시장별 상세 */
.market-details {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 1.5rem;
  margin-bottom: 2rem;
}

.market-card {
  background: var(--card-bg, #18181b);
  border-radius: 12px;
  padding: 1.5rem;
  border: 1px solid var(--border-color, #27272a);
}

.market-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid var(--border-color, #27272a);
}

.market-header h3 {
  margin: 0;
  font-size: 1.25rem;
}

.market-condition {
  padding: 0.25rem 0.75rem;
  border-radius: 20px;
  font-size: 0.8rem;
}

.market-stats {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

/* Stacked Bar 등락비 */
.ratio-bar-section {
  margin-bottom: 0.5rem;
}

.ratio-bar-label {
  display: flex;
  justify-content: space-between;
  font-size: 0.8rem;
  margin-bottom: 0.5rem;
}

.rising-count { color: #ef4444; font-weight: 600; }
.unchanged-count { color: #71717a; }
.falling-count { color: #3b82f6; font-weight: 600; }

/* 데이터 없을 때 스타일 */
.rising-count.no-data,
.unchanged-count.no-data,
.falling-count.no-data {
  color: #52525b;
  font-weight: normal;
}

.stacked-bar {
  display: flex;
  height: 24px;
  border-radius: 12px;
  overflow: hidden;
  background: #3f3f46;
  position: relative;
}

/* 데이터 없을 때 빈 트랙 */
.stacked-bar.empty {
  background: linear-gradient(135deg, #27272a 0%, #3f3f46 100%);
  justify-content: center;
  align-items: center;
}

.empty-bar-message {
  color: #71717a;
  font-size: 0.75rem;
  font-weight: 500;
}

/* 데이터 없을 때 바 위에 표시되는 오버레이 */
.empty-bar-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #71717a;
  font-size: 0.75rem;
  font-weight: 500;
  background: linear-gradient(135deg, rgba(39, 39, 42, 0.9) 0%, rgba(63, 63, 70, 0.9) 100%);
  border-radius: 12px;
}

.bar-segment {
  transition: width 0.6s cubic-bezier(0.4, 0, 0.2, 1);
}

.bar-segment.rising {
  background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.2);
}

.bar-segment.unchanged {
  background: linear-gradient(135deg, #71717a 0%, #52525b 100%);
}

.bar-segment.falling {
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.2);
}

.stat-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.stat-row.highlight {
  padding: 0.5rem;
  background: var(--bg-secondary, #27272a);
  border-radius: 6px;
  margin-top: 0.5rem;
}

.stat-row.adr-row {
  margin-top: 1rem;
}

.stat-label {
  color: var(--text-muted, #71717a);
}

.stat-value {
  font-weight: 600;
}

.stat-value.rising { color: #ef4444; }
.stat-value.falling { color: #3b82f6; }
.stat-value .positive { color: #ef4444; }
.stat-value .negative { color: #3b82f6; }

.adr-overheated { color: #ef4444; }
.adr-normal { color: #a1a1aa; }
.adr-oversold { color: #3b82f6; }
.adr-extreme-fear { color: #06b6d4; }

/* Progress Bar 스타일 */
.ratio-progress-bar,
.adr-progress-bar {
  margin-top: 0.5rem;
  margin-bottom: 0.75rem;
}

.progress-track {
  height: 8px;
  background: #4b5563;
  border-radius: 4px;
  overflow: hidden;
  position: relative;
}

.progress-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.3s ease;
}

.progress-fill.rising-fill {
  background: linear-gradient(90deg, #ef4444 0%, #f87171 100%);
}

.progress-fill.adr-overheated {
  background: linear-gradient(90deg, #ef4444 0%, #dc2626 100%);
}

.progress-fill.adr-normal {
  background: linear-gradient(90deg, #6b7280 0%, #9ca3af 100%);
}

.progress-fill.adr-oversold {
  background: linear-gradient(90deg, #3b82f6 0%, #60a5fa 100%);
}

.progress-fill.adr-extreme-fear {
  background: linear-gradient(90deg, #06b6d4 0%, #22d3ee 100%);
}

.progress-labels {
  display: flex;
  justify-content: space-between;
  margin-top: 0.25rem;
  font-size: 0.7rem;
  color: #71717a;
}

.progress-markers {
  position: relative;
  height: 16px;
  margin-top: 0.25rem;
}

.progress-markers .marker {
  position: absolute;
  transform: translateX(-50%);
  font-size: 0.65rem;
  color: #71717a;
}

/* 진단 섹션 */
.diagnosis-section {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 1.5rem;
  margin-bottom: 2rem;
}

.diagnosis-card,
.strategy-card {
  background: var(--card-bg, #18181b);
  border-radius: 12px;
  padding: 1.5rem;
  border: 1px solid var(--border-color, #27272a);
}

.diagnosis-card h3,
.strategy-card h3 {
  margin: 0 0 1rem 0;
  font-size: 1.1rem;
}

.diagnosis-card p,
.strategy-card p {
  margin: 0;
  color: var(--text-secondary, #a1a1aa);
  line-height: 1.6;
}

/* 데이터 관리 */
.data-management {
  background: var(--card-bg, #18181b);
  border-radius: 12px;
  padding: 1.5rem;
  border: 1px solid var(--border-color, #27272a);
}

.data-management h3 {
  margin: 0 0 1rem 0;
}

.management-actions {
  display: flex;
  gap: 1rem;
  margin-bottom: 1rem;
}

.btn-collect,
.btn-refresh {
  padding: 0.75rem 1.5rem;
  border-radius: 8px;
  border: none;
  cursor: pointer;
  font-weight: 600;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  min-width: 160px;
}

.btn-collect {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.btn-collect:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
}

.btn-collect:disabled {
  opacity: 0.8;
  cursor: not-allowed;
  transform: none;
}

.btn-refresh {
  background: #27272a;
  color: #ffffff;
  border: 1px solid #3f3f46;
}

.btn-refresh:hover:not(:disabled) {
  background: #3f3f46;
  color: #ffffff;
}

.btn-refresh:disabled {
  opacity: 0.8;
  cursor: not-allowed;
}

/* 버튼 로딩 상태 */
.btn-loading {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.spinner-small {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.management-note {
  color: var(--text-muted, #71717a);
  font-size: 0.85rem;
  margin: 0;
}

/* 로딩 */
.loading-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.spinner {
  width: 48px;
  height: 48px;
  border: 4px solid var(--border-color, #3f3f46);
  border-top-color: var(--accent-color, #667eea);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 기간 수집 로딩 */
.backfill-loading {
  background: rgba(0, 0, 0, 0.85);
}

.backfill-progress {
  margin-top: 1.5rem;
  text-align: center;
  max-width: 400px;
}

.backfill-progress .progress-title {
  font-size: 1.25rem;
  font-weight: 600;
  color: #f59e0b;
  margin: 0 0 0.5rem 0;
}

.backfill-progress .progress-detail {
  font-size: 1rem;
  color: #d4d4d8;
  margin: 0 0 1rem 0;
}

.backfill-progress .progress-hint {
  font-size: 0.9rem;
  color: #a1a1aa;
  margin: 0 0 1rem 0;
  line-height: 1.5;
}

.backfill-progress .progress-warning {
  font-size: 0.85rem;
  color: #ef4444;
  margin: 0;
  padding: 0.5rem 1rem;
  background: rgba(239, 68, 68, 0.1);
  border-radius: 6px;
}

/* ADR 차트 섹션 */
.adr-chart-section {
  background: var(--card-bg, #18181b);
  border-radius: 12px;
  padding: 1.5rem;
  margin-bottom: 2rem;
  border: 1px solid var(--border-color, #27272a);
  overflow: hidden;
  max-width: 100%;
  box-sizing: border-box;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.section-header h3 {
  margin: 0;
  font-size: 1.1rem;
}

/* Interactive Legend */
.chart-legend.interactive {
  display: flex;
  gap: 0.5rem;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.85rem;
  color: var(--text-primary, #e4e4e7);
  background: transparent;
  border: 1px solid var(--border-color, #3f3f46);
  padding: 0.4rem 0.75rem;
  border-radius: 20px;
  cursor: pointer;
  transition: all 0.2s;
}

.legend-item:hover {
  background: var(--bg-secondary, #27272a);
}

.legend-item.inactive {
  opacity: 0.4;
  text-decoration: line-through;
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.legend-item.kospi .legend-dot { background: #ef4444; }
.legend-item.kosdaq .legend-dot { background: #3b82f6; }
.legend-item.combined .legend-dot { background: #a855f7; }

.chart-container {
  width: 100%;
  max-width: 100%;
  height: 300px;
  position: relative;
  overflow: hidden;
  box-sizing: border-box;
}

/* Backfill 섹션 */
.backfill-section {
  margin-top: 1.5rem;
  padding-top: 1.5rem;
  border-top: 1px solid var(--border-color, #27272a);
}

.backfill-section h4 {
  margin: 0 0 1rem 0;
  font-size: 1rem;
  color: var(--text-secondary, #a1a1aa);
}

.backfill-form {
  display: flex;
  gap: 1rem;
  align-items: flex-end;
  flex-wrap: wrap;
}

.date-inputs {
  display: flex;
  gap: 1rem;
}

.input-group {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.input-group label {
  font-size: 0.8rem;
  color: var(--text-muted, #71717a);
}

.input-group input[type="date"] {
  padding: 0.5rem 0.75rem;
  border-radius: 6px;
  border: 1px solid var(--border-color, #3f3f46);
  background: var(--bg-secondary, #27272a);
  color: var(--text-primary, #e4e4e7);
  font-size: 0.9rem;
}

.input-group input[type="date"]::-webkit-calendar-picker-indicator {
  filter: invert(0.7);
}

.btn-backfill {
  padding: 0.5rem 1rem;
  border-radius: 8px;
  border: 1px solid var(--accent-color, #667eea);
  background: transparent;
  color: var(--accent-color, #667eea);
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  min-width: 120px;
  justify-content: center;
}

.btn-backfill:hover:not(:disabled) {
  background: var(--accent-color, #667eea);
  color: white;
}

.btn-backfill:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.backfill-result {
  width: 100%;
  margin-top: 0.75rem;
  padding: 0.75rem;
  background: rgba(34, 197, 94, 0.1);
  border: 1px solid rgba(34, 197, 94, 0.3);
  border-radius: 6px;
}

.backfill-result p {
  margin: 0;
  color: #22c55e;
  font-size: 0.9rem;
}

/* 반응형 */
@media (max-width: 768px) {
  .market-timing-page {
    padding: 1rem;
  }

  .main-status {
    flex-direction: column;
    text-align: center;
    gap: 1rem;
    padding: 1.5rem;
  }

  .status-icon {
    font-size: 3rem;
  }

  .adr-levels {
    grid-template-columns: 1fr;
  }

  .level {
    flex-direction: column;
    text-align: center;
    gap: 0.5rem;
  }

  .management-actions {
    flex-direction: column;
  }

  .btn-collect,
  .btn-refresh {
    width: 100%;
  }

  .section-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 0.75rem;
  }

  .chart-legend.interactive {
    flex-wrap: wrap;
    gap: 0.5rem;
  }

  .chart-container {
    height: 250px;
  }

  .backfill-form {
    flex-direction: column;
    align-items: stretch;
  }

  .date-inputs {
    flex-direction: column;
  }

  .btn-backfill {
    width: 100%;
  }

  .alert-content {
    flex-direction: column;
    text-align: center;
  }

  .alert-text {
    min-width: auto;
  }

  .btn-collect-now {
    width: 100%;
  }

  .no-data-content {
    padding: 1rem;
  }

  .no-data-icon {
    font-size: 2rem;
  }
}
</style>
