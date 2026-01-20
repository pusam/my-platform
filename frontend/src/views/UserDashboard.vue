<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>대시보드</h1>
        <div class="header-actions">
          <div class="header-user">
            <div class="user-avatar">{{ username.charAt(0) }}</div>
            <span>{{ username }}</span>
          </div>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

      <!-- 환영 메시지 -->
      <section class="welcome-card">
        <div class="welcome-content">
          <h2>환영합니다, <span class="highlight">{{ username }}</span>님!</h2>
          <p>플랫폼에 접속하셨습니다. 아래 메뉴에서 원하는 기능을 선택하세요.</p>
        </div>
        <div class="welcome-decoration">
          <svg viewBox="0 0 200 200" xmlns="http://www.w3.org/2000/svg">
            <path fill="rgba(102, 126, 234, 0.1)" d="M45.7,-51.9C59.1,-41.5,69.7,-26.2,71.8,-9.8C73.9,6.6,67.5,24.2,56.4,37.2C45.3,50.3,29.5,58.8,12.4,62.8C-4.7,66.8,-23.1,66.3,-38.4,58.5C-53.7,50.7,-65.9,35.6,-70.3,18.5C-74.7,1.4,-71.3,-17.7,-61.5,-32.1C-51.7,-46.5,-35.5,-56.2,-19.1,-65.1C-2.7,-74,14,-82.1,28.6,-77.3C43.2,-72.5,55.7,-54.8,45.7,-51.9Z" transform="translate(100 100)" />
          </svg>
        </div>
      </section>

      <!-- 메뉴 그리드 -->
      <section class="menu-grid">
        <article class="menu-card" @click="goToBoard">
          <div class="card-icon board">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
              <polyline points="14,2 14,8 20,8"/>
              <line x1="16" y1="13" x2="8" y2="13"/>
              <line x1="16" y1="17" x2="8" y2="17"/>
              <polyline points="10,9 9,9 8,9"/>
            </svg>
          </div>
          <h3>게시판</h3>
          <p>자유롭게 글을 작성하고 파일을 공유할 수 있습니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card" @click="goToMyContent">
          <div class="card-icon content">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7"/>
              <path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z"/>
            </svg>
          </div>
          <h3>내 콘텐츠</h3>
          <p>작성한 글과 파일을 확인하고 관리합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card" @click="goToAsset">
          <div class="card-icon asset">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <line x1="12" y1="1" x2="12" y2="23"/>
              <path d="M17 5H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6"/>
            </svg>
          </div>
          <h3>자산 관리</h3>
          <p>보유한 금/은 자산을 관리하고 손익을 확인합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card" @click="goToSettings">
          <div class="card-icon settings">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <circle cx="12" cy="12" r="3"/>
              <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-2 2 2 2 0 01-2-2v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83 0 2 2 0 010-2.83l.06-.06a1.65 1.65 0 00.33-1.82 1.65 1.65 0 00-1.51-1H3a2 2 0 01-2-2 2 2 0 012-2h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 010-2.83 2 2 0 012.83 0l.06.06a1.65 1.65 0 001.82.33H9a1.65 1.65 0 001-1.51V3a2 2 0 012-2 2 2 0 012 2v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 0 2 2 0 010 2.83l-.06.06a1.65 1.65 0 00-.33 1.82V9a1.65 1.65 0 001.51 1H21a2 2 0 012 2 2 2 0 01-2 2h-.09a1.65 1.65 0 00-1.51 1z"/>
            </svg>
          </div>
          <h3>내 설정</h3>
          <p>개인 정보 및 비밀번호를 관리합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card gold" @click="goToGold">
          <div class="card-icon gold-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <circle cx="12" cy="12" r="10"/>
              <path d="M12 6v12"/>
              <path d="M15 9.5c0-1.5-1.5-2.5-3-2.5s-3 1-3 2.5c0 2 6 1 6 4 0 1.5-1.5 2.5-3 2.5s-3-1-3-2.5"/>
            </svg>
          </div>
          <h3>금 시세</h3>
          <p>실시간 금 시세 정보를 확인합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card silver" @click="goToSilver">
          <div class="card-icon silver-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <circle cx="12" cy="12" r="10"/>
              <path d="M12 6v12"/>
              <path d="M15 9.5c0-1.5-1.5-2.5-3-2.5s-3 1-3 2.5c0 2 6 1 6 4 0 1.5-1.5 2.5-3 2.5s-3-1-3-2.5"/>
            </svg>
          </div>
          <h3>은 시세</h3>
          <p>실시간 은 시세 정보를 확인합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card" @click="goToFiles">
          <div class="card-icon files">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/>
            </svg>
          </div>
          <h3>내 파일</h3>
          <p>개인 파일과 폴더를 관리합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card finance" @click="goToFinance">
          <div class="card-icon finance-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <rect x="3" y="4" width="18" height="18" rx="2" ry="2"/>
              <line x1="16" y1="2" x2="16" y2="6"/>
              <line x1="8" y1="2" x2="8" y2="6"/>
              <line x1="3" y1="10" x2="21" y2="10"/>
            </svg>
          </div>
          <h3>가계부</h3>
          <p>월별 수입과 지출을 기록하고 관리합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card car" @click="goToCar">
          <div class="card-icon car-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M19 17h2c.6 0 1-.4 1-1v-3c0-.9-.7-1.7-1.5-1.9L18 10l-2.7-3.6c-.4-.5-1-.9-1.6-.9H10c-.7 0-1.3.4-1.6.9L5.5 10l-2 1.1C2.7 11.3 2 12.1 2 13v3c0 .6.4 1 1 1h2"/>
              <circle cx="7" cy="17" r="2"/>
              <circle cx="17" cy="17" r="2"/>
            </svg>
          </div>
          <h3>자동차 관리</h3>
          <p>정비 기록과 주행거리를 관리합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>
      </section>

      <!-- 오늘의 경제 뉴스 -->
      <section class="news-section" v-if="newsList.length > 0">
        <div class="news-header">
          <div class="news-title">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z"/>
              <path d="M7 7h10M7 11h10M7 15h7"/>
            </svg>
            <h3>오늘의 경제 뉴스</h3>
          </div>
          <span class="news-badge">AI 요약</span>
        </div>
        <div class="news-list">
          <article v-for="news in newsList" :key="news.id" class="news-item" @click="openNewsUrl(news.sourceUrl)">
            <div class="news-content">
              <h4>{{ news.title }}</h4>
              <p>{{ news.summary }}</p>
              <div class="news-meta">
                <span class="news-source">{{ news.sourceName }}</span>
                <span class="news-time">{{ formatNewsTime(news.summarizedAt) }}</span>
              </div>
            </div>
            <div class="news-arrow">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </div>
          </article>
        </div>
      </section>

      <!-- AI 상담 배너 -->
      <section class="ai-banner" @click="openAiChat">
        <div class="ai-banner-content">
          <div class="ai-icon">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M12 2a2 2 0 012 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 017 7h1a1 1 0 011 1v3a1 1 0 01-1 1h-1v1a2 2 0 01-2 2H5a2 2 0 01-2-2v-1H2a1 1 0 01-1-1v-3a1 1 0 011-1h1a7 7 0 017-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 012-2z"/>
              <circle cx="8.5" cy="14.5" r="1.5"/>
              <circle cx="15.5" cy="14.5" r="1.5"/>
              <path d="M9 18h6"/>
            </svg>
          </div>
          <div class="ai-text">
            <h3>AI 재무 상담사</h3>
            <p>자산 관리, 가계부, 투자에 대한 맞춤형 상담을 받아보세요</p>
          </div>
          <div class="ai-arrow">
            <span>상담 시작</span>
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </div>
        </div>
        <div class="ai-decoration"></div>
      </section>
    </div>
  </div>
