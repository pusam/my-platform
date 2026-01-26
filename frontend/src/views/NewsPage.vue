<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>경제 뉴스</h1>
        <div class="header-actions">
          <button @click="fetchNews" class="btn btn-refresh" :disabled="fetchingNews">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" :class="{ spinning: fetchingNews }">
              <path d="M21 12a9 9 0 11-9-9"/>
              <polyline points="21 3 21 9 15 9"/>
            </svg>
            {{ fetchingNews ? '수집 중...' : '새로고침' }}
          </button>
          <button @click="goBack" class="btn btn-back">돌아가기</button>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

      <!-- 설명 -->
      <div class="info-banner">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10"/>
          <line x1="12" y1="16" x2="12" y2="12"/>
          <line x1="12" y1="8" x2="12.01" y2="8"/>
        </svg>
        <span>AI가 요약한 오늘의 주요 경제 뉴스입니다. 매일 아침 8시에 자동 수집됩니다.</span>
        <span class="news-badge">AI 요약</span>
      </div>

      <!-- 로딩 상태 -->
      <LoadingSpinner v-if="loading && !newsList.length" message="뉴스를 불러오는 중..." />

      <!-- 뉴스 목록 -->
      <div v-else-if="newsList.length > 0" class="news-list">
        <article v-for="news in newsList" :key="news.id" class="news-card" @click="openNewsUrl(news.sourceUrl)">
          <div class="news-content">
            <div class="news-header">
              <span class="news-source">{{ news.sourceName }}</span>
              <span class="news-time">{{ formatNewsTime(news.summarizedAt) }}</span>
            </div>
            <h3>{{ news.title }}</h3>
            <p class="news-summary">{{ news.summary }}</p>
          </div>
          <div class="news-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </div>
        </article>
      </div>

      <!-- 뉴스 없음 -->
      <div v-else class="news-empty">
        <div class="empty-icon">📰</div>
        <h3>아직 수집된 뉴스가 없습니다</h3>
        <p>매일 아침 8시에 자동으로 수집되거나, 위 버튼을 눌러 수동으로 가져올 수 있습니다.</p>
        <button @click="fetchNews" :disabled="fetchingNews" class="btn-fetch">
          {{ fetchingNews ? '수집 중...' : '뉴스 가져오기' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { newsAPI } from '../utils/api';
import { UserManager } from '../utils/auth';
import LoadingSpinner from '../components/LoadingSpinner.vue';

const router = useRouter();

const newsList = ref([]);
const loading = ref(false);
const fetchingNews = ref(false);

const loadNews = async () => {
  try {
    loading.value = true;
    let response = await newsAPI.getTodayNews();
    if (response.data.data && response.data.data.length > 0) {
      newsList.value = response.data.data;
    } else {
      response = await newsAPI.getRecentNews();
      newsList.value = response.data.data || [];
    }
  } catch (error) {
    console.error('뉴스 로드 실패:', error);
    newsList.value = [];
  } finally {
    loading.value = false;
  }
};

const fetchNews = async () => {
  fetchingNews.value = true;
  try {
    await newsAPI.fetchNews();
    await loadNews();
  } catch (error) {
    console.error('뉴스 수집 실패:', error);
    alert('뉴스 수집에 실패했습니다. AI 서버(Ollama)가 실행 중인지 확인해주세요.');
  } finally {
    fetchingNews.value = false;
  }
};

const openNewsUrl = (url) => {
  if (url) {
    window.open(url, '_blank');
  }
};

const formatNewsTime = (dateStr) => {
  if (!dateStr) return '';
  const date = new Date(dateStr);
  const now = new Date();
  const diffHours = Math.floor((now - date) / (1000 * 60 * 60));
  if (diffHours < 1) return '방금 전';
  if (diffHours < 24) return `${diffHours}시간 전`;
  return date.toLocaleDateString('ko-KR');
};

const goBack = () => {
  router.back();
};

const logout = () => {
  UserManager.logout();
  router.push('/login');
};

onMounted(() => {
  loadNews();
});
</script>

<style scoped>
.info-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.1) 0%, rgba(147, 51, 234, 0.1) 100%);
  border: 1px solid rgba(59, 130, 246, 0.2);
  border-radius: 12px;
  margin-bottom: 24px;
  color: #4b5563;
  font-size: 14px;
}

.info-banner svg {
  color: #3B82F6;
  flex-shrink: 0;
}

.news-badge {
  margin-left: auto;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 6px 14px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 600;
}

.news-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.news-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  transition: all 0.3s ease;
  display: flex;
  gap: 20px;
  border: 2px solid transparent;
}

.news-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.12);
  border-color: #3b82f6;
}

.news-content {
  flex: 1;
}

.news-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.news-source {
  background: rgba(59, 130, 246, 0.1);
  color: #3b82f6;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
}

.news-time {
  color: #9ca3af;
  font-size: 13px;
}

.news-content h3 {
  margin: 0 0 12px 0;
  font-size: 18px;
  font-weight: 600;
  color: #1f2937;
  line-height: 1.4;
}

.news-summary {
  margin: 0;
  font-size: 14px;
  color: #6b7280;
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.news-arrow {
  display: flex;
  align-items: center;
  color: #d1d5db;
  transition: color 0.2s;
}

.news-card:hover .news-arrow {
  color: #3b82f6;
}

.news-empty {
  text-align: center;
  padding: 80px 20px;
  background: rgba(255, 255, 255, 0.95);
  border-radius: 16px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.news-empty .empty-icon {
  font-size: 64px;
  margin-bottom: 20px;
}

.news-empty h3 {
  margin: 0 0 12px 0;
  font-size: 20px;
  color: #1f2937;
}

.news-empty p {
  margin: 0 0 24px 0;
  color: #6b7280;
  font-size: 14px;
}

.btn-fetch {
  padding: 12px 24px;
  background: linear-gradient(135deg, #3B82F6 0%, #8B5CF6 100%);
  color: white;
  border: none;
  border-radius: 10px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s ease;
}

.btn-fetch:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.3);
}

.btn-fetch:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.btn-refresh {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: linear-gradient(135deg, #3B82F6 0%, #8B5CF6 100%);
  color: white;
  border: none;
  border-radius: 10px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s ease;
}

.btn-refresh:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.3);
}

.btn-refresh:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.btn-refresh svg.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

[data-theme="dark"] .news-card {
  background: rgba(31, 31, 35, 0.95);
}

[data-theme="dark"] .news-content h3 {
  color: #f9fafb;
}

[data-theme="dark"] .news-empty {
  background: rgba(31, 31, 35, 0.95);
}

[data-theme="dark"] .news-empty h3 {
  color: #f9fafb;
}

@media (max-width: 768px) {
  .news-card {
    padding: 16px;
  }

  .news-content h3 {
    font-size: 16px;
  }

  .news-summary {
    -webkit-line-clamp: 2;
  }
}
</style>
