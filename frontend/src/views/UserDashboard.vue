<template>
  <div class="user-dashboard">
    <div class="header">
      <h1>사용자 대시보드</h1>
      <div class="user-info">
        <span>{{ username }}</span>
        <button @click="logout" class="logout-btn">로그아웃</button>
      </div>
    </div>

    <div class="dashboard-content">
      <div class="welcome-section">
        <h2>환영합니다, {{ username }}님!</h2>
        <p>플랫폼에 접속하셨습니다.</p>
      </div>

      <div class="user-sections">
        <div class="section">
          <div class="section-icon">📋</div>
          <h2>게시판</h2>
          <p>자유롭게 글을 작성하고 파일을 공유할 수 있습니다.</p>
          <button @click="goToBoard" class="action-btn">게시판 이동</button>
        </div>

        <div class="section">
          <div class="section-icon">📝</div>
          <h2>내 콘텐츠</h2>
          <p>작성한 글과 파일을 확인하고 관리합니다.</p>
          <button @click="goToMyContent" class="action-btn">콘텐츠 보기</button>
        </div>

        <div class="section">
          <div class="section-icon">💰</div>
          <h2>자산 관리</h2>
          <p>보유한 금/은 자산을 관리하고 손익을 확인합니다.</p>
          <button @click="goToAsset" class="action-btn asset-btn">자산 보기</button>
        </div>

        <div class="section">
          <div class="section-icon">⚙️</div>
          <h2>내 설정</h2>
          <p>개인 정보 및 비밀번호를 관리합니다.</p>
          <button @click="goToSettings" class="action-btn">설정 열기</button>
        </div>

        <div class="section gold-section">
          <div class="section-icon">🪙</div>
          <h2>금 시세</h2>
          <p>실시간 금 시세 정보를 확인합니다.</p>
          <button @click="goToGold" class="action-btn gold-btn">시세 확인</button>
        </div>

        <div class="section silver-section">
          <div class="section-icon">🥈</div>
          <h2>은 시세</h2>
          <p>실시간 은 시세 정보를 확인합니다.</p>
          <button @click="goToSilver" class="action-btn silver-btn">시세 확인</button>
        </div>

        <div class="section files-section">
          <div class="section-icon">📁</div>
          <h2>내 파일</h2>
          <p>개인 파일과 폴더를 관리합니다.</p>
          <button @click="goToFiles" class="action-btn files-btn">파일 관리</button>
        </div>
      </div>

      <div class="info-section">
        <h3>시스템 정보</h3>
        <div class="info-grid">
          <div class="info-item">
            <span class="info-label">계정 유형:</span>
            <span class="info-value">일반 사용자</span>
          </div>
          <div class="info-item">
            <span class="info-label">상태:</span>
            <span class="info-value status-active">활성</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'UserDashboard',
  data() {
    return {
      username: ''
    }
  },
  mounted() {
    this.username = localStorage.getItem('username') || 'User'
  },
  methods: {
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
.user-dashboard {
  min-height: 100vh;
  background: linear-gradient(135deg, #00d2ff 0%, #3a7bd5 100%);
  padding: 20px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: white;
  padding: 20px 30px;
  border-radius: 10px;
  margin-bottom: 30px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.header h1 {
  margin: 0;
  color: #3a7bd5;
  font-size: 28px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 15px;
}

.user-info span {
  font-weight: 600;
  color: #333;
}

.logout-btn {
  padding: 10px 20px;
  background: #f44336;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 14px;
  transition: background 0.3s;
}

.logout-btn:hover {
  background: #d32f2f;
}

.dashboard-content {
  max-width: 1200px;
  margin: 0 auto;
}

.welcome-section {
  background: white;
  padding: 30px;
  border-radius: 10px;
  margin-bottom: 30px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
  text-align: center;
}

.welcome-section h2 {
  margin: 0 0 10px 0;
  color: #333;
  font-size: 24px;
}

.welcome-section p {
  color: #666;
  margin: 0;
  font-size: 16px;
}

.user-sections {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 20px;
  margin-bottom: 30px;
}

.section {
  background: white;
  padding: 30px;
  border-radius: 10px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
  text-align: center;
  transition: transform 0.3s;
}

.section:hover {
  transform: translateY(-5px);
}

.section-icon {
  font-size: 48px;
  margin-bottom: 15px;
}

.section h2 {
  margin: 0 0 15px 0;
  color: #333;
  font-size: 22px;
}

.section p {
  color: #666;
  line-height: 1.6;
  margin-bottom: 20px;
  min-height: 48px;
}

.action-btn {
  padding: 12px 24px;
  background: #3a7bd5;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 14px;
  transition: background 0.3s;
  width: 100%;
}

.action-btn.silver-btn {
  background: linear-gradient(135deg, #c0c0c0 0%, #808080 100%);
}

.action-btn.asset-btn {
  background: linear-gradient(135deg, #f7b733 0%, #fc4a1a 100%);
}

.action-btn.files-btn {
  background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);
}

.info-section {
  background: white;
  padding: 25px;
  border-radius: 10px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.info-section h3 {
  margin: 0 0 20px 0;
  color: #333;
  font-size: 20px;
}

.info-grid {
  display: grid;
  gap: 15px;
}

.info-item {
  display: flex;
  justify-content: space-between;
  padding: 12px;
  background: #f5f5f5;
  border-radius: 5px;
}

.info-label {
  font-weight: 600;
  color: #666;
}

.info-value {
  color: #333;
}

.status-active {
  color: #4caf50;
  font-weight: 600;
}

.gold-section {
  background: linear-gradient(135deg, #fff9e6 0%, #ffffff 100%);
  border: 2px solid #ffd700;
}

.gold-section .section-icon {
  filter: drop-shadow(0 2px 4px rgba(255, 215, 0, 0.4));
}

.gold-section h2 {
  color: #b8860b;
}

.gold-btn {
  background: linear-gradient(135deg, #ffd700, #daa520);
  color: #333;
}

.gold-btn:hover {
  background: linear-gradient(135deg, #ffed4a, #ffd700);
}

.silver-section {
  background: linear-gradient(135deg, #f8f9fa 0%, #ffffff 100%);
  border: 2px solid #c0c0c0;
}

.silver-section .section-icon {
  filter: drop-shadow(0 2px 4px rgba(192, 192, 192, 0.4));
}

.silver-section h2 {
  color: #708090;
}

.silver-btn {
  background: linear-gradient(135deg, #c0c0c0, #a8a8a8);
  color: #333;
}

.silver-btn:hover {
  background: linear-gradient(135deg, #d0d0d0, #c0c0c0);
}

.files-section {
  background: linear-gradient(135deg, #e8f4f8 0%, #ffffff 100%);
  border: 2px solid #5dade2;
}

.files-section .section-icon {
  filter: drop-shadow(0 2px 4px rgba(93, 173, 226, 0.4));
}

.files-section h2 {
  color: #2980b9;
}

.files-btn {
  background: linear-gradient(135deg, #5dade2, #3498db);
  color: white;
}

.files-btn:hover {
  background: linear-gradient(135deg, #7ec8e3, #5dade2);
}
</style>

