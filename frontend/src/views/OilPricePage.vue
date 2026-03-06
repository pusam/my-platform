<template>
  <div class="page-container oil-theme">
    <div class="page-content">
      <header class="common-header">
        <h1>🛢️ 원유 시세</h1>
        <div class="header-actions">
          <BackButton />
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

    <div class="oil-content">
      <div class="oil-price-widget">
        <div class="widget-header">
          <div class="widget-title">
            <span class="oil-icon">🛢️</span>
            <h2>WTI 원유 시세</h2>
          </div>
          <span class="update-time" v-if="oilPrice">
            {{ formatUpdateTime(oilPrice.fetchedAt) }}
          </span>
        </div>

        <LoadingSpinner v-if="loading" message="원유 시세를 불러오는 중..." />

        <div class="widget-body" v-else-if="oilPrice">
          <div class="price-main">
            <div class="price-label">1배럴 (USD)</div>
            <div class="price-value">${{ formatUsd(oilPrice.pricePerBarrel) }}</div>
            <div class="price-krw" v-if="oilPrice.priceKrw">
              ≈ {{ formatPrice(oilPrice.priceKrw) }}원
            </div>
          </div>

          <div class="price-details">
            <div class="detail-item">
              <span class="label">기준일</span>
              <span class="value">{{ formatDate(oilPrice.baseDate) }}</span>
            </div>
            <div class="detail-item">
              <span class="label">등락률</span>
              <span class="value" :class="changeRateClass">
                {{ oilPrice.changeRate > 0 ? '+' : '' }}{{ oilPrice.changeRate }}%
              </span>
            </div>
            <div class="detail-item">
              <span class="label">전일 대비</span>
              <span class="value" :class="changeRateClass">
                {{ oilPrice.changePrice > 0 ? '+' : '' }}{{ formatUsd(oilPrice.changePrice) }}
              </span>
            </div>
          </div>

          <div class="price-range">
            <div class="range-item">
              <span class="label">시가</span>
              <span class="value">${{ formatUsd(oilPrice.openPrice) }}</span>
            </div>
            <div class="range-item">
              <span class="label">고가</span>
              <span class="value high">${{ formatUsd(oilPrice.highPrice) }}</span>
            </div>
            <div class="range-item">
              <span class="label">저가</span>
              <span class="value low">${{ formatUsd(oilPrice.lowPrice) }}</span>
            </div>
            <div class="range-item">
              <span class="label">종가</span>
              <span class="value">${{ formatUsd(oilPrice.closePrice) }}</span>
            </div>
          </div>

          <div class="extra-info" v-if="oilPrice.volume">
            <div class="info-item">
              <span class="label">거래량</span>
              <span class="value">{{ formatVolume(oilPrice.volume) }}</span>
            </div>
          </div>

          <div class="widget-footer">
            <span class="next-update">다음 자동 갱신: {{ nextUpdateTime }}</span>
          </div>
        </div>

        <div class="widget-body error" v-else-if="error">
          <p>{{ error }}</p>
          <button @click="fetchOilPrice" class="retry-btn">다시 시도</button>
        </div>
      </div>

      <!-- 최근 한 달 차트 -->
      <div class="chart-section">
        <div class="chart-header">
          <h2>📊 최근 한 달 WTI 원유 시세 추이</h2>
        </div>
        <div class="chart-container">
          <canvas ref="chartCanvas"></canvas>
        </div>
      </div>

      <div class="info-section">
        <h3>원유 시세 안내</h3>
        <ul>
          <li>WTI(West Texas Intermediate) 원유 선물 시세입니다.</li>
          <li>KIS API 해외선물(CL) 시세를 기반으로 제공됩니다.</li>
          <li>원화 환산은 참고용이며, 실제 환율과 차이가 있을 수 있습니다.</li>
          <li>갱신 시간: 평일 07:00, 10:00, 14:00, 18:00, 22:00</li>
        </ul>
      </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { oilAPI } from '../utils/api'
import { UserManager } from '../utils/auth'
import { Chart, registerables } from 'chart.js'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import BackButton from '../components/BackButton.vue'

Chart.register(...registerables)

const router = useRouter()
const oilPrice = ref(null)
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

const fetchOilPrice = async () => {
  try {
    loading.value = true
    error.value = null
    const response = await oilAPI.getPrice()
    if (response.data.success) {
      oilPrice.value = response.data.data
      updateNextUpdateTime()
      await fetchChartData()
    } else {
      error.value = response.data.message
    }
  } catch (err) {
    error.value = '원유 시세를 불러오는데 실패했습니다.'
    console.error('Oil price fetch error:', err)
  } finally {
    loading.value = false
  }
}

const fetchChartData = async () => {
  try {
    const response = await oilAPI.getMonthlyHistory()
    if (response.data.success && response.data.data) {
      await nextTick()
      createChartFromData(response.data.data)
    }
  } catch (err) {
    console.error('Chart data fetch error:', err)
  }
}

