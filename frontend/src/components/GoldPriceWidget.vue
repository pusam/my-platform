<template>
  <div class="gold-price-widget">
    <div class="widget-header">
      <h3>금 시세</h3>
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
    </div>

    <div class="widget-body loading" v-else-if="loading">
      <p>금 시세를 불러오는 중...</p>
    </div>

    <div class="widget-body error" v-else-if="error">
      <p>{{ error }}</p>
      <button @click="fetchGoldPrice" class="retry-btn">다시 시도</button>
    </div>

    <div class="widget-footer">
      <span class="next-update">다음 자동 갱신: {{ nextUpdateTime }}</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { goldAPI } from '../utils/api'

const goldPrice = ref(null)
const loading = ref(true)
const error = ref(null)
const nextUpdateTime = ref('')

let pollingInterval = null
let countdownInterval = null
let nextUpdateTimestamp = null

// 금 시세 조회
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

// 다음 갱신 시간 계산 (9시, 12시, 15시30분, 18시)
const updateNextUpdateTime = () => {
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

// 카운트다운 업데이트
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

// 등락률 클래스
const changeRateClass = computed(() => {
  if (!goldPrice.value) return ''
  return goldPrice.value.changeRate > 0 ? 'positive' : goldPrice.value.changeRate < 0 ? 'negative' : ''
})

// 가격 포맷
const formatPrice = (price) => {
  if (!price) return '-'
  return new Intl.NumberFormat('ko-KR').format(price)
}

// 날짜 포맷 (YYYYMMDD -> YYYY.MM.DD)
const formatDate = (dateStr) => {
  if (!dateStr || dateStr.length !== 8) return dateStr
  return `${dateStr.substring(0, 4)}.${dateStr.substring(4, 6)}.${dateStr.substring(6, 8)}`
}

// DateTime 포맷
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

// API 호출 시간 포맷 (오전 9시 기준, 오후 1시 기준 등)
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

  // 8시간마다 자동 갱신 (프론트엔드 폴링)
  pollingInterval = setInterval(fetchGoldPrice, 28800000)

  // 카운트다운 업데이트 (1초마다)
  countdownInterval = setInterval(updateCountdown, 1000)
})

onUnmounted(() => {
  if (pollingInterval) clearInterval(pollingInterval)
  if (countdownInterval) clearInterval(countdownInterval)
})
</script>

<style scoped>
.gold-price-widget {
  background: linear-gradient(135deg, #fff9e6 0%, #ffffff 100%);
  border: 1px solid #ffd700;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.widget-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0e68c;
}

.widget-header h3 {
  margin: 0;
  color: #b8860b;
  font-size: 18px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.widget-header h3::before {
  content: '';
  display: inline-block;
  width: 12px;
  height: 12px;
  background: linear-gradient(135deg, #ffd700, #daa520);
  border-radius: 50%;
}

.update-time {
  font-size: 12px;
  color: #888;
}

.price-main {
  text-align: center;
  margin-bottom: 20px;
}

.price-label {
  font-size: 14px;
  color: #666;
  margin-bottom: 4px;
}

.price-value {
  font-size: 32px;
  font-weight: bold;
  color: #b8860b;
}

.price-details {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 16px;
  padding: 12px;
  background: rgba(255, 215, 0, 0.1);
  border-radius: 8px;
}

.detail-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.detail-item .label {
  font-size: 12px;
  color: #888;
}

.detail-item .value {
  font-size: 14px;
  font-weight: 500;
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
  gap: 8px;
  margin-bottom: 16px;
}

.range-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px;
  background: white;
  border-radius: 6px;
  border: 1px solid #eee;
}

.range-item .label {
  font-size: 11px;
  color: #888;
}

.range-item .value {
  font-size: 13px;
  font-weight: 500;
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
  padding-top: 12px;
  border-top: 1px solid #f0e68c;
}

.next-update {
  font-size: 12px;
  color: #888;
}

.refresh-btn {
  background: linear-gradient(135deg, #ffd700, #daa520);
  color: #333;
  border: none;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: opacity 0.2s;
}

.refresh-btn:hover:not(:disabled) {
  opacity: 0.9;
}

.refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.loading, .error {
  text-align: center;
  padding: 40px 20px;
  color: #666;
}

.retry-btn {
  margin-top: 12px;
  background: #5050ff;
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 6px;
  cursor: pointer;
}
</style>
