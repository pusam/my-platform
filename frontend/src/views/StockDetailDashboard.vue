<template>
  <div class="trading-dashboard">
    <GlobalNav :subtitle="stockName" />
    <!-- 종목 검색 모달 (Ctrl+K) -->
    <StockSearchModal v-if="showSearch" @close="showSearch = false"
      @select="(code) => { showSearch = false; router.push('/stock/' + code) }" />
    <!-- Header -->
    <header class="dashboard-header">
      <div class="header-left">
        <BackButton :dark="true" />
        <div class="stock-info">
          <h1 class="stock-name">{{ stockName || '종목 검색' }}</h1>
          <div class="stock-meta">
            <span class="stock-code">{{ stockCode }}</span>
            <!-- 종합 신호 매칭 뱃지 — 클릭 시 5개 분해 -->
            <span v-if="compositeSignal && compositeSignal.totalCount > 0"
                  class="composite-badge" :class="getCompositeBadgeClass()">
              신호 {{ compositeSignal.matchedCount }}/{{ compositeSignal.totalCount }}
              <InfoTooltip title="5가지 신호 매칭" position="left">
                <p>의사결정 단순화 — 단일 신호 X, 여러 개 같은 방향이면 적중률 ↑</p>
                <div v-for="s in compositeSignal.signals" :key="s.id" class="tip-row">
                  <span :style="{ color: s.matched ? '#4ade80' : 'rgba(255,255,255,0.35)' }">
                    {{ s.matched ? '✓' : '○' }}
                  </span>
                  <b>{{ s.label }}</b>
                  <em v-if="s.matched && s.detail" style="opacity:0.85">{{ s.detail }}</em>
                </div>
              </InfoTooltip>
            </span>
          </div>
        </div>
        <!-- 보드 → 상세 왕복 네비 — 종합판단 보드에서 진입한 경우에만(sessionStorage), 직접 진입 시 미표시 -->
        <div v-if="boardNavState" class="board-nav" title="종합 판단 보드 종목 순서로 이동">
          <button class="bn-btn" :disabled="boardNavState.idx === 0" @click="goBoardNav(-1)">◀ 이전</button>
          <span class="bn-pos">보드 {{ boardNavState.idx + 1 }}/{{ boardNavState.total }}</span>
          <button class="bn-btn" :disabled="boardNavState.idx >= boardNavState.total - 1" @click="goBoardNav(1)">다음 ▶</button>
        </div>
        <button class="search-btn" @click="showSearch = true" title="종목 검색 (Ctrl+K)">
          🔍
        </button>
        <NotificationBell :dark="true" />
      </div>
      <div class="header-center">
        <div class="price-info" v-if="priceInfo">
          <span class="current-price">{{ formatPrice(priceInfo.currentPrice) }}원</span>
          <span class="change-info" :class="priceClass">
            {{ Number(priceInfo.changePrice) > 0 ? '+' : '' }}{{ formatPrice(priceInfo.changePrice) }}
            ({{ Number(priceInfo.changeRate) > 0 ? '+' : '' }}{{ formatChangeRate(priceInfo.changeRate) }}%)
          </span>
        </div>
      </div>
      <div class="header-right dual-score-header">
        <div class="ai-score-box" :class="aiScoreClass">
          <span class="score-label">단기 트레이딩</span>
          <span class="score-value">{{ aiAnalysis?.overallScore || '-' }}</span>
          <span class="score-badge" :class="recBadgeClass">{{ getRecommendationLabel(aiAnalysis?.recommendation) }}</span>
        </div>
        <div class="ai-score-box" :class="fundScoreClass" v-if="diagnosisData?.overallScore">
          <span class="score-label">중장기 펀더멘털</span>
          <span class="score-value">{{ diagnosisData.overallScore }}</span>
          <span class="score-badge" :class="fundVerdictBadgeClass">{{ getAdjustedVerdict(diagnosisData) }}</span>
        </div>
      </div>
    </header>

    <!-- ===== [요약] 시세·AI점수(헤더) + 결론 + 핵심요약 (P-IA 3단계) ===== -->
    <!-- 종합 결론 카드 — 룰 기반 한 줄 결론 + 체크리스트 트리거 (phase 13) -->
    <StockConclusionCard v-if="stockCode" :stock-code="stockCode" />

    <!-- 차트 해설 — 지표를 문장으로 엮은 관찰용 해설 (매수 신호 아님, 점수/봇 미편입) -->
    <ChartNarrativeCard v-if="stockCode" :stock-code="stockCode" />

    <!-- 핵심 요약 카드 (RSI/20일선/외인/기관/리스크/AI) — 요약존으로 이동 -->
    <QuickSummaryBar :has-data="hasData" :loading="loading"
                     :diagnosis-data="diagnosisData" :ai-analysis="aiAnalysis" />

    <!-- 데이터 갱신 상태 -->
    <div class="freshness-bar" v-if="hasData">
      <DataFreshness :lastUpdated="lastUpdated" :isRefreshing="isRefreshing" :nextRefreshIn="nextRefreshIn" @refresh="manualRefresh" />
    </div>

    <!-- 검색바 + 실시간 상태 — 데이터 로드 후엔 큰 검색바 숨김(헤더 🔍/Ctrl+K 모달이 대체, 슬림화).
         종목 미선택(빈 화면) 상태에서만 진입용으로 노출. -->
    <div class="control-section">
      <div v-if="!hasData" class="search-bar">
        <StockCodeInput
          v-model="searchQuery"
          placeholder="종목명 또는 종목코드 입력 (예: 삼성전자, 005930)"
          @enter="searchStock"
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

    <!-- 메인 탭 바 (종합 분석 / 투자자 동향) -->
    <div v-if="hasData" class="main-tab-bar">
      <button class="main-tab-btn" :class="{ active: mainTab === 'analysis' }" @click="mainTab = 'analysis'">
        📊 종합 분석
      </button>
      <button class="main-tab-btn" :class="{ active: mainTab === 'investor' }" @click="mainTab = 'investor'">
        🏛️ 투자자 동향
      </button>
      <button class="main-tab-btn" :class="{ active: mainTab === 'indicators' }" @click="mainTab = 'indicators'">
        📈 트레이딩 지표
      </button>
    </div>

    <!-- ===== [근거] 행동권고 (점수·수급·기술 근거는 아래 main-grid) ===== -->
    <!-- 행동 권고 헤드라인 (펀더멘털+AI+수급 종합) -->
    <StockBriefingHeadline
      v-if="hasData && !loading"
      :diagnosisData="diagnosisData"
      :aiAnalysis="aiAnalysis"
    />

    <!-- 로딩 -->
    <div v-if="loading" class="loading-overlay">
      <div class="loading-spinner"></div>
      <p>종합 데이터 분석 중...</p>
    </div>

    <!-- ========== 투자자 동향 탭 (분리: InvestorTrendTab.vue) ========== -->
    <InvestorTrendTab v-else-if="hasData && mainTab === 'investor'"
                      :key="stockCode" :stock-code="stockCode" />

    <!-- ========== 트레이딩 지표 탭 ========== -->
    <div v-else-if="hasData && mainTab === 'indicators'" class="indicators-tab-content">
      <TradingIndicatorsPage :embedded="true" />
    </div>

    <!-- ========== 종합 분석 탭 (기존 컨텐츠) ========== -->
    <!-- 메인 2컬럼 그리드 -->
    <div v-else-if="hasData" class="main-grid">
      <!-- ========== Left Column: 차트 영역 ========== -->
      <div class="left-column">
        <!-- 주가 차트 — chartFullscreen 이면 화면 전체 오버레이(같은 DOM, CSS 만 전환 = 데이터/토글 상태 유지) -->
        <div class="chart-section" :class="{ fullscreen: chartFullscreen }">
          <div class="section-header">
            <h2>주가 차트</h2>
            <div class="chart-toggles">
              <button v-for="p in chartPeriodOptions" :key="'period'+p"
                :class="['ind-toggle period-toggle', { active: chartPeriod === p }]"
                @click="chartPeriod = p"
                :title="p === 1 ? '당일 분봉 (5분봉 합성)' : p + '거래일 표시'">
                {{ p }}일
              </button>
              <!-- MA/볼린저/패턴은 일봉 데이터 기반 — 분봉(1일) 모드에선 숨김(데이터 없음, §4c) -->
              <template v-if="!isIntraday">
                <button v-for="ind in indicatorList" :key="ind.key"
                  :class="['ind-toggle', { active: activeIndicators[ind.key] }]"
                  :style="{ '--ind-color': ind.color }"
                  @click="activeIndicators[ind.key] = !activeIndicators[ind.key]">
                  {{ ind.label }}
                </button>
              </template>
              <button v-if="srLevelsAvailable.length"
                :class="['ind-toggle sr-toggle', { active: showSrLines }]"
                @click="showSrLines = !showSrLines"
                title="지지/저항 가로선 토글">
                S/R
              </button>
              <button v-if="!isIntraday && patternMarkersAvailable.length"
                :class="['ind-toggle pattern-toggle', { active: showPatternMarkers }]"
                @click="showPatternMarkers = !showPatternMarkers"
                title="패턴 마커 토글">
                패턴
              </button>
              <button v-if="chartChannel"
                :class="['ind-toggle channel-toggle', { active: showChannel }]"
                @click="showChannel = !showChannel"
                title="추세 채널(회귀 채널) 토글">
                채널
              </button>
              <button class="ind-toggle zoom-toggle"
                @click="chartFullscreen = !chartFullscreen"
                :title="chartFullscreen ? '전체화면 닫기 (Esc)' : '차트 크게 보기'">
                {{ chartFullscreen ? '✕ 닫기' : '⛶ 크게' }}
              </button>
            </div>
          </div>
          <!-- 분봉(1일) 로딩/빈 상태 안내 — 원인별 구분(§4c 위장 없음) -->
          <div v-if="isIntraday && intradayLoading" class="intraday-note">⏳ 당일 분봉 불러오는 중…</div>
          <div v-else-if="isIntraday && intradayError" class="intraday-note">
            ⚠️ 분봉 조회 실패 — 시세 서버(KIS)/네트워크 문제일 수 있습니다. 잠시 후 다시 시도하거나 일봉 보기(7/30일)를 이용하세요.
          </div>
          <div v-else-if="isIntraday && !displayCandles.length" class="intraday-note">
            당일 분봉 없음 — 장 시작 전(09:00 이전)이거나 휴장일입니다. 일봉 보기(7/30일)를 이용하세요.
          </div>
          <!-- HTS 차트 (lightweight-charts) — 십자선·축눈금·줌/팬 내장. 데이터/토글은 props 로만 전달. -->
          <div v-else class="hts-chart-wrap">
            <HtsChart
              :display-candles="displayCandles"
              :display-volumes="displayVolumes"
              :ma-series="maSeriesForChart"
              :bollinger="bollingerForChart"
              :sr-levels="srLevelsForChart"
              :channel="showChannel ? chartChannel : null"
              :channel-color="channelColor"
              :markers-input="markersForChart"
              :is-intraday="isIntraday"
              :today-ymd="todayYmd"
            />
          </div>
          <!-- 추세 채널 해설 (표시 전용 관찰 — 매매 신호 아님) -->
          <div v-if="showChannel && channelCaption" class="channel-caption"
               :class="'dir-' + chartChannel.direction.toLowerCase()">
            <strong v-if="breakoutText" class="breakout-badge"
                    :class="chartBreakout.type === 'UP_BREAKOUT' ? 'up' : 'down'">
              {{ breakoutText }}
            </strong>
            <span>{{ channelCaption }}</span>
          </div>
          <!-- 마지막 봉 꼬리 관찰 (망치형/유성형) — 표시 전용, 매매 신호 아님 -->
          <div v-if="tailSignal" class="tail-note" :class="tailSignal.type === 'HAMMER' ? 'up' : 'down'">
            {{ tailSignal.label }}
            <span class="tail-sub">· 마지막 {{ isIntraday ? '5분봉' : '일봉' }} 기준 — 관찰용</span>
          </div>
        </div>

        <!-- 핵심 재무 -->
        <div class="financial-section">
          <div class="section-header">
            <h2>핵심 재무 <span class="ttm-label">TTM</span></h2>
            <div class="investment-tags" v-if="financial?.investmentTags?.length">
              <span v-for="(tag, i) in financial.investmentTags" :key="i" class="inv-tag">{{ tag }}</span>
            </div>
          </div>
          <div class="financial-grid">
            <div class="fin-card">
              <span class="fin-label">PER</span>
              <span class="fin-value" :class="getPERClass(financial?.per)">
                {{ financial?.per?.toFixed(1) || '-' }}배
                <span v-if="financial?.forwardPer" class="forward-badge" :class="{ 'forward-improved': financial.forwardPer < financial.per }">Fwd {{ financial.forwardPer.toFixed(1) }}배</span>
              </span>
            </div>
            <div class="fin-card">
              <span class="fin-label">PBR</span>
              <span class="fin-value" :class="getPBRClass(financial?.pbr)">
                {{ financial?.pbr?.toFixed(2) || '-' }}배
                <span v-if="financial?.forwardPbr" class="forward-badge" :class="{ 'forward-improved': financial.forwardPbr < financial.pbr }">Fwd {{ financial.forwardPbr.toFixed(2) }}배</span>
              </span>
            </div>
            <div class="fin-card">
              <span class="fin-label">EPS</span>
              <span class="fin-value">
                {{ formatPrice(financial?.eps) || '-' }}원
                <span v-if="financial?.forwardEps" class="forward-badge">Fwd {{ formatPrice(financial.forwardEps) }}원</span>
              </span>
            </div>
            <div class="fin-card">
              <span class="fin-label">BPS</span>
              <span class="fin-value">
                {{ formatPrice(financial?.bps) || '-' }}원
                <span v-if="financial?.forwardBps" class="forward-badge">Fwd {{ formatPrice(financial.forwardBps) }}원</span>
              </span>
            </div>
            <div class="fin-card">
              <span class="fin-label">시가총액</span>
              <span class="fin-value">{{ formatMarketCap(financial?.marketCap) }}</span>
            </div>
            <div class="fin-card" v-if="financial?.foreignOwnership">
              <span class="fin-label">외국인 지분</span>
              <span class="fin-value">{{ financial.foreignOwnership?.toFixed(1) }}%</span>
            </div>
            <div class="fin-card" v-if="financial?.totalShareholderReturn">
              <span class="fin-label">TSR</span>
              <span class="fin-value tsr-value">
                {{ financial.totalShareholderReturn?.toFixed(1) }}%
                <span v-if="financial?.buybackInfo" class="buyback-badge">자사주</span>
              </span>
            </div>
            <div class="fin-card" v-if="financial?.dividendYield && financial.dividendYield > 0">
              <span class="fin-label">배당수익률</span>
              <span class="fin-value" :class="{ 'positive': financial.dividendYield > 3 }">
                {{ financial.dividendYield?.toFixed(1) }}%
              </span>
            </div>
          </div>
          <div class="data-source-note" v-if="financial?.forwardPer || financial?.forwardPbr">
            *Forward 지표: EPS 성장률 기반 자체 추정 | 목표주가: 네이버 금융 (FnGuide)
          </div>
        </div>

        <!-- Peer Group 비교 → 심화존(DetailSection)으로 이동 (P-IA) -->

        <!-- 관련 뉴스 (좌측 하단) -->
        <div class="news-section-left">
          <div class="section-header">
            <h2>관련 뉴스</h2>
            <span class="news-count">{{ dedupedNews.length }}건</span>
          </div>
          <div class="news-list" v-if="dedupedNews.length">
            <div v-for="(news, index) in dedupedNews" :key="index" class="news-item">
              <div class="news-content">
                <a :href="news.link" target="_blank" rel="noopener noreferrer">{{ truncate(news.title, 60) }}</a>
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
          <!-- volumePower null/미수집 → 0 전달: 게이지가 "데이터 없음" 상태 표시 (과거 || 100 폴백이 항상-100% 버그 원인) -->
          <VolumePowerGauge
            :volumePower="supplyDemand?.dataSource === '장전(초기화)' ? 0 : (Number(supplyDemand?.volumePower) || 0)"
            :signal="supplyDemand?.volumeSignal || 'NEUTRAL'"
            :dataSource="supplyDemand?.dataSource || ''"
          />

          <!-- 투자자별 수급 막대 차트 -->
          <div class="investor-section">
            <div class="supply-header">
              <!--
                '당일'을 제목에 박는다(2026-08-28). 상단 지표 바의 "외국인"은 5일 누적이라
                같은 화면에 이름이 같고 값이 다른 두 숫자가 있었다(실측 018260 — 211억 vs 20억).
              -->
              <h3>투자자별 수급 <small class="scope-tag">당일</small></h3>
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
                      :class="netBuyClass(supplyDemand?.foreignNetBuy)"
                      :style="{ width: getBarWidth(supplyDemand?.foreignNetBuy) + '%' }"
                    ></div>
                  </div>
                </div>
                <span class="bar-value" :class="netBuyClass(supplyDemand?.foreignNetBuy)">
                  {{ netBuyText(supplyDemand?.foreignNetBuy) }}
                </span>
              </div>
              <!-- 기관 -->
              <div class="investor-bar-row">
                <span class="bar-label">기관</span>
                <div class="bar-container">
                  <div class="bar-track">
                    <div
                      class="bar-fill"
                      :class="netBuyClass(supplyDemand?.instNetBuy)"
                      :style="{ width: getBarWidth(supplyDemand?.instNetBuy) + '%' }"
                    ></div>
                  </div>
                </div>
                <span class="bar-value" :class="netBuyClass(supplyDemand?.instNetBuy)">
                  {{ netBuyText(supplyDemand?.instNetBuy) }}
                </span>
              </div>
              <!-- 프로그램 -->
              <div class="investor-bar-row highlight">
                <span class="bar-label">프로그램</span>
                <div class="bar-container">
                  <div class="bar-track">
                    <div
                      class="bar-fill"
                      :class="netBuyClass(supplyDemand?.programNetBuy)"
                      :style="{ width: getBarWidth(supplyDemand?.programNetBuy) + '%' }"
                    ></div>
                  </div>
                </div>
                <span class="bar-value" :class="netBuyClass(supplyDemand?.programNetBuy)">
                  {{ netBuyText(supplyDemand?.programNetBuy) }}
                </span>
              </div>
            </div>
          </div>
        </div>

        <!-- Zone B: 안전 점수 게이지 + AI 전략 -->
        <div class="zone zone-b">
          <!-- 2단계 로딩 인디케이터 -->
          <div v-if="heavyLoading && !riskInfo && !aiAnalysis" class="heavy-loading-indicator">
            <div class="heavy-loading-spinner"></div>
            <span>리스크/AI 분석 로딩 중...</span>
          </div>
          <!-- 안전 점수 게이지 (원형) -->
          <div class="risk-gauge-section" :class="safetyStatusClass">
            <div class="gauge-header">
              <h2>안전 점수</h2>
              <span class="risk-badge" :class="safetyStatusClass">
                {{ getSafetyStatusText(riskInfo?.riskStatus) }}
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
                    stroke="url(#safetyGaugeGradient)"
                    stroke-width="16"
                    stroke-linecap="round"
                    :stroke-dasharray="gaugeArcLength"
                    :stroke-dashoffset="safetyGaugeDashOffset"
                    class="gauge-arc"
                  />
                  <defs>
                    <linearGradient id="safetyGaugeGradient" x1="0%" y1="0%" x2="100%" y2="0%">
                      <stop offset="0%" stop-color="#ef4444" />
                      <stop offset="50%" stop-color="#eab308" />
                      <stop offset="100%" stop-color="#22c55e" />
                    </linearGradient>
                  </defs>
                </svg>
                <div class="gauge-value">
                  <span class="score" :class="safetyStatusClass">{{ safetyScore ?? '-' }}</span>
                  <span class="label">/100</span>
                </div>
              </div>
              <div class="gauge-labels">
                <span class="danger">위험</span>
                <span class="warning">주의</span>
                <span class="safe">안전</span>
              </div>
            </div>

            <!-- 리스크 키워드 태그 -->
            <div v-if="riskInfo?.riskTags?.length" class="risk-tags">
              <span v-for="(tag, i) in riskInfo.riskTags" :key="i" class="risk-tag">{{ tag }}</span>
            </div>

            <!-- 매수 금지 경고 (조건부 설명) -->
            <div v-if="riskInfo?.riskStatus === 'DANGER' || riskInfo?.riskStatus === 'WARNING'" class="danger-warning" :class="riskInfo?.riskStatus === 'WARNING' ? 'warning-level' : ''">
              <span class="warning-icon">{{ riskInfo?.riskStatus === 'DANGER' ? '🚨' : '⚠️' }}</span>
              <div class="warning-text">
                <strong>{{ riskInfo?.riskStatus === 'DANGER' ? '매수 주의' : '주의 필요' }}</strong>
                <p>{{ safetyDescriptionText }}</p>
              </div>
            </div>
          </div>

          <!-- AI 매매 전략 (분리: AIStrategyCard.vue) -->
          <AIStrategyCard :ai-analysis="aiAnalysis"
                          :diagnosis-data="diagnosisData"
                          :current-price="priceInfo?.currentPrice" />
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

    <!-- ===== [보조/심화] 아래는 전부 차트·수급(main-grid) 뒤 — 2026-07-14 상세 화면 슬림화.
         이전엔 이 블록들이 차트보다 위에 쌓여 핵심이 밀렸다. 컴포넌트는 자체 fetch/접기(v-show 마운트
         유지)라 위치 이동만으로 동작 무변. ===== -->

    <!-- 리스크 체크 카드 (DART 공시 + 뉴스 + AI 분석) — 안전점수 게이지(그리드)의 상세 근거 -->
    <StockRiskCard
      v-if="hasData && stockName"
      :stockName="stockName"
      :stockCode="stockCode"
    />

    <!-- [심화] 볼륨·지지저항·패턴·관련종목 — 기본 접힘 (자식은 v-show 로 마운트 유지) -->
    <DetailSection v-if="hasData" title="🔬 심화 분석 (볼륨·지지/저항·패턴·관련종목·Peer)">
      <!-- Peer Group 비교 — 분리: PeerComparisonCard.vue (P2-10), 심화존 이동(P-IA) -->
      <PeerComparisonCard v-if="peerComparisons?.length"
                          :peer-comparisons="peerComparisons"
                          :sector-name="sectorName"
                          :sector-avg-pbr="sectorAvgPbr" />

      <!--
        조회 실패 안내 — 2026-08-27 표시층 감사 D.
        이 패널들은 실패해도 화면이 안 바뀌어 "신호 없음"과 "조회 실패"가 구분되지 않았다.
        빈 화면이 곧 "패턴 없음"으로 읽히던 것을 막는다(§4c). 분봉이 쓰던 패턴과 동일.
      -->
      <p v-if="failedPanels.length" class="panel-fail">
        ⚠ {{ failedPanels.join(' · ') }} 조회 실패 — <b>신호가 없는 것이 아니라 못 불러온 것</b>이다.
      </p>

      <!-- Volume Profile (가격대별 누적 거래량) — 분리: VolumeProfileCard.vue (P2-10) -->
      <VolumeProfileCard v-if="volumeProfile && volumeProfile.bins?.length > 0"
                         :volume-profile="volumeProfile" />

      <!-- 지지/저항 레벨 (피벗 클러스터링) — 분리: SupportResistanceCard.vue (P2-10) -->
      <SupportResistanceCard v-if="supportResistance && (supportResistance.resistance?.length > 0 || supportResistance.support?.length > 0)"
                             :support-resistance="supportResistance" />

      <!-- 관련 종목 (phase 28 분리 — RelatedStocksList.vue) -->
      <RelatedStocksList :stocks="relatedStocks" @select="goToRelatedStock" />

      <!-- 차트 패턴 검출 (phase 27 분리 — ChartPatternList.vue) -->
      <ChartPatternList :patterns="chartPatterns" />
    </DetailSection>

    <!-- 📜 신호 이력 (signal_outcome 90일 재사용) — 자체 fetch(heavy 계열, quick 지연 없음), n=0 미렌더 -->
    <SignalHistorySection v-if="hasData && stockCode" :stock-code="stockCode" />

    <!-- 📰 재료 이력 (stock_catalyst 30일 재사용, read-only) — 자체 fetch(heavy 계열, quick 지연 없음), n=0 미렌더 -->
    <CatalystHistorySection v-if="hasData && stockCode" :stock-code="stockCode" />

    <!-- 📄 최근 공시 (DART 3개월, 표시 전용) — 위험 키워드 외 일반 공시까지 원문 링크로 확인.
         조회 실패는 '확인 불가'로 명시(§4c — '공시 없음'과 구분). 자체 fetch(heavy 계열). -->
    <RecentDisclosuresSection v-if="hasData && stockCode" :stock-code="stockCode" :stock-name="stockName" />

    <!-- 펀더멘털 진단 상세 — 점수·판정은 헤더(중장기 펀더멘털 박스)에 이미 노출, 근거는 접이식(슬림화).
         DetailSection v-show 라 접혀도 마운트 유지(진단 로딩 타이밍 무변). -->
    <DetailSection v-if="hasData && diagnosisData" title="🩺 중장기 펀더멘털 진단 (상세 근거)">
      <FundamentalDiagnosisPanel :diagnosis-data="diagnosisData" :supply-demand="supplyDemand" />
    </DetailSection>

    <!-- 면책조항 -->
    <footer class="disclaimer" v-if="hasData">
      <p>본 분석은 AI 알고리즘에 의한 참고 자료이며, 투자 결정은 본인의 판단과 책임 하에 이루어져야 합니다.</p>
    </footer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import GlobalNav from '../components/GlobalNav.vue';