</template>

<script>
import { newsAPI } from '../utils/api';

export default {
  name: 'UserDashboard',
  data() {
    return {
      username: '',
      newsList: []
    }
  },
  mounted() {
    this.username = localStorage.getItem('username') || 'User'
    this.loadNews()
  },
  methods: {
    async loadNews() {
      try {
        // 오늘 뉴스가 없으면 최근 뉴스 조회
        let response = await newsAPI.getTodayNews()
        if (response.data.data && response.data.data.length > 0) {
          this.newsList = response.data.data.slice(0, 5)
        } else {
          response = await newsAPI.getRecentNews()
          this.newsList = response.data.data ? response.data.data.slice(0, 5) : []
        }
      } catch (error) {
        console.error('뉴스 로드 실패:', error)
        this.newsList = []
      }
    },
    openNewsUrl(url) {
      if (url) {
        window.open(url, '_blank')
      }
    },
    formatNewsTime(dateStr) {
      if (!dateStr) return ''
      const date = new Date(dateStr)
      const now = new Date()
      const diffHours = Math.floor((now - date) / (1000 * 60 * 60))
      if (diffHours < 1) return '방금 전'
      if (diffHours < 24) return `${diffHours}시간 전`
      return date.toLocaleDateString('ko-KR')
    },
    goToBoard() {
      this.$router.push('/board')
    },
    goToMyContent() {
      this.$router.push('/my-content')
    },
    goToAsset() {
      this.$router.push('/asset')
    },
    goToSettings() {
      this.$router.push('/settings')
    },
    goToGold() {
      this.$router.push('/gold')
    },
    goToSilver() {
      this.$router.push('/silver')
    },
    goToFiles() {
      this.$router.push('/files')
    },
    goToFinance() {
      this.$router.push('/finance')
    },
    goToCar() {
      this.$router.push('/car')
    },
    openAiChat() {
      // 챗봇 열기 이벤트 발생
      window.dispatchEvent(new CustomEvent('open-chatbot'))
    },
    logout() {
      localStorage.removeItem('jwt_token')
      localStorage.removeItem('username')
      localStorage.removeItem('role')
      this.$router.push('/login')
    }
  }
}
</script>

