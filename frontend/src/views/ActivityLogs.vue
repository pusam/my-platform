<template>
  <div class="page-container">
    <div class="page-content">
      <header class="common-header">
        <h1>활동 로그</h1>
        <div class="header-actions">
          <button @click="goBack" class="btn btn-back">돌아가기</button>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

      <div class="logs-content">
        <!-- 필터 -->
        <div class="filters-section">
          <div class="filter-group">
            <label>사용자</label>
            <input type="text" v-model="filters.username" placeholder="사용자명" @keyup.enter="loadLogs" />
          </div>
          <div class="filter-group">
            <label>액션 유형</label>
            <select v-model="filters.actionType">
              <option value="">전체</option>
              <option value="LOGIN">로그인</option>
              <option value="LOGOUT">로그아웃</option>
              <option value="ROLE_CHANGE">권한 변경</option>
              <option value="STATUS_CHANGE">상태 변경</option>
              <option value="USER_DELETE">사용자 삭제</option>
            </select>
          </div>
          <button @click="loadLogs" class="btn-search">검색</button>
          <button @click="resetFilters" class="btn-reset">초기화</button>
        </div>

        <!-- 로그 목록 -->
        <div class="logs-section">
          <div class="section-header">
            <h2>활동 기록</h2>
            <span class="log-count">총 {{ totalElements }}건</span>
          </div>

          <div v-if="loading" class="loading">로딩 중...</div>

          <div v-else-if="logs.length === 0" class="empty-state">
            기록된 활동 로그가 없습니다.
          </div>

          <div v-else class="logs-list">
            <div v-for="log in logs" :key="log.id" class="log-item" :class="getLogClass(log.actionType)">
              <div class="log-icon">
                <svg v-if="log.actionType === 'LOGIN'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/>
                  <polyline points="10 17 15 12 10 7"/>
                  <line x1="15" y1="12" x2="3" y2="12"/>
                </svg>
                <svg v-else-if="log.actionType === 'LOGOUT'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
                  <polyline points="16 17 21 12 16 7"/>
                  <line x1="21" y1="12" x2="9" y2="12"/>
                </svg>
                <svg v-else-if="log.actionType === 'ROLE_CHANGE'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
                </svg>
                <svg v-else-if="log.actionType === 'STATUS_CHANGE'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="10"/>
                  <polyline points="12 6 12 12 16 14"/>
                </svg>
                <svg v-else-if="log.actionType === 'USER_DELETE'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/>
                  <circle cx="8.5" cy="7" r="4"/>
                  <line x1="18" y1="8" x2="23" y2="13"/>
                  <line x1="23" y1="8" x2="18" y2="13"/>
                </svg>
                <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="10"/>
                  <line x1="12" y1="16" x2="12" y2="12"/>
                  <line x1="12" y1="8" x2="12.01" y2="8"/>
                </svg>
              </div>
              <div class="log-content">
                <div class="log-main">
                  <span class="log-username">{{ log.username }}</span>
                  <span class="log-action-type">{{ getActionTypeLabel(log.actionType) }}</span>
                </div>
                <p class="log-description">{{ log.description }}</p>
                <div class="log-meta">
                  <span class="log-time">{{ formatDateTime(log.createdAt) }}</span>
                  <span v-if="log.ipAddress" class="log-ip">IP: {{ log.ipAddress }}</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 페이지네이션 -->
          <div v-if="totalPages > 1" class="pagination">
            <button @click="goToPage(currentPage - 1)" :disabled="currentPage === 0" class="page-btn">
              이전
            </button>
            <span class="page-info">{{ currentPage + 1 }} / {{ totalPages }}</span>
            <button @click="goToPage(currentPage + 1)" :disabled="currentPage >= totalPages - 1" class="page-btn">
              다음
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { adminAPI } from '../utils/api';
import { UserManager } from '../utils/auth';

