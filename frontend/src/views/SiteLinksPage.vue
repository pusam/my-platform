<template>
  <!--
    내 사이트 바로가기 (/sites) — 운영 편의 화면. 주식 화면이 아니다(추천·시세·봇과 무관).
    목록은 로그인한 본인 것만 서버가 준다 — 이 파일(화면 번들)에는 주소를 한 줄도 적지 않는다.
    주소는 사용자가 넣은 값이라 safeHref 를 통과한 것만 링크로 그리고, 글자는 전부 평문으로 그린다(v-html 금지).
  -->
  <div class="site-links-page">
    <GlobalNav subtitle="내 사이트" />
    <header class="page-header">
      <BackButton :dark="true" />
      <h1>내 사이트</h1>
      <button class="add-btn" type="button" data-test="add" @click="openAdd">+ 추가</button>
    </header>

    <p class="page-note">로그인한 본인에게만 보이는 목록입니다. 각 사이트의 로그인은 그 사이트에서 따로 합니다.</p>

    <div v-if="loading" class="state" data-test="loading"><div class="spinner"></div></div>

    <div v-else-if="loadFailed" class="state" data-test="load-failed">
      <p>목록을 불러오지 못했습니다.</p>
      <button class="retry-btn" type="button" @click="load">다시 시도</button>
    </div>

    <div v-else-if="links.length === 0" class="state" data-test="empty">
      <p>아직 넣은 사이트가 없습니다.</p>
      <p class="state-sub">오른쪽 위 '+ 추가'로 이름과 주소를 넣어 두세요.</p>
    </div>

    <ul v-else class="link-list" data-test="list">
      <li v-for="link in sorted" :key="link.id" class="link-card" data-test="card">
        <a v-if="safeHref(link.url)" class="link-main" :href="safeHref(link.url)"
           target="_blank" rel="noopener noreferrer" data-test="open">
          <span class="link-top">
            <span class="link-name">{{ link.name }}</span>
            <span class="login-badge" :class="{ needed: link.loginRequired }">{{ loginLabel(link) }}</span>
          </span>
          <span v-if="link.description" class="link-desc">{{ link.description }}</span>
          <span class="link-addr">{{ displayAddress(link.url) }}</span>
        </a>
        <div v-else class="link-main blocked" data-test="blocked">
          <span class="link-top">
            <span class="link-name">{{ link.name }}</span>
            <span class="login-badge blocked-badge">열 수 없는 주소</span>
          </span>
          <span v-if="link.description" class="link-desc">{{ link.description }}</span>
          <span class="link-addr">{{ link.url }}</span>
        </div>
        <div class="link-actions">
          <button class="edit-btn" type="button" data-test="edit" @click="openEdit(link)">수정</button>
          <button class="del-btn" type="button" data-test="delete" @click="remove(link)">삭제</button>
        </div>
      </li>
    </ul>

    <div v-if="showModal" class="modal-overlay" @click.self="closeModal">
      <div class="modal-content" role="dialog" aria-modal="true" :aria-label="editId ? '사이트 수정' : '사이트 추가'">
        <h2>{{ editId ? '사이트 수정' : '사이트 추가' }}</h2>
        <form data-test="form" @submit.prevent="submit">
          <div class="form-group">
            <label for="sl-name">이름</label>
            <input id="sl-name" v-model="form.name" maxlength="60" placeholder="예: 주식 플랫폼" required />
          </div>
          <div class="form-group">
            <label for="sl-url">주소</label>
            <input id="sl-url" v-model="form.url" maxlength="500" inputmode="url" autocapitalize="off"
                   autocomplete="off" spellcheck="false" placeholder="https://" required />
          </div>
          <div class="form-group">
            <label for="sl-desc">한 줄 설명 (선택)</label>
            <input id="sl-desc" v-model="form.description" maxlength="200" placeholder="무엇을 하는 사이트인지" />
          </div>
          <div class="form-row">
            <label class="check">
              <input v-model="form.loginRequired" type="checkbox" />
              <span>이 사이트는 로그인이 필요함</span>
            </label>
            <div class="form-group order">
              <label for="sl-order">순서</label>
              <input id="sl-order" v-model.number="form.sortOrder" type="number" step="1" />
            </div>
          </div>
          <p v-if="formError" class="form-error" role="alert" data-test="form-error">{{ formError }}</p>
          <div class="modal-actions">
            <button type="button" class="cancel-btn" @click="closeModal">취소</button>
            <button type="submit" class="submit-btn" :disabled="saving">{{ editId ? '수정' : '추가' }}</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import GlobalNav from '../components/GlobalNav.vue'
