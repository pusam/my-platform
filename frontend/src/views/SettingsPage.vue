<template>
  <div class="settings-page">
    <div class="header">
      <h1>내 설정</h1>
      <div class="header-actions">
        <button @click="goBack" class="back-btn">← 돌아가기</button>
        <button @click="logout" class="logout-btn">로그아웃</button>
      </div>
    </div>

    <div class="page-content">
      <!-- 프로필 섹션 -->
      <div class="settings-section">
        <h2>프로필 정보</h2>

        <div class="form-group">
          <label>아이디</label>
          <input type="text" :value="profile.username" disabled class="disabled-input" />
          <span class="hint">아이디는 변경할 수 없습니다.</span>
        </div>

        <div class="form-group">
          <label>이름</label>
          <input type="text" v-model="profileForm.name" placeholder="이름을 입력하세요" />
        </div>

        <div class="form-group">
          <label>역할</label>
          <input type="text" :value="profile.role === 'ADMIN' ? '관리자' : '일반 사용자'" disabled class="disabled-input" />
        </div>

        <div class="form-group">
          <label>가입일</label>
          <input type="text" :value="formatDate(profile.createdAt)" disabled class="disabled-input" />
        </div>

        <button @click="updateProfile" class="save-btn" :disabled="saving">
          {{ saving ? '저장 중...' : '프로필 저장' }}
        </button>

        <div class="message success" v-if="profileMessage">{{ profileMessage }}</div>
        <div class="message error" v-if="profileError">{{ profileError }}</div>
      </div>

      <!-- 비밀번호 변경 섹션 -->
      <div class="settings-section">
        <h2>비밀번호 변경</h2>

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

        <button @click="changePassword" class="save-btn" :disabled="changingPassword">
          {{ changingPassword ? '변경 중...' : '비밀번호 변경' }}
        </button>

        <div class="message success" v-if="passwordMessage">{{ passwordMessage }}</div>
        <div class="message error" v-if="passwordError">{{ passwordError }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { userSettingsAPI } from '../utils/api'
import { UserManager } from '../utils/auth'

const router = useRouter()

const profile = ref({
  username: '',
  name: '',
  role: '',
  createdAt: ''
})

const profileForm = reactive({
  name: ''
})

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
      profileForm.name = response.data.data.name
    }
  } catch (err) {
    console.error('프로필 조회 실패:', err)
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
    const response = await userSettingsAPI.updateProfile({ name: profileForm.name })
    if (response.data.success) {
      profile.value = response.data.data
      profileMessage.value = '프로필이 저장되었습니다.'
      // localStorage 업데이트
      localStorage.setItem('username', profileForm.name)
    } else {
      profileError.value = response.data.message
    }
  } catch (err) {
    profileError.value = '프로필 저장에 실패했습니다.'
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
})
</script>

<style scoped>
.settings-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 20px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: white;
  padding: 20px 30px;
  border-radius: 10px;
  margin-bottom: 30px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.header h1 {
  margin: 0;
  color: #333;
  font-size: 28px;
}

.header-actions {
  display: flex;
  gap: 10px;
}

.back-btn {
  padding: 10px 20px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 14px;
}

.back-btn:hover {
  background: #5a67d8;
}

.logout-btn {
  padding: 10px 20px;
  background: #f44336;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 14px;
}

.page-content {
  max-width: 600px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.settings-section {
  background: white;
  border-radius: 10px;
  padding: 30px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.settings-section h2 {
  margin: 0 0 25px 0;
  color: #333;
  font-size: 20px;
  padding-bottom: 15px;
  border-bottom: 2px solid #eee;
}

.form-group {
  margin-bottom: 20px;
}

.form-group label {
  display: block;
  margin-bottom: 8px;
  font-weight: 600;
  color: #333;
  font-size: 14px;
}

.form-group input {
  width: 100%;
  padding: 12px 15px;
  border: 1px solid #ddd;
  border-radius: 5px;
  font-size: 14px;
  box-sizing: border-box;
}

.form-group input:focus {
  outline: none;
  border-color: #667eea;
}

.disabled-input {
  background: #f5f5f5;
  color: #666;
}

.hint {
  display: block;
  margin-top: 5px;
  font-size: 12px;
  color: #888;
}

.save-btn {
  width: 100%;
  padding: 14px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 5px;
  font-size: 16px;
  cursor: pointer;
  transition: background 0.3s;
}

.save-btn:hover:not(:disabled) {
  background: #5a67d8;
}

.save-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.message {
  margin-top: 15px;
  padding: 12px;
  border-radius: 5px;
  font-size: 14px;
  text-align: center;
}

.message.success {
  background: #e8f5e9;
  color: #2e7d32;
}

.message.error {
  background: #ffebee;
  color: #c62828;
}
</style>
