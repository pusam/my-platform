<template>
  <!-- 접기 가능한 상세 섹션 (P-IA 3단계). v-show 로 자식을 항상 마운트 유지
       → 내부 카드의 보조 API 호출/useAutoRefresh 타이밍 보존(접어도 언마운트 안 됨). -->
  <section class="detail-section">
    <button
      type="button"
      class="detail-section-toggle"
      :aria-expanded="open ? 'true' : 'false'"
      @click="open = !open"
    >
      <span class="ds-title">{{ title }}</span>
      <span class="ds-chevron" :class="{ open }">▾</span>
    </button>
    <div v-show="open" class="detail-section-body">
      <slot />
    </div>
  </section>
</template>

<script setup>
import { ref } from 'vue';

const props = defineProps({
  title: { type: String, default: '상세' },
  defaultOpen: { type: Boolean, default: false }
});

const open = ref(props.defaultOpen);
</script>

<style scoped>
.detail-section {
  margin-bottom: 12px;
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px;
  overflow: hidden;
  background: rgba(255,255,255,0.02);
}
.detail-section-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: rgba(255,255,255,0.03);
  border: none;
  cursor: pointer;
  color: rgba(255,255,255,0.85);
  font-size: 14px;
  font-weight: 600;
}
.detail-section-toggle:hover { background: rgba(255,255,255,0.06); }
.ds-chevron { transition: transform 0.2s; opacity: 0.6; }
.ds-chevron.open { transform: rotate(180deg); }
.detail-section-body { padding: 4px 0 0; }
</style>