import BackButton from '../components/BackButton.vue';
import StockCodeInput from '../components/StockCodeInput.vue';
import InfoTooltip from '../components/InfoTooltip.vue';
import StockSearchModal from '../components/v2/StockSearchModal.vue';
import StockBriefingHeadline from '../components/v2/StockBriefingHeadline.vue';
import StockRiskCard from '../components/v2/StockRiskCard.vue';
import StockConclusionCard from '../components/v2/StockConclusionCard.vue';
import ChartNarrativeCard from '../components/v2/ChartNarrativeCard.vue';
import ChartPatternList from '../components/v2/ChartPatternList.vue';
import RelatedStocksList from '../components/v2/RelatedStocksList.vue';
import VolumeProfileCard from '../components/v2/VolumeProfileCard.vue';
import SupportResistanceCard from '../components/v2/SupportResistanceCard.vue';
import QuickSummaryBar from '../components/v2/QuickSummaryBar.vue';
import PeerComparisonCard from '../components/v2/PeerComparisonCard.vue';
import DetailSection from '../components/v2/DetailSection.vue';
import SignalHistorySection from '../components/v2/SignalHistorySection.vue';
import CatalystHistorySection from '../components/v2/CatalystHistorySection.vue';
import RecentDisclosuresSection from '../components/v2/RecentDisclosuresSection.vue';
import InvestorTrendTab from '../components/v2/InvestorTrendTab.vue';
import FundamentalDiagnosisPanel from '../components/v2/FundamentalDiagnosisPanel.vue';
import AIStrategyCard from '../components/v2/AIStrategyCard.vue';
import NotificationBell from '../components/NotificationBell.vue';
import VolumePowerGauge from '../components/VolumePowerGauge.vue';
import TradingIndicatorsPage from './TradingIndicatorsPage.vue';
import DataFreshness from '../components/DataFreshness.vue';
import HtsChart from '../components/v2/HtsChart.vue';
import apiClient, { stockDetailAPI, stockAPI, quantTaAPI } from '../utils/api';
import { netBuyClass, netBuyText } from '../utils/stockFormat';
import { toast } from '../utils/toast';
import { useChartCalculations } from '../composables/useChartCalculations';
import { channelComment, breakoutLabel } from '../utils/trendChannel';
import { toMarkerData } from '../utils/htsChartData';
import { detectTailSignal } from '../utils/candleAnatomy';

