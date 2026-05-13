<template>
  <div :class="['paper-trading-page', { embedded: props.embedded }]">
    <GlobalNav v-if="!props.embedded" />
    <LoadingSpinner v-if="loading" />
    <div v-else class="content-wrapper">
      <!-- 헤더 섹션 (embedded 모드에서는 숨김) -->
      <div v-if="!props.embedded" class="page-header-unified">
        <div class="header-title">
          <h1>자동매매</h1>
          <p class="subtitle">모의투자와 실전투자를 관리하세요</p>
        </div>
      </div>

      <!-- 데이터 갱신 상태 -->
      <div class="freshness-bar">
        <DataFreshness :lastUpdated="lastUpdated" :isRefreshing="isRefreshing" :nextRefreshIn="nextRefreshIn" @refresh="manualRefresh" />
      </div>

      <!-- 매매 안전장치 위젯 (admin) -->
      <TradingSafetyWidget />

      <!-- 탭 네비게이션 -->
      <div class="tab-navigation">
        <button
          :class="['tab-btn', { active: activeTab === 'virtual' }]"
          @click="activeTab = 'virtual'"
        >
          🤖 모의투자
        </button>
        <button
          :class="['tab-btn real', { active: activeTab === 'real' }]"
          @click="switchToRealTab"
        >
          🔴 실전투자
        </button>
        <button
          :class="['tab-btn', { active: activeTab === 'botPerformance' }]"
          @click="switchToBotPerformanceTab"
        >
          📊 봇 성과
        </button>
        <button
          :class="['tab-btn', { active: activeTab === 'weeklyReport' }]"
          @click="switchToWeeklyReportTab"
        >
          📝 주간 리포트
        </button>
      </div>

      <!-- 모의투자 탭 -->
      <div v-if="activeTab === 'virtual'" class="tab-content">
        <!-- 요약 카드 섹션 -->
        <div class="summary-grid">
          <!-- 가상 계좌 카드 -->
          <div class="summary-card account-card">
            <div class="card-icon">💰</div>
            <h3>가상 계좌</h3>
            <div class="card-content">
              <div class="stat-row">
                <span class="label">초기 자본</span>
                <span class="value">{{ formatCurrency(account.initialBalance) }}</span>
              </div>
              <div class="stat-row">
                <span class="label">현재 잔액</span>
                <span class="value highlight">{{ formatCurrency(account.currentBalance) }}</span>
              </div>
              <div class="stat-row">
                <span class="label">총 자산</span>
                <span class="value" :class="getProfitClass(totalAsset - account.initialBalance)">
                  {{ formatCurrency(totalAsset) }}
                </span>
              </div>
              <div class="stat-row profit-row">
                <span class="label">총 손익</span>
                <span class="value" :class="getProfitClass(account.totalProfitLoss)">
                  {{ formatProfitLoss(account.totalProfitLoss) }}
                  <small>({{ formatPercent(account.totalProfitRate) }})</small>
                </span>
              </div>
            </div>
            <button @click="showInitializeConfirm = true" class="reset-btn">
              계좌 초기화
            </button>
          </div>

          <!-- 포트폴리오 카드 -->
          <div class="summary-card portfolio-card">
            <div class="card-icon">📊</div>
            <h3>포트폴리오</h3>
            <div class="card-content">
              <div class="stat-row">
                <span class="label">보유 종목</span>
                <span class="value">{{ account.holdingCount || 0 }}종목</span>
              </div>
              <div class="stat-row">
                <span class="label">투자 금액</span>
                <span class="value">{{ formatCurrency(account.totalInvested) }}</span>
              </div>
              <div class="stat-row">
                <span class="label">평가 금액</span>
                <span class="value">{{ formatCurrency(account.totalEvaluation) }}</span>
              </div>
              <div class="stat-row">
                <span class="label">평가 손익</span>
                <span class="value" :class="getProfitClass(account.unrealizedProfitLoss)">
                  {{ formatProfitLoss(account.unrealizedProfitLoss) }}
                </span>
              </div>
            </div>
          </div>

          <!-- 거래 현황 카드 -->
          <div class="summary-card trade-card">
            <div class="card-icon">📈</div>
            <h3>거래 현황</h3>
            <div class="card-content">
              <div class="stat-row">
                <span class="label">총 매도 수</span>
                <span class="value">{{ account.totalTradeCount || 0 }}건</span>
              </div>
              <div class="stat-row">
                <span class="label">승률</span>
                <span class="value" :class="getWinRateClass(account.winRate)">
                  {{ formatPercent(account.winRate) }}
                </span>
              </div>
              <div class="stat-row">
                <span class="label">수익/손실</span>
                <span class="value">
                  <span class="win">{{ account.winCount || 0 }}승</span> /
                  <span class="lose">{{ account.loseCount || 0 }}패</span>
                </span>
              </div>
              <div class="stat-row">
                <span class="label">실현 손익</span>
                <span class="value" :class="getProfitClass(account.realizedProfitLoss)">
                  {{ formatProfitLoss(account.realizedProfitLoss) }}
                </span>
              </div>
            </div>
          </div>

          <!-- 자동봇 카드 (모의투자) -->
          <div class="summary-card bot-card" :class="{ active: botStatus.active && botStatus.tradingMode === 'VIRTUAL' }">
            <div class="card-icon">🤖</div>
            <h3>모의투자 봇</h3>
            <div class="card-content">
              <div class="stat-row">
                <span class="label">상태</span>
                <span class="value" :class="getBotStatusClass(botStatus.tradingMode === 'VIRTUAL' ? botStatus.status : 'STOPPED')">
                  {{ getBotStatusText(botStatus, 'VIRTUAL') }}
                </span>
              </div>
              <div class="stat-row">
                <span class="label">오늘 매수</span>
                <span class="value">{{ botStatus.tradingMode === 'VIRTUAL' ? (botStatus.todayBuyCount || 0) : 0 }}건</span>
              </div>
              <div class="stat-row">
                <span class="label">오늘 매도</span>
                <span class="value">{{ botStatus.tradingMode === 'VIRTUAL' ? (botStatus.todaySellCount || 0) : 0 }}건</span>
              </div>
              <div class="stat-row" v-if="botStatus.active && botStatus.tradingMode === 'VIRTUAL' && botStatus.lastTradeTime">
                <span class="label">마지막 거래</span>
                <span class="value time">{{ formatTime(botStatus.lastTradeTime) }}</span>
              </div>
            </div>
            <div class="bot-controls">
              <button
                v-if="!botStatus.active || botStatus.tradingMode !== 'VIRTUAL'"
                @click="startBot('VIRTUAL')"
                class="start-btn virtual-btn"
                :disabled="botLoading || (botStatus.active && botStatus.tradingMode === 'REAL')"
              >
                {{ botLoading ? '처리 중...' : '🤖 모의투자 봇 시작' }}
              </button>
              <button
                v-else
                @click="stopBot"
                class="stop-btn"
                :disabled="botLoading"
              >
                {{ botLoading ? '처리 중...' : '봇 중지' }}
              </button>
            </div>
          </div>
        </div>

        <!-- 포트폴리오 테이블 -->
        <div class="section">
          <div class="section-header">
            <h2>보유 종목</h2>
            <button @click="refreshPortfolio" class="refresh-btn" :disabled="portfolioLoading">
              {{ portfolioLoading ? '새로고침 중...' : '새로고침' }}
            </button>
          </div>

          <div v-if="portfolio.length > 0" class="table-container">
            <table class="portfolio-table">
              <thead>
                <tr>
                  <th>종목명</th>
                  <th>종목코드</th>
                  <th class="right">보유수량</th>
                  <th class="right">평균단가</th>
                  <th class="right">현재가</th>
                  <th class="right">평가금액</th>
                  <th class="right">손익</th>
                  <th class="right">손익률</th>
                  <th>매도</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in portfolio" :key="item.stockCode">
                  <td class="stock-name">{{ item.stockName }}</td>
                  <td class="stock-code">{{ item.stockCode }}</td>
                  <td class="right">{{ item.quantity }}주</td>
                  <td class="right">{{ formatNumber(item.averagePrice) }}원</td>
                  <td class="right">{{ formatNumber(item.currentPrice) }}원</td>
                  <td class="right">{{ formatCurrency(item.totalEvaluation) }}</td>
                  <td class="right" :class="getProfitClass(item.profitLoss)">
                    {{ formatProfitLoss(item.profitLoss) }}
                  </td>
                  <td class="right" :class="getProfitClass(item.profitRate)">
                    {{ formatPercent(item.profitRate) }}
                  </td>
                  <td>
                    <button @click="openSellModal(item, 'virtual')" class="sell-btn">매도</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="no-data">
            <p>보유 종목이 없습니다.</p>
          </div>
        </div>

        <!-- 거래 내역 -->
        <div class="section">
          <div class="section-header">
            <h2>거래 내역</h2>
            <button @click="openTradeModal('virtual')" class="trade-btn">수동 거래</button>
          </div>

          <div v-if="trades.length > 0" class="table-container">
            <table class="trades-table">
              <thead>
                <tr>
                  <th>시간</th>
                  <th>종목명</th>
                  <th>유형</th>
                  <th class="right">수량</th>
                  <th class="right">가격</th>
                  <th class="right">금액</th>
                  <th class="right" title="수수료 0.015% + 매도세 0.18%">비용</th>
                  <th class="right">손익</th>
                  <th>사유</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="trade in trades" :key="trade.id" :class="trade.tradeType.toLowerCase()">
                  <td class="time">{{ formatDateTime(trade.tradeDate) }}</td>
                  <td class="stock-name">{{ trade.stockName }}</td>
                  <td :class="trade.tradeType.toLowerCase()">{{ trade.tradeTypeName }}</td>
                  <td class="right">{{ trade.quantity }}주</td>
                  <td class="right">{{ formatNumber(trade.price) }}원</td>
                  <td class="right">{{ formatCurrency(trade.totalAmount) }}</td>
                  <td class="right cost-cell"
                      :title="`수수료 ${formatNumber(trade.commission || 0)}원${trade.tax ? ' + 세금 ' + formatNumber(trade.tax) + '원' : ''}`">
                    -{{ formatNumber((Number(trade.commission || 0) + Number(trade.tax || 0))) }}원
                  </td>
                  <td class="right" :class="getProfitClass(trade.profitLoss)">
                    {{ trade.profitLoss ? formatProfitLoss(trade.profitLoss) : '-' }}
                  </td>
                  <td class="reason">{{ trade.tradeReasonName }}</td>
                </tr>
              </tbody>
            </table>

            <!-- 페이징 -->
            <div class="pagination" v-if="totalPages > 1">
              <button @click="changePage(currentPage - 1)" :disabled="currentPage === 0">이전</button>
              <span>{{ currentPage + 1 }} / {{ totalPages }}</span>
              <button @click="changePage(currentPage + 1)" :disabled="currentPage >= totalPages - 1">다음</button>
            </div>
          </div>
          <div v-else class="no-data">
            <p>거래 내역이 없습니다.</p>
          </div>
        </div>
      </div>

      <!-- 실전투자 탭 -->
      <div v-if="activeTab === 'real'" class="tab-content">
        <!-- 경고 배너 -->
        <div class="warning-banner">
          <span class="warning-icon">⚠️</span>
          <span>실전투자 모드입니다. 실제 계좌에서 주문이 체결되며 손실이 발생할 수 있습니다.</span>
        </div>

        <!-- 요약 카드 섹션 -->
        <div class="summary-grid">
          <!-- 실제 계좌 카드 -->
          <div class="summary-card account-card real-account">
            <div class="card-icon">💳</div>
            <h3>
              실제 계좌
              <span v-if="realFreshnessLabel" class="freshness-badge" :class="realFreshnessClass">
                {{ realFreshnessLabel }}
              </span>
            </h3>
            <div class="card-content">
              <div class="stat-row">
                <span class="label">예수금</span>
                <span class="value highlight">{{ formatCurrency(realAccount.cashBalance) }}</span>
              </div>
              <div class="stat-row">
                <span class="label">총 평가금액</span>
                <span class="value">{{ formatCurrency(realAccount.totalEvaluation) }}</span>
              </div>
              <div class="stat-row">
                <span class="label">총 자산</span>
                <span class="value" :class="getProfitClass(realAccount.totalAsset - realAccount.totalInvested)">
                  {{ formatCurrency(realAccount.totalAsset) }}
                </span>
              </div>
              <div class="stat-row profit-row">
                <span class="label">평가 손익</span>
                <span class="value" :class="getProfitClass(realAccount.unrealizedProfitLoss)">
                  {{ formatProfitLoss(realAccount.unrealizedProfitLoss) }}
                  <small>({{ formatPercent(realAccount.profitRate) }})</small>
                </span>
              </div>
            </div>
          </div>

          <!-- 실전 포트폴리오 카드 -->
          <div class="summary-card portfolio-card">
            <div class="card-icon">📊</div>
            <h3>실전 포트폴리오</h3>
            <div class="card-content">
              <div class="stat-row">
                <span class="label">보유 종목</span>
                <span class="value">{{ realPortfolio.length || 0 }}종목</span>
              </div>
              <div class="stat-row">
                <span class="label">투자 금액</span>
                <span class="value">{{ formatCurrency(realAccount.totalInvested) }}</span>
              </div>
              <div class="stat-row">
                <span class="label">평가 금액</span>
                <span class="value">{{ formatCurrency(realAccount.totalEvaluation) }}</span>
              </div>
              <div class="stat-row">
                <span class="label">수익률</span>
                <span class="value" :class="getProfitClass(realAccount.profitRate)">
                  {{ formatPercent(realAccount.profitRate) }}
                </span>
              </div>
            </div>
          </div>

          <!-- 자동봇 카드 (실전투자) -->
          <div class="summary-card bot-card real-mode" :class="{ active: botStatus.active && botStatus.tradingMode === 'REAL' }">
            <div class="card-icon">🔴</div>
            <h3>실전투자 봇</h3>
            <div class="card-content">
              <div class="stat-row">
                <span class="label">상태</span>
                <span class="value" :class="getBotStatusClass(botStatus.tradingMode === 'REAL' ? botStatus.status : 'STOPPED')">
                  {{ getBotStatusText(botStatus, 'REAL') }}
                </span>
              </div>
              <div class="stat-row">
                <span class="label">오늘 매수</span>
                <span class="value">{{ botStatus.tradingMode === 'REAL' ? (botStatus.todayBuyCount || 0) : 0 }}건</span>
              </div>
              <div class="stat-row">
                <span class="label">오늘 매도</span>
                <span class="value">{{ botStatus.tradingMode === 'REAL' ? (botStatus.todaySellCount || 0) : 0 }}건</span>
              </div>
              <div class="stat-row" v-if="botStatus.active && botStatus.tradingMode === 'REAL' && botStatus.lastTradeTime">
                <span class="label">마지막 거래</span>
                <span class="value time">{{ formatTime(botStatus.lastTradeTime) }}</span>
              </div>
              <div class="stat-row error" v-if="botStatus.lastError">
                <span class="label">에러</span>
                <span class="value">{{ botStatus.lastError }}</span>
              </div>
            </div>
            <div class="bot-controls">
              <button
                v-if="!botStatus.active || botStatus.tradingMode !== 'REAL'"
                @click="startBot('REAL')"
                class="start-btn real-btn"
                :disabled="botLoading || (botStatus.active && botStatus.tradingMode === 'VIRTUAL')"
              >
                {{ botLoading ? '처리 중...' : '🔴 실전투자 봇 시작' }}
              </button>
              <button
                v-else
                @click="stopBot"
                class="stop-btn"
                :disabled="botLoading"
              >
                {{ botLoading ? '처리 중...' : '봇 중지' }}
              </button>
            </div>
          </div>
        </div>

        <!-- 실전 포트폴리오 테이블 -->
        <div class="section">
          <div class="section-header">
            <h2>실전 보유 종목</h2>
            <button @click="refreshRealPortfolio" class="refresh-btn" :disabled="realPortfolioLoading">
              {{ realPortfolioLoading ? '새로고침 중...' : '새로고침' }}
            </button>
          </div>

          <div v-if="realPortfolio.length > 0" class="table-container">
            <table class="portfolio-table">
              <thead>
                <tr>
                  <th>종목명</th>
                  <th>종목코드</th>
                  <th class="right">보유수량</th>
                  <th class="right">평균단가</th>
                  <th class="right">현재가</th>
                  <th class="right">평가금액</th>
                  <th class="right">손익</th>
                  <th class="right">손익률</th>
                  <th>매도</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in realPortfolio" :key="item.stockCode">
                  <td class="stock-name">{{ item.stockName }}</td>
                  <td class="stock-code">{{ item.stockCode }}</td>
                  <td class="right">{{ item.quantity }}주</td>
                  <td class="right">{{ formatNumber(item.averagePrice) }}원</td>
                  <td class="right">{{ formatNumber(item.currentPrice) }}원</td>
                  <td class="right">{{ formatCurrency(item.totalEvaluation) }}</td>
                  <td class="right" :class="getProfitClass(item.profitLoss)">
                    {{ formatProfitLoss(item.profitLoss) }}
                  </td>
                  <td class="right" :class="getProfitClass(item.profitRate)">
                    {{ formatPercent(item.profitRate) }}
                  </td>
                  <td>
                    <button @click="openSellModal(item, 'real')" class="sell-btn real-sell">매도</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="no-data">
            <p>실전 보유 종목이 없습니다.</p>
          </div>
        </div>

        <!-- 실전 수동 거래 -->
        <div class="section">
          <div class="section-header">
            <h2>실전 수동 거래</h2>
            <button @click="openTradeModal('real')" class="trade-btn real-trade-btn">수동 거래</button>
          </div>
          <div class="no-data">
            <p>실전 거래는 신중하게 진행하세요.</p>
          </div>
        </div>
      </div>

      <!-- 봇 성과 분석 탭 -->
      <div v-if="activeTab === 'botPerformance'" class="tab-content">
        <div class="section">
          <div class="section-header">
            <h2>봇 성과 분석</h2>
            <div class="perf-controls">
              <div class="perf-mode-toggle">
                <button
                  :class="['mode-btn', { active: perfMode === 'VIRTUAL' }]"
                  @click="switchPerfMode('VIRTUAL')"
                >🤖 모의</button>
                <button
                  :class="['mode-btn', { active: perfMode === 'REAL' }]"
                  @click="switchPerfMode('REAL')"
                >🔴 실전</button>
              </div>
              <select v-model="perfDays" @change="loadBotPerformance" class="perf-select">
                <option :value="7">최근 7일</option>
                <option :value="14">최근 14일</option>
                <option :value="30">최근 30일</option>
                <option :value="60">최근 60일</option>
                <option :value="90">최근 90일</option>
                <option :value="0">전체</option>
              </select>
              <button @click="loadBotPerformance" class="refresh-btn" :disabled="perfLoading">
                {{ perfLoading ? '로딩 중...' : '새로고침' }}
              </button>
            </div>
          </div>

          <!-- 성과 요약 카드 -->
          <div v-if="!perfLoading && botPerf" class="summary-grid perf-summary">
            <div class="summary-card">
              <h3>거래 현황</h3>
              <div class="card-content">
                <div class="stat-row">
                  <span class="label">총 매도 거래</span>
                  <span class="value">{{ botPerf.totalTrades }}건</span>
                </div>
                <div class="stat-row">
                  <span class="label">승률</span>
                  <span class="value" :class="getWinRateClass(botPerf.winRate)">
                    {{ formatPercent(botPerf.winRate) }}
                  </span>
                </div>
                <div class="stat-row">
                  <span class="label">수익/손실</span>
                  <span class="value">
                    <span class="win">{{ botPerf.winCount }}승</span> /
                    <span class="lose">{{ botPerf.loseCount }}패</span>
                  </span>
                </div>
                <div class="stat-row" v-if="botPerf.avgHoldingMinutes != null">
                  <span class="label">평균 보유 시간</span>
                  <span class="value">{{ formatHoldingTime(botPerf.avgHoldingMinutes) }}</span>
                </div>
              </div>
            </div>

            <div class="summary-card">
              <h3>손익 분석</h3>
              <div class="card-content">
                <div class="stat-row">
                  <span class="label">총 손익</span>
                  <span class="value" :class="getProfitClass(botPerf.totalPnl)">
                    {{ formatProfitLoss(botPerf.totalPnl) }}
                  </span>
                </div>
                <div class="stat-row">
                  <span class="label">평균 손익</span>
                  <span class="value" :class="getProfitClass(botPerf.avgPnl)">
                    {{ formatProfitLoss(botPerf.avgPnl) }}
                  </span>
                </div>
                <div class="stat-row">
                  <span class="label">최대 수익</span>
                  <span class="value positive">{{ formatProfitLoss(botPerf.maxWin) }}</span>
                </div>
                <div class="stat-row">
                  <span class="label">최대 손실</span>
                  <span class="value negative">{{ formatProfitLoss(botPerf.maxLoss) }}</span>
                </div>
                <div class="stat-row" v-if="botPerf.maxDrawdown != null">
                  <span class="label">최대 낙폭 (MDD)
                    <small class="hint" title="누적 손익 곡선의 고점 대비 최대 하락폭. 자산 보호 핵심 지표">ⓘ</small>
                  </span>
                  <span class="value negative">{{ formatProfitLoss(botPerf.maxDrawdown) }}</span>
                </div>
              </div>
            </div>

            <div class="summary-card">
              <h3>수익 팩터</h3>
              <div class="card-content">
                <div class="profit-factor-display">
                  <span class="pf-value" :class="getProfitFactorClass(botPerf.profitFactor)">
                    {{ botPerf.profitFactor != null ? Number(botPerf.profitFactor).toFixed(2) : '-' }}
                  </span>
                  <span class="pf-label">총수익 / 총손실</span>
                </div>
                <div class="pf-guide">
                  <span :class="{ highlight: botPerf.profitFactor >= 2 }">2.0 이상: 우수</span>
                  <span :class="{ highlight: botPerf.profitFactor >= 1 && botPerf.profitFactor < 2 }">1.0~2.0: 양호</span>
                  <span :class="{ highlight: botPerf.profitFactor < 1 }">1.0 미만: 손실</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 엑시트 사유별 통계 -->
          <div v-if="!perfLoading && botPerf && botPerf.exitReasonStats && Object.keys(botPerf.exitReasonStats).length > 0" class="perf-section">
            <h3>엑시트 사유별 통계</h3>
            <div class="table-container">
              <table class="perf-table">
                <thead>
                  <tr>
                    <th>사유</th>
                    <th class="right">건수</th>
                    <th class="right">총 손익</th>
                    <th class="right">평균 손익</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(stat, key) in botPerf.exitReasonStats" :key="key">
                    <td>{{ stat.reasonLabel }}</td>
                    <td class="right">{{ stat.count }}건</td>
                    <td class="right" :class="getProfitClass(stat.totalPnl)">{{ formatProfitLoss(stat.totalPnl) }}</td>
                    <td class="right" :class="getProfitClass(stat.avgPnl)">{{ formatProfitLoss(stat.avgPnl) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- 일별 손익 -->
          <div v-if="!perfLoading && botPerf && botPerf.dailyPnl && botPerf.dailyPnl.length > 0" class="perf-section">
            <h3>일별 손익 추이</h3>
            <div class="table-container">
              <table class="perf-table">
                <thead>
                  <tr>
                    <th>날짜</th>
                    <th class="right">손익</th>
                    <th class="right">거래 수</th>
                    <th class="right">누적 손익</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(day, idx) in botPerf.dailyPnl" :key="day.date">
                    <td>{{ day.date }}</td>
                    <td class="right" :class="getProfitClass(day.pnl)">{{ formatProfitLoss(day.pnl) }}</td>
                    <td class="right">{{ day.tradeCount }}건</td>
                    <td class="right" :class="getProfitClass(cumulativePnl(idx))">{{ formatProfitLoss(cumulativePnl(idx)) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- 종목별 손익 -->
          <div v-if="!perfLoading && botPerf && botPerf.stockPnl && botPerf.stockPnl.length > 0" class="perf-section">
            <h3>종목별 손익</h3>
            <div class="table-container">
              <table class="perf-table">
                <thead>
                  <tr>
                    <th>종목명</th>
                    <th>종목코드</th>
                    <th class="right">거래 수</th>
                    <th class="right">승률</th>
                    <th class="right">총 손익</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="stock in botPerf.stockPnl" :key="stock.stockCode">
                    <td class="stock-name">{{ stock.stockName }}</td>
                    <td class="stock-code">{{ stock.stockCode }}</td>
                    <td class="right">{{ stock.tradeCount }}건</td>
                    <td class="right" :class="getWinRateClass(stock.winRate)">{{ formatPercent(stock.winRate) }}</td>
                    <td class="right" :class="getProfitClass(stock.totalPnl)">{{ formatProfitLoss(stock.totalPnl) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- 데이터 없음 -->
          <div v-if="!perfLoading && (!botPerf || botPerf.totalTrades === 0)" class="no-data">
            <p>봇 거래 데이터가 없습니다.</p>
          </div>

          <!-- 로딩 -->
          <div v-if="perfLoading" class="no-data">
            <p>성과 데이터를 불러오는 중...</p>
          </div>
        </div>
      </div>

      <!-- 주간 매매 리포트 탭 -->
      <div v-if="activeTab === 'weeklyReport'" class="tab-content">
        <div class="section">
          <div class="section-header">
            <h2>주간 매매 리포트</h2>
            <div class="perf-controls">
              <div class="perf-mode-toggle">
                <button
                  :class="['mode-btn', { active: weeklyMode === 'VIRTUAL' }]"
                  @click="switchWeeklyMode('VIRTUAL')"
                >🤖 모의</button>
                <button
                  :class="['mode-btn real', { active: weeklyMode === 'REAL' }]"
                  @click="switchWeeklyMode('REAL')"
                >🔴 실전</button>
              </div>
              <button
                class="btn-generate-report"
                :disabled="weeklyGenerating"
                @click="generateWeeklyReport"
                title="지난주 리포트를 즉시 생성/재생성"
              >
                {{ weeklyGenerating ? '생성 중…' : '🔄 리포트 생성' }}
              </button>
            </div>
          </div>

          <!-- 최신 리포트 -->
          <div v-if="weeklyLatest" class="weekly-latest-card">
            <div class="weekly-head">
              <div>
                <span class="weekly-label">최신 리포트</span>
                <span class="weekly-period">
                  {{ formatDate(weeklyLatest.weekStart) }} ~ {{ formatDate(weeklyLatest.weekEnd) }}
                </span>
              </div>
              <span class="weekly-mode-badge" :class="weeklyLatest.mode === 'REAL' ? 'real' : ''">
                {{ weeklyLatest.mode === 'REAL' ? '실전' : '모의' }}
              </span>
            </div>

            <!-- 핵심 지표 -->
            <div class="weekly-stats">
              <div class="weekly-stat">
                <span class="stat-label">실현 손익</span>
                <span class="stat-value" :class="getProfitClass(weeklyLatest.realizedPnl)">
                  {{ formatProfitLoss(weeklyLatest.realizedPnl) }}
                </span>
              </div>
              <div class="weekly-stat">
                <span class="stat-label">매수/매도</span>
                <span class="stat-value">{{ weeklyLatest.totalBuys }} / {{ weeklyLatest.totalSells }}</span>
              </div>
              <div class="weekly-stat">
                <span class="stat-label">승/패</span>
                <span class="stat-value">
                  <span class="win">{{ weeklyLatest.winCount }}</span> / <span class="loss">{{ weeklyLatest.lossCount }}</span>
                </span>
              </div>
              <div class="weekly-stat">
                <span class="stat-label">승률</span>
                <span class="stat-value" :class="getWinRateClass(getWeeklyWinRate(weeklyLatest))">
                  {{ formatPercent(getWeeklyWinRate(weeklyLatest)) }}
                </span>
              </div>
              <div class="weekly-stat">
                <span class="stat-label">매수/매도 금액</span>
                <span class="stat-value small">
                  {{ formatCurrency(weeklyLatest.totalBuyAmount) }}<br>
                  / {{ formatCurrency(weeklyLatest.totalSellAmount) }}
                </span>
              </div>
              <div class="weekly-stat" v-if="weeklyLatest.blockedCount > 0">
                <span class="stat-label">차단된 거래</span>
                <span class="stat-value">{{ weeklyLatest.blockedCount }}건</span>
              </div>
            </div>

            <!-- AI 리포트 -->
            <div v-if="weeklyLatest.aiReport" class="weekly-ai-section">
              <h3 class="weekly-ai-title">🤖 AI 분석 리포트</h3>
              <div class="weekly-ai-body" v-html="formatAiReport(weeklyLatest.aiReport)"></div>
            </div>
            <div v-else class="weekly-no-ai">
              AI 분석이 아직 생성되지 않았습니다. [리포트 생성] 버튼으로 만들 수 있습니다.
            </div>
          </div>

          <!-- 12주 히스토리 -->
          <div v-if="weeklyHistory.length > 1" class="weekly-history">
            <h3 class="weekly-history-title">최근 {{ weeklyHistory.length }}주 히스토리</h3>
            <div class="weekly-history-table-wrap">
              <table class="weekly-history-table">
                <thead>
                  <tr>
                    <th>주차</th>
                    <th class="right">실현 손익</th>
                    <th class="right">매수/매도</th>
                    <th class="right">승률</th>
                    <th class="right">차단</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="r in weeklyHistory" :key="r.id"
                      :class="{ 'row-active': weeklyLatest && r.id === weeklyLatest.id }">
                    <td>{{ formatDate(r.weekStart) }}~{{ formatDate(r.weekEnd) }}</td>
                    <td class="right" :class="getProfitClass(r.realizedPnl)">
                      {{ formatProfitLoss(r.realizedPnl) }}
                    </td>
                    <td class="right">{{ r.totalBuys }} / {{ r.totalSells }}</td>
                    <td class="right" :class="getWinRateClass(getWeeklyWinRate(r))">
                      {{ formatPercent(getWeeklyWinRate(r)) }}
                    </td>
                    <td class="right">{{ r.blockedCount }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- 데이터 없음 -->
          <div v-if="!weeklyLoading && !weeklyLatest" class="no-data">
            <p>주간 리포트가 없습니다. [리포트 생성] 버튼으로 지난주 데이터로 생성하세요.</p>
          </div>

          <!-- 로딩 -->
          <div v-if="weeklyLoading" class="no-data">
            <p>리포트 데이터를 불러오는 중...</p>
          </div>
        </div>
      </div>

      <!-- 수동 거래 모달 -->
      <div v-if="showTradeModal" class="modal-overlay" @click.self="showTradeModal = false">
        <div class="modal" :class="{ 'real-modal': tradeMode === 'real' }">
          <h3>{{ tradeMode === 'real' ? '🔴 실전 수동 거래' : '수동 거래' }}</h3>
          <div v-if="tradeMode === 'real'" class="modal-warning">
            실제 계좌에서 주문이 체결됩니다!
          </div>
          <div class="form-group">
            <label>종목</label>
            <StockCodeInput
              v-model="tradeForm.stockCode"
              placeholder="종목명 또는 종목코드 (예: 삼성전자, 005930)"
              @select="onTradeStockSelected"
            />
          </div>
          <div class="form-group">
            <label>수량</label>
            <input v-model.number="tradeForm.quantity" type="number" min="1" placeholder="수량" />
          </div>
          <div class="form-group">
            <label>가격</label>
            <input v-model.number="tradeForm.price" type="number" min="1" placeholder="체결가" />
          </div>
          <div class="form-group">
            <label>거래 유형</label>
            <div class="trade-type-buttons">
              <button :class="{ active: tradeForm.tradeType === 'BUY' }" @click="tradeForm.tradeType = 'BUY'">
                매수
              </button>
              <button :class="{ active: tradeForm.tradeType === 'SELL' }" @click="tradeForm.tradeType = 'SELL'">
                매도
              </button>
            </div>
          </div>
          <div class="modal-actions">
            <button @click="showTradeModal = false" class="cancel-btn">취소</button>
            <button @click="executeTrade" class="submit-btn" :class="{ 'real-submit': tradeMode === 'real' }" :disabled="tradeLoading">
              {{ tradeLoading ? '처리 중...' : '거래 실행' }}
            </button>
          </div>
        </div>
      </div>

      <!-- 매도 모달 -->
      <div v-if="showSellModal" class="modal-overlay" @click.self="showSellModal = false">
        <div class="modal" :class="{ 'real-modal': sellMode === 'real' }">
          <h3>{{ sellMode === 'real' ? '🔴 ' : '' }}{{ sellForm.stockName }} 매도</h3>
          <div v-if="sellMode === 'real'" class="modal-warning">
            실제 계좌에서 매도됩니다!
          </div>
          <div class="sell-info">
            <p>보유 수량: <strong>{{ sellForm.maxQuantity }}주</strong></p>
            <p>현재가: <strong>{{ formatNumber(sellForm.currentPrice) }}원</strong></p>
          </div>
          <div class="form-group">
            <label>매도 수량</label>
            <input v-model.number="sellForm.quantity" type="number" :min="1" :max="sellForm.maxQuantity" />
          </div>
          <div class="form-group">
            <label>매도 가격</label>
            <input v-model.number="sellForm.price" type="number" min="1" />
          </div>
          <div class="modal-actions">
            <button @click="showSellModal = false" class="cancel-btn">취소</button>
            <button @click="executeSell" class="submit-btn sell" :class="{ 'real-submit': sellMode === 'real' }" :disabled="tradeLoading">
              {{ tradeLoading ? '처리 중...' : '매도 실행' }}
            </button>
          </div>
        </div>
      </div>

      <!-- 계좌 초기화 확인 모달 -->
      <div v-if="showInitializeConfirm" class="modal-overlay" @click.self="showInitializeConfirm = false">
        <div class="modal confirm-modal">
          <h3>계좌 초기화</h3>
          <p>정말 계좌를 초기화하시겠습니까?</p>
          <p class="warning">모든 거래 내역과 포트폴리오가 삭제됩니다.</p>
          <div class="form-group">
            <label>초기 자본금</label>
            <div class="amount-input-group">
              <input
                v-model.number="initForm.initialBalance"
                type="number"
                min="100000"
                step="1000000"
                placeholder="10,000,000"
              />
              <span class="unit">원</span>
            </div>
            <div class="amount-presets">
              <button type="button" @click="initForm.initialBalance = 5000000">500만</button>
              <button type="button" @click="initForm.initialBalance = 10000000">1,000만</button>
              <button type="button" @click="initForm.initialBalance = 50000000">5,000만</button>
              <button type="button" @click="initForm.initialBalance = 100000000">1억</button>
            </div>
          </div>
          <div class="modal-actions">
            <button @click="showInitializeConfirm = false" class="cancel-btn">취소</button>
            <button @click="initializeAccount" class="submit-btn danger" :disabled="initLoading">
              {{ initLoading ? '처리 중...' : '초기화' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, inject, onMounted, onUnmounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { paperTradingAPI } from '../utils/api';
import LoadingSpinner from '../components/LoadingSpinner.vue';
import GlobalNav from '../components/GlobalNav.vue';
import TradingSafetyWidget from '../components/v2/TradingSafetyWidget.vue';
import StockCodeInput from '../components/StockCodeInput.vue';
import DataFreshness from '../components/DataFreshness.vue';

const toast = inject('toast', { success(){}, error(){}, warning(){}, info(){} });

const props = defineProps({
  embedded: { type: Boolean, default: false }
});

const router = useRouter();
const route = useRoute();

// 탭 상태 (URL query parameter로 초기화)
const activeTab = ref(route.query.tab === 'real' ? 'real' : 'virtual');

// 상태
const loading = ref(true);
const portfolioLoading = ref(false);
const realPortfolioLoading = ref(false);
const botLoading = ref(false);
const tradeLoading = ref(false);
const initLoading = ref(false);

// 모의투자 데이터
const account = ref({});
const portfolio = ref([]);
const trades = ref([]);
const botStatus = ref({});

// 실전투자 데이터
const realAccount = ref({
  cashBalance: 0,
  totalEvaluation: 0,
  totalAsset: 0,
  totalInvested: 0,
  unrealizedProfitLoss: 0,
  profitRate: 0
});
const realPortfolio = ref([]);

// Live/Cached 배지 (5초마다 틱으로 갱신)
const nowTick = ref(Date.now());
let freshnessTickTimer = null;

const realFreshnessLabel = computed(() => {
  const ts = realAccount.value?.updatedAt;
  if (!ts) return '';
  const past = new Date(ts).getTime();
  if (isNaN(past)) return '';
  const secs = Math.max(0, Math.floor((nowTick.value - past) / 1000));
  if (secs < 5) return '🟢 Live';
  if (secs < 60) return `🟡 ${secs}초 전`;
  const mins = Math.floor(secs / 60);
  return `🟠 ${mins}분 전`;
});

const realFreshnessClass = computed(() => {
  const label = realFreshnessLabel.value;
  if (label.startsWith('🟢')) return 'fresh-live';
  if (label.startsWith('🟡')) return 'fresh-recent';
  return 'fresh-stale';
});

// 백엔드 AccountSummaryDto → UI 가 기대하는 필드명으로 변환
const mapRealAccount = (data) => ({
  ...data,
  cashBalance:   data.cashBalance   ?? data.currentBalance ?? 0,
  totalAsset:    data.totalAsset    ?? ((data.currentBalance || 0) + (data.totalEvaluation || 0)),
  profitRate:    data.profitRate    ?? data.totalProfitRate ?? 0,
  totalEvaluation: data.totalEvaluation ?? 0,
  totalInvested:   data.totalInvested   ?? 0,
  unrealizedProfitLoss: data.unrealizedProfitLoss ?? 0,
});

// 페이징
const currentPage = ref(0);
const totalPages = ref(0);
const pageSize = 20;

// 모달
const showTradeModal = ref(false);
const showSellModal = ref(false);
const showInitializeConfirm = ref(false);
const tradeMode = ref('virtual'); // 'virtual' or 'real'
const sellMode = ref('virtual');

// 폼
const tradeForm = ref({
  stockCode: '',
  quantity: 1,
  price: 0,
  tradeType: 'BUY'
});

const sellForm = ref({
  stockCode: '',
  stockName: '',
  quantity: 1,
  price: 0,
  maxQuantity: 0,
  currentPrice: 0
});

const initForm = ref({
  initialBalance: 10000000
});

// 봇 성과 분석 데이터
const perfLoading = ref(false);
const perfDays = ref(30);
const perfMode = ref('VIRTUAL');
const botPerf = ref(null);

const switchPerfMode = (mode) => {
  perfMode.value = mode;
  loadBotPerformance();
};

// 자동 새로고침
let refreshTimer = null;
let countdownTimer = null;

// 데이터 신선도 상태
const lastUpdated = ref(null);
const isRefreshing = ref(false);
const nextRefreshIn = ref(30);

const manualRefresh = async () => {
  await loadData();
  if (activeTab.value === 'real') {
    await loadRealData();
  }
};

// 총 자산 계산
const totalAsset = computed(() => {
  return (account.value.currentBalance || 0) + (account.value.totalEvaluation || 0);
});

// 실전투자 탭 전환
const switchToRealTab = () => {
  activeTab.value = 'real';
  loadRealData();
};

// 봇 성과 탭 전환
const switchToBotPerformanceTab = () => {
  activeTab.value = 'botPerformance';
  if (!botPerf.value) {
    loadBotPerformance();
  }
};

// ===== 주간 리포트 =====
const weeklyMode = ref('REAL');
const weeklyLatest = ref(null);
const weeklyHistory = ref([]);
const weeklyLoading = ref(false);
const weeklyGenerating = ref(false);

const switchToWeeklyReportTab = () => {
  activeTab.value = 'weeklyReport';
  if (!weeklyLatest.value) loadWeeklyReports();
};

const switchWeeklyMode = (mode) => {
  weeklyMode.value = mode;
  loadWeeklyReports();
};

const loadWeeklyReports = async () => {
  weeklyLoading.value = true;
  try {
    const [latestRes, historyRes] = await Promise.all([
      paperTradingAPI.getLatestWeeklyReport(weeklyMode.value),
      paperTradingAPI.getRecentWeeklyReports(weeklyMode.value)
    ]);
    weeklyLatest.value = latestRes.data?.data || null;
    weeklyHistory.value = Array.isArray(historyRes.data?.data) ? historyRes.data.data : [];
  } catch (e) {
    console.error('주간 리포트 로드 실패', e);
    toast.error('주간 리포트 로드 실패');
  } finally {
    weeklyLoading.value = false;
  }
};

const generateWeeklyReport = async () => {
  if (weeklyGenerating.value) return;
  weeklyGenerating.value = true;
  try {
    const res = await paperTradingAPI.generateWeeklyReport(weeklyMode.value);
    if (res.data?.success) {
      toast.success('리포트가 생성되었습니다');
      await loadWeeklyReports();
    } else {
      toast.error('리포트 생성 실패');
    }
  } catch (e) {
    console.error('주간 리포트 생성 실패', e);
    toast.error('리포트 생성 실패: ' + (e.message || ''));
  } finally {
    weeklyGenerating.value = false;
  }
};

const getWeeklyWinRate = (r) => {
  if (!r) return 0;
  const total = (r.winCount || 0) + (r.lossCount || 0);
  if (total === 0) return 0;
  return ((r.winCount || 0) / total) * 100;
};

const formatDate = (dateStr) => {
  if (!dateStr) return '-';
  const d = new Date(dateStr);
  return `${d.getMonth() + 1}/${d.getDate()}`;
};

const formatAiReport = (text) => {
  if (!text) return '';
  // 간단한 마크다운 풍 렌더링: ** -> <strong>, 줄바꿈 -> <br>
  const escaped = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
  return escaped
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>');
};

// 봇 성과 데이터 로드
const loadBotPerformance = async () => {
  perfLoading.value = true;
  try {
    const res = await paperTradingAPI.getBotPerformance(perfDays.value, perfMode.value);
    if (res.data.success) {
      botPerf.value = res.data.data;
    }
  } catch (error) {
    console.error('봇 성과 로드 오류:', error);
  } finally {
    perfLoading.value = false;
  }
};

// 누적 손익 계산 (일별 손익 테이블용 - 역순이므로 뒤에서부터 합산)
const cumulativePnl = (idx) => {
  if (!botPerf.value || !botPerf.value.dailyPnl) return 0;
  const dailyList = botPerf.value.dailyPnl;
  let sum = 0;
  for (let i = dailyList.length - 1; i >= idx; i--) {
    sum += Number(dailyList[i].pnl || 0);
  }
  return sum;
};

// 보유 시간 포맷
const formatHoldingTime = (minutes) => {
  if (minutes == null) return '-';
  if (minutes < 60) return Math.round(minutes) + '분';
  const hours = Math.floor(minutes / 60);
  const mins = Math.round(minutes % 60);
  return hours + '시간 ' + mins + '분';
};

// 수익팩터 클래스
const getProfitFactorClass = (value) => {
  if (value == null) return '';
  if (value >= 2) return 'pf-excellent';
  if (value >= 1) return 'pf-good';
  return 'pf-bad';
};

// 모의투자 데이터 로드 (각 API 독립적으로 처리 - 하나 실패해도 다른 것 표시)
const loadData = async () => {
  isRefreshing.value = true;
  try {
  const loadAccount = async () => {
    try {
      const res = await paperTradingAPI.getAccountSummary();
      if (res.data.success) account.value = res.data.data;
    } catch (e) { console.warn('계좌 로드 실패:', e.message); }
  };

  const loadPortfolio = async () => {
    try {
      const res = await paperTradingAPI.getPortfolio();
      if (res.data.success) portfolio.value = res.data.data;
    } catch (e) { console.warn('포트폴리오 로드 실패:', e.message); }
  };

  const loadTrades = async () => {
    try {
      const res = await paperTradingAPI.getTradeHistory(0, pageSize);
      if (res.data.success) {
        trades.value = res.data.data;
        totalPages.value = res.data.totalPages;
      }
    } catch (e) { console.warn('거래내역 로드 실패:', e.message); }
  };

  const loadBotStatus = async () => {
    try {
      const res = await paperTradingAPI.getBotStatus();
      if (res.data.success) botStatus.value = res.data.data;
    } catch (e) { console.warn('봇 상태 로드 실패:', e.message); }
  };

  await Promise.all([loadAccount(), loadPortfolio(), loadTrades(), loadBotStatus()]);
  loading.value = false;
  } finally {
    isRefreshing.value = false;
    lastUpdated.value = new Date();
    nextRefreshIn.value = 30;
  }
};

// 실전투자 데이터 로드
const loadRealData = async () => {
  realPortfolioLoading.value = true;
  try {
    const [accountRes, portfolioRes] = await Promise.all([
      paperTradingAPI.getRealAccountSummary(),
      paperTradingAPI.getRealPortfolio()
    ]);

    if (accountRes.data.success) {
      realAccount.value = mapRealAccount(accountRes.data.data);
    }
    if (portfolioRes.data.success) {
      realPortfolio.value = portfolioRes.data.data;
    }
  } catch (error) {
    console.error('실전투자 데이터 로드 오류:', error);
  } finally {
    realPortfolioLoading.value = false;
  }
};

// 포트폴리오 새로고침
const refreshPortfolio = async () => {
  portfolioLoading.value = true;
  try {
    const [accountRes, portfolioRes] = await Promise.all([
      paperTradingAPI.getAccountSummary(),
      paperTradingAPI.refreshPortfolio()
    ]);

    if (accountRes.data.success) {
      account.value = accountRes.data.data;
    }
    if (portfolioRes.data.success) {
      portfolio.value = portfolioRes.data.data;
    }
  } catch (error) {
    console.error('포트폴리오 새로고침 오류:', error);
    toast.error('새로고침에 실패했습니다');
  } finally {
    portfolioLoading.value = false;
  }
};

// 실전 포트폴리오 새로고침
const refreshRealPortfolio = async () => {
  realPortfolioLoading.value = true;
  try {
    const [accountRes, portfolioRes] = await Promise.all([
      paperTradingAPI.getRealAccountSummary(),
      paperTradingAPI.getRealPortfolio()
    ]);

    if (accountRes.data.success) {
      realAccount.value = mapRealAccount(accountRes.data.data);
    }
    if (portfolioRes.data.success) {
      realPortfolio.value = portfolioRes.data.data;
    }
  } catch (error) {
    console.error('실전 포트폴리오 새로고침 오류:', error);
    toast.error('새로고침에 실패했습니다');
  } finally {
    realPortfolioLoading.value = false;
  }
};

// 거래 내역 페이지 변경
const changePage = async (page) => {
  if (page < 0 || page >= totalPages.value) return;
  currentPage.value = page;

  try {
    const res = await paperTradingAPI.getTradeHistory(page, pageSize);
    if (res.data.success) {
      trades.value = res.data.data;
    }
  } catch (error) {
    console.error('거래 내역 조회 오류:', error);
  }
};

// 봇 시작 (모드 선택)
const startBot = async (mode) => {
  // 실전투자 모드일 경우 확인 다이얼로그
  if (mode === 'REAL') {
    const confirmed = confirm(
      '⚠️ 실전투자 모드로 봇을 시작하시겠습니까?\n\n' +
      '실제 계좌에서 주문이 체결됩니다.\n' +
      '손실이 발생할 수 있습니다.'
    );
    if (!confirmed) return;
  }

  botLoading.value = true;
  try {
    const res = await paperTradingAPI.startBot(mode);
    if (res.data.success) {
      botStatus.value = res.data.data;
      const modeName = mode === 'REAL' ? '실전투자' : '모의투자';
      toast.success(`자동매매 봇이 ${modeName} 모드로 시작되었습니다`);
    }
  } catch (error) {
    console.error('봇 시작 오류:', error);
    toast.error('봇 시작 실패: ' + (error.response?.data?.error || error.message));
  } finally {
    botLoading.value = false;
  }
};

// 봇 중지
const stopBot = async () => {
  botLoading.value = true;
  try {
    const res = await paperTradingAPI.stopBot();
    if (res.data.success) {
      botStatus.value = res.data.data;
      toast.warning('자동매매 봇이 중지되었습니다');
    }
  } catch (error) {
    console.error('봇 중지 오류:', error);
    toast.error('봇 중지에 실패했습니다');
  } finally {
    botLoading.value = false;
  }
};

// 거래 모달 열기
const openTradeModal = (mode) => {
  tradeMode.value = mode;
  tradeForm.value = { stockCode: '', quantity: 1, price: 0, tradeType: 'BUY' };
  showTradeModal.value = true;
};

// 종목 선택 시 — 캐시된 현재가가 있으면 가격 폼에 자동 채움
const onTradeStockSelected = (stock) => {
  if (stock?.currentPrice && stock.currentPrice > 0 && !tradeForm.value.price) {
    tradeForm.value.price = Number(stock.currentPrice);
  }
};

// 수동 거래 실행
const executeTrade = async () => {
  if (!tradeForm.value.stockCode || !tradeForm.value.quantity || !tradeForm.value.price) {
    toast.warning('모든 필드를 입력해주세요');
    return;
  }

  if (tradeMode.value === 'real') {
    const confirmed = confirm('실제 계좌에서 거래가 실행됩니다. 계속하시겠습니까?');
    if (!confirmed) return;
  }

  tradeLoading.value = true;
  try {
    const tradeData = {
      stockCode: tradeForm.value.stockCode,
      quantity: tradeForm.value.quantity,
      price: tradeForm.value.price,
      tradeType: tradeForm.value.tradeType
    };

    const res = tradeMode.value === 'real'
      ? await paperTradingAPI.placeRealTrade(tradeData)
      : await paperTradingAPI.placeTrade(tradeData);

    if (res.data.success) {
      toast.success(res.data.message || '거래가 체결되었습니다');
      showTradeModal.value = false;
      tradeForm.value = { stockCode: '', quantity: 1, price: 0, tradeType: 'BUY' };
      if (tradeMode.value === 'real') {
        await loadRealData();
      } else {
        await loadData();
      }
    } else {
      toast.error(res.data.error || '거래 실패');
    }
  } catch (error) {
    console.error('거래 실행 오류:', error);
    toast.error(error.response?.data?.error || '거래 실행에 실패했습니다');
  } finally {
    tradeLoading.value = false;
  }
};

// 매도 모달 열기
const openSellModal = (item, mode) => {
  sellMode.value = mode;
  sellForm.value = {
    stockCode: item.stockCode,
    stockName: item.stockName,
    quantity: item.quantity,
    price: item.currentPrice,
    maxQuantity: item.quantity,
    currentPrice: item.currentPrice
  };
  showSellModal.value = true;
};

// 매도 실행
const executeSell = async () => {
  if (!sellForm.value.quantity || !sellForm.value.price) {
    toast.warning('수량과 가격을 입력해주세요');
    return;
  }

  if (sellForm.value.quantity > sellForm.value.maxQuantity) {
    toast.warning('보유 수량을 초과할 수 없습니다');
    return;
  }

  if (sellMode.value === 'real') {
    const confirmed = confirm('실제 계좌에서 매도됩니다. 계속하시겠습니까?');
    if (!confirmed) return;
  }

  tradeLoading.value = true;
  try {
    const tradeData = {
      stockCode: sellForm.value.stockCode,
      quantity: sellForm.value.quantity,
      price: sellForm.value.price,
      tradeType: 'SELL'
    };

    const res = sellMode.value === 'real'
      ? await paperTradingAPI.placeRealTrade(tradeData)
      : await paperTradingAPI.placeTrade(tradeData);

    if (res.data.success) {
      toast.success(res.data.message || '매도가 체결되었습니다');
      showSellModal.value = false;
      if (sellMode.value === 'real') {
        await loadRealData();
      } else {
        await loadData();
      }
    } else {
      toast.error(res.data.error || '매도 실패');
    }
  } catch (error) {
    console.error('매도 실행 오류:', error);
    toast.error(error.response?.data?.error || '매도 실행에 실패했습니다');
  } finally {
    tradeLoading.value = false;
  }
};

// 계좌 초기화
const initializeAccount = async () => {
  initLoading.value = true;
  try {
    const res = await paperTradingAPI.initializeAccount(initForm.value.initialBalance);
    if (res.data.success) {
      toast.success('계좌가 초기화되었습니다');
      showInitializeConfirm.value = false;
      initForm.value.initialBalance = 10000000; // 기본값으로 리셋
      await loadData();
    }
  } catch (error) {
    console.error('계좌 초기화 오류:', error);
    toast.error('계좌 초기화에 실패했습니다');
  } finally {
    initLoading.value = false;
  }
};

// 포맷 함수들
const formatCurrency = (value) => {
  if (value === null || value === undefined) return '0원';
  return new Intl.NumberFormat('ko-KR').format(Math.round(value)) + '원';
};

const formatNumber = (value) => {
  if (value === null || value === undefined) return '0';
  return new Intl.NumberFormat('ko-KR').format(Math.round(value));
};

const formatProfitLoss = (value) => {
  if (value === null || value === undefined) return '0원';
  const sign = value >= 0 ? '+' : '';
  return sign + new Intl.NumberFormat('ko-KR').format(Math.round(value)) + '원';
};

const formatPercent = (value) => {
  if (value === null || value === undefined) return '0.00%';
  const sign = value >= 0 ? '+' : '';
  return sign + Number(value).toFixed(2) + '%';
};

const formatDateTime = (dateStr) => {
  if (!dateStr) return '-';
  const date = new Date(dateStr);
  return date.toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
};

const formatTime = (dateStr) => {
  if (!dateStr) return '-';
  const date = new Date(dateStr);
  return date.toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
};

// 클래스 함수들
const getProfitClass = (value) => {
  if (value === null || value === undefined) return '';
  return value > 0 ? 'positive' : value < 0 ? 'negative' : '';
};

const getWinRateClass = (value) => {
  if (value === null || value === undefined) return '';
  return value >= 50 ? 'positive' : 'negative';
};

const getBotStatusClass = (status) => {
  switch (status) {
    case 'RUNNING': return 'running';
    case 'STOPPED': return 'stopped';
    case 'ERROR': return 'error';
    case 'VIX_PAUSED': return 'vix-paused';
    case 'KOSPI_DROP_PAUSED': return 'vix-paused';
    case 'STOP_LOSS_PAUSED': return 'error';
    case 'KILL_SWITCH': return 'error';
    default: return '';
  }
};

const getBotStatusText = (botStatus, mode) => {
  if (botStatus.tradingMode !== mode) return '중지됨';
  switch (botStatus.status) {
    case 'VIX_PAUSED': return '⏸️ VIX 일시정지';
    case 'KOSPI_DROP_PAUSED': return '⏸️ KOSPI 하락 정지';
    case 'STOP_LOSS_PAUSED': return '🛑 연속손절 정지';
    case 'KILL_SWITCH': return '🛑 킬스위치 발동';
    case 'ERROR': return '⚠️ 오류';
    case 'RUNNING': return botStatus.active ? '실행 중' : '중지됨';
    default: return botStatus.active ? '실행 중' : '중지됨';
  }
};

onMounted(() => {
  loadData();
  // URL에서 실전투자 탭으로 진입한 경우 실전 데이터도 로드
  if (activeTab.value === 'real') {
    loadRealData();
  }
  // 30초마다 자동 새로고침
  refreshTimer = setInterval(() => {
    if (document.hidden) return;
    loadData();
    if (activeTab.value === 'real') {
      loadRealData();
    }
  }, 30000);
  // 카운트다운 (1초)
  countdownTimer = setInterval(() => {
    if (!document.hidden && nextRefreshIn.value > 0) nextRefreshIn.value--;
  }, 1000);
  // 배지 갱신 틱 (5초)
  freshnessTickTimer = setInterval(() => {
    nowTick.value = Date.now();
  }, 5000);
});

onUnmounted(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer);
  }
  if (countdownTimer) {
    clearInterval(countdownTimer);
  }
  if (freshnessTickTimer) {
    clearInterval(freshnessTickTimer);
  }
});
</script>

<style scoped>
.paper-trading-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
  padding: 2rem;
}
.paper-trading-page.embedded {
  min-height: unset;
  background: transparent;
  padding: 0;
}

.content-wrapper {
  max-width: 1400px;
  margin: 0 auto;
  background: #0f0f23;
  border-radius: 20px;
  padding: 2rem;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
  border: 1px solid #2a2a4a;
}

/* 탭 네비게이션 */
.tab-navigation {
  display: flex;
  gap: 1rem;
  margin-bottom: 2rem;
  border-bottom: 2px solid #2a2a4a;
  padding-bottom: 0;
}

.tab-btn {
  flex: 1;
  padding: 1rem 2rem;
  background: transparent;
  border: none;
  color: #888;
  font-size: 1.1rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s;
  border-bottom: 3px solid transparent;
  margin-bottom: -2px;
}

.tab-btn:hover {
  color: #fff;
  background: rgba(74, 74, 138, 0.2);
}

.tab-btn.active {
  color: #4fd1c5;
  border-bottom-color: #4fd1c5;
}

.tab-btn.real.active {
  color: #e53e3e;
  border-bottom-color: #e53e3e;
}

.tab-content {
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

/* 경고 배너 */
.warning-banner {
  background: linear-gradient(135deg, rgba(229, 62, 62, 0.2) 0%, rgba(197, 48, 48, 0.2) 100%);
  border: 1px solid #e53e3e;
  border-radius: 10px;
  padding: 1rem 1.5rem;
  margin-bottom: 1.5rem;
  display: flex;
  align-items: center;
  gap: 1rem;
}

.warning-banner .warning-icon {
  font-size: 1.5rem;
}

.warning-banner span {
  color: #fc8181;
  font-weight: 500;
}

/* 요약 그리드 */
.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 1.5rem;
  margin-bottom: 2rem;
}

.summary-card {
  background: #1a1a3a;
  border-radius: 15px;
  padding: 1.5rem;
  border: 1px solid rgba(255,255,255,0.1);
  transition: all 0.3s;
}

.summary-card:hover {
  border-color: #4a4a8a;
}

.summary-card.real-account {
  border-color: rgba(229, 62, 62, 0.5);
}

.summary-card .card-icon {
  font-size: 2rem;
  margin-bottom: 0.5rem;
}

.summary-card h3 {
  color: #fff;
  font-size: 1.1rem;
  margin-bottom: 1rem;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.freshness-badge {
  font-size: 0.7rem;
  font-weight: 500;
  padding: 0.1rem 0.5rem;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.freshness-badge.fresh-live {
  color: #4ade80;
  border-color: rgba(74, 222, 128, 0.3);
}

.freshness-badge.fresh-recent {
  color: #fbbf24;
  border-color: rgba(251, 191, 36, 0.3);
}

.freshness-badge.fresh-stale {
  color: #f97316;
  border-color: rgba(249, 115, 22, 0.3);
}

.card-content {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.stat-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.3rem 0;
}

.stat-row .label {
  color: #888;
  font-size: 0.9rem;
}

.stat-row .value {
  color: #fff;
  font-weight: 600;
}

.stat-row .value.highlight {
  color: #4fd1c5;
  font-size: 1.1rem;
}

.stat-row .value small {
  font-size: 0.85rem;
  margin-left: 0.5rem;
}

.stat-row .win { color: #e53e3e; }
.stat-row .lose { color: #3182ce; }

.profit-row {
  margin-top: 0.5rem;
  padding-top: 0.5rem;
  border-top: 1px solid #2a2a4a;
}

.reset-btn {
  margin-top: 1rem;
  width: 100%;
  background: #4a4a8a;
  color: white;
  border: none;
  padding: 0.5rem;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.3s;
}

.reset-btn:hover {
  background: #e53e3e;
}

/* 봇 카드 */
.bot-card.active {
  border-color: #48bb78;
}

.bot-card.real-mode {
  border-color: rgba(229, 62, 62, 0.5);
}

.bot-card.real-mode.active {
  border-color: #e53e3e;
  box-shadow: 0 0 20px rgba(229, 62, 62, 0.3);
}

.bot-controls {
  margin-top: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.start-btn, .stop-btn {
  width: 100%;
  padding: 0.75rem;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-weight: 600;
  transition: all 0.3s;
}

.start-btn.virtual-btn {
  background: #48bb78;
  color: white;
}

.start-btn.virtual-btn:hover:not(:disabled) {
  background: #38a169;
}

.start-btn.real-btn {
  background: linear-gradient(135deg, #e53e3e 0%, #c53030 100%);
  color: white;
  border: 1px solid #ff6b6b;
}

.start-btn.real-btn:hover:not(:disabled) {
  background: linear-gradient(135deg, #c53030 0%, #9b2c2c 100%);
  box-shadow: 0 0 10px rgba(229, 62, 62, 0.5);
}

.stop-btn {
  background: #e53e3e;
  color: white;
}

.stop-btn:hover:not(:disabled) {
  background: #c53030;
}

.start-btn:disabled, .stop-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 상태 클래스 */
.positive { color: #e53e3e !important; }
.positive::before { content: '▲ '; font-size: 0.75em; }
.negative { color: #3182ce !important; }
.negative::before { content: '▼ '; font-size: 0.75em; }
.running { color: #48bb78 !important; }
.stopped { color: #888 !important; }
.error { color: #ed8936 !important; }
.vix-paused { color: #f6e05e !important; }

/* 섹션 */
.section {
  margin-bottom: 2rem;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.section-header h2 {
  color: #fff;
  font-size: 1.3rem;
}

.refresh-btn, .trade-btn {
  background: #4a4a8a;
  color: white;
  border: none;
  padding: 0.5rem 1rem;
  border-radius: 8px;
  cursor: pointer;
  font-size: 0.9rem;
  transition: all 0.3s;
}

.refresh-btn:hover, .trade-btn:hover {
  background: #5a5a9a;
}

.trade-btn {
  background: #48bb78;
}

.trade-btn:hover {
  background: #38a169;
}

.real-trade-btn {
  background: linear-gradient(135deg, #e53e3e 0%, #c53030 100%);
}

.real-trade-btn:hover {
  background: linear-gradient(135deg, #c53030 0%, #9b2c2c 100%);
}

/* 테이블 */
.table-container {
  overflow-x: auto;
}

.portfolio-table, .trades-table {
  width: 100%;
  border-collapse: collapse;
  background: #1a1a3a;
  border-radius: 10px;
  overflow: hidden;
}

.portfolio-table th, .trades-table th,
.portfolio-table td, .trades-table td {
  padding: 1rem;
  text-align: left;
  border-bottom: 1px solid #2a2a4a;
}

.portfolio-table th, .trades-table th {
  background: #2a2a4a;
  color: #aaa;
  font-weight: 600;
  font-size: 0.9rem;
}

.portfolio-table td, .trades-table td {
  color: #fff;
}

.right {
  text-align: right !important;
}

.stock-name {
  font-weight: 600;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stock-code {
  color: #888;
  font-family: monospace;
}

.time {
  color: #888;
  font-size: 0.9rem;
}

.reason {
  color: #888;
  font-size: 0.85rem;
}

.trades-table tr.buy td:nth-child(3) { color: #e53e3e; }
.trades-table tr.sell td:nth-child(3) { color: #3182ce; }

.sell-btn {
  background: #e53e3e;
  color: white;
  border: none;
  padding: 0.4rem 0.8rem;
  border-radius: 5px;
  cursor: pointer;
  font-size: 0.85rem;
  transition: all 0.3s;
}

.sell-btn:hover {
  background: #c53030;
}

.sell-btn.real-sell {
  background: linear-gradient(135deg, #e53e3e 0%, #c53030 100%);
  border: 1px solid #ff6b6b;
}

/* 페이징 */
.pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 1rem;
  margin-top: 1rem;
  padding: 1rem;
}

.pagination button {
  background: #4a4a8a;
  color: white;
  border: none;
  padding: 0.5rem 1rem;
  border-radius: 5px;
  cursor: pointer;
}

.pagination button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.pagination span {
  color: #888;
}

/* 모달 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}

.modal {
  background: #1a1a3a;
  border-radius: 15px;
  padding: 2rem;
  width: 90%;
  max-width: 400px;
  border: 1px solid rgba(255,255,255,0.1);
}

.modal.real-modal {
  border-color: #e53e3e;
  box-shadow: 0 0 30px rgba(229, 62, 62, 0.3);
}

.modal h3 {
  color: #fff;
  margin-bottom: 1.5rem;
  text-align: center;
}

.modal-warning {
  background: rgba(229, 62, 62, 0.2);
  border: 1px solid #e53e3e;
  border-radius: 8px;
  padding: 0.75rem;
  margin-bottom: 1rem;
  text-align: center;
  color: #fc8181;
  font-weight: 500;
}

.form-group {
  margin-bottom: 1rem;
}

.form-group label {
  display: block;
  color: #aaa;
  margin-bottom: 0.5rem;
  font-size: 0.9rem;
}

.form-group input {
  width: 100%;
  padding: 0.75rem;
  background: #2a2a4a;
  border: 1px solid #3a3a5a;
  border-radius: 8px;
  color: #fff;
  font-size: 1rem;
}

.form-group input:focus {
  outline: none;
  border-color: #4a4a8a;
}

.trade-type-buttons {
  display: flex;
  gap: 1rem;
}

.trade-type-buttons button {
  flex: 1;
  padding: 0.75rem;
  border: 1px solid rgba(255,255,255,0.15);
  background: transparent;
  color: #888;
  border-radius: 8px;
  cursor: pointer;
  font-weight: 600;
  transition: all 0.3s;
}

.trade-type-buttons button.active {
  background: #4a4a8a;
  border-color: #4a4a8a;
  color: #fff;
}

.trade-type-buttons button:first-child.active {
  background: #e53e3e;
  border-color: #e53e3e;
}

.trade-type-buttons button:last-child.active {
  background: #3182ce;
  border-color: #3182ce;
}

.sell-info {
  background: #2a2a4a;
  padding: 1rem;
  border-radius: 8px;
  margin-bottom: 1rem;
}

.sell-info p {
  color: #aaa;
  margin: 0.5rem 0;
}

.sell-info strong {
  color: #fff;
}

.modal-actions {
  display: flex;
  gap: 1rem;
  margin-top: 1.5rem;
}

.cancel-btn, .submit-btn {
  flex: 1;
  padding: 0.75rem;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-weight: 600;
  transition: all 0.3s;
}

.cancel-btn {
  background: #3a3a5a;
  color: #aaa;
}

.cancel-btn:hover {
  background: #4a4a6a;
}

.submit-btn {
  background: #48bb78;
  color: white;
}

.submit-btn:hover:not(:disabled) {
  background: #38a169;
}

.submit-btn.sell {
  background: #e53e3e;
}

.submit-btn.sell:hover:not(:disabled) {
  background: #c53030;
}

.submit-btn.real-submit {
  background: linear-gradient(135deg, #e53e3e 0%, #c53030 100%);
}

.submit-btn.danger {
  background: #e53e3e;
}

.submit-btn.danger:hover:not(:disabled) {
  background: #c53030;
}

.submit-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.confirm-modal p {
  color: #aaa;
  text-align: center;
  margin: 0.5rem 0;
}

.confirm-modal .warning {
  color: #ed8936;
  font-size: 0.9rem;
}

.amount-input-group {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.amount-input-group input {
  flex: 1;
  padding: 0.75rem;
  background: #2a2a4a;
  border: 1px solid #3a3a5a;
  border-radius: 8px;
  color: #fff;
  font-size: 1rem;
}

.amount-input-group input:focus {
  outline: none;
  border-color: #4a4a8a;
}

.amount-input-group .unit {
  color: #aaa;
  font-size: 1rem;
}

.amount-presets {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.5rem;
}

.amount-presets button {
  flex: 1;
  padding: 0.5rem;
  background: #2a2a4a;
  border: 1px solid #3a3a5a;
  border-radius: 6px;
  color: #aaa;
  font-size: 0.85rem;
  cursor: pointer;
  transition: all 0.2s;
}

.amount-presets button:hover {
  background: #4a4a8a;
  border-color: #4a4a8a;
  color: #fff;
}

/* 데이터 없음 */
.no-data {
  text-align: center;
  padding: 3rem;
  color: #666;
  background: #1a1a3a;
  border-radius: 10px;
}

/* ===== 봇 성과 분석 탭 ===== */
.perf-controls {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  flex-wrap: wrap;
}

.perf-mode-toggle {
  display: inline-flex;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #3a3a5a;
}
.perf-mode-toggle .mode-btn {
  padding: 0.5rem 0.9rem;
  background: #2a2a4a;
  color: #aaa;
  border: none;
  cursor: pointer;
  font-size: 0.9rem;
  transition: background 0.15s, color 0.15s;
}
.perf-mode-toggle .mode-btn.active {
  background: rgba(102, 126, 234, 0.35);
  color: #fff;
  font-weight: 600;
}
.perf-mode-toggle .mode-btn:hover:not(.active) {
  background: #34345a;
  color: #ddd;
}

.perf-select {
  padding: 0.5rem 1rem;
  background: #2a2a4a;
  border: 1px solid #3a3a5a;
  border-radius: 8px;
  color: #fff;
  font-size: 0.9rem;
  cursor: pointer;
}

.perf-select:focus {
  outline: none;
  border-color: #4fd1c5;
}

.perf-summary {
  margin-bottom: 2rem;
}

.perf-section {
  margin-top: 2rem;
}

.perf-section h3 {
  color: #e0e0e0;
  margin-bottom: 1rem;
  font-size: 1.1rem;
  padding-left: 0.5rem;
  border-left: 3px solid #4fd1c5;
}

.perf-table {
  width: 100%;
  border-collapse: collapse;
  background: #1a1a3a;
  border-radius: 10px;
  overflow: hidden;
}

.perf-table th {
  background: #2a2a4a;
  color: #aaa;
  font-weight: 600;
  font-size: 0.85rem;
  padding: 0.75rem 1rem;
  text-align: left;
}

.perf-table th.right {
  text-align: right;
}

.perf-table td {
  padding: 0.65rem 1rem;
  color: #ddd;
  border-bottom: 1px solid #2a2a4a;
  font-size: 0.9rem;
}

.perf-table td.right {
  text-align: right;
}

.perf-table tbody tr:hover {
  background: rgba(79, 209, 197, 0.05);
}

/* 수익팩터 디스플레이 */
.profit-factor-display {
  text-align: center;
  padding: 1rem 0 0.5rem;
}

.pf-value {
  font-size: 2.5rem;
  font-weight: 700;
  display: block;
}

.pf-value.pf-excellent {
  color: #48bb78;
}

.pf-value.pf-good {
  color: #4fd1c5;
}

.pf-value.pf-bad {
  color: #e53e3e;
}

.pf-label {
  display: block;
  color: #888;
  font-size: 0.8rem;
  margin-top: 0.25rem;
}

.pf-guide {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  margin-top: 0.75rem;
  font-size: 0.8rem;
  color: #666;
}

.pf-guide .highlight {
  color: #4fd1c5;
  font-weight: 600;
}

/* 반응형 */
@media (max-width: 768px) {
  .paper-trading-page {
    padding: 1rem;
  }

  .content-wrapper {
    padding: 1rem;
  }

  .tab-navigation {
    flex-direction: column;
    gap: 0.5rem;
  }

  .tab-btn {
    border-bottom: none;
    border-left: 3px solid transparent;
    text-align: left;
    padding: 0.75rem 1rem;
  }

  .tab-btn.active {
    border-left-color: #4fd1c5;
    border-bottom-color: transparent;
  }

  .tab-btn.real.active {
    border-left-color: #e53e3e;
  }

  .summary-grid {
    grid-template-columns: 1fr;
  }

  /* 큰 폰트 축소 — 데스크톱 2.5rem 은 모바일에서 한 줄 폭 초과 가능 */
  .pf-value { font-size: 1.5rem; }
  .summary-card .card-icon { font-size: 1.5rem; margin-bottom: 0.25rem; }
  .summary-card h3 { font-size: 1rem; margin-bottom: 0.5rem; }

  .portfolio-table th:nth-child(4),
  .portfolio-table th:nth-child(5),
  .portfolio-table td:nth-child(4),
  .portfolio-table td:nth-child(5) {
    display: none;
  }
}

/* ===== 주간 리포트 ===== */
.btn-generate-report {
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  border: none;
  padding: 8px 16px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.2s;
}
.btn-generate-report:hover:not(:disabled) { opacity: 0.9; }
.btn-generate-report:disabled { opacity: 0.5; cursor: wait; }

.weekly-latest-card {
  background: rgba(255,255,255,0.95);
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 20px;
  border: 1px solid rgba(0,0,0,0.08);
}
.weekly-head {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(0,0,0,0.08);
}
.weekly-label {
  font-size: 11px;
  color: #888;
  font-weight: 700;
  text-transform: uppercase;
  margin-right: 8px;
}
.weekly-period { font-size: 14px; font-weight: 600; color: #333; }
.weekly-mode-badge {
  font-size: 11px;
  padding: 4px 10px;
  border-radius: 8px;
  background: rgba(102,126,234,0.15);
  color: #667eea;
  font-weight: 700;
}
.weekly-mode-badge.real {
  background: rgba(252,92,125,0.15);
  color: #fc5c7d;
}

.weekly-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}
.weekly-stat {
  background: #f8f9fa;
  padding: 12px 14px;
  border-radius: 8px;
}
.weekly-stat .stat-label {
  display: block;
  font-size: 11px;
  color: #888;
  font-weight: 600;
  margin-bottom: 4px;
}
.weekly-stat .stat-value {
  display: block;
  font-size: 16px;
  font-weight: 800;
  color: #333;
}
.weekly-stat .stat-value.small { font-size: 12px; line-height: 1.4; }
.weekly-stat .stat-value .win { color: #22c55e; }
.weekly-stat .stat-value .loss { color: #ef4444; }

.weekly-ai-section {
  background: linear-gradient(135deg, rgba(102,126,234,0.05), rgba(118,75,162,0.05));
  border: 1px solid rgba(102,126,234,0.15);
  border-radius: 10px;
  padding: 16px;
}
.weekly-ai-title {
  margin: 0 0 10px 0;
  font-size: 14px;
  font-weight: 700;
  color: #667eea;
}
.weekly-ai-body {
  font-size: 13px;
  color: #444;
  line-height: 1.7;
}
.weekly-ai-body strong { color: #333; }

.weekly-no-ai {
  padding: 16px;
  background: rgba(245,158,11,0.08);
  border: 1px dashed rgba(245,158,11,0.3);
  border-radius: 8px;
  font-size: 12px;
  color: #92400e;
  text-align: center;
}

.weekly-history { margin-top: 24px; }
.weekly-history-title {
  font-size: 14px;
  font-weight: 700;
  margin-bottom: 12px;
  color: #555;
}
.weekly-history-table-wrap { overflow-x: auto; }
.weekly-history-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.weekly-history-table th,
.weekly-history-table td {
  padding: 10px;
  text-align: left;
  border-bottom: 1px solid rgba(0,0,0,0.05);
}
.weekly-history-table th {
  background: #f8f9fa;
  color: #666;
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.4px;
}
.weekly-history-table td.right,
.weekly-history-table th.right { text-align: right; }
.weekly-history-table tr.row-active {
  background: rgba(102,126,234,0.05);
  font-weight: 600;
}

@media (max-width: 600px) {
  .weekly-stats { grid-template-columns: repeat(2, 1fr); }
  .weekly-history-table th,
  .weekly-history-table td { padding: 8px 6px; font-size: 11.5px; }
}

@media (max-width: 480px) {
  .paper-trading-page { padding: 0.6rem; }
  .content-wrapper { padding: 0.6rem; }
  .pf-value { font-size: 1.25rem; }
  .summary-card h3 { font-size: 0.9rem; }
  .summary-card .card-icon { font-size: 1.2rem; }
  .weekly-latest-card { padding: 14px; }
  .weekly-stats { grid-template-columns: 1fr; }
  .weekly-stat .stat-value { font-size: 14px; }
  .btn-generate-report { padding: 7px 12px; font-size: 12px; }
  .tab-btn { padding: 0.6rem 0.75rem; font-size: 13px; }
}

.freshness-bar { display: flex; justify-content: flex-end; margin: -4px 0 12px; }
@media (max-width: 480px) { .freshness-bar { justify-content: center; } }
</style>
