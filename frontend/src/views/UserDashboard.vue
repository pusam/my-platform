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

      <!-- 상단 탭 (ADMIN만 표시) -->
      <div v-if="isAdmin" class="dashboard-tabs">
        <button
          :class="['dash-tab', { active: activeTab === 'user' }]"
          @click="activeTab = 'user'"
        >🏠 유저 대시보드</button>
        <button
          :class="['dash-tab', { active: activeTab === 'system' }]"
          @click="activeTab = 'system'"
        >🛠️ 시스템 대시보드</button>
      </div>

      <!-- 시스템 대시보드 (ADMIN만) -->
      <div v-if="isAdmin && activeTab === 'system'">
        <AdminDashboard :embedded="true" />
      </div>

      <!-- 유저 대시보드 -->
      <div v-show="activeTab === 'user'">

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

      <!-- 시장 정보 위젯 -->
      <section class="market-info-section">
        <MarketInfoWidget />
      </section>

      <!-- 투자 섹션 -->
      <section class="menu-section invest-section">
        <div class="section-header">
          <span class="section-icon">📈</span>
          <h2>투자</h2>
        </div>

        <!-- 메인 카드: 주식 트레이딩 -->
        <article class="invest-hero-card" @click="goToStockDashboard">
          <div class="invest-hero-bg"></div>
          <div class="invest-hero-content">
            <div class="invest-hero-icon">
              <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M12 2L2 7l10 5 10-5-10-5z"/>
                <path d="M2 17l10 5 10-5"/>
                <path d="M2 12l10 5 10-5"/>
              </svg>
            </div>
            <div class="invest-hero-text">
              <h3>주식 트레이딩 대시보드 <span class="v2-badge">V2</span></h3>
              <p>AI 전략 · 시장 지도 · 스마트 머니 · 리서치</p>
            </div>
            <span class="invest-hero-arrow">→</span>
          </div>
        </article>

        <!--
          판정 관제실 — ADMIN 전용 운영 콘솔. 주식 허브 탭이 아니라 별도 라우트라
          (IA 규칙의 명시적 예외, CLAUDE.md §7) 여기서 직접 들어갈 입구를 둔다.
        -->
        <article v-if="isAdmin" class="control-room-card" @click="goToControlRoom">
          <div class="cr-icon">🛰</div>
          <div class="cr-text">
            <h3>판정 관제실</h3>
            <p>판정 캘린더 · 봇 게이트 · FLAGGED · AI 크루 <span class="cr-tag">읽기 전용</span></p>
          </div>
          <span class="cr-arrow">→</span>
        </article>
      </section>

      <!-- 관리 섹션 -->
      <section class="menu-section">
        <div class="section-header">
          <span class="section-icon">⚙️</span>
          <h2>관리</h2>
        </div>
        <div class="menu-grid">
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

          <article class="menu-card diet" @click="$router.push('/diet')">
            <div class="card-icon diet-icon">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M18 8h1a4 4 0 010 8h-1"/><path d="M2 8h16v9a4 4 0 01-4 4H6a4 4 0 01-4-4V8z"/><line x1="6" y1="1" x2="6" y2="4"/><line x1="10" y1="1" x2="10" y2="4"/><line x1="14" y1="1" x2="14" y2="4"/>
              </svg>
            </div>
            <h3>식단 관리</h3>
            <p>매일 식단과 영양소를 기록합니다.</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>

          <article class="menu-card exercise" @click="$router.push('/exercise')">
            <div class="card-icon exercise-icon">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M6.5 6.5l11 11"/><path d="M21 3l-3.5 3.5"/><path d="M3 21l3.5-3.5"/><path d="M18.5 5.5l-2 2"/><path d="M5.5 18.5l2-2"/><path d="M20 4l-1 1"/><path d="M4 20l1-1"/>
              </svg>
            </div>
            <h3>운동 관리</h3>
            <p>운동 기록과 소모 칼로리를 추적합니다.</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>

          <article class="menu-card lotto" @click="$router.push('/lotto')">
            <div class="card-icon lotto-icon">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="8" cy="9" r="4"/><circle cx="16" cy="15" r="4"/><path d="M12 3v2"/><path d="M20 8l-1.5 1.5"/>
              </svg>
            </div>
            <h3>로또 분석</h3>
            <p>균등성 검정과 인기 조합 회피 생성기입니다.</p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>

          <article class="menu-card asset" @click="goToAsset">
            <div class="card-icon asset-icon">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <line x1="12" y1="1" x2="12" y2="23"/>
                <path d="M17 5H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6"/>
              </svg>
            </div>
            <h3>자산 관리</h3>
            <p>총 자산 {{ formatCurrency(assetSummary.totalAssets) }} <span :class="assetSummary.totalProfit >= 0 ? 'text-positive' : 'text-negative'">({{ assetSummary.totalProfit >= 0 ? '+' : '' }}{{ assetSummary.profitRate?.toFixed(2) || 0 }}%)</span></p>
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
            <p>이번 달 <span :class="financeSummary.balance >= 0 ? 'text-positive' : 'text-negative'">{{ formatCurrency(financeSummary.balance) }}</span></p>
            <span class="card-arrow">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </span>
          </article>
        </div>
      </section>

      <!-- 기타 섹션 -->
      <section class="menu-section">
        <div class="section-header">
          <span class="section-icon">📋</span>
          <h2>기타</h2>
        </div>
        <div class="menu-grid">
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

        </div>
      </section>

      </div><!-- /v-show user -->

    </div>
  </div>
