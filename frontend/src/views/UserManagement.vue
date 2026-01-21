<template>
  <div class="page-container">
    <div class="page-content">
      <header class="common-header">
        <h1>사용자 관리</h1>
        <div class="header-actions">
          <button @click="goBack" class="btn btn-back">돌아가기</button>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

      <div class="user-management-content">
        <!-- 통계 카드 -->
        <div class="stats-row">
          <div class="stat-card">
            <span class="stat-label">전체 사용자</span>
            <span class="stat-value">{{ stats.totalUsers }}</span>
          </div>
          <div class="stat-card">
            <span class="stat-label">승인된 사용자</span>
            <span class="stat-value active">{{ stats.activeUsers }}</span>
          </div>
        </div>

        <!-- 사용자 목록 -->
        <div class="users-section">
          <div class="section-header">
            <h2>사용자 목록</h2>
            <button @click="loadUsers" class="btn-refresh">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="23 4 23 10 17 10"/>
                <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
              </svg>
              새로고침
            </button>
          </div>

          <div v-if="loading" class="loading">로딩 중...</div>

          <div v-else class="users-table-wrap">
            <table class="users-table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>아이디</th>
                  <th>이름</th>
                  <th>이메일</th>
                  <th>연락처</th>
                  <th>권한</th>
                  <th>상태</th>
                  <th>가입일</th>
                  <th>관리</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="user in users" :key="user.id">
                  <td>{{ user.id }}</td>
                  <td>{{ user.username }}</td>
                  <td>{{ user.name }}</td>
                  <td>{{ user.email }}</td>
                  <td>{{ user.phone }}</td>
                  <td>
                    <select v-model="user.role" @change="updateRole(user)" class="role-select" :class="user.role.toLowerCase()">
                      <option value="USER">일반</option>
                      <option value="ADMIN">관리자</option>
                    </select>
                  </td>
                  <td>
                    <select v-model="user.status" @change="updateStatus(user)" class="status-select" :class="user.status.toLowerCase()">
                      <option value="PENDING">대기</option>
                      <option value="APPROVED">승인</option>
                      <option value="REJECTED">거부</option>
                    </select>
                  </td>
                  <td>{{ formatDate(user.createdAt) }}</td>
                  <td>
                    <button @click="confirmDelete(user)" class="btn-delete">삭제</button>
                  </td>
                </tr>
              </tbody>
            </table>
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
  name: 'UserManagement',
  data() {
    return {
      users: [],
      loading: false,
      stats: {
        totalUsers: 0,
        activeUsers: 0
      }
    }
  },
  mounted() {
    this.loadUsers();
    this.loadStats();
  },
  methods: {
    async loadUsers() {
      try {
        this.loading = true;
        const response = await adminAPI.getAllUsers();
        if (response.data.success) {
          this.users = response.data.data;
        }
      } catch (error) {
        console.error('Failed to load users:', error);
        alert('사용자 목록을 불러오는데 실패했습니다.');
      } finally {
        this.loading = false;
      }
    },
    async loadStats() {
      try {
        const response = await adminAPI.getUserStats();
        if (response.data.success) {
          this.stats = response.data.data;
        }
      } catch (error) {
        console.error('Failed to load stats:', error);
      }
    },
    async updateRole(user) {
      try {
        const response = await adminAPI.updateUserRole(user.id, user.role);
        if (response.data.success) {
          alert('권한이 변경되었습니다.');
        }
      } catch (error) {
        console.error('Failed to update role:', error);
        alert('권한 변경에 실패했습니다.');
        this.loadUsers();
      }
    },
    async updateStatus(user) {
      try {
        const response = await adminAPI.updateUserStatus(user.id, user.status);
        if (response.data.success) {
          alert('상태가 변경되었습니다.');
          this.loadStats();
        }
      } catch (error) {
        console.error('Failed to update status:', error);
        alert('상태 변경에 실패했습니다.');
        this.loadUsers();
      }
    },
    async confirmDelete(user) {
      if (!confirm(`정말 '${user.username}' 사용자를 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.`)) {
        return;
      }
      try {
        const response = await adminAPI.deleteUser(user.id);
        if (response.data.success) {
          alert('사용자가 삭제되었습니다.');
          this.loadUsers();
          this.loadStats();
        }
      } catch (error) {
        console.error('Failed to delete user:', error);
        alert('삭제에 실패했습니다.');
      }
    },
    formatDate(dateStr) {
      if (!dateStr) return '';
      const date = new Date(dateStr);
      return date.toLocaleDateString('ko-KR');
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

.user-management-content {
  max-width: 1200px;
  margin: 0 auto;
}

.stats-row {
  display: flex;
  gap: 20px;
  margin-bottom: 24px;
}

.stat-card {
  background: white;
  padding: 20px 30px;
  border-radius: 12px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.stat-label {
  font-size: 14px;
  color: #666;
}

.stat-value {
  font-size: 28px;
  font-weight: bold;
  color: #333;
}

.stat-value.active {
  color: #4caf50;
}

.users-section {
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
}

.section-header h2 {
  margin: 0;
  font-size: 18px;
  color: #333;
}

.btn-refresh {
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
}

.btn-refresh:hover {
  background: #5568d3;
}

.loading {
  text-align: center;
  padding: 40px;
  color: #666;
}

.users-table-wrap {
  overflow-x: auto;
}

.users-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.users-table th,
.users-table td {
  padding: 12px;
  text-align: left;
  border-bottom: 1px solid #eee;
}

.users-table th {
  background: #f8f9fa;
  font-weight: 600;
  color: #333;
  white-space: nowrap;
}

.users-table tbody tr:hover {
  background: #f8f9fa;
}

.role-select, .status-select {
  padding: 6px 10px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
}

.role-select.admin {
  background: #fff3e0;
  border-color: #ff9800;
  color: #e65100;
}

.role-select.user {
  background: #e3f2fd;
  border-color: #2196f3;
  color: #1565c0;
}

.status-select.approved {
  background: #e8f5e9;
  border-color: #4caf50;
  color: #2e7d32;
}

.status-select.pending {
  background: #fff8e1;
  border-color: #ffc107;
  color: #f57f17;
}

.status-select.rejected {
  background: #ffebee;
  border-color: #f44336;
  color: #c62828;
}

.btn-delete {
  padding: 6px 12px;
  background: transparent;
  color: #f44336;
  border: 1px solid #f44336;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-delete:hover {
  background: #f44336;
  color: white;
}

@media (max-width: 768px) {
  .stats-row {
    flex-direction: column;
  }

  .users-table {
    font-size: 12px;
  }

  .users-table th,
  .users-table td {
    padding: 8px;
  }
}
</style>
