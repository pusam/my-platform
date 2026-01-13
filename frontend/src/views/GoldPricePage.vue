<template>
  <div class="gold-price-page">
    <div class="header">
      <h1>금 시세</h1>
      <div class="header-actions">
        <button @click="goBack" class="back-btn">← 돌아가기</button>
        <button @click="logout" class="logout-btn">로그아웃</button>
      </div>
    </div>

    <div class="page-content">
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

        <div class="widget-body" v-if="goldPrice">
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

      <div class="info-section">
        <h3>금 시세 안내</h3>
        <ul>
          <li>금 시세는 GoldAPI.io를 통해 실시간으로 제공됩니다.</li>
          <li>1돈 = 3.75g 기준으로 환산됩니다.</li>
          <li>24K 순금 국제 시세 기준 (XAU/KRW)입니다.</li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { goldAPI } from '../utils/api'
import { UserManager } from '../utils/auth'

const router = useRouter()
const goldPrice = ref(null)
const loading = ref(true)
const error = ref(null)
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
})
</script>

<style scoped>
.gold-price-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #00d2ff 0%, #3a7bd5 100%);
  padding: 20px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: white;
  padding: 20px 30px;
  border-radius: 10px;
  margin-bottom: 30px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.header h1 {
  margin: 0;
  color: #b8860b;
  font-size: 28px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.back-btn {
  padding: 10px 20px;
  background: #3a7bd5;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 14px;
  transition: background 0.3s;
}

.back-btn:hover {
  background: #2c5fb3;
}

.logout-btn {
  padding: 10px 20px;
  background: #f44336;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 14px;
  transition: background 0.3s;
}

.logout-btn:hover {
  background: #d32f2f;
}

.page-content {
  max-width: 800px;
  margin: 0 auto;
}

.gold-price-widget {
  background: linear-gradient(135deg, #fff9e6 0%, #ffffff 100%);
  border: 2px solid #ffd700;
  border-radius: 10px;
  padding: 30px;
  margin-bottom: 30px;
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