</template>

<script>
import { newsAPI, financeAPI, assetAPI } from '../utils/api';
import { UserManager } from '../utils/auth';
import MarketInfoWidget from '../components/MarketInfoWidget.vue';
import AdminDashboard from './AdminDashboard.vue';

export default {
  name: 'UserDashboard',
  components: {
    MarketInfoWidget,
    AdminDashboard
  },
  data() {
    return {
      activeTab: 'user',
      isAdmin: false,
      username: '',
      newsList: [],
      financeSummary: {
        totalIncome: 0,
        totalExpense: 0,
        balance: 0
      },
      assetSummary: {
        totalAssets: 0,
        totalProfit: 0,
        profitRate: 0
      },
    }
  },
  mounted() {
    this.username = localStorage.getItem('username') || 'User'
    this.isAdmin = localStorage.getItem('role') === 'ADMIN'
    this.loadNews()
    this.loadFinanceSummary()
    this.loadAssetSummary()
  },
  methods: {
    async loadFinanceSummary() {
      try {
        const now = new Date()
        const response = await financeAPI.getMonthlyTransactions(now.getFullYear(), now.getMonth() + 1)
        if (response.data.success) {
          const data = response.data.data
          this.financeSummary = {
            totalIncome: data.totalIncome || 0,
            totalExpense: data.totalExpense || 0,
            balance: data.balance || 0
          }
        }
      } catch (error) {
        console.error('가계부 요약 로드 실패:', error)
      }
    },
    async loadAssetSummary() {
      try {
        const response = await assetAPI.getAssetSummary()
        if (response.data.success) {
          const data = response.data.data
          this.assetSummary = {
            totalAssets: data.totalCurrentValue || 0,
            totalProfit: data.totalProfit || 0,
            profitRate: data.profitRate || 0
          }
        }
      } catch (error) {
        console.error('자산 요약 로드 실패:', error)
      }
    },
    formatCurrency(value) {
      if (!value) return '0원'
      return new Intl.NumberFormat('ko-KR').format(value) + '원'
    },
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
    goToFiles() {
      this.$router.push('/files')
    },
    goToFinance() {
      this.$router.push('/finance')
    },
    goToCar() {
      this.$router.push('/car')
    },
    goToControlRoom() {
      this.$router.push('/control-room')
    },
    goToStockDashboard() {
      this.$router.push('/stock-dashboard')
    },
    logout() {
      // UserManager.logout() 로 통일 — 손수 removeItem 은 jwt_refresh_token 을 안 지워
      // 라우터 가드(canRefresh)가 세션 살아있다고 보고 /login→/user 로 되돌려 "로그아웃 반응 없음" 버그였음.
      UserManager.logout()   // AT+RT 삭제 + 서버 Redis 토큰 best-effort 삭제
      this.$router.push('/login')
    }
  }
}
</script>

