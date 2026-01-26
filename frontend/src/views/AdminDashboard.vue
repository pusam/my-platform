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
          <p class="stat-status" :class="serverStatus ? 'online' : 'offline'">
            {{ serverStatus ? '정상' : '확인 중...' }}
          </p>
        </div>
        <div class="stat-card">
          <h3>권한</h3>
          <p class="stat-role">관리자</p>
        </div>
      </div>

      <!-- 서버 상태 모니터링 섹션 -->
      <div class="server-monitor-section">
        <div class="section-header">
          <h2>서버 상태 모니터링</h2>
          <div class="monitor-actions">
            <label class="auto-refresh-label">
              <input type="checkbox" v-model="autoRefresh" @change="toggleAutoRefresh" />
              자동 새로고침 (5초)
            </label>
            <button @click="loadServerStatus" class="refresh-btn" :disabled="loadingStatus">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" :class="{ spinning: loadingStatus }">
                <polyline points="23 4 23 10 17 10"/>
                <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
              </svg>
              새로고침
            </button>
          </div>
        </div>

        <div v-if="serverStatus" class="monitor-grid">
          <!-- CPU 정보 -->
          <div class="monitor-card">
            <div class="monitor-header">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="4" y="4" width="16" height="16" rx="2" ry="2"/>
                <rect x="9" y="9" width="6" height="6"/>
                <line x1="9" y1="1" x2="9" y2="4"/>
                <line x1="15" y1="1" x2="15" y2="4"/>
                <line x1="9" y1="20" x2="9" y2="23"/>
                <line x1="15" y1="20" x2="15" y2="23"/>
                <line x1="20" y1="9" x2="23" y2="9"/>
                <line x1="20" y1="14" x2="23" y2="14"/>
                <line x1="1" y1="9" x2="4" y2="9"/>
                <line x1="1" y1="14" x2="4" y2="14"/>
              </svg>
              <h3>CPU</h3>
            </div>
            <div class="monitor-content">
              <div class="usage-bar-container">
                <div class="usage-bar" :style="{ width: serverStatus.cpu.systemCpuUsage + '%' }"
                     :class="getUsageClass(serverStatus.cpu.systemCpuUsage)"></div>
              </div>
              <div class="usage-value">{{ serverStatus.cpu.systemCpuUsage.toFixed(1) }}%</div>
              <div class="monitor-details">
                <p>코어 수: {{ serverStatus.cpu.availableProcessors }}</p>
                <p>프로세스 CPU: {{ serverStatus.cpu.processCpuUsage.toFixed(1) }}%</p>
              </div>
            </div>
          </div>

          <!-- 시스템 메모리 -->
          <div class="monitor-card">
            <div class="monitor-header">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="2" y="6" width="20" height="12" rx="2"/>
                <line x1="6" y1="10" x2="6" y2="14"/>
                <line x1="10" y1="10" x2="10" y2="14"/>
                <line x1="14" y1="10" x2="14" y2="14"/>
                <line x1="18" y1="10" x2="18" y2="14"/>
              </svg>
              <h3>시스템 메모리</h3>
            </div>
            <div class="monitor-content">
              <div class="usage-bar-container">
                <div class="usage-bar" :style="{ width: serverStatus.memory.usagePercent + '%' }"
                     :class="getUsageClass(serverStatus.memory.usagePercent)"></div>
              </div>
              <div class="usage-value">{{ serverStatus.memory.usagePercent.toFixed(1) }}%</div>
              <div class="monitor-details">
                <p>사용 중: {{ formatBytes(serverStatus.memory.usedPhysicalMemory) }}</p>
                <p>전체: {{ formatBytes(serverStatus.memory.totalPhysicalMemory) }}</p>
              </div>
            </div>
          </div>

          <!-- JVM 메모리 -->
          <div class="monitor-card">
            <div class="monitor-header">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M18 20V10"/>
                <path d="M12 20V4"/>
                <path d="M6 20v-6"/>
              </svg>
              <h3>JVM 힙 메모리</h3>
            </div>
            <div class="monitor-content">
              <div class="usage-bar-container">
                <div class="usage-bar" :style="{ width: serverStatus.jvm.heapUsagePercent + '%' }"
                     :class="getUsageClass(serverStatus.jvm.heapUsagePercent)"></div>
              </div>
              <div class="usage-value">{{ serverStatus.jvm.heapUsagePercent.toFixed(1) }}%</div>
              <div class="monitor-details">
                <p>사용 중: {{ formatBytes(serverStatus.jvm.heapUsed) }}</p>
                <p>최대: {{ formatBytes(serverStatus.jvm.heapMax) }}</p>
                <p>가동 시간: {{ serverStatus.jvm.uptime }}</p>
              </div>
            </div>
          </div>

          <!-- 디스크 -->
          <div class="monitor-card" v-for="(disk, index) in serverStatus.disk" :key="index">
            <div class="monitor-header">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <ellipse cx="12" cy="5" rx="9" ry="3"/>
                <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/>
                <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/>
              </svg>
              <h3>디스크 ({{ disk.path }})</h3>
            </div>
            <div class="monitor-content">
              <div class="usage-bar-container">
                <div class="usage-bar" :style="{ width: disk.usagePercent + '%' }"
                     :class="getUsageClass(disk.usagePercent)"></div>
              </div>
              <div class="usage-value">{{ disk.usagePercent.toFixed(1) }}%</div>
              <div class="monitor-details">
                <p>사용 중: {{ formatBytes(disk.usedSpace) }}</p>
                <p>전체: {{ formatBytes(disk.totalSpace) }}</p>
                <p>여유: {{ formatBytes(disk.freeSpace) }}</p>
              </div>
            </div>
          </div>
        </div>

        <!-- 시스템 정보 -->
        <div v-if="serverStatus && serverStatus.system" class="system-info-card">
          <h3>시스템 정보</h3>
          <div class="system-info-grid">
            <div class="info-item">
              <span class="info-label">OS</span>
              <span class="info-value">{{ serverStatus.system.osName }} {{ serverStatus.system.osVersion }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">아키텍처</span>
              <span class="info-value">{{ serverStatus.system.osArch }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">Java 버전</span>
              <span class="info-value">{{ serverStatus.system.javaVersion }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">JVM</span>
              <span class="info-value">{{ serverStatus.system.jvmName }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">서버 시작</span>
              <span class="info-value">{{ formatDateTime(serverStatus.system.startTime) }}</span>
            </div>
          </div>
        </div>

        <div v-if="!serverStatus && !loadingStatus" class="monitor-error">
          서버 상태를 불러올 수 없습니다.
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
          <h2>👥 사용자 관리</h2>
          <p>모든 사용자의 권한과 상태를 관리할 수 있습니다.</p>
          <button @click="goToUserManagement" class="action-btn">사용자 관리</button>
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
          <h2>📝 활동 로그</h2>
          <p>사용자 활동 내역과 시스템 이벤트를 확인합니다.</p>
          <button @click="goToActivityLogs" class="action-btn">로그 보기</button>
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
import { adminAPI } from '../utils/api';

import apiClient from '../utils/api'

export default {
  name: 'AdminDashboard',
  data() {
    return {
      username: '',
      stats: {
        totalUsers: 0,
        activeUsers: 0
      },
      serverStatus: null,
      loadingStatus: false,
      autoRefresh: false,
      refreshInterval: null
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
    this.loadServerStatus()
  },
  beforeUnmount() {
    this.stopAutoRefresh()

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
      try {
        const response = await adminAPI.getUserStats()
        if (response.data.success) {
          this.stats = response.data.data
        }
      } catch (error) {
        console.error('Failed to load stats:', error)
        this.stats.totalUsers = 0
        this.stats.activeUsers = 0
      }
    },
    async loadServerStatus() {
      try {
        this.loadingStatus = true
        const response = await adminAPI.getServerStatus()
        if (response.data.success) {
          this.serverStatus = response.data.data
        }
      } catch (error) {
        console.error('Failed to load server status:', error)
        this.serverStatus = null
      } finally {
        this.loadingStatus = false
      }
    },
    toggleAutoRefresh() {
      if (this.autoRefresh) {
        this.startAutoRefresh()
      } else {
        this.stopAutoRefresh()
      }
    },
    startAutoRefresh() {
      this.refreshInterval = setInterval(() => {
        this.loadServerStatus()
      }, 5000)
    },
    stopAutoRefresh() {
      if (this.refreshInterval) {
        clearInterval(this.refreshInterval)
        this.refreshInterval = null
      }
    },
    getUsageClass(percent) {
      if (percent >= 90) return 'critical'
      if (percent >= 70) return 'warning'
      return 'normal'
    },
    formatBytes(bytes) {
      if (bytes === 0) return '0 Bytes'
      const k = 1024
      const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB']
      const i = Math.floor(Math.log(bytes) / Math.log(k))
      return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
    },
    formatDateTime(isoString) {
      if (!isoString) return ''
      const date = new Date(isoString)
      return date.toLocaleString('ko-KR')
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
    goToUserManagement() {
      this.$router.push('/admin/users')
    },
    goToActivityLogs() {
      this.$router.push('/admin/logs')
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

/* 서버 모니터링 섹션 */
.server-monitor-section {
  background: white;
  border-radius: 12px;
  padding: 24px;
  margin-bottom: 30px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.server-monitor-section .section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  flex-wrap: wrap;
  gap: 16px;
}

.server-monitor-section .section-header h2 {
  margin: 0;
  font-size: 20px;
  color: #333;
}

.monitor-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.auto-refresh-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  color: #666;
  cursor: pointer;
}

.auto-refresh-label input {
  cursor: pointer;
}

.refresh-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s;
}

.refresh-btn:hover:not(:disabled) {
  background: #5568d3;
}

.refresh-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.refresh-btn svg.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.monitor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 20px;
  margin-bottom: 24px;
}

.monitor-card {
  background: #f8f9fa;
  border-radius: 12px;
  padding: 20px;
  border: 1px solid #e9ecef;
}

.monitor-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.monitor-header svg {
  color: #667eea;
}

.monitor-header h3 {
  margin: 0;
  font-size: 16px;
  color: #333;
}

.monitor-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.usage-bar-container {
  height: 12px;
  background: #e9ecef;
  border-radius: 6px;
  overflow: hidden;
}

.usage-bar {
  height: 100%;
  border-radius: 6px;
  transition: width 0.3s ease;
}

.usage-bar.normal {
  background: linear-gradient(90deg, #4caf50, #66bb6a);
}

.usage-bar.warning {
  background: linear-gradient(90deg, #ff9800, #ffb74d);
}

.usage-bar.critical {
  background: linear-gradient(90deg, #f44336, #e57373);
}

.usage-value {
  font-size: 24px;
  font-weight: bold;
  color: #333;
}

.monitor-details {
  border-top: 1px solid #e9ecef;
  padding-top: 12px;
}

.monitor-details p {
  margin: 0 0 6px 0;
  font-size: 13px;
  color: #666;
}

.monitor-details p:last-child {
  margin-bottom: 0;
}

/* 시스템 정보 카드 */
.system-info-card {
  background: #f8f9fa;
  border-radius: 12px;
  padding: 20px;
  border: 1px solid #e9ecef;
}

.system-info-card h3 {
  margin: 0 0 16px 0;
  font-size: 16px;
  color: #333;
}

.system-info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.info-label {
  font-size: 12px;
  color: #999;
  text-transform: uppercase;
}

.info-value {
  font-size: 14px;
  color: #333;
  font-weight: 500;
}

.monitor-error {
  text-align: center;
  padding: 40px;
  color: #999;
}

/* 상태 카드 온라인/오프라인 */
.stat-status.online {
  color: #4caf50;
}

.stat-status.offline {
  color: #ff9800;
}

/* 반응형 */
@media (max-width: 768px) {
  .server-monitor-section .section-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .monitor-actions {
    width: 100%;
    justify-content: space-between;
  }

  .monitor-grid {
    grid-template-columns: 1fr;
  }

  .system-info-grid {
    grid-template-columns: 1fr;
  }
}
</style>


