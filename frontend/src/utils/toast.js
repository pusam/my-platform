// 전역 토스트 싱글톤 - Vue inject 없이(예: utils/api.js) 호출 가능
// App.vue가 mount 시 setToastHandler로 AppToast ref를 등록한다.

let handler = null

export const setToastHandler = (fn) => {
  handler = fn
}

const emit = (type, msg, duration) => {
  if (handler) {
    handler(msg, type, duration)
  } else if (typeof console !== 'undefined') {
    console.warn(`[toast:${type}]`, msg)
  }
}

export const toast = {
  success: (msg, duration = 3500) => emit('success', msg, duration),
  error: (msg, duration = 5000) => emit('error', msg, duration),
  warning: (msg, duration = 4000) => emit('warning', msg, duration),
  info: (msg, duration = 3500) => emit('info', msg, duration)
}