<style scoped>
/* 판정 관제실 진입 카드 — ADMIN 전용. 운영 콘솔이라 투자 카드와 톤을 구분한다. */
.control-room-card {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 12px;
  padding: 14px 18px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-left: 3px solid #9b4dff;
  border-radius: var(--card-radius);
  cursor: pointer;
  transition: border-color 0.2s, transform 0.2s;
}
.control-room-card:hover { border-color: #9b4dff; transform: translateY(-1px); }
.control-room-card .cr-icon { font-size: 26px; line-height: 1; }
.control-room-card .cr-text { flex: 1; min-width: 0; }
.control-room-card h3 { margin: 0 0 3px; font-size: 15px; color: var(--text-primary); }
.control-room-card p { margin: 0; font-size: 12px; color: var(--text-muted); }
.control-room-card .cr-tag {
  margin-left: 6px;
  padding: 1px 6px;
  border: 1px solid #9b4dff;
  border-radius: 4px;
  color: #b98cff;
  font-size: 10px;
}
.control-room-card .cr-arrow { color: var(--text-muted); font-size: 18px; }

/* 환영 카드 */
.welcome-card {
  background: rgba(30, 30, 50, 0.85);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: var(--card-padding);
  margin-bottom: var(--section-gap);
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.3);
  display: flex;
  justify-content: space-between;
  align-items: center;
  overflow: hidden;
  position: relative;
  border: 1px solid rgba(255, 255, 255, 0.08);
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

/* 메뉴 섹션 */
.menu-section {
  margin-bottom: 2rem;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 1rem;
  padding: 12px 16px;
  border-radius: 12px;
  background: rgba(30, 30, 50, 0.85);
  backdrop-filter: blur(10px);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
  border-left: 4px solid;
  border-image: linear-gradient(135deg, #818cf8 0%, #764ba2 100%) 1;
}

.section-icon {
  font-size: 1.5rem;
  filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.1));
}

.section-header h2 {
  font-size: 1.25rem;
  font-weight: 700;
  margin: 0;
  color: #f0f0f5;
  text-shadow: none;
}

/* 메뉴 그리드 */
.menu-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 20px;
}

.menu-card {
  background: rgba(30, 30, 50, 0.85);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: var(--card-padding);
  cursor: pointer;
  transition: all 0.3s ease;
  border: 2px solid rgba(255, 255, 255, 0.08);
  position: relative;
  overflow: hidden;
}

.menu-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.3);
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

.card-icon.files {
  background: linear-gradient(135deg, rgba(93, 173, 226, 0.15) 0%, rgba(52, 152, 219, 0.15) 100%);
  color: #3498db;
}

.card-icon.car-icon {
  background: linear-gradient(135deg, rgba(52, 73, 94, 0.25) 0%, rgba(44, 62, 80, 0.25) 100%);
  color: #94b8d8;
}

.card-icon.sector-icon {
  background: linear-gradient(135deg, rgba(79, 70, 229, 0.15) 0%, rgba(139, 92, 246, 0.15) 100%);
  color: #4F46E5;
}

