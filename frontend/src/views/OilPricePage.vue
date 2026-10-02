<template>
  <div :class="['page-container', 'oil-theme', { embedded: embedded }]">
    <div class="page-content">
      <header v-if="!embedded" class="common-header">
        <BackButton />
        <h1>🛢️ 원유 시세</h1>
        <div class="header-actions">
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
                {{ signedPercentOrDash(oilPrice.changeRate) }}
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
            <div class="range-item" v-if="oilPrice.openPrice != null">
              <span class="label">시가</span>
              <span class="value">${{ formatUsd(oilPrice.openPrice) }}</span>
            </div>
            <div class="range-item" v-if="oilPrice.highPrice != null">
              <span class="label">고가</span>
              <span class="value high">${{ formatUsd(oilPrice.highPrice) }}</span>
            </div>
            <div class="range-item" v-if="oilPrice.lowPrice != null">
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
            <span class="next-update">60초 간격 자동 갱신</span>
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
          <li>Yahoo Finance(CL=F) 실시간 시세를 기반으로 제공됩니다.</li>
          <li>원화 환산은 참고용이며, 실제 환율과 차이가 있을 수 있습니다.</li>
          <li>시세는 60초 간격으로 자동 갱신됩니다.</li>
        </ul>
      </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { signedPercentOrDash } from '@/utils/marketDataLabels'
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { oilAPI } from '../utils/api'
import { UserManager } from '../utils/auth'
import { Chart, registerables } from 'chart.js'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import BackButton from '../components/BackButton.vue'

Chart.register(...registerables)

const props = defineProps({
  embedded: { type: Boolean, default: false }
})

const router = useRouter()
const oilPrice = ref(null)
const loading = ref(true)
const error = ref(null)
const chartCanvas = ref(null)
let chartInstance = null

let pollingInterval = null

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
            color: '#b0b0c8',   // 어두운 카드 위 범례(금·은 시세와 같은 값)
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
            color: '#b0b0c8',
            callback: function(value) { return '$' + value.toFixed(1) },
            font: { size: 12 }
          },
          grid: { color: 'rgba(255, 255, 255, 0.06)' }
        },
        x: {
          ticks: { color: '#b0b0c8', font: { size: 12 } },
          grid: { display: false }
        }
      }
    }
  })
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
  pollingInterval = setInterval(fetchOilPrice, 60000) // 60초마다 갱신
})

onUnmounted(() => {
  if (pollingInterval) clearInterval(pollingInterval)
  if (chartInstance) chartInstance.destroy()
})
</script>

<style scoped>
@import '../assets/css/common.css';

/* oil-theme 은 금·은 시세처럼 다크 테마 기본 + 파란 액센트로만 작동한다(2026-10-01).
   이 화면만 밝은 테마 잔재(흰 카드·#2c3e50 글자)로 남아 시장 탭 '글로벌 → 원유 시세'에서 기준일 값이
   대비 1.0, 제목이 1.14 였다 — 금 시세(GoldPricePage)와 같은 구조로 옮겼다. */
.oil-theme {
  --oil-primary: #60a5fa;
  --oil-secondary: #93c5fd;
  --oil-light: rgba(59, 130, 246, 0.08);
}

.oil-theme .common-header h1 {
  background: linear-gradient(135deg, var(--oil-primary) 0%, var(--oil-secondary) 100%);
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
  background: var(--card-bg);
  border: 1px solid rgba(96, 165, 250, 0.25);
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
  border-bottom: 1px solid rgba(96, 165, 250, 0.2);
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
  color: var(--oil-primary);
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
  color: var(--oil-secondary);
}

.price-krw {
  font-size: 18px;
  color: var(--text-secondary);
  margin-top: 4px;
}

.price-details {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: var(--spacing-md);
  padding: 16px;
  background: var(--oil-light);
  border: 1px solid rgba(96, 165, 250, 0.18);
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
  color: var(--text-muted);
}

.info-item .value {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.widget-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 16px;
  border-top: 1px solid rgba(96, 165, 250, 0.2);
}

.next-update {
  font-size: 13px;
  color: var(--text-muted);
}

.error {
  text-align: center;
  padding: 60px 20px;
  color: var(--text-muted);
}

.retry-btn {
  margin-top: 16px;
  background: var(--oil-primary);
  color: var(--text-on-accent, #1a1a2e);
  border: none;
  padding: 12px 24px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
}

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
