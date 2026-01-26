// JWT 토큰 관리 유틸리티
export const TokenManager = {
  // 토큰 저장
  setToken(token) {
    localStorage.setItem('jwt_token', token);
  },

  // 토큰 가져오기
  getToken() {
    return localStorage.getItem('jwt_token');
  },

  // 토큰 삭제
  removeToken() {
    localStorage.removeItem('jwt_token');
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
    localStorage.setItem('user_info', JSON.stringify(user));
    // 역할 정보도 별도로 저장 (라우터 가드에서 빠른 접근)
    if (user && user.role) {
      localStorage.setItem('role', user.role);
    }
  },

  // 사용자 정보 가져오기
  getUser() {
    const userStr = localStorage.getItem('user_info');
    return userStr ? JSON.parse(userStr) : null;
  },

  // 역할 정보 가져오기
  getRole() {
    return localStorage.getItem('role');
  },

  // 사용자 정보 삭제
  removeUser() {
    localStorage.removeItem('user_info');
    localStorage.removeItem('role');
  },

  // 로그아웃 (토큰 및 사용자 정보 모두 삭제)
  logout() {
    TokenManager.removeToken();
    this.removeUser();
    localStorage.removeItem('role');
  }
};

