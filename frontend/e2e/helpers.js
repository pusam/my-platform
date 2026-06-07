// E2E 공용 헬퍼: 인증 우회(localStorage 시드) + API 라우트 모킹(백엔드 불필요).

function makeJwt() {
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString('base64url')
  // exp 를 먼 미래로 → TokenManager.isTokenValid() 통과
  return `${enc({ alg: 'HS256', typ: 'JWT' })}.${enc({ sub: 'e2e', exp: 9999999999 })}.sig`
}

/** 라우터 가드(requiresAuth/adminOnly) 통과용 토큰·role 시드. 앱 로드 전에 주입. */
export async function setupAuth(page, role = 'USER') {
  const token = makeJwt()
  await page.addInitScript(([t, r]) => {
    localStorage.setItem('jwt_token', t)
    localStorage.setItem('role', r)
    localStorage.setItem('user_info', JSON.stringify({ username: 'e2e', role: r }))
  }, [token, role])
}

/** 모든 /api 호출을 가볍게 모킹 — 종목 상세 quick 은 hasData=true 되도록 price 포함. */
export async function mockApi(page) {
  await page.route('**/api/**', (route) => {
    const url = route.request().url()
    if (/\/stock\/[^/]+\/quick/.test(url)) {
      return route.fulfill({
        json: { success: true, data: { stockName: '삼성전자', price: { currentPrice: 70000, changeRate: 1.5 } } }
      })
    }
    if (/\/stock\/[^/]+\/heavy/.test(url)) {
      return route.fulfill({ json: { success: true, data: {} } })
    }
    // 그 외 — 빈 배열 성공 응답(컴포넌트 hang/타임아웃 방지)
    return route.fulfill({ json: { success: true, data: [] } })
  })
}

/** 인증 + 모킹을 한 번에. */
export async function bootstrap(page, role = 'USER') {
  await setupAuth(page, role)
  await mockApi(page)
}
