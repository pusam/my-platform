<template>
  <header class="page-header">
    <div class="header-left">
      <BackButton />
    </div>
    <div class="header-center">
      <h1>{{ title }}</h1>
      <p v-if="subtitle" class="subtitle">{{ subtitle }}</p>
    </div>
    <div class="header-right">
      <slot name="actions"></slot>
    </div>
  </header>
</template>

<script>
import BackButton from './BackButton.vue'

export default {
  name: 'PageHeader',
  components: { BackButton },
  props: {
    title: {
      type: String,
      required: true
    },
    subtitle: {
      type: String,
      default: ''
    }
  }
}
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 0;
  margin-bottom: 20px;
  border-bottom: 1px solid #eee;
}

.header-left {
  flex-shrink: 0;
}

.header-center {
  flex: 1;
  text-align: center;
}

.header-center h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: #333;
}

.header-center .subtitle {
  margin: 8px 0 0;
  font-size: 14px;
  color: #666;
}

.header-right {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 다크 테마 지원 */
@media (prefers-color-scheme: dark) {
  .page-header {
    border-bottom-color: #333;
  }

  .header-center h1 {
    color: #fff;
  }

  .header-center .subtitle {
    color: #aaa;
  }
}

/* 모바일 반응형 */
@media (max-width: 768px) {
  .page-header {
    flex-direction: column;
    align-items: stretch;
  }

  .header-left {
    order: -1;
  }

  .header-center {
    text-align: left;
  }

  .header-center h1 {
    font-size: 20px;
  }

  .header-right {
    margin-top: 12px;
    justify-content: flex-end;
  }
}
</style>
