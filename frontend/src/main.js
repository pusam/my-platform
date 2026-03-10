import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'

// 초기 로딩에 필요한 페이지만 정적 import
import Login from './views/Login.vue'
import Signup from './views/Signup.vue'
import ForgotPassword from './views/ForgotPassword.vue'

// 나머지는 lazy loading (코드 스플리팅)
const Dashboard = () => import('./views/Dashboard.vue')
const AdminDashboard = () => import('./views/AdminDashboard.vue')
const UserDashboard = () => import('./views/UserDashboard.vue')
const BoardPage = () => import('./views/BoardPage.vue')
const GoldPricePage = () => import('./views/GoldPricePage.vue')
const SilverPricePage = () => import('./views/SilverPricePage.vue')
const MyContentPage = () => import('./views/MyContentPage.vue')
const SettingsPage = () => import('./views/SettingsPage.vue')
const AssetManagement = () => import('./views/AssetManagement.vue')
const FileManager = () => import('./views/FileManager.vue')
const FinanceManagement = () => import('./views/FinanceManagement.vue')
const CarManagement = () => import('./views/CarManagement.vue')
const UserManagement = () => import('./views/UserManagement.vue')
const ActivityLogs = () => import('./views/ActivityLogs.vue')
const SectorTradingPage = () => import('./views/SectorTradingPage.vue')
const InvestorAnalysisPage = () => import('./views/InvestorAnalysisPage.vue')
// InvestorTradePage, ConsecutiveBuyPage, InvestorSurgePage → InvestorAnalysisPage에 통합됨 (redirect)
const NewsPage = () => import('./views/NewsPage.vue')
const EarningsScreenerPage = () => import('./views/EarningsScreenerPage.vue')
const ResearchPage = () => import('./views/ResearchPage.vue')
const MarketTimingPage = () => import('./views/MarketTimingPage.vue')
const LottoAnalyzerPage = () => import('./views/LottoAnalyzerPage.vue')
const PensionLotteryPage = () => import('./views/PensionLotteryPage.vue')
const TradingIndicatorsPage = () => import('./views/TradingIndicatorsPage.vue')
const AiStrategyDashboardPage = () => import('./views/AiStrategyDashboardPage.vue')
const StockDetailDashboard = () => import('./views/StockDetailDashboard.vue')
const StockTradingDashboardV2 = () => import('./views/StockTradingDashboardV2.vue')
const PaperTradingPage = () => import('./views/PaperTradingPage.vue')
const GlobalFuturesPage = () => import('./views/GlobalFuturesPage.vue')
const OilPricePage = () => import('./views/OilPricePage.vue')
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
      name: 'Dashboard',
      component: Dashboard,
      meta: { requiresAuth: true }
    },
    {
      path: '/admin',
      name: 'AdminDashboard',
      component: AdminDashboard,
      meta: { requiresAuth: true, role: 'ADMIN' }
    },
    {
      path: '/user',
      name: 'UserDashboard',
      component: UserDashboard,
      meta: { requiresAuth: true, role: 'USER' }
    },
    {
      path: '/board',
      name: 'BoardPage',
      component: BoardPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/gold',
      name: 'GoldPrice',
      component: GoldPricePage,
      meta: { requiresAuth: true }
    },
    {
      path: '/silver',
      name: 'SilverPrice',
      component: SilverPricePage,
      meta: { requiresAuth: true }
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
      path: '/sector',
      name: 'SectorTrading',
      component: SectorTradingPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/news',
      name: 'NewsPage',
      component: NewsPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/investor',
      name: 'InvestorAnalysis',
      component: InvestorAnalysisPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/investor-trades',
      redirect: '/investor'
    },
    {
      path: '/consecutive-buy',
      redirect: '/investor'
    },
    {
      path: '/investor-surge',
      redirect: '/investor'
    },
    {
      path: '/investor-stock/:stockCode',
      redirect: to => `/stock/${to.params.stockCode}`
    },
    {
      path: '/earnings-screener',
      name: 'EarningsScreener',
      component: EarningsScreenerPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/research',
      name: 'Research',
      component: ResearchPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/market-timing',
      name: 'MarketTiming',
      component: MarketTimingPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/lotto',
      name: 'LottoAnalyzer',
      component: LottoAnalyzerPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/pension-lottery',
      name: 'PensionLottery',
      component: PensionLotteryPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/trading-indicators',
      name: 'TradingIndicators',
      component: TradingIndicatorsPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/stock-dashboard',
      name: 'StockTradingDashboardV2',
      component: StockTradingDashboardV2,
      meta: { requiresAuth: true }
    },
    {
      path: '/ai-stock',
      redirect: '/ai-strategy'
    },
    {
      path: '/ai-strategy',
      name: 'AiTradingStrategy',
      component: AiStrategyDashboardPage,
      meta: { requiresAuth: true }
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
      name: 'PaperTrading',
      component: PaperTradingPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/oil',
      name: 'OilPrice',
      component: OilPricePage,
      meta: { requiresAuth: true }
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
      meta: { requiresAuth: true, role: 'ADMIN' }
    },
    {
      path: '/admin/logs',
      name: 'ActivityLogs',
      component: ActivityLogs,
      meta: { requiresAuth: true, role: 'ADMIN' }
    },
    {
      path: '/admin/batch',
      name: 'BatchJobMonitor',
      component: BatchJobMonitor,
      meta: { requiresAuth: true, role: 'ADMIN' }
    }
  ]
})

// Navigation guard for authentication
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('jwt_token')
  const role = localStorage.getItem('role')

  // 인증이 필요한 페이지인데 토큰이 없는 경우
  if (to.meta.requiresAuth && !token) {
    next('/login')
    return
  }

  // 로그인된 상태에서 로그인 페이지 접근 시 역할별 대시보드로 이동
  if (to.path === '/login' && token) {
    if (role === 'ADMIN') {
      next('/admin')
    } else {
      next('/user')
    }
    return
  }

  // /dashboard 경로는 역할별로 리다이렉션
  if (to.path === '/dashboard' && token) {
    if (role === 'ADMIN') {
      next('/admin')
    } else {
      next('/user')
    }
    return
  }

  // 특정 역할이 필요한 페이지 접근 시 권한 체크
  if (to.meta.role && role && to.meta.role !== role) {
    // 권한이 없는 페이지 접근 시 자신의 대시보드로 이동
    if (role === 'ADMIN') {
      next('/admin')
    } else {
      next('/user')
    }
    return
  }

  // role이 필요한데 role이 없는 경우 (비정상 상태) - 로그인 페이지로
  if (to.meta.role && !role) {
    localStorage.removeItem('jwt_token')
    localStorage.removeItem('role')
    next('/login')
    return
  }

  next()
})

const app = createApp(App)
app.use(router)
app.mount('#app')
