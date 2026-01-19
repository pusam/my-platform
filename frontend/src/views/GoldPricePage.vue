<template>
  <div class="page-container gold-theme">
    <div class="page-content">
      <header class="common-header">
        <h1>🪙 금 시세</h1>
        <div class="header-actions">
          <button @click="goBack" class="btn btn-back">← 돌아가기</button>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

    <div class="gold-content">
      <div class="gold-price-widget">
        <div class="widget-header">
          <div class="widget-title">
            <span class="gold-icon">🪙</span>
            <h2>오늘의 금 시세</h2>
          </div>
          <span class="update-time" v-if="goldPrice">
            {{ formatUpdateTime(goldPrice.fetchedAt) }}
          </span>
        </div>

        <LoadingSpinner v-if="loading" message="금 시세를 불러오는 중..." />

        <div class="widget-body" v-else-if="goldPrice">
          <div class="price-main">
            <div class="price-label">1돈 (3.75g)</div>
            <div class="price-value">{{ formatPrice(goldPrice.pricePerDon) }}원</div>
          </div>

          <div class="price-details">
            <div class="detail-item">
              <span class="label">1g 가격</span>
              <span class="value">{{ formatPrice(goldPrice.pricePerGram) }}원</span>
            </div>
            <div class="detail-item">
              <span class="label">기준일</span>
              <span class="value">{{ formatDate(goldPrice.baseDate) }}</span>
            </div>
            <div class="detail-item">
              <span class="label">등락률</span>
              <span class="value" :class="changeRateClass">
                {{ goldPrice.changeRate > 0 ? '+' : '' }}{{ goldPrice.changeRate }}%
              </span>
            </div>
          </div>

          <div class="price-range">
            <div class="range-item">
              <span class="label">시가 (1돈)</span>
              <span class="value">{{ formatPrice(goldPrice.openPrice) }}원</span>
            </div>
            <div class="range-item">
              <span class="label">고가 (1돈)</span>
              <span class="value high">{{ formatPrice(goldPrice.highPrice) }}원</span>
            </div>
            <div class="range-item">
              <span class="label">저가 (1돈)</span>
              <span class="value low">{{ formatPrice(goldPrice.lowPrice) }}원</span>
            </div>
            <div class="range-item">
              <span class="label">종가 (1돈)</span>
              <span class="value">{{ formatPrice(goldPrice.closePrice) }}원</span>
            </div>
          </div>

          <div class="widget-footer">
            <span class="next-update">다음 자동 갱신: {{ nextUpdateTime }}</span>
          </div>
        </div>

        <div class="widget-body loading" v-else-if="loading">
          <div class="spinner"></div>
          <p>금 시세를 불러오는 중...</p>
        </div>

        <div class="widget-body error" v-else-if="error">
          <p>{{ error }}</p>
          <button @click="fetchGoldPrice" class="retry-btn">다시 시도</button>
        </div>
      </div>

      <!-- 최근 한 달 차트 -->
      <div class="chart-section">
        <div class="chart-header">
          <h2>📊 최근 한 달 금 시세 추이</h2>
        </div>
        <div class="chart-container">
          <canvas ref="chartCanvas"></canvas>
        </div>
      </div>

      <div class="info-section">
        <h3>금 시세 안내</h3>
        <ul>
          <li>금 시세는 GoldAPI.io를 통해 실시간으로 제공됩니다.</li>
          <li>24K 순금 국제 시세 기준 (XAU/KRW)입니다.</li>
        </ul>
      </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { goldAPI } from '../utils/api'
import { UserManager } from '../utils/auth'
import { Chart, registerables } from 'chart.js'
import LoadingSpinner from '../components/LoadingSpinner.vue'

// Chart.js 등록
Chart.register(...registerables)

const router = useRouter()
const goldPrice = ref(null)
const loading = ref(true)
const error = ref(null)
const chartCanvas = ref(null)
let chartInstance = null
const nextUpdateTime = ref('')

let pollingInterval = null
let countdownInterval = null
let nextUpdateTimestamp = null

const goBack = () => {
  router.back()
}

const logout = () => {
  UserManager.logout()
  router.push('/login')
}

const fetchGoldPrice = async () => {
  try {
    loading.value = true
    error.value = null
    const response = await goldAPI.getPrice()
    if (response.data.success) {
      goldPrice.value = response.data.data
      updateNextUpdateTime()
      // 차트용 히스토리 데이터 조회 후 차트 생성
      await fetchChartData()
    } else {
      error.value = response.data.message
    }
  } catch (err) {
    error.value = '금 시세를 불러오는데 실패했습니다.'
    console.error('Gold price fetch error:', err)
  } finally {
    loading.value = false
  }
}

