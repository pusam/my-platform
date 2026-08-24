<template>
  <div class="no-data">
    <div class="nd-title">데이터 없음</div>
    <div class="nd-reason">{{ reason || '값을 읽지 못했다 — 0 이나 정상이라는 뜻이 아니다' }}</div>
  </div>
</template>

<script setup>
/**
 * "데이터 없음" 표시 — §4c 의 화면 쪽 구현.
 *
 * 못 읽은 값을 0 이나 빈 칸으로 보여주면 운영자가 "이상 없음"으로 읽는다. 그래서 값 자리에
 * 명시적으로 이 블록을 넣고, 가능하면 왜 없는지(reason)까지 같이 보여준다.
 */
defineProps({
  reason: { type: String, default: null }
})
</script>

<style scoped>
.no-data {
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px dashed var(--cr-amb);
  color: var(--cr-amb);
}

.nd-title {
  font-family: var(--cr-mono);
  font-size: 12px;
  letter-spacing: 0.12em;
}

.nd-reason {
  font-size: 10.5px;
  line-height: 1.45;
  margin-top: 3px;
  opacity: 0.85;
}
</style>