const route = useRoute();
const router = useRouter();

// 종목 검색 (Ctrl+K)
const showSearch = ref(false);
const onSearchKeydown = (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
    e.preventDefault();
    showSearch.value = true;
  }
  // 차트 전체화면은 Esc 로 닫기 (검색 모달이 떠 있으면 모달 자체 Esc 가 우선)
  if (e.key === 'Escape' && chartFullscreen.value && !showSearch.value) {
    chartFullscreen.value = false;
  }
};

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
const peerComparisons = ref(null);
const sectorAvgPbr = ref(null);
const sectorName = ref(null);
const chartPatterns = ref([]);  // 차트 패턴 검출 결과
const supportResistance = ref(null);  // 지지/저항 레벨
const volumeProfile = ref(null);  // Volume Profile (가격대별 누적 거래량)
const compositeSignal = ref(null);  // 5개 신호 종합 평가
const relatedStocks = ref([]);  // 관련 종목 (correlation 기반)

// 차트 좌표·스타일 계산 → useChartCalculations.js 로 분리 (P-IA ③-3차). 토글 상태는 아래에 유지.
// 차트 표시 기간 — HTS 스타일: 1일(당일 5분봉) / 7·30·60·120·200일(일봉, 가용 데이터 있을 때만 노출).
const chartPeriod = ref(30);
const chartPeriodOptions = computed(() => {
  const available = chartData.value?.candles?.length || 0;
  const opts = [1, 7, 30];
  for (const p of [60, 120, 200]) {
    if (available >= p) opts.push(p);   // 히스토리 그만큼 있을 때만(백엔드 최대 200봉, §4c 위장 없음)
  }
  return opts;
});
// 종목 이동으로 캔들이 줄어 60일 옵션이 사라지면 선택도 30으로 클램프
// (안 하면 60 잔존 → dense 봉폭·토글 활성 표시가 어긋난 유령 상태).
watch(chartPeriodOptions, (opts) => {
  if (!opts.includes(chartPeriod.value)) chartPeriod.value = 30;
});

