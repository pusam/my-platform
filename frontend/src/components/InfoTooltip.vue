<template>
  <span class="info-tooltip" @click.stop="toggle" @mouseenter="show = true" @mouseleave="onMouseLeave">
    <span class="info-icon">❓</span>
    <span v-if="show" class="info-popup" :class="{ 'pos-right': position === 'right' }" @click.stop>
      <span class="info-title" v-if="title">{{ title }}</span>
      <slot></slot>
    </span>
  </span>
</template>

<script>
export default {
  name: 'InfoTooltip',
  props: {
    title: { type: String, default: '' },
    position: { type: String, default: 'left' } // 'left' or 'right'
  },
  data() {
    return { show: false }
  },
  mounted() {
    document.addEventListener('click', this.handleOutsideClick)
  },
  beforeUnmount() {
    document.removeEventListener('click', this.handleOutsideClick)
  },
  methods: {
    toggle() {
      this.show = !this.show
    },
    onMouseLeave() {
      // 모바일은 click 토글이라 mouseleave 무시. PC에서만 자동 닫기.
      // 간단하게: hover-out 후 짧은 지연
      setTimeout(() => { this.show = false }, 200)
    },
    handleOutsideClick() {
      this.show = false
    }
  }
}
</script>

<style scoped>
.info-tooltip {
  position: relative;
  display: inline-flex;
  align-items: center;
  cursor: pointer;
  user-select: none;
  margin-left: 4px;
}
.info-icon {
  font-size: 12px;
  opacity: 0.5;
  transition: opacity 0.15s;
  line-height: 1;
}
.info-tooltip:hover .info-icon { opacity: 1; }

.info-popup {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  width: 280px;
  max-width: 80vw;
  padding: 12px 14px;
  background: #1a1a2e;
  border: 1px solid rgba(255,255,255,0.18);
  border-radius: 10px;
  box-shadow: 0 10px 30px rgba(0,0,0,0.5);
  font-size: 12px;
  line-height: 1.55;
  color: rgba(255,255,255,0.85);
  font-weight: 400;
  z-index: 200;
  cursor: text;
  white-space: normal;
  text-align: left;
}
.info-popup.pos-right {
  left: auto;
  right: 0;
}
.info-title {
  display: block;
  font-weight: 700;
  color: #fff;
  font-size: 13px;
  margin-bottom: 6px;
  padding-bottom: 6px;
  border-bottom: 1px solid rgba(255,255,255,0.1);
}
.info-popup :deep(strong) { color: #facc15; font-weight: 600; }
.info-popup :deep(.tip-row) { display: flex; gap: 8px; margin: 3px 0; }
.info-popup :deep(.tip-row b) { color: #fff; min-width: 60px; }
.info-popup :deep(em) { color: #4ade80; font-style: normal; }
</style>
