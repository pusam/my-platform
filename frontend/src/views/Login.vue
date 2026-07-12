<template>
  <div class="login-page">
    <!-- Animated Background -->
    <div class="background-animation">
      <div class="shape shape-1"></div>
      <div class="shape shape-2"></div>
      <div class="shape shape-3"></div>
    </div>

    <!-- Login Card -->
    <div class="login-card">
      <div class="card-header">
        <div class="logo-container">
          <div class="logo-circle">
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M2 17L12 22L22 17" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M2 12L12 17L22 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
        </div>
        <h1 class="title">Welcome Back</h1>
        <p class="subtitle">로그인하여 계속 진행하세요</p>
      </div>

      <!-- Error Message -->
      <div v-if="errorMessage" class="alert-error">
        <svg viewBox="0 0 20 20" fill="currentColor">
          <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd"/>
        </svg>
        {{ errorMessage }}
      </div>

      <!-- Login Form -->
      <form class="login-form" @submit.prevent="handleLogin">
        <div class="form-group">
          <label for="username">
            <svg viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z" clip-rule="evenodd"/>
            </svg>
            아이디
          </label>
          <input
            type="text"
            id="username"
            v-model="username"
            placeholder="아이디를 입력하세요"
            required
            autocomplete="username"
          >
        </div>

        <div class="form-group">
          <label for="password">
            <svg viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M5 9V7a5 5 0 0110 0v2a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2v-5a2 2 0 012-2zm8-2v2H7V7a3 3 0 016 0z" clip-rule="evenodd"/>
            </svg>
            비밀번호
          </label>
          <input
            type="password"
            id="password"
            v-model="password"
            placeholder="비밀번호를 입력하세요"
            required
            autocomplete="current-password"
          >
        </div>

        <div class="form-options">
          <label class="checkbox-label">
            <input type="checkbox" v-model="remember">
            <span class="checkbox-custom"></span>
            <span class="checkbox-text">로그인 상태 유지</span>
          </label>
          <router-link to="/forgot-password" class="forgot-link">비밀번호 찾기</router-link>
        </div>

        <button type="submit" class="login-btn" :disabled="loading">
          <span v-if="!loading">로그인</span>
          <span v-else class="loading-spinner">
            <svg viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" opacity="0.25"/>
              <path d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" fill="currentColor"/>
            </svg>
            로그인 중...
          </span>
        </button>

        <!-- 패스키 (아이디 없이 OS 패스키 picker) — 모바일에서만 -->
        <button
          v-if="webauthnSupported && isMobile"
          type="button"
          class="btn-ghost webauthn-btn passkey-btn"
          :disabled="passkeyLoading"
          @click="handlePasskeyLogin"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <rect x="3" y="11" width="18" height="11" rx="2"/>
            <path d="M7 11V7a5 5 0 0110 0v4"/>
          </svg>
          {{ passkeyLoading ? '패스키 인증 중…' : '패스키로 로그인' }}
        </button>

        <!-- WebAuthn (아이디 + 지문) 로그인 — 모바일에서만 -->
        <button
          v-if="webauthnSupported && isMobile"
          type="button"
          class="btn-ghost webauthn-btn"
          :disabled="webauthnLoading || !username"
          @click="handleWebauthnLogin"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M12 11c-3 0-3 5-6 5M12 11c3 0 3 5 6 5M12 11V7M12 7a4 4 0 014 4M12 7a4 4 0 00-4 4"/>
            <path d="M4 11a8 8 0 0116 0v4a8 8 0 01-16 0z"/>
          </svg>
          {{ webauthnLoading ? '인증 중…' : '아이디 + 지문 로그인' }}
        </button>
      </form>

      <div class="card-footer">
        <p>계정이 없으신가요? <router-link to="/signup" class="signup-link">회원가입</router-link></p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { authAPI } from '../utils/api'
import { TokenManager, UserManager } from '../utils/auth'
import {
  isWebauthnSupported,
  loginWebauthn,
  loginPasskey,
  registerWebauthn,
  listCredentials,
} from '../utils/webauthn'
import { toast } from '../utils/toast'

const router = useRouter()

const username = ref('')
const password = ref('')
const remember = ref(false)
const errorMessage = ref('')
const loading = ref(false)

const webauthnSupported = ref(false)
const webauthnLoading = ref(false)
const passkeyLoading = ref(false)

const isMobile = computed(() => {
  if (typeof navigator === 'undefined') return false
  return /Mobi|Android|iPhone|iPad|iPod/i.test(navigator.userAgent || '')
})

onMounted(() => {
  webauthnSupported.value = isWebauthnSupported()
})