const createChartFromData = (historyData) => {
  if (!chartCanvas.value || !historyData || historyData.length === 0) return

  if (chartInstance) {
    chartInstance.destroy()
  }

  const labels = historyData.map(item => {
    const date = new Date(item.fetchedAt)
    return `${date.getMonth() + 1}/${date.getDate()}`
  })
  const prices = historyData.map(item => item.pricePerBarrel)

  const ctx = chartCanvas.value.getContext('2d')
  chartInstance = new Chart(ctx, {
    type: 'bar',
    data: {
      labels: labels,
      datasets: [{
        label: 'WTI 원유 ($/배럴)',
        data: prices,
        backgroundColor: 'rgba(41, 128, 185, 0.7)',
        borderColor: 'rgba(41, 128, 185, 1)',
        borderWidth: 2,
        borderRadius: 6,
        hoverBackgroundColor: 'rgba(41, 128, 185, 0.9)'
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
            font: { size: 14, family: "'Noto Sans KR', sans-serif" },
            padding: 15
          }
        },
        tooltip: {
          backgroundColor: 'rgba(0, 0, 0, 0.8)',
          padding: 12,
          titleFont: { size: 14 },
          bodyFont: { size: 13 },
          callbacks: {
            label: function(context) {
              return '시세: $' + context.parsed.y.toFixed(2) + '/배럴'
            }
          }
        }
      },
      scales: {
        y: {
          beginAtZero: false,
          ticks: {
            callback: function(value) { return '$' + value.toFixed(1) },
            font: { size: 12 }
          },
          grid: { color: 'rgba(0, 0, 0, 0.05)' }
        },
        x: {
          ticks: { font: { size: 12 } },
          grid: { display: false }
        }
      }
    }
  })
}

const updateNextUpdateTime = () => {
  const now = new Date()
  const hours = now.getHours()
  const nextDate = new Date(now)

  if (hours < 7) {
    nextDate.setHours(7, 0, 0, 0)
  } else if (hours < 10) {
    nextDate.setHours(10, 0, 0, 0)
  } else if (hours < 14) {
    nextDate.setHours(14, 0, 0, 0)
  } else if (hours < 18) {
    nextDate.setHours(18, 0, 0, 0)
  } else if (hours < 22) {
    nextDate.setHours(22, 0, 0, 0)
  } else {
    nextDate.setDate(nextDate.getDate() + 1)
    nextDate.setHours(7, 0, 0, 0)
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
  if (!oilPrice.value) return ''
  return oilPrice.value.changeRate > 0 ? 'positive' : oilPrice.value.changeRate < 0 ? 'negative' : ''
})

const formatUsd = (price) => {
  if (price == null) return '-'
  return Number(price).toFixed(2)
}

const formatPrice = (price) => {
  if (price == null) return '-'
  return new Intl.NumberFormat('ko-KR').format(price)
}

const formatVolume = (vol) => {
  if (vol == null) return '-'
  return new Intl.NumberFormat('ko-KR').format(vol)
}

const formatDate = (dateStr) => {
  if (!dateStr || dateStr.length !== 8) return dateStr || '-'
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
  fetchOilPrice()
  pollingInterval = setInterval(fetchOilPrice, 28800000)
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

.oil-theme {
  background: linear-gradient(135deg, #2c3e50 0%, #34495e 100%);
}

.oil-theme .common-header h1 {
  background: linear-gradient(135deg, #2980b9 0%, #3498db 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.oil-content {
  max-width: 800px;
  margin: 0 auto;
  position: relative;
  min-height: 300px;
}

.oil-price-widget {
  background: linear-gradient(135deg, #eaf2f8 0%, #ffffff 100%);
  border: 2px solid #2980b9;
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
  border-bottom: 2px solid #aed6f1;
}

.widget-title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.oil-icon {
  font-size: 32px;
}

.widget-header h2 {
  margin: 0;
  color: #2c3e50;
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
  color: #2c3e50;
}

.price-krw {
  font-size: 18px;
  color: #7f8c8d;
  margin-top: 4px;
}

.price-details {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 20px;
  padding: 16px;
  background: rgba(41, 128, 185, 0.08);
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

.extra-info {
  display: flex;
  justify-content: center;
  margin-bottom: 20px;
}

.info-item {
  display: flex;
  gap: 8px;
  align-items: center;
}

.info-item .label {
  font-size: 13px;
  color: #888;
}

.info-item .value {
  font-size: 15px;
  font-weight: 600;
  color: #333;
}

.widget-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 16px;
  border-top: 1px solid #aed6f1;
}

.next-update {
  font-size: 13px;
  color: #888;
}

.error {
  text-align: center;
  padding: 60px 20px;
  color: #666;
}

.retry-btn {
  margin-top: 16px;
  background: linear-gradient(135deg, #2980b9, #3498db);
  color: white;
  border: none;
  padding: 12px 24px;
  border-radius: 5px;
  font-size: 14px;
  cursor: pointer;
}

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
