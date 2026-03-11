<template>
  <div class="lotto-page">
    <LoadingSpinner v-if="loading" />
    <div v-else class="content-wrapper">
      <div class="page-header-unified">
        <BackButton :dark="true" />
        <div class="header-title">
          <h1>Lotto Quant Analyzer</h1>
          <p class="subtitle">통계 기반 로또 번호 추출기</p>
        </div>
      </div>

      <!-- 금주의 추천 번호 (하이라이트) -->
      <div v-if="weeklyData && weeklyData.analysis" class="weekly-section">
        <div class="weekly-header">
          <h2>금주의 추천 번호</h2>
          <span class="weekly-date">{{ formatDate(weeklyData.generatedDate) }} 생성</span>
        </div>
        <div class="weekly-games">
          <div v-for="game in weeklyData.analysis.recommendations" :key="'weekly-' + game.gameNo" class="weekly-game">
            <span class="weekly-game-no">{{ game.gameNo }}</span>
            <div class="weekly-numbers">
              <span v-for="num in game.numbers" :key="num"
                    :class="['lotto-ball', getBallColor(num)]">
                {{ num }}
              </span>
            </div>
          </div>
        </div>
        <p class="weekly-note">매주 월요일 06:00 자동 갱신</p>
      </div>

      <!-- 분석 정보 -->
      <div class="analysis-info" v-if="analysisData">
        <div class="info-card">
          <span class="label">기준 회차</span>
          <span class="value">{{ analysisData.latestDrawNo }}회</span>
        </div>
        <div class="info-card">
          <span class="label">분석 회차 수</span>
          <span class="value">{{ analysisData.analyzedDrawCount }}회</span>
        </div>
        <div class="info-card">
          <span class="label">분석 시간</span>
          <span class="value">{{ formatTime(analysisData.analysisTime) }}</span>
        </div>
      </div>

      <!-- 액션 버튼 -->
      <div class="action-bar">
        <button @click="generateNumbers" class="generate-btn" :disabled="generating">
          {{ generating ? '분석 중...' : '새 번호 추천받기' }}
        </button>
        <button @click="showHistory = !showHistory" class="history-btn">
          {{ showHistory ? '추천 번호 보기' : '당첨 내역 보기' }}
        </button>
      </div>

      <!-- 추천 번호 표시 -->
      <div v-if="!showHistory && analysisData && analysisData.recommendations" class="recommendations-section">
        <h2>추천 번호 (5게임)</h2>
        <div class="games-grid">
          <div v-for="game in analysisData.recommendations" :key="game.gameNo" class="game-card">
            <div class="game-header">
              <span class="game-no">{{ game.gameNo }}게임</span>
              <span class="confidence" :class="getConfidenceClass(game.confidence)">
                신뢰도 {{ game.confidence }}%
              </span>
            </div>

            <div class="numbers-row">
              <span v-for="num in game.numbers" :key="num"
                    :class="['lotto-ball', getBallColor(num)]">
                {{ num }}
              </span>
            </div>

            <div class="game-stats">
              <div class="stat-item">
                <span class="stat-label">합계</span>
                <span class="stat-value">{{ game.sum }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">홀:짝</span>
                <span class="stat-value">{{ game.oddEvenRatio }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">저:고</span>
                <span class="stat-value">{{ game.highLowRatio }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">연번</span>
                <span class="stat-value">{{ game.consecutiveCount }}개</span>
              </div>
            </div>

            <div class="strategy-tag">
              {{ game.strategy }}
            </div>
          </div>
        </div>
      </div>

      <!-- 당첨 내역 -->
      <div v-if="showHistory && recentDraws.length > 0" class="history-section">
        <h2>최근 당첨 내역</h2>
        <div class="history-table">
          <div v-for="draw in recentDraws" :key="draw.drawNo" class="history-row">
            <div class="draw-info">
              <span class="draw-no">{{ draw.drawNo }}회</span>
              <span class="draw-date">{{ draw.drawDate }}</span>
            </div>
            <div class="draw-numbers">
              <span v-for="num in draw.numbers" :key="num"
                    :class="['lotto-ball small', getBallColor(num)]">
                {{ num }}
              </span>
              <span class="plus">+</span>
              <span :class="['lotto-ball small bonus', getBallColor(draw.bonusNo)]">
                {{ draw.bonusNo }}
              </span>
            </div>
            <div class="draw-prize" v-if="draw.firstWinAmount">
              <span class="prize-label">1등</span>
              <span class="prize-amount">{{ formatPrize(draw.firstWinAmount) }}</span>
              <span class="winner-count">({{ draw.firstWinnerCount }}명)</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Hot/Cold 번호 분석 -->
      <div v-if="analysisData" class="analysis-section">
        <div class="analysis-card hot">
          <h3>Hot Numbers</h3>
          <p class="desc">최근 자주 나온 번호</p>
          <div class="number-chips">
            <span v-for="stat in analysisData.hotNumbers" :key="stat.number"
                  :class="['number-chip', getBallColor(stat.number)]">
              {{ stat.number }}
              <small>{{ stat.frequency10 }}회</small>
            </span>
          </div>
        </div>

        <div class="analysis-card cold">
          <h3>Cold Numbers</h3>
          <p class="desc">오랫동안 안 나온 번호</p>
          <div class="number-chips">
            <span v-for="stat in analysisData.coldNumbers" :key="stat.number"
                  :class="['number-chip', getBallColor(stat.number)]">
              {{ stat.number }}
              <small>{{ stat.lastAppearance }}회전</small>
            </span>
          </div>
        </div>
      </div>

      <!-- 통계 정보 -->
      <div v-if="analysisData && analysisData.statistics" class="statistics-section">
        <h2>통계 분석</h2>
        <div class="stats-grid">
          <div class="stats-card">
            <h4>합계 통계</h4>
            <div class="stats-content">
              <div class="stat-row">
                <span>평균</span>
                <span>{{ analysisData.statistics.avgSum }}</span>
              </div>
              <div class="stat-row">
                <span>최소</span>
                <span>{{ analysisData.statistics.minSum }}</span>
              </div>
              <div class="stat-row">
                <span>최대</span>
                <span>{{ analysisData.statistics.maxSum }}</span>
              </div>
            </div>
          </div>

          <div class="stats-card">
            <h4>홀짝 분포</h4>
            <div class="distribution-chart">
              <div v-for="(count, ratio) in analysisData.statistics.oddEvenDistribution"
                   :key="ratio" class="dist-bar">
                <span class="ratio-label">{{ ratio }}</span>
                <div class="bar-container">
                  <div class="bar" :style="{ width: getBarWidth(count, analysisData.analyzedDrawCount) }"></div>
                </div>
                <span class="count">{{ count }}</span>
              </div>
            </div>
          </div>

          <div class="stats-card">
            <h4>고저 분포</h4>
            <div class="distribution-chart">
              <div v-for="(count, ratio) in analysisData.statistics.highLowDistribution"
                   :key="ratio" class="dist-bar">
                <span class="ratio-label">{{ ratio }}</span>
                <div class="bar-container">
                  <div class="bar secondary" :style="{ width: getBarWidth(count, analysisData.analyzedDrawCount) }"></div>
                </div>
                <span class="count">{{ count }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 필터 조건 안내 -->
      <div class="filter-info">
        <h3>적용된 필터 조건</h3>
        <ul>
          <li><strong>합계 구간:</strong> 120 ~ 180 (가장 당첨이 많은 구간)</li>
          <li><strong>홀짝 비율:</strong> 3:3, 4:2, 2:4 (극단적 비율 제외)</li>
          <li><strong>고저 비율:</strong> 1~22(저) / 23~45(고) 균형 유지</li>
          <li><strong>연번 제한:</strong> 연속 번호 최대 2개까지</li>
          <li><strong>Hot/Cold 비율:</strong> 70% Hot + 30% Cold 하이브리드</li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import api from '../utils/api';
import LoadingSpinner from '../components/LoadingSpinner.vue';
import BackButton from '../components/BackButton.vue';

const router = useRouter();
const loading = ref(false);
const generating = ref(false);
const showHistory = ref(false);
const analysisData = ref(null);
const recentDraws = ref([]);
const weeklyData = ref(null);

/**
 * 번호 추천 생성
 */
const generateNumbers = async () => {
  generating.value = true;
  try {
    const response = await api.post('/lotto/generate');
    if (response.data.success) {
      analysisData.value = response.data.data;
      showHistory.value = false;
    } else {
      alert(response.data.message || '번호 생성에 실패했습니다.');
    }
  } catch (error) {
    console.error('번호 생성 오류:', error);
    alert('번호 생성 중 오류가 발생했습니다.');
  } finally {
    generating.value = false;
  }
};

/**
 * 분석 데이터 로드
 */
const loadAnalysis = async () => {
  loading.value = true;
  try {
    const response = await api.get('/lotto/analyze');
    if (response.data.success) {
      analysisData.value = response.data.data;
    }
  } catch (error) {
    console.error('분석 데이터 로드 오류:', error);
  } finally {
    loading.value = false;
  }
};

/**
 * 최근 당첨 내역 로드
 */
const loadRecentDraws = async () => {
  try {
    const response = await api.get('/lotto/recent?count=20');
    if (response.data.success) {
      recentDraws.value = response.data.data;
    }
  } catch (error) {
    console.error('당첨 내역 로드 오류:', error);
  }
};

/**
 * 금주의 추천 번호 로드
 */
const loadWeeklyRecommendation = async () => {
  try {
    const response = await api.get('/lotto/weekly');
    if (response.data.success) {
      weeklyData.value = response.data.data;
    }
  } catch (error) {
    console.error('금주의 추천 번호 로드 오류:', error);
  }
};

/**
 * 로또 볼 색상 반환
 */
const getBallColor = (num) => {
  if (num <= 10) return 'yellow';
  if (num <= 20) return 'blue';
  if (num <= 30) return 'red';
  if (num <= 40) return 'gray';
  return 'green';
};

/**
 * 신뢰도 클래스
 */
const getConfidenceClass = (confidence) => {
  if (confidence >= 80) return 'high';
  if (confidence >= 60) return 'medium';
  return 'low';
};

/**
 * 시간 포맷
 */
const formatTime = (dateTime) => {
  if (!dateTime) return '-';
  const date = new Date(dateTime);
  return date.toLocaleString('ko-KR');
};

/**
 * 당첨금 포맷
 */
const formatPrize = (amount) => {
  if (!amount) return '-';
  const billion = Math.floor(amount / 100000000);
  const million = Math.floor((amount % 100000000) / 10000);
  if (billion > 0) {
    return `${billion}억 ${million > 0 ? million + '만원' : '원'}`;
  }
  return `${million}만원`;
};

/**
 * 분포 바 너비 계산
 */
const getBarWidth = (count, total) => {
  if (!total) return '0%';
  return Math.round((count / total) * 100) + '%';
};

/**
 * 날짜 포맷
 */
const formatDate = (dateStr) => {
  if (!dateStr) return '-';
  const date = new Date(dateStr);
  return date.toLocaleDateString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric' });
};

onMounted(() => {
  loadWeeklyRecommendation();
  loadAnalysis();
  loadRecentDraws();
});
</script>

<style scoped>
.lotto-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
  padding: 20px;
  color: #fff;
}

.content-wrapper {
  max-width: 1200px;
  margin: 0 auto;
}

/* 금주의 추천 번호 */
.weekly-section {
  background: linear-gradient(135deg, rgba(255, 215, 0, 0.15) 0%, rgba(255, 107, 107, 0.15) 100%);
  border: 2px solid rgba(255, 215, 0, 0.4);
  border-radius: 20px;
  padding: 25px;
  margin-bottom: 30px;
  text-align: center;
}

.weekly-header {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 15px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.weekly-header h2 {
  margin: 0;
  font-size: 1.5rem;
  background: linear-gradient(90deg, #ffd700, #ff6b6b);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.weekly-date {
  background: rgba(255, 215, 0, 0.2);
  padding: 4px 12px;
  border-radius: 15px;
  font-size: 0.85rem;
  color: #ffd700;
}

.weekly-games {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 15px;
}

.weekly-game {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 15px;
  background: rgba(0, 0, 0, 0.2);
  padding: 12px 20px;
  border-radius: 12px;
}

.weekly-game-no {
  background: linear-gradient(90deg, #ffd700, #ff6b6b);
  color: #1a1a2e;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: bold;
  font-size: 0.9rem;
}

.weekly-numbers {
  display: flex;
  gap: 8px;
}

.weekly-note {
  margin: 0;
  font-size: 0.85rem;
  color: #888;
}

/* 분석 정보 */
.analysis-info {
  display: flex;
  justify-content: center;
  gap: 20px;
  margin-bottom: 30px;
  flex-wrap: wrap;
}

.info-card {
  background: rgba(255, 255, 255, 0.1);
  padding: 15px 25px;
  border-radius: 12px;
  text-align: center;
}

.info-card .label {
  display: block;
  font-size: 0.85rem;
  color: #888;
  margin-bottom: 5px;
}

.info-card .value {
  font-size: 1.2rem;
  font-weight: bold;
  color: #ffd700;
}

/* 액션 버튼 */
.action-bar {
  display: flex;
  justify-content: center;
  gap: 15px;
  margin-bottom: 30px;
}

.generate-btn {
  background: linear-gradient(90deg, #ffd700, #ff6b6b);
  border: none;
  color: #1a1a2e;
  padding: 15px 40px;
  font-size: 1.1rem;
  font-weight: bold;
  border-radius: 30px;
  cursor: pointer;
  transition: transform 0.3s, box-shadow 0.3s;
}

.generate-btn:hover:not(:disabled) {
  transform: scale(1.05);
  box-shadow: 0 5px 20px rgba(255, 215, 0, 0.4);
}

.generate-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.history-btn {
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.3);
  color: #fff;
  padding: 15px 30px;
  font-size: 1rem;
  border-radius: 30px;
  cursor: pointer;
  transition: background 0.3s;
}

.history-btn:hover {
  background: rgba(255, 255, 255, 0.2);
}

/* 추천 번호 섹션 */
.recommendations-section h2 {
  text-align: center;
  margin-bottom: 20px;
}

.games-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 20px;
}

.game-card {
  background: rgba(255, 255, 255, 0.08);
  border-radius: 16px;
  padding: 20px;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.game-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
}

.game-no {
  font-size: 1.2rem;
  font-weight: bold;
}

.confidence {
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 0.85rem;
}

.confidence.high {
  background: rgba(76, 175, 80, 0.3);
  color: #4caf50;
}

.confidence.medium {
  background: rgba(255, 193, 7, 0.3);
  color: #ffc107;
}

.confidence.low {
  background: rgba(244, 67, 54, 0.3);
  color: #f44336;
}

/* 로또 볼 */
.numbers-row {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-bottom: 15px;
}

.lotto-ball {
  width: 45px;
  height: 45px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.1rem;
  font-weight: bold;
  color: #fff;
  text-shadow: 1px 1px 2px rgba(0, 0, 0, 0.3);
}

.lotto-ball.small {
  width: 32px;
  height: 32px;
  font-size: 0.9rem;
}

.lotto-ball.yellow {
  background: linear-gradient(135deg, #ffc107, #ff9800);
}

.lotto-ball.blue {
  background: linear-gradient(135deg, #2196f3, #1976d2);
}

.lotto-ball.red {
  background: linear-gradient(135deg, #f44336, #d32f2f);
}

.lotto-ball.gray {
  background: linear-gradient(135deg, #9e9e9e, #757575);
}

.lotto-ball.green {
  background: linear-gradient(135deg, #4caf50, #388e3c);
}

.lotto-ball.bonus {
  border: 2px solid #ffd700;
}

/* 게임 통계 */
.game-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin-bottom: 15px;
  background: rgba(0, 0, 0, 0.2);
  padding: 10px;
  border-radius: 8px;
}

.stat-item {
  text-align: center;
}

.stat-label {
  display: block;
  font-size: 0.75rem;
  color: #888;
}

.stat-value {
  font-weight: bold;
  color: #ffd700;
}

.strategy-tag {
  text-align: center;
  font-size: 0.85rem;
  color: #888;
  padding: 8px;
  background: rgba(255, 215, 0, 0.1);
  border-radius: 8px;
}

/* 당첨 내역 */
.history-section h2 {
  text-align: center;
  margin-bottom: 20px;
}

.history-table {
  background: rgba(255, 255, 255, 0.05);
  border-radius: 12px;
  overflow: hidden;
}

.history-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 15px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  flex-wrap: wrap;
  gap: 10px;
}

.history-row:last-child {
  border-bottom: none;
}

.draw-info {
  min-width: 120px;
}

.draw-no {
  font-weight: bold;
  color: #ffd700;
  margin-right: 10px;
}

.draw-date {
  color: #888;
  font-size: 0.9rem;
}

.draw-numbers {
  display: flex;
  align-items: center;
  gap: 5px;
}

.plus {
  color: #888;
  margin: 0 5px;
}

.draw-prize {
  text-align: right;
  min-width: 150px;
}

.prize-label {
  color: #888;
  margin-right: 5px;
}

.prize-amount {
  font-weight: bold;
  color: #4caf50;
}

.winner-count {
  color: #888;
  font-size: 0.85rem;
  margin-left: 5px;
}

/* Hot/Cold 분석 */
.analysis-section {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 20px;
  margin: 30px 0;
}

.analysis-card {
  background: rgba(255, 255, 255, 0.08);
  border-radius: 16px;
  padding: 20px;
}

.analysis-card.hot {
  border-left: 4px solid #f44336;
}

.analysis-card.cold {
  border-left: 4px solid #2196f3;
}

.analysis-card h3 {
  margin: 0 0 5px 0;
}

.analysis-card .desc {
  color: #888;
  font-size: 0.85rem;
  margin-bottom: 15px;
}

.number-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.number-chip {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 12px;
  border-radius: 20px;
  font-weight: bold;
}

.number-chip small {
  font-size: 0.7rem;
  opacity: 0.8;
  margin-top: 2px;
}

/* 통계 섹션 */
.statistics-section {
  margin: 30px 0;
}

.statistics-section h2 {
  text-align: center;
  margin-bottom: 20px;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 20px;
}

.stats-card {
  background: rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  padding: 20px;
}

.stats-card h4 {
  margin: 0 0 15px 0;
  color: #ffd700;
}

.stat-row {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.stat-row:last-child {
  border-bottom: none;
}

.distribution-chart {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.dist-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ratio-label {
  width: 40px;
  font-size: 0.85rem;
  color: #888;
}

.bar-container {
  flex: 1;
  height: 20px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 10px;
  overflow: hidden;
}

.bar {
  height: 100%;
  background: linear-gradient(90deg, #ffd700, #ff6b6b);
  border-radius: 10px;
  transition: width 0.3s;
}

.bar.secondary {
  background: linear-gradient(90deg, #2196f3, #4caf50);
}

.count {
  width: 30px;
  text-align: right;
  font-size: 0.85rem;
  color: #888;
}

/* 필터 정보 */
.filter-info {
  background: rgba(255, 255, 255, 0.05);
  border-radius: 12px;
  padding: 20px;
  margin-top: 30px;
}

.filter-info h3 {
  margin: 0 0 15px 0;
  color: #ffd700;
}

.filter-info ul {
  list-style: none;
  padding: 0;
  margin: 0;
}

.filter-info li {
  padding: 8px 0;
  color: #ccc;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.filter-info li:last-child {
  border-bottom: none;
}

/* 반응형 */
@media (max-width: 768px) {
  .lotto-ball {
    width: 38px;
    height: 38px;
    font-size: 1rem;
  }

  .game-stats {
    grid-template-columns: repeat(2, 1fr);
  }

  .history-row {
    flex-direction: column;
    align-items: flex-start;
  }

  .draw-prize {
    text-align: left;
  }
}
</style>
