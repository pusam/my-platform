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
import UserApproval from './views/UserApproval.vue'
import AssetManagement from './views/AssetManagement.vue'
import FileManager from './views/FileManager.vue'
import FinanceManagement from './views/FinanceManagement.vue'
import CarManagement from './views/CarManagement.vue'

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
      path: '/user-approval',
      name: 'UserApproval',
      component: UserApproval,
      meta: { requiresAuth: true, role: 'ADMIN' }
    }
  ]
})

// Navigation guard for authentication
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('jwt_token')
  const role = localStorage.getItem('role')

  if (to.meta.requiresAuth && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    // 로그인된 상태에서 로그인 페이지 접근 시 역할별 대시보드로 이동
    if (role === 'ADMIN') {
      next('/admin')
    } else {
      next('/user')
    }
  } else if (to.meta.role && to.meta.role !== role) {
    // 권한이 없는 페이지 접근 시 자신의 대시보드로 이동
    if (role === 'ADMIN') {
      next('/admin')
    } else {
      next('/user')
    }
  } else {
    next()
  }
})

const app = createApp(App)
app.use(router)
app.mount('#app')

