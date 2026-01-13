<template>
  <div class="dashboard-container">
    <header class="header">
      <h1>My Platform</h1>
      <div class="user-info">
        <span>{{ userName }}</span>
        <button @click="logout" class="logout-btn">로그아웃</button>
      </div>
    </header>
    <main class="main-content">
      <h2>대시보드</h2>

      <!-- 위젯 그리드 -->
      <div class="widget-grid">
        <!-- 금 시세 위젯 -->
        <GoldPriceWidget />
        <!-- 은 시세 위젯 -->
        <SilverPriceWidget />
        <!-- 파일 관리 위젯 -->
        <FileManagerWidget />
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { UserManager } from '../utils/auth'
import { userAPI } from '../utils/api'
import GoldPriceWidget from '../components/GoldPriceWidget.vue'
import SilverPriceWidget from '../components/SilverPriceWidget.vue'
import FileManagerWidget from '../components/FileManagerWidget.vue'

const router = useRouter()

const userName = ref('')

onMounted(async () => {
  // 사용자 정보 표시
  const user = UserManager.getUser()
  if (user) {
    userName.value = user.name + '님'
  }
})

const logout = () => {
  UserManager.logout()
  router.push('/')
}
</script>

<style scoped>
@import '../assets/css/dashboard.css';

.widget-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 24px;
  margin-top: 24px;
}
</style>

