<template>
  <div class="trading-dashboard">
    <!-- Header -->
    <header class="dashboard-header">
      <div class="header-left">
        <BackButton />
        <div class="stock-info">
          <h1 class="stock-name">{{ stockName || '종목 검색' }}</h1>
          <span class="stock-code">{{ stockCode }}</span>
        </div>
      </div>
      <div class="header-center">
        <div class="price-info" v-if="priceInfo">
          <span class="current-price">{{ formatPrice(priceInfo.currentPrice) }}원</span>
          <span class="change-info" :class="priceClass">
            {{ Number(priceInfo.changePrice) > 0 ? '+' : '' }}{{ formatPrice(priceInfo.changePrice) }}
            ({{ Number(priceInfo.changeRate) > 0 ? '+' : '' }}{{ Number(priceInfo.changeRate)?.toFixed(2) }}%)
          </span>
        </div>
      </div>
      <div class="header-right">
        <div class="ai-score-box" :class="aiScoreClass">
          <span class="score-label">AI 점수</span>
          <span class="score-value">{{ aiAnalysis?.overallScore || '-' }}</span>
          <span class="score-badge">{{ aiAnalysis?.recommendation || '-' }}</span>
        </div>
      </div>
    </header>

    <!-- 검색바 + 실시간 상태 -->
    <div class="control-section">
      <div class="search-bar">
        <input
          type="text"
          v-model="searchQuery"
          @keyup.enter="searchStock"
          placeholder="종목명 또는 종목코드 입력 (예: 삼성전자, 005930)"
        />
        <button @click="searchStock" :disabled="loading">
          {{ loading ? '분석 중...' : '종합 분석' }}
        </button>
      </div>

      <div v-if="hasData" class="realtime-status" :class="{ active: autoRefresh }">
        <span class="status-dot" :class="{ pulsing: autoRefresh }"></span>
        <span class="status-text">{{ autoRefresh ? '실시간 감시 중' : '감시 대기' }}</span>
        <label class="auto-refresh-toggle">
          <input type="checkbox" v-model="autoRefresh" @change="toggleAutoRefresh" />
          10초 자동 갱신
        </label>
        <span class="update-time" v-if="lastUpdated">{{ formatTime(lastUpdated) }}</span>
      </div>
    </div>

    <!-- 로딩 -->
    <div v-if="loading" class="loading-overlay">
      <div class="loading-spinner"></div>
      <p>종합 데이터 분석 중...</p>
    </div>

    <!-- 메인 2컬럼 그리드 -->
    <div v-else-if="hasData" class="main-grid">
      <!-- ========== Left Column: 차트 영역 ========== -->
      <div class="left-column">
        <!-- 주가 차트 -->
        <div class="chart-section">
          <div class="section-header">
            <h2>주가 차트</h2>
            <div class="ma-legend">
              <span class="ma5">MA5: {{ chartData?.ma5?.toLocaleString() }}</span>
              <span class="ma20">MA20: {{ chartData?.ma20?.toLocaleString() }}</span>
              <span class="vwap">VWAP: {{ chartData?.vwap?.toLocaleString() }}</span>
            </div>
          </div>
          <div class="candlestick-container">
            <div class="candlestick-chart">
              <div
                v-for="(candle, index) in displayCandles"
                :key="index"
                class="candle"
                :class="{ up: candle.close >= candle.open, down: candle.close < candle.open }"
                :style="getCandleStyle(candle)"
              >
                <div class="wick" :style="getWickStyle(candle)"></div>
                <div class="body" :style="getBodyStyle(candle)"></div>
              </div>
            </div>
          </div>
          <div class="volume-chart">
            <div
              v-for="(vol, index) in displayVolumes"
              :key="index"
              class="volume-bar"
              :class="{ up: displayCandles[index]?.close >= displayCandles[index]?.open }"
              :style="{ height: getVolumeHeight(vol.volume) + '%' }"
            ></div>
          </div>
        </div>

        <!-- 핵심 재무 -->
        <div class="financial-section">
          <h2>핵심 재무</h2>
          <div class="financial-grid">
            <div class="fin-card">
              <span class="fin-label">PER</span>
              <span class="fin-value" :class="getPERClass(financial?.per)">
                {{ financial?.per?.toFixed(1) || '-' }}배
              </span>
            </div>
            <div class="fin-card">
              <span class="fin-label">PBR</span>
              <span class="fin-value" :class="getPBRClass(financial?.pbr)">
                {{ financial?.pbr?.toFixed(2) || '-' }}배
              </span>
            </div>
            <div class="fin-card">
              <span class="fin-label">EPS</span>
              <span class="fin-value">{{ formatPrice(financial?.eps) || '-' }}원</span>
            </div>
            <div class="fin-card">
              <span class="fin-label">BPS</span>
              <span class="fin-value">{{ formatPrice(financial?.bps) || '-' }}원</span>
            </div>
            <div class="fin-card wide">
              <span class="fin-label">시가총액</span>
              <span class="fin-value">{{ formatMarketCap(financial?.marketCap) }}</span>
            </div>
          </div>
        </div>

        <!-- 관련 뉴스 (좌측 하단) -->
        <div class="news-section-left">
          <div class="section-header">
            <h2>관련 뉴스</h2>
            <span class="news-count">{{ riskInfo?.news?.length || 0 }}건</span>
          </div>
          <div class="news-list" v-if="riskInfo?.news?.length">
            <div v-for="(news, index) in riskInfo.news.slice(0, 8)" :key="index" class="news-item">
              <div class="news-content">
                <a :href="news.link" target="_blank">{{ truncate(news.title, 60) }}</a>
                <p v-if="news.description" class="news-desc">{{ truncate(news.description, 80) }}</p>
              </div>
              <span class="news-date">{{ formatPubDate(news.pubDate) }}</span>
            </div>
          </div>
          <div v-else class="no-news">
            <p>관련 뉴스가 없습니다.</p>
          </div>
        </div>
      </div>

      <!-- ========== Right Column: 정보 영역 ========== -->
      <div class="right-column">
        <!-- Zone A: 체결강도 + 수급 -->
        <div class="zone zone-a">
          <!-- 체결강도 게이지 -->
          <VolumePowerGauge
            :volumePower="supplyDemand?.volumePower || 100"
            :signal="supplyDemand?.volumeSignal || 'NEUTRAL'"
          />

          <!-- 투자자별 수급 막대 차트 -->
          <div class="investor-section">
            <div class="supply-header">
              <h3>투자자별 수급</h3>
              <span class="data-source-badge" :class="supplySourceClass">
                {{ supplyDemand?.dataSource || '대기' }}
              </span>
            </div>
            <div class="investor-bar-chart">
              <!-- 외국인 -->
              <div class="investor-bar-row">
                <span class="bar-label">외국인</span>
                <div class="bar-container">
                  <div class="bar-track">
                    <div
                      class="bar-fill"
                      :class="supplyDemand?.foreignNetBuy >= 0 ? 'positive' : 'negative'"
                      :style="{ width: getBarWidth(supplyDemand?.foreignNetBuy) + '%' }"
                    ></div>
                  </div>
                </div>
                <span class="bar-value" :class="supplyDemand?.foreignNetBuy >= 0 ? 'positive' : 'negative'">
                  {{ supplyDemand?.foreignNetBuy >= 0 ? '+' : '' }}{{ supplyDemand?.foreignNetBuy?.toFixed(0) || 0 }}억
                </span>
              </div>
              <!-- 기관 -->
              <div class="investor-bar-row">
                <span class="bar-label">기관</span>
                <div class="bar-container">
                  <div class="bar-track">
                    <div
                      class="bar-fill"
                      :class="supplyDemand?.instNetBuy >= 0 ? 'positive' : 'negative'"
                      :style="{ width: getBarWidth(supplyDemand?.instNetBuy) + '%' }"
                    ></div>
                  </div>
                </div>
                <span class="bar-value" :class="supplyDemand?.instNetBuy >= 0 ? 'positive' : 'negative'">
                  {{ supplyDemand?.instNetBuy >= 0 ? '+' : '' }}{{ supplyDemand?.instNetBuy?.toFixed(0) || 0 }}억
                </span>
              </div>
              <!-- 프로그램 -->
              <div class="investor-bar-row highlight">
                <span class="bar-label">프로그램</span>
                <div class="bar-container">
                  <div class="bar-track">
                    <div
                      class="bar-fill"
                      :class="supplyDemand?.programNetBuy >= 0 ? 'positive' : 'negative'"
                      :style="{ width: getBarWidth(supplyDemand?.programNetBuy) + '%' }"
                    ></div>
                  </div>
                </div>
                <span class="bar-value" :class="supplyDemand?.programNetBuy >= 0 ? 'positive' : 'negative'">
                  {{ supplyDemand?.programNetBuy >= 0 ? '+' : '' }}{{ supplyDemand?.programNetBuy?.toFixed(0) || 0 }}억
                </span>
              </div>
            </div>
          </div>
        </div>

        <!-- Zone B: 리스크 게이지 + AI 전략 -->
        <div class="zone zone-b">
          <!-- 리스크 게이지 (원형) -->
          <div class="risk-gauge-section" :class="riskStatusClass">
            <div class="gauge-header">
              <h2>리스크 분석</h2>
              <span class="risk-badge" :class="riskStatusClass">
                {{ getRiskStatusText(riskInfo?.riskStatus) }}
              </span>
            </div>

            <div class="gauge-container">
              <div class="gauge">
                <svg viewBox="0 0 200 120" class="gauge-svg">
                  <path
                    d="M 20 100 A 80 80 0 0 1 180 100"
                    fill="none"
                    stroke="#2a2a4a"
                    stroke-width="16"
                    stroke-linecap="round"
                  />
                  <path
                    d="M 20 100 A 80 80 0 0 1 180 100"
                    fill="none"
                    stroke="url(#riskGaugeGradient)"
                    stroke-width="16"
                    stroke-linecap="round"
                    :stroke-dasharray="gaugeArcLength"
                    :stroke-dashoffset="gaugeDashOffset"
                    class="gauge-arc"
                  />
                  <defs>
                    <linearGradient id="riskGaugeGradient" x1="0%" y1="0%" x2="100%" y2="0%">
                      <stop offset="0%" stop-color="#22c55e" />
                      <stop offset="50%" stop-color="#eab308" />
                      <stop offset="100%" stop-color="#ef4444" />
                    </linearGradient>
                  </defs>
                </svg>
                <div class="gauge-value">
                  <span class="score" :class="riskStatusClass">{{ riskInfo?.riskScore ?? '-' }}</span>
                  <span class="label">/100</span>
                </div>
              </div>
              <div class="gauge-labels">
                <span class="safe">안전</span>
                <span class="warning">주의</span>
                <span class="danger">위험</span>
              </div>
            </div>

            <!-- 매수 금지 경고 -->
            <div v-if="riskInfo?.riskStatus === 'DANGER'" class="danger-warning">
              <span class="warning-icon">🚨</span>
              <div class="warning-text">
                <strong>매수 주의</strong>
                <p>리스크가 높아 신중한 판단이 필요합니다.</p>
              </div>
            </div>
          </div>

          <!-- AI 매매 전략 -->
          <div class="ai-strategy-section">
            <h2>AI 매매 전략</h2>
            <div class="strategy-box" :class="aiRecommendationClass">
              <div class="strategy-header">
                <span class="strategy-signal">{{ aiAnalysis?.technicalSignal || '-' }}</span>
                <span class="strategy-rec">{{ aiAnalysis?.recommendation || '-' }}</span>
              </div>
              <p class="strategy-text">{{ aiAnalysis?.strategy || '-' }}</p>

              <div class="reasons-section" v-if="aiAnalysis?.buyReasons?.length || aiAnalysis?.sellReasons?.length">
                <div class="buy-reasons" v-if="aiAnalysis?.buyReasons?.length">
                  <h4>매수 근거</h4>
                  <ul>
                    <li v-for="(reason, i) in aiAnalysis.buyReasons.slice(0, 3)" :key="'buy-'+i">{{ reason }}</li>
                  </ul>
                </div>
                <div class="sell-reasons" v-if="aiAnalysis?.sellReasons?.length">
                  <h4>매도 근거</h4>
                  <ul>
                    <li v-for="(reason, i) in aiAnalysis.sellReasons.slice(0, 3)" :key="'sell-'+i">{{ reason }}</li>
                  </ul>
                </div>
              </div>
            </div>
          </div>
        </div>

      </div>
    </div>

    <!-- 데이터 없음 -->
    <div v-else class="empty-state">
      <div class="empty-icon">📈</div>
      <h2>종합 트레이딩 대시보드</h2>
      <p>종목명 또는 종목코드를 입력하면<br/>차트, 수급, 리스크, AI 분석을 한눈에 보여드립니다.</p>
      <div class="feature-badges">
        <span class="badge">실시간 체결강도</span>
        <span class="badge">AI 리스크 분석</span>
        <span class="badge">매매 전략</span>
      </div>
    </div>

    <!-- 면책조항 -->
    <footer class="disclaimer" v-if="hasData">
      <p>본 분석은 AI 알고리즘에 의한 참고 자료이며, 투자 결정은 본인의 판단과 책임 하에 이루어져야 합니다.</p>
    </footer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRoute } from 'vue-router';
