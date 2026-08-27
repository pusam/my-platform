<!--
  펀더멘털 진단 패널 — StockDetailDashboard 에서 분리 (P-IA 후속 ③-2차, 동작 변화 0).
  diagnosisData(진단 결과) + supplyDemand(금일 수급 미니바용)을 prop 으로 받아 표시만 한다(자체 fetch 없음).
  ※ getAdjustedVerdict / isRsiOverbought 는 부모 헤더(중장기 점수 뱃지)도 쓰므로 부모에도 동일 헬퍼가 남아있다(의도된 복제).
-->
<template>
  <div class="fundamental-section">
    <h2 class="fund-section-title">펀더멘털 진단</h2>

    <!-- 종합 의견 -->
    <div class="verdict-section" :class="getAdjustedVerdictClass(diagnosisData)">
      <div class="verdict-header">
        <span class="verdict-icon">{{ getAdjustedVerdictIcon(diagnosisData) }}</span>
        <div class="verdict-info">
          <span class="verdict-label">{{ getAdjustedVerdict(diagnosisData) }}</span>
          <span class="verdict-score">종합 점수: {{ diagnosisData.overallScore }}점</span>
          <span v-if="isRsiOverbought(diagnosisData)" class="verdict-caution-tag">
            RSI 과열 - 단기 조정 주의
          </span>
        </div>
      </div>
    </div>

    <!-- 경고/긍정 요소 -->
    <div class="alerts-section">
      <div v-if="diagnosisData.warnings?.length" class="alert-box warning">
        <div class="alert-header">주의 사항</div>
        <ul>
          <li v-for="(w, idx) in diagnosisData.warnings" :key="'w-'+idx">{{ w }}</li>
        </ul>
      </div>
      <div v-if="diagnosisData.positives?.length" class="alert-box positive">
        <div class="alert-header">긍정적 요소</div>
        <ul>
          <li v-for="(p, idx) in diagnosisData.positives" :key="'p-'+idx">{{ p }}</li>
        </ul>
      </div>
    </div>

    <!-- 탭 -->
    <div class="fund-tabs">
      <button class="fund-tab-btn" :class="{ active: fundTab === 'financial' }" @click="fundTab = 'financial'">재무 건전성</button>
      <button class="fund-tab-btn" :class="{ active: fundTab === 'supply' }" @click="fundTab = 'supply'">최근 5일 누적 수급</button>
      <button class="fund-tab-btn" :class="{ active: fundTab === 'technical' }" @click="fundTab = 'technical'">기술적 분석</button>
    </div>

    <!-- 탭 콘텐츠 -->
    <div class="fund-tab-content">
      <!-- 재무 건전성 -->
      <div v-if="fundTab === 'financial'" class="analysis-card">
        <div class="card-header">
          <span class="card-icon">💰</span>
          <h3>재무 건전성 <span class="ttm-label">TTM</span></h3>
          <span class="card-score" :class="diagGetScoreClass(diagnosisData.financialHealth?.score)">
            {{ diagnosisData.financialHealth?.score || 0 }}점
          </span>
        </div>
        <div class="card-body" v-if="diagnosisData.financialHealth">
          <div class="metric-row">
            <span class="metric-label">영업이익</span>
            <span class="metric-value">{{ formatBillion(diagnosisData.financialHealth.operatingProfit) }}</span>
          </div>
          <div class="metric-row">
            <span class="metric-label">당기순이익</span>
            <span class="metric-value">{{ formatBillion(diagnosisData.financialHealth.netIncome) }}</span>
          </div>
          <div class="metric-row" v-if="diagnosisData.financialHealth.profitGapRatio">
            <span class="metric-label">순이익-영업이익 괴리</span>
            <span class="metric-value" :class="{ 'warning-text': diagnosisData.financialHealth.hasOneTimeGainWarning }">
              {{ formatPercent(diagnosisData.financialHealth.profitGapRatio) }}
              <span class="metric-hint">(영업이익 대비)</span>
            </span>
          </div>
          <div v-if="diagnosisData.financialHealth.hasOneTimeGainWarning" class="one-time-warning">
            {{ diagnosisData.financialHealth.oneTimeGainReason }}
          </div>
          <div class="metric-row">
            <span class="metric-label">영업이익률</span>
            <span class="metric-value">{{ formatPercent(diagnosisData.financialHealth.operatingMargin) }}</span>
          </div>
          <div class="metric-row">
            <span class="metric-label">ROE</span>
            <span class="metric-value">{{ formatPercent(diagnosisData.financialHealth.roe) }}</span>
          </div>
          <div class="card-assessment">{{ diagnosisData.financialHealth.assessment }}</div>
        </div>
      </div>

      <!-- 최근 5일 누적 수급 -->
      <div v-if="fundTab === 'supply'" class="analysis-card">
        <div class="card-header">
          <span class="card-icon">📊</span>
          <h3>최근 5일 누적 수급</h3>
          <span class="card-score" :class="diagGetScoreClass(diagnosisData.supplyDemand?.score)">
            {{ diagnosisData.supplyDemand?.score || 0 }}점
          </span>
        </div>
        <div class="card-body" v-if="diagnosisData.supplyDemand">
          <!-- 금일 수급 미니바 -->
          <div v-if="supplyDemand && supplyDemand.dataSource !== '장전(초기화)'" class="today-supply-mini">
            <span class="today-label">금일 수급</span>
            <!-- 결측은 방향 없이 '-' — `undefined >= 0` 이 false 라 순매도 색이 칠해지던 것 수정(감사 A-6) -->
            <span class="today-item" :class="netBuyClass(supplyDemand.foreignNetBuy)">
              외국인 {{ netBuyText(supplyDemand.foreignNetBuy) }}
            </span>
            <span class="today-item" :class="netBuyClass(supplyDemand.instNetBuy)">
              기관 {{ netBuyText(supplyDemand.instNetBuy) }}
            </span>
          </div>
          <div v-if="diagIsBeforeMarketOpen()" class="before-market-notice">
            장 시작 전입니다. 전일 기준 데이터입니다.
          </div>
          <div class="supply-row">
            <span class="investor-type">외국인</span>
            <span v-if="diagIsSupplyDataAvailable(diagnosisData.supplyDemand.foreignNet5Days)"
                  class="net-amount" :class="diagGetSupplyDemandClass(diagnosisData.supplyDemand.foreignNet5Days)">
              {{ diagGetSupplyDemandLabel(diagnosisData.supplyDemand.foreignNet5Days) }}
              {{ formatBillionAbs(diagnosisData.supplyDemand.foreignNet5Days) }}
            </span>
            <span v-else class="net-amount no-data">집계 중</span>
            <span class="buy-days" v-if="diagIsSupplyPositive(diagnosisData.supplyDemand.foreignNet5Days)">
              ({{ diagnosisData.supplyDemand.foreignBuyDays }}일 순매수)
            </span>
            <span class="sell-days" v-else-if="diagIsSupplyNegative(diagnosisData.supplyDemand.foreignNet5Days)">
              (연속 순매도 주의!)
            </span>
          </div>
          <div class="supply-row">
            <span class="investor-type">기관</span>
            <span v-if="diagIsSupplyDataAvailable(diagnosisData.supplyDemand.institutionNet5Days)"
                  class="net-amount" :class="diagGetSupplyDemandClass(diagnosisData.supplyDemand.institutionNet5Days)">
              {{ diagGetSupplyDemandLabel(diagnosisData.supplyDemand.institutionNet5Days) }}
              {{ formatBillionAbs(diagnosisData.supplyDemand.institutionNet5Days) }}
            </span>
            <span v-else class="net-amount no-data">집계 중</span>
            <span class="buy-days" v-if="diagIsSupplyPositive(diagnosisData.supplyDemand.institutionNet5Days)">
              ({{ diagnosisData.supplyDemand.institutionBuyDays }}일 순매수)
            </span>
            <span class="sell-days" v-else-if="diagIsSupplyNegative(diagnosisData.supplyDemand.institutionNet5Days)">
              (연속 순매도 주의!)
            </span>
          </div>
          <div class="supply-summary">
            <span v-if="diagnosisData.supplyDemand.isBothBuying" class="both-buying">외국인+기관 동반 매수!</span>
            <span v-else-if="diagnosisData.supplyDemand.isBothSelling" class="both-selling">외국인+기관 동반 매도 주의!</span>
          </div>
          <div class="card-assessment">{{ diagnosisData.supplyDemand.assessment }}</div>
        </div>
      </div>

      <!-- 기술적 분석 -->
      <div v-if="fundTab === 'technical'" class="analysis-card">
        <div class="card-header">
          <span class="card-icon">📈</span>
          <h3>기술적 분석</h3>
          <span class="card-score" :class="diagGetScoreClass(diagnosisData.technicalAnalysis?.score)">
            {{ diagnosisData.technicalAnalysis?.score || 0 }}점
          </span>
        </div>
        <div class="card-body" v-if="diagnosisData.technicalAnalysis">
          <!-- 이동평균선 & RSI -->
          <div class="tech-section">
            <div class="tech-section-title">이동평균선 & RSI</div>
            <div class="tech-indicators">
              <div class="indicator-item">
                <span class="indicator-label">이평선 정배열</span>
                <span class="indicator-status" :class="{ active: diagnosisData.technicalAnalysis.isArrangedUp }">
                  {{ diagnosisData.technicalAnalysis.isArrangedUp ? '정배열' : '아님' }}
                </span>
              </div>
              <div class="indicator-item">
                <span class="indicator-label">20일선 위치</span>
                <!--
                  ⚠ 삼항으로 쓰면 null(미산출)이 false 분기로 떨어져 "20일선 아래"라는
                  약세 신호로 뒤집힌다. 결측은 '없음'이지 '아래'가 아니다(§4c, 2026-08-27 감사 C-2).
                  같은 값을 QuickSummaryBar 는 '-' 로 그린다 — 두 화면이 어긋나지 않게 맞춘다.
                -->
                <span class="indicator-status" :class="{ active: diagnosisData.technicalAnalysis.isAboveMa20 === true }">
                  {{ ma20PositionLabel(diagnosisData.technicalAnalysis.isAboveMa20) }}
                </span>
              </div>
              <div class="indicator-item">
                <span class="indicator-label">골든/데드크로스</span>
                <span class="indicator-status">
                  <span v-if="diagnosisData.technicalAnalysis.isGoldenCross" class="golden">골든크로스</span>
                  <span v-else-if="diagnosisData.technicalAnalysis.isDeadCross" class="dead">데드크로스</span>
                  <span v-else>-</span>
                </span>
              </div>
              <div class="indicator-item">
                <span class="indicator-label">RSI (14일)</span>
                <span class="indicator-status" :class="diagGetRsiClass(diagnosisData.technicalAnalysis.rsiStatus)">
                  {{ formatNumber(diagnosisData.technicalAnalysis.rsi14, 1) }}
                  <span class="rsi-badge" :class="diagGetRsiBadgeClass(diagnosisData.technicalAnalysis.rsiStatus)">
                    {{ diagGetRsiStatusLabel(diagnosisData.technicalAnalysis.rsiStatus) }}
                  </span>
                </span>
              </div>
              <div v-if="diagnosisData.technicalAnalysis.rsiStatus === '과열'" class="rsi-warning">
                단기 조정 주의 - RSI 과열 구간
              </div>
            </div>
          </div>

          <!-- 볼린저 밴드 -->
          <div class="tech-section" v-if="diagnosisData.technicalAnalysis.upperBand">
            <div class="tech-section-title">볼린저 밴드</div>
            <div class="tech-indicators">
              <div class="indicator-item">
                <span class="indicator-label">상단</span>
                <span class="indicator-value">{{ formatPriceWon(diagnosisData.technicalAnalysis.upperBand) }}</span>
              </div>
              <div class="indicator-item">
                <span class="indicator-label">중단 (20SMA)</span>
                <span class="indicator-value">{{ formatPriceWon(diagnosisData.technicalAnalysis.middleBand) }}</span>
              </div>
              <div class="indicator-item">
                <span class="indicator-label">하단</span>
                <span class="indicator-value">{{ formatPriceWon(diagnosisData.technicalAnalysis.lowerBand) }}</span>
              </div>
              <div class="indicator-item">
                <span class="indicator-label">밴드폭</span>
                <span class="indicator-value">{{ formatNumber(diagnosisData.technicalAnalysis.bandWidth, 2) }}%</span>
              </div>
              <div class="indicator-item">
                <span class="indicator-label">스퀴즈</span>
                <span class="indicator-status" :class="{ active: diagnosisData.technicalAnalysis.isSqueeze, squeeze: diagnosisData.technicalAnalysis.isSqueeze }">
                  {{ diagnosisData.technicalAnalysis.isSqueeze ? '에너지 응축!' : '정상' }}
                </span>
              </div>
              <div class="indicator-item">
                <span class="indicator-label">상단 돌파</span>
                <span class="indicator-status" :class="{ active: diagnosisData.technicalAnalysis.isBreakout, breakout: diagnosisData.technicalAnalysis.isBreakout }">
                  {{ diagnosisData.technicalAnalysis.isBreakout ? '돌파!' : '-' }}
                </span>
              </div>
            </div>
          </div>

          <!-- MFI -->
          <div class="tech-section" v-if="diagnosisData.technicalAnalysis.mfiScore !== null && diagnosisData.technicalAnalysis.mfiScore !== undefined">
            <div class="tech-section-title">MFI (자금 흐름 지수)</div>
            <div class="tech-indicators">
              <div class="indicator-item wide">
                <span class="indicator-label">MFI (14일)</span>
                <span class="indicator-status" :class="diagGetMfiClass(diagnosisData.technicalAnalysis.mfiStatus)">
                  {{ formatNumber(diagnosisData.technicalAnalysis.mfiScore, 1) }} ({{ diagnosisData.technicalAnalysis.mfiStatus || '중립' }})
                </span>
              </div>
              <div class="mfi-description">
                <span v-if="diagnosisData.technicalAnalysis.mfiStatus === '과열'">거래량 동반 매수세 과열 - 차익실현 고려</span>
                <span v-else-if="diagnosisData.technicalAnalysis.mfiStatus === '침체'">거래량 동반 매수 기회 - 진짜 바닥일 수 있음</span>
                <span v-else>거래량이 실린 정상적인 흐름</span>
              </div>
            </div>
          </div>

          <div class="tech-signal">
            <span class="signal-label">종합 신호:</span>
            <span class="signal-value">{{ diagnosisData.technicalAnalysis.signalDescription || diagnosisData.technicalAnalysis.overallSignal }}</span>
            <!-- signalDescription(MFI·볼린저 반영) 없이 overallSignal(MA/RSI 추세)로 폴백된 경우, 근거 병기해 아래 종합평가와 모순처럼 안 보이게(AUDIT #3, 표시 정합만) -->
            <span v-if="!diagnosisData.technicalAnalysis.signalDescription && diagnosisData.technicalAnalysis.overallSignal"
                  class="signal-basis">(이동평균·RSI 추세 기준)</span>
          </div>
          <div class="card-assessment">
            {{ diagnosisData.technicalAnalysis.assessment }}
            <span v-if="diagnosisData.technicalAnalysis.assessment" class="assessment-basis">· 종합점수(MFI·볼린저 반영)</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ma20PositionLabel, netBuyClass, netBuyText } from '../../utils/stockFormat'
