<template>
  <!-- 📺 유튜브 의견 관리(관리자) — 영상 등록 → 타임스탬프 자막 등록 → 분석 요청 → 검토.
       서버는 영상 페이지·자막을 가져오지 않는다(자동 수집 미연결). 권한은 SecurityConfig URL 규칙(/api/admin/**)이 막는다.
       자막·모델 출력은 전부 평문으로 보여 준다(v-html 금지). -->
  <section class="yoa">
    <div class="yoa-head">
      <h2>📺 유튜브 의견 관리</h2>
      <span v-if="config" class="yoa-badge" :class="config.enabled ? 'on' : 'off'">{{ config.enabled ? '켜짐' : '꺼짐' }}</span>
      <button type="button" class="yoa-btn" :disabled="loading" @click="refreshAll">새로고침</button>
    </div>
    <p v-if="loadError" class="yoa-error">⚠ {{ loadError }}</p>

    <template v-if="config">
      <ul class="yoa-facts">
        <li><b>자동 수집: 연결 안 됨</b> — 공개 영상 자막은 YouTube Data API 로 받을 수 없습니다(captions.download 는 영상 편집 권한 필요).
          출처가 확인된 타임스탬프 자막을 여기서 직접 등록합니다. 제목만으로 내용을 추측하지 않습니다.</li>
        <li v-if="!config.enabled" class="yoa-warn">기능이 꺼져 있습니다 — 서버 .env 에 YOUTUBE_OPINION_ENABLED=true 를 넣고 backend 를 재생성하세요.</li>
        <li>등록 허용 채널: <span v-if="config.channels && config.channels.length">{{ config.channels.map(c => c.name + ' (' + c.id + ')').join(', ') }}</span>
          <span v-else class="yoa-warn">없음 — YOUTUBE_OPINION_CHANNELS 에 '채널ID=이름' 으로 직접 고른 채널만 넣습니다.</span></li>
        <li>Gemini {{ config.geminiConfigured ? '설정됨' : '미설정(분석 불가)' }} · 모델 {{ config.model }} · 프롬프트 {{ config.promptVersion }}</li>
        <li>오늘 분석 호출 {{ config.dailyCallsUsed }} / {{ config.dailyCallLimit }} · 1회 최대 {{ config.maxChunksPerRun }}구간 ·
          재료 보호 시간대 {{ config.quietWindow || '없음' }} · 자막 최대 {{ config.maxTranscriptKb }}KB · 집계 창 {{ config.windowDays }}일</li>
      </ul>

      <template v-if="config.enabled">
        <!-- ① 등록 -->
        <details class="yoa-block">
          <summary>① 영상 등록</summary>
          <form class="yoa-form" @submit.prevent="register">
            <label>YouTube 주소<input v-model.trim="form.url" required maxlength="300" placeholder="https://www.youtube.com/watch?v=…" /></label>
            <label>제목<input v-model.trim="form.title" required maxlength="300" /></label>
            <label>채널
              <select v-model="form.channelId" required>
                <option value="" disabled>선택</option>
                <option v-for="c in config.channels" :key="c.id" :value="c.id">{{ c.name }}</option>
              </select>
            </label>
            <label>게시 시각(KST)<input v-model="form.publishedAt" type="datetime-local" required /></label>
            <label class="yoa-wide">자막 출처<input v-model.trim="form.sourceNote" required maxlength="300"
              placeholder="누가 어떤 방식으로 제공한 자막인지(예: 채널 제공 SRT, 관리자 대조 완료)" /></label>
            <label class="yoa-wide">재업로드·편집본이면 원본 영상 주소/ID(선택)<input v-model.trim="form.duplicateOf" maxlength="300" /></label>
            <div class="yoa-wide yoa-participants">
              <span class="yoa-label">출연자(자막의 '이름:' 표기와 같은 이름) — 모르는 사람은 넣지 않습니다</span>
              <div v-for="(p, i) in form.participants" :key="i" class="yoa-participant">
                <input v-model.trim="p.name" maxlength="50" placeholder="이름" />
                <select v-model="p.role">
                  <option value="HOST">채널 운영자</option>
                  <option value="GUEST">출연자</option>
                </select>
                <button type="button" class="yoa-btn ghost" @click="form.participants.splice(i, 1)">삭제</button>
              </div>
              <button v-if="form.participants.length < 10" type="button" class="yoa-btn ghost"
                      @click="form.participants.push({ name: '', role: 'GUEST' })">+ 출연자</button>
            </div>
            <div class="yoa-wide yoa-actions">
              <button type="submit" class="yoa-btn primary" :disabled="busy">등록</button>
              <span v-if="formMessage" class="yoa-msg">{{ formMessage }}</span>
            </div>
          </form>
        </details>

        <!-- ② 영상 목록 -->
        <div class="yoa-block">
          <h3>② 영상 (최근 50)</h3>
          <p v-if="!videos.length" class="yoa-muted">등록된 영상이 없습니다.</p>
          <div v-for="v in videos" :key="v.videoId" class="yoa-video">
            <div class="yoa-video-head">
              <a :href="v.videoUrl" target="_blank" rel="noopener noreferrer" class="yoa-link">{{ v.title }}</a>
              <span class="yoa-status" :class="'st-' + String(v.status).toLowerCase()">{{ videoStatusLabel(v.status) }}</span>
            </div>
            <div class="yoa-video-meta">
              {{ v.channelName }} · 게시 {{ formatKst(v.publishedAt, { withYear: true }) }} · 등록 {{ formatKst(v.createdAt) }} ·
              자막 {{ v.transcriptVersions }}버전
              <template v-if="v.lastSuccessAt"> · 마지막 성공 {{ formatKst(v.lastSuccessAt) }}</template>
              <template v-if="v.duplicateOf"> · 재업로드(원본 {{ v.duplicateOf }}, 집계 제외)</template>
            </div>
            <div v-if="v.participants && v.participants.length" class="yoa-video-meta">
              출연: {{ v.participants.map(p => p.name + (p.role === 'HOST' ? '(운영자)' : '')).join(', ') }}
            </div>
            <p v-if="v.lastError" class="yoa-error">마지막 실패: {{ v.lastError }}</p>
            <div class="yoa-actions">
              <button type="button" class="yoa-btn ghost" @click="toggleTranscript(v.videoId)">자막 등록</button>
              <button type="button" class="yoa-btn" :disabled="busy || v.status === 'ANALYZING' || !v.transcriptVersions"
                      @click="analyze(v.videoId)">{{ v.currentRunId ? '재분석' : '분석' }}</button>
              <button type="button" class="yoa-btn ghost" @click="toggleRuns(v.videoId)">실행 이력</button>
              <span v-if="rowMessage[v.videoId]" class="yoa-msg">{{ rowMessage[v.videoId] }}</span>
            </div>

            <form v-if="transcriptFor === v.videoId" class="yoa-transcript" @submit.prevent="uploadTranscript(v.videoId)">
              <div class="yoa-actions">
                <select v-model="transcript.format">
                  <option value="AUTO">형식 자동</option>
                  <option value="SRT">SRT</option>
                  <option value="VTT">WebVTT</option>
                  <option value="TEXT">타임스탬프 텍스트</option>
                </select>
                <input type="file" accept=".srt,.vtt,.txt,text/plain" @change="readFile" />
              </div>
              <textarea v-model="transcript.content" rows="8"
                        placeholder="[00:01:05] 이름: 발언… 또는 SRT/VTT 원문. 발언자 표기는 등록한 출연자 이름만 인식합니다."></textarea>
              <div class="yoa-actions">
                <button type="submit" class="yoa-btn primary" :disabled="busy || !transcript.content">자막 저장</button>
                <span class="yoa-muted">{{ transcriptBytes }} / {{ config.maxTranscriptKb }}KB</span>
              </div>
            </form>

            <ul v-if="runs[v.videoId]" class="yoa-runs">
              <li v-if="!runs[v.videoId].length" class="yoa-muted">실행 이력 없음</li>
              <li v-for="r in runs[v.videoId]" :key="r.id" :class="{ current: r.current }">
                #{{ r.id }} {{ r.status }}{{ r.current ? ' (화면 표시 중)' : '' }} · {{ r.chunkCount }}구간 · 발언 {{ r.statementCount }} ·
                형식 불량 {{ r.droppedCount }} · {{ formatKst(r.startedAt) }} → {{ formatKst(r.finishedAt) || '진행 중' }} ·
                {{ r.model }}/{{ r.promptVersion }} · {{ r.requestedBy }}
                <div v-if="r.error" class="yoa-error">{{ r.error }}</div>
              </li>
            </ul>
          </div>
        </div>

        <!-- ③ 검토 -->
        <div class="yoa-block">
          <h3>③ 검토 대기 {{ review.length }}건 <span class="yoa-muted">— 승인 전에는 화면·집계에 나가지 않습니다</span></h3>
          <p v-if="!review.length" class="yoa-muted">검토할 발언이 없습니다.</p>
          <div v-for="r in review" :key="r.id" class="yoa-review">
            <div class="yoa-video-head">
              <span class="yoa-strong">{{ r.stockNameRaw }}</span>
              <span class="yoa-muted">{{ r.stockCode || '코드 미확인' }} · {{ r.mappingStatus }}</span>
              <span class="yoa-status">{{ stanceLabel(r.stance) }} · {{ r.statementType }}</span>
            </div>
            <div class="yoa-video-meta">{{ r.speakerName || '발언자 미상' }} · {{ r.videoTitle }}</div>
            <p class="yoa-claim">{{ r.claimSummary }}</p>
            <p v-if="r.conditions" class="yoa-video-meta">조건: {{ r.conditions }}</p>
            <p class="yoa-quote">“{{ r.evidenceQuote }}”</p>
            <p class="yoa-reasons">사유: {{ reviewReasonText(r.reviewReasons) || '-' }}</p>
            <div class="yoa-actions">
              <a v-if="safeYoutubeUrl(r.sourceUrl)" :href="safeYoutubeUrl(r.sourceUrl)" target="_blank" rel="noopener noreferrer"
                 class="yoa-link">▶ 원문 확인</a>
              <input v-model.trim="reviewCode[r.id]" maxlength="6" class="yoa-code" placeholder="종목코드" />
              <button type="button" class="yoa-btn primary" :disabled="busy" @click="decide(r, 'APPROVE')">승인</button>
              <button type="button" class="yoa-btn" :disabled="busy" @click="decide(r, 'REJECT')">거절</button>
            </div>
          </div>
        </div>
      </template>
    </template>
  </section>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { youtubeOpinionAPI } from '../../utils/api'
import {
  formatKst, stanceLabel, reviewReasonText, videoStatusLabel, safeYoutubeUrl
} from '../../utils/youtubeOpinion'

const config = ref(null)
const videos = ref([])
const review = ref([])
const runs = reactive({})
const transcriptFor = ref(null)      // 자막 입력창은 한 번에 한 영상만(입력 내용 공유 방지)
const rowMessage = reactive({})
const reviewCode = reactive({})
const loading = ref(false)
const busy = ref(false)
const loadError = ref(null)
const formMessage = ref(null)

const emptyForm = () => ({ url: '', title: '', channelId: '', publishedAt: '', sourceNote: '', duplicateOf: '', participants: [{ name: '', role: 'HOST' }] })
const form = reactive(emptyForm())
const transcript = reactive({ format: 'AUTO', content: '' })
const transcriptBytes = computed(() => Math.ceil(new Blob([transcript.content]).size / 1024) + 'KB')

const errorText = (e) => e?.response?.data?.message || e?.message || '요청 실패'

const refreshAll = async () => {
  loading.value = true
  loadError.value = null
  try {
    const { data } = await youtubeOpinionAPI.getConfig()
    config.value = data?.data || null
    if (config.value?.enabled) {
      const [v, r] = await Promise.all([youtubeOpinionAPI.getVideos(), youtubeOpinionAPI.getReviewQueue()])
      videos.value = v.data?.data || []
      review.value = r.data?.data || []
    }
  } catch (e) {
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

const register = async () => {
  busy.value = true
  formMessage.value = null
  try {
    const participants = form.participants.filter(p => p.name)
    const { data } = await youtubeOpinionAPI.registerVideo({ ...form, participants })
    formMessage.value = data?.data?.alreadyRegistered ? '이미 등록된 영상입니다 — 기존 기록을 그대로 둡니다.' : '등록했습니다.'
    if (!data?.data?.alreadyRegistered) Object.assign(form, emptyForm())
    await refreshAll()
  } catch (e) {
    formMessage.value = '⚠ ' + errorText(e)
  } finally {
    busy.value = false
  }
}

const toggleTranscript = (videoId) => {
  transcriptFor.value = transcriptFor.value === videoId ? null : videoId
  transcript.content = ''
}

const readFile = (event) => {
  const file = event.target.files?.[0]
  if (!file) return
  const maxBytes = (config.value?.maxTranscriptKb || 512) * 1024
  if (file.size > maxBytes) {
    transcript.content = ''
    loadError.value = `자막 파일이 너무 큽니다(최대 ${config.value?.maxTranscriptKb || 512}KB) — 필요한 구간만 올리세요.`
    return
  }
  const reader = new FileReader()
  reader.onload = () => { transcript.content = String(reader.result || '') }
  reader.readAsText(file, 'utf-8')
}

const uploadTranscript = async (videoId) => {
  busy.value = true
  try {
    const { data } = await youtubeOpinionAPI.uploadTranscript(videoId, transcript.format, transcript.content)
    const r = data?.data
    rowMessage[videoId] = r?.duplicate
      ? `같은 자막이라 새 버전을 만들지 않았습니다(v${r.version}).`
      : `자막 v${r.version} 저장 · 큐 ${r.cueCount}개 · 발언자 표기 ${r.labeledCues}개 · ${r.format}`
    transcript.content = ''
    transcriptFor.value = null
    await refreshAll()
  } catch (e) {
    rowMessage[videoId] = '⚠ ' + errorText(e)
  } finally {
    busy.value = false
  }
}

let pollTimer = null
const pollWhileAnalyzing = (tries = 0) => {
  clearTimeout(pollTimer)
  if (tries > 36) return
  pollTimer = setTimeout(async () => {
    await refreshAll()
    if (videos.value.some(v => v.status === 'ANALYZING')) pollWhileAnalyzing(tries + 1)
  }, 5000)
}

const analyze = async (videoId) => {
  busy.value = true
  try {
    const { data } = await youtubeOpinionAPI.analyze(videoId)
    rowMessage[videoId] = `분석 시작 — 실행 #${data?.data?.runId}, ${data?.data?.chunkCount}구간. 끝나면 목록이 갱신됩니다.`
    await refreshAll()
    pollWhileAnalyzing()
  } catch (e) {
    rowMessage[videoId] = '⚠ ' + errorText(e)
  } finally {
    busy.value = false
  }
}

const toggleRuns = async (videoId) => {
  if (runs[videoId]) { delete runs[videoId]; return }
  try {
    const { data } = await youtubeOpinionAPI.getRuns(videoId)
    runs[videoId] = data?.data || []
  } catch (e) {
    rowMessage[videoId] = '⚠ ' + errorText(e)
  }
}

const decide = async (row, decision) => {
  busy.value = true
  try {
    await youtubeOpinionAPI.review(row.id, decision, reviewCode[row.id] || null)
    await refreshAll()
  } catch (e) {
    loadError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

onMounted(refreshAll)
onBeforeUnmount(() => clearTimeout(pollTimer))

defineExpose({ refreshAll })
</script>

<style scoped>
.yoa {
  margin-top: 20px; padding: 18px; border-radius: 12px; min-width: 0;
  background: var(--surface-card, #12171f); border: 1px solid var(--border-color, #28313d);
  color: var(--text-primary, #edf1f5);
}
.yoa-head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
.yoa-head h2 { margin: 0; font-size: 17px; }
.yoa-badge { font-size: 11.5px; font-weight: 700; padding: 2px 8px; border-radius: 999px; }
.yoa-badge.on { color: var(--success, #34d399); background: var(--success-light, rgba(52, 211, 153, 0.12)); }
.yoa-badge.off { color: var(--text-secondary, #aab3bf); background: rgba(148, 163, 184, 0.12); }
.yoa-head .yoa-btn { margin-left: auto; }
.yoa-facts { margin: 12px 0 0; padding-left: 18px; font-size: 12.5px; line-height: 1.6; color: var(--text-secondary, #aab3bf); }
.yoa-warn { color: var(--warning, #fbbf24); }
.yoa-error { margin: 6px 0; font-size: 12.5px; color: var(--warning, #fbbf24); overflow-wrap: anywhere; }
.yoa-muted { font-size: 12px; color: var(--text-muted, #8a95a3); font-weight: 400; }
.yoa-msg { font-size: 12px; color: var(--text-secondary, #aab3bf); overflow-wrap: anywhere; }
.yoa-block { margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--border-color, #28313d); }
.yoa-block > summary, .yoa-block h3 { font-size: 14px; font-weight: 700; margin: 0 0 8px; cursor: pointer; }
.yoa-form { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 240px), 1fr)); gap: 10px; }
.yoa-form label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-secondary, #aab3bf); min-width: 0; }
.yoa-wide { grid-column: 1 / -1; }
.yoa-label { font-size: 12px; color: var(--text-secondary, #aab3bf); }
.yoa input, .yoa select, .yoa textarea {
  width: 100%; box-sizing: border-box; min-width: 0; font-size: 13px; padding: 7px 9px; border-radius: 6px;
  color: var(--text-primary, #edf1f5); background: var(--surface-card-soft, #10151c); border: 1px solid var(--border-color, #28313d);
}
.yoa textarea { font-family: inherit; line-height: 1.5; resize: vertical; }
.yoa-participants { display: flex; flex-direction: column; gap: 6px; }
.yoa-participant { display: flex; flex-wrap: wrap; gap: 6px; }
.yoa-participant input { flex: 1 1 160px; }
.yoa-participant select { flex: 0 1 130px; }
.yoa-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 8px; margin-top: 6px; }
.yoa-actions select { width: auto; }
.yoa-actions input[type='file'] { width: auto; max-width: 100%; }
.yoa-btn {
  font-size: 12.5px; font-weight: 600; padding: 6px 12px; border-radius: 6px; cursor: pointer;
  color: var(--text-primary, #edf1f5); background: rgba(255, 255, 255, 0.06); border: 1px solid var(--border-color, #28313d);
}
.yoa-btn.primary { color: var(--text-on-accent, #0b0e13); background: var(--primary-start, #8b93ff); border-color: transparent; }
.yoa-btn.ghost { background: transparent; }
.yoa-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.yoa-video, .yoa-review {
  padding: 10px 12px; margin-top: 8px; border-radius: 8px; min-width: 0;
  background: var(--surface-card-soft, #10151c); border: 1px solid var(--border-color, #28313d);
}
.yoa-video-head { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 10px; font-size: 13px; }
.yoa-video-meta { margin-top: 4px; font-size: 12px; color: var(--text-muted, #8a95a3); overflow-wrap: anywhere; }
.yoa-link { color: var(--info, #60a5fa); text-decoration: underline; text-underline-offset: 2px; overflow-wrap: anywhere; }
.yoa-strong { font-weight: 700; }
.yoa-status { font-size: 11.5px; font-weight: 700; padding: 1px 8px; border-radius: 999px; background: rgba(148, 163, 184, 0.12); color: var(--text-secondary, #aab3bf); }
.yoa-status.st-analyzed { color: var(--success, #34d399); background: var(--success-light, rgba(52, 211, 153, 0.12)); }
.yoa-status.st-failed { color: var(--warning, #fbbf24); background: var(--warning-light, rgba(251, 191, 36, 0.12)); }
.yoa-status.st-analyzing { color: var(--info, #60a5fa); background: var(--info-light, rgba(96, 165, 250, 0.12)); }
.yoa-transcript { margin-top: 8px; display: flex; flex-direction: column; gap: 6px; }
.yoa-runs { margin: 8px 0 0; padding-left: 18px; font-size: 12px; line-height: 1.6; color: var(--text-secondary, #aab3bf); }
.yoa-runs li.current { color: var(--text-primary, #edf1f5); }
.yoa-claim { margin: 6px 0 2px; font-size: 13px; overflow-wrap: anywhere; }
.yoa-quote { margin: 4px 0; font-size: 12px; color: var(--text-secondary, #aab3bf); border-left: 2px solid var(--border-color, #28313d); padding-left: 8px; overflow-wrap: anywhere; }
.yoa-reasons { margin: 4px 0; font-size: 12px; color: var(--warning, #fbbf24); }
.yoa-code { width: 110px !important; flex: 0 0 auto; }
</style>
