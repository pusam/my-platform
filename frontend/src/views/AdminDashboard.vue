<template>
  <div class="page-container">
    <div class="page-content">
      <header class="common-header">
        <h1>관리자 대시보드</h1>
        <div class="header-actions">
          <div class="header-user">
            <span>{{ username }}</span>
          </div>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

    <div class="dashboard-content">
      <div class="stats-grid">
        <div class="stat-card">
          <h3>총 사용자 수</h3>
          <p class="stat-number">{{ stats.totalUsers }}</p>
        </div>
        <div class="stat-card">
          <h3>활성 사용자</h3>
          <p class="stat-number">{{ stats.activeUsers }}</p>
        </div>
        <div class="stat-card">
          <h3>시스템 상태</h3>
          <p class="stat-status">정상</p>
        </div>
        <div class="stat-card">
          <h3>권한</h3>
          <p class="stat-role">관리자</p>
        </div>
      </div>

      <div class="admin-sections">
        <div class="section">
          <h2>📋 게시판 관리</h2>
          <p>전체 게시글을 관리하고 모니터링할 수 있습니다.</p>
          <button @click="goToBoard" class="action-btn">게시판 이동</button>
        </div>

        <div class="section">
          <h2>✅ 회원가입 승인</h2>
          <p>승인 대기 중인 사용자를 관리할 수 있습니다.</p>
          <button @click="goToUserApproval" class="action-btn">승인 관리</button>
        </div>

        <div class="section">
          <h2>사용자 관리</h2>
          <p>시스템의 모든 사용자를 관리할 수 있습니다.</p>
          <button class="action-btn">사용자 목록 보기</button>
        </div>

        <div class="section">
          <h2>시스템 설정</h2>
          <p>시스템 전역 설정을 변경할 수 있습니다.</p>
          <button class="action-btn">설정 관리</button>
        </div>

        <div class="section">
          <h2>로그 관리</h2>
          <p>시스템 로그 및 활동 내역을 확인합니다.</p>
          <button class="action-btn">로그 보기</button>
        </div>
      </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'AdminDashboard',
  data() {
    return {
      username: '',
      stats: {
        totalUsers: 0,
        activeUsers: 0
      }
    }
  },
  mounted() {
    this.username = localStorage.getItem('username') || 'Admin'
    this.loadStats()
  },
  methods: {
    loadStats() {
      // 통계 데이터 로드 (향후 API 연동)
      this.stats.totalUsers = 2
      this.stats.activeUsers = 2
    },
    goToBoard() {
      this.$router.push('/board')
    },
    goToUserApproval() {
      this.$router.push('/user-approval')
    },
    logout() {
      localStorage.removeItem('jwt_token')
      localStorage.removeItem('username')
      localStorage.removeItem('role')
      this.$router.push('/login')
    }
  }
}
</script>

<style scoped>
@import '../assets/css/common.css';

.dashboard-content {
  max-width: 1200px;
  margin: 0 auto;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 20px;
  margin-bottom: 30px;
}

.stat-card {
  background: white;
  padding: 25px;
  border-radius: 10px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
  text-align: center;
}

.stat-card h3 {
  margin: 0 0 15px 0;
  color: #666;
  font-size: 16px;
  font-weight: 500;
}

.stat-number {
  font-size: 36px;
  font-weight: bold;
  color: #667eea;
  margin: 0;
}

.stat-status {
  font-size: 24px;
  font-weight: bold;
  color: #4caf50;
  margin: 0;
}

.stat-role {
  font-size: 24px;
  font-weight: bold;
  color: #ff9800;
  margin: 0;
}

.admin-sections {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 20px;
}

.section {
  background: white;
  padding: 30px;
  border-radius: 10px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.section h2 {
  margin: 0 0 15px 0;
  color: #333;
  font-size: 22px;
}

.section p {
  color: #666;
  line-height: 1.6;
  margin-bottom: 20px;
}

.action-btn {
  padding: 12px 24px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 14px;
  transition: background 0.3s;
  width: 100%;
}

.action-btn:hover {
  background: #5568d3;
}
</style>