import BackButton from '../components/BackButton.vue';
import VolumePowerGauge from '../components/VolumePowerGauge.vue';
import { stockDetailAPI, stockAPI } from '../utils/api';

const route = useRoute();

// 상태
const loading = ref(false);
const searchQuery = ref('');
const stockCode = ref('');
const stockName = ref('');
const priceInfo = ref(null);
const supplyDemand = ref(null);
const financial = ref(null);
const riskInfo = ref(null);
const aiAnalysis = ref(null);
const chartData = ref(null);

// 실시간 갱신
const autoRefresh = ref(true);
const lastUpdated = ref(null);
let refreshInterval = null;

const hasData = computed(() => priceInfo.value !== null);

// 종목명 → 코드 매핑 (API 검색 실패 시 폴백)
const STOCK_MAP = {
  // 대형주
  '삼성전자': '005930',
  'SK하이닉스': '000660',
  'LG에너지솔루션': '373220',
  '삼성바이오로직스': '207940',
  '현대차': '005380',
  '현대자동차': '005380',
  '기아': '000270',
  'NAVER': '035420',
  '네이버': '035420',
  '카카오': '035720',
  'LG화학': '051910',
  '삼성SDI': '006400',
  'POSCO홀딩스': '005490',
  '포스코홀딩스': '005490',
  'KB금융': '105560',
  '신한지주': '055550',
  '하이브': '352820',
  'LG전자': '066570',
  '삼성물산': '028260',
  '삼성생명': '032830',
  '현대모비스': '012330',
  // 인기 테마주
  '에코프로': '086520',
  '에코프로비엠': '247540',
  '포스코퓨처엠': '003670',
  '엘앤에프': '066970',
  '두산에너빌리티': '034020',
  '한화에어로스페이스': '012450',
  'HLB': '028300',
  '셀트리온': '068270',
  '알테오젠': '196170',
  'SK이노베이션': '096770',
  '카카오뱅크': '323410',
  '크래프톤': '259960',
  '삼성엔지니어링': '028050',
  '한화오션': '042660',
  '두산밥캣': '241560',
  '한미반도체': '042700',
  '리노공업': '058470',
  '레인보우로보틱스': '277810',
  // 건설/엔지니어링
  'GS건설': '006360',
  '현대건설': '000720',
  '대우건설': '047040',
  'DL이앤씨': '375500',
  'HDC현대산업개발': '294870',
  '삼성엔지니어링': '028050',
  'GS': '078930',
  // 금융
  '하나금융지주': '086790',
  '우리금융지주': '316140',
  '메리츠금융지주': '138040',
  'NH투자증권': '005940',
  '한국금융지주': '071050',
  '미래에셋증권': '006800',
  '삼성증권': '016360',
  '키움증권': '039490',
  // 철강/화학
  '현대제철': '004020',
  'POSCO': '005490',
  '한화솔루션': '009830',
  'SKC': '011790',
  '롯데케미칼': '011170',
  '금호석유': '011780',
  // 통신/미디어
  'SK텔레콤': '017670',
  'KT': '030200',
  'LG유플러스': '032640',
  'CJ ENM': '035760',
  '스튜디오드래곤': '253450',
  // 유통/소비재
  '신세계': '004170',
  '롯데쇼핑': '023530',
  '이마트': '139480',
  'BGF리테일': '282330',
  'CJ제일제당': '097950',
  '오리온': '271560',
  '아모레퍼시픽': '090430',
  'LG생활건강': '051900',
  // 제약/바이오
  '삼성바이오로직스': '207940',
  '셀트리온헬스케어': '091990',
  '유한양행': '000100',
  '녹십자': '006280',
  '한미약품': '128940',
  'SK바이오팜': '326030',
  // 자동차/부품
  '한온시스템': '018880',
  '현대위아': '011210',
  '만도': '204320',
  'HL만도': '204320',
  // 기타 인기 종목
  '삼성화재': '000810',
  '현대해상': '001450',
  '한화': '000880',
  'SK': '034730',
  'LG': '003550',
  '호텔신라': '008770',
  '대한항공': '003490',
  '아시아나항공': '020560',
  'HMM': '011200',
  '팬오션': '028670',
  '코스모신소재': '005070',
  '포스코인터내셔널': '047050',
};