<style scoped>
/* 환영 카드 */
.welcome-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 40px;
  margin-bottom: 30px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
  display: flex;
  justify-content: space-between;
  align-items: center;
  overflow: hidden;
  position: relative;
}

.welcome-content {
  position: relative;
  z-index: 1;
}

.welcome-content h2 {
  font-size: 28px;
  color: var(--text-primary);
  margin: 0 0 12px 0;
}

.welcome-content .highlight {
  background: var(--primary-gradient);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.welcome-content p {
  font-size: 16px;
  color: var(--text-muted);
  margin: 0;
}

.welcome-decoration {
  position: absolute;
  right: -50px;
  top: -50px;
  width: 300px;
  height: 300px;
  opacity: 0.5;
}

/* 사용자 아바타 */
.user-avatar {
  width: 36px;
  height: 36px;
  background: var(--primary-gradient);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-weight: 700;
  font-size: 16px;
}

/* 메뉴 그리드 */
.menu-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 20px;
  margin-bottom: 30px;
}

.menu-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 28px;
  cursor: pointer;
  transition: all 0.3s ease;
  border: 2px solid transparent;
  position: relative;
  overflow: hidden;
}

.menu-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.15);
  border-color: var(--primary-start);
}

.menu-card:hover .card-arrow {
  opacity: 1;
  transform: translateX(0);
}

.card-icon {
  width: 60px;
  height: 60px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
  transition: transform 0.3s ease;
}

.menu-card:hover .card-icon {
  transform: scale(1.1);
}

.card-icon.board {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.15) 0%, rgba(118, 75, 162, 0.15) 100%);
  color: var(--primary-start);
}

.card-icon.content {
  background: linear-gradient(135deg, rgba(79, 172, 254, 0.15) 0%, rgba(0, 242, 254, 0.15) 100%);
  color: #4facfe;
}

.card-icon.asset {
  background: linear-gradient(135deg, rgba(247, 183, 51, 0.15) 0%, rgba(252, 74, 26, 0.15) 100%);
  color: #f7b733;
}

.card-icon.settings {
  background: linear-gradient(135deg, rgba(108, 117, 125, 0.15) 0%, rgba(73, 80, 87, 0.15) 100%);
  color: #6c757d;
}

.card-icon.gold-icon {
  background: linear-gradient(135deg, rgba(255, 215, 0, 0.2) 0%, rgba(218, 165, 32, 0.2) 100%);
  color: #daa520;
}

.card-icon.silver-icon {
  background: linear-gradient(135deg, rgba(192, 192, 192, 0.2) 0%, rgba(128, 128, 128, 0.2) 100%);
  color: #808080;
}

.card-icon.files {
  background: linear-gradient(135deg, rgba(93, 173, 226, 0.15) 0%, rgba(52, 152, 219, 0.15) 100%);
  color: #3498db;
}

.card-icon.finance-icon {
  background: linear-gradient(135deg, rgba(46, 204, 113, 0.15) 0%, rgba(26, 188, 156, 0.15) 100%);
  color: #2ecc71;
}

.card-icon.car-icon {
  background: linear-gradient(135deg, rgba(52, 73, 94, 0.15) 0%, rgba(44, 62, 80, 0.15) 100%);
  color: #34495e;
}

