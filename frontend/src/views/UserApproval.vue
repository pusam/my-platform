<template>
  <div class="user-management-page">
    <div class="page-header">
      <h1>👥 사용자 관리</h1>
      <button @click="goBack" class="btn-back">← 대시보드</button>
    </div>

    <!-- 탭 메뉴 -->
    <div class="tabs">
      <button
        @click="activeTab = 'pending'"
        :class="['tab', { active: activeTab === 'pending' }]"
      >
        🔔 승인 대기 ({{ pendingCount }})
      </button>
      <button
        @click="activeTab = 'all'"
        :class="['tab', { active: activeTab === 'all' }]"
      >
        📋 전체 사용자 ({{ allCount }})
      </button>
    </div>

    <!-- 승인 대기 탭 -->
    <div v-if="activeTab === 'pending'" class="tab-content">
      <div v-if="loading" class="loading">⏳ 로딩 중...</div>

      <div v-else-if="pendingUsers.length === 0" class="empty-state">
        <div class="empty-icon">✅</div>
        <p>승인 대기 중인 사용자가 없습니다.</p>
      </div>

      <div v-else class="users-grid">
        <div v-for="user in pendingUsers" :key="user.id" class="user-card pending">
          <div class="card-header">
            <span class="badge badge-warning">승인 대기</span>
            <span class="user-id">#{{ user.id }}</span>
          </div>
          <div class="card-body">
            <h3>{{ user.name }}</h3>
            <div class="user-info">
              <p><strong>아이디:</strong> {{ user.username }}</p>
              <p><strong>이메일:</strong> {{ user.email }}</p>
              <p><strong>전화번호:</strong> {{ user.phone }}</p>
              <p><strong>가입일:</strong> {{ formatDate(user.createdAt) }}</p>
            </div>
          </div>
          <div class="card-actions">
            <button @click="approveUser(user.id)" class="btn btn-approve" :disabled="processing">
              ✓ 승인
            </button>
            <button @click="rejectUser(user.id)" class="btn btn-reject" :disabled="processing">
              ✕ 거부
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 전체 사용자 탭 -->
    <div v-if="activeTab === 'all'" class="tab-content">
      <div v-if="loading" class="loading">⏳ 로딩 중...</div>

      <div v-else class="users-table-container">
        <table class="users-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>아이디</th>
              <th>이름</th>
              <th>이메일</th>
              <th>전화번호</th>
              <th>권한</th>
              <th>상태</th>
              <th>가입일</th>
              <th>관리</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in allUsers" :key="user.id">
              <td>{{ user.id }}</td>
              <td>{{ user.username }}</td>
              <td>{{ user.name }}</td>
              <td>{{ user.email }}</td>
              <td>{{ user.phone }}</td>
              <td>
                <span :class="['badge', user.role === 'ADMIN' ? 'badge-admin' : 'badge-user']">
                  {{ user.role }}
                </span>
              </td>
              <td>
                <span :class="['badge', getStatusClass(user.status)]">
                  {{ getStatusText(user.status) }}
                </span>
              </td>
              <td>{{ formatDate(user.createdAt) }}</td>
              <td class="actions">
                <button @click="showUserMenu(user)" class="btn-menu">⋮</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 사용자 관리 메뉴 모달 -->
    <div v-if="selectedUser" class="modal" @click.self="closeUserMenu">
      <div class="modal-content">
        <div class="modal-header">
          <h3>{{ selectedUser.name }} 관리</h3>
          <button @click="closeUserMenu" class="btn-close">✕</button>
        </div>
        <div class="modal-body">
          <div class="user-detail">
            <p><strong>아이디:</strong> {{ selectedUser.username }}</p>
            <p><strong>이메일:</strong> {{ selectedUser.email }}</p>
            <p><strong>권한:</strong> {{ selectedUser.role }}</p>
            <p><strong>상태:</strong> {{ getStatusText(selectedUser.status) }}</p>
          </div>
          <div class="menu-actions">
            <button
              v-if="selectedUser.status === 'INACTIVE'"
              @click="activateUser(selectedUser.id)"
              class="menu-btn"
            >
              ✓ 계정 활성화
            </button>
            <button
              v-if="selectedUser.status === 'ACTIVE'"
              @click="deactivateUser(selectedUser.id)"
              class="menu-btn"
            >
              🚫 계정 비활성화
            </button>
            <button
              v-if="selectedUser.role === 'USER'"
              @click="changeRole(selectedUser.id, 'ADMIN')"
              class="menu-btn"
            >
              👑 관리자로 변경
            </button>
            <button
              v-if="selectedUser.role === 'ADMIN'"
              @click="changeRole(selectedUser.id, 'USER')"
              class="menu-btn"
            >
              👤 일반 사용자로 변경
            </button>
            <button
              @click="deleteUser(selectedUser.id)"
              class="menu-btn danger"
            >
              🗑️ 계정 삭제
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 알림 메시지 -->
    <div v-if="message.text" :class="['toast', message.type]">
      {{ message.text }}
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import axios from 'axios';

