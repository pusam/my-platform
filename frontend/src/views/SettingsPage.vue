<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <BackButton />
        <h1>내 설정</h1>
        <div class="header-actions">
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

      <div class="settings-grid">
        <!-- 프로필 섹션 -->
        <section class="settings-card">
          <div class="card-header">
            <div class="card-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/>
                <circle cx="12" cy="7" r="4"/>
              </svg>
            </div>
            <h2>프로필 정보</h2>
          </div>

          <div class="card-body">
            <!-- 프로필 이미지 섹션 -->
            <div class="profile-image-section">
              <div class="profile-image-preview">
                <img v-if="profile.profileImage" :src="getProfileImageUrl()" alt="프로필 이미지" />
                <div v-else class="profile-image-placeholder">
                  <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                    <path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/>
                    <circle cx="12" cy="7" r="4"/>
                  </svg>
                </div>
              </div>
              <div class="profile-image-actions">
                <label class="btn btn-secondary btn-sm">
                  <input type="file" accept="image/*" @change="handleImageUpload" hidden />
                  이미지 변경
                </label>
                <button v-if="profile.profileImage" @click="deleteProfileImage" class="btn btn-danger btn-sm">
                  이미지 삭제
                </button>
              </div>
              <div v-if="imageError" class="image-error">{{ imageError }}</div>
            </div>

            <div class="form-group">
              <label>아이디</label>
              <input type="text" :value="profile.username" disabled class="disabled-input" />
              <span class="input-hint">아이디는 변경할 수 없습니다.</span>
            </div>

            <div class="form-group">
              <label>이름</label>
              <input type="text" v-model="profileForm.name" placeholder="이름을 입력하세요" />
            </div>

            <div class="form-group">
              <label>이메일</label>
              <input type="email" v-model="profileForm.email" placeholder="이메일을 입력하세요" />
            </div>

            <div class="form-group">
              <label>전화번호</label>
              <input type="tel" v-model="profileForm.phone" placeholder="전화번호를 입력하세요" />
            </div>

            <div class="form-group">
              <label>역할</label>
              <input type="text" :value="profile.role === 'ADMIN' ? '관리자' : '일반 사용자'" disabled class="disabled-input" />
            </div>

            <div class="form-group">
              <label>가입일</label>
              <input type="text" :value="formatDate(profile.createdAt)" disabled class="disabled-input" />
            </div>

            <button @click="updateProfile" class="btn btn-primary full-width" :disabled="saving">
              {{ saving ? '저장 중...' : '프로필 저장' }}
            </button>

            <div class="alert alert-success" v-if="profileMessage">{{ profileMessage }}</div>
            <div class="alert alert-error" v-if="profileError">{{ profileError }}</div>
          </div>
        </section>

        <!-- 비밀번호 변경 섹션 -->
        <section class="settings-card">
          <div class="card-header">
            <div class="card-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
                <path d="M7 11V7a5 5 0 0110 0v4"/>
              </svg>
            </div>
            <h2>비밀번호 변경</h2>
          </div>

          <div class="card-body">
            <div class="form-group">
              <label>현재 비밀번호</label>
              <input type="password" v-model="passwordForm.currentPassword" placeholder="현재 비밀번호" />
            </div>

            <div class="form-group">
              <label>새 비밀번호</label>
              <input type="password" v-model="passwordForm.newPassword" placeholder="새 비밀번호 (4자 이상)" />
            </div>

            <div class="form-group">
              <label>새 비밀번호 확인</label>
              <input type="password" v-model="passwordForm.confirmPassword" placeholder="새 비밀번호 확인" />
            </div>

            <button @click="changePassword" class="btn btn-primary full-width" :disabled="changingPassword">
              {{ changingPassword ? '변경 중...' : '비밀번호 변경' }}
            </button>

            <div class="alert alert-success" v-if="passwordMessage">{{ passwordMessage }}</div>
            <div class="alert alert-error" v-if="passwordError">{{ passwordError }}</div>
          </div>
        </section>

        <!-- ADMIN: 주간 매매 일지 (AI) -->
        <section class="settings-card" v-if="isAdmin">
          <div class="card-header">
            <div class="card-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M4 19.5A2.5 2.5 0 016.5 17H20"/>
                <path d="M6.5 2H20v20H6.5A2.5 2.5 0 014 19.5v-15A2.5 2.5 0 016.5 2z"/>
              </svg>
            </div>
            <h2>📒 주간 매매 일지 (AI)</h2>
          </div>

          <div class="card-body">
            <p class="input-hint">매주 일요일 19시 자동 생성. Gemini 가 지난주 매매를 분석해 코칭 리포트를 만듭니다.</p>

            <!-- 모드 탭 -->
            <div class="diary-tabs">
              <button :class="['diary-tab', { active: diaryMode === 'REAL' }]" @click="switchDiaryMode('REAL')">
                🔴 실전
              </button>
              <button :class="['diary-tab', { active: diaryMode === 'VIRTUAL' }]" @click="switchDiaryMode('VIRTUAL')">
                🤖 모의
              </button>
            </div>

            <div v-if="loadingDiary" class="input-hint">불러오는 중…</div>

            <div v-else-if="!latestReport" class="input-hint" style="margin: 12px 0;">
              아직 생성된 리포트가 없습니다. "지금 생성" 버튼으로 즉시 만들 수 있어요.
            </div>

            <div v-else class="diary-latest">
              <div class="diary-meta">
                <strong>{{ latestReport.weekStart }} ~ {{ latestReport.weekEnd }}</strong>
                <span class="input-hint" style="margin-left: 8px;">생성: {{ formatDateTime(latestReport.createdAt) }}</span>
              </div>
              <div class="diary-stats">
                <div>💰 손익 <strong :class="Number(latestReport.realizedPnl) >= 0 ? 'pnl-up' : 'pnl-down'">{{ formatKrw(latestReport.realizedPnl) }}원</strong></div>
                <div>📈 매수 {{ latestReport.totalBuys }}회 / 📉 매도 {{ latestReport.totalSells }}회</div>
                <div>✅ 승 {{ latestReport.winCount }} / ❌ 패 {{ latestReport.lossCount }}</div>
                <div v-if="latestReport.blockedCount > 0">⛔ 차단 {{ latestReport.blockedCount }}건</div>
              </div>
              <pre class="diary-report">{{ latestReport.aiReport }}</pre>
            </div>

            <div class="kill-actions" style="margin-top: 16px;">
              <button @click="generateDiary" :disabled="generatingDiary" class="btn btn-primary">
                {{ generatingDiary ? '생성 중… (10~30초)' : '📊 지난주 리포트 지금 생성' }}
              </button>
              <button @click="loadRecentDiary" :disabled="loadingDiary" class="btn btn-secondary">새로고침</button>
              <button v-if="recentReports.length > 0" @click="showHistory = !showHistory" class="btn btn-secondary">
                {{ showHistory ? '과거 리포트 닫기' : `과거 ${recentReports.length}주 보기` }}
              </button>
            </div>

            <div v-if="showHistory" class="diary-history" style="margin-top: 16px;">
              <div v-for="r in recentReports" :key="r.id" class="diary-history-item">
                <div class="diary-meta">
                  <strong>{{ r.weekStart }} ~ {{ r.weekEnd }}</strong>
                  <span :class="Number(r.realizedPnl) >= 0 ? 'pnl-up' : 'pnl-down'" style="margin-left: 12px;">
                    {{ formatKrw(r.realizedPnl) }}원
                  </span>
                </div>
                <details>
                  <summary class="input-hint" style="cursor: pointer;">본문 보기</summary>
                  <pre class="diary-report">{{ r.aiReport }}</pre>
                </details>
              </div>
            </div>

            <div class="alert alert-error" v-if="diaryError">{{ diaryError }}</div>
          </div>
        </section>

        <!-- ADMIN: 매매 비상 정지 -->
        <section class="settings-card kill-card" v-if="isAdmin">
          <div class="card-header">
            <div class="card-icon kill-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10"/>
                <path d="M15 9l-6 6M9 9l6 6"/>
              </svg>
            </div>
            <h2>🚨 매매 비상 정지</h2>
          </div>

          <div class="card-body">
            <p class="input-hint">실전매매가 즉시 차단됩니다. 자동매매 봇 + 수동 매수/매도 모두 적용.</p>

            <div v-if="loadingSafety" class="input-hint">상태 조회 중…</div>

            <div v-else-if="safetyStatus" class="kill-status">
              <div class="kill-row">
                <span>현재 상태</span>
                <strong :class="safetyStatus.killSwitchEnabled ? 'kill-on' : 'kill-off'">
                  {{ safetyStatus.killSwitchEnabled ? '⛔ 차단 중' : '✅ 정상 (매매 가능)' }}
                </strong>
              </div>
              <div class="kill-row" v-if="safetyStatus.killSwitchEnabled">
                <span>사유</span>
                <em>{{ safetyStatus.killSwitchReason || '-' }}</em>
              </div>
              <div class="kill-row">
                <span>오늘 매수 누적</span>
                <strong>{{ formatKrw(safetyStatus.todayBuyAmountKrw) }}원</strong>
              </div>
              <div class="kill-row">
                <span>일일 한도</span>
                <strong>{{ formatKrw(safetyStatus.dailyBuyLimitKrw) }}원</strong>
              </div>
              <div class="kill-row">
                <span>잔여 한도</span>
                <strong :class="Number(safetyStatus.remainingKrw) <= 0 ? 'kill-on' : ''">
                  {{ formatKrw(safetyStatus.remainingKrw) }}원
                </strong>
              </div>
              <div class="kill-row">
                <span>대형거래 알림 임계</span>
                <strong>{{ formatKrw(safetyStatus.alertThresholdKrw) }}원 이상</strong>
              </div>
            </div>

            <div class="form-group" style="margin-top: 16px;">
              <label>사유 (선택)</label>
              <input type="text" v-model="killReason" placeholder="예: 시장 변동성 급증, 점검" maxlength="200" />
            </div>

            <div class="kill-actions">
              <button
                v-if="!safetyStatus?.killSwitchEnabled"
                @click="enableKill"
                :disabled="killBusy"
                class="btn btn-danger"
              >
                {{ killBusy ? '처리 중…' : '⛔ 매매 비상 정지' }}
              </button>
              <button
                v-else
                @click="disableKill"
                :disabled="killBusy"
                class="btn btn-primary"
              >
                {{ killBusy ? '처리 중…' : '✅ 매매 재개' }}
              </button>
              <button @click="loadSafety" :disabled="loadingSafety" class="btn btn-secondary">새로고침</button>
            </div>

            <div class="alert alert-success" v-if="killMessage">{{ killMessage }}</div>
            <div class="alert alert-error" v-if="killError">{{ killError }}</div>
          </div>
        </section>

        <!-- 생체인증 (WebAuthn) 섹션 — 모바일(폰)에서만 표시 -->
        <section class="settings-card" v-if="webauthnSupported && isMobile">
          <div class="card-header">
            <div class="card-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M12 11c-3 0-3 5-6 5M12 11c3 0 3 5 6 5M12 11V7M12 7a4 4 0 014 4M12 7a4 4 0 00-4 4"/>
                <path d="M4 11a8 8 0 0116 0v4a8 8 0 01-16 0z"/>
              </svg>
            </div>
            <h2>지문 / Face ID 로그인</h2>
          </div>

          <div class="card-body">
            <p class="input-hint">비밀번호 대신 지문/Face ID 로 로그인할 수 있어요. 한 계정에 여러 기기 등록 가능합니다.</p>

            <div v-if="loadingCredentials" class="input-hint">불러오는 중…</div>

            <div v-else-if="credentials.length === 0" class="input-hint" style="margin: 12px 0;">
              아직 등록된 기기가 없습니다.
            </div>

            <ul v-else class="webauthn-list">
              <li v-for="c in credentials" :key="c.id" class="webauthn-item">
                <div>
                  <strong>{{ c.deviceName || '기기' }}</strong>
                  <div class="input-hint">
                    등록: {{ formatDate(c.createdAt) }}
                    <span v-if="c.lastUsedAt"> · 마지막 사용: {{ formatDate(c.lastUsedAt) }}</span>
                  </div>
                </div>
                <button @click="removeCredential(c.id)" class="btn btn-danger btn-sm">삭제</button>
              </li>
            </ul>

            <div class="form-group" style="margin-top: 16px;">
              <label>기기 이름 (선택)</label>
              <input type="text" v-model="newDeviceName" placeholder="예: 내 아이폰" maxlength="50" />
            </div>

            <button @click="registerWebauthn" :disabled="registering" class="btn btn-primary">
              {{ registering ? '등록 중…' : '이 기기 생체인증 등록' }}
            </button>

            <div class="alert alert-success" v-if="webauthnMessage">{{ webauthnMessage }}</div>
            <div class="alert alert-error" v-if="webauthnError">{{ webauthnError }}</div>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { userSettingsAPI } from '../utils/api'
