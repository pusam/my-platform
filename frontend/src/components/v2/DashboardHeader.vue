<template>
  <div class="dashboard-header">
    <div class="header-left">
      <BackButton :dark="true" />
    </div>
    <div class="header-center">
      <div class="gnb-tabs">
        <button
          v-for="tab in visibleTabs"
          :key="tab.key"
          :class="['gnb-btn', { active: activeTab === tab.key }]"
          @click="$emit('tab-change', tab.key)"
        >
          <span class="gnb-icon">{{ tab.icon }}</span>
          <span class="gnb-label">{{ tab.label }}</span>
        </button>
      </div>
    </div>
    <div class="header-right">
      <button class="search-btn" @click="$emit('open-search')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
        </svg>
        <kbd>Ctrl+K</kbd>
      </button>
      <NotificationBell :dark="true" />
      <span class="update-time">
        <span class="pulse-dot"></span>
        {{ currentTime }}
      </span>
    </div>
  </div>
</template>

<script>
import BackButton from '../BackButton.vue'
import NotificationBell from '../NotificationBell.vue'

export default {
  name: 'DashboardHeader',
  components: { BackButton, NotificationBell },
  props: {
    activeTab: { type: String, default: 'market' }
  },
  emits: ['open-search', 'tab-change'],
  data() {
    return {
      currentTime: '',
      // GNB(P-IA): 오늘(홈·결론) / 시장(거시) / 발굴(추천·전략) / 매매(봇·페이퍼). 시각은 탭 내 강조로만 반영.
      gnbTabs: [
        { key: 'today', label: '오늘', icon: '📌' },
        { key: 'market', label: '시장', icon: '🌐' },
        { key: 'discover', label: '발굴', icon: '🔍' },
        { key: 'trade', label: '매매', icon: '⚡' }
      ]
    }
  },
  computed: {
    visibleTabs() {
      return this.gnbTabs
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
  padding: 12px 0;
  margin-bottom: 20px;
  gap: 12px;
  position: sticky;
  top: var(--gnb-height);
  z-index: 100;
  background: var(--bg-hub-top, #0f0f1a); /* 허브 그라데이션 상단과 동일 — sticky 시 이음새 없게 */
}

.header-left {
  display: flex;
  align-items: center;
}

/* GNB Tabs */
.header-center {
  flex: 1;
  display: flex;
  justify-content: center;
}

.gnb-tabs {
  display: flex;
  gap: 4px;
  background: rgba(255,255,255,0.04);
  padding: 4px;
  border-radius: 12px;
}

.gnb-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 18px;
  border: none;
  background: transparent;
  color: rgba(255,255,255,0.5);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border-radius: 9px;
  transition: all 0.2s;
  white-space: nowrap;
}
.gnb-btn:hover {
  color: rgba(255,255,255,0.75);
  background: rgba(255,255,255,0.04);
}
.gnb-btn.active {
  background: rgba(102,126,234,0.18);
  color: #a5b4fc;
  font-weight: 600;
  box-shadow: 0 2px 8px rgba(102,126,234,0.15);
}

.gnb-icon {
  font-size: 14px;
}
.gnb-label {
  font-size: 13px;
}

/* Right */
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.search-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 9px;
  color: rgba(255,255,255,0.4);
  cursor: pointer;
  transition: all 0.2s;
}
.search-btn:hover {
  background: rgba(255,255,255,0.1);
  border-color: rgba(255,255,255,0.2);
  color: rgba(255,255,255,0.6);
}
.search-btn kbd {
  font-size: 10px;
  background: rgba(255,255,255,0.1);
  padding: 2px 5px;
  border-radius: 4px;
  color: rgba(255,255,255,0.35);
  font-family: inherit;
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
    gap: 10px;
    /* top 은 기본 규칙의 var(--gnb-height) 그대로 — common.css 가 모바일에서 40px 로 override (77px 매직넘버 제거) */
  }
  .header-center {
    order: 3;
    flex-basis: 100%;
  }
  .gnb-tabs {
    width: 100%;
  }
  .gnb-btn {
    flex: 1;
    justify-content: center;
    padding: 7px 10px;
  }
  .gnb-label { font-size: 12px; }
  .search-btn kbd { display: none; }
}
</style>
