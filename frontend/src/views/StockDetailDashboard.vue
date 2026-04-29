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
          <span class="stock-code">{{ stockCode }}</span>
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
            ({{ Number(priceInfo.changeRate) > 0 ? '+' : '' }}{{ Number(priceInfo.changeRate)?.toFixed(2) }}%)
          </span>
        </div>
      </div>
      <div class="header-right dual-score-header">
        <div class="ai-score-box" :class="aiScoreClass">
          <span class="score-label">단기 트레이딩</span>
          <span class="score-value">{{ aiAnalysis?.overallScore || '-' }}</span>
          <span class="score-badge">{{ getRecommendationLabel(aiAnalysis?.recommendation) }}</span>
        </div>
        <div class="ai-score-box" :class="fundScoreClass" v-if="diagnosisData?.overallScore">
          <span class="score-label">중장기 펀더멘털</span>
          <span class="score-value">{{ diagnosisData.overallScore }}</span>
          <span class="score-badge">{{ getAdjustedVerdict(diagnosisData) }}</span>
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

    <!-- 메인 탭 바 (종합 분석 / 투자자 동향) -->
    <div v-if="hasData" class="main-tab-bar">
      <button class="main-tab-btn" :class="{ active: mainTab === 'analysis' }" @click="mainTab = 'analysis'">
        📊 종합 분석
      </button>
      <button class="main-tab-btn" :class="{ active: mainTab === 'investor' }" @click="switchToInvestorTab">
        🏛️ 투자자 동향
      </button>
      <button class="main-tab-btn" :class="{ active: mainTab === 'indicators' }" @click="mainTab = 'indicators'">
        📈 트레이딩 지표
      </button>
    </div>

    <!-- 행동 권고 헤드라인 (펀더멘털+AI+수급 종합) -->
    <StockBriefingHeadline
      v-if="hasData && !loading"
      :diagnosisData="diagnosisData"
      :aiAnalysis="aiAnalysis"
    />

    <!-- 리스크 체크 카드 (DART 공시 + 뉴스 + AI 분석) -->
    <StockRiskCard
      v-if="hasData && stockName"
      :stockName="stockName"
      :stockCode="stockCode"
    />

    <!-- 핵심 요약 카드 (항상 고정) -->
    <div v-if="hasData && !loading" class="quick-summary-bar">
      <div class="qs-item">
        <span class="qs-label">RSI</span>
        <span class="qs-value" :class="getQsRsiClass()">
          {{ diagnosisData?.technicalAnalysis?.rsi14 != null ? Number(diagnosisData.technicalAnalysis.rsi14).toFixed(0) : '-' }}
        </span>
        <span class="qs-badge" :class="getQsRsiClass()">
          {{ getQsRsiLabel() }}
        </span>
      </div>
      <div class="qs-item">
        <span class="qs-label">20일선</span>
        <span class="qs-value" :class="getQsMaClass()">
          {{ getQsMaPosition() }}
        </span>
        <span class="qs-sub">{{ getQsMaDisparity() }}</span>
      </div>
      <div class="qs-item">
        <span class="qs-label">외국인</span>
        <span class="qs-value" :class="diagnosisData?.supplyDemand?.foreignNet5Days >= 0 ? 'qs-positive' : 'qs-negative'">
          {{ getQsForeignLabel() }}
        </span>
        <span class="qs-sub">{{ getQsForeignAmount() }}</span>
      </div>
      <div class="qs-item">
        <span class="qs-label">기관</span>
        <span class="qs-value" :class="diagnosisData?.supplyDemand?.instNet5Days >= 0 ? 'qs-positive' : 'qs-negative'">
          {{ getQsInstLabel() }}
        </span>
        <span class="qs-sub">{{ getQsInstAmount() }}</span>
      </div>
      <div class="qs-item">
        <span class="qs-label">리스크</span>
        <span class="qs-badge" :class="getQsRiskClass()">
          {{ getQsRiskLabel() }}
        </span>
      </div>
      <div class="qs-item">
        <span class="qs-label">AI 점수</span>
        <span class="qs-value">{{ aiAnalysis?.overallScore || '-' }}</span>
        <span class="qs-badge" :class="'qs-rec-' + (aiAnalysis?.recommendation || 'hold').toLowerCase()">
          {{ getRecommendationLabel(aiAnalysis?.recommendation) }}
        </span>
      </div>
    </div>
    <div v-else-if="loading" class="quick-summary-bar skeleton">
      <div class="qs-item qs-skeleton" v-for="i in 6" :key="i"><div class="qs-skeleton-bar"></div></div>
    </div>

    <!-- 로딩 -->
    <div v-if="loading" class="loading-overlay">
      <div class="loading-spinner"></div>
      <p>종합 데이터 분석 중...</p>
    </div>

    <!-- ========== 투자자 동향 탭 ========== -->
    <div v-else-if="hasData && mainTab === 'investor'" class="investor-tab-content">
      <div v-if="investorLoading" class="loading-overlay">
        <div class="loading-spinner"></div>
        <p>투자자 매매 동향 조회 중...</p>
      </div>

      <template v-else>
        <!-- 안내 문구 -->
        <div class="inv-info-banner">
          <span>💡</span>
          <span>이 데이터는 당일 순매수/매도 상위 20위 내에 진입한 날의 기록만 조회됩니다.</span>
        </div>

        <!-- 주가 vs 누적 순매수 추이 차트 -->
        <div v-if="invHasChartData" class="inv-section">
          <h2 class="inv-section-title">📊 주가 vs 누적 순매수 추이</h2>
          <div class="inv-period-selector">
            <button v-for="period in invChartPeriods" :key="period.value"
                    :class="['inv-period-btn', { active: invSelectedPeriod === period.value }]"
                    @click="invSelectedPeriod = period.value">
              {{ period.label }}
            </button>
          </div>
          <div class="inv-chart-wrapper">
            <Line :data="invChartData" :options="invChartOptions" />
          </div>
          <div class="inv-chart-legend">
            <span class="inv-legend-item"><span class="inv-legend-color" style="background:#888"></span>주가</span>
            <span class="inv-legend-item"><span class="inv-legend-color" style="background:#e53e3e"></span>외국인</span>
            <span class="inv-legend-item"><span class="inv-legend-color" style="background:#48bb78"></span>기관</span>
            <span class="inv-legend-item"><span class="inv-legend-color" style="background:#9f7aea"></span>연기금</span>
          </div>
        </div>

        <!-- 장중 수급 추이 -->
        <div v-if="invHasSurgeData" class="inv-section">
          <h2 class="inv-section-title">📈 장중 수급 추이 (오늘)</h2>
          <div class="inv-investor-tabs">
            <button v-for="type in invInvestorTypes" :key="type.value"
                    :class="['inv-tab-btn', { active: invSelectedInvestor === type.value }]"
                    @click="invSelectedInvestor = type.value">
              {{ type.icon }} {{ type.label }}
            </button>
          </div>
          <div v-if="invCurrentSurgeTrend.length > 0" class="inv-surge-grid">
            <div v-for="item in invCurrentSurgeTrend" :key="item.snapshotTime" class="inv-surge-item">
              <div class="inv-time-badge">{{ invFormatTime(item.snapshotTime) }}</div>
              <div class="inv-surge-details">
                <div class="inv-detail-row">
                  <span class="label">순위</span>
                  <span class="value">#{{ item.currentRank }}</span>
                </div>
                <div class="inv-detail-row highlight">
                  <span class="label">누적 순매수</span>
                  <span class="value" :class="invGetAmountClass(item.netBuyAmount)">
                    {{ invFormatAmount(item.netBuyAmount) }}
                  </span>
                </div>
                <div class="inv-detail-row" v-if="invHasAmountChange(item.amountChange)">
                  <span class="label">변화량</span>
                  <span class="value" :class="invGetAmountClass(item.amountChange)">
                    {{ invFormatAmountWithSign(item.amountChange) }}
                  </span>
                </div>
                <div class="inv-detail-row" v-if="item.currentPrice">
                  <span class="label">현재가</span>
                  <span class="value">{{ formatPrice(item.currentPrice) }}원</span>
                </div>
                <div class="inv-detail-row" v-if="item.changeRate">
                  <span class="label">등락률</span>
                  <span class="value" :class="invGetAmountClass(item.changeRate)">
                    {{ invFormatRate(item.changeRate) }}
                  </span>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="inv-no-data">선택한 투자자의 장중 데이터가 없습니다.</div>
        </div>

        <!-- 일별 매매 동향 -->
        <div v-if="invRecentDailyTrades.length > 0" class="inv-section">
          <h2 class="inv-section-title">📅 일별 매매 동향 (최근 30일)</h2>
          <div class="inv-daily-trades">
            <div v-for="day in invRecentDailyTrades" :key="day.tradeDate" class="inv-daily-card">
              <div class="inv-date-header">{{ invFormatDate(day.tradeDate) }}</div>
              <div class="inv-investor-grid">
                <div v-for="inv in [
                  { key: 'foreign', label: '외국인', icon: '🌍', color: '#e53e3e' },
                  { key: 'institution', label: '기관', icon: '🏢', color: '#48bb78' },
                  { key: 'pension', label: '연기금', icon: '💎', color: '#9f7aea' }
                ]" :key="inv.key" class="inv-investor-item" :style="{ borderLeftColor: inv.color }">
                  <div class="inv-investor-label">{{ inv.icon }} {{ inv.label }}</div>
                  <div class="inv-amounts">
                    <div class="inv-amount-row">
                      <span class="label">매수:</span>
                      <span class="value">{{ invFormatAmount(day[inv.key]?.buyAmount) }}</span>
                    </div>
                    <div class="inv-amount-row">
                      <span class="label">매도:</span>
                      <span class="value">{{ invFormatAmount(day[inv.key]?.sellAmount) }}</span>
                    </div>
                    <div class="inv-amount-row net">
                      <span class="label">순매수:</span>
                      <span class="value" :class="invGetAmountClass(day[inv.key]?.netBuyAmount)">
                        {{ invFormatAmount(day[inv.key]?.netBuyAmount) }}
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div v-if="!invHasSurgeData && !invHasChartData && !investorLoading" class="inv-no-data">
          <p>💡 해당 종목의 투자자 매매 데이터가 없습니다.</p>
          <p class="hint">상위 50개 종목에 포함된 경우에만 데이터가 수집됩니다.</p>
        </div>
      </template>
    </div>

    <!-- ========== 트레이딩 지표 탭 ========== -->
    <div v-else-if="hasData && mainTab === 'indicators'" class="indicators-tab-content">
      <TradingIndicatorsPage :embedded="true" />
    </div>

    <!-- ========== 종합 분석 탭 (기존 컨텐츠) ========== -->
    <!-- 메인 2컬럼 그리드 -->
    <div v-else-if="hasData" class="main-grid">
      <!-- ========== Left Column: 차트 영역 ========== -->
      <div class="left-column">
        <!-- 주가 차트 -->
        <div class="chart-section">
          <div class="section-header">
            <h2>주가 차트</h2>
            <div class="chart-toggles">
              <button v-for="ind in indicatorList" :key="ind.key"
                :class="['ind-toggle', { active: activeIndicators[ind.key] }]"
                :style="{ '--ind-color': ind.color }"
                @click="activeIndicators[ind.key] = !activeIndicators[ind.key]">
                {{ ind.label }}
              </button>
            </div>
          </div>
          <div class="candlestick-container">
            <div class="candlestick-chart" ref="candleChartRef">
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
              <!-- 이동평균선 + 볼린저밴드 SVG 오버레이 -->
              <svg v-if="displayCandles.length" class="chart-overlay" viewBox="0 0 100 100" preserveAspectRatio="none">
                <polyline v-if="activeIndicators.ma5 && maLinePath('maLine5')"
                  :points="maLinePath('maLine5')" fill="none" stroke="#f59e0b" stroke-width="1.5" opacity="0.8"/>
                <polyline v-if="activeIndicators.ma20 && maLinePath('maLine20')"
                  :points="maLinePath('maLine20')" fill="none" stroke="#3b82f6" stroke-width="1.5" opacity="0.8"/>
                <polyline v-if="activeIndicators.ma60 && maLinePath('maLine60')"
                  :points="maLinePath('maLine60')" fill="none" stroke="#10b981" stroke-width="1.5" opacity="0.8"/>
                <polyline v-if="activeIndicators.ma120 && maLinePath('maLine120')"
                  :points="maLinePath('maLine120')" fill="none" stroke="#a855f7" stroke-width="1.5" opacity="0.8"/>
                <template v-if="activeIndicators.bb">
                  <polyline v-if="maLinePath('bbUpper')"
                    :points="maLinePath('bbUpper')" fill="none" stroke="#6b7280" stroke-width="1" stroke-dasharray="4,3" opacity="0.6"/>
                  <polyline v-if="maLinePath('bbLower')"
                    :points="maLinePath('bbLower')" fill="none" stroke="#6b7280" stroke-width="1" stroke-dasharray="4,3" opacity="0.6"/>
                </template>
              </svg>
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

        <!-- Peer Group 비교 -->
        <div class="peer-section" v-if="peerComparisons?.length">
          <div class="peer-header">
            <h2>섹터 Peer Group</h2>
            <span v-if="sectorName" class="sector-name-badge">{{ sectorName }}</span>
          </div>
          <div class="peer-chart">
            <div
              v-for="(peer, i) in peerComparisons"
              :key="i"
              class="peer-bar-row"
              :class="{ current: peer.isCurrent }"
            >
              <span class="peer-name">{{ peer.stockName }}</span>
              <div class="peer-bar-container">
                <div
                  class="peer-bar-fill"
                  :style="{ width: getPeerBarWidth(peer.pbr) + '%' }"
                  :class="getPeerBarClass(peer.pbr)"
                ></div>
                <div
                  v-if="sectorAvgPbr"
                  class="sector-avg-line"
                  :style="{ left: getPeerBarWidth(sectorAvgPbr) + '%' }"
                ></div>
              </div>
              <span class="peer-pbr">PBR {{ peer.pbr?.toFixed(2) }}배</span>
              <span class="peer-div">배당 {{ peer.dividendYield?.toFixed(1) }}%</span>
            </div>
            <div v-if="sectorAvgPbr" class="sector-avg-label">
              <span class="avg-line-indicator"></span>
              업종 평균 PBR {{ sectorAvgPbr?.toFixed(2) }}배
            </div>
          </div>
        </div>

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
          <VolumePowerGauge
            :volumePower="supplyDemand?.dataSource === '장전(초기화)' ? 0 : (supplyDemand?.volumePower || 100)"
            :signal="supplyDemand?.volumeSignal || 'NEUTRAL'"
            :dataSource="supplyDemand?.dataSource || ''"
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

          <!-- AI 매매 전략 -->
          <div class="ai-strategy-section">
            <h2>AI 매매 전략</h2>

            <!-- 차트 시그널 칩 (규칙 기반 확정 신호) -->
            <div v-if="aiAnalysis?.chartSignals?.length" class="chart-signal-chips">
              <span
                v-for="sig in aiAnalysis.chartSignals"
                :key="sig.code"
                class="chip"
                :class="'chip-' + (sig.tone || 'neutral')"
                :title="sig.detail"
              >{{ sig.label }}</span>
            </div>

            <!-- AI 차트 해석 카드 -->
            <div v-if="aiAnalysis?.chartAnalysis" class="chart-analysis-card">
              <div class="chart-analysis-head">
                <span class="chart-analysis-icon">📈</span>
                <span class="chart-analysis-title">AI 차트 해석</span>
              </div>
              <p class="chart-analysis-body">{{ aiAnalysis.chartAnalysis }}</p>
            </div>

            <div class="strategy-box" :class="aiRecommendationClass">
              <div class="strategy-header">
                <span class="strategy-signal">{{ aiAnalysis?.technicalSignal || '-' }}</span>
                <span class="strategy-rec" :class="'rec-' + aiRecommendationClass">{{ getRecommendationLabel(aiAnalysis?.recommendation) }}</span>
              </div>
              <p class="strategy-text">{{ aiAnalysis?.strategy || '-' }}</p>

              <!-- 단기/장기 충돌 분석 -->
              <div v-if="aiAnalysis?.conflictAnalysis" class="conflict-analysis">
                <p>{{ aiAnalysis.conflictAnalysis }}</p>
              </div>

              <!-- 동적 가격 가이드 -->
              <div v-if="aiAnalysis?.priceGuide" class="price-guide">
                <span class="price-guide-icon">💰</span>
                <span class="price-guide-text">{{ aiAnalysis.priceGuide }}</span>
              </div>

              <!-- 목표주가 컨센서스 -->
              <div v-if="aiAnalysis?.consensusTargetPrice" class="consensus-section">
                <div class="consensus-header">
                  <span class="consensus-label">증권사 목표주가</span>
                  <span class="consensus-price">{{ formatPrice(aiAnalysis.consensusTargetPrice) }}원</span>
                  <span class="consensus-upside" :class="aiAnalysis.targetUpside >= 0 ? 'positive' : 'negative'">
                    {{ aiAnalysis.targetUpside >= 0 ? '+' : '' }}{{ aiAnalysis.targetUpside?.toFixed(1) }}%
                  </span>
                </div>
                <div class="consensus-bar-wrap">
                  <div class="consensus-bar-bg">
                    <div class="consensus-bar-current"
                         :style="{ width: consensusBarWidth + '%' }"></div>
                    <div class="consensus-bar-marker"
                         :style="{ left: consensusBarWidth + '%' }">
                      <span class="marker-label">현재가</span>
                    </div>
                  </div>
                  <div class="consensus-bar-labels">
                    <span>0</span>
                    <span>목표가</span>
                  </div>
                </div>
                <div class="consensus-source" v-if="aiAnalysis.consensusSource">
                  *{{ aiAnalysis.consensusSource }}
                </div>
              </div>

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

            <!-- 한 줄 요약 가이드 (점수 차이 클 때만) -->
            <div v-if="scoreDiffComment" class="score-diff-comment">
              <p>{{ scoreDiffComment }}</p>
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

    <!-- 펀더멘털 진단 섹션 -->
    <div v-if="hasData && diagnosisData" class="fundamental-section">
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
              <span class="today-item" :class="supplyDemand.foreignNetBuy >= 0 ? 'positive' : 'negative'">
                외국인 {{ supplyDemand.foreignNetBuy >= 0 ? '+' : '' }}{{ supplyDemand.foreignNetBuy?.toFixed(0) || 0 }}억
              </span>
              <span class="today-item" :class="supplyDemand.instNetBuy >= 0 ? 'positive' : 'negative'">
                기관 {{ supplyDemand.instNetBuy >= 0 ? '+' : '' }}{{ supplyDemand.instNetBuy?.toFixed(0) || 0 }}억
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
                  <span class="indicator-status" :class="{ active: diagnosisData.technicalAnalysis.isAboveMa20 }">
                    {{ diagnosisData.technicalAnalysis.isAboveMa20 ? '20일선 위' : '20일선 아래' }}
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
            </div>
            <div class="card-assessment">{{ diagnosisData.technicalAnalysis.assessment }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 면책조항 -->
    <footer class="disclaimer" v-if="hasData">
      <p>본 분석은 AI 알고리즘에 의한 참고 자료이며, 투자 결정은 본인의 판단과 책임 하에 이루어져야 합니다.</p>
    </footer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import GlobalNav from '../components/GlobalNav.vue';
import BackButton from '../components/BackButton.vue';
import StockSearchModal from '../components/v2/StockSearchModal.vue';
import StockBriefingHeadline from '../components/v2/StockBriefingHeadline.vue';
import StockRiskCard from '../components/v2/StockRiskCard.vue';
import NotificationBell from '../components/NotificationBell.vue';
import VolumePowerGauge from '../components/VolumePowerGauge.vue';
import TradingIndicatorsPage from './TradingIndicatorsPage.vue';
import { stockDetailAPI, stockAPI } from '../utils/api';
import api from '../utils/api';
import { toast } from '../utils/toast';
import { Line } from 'vue-chartjs';
import {
  Chart as ChartJS,
  CategoryScale, LinearScale, PointElement, LineElement, Title, Tooltip, Legend
} from 'chart.js';

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Title, Tooltip, Legend);