// 코드 → 종목명 매핑 (역방향)
const CODE_TO_NAME = Object.fromEntries(
  Object.entries(STOCK_MAP).map(([name, code]) => [code, name])
);

// 차트 표시용 (최근 30개)
const displayCandles = computed(() => {
  if (!chartData.value?.candles) return [];
  return chartData.value.candles.slice(0, 30).reverse();
});

const displayVolumes = computed(() => {
  if (!chartData.value?.volumes) return [];
  return chartData.value.volumes.slice(0, 30).reverse();
});

// 스타일 클래스
const priceClass = computed(() => {
  if (!priceInfo.value) return '';
  const rate = Number(priceInfo.value.changeRate) || 0;
  if (rate > 0) return 'positive';
  if (rate < 0) return 'negative';
  return 'neutral';
});

const aiScoreClass = computed(() => {
  const score = aiAnalysis.value?.overallScore;
  if (!score) return '';
  if (score >= 70) return 'high';
  if (score >= 50) return 'medium';
  return 'low';
});

const riskStatusClass = computed(() => {
  const status = riskInfo.value?.riskStatus;
  if (status === 'SAFE') return 'safe';
  if (status === 'WARNING') return 'warning';
  if (status === 'DANGER') return 'danger';
  return '';
});

const supplySourceClass = computed(() => {
  const source = supplyDemand.value?.dataSource;
  if (source === '실시간') return 'live';
  if (source === '일별(DB)') return 'daily';
  if (source === '장전(초기화)') return 'pre-market';
  return '';
});