import { ref } from 'vue';

defineProps({
  diagnosisData: { type: Object, required: true },
  supplyDemand: { type: Object, default: null }
});

// 패널 내 탭(재무/수급/기술)
const fundTab = ref('financial');

// ===== 포맷터 (진단 패널 전용) =====
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

const formatBillion = (value) => {
  if (value === null || value === undefined) return 'N/A';
  const num = Number(value);
  if (isNaN(num)) return 'N/A';
  if (num === 0) return '0억';
  if (Math.abs(num) >= 10000) return `${(num / 10000).toFixed(1)}조`;
  if (Math.abs(num) >= 1) return `${num.toLocaleString('ko-KR')}억`;
  return `${(num * 100).toFixed(0)}백만`;
};

const formatBillionAbs = (value) => {
  if (value === null || value === undefined) return '-';
  const num = Math.abs(Number(value));
  if (num >= 10000) return `${(num / 10000).toFixed(1)}조`;
  if (num >= 1) return `${num.toLocaleString('ko-KR')}억`;
  return `${(num * 100).toFixed(0)}백만`;
};

const formatPriceWon = (value) => {
  if (value === null || value === undefined) return '-';
  return Number(value).toLocaleString() + '원';
};

// ===== 진단 점수/등급 =====
const diagGetScoreClass = (score) => {
  if (score >= 70) return 'score-high';
  if (score >= 40) return 'score-mid';
  return 'score-low';
};