// 1일 = 당일 분봉(백엔드가 5분봉 합성) — 선택/종목 변경 시 on-demand 조회(백엔드 2분 캐시).
const isIntraday = computed(() => chartPeriod.value === 1);
const intradayData = ref(null);
const intradayLoading = ref(false);
const intradayError = ref(false);   // true=조회 자체 실패(엔드포인트/네트워크), false=정상 응답(빈 결과=장전/휴장)

/**
 * best-effort 패널의 조회 실패 — 2026-08-27 표시층 감사 D.
 *
 * 이 패널들은 실패해도 화면이 안 바뀌어 "신호 없음"과 "조회 실패"가 구분되지 않았다.
 * .catch 가 console.warn 만 하고, res.data.success 가 false 여도 아무 일이 없었다.
 * 분봉(intradayError)이 쓰던 패턴을 그대로 확대한다 — 실패는 화면이 말해야 한다(§4c).
 *
 * 키는 패널 이름. true = 조회 실패(네트워크/예외/success:false). 빈 결과(정상)와 다르다.
 */
const panelError = ref({});

/** 실패한 패널의 사람이 읽을 이름 — 화면 한 줄 안내에 쓴다. */
const PANEL_LABELS = {
  chartPatterns: '차트 패턴',
  supportResistance: '지지/저항',
  volumeProfile: 'Volume Profile',
  compositeSignal: '종합 신호',
  relatedStocks: '관련 종목'
};
const failedPanels = computed(() =>
  Object.entries(panelError.value).filter(([, failed]) => failed).map(([k]) => PANEL_LABELS[k] || k)
);
let intradayFetchedFor = '';
const loadIntraday = async () => {
  if (!stockCode.value) return;
  intradayLoading.value = true;
  intradayError.value = false;
  try {
    const res = await apiClient.get(`/stock/${stockCode.value}/intraday-candles`);
    intradayData.value = res.data?.data || null;
    // 백엔드 fetchFailed = 장중 KIS 수집 실패(HTTP 200) — '장전/휴장 빈결과' 오안내 방지
    intradayError.value = !!intradayData.value?.fetchFailed;
    intradayFetchedFor = stockCode.value;
  } catch (e) {
    intradayData.value = null;
    intradayError.value = true;   // 조회 실패 — 빈 결과(정상)와 구분해 안내
  } finally {
    intradayLoading.value = false;
  }
};
watch([chartPeriod, stockCode], () => {
  if (isIntraday.value && (intradayFetchedFor !== stockCode.value || !intradayData.value)) {
    loadIntraday();
  }
});

// 차트 데이터 소스 전환 — 1일은 분봉 DTO(ChartData 동형, maLine 없음 → MA/BB 오버레이 자동 미표시),
// 일봉 기간은 기존 chartData 슬라이스. 분봉은 전체 표시(999 = 슬라이스 무력화).
const effectiveChartData = computed(() => (isIntraday.value ? intradayData.value : chartData.value));
const chartDisplayCount = computed(() => (isIntraday.value ? 999 : chartPeriod.value));

// HtsChart(lightweight-charts) 마이그레이션(07-15) 후 이 화면은 데이터 계층만 소비 —
// 구 DIV/SVG 렌더 헬퍼(getCandleStyle 등)·±2% 필터 계열(chartSrLines/chartPatternMarkers)은 미사용이라 구조분해 제외.
const {
  displayCandles, displayVolumes, chartChannel, chartBreakout
} = useChartCalculations(effectiveChartData, supportResistance, chartPatterns, chartDisplayCount);

// 추세 채널 표시 상태 + 해설 — 방향색은 매매 신호색 한국 관례(상승=적/하락=청/횡보=회) 동기.
const showChannel = ref(true);
const channelCaption = computed(() => channelComment(chartChannel.value, isIntraday.value ? '5분' : '일'));
const breakoutText = computed(() => breakoutLabel(chartBreakout.value));
const channelColor = computed(() => {
  const dir = chartChannel.value?.direction;
  return dir === 'UP' ? '#ef4444' : dir === 'DOWN' ? '#3b82f6' : '#9ca3af';
});
// ===== HtsChart(lightweight-charts) props — useChartCalculations 데이터 계층을 렌더러에 전달 =====
// 오늘 날짜(KST yyyy-MM-dd) — 분봉 'HH:mm' → epoch 변환 기준(당일 분봉이라 오늘).
const todayYmd = computed(() =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date()));
// 이동평균 — 토글 ON 인 것만, 분봉 모드엔 없음(백엔드 maLine 미제공, §4c).
const MA_KEY_MAP = { ma5: 'maLine5', ma20: 'maLine20', ma60: 'maLine60', ma120: 'maLine120' };
const maSeriesForChart = computed(() => {
  if (isIntraday.value || !chartData.value) return {};
  const out = {};
  for (const [tog, dataKey] of Object.entries(MA_KEY_MAP)) {
    if (activeIndicators[tog] && chartData.value[dataKey]) out[tog] = chartData.value[dataKey];
  }
  return out;
});
const bollingerForChart = computed(() => {
  if (isIntraday.value || !activeIndicators.bb || !chartData.value) return null;
  const { bbUpper, bbLower } = chartData.value;
  return (bbUpper && bbLower) ? { upper: bbUpper, lower: bbLower } : null;
});
// S/R 레벨 — 표시 봉 가격대 근처만(멀리 있는 레벨이 스케일 흐리지 않게). 원 chartSrLines 필터 계승.
// available(토글 무관)과 forChart(토글 반영)를 분리 — 토글 버튼 노출 게이팅이 실제 렌더와 같은
// 필터를 봐야 "선은 그려지는데 끌 버튼이 없는" 상태가 안 생긴다.
const srLevelsAvailable = computed(() => {
  if (!supportResistance.value || !displayCandles.value.length) return [];
  const lo = Math.min(...displayCandles.value.map(c => c.low)) * 0.9;
  const hi = Math.max(...displayCandles.value.map(c => c.high)) * 1.1;
  const inRange = (p) => Number(p) >= lo && Number(p) <= hi;
  const out = [];
  for (const l of (supportResistance.value.resistance || [])) {
    if (inRange(l.price)) out.push({ price: l.price, type: 'resistance', strength: l.strength });
  }
  for (const l of (supportResistance.value.support || [])) {
    if (inRange(l.price)) out.push({ price: l.price, type: 'support', strength: l.strength });
  }
  return out;
});
const srLevelsForChart = computed(() => (showSrLines.value ? srLevelsAvailable.value : []));
const patternMarkersAvailable = computed(() =>
  isIntraday.value ? [] : toMarkerData(chartPatterns.value || [], displayCandles.value));
const markersForChart = computed(() =>
  (showPatternMarkers.value && !isIntraday.value) ? chartPatterns.value : []);

// 차트 전체화면 (⛶ 크게 보기) — 같은 DOM 에 CSS 오버레이만 전환, Esc 로 닫기.
const chartFullscreen = ref(false);

// 마지막 봉 꼬리 관찰 배지(망치형/유성형) — 표시 전용, 산식 미편입.
const tailSignal = computed(() => detectTailSignal(displayCandles.value[displayCandles.value.length - 1]));

// 2단계 로딩 상태
const heavyLoading = ref(false);

// 펀더멘털 진단
const diagnosisData = ref(null);
const diagnosisLoading = ref(false);

// 메인 탭
const mainTab = ref('analysis');

// 실시간 갱신
const autoRefresh = ref(true);
const lastUpdated = ref(null);
const isRefreshing = ref(false);
const nextRefreshIn = ref(15);
let refreshInterval = null;
let countdownTimer = null;

const hasData = computed(() => priceInfo.value !== null);

// ---- 보드 → 상세 왕복 네비 (SectionJudgmentBoard 가 sessionStorage 로 전달, 순수 프론트) ----
// 보드 행 클릭이 새 탭을 열 때 sessionStorage 복사본으로 상속됨. 직접 진입(키 없음/목록 밖 종목)은 미표시.
const BOARD_NAV_KEY = 'judgmentBoard.nav';
const boardNavCodes = ref([]);
const loadBoardNav = () => {
  try {
    const raw = sessionStorage.getItem(BOARD_NAV_KEY);
    const parsed = raw ? JSON.parse(raw) : null;
    boardNavCodes.value = Array.isArray(parsed?.codes) ? parsed.codes.filter(c => typeof c === 'string') : [];
  } catch { boardNavCodes.value = []; }
};
const boardNavState = computed(() => {
  if (!stockCode.value || boardNavCodes.value.length < 2) return null;
  const idx = boardNavCodes.value.indexOf(stockCode.value);
  return idx < 0 ? null : { idx, total: boardNavCodes.value.length };
});
const goBoardNav = (delta) => {
  const st = boardNavState.value;
  if (!st) return;
  const next = boardNavCodes.value[st.idx + delta];
  if (!next) return;
  // 같은 컴포넌트 재사용 라우트(/stock/:code)라 URL 만 바꾸면 리로드 안 됨 — 명시적으로 재조회
  router.replace(`/stock/${next}`);
  searchQuery.value = next;
  searchStock();
};

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