.menu-card.car {
  background: linear-gradient(135deg, rgba(20, 25, 40, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%);
  border: 2px solid rgba(52, 73, 94, 0.3);
}

.menu-card.car:hover {
  border-color: #34495e;
  box-shadow: 0 20px 40px rgba(52, 73, 94, 0.25);
}

.menu-card.car h3 {
  color: #94b8d8;
}

/* 식단 카드 */
.card-icon.lotto-icon { background: linear-gradient(135deg, rgba(99,102,241,0.15) 0%, rgba(168,85,247,0.15) 100%); color: #a5b4fc; }
.menu-card.lotto { background: linear-gradient(135deg, rgba(25, 22, 45, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%); border: 2px solid rgba(99,102,241,0.2); }
.menu-card.lotto:hover { border-color: #6366f1; box-shadow: 0 20px 40px rgba(99,102,241,0.25); }
.menu-card.lotto h3 { color: #a5b4fc; }

.card-icon.diet-icon { background: linear-gradient(135deg, rgba(251,191,36,0.15) 0%, rgba(245,158,11,0.15) 100%); color: #f59e0b; }
.menu-card.diet { background: linear-gradient(135deg, rgba(30, 25, 15, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%); border: 2px solid rgba(245,158,11,0.2); }
.menu-card.diet:hover { border-color: #f59e0b; box-shadow: 0 20px 40px rgba(245,158,11,0.25); }
.menu-card.diet h3 { color: #fbbf24; }

/* 운동 카드 */
.card-icon.exercise-icon { background: linear-gradient(135deg, rgba(16,185,129,0.15) 0%, rgba(5,150,105,0.15) 100%); color: #10b981; }
.menu-card.exercise { background: linear-gradient(135deg, rgba(15, 30, 25, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%); border: 2px solid rgba(16,185,129,0.2); }
.menu-card.exercise:hover { border-color: #10b981; box-shadow: 0 20px 40px rgba(16,185,129,0.25); }
.menu-card.exercise h3 { color: #6ee7b7; }

/* AI 분석 카드 */
.card-icon.ai-analysis-icon {
  background: linear-gradient(135deg, rgba(236, 72, 153, 0.15) 0%, rgba(168, 85, 247, 0.15) 100%);
  color: #a855f7;
}

.menu-card.ai-analysis {
  background: linear-gradient(135deg, rgba(25, 15, 35, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%);
  border: 2px solid rgba(168, 85, 247, 0.3);
}

.menu-card.ai-analysis:hover {
  border-color: #a855f7;
  box-shadow: 0 20px 40px rgba(168, 85, 247, 0.25);
}

.menu-card.ai-analysis h3 {
  color: #c084fc;
}

/* ===== 투자 섹션 ===== */

/* 메인 히어로 카드 */
.invest-hero-card {
  position: relative;
  border-radius: 20px;
  padding: 28px 32px;
  cursor: pointer;
  overflow: hidden;
  margin-bottom: 16px;
  transition: all 0.3s ease;
  border: 1px solid rgba(129, 140, 248, 0.25);
}

.invest-hero-bg {
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, #1e1b4b 0%, #312e81 40%, #4338ca 100%);
  z-index: 0;
}

.invest-hero-bg::after {
  content: '';
  position: absolute;
  top: -50%;
  left: -50%;
  width: 200%;
  height: 200%;
  background: linear-gradient(45deg, transparent 30%, rgba(255,255,255,0.04) 50%, transparent 70%);
  animation: hero-shine 4s infinite;
}

@keyframes hero-shine {
  0% { transform: translateX(-100%) rotate(45deg); }
  100% { transform: translateX(100%) rotate(45deg); }
}

.invest-hero-content {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 20px;
}

.invest-hero-icon {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.12);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #a5b4fc;
  flex-shrink: 0;
}

.invest-hero-text {
  flex: 1;
}

.invest-hero-text h3 {
  margin: 0 0 6px;
  font-size: 18px;
  font-weight: 700;
  color: #fff;
  display: flex;
  align-items: center;
  gap: 10px;
}

.v2-badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 800;
  background: linear-gradient(135deg, #fbbf24, #f59e0b);
  color: #78350f;
  letter-spacing: 0.5px;
}

.invest-hero-text p {
  margin: 0;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.6);
}

.invest-hero-arrow {
  font-size: 24px;
  color: rgba(255, 255, 255, 0.4);
  transition: all 0.3s ease;
  flex-shrink: 0;
}

.invest-hero-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 20px 50px rgba(67, 56, 202, 0.4);
  border-color: rgba(129, 140, 248, 0.5);
}

.invest-hero-card:hover .invest-hero-arrow {
  color: #fff;
  transform: translateX(4px);
}

/* 서브 카드 그리드 */
.invest-sub-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.invest-sub-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  border-radius: 16px;
  background: rgba(30, 30, 50, 0.85);
  border: 1px solid rgba(255, 255, 255, 0.08);
  cursor: pointer;
  transition: all 0.25s ease;
}

.invest-sub-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 12px 30px rgba(0, 0, 0, 0.3);
  border-color: rgba(255, 255, 255, 0.15);
}

.invest-sub-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.invest-sub-icon.futures-gradient {
  background: linear-gradient(135deg, #059669, #10b981);
  color: #fff;
}

.invest-sub-icon.news-gradient {
  background: linear-gradient(135deg, #2563eb, #3b82f6);
  color: #fff;
}

.invest-sub-icon.timing-gradient {
  background: linear-gradient(135deg, #7c3aed, #8b5cf6);
  color: #fff;
}

.invest-sub-text h4 {
  margin: 0 0 2px;
  font-size: 14px;
  font-weight: 700;
  color: #f0f0f5;
}

.invest-sub-text p {
  margin: 0;
  font-size: 12px;
  color: #7878a0;
}

@media (max-width: 768px) {
  .invest-sub-grid {
    grid-template-columns: 1fr;
  }
  .invest-hero-card {
    padding: 20px;
  }
  .invest-hero-text h3 {
    font-size: 16px;
  }
}

/* 자산 관리 카드 */
.card-icon.asset-icon {
  background: linear-gradient(135deg, rgba(247, 183, 51, 0.15) 0%, rgba(252, 74, 26, 0.15) 100%);
  color: #f7b733;
}

.menu-card.asset {
  background: linear-gradient(135deg, rgba(30, 25, 15, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%);
  border: 2px solid rgba(247, 183, 51, 0.3);
}

.menu-card.asset:hover {
  border-color: #f7b733;
  box-shadow: 0 20px 40px rgba(247, 183, 51, 0.25);
}

.menu-card.asset h3 {
  color: #fbbf24;
}

/* 가계부 카드 */
.card-icon.finance-icon {
  background: linear-gradient(135deg, rgba(46, 204, 113, 0.15) 0%, rgba(39, 174, 96, 0.15) 100%);
  color: #2ecc71;
}

.card-icon.stock-icon {
  background: linear-gradient(135deg, rgba(231, 76, 60, 0.15) 0%, rgba(192, 57, 43, 0.15) 100%);
  color: #e74c3c;
}

.menu-card.stock {
  background: linear-gradient(135deg, rgba(35, 15, 15, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%);
  border: 2px solid rgba(231, 76, 60, 0.3);
}

.menu-card.stock:hover {
  border-color: #e74c3c;
  box-shadow: 0 20px 40px rgba(231, 76, 60, 0.25);
}

.menu-card.stock h3 {
  color: #f87171;
}

.menu-card.finance {
  background: linear-gradient(135deg, rgba(15, 30, 20, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%);
  border: 2px solid rgba(46, 204, 113, 0.3);
}

.menu-card.finance:hover {
  border-color: #2ecc71;
  box-shadow: 0 20px 40px rgba(46, 204, 113, 0.25);
}

.menu-card.finance h3 {
  color: #6ee7b7;
}

/* 뉴스 카드 */
.card-icon.news-icon {
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.15) 0%, rgba(99, 102, 241, 0.15) 100%);
  color: #3b82f6;
}

.menu-card.news {
  background: linear-gradient(135deg, rgba(15, 20, 40, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%);
  border: 2px solid rgba(59, 130, 246, 0.3);
}

.menu-card.news:hover {
  border-color: #3b82f6;
  box-shadow: 0 20px 40px rgba(59, 130, 246, 0.25);
}

.menu-card.news h3 {
  color: #93c5fd;
}


/* 투자자 매매 동향 카드 */
.card-icon.investor-icon {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.15) 0%, rgba(118, 75, 162, 0.15) 100%);
  color: var(--primary-start);
}

.menu-card.investor {
  background: linear-gradient(135deg, rgba(20, 20, 45, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%);
  border: 2px solid rgba(129, 140, 248, 0.3);
}

.menu-card.investor:hover {
  border-color: #818cf8;
  box-shadow: 0 20px 40px rgba(129, 140, 248, 0.25);
}

.menu-card.investor h3 {
  color: #a5b4fc;
}

/* 연속 매수 카드 */
.card-icon.consecutive-icon {
  background: linear-gradient(135deg, rgba(237, 137, 54, 0.15) 0%, rgba(221, 107, 32, 0.15) 100%);
  color: #ed8936;
}

.menu-card.consecutive {
  background: linear-gradient(135deg, rgba(30, 22, 12, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%);
  border: 2px solid rgba(237, 137, 54, 0.3);
}

.menu-card.consecutive:hover {
  border-color: #ed8936;
  box-shadow: 0 20px 40px rgba(237, 137, 54, 0.25);
}

.menu-card.consecutive h3 {
  color: #fdba74;
}

/* 수급 급증 카드 */
.card-icon.surge-icon {
  background: linear-gradient(135deg, rgba(229, 62, 62, 0.15) 0%, rgba(197, 48, 48, 0.15) 100%);
  color: #e53e3e;
}

.menu-card.surge {
  background: linear-gradient(135deg, rgba(35, 15, 15, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%);
  border: 2px solid rgba(229, 62, 62, 0.3);
}

.menu-card.surge:hover {
  border-color: #e53e3e;
  box-shadow: 0 20px 40px rgba(229, 62, 62, 0.25);
}

.menu-card.surge h3 {
  color: #fca5a5;
}

/* 실적 스크리너 카드 */
.card-icon.screener-icon {
  background: linear-gradient(135deg, rgba(74, 222, 128, 0.15) 0%, rgba(34, 197, 94, 0.15) 100%);
  color: #4ade80;
}

.menu-card.screener {
  background: linear-gradient(135deg, rgba(15, 30, 20, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%);
  border: 2px solid rgba(74, 222, 128, 0.3);
}

.menu-card.screener:hover {
  border-color: #4ade80;
  box-shadow: 0 20px 40px rgba(74, 222, 128, 0.25);
}

.menu-card.screener h3 {
  color: #6ee7b7;
}

/* 시장 지표 카드 */
.card-icon.market-timing-icon {
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.15) 0%, rgba(217, 119, 6, 0.15) 100%);
  color: #f59e0b;
}

.menu-card.market-timing {
  background: linear-gradient(135deg, rgba(30, 25, 15, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%);
  border: 2px solid rgba(245, 158, 11, 0.3);
}

.menu-card.market-timing:hover {
  border-color: #f59e0b;
  box-shadow: 0 20px 40px rgba(245, 158, 11, 0.25);
}

.menu-card.market-timing h3 {
  color: #fbbf24;
}

/* 트레이딩 지표 카드 */
.card-icon.trading-indicators-icon {
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.15) 0%, rgba(109, 40, 217, 0.15) 100%);
  color: #8b5cf6;
}

.menu-card.trading-indicators {
  background: linear-gradient(135deg, rgba(22, 18, 40, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%);
  border: 2px solid rgba(139, 92, 246, 0.3);
}

.menu-card.trading-indicators:hover {
  border-color: #8b5cf6;
  box-shadow: 0 20px 40px rgba(139, 92, 246, 0.25);
}

.menu-card.trading-indicators h3 {
  color: #a78bfa;
}

/* 종목 종합상세 카드 */
.card-icon.stock-detail-icon {
  background: linear-gradient(135deg, rgba(6, 182, 212, 0.15) 0%, rgba(14, 165, 233, 0.15) 100%);
  color: #06b6d4;
}

.menu-card.stock-detail {
  background: linear-gradient(135deg, rgba(12, 25, 30, 0.9) 0%, rgba(30, 30, 50, 0.9) 100%);
  border: 2px solid rgba(6, 182, 212, 0.3);
}

.menu-card.stock-detail:hover {
  border-color: #06b6d4;
  box-shadow: 0 20px 40px rgba(6, 182, 212, 0.25);
}

.menu-card.stock-detail h3 {
  color: #67e8f9;
}

/* AI 뱃지 (메뉴 카드용) */
.menu-ai-badge {
  display: inline-block;
  background: linear-gradient(135deg, #818cf8 0%, #764ba2 100%);
  color: white;
  padding: 2px 8px;
  border-radius: 8px;
  font-size: 10px;
  font-weight: 700;
  margin-left: 6px;
  vertical-align: middle;
}

/* 텍스트 색상 유틸리티 */
.text-positive {
  color: #ef4444;
}

.text-negative {
  color: #3b82f6;
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


.ai-decoration::after {
  content: '';
  position: absolute;
  right: 150px;
  bottom: -50px;
  width: 200px;
  height: 200px;
  background: var(--border-light);
  border-radius: 50%;
}

/* 반응형 */
@media (max-width: 768px) {
  .welcome-card {
    padding: var(--card-padding);
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

/* 시장 정보 섹션 */
.market-info-section {
  margin-bottom: var(--section-gap);
}

/* 대시보드 탭 */
.dashboard-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 24px;
  background: rgba(30, 30, 50, 0.85);
  border-radius: 12px;
  padding: 4px;
  border: 1px solid rgba(255, 255, 255, 0.08);
}
.dash-tab {
  flex: 1;
  padding: 14px 16px;
  border: none;
  background: transparent;
  border-radius: 10px;
  font-size: 15px;
  font-weight: 700;
  color: #7878a0;
  cursor: pointer;
  transition: all 0.2s;
}
.dash-tab:hover {
  color: #f0f0f5;
  background: rgba(255, 255, 255, 0.08);
}
.dash-tab.active {
  background: rgba(255, 255, 255, 0.1);
  color: #f0f0f5;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}

@media (max-width: 768px) {
  .dashboard-tabs {
    padding: 3px;
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
    scrollbar-width: none;
    flex-wrap: nowrap;
  }
  .dashboard-tabs::-webkit-scrollbar { display: none; }
  .dash-tab {
    padding: 10px 12px;
    font-size: 13px;
    flex: 0 0 auto;
    white-space: nowrap;
    min-width: 80px;
  }
}

@media (max-width: 480px) {
  .welcome-content h2 { font-size: 18px; }
  .welcome-card { padding: 16px; }
  .ai-banner { padding: 16px; }
  .ai-icon { width: 52px; height: 52px; }
  .ai-text h3 { font-size: 17px; }
  .news-section { padding: 14px; }
  .news-item { padding: 12px 14px; }
}
</style>
