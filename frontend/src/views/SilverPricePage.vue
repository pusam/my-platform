<template>
  <div :class="['page-container', 'silver-theme', { embedded: embedded }]">
    <div class="page-content">
      <header v-if="!embedded" class="common-header">
        <BackButton />
        <h1>🥈 은 시세</h1>
        <div class="header-actions">
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

    <div class="silver-content">
      <div class="silver-price-widget">
        <div class="widget-header">
          <div class="widget-title">
            <span class="silver-icon">🥈</span>
            <h2>오늘의 은 시세</h2>
          </div>
          <span class="update-time" v-if="silverPrice">
            {{ formatUpdateTime(silverPrice.fetchedAt) }}
          </span>
        </div>

        <LoadingSpinner v-if="loading" message="은 시세를 불러오는 중..." />

        <div class="widget-body" v-else-if="silverPrice">
          <div class="price-main">
            <div class="price-label">1kg</div>
            <div class="price-value">{{ formatPrice(silverPrice.pricePerKg) }}원</div>
          </div>

          <div class="price-details">
            <div class="detail-item">
              <span class="label">1g 가격</span>
              <span class="value">{{ formatPrice(silverPrice.pricePerGram) }}원</span>
            </div>
            <div class="detail-item">
              <span class="label">1돈 (3.75g)</span>
              <span class="value">{{ formatPrice(silverPrice.pricePerDon) }}원</span>
            </div>
            <div class="detail-item">
              <span class="label">기준일</span>
              <span class="value">{{ formatDate(silverPrice.baseDate) }}</span>
            </div>
            <div class="detail-item">
              <span class="label">등락률</span>
              <span class="value" :class="changeRateClass">
                {{ silverPrice.changeRate > 0 ? '+' : '' }}{{ silverPrice.changeRate }}%
              </span>
            </div>
          </div>

          <div class="price-range">
            <div class="range-item">
              <span class="label">시가 (1돈)</span>
              <span class="value">{{ formatPrice(silverPrice.openPrice) }}원</span>
            </div>
            <div class="range-item">
              <span class="label">고가 (1돈)</span>
              <span class="value high">{{ formatPrice(silverPrice.highPrice) }}원</span>
            </div>
            <div class="range-item">
              <span class="label">저가 (1돈)</span>
              <span class="value low">{{ formatPrice(silverPrice.lowPrice) }}원</span>
            </div>
            <div class="range-item">
              <span class="label">종가 (1돈)</span>
              <span class="value">{{ formatPrice(silverPrice.closePrice) }}원</span>
            </div>
          </div>

          <div class="widget-footer">
            <span class="next-update">다음 자동 갱신: {{ nextUpdateTime }}</span>
          </div>
        </div>

        <div class="widget-body loading" v-else-if="loading">
          <div class="spinner"></div>
          <p>은 시세를 불러오는 중...</p>
        </div>

        <div class="widget-body error" v-else-if="error">
          <p>{{ error }}</p>
          <button @click="fetchSilverPrice" class="retry-btn">다시 시도</button>
        </div>
      </div>

      <!-- 최근 한 달 차트 -->
      <div class="chart-section">
        <div class="chart-header">
          <h2>📊 최근 한 달 은 시세 추이</h2>
        </div>
        <div class="chart-container">
          <canvas ref="chartCanvas"></canvas>
        </div>
      </div>

      <div class="info-section">
        <h3>은 시세 안내</h3>
        <ul>
          <li>은 시세는 GoldAPI.io를 통해 실시간으로 제공됩니다.</li>
          <li>순은 국제 시세 기준 (XAG/KRW)입니다.</li>
        </ul>
      </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { silverAPI } from '../utils/api'
import { UserManager } from '../utils/auth'
import { Chart, registerables } from 'chart.js'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import BackButton from '../components/BackButton.vue'

const props = defineProps({
  embedded: { type: Boolean, default: false }
})

// Chart.js 등록
Chart.register(...registerables)

const router = useRouter()
const silverPrice = ref(null)
const loading = ref(true)
const error = ref(null)
const chartCanvas = ref(null)
let chartInstance = null
const nextUpdateTime = ref('')

let pollingInterval = null
let countdownInterval = null
let nextUpdateTimestamp = null

const logout = () => {
  UserManager.logout()
  router.push('/login')
}