// DB에서 차트 데이터 가져오기
const fetchChartData = async () => {
  try {
    const response = await goldAPI.getMonthlyHistory()
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
        label: '금 시세 (원/돈)',
        data: prices,
        backgroundColor: 'rgba(255, 193, 7, 0.7)',
        borderColor: 'rgba(255, 193, 7, 1)',
        borderWidth: 2,
        borderRadius: 6,
        hoverBackgroundColor: 'rgba(255, 193, 7, 0.9)'
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
            font: {
              size: 14,
              family: "'Noto Sans KR', sans-serif"
            },
            padding: 15
          }
        },
        tooltip: {
          backgroundColor: 'rgba(0, 0, 0, 0.8)',
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
            callback: function(value) {
              return formatPrice(value) + '원'
            },
            font: {
              size: 12
            }
          },
          grid: {
            color: 'rgba(0, 0, 0, 0.05)'
          }
        },
        x: {
          ticks: {
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
        label: '금 시세 (원/돈)',
        data: monthlyData.prices,
        backgroundColor: 'rgba(255, 193, 7, 0.7)',
        borderColor: 'rgba(255, 193, 7, 1)',
        borderWidth: 2,
        borderRadius: 6,
        hoverBackgroundColor: 'rgba(255, 193, 7, 0.9)'
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
            font: {
              size: 14,
              family: "'Noto Sans KR', sans-serif"
            },
            padding: 15
          }
        },
        tooltip: {
          backgroundColor: 'rgba(0, 0, 0, 0.8)',
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
            callback: function(value) {
              return formatPrice(value) + '원'
            },
            font: {
              size: 12
            }
          },
          grid: {
            color: 'rgba(0, 0, 0, 0.05)'
          }
        },
        x: {
          ticks: {
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
  const basePrice = goldPrice.value?.pricePerDon || 500000

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
  if (!goldPrice.value) return ''
  return goldPrice.value.changeRate > 0 ? 'positive' : goldPrice.value.changeRate < 0 ? 'negative' : ''
})

const formatPrice = (price) => {
  if (!price) return '-'
  return new Intl.NumberFormat('ko-KR').format(price)
}

const formatDate = (dateStr) => {
  if (!dateStr || dateStr.length !== 8) return dateStr
  return `${dateStr.substring(0, 4)}.${dateStr.substring(4, 6)}.${dateStr.substring(6, 8)}`
}

const formatDateTime = (dateTime) => {
  if (!dateTime) return ''
  const date = new Date(dateTime)
  return date.toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
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
  fetchGoldPrice()
  pollingInterval = setInterval(fetchGoldPrice, 28800000)  // 8시간
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

.gold-theme {
  background: linear-gradient(135deg, #f7b733 0%, #fc4a1a 100%);
}

.gold-theme .common-header h1 {
  background: linear-gradient(135deg, #b8860b 0%, #daa520 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.gold-content {
  max-width: 800px;
  margin: 0 auto;
  position: relative;
  min-height: 300px;
}

.gold-price-widget {
  background: linear-gradient(135deg, #fff9e6 0%, #ffffff 100%);
  border: 2px solid #ffd700;
  border-radius: 10px;
  padding: 30px;
  margin-bottom: 30px;
  position: relative;
  min-height: 200px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.widget-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 2px solid #f0e68c;
}

.widget-title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.gold-icon {
  font-size: 32px;
}

.widget-header h2 {
  margin: 0;
  color: #b8860b;
  font-size: 24px;
}

.update-time {
  font-size: 13px;
  color: #888;
}

.price-main {
  text-align: center;
  margin-bottom: 30px;
}

.price-label {
  font-size: 16px;
  color: #666;
  margin-bottom: 8px;
}

.price-value {
  font-size: 48px;
  font-weight: bold;
  color: #b8860b;
}

.price-details {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 20px;
  padding: 16px;
  background: rgba(255, 215, 0, 0.1);
  border-radius: 10px;
}

.detail-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.detail-item .label {
  font-size: 13px;
  color: #888;
  margin-bottom: 4px;
}

.detail-item .value {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.detail-item .value.positive {
  color: #e74c3c;
}

.detail-item .value.negative {
  color: #3498db;
}

.price-range {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}

.range-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12px;
  background: white;
  border-radius: 8px;
  border: 1px solid #eee;
}

.range-item .label {
  font-size: 12px;
  color: #888;
  margin-bottom: 4px;
}

.range-item .value {
  font-size: 14px;
  font-weight: 600;
}

.range-item .value.high {
  color: #e74c3c;
}

.range-item .value.low {
  color: #3498db;
}

.widget-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 16px;
  border-top: 1px solid #f0e68c;
}

.next-update {
  font-size: 13px;
  color: #888;
}

.refresh-btn {
  background: linear-gradient(135deg, #ffd700, #daa520);
  color: #333;
  border: none;
  padding: 12px 24px;
  border-radius: 5px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s;
}

.refresh-btn:hover:not(:disabled) {
  background: linear-gradient(135deg, #ffed4a, #ffd700);
}

.refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.loading, .error {
  text-align: center;
  padding: 60px 20px;
  color: #666;
}

.spinner {
  width: 40px;
  height: 40px;
  border: 4px solid #f0e68c;
  border-top-color: #ffd700;
  border-radius: 50%;
  margin: 0 auto 16px;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.retry-btn {
  margin-top: 16px;
  background: linear-gradient(135deg, #ffd700, #daa520);
  color: #333;
  border: none;
  padding: 12px 24px;
  border-radius: 5px;
  font-size: 14px;
  cursor: pointer;
}

/* 차트 섹션 */
.chart-section {
  background: white;
  padding: 30px;
  border-radius: 10px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
  margin-bottom: 20px;
}

.chart-header {
  margin-bottom: 25px;
}

.chart-header h2 {
  margin: 0;
  color: #333;
  font-size: 22px;
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
  background: white;
  padding: 25px;
  border-radius: 10px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.info-section h3 {
  margin: 0 0 20px 0;
  color: #333;
  font-size: 20px;
}

.info-section ul {
  margin: 0;
  padding-left: 20px;
  color: #666;
}

.info-section li {
  margin-bottom: 10px;
  line-height: 1.6;
}
</style>
