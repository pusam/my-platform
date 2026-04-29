import axios from 'axios';
import { TokenManager, UserManager } from './auth';
import { toast } from './toast';

// Axios 인스턴스 생성
const apiClient = axios.create({
  baseURL: '/api',
  timeout: 30000,  // 30초로 증가 (서버 응답 대기)
  headers: {
    'Content-Type': 'application/json'
  }
});

// 요청 인터셉터 - 모든 요청에 JWT 토큰 추가
apiClient.interceptors.request.use(
  (config) => {
    const token = TokenManager.getToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// 401 리다이렉트 중복 방지 (타이머 기반 자동 해제)
let isRedirecting = false;
let redirectTimer = null;

// 응답 인터셉터 - 401 에러 시 로그아웃 처리
apiClient.interceptors.response.use(
  (response) => {
    return response;
  },
  (error) => {
    if (error.response && error.response.status === 429) {
      toast.warning('요청이 너무 많습니다. 잠시 후 다시 시도해주세요.');
      return Promise.reject(error);
    }
    if (error.response && error.response.status === 401 && !isRedirecting) {
      isRedirecting = true;
      UserManager.logout();
      // 3초 후 플래그 해제 (리다이렉트 실패 대비)
      clearTimeout(redirectTimer);
      redirectTimer = setTimeout(() => { isRedirecting = false; }, 3000);
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default apiClient;

// API 함수들
export const authAPI = {
  // 로그인
  login(username, password) {
    return axios.post('/api/auth/login', {
      username,
      password
    });
  },

  // 회원가입
  signup(signupData) {
    return axios.post('/api/auth/signup', signupData);
  }
};

export const userAPI = {
  // 현재 사용자 정보 조회
  getCurrentUser() {
    return apiClient.get('/me');
  },

  // 테스트 API
  test() {
    return apiClient.get('/test');
  }
};

// Gold Price API
export const goldAPI = {
  // 금 시세 조회
  getPrice() {
    return apiClient.get('/gold/price');
  },
  // 최근 30일 금 시세 조회 (차트용)
  getMonthlyHistory() {
    return apiClient.get('/gold/history/month');
  }
};

// Silver Price API
export const silverAPI = {
  // 은 시세 조회
  getPrice() {
    return apiClient.get('/silver/price');
  },
  // 최근 30일 은 시세 조회 (차트용)
  getMonthlyHistory() {
    return apiClient.get('/silver/history/month');
  }
};

// Board API
export const boardAPI = {
  // 전체 게시글 목록
  getBoards(page = 0, size = 10) {
    return apiClient.get('/board', { params: { page, size } });
  },
  // 내 게시글 목록
  getMyBoards(page = 0, size = 10) {
    return apiClient.get('/board/my', { params: { page, size } });
  },
  // 게시글 상세
  getBoard(id) {
    return apiClient.get(`/board/${id}`);
  },
  // 게시글 삭제
  deleteBoard(id) {
    return apiClient.delete(`/board/${id}`);
  }
};

// User API
export const userSettingsAPI = {
  // 프로필 조회
  getProfile() {
    return apiClient.get('/user/profile');
  },
  // 프로필 수정
  updateProfile(data) {
    return apiClient.put('/user/profile', data);
  },
  // 비밀번호 변경
  changePassword(data) {
    return apiClient.put('/user/password', data);
  },
  // 프로필 이미지 업로드
  uploadProfileImage(file) {
    const formData = new FormData();
    formData.append('file', file);
    return apiClient.post('/user/profile/image', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
  },
  // 프로필 이미지 삭제
  deleteProfileImage() {
    return apiClient.delete('/user/profile/image');
  },
  // 승인 대기 사용자 목록 (관리자용)
  getPendingUsers() {
    return apiClient.get('/user/pending');
  },
  // 사용자 승인/거부 (관리자용)
  approveUser(userId, status) {
    return apiClient.put(`/user/${userId}/approval`, { status });
  }
};

// Asset API
export const assetAPI = {
  // 자산 등록
  addAsset(data) {
    return apiClient.post('/asset', data);
  },
  // 내 자산 목록
  getMyAssets() {
    return apiClient.get('/asset');
  },
  // 자산 요약 (손익 포함)
  getAssetSummary() {
    return apiClient.get('/asset/summary');
  },
  // 자산 삭제
  deleteAsset(assetId) {
    return apiClient.delete(`/asset/${assetId}`);
  }
};

// File Management API
export const fileAPI = {
  // 폴더 내용 조회
  getFolderContent(folderId = null) {
    return apiClient.get('/files/folders', { params: { folderId } });
  },
  // 폴더 생성
  createFolder(parentId, name) {
    return apiClient.post('/files/folders', null, { params: { parentId, name } });
  },
  // 파일 업로드
  uploadFile(folderId, file, uploadDate) {
    const formData = new FormData();
    formData.append('file', file);
    if (folderId) formData.append('folderId', folderId);
    if (uploadDate) formData.append('uploadDate', uploadDate);

    return apiClient.post('/files/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 300000  // 5분 (대용량 파일 업로드 대기)
    });
  },
  // 폴더 삭제
  deleteFolder(folderId) {
    return apiClient.delete(`/files/folders/${folderId}`);
  },
  // 파일 삭제
  deleteFile(fileId) {
    return apiClient.delete(`/files/${fileId}`);
  },
  // 파일 다운로드 URL
  getDownloadUrl(fileId) {
    return `/api/files/download/${fileId}`;
  }
};

// Finance API
export const financeAPI = {
  // 월별 거래 내역 조회
  getMonthlyTransactions(year, month) {
    return apiClient.get(`/finance/transactions?year=${year}&month=${month}`);
  },
  // 전체 거래 내역 조회
  getAllTransactions() {
    return apiClient.get('/finance/transactions/all');
  },
  // 거래 등록
  addTransaction(data) {
    return apiClient.post('/finance/transactions', data);
  },
  // 거래 삭제
  deleteTransaction(id) {
    return apiClient.delete(`/finance/transactions/${id}`);
  },
  // 고정 수입/지출 목록 조회
  getRecurring(type = null) {
    const params = type ? { type } : {};
    return apiClient.get('/finance/recurring', { params });
  },
  // 고정 수입/지출 등록
  addRecurring(data) {
    return apiClient.post('/finance/recurring', data);
  },
  // 고정 수입/지출 수정 (금액 변경 시 히스토리 기록)
  updateRecurring(id, data) {
    return apiClient.put(`/finance/recurring/${id}`, data);
  },
  // 고정 수입/지출 비활성화
  deactivateRecurring(id) {
    return apiClient.put(`/finance/recurring/${id}/deactivate`);
  },
  // 고정 수입/지출 삭제
  deleteRecurring(id) {
    return apiClient.delete(`/finance/recurring/${id}`);
  }
};

// Stock Price API
export const stockAPI = {
  // 종목 검색
  searchStocks(keyword) {
    return apiClient.get('/stock/search', { params: { keyword } });
  },
  // 종목 시세 조회
  getStockPrice(stockCode) {
    return apiClient.get(`/stock/${stockCode}`);
  }
};

// Sector Trading API
export const sectorAPI = {
  // 섹터별 거래대금 조회 (period: TODAY, MIN_5, MIN_30)
  // 타임아웃 120초 - 종목 시세 조회가 오래 걸릴 수 있음
  getSectorTrading(period = 'TODAY') {
    return apiClient.get('/sector/trading', { params: { period }, timeout: 120000 });
  },
  // 특정 섹터 상세 조회
  getSectorDetail(sectorCode) {
    return apiClient.get(`/sector/trading/${sectorCode}`, { timeout: 120000 });
  },
  // 캐시 새로고침
  refreshSectorTrading() {
    return apiClient.post('/sector/trading/refresh', {}, { timeout: 120000 });
  },
  // 섹터 로테이션 (전일 대비 자금 흐름)
  getSectorRotation() {
    return apiClient.get('/sector/trading/rotation');
  },
  // 섹터 기회 발굴 — 주도 섹터 × 유망 종목 TOP N
  // 서버가 Redis/메모리 캐시에서 조합만 함 (KIS 재호출 X)
  getSectorOpportunities(topSectors = 4, picksPerSector = 3) {
    return apiClient.get('/sector/opportunities', {
      params: { topSectors, picksPerSector }
    });
  }
};

// Car Record API
export const carAPI = {
  // 정비 기록 목록 조회
  getRecords(type = null) {
    const params = type ? { type } : {};
    return apiClient.get('/car/records', { params });
  },
  // 정비 기록 등록
  addRecord(data) {
    return apiClient.post('/car/records', data);
  },
  // 정비 기록 삭제
  deleteRecord(id) {
    return apiClient.delete(`/car/records/${id}`);
  },
  // 정비 요약 정보
  getSummary() {
    return apiClient.get('/car/summary');
  }
};

// Diet API (식단 관리)
export const dietAPI = {
  getRecords(type = null) {
    const params = type ? { type } : {};
    return apiClient.get('/diet/records', { params });
  },
  addRecord(data) {
    return apiClient.post('/diet/records', data);
  },
  updateRecord(id, data) {
    return apiClient.put(`/diet/records/${id}`, data);
  },
  deleteRecord(id) {
    return apiClient.delete(`/diet/records/${id}`);
  },
  getSummary() {
    return apiClient.get('/diet/summary');
  }
};

// Exercise API (운동 관리)
export const exerciseAPI = {
  getRecords(type = null) {
    const params = type ? { type } : {};
    return apiClient.get('/exercise/records', { params });
  },
  addRecord(data) {
    return apiClient.post('/exercise/records', data);
  },
  updateRecord(id, data) {
    return apiClient.put(`/exercise/records/${id}`, data);
  },
  deleteRecord(id) {
    return apiClient.delete(`/exercise/records/${id}`);
  },
  getSummary() {
    return apiClient.get('/exercise/summary');
  }
};

// News Summary API
export const newsAPI = {
  // 오늘의 뉴스 조회
  getTodayNews() {
    return apiClient.get('/news/today');
  },
  // 최근 뉴스 조회
  getRecentNews() {
    return apiClient.get('/news/recent');
  },
  // 뉴스 수동 수집 (관리자용)
  fetchNews() {
    return apiClient.post('/news/fetch', {}, { timeout: 120000 });
  },
  // 최신 뉴스 폴링 (minutes 이내 수집된 뉴스, urgent 플래그 포함)
  pollNews(minutes = 15) {
    return apiClient.get('/news/poll', { params: { minutes } });
  }
};

// Admin API
export const adminAPI = {
  // 서버 상태 조회
  getServerStatus() {
    return apiClient.get('/admin/server/status');
  },

  // 사용자 관리
  getAllUsers() {
    return apiClient.get('/admin/users');
  },
  getUser(id) {
    return apiClient.get(`/admin/users/${id}`);
  },
  updateUserRole(id, role) {
    return apiClient.put(`/admin/users/${id}/role`, { role });
  },
  updateUserStatus(id, status) {
    return apiClient.put(`/admin/users/${id}/status`, { status });
  },
  deleteUser(id) {
    return apiClient.delete(`/admin/users/${id}`);
  },
  unlockUser(id) {
    return apiClient.post(`/admin/users/${id}/unlock`);
  },
  getUserStats() {
    return apiClient.get('/admin/users/stats');
  },

  // 활동 로그
  getLogs(page = 0, size = 20, username = null, actionType = null) {
    const params = { page, size };
    if (username) params.username = username;
    if (actionType) params.actionType = actionType;
    return apiClient.get('/admin/logs', { params });
  },
  getRecentLogs() {
    return apiClient.get('/admin/logs/recent');
  }
};

// Notification API
export const notificationAPI = {
  // 알림 목록 조회
  getNotifications(page = 0, size = 20) {
    return apiClient.get('/notifications', { params: { page, size } });
  },
  // 읽지 않은 알림 수 조회
  getUnreadCount() {
    return apiClient.get('/notifications/unread-count');
  },
  // 알림 읽음 처리
  markAsRead(notificationId) {
    return apiClient.put(`/notifications/${notificationId}/read`);
  },
  // 모든 알림 읽음 처리
  markAllAsRead() {
    return apiClient.put('/notifications/read-all');
  }
};

// Export API
export const exportAPI = {
  // 자산 Excel 내보내기
  exportAssetsExcel() {
    return apiClient.get('/export/assets?format=xlsx', { responseType: 'blob' });
  },
  // 자산 CSV 내보내기
  exportAssetsCsv() {
    return apiClient.get('/export/assets?format=csv', { responseType: 'blob' });
  },
  // 가계부 Excel 내보내기
  exportFinanceExcel(year = null, month = null) {
    let url = '/export/finance?format=xlsx';
    if (year && month) {
      url += `&year=${year}&month=${month}`;
    }
    return apiClient.get(url, { responseType: 'blob' });
  },
  // 가계부 CSV 내보내기
  exportFinanceCsv(year = null, month = null) {
    let url = '/export/finance?format=csv';
    if (year && month) {
      url += `&year=${year}&month=${month}`;
    }
    return apiClient.get(url, { responseType: 'blob' });
  }
};

// Investor Daily Trade API (투자자별 일별 상위 종목)
export const investorTradeAPI = {
  // 데이터 수집 (당일)
  collect() {
    return apiClient.post('/investor-trades/collect');
  },
  // 데이터 수집 (특정일)
  collectByDate(date) {
    return apiClient.post(`/investor-trades/collect/${date}`);
  },
  // 일자별 전체 조회
  getByDate(date) {
    return apiClient.get('/investor-trades', { params: { date } });
  },
  // 시장별 조회
  getByMarket(marketType, date) {
    return apiClient.get(`/investor-trades/market/${marketType}`, { params: { date } });
  },
  // 투자자별 조회
  getByInvestor(investorType, date) {
    return apiClient.get(`/investor-trades/investor/${investorType}`, { params: { date } });
  },
  // 시장 + 투자자별 조회
  getByMarketAndInvestor(marketType, investorType, date) {
    return apiClient.get(`/investor-trades/market/${marketType}/investor/${investorType}`, { params: { date } });
  },
  // 연기금 코스피 조회
  getPensionKospi(date) {
    return apiClient.get('/investor-trades/pension/kospi', { params: { date } });
  },
  // 연기금 코스닥 조회
  getPensionKosdaq(date) {
    return apiClient.get('/investor-trades/pension/kosdaq', { params: { date } });
  },
  // 기간별 투자자 조회
  getByInvestorRange(investorType, startDate, endDate) {
    return apiClient.get(`/investor-trades/investor/${investorType}/range`, {
      params: { startDate, endDate }
    });
  },
  // 기간별 종목 조회
  getByStockRange(stockCode, startDate, endDate) {
    return apiClient.get(`/investor-trades/stock/${stockCode}/range`, {
      params: { startDate, endDate }
    });
  },
  // 누적 통계 조회
  getStats(investorType, marketType, startDate, endDate) {
    return apiClient.get(`/investor-trades/stats/${investorType}/${marketType}`, {
      params: { startDate, endDate }
    });
  },
  // 데이터 있는 날짜 목록
  getAvailableDates(marketType, investorType) {
    return apiClient.get(`/investor-trades/dates/${marketType}/${investorType}`);
  },
  // 데이터 재수집
  recollect(marketType, investorType, date) {
    return apiClient.post(`/investor-trades/recollect/${marketType}/${investorType}/${date}`);
  }
};

// 시장 지표 API
export const marketAPI = {
  // 현재 시장 상태 조회
  getStatus() {
    return apiClient.get('/market/status');
  },
  // 간단 시장 상태 (위젯용)
  getSimpleStatus() {
    return apiClient.get('/market/status/simple');
  },
  // ADR 히스토리 조회
  getAdrHistory(days = 60) {
    return apiClient.get('/market/adr/history', { params: { days } });
  },
  // AI 시장 예측 (향후 5일)
  getForecast() {
    return apiClient.get('/market/forecast', { timeout: 60000 });
  },
  // 시장 데이터 수집
  collectData() {
    return apiClient.post('/market/collect');
  },
  // 기간별 시장 데이터 수집 (Backfill) - 최대 3분 타임아웃
  collectDataForPeriod(startDate, endDate) {
    return apiClient.post('/market/collect/period', null, {
      params: { startDate, endDate },
      timeout: 180000  // 3분 (60일 데이터 수집에 약 1~2분 소요)
    });
  },
  // 급등주 (당일 등락률 상위 TOP 50)
  getPriceRise() {
    return apiClient.get('/market/price-rise');
  },
  // 급락주 (당일 등락률 하위 TOP 50)
  getPriceFall() {
    return apiClient.get('/market/price-fall');
  }
};

// 텔레그램 알림 API
export const telegramAPI = {
  // 텔레그램 상태 확인
  getStatus() {
    return apiClient.get('/telegram/status');
  },
  // 테스트 메시지 발송
  sendTest() {
    return apiClient.post('/telegram/test');
  },
  // 주식 알림 테스트
  sendStockAlertTest() {
    return apiClient.post('/telegram/test-stock-alert');
  },
  // 숏스퀴즈 알림 테스트
  sendSqueezeAlertTest() {
    return apiClient.post('/telegram/test-squeeze-alert');
  },
  // 채널별 테스트
  testChannel(channel) {
    return apiClient.post('/telegram/test-channel', null, { params: { channel } });
  },
  // 커스텀 메시지 발송
  sendMessage(message) {
    return apiClient.post('/telegram/send', null, { params: { message } });
  }
};

// Paper Trading (모의투자) API
export const paperTradingAPI = {
  // ===== 주간 매매 리포트 (admin) =====
  getLatestWeeklyReport(mode = 'REAL') {
    return apiClient.get('/admin/trading/diary/latest', { params: { mode } });
  },
  getRecentWeeklyReports(mode = 'REAL') {
    return apiClient.get('/admin/trading/diary/recent', { params: { mode } });
  },
  generateWeeklyReport(mode = 'REAL') {
    return apiClient.post('/admin/trading/diary/generate', null, {
      params: { mode },
      timeout: 60000  // AI 생성 시간 고려
    });
  },

  // 계좌 요약 조회
  getAccountSummary() {
    return apiClient.get('/paper-trading/account/summary');
  },
  // 포트폴리오 조회
  getPortfolio() {
    return apiClient.get('/paper-trading/portfolio');
  },
  // 거래 내역 조회 (페이징)
  getTradeHistory(page = 0, size = 20) {
    return apiClient.get('/paper-trading/trades', { params: { page, size } });
  },
  // 거래 통계 조회
  getStatistics() {
    return apiClient.get('/paper-trading/statistics');
  },
  // 수동 매수/매도
  placeTrade(data) {
    return apiClient.post('/paper-trading/trades', data);
  },
  // 계좌 초기화 (초기 자본금 지정 가능)
  initializeAccount(initialBalance = null) {
    return apiClient.post('/paper-trading/account/initialize', { initialBalance });
  },
  // 봇 상태 조회
  getBotStatus() {
    return apiClient.get('/paper-trading/bot/status');
  },
  // 봇 시작 (모드 선택: VIRTUAL 또는 REAL)
  startBot(mode = 'VIRTUAL') {
    return apiClient.post(`/paper-trading/bot/start?mode=${mode}`);
  },
  // 봇 중지
  stopBot() {
    return apiClient.post('/paper-trading/bot/stop');
  },
  // 포트폴리오 현재가 업데이트
  refreshPortfolio() {
    return apiClient.post('/paper-trading/portfolio/refresh');
  },
  // 봇 모드 조회
  getBotMode() {
    return apiClient.get('/paper-trading/bot/mode');
  },
  // 실전투자 계좌 요약 조회
  getRealAccountSummary() {
    return apiClient.get('/paper-trading/real/account/summary');
  },
  // 실전투자 포트폴리오 조회
  getRealPortfolio() {
    return apiClient.get('/paper-trading/real/portfolio');
  },
  // 실전투자 수동 매수/매도
  placeRealTrade(data) {
    return apiClient.post('/paper-trading/real/trades', data);
  },
  // 봇 성과 분석 조회 (mode: REAL | VIRTUAL)
  getBotPerformance(days = 30, mode = 'VIRTUAL') {
    return apiClient.get('/paper-trading/bot-performance', { params: { days, mode } });
  }
};

// Scalping Analysis API (단타 분석)
export const scalpingAPI = {
  // 단타 분석 조회 (체결강도, 프로그램매매, 투자자 매매)
  getAnalysis(stockCode) {
    return apiClient.get(`/scalping/${stockCode}`);
  },
  // 체결강도만 빠르게 갱신 (자동 갱신용)
  refreshVolumePower(stockCode) {
    return apiClient.get(`/scalping/${stockCode}/refresh`);
  }
};

// Exchange Rate API (환율)
export const exchangeRateAPI = {
  // 현재 환율 조회 (USD/KRW)
  getCurrentRate() {
    return apiClient.get('/exchange-rate');
  }
};

// Trading Indicator API (트레이딩 지표)
export const tradingIndicatorAPI = {
  // VWAP 조회
  getVwap(stockCode) {
    return apiClient.get(`/trading-indicators/vwap/${stockCode}`);
  },
  // 나스닥 선물 조회
  getNasdaqFutures() {
    return apiClient.get('/trading-indicators/global/nasdaq-futures');
  },
  // S&P 500 선물 조회
  getSP500Futures() {
    return apiClient.get('/trading-indicators/global/sp500-futures');
  },
  // 글로벌 악재 필터 체크
  checkGlobalHalt() {
    return apiClient.get('/trading-indicators/global/halt-check');
  },
  // 주도 섹터 랭킹
  getLeadingSectors() {
    return apiClient.get('/trading-indicators/sectors/leading');
  },
  // 섹터 상세
  getSectorDetail(sectorCode) {
    return apiClient.get(`/trading-indicators/sectors/${sectorCode}`);
  },
  // RSI 다이버전스 탐지 (가격 데이터 직접 전달)
  detectDivergence(prices, lookbackPeriod = 40) {
    return apiClient.post('/trading-indicators/divergence/detect', {
      prices,
      lookbackPeriod
    });
  },
  // 종목코드로 RSI 다이버전스 탐지
  detectDivergenceByStock(stockCode, lookbackPeriod = 40) {
    return apiClient.get(`/trading-indicators/divergence/${stockCode}?lookbackPeriod=${lookbackPeriod}`);
  },
  // 종합 분석
  getComprehensive(stockCode) {
    return apiClient.get(`/trading-indicators/comprehensive/${stockCode}`);
  }
};

// AI Analysis API (AI 주식 분석)
export const aiAnalysisAPI = {
  // AI 분석 결과 조회
  getAnalysis() {
    return apiClient.get('/ai-analysis');
  },
  // AI 분석 새로고침
  refresh() {
    return apiClient.post('/ai-analysis/refresh');
  }
};

// Quant Screener API (퀀트 스크리너)
export const screenerAPI = {
  // 마법의 공식 스크리너
  getMagicFormula(limit = 30, minMarketCap = null) {
    const params = { limit };
    if (minMarketCap) params.minMarketCap = minMarketCap;
    return apiClient.get('/screener/magic-formula', { params });
  },
  // PEG 스크리너 (저평가 성장주)
  getLowPegStocks(limit = 30, maxPeg = null, minEpsGrowth = null) {
    const params = { limit };
    if (maxPeg) params.maxPeg = maxPeg;
    if (minEpsGrowth) params.minEpsGrowth = minEpsGrowth;
    return apiClient.get('/screener/peg', { params });
  },
  // 턴어라운드 스크리너 (흑자전환 종목)
  getTurnaroundStocks(limit = 30) {
    return apiClient.get('/screener/turnaround', { params: { limit } });
  },
  // 스크리너 요약
  getSummary() {
    return apiClient.get('/screener/summary');
  }
};

// TA(기술적 분석) 퀀트 API — DB 캐시만, AI/외부 호출 없음
export const quantTaAPI = {
  // 조건 조합 종목 필터링
  // filter: { rsiBelow, rsiAbove, goldenCross, arrangedUp, aboveMa20, belowMa20,
  //           volumeRatioMin, bollingerLowerTouch, bollingerSqueeze, changeRateMin, changeRateMax }
  screen(filter, limit = 50) {
    return apiClient.post('/quant-ta/screen', { filter, limit });
  },
  // 종목 리스트 상관관계 매트릭스
  correlation(stockCodes, days = 60) {
    return apiClient.post('/quant-ta/correlation', { stockCodes, days });
  },
  // universe 현황 (스크리너 가용 종목 수)
  universeStatus() {
    return apiClient.get('/quant-ta/universe-status');
  },
  // 거래량 상위 N개 일봉 일괄 수집 (admin)
  collectHistory(topN = 200) {
    return apiClient.post('/quant-ta/collect-history', { topN });
  },
  // 수집 진행률
  collectHistoryProgress() {
    return apiClient.get('/quant-ta/collect-history/progress');
  },
  // 종목 코드 → 종목명 매핑
  resolveNames(stockCodes) {
    return apiClient.post('/quant-ta/resolve-names', { stockCodes });
  },
  // history의 빈 종목명 일괄 보정 (admin)
  backfillNames() {
    return apiClient.post('/quant-ta/backfill-names');
  }
};

// 공매도 잔고 API
export const shortSellingAPI = {
  // 공매도 비율 상위 종목 (기본 20개)
  getTop(limit = 20) {
    return apiClient.get('/short-selling/top', { params: { limit } });
  },
  // 특정 종목 공매도 이력
  getStockHistory(stockCode) {
    return apiClient.get(`/short-selling/${stockCode}`);
  },
  // 수동 수집 (admin)
  collect() {
    return apiClient.post('/short-selling/collect', null, { timeout: 120000 });
  }
};

// AI Strategy Snapshot API (스냅샷 기반 - DB 조회만)
export const aiStrategyAPI = {
  // 모든 전략의 최신 스냅샷 조회
  getLatest() {
    return apiClient.get('/ai-strategy/latest');
  },
  // 특정 전략의 최신 스냅샷 조회
  getLatestByType(strategyType) {
    return apiClient.get(`/ai-strategy/latest/${strategyType}`);
  },
  // 스냅샷 통계 조회 (관리용)
  getStats() {
    return apiClient.get('/ai-strategy/stats');
  },
  // 수동 스냅샷 수집 (테스트/관리용)
  collectSnapshots() {
    return apiClient.post('/ai-strategy/collect');
  },
  // AI 전략 성과 분석 (백테스트)
  getPerformance(days = 30) {
    return apiClient.get('/ai-strategy/performance', { params: { days }, timeout: 60000 });
  }
};

// Investor Trade API (투자자 매매)
export const investorAPI = {
  // 투자자별 상위 매수/매도 종목
  getTopTrades(investorType, tradeType, limit = 50) {
    return apiClient.get('/investor/top-trades', {
      params: { investorType, tradeType, limit }
    });
  },
  // 실시간 상위 매매 (장중: KIS API 직접, 장외: DB)
  getTopTradesRealtime(investorType, limit = 10) {
    return apiClient.get('/investor/top-trades/realtime', {
      params: { investorType, limit }
    });
  },
  // 전체 투자자 상위 매매 (외국인+기관 통합)
  getAllTopTrades(tradeType = 'BUY', limit = 50) {
    return apiClient.get('/investor/all-top-trades', {
      params: { tradeType, limit }
    });
  },
  // 데이터 수집 (당일)
  collect() {
    return apiClient.post('/investor/collect');
  },
  // 연속 매수 종목 전체 조회
  getAllConsecutiveBuy(minDays = 3) {
    return apiClient.get('/investor/consecutive-buy/all', { params: { minDays } });
  },
  // 수급 급증 종목 전체 조회
  getAllSurgeStocks(minChange = null) {
    const params = {};
    if (minChange) params.minChange = minChange;
    return apiClient.get('/investor/surge/all', { params });
  },
  // 수급 급증 스냅샷 수집
  collectSurge() {
    return apiClient.post('/investor/surge/collect');
  },
  // 외국인+기관 공통 순매수 종목
  getCommonSurgeStocks(minChange = null) {
    const params = {};
    if (minChange) params.minChange = minChange;
    return apiClient.get('/investor/surge/common', { params });
  },
  // 멀티 컨빅션 시그널
  getConvictionSignals() {
    return apiClient.get('/investor/conviction');
  }
};

// Risk Analysis API (리스크 분석)
export const riskAPI = {
  // 종합 리스크 분석 (최대 90초 - DART/뉴스/AI 분석 포함)
  checkRisk(stockName) {
    return apiClient.get('/risk/check', { params: { stockName }, timeout: 90000 });
  },
  // 빠른 위험 체크 (공시만)
  quickCheck(stockName) {
    return apiClient.get('/risk/quick', { params: { stockName } });
  },
  // 매수 가능 여부 확인
  isSafeToBuy(stockName) {
    return apiClient.get('/risk/safe', { params: { stockName } });
  },
  // 여러 종목 일괄 체크
  batchCheck(stockNames) {
    return apiClient.post('/risk/batch', { stockNames });
  }
};

// Stock Detail API (종목 종합 상세)
export const stockDetailAPI = {
  // 종목 종합 상세 조회 (수급/재무/리스크/AI 분석 통합) — 레거시 (전체 한방 호출)
  getSummary(stockCode) {
    return apiClient.get(`/stock/${stockCode}/summary`, { timeout: 90000 });
  },
  // 1단계: 빠른 데이터 (시세/수급/차트/재무) — 3~5초
  getQuick(stockCode) {
    return apiClient.get(`/stock/${stockCode}/quick`, { timeout: 30000 });
  },
  // 2단계: 무거운 데이터 (리스크/AI/피어) — 캐시히트 1초, 미스 10~30초
  getHeavy(stockCode) {
    return apiClient.get(`/stock/${stockCode}/heavy`, { timeout: 60000 });
  },
  // 종목 펀더멘털 진단 (재무/수급/기술적 분석)
  getDiagnosis(stockCode) {
    return apiClient.get(`/analysis/diagnosis/${stockCode}`, { timeout: 60000 });
  },
  // 배치 점수 조회 (스크리너 듀얼 점수용)
  batchScores(stockCodes) {
    return apiClient.post('/analysis/batch-scores', { stockCodes }, { timeout: 90000 });
  }
};

// 배치 잡 모니터링 API
export const batchJobAPI = {
  getExecutions(params = {}) {
    return apiClient.get('/admin/batch-jobs', { params });
  },
  getSummary() {
    return apiClient.get('/admin/batch-jobs/summary');
  },
  getJobNames() {
    return apiClient.get('/admin/batch-jobs/names');
  }
};

// 관심종목 API
export const watchlistAPI = {
  getList() {
    return apiClient.get('/watchlist');
  },
  add(stockCode, stockName) {
    return apiClient.post('/watchlist', { stockCode, stockName });
  },
  setAlert(id, targetPrice, alertCondition) {
    return apiClient.put(`/watchlist/${id}/alert`, { targetPrice, alertCondition });
  },
  delete(id) {
    return apiClient.delete(`/watchlist/${id}`);
  },
  checkBookmark(stockCode) {
    return apiClient.get('/watchlist/check', { params: { stockCode } });
  },
  getRiskStatus(stockCodes) {
    return apiClient.post('/watchlist/risk-status', stockCodes);
  }
};

// Oil Price API (원유 시세)
export const oilAPI = {
  getPrice() {
    return apiClient.get('/oil/price');
  },
  getMonthlyHistory() {
    return apiClient.get('/oil/history/month');
  }
};

// 글로벌 선물 API (해외선물/야간선물)
export const globalFuturesAPI = {
  getAllQuotes() {
    return apiClient.get('/global-futures/quotes', { timeout: 30000 });
  },
  getQuote(symbol) {
    return apiClient.get(`/global-futures/quotes/${symbol}`);
  },
  getKospiImpact() {
    return apiClient.get('/global-futures/kospi-impact', { timeout: 30000 });
  }
};

// 실적공시 API
// Preemptive Radar API (선점 레이더)
// AI 종합 추천 API
export const recommendationAPI = {
  getTop5() {
    return apiClient.get('/recommendation/top5', { timeout: 30000 });
  }
};

export const radarAPI = {
  getFull() {
    return apiClient.get('/radar', { timeout: 60000 });
  },
  getPolicyNews() {
    return apiClient.get('/radar/policy-news');
  },
  getNearHigh() {
    return apiClient.get('/radar/near-high', { timeout: 30000 });
  },
  getLargeHoldings() {
    return apiClient.get('/radar/large-holdings');
  },
  getEarningsPredictions() {
    return apiClient.get('/radar/earnings-predictions');
  }
};

export const earningsAPI = {
  getRecent(months = 3) {
    return apiClient.get('/earnings/recent', { params: { months } });
  },
  getCalendar(year, month) {
    return apiClient.get('/earnings/calendar', { params: { year, month } });
  },
  getWatchlist() {
    return apiClient.get('/earnings/watchlist');
  },
  search(q) {
    return apiClient.get('/earnings/search', { params: { q } });
  },
  getStats(months = 3) {
    return apiClient.get('/earnings/stats', { params: { months } });
  },
  collect() {
    return apiClient.post('/earnings/collect');
  },
  getSummary(corpName, corpCode, reportType) {
    return apiClient.get('/earnings/summary', {
      params: { corpName, corpCode, reportType },
      timeout: 60000
    });
  }
};

// 간편 사용을 위한 export
export const signup = (signupData) => authAPI.signup(signupData);
export const getPendingUsers = () => userSettingsAPI.getPendingUsers();
export const approveUser = (userId, status) => userSettingsAPI.approveUser(userId, status);