import BackButton from '../components/BackButton.vue'
import { siteLinksAPI } from '../utils/api'
import { toast } from '../utils/toast'
import { displayAddress, nextSortOrder, safeHref, sortLinks } from '../utils/siteLinks'

const loading = ref(false)
const loadFailed = ref(false)
const links = ref([])
const showModal = ref(false)
const editId = ref(null)
const saving = ref(false)
const formError = ref('')

const emptyForm = () => ({ name: '', url: '', description: '', loginRequired: false, sortOrder: nextSortOrder(links.value) })
const form = ref(emptyForm())

const sorted = computed(() => sortLinks(links.value))
const loginLabel = (link) => (link.loginRequired ? '로그인 필요' : '로그인 없음')

// 서버가 거절한 이유(400 의 details)를 그대로 보여 준다 — 없으면 일반 문구.
const serverMessage = (e, fallback) => {
  const data = e?.response?.data
  if (typeof data?.details === 'string' && data.details) return data.details
  if (typeof data?.message === 'string' && data.message) return data.message
  return fallback
}

const load = async () => {
  loading.value = true
  loadFailed.value = false
  try {
    const res = await siteLinksAPI.list()
    if (!res?.data?.success || !Array.isArray(res.data.data)) throw new Error('unexpected response')
    links.value = res.data.data
  } catch (e) {
    // 실패를 '없음'으로 보이지 않는다 — 빈 목록과 구분해서 말한다.
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

const openAdd = () => {
  editId.value = null
  form.value = emptyForm()
  formError.value = ''
  showModal.value = true
}

const openEdit = (link) => {
  editId.value = link.id
  form.value = {
    name: link.name ?? '',
    url: link.url ?? '',
    description: link.description ?? '',
    loginRequired: !!link.loginRequired,
    sortOrder: link.sortOrder ?? 0
  }
  formError.value = ''
  showModal.value = true
}

const closeModal = () => {
  showModal.value = false
  editId.value = null
  formError.value = ''
}

const submit = async () => {
  if (saving.value) return
  const name = (form.value.name || '').trim()
  const url = (form.value.url || '').trim()
  if (!name) { formError.value = '이름을 입력하세요.'; return }
  if (!safeHref(url)) { formError.value = '주소는 http:// 또는 https:// 로 시작하는 올바른 주소여야 합니다.'; return }
  const payload = {
    name,
    url,
    description: (form.value.description || '').trim(),
    loginRequired: !!form.value.loginRequired,
    sortOrder: Number.isFinite(form.value.sortOrder) ? Math.trunc(form.value.sortOrder) : 0
  }
  saving.value = true
  formError.value = ''
  try {
    if (editId.value) await siteLinksAPI.update(editId.value, payload)
    else await siteLinksAPI.add(payload)
    closeModal()
    await load()
  } catch (e) {
    formError.value = serverMessage(e, '저장하지 못했습니다.')
  } finally {
    saving.value = false
  }
}

const remove = async (link) => {
  if (!window.confirm(`'${link.name}' 바로가기를 지울까요? (사이트 자체는 그대로입니다)`)) return
  try {
    await siteLinksAPI.remove(link.id)
    await load()
  } catch (e) {
    toast.error(serverMessage(e, '지우지 못했습니다.'))
  }
}

onMounted(load)
</script>

<style scoped>
.site-links-page { min-height: 100vh; background: linear-gradient(135deg, #0f0f23 0%, #1a1a3e 100%); color: #fff; padding: 20px; }
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.page-header h1 { flex: 1; font-size: 20px; margin: 0; }
.add-btn { background: #4F46E5; color: #fff; border: none; border-radius: 8px; padding: 10px 16px; min-height: 44px; cursor: pointer; font-size: 14px; white-space: nowrap; }
.add-btn:hover { background: #5558e6; }
.page-note { margin: 0 0 16px; font-size: 13px; line-height: 1.5; color: rgba(255,255,255,0.6); word-break: keep-all; overflow-wrap: break-word; }

.state { text-align: center; padding: 56px 20px; color: rgba(255,255,255,0.7); }
.state p { margin: 0 0 8px; }
.state-sub { font-size: 13px; color: rgba(255,255,255,0.5); }
.retry-btn { margin-top: 8px; background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.2); color: #fff; border-radius: 8px; padding: 10px 18px; min-height: 44px; cursor: pointer; }
.spinner { width: 32px; height: 32px; border: 3px solid rgba(255,255,255,0.1); border-top-color: #4F46E5; border-radius: 50%; animation: spin 0.7s linear infinite; margin: 0 auto; }
@keyframes spin { to { transform: rotate(360deg); } }

.link-list { list-style: none; margin: 0 auto; padding: 0; max-width: 760px; display: flex; flex-direction: column; gap: 10px; }
.link-card { display: flex; align-items: stretch; background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.1); border-radius: 12px; overflow: hidden; }
.link-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; padding: 14px 16px; color: inherit; text-decoration: none; }
a.link-main:hover { background: rgba(255,255,255,0.05); }
a.link-main:focus-visible { outline: 2px solid #818cf8; outline-offset: -2px; }
.link-top { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
/* 띄어쓰기 없는 긴 이름도 카드 밖으로 나가지 않게 — flex 자식이라 min-width:0 + anywhere 가 있어야 줄어든다 */
.link-name { min-width: 0; font-size: 16px; font-weight: 600; word-break: keep-all; overflow-wrap: anywhere; }
.login-badge { font-size: 11px; padding: 2px 8px; border-radius: 10px; background: rgba(255,255,255,0.08); color: rgba(255,255,255,0.65); white-space: nowrap; }
.login-badge.needed { background: rgba(251,191,36,0.16); color: #fbbf24; }
.login-badge.blocked-badge { background: rgba(239,68,68,0.16); color: #fca5a5; }
.link-desc { font-size: 13px; line-height: 1.45; color: rgba(255,255,255,0.7); word-break: keep-all; overflow-wrap: break-word; }
.link-addr { font-size: 12px; color: #a5b4fc; overflow-wrap: anywhere; }
.link-main.blocked .link-addr { color: rgba(255,255,255,0.5); }

.link-actions { display: flex; flex-direction: column; justify-content: center; gap: 6px; padding: 8px 10px; border-left: 1px solid rgba(255,255,255,0.08); }
.edit-btn, .del-btn { background: none; border: 1px solid rgba(255,255,255,0.15); color: rgba(255,255,255,0.65); border-radius: 6px; padding: 6px 12px; min-height: 36px; cursor: pointer; font-size: 12px; white-space: nowrap; }
.edit-btn:hover { border-color: #4F46E5; color: #a5b4fc; }
.del-btn:hover { border-color: #ef4444; color: #fca5a5; }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.7); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 12px; }
.modal-content { background: #1e1e3a; border-radius: 16px; padding: 24px; width: 100%; max-width: 480px; max-height: 90vh; overflow-y: auto; box-sizing: border-box; }
.modal-content h2 { margin: 0 0 16px; font-size: 18px; }
.form-group { margin-bottom: 12px; }
.form-group label { display: block; font-size: 12px; color: rgba(255,255,255,0.6); margin-bottom: 4px; }
.form-group input { width: 100%; background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.14); border-radius: 8px; padding: 10px; color: #fff; font-size: 16px; box-sizing: border-box; }
.form-row { display: flex; align-items: flex-end; gap: 12px; }
.check { flex: 1; display: flex; align-items: center; gap: 8px; min-height: 44px; font-size: 14px; color: rgba(255,255,255,0.85); cursor: pointer; margin-bottom: 12px; }
.check input { width: 18px; height: 18px; }
.form-group.order { width: 96px; }
.form-error { margin: 4px 0 0; font-size: 13px; color: #fca5a5; word-break: keep-all; overflow-wrap: break-word; }
.modal-actions { display: flex; gap: 10px; margin-top: 16px; }
.cancel-btn { flex: 1; background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.14); color: rgba(255,255,255,0.75); border-radius: 8px; padding: 12px; min-height: 44px; cursor: pointer; }
.submit-btn { flex: 1; background: #4F46E5; color: #fff; border: none; border-radius: 8px; padding: 12px; min-height: 44px; cursor: pointer; font-weight: 600; }
.submit-btn:hover { background: #5558e6; }
.submit-btn:disabled { opacity: 0.6; cursor: default; }

@media (max-width: 480px) {
  .site-links-page { padding: 10px; }
  .page-header h1 { font-size: 17px; }
  .link-main { padding: 12px; }
  .link-actions { padding: 8px; }
  .modal-content { padding: 18px; }
  .form-row { flex-direction: column; align-items: stretch; gap: 0; }
  .form-group.order { width: 100%; }
}
</style>