const route = useRoute();
const router = useRouter();

// 종목 검색 (Ctrl+K)
const showSearch = ref(false);
const onSearchKeydown = (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
    e.preventDefault();
    showSearch.value = true;
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

// 2단계 로딩 상태
const heavyLoading = ref(false);

// 펀더멘털 진단
const diagnosisData = ref(null);
const diagnosisLoading = ref(false);
const fundTab = ref('financial');

// 메인 탭
const mainTab = ref('analysis');

// 투자자 동향 탭 상태
const investorLoading = ref(false);
const invStockData = ref(null);
const invSurgeTrend = ref({ FOREIGN: [], INSTITUTION: [], PENSION: [] });
const invSelectedInvestor = ref('FOREIGN');
const invSelectedPeriod = ref(30);
const invDataLoaded = ref(false);

const invInvestorTypes = [
  { value: 'FOREIGN', label: '외국인', icon: '🌍' },
  { value: 'INSTITUTION', label: '기관', icon: '🏢' },
  { value: 'PENSION', label: '연기금', icon: '💎' }
];

const invChartPeriods = [
  { value: 30, label: '1개월' },
  { value: 90, label: '3개월' },
  { value: 180, label: '6개월' }
];

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

// 차트 지표 토글
const activeIndicators = reactive({
  ma5: true, ma20: true, ma60: false, ma120: false, bb: false
});
const indicatorList = [
  { key: 'ma5', label: 'MA5', color: '#f59e0b' },
  { key: 'ma20', label: 'MA20', color: '#3b82f6' },
  { key: 'ma60', label: 'MA60', color: '#10b981' },
  { key: 'ma120', label: 'MA120', color: '#a855f7' },
  { key: 'bb', label: '볼린저', color: '#6b7280' }
];

// 이동평균선/볼린저밴드 SVG path 생성
const maLinePath = (lineKey) => {
  const data = chartData.value?.[lineKey];
  if (!data || !displayCandles.value.length) return null;
  // data는 최신→과거 순 (캔들과 동일), 표시는 reverse
  const lineData = data.slice(0, 30).reverse();
  const range = chartPriceRange.value;
  const count = displayCandles.value.length;
  if (range.max === range.min) return null;

  const points = [];
  for (let i = 0; i < Math.min(lineData.length, count); i++) {
    if (lineData[i] == null) continue;
    const x = ((i + 0.5) / count) * 100;
    const y = 100 - ((lineData[i] - range.min) / (range.max - range.min)) * 100;
    points.push(`${x},${y}`);
  }
  return points.length >= 2 ? points.join(' ') : null;
};

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

const scoreDiffComment = computed(() => {
  const trading = aiAnalysis.value?.overallScore;
  const fundamental = diagnosisData.value?.overallScore;
  if (!trading || !fundamental) return null;

  const guide = aiAnalysis.value?.priceGuide;
  const suffix = guide ? ` → ${guide}` : '';

  // 점수 차이가 클 때만 표시 (20점 이상 차이 또는 양쪽 극단)
  if (fundamental > trading + 20) {
    return `펀더멘털은 우수하나(${fundamental}점), 단기 수급 부진으로 인한 조정 주의(${trading}점)${suffix}`;
  }
  if (trading > fundamental + 20) {
    return `단기 모멘텀은 강하나(${trading}점), 펀더멘털 보강이 필요한 구간(${fundamental}점)${suffix}`;
  }
  if (trading >= 70 && fundamental >= 70) {
    return `단기(${trading}점)·중장기(${fundamental}점) 모두 양호 — 추세 추종 유효${suffix}`;
  }
  if (trading <= 40 && fundamental <= 40) {
    return `단기(${trading}점)·중장기(${fundamental}점) 모두 부진 — 신중한 접근 필요${suffix}`;
  }
  // 차이가 작고 극단도 아니면 숨김
  return null;
});

// ★ 목표주가 컨센서스 바 너비 (현재가 / 목표가 비율)
const consensusBarWidth = computed(() => {
  const target = aiAnalysis.value?.consensusTargetPrice;
  const current = priceInfo.value?.currentPrice;
  if (!target || !current || target <= 0) return 0;
  const ratio = (current / target) * 100;
  return Math.min(Math.max(ratio, 5), 100);
});

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

const aiRecommendationClass = computed(() => {
  const rec = aiAnalysis.value?.recommendation;
  if (rec === 'BUY') return 'buy';
  if (rec === 'TRADING_BUY') return 'trading-buy';
  if (rec === 'WAIT_AND_BUY') return 'wait-buy';
  if (rec === 'SELL') return 'sell';
  return 'hold';
});

// Recommendation 라벨 한글 변환
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

// ==================== 핵심 요약 카드 헬퍼 ====================
const getQsRsiClass = () => {
  const rsi = diagnosisData.value?.technicalAnalysis?.rsi14;
  if (rsi == null) return '';
  if (rsi >= 70) return 'qs-danger';
  if (rsi <= 30) return 'qs-cold';
  return 'qs-neutral';
};
const getQsRsiLabel = () => {
  const rsi = diagnosisData.value?.technicalAnalysis?.rsi14;
  if (rsi == null) return '';
  if (rsi >= 70) return '과매수';
  if (rsi <= 30) return '과매도';
  return '중립';
};
const getQsMaClass = () => {
  const d = diagnosisData.value?.technicalAnalysis?.disparity20;
  if (d == null) return '';
  return d >= 0 ? 'qs-positive' : 'qs-negative';
};
const getQsMaPosition = () => {
  const d = diagnosisData.value?.technicalAnalysis?.disparity20;
  if (d == null) return '-';
  return d >= 0 ? '위' : '아래';
};
const getQsMaDisparity = () => {
  const d = diagnosisData.value?.technicalAnalysis?.disparity20;
  if (d == null) return '';
  return (d >= 0 ? '+' : '') + Number(d).toFixed(1) + '%';
};
const getQsForeignLabel = () => {
  const v = diagnosisData.value?.supplyDemand?.foreignNet5Days;
  if (v == null) return '-';
  return v >= 0 ? '순매수' : '순매도';
};
const getQsForeignAmount = () => {
  const v = diagnosisData.value?.supplyDemand?.foreignNet5Days;
  if (v == null) return '';
  return (v >= 0 ? '+' : '') + Number(v).toFixed(0) + '억';
};
const getQsInstLabel = () => {
  const v = diagnosisData.value?.supplyDemand?.instNet5Days;
  if (v == null) return '-';
  return v >= 0 ? '순매수' : '순매도';
};
const getQsInstAmount = () => {
  const v = diagnosisData.value?.supplyDemand?.instNet5Days;
  if (v == null) return '';
  return (v >= 0 ? '+' : '') + Number(v).toFixed(0) + '억';
};
const getQsRiskClass = () => {
  const score = diagnosisData.value?.overallScore;
  if (score == null) return 'qs-neutral';
  if (score >= 70) return 'qs-safe';
  if (score >= 40) return 'qs-warning';
  return 'qs-danger';
};
const getQsRiskLabel = () => {
  const score = diagnosisData.value?.overallScore;
  if (score == null) return '-';
  if (score >= 70) return 'SAFE';
  if (score >= 40) return 'WARNING';
  return 'DANGER';
};

// Peer Group 바 너비 계산 (PBR 기준, max 2.0)
const getPeerBarWidth = (pbr) => {
  if (!pbr) return 0;
  return Math.min(100, (pbr / 2.0) * 100);
};

const getPeerBarClass = (pbr) => {
  if (!pbr) return '';
  if (pbr < 0.5) return 'peer-very-low';
  if (pbr < 1.0) return 'peer-low';
  if (pbr < 1.5) return 'peer-mid';
  return 'peer-high';
};

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
    // 종목 변경 시 투자자 데이터 초기화
    invDataLoaded.value = false;
    invStockData.value = null;
    invSurgeTrend.value = { FOREIGN: [], INSTITUTION: [], PENSION: [] };
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

// ========== 펀더멘털 진단 헬퍼 함수 ==========
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

// 진단 점수 클래스
const diagGetScoreClass = (score) => {
  if (score >= 70) return 'score-high';
  if (score >= 40) return 'score-mid';
  return 'score-low';
};

// verdict 관련
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

const isRsiOverbought = (data) => {
  if (!data || !data.rsiStatus) return false;
  return data.rsiStatus === '과열';
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

// RSI/MFI 관련
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

// 수급 관련
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

// ========== 투자자 동향 탭 로직 ==========
const invHasSurgeData = computed(() =>
  invSurgeTrend.value.FOREIGN.length > 0 ||
  invSurgeTrend.value.INSTITUTION.length > 0 ||
  invSurgeTrend.value.PENSION.length > 0
);

const invCurrentSurgeTrend = computed(() =>
  invSurgeTrend.value[invSelectedInvestor.value] || []
);

const invHasChartData = computed(() =>
  invStockData.value?.dailyTrades?.length > 0
);

const invRecentDailyTrades = computed(() => {
  if (!invStockData.value?.dailyTrades) return [];
  return invStockData.value.dailyTrades.slice(0, 30);
});

const invFilteredChartData = computed(() => {
  if (!invStockData.value?.dailyTrades) return [];
  const sorted = [...invStockData.value.dailyTrades]
    .sort((a, b) => new Date(a.tradeDate) - new Date(b.tradeDate));
  const filtered = sorted.slice(-invSelectedPeriod.value);
  let fCum = 0, iCum = 0, pCum = 0;
  return filtered.map(day => {
    fCum += Number(day.foreign?.netBuyAmount || 0);
    iCum += Number(day.institution?.netBuyAmount || 0);
    pCum += Number(day.pension?.netBuyAmount || 0);
    return {
      date: day.tradeDate,
      price: day.closePrice || day.foreign?.closePrice || day.institution?.closePrice,
      foreignCumulative: fCum,
      institutionCumulative: iCum,
      pensionCumulative: pCum
    };
  });
});

const invChartData = computed(() => {
  const data = invFilteredChartData.value;
  return {
    labels: data.map(d => {
      const date = new Date(d.date);
      return `${date.getMonth() + 1}/${date.getDate()}`;
    }),
    datasets: [
      { label: '주가', data: data.map(d => d.price), borderColor: '#888', backgroundColor: 'rgba(136,136,136,0.1)', yAxisID: 'y-price', tension: 0.1, pointRadius: 2, borderWidth: 2 },
      { label: '외국인', data: data.map(d => d.foreignCumulative), borderColor: '#e53e3e', backgroundColor: 'rgba(229,62,62,0.1)', yAxisID: 'y-cumulative', tension: 0.1, pointRadius: 2, borderWidth: 2 },
      { label: '기관', data: data.map(d => d.institutionCumulative), borderColor: '#48bb78', backgroundColor: 'rgba(72,187,120,0.1)', yAxisID: 'y-cumulative', tension: 0.1, pointRadius: 2, borderWidth: 2 },
      { label: '연기금', data: data.map(d => d.pensionCumulative), borderColor: '#9f7aea', backgroundColor: 'rgba(159,122,234,0.1)', yAxisID: 'y-cumulative', tension: 0.1, pointRadius: 2, borderWidth: 2 }
    ]
  };
});

const invChartOptions = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  interaction: { mode: 'index', intersect: false },
  plugins: {
    legend: { display: false },
    tooltip: {
      backgroundColor: 'rgba(15,15,35,0.95)',
      titleColor: '#fff', bodyColor: '#ccc',
      borderColor: '#4a4a8a', borderWidth: 1, padding: 12,
      callbacks: {
        label: (ctx) => {
          const label = ctx.dataset.label || '';
          const value = ctx.parsed.y;
          if (label === '주가') return `${label}: ${Number(value).toLocaleString()}원`;
          return `${label}: ${value.toFixed(2)}억`;
        }
      }
    }
  },
  scales: {
    x: { ticks: { color: '#888', maxTicksLimit: 15 }, grid: { color: 'rgba(255, 255, 255, 0.05)' } },
    'y-price': {
      type: 'linear', display: true, position: 'left',
      title: { display: true, text: '주가 (원)', color: '#888' },
      ticks: { color: '#888', callback: (v) => Number(v).toLocaleString() },
      grid: { color: 'rgba(255, 255, 255, 0.05)' }
    },
    'y-cumulative': {
      type: 'linear', display: true, position: 'right',
      title: { display: true, text: '누적 순매수 (억)', color: '#888' },
      ticks: { color: '#888', callback: (v) => v.toFixed(0) + '억' },
      grid: { drawOnChartArea: false }
    }
  }
}));