import apiClient from '../utils/api'
import { UserManager } from '../utils/auth'
import BackButton from '../components/BackButton.vue'
import {
  isWebauthnSupported,
  registerWebauthn as doRegisterWebauthn,
  listCredentials as fetchCredentials,
  deleteCredential as removeCredentialApi,
} from '../utils/webauthn'

const router = useRouter()

const profile = ref({
  username: '',
  name: '',
  email: '',
  phone: '',
  profileImage: '',
  role: '',
  createdAt: ''
})

const profileForm = reactive({
  name: '',
  email: '',
  phone: ''
})

const imageError = ref('')

const passwordForm = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const saving = ref(false)
const changingPassword = ref(false)
const profileMessage = ref('')
const profileError = ref('')
const passwordMessage = ref('')
const passwordError = ref('')

const isAdmin = computed(() => (profile.value?.role || localStorage.getItem('role')) === 'ADMIN')

// 매매 일지 (AI)
const diaryMode = ref('REAL')
const latestReport = ref(null)
const recentReports = ref([])
const loadingDiary = ref(false)
const generatingDiary = ref(false)
const diaryError = ref('')
const showHistory = ref(false)

const switchDiaryMode = (m) => {
  diaryMode.value = m
  loadRecentDiary()
}

const formatDateTime = (s) => {
  if (!s) return '-'
  const d = new Date(s)
  return d.toLocaleString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

const loadDiaryLatest = async () => {
  if (!isAdmin.value) return
  loadingDiary.value = true
  diaryError.value = ''
  try {
    const res = await apiClient.get('/admin/trading/diary/latest', { params: { mode: diaryMode.value } })
    if (res.data?.success) latestReport.value = res.data.data
  } catch (e) {
    diaryError.value = e.message || '리포트 조회 실패'
  } finally {
    loadingDiary.value = false
  }
}

const loadRecentDiary = async () => {
  await loadDiaryLatest()
  try {
    const res = await apiClient.get('/admin/trading/diary/recent', { params: { mode: diaryMode.value } })
    if (res.data?.success) recentReports.value = res.data.data || []
  } catch (e) { /* noop */ }
}

const generateDiary = async () => {
  const label = diaryMode.value === 'REAL' ? '실전' : '모의'
  if (!confirm(`지난주 ${label} 매매 리포트를 지금 생성할까요? AI 호출이라 10~30초 걸릴 수 있어요.`)) return
  generatingDiary.value = true
  diaryError.value = ''
  try {
    const res = await apiClient.post('/admin/trading/diary/generate', null, { params: { mode: diaryMode.value } })
    if (res.data?.success) {
      latestReport.value = res.data.data
      await loadRecentDiary()
    } else {
      diaryError.value = res.data?.message || '생성 실패'
    }
  } catch (e) {
    diaryError.value = e.message || '생성 실패'
  } finally {
    generatingDiary.value = false
  }
}

// 매매 안전장치
const safetyStatus = ref(null)
const loadingSafety = ref(false)
const killReason = ref('')
const killBusy = ref(false)
const killMessage = ref('')
const killError = ref('')

const formatKrw = (n) => {
  if (n == null || n === '') return '-'
  return Number(n).toLocaleString('ko-KR')
}

const loadSafety = async () => {
  if (!isAdmin.value) return
  loadingSafety.value = true
  killError.value = ''
  try {
    const res = await apiClient.get('/admin/trading/safety/status')
    if (res.data?.success) {
      safetyStatus.value = res.data.data
    } else {
      killError.value = res.data?.message || '상태 조회 실패'
    }
  } catch (e) {
    killError.value = e.message || '상태 조회 실패'
  } finally {
    loadingSafety.value = false
  }
}

const enableKill = async () => {
  if (!confirm('정말 매매를 비상 정지하시겠습니까?\n자동매매 봇 + 수동 매매 모두 즉시 차단됩니다.')) return
  killBusy.value = true
  killMessage.value = ''
  killError.value = ''
  try {
    const res = await apiClient.post('/admin/trading/safety/kill-switch/enable', { reason: killReason.value })
    if (res.data?.success) {
      killMessage.value = '⛔ 매매 비상 정지 활성화됨'
      killReason.value = ''
      await loadSafety()
    } else {
      killError.value = res.data?.message || '실패'
    }
  } catch (e) {
    killError.value = e.message || '실패'
  } finally {
    killBusy.value = false
  }
}

const disableKill = async () => {
  if (!confirm('매매를 재개하시겠습니까?')) return
  killBusy.value = true
  killMessage.value = ''
  killError.value = ''
  try {
    const res = await apiClient.post('/admin/trading/safety/kill-switch/disable', { reason: killReason.value })
    if (res.data?.success) {
      killMessage.value = '✅ 매매 재개됨'
      killReason.value = ''
      await loadSafety()
    } else {
      killError.value = res.data?.message || '실패'
    }
  } catch (e) {
    killError.value = e.message || '실패'
  } finally {
    killBusy.value = false
  }
}

// 모바일(폰/태블릿) 판별 — PC 에서는 지문 카드 숨김
const isMobile = computed(() => {
  if (typeof navigator === 'undefined') return false
  const ua = navigator.userAgent || ''
  return /Mobi|Android|iPhone|iPad|iPod/i.test(ua)
})

// WebAuthn
const webauthnSupported = ref(false)
const credentials = ref([])
const loadingCredentials = ref(false)
const registering = ref(false)
const newDeviceName = ref('')
const webauthnMessage = ref('')
const webauthnError = ref('')

const loadCredentials = async () => {
  if (!webauthnSupported.value) return
  loadingCredentials.value = true
  try {
    credentials.value = await fetchCredentials()
  } catch (e) {
    webauthnError.value = e.message || '목록 조회 실패'
  } finally {
    loadingCredentials.value = false
  }
}

const registerWebauthn = async () => {
  webauthnMessage.value = ''
  webauthnError.value = ''
  registering.value = true
  try {
    await doRegisterWebauthn(newDeviceName.value)
    webauthnMessage.value = '지문/생체인증이 등록되었습니다.'
    newDeviceName.value = ''
    await loadCredentials()
  } catch (e) {
    if (e?.name === 'NotAllowedError') {
      webauthnError.value = '사용자가 취소했거나 이 기기에 생체인증을 사용할 수 없습니다.'
    } else {
      webauthnError.value = e.message || '등록 실패'
    }
  } finally {
    registering.value = false
  }
}

const removeCredential = async (id) => {
  if (!confirm('이 기기 등록을 삭제할까요?')) return
  webauthnMessage.value = ''
  webauthnError.value = ''
  try {
    await removeCredentialApi(id)
    webauthnMessage.value = '삭제되었습니다.'
    await loadCredentials()
  } catch (e) {
    webauthnError.value = e.message || '삭제 실패'
  }
}

const goBack = () => {
  router.back()
}

const logout = () => {
  UserManager.logout()
  router.push('/login')
}

const fetchProfile = async () => {
  try {
    const response = await userSettingsAPI.getProfile()
    if (response.data.success) {
      profile.value = response.data.data
      profileForm.name = response.data.data.name || ''
      profileForm.email = response.data.data.email || ''
      profileForm.phone = response.data.data.phone || ''
    }
  } catch (err) {
    console.error('프로필 조회 실패:', err)
  }
}

const getProfileImageUrl = () => {
  if (profile.value.profileImage) {
    return `/api/uploads/${profile.value.profileImage}`
  }
  return ''
}

const handleImageUpload = async (event) => {
  const file = event.target.files[0]
  if (!file) return

  imageError.value = ''

  // 파일 크기 체크 (5MB)
  if (file.size > 5 * 1024 * 1024) {
    imageError.value = '이미지 크기는 5MB 이하여야 합니다.'
    return
  }

  // 이미지 타입 체크
  if (!file.type.startsWith('image/')) {
    imageError.value = '이미지 파일만 업로드 가능합니다.'
    return
  }

  try {
    const response = await userSettingsAPI.uploadProfileImage(file)
    if (response.data.success) {
      profile.value = response.data.data
      profileMessage.value = '프로필 이미지가 업로드되었습니다.'
    }
  } catch (err) {
    imageError.value = '이미지 업로드에 실패했습니다.'
  }
}

const deleteProfileImage = async () => {
  if (!confirm('프로필 이미지를 삭제하시겠습니까?')) return

  try {
    const response = await userSettingsAPI.deleteProfileImage()
    if (response.data.success) {
      profile.value = response.data.data
      profileMessage.value = '프로필 이미지가 삭제되었습니다.'
    }
  } catch (err) {
    imageError.value = '이미지 삭제에 실패했습니다.'
  }
}

const updateProfile = async () => {
  profileMessage.value = ''
  profileError.value = ''

  if (!profileForm.name || profileForm.name.trim() === '') {
    profileError.value = '이름을 입력해주세요.'
    return
  }

  try {
    saving.value = true
    const response = await userSettingsAPI.updateProfile({
      name: profileForm.name,
      email: profileForm.email,
      phone: profileForm.phone
    })
    if (response.data.success) {
      profile.value = response.data.data
      profileMessage.value = '프로필이 저장되었습니다.'
      localStorage.setItem('username', profileForm.name)
    } else {
      profileError.value = response.data.message
    }
  } catch (err) {
    if (err.response?.data?.message) {
      profileError.value = err.response.data.message
    } else {
      profileError.value = '프로필 저장에 실패했습니다.'
    }
  } finally {
    saving.value = false
  }
}

const changePassword = async () => {
  passwordMessage.value = ''
  passwordError.value = ''

  if (!passwordForm.currentPassword) {
    passwordError.value = '현재 비밀번호를 입력해주세요.'
    return
  }

  if (!passwordForm.newPassword || passwordForm.newPassword.length < 4) {
    passwordError.value = '새 비밀번호는 4자 이상이어야 합니다.'
    return
  }

  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    passwordError.value = '새 비밀번호가 일치하지 않습니다.'
    return
  }

  try {
    changingPassword.value = true
    const response = await userSettingsAPI.changePassword({
      currentPassword: passwordForm.currentPassword,
      newPassword: passwordForm.newPassword
    })
    if (response.data.success) {
      passwordMessage.value = '비밀번호가 변경되었습니다.'
      passwordForm.currentPassword = ''
      passwordForm.newPassword = ''
      passwordForm.confirmPassword = ''
    } else {
      passwordError.value = response.data.message
    }
  } catch (err) {
    if (err.response?.data?.message) {
      passwordError.value = err.response.data.message
    } else {
      passwordError.value = '비밀번호 변경에 실패했습니다.'
    }
  } finally {
    changingPassword.value = false
  }
}

