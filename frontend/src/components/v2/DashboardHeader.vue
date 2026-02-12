<template>
  <div class="dashboard-header">
    <div class="header-left">
      <button class="back-btn" @click="$router.push('/user')">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="15,18 9,12 15,6"/>
        </svg>
      </button>
      <div class="header-title">
        <h1>주식 트레이딩 대시보드</h1>
        <span class="header-subtitle">V2.0</span>
      </div>
    </div>
    <div class="header-center">
      <div class="search-trigger" @click="$emit('open-search')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
        </svg>
        <span>종목 검색...</span>
        <kbd>Ctrl+K</kbd>
      </div>
    </div>
    <div class="header-right">
      <span class="update-time">
        <span class="pulse-dot"></span>
        {{ currentTime }}
      </span>
    </div>
  </div>
</template>

<script>
export default {
  name: 'DashboardHeader',
  emits: ['open-search'],
  data() {
    return {
      currentTime: ''
    }
  },
  mounted() {
    this.updateTime()
    this.timer = setInterval(this.updateTime, 60000)
  },
  beforeUnmount() {
    clearInterval(this.timer)
  },
  methods: {
    updateTime() {
      const now = new Date()
      this.currentTime = now.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
    }
  }
}
</script>

<style scoped>
.dashboard-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 0;
  margin-bottom: 24px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.back-btn {
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 10px;
  color: rgba(255,255,255,0.7);
  cursor: pointer;
  padding: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}
.back-btn:hover {
  background: rgba(255,255,255,0.1);
  color: white;
}

.header-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.header-title h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: rgba(255,255,255,0.95);
}

.header-subtitle {
  font-size: 12px;
  color: #667eea;
  background: rgba(102, 126, 234, 0.15);
  padding: 2px 8px;
  border-radius: 6px;
  font-weight: 600;
}

.header-center {
  flex: 1;
  display: flex;
  justify-content: center;
  max-width: 400px;
  margin: 0 24px;
}

.search-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 16px;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
  color: rgba(255,255,255,0.4);
  font-size: 14px;
}
.search-trigger:hover {
  background: rgba(255,255,255,0.1);
  border-color: rgba(255,255,255,0.2);
  color: rgba(255,255,255,0.6);
}
.search-trigger span {
  flex: 1;
}
.search-trigger kbd {
  font-size: 11px;
  background: rgba(255,255,255,0.1);
  padding: 2px 6px;
  border-radius: 4px;
  color: rgba(255,255,255,0.4);
  font-family: inherit;
}

.header-right {
  display: flex;
  align-items: center;
}

.update-time {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: rgba(255,255,255,0.5);
}

.pulse-dot {
  width: 6px;
  height: 6px;
  background: #4ade80;
  border-radius: 50%;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

@media (max-width: 768px) {
  .dashboard-header {
    flex-wrap: wrap;
    gap: 12px;
  }
  .header-center {
    order: 3;
    max-width: 100%;
    margin: 0;
    flex-basis: 100%;
  }
  .header-title h1 { font-size: 16px; }
  .search-trigger kbd { display: none; }
}
</style>
