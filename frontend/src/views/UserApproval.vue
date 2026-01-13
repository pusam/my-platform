<template>
  <div class="approval-page">
    <h1>회원가입 승인 관리</h1>

    <div v-if="loading" class="loading">
      로딩 중...
    </div>

    <div v-else-if="errorMessage" class="error-message">
      {{ errorMessage }}
    </div>

    <div v-else-if="pendingUsers.length === 0" class="empty-message">
      승인 대기 중인 사용자가 없습니다.
    </div>

    <div v-else class="users-table">
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>아이디</th>
            <th>이름</th>
            <th>가입일시</th>
            <th>처리</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in pendingUsers" :key="user.id">
            <td>{{ user.id }}</td>
            <td>{{ user.username }}</td>
            <td>{{ user.name }}</td>
            <td>{{ formatDate(user.createdAt) }}</td>
            <td class="actions">
              <button
                @click="handleApproval(user.id, 'APPROVED')"
                class="btn btn-success"
                :disabled="processing"
              >
                승인
              </button>
              <button
                @click="handleApproval(user.id, 'REJECTED')"
                class="btn btn-danger"
                :disabled="processing"
              >
                거부
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="successMessage" class="success-message">
      {{ successMessage }}
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { getPendingUsers, approveUser } from '../utils/api';

const pendingUsers = ref([]);
const loading = ref(true);
const processing = ref(false);
const errorMessage = ref('');
const successMessage = ref('');

const loadPendingUsers = async () => {
  try {
    loading.value = true;
    errorMessage.value = '';
    const response = await getPendingUsers();
    pendingUsers.value = response.data || [];
  } catch (error) {
    console.error('Failed to load pending users:', error);
    errorMessage.value = '승인 대기 목록을 불러오는데 실패했습니다.';
  } finally {
    loading.value = false;
  }
};

const handleApproval = async (userId, status) => {
  if (!confirm(`정말 이 사용자를 ${status === 'APPROVED' ? '승인' : '거부'}하시겠습니까?`)) {
    return;
  }

  try {
    processing.value = true;
    errorMessage.value = '';
    successMessage.value = '';

    await approveUser(userId, status);

    successMessage.value = status === 'APPROVED' ? '승인되었습니다.' : '거부되었습니다.';

    // 목록 새로고침
    await loadPendingUsers();

    // 성공 메시지 3초 후 제거
    setTimeout(() => {
      successMessage.value = '';
    }, 3000);
  } catch (error) {
    console.error('Approval error:', error);
    errorMessage.value = error.response?.data?.message || '처리 중 오류가 발생했습니다.';
  } finally {
    processing.value = false;
  }
};

const formatDate = (dateString) => {
  if (!dateString) return '-';
  const date = new Date(dateString);
  return date.toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
};

onMounted(() => {
  loadPendingUsers();
});
</script>

<style scoped>
.approval-page {
  padding: 30px;
  max-width: 1200px;
  margin: 0 auto;
}

.approval-page h1 {
  margin-bottom: 30px;
  color: #333;
  font-size: 28px;
}

.loading {
  text-align: center;
  padding: 40px;
  font-size: 18px;
  color: #666;
}

.error-message {
  background-color: #fee;
  color: #c33;
  padding: 15px;
  border-radius: 6px;
  margin-bottom: 20px;
  text-align: center;
}

.success-message {
  background-color: #efe;
  color: #3c3;
  padding: 15px;
  border-radius: 6px;
  margin-top: 20px;
  text-align: center;
}

.empty-message {
  text-align: center;
  padding: 40px;
  font-size: 16px;
  color: #999;
  background: #f9f9f9;
  border-radius: 8px;
}

.users-table {
  background: white;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
  overflow: hidden;
}

table {
  width: 100%;
  border-collapse: collapse;
}

thead {
  background: #f5f5f5;
}

th {
  padding: 15px;
  text-align: left;
  font-weight: 600;
  color: #555;
  border-bottom: 2px solid #ddd;
}

td {
  padding: 15px;
  border-bottom: 1px solid #eee;
  color: #333;
}

tr:hover {
  background: #fafafa;
}

.actions {
  display: flex;
  gap: 10px;
}

.btn {
  padding: 8px 16px;
  border: none;
  border-radius: 4px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-success {
  background: #28a745;
  color: white;
}

.btn-success:hover:not(:disabled) {
  background: #218838;
}

.btn-danger {
  background: #dc3545;
  color: white;
}

.btn-danger:hover:not(:disabled) {
  background: #c82333;
}
</style>