const aiRecommendationClass = computed(() => {
  const rec = aiAnalysis.value?.recommendation;
  if (rec === 'BUY') return 'buy';
  if (rec === 'SELL') return 'sell';
  return 'hold';
});

// 리스크 게이지 계산
const gaugeArcLength = computed(() => 251.2);
const gaugeDashOffset = computed(() => {
  if (!riskInfo.value?.riskScore) return gaugeArcLength.value;
  const progress = riskInfo.value.riskScore / 100;
  return gaugeArcLength.value * (1 - progress);
});

// 막대 차트 너비 계산
const getBarWidth = (value) => {
  if (!value || !supplyDemand.value) return 0;
  const maxVal = Math.max(
    Math.abs(supplyDemand.value.foreignNetBuy || 0),
    Math.abs(supplyDemand.value.instNetBuy || 0),
    Math.abs(supplyDemand.value.programNetBuy || 0),
    1
  );
  return Math.min((Math.abs(value) / maxVal) * 100, 100);
};

// 종목 검색
const searchStock = async () => {
  if (!searchQuery.value.trim()) return;

  loading.value = true;
  stopAutoRefresh();

  try {
    let code = searchQuery.value.trim();
    let searchedName = null;

    // 6자리 숫자 코드가 아닌 경우 (종목명으로 검색)
    if (!/^\d{6}$/.test(code)) {
      // 1. 로컬 매핑에서 먼저 확인
      if (STOCK_MAP[code]) {
        searchedName = code;
        code = STOCK_MAP[code];
        stockName.value = searchedName;
        console.log('[StockDetail] 로컬 매핑 사용:', searchedName, '->', code);
      } else {
        // 2. API 검색 시도
        try {
          const searchResult = await stockAPI.searchStocks(code);
          if (searchResult.data.success && searchResult.data.data?.length > 0) {
            code = searchResult.data.data[0].stockCode;
            searchedName = searchResult.data.data[0].stockName;
            stockName.value = searchedName;
            console.log('[StockDetail] API 검색 성공:', searchedName, '->', code);
          } else {
            alert('종목을 찾을 수 없습니다. 정확한 종목명이나 6자리 코드를 입력해주세요.');
            loading.value = false;
            return;
          }
        } catch (apiError) {
          console.warn('[StockDetail] API 검색 실패:', apiError);
          alert('종목 검색에 실패했습니다. 정확한 종목명이나 6자리 코드를 입력해주세요.');
          loading.value = false;
          return;
        }
      }
    } else {
      // 코드로 검색 시, 역방향 매핑으로 종목명 찾기
      searchedName = CODE_TO_NAME[code] || null;
      if (searchedName) {
        stockName.value = searchedName;
      }
    }

    stockCode.value = code;
    await fetchAllData(code, searchedName);

    // 자동 갱신 시작
    if (autoRefresh.value) {
      startAutoRefresh();
    }
  } catch (error) {
    console.error('종목 조회 오류:', error);
    alert('종목 조회에 실패했습니다.');
  } finally {
    loading.value = false;
  }
};

