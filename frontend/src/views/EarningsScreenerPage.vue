<template>
  <div class="screener-page">
    <LoadingSpinner v-if="loading" />
    <div v-else class="content-wrapper">
      <div class="page-header">
        <BackButton />
        <h1>실적 기반 저평가 스크리너</h1>
        <p class="subtitle">마법의 공식, PEG, 턴어라운드 종목 발굴</p>
      </div>

      <!-- 데이터 기준일 표시 (상단 고정) -->
      <div class="data-timestamp-bar">
        <div v-if="collectStatus?.lastUpdatedAt" class="timestamp-info">
          <span class="timestamp-icon">📅</span>
          <span class="timestamp-label">데이터 기준일:</span>
          <span class="timestamp-value">{{ formatDateTime(collectStatus.lastUpdatedAt) }}</span>
          <span class="data-count">({{ collectStatus.totalRecords?.toLocaleString() }}개 종목)</span>
        </div>
        <div v-else class="timestamp-info warning">
          <span class="timestamp-icon">⚠️</span>
          <span class="timestamp-label">데이터가 없습니다. 배치 수집을 기다려주세요.</span>
        </div>
        <span class="batch-info">자동 수집: 매일 08:30, 15:40</span>
      </div>

      <div class="screener-tabs">
        <!-- 일반 스크리너 탭 -->
        <button v-for="tab in tabs" :key="tab.value"
                :class="['tab-btn', { active: selectedTab === tab.value }]"
                @click="changeTab(tab.value)">
          {{ tab.icon }} {{ tab.label }}
        </button>

        <!-- 구분선 -->
        <span class="tab-divider" v-if="showAdminTab">|</span>

        <!-- 관리자 탭 (더블클릭으로 표시) -->
        <button v-for="tab in adminTabs" :key="tab.value"
                v-show="showAdminTab"
                :class="['tab-btn admin-tab', { active: selectedTab === tab.value }]"
                @click="changeTab(tab.value)">
          {{ tab.icon }} {{ tab.label }}
        </button>

        <!-- 관리자 모드 토글 (작은 버튼) -->
        <button class="admin-toggle-btn"
                @dblclick="showAdminTab = !showAdminTab"
                :title="showAdminTab ? '관리자 탭 숨기기 (더블클릭)' : '관리자 탭 표시 (더블클릭)'">
          {{ showAdminTab ? '🔓' : '🔒' }}
        </button>
      </div>

      <!-- 마법의 공식 탭 -->
      <div v-if="selectedTab === 'magic-formula'" class="tab-content">
        <div class="filter-bar">
          <div class="filter-item">
            <label>최소 시가총액</label>
            <select v-model="magicFormulaFilters.minMarketCap" @change="fetchMagicFormula">
              <option :value="null">전체</option>
              <option :value="100">100억 이상</option>
              <option :value="500">500억 이상</option>
              <option :value="1000">1,000억 이상</option>
              <option :value="5000">5,000억 이상</option>
              <option :value="10000">1조 이상</option>
            </select>
          </div>
          <div class="filter-item">
            <label>조회 개수</label>
            <select v-model="magicFormulaFilters.limit" @change="fetchMagicFormula">
              <option :value="20">20개</option>
              <option :value="30">30개</option>
              <option :value="50">50개</option>
              <option :value="100">100개</option>
            </select>
          </div>
          <button @click="fetchMagicFormula" class="refresh-btn">새로고침</button>
        </div>

        <div class="info-box">
          <strong>마법의 공식이란?</strong>
          <p>영업이익률(수익성) + ROE(자기자본수익률) + 저PER(저평가) 순위를 합산하여 저평가된 우량주를 찾는 전략입니다.</p>
        </div>

        <!-- AI 분석 섹션 -->
        <div class="ai-section">
          <button @click="fetchMagicFormulaAI" class="ai-btn" :disabled="aiLoading">
            <span class="ai-icon">🤖</span>
            {{ aiLoading ? 'Gemini 분석 중...' : 'Gemini AI 추천 받기' }}
          </button>
          <div v-if="magicFormulaAI" class="ai-result">
            <div class="ai-result-header">
              <span class="ai-badge">Gemini AI 분석</span>
            </div>
            <div class="ai-result-content" v-html="formatAIResponse(magicFormulaAI)"></div>
          </div>
        </div>

        <div v-if="magicFormulaStocks.length > 0" class="stocks-table-wrapper">
          <table class="stocks-table">
            <thead>
              <tr>
                <th>순위</th>
                <th>종목</th>
                <th>시장</th>
                <th>PER</th>
                <th>PBR</th>
                <th>ROE</th>
                <th>영업이익률</th>
                <th>시가총액</th>
                <th>종합점수</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="stock in magicFormulaStocks" :key="stock.stockCode">
                <td class="rank">{{ stock.magicFormulaRank }}</td>
                <td class="stock-info clickable" @click="openDiagnosis(stock.stockCode, stock.stockName)">
                  <span class="stock-name">{{ stock.stockName }}</span>
                  <span class="stock-code">{{ stock.stockCode }}</span>
                  <span class="detail-hint">클릭하여 상세 진단</span>
                </td>
                <td>{{ stock.market }}</td>
                <td class="metric-cell">
                  <span class="metric-value-bar" :style="getValueBarStyle(stock.per, 'per')"></span>
                  <span class="metric-text">{{ formatNumber(stock.per, 2) }}</span>
                  <span v-if="isGoodValue(stock.per, 'per')" class="good-badge">👍</span>
                </td>
                <td class="metric-cell">
                  <span class="metric-value-bar" :style="getValueBarStyle(stock.pbr, 'pbr')"></span>
                  <span class="metric-text">{{ formatNumber(stock.pbr, 2) }}</span>
                  <span v-if="isGoodValue(stock.pbr, 'pbr')" class="good-badge">👍</span>
                </td>
                <td class="metric-cell">
                  <span class="metric-value-bar" :style="getValueBarStyle(stock.roe, 'roe')"></span>
                  <span class="metric-text">{{ formatPercent(stock.roe) }}</span>
                  <span v-if="isGoodValue(stock.roe, 'roe')" class="good-badge">👍</span>
                </td>
                <td class="metric-cell">
                  <span class="metric-value-bar" :style="getValueBarStyle(stock.operatingMargin, 'margin')"></span>
                  <span class="metric-text">{{ formatPercent(stock.operatingMargin) }}</span>
                  <span v-if="isGoodValue(stock.operatingMargin, 'margin')" class="good-badge">👍</span>
                </td>
                <td>{{ formatMarketCap(stock.marketCap) }}</td>
                <td class="score">{{ stock.magicFormulaScore }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="no-data">
          <p>조건에 맞는 종목이 없습니다.</p>
        </div>
      </div>

      <!-- PEG 스크리너 탭 -->
      <div v-if="selectedTab === 'peg'" class="tab-content">
        <div class="filter-bar">
          <div class="filter-item">
            <label>최대 PEG</label>
            <select v-model="pegFilters.maxPeg" @change="fetchPegStocks">
              <option :value="0.5">0.5 이하</option>
              <option :value="0.7">0.7 이하</option>
              <option :value="1.0">1.0 이하</option>
              <option :value="1.5">1.5 이하</option>
              <option :value="2.0">2.0 이하</option>
            </select>
          </div>
          <div class="filter-item">
            <label>최소 EPS 성장률</label>
            <select v-model="pegFilters.minEpsGrowth" @change="fetchPegStocks">
              <option :value="5">5% 이상</option>
              <option :value="10">10% 이상</option>
              <option :value="15">15% 이상</option>
              <option :value="20">20% 이상</option>
              <option :value="30">30% 이상</option>
            </select>
          </div>
          <div class="filter-item">
            <label>조회 개수</label>
            <select v-model="pegFilters.limit" @change="fetchPegStocks">
              <option :value="20">20개</option>
              <option :value="30">30개</option>
              <option :value="50">50개</option>
            </select>
          </div>
          <button @click="fetchPegStocks" class="refresh-btn">새로고침</button>
        </div>

        <div class="info-box">
          <strong>PEG란?</strong>
          <p>PEG = PER / EPS성장률. PEG가 1 미만이면 성장률 대비 저평가된 종목으로 간주합니다. 피터 린치가 애용한 지표입니다.</p>
        </div>

        <!-- AI 분석 섹션 -->
        <div class="ai-section">
          <button @click="fetchPegAI" class="ai-btn" :disabled="aiLoading">
            <span class="ai-icon">🤖</span>
            {{ aiLoading ? 'Gemini 분석 중...' : 'Gemini AI 추천 받기' }}
          </button>
          <div v-if="pegAI" class="ai-result">
            <div class="ai-result-header">
              <span class="ai-badge">Gemini AI 분석</span>
            </div>
            <div class="ai-result-content" v-html="formatAIResponse(pegAI)"></div>
          </div>
        </div>

        <div v-if="pegStocks.length > 0" class="stocks-table-wrapper">
          <table class="stocks-table">
            <thead>
              <tr>
                <th>#</th>
                <th>종목</th>
                <th>시장</th>
                <th>PEG</th>
                <th>PER</th>
                <th>EPS 성장률</th>
                <th>ROE</th>
                <th>시가총액</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(stock, index) in pegStocks" :key="stock.stockCode">
                <td class="rank">{{ index + 1 }}</td>
                <td class="stock-info clickable" @click="openDiagnosis(stock.stockCode, stock.stockName)">
                  <span class="stock-name">{{ stock.stockName }}</span>
                  <span class="stock-code">{{ stock.stockCode }}</span>
                  <span class="detail-hint">클릭하여 상세 진단</span>
                </td>
                <td>{{ stock.market }}</td>
                <td :class="getPegClass(stock.peg)">{{ formatNumber(stock.peg, 2) }}</td>
                <td>{{ formatNumber(stock.per, 2) }}</td>
                <td class="positive">{{ formatPercent(stock.epsGrowth) }}</td>
                <td>{{ formatPercent(stock.roe) }}</td>
                <td>{{ formatMarketCap(stock.marketCap) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="no-data">
          <p>조건에 맞는 종목이 없습니다.</p>
        </div>
      </div>

      <!-- 턴어라운드 탭 -->
      <div v-if="selectedTab === 'turnaround'" class="tab-content">
        <div class="filter-bar">
          <div class="filter-item">
            <label>조회 개수</label>
            <select v-model="turnaroundFilters.limit" @change="fetchTurnaroundStocks">
              <option :value="20">20개</option>
              <option :value="30">30개</option>
              <option :value="50">50개</option>
            </select>
          </div>
          <button @click="fetchTurnaroundStocks" class="refresh-btn">새로고침</button>
        </div>

        <div class="info-box">
          <strong>턴어라운드 종목이란?</strong>
          <p>적자에서 흑자로 전환되었거나, 순이익이 50% 이상 급증한 종목입니다. 실적 개선 모멘텀이 기대됩니다.</p>
        </div>

        <!-- AI 분석 섹션 -->
        <div class="ai-section">
          <button @click="fetchTurnaroundAI" class="ai-btn" :disabled="aiLoading">
            <span class="ai-icon">🤖</span>
            {{ aiLoading ? 'Gemini 분석 중...' : 'Gemini AI 추천 받기' }}
          </button>
          <div v-if="turnaroundAI" class="ai-result">
            <div class="ai-result-header">
              <span class="ai-badge">Gemini AI 분석</span>
            </div>
            <div class="ai-result-content" v-html="formatAIResponse(turnaroundAI)"></div>
          </div>
        </div>

        <div v-if="turnaroundStocks.length > 0" class="stocks-grid">
          <div v-for="(stock, index) in turnaroundStocks" :key="stock.stockCode"
               :class="['stock-card', getTurnaroundClass(stock.turnaroundType)]">
            <div class="turnaround-badge" :class="getTurnaroundClass(stock.turnaroundType)">
              {{ getTurnaroundLabel(stock.turnaroundType) }}
            </div>

            <div class="stock-header clickable" @click="openDiagnosis(stock.stockCode, stock.stockName)">
              <div class="stock-info-col">
                <span class="stock-name">{{ stock.stockName }}</span>
                <span class="stock-code">{{ stock.stockCode }}</span>
                <span class="detail-hint">클릭하여 상세 진단</span>
              </div>
              <span class="rank-badge">#{{ index + 1 }}</span>
            </div>

            <div class="stock-details">
              <div class="detail-row highlight">
                <span class="label">이전 순이익</span>
                <span class="value" :class="getAmountClass(stock.previousNetIncome)">
                  {{ formatAmount(stock.previousNetIncome) }}
                </span>
              </div>
              <div class="detail-row highlight">
                <span class="label">현재 순이익</span>
                <span class="value" :class="getAmountClass(stock.currentNetIncome)">
                  {{ formatAmount(stock.currentNetIncome) }}
                </span>
              </div>
              <div class="detail-row" v-if="stock.turnaroundType !== 'LOSS_TO_PROFIT'">
                <span class="label">변화율</span>
                <span class="value positive">+{{ formatPercent(stock.netIncomeChangeRate) }}</span>
              </div>
              <!-- PER이 유효하면 PER 표시, 아니면 PBR 표시 -->
              <div class="detail-row" v-if="isValidPer(stock.per)">
                <span class="label">PER</span>
                <span class="value">{{ formatNumber(stock.per, 2) }}배</span>
              </div>
              <div class="detail-row" v-else>
                <span class="label">PBR</span>
                <span class="value" :class="{ 'value-highlight': stock.pbr && stock.pbr < 1 }">
                  {{ stock.pbr ? formatNumber(stock.pbr, 2) + '배' : '-' }}
                </span>
              </div>
              <div class="detail-row">
                <span class="label">시가총액</span>
                <span class="value">{{ formatMarketCapSafe(stock) }}</span>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="no-data">
          <p>턴어라운드 종목이 없습니다.</p>
        </div>
      </div>

      <!-- 데이터 관리 탭 -->
      <div v-if="selectedTab === 'data-management'" class="tab-content">

        <!-- 원버튼 전체 수집 카드 -->
        <div class="action-card primary-action">
          <div class="action-header">
            <span class="action-icon">🚀</span>
            <h4>원버튼 전체 데이터 수집</h4>
            <span class="recommended-badge">추천</span>
          </div>
          <p class="action-desc">
            <strong>모든 데이터를 한 번에 수집합니다:</strong><br>
            1️⃣ 기본 재무 데이터 → 2️⃣ 영업이익률 → 3️⃣ 분기별 재무제표 → 4️⃣ 성장률 계산 (PEG용)
          </p>
          <div class="action-info">
            <span class="info-tag highlight">⏱️ 총 30-40분 소요</span>
            <span class="info-tag">📈 2,000+ 종목</span>
            <span class="info-tag">✨ 마법의 공식 + PEG 스크리너</span>
          </div>
          <button
            @click="collectAllInOne"
            class="action-btn primary large"
            :disabled="isCollectingAll"
          >
            <span v-if="isCollectingAll" class="spinner"></span>
            {{ isCollectingAll ? collectAllProgress : '🚀 전체 데이터 수집 시작' }}
          </button>
          <div v-if="collectAllResult" class="collect-result">
            <pre>{{ JSON.stringify(collectAllResult, null, 2) }}</pre>
          </div>
        </div>

        <!-- 수집 상태 카드 -->
        <div class="status-card">
          <div class="status-header">
            <h3>📊 수집 현황</h3>
            <button @click="fetchCollectStatus" class="refresh-btn small">새로고침</button>
          </div>
          <div v-if="collectStatus" class="status-grid">
            <div class="status-item">
              <span class="status-label">전체 데이터</span>
              <span class="status-value">{{ collectStatus.totalRecords?.toLocaleString() || 0 }}건</span>
            </div>
            <div class="status-item">
              <span class="status-label">영업이익률 있음</span>
              <span class="status-value positive">{{ collectStatus.withOperatingMargin?.toLocaleString() || 0 }}건</span>
            </div>
            <div class="status-item">
              <span class="status-label">영업이익률 없음</span>
              <span class="status-value warning-text">{{ collectStatus.missingOperatingMargin?.toLocaleString() || 0 }}건</span>
            </div>
            <div class="status-item">
              <span class="status-label">성장률 데이터 (PEG용)</span>
              <span class="status-value" :class="collectStatus.withGrowthData > 0 ? 'positive' : 'warning-text'">
                {{ collectStatus.withGrowthData?.toLocaleString() || 0 }}건
              </span>
            </div>
          </div>

          <!-- 마지막 자동 수집 결과 -->
          <div v-if="collectStatus?.lastAutoCollect?.lastCollectTime" class="auto-collect-status">
            <div class="auto-collect-header">
              <span class="auto-collect-icon">🤖</span>
              <span class="auto-collect-title">자동 수집 (매일 08:30, 15:40)</span>
            </div>
            <div class="auto-collect-result" :class="collectStatus.lastAutoCollect.success ? 'success' : 'fail'">
              <span class="result-icon">{{ collectStatus.lastAutoCollect.success ? '✅' : '❌' }}</span>
              <span class="result-time">{{ formatDateTime(collectStatus.lastAutoCollect.lastCollectTime) }}</span>
              <span class="result-status">{{ collectStatus.lastAutoCollect.success ? '성공' : '실패' }}</span>
            </div>
            <div v-if="collectStatus.lastAutoCollect.message" class="auto-collect-message">
              {{ collectStatus.lastAutoCollect.message }}
            </div>
          </div>
          <div v-else-if="collectStatus" class="auto-collect-status pending">
            <div class="auto-collect-header">
              <span class="auto-collect-icon">🤖</span>
              <span class="auto-collect-title">자동 수집 (매일 08:30, 15:40)</span>
            </div>
            <div class="auto-collect-message">
              아직 자동 수집이 실행되지 않았습니다. 서버 재시작 후 첫 스케줄 시간에 자동으로 실행됩니다.
            </div>
          </div>

          <div v-if="!collectStatus" class="status-loading">
            상태 조회 중...
          </div>
        </div>

        <!-- 기존 진행 상황 (간단 로그) -->
        <div v-if="collectProgress && !showProgressBar" class="progress-log">
          <div class="progress-header">
            <span>📋 진행 상황</span>
          </div>
          <div class="progress-content">
            {{ collectProgress }}
          </div>
        </div>

        <!-- 실시간 프로그레스 바 (SSE 연동) -->
        <div v-if="showProgressBar" class="sse-progress-panel">
          <div class="sse-progress-header">
            <span class="sse-title">📡 실시간 진행 상황</span>
            <button @click="closeProgressBar" class="close-btn small">&times;</button>
          </div>

          <!-- 프로그레스 바 -->
          <div class="progress-bar-container">
            <div class="progress-bar">
              <div class="progress-fill" :style="{ width: sseProgress.percent + '%' }"></div>
            </div>
            <div class="progress-stats">
              <span class="progress-percent">{{ sseProgress.percent }}%</span>
              <span class="progress-counts" v-if="sseProgress.total > 0">
                ({{ sseProgress.current }} / {{ sseProgress.total }})
              </span>
            </div>
          </div>

          <!-- 진행 정보 -->
          <div class="progress-info">
            <div class="info-row">
              <span class="info-label">상태:</span>
              <span class="info-value">{{ sseProgress.message }}</span>
            </div>
            <div class="info-row stats" v-if="sseProgress.success > 0 || sseProgress.fail > 0">
              <span class="stat-item success">성공: {{ sseProgress.success }}</span>
              <span class="stat-item fail">실패: {{ sseProgress.fail }}</span>
            </div>
          </div>

          <!-- 로그 창 -->
          <div class="log-container">
            <div class="log-header">
              <span>로그</span>
              <span class="log-count">({{ sseProgress.logs.length }})</span>
            </div>
            <div class="log-list">
              <div
                v-for="(log, idx) in sseProgress.logs.slice(0, 20)"
                :key="idx"
                :class="['log-item', log.level.toLowerCase()]"
              >
                <span class="log-time">{{ log.timestamp }}</span>
                <span class="log-level">{{ log.level }}</span>
                <span class="log-message">{{ log.message }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 종목 상세 진단 모달 -->
    <div v-if="showDiagnosisModal" class="modal-overlay" @click.self="closeDiagnosis">
      <div class="diagnosis-modal">
        <div class="modal-header">
          <h2>
            <span class="modal-icon">🔍</span>
            {{ diagnosisData?.stockName || selectedStockCode }} 상세 진단
          </h2>
          <button class="close-btn" @click="closeDiagnosis">&times;</button>
        </div>

        <div v-if="diagnosisLoading" class="modal-loading">
          <div class="spinner large"></div>
          <p>진단 중...</p>
        </div>

        <div v-else-if="diagnosisData" class="modal-content">
          <!-- 종합 의견 -->
          <div class="verdict-section" :class="getAdjustedVerdictClass(diagnosisData)">
            <div class="verdict-header">
              <span class="verdict-icon">{{ getAdjustedVerdictIcon(diagnosisData) }}</span>
              <div class="verdict-info">
                <span class="verdict-label">{{ getAdjustedVerdict(diagnosisData) }}</span>
                <span class="verdict-score">종합 점수: {{ diagnosisData.overallScore }}점</span>
                <!-- RSI 과열 시 추가 경고 -->
                <span v-if="isRsiOverbought(diagnosisData)" class="verdict-caution-tag">
                  ⚠️ RSI 과열 - 단기 조정 주의
                </span>
              </div>
            </div>
          </div>

          <!-- 경고/긍정 요소 -->
          <div class="alerts-section">
            <div v-if="diagnosisData.warnings?.length" class="alert-box warning">
              <div class="alert-header">⚠️ 주의 사항</div>
              <ul>
                <li v-for="(warning, idx) in diagnosisData.warnings" :key="idx">{{ warning }}</li>
              </ul>
            </div>
            <div v-if="diagnosisData.positives?.length" class="alert-box positive">
              <div class="alert-header">✅ 긍정적 요소</div>
              <ul>
                <li v-for="(positive, idx) in diagnosisData.positives" :key="idx">{{ positive }}</li>
              </ul>
            </div>
          </div>

          <!-- 3가지 분석 섹션 -->
          <div class="analysis-grid">
            <!-- 1. 재무 건전성 -->
            <div class="analysis-card">
              <div class="card-header">
                <span class="card-icon">💰</span>
                <h3>재무 건전성</h3>
                <span class="card-score" :class="getScoreClass(diagnosisData.financialHealth?.score)">
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
                  <span class="metric-label">이익 차이</span>
                  <span class="metric-value" :class="{ 'warning-text': diagnosisData.financialHealth.hasOneTimeGainWarning }">
                    {{ formatPercent(diagnosisData.financialHealth.profitGapRatio) }}
                  </span>
                </div>
                <div v-if="diagnosisData.financialHealth.hasOneTimeGainWarning" class="one-time-warning">
                  ⚠️ {{ diagnosisData.financialHealth.oneTimeGainReason }}
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

            <!-- 2. 수급 현황 -->
            <div class="analysis-card">
              <div class="card-header">
                <span class="card-icon">📊</span>
                <h3>수급 현황 (5일)</h3>
                <span class="card-score" :class="getScoreClass(diagnosisData.supplyDemand?.score)">
                  {{ diagnosisData.supplyDemand?.score || 0 }}점
                </span>
              </div>
              <div class="card-body" v-if="diagnosisData.supplyDemand">
                <!-- 장 시작 전 안내 -->
                <div v-if="isBeforeMarketOpen()" class="before-market-notice">
                  ⏰ 장 시작 전입니다. 전일 기준 데이터입니다.
                </div>
                <div class="supply-row">
                  <span class="investor-type">외국인</span>
                  <span v-if="isSupplyDataAvailable(diagnosisData.supplyDemand.foreignNet5Days)"
                        class="net-amount" :class="getSupplyDemandClass(diagnosisData.supplyDemand.foreignNet5Days)">
                    {{ getSupplyDemandLabel(diagnosisData.supplyDemand.foreignNet5Days) }}
                    {{ formatBillionAbs(diagnosisData.supplyDemand.foreignNet5Days) }}
                  </span>
                  <span v-else class="net-amount no-data">집계 중</span>
                  <span class="buy-days" v-if="isSupplyPositive(diagnosisData.supplyDemand.foreignNet5Days)">
                    ({{ diagnosisData.supplyDemand.foreignBuyDays }}일 순매수)
                  </span>
                  <span class="sell-days" v-else-if="isSupplyNegative(diagnosisData.supplyDemand.foreignNet5Days)">
                    (연속 순매도 주의!)
                  </span>
                </div>
                <div class="supply-row">
                  <span class="investor-type">기관</span>
                  <span v-if="isSupplyDataAvailable(diagnosisData.supplyDemand.institutionNet5Days)"
                        class="net-amount" :class="getSupplyDemandClass(diagnosisData.supplyDemand.institutionNet5Days)">
                    {{ getSupplyDemandLabel(diagnosisData.supplyDemand.institutionNet5Days) }}
                    {{ formatBillionAbs(diagnosisData.supplyDemand.institutionNet5Days) }}
                  </span>
                  <span v-else class="net-amount no-data">집계 중</span>
                  <span class="buy-days" v-if="isSupplyPositive(diagnosisData.supplyDemand.institutionNet5Days)">
                    ({{ diagnosisData.supplyDemand.institutionBuyDays }}일 순매수)
                  </span>
                  <span class="sell-days" v-else-if="isSupplyNegative(diagnosisData.supplyDemand.institutionNet5Days)">
                    (연속 순매도 주의!)
                  </span>
                </div>
                <div class="supply-summary">
                  <span v-if="diagnosisData.supplyDemand.isBothBuying" class="both-buying">🔥 외국인+기관 동반 매수!</span>
                  <span v-else-if="diagnosisData.supplyDemand.isBothSelling" class="both-selling">❄️ 외국인+기관 동반 매도 주의!</span>
                </div>
                <div class="card-assessment">{{ diagnosisData.supplyDemand.assessment }}</div>
              </div>
            </div>

            <!-- 3. 기술적 분석 -->
            <div class="analysis-card">
              <div class="card-header">
                <span class="card-icon">📈</span>
                <h3>기술적 분석</h3>
                <span class="card-score" :class="getScoreClass(diagnosisData.technicalAnalysis?.score)">
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
                        {{ diagnosisData.technicalAnalysis.isArrangedUp ? '✅ 정배열' : '❌ 아님' }}
                      </span>
                    </div>
                    <div class="indicator-item">
                      <span class="indicator-label">20일선 위치</span>
                      <span class="indicator-status" :class="{ active: diagnosisData.technicalAnalysis.isAboveMa20 }">
                        {{ diagnosisData.technicalAnalysis.isAboveMa20 ? '✅ 20일선 위' : '❌ 20일선 아래' }}
                      </span>
                    </div>
                    <div class="indicator-item">
                      <span class="indicator-label">골든/데드크로스</span>
                      <span class="indicator-status">
                        <span v-if="diagnosisData.technicalAnalysis.isGoldenCross" class="golden">🌟 골든크로스</span>
                        <span v-else-if="diagnosisData.technicalAnalysis.isDeadCross" class="dead">💀 데드크로스</span>
                        <span v-else>-</span>
                      </span>
                    </div>
                    <div class="indicator-item">
                      <span class="indicator-label">RSI (14일)</span>
                      <span class="indicator-status" :class="getRsiClass(diagnosisData.technicalAnalysis.rsiStatus)">
                        {{ formatNumber(diagnosisData.technicalAnalysis.rsi14, 1) }}
                        <span class="rsi-badge" :class="getRsiBadgeClass(diagnosisData.technicalAnalysis.rsiStatus)">
                          {{ getRsiStatusLabel(diagnosisData.technicalAnalysis.rsiStatus) }}
                        </span>
                      </span>
                    </div>
                    <div v-if="diagnosisData.technicalAnalysis.rsiStatus === '과열'" class="rsi-warning">
                      ⚠️ 단기 조정 주의 - RSI 과열 구간
                    </div>
                  </div>
                </div>

                <!-- 볼린저 밴드 -->
                <div class="tech-section" v-if="diagnosisData.technicalAnalysis.upperBand">
                  <div class="tech-section-title">📊 볼린저 밴드</div>
                  <div class="tech-indicators">
                    <div class="indicator-item">
                      <span class="indicator-label">상단</span>
                      <span class="indicator-value">{{ formatPrice(diagnosisData.technicalAnalysis.upperBand) }}</span>
                    </div>
                    <div class="indicator-item">
                      <span class="indicator-label">중단 (20SMA)</span>
                      <span class="indicator-value">{{ formatPrice(diagnosisData.technicalAnalysis.middleBand) }}</span>
                    </div>
                    <div class="indicator-item">
                      <span class="indicator-label">하단</span>
                      <span class="indicator-value">{{ formatPrice(diagnosisData.technicalAnalysis.lowerBand) }}</span>
                    </div>
                    <div class="indicator-item">
                      <span class="indicator-label">밴드폭</span>
                      <span class="indicator-value">{{ formatNumber(diagnosisData.technicalAnalysis.bandWidth, 2) }}%</span>
                    </div>
                    <div class="indicator-item">
                      <span class="indicator-label">스퀴즈</span>
                      <span class="indicator-status" :class="{ active: diagnosisData.technicalAnalysis.isSqueeze, squeeze: diagnosisData.technicalAnalysis.isSqueeze }">
                        {{ diagnosisData.technicalAnalysis.isSqueeze ? '🔥 에너지 응축!' : '정상' }}
                      </span>
                    </div>
                    <div class="indicator-item">
                      <span class="indicator-label">상단 돌파</span>
                      <span class="indicator-status" :class="{ active: diagnosisData.technicalAnalysis.isBreakout, breakout: diagnosisData.technicalAnalysis.isBreakout }">
                        {{ diagnosisData.technicalAnalysis.isBreakout ? '🚀 돌파!' : '-' }}
                      </span>
                    </div>
                  </div>
                </div>

                <!-- MFI -->
                <div class="tech-section" v-if="diagnosisData.technicalAnalysis.mfiScore !== null">
                  <div class="tech-section-title">💰 MFI (자금 흐름 지수)</div>
                  <div class="tech-indicators">
                    <div class="indicator-item wide">
                      <span class="indicator-label">MFI (14일)</span>
                      <span class="indicator-status" :class="getMfiClass(diagnosisData.technicalAnalysis.mfiStatus)">
                        {{ formatNumber(diagnosisData.technicalAnalysis.mfiScore, 1) }} ({{ diagnosisData.technicalAnalysis.mfiStatus || '중립' }})
                      </span>
                    </div>
                    <div class="mfi-description">
                      <span v-if="diagnosisData.technicalAnalysis.mfiStatus === '과열'">⚠️ 거래량 동반 매수세 과열 - 차익실현 고려</span>
                      <span v-else-if="diagnosisData.technicalAnalysis.mfiStatus === '침체'">💡 거래량 동반 매수 기회 - 진짜 바닥일 수 있음</span>
                      <span v-else>거래량이 실린 정상적인 흐름</span>
                    </div>
                  </div>
                </div>

                <div class="tech-signal">
                  <span class="signal-label">종합 신호:</span>
                  <span class="signal-value">{{ diagnosisData.technicalAnalysis.signalDescription || diagnosisData.technicalAnalysis.overallSignal }}</span>
                </div>
                <div class="card-assessment">{{ diagnosisData.technicalAnalysis.assessment }}</div>
              </div>
            </div>
          </div>
        </div>

        <div v-else class="modal-error">
          <p>진단 데이터를 불러올 수 없습니다.</p>
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
const selectedTab = ref('magic-formula');

// 일반 사용자용 탭 (스크리너)
const tabs = [
  { value: 'magic-formula', label: '마법의 공식', icon: '✨' },
  { value: 'peg', label: 'PEG 스크리너', icon: '📈' },
  { value: 'turnaround', label: '턴어라운드', icon: '🔄' }
];

// 관리자용 탭 (데이터 수집은 관리자만 접근)
const adminTabs = [
  { value: 'data-management', label: '관리자', icon: '🔧', admin: true }
];

// 관리자 모드 (더블클릭으로 활성화)
const showAdminTab = ref(false);

// 데이터
const magicFormulaStocks = ref([]);
const pegStocks = ref([]);
const turnaroundStocks = ref([]);

// 데이터 수집 상태
const collectStatus = ref(null);
const isCollecting = ref(false);
const magicFormulaCollecting = ref(false);
const isCrawling = ref(false);
const isCollectingAll = ref(false);
const collectAllProgress = ref('');
const collectAllResult = ref(null);
const isFixingNames = ref(false);
const isCollectingQuarterly = ref(false);
const isCollectingSingleQuarterly = ref(false);
const quarterlyStockCode = ref('');
const quarterlyResult = ref(null);
const collectProgress = ref('');

// SSE 실시간 진행률
const sseConnection = ref(null);
const sseProgress = ref({
  percent: 0,
  current: 0,
  total: 0,
  success: 0,
  fail: 0,
  message: '',
  logs: []
});
const showProgressBar = ref(false);

// AI 분석 결과
const aiLoading = ref(false);
const magicFormulaAI = ref('');
const pegAI = ref('');
const turnaroundAI = ref('');

// 종목 상세 진단 모달
const showDiagnosisModal = ref(false);
const diagnosisLoading = ref(false);
const diagnosisData = ref(null);
const selectedStockCode = ref('');

// 필터
const magicFormulaFilters = ref({
  limit: 30,
  minMarketCap: null
});

const pegFilters = ref({
  maxPeg: 1.0,
  minEpsGrowth: 10,
  limit: 30
});

const turnaroundFilters = ref({
  limit: 30
});

// 크롤링 옵션
const crawlForceUpdate = ref(false);
const testStockCode = ref('');
const crawlPreview = ref(null);

const changeTab = (tab) => {
  selectedTab.value = tab;
  if (tab === 'magic-formula') {
    autoCollectMagicFormula();
  } else if (tab === 'peg' && pegStocks.value.length === 0) {
    fetchPegStocks();
  } else if (tab === 'turnaround' && turnaroundStocks.value.length === 0) {
    fetchTurnaroundStocks();
  } else if (tab === 'data-management') {
    fetchCollectStatus();
  }
};

// 마법의 공식 탭 진입 시 기존 데이터 바로 조회 (자동 수집 안함)
const autoCollectMagicFormula = async () => {
  // 기존 데이터 바로 조회 (수집은 스케줄러에서 자동 처리)
  await fetchMagicFormula();
  await fetchCollectStatus();
};

// ========== 데이터 수집 관련 함수 ==========

const fetchCollectStatus = async () => {
  try {
    const response = await api.get('/screener/collect-status');
    if (response.data.success) {
      collectStatus.value = response.data;
    }
  } catch (error) {
    console.error('수집 상태 조회 오류:', error);
  }
};

// 원버튼 전체 데이터 수집 (SSE 방식)
const collectAllInOne = async () => {
  if (isCollectingAll.value) return;

  if (!confirm('전체 데이터 수집을 시작하시겠습니까?\n\n' +
               '1단계: 기본 재무 데이터 (10-15분)\n' +
               '2단계: 영업이익률 크롤링 (15-20분)\n' +
               '3단계: 분기별 재무제표 (10-15분)\n' +
               '4단계: 성장률 계산 (즉시 완료)\n\n' +
               '총 약 30-40분 소요됩니다.\n' +
               '진행률이 실시간으로 표시됩니다.')) {
    return;
  }

  isCollectingAll.value = true;
  collectAllProgress.value = '🚀 전체 수집 시작 중...';
  collectAllResult.value = null;

  // SSE 구독 시작
  startSseSubscription('collect-all-in-one');

  try {
    // 비동기 API 호출 (즉시 반환)
    const response = await api.post('/screener/collect-all-in-one');

    if (!response.data.success) {
      collectAllProgress.value = '❌ 수집 시작 실패: ' + response.data.message;
      isCollectingAll.value = false;
      closeProgressBar();
    }
    // 성공 시 SSE를 통해 진행 상황이 업데이트됨
  } catch (error) {
    console.error('전체 데이터 수집 오류:', error);
    collectAllProgress.value = '❌ 수집 시작 오류';
    alert('수집 시작 중 오류가 발생했습니다: ' + (error.response?.data?.message || error.message));
    isCollectingAll.value = false;
    closeProgressBar();
  }
};

const collectAllFinancialData = async () => {
  if (isCollecting.value) return;

  if (!confirm('전 종목 재무 데이터 수집을 시작하시겠습니까?\n약 10-15분 소요됩니다.')) {
    return;
  }

  isCollecting.value = true;
  collectProgress.value = '재무 데이터 수집 시작...';

  try {
    const response = await api.post('/screener/collect-all', {}, { timeout: 120000 });
    if (response.data.success) {
      const data = response.data.data;
      collectProgress.value = `수집 완료! 총 ${data.total}개 종목 중 성공: ${data.successCount}, 실패: ${data.failCount} (소요시간: ${data.elapsedSeconds}초)`;
      await fetchCollectStatus();
    } else {
      collectProgress.value = '수집 실패: ' + response.data.message;
    }
  } catch (error) {
    console.error('재무 데이터 수집 오류:', error);
    collectProgress.value = '수집 중 오류 발생: ' + (error.response?.data?.message || error.message);
  } finally {
    isCollecting.value = false;
  }
};

// SSE 구독 시작
const startSseSubscription = (taskType) => {
  // 기존 연결 종료
  if (sseConnection.value) {
    sseConnection.value.close();
  }

  // 진행률 초기화
  sseProgress.value = {
    percent: 0,
    current: 0,
    total: 0,
    success: 0,
    fail: 0,
    message: '연결 중...',
    logs: []
  };
  showProgressBar.value = true;

  // SSE 연결 (API 베이스 URL 사용)
  const baseUrl = import.meta.env.VITE_API_BASE_URL || '';
  const sseUrl = `${baseUrl}/api/sse/subscribe?taskType=${taskType}&clientId=${Date.now()}`;

  const eventSource = new EventSource(sseUrl, { withCredentials: true });
  sseConnection.value = eventSource;

  eventSource.addEventListener('CONNECTED', (e) => {
    const data = JSON.parse(e.data);
    sseProgress.value.message = data.message;
    addLog('INFO', 'SSE 연결 성공');
  });

  eventSource.addEventListener('START', (e) => {
    const data = JSON.parse(e.data);
    sseProgress.value.total = data.totalCount;
    sseProgress.value.message = data.message;
    addLog('INFO', data.message);
  });

  eventSource.addEventListener('PROGRESS', (e) => {
    const data = JSON.parse(e.data);
    sseProgress.value.percent = data.percent;
    sseProgress.value.current = data.current || 0;
    sseProgress.value.total = data.total || sseProgress.value.total;
    sseProgress.value.success = data.success || 0;
    sseProgress.value.fail = data.fail || 0;
    sseProgress.value.message = data.message;
  });

  eventSource.addEventListener('LOG', (e) => {
    const data = JSON.parse(e.data);
    addLog(data.level, data.message);
  });

  eventSource.addEventListener('STEP', (e) => {
    const data = JSON.parse(e.data);
    sseProgress.value.percent = data.percent;
    sseProgress.value.current = data.step;
    sseProgress.value.total = data.totalSteps;
    sseProgress.value.message = data.message;
    collectAllProgress.value = data.message;
    addLog('INFO', `[단계 ${data.step}/${data.totalSteps}] ${data.message}`);
  });

  eventSource.addEventListener('COMPLETE', (e) => {
    const data = JSON.parse(e.data);
    sseProgress.value.percent = 100;
    sseProgress.value.message = data.message;
    addLog('SUCCESS', data.message);
    collectProgress.value = data.message;
    collectAllProgress.value = '✅ ' + data.message;
    eventSource.close();
    sseConnection.value = null;
    isCrawling.value = false;
    isCollectingQuarterly.value = false;
    isCollectingAll.value = false;
    fetchCollectStatus();
  });

  eventSource.addEventListener('ERROR', (e) => {
    const data = JSON.parse(e.data);
    sseProgress.value.message = data.message;
    addLog('ERROR', data.message);
    collectProgress.value = '오류: ' + data.message;
    collectAllProgress.value = '❌ 오류: ' + data.message;
    isCollectingAll.value = false;
  });

  // 재연결 시도 횟수 추적
  let reconnectAttempts = 0;
  const maxReconnectAttempts = 5;

  eventSource.onerror = () => {
    console.error('SSE 연결 오류');
    eventSource.close();
    sseConnection.value = null;

    // 수집이 진행 중이면 자동 재연결 시도
    if (sseProgress.value.percent < 100 && sseProgress.value.percent > 0 && reconnectAttempts < maxReconnectAttempts) {
      reconnectAttempts++;
      addLog('WARN', `SSE 연결 끊김 - 재연결 시도 중... (${reconnectAttempts}/${maxReconnectAttempts})`);
      sseProgress.value.message = `🔄 재연결 중... (${reconnectAttempts}/${maxReconnectAttempts})`;

      // 3초 후 재연결 시도
      setTimeout(() => {
        if (isCollectingAll.value || isCrawling.value || isCollectingQuarterly.value) {
          startSseSubscription(taskType);
        }
      }, 3000);
    } else if (reconnectAttempts >= maxReconnectAttempts) {
      addLog('ERROR', 'SSE 재연결 실패 - 백그라운드에서 수집 계속됨');
      sseProgress.value.message = '⚠️ 연결 끊김 - 수집은 백그라운드에서 계속됩니다';
      collectAllProgress.value = '⚠️ 연결 끊김 (백그라운드 수집 중)';
      isCrawling.value = false;
      isCollectingQuarterly.value = false;
      isCollectingAll.value = false;
    } else {
      addLog('ERROR', 'SSE 연결이 끊어졌습니다.');
      isCrawling.value = false;
      isCollectingQuarterly.value = false;
      isCollectingAll.value = false;
    }
  };
};

// 로그 추가 (최대 50개 유지)
const addLog = (level, message) => {
  const timestamp = new Date().toLocaleTimeString();
  sseProgress.value.logs.unshift({ level, message, timestamp });
  if (sseProgress.value.logs.length > 50) {
    sseProgress.value.logs.pop();
  }
};

// 진행률 바 닫기
const closeProgressBar = () => {
  showProgressBar.value = false;
  if (sseConnection.value) {
    sseConnection.value.close();
    sseConnection.value = null;
  }
};

const crawlOperatingMargin = async () => {
  if (isCrawling.value) return;

  if (!confirm('영업이익률 크롤링을 시작하시겠습니까?\n\n' +
    '• 진행률이 실시간으로 표시됩니다.\n' +
    '• 약 15-20분 소요됩니다.\n' +
    '• 브라우저를 닫아도 백그라운드에서 계속 진행됩니다.')) {
    return;
  }

  isCrawling.value = true;
  collectProgress.value = '영업이익률 크롤링 시작...';

  // SSE 구독 시작
  startSseSubscription('crawl-operating-margin');

  try {
    // 비동기 크롤링 API 호출
    const response = await api.post('/screener/crawl-operating-margin/async', null, {
      params: { forceUpdate: crawlForceUpdate.value }
    });

    if (!response.data.success) {
      collectProgress.value = response.data.message;
      isCrawling.value = false;
      closeProgressBar();
    }
  } catch (error) {
    console.error('영업이익률 크롤링 오류:', error);
    collectProgress.value = '크롤링 시작 오류: ' + (error.response?.data?.message || error.message);
    isCrawling.value = false;
    closeProgressBar();
  }
};

// 분기별 재무제표 수집 (PEG, 턴어라운드용)
const collectQuarterlyFinance = async () => {
  if (isCollectingQuarterly.value) return;

  if (!confirm('분기별 재무제표 수집을 시작하시겠습니까?\n\n' +
    '• 네이버 금융에서 최근 4개 분기 데이터를 크롤링합니다.\n' +
    '• EPS 성장률이 계산되어 PEG 스크리너가 작동합니다.\n' +
    '• 과거 분기 데이터로 턴어라운드 분석이 가능해집니다.\n' +
    '• 진행률이 실시간으로 표시됩니다.\n\n' +
    '약 20-25분 소요됩니다.')) {
    return;
  }

  isCollectingQuarterly.value = true;
  collectProgress.value = '분기별 재무제표 수집 시작...';

  // SSE 구독 시작
  startSseSubscription('collect-finance');

  try {
    // 비동기 수집 API 호출
    const response = await api.post('/screener/collect/finance/async');

    if (!response.data.success) {
      collectProgress.value = response.data.message;
      isCollectingQuarterly.value = false;
      closeProgressBar();
    }
  } catch (error) {
    console.error('분기별 재무제표 수집 오류:', error);
    collectProgress.value = '수집 시작 오류: ' + (error.response?.data?.message || error.message);
    isCollectingQuarterly.value = false;
    closeProgressBar();
  }
};

// 단일 종목 분기별 재무제표 수집
const collectSingleQuarterly = async () => {
  if (!quarterlyStockCode.value || isCollectingSingleQuarterly.value) return;

  isCollectingSingleQuarterly.value = true;
  quarterlyResult.value = null;

  try {
    const response = await api.post(`/screener/collect/finance/${quarterlyStockCode.value}`);
    quarterlyResult.value = {
      stockCode: quarterlyStockCode.value,
      success: response.data.success,
      message: response.data.message
    };
  } catch (error) {
    console.error('단일 종목 분기별 수집 오류:', error);
    quarterlyResult.value = {
      stockCode: quarterlyStockCode.value,
      success: false,
      message: error.response?.data?.message || error.message
    };
  } finally {
    isCollectingSingleQuarterly.value = false;
  }
};

const previewCrawl = async () => {
  if (!testStockCode.value) return;

  crawlPreview.value = null;

  try {
    const response = await api.get(`/screener/crawl-preview/${testStockCode.value}`);
    crawlPreview.value = response.data;
  } catch (error) {
    console.error('크롤링 미리보기 오류:', error);
    crawlPreview.value = { success: false, message: '오류 발생' };
  }
};

const fixStockNames = async () => {
  if (isFixingNames.value) return;

  if (!confirm('종목명 일괄 수정을 시작하시겠습니까?\n종목코드가 종목명으로 저장된 데이터를 수정합니다.')) {
    return;
  }

  isFixingNames.value = true;
  collectProgress.value = '종목명 수정 시작...';

  try {
    const response = await api.post('/screener/fix-stock-names');
    if (response.data.success) {
      const data = response.data.data;
      collectProgress.value = `종목명 수정 완료! 총 ${data.total}개 중 수정: ${data.fixedCount}, 실패: ${data.failCount}, 스킵: ${data.skipCount} (소요시간: ${data.elapsedSeconds}초)`;
      await fetchCollectStatus();
    } else {
      collectProgress.value = '종목명 수정 실패: ' + response.data.message;
    }
  } catch (error) {
    console.error('종목명 수정 오류:', error);
    collectProgress.value = '종목명 수정 중 오류 발생: ' + (error.response?.data?.message || error.message);
  } finally {
    isFixingNames.value = false;
  }
};

const fetchMagicFormula = async () => {
  loading.value = true;
  try {
    const params = {
      limit: magicFormulaFilters.value.limit
    };
    if (magicFormulaFilters.value.minMarketCap) {
      params.minMarketCap = magicFormulaFilters.value.minMarketCap;
    }
    const response = await api.get('/screener/magic-formula', {
      params,
      timeout: 30000  // 30초 (시세 조회에 시간 소요)
    });
    if (response.data.success) {
      magicFormulaStocks.value = response.data.data;
    }
  } catch (error) {
    console.error('마법의 공식 스크리닝 오류:', error);
  } finally {
    loading.value = false;
  }
};

const fetchPegStocks = async () => {
  loading.value = true;
  try {
    const response = await api.get('/screener/peg', {
      params: {
        maxPeg: pegFilters.value.maxPeg,
        minEpsGrowth: pegFilters.value.minEpsGrowth,
        limit: pegFilters.value.limit
      },
      timeout: 30000  // 30초 (시세 조회에 시간 소요)
    });
    if (response.data.success) {
      pegStocks.value = response.data.data;
    }
  } catch (error) {
    console.error('PEG 스크리닝 오류:', error);
  } finally {
    loading.value = false;
  }
};

const fetchTurnaroundStocks = async () => {
  loading.value = true;
  try {
    const response = await api.get('/screener/turnaround', {
      params: {
        limit: turnaroundFilters.value.limit
      },
      timeout: 30000  // 30초 (시세 조회에 시간 소요)
    });
    if (response.data.success) {
      turnaroundStocks.value = response.data.data;
      // 디버깅용: 턴어라운드 데이터 확인
      console.log('[턴어라운드] 전체 데이터:', response.data.data);
      if (response.data.data.length > 0) {
        console.log('[턴어라운드] 첫 번째 종목 상세:', response.data.data[0]);
        console.log('[턴어라운드] marketCap:', response.data.data[0].marketCap);
        console.log('[턴어라운드] pbr:', response.data.data[0].pbr);
        console.log('[턴어라운드] per:', response.data.data[0].per);
      }
    }
  } catch (error) {
    console.error('턴어라운드 스크리닝 오류:', error);
  } finally {
    loading.value = false;
  }
};

// AI 분석 함수들
const fetchMagicFormulaAI = async () => {
  aiLoading.value = true;
  magicFormulaAI.value = '';
  try {
    const params = { limit: 10 };
    if (magicFormulaFilters.value.minMarketCap) {
      params.minMarketCap = magicFormulaFilters.value.minMarketCap;
    }
    const response = await api.get('/screener/magic-formula/ai-analysis', { params, timeout: 60000 });
    if (response.data.success) {
      magicFormulaAI.value = response.data.analysis;
    } else {
      magicFormulaAI.value = response.data.message || 'AI 분석에 실패했습니다.';
    }
  } catch (error) {
    console.error('마법의 공식 AI 분석 오류:', error);
    magicFormulaAI.value = 'AI 분석 중 오류가 발생했습니다.';
  } finally {
    aiLoading.value = false;
  }
};

const fetchPegAI = async () => {
  aiLoading.value = true;
  pegAI.value = '';
  try {
    const response = await api.get('/screener/peg/ai-analysis', {
      params: {
        maxPeg: pegFilters.value.maxPeg,
        minEpsGrowth: pegFilters.value.minEpsGrowth,
        limit: 10
      },
      timeout: 60000  // AI 분석에 시간 소요
    });
    if (response.data.success) {
      pegAI.value = response.data.analysis;
    } else {
      pegAI.value = response.data.message || 'AI 분석에 실패했습니다.';
    }
  } catch (error) {
    console.error('PEG AI 분석 오류:', error);
    pegAI.value = 'AI 분석 중 오류가 발생했습니다.';
  } finally {
    aiLoading.value = false;
  }
};

const fetchTurnaroundAI = async () => {
  aiLoading.value = true;
  turnaroundAI.value = '';
  try {
    const response = await api.get('/screener/turnaround/ai-analysis', {
      params: { limit: 10 },
      timeout: 60000  // AI 분석에 시간 소요
    });
    if (response.data.success) {
      turnaroundAI.value = response.data.analysis;
    } else {
      turnaroundAI.value = response.data.message || 'AI 분석에 실패했습니다.';
    }
  } catch (error) {
    console.error('턴어라운드 AI 분석 오류:', error);
    turnaroundAI.value = 'AI 분석 중 오류가 발생했습니다.';
  } finally {
    aiLoading.value = false;
  }
};

const formatAIResponse = (text) => {
  if (!text) return '';
  // 줄바꿈을 <br>로 변환하고, **text**를 <strong>으로 변환
  return text
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>');
};

// 포맷팅 함수
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

const formatMarketCap = (value) => {
  if (!value) return '-';
  const num = Number(value);
  if (num >= 10000) {
    return `${(num / 10000).toFixed(1)}조`;
  }
  return `${num.toLocaleString('ko-KR')}억`;
};

// 턴어라운드 종목 시가총액 안전 포맷 (여러 필드 확인)
const formatMarketCapSafe = (stock) => {
  // marketCap 필드 우선, 없으면 market_cap 확인
  const value = stock.marketCap || stock.market_cap || stock.marketCapitalization;
  if (!value || value === 0) return '-';

  const num = Number(value);
  if (isNaN(num) || num <= 0) return '-';

  if (num >= 10000) {
    return `${(num / 10000).toFixed(1)}조`;
  } else if (num >= 1) {
    return `${Math.round(num).toLocaleString('ko-KR')}억`;
  }
  return '-';
};

// PER이 유효한지 확인 (양수이고 0보다 큰 경우만)
const isValidPer = (per) => {
  if (per === null || per === undefined) return false;
  const num = Number(per);
  return !isNaN(num) && num > 0;
};

const formatAmount = (value) => {
  if (value === null || value === undefined) return 'N/A';
  const num = Number(value);
  if (isNaN(num)) return 'N/A';
  // 0인 경우 명확히 표시
  if (num === 0) return '0억원';
  const sign = num >= 0 ? '' : '';
  if (Math.abs(num) >= 10000) {
    return `${sign}${(num / 10000).toFixed(1)}조`;
  }
  return `${sign}${num.toLocaleString('ko-KR')}억`;
};

const formatBillion = (value) => {
  if (value === null || value === undefined) return 'N/A';
  const num = Number(value);
  if (isNaN(num)) return 'N/A';
  // 0인 경우 명확히 표시
  if (num === 0) return '0억';
  if (Math.abs(num) >= 10000) {
    return `${(num / 10000).toFixed(1)}조`;
  }
  if (Math.abs(num) >= 1) {
    return `${num.toLocaleString('ko-KR')}억`;
  }
  return `${(num * 100).toFixed(0)}백만`;
};

// 절대값으로 억 단위 표시 (부호는 별도 라벨로 표시)
const formatBillionAbs = (value) => {
  if (value === null || value === undefined) return '-';
  const num = Math.abs(Number(value));
  if (num >= 10000) {
    return `${(num / 10000).toFixed(1)}조`;
  }
  if (num >= 1) {
    return `${num.toLocaleString('ko-KR')}억`;
  }
  return `${(num * 100).toFixed(0)}백만`;
};

// 수급 분석 색상 클래스 (양수=빨강/매수, 음수=파랑/매도)
// ⚠️ 핵심: 반드시 Number()로 변환 후 비교해야 함 (BigDecimal이 문자열로 올 수 있음)
const getSupplyDemandClass = (value) => {
  if (value === null || value === undefined) return '';
  const num = Number(value);
  if (isNaN(num)) return '';
  if (num > 0) return 'supply-positive';    // 순매수 → 빨간색
  if (num < 0) return 'supply-negative';    // 순매도 → 파란색
  return '';
};

// 수급 분석 라벨 (매수/매도)
const getSupplyDemandLabel = (value) => {
  if (value === null || value === undefined) return '';
  const num = Number(value);
  if (isNaN(num)) return '';
  if (num > 0) return '순매수';
  if (num < 0) return '순매도';
  return '보합';
};

// 수급 값이 양수인지 확인 (템플릿용 헬퍼)
const isSupplyPositive = (value) => {
  if (value === null || value === undefined) return false;
  const num = Number(value);
  return !isNaN(num) && num > 0;
};

// 수급 값이 음수인지 확인 (템플릿용 헬퍼)
const isSupplyNegative = (value) => {
  if (value === null || value === undefined) return false;
  const num = Number(value);
  return !isNaN(num) && num < 0;
};

// ========== 종목 상세 진단 (더블 체크) ==========
const openDiagnosis = async (stockCode, stockName) => {
  selectedStockCode.value = stockCode;
  showDiagnosisModal.value = true;
  diagnosisLoading.value = true;
  diagnosisData.value = null;

  try {
    const response = await api.get(`/analysis/diagnosis/${stockCode}`);
    if (response.data.success) {
      diagnosisData.value = response.data.data;
    } else {
      console.error('진단 실패:', response.data.message);
    }
  } catch (error) {
    console.error('진단 API 오류:', error);
  } finally {
    diagnosisLoading.value = false;
  }
};

const closeDiagnosis = () => {
  showDiagnosisModal.value = false;
  diagnosisData.value = null;
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

const getScoreClass = (score) => {
  if (score >= 70) return 'score-high';
  if (score >= 40) return 'score-mid';
  return 'score-low';
};

const getRsiClass = (status) => {
  if (status === '과열') return 'rsi-caution'; // 주황색 (경고)으로 변경
  if (status === '침체') return 'rsi-oversold';
  return 'rsi-neutral';
};

// RSI 뱃지 클래스 (과열은 주황색)
const getRsiBadgeClass = (status) => {
  if (status === '과열') return 'badge-caution';
  if (status === '침체') return 'badge-opportunity';
  return 'badge-neutral';
};

// RSI 상태 라벨
const getRsiStatusLabel = (status) => {
  if (status === '과열') return '⚠️ 과열';
  if (status === '침체') return '💡 매수 기회';
  return '중립';
};

// 장 시작 전인지 확인 (09:00 이전)
const isBeforeMarketOpen = () => {
  const now = new Date();
  const hours = now.getHours();
  const minutes = now.getMinutes();
  return hours < 9 || (hours === 9 && minutes === 0);
};

// 수급 데이터가 유효한지 확인 (0이 아닌 실제 데이터인지)
const isSupplyDataAvailable = (value) => {
  if (value === null || value === undefined) return false;
  const num = Number(value);
  return !isNaN(num) && num !== 0;
};

// RSI가 과열 상태인지 확인 (70 이상)
const isRsiOverbought = (data) => {
  if (!data || !data.rsiStatus) return false;
  return data.rsiStatus === '과열';
};

// RSI 과열 시 조정된 verdict 클래스 (주황색 경고로 표시)
const getAdjustedVerdictClass = (data) => {
  if (!data) return 'verdict-neutral';

  // RSI 과열 시 매수 신호라도 주황색 경고로 변경
  if (isRsiOverbought(data) && (data.verdictLevel === 'STRONG_BUY' || data.verdictLevel === 'BUY')) {
    return 'verdict-caution';
  }
  return getVerdictClass(data.verdictLevel);
};

// RSI 과열 시 조정된 verdict 아이콘
const getAdjustedVerdictIcon = (data) => {
  if (!data) return '❓';

  // RSI 과열 시 매수 신호라도 경고 아이콘으로 변경
  if (isRsiOverbought(data) && (data.verdictLevel === 'STRONG_BUY' || data.verdictLevel === 'BUY')) {
    return '⚠️';
  }
  return getVerdictIcon(data.verdictLevel);
};

// RSI 과열 시 조정된 verdict 라벨
const getAdjustedVerdict = (data) => {
  if (!data) return '분석 중';

  // RSI 과열 시 매수 신호라도 '관망'으로 변경
  if (isRsiOverbought(data) && (data.verdictLevel === 'STRONG_BUY' || data.verdictLevel === 'BUY')) {
    return '관망';
  }
  return data.verdict || '분석 중';
};

const getMfiClass = (status) => {
  if (status === '과열') return 'mfi-overbought';
  if (status === '침체') return 'mfi-oversold';
  return 'mfi-neutral';
};

const formatPrice = (value) => {
  if (value === null || value === undefined) return '-';
  return Number(value).toLocaleString() + '원';
};

// ========== 개선된 지표 표시 함수들 ==========

// 좋은 수치인지 판단 (👍 뱃지 표시용)
const isGoodValue = (value, type) => {
  if (value === null || value === undefined) return false;
  const num = Number(value);

  switch (type) {
    case 'per':
      return num > 0 && num < 15; // PER 15 미만이면 좋음
    case 'pbr':
      return num > 0 && num < 1.5; // PBR 1.5 미만이면 좋음
    case 'roe':
      return num >= 10; // ROE 10% 이상이면 좋음
    case 'margin':
      return num >= 10; // 영업이익률 10% 이상이면 좋음
    default:
      return false;
  }
};

// 배경 컬러바 스타일 (수치의 높낮이 시각화)
const getValueBarStyle = (value, type) => {
  if (value === null || value === undefined) return { width: '0%' };
  const num = Number(value);

  let percent = 0;
  let color = '';

  switch (type) {
    case 'per':
      // PER: 0~50 범위, 낮을수록 좋음 (녹색)
      percent = Math.min(Math.max(num / 50, 0), 1) * 100;
      color = num < 15 ? 'rgba(34, 197, 94, 0.2)' : num > 30 ? 'rgba(239, 68, 68, 0.15)' : 'rgba(156, 163, 175, 0.15)';
      break;
    case 'pbr':
      // PBR: 0~5 범위, 낮을수록 좋음 (녹색)
      percent = Math.min(Math.max(num / 5, 0), 1) * 100;
      color = num < 1.5 ? 'rgba(34, 197, 94, 0.2)' : num > 3 ? 'rgba(239, 68, 68, 0.15)' : 'rgba(156, 163, 175, 0.15)';
      break;
    case 'roe':
    case 'margin':
      // ROE/마진: 0~30 범위, 높을수록 좋음 (녹색)
      percent = Math.min(Math.max(num / 30, 0), 1) * 100;
      color = num >= 10 ? 'rgba(34, 197, 94, 0.2)' : num < 5 ? 'rgba(239, 68, 68, 0.15)' : 'rgba(156, 163, 175, 0.15)';
      break;
    default:
      percent = 50;
      color = 'rgba(156, 163, 175, 0.15)';
  }

  return {
    width: `${percent}%`,
    background: color
  };
};

// 기존 getValueClass는 유지 (하위 호환성)
const getValueClass = (value, type) => {
  return ''; // 이제 색상 대신 기본 검정색 사용
};

const getPegClass = (peg) => {
  if (peg === null || peg === undefined) return '';
  const num = Number(peg);
  if (num < 0.7) return 'very-positive';
  if (num < 1.0) return 'positive';
  if (num > 1.5) return 'negative';
  return '';
};

const getAmountClass = (value) => {
  if (value === null || value === undefined) return '';
  return Number(value) >= 0 ? 'positive' : 'negative';
};

const getTurnaroundClass = (type) => {
  switch (type) {
    case 'LOSS_TO_PROFIT':
      return 'loss-to-profit';
    case 'PROFIT_GROWTH':
      return 'profit-growth';
    default:
      return '';
  }
};

const getTurnaroundLabel = (type) => {
  switch (type) {
    case 'LOSS_TO_PROFIT':
      return '흑자전환';
    case 'PROFIT_GROWTH':
      return '이익급증';
    default:
      return '';
  }
};

// 날짜/시간 포맷팅
const formatDateTime = (dateTimeStr) => {
  if (!dateTimeStr) return '';
  const date = new Date(dateTimeStr);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const hours = String(date.getHours()).padStart(2, '0');
  const minutes = String(date.getMinutes()).padStart(2, '0');
  return `${year}-${month}-${day} ${hours}:${minutes}`;
};

// 수동 수집 다이얼로그
const showManualCollectDialog = () => {
  selectedTab.value = 'data-management';
};

onMounted(async () => {
  // 페이지 진입 시 바로 데이터 조회 (수집은 스케줄러가 처리)
  await fetchCollectStatus();
  await fetchMagicFormula();
});
</script>

<style scoped>
/* ========== 라이트 모드 (기본) ========== */
.screener-page {
  min-height: 100vh;
  background: var(--bg-gradient);
  padding: 2rem;
}

.content-wrapper {
  max-width: 1400px;
  margin: 0 auto;
  background: var(--card-bg);
  border-radius: 20px;
  padding: 2rem;
  box-shadow: var(--card-shadow);
  border: 1px solid var(--border-color);
}

.page-header {
  text-align: center;
  margin-bottom: 2rem;
  position: relative;
}

.back-button {
  position: absolute;
  left: 0;
  top: 0;
  background: var(--primary-gradient);
  color: white;
  border: none;
  padding: 0.75rem 1.5rem;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1rem;
  transition: all 0.3s;
}

.back-button:hover {
  transform: translateX(-5px);
  opacity: 0.9;
}

.page-header h1 {
  color: var(--text-primary);
  margin-bottom: 0.5rem;
}

.subtitle {
  color: var(--text-muted);
  font-size: 1.1rem;
}

/* 데이터 상태 바 */
.data-status-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 1rem;
  margin-top: 1rem;
  padding: 0.75rem 1rem;
  background: var(--bg-secondary);
  border-radius: 10px;
  font-size: 0.9rem;
}

.last-updated {
  color: var(--text-secondary);
}

.last-updated .data-count {
  color: var(--text-muted);
  margin-left: 0.5rem;
}

.last-updated.warning-text {
  color: #e67e22;
}

.manual-collect-btn {
  background: transparent;
  border: 1px solid var(--border-color);
  color: var(--text-muted);
  padding: 0.4rem 0.8rem;
  border-radius: 6px;
  font-size: 0.8rem;
  cursor: pointer;
  transition: all 0.2s;
}

.manual-collect-btn:hover {
  background: var(--bg-secondary);
  color: var(--text-primary);
  border-color: var(--primary-color);
}

/* 데이터 기준일 표시 (상단 고정) */
.data-timestamp-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.75rem 1.25rem;
  background: linear-gradient(135deg, #667eea15, #764ba215);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  margin-bottom: 1.5rem;
}

.timestamp-info {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.95rem;
}

.timestamp-info.warning {
  color: #e67e22;
}

.timestamp-icon {
  font-size: 1.1rem;
}

.timestamp-label {
  color: var(--text-secondary);
  font-weight: 500;
}

.timestamp-value {
  color: var(--primary-color);
  font-weight: 600;
  font-size: 1rem;
}

.timestamp-info .data-count {
  color: var(--text-muted);
  font-size: 0.85rem;
  margin-left: 0.25rem;
}

.batch-info {
  color: var(--text-muted);
  font-size: 0.8rem;
  background: var(--bg-secondary);
  padding: 0.35rem 0.75rem;
  border-radius: 6px;
}

/* 관리자 탭 스타일 */
.tab-divider {
  color: var(--border-color);
  margin: 0 0.5rem;
  font-weight: 300;
}

.admin-tab {
  background: #f8d7da !important;
  border-color: #f5c6cb !important;
  color: #721c24 !important;
}

.admin-tab:hover {
  background: #f1b0b7 !important;
}

.admin-tab.active {
  background: #dc3545 !important;
  color: white !important;
}

.admin-toggle-btn {
  background: transparent;
  border: none;
  font-size: 0.9rem;
  cursor: pointer;
  padding: 0.5rem;
  opacity: 0.5;
  transition: opacity 0.2s;
  margin-left: 0.5rem;
}

.admin-toggle-btn:hover {
  opacity: 1;
}

.screener-tabs {
  display: flex;
  justify-content: center;
  gap: 1rem;
  margin-bottom: 2rem;
  border-bottom: 2px solid var(--border-color);
}

.tab-btn {
  padding: 1rem 2rem;
  background: none;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 1.1rem;
  font-weight: 600;
  transition: all 0.3s;
  border-bottom: 3px solid transparent;
  margin-bottom: -2px;
}

.tab-btn.active {
  color: var(--success);
  border-bottom-color: var(--success);
}

.tab-btn:hover:not(.active) {
  color: var(--text-secondary);
}

.tab-content {
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.filter-bar {
  display: flex;
  gap: 1.5rem;
  align-items: center;
  margin-bottom: 1.5rem;
  padding: 1rem;
  background: var(--border-light);
  border-radius: 10px;
  flex-wrap: wrap;
}

.filter-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.filter-item label {
  font-weight: 600;
  color: var(--text-secondary);
  white-space: nowrap;
}

.filter-item select {
  padding: 0.5rem 1rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 1rem;
  cursor: pointer;
  background: var(--card-bg);
  color: var(--text-primary);
}

.refresh-btn {
  padding: 0.5rem 1rem;
  background: var(--primary-gradient);
  color: white;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 0.9rem;
  font-weight: 600;
  transition: all 0.3s;
  margin-left: auto;
}

.refresh-btn:hover {
  opacity: 0.9;
}

.info-box {
  background: var(--border-light);
  border-left: 4px solid var(--success);
  padding: 1rem 1.5rem;
  margin-bottom: 1.5rem;
  border-radius: 0 10px 10px 0;
}

.info-box strong {
  color: var(--success);
  display: block;
  margin-bottom: 0.5rem;
}

.info-box p {
  color: var(--text-secondary);
  margin: 0;
  font-size: 0.95rem;
  line-height: 1.5;
}

.stocks-table-wrapper {
  overflow-x: auto;
}

.stocks-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.95rem;
}

.stocks-table th,
.stocks-table td {
  padding: 1rem;
  text-align: left;
  border-bottom: 1px solid var(--border-color);
  color: var(--text-primary);
}

.stocks-table th {
  background: var(--border-light);
  color: var(--text-secondary);
  font-weight: 600;
  white-space: nowrap;
}

.stocks-table tbody tr:hover {
  background: var(--border-light);
}

.stocks-table .rank {
  font-weight: 700;
  color: var(--success);
  text-align: center;
}

.stocks-table .stock-info {
  display: flex;
  flex-direction: column;
}

.stocks-table .stock-name {
  font-weight: 600;
  color: var(--text-primary);
}

.stocks-table .stock-code {
  font-size: 0.85rem;
  color: var(--text-muted);
  font-family: monospace;
}

.stocks-table .score {
  font-weight: 700;
  color: var(--text-primary);
  text-align: center;
}

/* 한국 주식 기준 색상: 양수=빨간색(상승/매수), 음수=파란색(하락/매도) */
.positive {
  color: #e53e3e !important;  /* 빨간색: 상승, 매수, 양수 */
}

.very-positive {
  color: #c53030 !important;  /* 진한 빨간색: 강한 상승 */
  font-weight: 700;
}

.negative {
  color: #3182ce !important;  /* 파란색: 하락, 매도, 음수 */
}

/* 개선된 지표 셀 스타일 (1. 스크리너 테이블 색상 로직 개선) */
.metric-cell {
  position: relative;
  padding: 0 !important; /* 패딩 제거하여 배경색 꽉 채우기 */
}

.metric-value-bar {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  height: 100%;
  z-index: 0;
  /* border-radius 제거하여 셀 사이 끊김 방지 */
}

.metric-text {
  position: relative;
  z-index: 1;
  color: var(--text-primary); /* 기본 검정색 */
  font-weight: 500;
  padding: 0.75rem 0.5rem 0.75rem 0.75rem;
  display: inline-block;
}

.good-badge {
  position: relative;
  z-index: 1;
  font-size: 0.85rem;
  animation: pop-in 0.3s ease;
  padding-right: 0.5rem;
}

@keyframes pop-in {
  0% { transform: scale(0); opacity: 0; }
  100% { transform: scale(1); opacity: 1; }
}

/* 수급 데이터 없음 스타일 (2. 상세 진단 모달 예외 처리) */
.net-amount.no-data {
  color: var(--text-muted) !important;
  font-style: italic;
  font-weight: 400;
}

.before-market-notice {
  padding: 0.5rem 0.75rem;
  background: rgba(59, 130, 246, 0.1);
  border: 1px solid rgba(59, 130, 246, 0.3);
  border-radius: 6px;
  font-size: 0.8rem;
  color: #3b82f6;
  margin-bottom: 0.75rem;
  text-align: center;
}

/* 턴어라운드 카드 그리드 */
.stocks-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 1.5rem;
}

.stock-card {
  background: var(--card-bg);
  border-radius: 15px;
  padding: 1.5rem;
  border: 2px solid var(--border-color);
  transition: all 0.3s;
  position: relative;
}

.stock-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.15);
}

.stock-card.loss-to-profit {
  border-color: var(--success);
  background: linear-gradient(135deg, var(--card-bg) 0%, rgba(74, 222, 128, 0.1) 100%);
}

.stock-card.profit-growth {
  border-color: var(--info);
  background: linear-gradient(135deg, var(--card-bg) 0%, rgba(59, 130, 246, 0.1) 100%);
}

.turnaround-badge {
  position: absolute;
  top: -10px;
  right: 15px;
  padding: 0.3rem 0.8rem;
  border-radius: 15px;
  font-size: 0.8rem;
  font-weight: 700;
  color: white;
}

.turnaround-badge.loss-to-profit {
  background: linear-gradient(135deg, #4ade80 0%, #22c55e 100%);
}

.turnaround-badge.profit-growth {
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
}

/* PBR 1배 미만 강조 (저평가) */
.value-highlight {
  color: var(--success) !important;
  font-weight: 700;
}

.stock-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid var(--border-color);
}

.stock-header.clickable {
  cursor: pointer;
  transition: all 0.2s;
  border-radius: 8px;
  margin: -0.5rem;
  padding: 0.5rem;
  padding-bottom: 1rem;
  margin-bottom: 0.5rem;
}

.stock-header.clickable:hover {
  background: var(--border-light);
}

.stock-header.clickable:hover .stock-name {
  color: var(--primary-start);
}

.stock-info-col .detail-hint {
  display: none;
  font-size: 0.7rem;
  color: var(--primary-start);
  margin-top: 0.25rem;
}

.stock-header.clickable:hover .detail-hint {
  display: block;
}

.stock-info-col {
  display: flex;
  flex-direction: column;
}

.stock-info-col .stock-name {
  font-size: 1.2rem;
  font-weight: 700;
  color: var(--text-primary);
}

.stock-info-col .stock-code {
  font-size: 0.9rem;
  color: var(--text-muted);
  font-family: monospace;
}

.rank-badge {
  background: var(--primary-gradient);
  color: white;
  padding: 0.3rem 0.8rem;
  border-radius: 15px;
  font-weight: 700;
  font-size: 0.9rem;
}

.stock-details {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-row.highlight {
  background: var(--border-light);
  padding: 0.5rem;
  border-radius: 8px;
}

.detail-row .label {
  color: var(--text-muted);
  font-size: 0.9rem;
}

.detail-row .value {
  font-weight: 600;
  color: var(--text-primary);
}

/* AI 분석 섹션 */
.ai-section {
  margin-bottom: 1.5rem;
}

.ai-btn {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1.5rem;
  background: var(--primary-gradient);
  color: white;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1rem;
  font-weight: 600;
  transition: all 0.3s;
}

.ai-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 5px 20px rgba(102, 126, 234, 0.4);
}

.ai-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.ai-icon {
  font-size: 1.2rem;
}

.ai-result {
  margin-top: 1rem;
  background: var(--card-bg);
  border: 1px solid var(--primary-start);
  border-radius: 15px;
  overflow: hidden;
  animation: fadeIn 0.3s ease;
}

.ai-result-header {
  padding: 0.75rem 1.5rem;
  background: var(--primary-gradient);
}

.ai-badge {
  color: white;
  font-weight: 700;
  font-size: 0.9rem;
}

.ai-result-content {
  padding: 1.5rem;
  color: var(--text-secondary);
  line-height: 1.8;
  font-size: 0.95rem;
}

.ai-result-content strong {
  color: var(--success);
}

.no-data {
  text-align: center;
  padding: 3rem;
  color: var(--text-muted);
}

.no-data p {
  font-size: 1.2rem;
}

/* 원버튼 전체 수집 스타일 */
.action-card.primary-action {
  background: linear-gradient(135deg, #667eea15, #764ba215);
  border: 2px solid #667eea;
  margin-bottom: 1.5rem;
}

.action-card.primary-action .action-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.recommended-badge {
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: white;
  font-size: 0.7rem;
  padding: 0.2rem 0.5rem;
  border-radius: 4px;
  font-weight: 600;
}

.action-btn.large {
  padding: 1rem 2rem;
  font-size: 1.1rem;
}

.info-tag.highlight {
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: white;
}

.collect-result.success {
  background: #10b98120;
  color: #10b981;
  border: 1px solid #10b981;
  padding: 0.75rem;
  border-radius: 6px;
  margin-top: 0.75rem;
  font-weight: 500;
}

.divider {
  display: flex;
  align-items: center;
  margin: 2rem 0;
  color: var(--text-muted);
}

.divider::before,
.divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--border-color);
}

