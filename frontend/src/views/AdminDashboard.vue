<template>
  <div class="page-container">
    <div class="page-content">
      <header class="common-header">
        <h1>⚙️ 관리자 대시보드</h1>
        <div class="header-actions">
          <div class="header-user">
            <span class="admin-badge">ADMIN</span>
            <span>{{ username }}</span>
          </div>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

      <div class="dashboard-content">
        <!-- 상단 통계 카드 -->
        <div class="stats-grid">
          <div class="stat-card users">
            <div class="stat-icon">👥</div>
            <div class="stat-info">
              <h3>전체 사용자</h3>
              <p class="stat-number">{{ stats.totalUsers || 0 }}</p>
              <span class="stat-detail">활성: {{ stats.activeUsers || 0 }} / 대기: {{ stats.pendingUsers || 0 }}</span>
            </div>
          </div>

          <div class="stat-card boards">
            <div class="stat-icon">📋</div>
            <div class="stat-info">
              <h3>게시글</h3>
              <p class="stat-number">{{ stats.totalBoards || 0 }}</p>
              <span class="stat-detail">오늘: {{ stats.todayBoards || 0 }}개</span>
            </div>
          </div>

          <div class="stat-card files">
            <div class="stat-icon">📁</div>
            <div class="stat-info">
              <h3>파일</h3>
              <p class="stat-number">{{ stats.totalFiles || 0 }}</p>
              <span class="stat-detail">{{ formatFileSize(stats.totalFileSize) }}</span>
            </div>
          </div>

          <div class="stat-card assets">
            <div class="stat-icon">💰</div>
            <div class="stat-info">
              <h3>자산/거래</h3>
              <p class="stat-number">{{ stats.totalAssets || 0 }}</p>
              <span class="stat-detail">거래: {{ stats.totalTransactions || 0 }}건</span>
            </div>
          </div>

          <div class="stat-card status">
            <div class="stat-icon">🟢</div>
            <div class="stat-info">
              <h3>시스템 상태</h3>
              <p class="stat-status" :class="{ healthy: stats.systemStatus === 'HEALTHY' }">
                {{ stats.systemStatus === 'HEALTHY' ? '정상' : '점검 필요' }}
              </p>
              <span class="stat-detail">가동: {{ stats.serverUptime }}</span>
            </div>
          </div>

          <div class="stat-card admin">
            <div class="stat-icon">👨‍💼</div>
            <div class="stat-info">
              <h3>관리자</h3>
              <p class="stat-number">{{ stats.adminCount || 0 }}</p>
              <span class="stat-detail">권한: 최고 관리자</span>
            </div>
          </div>
        </div>

        <!-- 관리 섹션 -->
        <div class="admin-sections">
          <div class="section highlight" v-if="stats.pendingUsers > 0">
            <div class="section-header">
              <h2>⚠️ 승인 대기 중</h2>
              <span class="badge">{{ stats.pendingUsers }}</span>
            </div>
            <p>{{ stats.pendingUsers }}명의 사용자가 승인을 기다리고 있습니다.</p>
            <button @click="goToUserApproval" class="action-btn primary">즉시 확인</button>
          </div>

          <div class="section">
            <div class="section-header">
              <h2>👥 사용자 관리</h2>
            </div>
            <p>전체 사용자 목록, 권한 변경, 계정 관리</p>
            <div class="action-group">
              <button @click="goToUserApproval" class="action-btn">승인 관리</button>
              <button @click="viewAllUsers" class="action-btn">전체 사용자</button>
            </div>
          </div>

          <div class="section">
            <div class="section-header">
              <h2>📋 게시판 관리</h2>
            </div>
            <p>게시글 모니터링, 신고 관리, 통계 확인</p>
            <div class="action-group">
              <button @click="goToBoard" class="action-btn">게시판 이동</button>
              <button @click="viewBoardStats" class="action-btn">통계 보기</button>
            </div>
          </div>

          <div class="section">
            <div class="section-header">
              <h2>📊 주식 API 관리</h2>
            </div>
            <p>KIS API, Reddit API 사용 현황 및 캐시 관리</p>
            <div class="action-group">
              <button @click="viewApiStats" class="action-btn">API 통계</button>
              <button @click="clearCache" class="action-btn">캐시 초기화</button>
            </div>
          </div>

          <div class="section">
            <div class="section-header">
              <h2>📁 파일 관리</h2>
            </div>
            <p>업로드된 파일 관리 및 용량 모니터링</p>
            <div class="action-group">
              <button @click="viewFileManager" class="action-btn">파일 목록</button>
              <button @click="cleanupFiles" class="action-btn">정리</button>
            </div>
          </div>

          <div class="section">
            <div class="section-header">
              <h2>⚙️ 시스템 설정</h2>
            </div>
            <p>전역 설정, 보안 정책, 백업 관리</p>
            <div class="action-group">
              <button @click="viewSettings" class="action-btn">설정</button>
              <button @click="viewLogs" class="action-btn">로그</button>
            </div>
          </div>
        </div>

        <!-- 최근 활동 -->
        <div class="recent-activity">
          <h2>📊 최근 활동</h2>
          <div class="activity-list">
            <div class="activity-item">
              <span class="activity-icon">👤</span>
              <span class="activity-text">최근 가입: {{ formatDate(stats.lastSignupDate) }}</span>
            </div>
            <div class="activity-item">
              <span class="activity-icon">📝</span>
              <span class="activity-text">오늘 작성된 게시글: {{ stats.todayBoards }}개</span>
            </div>
            <div class="activity-item">
              <span class="activity-icon">🔑</span>
              <span class="activity-text">오늘 로그인: {{ stats.todayLogins }}회</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import apiClient from '../utils/api'

