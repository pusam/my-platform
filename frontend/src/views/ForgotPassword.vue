<template>
  <div class="forgot-password-container">
    <div class="forgot-password-box">
      <h2>비밀번호 찾기</h2>

      <!-- 단계 1: 아이디 + 이메일 입력 -->
      <form v-if="step === 1" @submit.prevent="sendVerificationCode">
        <div class="form-group">
          <label for="username">아이디</label>
          <input
            type="text"
            id="username"
            v-model="username"
            placeholder="아이디를 입력하세요"
            required
          />
        </div>

        <div class="form-group">
          <label for="email">이메일</label>
          <input
            type="email"
            id="email"
            v-model="email"
            placeholder="가입 시 사용한 이메일을 입력하세요"
            required
          />
        </div>

        <div v-if="errorMessage" class="error-message">
          {{ errorMessage }}
        </div>

        <div v-if="successMessage" class="success-message">
          {{ successMessage }}
        </div>

        <button type="submit" class="btn btn-primary" :disabled="loading">
          {{ loading ? '처리 중...' : '인증번호 발송' }}
        </button>

        <div class="back-link">
          <router-link to="/login">로그인으로 돌아가기</router-link>
        </div>
      </form>

      <!-- 단계 2: 인증번호 입력 -->
      <form v-if="step === 2" @submit.prevent="verifyCode">
        <div class="info-message">
          <p>{{ username }} ({{ email }})로 인증번호가 발송되었습니다.</p>
          <p>이메일을 확인해주세요. (10분간 유효)</p>
        </div>

        <div class="form-group">
          <label for="verificationCode">인증번호</label>
          <input
            type="text"
            id="verificationCode"
            v-model="verificationCode"
            placeholder="6자리 인증번호를 입력하세요"
            maxlength="6"
            pattern="[0-9]{6}"
            required
          />
        </div>

        <div v-if="errorMessage" class="error-message">
          {{ errorMessage }}
        </div>

        <button type="submit" class="btn btn-primary" :disabled="loading">
          {{ loading ? '확인 중...' : '인증번호 확인' }}
        </button>

        <div class="back-link">
          <a href="#" @click.prevent="goBackToStep1">아이디/이메일 다시 입력</a>
        </div>
      </form>

      <!-- 단계 3: 새 비밀번호 입력 -->
      <form v-if="step === 3" @submit.prevent="resetPassword">
        <div class="info-message">
          <p>새로운 비밀번호를 입력하세요.</p>
        </div>

        <div class="form-group">
          <label for="newPassword">새 비밀번호</label>
          <input
            type="password"
            id="newPassword"
            v-model="newPassword"
            placeholder="새 비밀번호를 입력하세요"
            required
          />
        </div>

        <div class="form-group">
          <label for="confirmPassword">비밀번호 확인</label>
          <input
            type="password"
            id="confirmPassword"
            v-model="confirmPassword"
            placeholder="비밀번호를 다시 입력하세요"
            required
          />
        </div>

        <div v-if="errorMessage" class="error-message">
          {{ errorMessage }}
        </div>

        <div v-if="successMessage" class="success-message">
          {{ successMessage }}
        </div>

        <button type="submit" class="btn btn-primary" :disabled="loading">
          {{ loading ? '처리 중...' : '비밀번호 변경' }}
        </button>
      </form>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'

const router = useRouter()

const step = ref(1)
const username = ref('')
const email = ref('')
const verificationCode = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const errorMessage = ref('')
const successMessage = ref('')
const loading = ref(false)

const sendVerificationCode = async () => {
  errorMessage.value = ''
  successMessage.value = ''
  loading.value = true

  try {
    const response = await axios.post('/api/password/reset/send', {
      username: username.value,
      email: email.value
    })

    if (response.data.success) {
      successMessage.value = response.data.message
      setTimeout(() => {
        step.value = 2
        successMessage.value = ''
      }, 1500)
    } else {
      errorMessage.value = response.data.message
    }
  } catch (error) {
    errorMessage.value = '인증번호 발송에 실패했습니다.'
  } finally {
    loading.value = false
  }
}

const verifyCode = async () => {
  errorMessage.value = ''
  loading.value = true

  try {
    const response = await axios.post('/api/password/reset/verify', {
      username: username.value,
      email: email.value,
      token: verificationCode.value
    })

    if (response.data.success) {
      step.value = 3
    } else {
      errorMessage.value = response.data.message
    }
  } catch (error) {
    errorMessage.value = '인증번호 확인에 실패했습니다.'
  } finally {
    loading.value = false
  }
}

const resetPassword = async () => {
  errorMessage.value = ''
  successMessage.value = ''

  if (newPassword.value !== confirmPassword.value) {
    errorMessage.value = '비밀번호가 일치하지 않습니다.'
    return
  }

  if (newPassword.value.length < 4) {
    errorMessage.value = '비밀번호는 4자 이상이어야 합니다.'
    return
  }

  loading.value = true

  try {
    const response = await axios.post('/api/password/reset/confirm', {
      username: username.value,
      email: email.value,
      token: verificationCode.value,
      newPassword: newPassword.value
    })

    if (response.data.success) {
      successMessage.value = '비밀번호가 변경되었습니다. 로그인 페이지로 이동합니다.'
      setTimeout(() => {
        router.push('/login')
      }, 2000)
    } else {
      errorMessage.value = response.data.message
    }
  } catch (error) {
    errorMessage.value = '비밀번호 변경에 실패했습니다.'
  } finally {
    loading.value = false
  }
}

const goBackToStep1 = () => {
  step.value = 1
  username.value = ''
  verificationCode.value = ''
  errorMessage.value = ''
  successMessage.value = ''
}
</script>

<style scoped>
.forgot-password-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 20px;
}

.forgot-password-box {
  background: white;
  padding: 40px;
  border-radius: 12px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.2);
  width: 100%;
  max-width: 450px;
}

.forgot-password-box h2 {
  text-align: center;
  color: #333;
  margin-bottom: 30px;
  font-size: 28px;
  font-weight: 600;
}

.form-group {
  margin-bottom: 20px;
}

.form-group label {
  display: block;
  margin-bottom: 8px;
  color: #555;
  font-weight: 500;
  font-size: 14px;
}

.form-group input {
  width: 100%;
  padding: 12px 15px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
  transition: border-color 0.3s;
  box-sizing: border-box;
}

.form-group input:focus {
  outline: none;
  border-color: #667eea;
}

.info-message {
  background: #e3f2fd;
  border-left: 4px solid #2196f3;
  padding: 15px;
  margin-bottom: 20px;
  border-radius: 4px;
}

.info-message p {
  margin: 5px 0;
  color: #1976d2;
  font-size: 14px;
}

.error-message {
  background: #ffebee;
  color: #c62828;
  padding: 12px;
  border-radius: 6px;
  margin-bottom: 15px;
  font-size: 14px;
  border-left: 4px solid #c62828;
}

.success-message {
  background: #e8f5e9;
  color: #2e7d32;
  padding: 12px;
  border-radius: 6px;
  margin-bottom: 15px;
  font-size: 14px;
  border-left: 4px solid #4caf50;
}

.btn {
  width: 100%;
  padding: 14px;
  border: none;
  border-radius: 6px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s;
}

.btn-primary {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 5px 15px rgba(102, 126, 234, 0.4);
}

.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.back-link {
  text-align: center;
  margin-top: 20px;
}

.back-link a {
  color: #667eea;
  text-decoration: none;
  font-size: 14px;
}

.back-link a:hover {
  text-decoration: underline;
}
</style>