.divider span {
  padding: 0 1rem;
  font-size: 0.9rem;
}

.collect-result {
  margin-top: 1rem;
  background: var(--bg-secondary);
  border-radius: 8px;
  padding: 1rem;
  font-size: 0.8rem;
  max-height: 200px;
  overflow-y: auto;
}

.collect-result pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}

/* 데이터 관리 탭 스타일 */
.info-box.warning {
  border-left-color: var(--warning);
}

.info-box.warning strong {
  color: var(--warning);
}

.status-card {
  background: var(--card-bg);
  border-radius: 15px;
  padding: 1.5rem;
  margin-bottom: 2rem;
  border: 1px solid var(--border-color);
}

.status-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.status-header h3 {
  color: var(--text-primary);
  margin: 0;
  font-size: 1.1rem;
}

.refresh-btn.small {
  padding: 0.4rem 0.8rem;
  font-size: 0.85rem;
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1rem;
}

.status-item {
  background: var(--border-light);
  padding: 1rem;
  border-radius: 10px;
  text-align: center;
}

.status-label {
  display: block;
  color: var(--text-muted);
  font-size: 0.9rem;
  margin-bottom: 0.5rem;
}

.status-value {
  display: block;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-primary);
}

.status-value.positive {
  color: var(--success);
}

