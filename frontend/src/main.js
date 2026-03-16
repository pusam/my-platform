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
// GoldPricePage, SilverPricePage, OilPricePage → GlobalFuturesPage에 탭으로 통합됨 (redirect)
const MyContentPage = () => import('./views/MyContentPage.vue')
const SettingsPage = () => import('./views/SettingsPage.vue')
const AssetManagement = () => import('./views/AssetManagement.vue')
const FileManager = () => import('./views/FileManager.vue')
const FinanceManagement = () => import('./views/FinanceManagement.vue')
const CarManagement = () => import('./views/CarManagement.vue')
const UserManagement = () => import('./views/UserManagement.vue')
const ActivityLogs = () => import('./views/ActivityLogs.vue')
// SectorTradingPage, EarningsScreenerPage → ResearchPage에 탭으로 통합됨 (redirect)
// InvestorAnalysisPage → ResearchPage에 탭으로 통합됨 (redirect)
// InvestorTradePage, ConsecutiveBuyPage, InvestorSurgePage → ResearchPage에 통합됨 (redirect)
const NewsPage = () => import('./views/NewsPage.vue')
const ResearchPage = () => import('./views/ResearchPage.vue')
const MarketTimingPage = () => import('./views/MarketTimingPage.vue')
const LottoAnalyzerPage = () => import('./views/LottoAnalyzerPage.vue')
const PensionLotteryPage = () => import('./views/PensionLotteryPage.vue')
// TradingIndicatorsPage → StockDetailDashboard에 탭으로 통합됨 (redirect)
// AiStrategyDashboardPage → StockTradingDashboardV2에 AI전략 탭으로 통합됨 (redirect)
const StockDetailDashboard = () => import('./views/StockDetailDashboard.vue')
const StockTradingDashboardV2 = () => import('./views/StockTradingDashboardV2.vue')
const PaperTradingPage = () => import('./views/PaperTradingPage.vue')
// GlobalFuturesPage → 대시보드 + MarketTimingPage에 흡수됨 (redirect)
// OilPricePage → 대시보드에 흡수됨 (redirect)
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
      meta: { requiresAuth: true }
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
      path: '/sector',
      redirect: '/research'
    },
    {
      path: '/news',
      name: 'NewsPage',
      component: NewsPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/investor',
      redirect: '/research'
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
      redirect: '/research'
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
      redirect: '/ai-strategy'
    },
    {
      path: '/ai-strategy',
      redirect: '/stock-dashboard'
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
      redirect: '/global-futures'
    },
    {
      path: '/global-futures',
      redirect: '/market-timing'
    },
    {
      path: '/admin/users',
      name: 'UserManagement',
      component: UserManagement,
      meta: { requiresAuth: true }
    },
    {
      path: '/admin/logs',
      name: 'ActivityLogs',
      component: ActivityLogs,
      meta: { requiresAuth: true }
    },
    {
      path: '/admin/batch',
      name: 'BatchJobMonitor',
      component: BatchJobMonitor,
      meta: { requiresAuth: true }
    }
  ]
})

// Navigation guard for authentication
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('jwt_token')

  // 인증이 필요한 페이지인데 토큰이 없는 경우
  if (to.meta.requiresAuth && !token) {
    next('/login')
    return
  }

  // 로그인된 상태에서 로그인/대시보드 접근 시 유저 대시보드로 이동
  if ((to.path === '/login' || to.path === '/dashboard') && token) {
    next('/user')
    return
  }

  next()
})

const app = createApp(App)
app.use(router)
app.mount('#app')