const fetchSilverPrice = async () => {
  try {
    loading.value = true
    error.value = null
    const response = await silverAPI.getPrice()
    if (response.data.success) {
      silverPrice.value = response.data.data
      updateNextUpdateTime()
      // 차트용 히스토리 데이터 조회 후 차트 생성
      await fetchChartData()
    } else {
      error.value = response.data.message
    }
  } catch (err) {
    error.value = '은 시세를 불러오는데 실패했습니다.'
    console.error('Silver price fetch error:', err)
  } finally {
    loading.value = false
  }
}

// DB에서 차트 데이터 가져오기
const fetchChartData = async () => {
  try {
    const response = await silverAPI.getMonthlyHistory()
    if (response.data.success && response.data.data) {
      await nextTick()
      createChartFromData(response.data.data)
    }
  } catch (err) {
    console.error('Chart data fetch error:', err)
    // 실패 시 시뮬레이션 데이터로 차트 생성
    await nextTick()
    createChart()
  }
}

// DB 데이터로 차트 생성
const createChartFromData = (historyData) => {
  if (!chartCanvas.value || !historyData || historyData.length === 0) return

  // 기존 차트가 있으면 삭제
  if (chartInstance) {
    chartInstance.destroy()
  }

  const labels = historyData.map(item => {
    const date = new Date(item.fetchedAt)
    return `${date.getMonth() + 1}/${date.getDate()}`
  })
  const prices = historyData.map(item => item.pricePerDon)

  const ctx = chartCanvas.value.getContext('2d')
  chartInstance = new Chart(ctx, {
    type: 'bar',
    data: {
      labels: labels,
      datasets: [{
        label: '은 시세 (원/돈)',
        data: prices,
        backgroundColor: 'rgba(192, 192, 192, 0.7)',
        borderColor: 'rgba(169, 169, 169, 1)',
        borderWidth: 2,
        borderRadius: 6,
        hoverBackgroundColor: 'rgba(192, 192, 192, 0.9)'
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: {
          display: true,
          position: 'top',
          labels: {
            color: '#b0b0c8',
            font: {
              size: 14,
              family: "'Noto Sans KR', sans-serif"
            },
            padding: 15
          }
        },
        tooltip: {
          backgroundColor: 'rgba(15, 15, 26, 0.95)',
          titleColor: '#f0f0f5',
          bodyColor: '#f0f0f5',
          borderColor: 'rgba(209, 213, 219, 0.4)',
          borderWidth: 1,
          padding: 12,
          titleFont: {
            size: 14
          },
          bodyFont: {
            size: 13
          },
          callbacks: {
            label: function(context) {
              return '시세: ' + formatPrice(context.parsed.y) + '원'
            }
          }
        }
      },
      scales: {
        y: {
          beginAtZero: false,
          ticks: {
            color: '#b0b0c8',
            callback: function(value) {
              return formatPrice(value) + '원'
            },
            font: {
              size: 12
            }
          },
          grid: {
            color: 'rgba(255, 255, 255, 0.06)'
          }
        },
        x: {
          ticks: {
            color: '#b0b0c8',
            font: {
              size: 12
            }
          },
          grid: {
            display: false
          }
        }
      }
    }
  })
}

// 차트 생성 함수 (시뮬레이션 데이터용 - fallback)
const createChart = () => {
  if (!chartCanvas.value) return

  // 기존 차트가 있으면 삭제
  if (chartInstance) {
    chartInstance.destroy()
  }

  // 시뮬레이션 데이터 생성
  const monthlyData = generateMonthlyData()

  const ctx = chartCanvas.value.getContext('2d')
  chartInstance = new Chart(ctx, {
    type: 'bar',
    data: {
      labels: monthlyData.labels,
      datasets: [{
        label: '은 시세 (원/돈)',
        data: monthlyData.prices,
        backgroundColor: 'rgba(192, 192, 192, 0.7)',
        borderColor: 'rgba(169, 169, 169, 1)',
        borderWidth: 2,
        borderRadius: 6,
        hoverBackgroundColor: 'rgba(192, 192, 192, 0.9)'
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: {
          display: true,
          position: 'top',
          labels: {
            color: '#b0b0c8',
            font: {
              size: 14,
              family: "'Noto Sans KR', sans-serif"
            },
            padding: 15
          }
        },
        tooltip: {
          backgroundColor: 'rgba(15, 15, 26, 0.95)',
          titleColor: '#f0f0f5',
          bodyColor: '#f0f0f5',
          borderColor: 'rgba(209, 213, 219, 0.4)',
          borderWidth: 1,
          padding: 12,
          titleFont: {
            size: 14
          },
          bodyFont: {
            size: 13
          },
          callbacks: {
            label: function(context) {
              return '시세: ' + formatPrice(context.parsed.y) + '원'
            }
          }
        }
      },
      scales: {
        y: {
          beginAtZero: false,
          ticks: {
            color: '#b0b0c8',
            callback: function(value) {
              return formatPrice(value) + '원'
            },
            font: {
              size: 12
            }
          },
          grid: {
            color: 'rgba(255, 255, 255, 0.06)'
          }
        },
        x: {
          ticks: {
            color: '#b0b0c8',
            font: {
              size: 12
            }
          },
          grid: {
            display: false
          }
        }
      }
    }
  })
}

// 최근 한 달 데이터 생성 (시뮬레이션)
const generateMonthlyData = () => {
  const labels = []
  const prices = []
  const today = new Date()
  const basePrice = silverPrice.value?.pricePerDon || 6000

  // 30일 데이터 생성
  for (let i = 29; i >= 0; i--) {
    const date = new Date(today)
    date.setDate(date.getDate() - i)

    // 날짜 라벨 (MM/DD 형식)
    labels.push(`${date.getMonth() + 1}/${date.getDate()}`)

    // 가격 시뮬레이션 (기준가 ±5% 범위에서 랜덤)
    const variation = (Math.random() - 0.5) * 0.1 // -5% ~ +5%
    const price = Math.round(basePrice * (1 + variation))
    prices.push(price)
  }

  return { labels, prices }
}

const updateNextUpdateTime = () => {
  // 다음 갱신 시간 계산 (9시, 12시, 15시30분, 18시)
  const now = new Date()
  const hours = now.getHours()
  const minutes = now.getMinutes()
  const nextDate = new Date(now)

  if (hours < 9) {
    nextDate.setHours(9, 0, 0, 0)
  } else if (hours < 12) {
    nextDate.setHours(12, 0, 0, 0)
  } else if (hours < 15 || (hours === 15 && minutes < 30)) {
    nextDate.setHours(15, 30, 0, 0)
  } else if (hours < 18) {
    nextDate.setHours(18, 0, 0, 0)
  } else {
    nextDate.setDate(nextDate.getDate() + 1)
    nextDate.setHours(9, 0, 0, 0)
  }

  nextUpdateTimestamp = nextDate.getTime()
  updateCountdown()
}

const updateCountdown = () => {
  if (!nextUpdateTimestamp) return
  const remaining = nextUpdateTimestamp - Date.now()
  if (remaining <= 0) {
    nextUpdateTime.value = '곧 갱신됩니다'
    return
  }
  const hours = Math.floor(remaining / 3600000)
  const minutes = Math.floor((remaining % 3600000) / 60000)
  if (hours > 0) {
    nextUpdateTime.value = `${hours}시간 ${minutes}분 후`
  } else {
    nextUpdateTime.value = `${minutes}분 후`
  }
}

const changeRateClass = computed(() => {
  if (!silverPrice.value) return ''
  return silverPrice.value.changeRate > 0 ? 'positive' : silverPrice.value.changeRate < 0 ? 'negative' : ''
})

const formatPrice = (price) => {
  if (!price) return '-'
  return new Intl.NumberFormat('ko-KR').format(price)
}

const formatDate = (dateStr) => {
  if (!dateStr || dateStr.length !== 8) return dateStr
  return `${dateStr.substring(0, 4)}.${dateStr.substring(4, 6)}.${dateStr.substring(6, 8)}`
}

const formatUpdateTime = (dateTime) => {
  if (!dateTime) return ''
  const date = new Date(dateTime)
  const hours = date.getHours()
  const ampm = hours < 12 ? '오전' : '오후'
  const displayHour = hours <= 12 ? hours : hours - 12
  return `${ampm} ${displayHour}시 기준`
}

onMounted(() => {
  fetchSilverPrice()
  pollingInterval = setInterval(fetchSilverPrice, 28800000)  // 8시간
  countdownInterval = setInterval(updateCountdown, 1000)
})

onUnmounted(() => {
  if (pollingInterval) clearInterval(pollingInterval)
  if (countdownInterval) clearInterval(countdownInterval)
  if (chartInstance) chartInstance.destroy()
})
</script>

<style scoped>
@import '../assets/css/common.css';

.silver-theme .common-header h1 {
  background: linear-gradient(135deg, var(--silver-primary) 0%, var(--silver-secondary) 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.silver-content {
  max-width: 800px;
  margin: 0 auto;
  position: relative;
  min-height: 300px;
}

.page-content {
  max-width: 800px;
  margin: 0 auto;
}

.silver-price-widget {
  background: var(--card-bg);
  border: 1px solid rgba(209, 213, 219, 0.25);
  border-radius: var(--card-radius);
  padding: var(--card-padding);
  margin-bottom: var(--spacing-lg);
  position: relative;
  min-height: 200px;
  box-shadow: var(--card-shadow);
}

.widget-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-md);
  padding-bottom: 16px;
  border-bottom: 1px solid rgba(209, 213, 219, 0.2);
}

.widget-title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.silver-icon {
  font-size: 32px;
}

.widget-header h2 {
  margin: 0;
  color: var(--silver-primary);
  font-size: var(--header-title-size);
  font-weight: var(--header-title-weight);
}

.update-time {
  font-size: 13px;
  color: var(--text-muted);
}

.price-main {
  text-align: center;
  margin-bottom: var(--spacing-lg);
}

.price-label {
  font-size: 16px;
  color: var(--text-secondary);
  margin-bottom: 8px;
}

.price-value {
  font-size: 48px;
  font-weight: bold;
  background: linear-gradient(135deg, var(--silver-primary) 0%, var(--silver-secondary) 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.price-details {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: var(--spacing-md);
  padding: 16px;
  background: var(--silver-light);
  border: 1px solid rgba(209, 213, 219, 0.18);
  border-radius: 12px;
}

.detail-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.detail-item .label {
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 4px;
}

.detail-item .value {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.detail-item .value.positive {
  color: var(--stock-up);
}

.detail-item .value.negative {
  color: var(--stock-down);
}

.price-range {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: var(--spacing-md);
}

.range-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12px;
  background: var(--bg-surface);
  border-radius: 8px;
  border: 1px solid var(--border-light);
}

.range-item .label {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 4px;
}

.range-item .value {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.range-item .value.high {
  color: var(--stock-up);
}

.range-item .value.low {
  color: var(--stock-down);
}

.widget-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 16px;
  border-top: 1px solid rgba(209, 213, 219, 0.2);
}

.next-update {
  font-size: 13px;
  color: var(--text-muted);
}

.refresh-btn {
  background: linear-gradient(135deg, var(--silver-primary) 0%, var(--silver-secondary) 100%);
  color: var(--text-on-accent, #1a1a2e);
  border: none;
  padding: 12px 24px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  transition: all var(--transition-normal);
}

.refresh-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 14px rgba(209, 213, 219, 0.4);
}

.refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.loading, .error {
  text-align: center;
  padding: 60px 20px;
  color: var(--text-muted);
}

.spinner {
  width: 40px;
  height: 40px;
  border: 4px solid rgba(209, 213, 219, 0.2);
  border-top-color: var(--silver-primary);
  border-radius: 50%;
  margin: 0 auto 16px;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.retry-btn {
  margin-top: 16px;
  background: linear-gradient(135deg, var(--silver-primary) 0%, var(--silver-secondary) 100%);
  color: var(--text-on-accent, #1a1a2e);
  border: none;
  padding: 12px 24px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  transition: all var(--transition-normal);
}

.retry-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 14px rgba(209, 213, 219, 0.4);
}

/* 차트 섹션 */
.chart-section {
  background: var(--card-bg);
  padding: var(--card-padding);
  border-radius: var(--card-radius);
  box-shadow: var(--card-shadow);
  border: 1px solid var(--border-light);
  margin-bottom: var(--spacing-md);
}

.chart-header {
  margin-bottom: var(--spacing-md);
}

.chart-header h2 {
  margin: 0;
  color: var(--text-white);
  font-size: var(--header-title-size);
  font-weight: 600;
}

.chart-container {
  position: relative;
  height: 400px;
  width: 100%;
}

.chart-container canvas {
  max-width: 100%;
  max-height: 100%;
}

.info-section {
  background: var(--card-bg);
  padding: var(--card-padding);
  border-radius: var(--card-radius);
  box-shadow: var(--card-shadow);
  border: 1px solid var(--border-light);
}

.info-section h3 {
  margin: 0 0 var(--spacing-md) 0;
  color: var(--text-white);
  font-size: 20px;
  font-weight: 700;
}

.info-section ul {
  margin: 0;
  padding-left: 20px;
  color: var(--text-secondary);
}

.info-section li {
  margin-bottom: 10px;
  line-height: 1.6;
}

.page-container.embedded {
  padding: 0;
  background: transparent;
  min-height: auto;
}

.page-container.embedded .page-content {
  padding: 0;
}
</style>
