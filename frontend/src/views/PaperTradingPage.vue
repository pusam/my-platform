<template>
  <div class="paper-trading-page">
    <LoadingSpinner v-if="loading" />
    <div v-else class="content-wrapper">
      <!-- 헤더 섹션 -->
      <div class="page-header">
        <button @click="goBack" class="back-button">← 돌아가기</button>
        <h1>자동매매</h1>
        <p class="subtitle">모의투자와 실전투자를 관리하세요</p>
      </div>

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
                  {{ botStatus.active && botStatus.tradingMode === 'VIRTUAL' ? '실행 중' : '중지됨' }}
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
            <h3>실제 계좌</h3>
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
                  {{ botStatus.active && botStatus.tradingMode === 'REAL' ? '실행 중' : '중지됨' }}
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

      <!-- 수동 거래 모달 -->
      <div v-if="showTradeModal" class="modal-overlay" @click.self="showTradeModal = false">
        <div class="modal" :class="{ 'real-modal': tradeMode === 'real' }">
          <h3>{{ tradeMode === 'real' ? '🔴 실전 수동 거래' : '수동 거래' }}</h3>
          <div v-if="tradeMode === 'real'" class="modal-warning">
            실제 계좌에서 주문이 체결됩니다!
          </div>
          <div class="form-group">
            <label>종목코드</label>
            <input v-model="tradeForm.stockCode" placeholder="예: 005930" maxlength="6" />
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
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { paperTradingAPI } from '../utils/api';
import LoadingSpinner from '../components/LoadingSpinner.vue';

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

// 자동 새로고침
let refreshTimer = null;

// 총 자산 계산
const totalAsset = computed(() => {
  return (account.value.currentBalance || 0) + (account.value.totalEvaluation || 0);
});

// 실전투자 탭 전환
const switchToRealTab = () => {
  activeTab.value = 'real';
  loadRealData();
};

// 모의투자 데이터 로드
const loadData = async () => {
  try {
    const [accountRes, portfolioRes, tradesRes, botRes] = await Promise.all([
      paperTradingAPI.getAccountSummary(),
      paperTradingAPI.getPortfolio(),
      paperTradingAPI.getTradeHistory(0, pageSize),
      paperTradingAPI.getBotStatus()
    ]);

    if (accountRes.data.success) {
      account.value = accountRes.data.data;
    }
    if (portfolioRes.data.success) {
      portfolio.value = portfolioRes.data.data;
    }
    if (tradesRes.data.success) {
      trades.value = tradesRes.data.data;
      totalPages.value = tradesRes.data.totalPages;
    }
    if (botRes.data.success) {
      botStatus.value = botRes.data.data;
    }
  } catch (error) {
    console.error('데이터 로드 오류:', error);
  } finally {
    loading.value = false;
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
      realAccount.value = accountRes.data.data;
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
    alert('새로고침에 실패했습니다.');
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
      realAccount.value = accountRes.data.data;
    }
    if (portfolioRes.data.success) {
      realPortfolio.value = portfolioRes.data.data;
    }
  } catch (error) {
    console.error('실전 포트폴리오 새로고침 오류:', error);
    alert('새로고침에 실패했습니다.');
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
      alert(`자동매매 봇이 ${modeName} 모드로 시작되었습니다.`);
    }
  } catch (error) {
    console.error('봇 시작 오류:', error);
    alert('봇 시작에 실패했습니다: ' + (error.response?.data?.error || error.message));
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
      alert('자동매매 봇이 중지되었습니다.');
    }
  } catch (error) {
    console.error('봇 중지 오류:', error);
    alert('봇 중지에 실패했습니다.');
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

// 수동 거래 실행
const executeTrade = async () => {
  if (!tradeForm.value.stockCode || !tradeForm.value.quantity || !tradeForm.value.price) {
    alert('모든 필드를 입력해주세요.');
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
      alert(res.data.message);
      showTradeModal.value = false;
      tradeForm.value = { stockCode: '', quantity: 1, price: 0, tradeType: 'BUY' };
      if (tradeMode.value === 'real') {
        await loadRealData();
      } else {
        await loadData();
      }
    } else {
      alert(res.data.error || '거래 실패');
    }
  } catch (error) {
    console.error('거래 실행 오류:', error);
    alert(error.response?.data?.error || '거래 실행에 실패했습니다.');
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
    alert('수량과 가격을 입력해주세요.');
    return;
  }

  if (sellForm.value.quantity > sellForm.value.maxQuantity) {
    alert('보유 수량을 초과할 수 없습니다.');
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
      alert(res.data.message);
      showSellModal.value = false;
      if (sellMode.value === 'real') {
        await loadRealData();
      } else {
        await loadData();
      }
    } else {
      alert(res.data.error || '매도 실패');
    }
  } catch (error) {
    console.error('매도 실행 오류:', error);
    alert(error.response?.data?.error || '매도 실행에 실패했습니다.');
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
      alert(res.data.message);
      showInitializeConfirm.value = false;
      initForm.value.initialBalance = 10000000; // 기본값으로 리셋
      await loadData();
    }
  } catch (error) {
    console.error('계좌 초기화 오류:', error);
    alert('계좌 초기화에 실패했습니다.');
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
    default: return '';
  }
};

const goBack = () => {
  router.back();
};

onMounted(() => {
  loadData();
  // URL에서 실전투자 탭으로 진입한 경우 실전 데이터도 로드
  if (activeTab.value === 'real') {
    loadRealData();
  }
  // 30초마다 자동 새로고침
  refreshTimer = setInterval(() => {
    loadData();
    if (activeTab.value === 'real') {
      loadRealData();
    }
  }, 30000);
});

onUnmounted(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer);
  }
});
</script>

<style scoped>
.paper-trading-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
  padding: 2rem;
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

.page-header {
  text-align: center;
  margin-bottom: 1.5rem;
  position: relative;
}

.back-button {
  position: absolute;
  left: 0;
  top: 0;
  background: #4a4a8a;
  color: white;
  border: none;
  padding: 0.75rem 1.5rem;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1rem;
  transition: all 0.3s;
}

.back-button:hover {
  background: #5a5a9a;
  transform: translateX(-5px);
}

.page-header h1 {
  color: #fff;
  margin-bottom: 0.5rem;
  font-size: 2rem;
}

.subtitle {
  color: #888;
  font-size: 1.1rem;
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
  border: 2px solid #2a2a4a;
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
.negative { color: #3182ce !important; }
.running { color: #48bb78 !important; }
.stopped { color: #888 !important; }
.error { color: #ed8936 !important; }

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
  border: 2px solid #2a2a4a;
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
  border: 2px solid #3a3a5a;
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

/* 반응형 */
@media (max-width: 768px) {
  .paper-trading-page {
    padding: 1rem;
  }

  .content-wrapper {
    padding: 1rem;
  }

  .page-header h1 {
    margin-top: 3rem;
    font-size: 1.5rem;
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

  .portfolio-table th:nth-child(4),
  .portfolio-table th:nth-child(5),
  .portfolio-table td:nth-child(4),
  .portfolio-table td:nth-child(5) {
    display: none;
  }
}
</style>
