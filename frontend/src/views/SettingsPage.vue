<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>내 설정</h1>
        <div class="header-actions">
          <button @click="goBack" class="btn btn-back">돌아가기</button>
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
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
}

.settings-card .card-header {
  padding: 20px var(--card-padding);
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  display: flex;
  align-items: center;
  gap: 16px;
  border-bottom: 2px solid var(--border-light);
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
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  color: var(--text-muted);
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
  border-bottom: 2px solid var(--border-light);
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
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
}

[data-theme="dark"] .profile-image-placeholder {
  background: linear-gradient(135deg, #27272a 0%, #1f1f23 100%);
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
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  color: var(--text-secondary);
  border: 2px solid var(--border-color);
  cursor: pointer;
  transition: all 0.2s;
}

.btn-secondary:hover {
  border-color: var(--primary-start);
  color: var(--primary-start);
}

[data-theme="dark"] .btn-secondary {
  background: linear-gradient(135deg, #27272a 0%, #1f1f23 100%);
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

@media (max-width: 768px) {
  .settings-grid {
    gap: 16px;
  }

  .settings-card .card-body {
    padding: 20px;
  }
}
</style>
