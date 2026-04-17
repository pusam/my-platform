// Flutter WebView (flutter_inappwebview) JS 브리지 래퍼
// 네이티브 앱이 아닌 일반 브라우저에서는 no-op으로 동작한다.

function bridge() {
  return (typeof window !== 'undefined' && window.flutter_inappwebview) || null;
}

export const NativeBridge = {
  isAvailable() {
    return bridge() !== null;
  },

  async saveAuthToken(token) {
    const b = bridge();
    if (!b || !token) return false;
    try {
      const res = await b.callHandler('saveAuthToken', token);
      return !!(res && res.ok);
    } catch (e) {
      console.warn('[NativeBridge] saveAuthToken failed:', e);
      return false;
    }
  },

  async clearAuthToken() {
    const b = bridge();
    if (!b) return false;
    try {
      const res = await b.callHandler('clearAuthToken');
      return !!(res && res.ok);
    } catch (e) {
      console.warn('[NativeBridge] clearAuthToken failed:', e);
      return false;
    }
  },

  async getAuthToken() {
    const b = bridge();
    if (!b) return null;
    try {
      const res = await b.callHandler('getAuthToken');
      return (res && res.token) || null;
    } catch (e) {
      console.warn('[NativeBridge] getAuthToken failed:', e);
      return null;
    }
  }
};

// 네이티브가 부팅 시 window.AUTH_TOKEN을 미리 주입하거나
// window.onNativeAuthToken(token) 콜백을 호출한다.
// 이 함수는 앱 진입 시 1회 호출되어야 한다.
export function consumeInjectedAuthToken(onToken) {
  if (typeof window === 'undefined') return;

  if (window.AUTH_TOKEN) {
    try { onToken(window.AUTH_TOKEN); } catch (_) {}
    // 1회 소비 후 제거 (메모리 잔류 방지)
    try { delete window.AUTH_TOKEN; } catch (_) { window.AUTH_TOKEN = undefined; }
  }

  window.onNativeAuthToken = (token) => {
    if (token) {
      try { onToken(token); } catch (_) {}
    }
  };
}
