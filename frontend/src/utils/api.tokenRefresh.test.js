import { describe, it, expect, beforeEach, vi } from 'vitest'

// 2026-11-10 재현: access token(15분)이 만료된 뒤 열어 둔 탭의 폴링이 만료된 토큰으로 먼저 나가 401 을 받고 나서야
// 갱신했다 — 11/9 밤 뉴스·환율·시장 상태 폴링이 15분마다 401 을 하나씩(137번) 남겼다(서버 WARN 2줄씩).
// 이제 만료가 임박·경과한 요청은 보내기 전에 갱신한다. 401 뒤 갱신(안전망)은 그대로다.

function jwt(secondsFromNow) {
  const enc = (o) => btoa(JSON.stringify(o)).replace(/=+$/, '')
  return `${enc({ alg: 'HS256', typ: 'JWT' })}.${enc({ sub: 'u', exp: Math.floor(Date.now() / 1000) + secondsFromNow })}.sig`
}

function expOf(token) {
  try { return JSON.parse(atob(token.split('.')[1])).exp * 1000 } catch { return 0 }
}

// 모든 요청을 가로채는 가짜 서버 — 만료된 토큰이면 401, /auth/refresh 는 새 토큰을 준다
async function setup({ refreshOk = true } = {}) {
  vi.resetModules()
  const axios = (await import('axios')).default
  const calls = []
  const freshToken = jwt(15 * 60)
  axios.defaults.adapter = async (config) => {
    const auth = (config.headers && (config.headers.Authorization || config.headers.get?.('Authorization'))) || ''
    calls.push({ url: config.url, auth })
    if (config.url === '/auth/refresh') {
      const data = refreshOk ? { success: true, token: freshToken, refreshToken: 'rt-2' } : { success: false }
      return { data, status: 200, statusText: 'OK', headers: {}, config }
    }
    // 인증 API 는 토큰 없이 열려 있다(permitAll) — 로그인 실패도 200 + success:false 로 온다
    if (String(config.url).startsWith('/auth/')) {
      return { data: { success: false }, status: 200, statusText: 'OK', headers: {}, config }
    }
    const token = String(auth).replace('Bearer ', '')
    if (!token || expOf(token) <= Date.now()) {
      const response = { data: {}, status: 401, statusText: 'Unauthorized', headers: {}, config }
      throw new axios.AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, null, response)
    }
    return { data: { ok: true }, status: 200, statusText: 'OK', headers: {}, config }
  }
  const mod = await import('./api')
  return { apiClient: mod.default, calls, freshToken }
}

describe('api — 만료 임박 토큰은 보내기 전에 갱신한다', () => {
  beforeEach(() => {
    localStorage.clear()
    global.fetch = vi.fn(() => Promise.resolve({ ok: true }))
  })

  it('재현(11/9 밤): 만료된 토큰으로 폴링하면 401 없이 먼저 갱신하고 새 토큰으로 보낸다', async () => {
    localStorage.setItem('jwt_token', jwt(-60))           // 1분 전 만료
    localStorage.setItem('jwt_refresh_token', 'rt-1')
    const { apiClient, calls, freshToken } = await setup()

    const res = await apiClient.get('/news/poll')

    expect(res.data.ok).toBe(true)
    // 고치기 전: ['/news/poll'(401), '/auth/refresh', '/news/poll'] — 첫 요청이 만료된 토큰으로 나갔다
    expect(calls.map(c => c.url)).toEqual(['/auth/refresh', '/news/poll'])
    expect(calls[1].auth).toBe(`Bearer ${freshToken}`)
    expect(localStorage.getItem('jwt_token')).toBe(freshToken)
    expect(localStorage.getItem('jwt_refresh_token')).toBe('rt-2')
  })

  it('만료 30초 전이면 미리 갱신한다', async () => {
    localStorage.setItem('jwt_token', jwt(10))
    localStorage.setItem('jwt_refresh_token', 'rt-1')
    const { apiClient, calls } = await setup()

    await apiClient.get('/market/status/simple')

    expect(calls.map(c => c.url)).toEqual(['/auth/refresh', '/market/status/simple'])
  })

  it('동시에 나가는 폴링 3개도 /auth/refresh 는 한 번', async () => {
    localStorage.setItem('jwt_token', jwt(-5))
    localStorage.setItem('jwt_refresh_token', 'rt-1')
    const { apiClient, calls, freshToken } = await setup()

    await Promise.all([
      apiClient.get('/news/poll'),
      apiClient.get('/market/status/simple'),
      apiClient.get('/global-futures/quotes/KRW')
    ])

    expect(calls.filter(c => c.url === '/auth/refresh')).toHaveLength(1)
    const sent = calls.filter(c => c.url !== '/auth/refresh')
    expect(sent).toHaveLength(3)                                   // 401 재시도 없이 한 번씩
    expect(sent.every(c => c.auth === `Bearer ${freshToken}`)).toBe(true)
  })

  it('넉넉히 남은 토큰은 갱신하지 않는다', async () => {
    const token = jwt(10 * 60)
    localStorage.setItem('jwt_token', token)
    localStorage.setItem('jwt_refresh_token', 'rt-1')
    const { apiClient, calls } = await setup()

    await apiClient.get('/news/poll')

    expect(calls.map(c => c.url)).toEqual(['/news/poll'])
    expect(calls[0].auth).toBe(`Bearer ${token}`)
  })

  it('로그인 요청은 남은 토큰이 만료돼 있어도 갱신을 먼저 부르지 않는다', async () => {
    localStorage.setItem('jwt_token', jwt(-60))
    localStorage.setItem('jwt_refresh_token', 'rt-1')
    const { apiClient, calls } = await setup()

    await apiClient.post('/auth/login', { username: 'u', password: 'p' }).catch(() => {})

    expect(calls.map(c => c.url)).not.toContain('/auth/refresh')
  })

  it('미리 갱신이 실패하면 옛 토큰으로 보내고, 401 뒤 갱신(안전망)이 한 번 더 시도한다', async () => {
    localStorage.setItem('jwt_token', jwt(-60))
    localStorage.setItem('jwt_refresh_token', 'rt-1')
    const { apiClient, calls } = await setup({ refreshOk: false })

    await expect(apiClient.get('/news/poll')).rejects.toBeTruthy()

    expect(calls.map(c => c.url)).toEqual(['/auth/refresh', '/news/poll', '/auth/refresh'])
  })
})
