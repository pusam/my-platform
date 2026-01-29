<template>
  <div v-if="visible" class="modal-overlay" @click="close">
    <div class="modal-content" @click.stop>
      <div class="modal-header">
        <h2>대시보드 위젯 설정</h2>
        <button @click="close" class="modal-close">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/>
            <line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>

      <div class="modal-body">
        <p class="settings-description">대시보드에 표시할 위젯을 선택하세요.</p>

        <div class="widget-list">
          <label v-for="widget in widgets" :key="widget.id" class="widget-item">
            <input
              type="checkbox"
              :checked="widget.enabled"
              @change="toggleWidget(widget.id)"
            />
            <div class="widget-info">
              <div class="widget-icon" :class="widget.iconClass">
                <component :is="widget.icon" />
              </div>
              <div class="widget-details">
                <span class="widget-name">{{ widget.name }}</span>
                <span class="widget-desc">{{ widget.description }}</span>
              </div>
            </div>
            <div class="toggle-switch" :class="{ active: widget.enabled }">
              <div class="toggle-knob"></div>
            </div>
          </label>
        </div>
      </div>

      <div class="modal-actions">
        <button @click="resetToDefault" class="btn btn-secondary">기본값으로 복원</button>
        <button @click="saveAndClose" class="btn btn-primary">저장</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue';

const props = defineProps({
  visible: Boolean,
  settings: Object
});

const emit = defineEmits(['close', 'update:settings']);

const defaultWidgets = [
  { id: 'goldPrice', name: '금 시세', description: '실시간 금 시세를 표시합니다', iconClass: 'gold', enabled: true },
  { id: 'silverPrice', name: '은 시세', description: '실시간 은 시세를 표시합니다', iconClass: 'silver', enabled: true },
  { id: 'assetSummary', name: '자산 요약', description: '보유 자산 요약 정보를 표시합니다', iconClass: 'asset', enabled: true },
  { id: 'news', name: '경제 뉴스', description: '오늘의 경제 뉴스를 표시합니다', iconClass: 'news', enabled: true },
  { id: 'financeSummary', name: '가계부 요약', description: '이번 달 수입/지출 요약을 표시합니다', iconClass: 'finance', enabled: true },
  { id: 'investorTrades', name: '투자자 매매 동향', description: '외국인·기관·개인 매매 상위 종목을 표시합니다', iconClass: 'investor', enabled: true },
  { id: 'earningsScreener', name: '실적 스크리너', description: '마법의 공식, PEG, 턴어라운드 종목을 스크리닝합니다', iconClass: 'screener', enabled: true }
];

const widgets = ref([...defaultWidgets]);

const loadSettings = () => {
  const saved = localStorage.getItem('dashboardWidgets');
  if (saved) {
    const savedWidgets = JSON.parse(saved);
    widgets.value = defaultWidgets.map(w => ({
      ...w,
      enabled: savedWidgets[w.id] !== false
    }));
  }
};

const toggleWidget = (widgetId) => {
  const widget = widgets.value.find(w => w.id === widgetId);
  if (widget) {
    widget.enabled = !widget.enabled;
  }
};

const saveSettings = () => {
  const settings = {};
  widgets.value.forEach(w => {
    settings[w.id] = w.enabled;
  });
  localStorage.setItem('dashboardWidgets', JSON.stringify(settings));
  emit('update:settings', settings);
};

const saveAndClose = () => {
  saveSettings();
  close();
};

const resetToDefault = () => {
  widgets.value = defaultWidgets.map(w => ({ ...w }));
  saveSettings();
};

const close = () => {
  emit('close');
};

onMounted(() => {
  loadSettings();
});

watch(() => props.visible, (newVal) => {
  if (newVal) {
    loadSettings();
  }
});
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  animation: fadeIn 0.2s ease;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.modal-content {
  background: white;
  border-radius: 16px;
  width: 90%;
  max-width: 500px;
  max-height: 90vh;
  overflow-y: auto;
  animation: slideUp 0.25s ease;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24px 28px;
  border-bottom: 1px solid var(--border-light);
}

.modal-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}

.modal-close {
  width: 36px;
  height: 36px;
  background: var(--bg-page);
  border: none;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  transition: all 0.2s;
}

.modal-close:hover {
  background: var(--border-color);
  color: var(--text-primary);
}

.modal-body {
  padding: 24px 28px;
}

.settings-description {
  margin: 0 0 20px 0;
  color: var(--text-muted);
  font-size: 14px;
}

.widget-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.widget-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  background: var(--bg-page);
  border: 1px solid var(--border-light);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.widget-item:hover {
  border-color: var(--primary-start);
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.1);
}

.widget-item input[type="checkbox"] {
  display: none;
}

.widget-info {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: 1;
}

.widget-icon {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.widget-icon.gold {
  background: var(--gold-light);
  color: var(--gold-secondary);
}

.widget-icon.silver {
  background: var(--silver-light);
  color: var(--silver-secondary);
}

.widget-icon.asset {
  background: rgba(245, 158, 11, 0.12);
  color: #d97706;
}

.widget-icon.investor {
  background: rgba(102, 126, 234, 0.12);
  color: #667eea;
}

.widget-icon.news {
  background: rgba(59, 130, 246, 0.12);
  color: #3b82f6;
}

.widget-icon.finance {
  background: rgba(16, 185, 129, 0.12);
  color: #10b981;
}

.widget-icon.screener {
  background: rgba(34, 197, 94, 0.12);
  color: #22c55e;
}

.widget-details {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.widget-name {
  font-weight: 600;
  font-size: 15px;
  color: var(--text-primary);
}

.widget-desc {
  font-size: 12px;
  color: var(--text-muted);
}

.toggle-switch {
  width: 48px;
  height: 26px;
  background: #d1d5db;
  border-radius: 13px;
  position: relative;
  transition: background 0.25s;
  flex-shrink: 0;
}

.toggle-switch.active {
  background: linear-gradient(135deg, var(--primary-start) 0%, var(--primary-end) 100%);
}

.toggle-knob {
  width: 22px;
  height: 22px;
  background: white;
  border-radius: 50%;
  position: absolute;
  top: 2px;
  left: 2px;
  transition: transform 0.25s;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.15);
}

.toggle-switch.active .toggle-knob {
  transform: translateX(22px);
}

.modal-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  padding: 20px 28px;
  border-top: 1px solid var(--border-light);
}

.btn {
  padding: 12px 24px;
  border: none;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-secondary {
  background: white;
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
}

.btn-secondary:hover {
  background: var(--bg-page);
  border-color: var(--primary-start);
  color: var(--primary-start);
}

.btn-primary {
  background: linear-gradient(135deg, var(--primary-start) 0%, var(--primary-end) 100%);
  color: white;
  box-shadow: 0 4px 14px rgba(102, 126, 234, 0.35);
}

.btn-primary:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(102, 126, 234, 0.45);
}
</style>
