<template>
  <div class="page-container">
    <div class="page-content">
      <header class="common-header">
        <h1>🔄 배치 잡 모니터링</h1>
        <div class="header-actions">
          <button @click="goBack" class="btn btn-back">← 관리자 대시보드</button>
        </div>
      </header>

      <div class="batch-content">
        <!-- 요약 카드 -->
        <div class="summary-grid">
          <div class="summary-card total">
            <div class="summary-icon">📊</div>
            <div class="summary-info">
              <h3>오늘 총 실행</h3>
              <p class="summary-number">{{ summary.totalToday || 0 }}</p>
            </div>
          </div>
          <div class="summary-card success">
            <div class="summary-icon">✅</div>
            <div class="summary-info">
              <h3>성공</h3>
              <p class="summary-number">{{ summary.successToday || 0 }}</p>
            </div>
          </div>
          <div class="summary-card failed">
            <div class="summary-icon">❌</div>
            <div class="summary-info">
              <h3>실패</h3>
              <p class="summary-number">{{ summary.failedToday || 0 }}</p>
            </div>
          </div>
          <div class="summary-card running">
            <div class="summary-icon">⏳</div>
            <div class="summary-info">
              <h3>실행중</h3>
              <p class="summary-number">{{ summary.runningNow || 0 }}</p>
            </div>
          </div>
        </div>

        <!-- 필터 -->
        <div class="filter-bar">
          <div class="filter-group">
            <label>배치 이름</label>
            <select v-model="selectedJobName" @change="loadExecutions">
              <option value="">전체</option>
              <option v-for="name in jobNames" :key="name" :value="name">{{ name }}</option>
            </select>
          </div>
          <div class="filter-actions">
            <label class="auto-refresh-toggle">
              <input type="checkbox" v-model="autoRefresh" @change="toggleAutoRefresh" />
              자동 새로고침 (10초)
            </label>
            <button @click="refreshAll" class="refresh-btn" :disabled="loading">
              {{ loading ? '로딩...' : '새로고침' }}
            </button>
          </div>
        </div>

        <!-- 실행 이력 테이블 -->
        <div class="table-container">
          <table class="batch-table">
            <thead>
              <tr>
                <th>배치명</th>
                <th>클래스</th>
                <th>시작시간</th>
                <th>종료시간</th>
                <th>소요시간</th>
                <th>상태</th>
                <th>에러</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="executions.length === 0">
                <td colspan="7" class="empty-row">실행 이력이 없습니다.</td>
              </tr>
              <tr v-for="exec in executions" :key="exec.id">
                <td class="job-name">{{ exec.jobName }}</td>
                <td class="job-class" :title="exec.jobClass">{{ shortClassName(exec.jobClass) }}</td>
                <td>{{ formatDateTime(exec.startedAt) }}</td>
                <td>{{ exec.finishedAt ? formatDateTime(exec.finishedAt) : '-' }}</td>
                <td>{{ formatDuration(exec.durationMs) }}</td>
                <td>
                  <span class="status-badge" :class="exec.status.toLowerCase()">
                    {{ statusLabel(exec.status) }}
                  </span>
                </td>
                <td class="error-cell">
                  <span v-if="exec.errorMessage" class="error-text" :title="exec.errorMessage">
                    {{ truncate(exec.errorMessage, 50) }}
                  </span>
                  <span v-else class="no-error">-</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 페이지네이션 -->
        <div class="pagination" v-if="totalPages > 1">
          <button @click="goToPage(currentPage - 1)" :disabled="currentPage === 0" class="page-btn">이전</button>
          <span class="page-info">{{ currentPage + 1 }} / {{ totalPages }}</span>
          <button @click="goToPage(currentPage + 1)" :disabled="currentPage >= totalPages - 1" class="page-btn">다음</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { batchJobAPI } from '../../utils/api';