const getVerdictClass = (level) => {
  switch (level) {
    case 'STRONG_BUY': return 'verdict-strong-buy';
    case 'BUY': return 'verdict-buy';
    case 'NEUTRAL': return 'verdict-neutral';
    case 'CAUTION': return 'verdict-caution';
    case 'AVOID': return 'verdict-avoid';
    default: return 'verdict-neutral';
  }
};

const getVerdictIcon = (level) => {
  switch (level) {
    case 'STRONG_BUY': return '🚀';
    case 'BUY': return '👍';
    case 'NEUTRAL': return '🤔';
    case 'CAUTION': return '⚠️';
    case 'AVOID': return '🛑';
    default: return '❓';
  }
};

// ※ 부모 헤더(중장기 점수 뱃지)도 동일 로직을 보유 — 의도된 복제.
//    rsiStatus 는 StockDiagnosisDto 의 technicalAnalysis 안에만 있다 (최상위 X).
const isRsiOverbought = (data) => {
  return data?.technicalAnalysis?.rsiStatus === '과열';
};

const getAdjustedVerdictClass = (data) => {
  if (!data) return 'verdict-neutral';
  if (isRsiOverbought(data) && (data.verdictLevel === 'STRONG_BUY' || data.verdictLevel === 'BUY')) {
    return 'verdict-caution';
  }
  return getVerdictClass(data.verdictLevel);
};

