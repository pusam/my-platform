import axios from 'axios';
import { TokenManager, UserManager } from './auth';

// Axios 인스턴스 생성
const apiClient = axios.create({
  baseURL: '/api',
  timeout: 10000,
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

// 401 리다이렉트 중복 방지 플래그
let isRedirecting = false;

// 응답 인터셉터 - 401 에러 시 로그아웃 처리
apiClient.interceptors.response.use(
  (response) => {
    return response;
  },
  (error) => {
    if (error.response && error.response.status === 401 && !isRedirecting) {
      // 토큰이 만료되었거나 유효하지 않은 경우
      isRedirecting = true;
      UserManager.logout();
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
      headers: { 'Content-Type': 'multipart/form-data' }
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
  getSectorTrading(period = 'TODAY') {
    return apiClient.get('/sector/trading', { params: { period } });
  },
  // 특정 섹터 상세 조회
  getSectorDetail(sectorCode) {
    return apiClient.get(`/sector/trading/${sectorCode}`);
  },
  // 캐시 새로고침
  refreshSectorTrading() {
    return apiClient.post('/sector/trading/refresh');
  }
};

// AI Chat API
export const aiAPI = {
  // AI 채팅 (타임아웃 60초로 설정 - AI 응답이 느릴 수 있음)
  chat(message, useContext = false) {
    return apiClient.post('/ai/chat', { message, useContext }, { timeout: 60000 });
  },
  // AI 서버 상태 확인
  checkStatus() {
    return apiClient.get('/ai/status');
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

// Reddit API
export const redditAPI = {
  // API 상태 확인
  getStatus() {
    return apiClient.get('/reddit/status');
  },
  // 주식 관련 인기 게시물
  getStockPosts(limit = 10) {
    return apiClient.get('/reddit/posts', { params: { limit } });
  },
  // 특정 서브레딧 게시물
  getSubredditPosts(subreddit, sort = 'hot', limit = 25) {
    return apiClient.get(`/reddit/subreddit/${subreddit}`, { params: { sort, limit } });
  },
  // 주식 관련 검색
  searchPosts(query, limit = 25) {
    return apiClient.get('/reddit/search', { params: { query, limit } });
  },
  // 트렌딩 티커
  getTrendingTickers(postLimit = 15) {
    return apiClient.get('/reddit/trending', { params: { postLimit } });
  },
  // 지원 서브레딧 목록
  getSubreddits() {
    return apiClient.get('/reddit/subreddits');
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
  // 시장 데이터 수집
  collectData() {
    return apiClient.post('/market/collect');
  },
  // 기간별 시장 데이터 수집 (Backfill)
  collectDataForPeriod(startDate, endDate) {
    return apiClient.post('/market/collect/period', null, {
      params: { startDate, endDate }
    });
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
  // 커스텀 메시지 발송
  sendMessage(message) {
    return apiClient.post('/telegram/send', null, { params: { message } });
  }
};

// Paper Trading (모의투자) API
export const paperTradingAPI = {
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
  }
};

// 간편 사용을 위한 export
export const signup = (signupData) => authAPI.signup(signupData);
export const getPendingUsers = () => userSettingsAPI.getPendingUsers();
export const approveUser = (userId, status) => userSettingsAPI.approveUser(userId, status);

