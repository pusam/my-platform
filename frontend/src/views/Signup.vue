<template>
  <div class="signup-container">
    <div class="signup-box">
      <h2>회원가입</h2>
      <form @submit.prevent="handleSignup">
        <div class="form-group">
          <label for="username">아이디</label>
          <input
            type="text"
            id="username"
            v-model="formData.username"
            placeholder="아이디를 입력하세요"
            required
          />
        </div>

        <div class="form-group">
          <label for="password">비밀번호</label>
          <input
            type="password"
            id="password"
            v-model="formData.password"
            placeholder="비밀번호를 입력하세요"
            required
          />
        </div>

        <div class="form-group">
          <label for="passwordConfirm">비밀번호 확인</label>
          <input
            type="password"
            id="passwordConfirm"
            v-model="formData.passwordConfirm"
            placeholder="비밀번호를 다시 입력하세요"
            required
          />
        </div>

        <div class="form-group">
          <label for="name">이름</label>
          <input
            type="text"
            id="name"
            v-model="formData.name"
            placeholder="이름을 입력하세요"
            required
          />
        </div>

        <div class="form-group">
          <label for="email">이메일</label>
          <div class="email-verification">
            <input
              type="email"
              id="email"
              v-model="formData.email"
              placeholder="이메일을 입력하세요"
              required
              :disabled="emailVerified"
            />
            <button
              type="button"
              @click="sendVerificationCode"
              class="btn-verify"
              :disabled="!formData.email || emailSending || emailVerified"
            >
              {{ emailVerified ? '✓ 인증완료' : (emailSending ? '발송 중...' : '인증번호 발송') }}
            </button>
          </div>
        </div>

        <div v-if="verificationSent && !emailVerified" class="form-group">
          <label for="verificationToken">이메일 인증번호</label>
          <div class="email-verification">
            <input
              type="text"
              id="verificationToken"
              v-model="formData.verificationToken"
              placeholder="6자리 인증번호"
              maxlength="6"
              required
            />
            <button
              type="button"
              @click="verifyEmail"
              class="btn-verify"
              :disabled="!formData.verificationToken || verifying"
            >
              {{ verifying ? '확인 중...' : '인증 확인' }}
            </button>
          </div>
          <small class="hint">이메일로 발송된 6자리 인증번호를 입력하세요 (10분간 유효)</small>
        </div>

        <div class="form-group">
          <label for="phone">핸드폰번호</label>
          <input
            type="tel"
            id="phone"
            v-model="formData.phone"
            placeholder="010-1234-5678"
            pattern="[0-9]{3}-[0-9]{4}-[0-9]{4}"
            required
          />
          <small class="hint">형식: 010-1234-5678</small>
        </div>

        <div v-if="errorMessage" class="error-message">
          {{ errorMessage }}
        </div>

        <div v-if="successMessage" class="success-message">
          {{ successMessage }}
        </div>

        <button type="submit" class="btn btn-primary" :disabled="loading">
          {{ loading ? '처리 중...' : '회원가입' }}
        </button>

        <div class="login-link">
          이미 계정이 있으신가요? <router-link to="/login">로그인</router-link>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import axios from 'axios';
import { signup } from '../utils/api';
import { toast } from '../utils/toast';

const router = useRouter();

const formData = ref({
  username: '',
  password: '',
  passwordConfirm: '',
  name: '',
  email: '',
  phone: '',
  verificationToken: ''
});

const errorMessage = ref('');
const successMessage = ref('');
const loading = ref(false);
const emailSending = ref(false);
const verifying = ref(false);
const verificationSent = ref(false);
const emailVerified = ref(false);

// 이메일 인증번호 발송
const sendVerificationCode = async () => {
  if (!formData.value.email) {
    errorMessage.value = '이메일을 입력해주세요.';
    return;
  }

  emailSending.value = true;
  errorMessage.value = '';

  try {
    const response = await axios.post('/api/auth/send-verification', {
      email: formData.value.email
    });

    if (response.data.success) {
      successMessage.value = '인증번호가 이메일로 발송되었습니다.';
      verificationSent.value = true;
      setTimeout(() => {
        successMessage.value = '';
      }, 3000);
    } else {
      errorMessage.value = response.data.message;
    }
  } catch (error) {
    errorMessage.value = '인증번호 발송에 실패했습니다.';
  } finally {
    emailSending.value = false;
  }
};