.status-value.warning-text {
  color: var(--warning);
}

.status-loading {
  text-align: center;
  color: var(--text-muted);
  padding: 1rem;
}

/* 자동 수집 상태 */
.auto-collect-status {
  margin-top: 1rem;
  padding: 1rem;
  background: var(--border-light);
  border-radius: 10px;
  border-left: 4px solid var(--primary);
}

.auto-collect-status.pending {
  border-left-color: var(--text-muted);
}

.auto-collect-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}

.auto-collect-icon {
  font-size: 1.2rem;
}

.auto-collect-title {
  font-weight: 600;
  color: var(--text-primary);
}

.auto-collect-result {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem 0.75rem;
  border-radius: 6px;
  margin-bottom: 0.5rem;
}

.auto-collect-result.success {
  background: rgba(16, 185, 129, 0.1);
}

.auto-collect-result.fail {
  background: rgba(239, 68, 68, 0.1);
}

.auto-collect-result .result-icon {
  font-size: 1rem;
}

.auto-collect-result .result-time {
  color: var(--text-muted);
  font-size: 0.9rem;
}

.auto-collect-result .result-status {
  font-weight: 600;
}

.auto-collect-result.success .result-status {
  color: var(--success);
}

.auto-collect-result.fail .result-status {
  color: var(--danger);
}

