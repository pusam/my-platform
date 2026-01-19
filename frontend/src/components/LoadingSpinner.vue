<template>
  <div :class="['loading-overlay', { 'loading-overlay--fullscreen': fullscreen }]">
    <div class="loading-spinner">
      <div class="spinner"></div>
      <p v-if="message">{{ message }}</p>
    </div>
  </div>
</template>

<script setup>
defineProps({
  message: {
    type: String,
    default: '로딩 중...'
  },
  fullscreen: {
    type: Boolean,
    default: false
  }
});
</script>

<style scoped>
.loading-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(255, 255, 255, 0.95);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  backdrop-filter: blur(4px);
}

.loading-overlay--fullscreen {
  position: fixed;
  z-index: 9999;
  backdrop-filter: blur(8px);
}

.loading-spinner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

.spinner {
  width: 56px;
  height: 56px;
  border: 5px solid #f0f0f0;
  border-top: 5px solid #667eea;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.2);
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

.loading-spinner p {
  margin: 0;
  color: #667eea;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.5px;
}
</style>
