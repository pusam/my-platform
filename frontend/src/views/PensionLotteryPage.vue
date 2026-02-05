<template>
  <div class="pension-page">
    <LoadingSpinner v-if="loading" />
    <div v-else class="content-wrapper">
      <div class="page-header">
        <BackButton />
        <h1>연금복권 720+ 분석기</h1>
        <p class="subtitle">통계 기반 연금복권 번호 추출기</p>
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
              <span class="group-badge">{{ game.group }}조</span>
              <span v-for="(digit, idx) in game.digits" :key="idx"
                    :class="['pension-digit', getDigitColor(digit)]">
                {{ digit }}
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
              <span class="group-badge large">{{ game.group }}조</span>
              <div class="digit-container">
                <span v-for="(digit, idx) in game.digits" :key="idx"
                      :class="['pension-digit', getDigitColor(digit)]">
                  {{ digit }}
                </span>
              </div>
            </div>

            <div class="game-stats">
              <div class="stat-item">
                <span class="stat-label">합계</span>
                <span class="stat-value">{{ game.digitSum }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">홀짝</span>
                <span class="stat-value small">{{ game.oddEvenPattern }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">고저</span>
                <span class="stat-value small">{{ game.highLowPattern }}</span>
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
            </div>
            <div class="draw-numbers">
              <div class="number-set">
                <span class="set-label">1등</span>
                <span class="group-badge small">{{ draw.firstGroup }}조</span>
                <span v-for="(digit, idx) in draw.firstNumber.split('')" :key="'f-' + idx"
                      :class="['pension-digit small', getDigitColor(parseInt(digit))]">
                  {{ digit }}
                </span>
              </div>
              <div class="number-set bonus">
                <span class="set-label">보너스</span>
                <span class="group-badge small">{{ draw.bonusGroup }}조</span>
                <span v-for="(digit, idx) in draw.bonusNumber.split('')" :key="'b-' + idx"
                      :class="['pension-digit small bonus', getDigitColor(parseInt(digit))]">
                  {{ digit }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 조 번호 통계 -->
      <div v-if="analysisData && analysisData.groupStats" class="group-stats-section">
        <h2>조 번호 통계</h2>
        <div class="group-stats-grid">
          <div v-for="(stat, group) in analysisData.groupStats" :key="group" class="group-stat-card">
            <span class="group-number">{{ group }}조</span>
            <div class="group-bar-container">
              <div class="group-bar" :style="{ width: stat.percentage + '%' }"></div>
            </div>
            <span class="group-percentage">{{ stat.percentage }}%</span>
            <span class="group-count">{{ stat.frequency }}회</span>
          </div>
        </div>
      </div>

      <!-- Hot/Cold 숫자 분석 -->
      <div v-if="analysisData" class="analysis-section">
        <div class="analysis-card hot">
          <h3>Hot Numbers</h3>
          <p class="desc">최근 자주 나온 숫자 (자리별)</p>
          <div class="number-chips">
            <span v-for="stat in analysisData.hotDigits?.slice(0, 12)" :key="stat.position + '-' + stat.digit"
                  :class="['number-chip', getDigitColor(stat.digit)]">
              {{ stat.position }}번째: {{ stat.digit }}
              <small>{{ stat.frequency10 }}회</small>
            </span>
          </div>
        </div>

        <div class="analysis-card cold">
          <h3>Cold Numbers</h3>
          <p class="desc">오랫동안 안 나온 숫자 (자리별)</p>
          <div class="number-chips">
            <span v-for="stat in analysisData.coldDigits?.slice(0, 12)" :key="stat.position + '-' + stat.digit"
                  :class="['number-chip', getDigitColor(stat.digit)]">
              {{ stat.position }}번째: {{ stat.digit }}
              <small>{{ stat.lastAppearance }}회전</small>
            </span>
          </div>
        </div>
      </div>

      <!-- 당첨금 안내 -->
      <div class="prize-info">
        <h3>연금복권 720+ 당첨금</h3>
        <div class="prize-table">
          <div class="prize-row first">
            <span class="prize-rank">1등</span>
            <span class="prize-match">조 + 6자리 일치</span>
            <span class="prize-amount">월 700만원 x 20년</span>
          </div>
          <div class="prize-row">
            <span class="prize-rank">2등</span>
            <span class="prize-match">조 + 끝 5자리 일치</span>
            <span class="prize-amount">월 100만원 x 10년</span>
          </div>
          <div class="prize-row">
            <span class="prize-rank">3등</span>
            <span class="prize-match">조 + 끝 4자리 일치</span>
            <span class="prize-amount">100만원</span>
          </div>
          <div class="prize-row">
            <span class="prize-rank">4등</span>
            <span class="prize-match">조 + 끝 3자리 일치</span>
            <span class="prize-amount">10만원</span>
          </div>
          <div class="prize-row">
            <span class="prize-rank">5등</span>
            <span class="prize-match">조 + 끝 2자리 일치</span>
            <span class="prize-amount">5천원</span>
          </div>
          <div class="prize-row">
            <span class="prize-rank">6등</span>
            <span class="prize-match">조 + 끝 1자리 일치</span>
            <span class="prize-amount">1천원</span>
          </div>
          <div class="prize-row bonus">
            <span class="prize-rank">보너스</span>
            <span class="prize-match">보너스 6자리 일치</span>
            <span class="prize-amount">월 100만원 x 10년</span>
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
import BackButton from '../components/BackButton.vue';

const router = useRouter();
const loading = ref(false);
const generating = ref(false);
const showHistory = ref(false);
const analysisData = ref(null);
const recentDraws = ref([]);
const weeklyData = ref(null);

const generateNumbers = async () => {
  generating.value = true;
  try {
    const response = await api.post('/pension-lottery/generate');
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

const loadAnalysis = async () => {
  loading.value = true;
  try {
    const response = await api.get('/pension-lottery/analyze');
    if (response.data.success) {
      analysisData.value = response.data.data;
    }
  } catch (error) {
    console.error('분석 데이터 로드 오류:', error);
  } finally {
    loading.value = false;
  }
};

const loadRecentDraws = async () => {
  try {
    const response = await api.get('/pension-lottery/recent?count=20');
    if (response.data.success) {
      recentDraws.value = response.data.data;
    }
  } catch (error) {
    console.error('당첨 내역 로드 오류:', error);
  }
};

const loadWeeklyRecommendation = async () => {
  try {
    const response = await api.get('/pension-lottery/weekly');
    if (response.data.success) {
      weeklyData.value = response.data.data;
    }
  } catch (error) {
    console.error('금주의 추천 번호 로드 오류:', error);
  }
};

const getDigitColor = (digit) => {
  if (digit <= 2) return 'low';
  if (digit <= 4) return 'mid-low';
  if (digit <= 6) return 'mid-high';
  return 'high';
};

const getConfidenceClass = (confidence) => {
  if (confidence >= 80) return 'high';
  if (confidence >= 60) return 'medium';
  return 'low';
};

const formatTime = (dateTime) => {
  if (!dateTime) return '-';
  const date = new Date(dateTime);
  return date.toLocaleString('ko-KR');
};

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
.pension-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%);
  padding: 20px;
  color: #fff;
}

.content-wrapper {
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  text-align: center;
  margin-bottom: 30px;
}

.back-button {
  position: absolute;
  left: 20px;
  background: rgba(255, 255, 255, 0.1);
  border: none;
  color: #fff;
  padding: 8px 16px;
  border-radius: 20px;
  cursor: pointer;
  transition: background 0.3s;
}

.back-button:hover {
  background: rgba(255, 255, 255, 0.2);
}

.page-header h1 {
  font-size: 2.5rem;
  margin: 0;
  background: linear-gradient(90deg, #00d4ff, #7c3aed);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.subtitle {
  color: #a0c4ff;
  margin-top: 8px;
}

/* 금주의 추천 번호 */
.weekly-section {
  background: linear-gradient(135deg, rgba(0, 212, 255, 0.15) 0%, rgba(124, 58, 237, 0.15) 100%);
  border: 2px solid rgba(0, 212, 255, 0.4);
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
  background: linear-gradient(90deg, #00d4ff, #7c3aed);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.weekly-date {
  background: rgba(0, 212, 255, 0.2);
  padding: 4px 12px;
  border-radius: 15px;
  font-size: 0.85rem;
  color: #00d4ff;
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
  background: linear-gradient(90deg, #00d4ff, #7c3aed);
  color: #1e3c72;
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
  align-items: center;
  gap: 8px;
}

.weekly-note {
  margin: 0;
  font-size: 0.85rem;
  color: #a0c4ff;
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
  color: #a0c4ff;
  margin-bottom: 5px;
}

.info-card .value {
  font-size: 1.2rem;
  font-weight: bold;
  color: #00d4ff;
}

/* 액션 버튼 */
.action-bar {
  display: flex;
  justify-content: center;
  gap: 15px;
  margin-bottom: 30px;
}

.generate-btn {
  background: linear-gradient(90deg, #00d4ff, #7c3aed);
  border: none;
  color: #fff;
  padding: 15px 40px;
  font-size: 1.1rem;
  font-weight: bold;
  border-radius: 30px;
  cursor: pointer;
  transition: transform 0.3s, box-shadow 0.3s;
}

.generate-btn:hover:not(:disabled) {
  transform: scale(1.05);
  box-shadow: 0 5px 20px rgba(0, 212, 255, 0.4);
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

/* 조 번호 배지 */
.group-badge {
  background: linear-gradient(135deg, #ff6b6b, #ee5a5a);
  color: #fff;
  padding: 6px 12px;
  border-radius: 8px;
  font-weight: bold;
  font-size: 0.9rem;
}

.group-badge.large {
  padding: 8px 16px;
  font-size: 1rem;
}

.group-badge.small {
  padding: 4px 8px;
  font-size: 0.75rem;
}

/* 연금복권 숫자 */
.pension-digit {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 1.2rem;
  font-weight: bold;
  color: #fff;
}

.pension-digit.small {
  width: 28px;
  height: 28px;
  font-size: 0.9rem;
}

.pension-digit.low {
  background: linear-gradient(135deg, #4caf50, #45a049);
}

.pension-digit.mid-low {
  background: linear-gradient(135deg, #2196f3, #1976d2);
}

.pension-digit.mid-high {
  background: linear-gradient(135deg, #ff9800, #f57c00);
}

.pension-digit.high {
  background: linear-gradient(135deg, #9c27b0, #7b1fa2);
}

.pension-digit.bonus {
  border: 2px solid #00d4ff;
}

/* 추천 번호 섹션 */
.recommendations-section h2 {
  text-align: center;
  margin-bottom: 20px;
}

.games-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
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

.numbers-row {
  display: flex;
  align-items: center;
  gap: 15px;
  margin-bottom: 15px;
  flex-wrap: wrap;
  justify-content: center;
}

.digit-container {
  display: flex;
  gap: 6px;
}

.game-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
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
  color: #a0c4ff;
}

.stat-value {
  font-weight: bold;
  color: #00d4ff;
}

.stat-value.small {
  font-size: 0.75rem;
}

.strategy-tag {
  text-align: center;
  font-size: 0.85rem;
  color: #a0c4ff;
  padding: 8px;
  background: rgba(0, 212, 255, 0.1);
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
  padding: 15px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  flex-wrap: wrap;
  gap: 15px;
}

.history-row:last-child {
  border-bottom: none;
}

.draw-info {
  min-width: 80px;
}

.draw-no {
  font-weight: bold;
  color: #00d4ff;
}

.draw-numbers {
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex: 1;
}

.number-set {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.number-set.bonus {
  opacity: 0.8;
}

.set-label {
  font-size: 0.8rem;
  color: #a0c4ff;
  min-width: 45px;
}

/* 조 번호 통계 */
.group-stats-section {
  margin: 30px 0;
}

.group-stats-section h2 {
  text-align: center;
  margin-bottom: 20px;
}

.group-stats-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 15px;
  justify-content: center;
}

.group-stat-card {
  background: rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  padding: 15px 20px;
  display: flex;
  align-items: center;
  gap: 15px;
  min-width: 200px;
}

.group-number {
  font-size: 1.2rem;
  font-weight: bold;
  color: #ff6b6b;
}

.group-bar-container {
  flex: 1;
  height: 10px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 5px;
  overflow: hidden;
}

.group-bar {
  height: 100%;
  background: linear-gradient(90deg, #00d4ff, #7c3aed);
  border-radius: 5px;
  transition: width 0.3s;
}

.group-percentage {
  font-weight: bold;
  color: #00d4ff;
  min-width: 45px;
  text-align: right;
}

.group-count {
  color: #a0c4ff;
  font-size: 0.85rem;
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
  color: #a0c4ff;
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
  border-radius: 12px;
  font-weight: bold;
  font-size: 0.85rem;
}

.number-chip small {
  font-size: 0.7rem;
  opacity: 0.8;
  margin-top: 2px;
}

/* 당첨금 안내 */
.prize-info {
  background: rgba(255, 255, 255, 0.05);
  border-radius: 12px;
  padding: 20px;
  margin-top: 30px;
}

.prize-info h3 {
  text-align: center;
  margin: 0 0 20px 0;
  color: #00d4ff;
}

.prize-table {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.prize-row {
  display: grid;
  grid-template-columns: 80px 1fr 150px;
  gap: 15px;
  padding: 12px 15px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 8px;
  align-items: center;
}

.prize-row.first {
  background: linear-gradient(90deg, rgba(255, 215, 0, 0.2), rgba(255, 215, 0, 0.05));
  border: 1px solid rgba(255, 215, 0, 0.3);
}

.prize-row.bonus {
  background: linear-gradient(90deg, rgba(0, 212, 255, 0.2), rgba(0, 212, 255, 0.05));
  border: 1px solid rgba(0, 212, 255, 0.3);
}

.prize-rank {
  font-weight: bold;
}

.prize-match {
  color: #a0c4ff;
  font-size: 0.9rem;
}

.prize-amount {
  text-align: right;
  font-weight: bold;
  color: #4caf50;
}

/* 반응형 */
@media (max-width: 768px) {
  .page-header h1 {
    font-size: 1.8rem;
  }

  .back-button {
    position: static;
    margin-bottom: 15px;
  }

  .pension-digit {
    width: 32px;
    height: 32px;
    font-size: 1rem;
  }

  .prize-row {
    grid-template-columns: 1fr;
    text-align: center;
  }

  .prize-amount {
    text-align: center;
  }

  .history-row {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
