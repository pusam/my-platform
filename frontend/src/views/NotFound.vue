<template>
  <div class="notfound">
    <div class="nf-card">
      <div class="nf-code">404</div>
      <h1 class="nf-title">없는 페이지입니다</h1>
      <p class="nf-desc">
        주소가 바뀌었거나 잘못 입력된 것 같습니다.
        <span class="nf-path">{{ attemptedPath }}</span>
      </p>
      <div class="nf-actions">
        <button class="nf-btn primary" @click="goHome">주식 허브로</button>
        <button class="nf-btn" @click="goBack">뒤로 가기</button>
      </div>
    </div>
  </div>
</template>

<script>
/**
 * 없는 경로 안내(2026-09-21 디자인 점검).
 *
 * <p>이전엔 catch-all 라우트가 없어서 오타 주소나 없어진 링크가 <b>완전히 빈 화면</b>으로 떨어졌다 —
 * 콘솔 에러도 없어서 "사이트가 죽었나"와 구분되지 않았다. 데이터가 없을 때 0 으로 위장하지 않는 것과
 * 같은 원칙으로, 없는 화면도 "없다"고 말한다.
 */
export default {
  name: 'NotFound',
  computed: {
    attemptedPath() {
      return this.$route?.fullPath || ''
    }
  },
  methods: {
    goHome() {
      this.$router.replace('/stock-dashboard')
    },
    goBack() {
      if (window.history.length > 1) this.$router.back()
      else this.$router.replace('/stock-dashboard')
    }
  }
}
</script>

<style scoped>
.notfound {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  background: var(--bg-gradient-auth, linear-gradient(135deg, #0f0f1a 0%, #1a1a2e 100%));
}
.nf-card {
  width: 100%;
  max-width: 420px;
  text-align: center;
  padding: 32px 24px;
  background: var(--surface-card, rgba(20, 24, 38, 0.85));
  border: 1px solid var(--border-color, rgba(255, 255, 255, 0.08));
  border-radius: var(--card-radius, 16px);
  box-shadow: var(--card-shadow, 0 4px 20px rgba(0, 0, 0, 0.25));
}
.nf-code {
  font-size: 56px;
  font-weight: 800;
  line-height: 1;
  background: var(--primary-gradient, linear-gradient(135deg, #818cf8 0%, #a78bfa 100%));
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.nf-title {
  margin: 16px 0 8px;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary, #f0f0f5);
}
.nf-desc {
  margin: 0 0 24px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-secondary, #b0b0c8);
}
.nf-path {
  display: block;
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-muted, #7878a0);
  word-break: break-all;
}
.nf-actions {
  display: flex;
  gap: 10px;
  justify-content: center;
  flex-wrap: wrap;
}
.nf-btn {
  min-height: 44px;            /* 터치 타겟 */
  padding: 0 20px;
  border-radius: 10px;
  border: 1px solid var(--border-color, rgba(255, 255, 255, 0.08));
  background: var(--bg-surface, #252540);
  color: var(--text-primary, #f0f0f5);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: filter 0.15s ease;
}
.nf-btn:hover { filter: brightness(1.15); }
.nf-btn.primary {
  background: var(--primary-gradient, linear-gradient(135deg, #818cf8 0%, #a78bfa 100%));
  border-color: transparent;
  color: var(--text-on-accent, #1a1a2e);
}
@media (max-width: 380px) {
  .nf-actions { flex-direction: column; }
  .nf-btn { width: 100%; }
}
</style>