const switchToInvestorTab = async () => {
  mainTab.value = 'investor';
  if (!invDataLoaded.value && stockCode.value) {
    await fetchInvestorData();
  }
};

const fetchInvestorData = async () => {
  if (!stockCode.value) return;
  investorLoading.value = true;
  try {
    const [dailyRes, foreignRes, instRes, pensionRes] = await Promise.all([
      api.get(`/investor/stock/${stockCode.value}`, { params: { days: 180 } }),
      api.get(`/investor/surge/trend/${stockCode.value}`, { params: { investorType: 'FOREIGN' } }),
      api.get(`/investor/surge/trend/${stockCode.value}`, { params: { investorType: 'INSTITUTION' } }),
      api.get(`/investor/surge/trend/${stockCode.value}`, { params: { investorType: 'PENSION' } })
    ]);

    if (dailyRes.data.success && dailyRes.data.data) {
      invStockData.value = dailyRes.data.data;
    }
    if (foreignRes.data.success) invSurgeTrend.value.FOREIGN = foreignRes.data.data || [];
    if (instRes.data.success) invSurgeTrend.value.INSTITUTION = instRes.data.data || [];
    if (pensionRes.data.success) invSurgeTrend.value.PENSION = pensionRes.data.data || [];

    // 데이터 있는 탭으로 자동 전환
    if (!invSurgeTrend.value[invSelectedInvestor.value]?.length) {
      if (invSurgeTrend.value.FOREIGN.length) invSelectedInvestor.value = 'FOREIGN';
      else if (invSurgeTrend.value.INSTITUTION.length) invSelectedInvestor.value = 'INSTITUTION';
      else if (invSurgeTrend.value.PENSION.length) invSelectedInvestor.value = 'PENSION';
    }
    invDataLoaded.value = true;
  } catch (err) {
    console.error('투자자 데이터 조회 오류:', err);
  } finally {
    investorLoading.value = false;
  }
};

