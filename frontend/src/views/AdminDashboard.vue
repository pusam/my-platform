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
          <h2>👥 사용자 관리</h2>
          <p>모든 사용자의 권한과 상태를 관리할 수 있습니다.</p>
          <button @click="goToUserManagement" class="action-btn">사용자 관리</button>
        </div>

        <div class="section">
          <h2>📝 활동 로그</h2>
          <p>사용자 활동 내역과 시스템 이벤트를 확인합니다.</p>
          <button @click="goToActivityLogs" class="action-btn">로그 보기</button>
        </div>
      </div>
      </div>
    </div>
  </div>
</template>

<script>
import { adminAPI } from '../utils/api';

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
    }
  },
  mounted() {
    this.username = localStorage.getItem('username') || 'Admin'
    this.loadStats()
    this.loadServerStatus()
  },
  beforeUnmount() {
    this.stopAutoRefresh()
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
    },
    goToBoard() {
      this.$router.push('/board')
    },
    goToUserApproval() {
      this.$router.push('/user-approval')
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

