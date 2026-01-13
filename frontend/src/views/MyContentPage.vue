<template>
  <div class="my-content-page">
    <div class="header">
      <h1>내 콘텐츠</h1>
      <div class="header-actions">
        <button @click="goBack" class="back-btn">← 돌아가기</button>
        <button @click="logout" class="logout-btn">로그아웃</button>
      </div>
    </div>

    <div class="page-content">
      <div class="content-section">
        <div class="section-header">
          <h2>내가 작성한 게시글</h2>
          <span class="total-count">총 {{ totalCount }}개</span>
        </div>

        <div class="board-list" v-if="boards.length > 0">
          <div class="board-item" v-for="board in boards" :key="board.id" @click="goToBoard(board.id)">
            <div class="board-info">
              <h3 class="board-title">{{ board.title }}</h3>
              <div class="board-meta">
                <span class="date">{{ formatDate(board.createdAt) }}</span>
                <span class="views">조회 {{ board.views }}</span>
                <span class="files" v-if="board.files && board.files.length > 0">
                  첨부 {{ board.files.length }}개
                </span>
              </div>
            </div>
            <button class="delete-btn" @click.stop="confirmDelete(board)">삭제</button>
          </div>
        </div>

        <div class="empty-state" v-else-if="!loading">
          <p>작성한 게시글이 없습니다.</p>
          <button @click="goToBoard()" class="action-btn">게시글 작성하기</button>
        </div>

        <div class="loading" v-if="loading">
          <div class="spinner"></div>
          <p>불러오는 중...</p>
        </div>

        <!-- 페이지네이션 -->
        <div class="pagination" v-if="totalPages > 1">
          <button
            @click="changePage(currentPage - 1)"
            :disabled="currentPage === 0"
            class="page-btn"
          >이전</button>
          <span class="page-info">{{ currentPage + 1 }} / {{ totalPages }}</span>
          <button
            @click="changePage(currentPage + 1)"
            :disabled="currentPage >= totalPages - 1"
            class="page-btn"
          >다음</button>
        </div>
      </div>
    </div>

    <!-- 삭제 확인 모달 -->
    <div class="modal-overlay" v-if="showDeleteModal" @click="showDeleteModal = false">
      <div class="modal" @click.stop>
        <h3>게시글 삭제</h3>
        <p>"{{ selectedBoard?.title }}"을(를) 삭제하시겠습니까?</p>
        <div class="modal-actions">
          <button @click="showDeleteModal = false" class="cancel-btn">취소</button>
          <button @click="deleteBoard" class="confirm-btn">삭제</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { boardAPI } from '../utils/api'
import { UserManager } from '../utils/auth'

const router = useRouter()
const boards = ref([])
const loading = ref(true)
const currentPage = ref(0)
const totalPages = ref(0)
const totalCount = ref(0)
const showDeleteModal = ref(false)
const selectedBoard = ref(null)

const goBack = () => {
  router.back()
}

const logout = () => {
  UserManager.logout()
  router.push('/login')
}

const fetchMyBoards = async (page = 0) => {
  try {
    loading.value = true
    const response = await boardAPI.getMyBoards(page, 10)
    if (response.data.success) {
      boards.value = response.data.data.content
      totalPages.value = response.data.data.totalPages
      totalCount.value = response.data.data.totalElements
      currentPage.value = page
    }
  } catch (err) {
    console.error('내 게시글 조회 실패:', err)
  } finally {
    loading.value = false
  }
}

const changePage = (page) => {
  if (page >= 0 && page < totalPages.value) {
    fetchMyBoards(page)
  }
}

const goToBoard = (id) => {
  if (id) {
    router.push(`/board?view=${id}`)
  } else {
    router.push('/board')
  }
}

const confirmDelete = (board) => {
  selectedBoard.value = board
  showDeleteModal.value = true
}

const deleteBoard = async () => {
  if (!selectedBoard.value) return

  try {
    await boardAPI.deleteBoard(selectedBoard.value.id)
    showDeleteModal.value = false
    selectedBoard.value = null
    fetchMyBoards(currentPage.value)
  } catch (err) {
    console.error('게시글 삭제 실패:', err)
    alert('게시글 삭제에 실패했습니다.')
  }
}

const formatDate = (dateStr) => {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  return date.toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  })
}

onMounted(() => {
  fetchMyBoards()
})
</script>

<style scoped>
.my-content-page {
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
  max-width: 900px;
  margin: 0 auto;
}

.content-section {
  background: white;
  border-radius: 10px;
  padding: 30px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 15px;
  border-bottom: 2px solid #eee;
}

.section-header h2 {
  margin: 0;
  color: #333;
  font-size: 22px;
}

.total-count {
  color: #666;
  font-size: 14px;
}

.board-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.board-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  background: #f8f9fa;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.board-item:hover {
  background: #e9ecef;
  transform: translateX(5px);
}

.board-info {
  flex: 1;
}

.board-title {
  margin: 0 0 8px 0;
  font-size: 16px;
  color: #333;
}

.board-meta {
  display: flex;
  gap: 15px;
  font-size: 13px;
  color: #888;
}

.delete-btn {
  padding: 8px 16px;
  background: #f44336;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 13px;
}

.delete-btn:hover {
  background: #d32f2f;
}

.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: #666;
}

.empty-state p {
  margin-bottom: 20px;
}

.action-btn {
  padding: 12px 24px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 14px;
}

.loading {
  text-align: center;
  padding: 40px;
}

.spinner {
  width: 40px;
  height: 40px;
  border: 4px solid #eee;
  border-top-color: #667eea;
  border-radius: 50%;
  margin: 0 auto 16px;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 20px;
  margin-top: 30px;
  padding-top: 20px;
  border-top: 1px solid #eee;
}

.page-btn {
  padding: 8px 16px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
}

.page-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.page-info {
  color: #666;
}

/* 모달 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}

.modal {
  background: white;
  padding: 30px;
  border-radius: 10px;
  max-width: 400px;
  width: 90%;
}

.modal h3 {
  margin: 0 0 15px 0;
  color: #333;
}

.modal p {
  color: #666;
  margin-bottom: 25px;
}

.modal-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}

.cancel-btn {
  padding: 10px 20px;
  background: #eee;
  color: #333;
  border: none;
  border-radius: 5px;
  cursor: pointer;
}

.confirm-btn {
  padding: 10px 20px;
  background: #f44336;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
}
</style>