export default {
  name: 'BatchJobMonitor',
  data() {
    return {
      summary: {},
      executions: [],
      jobNames: [],
      selectedJobName: '',
      currentPage: 0,
      totalPages: 0,
      pageSize: 50,
      loading: false,
      autoRefresh: true,
      refreshTimer: null
    }
  },
  mounted() {
    this.refreshAll()
    this.startAutoRefresh()
  },
  beforeUnmount() {
    this.stopAutoRefresh()
  },
  methods: {
    async refreshAll() {
      this.loading = true
      try {
        await Promise.all([
          this.loadSummary(),
          this.loadExecutions(),
          this.loadJobNames()
        ])
      } finally {
        this.loading = false
      }
    },
    async loadSummary() {
      try {
        const res = await batchJobAPI.getSummary()
        if (res.data.success) {
          this.summary = res.data.data
        }
      } catch (e) {
        console.error('배치 요약 로드 실패:', e)
      }
    },
    async loadExecutions() {
      try {
        const params = { page: this.currentPage, size: this.pageSize }
        if (this.selectedJobName) {
          params.jobName = this.selectedJobName
        }
        const res = await batchJobAPI.getExecutions(params)
        if (res.data.success) {
          this.executions = res.data.data.content || []
          this.totalPages = res.data.data.totalPages || 0
        }
      } catch (e) {
        console.error('배치 이력 로드 실패:', e)
      }
    },
    async loadJobNames() {
      try {
        const res = await batchJobAPI.getJobNames()
        if (res.data.success) {
          this.jobNames = res.data.data || []
        }
      } catch (e) {
        console.error('배치 이름 로드 실패:', e)
      }
    },
    goToPage(page) {
      if (page >= 0 && page < this.totalPages) {
        this.currentPage = page
        this.loadExecutions()
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
      this.stopAutoRefresh()
      if (this.autoRefresh) {
        this.refreshTimer = setInterval(() => {
          this.refreshAll()
        }, 10000)
      }
    },
    stopAutoRefresh() {
      if (this.refreshTimer) {
        clearInterval(this.refreshTimer)
        this.refreshTimer = null
      }
    },
    shortClassName(fullClass) {
      if (!fullClass) return '-'
      const parts = fullClass.split('.')
      return parts[parts.length - 1]
    },
    formatDateTime(dt) {
      if (!dt) return '-'
      const d = new Date(dt)
      const pad = n => String(n).padStart(2, '0')
      return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
    },
    formatDuration(ms) {
      if (ms == null) return '-'
      if (ms < 1000) return ms + 'ms'
      if (ms < 60000) return (ms / 1000).toFixed(1) + '초'
      return (ms / 60000).toFixed(1) + '분'
    },
    statusLabel(status) {
      const map = { SUCCESS: '성공', FAILED: '실패', RUNNING: '실행중' }
      return map[status] || status
    },
    truncate(str, len) {
      if (!str) return ''
      return str.length > len ? str.substring(0, len) + '...' : str
    },
    goBack() {
      this.$router.push('/admin')
    }
  }
}
</script>

<style scoped>
@import '../../assets/css/common.css';

.batch-content {
  max-width: 1400px;
  margin: 0 auto;
}

/* 요약 카드 */
.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.summary-card {
  background: white;
  padding: 20px;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  display: flex;
  align-items: center;
  gap: 12px;
  transition: transform 0.2s, box-shadow 0.2s;
}

.summary-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.summary-card.total { border-left: 4px solid #2196F3; }
.summary-card.success { border-left: 4px solid #4CAF50; }
.summary-card.failed { border-left: 4px solid #F44336; }
.summary-card.running { border-left: 4px solid #FF9800; }

.summary-icon {
  font-size: 36px;
  opacity: 0.8;
}

.summary-info h3 {
  margin: 0 0 4px 0;
  font-size: 13px;
  color: #666;
  font-weight: 500;
}

.summary-number {
  margin: 0;
  font-size: 28px;
  font-weight: bold;
  color: #333;
}

/* 필터 바 */
.filter-bar {
  background: white;
  padding: 16px 20px;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
  flex-wrap: wrap;
  gap: 12px;
}

.filter-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-group label {
  font-size: 14px;
  color: #555;
  font-weight: 500;
}

.filter-group select {
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
  color: #333;
  background: white;
  min-width: 200px;
}

.filter-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.auto-refresh-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #666;
  cursor: pointer;
}

.auto-refresh-toggle input[type="checkbox"] {
  cursor: pointer;
}

.refresh-btn {
  padding: 8px 16px;
  background: #2196F3;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
  transition: background 0.2s;
}

.refresh-btn:hover {
  background: #1976D2;
}

.refresh-btn:disabled {
  background: #90CAF9;
  cursor: not-allowed;
}

/* 테이블 */
.table-container {
  background: white;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  overflow-x: auto;
}

.batch-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.batch-table thead {
  background: #f8f9fa;
}

.batch-table th {
  padding: 12px 16px;
  text-align: left;
  font-weight: 600;
  color: #555;
  border-bottom: 2px solid #e9ecef;
  white-space: nowrap;
}

.batch-table td {
  padding: 10px 16px;
  border-bottom: 1px solid #f1f3f5;
  color: #333;
}

.batch-table tbody tr:hover {
  background: #f8f9fa;
}

.job-name {
  font-weight: 500;
  color: #2196F3;
}

.job-class {
  color: #888;
  font-size: 13px;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.empty-row {
  text-align: center;
  color: #999;
  padding: 40px 16px !important;
}

/* 상태 배지 */
.status-badge {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 600;
}

.status-badge.success {
  background: #E8F5E9;
  color: #2E7D32;
}

.status-badge.failed {
  background: #FFEBEE;
  color: #C62828;
}

.status-badge.running {
  background: #FFF3E0;
  color: #E65100;
}

/* 에러 메시지 */
.error-cell {
  max-width: 200px;
}

.error-text {
  color: #C62828;
  font-size: 12px;
  cursor: help;
}

.no-error {
  color: #ccc;
}

/* 페이지네이션 */
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 20px;
  padding: 16px;
}

.page-btn {
  padding: 8px 16px;
  background: #2196F3;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  transition: background 0.2s;
}

.page-btn:hover {
  background: #1976D2;
}

.page-btn:disabled {
  background: #e0e0e0;
  color: #999;
  cursor: not-allowed;
}

.page-info {
  font-size: 14px;
  color: #666;
}

/* 뒤로가기 */
.btn-back {
  padding: 8px 16px;
  background: rgba(255, 255, 255, 0.2);
  color: white;
  border: 1px solid rgba(255, 255, 255, 0.3);
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  transition: background 0.2s;
}

.btn-back:hover {
  background: rgba(255, 255, 255, 0.3);
}

/* 반응형 */
@media (max-width: 768px) {
  .summary-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-actions {
    justify-content: space-between;
  }

  .batch-table {
    font-size: 12px;
  }

  .batch-table th,
  .batch-table td {
    padding: 8px 10px;
  }
}
</style>
