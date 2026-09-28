<template>
  <!-- 📺 유튜브 참고 의견 — 저장된 분석 결과만 읽는다(이 화면이 자막 수집·분석을 트리거하지 않음).
       추천 점수·순위·결론 카드와 무관한 외부 참고라 결론 카드와 떨어진 보조 영역에 둔다.
       조회 실패·분석된 영상 없음·언급 없음·의견 있음을 서로 다른 문구로(§4c). 기능이 꺼져 있으면 그리지 않는다. -->
  <DetailSection v-if="loaded && state.kind !== 'disabled'" :title="title">
    <div class="yos-body">
      <p class="yos-scope">
        {{ view && view.scope ? view.scope : '분석된 영상 기준' }} · 최근 {{ windowDays }}일(영상 게시 시각 기준) ·
        <b>추천 점수·순위와 무관한 외부 참고</b>입니다. 발언자 개인 의견이며 이 플랫폼의 판단이 아닙니다.
      </p>
      <p v-if="coverage" class="yos-coverage">{{ coverage }}</p>
      <div v-if="state.kind === 'unavailable'" class="yos-unavailable">⚠ {{ state.text }}</div>
      <p v-else-if="state.kind !== 'has'" class="yos-empty">{{ state.text }}</p>
      <template v-else>
        <p class="yos-summary">{{ state.text }}</p>
        <YoutubeOpinionList :view="view" />
      </template>
    </div>
  </DetailSection>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { youtubeOpinionAPI } from '../../utils/api'
import DetailSection from './DetailSection.vue'
import YoutubeOpinionList from './YoutubeOpinionList.vue'
import { viewState, sectionTitle, coverageText } from '../../utils/youtubeOpinion'

const props = defineProps({
  stockCode: { type: String, required: true }
})

const view = ref(null)
const loaded = ref(false)
let reqSeq = 0   // 종목 전환 시 늦게 온 이전 종목 응답이 덮지 않게

const load = async (code) => {
  loaded.value = false
  view.value = null
  if (!code) return
  const seq = ++reqSeq
  try {
    const { data } = await youtubeOpinionAPI.getStock(code)
    if (seq !== reqSeq) return
    view.value = data?.success ? data.data : null
  } catch (e) {
    if (seq !== reqSeq) return
    view.value = null   // 조회 실패 — '의견 없음'이 아니라 '확인 불가'로 보인다
  }
  loaded.value = true
}

watch(() => props.stockCode, (code) => load(code), { immediate: true })

const state = computed(() => viewState(view.value))
const title = computed(() => sectionTitle(view.value))
const windowDays = computed(() => view.value?.windowDays ?? 7)
const coverage = computed(() => (state.value.kind === 'unavailable' ? null : coverageText(view.value?.coverage)))
</script>

<style scoped>
.yos-body { padding: 4px 16px 14px; color: var(--text-primary, #edf1f5); display: flex; flex-direction: column; gap: 8px; }
.yos-scope {
  margin: 0; font-size: 11.5px; line-height: 1.55; color: var(--text-secondary, #aab3bf);
  border-left: 2px solid var(--primary-start, #8b93ff); padding-left: 8px;
}
.yos-coverage { margin: 0; font-size: 11.5px; color: var(--text-muted, #8a95a3); }
.yos-summary { margin: 0; font-size: 13px; font-weight: 600; }
.yos-empty { margin: 0; font-size: 12.5px; color: var(--text-secondary, #aab3bf); padding: 4px 2px; }
.yos-unavailable {
  font-size: 12.5px; padding: 8px 10px; border-radius: 6px;
  color: var(--warning, #fbbf24); background: var(--warning-light, rgba(251, 191, 36, 0.12));
  border-left: 2px solid var(--warning, #fbbf24);
}
</style>
