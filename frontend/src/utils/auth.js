import { NativeBridge } from './nativeBridge';

// localStorage 안전 래퍼 (시크릿 모드/용량 초과 대비)
function safeGetItem(key) {
  try {
    return localStorage.getItem(key);
  } catch (e) {
    console.warn('localStorage 읽기 실패:', e);
    return null;
  }
}

function safeSetItem(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch (e) {
    console.warn('localStorage 쓰기 실패:', e);
  }
}

function safeRemoveItem(key) {
  try {
    localStorage.removeItem(key);
  } catch (e) {
    console.warn('localStorage 삭제 실패:', e);
  }
}

// JWT 토큰 관리 유틸리티
export const TokenManager = {
  // 토큰 저장 (네이티브 앱이면 secure storage로도 복사)
  setToken(token) {
    safeSetItem('jwt_token', token);
    // 파이어앤드포겟 — 실패해도 웹 로그인 흐름은 방해하지 않음
    NativeBridge.saveAuthToken(token).catch(() => {});
  },

  // 토큰 가져오기
  getToken() {
    return safeGetItem('jwt_token');
  },

  // 토큰 삭제
  removeToken() {
    safeRemoveItem('jwt_token');
    NativeBridge.clearAuthToken().catch(() => {});
  },

  // 토큰 존재 여부 확인
  hasToken() {
    return !!this.getToken();
  },

  // Authorization 헤더 생성
  getAuthHeader() {
    const token = this.getToken();
    return token ? `Bearer ${token}` : null;
  }
};

// 사용자 정보 관리
export const UserManager = {
  // 사용자 정보 저장
  setUser(user) {
    safeSetItem('user_info', JSON.stringify(user));
    // 역할 정보도 별도로 저장 (라우터 가드에서 빠른 접근)
    if (user && user.role) {
      safeSetItem('role', user.role);
    }
  },

  // 사용자 정보 가져오기
  getUser() {
    const userStr = safeGetItem('user_info');
    if (!userStr) return null;
    try {
      return JSON.parse(userStr);
    } catch (e) {
      return null;
    }
  },

  // 역할 정보 가져오기
  getRole() {
    return safeGetItem('role');
  },

  // 사용자 정보 삭제
  removeUser() {
    safeRemoveItem('user_info');
    safeRemoveItem('role');
  },

  // 로그아웃 (토큰 및 사용자 정보 모두 삭제)
  logout() {
    TokenManager.removeToken();
    this.removeUser();
    safeRemoveItem('role');
  }
};