// 투자자 탭 포맷터
const invFormatTime = (timeStr) => {
  if (!timeStr) return '-';
  const parts = timeStr.split(':');
  return `${parts[0]}:${parts[1]}`;
};

const invFormatAmount = (value) => {
  if (!value) return '0억';
  const num = Number(value);
  return `${num.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}억`;
};

const invFormatAmountWithSign = (value) => {
  if (!value) return '0억';
  const num = Number(value);
  const sign = num > 0 ? '+' : '';
  return `${sign}${num.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}억`;
};

const invFormatRate = (value) => {
  if (!value) return '0.00%';
  const sign = value > 0 ? '+' : '';
  return `${sign}${Number(value).toFixed(2)}%`;
};

const invFormatDate = (dateStr) => {
  const date = new Date(dateStr);
  return `${date.getMonth() + 1}월 ${date.getDate()}일 (${['일','월','화','수','목','금','토'][date.getDay()]})`;
};

const invGetAmountClass = (value) => {
  if (!value) return '';
  return Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : '';
};

const invHasAmountChange = (value) => {
  if (value === null || value === undefined) return false;
  return Math.abs(Number(value)) > 0.01;
};

// URL 파라미터 처리
onMounted(() => {
  document.addEventListener('keydown', onSearchKeydown);
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
  top: 48px;
  z-index: 100;
}

@media (max-width: 768px) {
  .stock-header { top: 40px; }
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
.change-info.positive::before { content: '▲ '; font-size: 0.85em; }
.change-info.negative { color: #3b82f6; }
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

/* 이동평균선 SVG 오버레이 */
.chart-overlay {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  viewBox: 0 0 100 100;
}

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

/* Peer Group 비교 */
.peer-section {
  background: rgba(30, 30, 60, 0.6);
  border-radius: 16px;
  padding: 20px;
  border: 1px solid #2a2a5a;
  margin-top: 12px;
}

.peer-chart {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.peer-bar-row {
  display: grid;
  grid-template-columns: 80px 1fr 90px 70px;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 0.8rem;
}

.peer-bar-row.current {
  background: rgba(167, 139, 250, 0.1);
  border: 1px solid rgba(167, 139, 250, 0.3);
}

.peer-name {
  color: #ccc;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.peer-bar-row.current .peer-name {
  color: #a78bfa;
  font-weight: 700;
}

.peer-bar-container {
  position: relative;
  height: 14px;
  background: var(--border-light);
  border-radius: 7px;
  overflow: visible;
}

.peer-bar-fill {
  height: 100%;
  border-radius: 7px;
  transition: width 0.5s ease;
}

.peer-bar-fill.peer-very-low { background: linear-gradient(90deg, #22c55e, #4ade80); }
.peer-bar-fill.peer-low { background: linear-gradient(90deg, #4ade80, #a3e635); }
.peer-bar-fill.peer-mid { background: linear-gradient(90deg, #eab308, #f59e0b); }
.peer-bar-fill.peer-high { background: linear-gradient(90deg, #f87171, #ef4444); }

.peer-pbr { color: #aaa; font-family: 'Monaco', monospace; font-size: 0.75rem; }
.peer-div { color: #888; font-size: 0.7rem; }

.peer-bar-row.current .peer-pbr { color: #a78bfa; font-weight: 600; }
.peer-bar-row.current .peer-div { color: #c4b5fd; }

/* Peer Group 헤더/섹터 */
.peer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.peer-header h2 {
  margin: 0;
  font-size: 1.1rem;
}

.sector-name-badge {
  font-size: 0.7rem;
  padding: 3px 10px;
  border-radius: 10px;
  background: rgba(59, 130, 246, 0.15);
  color: #60a5fa;
  border: 1px solid rgba(59, 130, 246, 0.3);
}

/* 섹터 평균 PBR 라인 */
.sector-avg-line {
  position: absolute;
  top: -2px;
  width: 2px;
  height: 18px;
  background: #f59e0b;
  z-index: 2;
}

.sector-avg-label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  font-size: 0.7rem;
  color: #f59e0b;
}

.avg-line-indicator {
  display: inline-block;
  width: 12px;
  height: 2px;
  background: #f59e0b;
}

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

/* 동적 가격 가이드 */
.price-guide {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 12px;
  padding: 10px 14px;
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.2);
  border-radius: 10px;
}

.price-guide-icon {
  font-size: 1rem;
  flex-shrink: 0;
}

.price-guide-text {
  font-size: 0.8rem;
  color: #fbbf24;
  line-height: 1.5;
}

/* 목표주가 컨센서스 */
.consensus-section {
  margin-top: 12px;
  padding: 10px 12px;
  background: rgba(96, 165, 250, 0.08);
  border: 1px solid rgba(96, 165, 250, 0.2);
  border-radius: 10px;
}
.consensus-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.consensus-label {
  font-size: 0.75rem;
  color: rgba(255,255,255,0.6);
}
.consensus-price {
  font-size: 0.9rem;
  font-weight: 700;
  color: #60a5fa;
}
.consensus-upside {
  font-size: 0.75rem;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
}
.consensus-upside.positive {
  color: #22c55e;
  background: rgba(34, 197, 94, 0.15);
}
.consensus-upside.negative {
  color: #ef4444;
  background: rgba(239, 68, 68, 0.15);
}
.consensus-bar-wrap {
  margin-bottom: 4px;
}
.consensus-bar-bg {
  position: relative;
  height: 8px;
  background: rgba(255,255,255,0.1);
  border-radius: 4px;
  overflow: visible;
}
.consensus-bar-current {
  height: 100%;
  background: linear-gradient(90deg, #22c55e, #60a5fa);
  border-radius: 4px;
  transition: width 0.6s ease;
}
.consensus-bar-marker {
  position: absolute;
  top: -16px;
  transform: translateX(-50%);
}
.marker-label {
  font-size: 0.6rem;
  color: rgba(255,255,255,0.5);
}
.consensus-bar-labels {
  display: flex;
  justify-content: space-between;
  font-size: 0.6rem;
  color: rgba(255,255,255,0.35);
  margin-top: 2px;
}
.consensus-source {
  font-size: 0.6rem;
  color: rgba(255,255,255,0.5);
  margin-top: 6px;
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

/* 차트 시그널 칩 */
.chart-signal-chips {
  display: flex; flex-wrap: wrap; gap: 6px;
  margin-bottom: 10px;
}
.chip {
  font-size: 11px; font-weight: 600;
  padding: 3px 10px; border-radius: 12px;
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  color: rgba(255,255,255,0.75);
  cursor: default;
}
.chip-positive {
  color: #4ade80;
  background: rgba(74,222,128,0.1);
  border-color: rgba(74,222,128,0.3);
}
.chip-negative {
  color: #f87171;
  background: rgba(248,113,113,0.1);
  border-color: rgba(248,113,113,0.3);
}
.chip-neutral {
  color: rgba(255,255,255,0.7);
}

/* AI 차트 해석 카드 */
.chart-analysis-card {
  padding: 12px 14px;
  background: rgba(99,102,241,0.08);
  border: 1px solid rgba(99,102,241,0.25);
  border-radius: 10px;
  margin-bottom: 12px;
}
.chart-analysis-head {
  display: flex; align-items: center; gap: 6px;
  margin-bottom: 6px;
}
.chart-analysis-icon { font-size: 14px; }
.chart-analysis-title {
  font-size: 12px; font-weight: 700;
  color: #a5b4fc;
  letter-spacing: 0.3px;
}
.chart-analysis-body {
  font-size: 13px;
  line-height: 1.55;
  color: rgba(255,255,255,0.82);
  white-space: pre-wrap;
}

.strategy-box {
  padding: 16px;
  border-radius: 10px;
  background: rgba(50, 50, 80, 0.5);
}

.strategy-box.buy { border: 2px solid #22c55e; }
.strategy-box.trading-buy { border: 2px solid #4ade80; }
.strategy-box.wait-buy { border: 2px solid #a78bfa; }
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
.strategy-box.trading-buy .strategy-rec { color: #4ade80; }
.strategy-box.wait-buy .strategy-rec { color: #a78bfa; }
.strategy-box.sell .strategy-rec { color: #ef4444; }
.strategy-box.hold .strategy-rec { color: #6b7280; }

/* 충돌 분석 박스 */
.conflict-analysis {
  margin-top: 12px;
  padding: 10px 14px;
  background: rgba(167, 139, 250, 0.1);
  border: 1px solid rgba(167, 139, 250, 0.25);
  border-radius: 8px;
  font-size: 0.82rem;
  line-height: 1.5;
  color: #c4b5fd;
}

.conflict-analysis p {
  margin: 0;
}

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

/* ========== Score Diff Comment ========== */
.score-diff-comment {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding: 12px 16px;
  background: rgba(102, 126, 234, 0.1);
  border: 1px solid rgba(102, 126, 234, 0.3);
  border-radius: 10px;
}

.score-diff-comment .diff-icon {
  font-size: 1.2rem;
  flex-shrink: 0;
}

.score-diff-comment p {
  margin: 0;
  font-size: 0.9rem;
  color: #a5b4fc;
  line-height: 1.4;
}

/* ========== Fundamental Section ========== */
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
  .ai-score-box { padding: 10px 16px; }
  .score-value { font-size: 2rem; }

  .search-bar { flex-direction: column; }
  .realtime-status { flex-wrap: wrap; gap: 8px; }

  .reasons-section { grid-template-columns: 1fr; }

  .dual-score-header { flex-direction: column; }
  .alerts-section { grid-template-columns: 1fr; }
  /* fund-tabs는 가로 스크롤 + wrap이 더 자연스러움 (column보다) */
  .fund-tabs { flex-wrap: wrap; }
  .fund-tab-btn { flex: 1 1 30%; min-width: 0; padding: 8px 12px; font-size: 12px; }
  .inv-investor-grid { grid-template-columns: 1fr; }
  .inv-surge-grid { grid-template-columns: 1fr; }
  .inv-investor-tabs { flex-wrap: wrap; }
  .inv-tab-btn { flex: 1; min-width: 100px; text-align: center; }

  /* 메인 탭 바 — 작은 폰 패딩/폰트 축소 */
  .main-tab-btn { padding: 9px 10px; font-size: 13px; }

  /* 차트 영역 — 350px → 260px 축소 */
  .inv-chart-wrapper { height: 260px; padding: 12px; }
  .inv-chart-legend { gap: 14px; }
  .inv-legend-item { font-size: 0.78rem; }
}

@media (max-width: 480px) {
  .trading-dashboard { padding: 10px; }
  .stock-header { padding: 14px; }
  .stock-name { font-size: 1.2rem; }
  .current-price { font-size: 1.4rem; }
  .change-info { font-size: 0.95rem; }
  .score-value { font-size: 1.7rem; }
  .ai-score-box { padding: 8px 14px; }

  /* 메인 탭 — 더 컴팩트 */
  .main-tab-btn { padding: 8px 6px; font-size: 12px; }

  /* 차트 — 더 작게 */
  .inv-chart-wrapper { height: 220px; padding: 10px; }

  /* 피어 비교 바 — 고정폭 압축 (80/90/70 → 60/65/55) */
  .peer-bar-row { grid-template-columns: 60px 1fr 65px 55px; gap: 6px; padding: 5px 8px; font-size: 0.72rem; }

  /* 재무정보 그리드 — 너무 좁아 한 줄로 */
  .financial-grid { grid-template-columns: 1fr; gap: 6px; }
}

/* ========== 메인 탭 바 ========== */
/* 핵심 요약 카드 */
.quick-summary-bar {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 8px;
  margin-bottom: 12px;
  padding: 12px;
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px;
}
.quick-summary-bar.skeleton { opacity: 0.5; }
.qs-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 6px 4px;
}
.qs-label { font-size: 10px; color: rgba(255,255,255,0.6); font-weight: 600; }
.qs-value { font-size: 16px; font-weight: 800; color: rgba(255,255,255,0.9); }
.qs-sub { font-size: 10px; color: rgba(255,255,255,0.6); }
.qs-badge {
  font-size: 10px; font-weight: 700; padding: 1px 6px; border-radius: 4px;
  background: rgba(107,114,128,0.2); color: #9ca3af;
}
.qs-positive { color: #ef4444; }
.qs-negative { color: #3b82f6; }
.qs-neutral { color: rgba(255,255,255,0.7); }
.qs-danger { color: #ef4444; }
.qs-danger.qs-badge { background: rgba(239,68,68,0.15); color: #ef4444; }
.qs-cold { color: #3b82f6; }
.qs-cold.qs-badge { background: rgba(59,130,246,0.15); color: #3b82f6; }
.qs-safe { background: rgba(34,197,94,0.15); color: #22c55e; }
.qs-warning { background: rgba(245,158,11,0.15); color: #f59e0b; }
.qs-rec-buy { background: rgba(239,68,68,0.15); color: #ef4444; }
.qs-rec-trading_buy { background: rgba(239,68,68,0.1); color: #f87171; }
.qs-rec-hold { background: rgba(107,114,128,0.15); color: #9ca3af; }
.qs-rec-sell { background: rgba(59,130,246,0.15); color: #3b82f6; }
.qs-rec-wait_and_buy { background: rgba(245,158,11,0.15); color: #f59e0b; }
.qs-skeleton { height: 50px; }
.qs-skeleton-bar {
  width: 60%; height: 14px; border-radius: 4px;
  background: rgba(255,255,255,0.08);
  animation: skeleton-pulse 1.5s infinite;
}
@keyframes skeleton-pulse { 0%,100% { opacity: 0.5; } 50% { opacity: 0.2; } }

@media (max-width: 768px) {
  .quick-summary-bar { grid-template-columns: repeat(3, 1fr); }
  .qs-value { font-size: 14px; }
}
@media (max-width: 480px) {
  .quick-summary-bar { grid-template-columns: repeat(2, 1fr); gap: 6px; padding: 10px; }
  .qs-value { font-size: 13px; }
  .qs-label { font-size: 9.5px; }
  .qs-sub { font-size: 9.5px; }
  .qs-badge { font-size: 9.5px; padding: 1px 5px; }
}

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
.investor-tab-content {
  max-width: 1200px;
  margin: 0 auto;
}

.inv-info-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(30, 30, 60, 0.6);
  border: 1px solid rgba(74, 74, 138, 0.3);
  border-radius: 10px;
  padding: 12px 16px;
  margin-bottom: 20px;
  color: #ccc;
  font-size: 0.9rem;
}

.inv-section {
  margin-bottom: 24px;
}

.inv-section-title {
  color: #fff;
  font-size: 1.2rem;
  margin-bottom: 14px;
  padding-bottom: 8px;
  border-bottom: 2px solid rgba(255,255,255,0.1);
}

.inv-period-selector {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.inv-period-btn {
  padding: 8px 16px;
  background: rgba(30, 30, 60, 0.6);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 8px;
  color: #888;
  cursor: pointer;
  font-size: 0.9rem;
  font-weight: 600;
  transition: all 0.3s;
}

.inv-period-btn.active {
  background: rgba(102,126,234,0.3);
  border-color: rgba(102,126,234,0.5);
  color: #fff;
}

.inv-chart-wrapper {
  background: rgba(30, 30, 60, 0.5);
  border-radius: 12px;
  padding: 16px;
  height: 350px;
  border: 1px solid rgba(255,255,255,0.08);
}

.inv-chart-legend {
  display: flex;
  justify-content: center;
  gap: 24px;
  margin-top: 12px;
}

.inv-legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #ccc;
  font-size: 0.85rem;
}

.inv-legend-color {
  width: 20px;
  height: 3px;
  border-radius: 2px;
}

.inv-investor-tabs {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.inv-tab-btn {
  padding: 10px 20px;
  background: rgba(30, 30, 60, 0.6);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 10px;
  color: #888;
  cursor: pointer;
  font-size: 0.95rem;
  font-weight: 600;
  transition: all 0.3s;
}

.inv-tab-btn.active {
  background: rgba(229, 62, 62, 0.2);
  border-color: rgba(229, 62, 62, 0.4);
  color: #e53e3e;
}

.inv-tab-btn:hover:not(.active) {
  border-color: rgba(255,255,255,0.2);
  color: #ccc;
}

.inv-surge-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.inv-surge-item {
  background: rgba(30, 30, 60, 0.5);
  border-radius: 12px;
  padding: 14px;
  border: 1px solid rgba(255,255,255,0.08);
}

.inv-time-badge {
  display: inline-block;
  background: rgba(102,126,234,0.3);
  color: #fff;
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 0.85rem;
  font-weight: 600;
  margin-bottom: 10px;
}

.inv-surge-details {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.inv-detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.inv-detail-row .label { color: #888; font-size: 0.85rem; }
.inv-detail-row .value { font-weight: 600; color: #ddd; font-family: monospace; }

.inv-detail-row.highlight {
  background: rgba(255,255,255,0.04);
  padding: 6px 8px;
  border-radius: 8px;
  margin: 2px 0;
}

.inv-no-data {
  text-align: center;
  padding: 40px;
  color: #666;
}

.inv-no-data .hint {
  font-size: 0.9rem;
  color: #555;
  margin-top: 8px;
}

.inv-daily-trades {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 60vh;
  overflow-y: auto;
  padding: 4px;
}

.inv-daily-card {
  background: rgba(30, 30, 60, 0.5);
  border-radius: 12px;
  padding: 14px;
  border: 1px solid rgba(255,255,255,0.08);
}

.inv-daily-card:hover { border-color: rgba(255,255,255,0.15); }

.inv-date-header {
  font-size: 1rem;
  font-weight: 700;
  color: #fff;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}

.inv-investor-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.inv-investor-item {
  background: rgba(0, 0, 0, 0.2);
  border-radius: 10px;
  padding: 12px;
  border: 1px solid var(--border-light);
  border-left: 3px solid;
}

.inv-investor-label {
  font-weight: 700;
  font-size: 0.9rem;
  color: #fff;
  margin-bottom: 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}

.inv-amounts {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.inv-amount-row {
  display: flex;
  justify-content: space-between;
  font-size: 0.85rem;
}

.inv-amount-row .label { color: #888; }
.inv-amount-row .value { font-weight: 600; font-family: monospace; color: #ddd; }
.inv-amount-row.net { margin-top: 4px; padding-top: 4px; border-top: 1px solid rgba(255,255,255,0.08); }
.inv-amount-row.net .label { font-weight: 700; }

.inv-amount-row .value.positive { color: #e53e3e !important; }
.inv-amount-row .value.negative { color: #3b82f6 !important; }
.inv-detail-row .value.positive { color: #e53e3e !important; }
.inv-detail-row .value.negative { color: #3b82f6 !important; }

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
</style>