export default {
  name: 'AdminDashboard',
  data() {
    return {
      username: '',
      stats: {
        totalUsers: 0,
        activeUsers: 0,
        pendingUsers: 0,
        adminCount: 0,
        totalBoards: 0,
        todayBoards: 0,
        totalFiles: 0,
        totalFileSize: 0,
        totalAssets: 0,
        totalTransactions: 0,
        todayLogins: 0,
        lastSignupDate: null,
        serverUptime: '0분',
        systemStatus: 'HEALTHY'
      },
      loading: false
    }
  },
  mounted() {
    this.username = localStorage.getItem('username') || 'Admin'
    this.loadStats()

    // 30초마다 자동 새로고침
    this.statsInterval = setInterval(() => {
      this.loadStats()
    }, 30000)
  },
  beforeUnmount() {
    if (this.statsInterval) {
      clearInterval(this.statsInterval)
    }
  },
  methods: {
    async loadStats() {
      this.loading = true
      try {
        const response = await apiClient.get('/admin/stats')
        if (response.data.success) {
          this.stats = response.data.data
        }
      } catch (error) {
        console.error('통계 로딩 실패:', error)
      } finally {
        this.loading = false
      }
    },
    formatFileSize(sizeInMB) {
      if (!sizeInMB) return '0 MB'
      if (sizeInMB >= 1024) {
        return `${(sizeInMB / 1024).toFixed(2)} GB`
      }
      return `${sizeInMB.toFixed(2)} MB`
    },
    formatDate(dateString) {
      if (!dateString) return '없음'
      const date = new Date(dateString)
      return date.toLocaleString('ko-KR')
    },
    goToBoard() {
      this.$router.push('/board')
    },
    goToUserApproval() {
      this.$router.push('/user-approval')
    },
    viewAllUsers() {
      alert('전체 사용자 관리 페이지 (개발 예정)')
    },
    viewBoardStats() {
      alert('게시판 통계 페이지 (개발 예정)')
    },
    async viewApiStats() {
      try {
        const response = await apiClient.get('/admin/api-stats')
        if (response.data.success) {
          const stats = response.data.data
          const caches = stats.caches

          let message = '=== API 캐시 상태 ===\n\n'
          message += '📊 투자 정보 API:\n'
          message += `- 투자자 매매동향: ${caches.investorTrend || '비활성'}\n`
          message += `- 연속 매수 종목: ${caches.continuousBuy || '비활성'}\n`
          message += `- 수급 급등 종목: ${caches.supplySurge || '비활성'}\n\n`

          message += '🌐 Reddit 주식 정보:\n'
          message += `- 미국 주식: ${caches.redditUSStocks || '비활성'}\n`
          message += `- 한국 주식: ${caches.redditKRStocks || '비활성'}\n`
          message += `- 게시글: ${caches.redditPosts || '비활성'}\n\n`

          message += '💰 금/은 시세:\n'
          message += `- 금 시세: ${caches.goldPrice || '비활성'}\n`
          message += `- 은 시세: ${caches.silverPrice || '비활성'}\n\n`

          message += `총 ${stats.totalCaches}개의 캐시 활성\n\n`
          message += `💡 ${stats.message}`

          alert(message)
        }
      } catch (error) {
        console.error('API 통계 로딩 실패:', error)
        alert('API 통계를 불러올 수 없습니다.')
      }
    },
    async clearCache() {
      if (confirm('모든 API 캐시를 초기화하시겠습니까?\n\n다음 API 호출 시 외부 서버에서 최신 데이터를 가져옵니다.\n- KIS API (투자자 매매동향, 연속 매수, 수급 급등)\n- Reddit API (미국/한국 주식 트렌드)\n- 금/은 시세')) {
        try {
          const response = await apiClient.post('/admin/clear-cache')
          if (response.data.success) {
            alert(response.data.message || '캐시가 초기화되었습니다.')
            this.loadStats() // 통계 새로고침
          } else {
            alert(response.data.message || '캐시 초기화에 실패했습니다.')
          }
        } catch (error) {
          console.error('캐시 초기화 실패:', error)
          alert('캐시 초기화에 실패했습니다.')
        }
      }
    },
    viewFileManager() {
      this.$router.push('/file-manager')
    },
    cleanupFiles() {
      alert('파일 정리 기능 (개발 예정)')
    },
    viewSettings() {
      alert('시스템 설정 페이지 (개발 예정)')
    },
    viewLogs() {
      alert('시스템 로그 페이지 (개발 예정)')
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
  max-width: 1400px;
  margin: 0 auto;
}

/* 통계 그리드 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 20px;
  margin-bottom: 30px;
}

.stat-card {
  background: white;
  padding: 25px;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  display: flex;
  align-items: center;
  gap: 15px;
  transition: transform 0.2s, box-shadow 0.2s;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.stat-icon {
  font-size: 48px;
  opacity: 0.8;
}

.stat-info {
  flex: 1;
}

.stat-info h3 {
  margin: 0 0 8px 0;
  font-size: 14px;
  color: #666;
  font-weight: 500;
}

.stat-number {
  margin: 0;
  font-size: 32px;
  font-weight: bold;
  color: #333;
}

.stat-status {
  margin: 0;
  font-size: 24px;
  font-weight: bold;
}

.stat-status.healthy {
  color: #4CAF50;
}

.stat-detail {
  font-size: 12px;
  color: #999;
}

/* 색상 테마 */
.stat-card.users { border-left: 4px solid #2196F3; }
.stat-card.boards { border-left: 4px solid #4CAF50; }
.stat-card.files { border-left: 4px solid #FF9800; }
.stat-card.assets { border-left: 4px solid #9C27B0; }
.stat-card.status { border-left: 4px solid #4CAF50; }
.stat-card.admin { border-left: 4px solid #F44336; }

/* 관리 섹션 */
.admin-sections {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 20px;
  margin-bottom: 30px;
}

.section {
  background: white;
  padding: 25px;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.section.highlight {
  border: 2px solid #FF9800;
  background: linear-gradient(135deg, #FFF3E0 0%, #FFE0B2 100%);
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.section-header h2 {
  margin: 0;
  font-size: 18px;
  color: #333;
}

.badge {
  background: #F44336;
  color: white;
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 14px;
  font-weight: bold;
}

.section p {
  color: #666;
  margin: 0 0 15px 0;
  font-size: 14px;
}

.action-group {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.action-btn {
  padding: 10px 20px;
  background: #2196F3;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
  transition: background 0.2s;
}

.action-btn:hover {
  background: #1976D2;
}

.action-btn.primary {
  background: #FF9800;
  font-size: 16px;
  padding: 12px 24px;
}

.action-btn.primary:hover {
  background: #F57C00;
}

/* 최근 활동 */
.recent-activity {
  background: white;
  padding: 25px;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.recent-activity h2 {
  margin: 0 0 20px 0;
  font-size: 18px;
  color: #333;
}

.activity-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.activity-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  background: #F5F5F5;
  border-radius: 8px;
}

.activity-icon {
  font-size: 24px;
}

.activity-text {
  font-size: 14px;
  color: #666;
}

/* 관리자 배지 */
.admin-badge {
  background: linear-gradient(135deg, #F44336 0%, #E91E63 100%);
  color: white;
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: bold;
  margin-right: 8px;
}
</style>


