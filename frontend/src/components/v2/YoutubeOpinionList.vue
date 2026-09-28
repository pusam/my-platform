<template>
  <!-- 유튜브 참고 의견 목록 — 인물별 입장 + 발언 카드(원문 타임스탬프 링크). 전부 평문 렌더링(v-html 금지):
       자막·모델 출력은 외부 데이터라 태그처럼 보이는 글자도 글자 그대로 보여 준다. -->
  <div class="yo-list">
    <div v-if="view.persons && view.persons.length" class="yo-persons">
      <div v-for="p in view.persons" :key="'p' + p.personId" class="yo-person">
        <span class="yo-person-name">{{ p.name }}</span>
        <span v-if="roleLabel(p.role)" class="yo-role">{{ roleLabel(p.role) }}</span>
        <span class="yo-stance" :class="stanceClass(p.stance)">{{ stanceLabel(p.stance) }}</span>
        <span v-if="sequenceText(p.sequence)" class="yo-seq">{{ sequenceText(p.sequence) }}</span>
        <span class="yo-person-meta">발언 {{ p.statements }}건 · 최근 {{ formatKst(p.lastPublishedAt) }}</span>
      </div>
    </div>

    <ul class="yo-opinions">
      <li v-for="o in shown" :key="'o' + o.id" class="yo-op">
        <div class="yo-op-head">
          <span class="yo-speaker">{{ o.speakerName || '발언자 미상' }}</span>
          <span v-if="roleLabel(o.speakerRole)" class="yo-role">{{ roleLabel(o.speakerRole) }}</span>
          <span class="yo-stance" :class="stanceClass(o.stance)">{{ stanceLabel(o.stance) }}</span>
          <span class="yo-op-date" :title="'게시 ' + (formatKst(o.publishedAt, { withYear: true }) || '-')">
            {{ formatKst(o.publishedAt) }}
          </span>
        </div>
        <p class="yo-claim">{{ o.claimSummary }}</p>
        <p v-if="o.conditions" class="yo-line yo-cond"><span class="yo-k">조건</span>{{ o.conditions }}</p>
        <p v-if="o.horizon" class="yo-line"><span class="yo-k">기간</span>{{ o.horizon }}</p>
        <p v-if="formatPrice(o.targetPrice)" class="yo-line"><span class="yo-k">언급 가격</span>{{ formatPrice(o.targetPrice) }}</p>
        <p v-if="o.rationale" class="yo-line"><span class="yo-k">이유</span>{{ o.rationale }}</p>
        <blockquote class="yo-quote">“{{ o.evidenceQuote }}”</blockquote>
        <div class="yo-op-foot">
          <a v-if="safeYoutubeUrl(o.sourceUrl)" class="yo-src" :href="safeYoutubeUrl(o.sourceUrl)"
             target="_blank" rel="noopener noreferrer" @click.stop @keydown.enter.stop>▶ 원문 {{ o.startLabel }}</a>
          <span class="yo-video" :title="o.videoTitle">{{ o.channelName }} · {{ o.videoTitle }}</span>
          <span class="yo-times">게시 {{ formatKst(o.publishedAt) || '-' }} · 분석 {{ formatKst(o.analyzedAt) || '-' }}</span>
          <span v-if="!compact && (o.model || o.promptVersion)" class="yo-model">{{ o.model }} / {{ o.promptVersion }}</span>
        </div>
      </li>
    </ul>
    <p v-if="hiddenCount > 0" class="yo-more">외 {{ hiddenCount }}건 — 종목 상세의 '유튜브 참고 의견'에서 전체 확인</p>

    <details v-if="!compact && view.older && view.older.length" class="yo-older">
      <summary>지난 의견 {{ view.older.length }}건 — 집계 창({{ view.windowDays }}일) 밖이라 현재 집계에서 제외</summary>
      <ul>
        <li v-for="o in view.older" :key="'old' + o.id" class="yo-old">
          <span class="yo-old-date">{{ formatKst(o.publishedAt, { withYear: true }) }}</span>
          <span class="yo-speaker">{{ o.speakerName || '발언자 미상' }}</span>
          <span class="yo-stance" :class="stanceClass(o.stance)">{{ stanceLabel(o.stance) }}</span>
          <span class="yo-old-claim">{{ o.claimSummary }}</span>
          <a v-if="safeYoutubeUrl(o.sourceUrl)" class="yo-src" :href="safeYoutubeUrl(o.sourceUrl)"
             target="_blank" rel="noopener noreferrer">원문 {{ o.startLabel }}</a>
        </li>
      </ul>
    </details>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import {
  stanceLabel, stanceClass, roleLabel, formatKst, safeYoutubeUrl, sequenceText, formatPrice
} from '../../utils/youtubeOpinion'

