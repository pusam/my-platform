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
          <BackButton />
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
      <div v-else-if="enrichedNews.length > 0" class="news-list">
        <article
          v-for="news in enrichedNews"
          :key="news.id"
          :class="['news-card', { 'news-card--hot': news._isHot }]"
          @click="openNewsUrl(news.sourceUrl)"
        >
          <div class="news-content">
            <div class="news-header">
              <span class="news-source">{{ news.sourceName }}</span>
              <span v-if="news._sector" class="sector-tag">{{ news._sector }}</span>
              <span v-if="news._isHot" class="hot-badge">HOT</span>
              <span class="news-time">{{ formatNewsTime(news.summarizedAt) }}</span>
            </div>
            <h3>{{ news.title }}</h3>
            <!-- 불릿 요약 -->
            <ul v-if="news._bullets.length > 0" class="news-bullets">
              <li v-for="(bullet, i) in news._bullets" :key="i">{{ bullet }}</li>
            </ul>
            <!-- 줄글 폴백 (기존 뉴스 호환) -->
            <p v-else class="news-summary">{{ news.summary }}</p>
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
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { newsAPI } from '../utils/api';
import { UserManager } from '../utils/auth';
import LoadingSpinner from '../components/LoadingSpinner.vue';
import BackButton from '../components/BackButton.vue';

const router = useRouter();

const newsList = ref([]);
const loading = ref(false);
const fetchingNews = ref(false);

// ═══ HOT 키워드 ═══
const HOT_KEYWORDS = [
  '수주', '실적', '계약', '급등', '급락', '사상최고', '역대급', '호실적',
  '인수', '합병', 'M&A', 'IPO', '상장', '흑자전환', '적자전환',
  '사상최대', '어닝서프라이즈', '어닝쇼크'
];

// ═══ 섹터 태그 매핑 ═══
const SECTOR_MAP = [
  { keywords: ['반도체', 'HBM', '파운드리', 'DRAM', 'NAND', '웨이퍼'], tag: '반도체' },
  { keywords: ['2차전지', '배터리', '리튬', '양극재', '음극재', '전해질'], tag: '2차전지' },
  { keywords: ['AI', '인공지능', 'GPT', 'LLM', '딥러닝'], tag: 'AI' },
  { keywords: ['나스닥', 'S&P', '다우', '뉴욕증시', '월스트리트', '미국증시'], tag: '미국증시' },
  { keywords: ['코스피', '코스닥', '한국증시', '유가증권'], tag: '국내증시' },
  { keywords: ['금리', '기준금리', '연준', 'Fed', '한은', '통화정책'], tag: '금리·통화' },
  { keywords: ['환율', '달러', '원화', '엔화', '위안화'], tag: '환율' },
  { keywords: ['유가', '원유', 'OPEC', '천연가스', 'WTI'], tag: '원자재' },
  { keywords: ['바이오', '제약', '신약', '임상', 'FDA'], tag: '바이오' },
  { keywords: ['자동차', '전기차', 'EV', '자율주행', '테슬라'], tag: '자동차' },
  { keywords: ['수출', '무역', '관세', '수입규제', '통상'], tag: '무역' },
  { keywords: ['부동산', '건설', 'PF', '아파트', '분양'], tag: '부동산' },
];

const detectSector = (title) => {
  for (const { keywords, tag } of SECTOR_MAP) {
    if (keywords.some(kw => title.includes(kw))) return tag;
  }
  return null;
};

const isHot = (title) => HOT_KEYWORDS.some(kw => title.includes(kw));

const parseBullets = (summary) => {
  if (!summary) return [];
  const bullets = summary.split('• ').filter(s => s.trim().length > 0);
  return bullets.length >= 2 ? bullets.map(b => b.trim().replace(/\n/g, '')) : [];
};

// ═══ 뉴스 가공 computed ═══
const enrichedNews = computed(() =>
  newsList.value.map(news => ({
    ...news,
    _isHot: isHot(news.title || ''),
    _sector: detectSector(news.title || ''),
    _bullets: parseBullets(news.summary)
  }))
);

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

/* HOT 뉴스 카드 강조 */
.news-card--hot {
  border-left: 4px solid #DC2626;
  border-color: rgba(220, 38, 38, 0.2);
  background: linear-gradient(135deg, rgba(255,255,255,0.98), rgba(254,242,242,0.95));
}
.news-card--hot:hover {
  border-color: #DC2626;
  box-shadow: 0 8px 30px rgba(220, 38, 38, 0.15);
}

.news-content {
  flex: 1;
}

.news-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.news-source {
  background: rgba(59, 130, 246, 0.1);
  color: #3b82f6;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
}

/* 섹터 태그 */
.sector-tag {
  background: #EEF2FF;
  color: #4338CA;
  padding: 3px 10px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 700;
  border: 1px solid #C7D2FE;
  letter-spacing: 0.3px;
}

/* HOT 뱃지 */
.hot-badge {
  background: linear-gradient(135deg, #DC2626, #B91C1C);
  color: white;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.5px;
  animation: hotPulse 2s ease-in-out infinite;
}
@keyframes hotPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.8; }
}

.news-time {
  color: #9ca3af;
  font-size: 13px;
  margin-left: auto;
}

.news-content h3 {
  margin: 0 0 12px 0;
  font-size: 18px;
  font-weight: 600;
  color: #1f2937;
  line-height: 1.4;
}

/* 불릿 요약 */
.news-bullets {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.news-bullets li {
  font-size: 14px;
  color: #374151;
  line-height: 1.6;
  padding-left: 16px;
  position: relative;
  font-weight: 500;
}
.news-bullets li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 9px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #6366F1;
}

/* 줄글 폴백 */
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