export default {
  name: 'ActivityLogs',
  data() {
    return {
      logs: [],
      loading: false,
      filters: {
        username: '',
        actionType: ''
      },
      currentPage: 0,
      totalPages: 0,
      totalElements: 0,
      pageSize: 20
    }
  },
  mounted() {
    this.loadLogs();
  },
  methods: {
    async loadLogs() {
      try {
        this.loading = true;
        const response = await adminAPI.getLogs(
          this.currentPage,
          this.pageSize,
          this.filters.username || null,
          this.filters.actionType || null
        );
        if (response.data.success) {
          const data = response.data.data;
          this.logs = data.content || [];
          this.totalPages = data.totalPages || 0;
          this.totalElements = data.totalElements || 0;
        }
      } catch (error) {
        console.error('Failed to load logs:', error);
        alert('로그를 불러오는데 실패했습니다.');
      } finally {
        this.loading = false;
      }
    },
    resetFilters() {
      this.filters = {
        username: '',
        actionType: ''
      };
      this.currentPage = 0;
      this.loadLogs();
    },
    goToPage(page) {
      if (page >= 0 && page < this.totalPages) {
        this.currentPage = page;
        this.loadLogs();
      }
    },
    getActionTypeLabel(type) {
      const labels = {
        'LOGIN': '로그인',
        'LOGOUT': '로그아웃',
        'ROLE_CHANGE': '권한 변경',
        'STATUS_CHANGE': '상태 변경',
        'USER_DELETE': '사용자 삭제'
      };
      return labels[type] || type;
    },
    getLogClass(actionType) {
      const classes = {
        'LOGIN': 'login',
        'LOGOUT': 'logout',
        'ROLE_CHANGE': 'role',
        'STATUS_CHANGE': 'status',
        'USER_DELETE': 'delete'
      };
      return classes[actionType] || '';
    },
    formatDateTime(dateStr) {
      if (!dateStr) return '';
      const date = new Date(dateStr);
      return date.toLocaleString('ko-KR');
    },
    goBack() {
      this.$router.back();
    },
    logout() {
      UserManager.logout();
      this.$router.push('/login');
    }
  }
}
</script>

<style scoped>
@import '../assets/css/common.css';

.logs-content {
  max-width: 1000px;
  margin: 0 auto;
}

.filters-section {
  background: white;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 24px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
  display: flex;
  gap: 16px;
  align-items: flex-end;
  flex-wrap: wrap;
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.filter-group label {
  font-size: 13px;
  color: #666;
  font-weight: 500;
}

.filter-group input,
.filter-group select {
  padding: 10px 14px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
  min-width: 150px;
}

.btn-search {
  padding: 10px 20px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
}

.btn-search:hover {
  background: #5568d3;
}

.btn-reset {
  padding: 10px 20px;
  background: #f1f3f5;
  color: #666;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
}

.btn-reset:hover {
  background: #e9ecef;
}

.logs-section {
  background: white;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #eee;
}

.section-header h2 {
  margin: 0;
  font-size: 18px;
  color: #333;
}

.log-count {
  font-size: 14px;
  color: #666;
}

.loading, .empty-state {
  text-align: center;
  padding: 40px;
  color: #666;
}

.logs-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.log-item {
  display: flex;
  gap: 16px;
  padding: 16px;
  background: #f8f9fa;
  border-radius: 10px;
  border-left: 4px solid #ccc;
}

.log-item.login {
  border-left-color: #4caf50;
}

.log-item.logout {
  border-left-color: #9e9e9e;
}

.log-item.role {
  border-left-color: #ff9800;
}

.log-item.status {
  border-left-color: #2196f3;
}

.log-item.delete {
  border-left-color: #f44336;
}

.log-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: white;
  flex-shrink: 0;
}

.log-item.login .log-icon {
  color: #4caf50;
}

.log-item.logout .log-icon {
  color: #9e9e9e;
}

.log-item.role .log-icon {
  color: #ff9800;
}

.log-item.status .log-icon {
  color: #2196f3;
}

.log-item.delete .log-icon {
  color: #f44336;
}

.log-content {
  flex: 1;
}

.log-main {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.log-username {
  font-weight: 600;
  color: #333;
}

.log-action-type {
  font-size: 12px;
  padding: 2px 8px;
  background: rgba(0, 0, 0, 0.1);
  border-radius: 4px;
  color: #666;
}

.log-description {
  margin: 0 0 8px 0;
  font-size: 14px;
  color: #555;
}

.log-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #999;
}

.pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid #eee;
}

.page-btn {
  padding: 8px 16px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.page-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.page-info {
  font-size: 14px;
  color: #666;
}

@media (max-width: 768px) {
  .filters-section {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-group input,
  .filter-group select {
    width: 100%;
  }

  .log-item {
    flex-direction: column;
    gap: 12px;
  }

  .log-icon {
    width: 32px;
    height: 32px;
  }
}
</style>