const props = defineProps({
  view: { type: Object, required: true },
  // '오늘' 탭 펼침용 — 발언 수를 줄이고 지난 의견·모델 표기를 뺀다
  compact: { type: Boolean, default: false }
})

const COMPACT_LIMIT = 3
const opinions = computed(() => props.view.opinions || [])
const shown = computed(() => (props.compact ? opinions.value.slice(0, COMPACT_LIMIT) : opinions.value))
const hiddenCount = computed(() => opinions.value.length - shown.value.length)
</script>

<style scoped>
.yo-list { display: flex; flex-direction: column; gap: 10px; color: var(--text-primary, #edf1f5); }
.yo-persons { display: flex; flex-direction: column; gap: 4px; }
.yo-person {
  display: flex; flex-wrap: wrap; align-items: center; gap: 6px 8px;
  font-size: 12.5px; padding: 6px 10px; border-radius: 6px;
  background: var(--surface-card-soft, rgba(255, 255, 255, 0.03));
}
.yo-person-name, .yo-speaker { font-weight: 600; }
.yo-role {
  font-size: 11px; padding: 1px 6px; border-radius: 999px;
  color: var(--text-secondary, #aab3bf); border: 1px solid var(--border-color, #28313d);
}
.yo-seq { font-size: 11.5px; color: var(--text-secondary, #aab3bf); }
.yo-person-meta { font-size: 11.5px; color: var(--text-muted, #8a95a3); margin-left: auto; }

.yo-stance { font-size: 11.5px; font-weight: 700; padding: 1px 7px; border-radius: 999px; white-space: nowrap; }
.yo-stance-positive { color: var(--stock-up, #f87171); background: var(--danger-light, rgba(248, 113, 113, 0.12)); }
.yo-stance-negative { color: var(--stock-down, #60a5fa); background: var(--info-light, rgba(96, 165, 250, 0.12)); }
.yo-stance-conditional { color: var(--warning, #fbbf24); background: var(--warning-light, rgba(251, 191, 36, 0.12)); }
.yo-stance-mixed { color: var(--primary-light, #b7bcff); background: rgba(139, 147, 255, 0.12); }
.yo-stance-neutral, .yo-stance-undeterminable {
  color: var(--text-secondary, #aab3bf); background: rgba(148, 163, 184, 0.12);
}

.yo-opinions { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.yo-op {
  padding: 10px 12px; border-radius: 8px; min-width: 0;
  border: 1px solid var(--border-color, #28313d);
  background: var(--surface-card, #12171f);
}
.yo-op-head { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 8px; font-size: 12.5px; }
.yo-op-date { font-size: 11.5px; color: var(--text-muted, #8a95a3); margin-left: auto; font-variant-numeric: tabular-nums; }
.yo-claim { margin: 6px 0 2px; font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.yo-line { margin: 2px 0; font-size: 12px; line-height: 1.5; color: var(--text-secondary, #aab3bf); overflow-wrap: anywhere; }
.yo-cond { color: var(--warning, #fbbf24); }
.yo-k {
  display: inline-block; min-width: 3.5em; margin-right: 6px;
  font-size: 11px; color: var(--text-muted, #8a95a3);
}
.yo-quote {
  margin: 6px 0 0; padding: 4px 10px; font-size: 12px; line-height: 1.5;
  color: var(--text-secondary, #aab3bf); border-left: 2px solid var(--border-color, #28313d);
  overflow-wrap: anywhere;
}
.yo-op-foot {
  display: flex; flex-wrap: wrap; align-items: center; gap: 4px 10px; margin-top: 8px;
  font-size: 11.5px; color: var(--text-muted, #8a95a3);
}
.yo-src {
  color: var(--info, #60a5fa); font-weight: 600; text-decoration: underline; text-underline-offset: 2px;
  white-space: nowrap;
}
.yo-src:hover { color: var(--primary-light, #b7bcff); }
.yo-video { min-width: 0; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.yo-times { font-variant-numeric: tabular-nums; }
.yo-model { opacity: 0.8; }
.yo-more { margin: 0; font-size: 11.5px; color: var(--text-muted, #8a95a3); }

.yo-older { font-size: 12px; color: var(--text-secondary, #aab3bf); }
.yo-older summary { cursor: pointer; font-size: 12px; padding: 4px 0; }
.yo-older ul { list-style: none; margin: 6px 0 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.yo-old { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; }
.yo-old-date { font-variant-numeric: tabular-nums; color: var(--text-muted, #8a95a3); }
.yo-old-claim { min-width: 0; overflow-wrap: anywhere; }
</style>
