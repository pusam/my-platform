import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import { TokenManager, UserManager } from './utils/auth'
import { consumeInjectedAuthToken } from './utils/nativeBridge'

// 초기 로딩에 필요한 페이지만 정적 import
import Login from './views/Login.vue'
import Signup from './views/Signup.vue'
import ForgotPassword from './views/ForgotPassword.vue'

// 나머지는 lazy loading (코드 스플리팅)
const AdminDashboard = () => import('./views/AdminDashboard.vue')
const UserDashboard = () => import('./views/UserDashboard.vue')
const BoardPage = () => import('./views/BoardPage.vue')
// GoldPricePage, SilverPricePage, OilPricePage → GlobalFuturesPage에 탭으로 통합됨 (redirect)
const MyContentPage = () => import('./views/MyContentPage.vue')
const SettingsPage = () => import('./views/SettingsPage.vue')
const AssetManagement = () => import('./views/AssetManagement.vue')
const FileManager = () => import('./views/FileManager.vue')
const FinanceManagement = () => import('./views/FinanceManagement.vue')
const CarManagement = () => import('./views/CarManagement.vue')
const DietManagement = () => import('./views/DietManagement.vue')
const ExerciseManagement = () => import('./views/ExerciseManagement.vue')
const UserManagement = () => import('./views/UserManagement.vue')
const ActivityLogs = () => import('./views/ActivityLogs.vue')
// /research, /paper-trading 등 레거시 경로는 StockTradingDashboardV2 sub-tab으로 redirect
const StockDetailDashboard = () => import('./views/StockDetailDashboard.vue')
const StockTradingDashboardV2 = () => import('./views/StockTradingDashboardV2.vue')
const GlobalFuturesPage = () => import('./views/GlobalFuturesPage.vue')
const BatchJobMonitor = () => import('./components/admin/BatchJobMonitor.vue')

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to, from, savedPosition) {
    // 브라우저 뒤로가기 시 이전 스크롤 위치 복원
    if (savedPosition) {
      return savedPosition
    }
    // 그 외에는 항상 맨 위로
    return { top: 0, behavior: 'smooth' }
  },
  routes: [
    {
      path: '/',
      redirect: '/login'
    },
    {
      path: '/login',
      name: 'Login',
      component: Login
    },
    {
      path: '/signup',
      name: 'Signup',
      component: Signup
    },
    {
      path: '/forgot-password',
      name: 'ForgotPassword',
      component: ForgotPassword
    },
    {
      path: '/dashboard',
      redirect: '/user'
    },
    {
      path: '/admin',
      name: 'AdminDashboard',
      component: AdminDashboard,
      // /admin/users, /admin/logs, /admin/batch 는 adminOnly 있는데 메인은 빠져있던 누락 보정.
      // 백엔드 SecurityConfig 가 /api/admin/** 를 hasRole("ADMIN") 으로 막아 데이터는 안 새지만
      // 일반 USER 가 페이지 진입해서 빈 화면/401 보는 UX 깨짐 방지.
      meta: { requiresAuth: true, adminOnly: true }
    },
    {
      path: '/user',
      name: 'UserDashboard',
      component: UserDashboard,
      meta: { requiresAuth: true }
    },
    {
      path: '/board',
      name: 'BoardPage',
      component: BoardPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/gold',
      redirect: '/global-futures'
    },
    {
      path: '/silver',
      redirect: '/global-futures'
    },
    {
      path: '/my-content',
      name: 'MyContent',
      component: MyContentPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/settings',
      name: 'Settings',
      component: SettingsPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/asset',
      name: 'AssetManagement',
      component: AssetManagement,
      meta: { requiresAuth: true }
    },
    {
      path: '/files',
      name: 'FileManager',
      component: FileManager,
      meta: { requiresAuth: true }
    },
    {
      path: '/finance',
      name: 'FinanceManagement',
      component: FinanceManagement,
      meta: { requiresAuth: true }
    },
    {
      path: '/car',
      name: 'CarManagement',
      component: CarManagement,
      meta: { requiresAuth: true }
    },
    {
      path: '/diet',
      name: 'DietManagement',
      component: DietManagement,
      meta: { requiresAuth: true }
    },
    {
      path: '/exercise',
      name: 'ExerciseManagement',
      component: ExerciseManagement,
      meta: { requiresAuth: true }
    },
    {
      path: '/sector',
      redirect: '/stock-dashboard?tab=market'
    },
    {
      path: '/news',
      redirect: '/stock-dashboard?tab=market&sub=news'
    },
    {
      path: '/investor',
      redirect: '/stock-dashboard?tab=market&sub=investor'
    },
    {
      path: '/investor-trades',
      redirect: '/stock-dashboard?tab=market&sub=investor'
    },
    {
      path: '/consecutive-buy',
      redirect: '/stock-dashboard?tab=market&sub=investor'
    },
    {
      path: '/investor-surge',
      redirect: '/stock-dashboard?tab=market&sub=investor'
    },
    {
      path: '/investor-stock/:stockCode',
      redirect: to => `/stock/${to.params.stockCode}`
    },
    {
      path: '/earnings-screener',
      redirect: '/stock-dashboard?tab=discover&sub=screener'
    },
    {
      path: '/research',
      redirect: '/stock-dashboard?tab=discover'
    },
    {
      path: '/market-timing',
      redirect: '/stock-dashboard?tab=market&sub=timing'
    },
    {
      path: '/trading-indicators',
      redirect: '/stock-dashboard'
    },
    {
      path: '/stock-dashboard',
      name: 'StockTradingDashboardV2',
      component: StockTradingDashboardV2,
      meta: { requiresAuth: true }
    },
    {
      path: '/ai-stock',
      redirect: '/stock-dashboard?tab=discover'
    },
    {
      path: '/ai-strategy',
      redirect: '/stock-dashboard?tab=discover&sub=ai-strategy'
    },
    {
      path: '/stock-detail',
      redirect: '/stock-dashboard'
    },
    {
      path: '/stock/:stockCode',
      name: 'StockDetailWithCode',
      component: StockDetailDashboard,
      meta: { requiresAuth: true }
    },
    {
      path: '/paper-trading',
      redirect: '/stock-dashboard?tab=trade',
      meta: { requiresAuth: true, adminOnly: true }
    },
    {
      path: '/oil',
      redirect: '/global-futures'
    },
    {
      path: '/global-futures',
      name: 'GlobalFutures',
      component: GlobalFuturesPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/admin/users',
      name: 'UserManagement',
      component: UserManagement,
      meta: { requiresAuth: true, adminOnly: true }
    },
    {
      path: '/admin/logs',
      name: 'ActivityLogs',
      component: ActivityLogs,
      meta: { requiresAuth: true, adminOnly: true }
    },
    {
      path: '/admin/batch',
      name: 'BatchJobMonitor',
      component: BatchJobMonitor,
      meta: { requiresAuth: true, adminOnly: true }
    }
  ]
})

