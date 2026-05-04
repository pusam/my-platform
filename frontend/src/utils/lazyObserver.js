/**
 * IntersectionObserver 헬퍼 — 엘리먼트가 뷰포트에 진입할 때 콜백을 1회 실행하고 해제.
 * - 옵션 API/setup 양쪽에서 사용 가능
 * - rootMargin 200px: 뷰포트 진입 직전에 미리 트리거(체감 지연 감소)
 * - SSR/구버전 폴백: IntersectionObserver 없으면 즉시 실행
 *
 * @param {Element|null} el  관찰할 DOM 엘리먼트
 * @param {() => void} callback  진입 시 호출할 콜백
 * @param {object} [options]  rootMargin/threshold 등 IntersectionObserver 옵션
 * @returns {() => void} 수동 해제용 disposer
 */
export function observeOnce(el, callback, options = {}) {
  if (!el) {
    callback();
    return () => {};
  }
  if (typeof IntersectionObserver === 'undefined') {
    callback();
    return () => {};
  }
  const observer = new IntersectionObserver((entries) => {
    if (entries.some(e => e.isIntersecting)) {
      observer.disconnect();
      callback();
    }
  }, { rootMargin: '200px', threshold: 0, ...options });
  observer.observe(el);
  return () => observer.disconnect();
}
