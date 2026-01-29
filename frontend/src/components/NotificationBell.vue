<template>
  <div class="notification-wrapper" v-if="isLoggedIn">
    <button @click="toggleDropdown" class="notification-bell" :class="{ 'has-unread': unreadCount > 0 }">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9"/>
        <path d="M13.73 21a2 2 0 01-3.46 0"/>
      </svg>
      <span v-if="unreadCount > 0" class="notification-badge">
        {{ unreadCount > 99 ? '99+' : unreadCount }}
      </span>
    </button>

    <div v-if="showDropdown" class="notification-dropdown" @click.stop>
      <div class="dropdown-header">
        <h3>알림</h3>
        <button v-if="unreadCount > 0" @click="markAllAsRead" class="btn-mark-all">
          모두 읽음
        </button>
      </div>

      <div class="dropdown-body">
        <div v-if="loading" class="loading-state">
          <span>로딩 중...</span>
        </div>

        <div v-else-if="notifications.length === 0" class="empty-state">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9"/>
            <path d="M13.73 21a2 2 0 01-3.46 0"/>
          </svg>
          <p>알림이 없습니다</p>
        </div>

        <div v-else class="notification-list">
          <div
            v-for="notification in notifications"
            :key="notification.id"
            class="notification-item"
            :class="{ unread: !notification.isRead, [notification.type?.toLowerCase()]: true }"
            @click="handleNotificationClick(notification)"
          >
            <div class="notification-icon" :class="notification.type?.toLowerCase()">
              <svg v-if="notification.type === 'SUCCESS'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="20 6 9 17 4 12"/>
              </svg>
              <svg v-else-if="notification.type === 'WARNING'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
                <line x1="12" y1="9" x2="12" y2="13"/>
                <line x1="12" y1="17" x2="12.01" y2="17"/>
              </svg>
              <svg v-else-if="notification.type === 'ERROR'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10"/>
                <line x1="15" y1="9" x2="9" y2="15"/>
                <line x1="9" y1="9" x2="15" y2="15"/>
              </svg>
              <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10"/>
                <line x1="12" y1="16" x2="12" y2="12"/>
                <line x1="12" y1="8" x2="12.01" y2="8"/>
              </svg>
            </div>
            <div class="notification-content">
              <div class="notification-title">{{ notification.title }}</div>
              <div v-if="notification.message" class="notification-message">{{ notification.message }}</div>
              <div class="notification-time">{{ formatTime(notification.createdAt) }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="showDropdown" class="notification-overlay" @click="showDropdown = false"></div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { notificationAPI } from '../utils/api';
import { TokenManager } from '../utils/auth';

const router = useRouter();

const showDropdown = ref(false);
const notifications = ref([]);
const unreadCount = ref(0);
const loading = ref(false);
let pollInterval = null;

const isLoggedIn = computed(() => !!TokenManager.getToken());

const toggleDropdown = () => {
  showDropdown.value = !showDropdown.value;
  if (showDropdown.value) {
    loadNotifications();
  }
};

const loadNotifications = async () => {
  if (!isLoggedIn.value) return;

  try {
    loading.value = true;
    const response = await notificationAPI.getNotifications(0, 20);
    if (response.data.success) {
      notifications.value = response.data.data || [];
    }
  } catch (error) {
    console.error('알림 로드 실패:', error);
  } finally {
    loading.value = false;
  }
};

const loadUnreadCount = async () => {
  if (!isLoggedIn.value) return;

  try {
    const response = await notificationAPI.getUnreadCount();
    if (response.data.success) {
      unreadCount.value = response.data.data.count || 0;
    }
  } catch (error) {
    console.error('읽지 않은 알림 수 로드 실패:', error);
  }
};

const handleNotificationClick = async (notification) => {
  if (!notification.isRead) {
    await markAsRead(notification.id);
  }

  if (notification.link) {
    showDropdown.value = false;
    router.push(notification.link);
  }
};

const markAsRead = async (notificationId) => {
  try {
    await notificationAPI.markAsRead(notificationId);
    const notification = notifications.value.find(n => n.id === notificationId);
    if (notification) {
      notification.isRead = true;
      unreadCount.value = Math.max(0, unreadCount.value - 1);
    }
  } catch (error) {
    console.error('알림 읽음 처리 실패:', error);
  }
};

const markAllAsRead = async () => {
  try {
    await notificationAPI.markAllAsRead();
    notifications.value.forEach(n => n.isRead = true);
    unreadCount.value = 0;
  } catch (error) {
    console.error('모든 알림 읽음 처리 실패:', error);
  }
};

const formatTime = (dateStr) => {
  if (!dateStr) return '';
  const date = new Date(dateStr);
  const now = new Date();
  const diff = now - date;

  const minutes = Math.floor(diff / 60000);
  const hours = Math.floor(diff / 3600000);
  const days = Math.floor(diff / 86400000);

  if (minutes < 1) return '방금 전';
  if (minutes < 60) return `${minutes}분 전`;
  if (hours < 24) return `${hours}시간 전`;
  if (days < 7) return `${days}일 전`;

  return date.toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' });
};

onMounted(() => {
  if (isLoggedIn.value) {
    loadUnreadCount();
    // 30초마다 읽지 않은 알림 수 확인
    pollInterval = setInterval(loadUnreadCount, 30000);
  }
});

onUnmounted(() => {
  if (pollInterval) {
    clearInterval(pollInterval);
  }
});
</script>

<style scoped>
.notification-wrapper {
  position: relative;
}

.notification-bell {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border: none;
  border-radius: 12px;
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  color: #4a5568;
  cursor: pointer;
  transition: all 0.3s ease;
}

.notification-bell:hover {
  transform: scale(1.05);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.notification-bell.has-unread {
  animation: bellShake 0.5s ease-in-out;
}

@keyframes bellShake {
  0%, 100% { transform: rotate(0); }
  25% { transform: rotate(10deg); }
  50% { transform: rotate(-10deg); }
  75% { transform: rotate(5deg); }
}

.notification-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  background: linear-gradient(135deg, #fc5c7d 0%, #e74c3c 100%);
  color: white;
  font-size: 11px;
  font-weight: 700;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.notification-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 999;
}

.notification-dropdown {
  position: absolute;
  top: 50px;
  right: 0;
  width: 360px;
  max-height: 480px;
  background: white;
  border-radius: 16px;
  box-shadow: 0 10px 50px rgba(0, 0, 0, 0.2);
  z-index: 1000;
  overflow: hidden;
  animation: slideDown 0.2s ease;
}

@keyframes slideDown {
  from {
    opacity: 0;
    transform: translateY(-10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.dropdown-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 2px solid var(--border-light);
}

.dropdown-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
}

.btn-mark-all {
  background: none;
  border: none;
  color: var(--primary-start);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.2s;
}

.btn-mark-all:hover {
  opacity: 0.8;
}

.dropdown-body {
  max-height: 400px;
  overflow-y: auto;
}

.loading-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  color: var(--text-muted);
}

.empty-state svg {
  margin-bottom: 12px;
  opacity: 0.5;
}

.empty-state p {
  margin: 0;
  font-size: 14px;
}

.notification-list {
  padding: 8px 0;
}

.notification-item {
  display: flex;
  gap: 14px;
  padding: 14px 20px;
  cursor: pointer;
  transition: background 0.2s;
}

.notification-item:hover {
  background: rgba(102, 126, 234, 0.05);
}

.notification-item.unread {
  background: rgba(102, 126, 234, 0.08);
}

.notification-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.notification-icon.info {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.15) 0%, rgba(118, 75, 162, 0.15) 100%);
  color: var(--primary-start);
}

.notification-icon.success {
  background: linear-gradient(135deg, rgba(72, 187, 120, 0.15) 0%, rgba(56, 161, 105, 0.15) 100%);
  color: #48bb78;
}

.notification-icon.warning {
  background: linear-gradient(135deg, rgba(237, 137, 54, 0.15) 0%, rgba(236, 201, 75, 0.15) 100%);
  color: #ed8936;
}

.notification-icon.error {
  background: linear-gradient(135deg, rgba(252, 92, 125, 0.15) 0%, rgba(231, 76, 60, 0.15) 100%);
  color: #fc5c7d;
}

.notification-content {
  flex: 1;
  min-width: 0;
}

.notification-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 4px;
}

.notification-message {
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notification-time {
  font-size: 11px;
  color: var(--text-light);
}

@media (max-width: 480px) {
  .notification-dropdown {
    position: fixed;
    top: 60px;
    left: 10px;
    right: 10px;
    width: auto;
  }
}
</style>
