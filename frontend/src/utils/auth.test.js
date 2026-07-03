import { describe, it, expect, beforeEach, vi } from 'vitest';
import { UserManager, TokenManager } from './auth';

/**
 * 로그아웃 세션 완전 종료 회귀 — UserManager.logout() 은 AT + RT 를 모두 삭제해야 한다.
 * RT 가 남으면 라우터 가드(main.js)의 canRefresh=true → sessionAlive=true 로 판정,
 * /login 접근을 /user 로 되돌려 "로그아웃해도 반응 없음" 버그가 됐다(UserDashboard/AdminDashboard
 * 의 손수 removeItem 이 jwt_refresh_token 을 안 지우던 문제 — UserManager.logout 로 통일).
 */
describe('UserManager.logout — 세션 완전 종료(가드 canRefresh 차단)', () => {
  beforeEach(() => {
    localStorage.clear();
    // 서버 로그아웃 fetch stub (best-effort — 실패해도 로컬 삭제는 진행)
    global.fetch = vi.fn(() => Promise.resolve({ ok: true }));
  });

  it('AT + RT 를 모두 삭제 — RT 잔존 시 가드가 /login→/user 로 되돌리던 버그 방지', () => {
    TokenManager.setToken('a.b.c');
    TokenManager.setRefreshToken('refresh-xyz');
    expect(TokenManager.getRefreshToken()).toBe('refresh-xyz');

    UserManager.logout();

    expect(TokenManager.getToken()).toBeNull();
    expect(TokenManager.getRefreshToken()).toBeNull();   // canRefresh=false → 세션 실제 종료
  });

  it('토큰 있으면 서버 로그아웃(POST /api/auth/logout)도 호출 — 서버 Redis RT 삭제', () => {
    TokenManager.setToken('a.b.c');

    UserManager.logout();

    expect(global.fetch).toHaveBeenCalledWith('/api/auth/logout', expect.objectContaining({ method: 'POST' }));
  });
});