.menu-card.car {
  background: linear-gradient(135deg, rgba(245, 247, 250, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(52, 73, 94, 0.2);
}

.menu-card.car:hover {
  border-color: #34495e;
  box-shadow: 0 20px 40px rgba(52, 73, 94, 0.15);
}

.menu-card.car h3 {
  color: #2c3e50;
}


.menu-card h3 {
  font-size: 20px;
  color: var(--text-primary);
  margin: 0 0 10px 0;
  font-weight: 600;
}

.menu-card p {
  font-size: 14px;
  color: var(--text-muted);
  margin: 0;
  line-height: 1.5;
}

.card-arrow {
  position: absolute;
  right: 24px;
  top: 50%;
  transform: translateX(10px) translateY(-50%);
  opacity: 0;
  transition: all 0.3s ease;
  color: var(--primary-start);
}

/* 금/은 특별 스타일 */
.menu-card.gold {
  background: linear-gradient(135deg, rgba(255, 249, 230, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(255, 215, 0, 0.3);
}

.menu-card.gold:hover {
  border-color: #ffd700;
  box-shadow: 0 20px 40px rgba(255, 215, 0, 0.2);
}

.menu-card.gold h3 {
  color: #b8860b;
}

.menu-card.silver {
  background: linear-gradient(135deg, rgba(248, 249, 250, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(192, 192, 192, 0.3);
}

.menu-card.silver:hover {
  border-color: #c0c0c0;
  box-shadow: 0 20px 40px rgba(128, 128, 128, 0.2);
}

.menu-card.silver h3 {
  color: #708090;
}

/* 뉴스 섹션 */
.news-section {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 28px;
  margin-bottom: 30px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
}

.news-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.news-title {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--text-primary);
}

.news-title svg {
  color: var(--primary-start);
}

.news-title h3 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.news-badge {
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
  gap: 12px;
}

.news-item {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 16px 20px;
  background: linear-gradient(135deg, #f8f9fa 0%, #fff 100%);
  border-radius: 14px;
  cursor: pointer;
  transition: all 0.2s ease;
  border: 1px solid var(--border-light);
}

.news-item:hover {
  border-color: var(--primary-start);
  box-shadow: 0 4px 15px rgba(102, 126, 234, 0.1);
  transform: translateX(4px);
}

.news-content {
  flex: 1;
  min-width: 0;
}

.news-content h4 {
  margin: 0 0 8px 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.news-content p {
  margin: 0 0 10px 0;
  font-size: 13px;
  color: var(--text-muted);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.news-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
}

.news-source {
  color: var(--primary-start);
  font-weight: 500;
}

.news-time {
  color: #adb5bd;
}

.news-arrow {
  color: #ced4da;
  transition: color 0.2s;
  flex-shrink: 0;
  padding-top: 4px;
}

.news-item:hover .news-arrow {
  color: var(--primary-start);
}

/* AI 상담 배너 */
.ai-banner {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 20px;
  padding: 32px 40px;
  cursor: pointer;
  transition: all 0.3s ease;
  position: relative;
  overflow: hidden;
  box-shadow: 0 10px 40px rgba(102, 126, 234, 0.3);
}

.ai-banner:hover {
  transform: translateY(-4px);
  box-shadow: 0 20px 50px rgba(102, 126, 234, 0.4);
}

.ai-banner:hover .ai-arrow svg {
  transform: translateX(4px);
}

.ai-banner-content {
  display: flex;
  align-items: center;
  gap: 24px;
  position: relative;
  z-index: 1;
}

.ai-icon {
  width: 80px;
  height: 80px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  flex-shrink: 0;
}

.ai-text {
  flex: 1;
}

.ai-text h3 {
  font-size: 24px;
  color: white;
  margin: 0 0 8px 0;
  font-weight: 700;
}

.ai-text p {
  font-size: 15px;
  color: rgba(255, 255, 255, 0.85);
  margin: 0;
}

.ai-arrow {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.2);
  padding: 12px 20px;
  border-radius: 12px;
  color: white;
  font-weight: 600;
  font-size: 15px;
  transition: all 0.3s ease;
}

.ai-arrow:hover {
  background: rgba(255, 255, 255, 0.3);
}

.ai-arrow svg {
  transition: transform 0.3s ease;
}

.ai-decoration {
  position: absolute;
  right: -100px;
  top: -100px;
  width: 300px;
  height: 300px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 50%;
}

.ai-decoration::after {
  content: '';
  position: absolute;
  right: 150px;
  bottom: -50px;
  width: 200px;
  height: 200px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 50%;
}

/* 반응형 */
@media (max-width: 768px) {
  .welcome-card {
    padding: 28px;
  }

  .welcome-content h2 {
    font-size: 22px;
  }

  .welcome-decoration {
    display: none;
  }

  .menu-grid {
    grid-template-columns: 1fr;
  }

  .ai-banner {
    padding: 24px;
  }

  .ai-banner-content {
    flex-direction: column;
    text-align: center;
    gap: 16px;
  }

  .ai-icon {
    width: 64px;
    height: 64px;
  }

  .ai-icon svg {
    width: 36px;
    height: 36px;
  }

  .ai-text h3 {
    font-size: 20px;
  }

  .ai-text p {
    font-size: 14px;
  }

  .ai-arrow {
    width: 100%;
    justify-content: center;
  }

  .ai-decoration {
    display: none;
  }

  .news-section {
    padding: 20px;
  }

  .news-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .news-item {
    padding: 14px 16px;
  }

  .news-content h4 {
    font-size: 14px;
  }

  .news-content p {
    font-size: 12px;
    -webkit-line-clamp: 3;
  }
}
</style>