.auto-collect-message {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.collect-actions {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 1.5rem;
  margin-bottom: 2rem;
}

.action-card {
  background: var(--card-bg);
  border-radius: 15px;
  padding: 1.5rem;
  border: 1px solid var(--border-color);
  transition: all 0.3s;
}

.action-card:hover {
  border-color: var(--primary-start);
}

.action-card.highlight {
  background: linear-gradient(135deg, rgba(74, 144, 226, 0.08), rgba(80, 227, 194, 0.08));
  border-color: var(--primary-start);
}

.new-badge {
  background: linear-gradient(135deg, #ff6b6b, #ee5a24);
  color: white;
  font-size: 0.7rem;
  font-weight: bold;
  padding: 0.2rem 0.5rem;
  border-radius: 10px;
  margin-left: auto;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.action-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1rem;
}

.action-icon {
  font-size: 1.5rem;
}

.action-header h4 {
  color: var(--text-primary);
  margin: 0;
  font-size: 1.1rem;
}

.action-desc {
  color: var(--text-secondary);
  font-size: 0.9rem;
  line-height: 1.5;
  margin-bottom: 1rem;
}

.action-info {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
  margin-bottom: 1rem;
}

.info-tag {
  background: var(--border-light);
  padding: 0.4rem 0.8rem;
  border-radius: 20px;
  font-size: 0.8rem;
  color: var(--text-muted);
}

.action-options {
  margin-bottom: 1rem;
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 0.9rem;
}

.checkbox-label input[type="checkbox"] {
  width: 18px;
  height: 18px;
  cursor: pointer;
}

.action-btn {
  width: 100%;
  padding: 0.75rem 1.5rem;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1rem;
  font-weight: 600;
  transition: all 0.3s;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
}

.action-btn.primary {
  background: linear-gradient(135deg, #4ade80 0%, #22c55e 100%);
  color: #000;
}

.action-btn.primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 5px 20px rgba(74, 222, 128, 0.3);
}

.action-btn.secondary {
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  color: #fff;
}

.action-btn.secondary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 5px 20px rgba(59, 130, 246, 0.3);
}

.action-btn.warning {
  background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%);
  color: #000;
}

