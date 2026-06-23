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
    safeRemoveItem('jwt_refresh_token');
    NativeBridge.clearAuthToken().catch(() => {});
  },

  // Refresh Token — 7일 TTL, /api/auth/refresh 호출용
  setRefreshToken(refreshToken) {
    if (refreshToken) safeSetItem('jwt_refresh_token', refreshToken);
  },
  getRefreshToken() {
    return safeGetItem('jwt_refresh_token');
  },
  removeRefreshToken() {
    safeRemoveItem('jwt_refresh_token');
  },

  // 토큰 존재 여부 확인
  hasToken() {
    return !!this.getToken();
  },

  // JWT payload의 exp 클레임을 디코드해 만료 여부 확인
  // 반환: true = 유효 / false = 없음·손상·만료
  isTokenValid() {
    const token = this.getToken();
    if (!token) return false;
    try {
      const parts = token.split('.');
      if (parts.length !== 3) return false;
      // base64url → base64
      const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      const payload = JSON.parse(atob(b64));
      if (!payload || typeof payload.exp !== 'number') return false;
      return payload.exp * 1000 > Date.now();
    } catch (e) {
      return false;
    }
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
    // 서버 Redis 의 AT/RT 도 best-effort 삭제 — 로그아웃 후 옛 RT 로 재인증되는 것 차단.
    // api.js(apiClient) 를 import 하면 순환참조라 raw fetch 사용. keepalive 로 이동/언로드 중에도 전송 보장.
    const token = TokenManager.getToken();
    if (token) {
      try {
        fetch('/api/auth/logout', {
          method: 'POST',
          headers: { 'Authorization': `Bearer ${token}` },
          keepalive: true
        }).catch(() => {});
      } catch (e) { /* fetch 미지원/실패 무시 — 로컬 로그아웃은 계속 */ }
    }
    TokenManager.removeToken();
    this.removeUser();
    safeRemoveItem('role');
  }
};
