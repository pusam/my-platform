<template>
  <div class="notification-wrapper" :class="{ dark }" v-if="isLoggedIn">
    <button @click="toggleDropdown" class="notification-bell" :class="{ 'has-unread': unreadCount > 0, dark }">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9"/>
        <path d="M13.73 21a2 2 0 01-3.46 0"/>
      </svg>
      <span v-if="unreadCount > 0" class="notification-badge">
        {{ unreadCount > 99 ? '99+' : unreadCount }}
      </span>
    </button>

    <div v-if="showDropdown" class="notification-dropdown" :class="{ dark }" @click.stop>
      <div class="dropdown-header">
        <h3>알림</h3>
        <div class="header-actions">
          <button @click="toggleFilters" class="btn-filter" :class="{ active: showFilters }" title="카테고리 필터">
            ⚙️
          </button>
          <button v-if="unreadCount > 0" @click="markAllAsRead" class="btn-mark-all">
            모두 읽음
          </button>
        </div>
      </div>

      <!-- 카테고리 필터 패널 -->
      <div v-if="showFilters" class="filter-panel">
        <div class="filter-title">표시할 카테고리</div>
        <div class="filter-grid">
          <label v-for="cat in categories" :key="cat.key" class="filter-item">
            <input type="checkbox" :checked="!disabledCategories.includes(cat.key)"
                   @change="toggleCategory(cat.key)" />
            <span class="filter-icon">{{ cat.icon }}</span>
            <span>{{ cat.label }}</span>
          </label>
        </div>
        <div class="filter-hint">설정은 이 브라우저에만 저장됩니다 (텔레그램에는 영향 없음)</div>
      </div>

      <div class="dropdown-body">
        <div v-if="loading" class="loading-state">
          <span>로딩 중...</span>
        </div>

        <div v-else-if="filteredNotifications.length === 0" class="empty-state">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9"/>
            <path d="M13.73 21a2 2 0 01-3.46 0"/>
          </svg>
          <p v-if="notifications.length === 0">알림이 없습니다</p>
          <p v-else>필터링되어 모두 숨겨졌습니다</p>
        </div>

        <div v-else class="notification-list">
          <div
            v-for="notification in filteredNotifications"
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
import { ref, onMounted, onUnmounted, computed, defineProps } from 'vue';
import { useRouter } from 'vue-router';
import { notificationAPI } from '../utils/api';
import { TokenManager } from '../utils/auth';

defineProps({
  dark: { type: Boolean, default: false }   // V2 다크 테마 페이지에서 true
});

const router = useRouter();

const showDropdown = ref(false);
const showFilters = ref(false);
const notifications = ref([]);
const unreadCount = ref(0);
const loading = ref(false);
let pollInterval = null;

const isLoggedIn = computed(() => !!TokenManager.getToken());

// ===== 카테고리 분류 =====
const categories = [
  { key: 'BUY_SIGNAL', label: '매수 신호', icon: '🚀',
    keywords: ['강력매수', '매수후보', '매수신호', 'AI', '도달', '수익', '손실'] },
  { key: 'SUPPLY', label: '수급/외인기관', icon: '💰',
    keywords: ['외국인', '기관', '수급', '복합', '연속', '레이더'] },
  { key: 'FUNDAMENTAL', label: '펀더멘털', icon: '📊',
    keywords: ['마법공식', '턴어라운드', '어닝', '실적', 'PEG', '서프라이즈'] },
  { key: 'MARKET', label: '시장 분위기', icon: '🌅',
    keywords: ['모닝브리핑', '브리핑', '시장', '장 마감', '장마감'] },
  { key: 'RISK', label: '리스크', icon: '⚠️',
    keywords: ['공매도', '리스크', '위험', '경보', '주의'] },
  { key: 'ETC', label: '기타', icon: '📌', keywords: [] }
];

const STORAGE_KEY = 'notif_disabled_categories';
const disabledCategories = ref(loadDisabled());

function loadDisabled() {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v ? JSON.parse(v) : [];
  } catch { return []; }
}
function saveDisabled() {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(disabledCategories.value)); } catch {}
}

const classifyCategory = (notif) => {
  const text = (notif.title || '') + ' ' + (notif.message || '');
  for (const cat of categories) {
    if (cat.key === 'ETC') continue;
    if (cat.keywords.some(k => text.includes(k))) return cat.key;
  }
  return 'ETC';
};

