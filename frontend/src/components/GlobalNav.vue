<template>
  <nav class="global-nav">
    <div class="nav-inner">
      <div class="nav-left">
        <router-link to="/user" class="nav-home" title="홈으로">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z"/>
            <polyline points="9,22 9,12 15,12 15,22"/>
          </svg>
        </router-link>
        <span class="nav-sub" v-if="subtitle">{{ subtitle }}</span>
      </div>

      <!-- PC: 상단 탭 -->
      <div class="nav-tabs">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="nav-tab"
          :class="{ active: isActive(item.path) }"
        >
          <span class="tab-icon">{{ item.icon }}</span>
          <span class="tab-text">{{ item.label }}</span>
        </router-link>
      </div>

      <div class="nav-right">
        <slot />
      </div>
    </div>

    <!-- 모바일: 하단 탭바 -->
    <div class="mobile-tabbar">
      <router-link
        v-for="item in navItems"
        :key="'m-' + item.path"
        :to="item.path"
        class="mobile-tab"
        :class="{ active: isActive(item.path) }"
      >
        <span class="mobile-icon">{{ item.icon }}</span>
        <span class="mobile-label">{{ item.label }}</span>
      </router-link>
    </div>
  </nav>
</template>

<script setup>
import { defineProps } from 'vue'
import { useRoute } from 'vue-router'

defineProps({
  subtitle: { type: String, default: '' }
})

const route = useRoute()

const navItems = [
  { path: '/stock-dashboard', icon: '📈', label: '시장' },
  { path: '/research', icon: '🔬', label: '분석' },
  { path: '/global-futures', icon: '🌍', label: '글로벌' },
  { path: '/paper-trading', icon: '🤖', label: '매매' }
]

const isActive = (itemPath) => {
  const current = route.path
  if (itemPath === '/stock-dashboard') {
    return current === '/stock-dashboard' || current.startsWith('/stock/')
  }
  if (itemPath === '/research') {
    return current === '/research' || current === '/market-timing'
  }
  return current.startsWith(itemPath)
}
</script>

<style scoped>
/* 상단 GNB (PC) */
.global-nav {
  position: sticky;
  top: 0;
  z-index: 950;
  pointer-events: auto;
}

.nav-inner {
  display: flex;
  align-items: center;
  height: 48px;
  padding: 0 20px;
  background: rgba(15, 15, 26, 0.92);
  backdrop-filter: blur(16px);
  border-bottom: 1px solid rgba(255,255,255,0.06);
  position: relative;
  z-index: 951;
}

.nav-left {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-right: 12px;
}

.nav-home {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  color: rgba(255,255,255,0.5);
  transition: all 0.15s;
}

.nav-home:hover {
  color: rgba(255,255,255,0.9);
  background: rgba(255,255,255,0.08);
}

.nav-sub {
  font-size: 13px;
  font-weight: 600;
  color: rgba(255,255,255,0.7);
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 탭 영역 */
.nav-tabs {
  display: flex;
  gap: 2px;
  flex: 1;
  justify-content: center;
}

.nav-tab {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 8px 16px;
  border-radius: 8px;
  text-decoration: none;
  color: rgba(255,255,255,0.4);
  font-size: 13px;
  font-weight: 500;
  transition: all 0.15s;
  white-space: nowrap;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  user-select: none;
}

.nav-tab:hover {
  color: rgba(255,255,255,0.7);
  background: rgba(255,255,255,0.05);
}

.nav-tab.active {
  color: #fff;
  background: rgba(255,255,255,0.1);
  font-weight: 600;
}

.tab-icon { font-size: 14px; }
.tab-text { font-size: 13px; }

.nav-right {
  display: flex;
  align-items: center;
  margin-left: 12px;
}

/* 모바일 하단 탭바 (PC에서 숨김) */
.mobile-tabbar { display: none; }

@media (max-width: 768px) {
  /* 상단 GNB 간소화 */
  .nav-tabs { display: none; }
  .nav-inner { height: 40px; padding: 0 12px; }
  .nav-sub { max-width: 200px; font-size: 14px; font-weight: 700; }

  /* 하단 탭바 */
  .mobile-tabbar {
    display: flex;
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    height: 56px;
    background: rgba(15, 15, 26, 0.95);
    backdrop-filter: blur(16px);
    border-top: 1px solid rgba(255,255,255,0.06);
    z-index: 900;
    padding: 0 4px;
    padding-bottom: env(safe-area-inset-bottom, 0);
  }

  .mobile-tab {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 2px;
    text-decoration: none;
    color: rgba(255,255,255,0.35);
    transition: color 0.15s;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
    user-select: none;
    min-height: 44px;
  }

  .mobile-tab.active {
    color: #fff;
  }

  .mobile-icon { font-size: 20px; }
  .mobile-label { font-size: 10px; font-weight: 600; }
}
</style>