const handlePasskeyLogin = async () => {
  errorMessage.value = ''
  passkeyLoading.value = true
  try {
    const result = await loginPasskey()
    TokenManager.setToken(result.token)
    UserManager.setUser({
      username: result.username,
      name: result.name,
      role: result.role
    })
    await router.push('/user')
  } catch (e) {
    if (e?.name === 'NotAllowedError') {
      errorMessage.value = '패스키 인증이 취소되었습니다.'
    } else {
      errorMessage.value = e.message || '패스키 로그인 실패. 등록된 패스키가 없을 수 있어요.'
    }
  } finally {
    passkeyLoading.value = false
  }
}

const handleWebauthnLogin = async () => {
  errorMessage.value = ''
  if (!username.value.trim()) {
    errorMessage.value = '아이디를 먼저 입력하세요.'
    return
  }
  webauthnLoading.value = true
  try {
    const result = await loginWebauthn(username.value.trim())
    TokenManager.setToken(result.token)
    UserManager.setUser({
      username: result.username,
      name: result.name,
      role: result.role
    })
    await router.push('/user')
  } catch (e) {
    if (e?.name === 'NotAllowedError') {
      errorMessage.value = '생체인증이 취소되었습니다.'
    } else {
      errorMessage.value = e.message || '지문 로그인 실패'
    }
  } finally {
    webauthnLoading.value = false
  }
}

const handleLogin = async () => {
  errorMessage.value = ''
  loading.value = true

  try {
    const response = await authAPI.login(username.value, password.value)
    const data = response.data

    if (data.success) {
      // JWT 토큰 저장 (access + refresh)
      TokenManager.setToken(data.token)
      TokenManager.setRefreshToken(data.refreshToken)

      // 사용자 정보 저장 (role 포함)
      UserManager.setUser({
        username: data.username,
        name: data.name,
        role: data.role
      })

      // 비번 로그인 성공 시 지문 등록 프롬프트 (등록 안 된 사용자에게만 1회)
      await maybePromptEnroll(data.username)

      // 대시보드로 이동
      await router.push('/user')
    } else {
      errorMessage.value = data.message
    }
  } catch (error) {
    errorMessage.value = error.response?.data?.message || '로그인 처리 중 오류가 발생했습니다.'
  } finally {
    loading.value = false
  }
}

/**
 * 비번 로그인 성공 직후 지문/Face ID 등록을 제안.
 * 조건:
 *  - 브라우저가 WebAuthn 지원
 *  - 이 사용자 이 브라우저에서 이전에 "안 묻기" 선택하지 않음
 *  - 현재 등록된 credential 이 0개
 */
async function maybePromptEnroll(username) {
  // PC 에서는 지문 등록 권유 안 함 (모바일 전용 UX)
  if (!isMobile.value) return
  if (!isWebauthnSupported()) return

  const dismissKey = `webauthn:enroll-dismissed:${username}`
  if (localStorage.getItem(dismissKey) === '1') return

  let existing
  try {
    existing = await listCredentials()
  } catch {
    return // 목록 조회 실패하면 조용히 넘김
  }
  if (existing.length > 0) return

  const yes = confirm(
    '다음부터 지문 / Face ID 로 빠르게 로그인하시겠습니까?\n\n' +
    '지금 이 기기를 등록하면 다음 로그인부터 비밀번호 없이 생체인증으로 들어올 수 있어요.\n\n' +
    '[확인] 지금 등록\n[취소] 나중에 (마이페이지에서 언제든 등록 가능)'
  )
  if (!yes) {
    localStorage.setItem(dismissKey, '1')
    return
  }
  try {
    await registerWebauthn('이 기기')
    toast.success('✅ 등록 완료! 다음 로그인부터 지문/Face ID 로 로그인 가능합니다.')
  } catch (e) {
    if (e?.name === 'NotAllowedError') {
      toast.info('생체인증이 취소되었습니다. 나중에 마이페이지에서 등록할 수 있어요.')
    } else {
      toast.error('등록 실패: ' + (e.message || e))
    }
    // 실패해도 로그인은 계속 진행 (try 밖에서 router.push)
  }
}
</script>

<style scoped>
@import '../assets/css/login.css';

/* 골격은 공용 .btn-ghost — 로그인 CTA 라 더 강한 표면·크기만 로컬 */
.webauthn-btn {
  margin-top: 10px;
  width: 100%;
  padding: 12px 16px;
  font-size: 15px;
  font-weight: 500;
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
  border-color: rgba(255, 255, 255, 0.2);
  border-radius: 12px;
}
.webauthn-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.14);
  border-color: rgba(255, 255, 255, 0.35);
}
.webauthn-btn svg {
  width: 20px;
  height: 20px;
}
.passkey-btn {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.18), rgba(118, 75, 162, 0.18));
  border-color: rgba(118, 75, 162, 0.45);
}
.passkey-btn:hover:not(:disabled) {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.32), rgba(118, 75, 162, 0.32));
  border-color: rgba(118, 75, 162, 0.7);
}
</style>