// 차트 지표 토글
const activeIndicators = reactive({
  ma5: true, ma20: true, ma60: false, ma120: false, bb: false
});
// 지지/저항 + 패턴 마커 토글 (default ON — 결과 있으면 자동 노출)
const showSrLines = ref(true);
const showPatternMarkers = ref(true);
const indicatorList = [
  { key: 'ma5', label: 'MA5', color: '#f59e0b' },
  { key: 'ma20', label: 'MA20', color: '#3b82f6' },
  { key: 'ma60', label: 'MA60', color: '#10b981' },
  { key: 'ma120', label: 'MA120', color: '#a855f7' },
  { key: 'bb', label: '볼린저', color: '#6b7280' }
];

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

const fundScoreClass = computed(() => {
  const score = diagnosisData.value?.overallScore;
  if (!score) return '';
  if (score >= 70) return 'high';
  if (score >= 50) return 'medium';
  return 'low';
});

// 헤더 점수 뱃지(행동 라벨)만 매매 신호색(매수=빨강/매도=파랑, common.css 원칙) —
// 점수 숫자·테두리(high/medium/low)는 품질 스케일이라 초록 계열 유지.
const recBadgeClass = computed(() => {
  const map = {
    'BUY': 'sb-buy', 'TRADING_BUY': 'sb-buy',
    'WAIT_AND_BUY': 'sb-neutral', 'HOLD': 'sb-neutral',
    'SELL': 'sb-sell'
  };
  return map[aiAnalysis.value?.recommendation] || '';
});

const fundVerdictBadgeClass = computed(() => {
  const d = diagnosisData.value;
  if (!d) return '';
  // getAdjustedVerdict 와 동일 규칙 — RSI 과열이면 매수 verdict 라도 '관망' 표시라 중립색
  if (isRsiOverbought(d) && (d.verdictLevel === 'STRONG_BUY' || d.verdictLevel === 'BUY')) return 'sb-neutral';
  // StockDiagnosisDto.VerdictLevel 전체: STRONG_BUY/BUY/NEUTRAL/CAUTION/AVOID
  const map = {
    'STRONG_BUY': 'sb-strong-buy', 'BUY': 'sb-buy',
    'NEUTRAL': 'sb-neutral',
    'CAUTION': 'sb-caution', 'AVOID': 'sb-sell'
  };
  return map[d.verdictLevel] || '';
});

// scoreDiffComment / consensusBarWidth / aiRecommendationClass → AIStrategyCard.vue 로 이동 (P-IA ③ 후속)

// ★ 뉴스 중복제거 (제목 기준 Set 필터링)
const dedupedNews = computed(() => {
  const news = riskInfo.value?.news;
  if (!news || !news.length) return [];
  const seen = new Set();
  return news.filter(n => {
    const title = (n.title || '').trim();
    if (!title || seen.has(title)) return false;
    seen.add(title);
    return true;
  }).slice(0, 8);
});

// ★ 안전 점수: 펀더멘털 종합 점수 기반 + 리스크 감점
const safetyScore = computed(() => {
  // 1순위: 진단 종합 점수 (재무건전성 + 수급 + 기술적분석)
  const diagScore = diagnosisData.value?.overallScore;
  const risk = riskInfo.value?.riskScore;

  if (diagScore != null) {
    // 리스크 점수가 있으면 감점 (위험 공시/뉴스 반영)
    const riskPenalty = risk != null ? Math.floor(risk * 0.3) : 0;
    return Math.max(0, Math.min(100, diagScore - riskPenalty));
  }

  // 진단 데이터 없으면 리스크 반전으로 폴백
  if (risk !== null && risk !== undefined) return 100 - risk;
  return null;
});

const safetyStatusClass = computed(() => {
  const score = safetyScore.value;
  if (score === null) {
    // 점수 없으면 리스크 상태로 폴백
    const status = riskInfo.value?.riskStatus;
    if (status === 'SAFE') return 'safe';
    if (status === 'WARNING') return 'warning';
    if (status === 'DANGER') return 'danger';
    return '';
  }
  if (score >= 65) return 'safe';
  if (score >= 40) return 'warning';
  return 'danger';
});

// ★ 안전 점수 조건부 설명 텍스트 (펀더멘털 점수 연동)
const safetyDescriptionText = computed(() => {
  const fundamental = diagnosisData.value?.overallScore;
  const safety = safetyScore.value;
  const trading = aiAnalysis.value?.overallScore;

  // 펀더멘털 우수 + 안전점수 낮음 → 단기 과열
  if (fundamental && fundamental >= 70 && safety !== null && safety <= 40) {
    return '단기 과열 주의 — 펀더멘털은 우수하나 단기 급등으로 차익실현 매물이 나올 수 있습니다.';
  }
  // 트레이딩 점수도 낮고 펀더멘털도 낮음 → 전반 위험
  if (fundamental && fundamental <= 40 && safety !== null && safety <= 30) {
    return '복합 리스크 — 재무 지표와 수급 모두 부진하여 손실 위험이 높습니다.';
  }
  // 수급 과열 (트레이딩 높고 안전 낮음)
  if (trading && trading >= 60 && safety !== null && safety <= 30) {
    return '수급 과열 주의 — 단기 매수세가 강하나 위험 공시/뉴스가 감지되어 급반전 가능성이 있습니다.';
  }
  // WARNING 레벨
  if (riskInfo.value?.riskStatus === 'WARNING') {
    return '일부 리스크 요인이 감지되었습니다. 공시/뉴스를 확인하고 신중하게 접근하세요.';
  }
  // 기본
  return '리스크가 높아 신중한 판단이 필요합니다.';
});

const supplySourceClass = computed(() => {
  const source = supplyDemand.value?.dataSource;
  if (source === '실시간') return 'live';
  if (source === '일별(DB)') return 'daily';
  if (source === '장전(초기화)') return 'pre-market';
  return '';
});

// aiRecommendationClass → AIStrategyCard.vue 로 이동 (P-IA ③ 후속)

// Recommendation 라벨 한글 변환 (헤더 단기 점수 뱃지도 사용 → 부모 유지, 카드와 의도적 중복)
const getRecommendationLabel = (rec) => {
  const map = {
    'BUY': 'BUY',
    'TRADING_BUY': 'Trading Buy',
    'WAIT_AND_BUY': 'Wait & Buy',
    'HOLD': 'HOLD',
    'SELL': 'SELL'
  };
  return map[rec] || rec || '-';
};

// 핵심 요약 카드 헬퍼(getQs*) → QuickSummaryBar.vue 로 이동 (P2-10)

// Peer Group 바 너비 계산 (PBR 기준, max 2.0)
// getPeerBarWidth / getPeerBarClass → PeerComparisonCard.vue 로 이동 (P2-10)