const router = useRouter();
const activeTab = ref('pending');
const pendingUsers = ref([]);
const allUsers = ref([]);
const loading = ref(false);
const processing = ref(false);
const selectedUser = ref(null);
const message = ref({ text: '', type: '' });

const pendingCount = computed(() => pendingUsers.value.length);
const allCount = computed(() => allUsers.value.length);

onMounted(() => {
  loadPendingUsers();
  loadAllUsers();
});

const loadPendingUsers = async () => {
  loading.value = true;
  try {
    const token = localStorage.getItem('jwt_token');
    const response = await axios.get('/api/admin/users/pending', {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    if (response.data.success) {
      pendingUsers.value = response.data.data;
    }
  } catch (error) {
    showMessage('승인 대기 목록 조회 실패', 'error');
  } finally {
    loading.value = false;
  }
};

const loadAllUsers = async () => {
  loading.value = true;
  try {
    const token = localStorage.getItem('jwt_token');
    const response = await axios.get('/api/admin/users', {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    if (response.data.success) {
      allUsers.value = response.data.data;
    }
  } catch (error) {
    showMessage('사용자 목록 조회 실패', 'error');
  } finally {
    loading.value = false;
  }
};

const approveUser = async (userId) => {
  if (!confirm('이 사용자의 회원가입을 승인하시겠습니까?')) return;

  processing.value = true;
  try {
    const token = localStorage.getItem('jwt_token');
    const response = await axios.post(`/api/admin/users/${userId}/approve`, {}, {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    if (response.data.success) {
      showMessage('회원가입이 승인되었습니다.', 'success');
      await loadPendingUsers();
      await loadAllUsers();
    }
  } catch (error) {
    showMessage('승인 실패', 'error');
  } finally {
    processing.value = false;
  }
};

const rejectUser = async (userId) => {
  if (!confirm('이 사용자의 회원가입을 거부하시겠습니까?\n계정이 삭제됩니다.')) return;

  processing.value = true;
  try {
    const token = localStorage.getItem('jwt_token');
    const response = await axios.post(`/api/admin/users/${userId}/reject`, {}, {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    if (response.data.success) {
      showMessage('회원가입이 거부되었습니다.', 'success');
      await loadPendingUsers();
    }
  } catch (error) {
    showMessage('거부 실패', 'error');
  } finally {
    processing.value = false;
  }
};

const activateUser = async (userId) => {
  if (!confirm('이 계정을 활성화하시겠습니까?')) return;

  try {
    const token = localStorage.getItem('jwt_token');
    const response = await axios.post(`/api/admin/users/${userId}/activate`, {}, {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    if (response.data.success) {
      showMessage('계정이 활성화되었습니다.', 'success');
      closeUserMenu();
      await loadAllUsers();
    }
  } catch (error) {
    showMessage('활성화 실패', 'error');
  }
};

const deactivateUser = async (userId) => {
  if (!confirm('이 계정을 비활성화하시겠습니까?')) return;

  try {
    const token = localStorage.getItem('jwt_token');
    const response = await axios.post(`/api/admin/users/${userId}/deactivate`, {}, {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    if (response.data.success) {
      showMessage('계정이 비활성화되었습니다.', 'success');
      closeUserMenu();
      await loadAllUsers();
    }
  } catch (error) {
    showMessage('비활성화 실패', 'error');
  }
};

const changeRole = async (userId, newRole) => {
  if (!confirm(`권한을 ${newRole}(으)로 변경하시겠습니까?`)) return;

  try {
    const token = localStorage.getItem('jwt_token');
    const response = await axios.put(`/api/admin/users/${userId}/role`,
      { role: newRole },
      { headers: { 'Authorization': `Bearer ${token}` } }
    );
    if (response.data.success) {
      showMessage('권한이 변경되었습니다.', 'success');
      closeUserMenu();
      await loadAllUsers();
    }
  } catch (error) {
    showMessage('권한 변경 실패', 'error');
  }
};

const deleteUser = async (userId) => {
  if (!confirm('정말로 이 사용자를 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.')) return;

  try {
    const token = localStorage.getItem('jwt_token');
    const response = await axios.delete(`/api/admin/users/${userId}`, {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    if (response.data.success) {
      showMessage('사용자가 삭제되었습니다.', 'success');
      closeUserMenu();
      await loadAllUsers();
      await loadPendingUsers();
    }
  } catch (error) {
    showMessage(error.response?.data?.message || '삭제 실패', 'error');
  }
};

const showUserMenu = (user) => {
  selectedUser.value = user;
};

const closeUserMenu = () => {
  selectedUser.value = null;
};

const showMessage = (text, type) => {
  message.value = { text, type };
  setTimeout(() => {
    message.value = { text: '', type: '' };
  }, 3000);
};

const getStatusClass = (status) => {
  const classes = {
    'ACTIVE': 'badge-success',
    'INACTIVE': 'badge-secondary',
    'PENDING': 'badge-warning'
  };
  return classes[status] || 'badge-secondary';
};

const getStatusText = (status) => {
  const texts = {
    'ACTIVE': '활성',
    'INACTIVE': '비활성',
    'PENDING': '대기'
  };
  return texts[status] || status;
};

const formatDate = (dateString) => {
  if (!dateString) return '-';
  const date = new Date(dateString);
  return date.toLocaleString('ko-KR');
};

const goBack = () => {
  router.push('/admin');
};
</script>

<style scoped>
.user-management-page {
  min-height: 100vh;
  background: #f5f7fa;
  padding: 30px;
  max-width: 1400px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 30px;
}

.page-header h1 {
  margin: 0;
  font-size: 32px;
  color: #2c3e50;
}

.btn-back {
  padding: 10px 20px;
  background: white;
  color: #2c3e50;
  border: 2px solid #e9ecef;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  transition: all 0.2s;
}

.btn-back:hover {
  background: #f8f9fa;
  transform: translateY(-2px);
}

/* 탭 */
.tabs {
  display: flex;
  gap: 10px;
  margin-bottom: 30px;
}

.tab {
  padding: 12px 24px;
  background: white;
  border: 2px solid #e9ecef;
  border-radius: 10px;
  cursor: pointer;
  font-size: 15px;
  font-weight: 600;
  color: #6c757d;
  transition: all 0.2s;
}

.tab:hover {
  border-color: #667eea;
  color: #667eea;
}

.tab.active {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border-color: transparent;
}

/* 로딩 & 빈 상태 */
.loading, .empty-state {
  text-align: center;
  padding: 60px 20px;
  background: white;
  border-radius: 16px;
}

.empty-icon {
  font-size: 64px;
  margin-bottom: 20px;
}

/* 사용자 카드 그리드 */
.users-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
  gap: 20px;
}

.user-card {
  background: white;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
  transition: all 0.2s;
}

.user-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.1);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
}

.user-id {
  font-size: 12px;
  color: #95a5a6;
  font-weight: 600;
}

.card-body h3 {
  margin: 0 0 15px 0;
  font-size: 20px;
  color: #2c3e50;
}

.user-info p {
  margin: 8px 0;
  font-size: 14px;
  color: #6c757d;
}

.card-actions {
  display: flex;
  gap: 10px;
  margin-top: 20px;
}

.btn {
  flex: 1;
  padding: 10px;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  transition: all 0.2s;
}

.btn-approve {
  background: #28a745;
  color: white;
}

.btn-approve:hover:not(:disabled) {
  background: #218838;
  transform: translateY(-2px);
}

.btn-reject {
  background: #dc3545;
  color: white;
}

.btn-reject:hover:not(:disabled) {
  background: #c82333;
  transform: translateY(-2px);
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 뱃지 */
.badge {
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 600;
}

.badge-warning {
  background: #fff3cd;
  color: #856404;
}

.badge-success {
  background: #d4edda;
  color: #155724;
}

.badge-secondary {
  background: #e2e3e5;
  color: #6c757d;
}

.badge-admin {
  background: #667eea;
  color: white;
}

.badge-user {
  background: #e9ecef;
  color: #495057;
}

/* 테이블 */
.users-table-container {
  background: white;
  border-radius: 16px;
  padding: 20px;
  overflow-x: auto;
}

.users-table {
  width: 100%;
  border-collapse: collapse;
}

.users-table th {
  text-align: left;
  padding: 12px;
  background: #f8f9fa;
  font-weight: 600;
  color: #495057;
  border-bottom: 2px solid #dee2e6;
}

.users-table td {
  padding: 12px;
  border-bottom: 1px solid #f1f3f5;
}

.users-table tbody tr:hover {
  background: #f8f9fa;
}

.actions {
  text-align: center;
}

.btn-menu {
  padding: 6px 12px;
  background: #f8f9fa;
  border: 1px solid #dee2e6;
  border-radius: 6px;
  cursor: pointer;
  font-size: 18px;
  transition: all 0.2s;
}

.btn-menu:hover {
  background: #e9ecef;
}

/* 모달 */
.modal {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: white;
  border-radius: 16px;
  width: 90%;
  max-width: 500px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px;
  border-bottom: 1px solid #f1f3f5;
}

.modal-header h3 {
  margin: 0;
  font-size: 20px;
}

.btn-close {
  width: 32px;
  height: 32px;
  background: #f8f9fa;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  font-size: 18px;
  transition: all 0.2s;
}

.btn-close:hover {
  background: #e9ecef;
}

.modal-body {
  padding: 20px;
}

.user-detail {
  padding: 15px;
  background: #f8f9fa;
  border-radius: 8px;
  margin-bottom: 20px;
}

.user-detail p {
  margin: 8px 0;
  font-size: 14px;
}

.menu-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.menu-btn {
  padding: 12px;
  background: white;
  border: 2px solid #e9ecef;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  text-align: left;
  transition: all 0.2s;
}

.menu-btn:hover {
  background: #f8f9fa;
  border-color: #667eea;
}

.menu-btn.danger {
  border-color: #dc3545;
  color: #dc3545;
}

.menu-btn.danger:hover {
  background: #dc3545;
  color: white;
}

/* 토스트 메시지 */
.toast {
  position: fixed;
  bottom: 30px;
  right: 30px;
  padding: 16px 24px;
  border-radius: 10px;
  font-weight: 600;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  z-index: 1001;
  animation: slideIn 0.3s;
}

.toast.success {
  background: #28a745;
  color: white;
}

.toast.error {
  background: #dc3545;
  color: white;
}

@keyframes slideIn {
  from {
    transform: translateX(400px);
    opacity: 0;
  }
  to {
    transform: translateX(0);
    opacity: 1;
  }
}
</style>