const getAdjustedVerdictIcon = (data) => {
  if (!data) return '❓';
  if (isRsiOverbought(data) && (data.verdictLevel === 'STRONG_BUY' || data.verdictLevel === 'BUY')) {
    return '⚠️';
  }
  return getVerdictIcon(data.verdictLevel);
};

const getAdjustedVerdict = (data) => {
  if (!data) return '분석 중';
  if (isRsiOverbought(data) && (data.verdictLevel === 'STRONG_BUY' || data.verdictLevel === 'BUY')) {
    return '관망';
  }
  return data.verdict || '분석 중';
};

// ===== RSI / MFI =====
const diagGetRsiClass = (status) => {
  if (status === '과열') return 'rsi-caution';
  if (status === '침체') return 'rsi-oversold';
  return 'rsi-neutral';
};

const diagGetRsiBadgeClass = (status) => {
  if (status === '과열') return 'badge-caution';
  if (status === '침체') return 'badge-opportunity';
  return 'badge-neutral';
};

const diagGetRsiStatusLabel = (status) => {
  if (status === '과열') return '과열';
  if (status === '침체') return '매수 기회';
  return '중립';
};

const diagGetMfiClass = (status) => {
  if (status === '과열') return 'mfi-overbought';
  if (status === '침체') return 'mfi-oversold';
  return 'mfi-neutral';
};