.action-btn.warning:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 5px 20px rgba(245, 158, 11, 0.3);
}

.action-btn.small {
  width: auto;
  padding: 0.5rem 1rem;
  font-size: 0.9rem;
  background: var(--primary-gradient);
  color: #fff;
}

.action-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none !important;
}

.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid transparent;
  border-top-color: currentColor;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.test-input-group {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1rem;
}

.test-input {
  flex: 1;
  padding: 0.5rem 1rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--card-bg);
  color: var(--text-primary);
  font-size: 1rem;
}

.test-input::placeholder {
  color: var(--text-muted);
}

.preview-result {
  background: var(--border-light);
  border-radius: 10px;
  overflow: hidden;
  margin-top: 1rem;
}

.preview-header {
  background: var(--primary-gradient);
  padding: 0.75rem 1rem;
  color: #fff;
  font-weight: 600;
  font-size: 0.9rem;
}

.preview-header.success {
  background: linear-gradient(135deg, #27ae60, #2ecc71);
}

.preview-header.fail {
  background: linear-gradient(135deg, #e74c3c, #c0392b);
}

.success-message {
  color: var(--success);
  font-weight: 500;
  margin: 0;
}

.spinner.small {
  width: 14px;
  height: 14px;
  border-width: 2px;
  margin-right: 4px;
}

.preview-data {
  padding: 1rem;
}

.preview-item {
  display: flex;
  justify-content: space-between;
  padding: 0.5rem 0;
  border-bottom: 1px solid var(--border-color);
  color: var(--text-secondary);
}

.preview-item:last-child {
  border-bottom: none;
}

.preview-item .value {
  color: var(--success);
  font-weight: 600;
}

.preview-empty {
  padding: 1rem;
  text-align: center;
  color: var(--text-muted);
}

.progress-log {
  background: var(--card-bg);
  border-radius: 15px;
  overflow: hidden;
  border: 1px solid var(--border-color);
}

.progress-header {
  background: var(--border-light);
  padding: 0.75rem 1rem;
  color: var(--text-primary);
  font-weight: 600;
}

.progress-content {
  padding: 1rem;
  color: var(--success);
  font-family: monospace;
  white-space: pre-wrap;
  line-height: 1.6;
}

/* SSE 실시간 프로그레스 패널 */
.sse-progress-panel {
  background: linear-gradient(135deg, #1a1a3a 0%, #2a2a4a 100%);
  border-radius: 15px;
  overflow: hidden;
  border: 1px solid #4a4a8a;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.3);
}

.sse-progress-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1rem 1.5rem;
  background: linear-gradient(135deg, #4a4a8a 0%, #5a5a9a 100%);
}

.sse-title {
  font-weight: 700;
  color: white;
  font-size: 1.1rem;
}

.sse-progress-header .close-btn {
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: white;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  font-size: 1.2rem;
  cursor: pointer;
  transition: all 0.2s;
}

.sse-progress-header .close-btn:hover {
  background: rgba(255, 255, 255, 0.3);
}

.progress-bar-container {
  padding: 1.5rem;
}

.progress-bar {
  height: 24px;
  background: #0f0f23;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid #3a3a5a;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #4299e1, #48bb78, #f6ad55);
  background-size: 200% 100%;
  animation: progressGradient 2s linear infinite;
  border-radius: 12px;
  transition: width 0.3s ease;
}

@keyframes progressGradient {
  0% { background-position: 0% 50%; }
  50% { background-position: 100% 50%; }
  100% { background-position: 0% 50%; }
}

.progress-stats {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.75rem;
  color: #ccc;
}

.progress-percent {
  font-size: 1.5rem;
  font-weight: 700;
  color: #4299e1;
}

.progress-counts {
  font-size: 1rem;
  color: #888;
}

.progress-info {
  padding: 0 1.5rem 1rem;
}

.info-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}

.info-label {
  color: #888;
  font-size: 0.9rem;
}

.info-value {
  color: #fff;
  font-size: 0.95rem;
}

.info-row.stats {
  gap: 1.5rem;
}

.stat-item {
  font-weight: 600;
  padding: 0.25rem 0.75rem;
  border-radius: 15px;
  font-size: 0.85rem;
}

.stat-item.success {
  background: rgba(72, 187, 120, 0.2);
  color: #48bb78;
}

.stat-item.fail {
  background: rgba(229, 62, 62, 0.2);
  color: #e53e3e;
}

.log-container {
  border-top: 1px solid #3a3a5a;
}

.log-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1.5rem;
  background: #0f0f23;
  color: #888;
  font-size: 0.9rem;
}

.log-count {
  color: #666;
}

.log-list {
  max-height: 200px;
  overflow-y: auto;
  padding: 0.5rem;
  background: #0a0a1a;
}

.log-item {
  display: flex;
  gap: 0.75rem;
  padding: 0.4rem 0.75rem;
  font-size: 0.8rem;
  font-family: monospace;
  border-radius: 5px;
  margin-bottom: 0.25rem;
}

.log-item:hover {
  background: rgba(255, 255, 255, 0.05);
}

.log-time {
  color: #666;
  min-width: 70px;
}

.log-level {
  font-weight: 600;
  min-width: 60px;
  text-transform: uppercase;
}

.log-message {
  color: #ccc;
  flex: 1;
}

.log-item.info .log-level {
  color: #4299e1;
}

.log-item.success .log-level {
  color: #48bb78;
}

.log-item.error .log-level {
  color: #e53e3e;
}

.log-item.warning .log-level {
  color: #f6ad55;
}

@media (max-width: 768px) {
  .screener-page {
    padding: 1rem;
  }

  .content-wrapper {
    padding: 1rem;
  }

  .page-header h1 {
    margin-top: 3rem;
    font-size: 1.5rem;
  }

  .screener-tabs {
    flex-wrap: wrap;
  }

  .tab-btn {
    padding: 0.75rem 1rem;
    font-size: 0.9rem;
  }

  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-item {
    justify-content: space-between;
  }

  .refresh-btn {
    margin-left: 0;
    margin-top: 0.5rem;
  }

  .stocks-grid {
    grid-template-columns: 1fr;
  }

  .stocks-table {
    font-size: 0.85rem;
  }

  .stocks-table th,
  .stocks-table td {
    padding: 0.5rem;
  }

  .status-grid {
    grid-template-columns: 1fr;
  }

  .collect-actions {
    grid-template-columns: 1fr;
  }

  .test-input-group {
    flex-direction: column;
  }

  .action-btn.small {
    width: 100%;
  }
}

/* ========== 종목 상세 진단 모달 ========== */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
  padding: 1rem;
}