const formatDate = (dateStr) => {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  return date.toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric'
  })
}

onMounted(() => {
  fetchProfile()
  // 모바일에서만 WebAuthn 카드 노출 (PC 에서도 지원하지만 사용 안 함)
  if (isMobile.value) {
    webauthnSupported.value = isWebauthnSupported()
    if (webauthnSupported.value) {
      loadCredentials()
    }
  }
  if (isAdmin.value) {
    loadSafety()
    loadRecentDiary()
  }
})
</script>

<style scoped>
.settings-grid {
  max-width: var(--content-max-width);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: var(--section-gap);
}

.settings-card {
  background: rgba(30, 30, 50, 0.85);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
}

.settings-card .card-header {
  padding: 20px var(--card-padding);
  background: linear-gradient(135deg, #252540 0%, #2a2a45 100%);
  display: flex;
  align-items: center;
  gap: 16px;
  border-bottom: 2px solid rgba(255,255,255,0.08);
}

.settings-card .card-header .card-icon {
  width: 48px;
  height: 48px;
  background: var(--primary-gradient);
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
}

.settings-card .card-header h2 {
  margin: 0;
  font-size: 20px;
  color: var(--text-primary);
  font-weight: 600;
}

.settings-card .card-body {
  padding: var(--card-padding);
}

.form-group {
  margin-bottom: var(--spacing-md);
}

.form-group label {
  display: block;
  margin-bottom: 8px;
  font-weight: 600;
  color: var(--text-secondary);
  font-size: 14px;
}

.form-group input {
  width: 100%;
  padding: 14px 16px;
  border: 2px solid var(--border-color);
  border-radius: 12px;
  font-size: 15px;
  box-sizing: border-box;
  transition: all 0.3s ease;
}

.form-group input:focus {
  outline: none;
  border-color: var(--primary-start);
  box-shadow: 0 0 0 4px rgba(102, 126, 234, 0.1);
}

.disabled-input {
  background: linear-gradient(135deg, #252540 0%, #2a2a45 100%);
  color: #7878a0;
  cursor: not-allowed;
}

.input-hint {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
  font-style: italic;
}

.btn.full-width {
  width: 100%;
  margin-top: 8px;
}

.alert {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: 12px;
  font-size: 14px;
  text-align: center;
}

.alert-success {
  background: linear-gradient(135deg, rgba(72, 187, 120, 0.1) 0%, rgba(56, 161, 105, 0.1) 100%);
  color: var(--success);
  border: 1px solid rgba(72, 187, 120, 0.3);
}

.alert-error {
  background: linear-gradient(135deg, rgba(252, 92, 125, 0.1) 0%, rgba(231, 76, 60, 0.1) 100%);
  color: var(--danger);
  border: 1px solid rgba(252, 92, 125, 0.3);
}

/* 프로필 이미지 섹션 */
.profile-image-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 28px;
  padding-bottom: 28px;
  border-bottom: 2px solid rgba(255,255,255,0.08);
}

.profile-image-preview {
  width: 120px;
  height: 120px;
  border-radius: 50%;
  overflow: hidden;
  margin-bottom: 16px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
}

.profile-image-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.profile-image-placeholder {
  width: 100%;
  height: 100%;
  background: linear-gradient(135deg, #252540 0%, #2a2a45 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #7878a0;
}

.profile-image-actions {
  display: flex;
  gap: 10px;
}

.btn-sm {
  padding: 8px 16px;
  font-size: 13px;
}

.btn-secondary {
  background: linear-gradient(135deg, #252540 0%, #2a2a45 100%);
  color: #b0b0c8;
  border: 2px solid rgba(255,255,255,0.08);
  cursor: pointer;
  transition: all 0.2s;
}

.btn-secondary:hover {
  border-color: var(--primary-start);
  color: var(--primary-start);
}

.btn-danger {
  background: linear-gradient(135deg, #fc5c7d 0%, #e74c3c 100%);
  color: white;
  border: none;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-danger:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(252, 92, 125, 0.4);
}

.image-error {
  margin-top: 10px;
  font-size: 13px;
  color: var(--danger);
}

.webauthn-list {
  list-style: none;
  padding: 0;
  margin: 12px 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.webauthn-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.kill-card {
  border: 1px solid rgba(252, 92, 125, 0.4);
}
.kill-icon {
  background: linear-gradient(135deg, #fc5c7d, #6a82fb);
}
.kill-status {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
}
.kill-row {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
}
.kill-row span { opacity: 0.7; }
.kill-on  { color: #fc5c7d; }
.kill-off { color: #2ecc71; }
.kill-actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.diary-tabs {
  display: flex;
  gap: 6px;
  margin: 12px 0 14px;
}
.diary-tab {
  padding: 8px 14px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: #ccc;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.15s;
}
.diary-tab.active {
  background: rgba(102, 126, 234, 0.25);
  border-color: rgba(102, 126, 234, 0.6);
  color: #fff;
  font-weight: 600;
}
.diary-tab:hover:not(.active) { background: rgba(255, 255, 255, 0.08); }
.diary-meta { font-size: 14px; margin-bottom: 8px; }
.diary-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 6px 14px;
  font-size: 14px;
  margin: 10px 0;
}
.diary-report {
  white-space: pre-wrap;
  word-break: break-word;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  padding: 14px;
  margin: 8px 0 0;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.6;
  max-height: 600px;
  overflow-y: auto;
}
.diary-history {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.diary-history-item {
  padding: 12px 14px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.06);
}
.pnl-up   { color: #2ecc71; }
.pnl-down { color: #fc5c7d; }

@media (max-width: 768px) {
  .settings-grid {
    gap: 16px;
  }

  .settings-card .card-body {
    padding: 20px;
  }
}

@media (max-width: 480px) {
  .settings-grid {
    padding: 10px;
    gap: 12px;
  }

  .settings-card .card-header {
    padding: 14px;
    gap: 10px;
  }

  .settings-card .card-header h2 {
    font-size: 16px;
  }

  .settings-card .card-body {
    padding: 14px;
  }

  .profile-image-preview {
    width: 96px;
    height: 96px;
  }
}
</style>