// 이메일 인증번호 확인
const verifyEmail = async () => {
  if (!formData.value.verificationToken) {
    errorMessage.value = '인증번호를 입력해주세요.';
    return;
  }

  verifying.value = true;
  errorMessage.value = '';

  try {
    const response = await axios.post('/api/auth/verify-email', {
      email: formData.value.email,
      token: formData.value.verificationToken
    });

    if (response.data.success) {
      successMessage.value = '✓ 이메일 인증이 완료되었습니다!';
      emailVerified.value = true;
      setTimeout(() => {
        successMessage.value = '';
      }, 3000);
    } else {
      errorMessage.value = response.data.message;
    }
  } catch (error) {
    errorMessage.value = '인증 확인에 실패했습니다.';
  } finally {
    verifying.value = false;
  }
};

const handleSignup = async () => {
  errorMessage.value = '';
  successMessage.value = '';

  // 이메일 인증 확인
  if (!emailVerified.value) {
    errorMessage.value = '이메일 인증을 완료해주세요.';
    return;
  }

  // 클라이언트 측 유효성 검증
  if (!formData.value.username || !formData.value.password || !formData.value.name) {
    errorMessage.value = '필수 항목을 모두 입력해주세요.';
    return;
  }

  if (formData.value.password !== formData.value.passwordConfirm) {
    errorMessage.value = '비밀번호가 일치하지 않습니다.';
    return;
  }

  if (formData.value.password.length < 4) {
    errorMessage.value = '비밀번호는 4자 이상이어야 합니다.';
    return;
  }

  try {
    loading.value = true;
    const response = await signup(formData.value);

    if (response.success) {
      successMessage.value = response.message;
      toast.success(response.message);
      // 3초 후 로그인 페이지로 이동
      setTimeout(() => {
        router.push('/login');
      }, 3000);
    } else {
      errorMessage.value = response.message || '회원가입에 실패했습니다.';
    }
  } catch (error) {
    console.error('Signup failed:', error);
    errorMessage.value = '회원가입 중 오류가 발생했습니다.';
  } finally {
    loading.value = false;
  }
};
</script>

<style scoped>
.signup-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #0f0f1a 0%, #1a1a2e 100%);
  padding: 20px;
}

.signup-box {
  background: rgba(30, 30, 50, 0.9);
  backdrop-filter: blur(20px);
  padding: 40px;
  border-radius: 12px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.3);
  border: 1px solid rgba(255, 255, 255, 0.1);
  width: 100%;
  max-width: 450px;
}

.signup-box h2 {
  text-align: center;
  color: #f0f0f5;
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
  color: #b0b0c8;
  font-weight: 500;
  font-size: 14px;
}

.form-group input {
  width: 100%;
  padding: 12px 15px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 6px;
  font-size: 14px;
  transition: border-color 0.3s;
  box-sizing: border-box;
  background: #252540;
  color: #f0f0f5;
}

.form-group .hint {
  display: block;
  margin-top: 5px;
  font-size: 12px;
  color: #7878a0;
  font-style: italic;
}

.email-verification {
  display: flex;
  gap: 8px;
}

.email-verification input {
  flex: 1;
}

.btn-verify {
  padding: 12px 20px;
  background: var(--primary-start);
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s;
  white-space: nowrap;
}

.btn-verify:hover:not(:disabled) {
  background: #5568d3;
  transform: translateY(-1px);
}

.btn-verify:disabled {
  background: #3a3a5c;
  color: #7878a0;
  cursor: not-allowed;
  transform: none;
}

.form-group input:focus {
  outline: none;
  border-color: #818cf8;
  box-shadow: 0 0 0 4px rgba(129, 140, 248, 0.2);
}

.form-group input::placeholder {
  color: #7878a0;
}

.error-message {
  background-color: rgba(248, 113, 113, 0.12);
  color: #f87171;
  padding: 12px;
  border-radius: 6px;
  margin-bottom: 20px;
  font-size: 14px;
  text-align: center;
  border: 1px solid rgba(248, 113, 113, 0.2);
}

.success-message {
  background-color: rgba(74, 222, 128, 0.12);
  color: #4ade80;
  padding: 12px;
  border-radius: 6px;
  margin-bottom: 20px;
  font-size: 14px;
  text-align: center;
  white-space: pre-line;
  line-height: 1.6;
  border: 1px solid rgba(74, 222, 128, 0.2);
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
  background: linear-gradient(135deg, var(--primary-start) 0%, #764ba2 100%);
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

.login-link {
  text-align: center;
  margin-top: 20px;
  color: #7878a0;
  font-size: 14px;
}

.login-link a {
  color: #818cf8;
  text-decoration: none;
  font-weight: 600;
}

.login-link a:hover {
  text-decoration: underline;
}
</style>