// ===== 수급 =====
const diagIsBeforeMarketOpen = () => {
  const now = new Date();
  const hours = now.getHours();
  const minutes = now.getMinutes();
  return hours < 9 || (hours === 9 && minutes === 0);
};

const diagIsSupplyDataAvailable = (value) => {
  if (value === null || value === undefined) return false;
  const num = Number(value);
  return !isNaN(num) && num !== 0;
};

const diagIsSupplyPositive = (value) => {
  if (value === null || value === undefined) return false;
  const num = Number(value);
  return !isNaN(num) && num > 0;
};

const diagIsSupplyNegative = (value) => {
  if (value === null || value === undefined) return false;
  const num = Number(value);
  return !isNaN(num) && num < 0;
};

const diagGetSupplyDemandClass = (value) => {
  if (value === null || value === undefined) return '';
  const num = Number(value);
  if (isNaN(num)) return '';
  if (num > 0) return 'supply-positive';
  if (num < 0) return 'supply-negative';
  return '';
};

const diagGetSupplyDemandLabel = (value) => {
  if (value === null || value === undefined) return '';
  const num = Number(value);
  if (isNaN(num)) return '';
  if (num > 0) return '순매수';
  if (num < 0) return '순매도';
  return '보합';
};
</script>

<style scoped>
.fundamental-section {
  margin-top: 20px;
  padding: 24px;
  background: rgba(30, 30, 60, 0.6);
  border-radius: 16px;
  border: 1px solid #2a2a5a;
}