// 모든 데이터 가져오기
const fetchAllData = async (code, searchedName) => {
  try {
    const response = await stockDetailAPI.getSummary(code);
    if (response.data.success && response.data.data) {
      const data = response.data.data;

      // ★ 종목명 우선순위: 로컬매핑 > API응답 > 검색어 > 코드
      // API가 코드를 종목명으로 반환하는 경우 방지
      const localName = CODE_TO_NAME[code];
      const apiName = data.stockName && data.stockName !== code ? data.stockName : null;
      stockName.value = localName || apiName || searchedName || code;
      priceInfo.value = data.price;
      supplyDemand.value = data.supplyDemand;
      financial.value = data.financial;
      riskInfo.value = data.risk;
      aiAnalysis.value = data.aiAnalysis;
      chartData.value = data.chartData;

      lastUpdated.value = new Date();
    }
  } catch (error) {
    console.error('데이터 로드 오류:', error);
  }
};

// 실시간 갱신 (체결강도, 가격 등)
const refreshRealtimeData = async () => {
  if (!stockCode.value || !autoRefresh.value) return;

  try {
    const response = await stockDetailAPI.getSummary(stockCode.value);
    if (response.data.success && response.data.data) {
      const data = response.data.data;
      // 실시간 데이터만 갱신
      priceInfo.value = data.price;
      supplyDemand.value = data.supplyDemand;
      lastUpdated.value = new Date();

    }
  } catch (error) {
    console.error('실시간 갱신 오류:', error);
  }
};

const toggleAutoRefresh = () => {
  if (autoRefresh.value) {
    startAutoRefresh();
  } else {
    stopAutoRefresh();
  }
};

const startAutoRefresh = () => {
  if (refreshInterval) clearInterval(refreshInterval);
  refreshInterval = setInterval(refreshRealtimeData, 10000);
};

const stopAutoRefresh = () => {
  if (refreshInterval) {
    clearInterval(refreshInterval);
    refreshInterval = null;
  }
};

// 차트 스타일 계산
const chartPriceRange = computed(() => {
  if (!displayCandles.value.length) return { min: 0, max: 100 };
  const prices = displayCandles.value.flatMap(c => [c.high, c.low]);
  return {
    min: Math.min(...prices) * 0.98,
    max: Math.max(...prices) * 1.02
  };
});

const maxVolume = computed(() => {
  if (!displayVolumes.value.length) return 1;
  return Math.max(...displayVolumes.value.map(v => v.volume));
});

const getCandleStyle = (candle) => {
  const range = chartPriceRange.value;
  const height = ((Math.max(candle.open, candle.close) - Math.min(candle.open, candle.close)) / (range.max - range.min)) * 100;
  const bottom = ((Math.min(candle.open, candle.close) - range.min) / (range.max - range.min)) * 100;
  return { height: Math.max(height, 1) + '%', bottom: bottom + '%' };
};

const getWickStyle = (candle) => {
  const range = chartPriceRange.value;
  const height = ((candle.high - candle.low) / (range.max - range.min)) * 100;
  const bottom = ((candle.low - range.min) / (range.max - range.min)) * 100;
  return { height: height + '%', bottom: bottom + '%' };
};

const getBodyStyle = (candle) => {
  const range = chartPriceRange.value;
  const bodyTop = Math.max(candle.open, candle.close);
  const bodyBottom = Math.min(candle.open, candle.close);
  const height = ((bodyTop - bodyBottom) / (range.max - range.min)) * 100;
  const bottom = ((bodyBottom - range.min) / (range.max - range.min)) * 100;
  return { height: Math.max(height, 1) + '%', bottom: bottom + '%' };
};

const getVolumeHeight = (volume) => {
  return (volume / maxVolume.value) * 100;
};

// 포맷터
const formatPrice = (price) => {
  if (!price) return '-';
  return Number(price).toLocaleString('ko-KR');
};

const formatMarketCap = (cap) => {
  if (!cap) return '-';
  if (cap >= 10000) return (cap / 10000).toFixed(1) + '조';
  return cap.toLocaleString() + '억';
};

const formatTime = (date) => {
  if (!date) return '';
  return date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
};

const formatPubDate = (pubDate) => {
  if (!pubDate) return '';
  try {
    const date = new Date(pubDate);
    return `${date.getMonth() + 1}/${date.getDate()}`;
  } catch {
    return pubDate.substring(0, 10);
  }
};

const truncate = (str, len) => {
  if (!str) return '';
  return str.length > len ? str.substring(0, len) + '...' : str;
};

// 신호/상태 텍스트
const getRiskStatusText = (status) => {
  const map = { 'SAFE': '안전', 'WARNING': '주의', 'DANGER': '위험' };
  return map[status] || '-';
};

const getPERClass = (per) => {
  if (!per) return '';
  if (per > 0 && per < 10) return 'positive';
  if (per > 30) return 'negative';
  return '';
};