// 안전 점수 게이지 계산
const gaugeArcLength = computed(() => 251.2);
const safetyGaugeDashOffset = computed(() => {
  if (safetyScore.value === null) return gaugeArcLength.value;
  const progress = safetyScore.value / 100;
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
      } else {
        // 2. API 검색 시도
        try {
          const searchResult = await stockAPI.searchStocks(code);
          if (searchResult.data.success && searchResult.data.data?.length > 0) {
            code = searchResult.data.data[0].stockCode;
            searchedName = searchResult.data.data[0].stockName;
            stockName.value = searchedName;
          } else {
            toast.warning('종목을 찾을 수 없습니다. 정확한 종목명이나 6자리 코드를 입력해주세요.');
            loading.value = false;
            return;
          }
        } catch (apiError) {
          console.warn('[StockDetail] API 검색 실패:', apiError);
          toast.error('종목 검색에 실패했습니다. 정확한 종목명이나 6자리 코드를 입력해주세요.');
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
    // 종목 변경 시 분석 탭으로 복귀 — 투자자 탭(InvestorTrendTab)은 :key=stockCode 로 새로 마운트되며 자체 로드
    mainTab.value = 'analysis';

    await fetchAllData(code, searchedName);

    // 자동 갱신 시작
    if (autoRefresh.value) {
      startAutoRefresh();
    }
  } catch (error) {
    console.error('종목 조회 오류:', error);
    toast.error('종목 조회에 실패했습니다.');
  } finally {
    loading.value = false;
  }
};

// 모든 데이터 가져오기 (점진적 로딩: Quick → Heavy + Diagnosis 병렬)
const fetchAllData = async (code, searchedName) => {
  try {
    // ★ 1단계: Quick (시세/수급/차트/재무) — 빠르게 화면 표시
    const quickRes = await stockDetailAPI.getQuick(code);

    if (quickRes.data.success && quickRes.data.data) {
      const data = quickRes.data.data;
      const localName = CODE_TO_NAME[code];
      const apiName = data.stockName && data.stockName !== code ? data.stockName : null;
      stockName.value = localName || apiName || searchedName || code;
      priceInfo.value = data.price;
      supplyDemand.value = data.supplyDemand;
      financial.value = data.financial;
      chartData.value = data.chartData;
      lastUpdated.value = new Date();
    }

    // 차트 패턴 + 지지/저항 + Volume Profile (병렬 fire-and-forget)
    // ⚠ 실패 상태는 아래 로더들을 띄우기 '전에' 비운다 — 로더 사이에 두면 먼저 끝난 결과를 지운다.
    panelError.value = {};
    chartPatterns.value = [];
    supportResistance.value = null;
    volumeProfile.value = null;
    quantTaAPI.patterns(code)
      .then(res => {
        if (res.data?.success) { chartPatterns.value = res.data.data || []; panelError.value = { ...panelError.value, chartPatterns: false }; }
        else panelError.value = { ...panelError.value, chartPatterns: true };
      })
      .catch(err => { console.warn('차트 패턴 실패:', err.message); panelError.value = { ...panelError.value, chartPatterns: true }; });
    quantTaAPI.supportResistance(code)
      .then(res => {
        if (res.data?.success) { supportResistance.value = res.data.data; panelError.value = { ...panelError.value, supportResistance: false }; }
        else panelError.value = { ...panelError.value, supportResistance: true };
      })
      .catch(err => { console.warn('지지/저항 실패:', err.message); panelError.value = { ...panelError.value, supportResistance: true }; });
    quantTaAPI.volumeProfile(code)
      .then(res => {
        if (res.data?.success) { volumeProfile.value = res.data.data; panelError.value = { ...panelError.value, volumeProfile: false }; }
        else panelError.value = { ...panelError.value, volumeProfile: true };
      })
      .catch(err => { console.warn('Volume Profile 실패:', err.message); panelError.value = { ...panelError.value, volumeProfile: true }; });
    compositeSignal.value = null;
    relatedStocks.value = [];
    quantTaAPI.compositeSignal(code)
      .then(res => {
        if (res.data?.success) { compositeSignal.value = res.data.data; panelError.value = { ...panelError.value, compositeSignal: false }; }
        else panelError.value = { ...panelError.value, compositeSignal: true };
      })
      .catch(err => { console.warn('종합 신호 실패:', err.message); panelError.value = { ...panelError.value, compositeSignal: true }; });
    quantTaAPI.relatedStocks(code, 5)
      .then(res => {
        if (res.data?.success) { relatedStocks.value = res.data.data || []; panelError.value = { ...panelError.value, relatedStocks: false }; }
        else panelError.value = { ...panelError.value, relatedStocks: true };
      })
      .catch(err => { console.warn('관련 종목 실패:', err.message); panelError.value = { ...panelError.value, relatedStocks: true }; });

    // ★ 2단계: Heavy (리스크/AI/피어) + Diagnosis 병렬 — 백그라운드 로딩
    heavyLoading.value = true;
    const [heavyRes, diagnosisRes] = await Promise.allSettled([
      stockDetailAPI.getHeavy(code),
      stockDetailAPI.getDiagnosis(code)
    ]);

    // Heavy 처리
    if (heavyRes.status === 'fulfilled' && heavyRes.value.data.success && heavyRes.value.data.data) {
      const data = heavyRes.value.data.data;
      riskInfo.value = data.risk;
      aiAnalysis.value = data.aiAnalysis;
      peerComparisons.value = data.peerComparisons;
      sectorAvgPbr.value = data.sectorAvgPbr;
      sectorName.value = data.sectorName;
      // Heavy에서 상세 재무(배당/Forward/투자태그)가 오면 Quick 경량 재무를 덮어씀
      if (data.financial) financial.value = data.financial;
    }

    // Diagnosis 처리
    if (diagnosisRes.status === 'fulfilled' && diagnosisRes.value.data.success) {
      diagnosisData.value = diagnosisRes.value.data.data;
    } else {
      diagnosisData.value = null;
    }
  } catch (error) {
    console.error('데이터 로드 오류:', error);
  } finally {
    heavyLoading.value = false;
  }
};

// 실시간 갱신 (체결강도, 가격 등) — Quick API 사용으로 빠르게
const refreshRealtimeData = async () => {
  if (!stockCode.value || !autoRefresh.value) return;

  isRefreshing.value = true;
  try {
    const response = await stockDetailAPI.getQuick(stockCode.value);
    if (response.data.success && response.data.data) {
      const data = response.data.data;
      // 실시간 데이터만 갱신
      priceInfo.value = data.price;
      supplyDemand.value = data.supplyDemand;
      lastUpdated.value = new Date();
    }
  } catch (error) {
    console.error('실시간 갱신 오류:', error);
  } finally {
    isRefreshing.value = false;
    nextRefreshIn.value = 15;
  }
};

const manualRefresh = () => {
  if (stockCode.value) {
    refreshRealtimeData();
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
  if (countdownTimer) clearInterval(countdownTimer);
  refreshInterval = setInterval(() => {
    if (!document.hidden) refreshRealtimeData();
  }, 15000);
  countdownTimer = setInterval(() => {
    if (!document.hidden && nextRefreshIn.value > 0) nextRefreshIn.value--;
  }, 1000);
};

const stopAutoRefresh = () => {
  if (refreshInterval) {
    clearInterval(refreshInterval);
    refreshInterval = null;
  }
  if (countdownTimer) {
    clearInterval(countdownTimer);
    countdownTimer = null;
  }
};

// 포맷터
const formatPrice = (price) => {
  if (!price) return '-';
  return Number(price).toLocaleString('ko-KR');
};

// changeRate 미수신 시 "NaN%" 노출 방지 (Number(undefined)=NaN)
const formatChangeRate = (rate) => {
  const n = Number(rate);
  return Number.isNaN(n) ? '-' : n.toFixed(2);
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

// 안전 점수 상태 텍스트 (리스크 반전)
const getSafetyStatusText = (status) => {
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

// 펀더멘털 진단 헬퍼/포맷터는 FundamentalDiagnosisPanel.vue 로 이동 (P-IA ③-2차).
// (단, 헤더 중장기 점수 뱃지가 쓰는 getAdjustedVerdict/isRsiOverbought 는 아래에 유지)

// ===== 차트 패턴 helpers =====
const PATTERN_ICONS = {
  DOUBLE_TOP: '📉', DOUBLE_BOTTOM: '📈',
  HEAD_AND_SHOULDERS: '🏔️', INVERSE_HEAD_AND_SHOULDERS: '🪨',
  TRIANGLE_ASCENDING: '📈', TRIANGLE_DESCENDING: '📉', TRIANGLE_SYMMETRIC: '⚖️',
  CUP_AND_HANDLE: '☕'
};
const getCpsIcon = (type) => PATTERN_ICONS[type] || '📊';
const getCpsConfidenceLabel = (c) => ({ HIGH: '높음', MEDIUM: '보통', LOW: '낮음' }[c] || c || '보통');
const getCpsSignalLabel = (s) => ({ BULLISH: '상승 신호', BEARISH: '하락 신호', NEUTRAL: '관찰' }[s] || s || '중립');
// getSrStrengthLabel → SupportResistanceCard.vue 로 이동 (P2-10)

// ===== 종합 신호 뱃지 helpers =====
const getCompositeBadgeClass = () => {
  if (!compositeSignal.value) return '';
  const m = compositeSignal.value.matchedCount;
  if (m >= 4) return 'cb-strong';
  if (m >= 3) return 'cb-medium';
  if (m >= 1) return 'cb-weak';
  return 'cb-none';
};

// ===== 관련 종목 helpers =====
const getCorrClass = (corr) => {
  const c = Number(corr);
  if (c >= 0.8) return 'corr-strong';
  if (c >= 0.65) return 'corr-medium';
  return 'corr-weak';
};
const goToRelatedStock = (code) => {
  searchQuery.value = code;
  searchStock();
};

// Volume Profile helpers → VolumeProfileCard.vue 로 이동 (P2-10)

// ★ 헤더(중장기 점수 뱃지)가 쓰는 getAdjustedVerdict 의 의존 헬퍼 — 패널과 의도적 중복 유지.
//    rsiStatus 는 StockDiagnosisDto 의 technicalAnalysis 안에만 있다 (최상위 X).
const isRsiOverbought = (data) => {
  return data?.technicalAnalysis?.rsiStatus === '과열';
};

const getAdjustedVerdict = (data) => {
  if (!data) return '분석 중';
  if (isRsiOverbought(data) && (data.verdictLevel === 'STRONG_BUY' || data.verdictLevel === 'BUY')) {
    return '관망';
  }
  return data.verdict || '분석 중';
};

// URL 파라미터 처리
onMounted(() => {
  document.addEventListener('keydown', onSearchKeydown);
  loadBoardNav();   // 보드 진입 여부 확인(sessionStorage) — 없으면 네비 미표시
  const code = route.query.code || route.params.stockCode;
  if (code) {
    searchQuery.value = code;
    searchStock();
  }
});

onUnmounted(() => {
  stopAutoRefresh();
  document.removeEventListener('keydown', onSearchKeydown);
});
</script>

<style scoped>
.trading-dashboard {
  min-height: 100vh;
  background: linear-gradient(135deg, #0f0f23 0%, #1a1a3e 100%);
  color: #fff;
  padding: 20px;
}

.search-btn {
  background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.1);
  border-radius: 8px; padding: 6px 10px; cursor: pointer; font-size: 14px;
  color: rgba(255,255,255,0.6); transition: all 0.15s; margin-left: 8px;
}
.search-btn:hover { background: rgba(255,255,255,0.15); color: #fff; }

/* 보드 → 상세 왕복 네비 (종합판단 보드 진입 시에만 표시) */
.board-nav {
  display: flex; align-items: center; gap: 6px;
  background: rgba(56, 189, 248, 0.08);
  border: 1px solid rgba(56, 189, 248, 0.25);
  border-radius: 8px; padding: 4px 8px; white-space: nowrap;
}
.bn-btn {
  background: transparent; border: none; cursor: pointer;
  color: #7dd3fc; font-size: 12px; font-weight: 600; padding: 2px 4px;
}
.bn-btn:hover:not(:disabled) { color: #e0f2fe; }
.bn-btn:disabled { opacity: 0.35; cursor: default; }
.bn-pos { font-size: 11px; color: rgba(255, 255, 255, 0.65); font-variant-numeric: tabular-nums; }

/* Header */
.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px;
  background: rgba(30, 30, 60, 0.95);
  border-radius: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 16px;
  position: sticky;
  top: var(--gnb-height);  /* common.css 의 단일 소스 — 모바일 자동 40px */
  z-index: 100;
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

/* 등락색 토큰 통일 — 같은 화면의 돌파배지/꼬리/패턴(#f87171 계열)과 톤 일치 */
.change-info.positive { color: var(--stock-up, #f87171); }
.change-info.positive::before { content: '▲ '; font-size: 0.85em; }
.change-info.negative { color: var(--stock-down, #60a5fa); }
.change-info.negative::before { content: '▼ '; font-size: 0.85em; }
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
/* 행동 라벨(추천/verdict) 신호색 — 매수=빨강/매도=파랑(한국 관례, common.css --signal-*).
   점수 숫자·박스 테두리는 품질 스케일이라 초록 계열 유지 — 이 구분을 깨지 말 것. */
.score-badge.sb-strong-buy { color: #fff; background: var(--signal-strong-buy, #ef4444); }
.score-badge.sb-buy { color: var(--signal-buy, #f87171); background: rgba(239, 68, 68, 0.15); }
.score-badge.sb-neutral { color: var(--signal-neutral, #a3a3a3); background: rgba(255, 255, 255, 0.1); }
.score-badge.sb-caution { color: var(--warning, #fbbf24); background: rgba(251, 191, 36, 0.14); }
.score-badge.sb-sell { color: var(--signal-sell, #60a5fa); background: rgba(59, 130, 246, 0.15); }

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
  background: linear-gradient(135deg, var(--primary-start), #764ba2);
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
  border: 1px solid var(--border-color, rgba(255, 255, 255, 0.08));
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
  border-top-color: var(--primary-start);
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

/* HTS 차트(lightweight-charts) 래퍼 — autoSize 가 이 높이를 관찰해 캔버스를 맞춘다 */
.hts-chart-wrap {
  height: 300px;
  background: rgba(0,0,0,0.2);
  border-radius: 8px;
  padding: 6px;
}

/* 차트 전체화면(⛶ 크게 보기) — 같은 DOM 을 고정 오버레이로 확대(lightweight-charts autoSize 가 리사이즈) */
.chart-section.fullscreen {
  position: fixed;
  inset: 0;
  z-index: 1200;
  border-radius: 0;
  background: #14142a;
  overflow-y: auto;
  padding: 24px 28px;
}
.chart-section.fullscreen .hts-chart-wrap {
  height: calc(100vh - 200px);
  min-height: 360px;
}
.chart-section.fullscreen .channel-caption { font-size: 13px; }

/* SR / 패턴 토글 */
.ind-toggle.sr-toggle.active { background: rgba(239,68,68,0.18); border-color: rgba(239,68,68,0.4); color: #f87171; }
.ind-toggle.pattern-toggle.active { background: rgba(168,85,247,0.18); border-color: rgba(168,85,247,0.4); color: #c084fc; }
.ind-toggle.channel-toggle.active { background: rgba(20,184,166,0.18); border-color: rgba(20,184,166,0.4); color: #2dd4bf; }

/* 추세 채널 해설 캡션 — 표시 전용 관찰 문구 */
.channel-caption {
  margin-top: 6px;
  padding: 5px 10px;
  font-size: 11.5px;
  line-height: 1.4;
  border-radius: 6px;
  border: 1px solid rgba(255,255,255,0.08);
  background: rgba(255,255,255,0.03);
  color: rgba(255,255,255,0.55);
}
.channel-caption.dir-up { border-left: 3px solid #ef4444; }
.channel-caption.dir-down { border-left: 3px solid #3b82f6; }
.channel-caption.dir-flat { border-left: 3px solid #9ca3af; }
.channel-caption .breakout-badge {
  display: block;
  margin-bottom: 2px;
  font-weight: 700;
}
.channel-caption .breakout-badge.up { color: #f87171; }
.channel-caption .breakout-badge.down { color: #60a5fa; }

/* 기간 토글 + 60봉 dense 모드 (봉 폭 축소) */
.ind-toggle.period-toggle.active { background: rgba(255,255,255,0.12); border-color: rgba(255,255,255,0.35); color: rgba(255,255,255,0.85); }
.scope-tag {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 500;
  color: rgba(255,255,255,0.45);
}

.panel-fail {
  margin: 0 0 10px;
  padding: 7px 11px;
  font-size: 12px;
  line-height: 1.5;
  color: #fbbf24;
  background: rgba(251, 191, 36, 0.08);
  border: 1px solid rgba(251, 191, 36, 0.3);
  border-radius: 6px;
}
.panel-fail b { color: #fcd34d; }

.intraday-note {
  margin-bottom: 8px;
  padding: 6px 10px;
  font-size: 12px;
  color: rgba(255,255,255,0.55);
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 6px;
}

/* 마지막 봉 꼬리 관찰 배지 — 채널 캡션과 같은 톤 */
.tail-note {
  margin-top: 6px;
  padding: 5px 10px;
  font-size: 11.5px;
  line-height: 1.4;
  border-radius: 6px;
  border: 1px solid rgba(255,255,255,0.08);
  background: rgba(255,255,255,0.03);
  color: rgba(255,255,255,0.6);
}
.tail-note.up { border-left: 3px solid #f87171; }
.tail-note.down { border-left: 3px solid #60a5fa; }
.tail-note .tail-sub { opacity: 0.55; font-size: 10.5px; }

/* 차트 지표 토글 버튼 */
.chart-toggles {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
.ind-toggle {
  padding: 4px 10px;
  font-size: 11px;
  font-weight: 600;
  border: 1px solid rgba(255,255,255,0.12);
  border-radius: 6px;
  background: transparent;
  color: rgba(255,255,255,0.35);
  cursor: pointer;
  transition: all 0.15s;
  -webkit-tap-highlight-color: transparent;
}
.ind-toggle:hover {
  border-color: rgba(255,255,255,0.25);
  color: rgba(255,255,255,0.6);
  background: rgba(255,255,255,0.04);
}
.ind-toggle.active {
  border-color: var(--ind-color);
  color: var(--ind-color);
}
.ind-toggle.active[style*="--ind-color: #f59e0b"] { background: rgba(245,158,11,0.18); }
.ind-toggle.active[style*="--ind-color: #3b82f6"] { background: rgba(59,130,246,0.18); }
.ind-toggle.active[style*="--ind-color: #10b981"] { background: rgba(16,185,129,0.18); }
.ind-toggle.active[style*="--ind-color: #a855f7"] { background: rgba(168,85,247,0.18); }
.ind-toggle.active[style*="--ind-color: #6b7280"] { background: rgba(107,114,128,0.18); }

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
.fin-value.positive { color: var(--stock-up, #f87171); }
.fin-value.negative { color: var(--stock-down, #60a5fa); }

/* Forward 지표 배지 */
.forward-badge {
  display: inline-block;
  font-size: 0.65rem;
  color: #a78bfa;
  background: rgba(167, 139, 250, 0.15);
  padding: 1px 6px;
  border-radius: 8px;
  margin-left: 6px;
  font-weight: 500;
  vertical-align: middle;
}

.forward-badge.forward-improved {
  color: #22c55e;
  background: rgba(34, 197, 94, 0.15);
}

/* .ttm-label → FundamentalDiagnosisPanel.vue 로 이동 (P-IA ③-2차) */

/* 핵심 투자 포인트 태그 */
.investment-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.inv-tag {
  font-size: 0.7rem;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 12px;
  background: rgba(34, 197, 94, 0.15);
  color: #22c55e;
  border: 1px solid rgba(34, 197, 94, 0.3);
  white-space: nowrap;
}

/* Peer Group(.peer-* / .sector-avg-* / .avg-line-indicator) 스타일 → PeerComparisonCard.vue 로 이동 (P2-10) */

/* TSR / Buyback */
.tsr-value {
  color: #22c55e;
}

.buyback-badge {
  display: inline-block;
  font-size: 0.6rem;
  padding: 1px 5px;
  border-radius: 6px;
  background: rgba(34, 197, 94, 0.15);
  color: #4ade80;
  margin-left: 4px;
  vertical-align: middle;
}

/* 리스크 태그 */
.risk-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.risk-tag {
  font-size: 0.7rem;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 12px;
  background: rgba(239, 68, 68, 0.12);
  color: #f87171;
  border: 1px solid rgba(239, 68, 68, 0.25);
  white-space: nowrap;
}


/* 데이터 출처 노트 */
.data-source-note {
  font-size: 0.6rem;
  color: rgba(255,255,255,0.5);
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px solid var(--border-light);
}

/* 안전 점수 WARNING 레벨 */
.danger-warning.warning-level {
  background: rgba(234, 179, 8, 0.1);
  border-color: rgba(234, 179, 8, 0.3);
}
.danger-warning.warning-level strong {
  color: #eab308;
}

/* Zone A - Investor Section */
.investor-section {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--border-color, rgba(255, 255, 255, 0.08));
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

.bar-value.positive { color: var(--stock-up, #f87171); }
.bar-value.negative { color: var(--stock-down, #60a5fa); }

/* Zone B - Risk & AI */
.risk-gauge-section {
  padding: 16px;
  border-radius: 12px;
  background: linear-gradient(135deg, #1a1a3a 0%, #0f0f23 100%);
  border: 2px solid var(--surface-panel-strong, #2a2a4a);
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
  border-bottom: 1px solid var(--border-light);
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

/* ========== Dual Score Header ========== */
.dual-score-header {
  display: flex;
  gap: 12px;
  align-items: stretch;
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

  /* 헤더 컴팩트 — 3행 구조: [←/종목명/검색/알림] / [가격] / [점수2개] */
  .dashboard-header {
    flex-direction: column;
    text-align: center;
    gap: 8px;
    padding: 12px;
  }

  /* 좌측: 가로 정렬로 되돌림 (기본 mobile 규칙이 column 이라 명시 필요) */
  .header-left {
    flex-direction: row;
    width: 100%;
    align-items: center;
    gap: 8px;
  }
  .stock-info {
    flex: 1;
    min-width: 0;
    align-items: flex-start;
    overflow: hidden;
  }
  .stock-name {
    font-size: 1.2rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .stock-code { font-size: 0.78rem; }
  /* "돌아가기" 텍스트는 모바일에서 숨기고 아이콘만 */
  .back-button span { display: none; }
  .back-button { padding: 6px 8px; }

  /* 가운데: 가격 */
  .header-center { width: 100%; }
  .current-price { font-size: 1.4rem; }
  .change-info { font-size: 0.9rem; }

  /* 우측: 두 점수 박스를 가로로 (기본 .dual-score-header column 규칙 override) */
  .dual-score-header {
    flex-direction: row;
    width: 100%;
    gap: 8px;
  }
  .ai-score-box {
    flex: 1;
    min-width: 0;
    padding: 8px 10px;
  }
  .score-value { font-size: 1.6rem; }
  .score-label { font-size: 0.7rem; }
  .score-badge { font-size: 0.7rem; }

  .search-bar { flex-direction: column; }
  .realtime-status { flex-wrap: wrap; gap: 8px; }

  /* .reasons-section 반응형 → AIStrategyCard.vue 로 이동 (P-IA ③ 후속) */

  /* .alerts-section / .fund-tabs 반응형 → FundamentalDiagnosisPanel.vue 로 이동 (P-IA ③-2차) */
  /* .inv-* 반응형 → InvestorTrendTab.vue 로 이동 (P-IA 후속) */

  /* 메인 탭 바 — 작은 폰 패딩/폰트 축소 */
  .main-tab-btn { padding: 9px 10px; font-size: 13px; }
}

@media (max-width: 480px) {
  .trading-dashboard { padding: 10px; }
  .dashboard-header { padding: 10px; }
  .stock-name { font-size: 1rem; }
  .current-price { font-size: 1.2rem; }
  .change-info { font-size: 0.85rem; }
  .score-value { font-size: 1.3rem; }
  .score-label { font-size: 0.65rem; }
  .score-badge { font-size: 0.65rem; padding: 1px 6px; }
  .ai-score-box { padding: 6px 8px; gap: 2px; }

  /* 메인 탭 — 더 컴팩트 */
  .main-tab-btn { padding: 8px 6px; font-size: 12px; }

  /* (차트 .inv-chart-wrapper 반응형 → InvestorTrendTab.vue 로 이동, P-IA 후속) */
  /* (피어 비교 바 반응형 → PeerComparisonCard.vue 로 이동, P2-10) */

  /* 재무정보 그리드 — 너무 좁아 한 줄로 */
  .financial-grid { grid-template-columns: 1fr; gap: 6px; }
}

/* ========== 종합 신호 뱃지 ========== */
.stock-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.composite-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 10px;
  border: 1px solid;
  font-variant-numeric: tabular-nums;
}
.composite-badge.cb-strong { background: rgba(34,197,94,0.18); color: #4ade80; border-color: rgba(34,197,94,0.4); }
.composite-badge.cb-medium { background: rgba(234,179,8,0.18); color: #facc15; border-color: rgba(234,179,8,0.4); }
.composite-badge.cb-weak { background: rgba(249,115,22,0.18); color: #fb923c; border-color: rgba(249,115,22,0.4); }
.composite-badge.cb-none { background: rgba(156,163,175,0.12); color: rgba(255,255,255,0.4); border-color: rgba(156,163,175,0.2); }

/* ========== 관련 종목 섹션 ========== */
.related-section {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px;
  padding: 14px 16px;
  margin-bottom: 12px;
}
.related-header { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.related-icon { font-size: 18px; }
.related-title { margin: 0; color: rgba(255,255,255,0.9); font-size: 15px; font-weight: 600; }
.related-list { display: flex; flex-direction: column; gap: 4px; }
.related-row {
  display: grid;
  grid-template-columns: 1fr auto auto;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: rgba(255,255,255,0.03);
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}
.related-row:hover { background: rgba(99,102,241,0.1); }
.related-name { color: rgba(255,255,255,0.95); font-weight: 600; font-size: 13px; }
.related-code { color: rgba(255,255,255,0.4); font-size: 11px; font-variant-numeric: tabular-nums; }
.related-corr {
  font-size: 11px; padding: 2px 8px; border-radius: 8px; font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.related-corr.corr-strong { background: rgba(34,197,94,0.18); color: #4ade80; }
.related-corr.corr-medium { background: rgba(234,179,8,0.18); color: #facc15; }
.related-corr.corr-weak { background: rgba(156,163,175,0.18); color: #d1d5db; }

/* Volume Profile(.vp-*) / 지지·저항(.sr-*) 섹션 스타일 → 각 컴포넌트로 이동 (P2-10)
   VolumeProfileCard.vue · SupportResistanceCard.vue */

/* ========== 차트 패턴 검출 섹션 ========== */
.chart-patterns-section {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px;
  padding: 14px 16px;
  margin-bottom: 12px;
}
.cps-header { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.cps-header-icon { font-size: 18px; }
.cps-title { margin: 0; color: rgba(255,255,255,0.9); font-size: 15px; font-weight: 600; flex: 1; }
.cps-disclaimer {
  font-size: 11px; color: rgba(255,255,255,0.4);
  padding: 2px 8px; background: rgba(255,255,255,0.05);
  border-radius: 10px;
}
.cps-list { display: flex; flex-direction: column; gap: 8px; }
.cps-card {
  background: rgba(255,255,255,0.04);
  border-left: 3px solid rgba(255,255,255,0.2);
  border-radius: 8px;
  padding: 10px 12px;
}
.cps-card.sig-bullish { border-left-color: #ef4444; }   /* 한국 관행: 상승=빨강 */
.cps-card.sig-bearish { border-left-color: #3b82f6; }   /* 하락=파랑 */
.cps-card.sig-neutral { border-left-color: #9ca3af; }
.cps-card-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 6px; }
.cps-card-icon { font-size: 16px; }
.cps-card-label { color: rgba(255,255,255,0.95); font-weight: 600; font-size: 14px; flex: 1; min-width: 0; }
.cps-badge { font-size: 11px; padding: 2px 8px; border-radius: 10px; white-space: nowrap; }
.cps-confidence.cf-high   { background: rgba(34,197,94,0.18); color: #4ade80; }
.cps-confidence.cf-medium { background: rgba(234,179,8,0.18); color: #facc15; }
.cps-confidence.cf-low    { background: rgba(156,163,175,0.18); color: #d1d5db; }
.cps-signal.sg-bullish { background: rgba(239,68,68,0.18); color: #f87171; }
.cps-signal.sg-bearish { background: rgba(59,130,246,0.18); color: #60a5fa; }
.cps-signal.sg-neutral { background: rgba(156,163,175,0.18); color: #d1d5db; }
.cps-card-desc {
  margin: 0 0 6px 0; color: rgba(255,255,255,0.7); font-size: 13px; line-height: 1.5;
}
.cps-dates { font-size: 11px; color: rgba(255,255,255,0.45); font-variant-numeric: tabular-nums; }
.cps-ref { margin-left: 4px; }

/* ========== 메인 탭 바 ========== */
/* 핵심 요약 카드(.quick-summary-bar / .qs-*) 스타일 → QuickSummaryBar.vue 로 이동 (P2-10) */

.main-tab-bar {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
  background: rgba(0, 0, 0, 0.3);
  border-radius: 12px;
  padding: 4px;
}

.main-tab-btn {
  flex: 1;
  padding: 12px 20px;
  background: transparent;
  border: none;
  border-radius: 10px;
  color: #888;
  font-size: 1rem;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.3s;
}

.main-tab-btn:hover { color: #ccc; background: var(--border-light); }
.main-tab-btn.active {
  color: #fff;
  background: linear-gradient(135deg, rgba(102,126,234,0.3), rgba(118,75,162,0.3));
  box-shadow: 0 4px 12px rgba(102,126,234,0.2);
}

/* ========== 투자자 동향 탭 스타일 ========== */
/* 투자자 동향 탭 스타일 → InvestorTrendTab.vue 로 이동 (P-IA 후속) */

/* 2단계 로딩 인디케이터 */
.heavy-loading-indicator {
  display: flex; align-items: center; gap: 10px;
  padding: 16px 20px; margin-bottom: 12px;
  background: rgba(99, 102, 241, 0.1);
  border: 1px solid rgba(99, 102, 241, 0.2);
  border-radius: 12px;
  color: rgba(255,255,255,0.7); font-size: 13px;
}
.heavy-loading-spinner {
  width: 18px; height: 18px;
  border: 2px solid rgba(99, 102, 241, 0.3);
  border-top-color: #6366f1;
  border-radius: 50%;
  animation: heavy-spin 0.8s linear infinite;
}
@keyframes heavy-spin { to { transform: rotate(360deg); } }

.freshness-bar { display: flex; justify-content: flex-end; margin: -4px 0 12px; }
@media (max-width: 480px) { .freshness-bar { justify-content: center; } }
</style>
