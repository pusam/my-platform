import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import Login from './views/Login.vue'
import Signup from './views/Signup.vue'
import ForgotPassword from './views/ForgotPassword.vue'
import Dashboard from './views/Dashboard.vue'
import AdminDashboard from './views/AdminDashboard.vue'
import UserDashboard from './views/UserDashboard.vue'
import BoardPage from './views/BoardPage.vue'
import GoldPricePage from './views/GoldPricePage.vue'
import SilverPricePage from './views/SilverPricePage.vue'
import MyContentPage from './views/MyContentPage.vue'
import SettingsPage from './views/SettingsPage.vue'
import AssetManagement from './views/AssetManagement.vue'
import FileManager from './views/FileManager.vue'
import FinanceManagement from './views/FinanceManagement.vue'
import CarManagement from './views/CarManagement.vue'
import UserManagement from './views/UserManagement.vue'
import ActivityLogs from './views/ActivityLogs.vue'
import SectorTradingPage from './views/SectorTradingPage.vue'
import RedditPage from './views/RedditPage.vue'
import InvestorTradePage from './views/InvestorTradePage.vue'
import InvestorStockDetailPage from './views/InvestorStockDetailPage.vue'
import ConsecutiveBuyPage from './views/ConsecutiveBuyPage.vue'
import InvestorSurgePage from './views/InvestorSurgePage.vue'
import NewsPage from './views/NewsPage.vue'

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
      path: '/reddit',
      name: 'RedditPage',
      component: RedditPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/news',
      name: 'NewsPage',
      component: NewsPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/investor-trades',
      name: 'InvestorTrade',
      component: InvestorTradePage,
      meta: { requiresAuth: true }
    },
    {
      path: '/investor-stock/:stockCode',
      name: 'InvestorStockDetail',
      component: InvestorStockDetailPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/consecutive-buy',
      name: 'ConsecutiveBuy',
      component: ConsecutiveBuyPage,
      meta: { requiresAuth: true }
    },
    {
      path: '/investor-surge',
      name: 'InvestorSurge',
      component: InvestorSurgePage,
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
    }
  ]
})

// Navigation guard for authentication
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('jwt_token')
  const role = localStorage.getItem('role')

  // 인증이 필요한 페이지인데 토큰이 없으면 로그인으로
  // 인증이 필요한 페이지인데 토큰이 없는 경우
  if (to.meta.requiresAuth && !token) {
    next('/login')
    return
  }

  // 로그인 페이지 접근 시 이미 로그인되어 있으면 역할별 대시보드로
  if (to.path === '/login' && token) {
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

  // 특정 역할이 필요한 페이지 접근 시 권한 체크
  if (to.meta.role && role && to.meta.role !== role) {
    // 권한이 없는 페이지 접근 시 자신의 대시보드로 이동
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

  // 권한이 필요한 페이지인데 역할이 맞지 않으면 자신의 대시보드로
  if (to.meta.role && to.meta.role !== role) {
    if (role === 'ADMIN') {
      next('/admin')
    } else {
      next('/user')
    }
    return
  }

  // 모든 검사를 통과하면 이동 허용
  next()
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

