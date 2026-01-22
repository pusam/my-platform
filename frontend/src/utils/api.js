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

// Investor Trading API (수급 탐지기)
export const investorAPI = {
  // 종목별 투자자 동향 조회
  getInvestorTrading(stockCode) {
    return apiClient.get(`/investor/${stockCode}`);
  },
  // 주요 종목 수급 현황
  getTopStocks() {
    return apiClient.get('/investor/top');
  },
  // 관심 종목 수급 조회
  getWatchlist(stockCodes) {
    return apiClient.post('/investor/watchlist', stockCodes);
  },
  // 수급 순위 조회 (외국인+기관 순매수 상위)
  getRanking(sector = '', sortBy = 'TOTAL') {
    return apiClient.get('/investor/ranking', { params: { sector, sortBy } });
  },
  // 수급 이상 종목 탐지 (프로그램 순매수 쌓이는데 주가 횡보 등)
  getAnomalyStocks() {
    return apiClient.get('/investor/anomaly');
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

// 간편 사용을 위한 export
export const signup = (signupData) => authAPI.signup(signupData);
export const getPendingUsers = () => userSettingsAPI.getPendingUsers();
export const approveUser = (userId, status) => userSettingsAPI.approveUser(userId, status);

