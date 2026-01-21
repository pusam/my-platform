<template>
  <div class="reddit-page">
    <!-- Header -->
    <div class="page-header">
      <h1>Reddit 주식 정보</h1>
      <p class="subtitle">해외 주식 커뮤니티의 실시간 트렌드와 인기 게시물</p>
    </div>

    <!-- Main Content -->
    <div class="content-grid">
      <!-- Trending Tickers Section -->
      <div class="trending-section">
        <div class="section-header">
          <h2>트렌딩 티커</h2>
          <button @click="loadTrendingTickers" :disabled="loadingTrending" class="refresh-btn">
            <span v-if="loadingTrending">로딩...</span>
            <span v-else>새로고침</span>
          </button>
        </div>
        <div v-if="loadingTrending" class="loading-spinner">
          <div class="spinner"></div>
        </div>
        <div v-else-if="trendingTickers.length === 0" class="empty-state">
          데이터가 없습니다
        </div>
        <div v-else class="ticker-grid">
          <div
            v-for="(ticker, index) in trendingTickers"
            :key="ticker.ticker"
            class="ticker-card"
            @click="searchTicker(ticker.ticker)"
          >
            <div class="ticker-rank">{{ index + 1 }}</div>
            <div class="ticker-symbol">${{ ticker.ticker }}</div>
            <div class="ticker-mentions">{{ ticker.mentionCount }}회 언급</div>
          </div>
        </div>
      </div>

      <!-- Search Section -->
      <div class="search-section">
        <div class="search-box">
          <input
            v-model="searchQuery"
            type="text"
            placeholder="종목명 또는 티커 검색 (예: AAPL, Tesla)"
            @keyup.enter="searchPosts"
          />
          <button @click="searchPosts" :disabled="loading || !searchQuery.trim()">검색</button>
        </div>
      </div>

      <!-- Tab Navigation -->
      <div class="tab-navigation">
        <button
          v-for="sub in subreddits"
          :key="sub"
          :class="['tab-btn', { active: selectedSubreddit === sub }]"
          @click="selectSubreddit(sub)"
        >
          r/{{ sub }}
        </button>
        <button
          :class="['tab-btn', { active: selectedSubreddit === 'all' }]"
          @click="selectSubreddit('all')"
        >
          전체
        </button>
      </div>

      <!-- Sort Options -->
      <div class="sort-options">
        <label>정렬:</label>
        <select v-model="sortBy" @change="loadPosts">
          <option value="hot">인기</option>
          <option value="new">최신</option>
          <option value="top">추천순</option>
          <option value="rising">상승중</option>
        </select>
      </div>

      <!-- Posts List -->
      <div class="posts-section">
        <div v-if="loading" class="loading-spinner">
          <div class="spinner"></div>
          <p>게시물 로딩 중...</p>
        </div>
        <div v-else-if="posts.length === 0" class="empty-state">
          게시물이 없습니다
        </div>
        <div v-else class="posts-list">
          <div
            v-for="post in posts"
            :key="post.id"
            class="post-card"
            @click="openPost(post.permalink)"
          >
            <div class="post-header">
              <span class="subreddit">r/{{ post.subreddit }}</span>
              <span class="author">u/{{ post.author }}</span>
              <span class="time">{{ formatTime(post.createdAt) }}</span>
            </div>
            <h3 class="post-title">{{ post.title }}</h3>
            <p v-if="post.selftext" class="post-preview">{{ truncateText(post.selftext, 150) }}</p>
            <div v-if="post.mentionedTickers && post.mentionedTickers.length > 0" class="tickers">
              <span
                v-for="ticker in post.mentionedTickers.slice(0, 5)"
                :key="ticker"
                class="ticker-tag"
                @click.stop="searchTicker(ticker)"
              >
                ${{ ticker }}
              </span>
            </div>
            <div v-if="post.flair" class="flair">{{ post.flair }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { redditAPI } from '../utils/api'

export default {
  name: 'RedditPage',
  data() {
    return {
      loading: false,
      loadingTrending: false,
      searchQuery: '',
      selectedSubreddit: 'all',
      sortBy: 'hot',
      posts: [],
      trendingTickers: [],
      subreddits: []
    }
  },
  mounted() {
    this.loadInitialData()
  },
  methods: {
    async loadInitialData() {
      try {
        const response = await redditAPI.getStatus()
        if (response.data.success) {
          this.subreddits = response.data.data.subreddits || []
        }
        this.loadTrendingTickers()
        this.loadPosts()
      } catch (error) {
        console.error('Reddit 초기 데이터 로드 실패:', error)
      }
    },
    async loadTrendingTickers() {
      this.loadingTrending = true
      try {
        const response = await redditAPI.getTrendingTickers(15)
        if (response.data.success) {
          this.trendingTickers = response.data.data || []
        }
      } catch (error) {
        console.error('트렌딩 티커 로드 실패:', error)
      } finally {
        this.loadingTrending = false
      }
    },
    async loadPosts() {
      this.loading = true
      try {
        let response
        if (this.selectedSubreddit === 'all') {
          response = await redditAPI.getStockPosts(10)
        } else {
          response = await redditAPI.getSubredditPosts(this.selectedSubreddit, this.sortBy, 25)
        }
        if (response.data.success) {
          this.posts = response.data.data || []
        }
      } catch (error) {
        console.error('게시물 로드 실패:', error)
      } finally {
        this.loading = false
      }
    },
    async searchPosts() {
      if (!this.searchQuery.trim()) return
      this.loading = true
      try {
        const response = await redditAPI.searchPosts(this.searchQuery, 25)
        if (response.data.success) {
          this.posts = response.data.data || []
          this.selectedSubreddit = ''
        }
      } catch (error) {
        console.error('검색 실패:', error)
      } finally {
        this.loading = false
      }
    },
    selectSubreddit(sub) {
      this.selectedSubreddit = sub
      this.searchQuery = ''
      this.loadPosts()
    },
    searchTicker(ticker) {
      this.searchQuery = ticker
      this.searchPosts()
    },
    openPost(permalink) {
      window.open(permalink, '_blank')
    },
    formatTime(dateString) {
      if (!dateString) return ''
      const date = new Date(dateString)
      const now = new Date()
      const diff = Math.floor((now - date) / 1000)

      if (diff < 60) return `${diff}초 전`
      if (diff < 3600) return `${Math.floor(diff / 60)}분 전`
      if (diff < 86400) return `${Math.floor(diff / 3600)}시간 전`
      return `${Math.floor(diff / 86400)}일 전`
    },
    formatNumber(num) {
      if (num >= 1000000) return (num / 1000000).toFixed(1) + 'M'
      if (num >= 1000) return (num / 1000).toFixed(1) + 'K'
      return num.toString()
    },
    truncateText(text, maxLength) {
      if (!text) return ''
      if (text.length <= maxLength) return text
      return text.substring(0, maxLength) + '...'
    }
  }
}
</script>

<style scoped>
.reddit-page {
  padding: 20px;
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 24px;
}

.page-header h1 {
  font-size: 24px;
  font-weight: 600;
  color: #1f2937;
  margin: 0 0 8px 0;
}

.subtitle {
  color: #6b7280;
  font-size: 14px;
  margin: 0;
}

/* Content Grid */
.content-grid {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* Trending Section */
.trending-section {
  background: white;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.section-header h2 {
  margin: 0;
  font-size: 18px;
  color: #1f2937;
}

.refresh-btn {
  background: #ff4500;
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}

.refresh-btn:hover:not(:disabled) {
  background: #e03d00;
}

.refresh-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.ticker-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 12px;
}

.ticker-card {
  background: linear-gradient(135deg, #ff4500 0%, #ff6b35 100%);
  color: white;
  padding: 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: transform 0.2s;
  text-align: center;
}

.ticker-card:hover {
  transform: translateY(-2px);
}

.ticker-rank {
  font-size: 12px;
  opacity: 0.8;
}

.ticker-symbol {
  font-size: 18px;
  font-weight: bold;
  margin: 4px 0;
}

.ticker-mentions {
  font-size: 11px;
  opacity: 0.9;
}

/* Search Section */
.search-section {
  background: white;
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.search-box {
  display: flex;
  gap: 12px;
}

.search-box input {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 14px;
  color: #1f2937;
}

.search-box input::placeholder {
  color: #9ca3af;
}

.search-box input:focus {
  outline: none;
  border-color: #ff4500;
}

.search-box button {
  background: #ff4500;
  color: white;
  border: none;
  padding: 12px 24px;
  border-radius: 8px;
  cursor: pointer;
  font-weight: 500;
}

.search-box button:hover:not(:disabled) {
  background: #e03d00;
}

.search-box button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Tab Navigation */
.tab-navigation {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.tab-btn {
  background: white;
  border: 1px solid #d1d5db;
  padding: 8px 16px;
  border-radius: 20px;
  cursor: pointer;
  font-size: 13px;
  color: #6b7280;
  transition: all 0.2s;
}

.tab-btn:hover {
  border-color: #ff4500;
  color: #ff4500;
}

.tab-btn.active {
  background: #ff4500;
  border-color: #ff4500;
  color: white;
}

/* Sort Options */
.sort-options {
  display: flex;
  align-items: center;
  gap: 12px;
}

.sort-options label {
  color: #6b7280;
  font-size: 14px;
}

.sort-options select {
  padding: 8px 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 14px;
  color: #1f2937;
  cursor: pointer;
}

/* Posts Section */
.posts-section {
  min-height: 400px;
}

.posts-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.post-card {
  background: white;
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  cursor: pointer;
  transition: box-shadow 0.2s;
}

.post-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.post-header {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: #6b7280;
  margin-bottom: 8px;
}

.subreddit {
  color: #ff4500;
  font-weight: 500;
}

.post-title {
  margin: 0 0 8px 0;
  font-size: 16px;
  font-weight: 500;
  color: #1f2937;
  line-height: 1.4;
}

.post-preview {
  margin: 0 0 12px 0;
  font-size: 13px;
  color: #6b7280;
  line-height: 1.5;
}

.post-meta {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: #6b7280;
  margin-bottom: 8px;
}

.post-meta .icon {
  margin-right: 4px;
}

.score .icon {
  color: #ff4500;
}

.tickers {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.ticker-tag {
  background: #fff7ed;
  color: #ea580c;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
}

.ticker-tag:hover {
  background: #ffedd5;
}

.flair {
  display: inline-block;
  background: #e5e7eb;
  color: #4b5563;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  margin-top: 8px;
}

/* Loading & Empty States */
.loading-spinner {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px;
  color: #6b7280;
}

.spinner {
  width: 32px;
  height: 32px;
  border: 3px solid #e5e7eb;
  border-top-color: #ff4500;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.empty-state {
  text-align: center;
  padding: 40px;
  color: #6b7280;
}

/* Responsive */
@media (max-width: 768px) {
  .reddit-page {
    padding: 16px;
  }

  .api-warning {
    flex-direction: column;
  }

  .search-box {
    flex-direction: column;
  }

  .tab-navigation {
    overflow-x: auto;
    flex-wrap: nowrap;
    padding-bottom: 8px;
  }

  .ticker-grid {
    grid-template-columns: repeat(auto-fill, minmax(100px, 1fr));
  }
}
</style>
