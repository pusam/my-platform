import { ref, onMounted, onBeforeUnmount } from 'vue'

/**
 * 자동 폴링 + 수동 갱신 composable
 *
 * @param {Function} fetcher - 비동기 데이터 로더. 호출 결과는 Promise<any>.
 * @param {Object} options
 * @param {number} options.interval - 폴링 주기 (ms). 기본 15000.
 * @param {boolean} options.immediate - mount 시 즉시 1회 실행. 기본 true.
 * @param {boolean} options.pauseWhenHidden - 탭 비활성 시 폴링 정지. 기본 true.
 *
 * @returns {{
 *   lastUpdated: Ref<Date|null>,
 *   isRefreshing: Ref<boolean>,
 *   nextRefreshIn: Ref<number>,  // 다음 갱신까지 남은 초
 *   manualRefresh: () => Promise<void>,
 *   stop: () => void,
 *   start: () => void
 * }}
 */
export function useAutoRefresh(fetcher, options = {}) {
  const {
    interval = 15000,
    immediate = true,
    pauseWhenHidden = true,
  } = options

  const lastUpdated = ref(null)
  const isRefreshing = ref(false)
  const nextRefreshIn = ref(Math.ceil(interval / 1000))

  let pollTimer = null
  let countdownTimer = null
  let stopped = false

  const runFetch = async () => {
    if (isRefreshing.value) return
    isRefreshing.value = true
    try {
      await fetcher()
      lastUpdated.value = new Date()
    } catch (e) {
      // fetcher 자체에서 처리. 여기서는 silent — 마지막 성공 시각만 유지.
      console.error('[useAutoRefresh] fetch error:', e)
    } finally {
      isRefreshing.value = false
      nextRefreshIn.value = Math.ceil(interval / 1000)
    }
  }

  const startPolling = () => {
    if (pollTimer) clearInterval(pollTimer)
    if (countdownTimer) clearInterval(countdownTimer)

    pollTimer = setInterval(() => {
      if (pauseWhenHidden && typeof document !== 'undefined' && document.hidden) return
      runFetch()
    }, interval)

    // 1초 단위 카운트다운 — UI 표시용
    countdownTimer = setInterval(() => {
      if (pauseWhenHidden && typeof document !== 'undefined' && document.hidden) return
      if (nextRefreshIn.value > 0) nextRefreshIn.value -= 1
    }, 1000)
  }

  const stop = () => {
    stopped = true
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
    if (countdownTimer) { clearInterval(countdownTimer); countdownTimer = null }
  }

  const start = () => {
    stopped = false
    startPolling()
  }

  const manualRefresh = async () => {
    nextRefreshIn.value = Math.ceil(interval / 1000)
    await runFetch()
  }

  const handleVisibility = () => {
    if (!pauseWhenHidden) return
    if (typeof document === 'undefined') return
    // 탭 다시 활성화되면 즉시 1회 갱신
    if (!document.hidden && !stopped && lastUpdated.value) {
      const elapsed = Date.now() - lastUpdated.value.getTime()
      if (elapsed > interval) runFetch()
    }
  }

  onMounted(() => {
    if (immediate) runFetch()
    startPolling()
    if (pauseWhenHidden && typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', handleVisibility)
    }
  })

  onBeforeUnmount(() => {
    stop()
    if (pauseWhenHidden && typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', handleVisibility)
    }
  })

  return { lastUpdated, isRefreshing, nextRefreshIn, manualRefresh, stop, start }
}