.diagnosis-modal {
  background: var(--card-bg);
  border-radius: 20px;
  width: 100%;
  max-width: 900px;
  max-height: 90vh;
  box-shadow: 0 25px 50px rgba(0, 0, 0, 0.3);
  /* Flex 레이아웃: 헤더 고정, 콘텐츠만 스크롤 */
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* common.css의 .modal-content 전역 스타일 오버라이드 */
.diagnosis-modal .modal-content {
  width: 100% !important;
  max-width: none !important;
  flex: 1;
  overflow-y: auto;
  box-sizing: border-box;
  padding: 1.5rem 2rem 2rem;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  background: transparent;
  border-radius: 0;
  box-shadow: none;
  animation: none;
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.5rem 2rem;
  border-bottom: 1px solid var(--border-color);
  background: var(--card-bg);
  /* 헤더는 고정 (flex-shrink 방지) */
  flex-shrink: 0;
}

.modal-header h2 {
  margin: 0;
  font-size: 1.4rem;
  color: var(--text-primary);
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.modal-icon {
  font-size: 1.5rem;
}

.close-btn {
  background: none;
  border: none;
  font-size: 2rem;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 0;
  line-height: 1;
  transition: color 0.2s;
}

.close-btn:hover {
  color: var(--text-primary);
}

.modal-loading {
  padding: 4rem 2rem;
  text-align: center;
  /* Flex 레이아웃에서 남은 공간 채우기 */
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
}

.modal-loading p {
  color: var(--text-secondary);
  margin-top: 1rem;
}

.spinner.large {
  width: 50px;
  height: 50px;
  border-width: 4px;
  margin: 0 auto;
}

.modal-error {
  padding: 3rem 2rem;
  text-align: center;
  color: var(--text-secondary);
  /* Flex 레이아웃에서 남은 공간 채우기 */
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
}

/* 종합 의견 섹션 */
.verdict-section {
  border-radius: 15px;
  padding: 1.5rem;
  margin-bottom: 1.5rem;
  width: 100%;
  box-sizing: border-box;
}

.verdict-strong-buy {
  background: linear-gradient(135deg, rgba(46, 204, 113, 0.2), rgba(39, 174, 96, 0.2));
  border: 1px solid rgba(46, 204, 113, 0.3);
}

.verdict-buy {
  background: linear-gradient(135deg, rgba(52, 152, 219, 0.2), rgba(41, 128, 185, 0.2));
  border: 1px solid rgba(52, 152, 219, 0.3);
}

.verdict-neutral {
  background: linear-gradient(135deg, rgba(149, 165, 166, 0.2), rgba(127, 140, 141, 0.2));
  border: 1px solid rgba(149, 165, 166, 0.3);
}

.verdict-caution {
  background: linear-gradient(135deg, rgba(241, 196, 15, 0.2), rgba(243, 156, 18, 0.2));
  border: 1px solid rgba(241, 196, 15, 0.3);
}

.verdict-avoid {
  background: linear-gradient(135deg, rgba(231, 76, 60, 0.2), rgba(192, 57, 43, 0.2));
  border: 1px solid rgba(231, 76, 60, 0.3);
}

.verdict-header {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.verdict-icon {
  font-size: 2.5rem;
}

.verdict-info {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.verdict-label {
  font-size: 1.4rem;
  font-weight: 700;
  color: var(--text-primary);
}

.verdict-score {
  font-size: 0.95rem;
  color: var(--text-secondary);
}

.verdict-caution-tag {
  display: inline-block;
  margin-top: 0.5rem;
  padding: 0.35rem 0.75rem;
  font-size: 0.85rem;
  font-weight: 600;
  color: #f39c12;
  background: rgba(243, 156, 18, 0.15);
  border: 1px solid rgba(243, 156, 18, 0.3);
  border-radius: 4px;
}

/* 경고/긍정 요소 */
.alerts-section {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 1rem;
  margin-bottom: 1.5rem;
  width: 100%;
  box-sizing: border-box;
}

.alert-box {
  border-radius: 12px;
  padding: 1rem;
}

.alert-box.warning {
  background: rgba(241, 196, 15, 0.1);
  border: 1px solid rgba(241, 196, 15, 0.3);
}

.alert-box.positive {
  background: rgba(46, 204, 113, 0.1);
  border: 1px solid rgba(46, 204, 113, 0.3);
}

.alert-header {
  font-weight: 600;
  margin-bottom: 0.75rem;
  color: var(--text-primary);
}

.alert-box ul {
  margin: 0;
  padding-left: 1.25rem;
}

.alert-box li {
  color: var(--text-secondary);
  margin-bottom: 0.4rem;
  font-size: 0.9rem;
}

/* 분석 그리드 */
.analysis-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 1.25rem;
  width: 100%;
  box-sizing: border-box;
}

.analysis-card {
  background: var(--border-light);
  border-radius: 15px;
  overflow: hidden;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 1rem 1.25rem;
  background: var(--card-bg);
  border-bottom: 1px solid var(--border-color);
}

.card-icon {
  font-size: 1.25rem;
}

.card-header h3 {
  margin: 0;
  font-size: 1rem;
  color: var(--text-primary);
  flex: 1;
}

.card-score {
  font-weight: 700;
  padding: 0.3rem 0.75rem;
  border-radius: 20px;
  font-size: 0.85rem;
}

.card-score.score-high {
  background: rgba(46, 204, 113, 0.2);
  color: #27ae60;
}

.card-score.score-mid {
  background: rgba(241, 196, 15, 0.2);
  color: #f39c12;
}

.card-score.score-low {
  background: rgba(231, 76, 60, 0.2);
  color: #e74c3c;
}

.card-body {
  padding: 1rem 1.25rem;
}

.metric-row {
  display: flex;
  justify-content: space-between;
  padding: 0.5rem 0;
  border-bottom: 1px solid var(--border-color);
}

.metric-row:last-of-type {
  border-bottom: none;
}

.metric-label {
  color: var(--text-secondary);
  font-size: 0.9rem;
}

.metric-value {
  color: var(--text-primary);
  font-weight: 600;
}

.one-time-warning {
  background: rgba(241, 196, 15, 0.15);
  border-radius: 8px;
  padding: 0.75rem;
  margin: 0.75rem 0;
  font-size: 0.85rem;
  color: #f39c12;
}

.card-assessment {
  margin-top: 1rem;
  padding: 0.5rem 0.75rem;
  background: var(--card-bg);
  border-radius: 8px;
  text-align: center;
  font-weight: 600;
  color: var(--text-primary);
}

/* 수급 현황 */
.supply-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 0;
  border-bottom: 1px solid var(--border-color);
}

.supply-row:last-of-type {
  border-bottom: none;
}

.investor-type {
  color: var(--text-secondary);
  min-width: 50px;
}

.net-amount {
  font-weight: 700;
  flex: 1;
}

/* 수급 전용 색상 클래스 - 한국 주식 기준 */
/* 양수(순매수) → 빨간색, 음수(순매도) → 파란색 */
.net-amount.supply-positive {
  color: #e53e3e !important;  /* 빨간색: 순매수 */
}

.net-amount.supply-negative {
  color: #3182ce !important;  /* 파란색: 순매도 */
}

/* 일반 positive/negative도 유지 (호환성) */
.net-amount.positive {
  color: #e53e3e !important;
}

.net-amount.negative {
  color: #3182ce !important;
}

.buy-days {
  font-size: 0.8rem;
  color: var(--text-muted);
}

.sell-days {
  font-size: 0.85rem;
  color: #3182ce !important;  /* 파란색 - 매도 */
  font-weight: 700;
  background-color: rgba(49, 130, 206, 0.1);
  padding: 2px 6px;
  border-radius: 4px;
}

.supply-summary {
  padding: 0.75rem 0;
  text-align: center;
}

.both-buying {
  color: #e74c3c;
  font-weight: 600;
}

.both-selling {
  color: #3498db;
  font-weight: 600;
}

/* 기술적 분석 */
.tech-indicators {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.indicator-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.5rem 0;
  border-bottom: 1px solid var(--border-color);
}

.indicator-item:last-child {
  border-bottom: none;
}

.indicator-label {
  color: var(--text-secondary);
  font-size: 0.9rem;
}

.indicator-status {
  font-weight: 500;
  color: var(--text-muted);
}

.indicator-status.active {
  color: #27ae60;
}

.indicator-status .golden {
  color: #f1c40f;
}

.indicator-status .dead {
  color: #e74c3c;
}

/* RSI 스타일 (과열은 주황색 경고로 변경) */
.rsi-overbought,
.rsi-caution {
  color: #f59e0b; /* 주황색: 경고 (공포감 완화) */
}

.rsi-oversold {
  color: #27ae60;
}

.rsi-neutral {
  color: var(--text-secondary);
}

/* RSI 뱃지 */
.rsi-badge {
  display: inline-block;
  padding: 0.15rem 0.5rem;
  border-radius: 10px;
  font-size: 0.75rem;
  font-weight: 600;
  margin-left: 0.5rem;
}

.badge-caution {
  background: rgba(245, 158, 11, 0.15);
  color: #f59e0b;
  border: 1px solid rgba(245, 158, 11, 0.3);
}

.badge-opportunity {
  background: rgba(39, 174, 96, 0.15);
  color: #27ae60;
  border: 1px solid rgba(39, 174, 96, 0.3);
}

.badge-neutral {
  background: rgba(156, 163, 175, 0.15);
  color: var(--text-muted);
}

/* RSI 과열 경고 메시지 */
.rsi-warning {
  margin-top: 0.5rem;
  padding: 0.5rem 0.75rem;
  background: rgba(245, 158, 11, 0.1);
  border: 1px solid rgba(245, 158, 11, 0.3);
  border-radius: 6px;
  font-size: 0.8rem;
  color: #f59e0b;
}

/* MFI 스타일 */
.mfi-overbought {
  color: #e74c3c;
  font-weight: 600;
}

.mfi-oversold {
  color: #27ae60;
  font-weight: 600;
}

.mfi-neutral {
  color: var(--text-secondary);
}

/* 기술적 분석 섹션 */
.tech-section {
  margin-bottom: 1rem;
  padding-bottom: 0.75rem;
  border-bottom: 1px dashed var(--border-color);
}

.tech-section:last-of-type {
  border-bottom: none;
  margin-bottom: 0;
}

.tech-section-title {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--accent-color);
  margin-bottom: 0.5rem;
  padding-left: 0.25rem;
}

/* 볼린저 밴드 & MFI */
.indicator-value {
  font-weight: 500;
  color: var(--text-primary);
}

.indicator-item.wide {
  flex-direction: column;
  align-items: flex-start;
  gap: 0.25rem;
}

.indicator-status.squeeze {
  color: #ff6b35;
  font-weight: 700;
  animation: pulse 1.5s infinite;
}

.indicator-status.breakout {
  color: #00d2d3;
  font-weight: 700;
}

.mfi-description {
  font-size: 0.8rem;
  color: var(--text-muted);
  padding: 0.5rem;
  background: rgba(255, 255, 255, 0.03);
  border-radius: 4px;
  margin-top: 0.25rem;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

.collecting-status {
  text-align: center;
  color: #667eea;
  font-weight: 600;
  margin: 1rem 0;
  animation: pulse 1.5s infinite;
}

.tech-signal {
  display: flex;
  justify-content: center;
  gap: 0.5rem;
  padding: 0.75rem;
  margin-top: 0.75rem;
  background: var(--card-bg);
  border-radius: 8px;
}

.signal-label {
  color: var(--text-secondary);
}

.signal-value {
  font-weight: 700;
  color: var(--text-primary);
}

/* 종목명 클릭 가능 스타일 */
.stock-info.clickable {
  cursor: pointer;
  position: relative;
  transition: all 0.2s;
}

.stock-info.clickable:hover {
  background: var(--border-light);
}

.stock-info.clickable:hover .stock-name {
  color: var(--primary-start);
}

.detail-hint {
  display: none;
  font-size: 0.7rem;
  color: var(--primary-start);
  position: absolute;
  bottom: -2px;
  left: 0;
}

.stock-info.clickable:hover .detail-hint {
  display: block;
}

/* 모달 반응형 */
@media (max-width: 768px) {
  .diagnosis-modal {
    max-height: 95vh;
    border-radius: 15px 15px 0 0;
    margin-top: auto;
    /* 모바일에서도 flex 레이아웃 유지 */
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .modal-header {
    padding: 1rem 1.5rem;
    flex-shrink: 0;
  }

  .modal-header h2 {
    font-size: 1.1rem;
  }

  .diagnosis-modal .modal-content {
    padding: 1rem 1.5rem 1.5rem;
  }

  .verdict-icon {
    font-size: 2rem;
  }

  .verdict-label {
    font-size: 1.2rem;
  }

  .analysis-grid {
    grid-template-columns: 1fr;
  }
}
</style>
