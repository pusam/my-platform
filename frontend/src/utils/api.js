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

// 응답 인터셉터 - 401 에러 시 로그아웃 처리
apiClient.interceptors.response.use(
  (response) => {
    return response;
  },
  (error) => {
    if (error.response && error.response.status === 401) {
      // 토큰이 만료되었거나 유효하지 않은 경우
      UserManager.logout();
      window.location.href = '/';
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

// 간편 사용을 위한 export
export const signup = (signupData) => authAPI.signup(signupData);
export const getPendingUsers = () => userSettingsAPI.getPendingUsers();
export const approveUser = (userId, status) => userSettingsAPI.approveUser(userId, status);