const filteredNotifications = computed(() => {
  if (disabledCategories.value.length === 0) return notifications.value;
  return notifications.value.filter(n => !disabledCategories.value.includes(classifyCategory(n)));
});

const toggleFilters = () => { showFilters.value = !showFilters.value; };

const toggleCategory = (key) => {
  const idx = disabledCategories.value.indexOf(key);
  if (idx >= 0) disabledCategories.value.splice(idx, 1);
  else disabledCategories.value.push(key);
  saveDisabled();
};

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
  // 탭 비활성화 시 폴링 스킵 (배경 API 호출 누적 방지)
  if (typeof document !== 'undefined' && document.hidden) return;

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

/* ===== 다크 테마 (V2 대시보드/종목 상세 페이지용) ===== */
.notification-bell.dark {
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.12);
  color: rgba(255,255,255,0.75);
  width: 36px;
  height: 36px;
  border-radius: 9px;
}
.notification-bell.dark:hover {
  background: rgba(255,255,255,0.12);
  color: #fff;
  transform: none;
  box-shadow: none;
}

.notification-dropdown.dark {
  background: var(--surface-solid, #1a1a2e);
  border: 1px solid rgba(255,255,255,0.1);
  color: rgba(255,255,255,0.9);
}
.notification-dropdown.dark .dropdown-header {
  border-bottom-color: rgba(255,255,255,0.08);
}
.notification-dropdown.dark .dropdown-header h3 {
  color: rgba(255,255,255,0.95);
}
.notification-dropdown.dark .btn-mark-all {
  color: #a5b4fc;
}
.notification-dropdown.dark .notification-item {
  border-bottom: 1px solid rgba(255,255,255,0.04);
}
.notification-dropdown.dark .notification-item:hover {
  background: rgba(255,255,255,0.04);
}
.notification-dropdown.dark .notification-item.unread {
  background: rgba(124,58,237,0.08);
}
.notification-dropdown.dark .notification-item.unread:hover {
  background: rgba(124,58,237,0.12);
}
.notification-dropdown.dark .notification-title {
  color: rgba(255,255,255,0.95);
}
.notification-dropdown.dark .notification-message {
  color: rgba(255,255,255,0.65);
}
.notification-dropdown.dark .notification-time {
  color: rgba(255,255,255,0.4);
}
.notification-dropdown.dark .empty-state,
.notification-dropdown.dark .loading-state {
  color: rgba(255,255,255,0.5);
}

/* ===== 카테고리 필터 ===== */
.header-actions { display: flex; gap: 8px; align-items: center; }
.btn-filter {
  background: none;
  border: 1px solid var(--border-light, #e9ecef);
  width: 28px; height: 28px;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.15s;
}
.btn-filter:hover, .btn-filter.active {
  background: rgba(102,126,234,0.12);
  border-color: rgba(102,126,234,0.4);
}

.filter-panel {
  padding: 12px 16px;
  background: rgba(102,126,234,0.04);
  border-bottom: 1px solid rgba(0,0,0,0.06);
}
.filter-title {
  font-size: 11.5px;
  font-weight: 700;
  color: var(--text-muted, #888);
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 0.4px;
}
.filter-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 6px;
}
.filter-item {
  display: flex; align-items: center; gap: 6px;
  font-size: 12px;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 5px;
  transition: background 0.1s;
}
.filter-item:hover { background: rgba(102,126,234,0.08); }
.filter-item input[type="checkbox"] { accent-color: #667eea; }
.filter-icon { font-size: 13px; }
.filter-hint {
  font-size: 10.5px;
  color: var(--text-light, #aaa);
  margin-top: 8px;
  font-style: italic;
}

/* dark variant */
.notification-dropdown.dark .btn-filter {
  border-color: rgba(255,255,255,0.15);
  color: rgba(255,255,255,0.75);
}
.notification-dropdown.dark .btn-filter:hover,
.notification-dropdown.dark .btn-filter.active {
  background: rgba(124,58,237,0.18);
  border-color: rgba(124,58,237,0.5);
}
.notification-dropdown.dark .filter-panel {
  background: rgba(124,58,237,0.06);
  border-bottom-color: rgba(255,255,255,0.06);
}
.notification-dropdown.dark .filter-title { color: rgba(255,255,255,0.55); }
.notification-dropdown.dark .filter-item { color: rgba(255,255,255,0.85); }
.notification-dropdown.dark .filter-item:hover { background: rgba(255,255,255,0.05); }
.notification-dropdown.dark .filter-hint { color: rgba(255,255,255,0.4); }
</style>