.fund-section-title {
  display: block;
  font-size: 1.2rem;
  font-weight: 700;
  margin-bottom: 20px;
  color: #fff;
}

/* Verdict Section */
.verdict-section {
  padding: 16px;
  border-radius: 12px;
  margin-bottom: 16px;
  border: 2px solid #3a3a6a;
  background: rgba(50, 50, 80, 0.3);
}

.verdict-section.verdict-strong-buy { border-color: #22c55e; background: rgba(34, 197, 94, 0.1); }
.verdict-section.verdict-buy { border-color: #4ade80; background: rgba(74, 222, 128, 0.08); }
.verdict-section.verdict-neutral { border-color: #6b7280; background: rgba(107, 114, 128, 0.08); }
.verdict-section.verdict-caution { border-color: #f59e0b; background: rgba(245, 158, 11, 0.1); }
.verdict-section.verdict-avoid { border-color: #ef4444; background: rgba(239, 68, 68, 0.1); }

.verdict-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.verdict-icon {
  font-size: 2rem;
  flex-shrink: 0;
}

.verdict-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.verdict-label {
  font-size: 1.3rem;
  font-weight: 700;
}

.verdict-section.verdict-strong-buy .verdict-label { color: #22c55e; }
.verdict-section.verdict-buy .verdict-label { color: #4ade80; }
.verdict-section.verdict-neutral .verdict-label { color: #9ca3af; }
.verdict-section.verdict-caution .verdict-label { color: #f59e0b; }
.verdict-section.verdict-avoid .verdict-label { color: #ef4444; }

.verdict-score {
  font-size: 0.9rem;
  color: #aaa;
  font-family: 'Monaco', monospace;
}

.verdict-caution-tag {
  display: inline-block;
  padding: 2px 8px;
  background: rgba(245, 158, 11, 0.2);
  border: 1px solid rgba(245, 158, 11, 0.4);
  border-radius: 6px;
  font-size: 0.75rem;
  color: #f59e0b;
  width: fit-content;
}

/* Alerts Section */
.alerts-section {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 16px;
}

.alert-box {
  padding: 12px;
  border-radius: 10px;
}

.alert-box.warning {
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.3);
}

.alert-box.positive {
  background: rgba(34, 197, 94, 0.08);
  border: 1px solid rgba(34, 197, 94, 0.3);
}

.alert-header {
  font-size: 0.85rem;
  font-weight: 600;
  margin-bottom: 8px;
}

.alert-box.warning .alert-header { color: #f59e0b; }
.alert-box.positive .alert-header { color: #22c55e; }

.alert-box ul {
  margin: 0;
  padding-left: 16px;
}

.alert-box li {
  font-size: 0.8rem;
  color: #bbb;
  margin-bottom: 4px;
  line-height: 1.4;
}

/* Fund Tabs */
.fund-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 10px;
  padding: 4px;
}

.fund-tab-btn {
  flex: 1;
  padding: 10px 12px;
  background: transparent;
  border: none;
  border-radius: 8px;
  color: #888;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.fund-tab-btn:hover { color: #ccc; background: var(--border-light); }
.fund-tab-btn.active {
  color: #fff;
  background: rgba(102, 126, 234, 0.3);
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.2);
}

/* Analysis Card */
.fund-tab-content .analysis-card {
  background: rgba(50, 50, 80, 0.3);
  border-radius: 12px;
  padding: 20px;
  border: 1px solid #2a2a5a;
}

.fund-tab-content .card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}

.fund-tab-content .card-header h3 {
  margin: 0;
  font-size: 1rem;
  flex: 1;
}

.card-icon { font-size: 1.2rem; }

.card-score {
  padding: 4px 12px;
  border-radius: 16px;
  font-size: 0.85rem;
  font-weight: 700;
  font-family: 'Monaco', monospace;
}

.card-score.score-high { background: rgba(34, 197, 94, 0.2); color: #22c55e; }
.card-score.score-mid { background: rgba(234, 179, 8, 0.2); color: #eab308; }
.card-score.score-low { background: rgba(239, 68, 68, 0.2); color: #ef4444; }

/* Metric Row */
.metric-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid rgba(255,255,255,0.04);
}

.metric-label {
  color: #888;
  font-size: 0.85rem;
}

.metric-value {
  font-weight: 600;
  font-family: 'Monaco', monospace;
  font-size: 0.9rem;
}

.metric-value.warning-text { color: #f59e0b; }

.metric-hint {
  font-size: 0.7rem;
  color: #888;
  font-weight: 400;
  margin-left: 4px;
}

.one-time-warning {
  padding: 6px 10px;
  margin: 4px 0;
  background: rgba(245, 158, 11, 0.1);
  border-radius: 6px;
  font-size: 0.8rem;
  color: #f59e0b;
}

.card-assessment {
  margin-top: 12px;
  padding: 10px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 8px;
  font-size: 0.85rem;
  color: #aaa;
  line-height: 1.5;
}

/* 계산 근거 병기(AUDIT #3) — MA/RSI 추세 vs 종합점수(MFI·볼린저) 소스 구분 표기. 은은한 톤. */
.signal-basis,
.assessment-basis {
  font-size: 0.72rem;
  color: #777;
  margin-left: 6px;
  white-space: nowrap;
}

/* Supply Row */
.supply-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid rgba(255,255,255,0.04);
}

.investor-type {
  width: 60px;
  color: #aaa;
  font-size: 0.85rem;
  font-weight: 600;
  flex-shrink: 0;
}

.net-amount {
  font-weight: 700;
  font-family: 'Monaco', monospace;
  font-size: 0.95rem;
}

.net-amount.supply-positive { color: #ef4444; }
.net-amount.supply-negative { color: #3b82f6; }
.net-amount.no-data { color: #666; font-weight: 400; }

.buy-days { color: #ef4444; font-size: 0.8rem; }
.sell-days { color: #3b82f6; font-size: 0.8rem; }

.supply-summary {
  padding: 8px 0;
  text-align: center;
}

.both-buying { color: #ef4444; font-weight: 700; font-size: 0.9rem; }
.both-selling { color: #3b82f6; font-weight: 700; font-size: 0.9rem; }

/* 금일 수급 미니바 */
.today-supply-mini {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  margin-bottom: 10px;
  background: rgba(167, 139, 250, 0.08);
  border: 1px solid rgba(167, 139, 250, 0.2);
  border-radius: 8px;
  font-size: 0.8rem;
}
.today-label {
  color: #a78bfa;
  font-weight: 600;
  white-space: nowrap;
}
.today-item {
  font-weight: 600;
  font-family: 'Monaco', monospace;
  white-space: nowrap;
}
.today-item.positive { color: #ef4444; }
.today-item.negative { color: #3b82f6; }

.before-market-notice {
  padding: 8px 12px;
  background: rgba(156, 163, 175, 0.1);
  border: 1px solid rgba(156, 163, 175, 0.3);
  border-radius: 8px;
  font-size: 0.8rem;
  color: #9ca3af;
  margin-bottom: 8px;
}

/* Tech Section */
.tech-section {
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(255,255,255,0.06);
}

.tech-section:last-of-type { border-bottom: none; }

.tech-section-title {
  font-size: 0.9rem;
  font-weight: 600;
  color: #ccc;
  margin-bottom: 10px;
}

.tech-indicators {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.indicator-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 10px;
  background: rgba(0, 0, 0, 0.15);
  border-radius: 6px;
}

.indicator-item.wide { flex-direction: column; align-items: flex-start; gap: 4px; }

.indicator-label {
  color: #888;
  font-size: 0.8rem;
}

.indicator-status {
  font-size: 0.85rem;
  font-weight: 600;
  color: #aaa;
}

.indicator-status.active { color: #22c55e; }
.indicator-value {
  font-family: 'Monaco', monospace;
  font-size: 0.85rem;
  color: #ccc;
}

.indicator-status.squeeze { color: #f59e0b; }
.indicator-status.breakout { color: #ef4444; }

.golden { color: #22c55e; font-weight: 700; }
.dead { color: #ef4444; font-weight: 700; }

/* RSI */
.rsi-caution { color: #f59e0b; }
.rsi-oversold { color: #3b82f6; }
.rsi-neutral { color: #9ca3af; }

.rsi-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 0.7rem;
  margin-left: 6px;
}

.badge-caution { background: rgba(245, 158, 11, 0.2); color: #f59e0b; }
.badge-opportunity { background: rgba(59, 130, 246, 0.2); color: #60a5fa; }
.badge-neutral { background: rgba(156, 163, 175, 0.2); color: #9ca3af; }

.rsi-warning {
  padding: 6px 10px;
  background: rgba(245, 158, 11, 0.1);
  border-radius: 6px;
  font-size: 0.8rem;
  color: #f59e0b;
}

/* MFI */
.mfi-overbought { color: #f59e0b; }
.mfi-oversold { color: #3b82f6; }
.mfi-neutral { color: #9ca3af; }

.mfi-description {
  padding: 6px 10px;
  font-size: 0.8rem;
  color: #aaa;
}

/* Tech Signal */
.tech-signal {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px;
  background: rgba(102, 126, 234, 0.08);
  border-radius: 8px;
  margin-top: 8px;
}

.signal-label {
  color: #888;
  font-size: 0.85rem;
  font-weight: 600;
  flex-shrink: 0;
}

.signal-value {
  font-size: 0.85rem;
  color: #a5b4fc;
}

@media (max-width: 768px) {
  .alerts-section { grid-template-columns: 1fr; }
  /* fund-tabs는 가로 스크롤 + wrap이 더 자연스러움 (column보다) */
  .fund-tabs { flex-wrap: wrap; }
  .fund-tab-btn { flex: 1 1 30%; min-width: 0; padding: 8px 12px; font-size: 12px; }
}

.ttm-label {
  display: inline-block;
  font-size: 0.6rem;
  font-weight: 600;
  color: #60a5fa;
  background: rgba(96, 165, 250, 0.15);
  padding: 1px 6px;
  border-radius: 4px;
  margin-left: 6px;
  vertical-align: middle;
}
</style>