// Navigation guard for authentication
router.beforeEach((to, from, next) => {
  const hasToken = TokenManager.hasToken()
  // 토큰은 있지만 만료/손상된 경우: 조용히 세션 정리 후 로그인으로 보낸다
  const tokenValid = hasToken && TokenManager.isTokenValid()
  if (hasToken && !tokenValid) {
    UserManager.logout()
  }

  // 인증이 필요한 페이지인데 유효 토큰이 없는 경우
  if (to.meta.requiresAuth && !tokenValid) {
    next('/login')
    return
  }

  // ADMIN 전용 페이지 가드
  if (to.meta.adminOnly && localStorage.getItem('role') !== 'ADMIN') {
    next('/user')
    return
  }

  // 이미 유효 토큰이 있는 상태에서 로그인 접근 시 유저 대시보드로
  if (to.path === '/login' && tokenValid) {
    next('/user')
    return
  }

  next()
})

// 네이티브 앱(지문 로그인 성공) 토큰 수신 → 자동 로그인
consumeInjectedAuthToken((token) => {
  if (!token) return
  TokenManager.setToken(token)
  if (window.location.pathname === '/login' || window.location.pathname === '/') {
    router.push('/user').catch(() => {})
  }
})

const app = createApp(App)

// 전역 에러 핸들러 — Vue 렌더/setup/watch/mounted 등에서 발생하는 미처리 예외를 캐치.
// 기존엔 silently 무시되어 디버깅 어려웠음. 콘솔에 컨텍스트와 함께 출력.
// 차후 Sentry 등 도입 시 여기서 ship.
app.config.errorHandler = (err, instance, info) => {
  const componentName = instance?.$options?.name || instance?.$?.type?.name || 'unknown'
  console.error(`[Vue ${info}] ${componentName}:`, err)
}

// 라우터 navigation 실패 핸들러 — push().catch 누락 시 silent 실패 회피
router.onError((err) => {
  console.error('[Router Error]', err)
})

app.use(router)
app.mount('#app')