const getPBRClass = (pbr) => {
  if (!pbr) return '';
  if (pbr > 0 && pbr < 1) return 'positive';
  if (pbr > 3) return 'negative';
  return '';
};

// URL 파라미터 처리
onMounted(() => {
  const code = route.query.code || route.params.stockCode;
  if (code) {
    searchQuery.value = code;
    searchStock();
  }
});

onUnmounted(() => {
  stopAutoRefresh();
});
</script>

<style scoped>
.trading-dashboard {
  min-height: 100vh;
  background: linear-gradient(135deg, #0f0f23 0%, #1a1a3e 100%);
  color: #fff;
  padding: 20px;
}

/* Header */
.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px;
  background: rgba(30, 30, 60, 0.8);
  border-radius: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 16px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stock-info {
  display: flex;
  flex-direction: column;
}

.stock-name {
  font-size: 1.8rem;
  font-weight: 700;
  margin: 0;
}

.stock-code {
  color: #888;
  font-size: 0.9rem;
}

.price-info {
  text-align: center;
}

.current-price {
  font-size: 2rem;
  font-weight: 700;
  font-family: 'Monaco', monospace;
}

.change-info {
  display: block;
  font-size: 1.1rem;
  margin-top: 4px;
}

.change-info.positive { color: #ef4444; }
.change-info.negative { color: #3b82f6; }
.change-info.neutral { color: #9ca3af; }

.ai-score-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px 24px;
  background: rgba(50, 50, 80, 0.5);
  border-radius: 12px;
  border: 2px solid #4a4a7a;
}

.ai-score-box.high { border-color: #22c55e; }
.ai-score-box.medium { border-color: #eab308; }
.ai-score-box.low { border-color: #ef4444; }

.score-label { font-size: 0.8rem; color: #888; }
.score-value { font-size: 2.5rem; font-weight: 800; font-family: 'Monaco', monospace; }
.ai-score-box.high .score-value { color: #22c55e; }
.ai-score-box.medium .score-value { color: #eab308; }
.ai-score-box.low .score-value { color: #ef4444; }

.score-badge {
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 0.8rem;
  font-weight: 600;
  background: rgba(255,255,255,0.1);
}

/* Control Section */
.control-section {
  margin-bottom: 20px;
}

.search-bar {
  display: flex;
  gap: 12px;
  max-width: 600px;
  margin: 0 auto 12px;
}

.search-bar input {
  flex: 1;
  padding: 14px 20px;
  background: rgba(30, 30, 60, 0.8);
  border: 1px solid #3a3a6a;
  border-radius: 12px;
  color: #fff;
  font-size: 1rem;
}

.search-bar input::placeholder { color: #666; }

.search-bar button {
  padding: 14px 28px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border: none;
  border-radius: 12px;
  color: #fff;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.2s;
}

.search-bar button:hover:not(:disabled) { transform: scale(1.02); }
.search-bar button:disabled { opacity: 0.6; cursor: not-allowed; }

.realtime-status {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 10px 20px;
  background: rgba(30, 30, 60, 0.6);
  border-radius: 10px;
  max-width: 600px;
  margin: 0 auto;
  border: 1px solid #2a2a4a;
}

.realtime-status.active { border-color: #22c55e; }

.status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #71717a;
}

.status-dot.pulsing {
  background: #22c55e;
  animation: pulse-glow 1.5s ease-in-out infinite;
}

@keyframes pulse-glow {
  0%, 100% { opacity: 1; box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.7); }
  50% { opacity: 0.8; box-shadow: 0 0 0 6px rgba(34, 197, 94, 0); }
}

.status-text { font-size: 0.9rem; color: #aaa; }
.realtime-status.active .status-text { color: #22c55e; }

.auto-refresh-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #888;
  font-size: 0.85rem;
  cursor: pointer;
}

.auto-refresh-toggle input { width: 16px; height: 16px; cursor: pointer; }

.update-time { color: #666; font-size: 0.8rem; font-family: 'Monaco', monospace; }

/* Loading */
.loading-overlay {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  color: #888;
}

.loading-spinner {
  width: 50px;
  height: 50px;
  border: 4px solid #3a3a6a;
  border-top-color: #667eea;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin-bottom: 16px;
}

@keyframes spin { to { transform: rotate(360deg); } }

/* Main Grid - 2 Column */
.main-grid {
  display: grid;
  grid-template-columns: 1.3fr 1fr;
  gap: 20px;
}

@media (max-width: 1200px) {
  .main-grid { grid-template-columns: 1fr; }
}

/* Left Column */
.left-column {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* Right Column */
.right-column {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* Zones */
.zone {
  background: rgba(30, 30, 60, 0.6);
  border-radius: 16px;
  padding: 20px;
  border: 1px solid #2a2a5a;
}

.section-header, h2 {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  font-size: 1.1rem;
  font-weight: 600;
}

/* Chart Section */
.chart-section {
  background: rgba(30, 30, 60, 0.6);
  border-radius: 16px;
  padding: 20px;
  border: 1px solid #2a2a5a;
}

.ma-legend {
  display: flex;
  gap: 12px;
  font-size: 0.75rem;
}

.ma5 { color: #f59e0b; }
.ma20 { color: #3b82f6; }
.vwap { color: #a855f7; }

.candlestick-container {
  height: 220px;
  background: rgba(0,0,0,0.2);
  border-radius: 8px;
  padding: 10px;
  overflow: hidden;
}

.candlestick-chart {
  display: flex;
  justify-content: space-around;
  align-items: flex-end;
  height: 100%;
  position: relative;
}

.candle {
  width: 8px;
  position: relative;
}

.candle .wick {
  position: absolute;
  width: 1px;
  left: 50%;
  transform: translateX(-50%);
  background: currentColor;
}

.candle .body {
  position: absolute;
  width: 100%;
  border-radius: 1px;
}

.candle.up { color: #ef4444; }
.candle.up .body { background: #ef4444; }
.candle.down { color: #3b82f6; }
.candle.down .body { background: #3b82f6; }

.volume-chart {
  display: flex;
  justify-content: space-around;
  align-items: flex-end;
  height: 50px;
  margin-top: 8px;
  background: rgba(0,0,0,0.2);
  border-radius: 4px;
  padding: 4px;
}

.volume-bar {
  width: 8px;
  background: #4a4a8a;
  border-radius: 2px;
  transition: height 0.2s;
}

.volume-bar.up { background: rgba(239, 68, 68, 0.5); }

/* Program Chart Section */

/* Financial Section */
.financial-section {
  background: rgba(30, 30, 60, 0.6);
  border-radius: 16px;
  padding: 20px;
  border: 1px solid #2a2a5a;
}

.financial-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.fin-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: rgba(50, 50, 80, 0.3);
  border-radius: 8px;
}

.fin-card.wide { grid-column: span 2; }
.fin-label { color: #888; font-size: 0.85rem; }
.fin-value { font-weight: 600; font-family: 'Monaco', monospace; }
.fin-value.positive { color: #ef4444; }
.fin-value.negative { color: #3b82f6; }

/* Zone A - Investor Section */
.investor-section {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid #2a2a4a;
}

.supply-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.supply-header h3 {
  margin: 0;
  font-size: 1rem;
  color: #ccc;
}

.data-source-badge {
  padding: 3px 10px;
  border-radius: 10px;
  font-size: 0.7rem;
  font-weight: 600;
}

.data-source-badge.live {
  background: rgba(34, 197, 94, 0.2);
  color: #22c55e;
  border: 1px solid rgba(34, 197, 94, 0.4);
}

.data-source-badge.daily {
  background: rgba(59, 130, 246, 0.2);
  color: #60a5fa;
  border: 1px solid rgba(59, 130, 246, 0.4);
}

.data-source-badge.pre-market {
  background: rgba(156, 163, 175, 0.2);
  color: #9ca3af;
  border: 1px solid rgba(156, 163, 175, 0.4);
}

.investor-bar-chart {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.investor-bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 8px;
}

.investor-bar-row.highlight {
  background: linear-gradient(135deg, #1a1a3a 0%, #2a2a4a 100%);
  border: 1px solid #3a3a5a;
}

.bar-label {
  width: 70px;
  color: #aaa;
  font-size: 0.85rem;
  flex-shrink: 0;
}

.bar-container { flex: 1; }

.bar-track {
  height: 20px;
  background: #27272a;
  border-radius: 10px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: 10px;
  transition: width 0.5s ease;
}

.bar-fill.positive {
  background: linear-gradient(90deg, #ef4444 0%, #dc2626 100%);
  box-shadow: 0 0 8px rgba(239, 68, 68, 0.4);
}

.bar-fill.negative {
  background: linear-gradient(90deg, #3b82f6 0%, #2563eb 100%);
  box-shadow: 0 0 8px rgba(59, 130, 246, 0.4);
}

.bar-value {
  width: 65px;
  text-align: right;
  font-size: 1rem;
  font-weight: 700;
  font-family: 'Monaco', monospace;
  flex-shrink: 0;
}

.bar-value.positive { color: #ef4444; }
.bar-value.negative { color: #3b82f6; }

/* Zone B - Risk & AI */
.risk-gauge-section {
  padding: 16px;
  border-radius: 12px;
  background: linear-gradient(135deg, #1a1a3a 0%, #0f0f23 100%);
  border: 2px solid #2a2a4a;
  transition: all 0.3s ease;
}

.risk-gauge-section.safe { border-color: #22c55e; box-shadow: 0 0 20px rgba(34, 197, 94, 0.15); }
.risk-gauge-section.warning { border-color: #eab308; box-shadow: 0 0 20px rgba(234, 179, 8, 0.15); }
.risk-gauge-section.danger { border-color: #ef4444; box-shadow: 0 0 20px rgba(239, 68, 68, 0.2); }

.gauge-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.gauge-header h2 { margin: 0; font-size: 1rem; }

.risk-badge {
  padding: 4px 12px;
  border-radius: 16px;
  font-size: 0.8rem;
  font-weight: 700;
  text-transform: uppercase;
}

.risk-badge.safe { background: linear-gradient(135deg, #22c55e 0%, #16a34a 100%); color: #fff; }
.risk-badge.warning { background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%); color: #000; }
.risk-badge.danger { background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%); color: #fff; animation: blink-danger 1s ease-in-out infinite; }

@keyframes blink-danger {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.gauge-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 12px;
}

.gauge {
  position: relative;
  width: 160px;
  height: 100px;
}

.gauge-svg { width: 100%; height: 100%; }
.gauge-arc { transition: stroke-dashoffset 1s ease-out; }

.gauge-value {
  position: absolute;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  text-align: center;
}

.gauge-value .score {
  font-size: 2.2rem;
  font-weight: 800;
  font-family: 'Monaco', monospace;
}

.gauge-value .score.safe { color: #22c55e; }
.gauge-value .score.warning { color: #eab308; }
.gauge-value .score.danger { color: #ef4444; }
.gauge-value .label { font-size: 0.9rem; color: #666; }

.gauge-labels {
  display: flex;
  justify-content: space-between;
  width: 160px;
  margin-top: 6px;
}

.gauge-labels span { font-size: 0.7rem; font-weight: 600; }
.gauge-labels .safe { color: #22c55e; }
.gauge-labels .warning { color: #eab308; }
.gauge-labels .danger { color: #ef4444; }

.danger-warning {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px;
  background: rgba(239, 68, 68, 0.15);
  border: 1px solid rgba(239, 68, 68, 0.4);
  border-radius: 8px;
  margin-top: 12px;
}

.warning-icon { font-size: 1.2rem; flex-shrink: 0; }
.warning-text strong { color: #ef4444; font-size: 0.9rem; display: block; margin-bottom: 2px; }
.warning-text p { margin: 0; font-size: 0.8rem; color: #ff8a8a; }

/* AI Strategy */
.ai-strategy-section {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid #2a2a4a;
}

.ai-strategy-section h2 { font-size: 1rem; margin-bottom: 12px; }

.strategy-box {
  padding: 16px;
  border-radius: 10px;
  background: rgba(50, 50, 80, 0.5);
}

.strategy-box.buy { border: 2px solid #22c55e; }
.strategy-box.sell { border: 2px solid #ef4444; }
.strategy-box.hold { border: 2px solid #6b7280; }

.strategy-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.strategy-signal {
  padding: 4px 10px;
  background: rgba(255,255,255,0.1);
  border-radius: 6px;
  font-size: 0.8rem;
}

.strategy-rec { font-size: 1.1rem; font-weight: 700; }
.strategy-box.buy .strategy-rec { color: #22c55e; }
.strategy-box.sell .strategy-rec { color: #ef4444; }
.strategy-box.hold .strategy-rec { color: #6b7280; }

.strategy-text { font-size: 0.9rem; color: #ccc; line-height: 1.5; margin: 0; }

.reasons-section {
  margin-top: 12px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.buy-reasons, .sell-reasons {
  padding: 10px;
  border-radius: 6px;
  background: rgba(0,0,0,0.2);
}

.buy-reasons h4 { color: #22c55e; margin: 0 0 6px 0; font-size: 0.8rem; }
.sell-reasons h4 { color: #ef4444; margin: 0 0 6px 0; font-size: 0.8rem; }

.reasons-section ul { margin: 0; padding-left: 14px; }
.reasons-section li { font-size: 0.75rem; color: #aaa; margin-bottom: 3px; }

/* Left Column - News */
.news-section-left {
  background: rgba(30, 30, 60, 0.6);
  border-radius: 16px;
  padding: 20px;
  border: 1px solid #2a2a5a;
}

.news-section-left h2 { font-size: 1rem; }
.news-count { font-size: 0.85rem; color: #888; }

.news-list {
  max-height: 250px;
  overflow-y: auto;
  padding-right: 4px;
}

.news-list::-webkit-scrollbar { width: 4px; }
.news-list::-webkit-scrollbar-track { background: rgba(50, 50, 80, 0.3); border-radius: 2px; }
.news-list::-webkit-scrollbar-thumb { background: #4a4a8a; border-radius: 2px; }

.news-item {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 10px 0;
  border-bottom: 1px solid rgba(255,255,255,0.05);
}

.news-content {
  flex: 1;
  min-width: 0;
}

.news-item a {
  color: #a5b4fc;
  text-decoration: none;
  font-size: 0.85rem;
}

.news-desc {
  color: #888;
  font-size: 0.75rem;
  margin: 4px 0 0 0;
  line-height: 1.3;
}

.news-item a:hover { text-decoration: underline; }
.news-date { color: #666; font-size: 0.75rem; flex-shrink: 0; margin-left: 10px; white-space: nowrap; }

.no-news {
  padding: 20px;
  text-align: center;
  color: #666;
}

/* Empty State */
.empty-state {
  text-align: center;
  padding: 80px 20px;
  color: #666;
}

.empty-icon { font-size: 4rem; margin-bottom: 16px; }
.empty-state h2 { display: block; margin-bottom: 8px; color: #fff; }
.empty-state p { line-height: 1.6; margin-bottom: 20px; }

.feature-badges {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
}

.feature-badges .badge {
  padding: 8px 16px;
  background: rgba(102, 126, 234, 0.2);
  border: 1px solid rgba(102, 126, 234, 0.4);
  border-radius: 20px;
  font-size: 0.85rem;
  color: #a5b4fc;
}

/* Disclaimer */
.disclaimer {
  text-align: center;
  padding: 20px;
  margin-top: 20px;
  color: #666;
  font-size: 0.8rem;
}

/* Responsive */
@media (max-width: 768px) {
  .trading-dashboard { padding: 12px; }

  .dashboard-header {
    flex-direction: column;
    text-align: center;
    gap: 12px;
  }

  .header-left { flex-direction: column; }
  .stock-name { font-size: 1.4rem; }
  .current-price { font-size: 1.6rem; }

  .search-bar { flex-direction: column; }
  .realtime-status { flex-wrap: wrap; gap: 8px; }

  .reasons-section { grid-template-columns: 1fr; }
}
</style>
