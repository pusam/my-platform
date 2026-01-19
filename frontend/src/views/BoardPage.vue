<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>게시판</h1>
        <div class="header-actions">
          <div class="header-user">
            <span>{{ currentUsername }}</span>
          </div>
          <button @click="goBack" class="btn btn-back">돌아가기</button>
        </div>
      </header>

      <!-- 검색 & 글쓰기 영역 -->
      <div class="action-bar" v-if="!isWriting && !selectedBoard">
        <div class="search-box">
          <input
            v-model="searchKeyword"
            @keyup.enter="searchBoards"
            type="text"
            placeholder="게시글 검색..."
            class="search-input"
          >
          <button @click="searchBoards" class="btn-search">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="11" cy="11" r="8"/>
              <path d="M21 21l-4.35-4.35"/>
            </svg>
          </button>
        </div>
        <div class="action-right">
          <span class="board-count">총 {{ totalElements }}개</span>
          <button @click="goToWrite" class="btn btn-primary">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 5v14M5 12h14"/>
            </svg>
            글쓰기
          </button>
        </div>
      </div>

      <!-- 게시글 목록 -->
      <div v-if="!isWriting && !selectedBoard" class="board-section">
        <LoadingSpinner v-if="loading" message="게시글을 불러오는 중..." />

        <div v-else-if="boards.length === 0" class="empty-state">
          <div class="empty-icon">
            <svg width="80" height="80" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
              <polyline points="14,2 14,8 20,8"/>
              <line x1="16" y1="13" x2="8" y2="13"/>
              <line x1="16" y1="17" x2="8" y2="17"/>
              <polyline points="10,9 9,9 8,9"/>
            </svg>
          </div>
          <h3>게시글이 없습니다</h3>
          <p>첫 번째 게시글을 작성해보세요!</p>
          <button @click="goToWrite" class="btn btn-primary">글쓰기</button>
        </div>

        <div v-else class="board-grid">
          <article
            v-for="board in boards"
            :key="board.id"
            @click="viewBoard(board.id)"
            class="board-card"
          >
            <div class="card-top">
              <span class="board-id">#{{ board.id }}</span>
              <span class="board-views">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                  <circle cx="12" cy="12" r="3"/>
                </svg>
                {{ board.views }}
              </span>
            </div>
            <h3 class="card-title">
              {{ board.title }}
              <span v-if="board.files && board.files.length > 0" class="file-badge">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48"/>
                </svg>
                {{ board.files.length }}
              </span>
            </h3>
            <div class="card-bottom">
              <div class="author-info">
                <div class="author-avatar">{{ board.authorName.charAt(0) }}</div>
                <span class="author-name">{{ board.authorName }}</span>
              </div>
              <span class="post-date">{{ formatDate(board.createdAt) }}</span>
            </div>
          </article>
        </div>

        <!-- 페이지네이션 -->
        <div v-if="boards.length > 0" class="pagination">
          <button @click="changePage(currentPage - 1)" :disabled="currentPage === 0" class="page-btn">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="15,18 9,12 15,6"/>
            </svg>
            이전
          </button>
          <div class="page-info">
            <span class="current-page">{{ currentPage + 1 }}</span>
            <span>/</span>
            <span>{{ totalPages }}</span>
          </div>
          <button @click="changePage(currentPage + 1)" :disabled="currentPage >= totalPages - 1" class="page-btn">
            다음
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </button>
        </div>
      </div>

      <!-- 글쓰기/수정 페이지 -->
      <div v-if="isWriting" class="write-section">
        <div class="write-card">
          <div class="write-header">
            <h2>{{ isEditing ? '게시글 수정' : '새 게시글' }}</h2>
            <button @click="confirmCancel" class="btn-close">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>

          <div class="write-form">
            <div class="form-group">
              <label>제목</label>
              <div class="title-input-wrapper">
                <input
                  v-model="form.title"
                  type="text"
                  placeholder="제목을 입력하세요"
                  maxlength="200"
                >
                <span class="char-count">{{ form.title.length }}/200</span>
              </div>
            </div>

            <div class="form-group">
              <label>내용</label>
              <QuillEditor
                v-model:content="form.content"
                contentType="html"
                theme="snow"
                :toolbar="editorToolbar"
                class="content-editor"
                placeholder="내용을 입력하세요..."
              />
            </div>

            <div class="form-group">
              <label>파일 첨부</label>
              <div class="file-upload-area">
                <label class="file-drop-zone">
                  <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                    <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                    <polyline points="17,8 12,3 7,8"/>
                    <line x1="12" y1="3" x2="12" y2="15"/>
                  </svg>
                  <span>클릭하여 파일 선택</span>
                  <span class="file-hint">파일당 최대 10MB, 총 50MB</span>
                  <input type="file" @change="handleFileChange" multiple class="file-input" accept="*/*">
                </label>
              </div>
            </div>

            <div v-if="selectedFiles.length > 0" class="attached-files">
              <div v-for="(file, index) in selectedFiles" :key="index" class="file-chip">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
                  <polyline points="14,2 14,8 20,8"/>
                </svg>
                <span class="file-name">{{ file.name }}</span>
                <span class="file-size">{{ formatFileSize(file.size) }}</span>
                <button @click="removeFile(index)" class="btn-remove-file">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="18" y1="6" x2="6" y2="18"/>
                    <line x1="6" y1="6" x2="18" y2="18"/>
                  </svg>
                </button>
              </div>
            </div>

            <div class="form-actions">
              <button @click="confirmCancel" class="btn btn-secondary">취소</button>
              <button @click="submitForm" class="btn btn-primary" :disabled="!form.title.trim()">
                {{ isEditing ? '수정 완료' : '게시글 등록' }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- 게시글 상세보기 -->
      <div v-if="selectedBoard" class="detail-section">
        <article class="detail-card">
          <div class="detail-header">
            <h2 class="detail-title">{{ selectedBoard.title }}</h2>
            <div class="detail-meta">
              <div class="meta-item">
                <div class="author-avatar large">{{ selectedBoard.authorName.charAt(0) }}</div>
                <span>{{ selectedBoard.authorName }}</span>
              </div>
              <div class="meta-item">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                  <circle cx="12" cy="12" r="3"/>
                </svg>
                <span>{{ selectedBoard.views }}</span>
              </div>
              <div class="meta-item">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="3" y="4" width="18" height="18" rx="2" ry="2"/>
                  <line x1="16" y1="2" x2="16" y2="6"/>
                  <line x1="8" y1="2" x2="8" y2="6"/>
                  <line x1="3" y1="10" x2="21" y2="10"/>
                </svg>
                <span>{{ formatDate(selectedBoard.createdAt) }}</span>
              </div>
            </div>
          </div>

          <div class="detail-content" v-html="selectedBoard.content"></div>

          <div v-if="selectedBoard.files && selectedBoard.files.length > 0" class="detail-files">
            <h3>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48"/>
              </svg>
              첨부파일 ({{ selectedBoard.files.length }})
            </h3>
            <div class="files-grid">
              <div v-for="file in selectedBoard.files" :key="file.id" class="file-item">
                <a @click.prevent="downloadFile(file.id, file.originalFilename)" href="#" class="file-link">
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                    <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
                    <polyline points="14,2 14,8 20,8"/>
                  </svg>
                  <div class="file-info">
                    <span class="file-name">{{ file.originalFilename }}</span>
                    <span class="file-size">{{ formatFileSize(file.fileSize) }}</span>
                  </div>
                </a>
                <button
                  v-if="canEdit(selectedBoard)"
                  @click="deleteFile(selectedBoard.id, file.id)"
                  class="btn-file-delete"
                >
                  삭제
                </button>
              </div>
            </div>
          </div>

          <div class="detail-actions">
            <button @click="backToList" class="btn btn-secondary">목록으로</button>
            <div v-if="canEdit(selectedBoard)" class="action-group">
              <button @click="editBoard" class="btn btn-primary">수정</button>
              <button @click="confirmDelete" class="btn btn-danger">삭제</button>
            </div>
          </div>
        </article>
      </div>
    </div>
  </div>
</template>

<script>
import axios from 'axios';
import { QuillEditor } from '@vueup/vue-quill';
import '@vueup/vue-quill/dist/vue-quill.snow.css';
import LoadingSpinner from '../components/LoadingSpinner.vue';

export default {
  name: 'BoardPage',
  components: {
    QuillEditor,
    LoadingSpinner
  },
  data() {
    return {
      boards: [],
      selectedBoard: null,
      isWriting: false,
      isEditing: false,
      searchKeyword: '',
      currentPage: 0,
      totalPages: 0,
      totalElements: 0,
      pageSize: 10,
      loading: false,
      form: {
        title: '',
        content: ''
      },
      selectedFiles: [],
      currentUsername: '',
      editorToolbar: [
        [{ 'header': [1, 2, 3, 4, 5, 6, false] }],
        ['bold', 'italic', 'underline', 'strike'],
        [{ 'color': [] }, { 'background': [] }],
        [{ 'list': 'ordered'}, { 'list': 'bullet' }],
        [{ 'align': [] }],
        ['link', 'image'],
        ['clean']
      ]
    };
  },
  mounted() {
    this.currentUsername = localStorage.getItem('username') || '';
    this.loadBoards();
  },
  methods: {
    async loadBoards(page = 0) {
      try {
        this.loading = true;
        const token = localStorage.getItem('jwt_token');
        const response = await axios.get(`/api/board?page=${page}&size=${this.pageSize}`, {
          headers: { 'Authorization': `Bearer ${token}` }
        });

        if (response.data.success) {
          this.boards = response.data.data.content;
          this.currentPage = response.data.data.number;
          this.totalPages = response.data.data.totalPages;
          this.totalElements = response.data.data.totalElements || 0;
        }
      } catch (error) {
        console.error('게시글 로드 실패:', error);
        alert('게시글을 불러오는데 실패했습니다.');
      } finally {
        this.loading = false;
      }
    },
    async searchBoards() {
      if (!this.searchKeyword.trim()) {
        this.loadBoards();
        return;
      }

      try {
        this.loading = true;
        const token = localStorage.getItem('jwt_token');
        const response = await axios.get(
          `/api/board/search?keyword=${encodeURIComponent(this.searchKeyword)}&page=${this.currentPage}&size=${this.pageSize}`,
          { headers: { 'Authorization': `Bearer ${token}` } }
        );

        if (response.data.success) {
          this.boards = response.data.data.content;
          this.totalPages = response.data.data.totalPages;
          this.totalElements = response.data.data.totalElements || 0;
        }
      } catch (error) {
        console.error('검색 실패:', error);
        alert('검색에 실패했습니다.');
      } finally {
        this.loading = false;
      }
    },
    async viewBoard(id) {
      try {
        const token = localStorage.getItem('jwt_token');
        const response = await axios.get(`/api/board/${id}`, {
          headers: { 'Authorization': `Bearer ${token}` }
        });

        if (response.data.success) {
          this.selectedBoard = response.data.data;
        }
      } catch (error) {
        console.error('게시글 조회 실패:', error);
        alert('게시글을 불러오는데 실패했습니다.');
      }
    },
    goToWrite() {
      this.isWriting = true;
      this.isEditing = false;
      this.selectedBoard = null;
      this.form = { title: '', content: '' };
      this.selectedFiles = [];
      window.scrollTo(0, 0);
    },
    editBoard() {
      this.isWriting = true;
      this.isEditing = true;
      this.form = {
        title: this.selectedBoard.title,
        content: this.selectedBoard.content
      };
      this.selectedBoard = null;
    },
    async submitForm() {
      if (!this.form.title.trim() || !this.form.content.trim()) {
        alert('제목과 내용을 입력하세요.');
        return;
      }

      try {
        const token = localStorage.getItem('jwt_token');
        const formData = new FormData();

        const boardBlob = new Blob([JSON.stringify(this.form)], { type: 'application/json' });
        formData.append('board', boardBlob);

        this.selectedFiles.forEach(file => {
          formData.append('files', file);
        });

        let response;
        if (this.isEditing) {
          response = await axios.put(`/api/board/${this.selectedBoard.id}`, formData, {
            headers: {
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'multipart/form-data'
            }
          });
        } else {
          response = await axios.post('/api/board', formData, {
            headers: {
              'Authorization': `Bearer ${token}`,
              'Content-Type': 'multipart/form-data'
            }
          });
        }

        if (response.data.success) {
          alert(response.data.message);
          this.isWriting = false;
          this.loadBoards();
        }
      } catch (error) {
        console.error('게시글 작성/수정 실패:', error);
        alert('게시글 작성/수정에 실패했습니다.');
      }
    },
    confirmCancel() {
      if (this.form.title || this.form.content) {
        if (!confirm('작성 중인 내용이 있습니다. 정말 취소하시겠습니까?')) {
          return;
        }
      }
      this.cancelWrite();
    },
    cancelWrite() {
      this.isWriting = false;
      this.isEditing = false;
      this.form = { title: '', content: '' };
      this.selectedFiles = [];
    },
    confirmDelete() {
      if (!confirm('정말 이 게시글을 삭제하시겠습니까?')) return;
      this.deleteBoard();
    },
    async deleteBoard() {
      try {
        const token = localStorage.getItem('jwt_token');
        const response = await axios.delete(`/api/board/${this.selectedBoard.id}`, {
          headers: { 'Authorization': `Bearer ${token}` }
        });

        if (response.data.success) {
          alert(response.data.message);
          this.selectedBoard = null;
          this.loadBoards();
        }
      } catch (error) {
        console.error('게시글 삭제 실패:', error);
        alert('게시글 삭제에 실패했습니다.');
      }
    },
    async deleteFile(boardId, fileId) {
      if (!confirm('파일을 삭제하시겠습니까?')) return;

      try {
        const token = localStorage.getItem('jwt_token');
        await axios.delete(`/api/board/${boardId}/file/${fileId}`, {
          headers: { 'Authorization': `Bearer ${token}` }
        });
        this.viewBoard(boardId);
      } catch (error) {
        console.error('파일 삭제 실패:', error);
        alert('파일 삭제에 실패했습니다.');
      }
    },
    async downloadFile(fileId, filename) {
      try {
        const token = localStorage.getItem('jwt_token');
        const response = await axios.get(`/api/board/file/${fileId}`, {
          headers: { 'Authorization': `Bearer ${token}` },
          responseType: 'blob'
        });

        const url = window.URL.createObjectURL(new Blob([response.data]));
        const link = document.createElement('a');
        link.href = url;
        link.setAttribute('download', filename);
        document.body.appendChild(link);
        link.click();
        link.remove();
      } catch (error) {
        console.error('파일 다운로드 실패:', error);
        alert('파일 다운로드에 실패했습니다.');
      }
    },
    handleFileChange(event) {
      const files = Array.from(event.target.files);
      const maxFileSize = 10 * 1024 * 1024;
      const maxTotalSize = 50 * 1024 * 1024;

      for (const file of files) {
        if (file.size > maxFileSize) {
          alert(`${file.name} 파일이 너무 큽니다. (최대 10MB)\n현재 크기: ${this.formatFileSize(file.size)}`);
          event.target.value = '';
          return;
        }
      }

      const newFiles = [...this.selectedFiles, ...files];
      const totalSize = newFiles.reduce((sum, file) => sum + file.size, 0);

      if (totalSize > maxTotalSize) {
        alert(`전체 파일 크기가 50MB를 초과합니다.\n현재 크기: ${this.formatFileSize(totalSize)}`);
        event.target.value = '';
        return;
      }

      this.selectedFiles = newFiles;
      event.target.value = '';
    },
    removeFile(index) {
      this.selectedFiles.splice(index, 1);
    },
    backToList() {
      this.selectedBoard = null;
    },
    changePage(page) {
      if (page >= 0 && page < this.totalPages) {
        this.loadBoards(page);
      }
    },
    canEdit(board) {
      return board && board.author === this.currentUsername;
    },
    goBack() {
      const role = localStorage.getItem('role');
      if (role === 'ADMIN') {
        this.$router.push('/admin');
      } else {
        this.$router.push('/user');
      }
    },
    formatDate(dateString) {
      const date = new Date(dateString);
      return date.toLocaleString('ko-KR');
    },
    formatFileSize(bytes) {
      if (bytes === 0) return '0 Bytes';
      const k = 1024;
      const sizes = ['Bytes', 'KB', 'MB', 'GB'];
      const i = Math.floor(Math.log(bytes) / Math.log(k));
      return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
    }
  }
};
</script>

<style scoped>
/* 액션 바 */
.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  gap: 20px;
  flex-wrap: wrap;
}

.search-box {
  display: flex;
  gap: 8px;
  flex: 1;
  max-width: 400px;
}

.search-input {
  flex: 1;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(10px);
}

.btn-search {
  padding: 14px 18px;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(10px);
  border: 2px solid var(--border-color);
  border-radius: 12px;
  cursor: pointer;
  color: var(--text-secondary);
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-search:hover {
  background: var(--primary-gradient);
  border-color: transparent;
  color: white;
}

.action-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.board-count {
  color: white;
  font-weight: 600;
  font-size: 14px;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
}

/* 게시글 그리드 */
.board-section {
  animation: fadeIn 0.4s ease;
  position: relative;
  min-height: 200px;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.board-grid {
  display: grid;
  gap: 20px;
}

.board-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: 24px;
  cursor: pointer;
  transition: all 0.3s ease;
  border: 2px solid transparent;
}

.board-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.15);
  border-color: var(--primary-start);
}

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.board-id {
  font-size: 12px;
  color: var(--text-muted);
  font-weight: 600;
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  padding: 4px 12px;
  border-radius: 20px;
}

.board-views {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--text-muted);
}

.card-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 16px 0;
  line-height: 1.5;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.file-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--primary-start);
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.1) 0%, rgba(118, 75, 162, 0.1) 100%);
  padding: 4px 10px;
  border-radius: 12px;
  font-weight: 500;
}

.card-bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.author-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.author-avatar {
  width: 32px;
  height: 32px;
  background: var(--primary-gradient);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-weight: 600;
  font-size: 14px;
}

.author-avatar.large {
  width: 40px;
  height: 40px;
  font-size: 16px;
}

.author-name {
  font-weight: 500;
  color: var(--text-secondary);
  font-size: 14px;
}

.post-date {
  font-size: 13px;
  color: var(--text-muted);
}

/* 글쓰기 섹션 */
.write-section {
  animation: slideUp 0.4s ease;
}

@keyframes slideUp {
  from { opacity: 0; transform: translateY(20px); }
  to { opacity: 1; transform: translateY(0); }
}

.write-card {
  background: rgba(255, 255, 255, 0.98);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 40px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

.write-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 32px;
  padding-bottom: 24px;
  border-bottom: 2px solid var(--border-light);
}

.write-header h2 {
  margin: 0;
  font-size: 26px;
  background: var(--primary-gradient);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.btn-close {
  width: 44px;
  height: 44px;
  background: #f8f9fa;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  color: var(--text-muted);
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-close:hover {
  background: var(--danger);
  color: white;
}

.write-form {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.title-input-wrapper {
  position: relative;
}

.title-input-wrapper input {
  font-size: 18px;
  font-weight: 500;
}

.char-count {
  position: absolute;
  right: 16px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 12px;
  color: var(--text-muted);
}

.content-editor {
  min-height: 400px;
  border-radius: 12px;
  border: 2px solid var(--border-color);
  overflow: hidden;
}

.file-upload-area {
  margin-top: 8px;
}

.file-drop-zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px;
  border: 2px dashed var(--border-color);
  border-radius: 16px;
  cursor: pointer;
  transition: all 0.3s;
  color: var(--text-muted);
  gap: 12px;
  background: linear-gradient(135deg, #fafbfc 0%, #f5f7fa 100%);
}

.file-drop-zone:hover {
  border-color: var(--primary-start);
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.05) 0%, rgba(118, 75, 162, 0.05) 100%);
  color: var(--primary-start);
}

.file-input {
  display: none;
}

.file-hint {
  font-size: 12px;
  color: var(--text-light);
}

.attached-files {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.file-chip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  font-size: 14px;
}

.file-chip .file-name {
  color: var(--text-primary);
  font-weight: 500;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-chip .file-size {
  color: var(--text-muted);
  font-size: 12px;
}

.btn-remove-file {
  width: 24px;
  height: 24px;
  background: var(--border-color);
  border: none;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  transition: all 0.2s;
}

.btn-remove-file:hover {
  background: var(--danger);
  color: white;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 24px;
  border-top: 1px solid var(--border-light);
}

/* 상세보기 섹션 */
.detail-section {
  animation: slideUp 0.4s ease;
}

.detail-card {
  background: rgba(255, 255, 255, 0.98);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 48px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
  max-width: 900px;
  margin: 0 auto;
}

.detail-header {
  margin-bottom: 32px;
  padding-bottom: 24px;
  border-bottom: 2px solid var(--border-light);
}

.detail-title {
  font-size: 32px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 20px 0;
  line-height: 1.4;
}

.detail-meta {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: var(--text-muted);
}

.detail-content {
  font-size: 16px;
  line-height: 1.9;
  color: var(--text-primary);
  margin-bottom: 32px;
  min-height: 200px;
}

.detail-content :deep(img) {
  max-width: 100%;
  height: auto;
  margin: 20px 0;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);
}

.detail-content :deep(p) {
  margin: 16px 0;
}

.detail-content :deep(h1),
.detail-content :deep(h2),
.detail-content :deep(h3) {
  margin: 28px 0 16px 0;
  font-weight: 700;
}

.detail-content :deep(ul),
.detail-content :deep(ol) {
  margin: 16px 0;
  padding-left: 32px;
}

.detail-content :deep(blockquote) {
  border-left: 4px solid var(--primary-start);
  padding: 16px 20px;
  margin: 20px 0;
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.05) 0%, rgba(118, 75, 162, 0.05) 100%);
  border-radius: 0 12px 12px 0;
  color: var(--text-secondary);
}

.detail-files {
  margin-bottom: 32px;
  padding: 24px;
  background: linear-gradient(135deg, #f8f9fa 0%, #f1f3f5 100%);
  border-radius: 16px;
}

.detail-files h3 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 20px 0;
  font-size: 16px;
  color: var(--text-primary);
}

.files-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.file-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  background: white;
  border: 1px solid var(--border-color);
  border-radius: 12px;
  transition: all 0.2s;
}

.file-item:hover {
  border-color: var(--primary-start);
  box-shadow: 0 4px 16px rgba(102, 126, 234, 0.1);
}

.file-link {
  display: flex;
  align-items: center;
  gap: 16px;
  text-decoration: none;
  color: var(--text-primary);
  flex: 1;
}

.file-link svg {
  color: var(--primary-start);
}

.file-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.file-info .file-name {
  font-weight: 500;
  color: var(--text-primary);
}

.file-info .file-size {
  font-size: 12px;
  color: var(--text-muted);
}

.btn-file-delete {
  padding: 8px 16px;
  background: linear-gradient(135deg, #fee 0%, #fdd 100%);
  color: var(--danger);
  border: 1px solid #fcc;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  transition: all 0.2s;
}

.btn-file-delete:hover {
  background: var(--danger);
  color: white;
  border-color: var(--danger);
}

.detail-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 24px;
  border-top: 2px solid var(--border-light);
}

.action-group {
  display: flex;
  gap: 12px;
}

/* Quill Editor 스타일 */
.content-editor :deep(.ql-container) {
  min-height: 400px;
  font-size: 15px;
}

.content-editor :deep(.ql-editor) {
  min-height: 400px;
  line-height: 1.8;
}

.content-editor :deep(.ql-toolbar) {
  border-top-left-radius: 12px;
  border-top-right-radius: 12px;
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  border-color: var(--border-color);
}

.content-editor :deep(.ql-container) {
  border-bottom-left-radius: 12px;
  border-bottom-right-radius: 12px;
  border-color: var(--border-color);
}

/* 반응형 */
@media (max-width: 768px) {
  .action-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .search-box {
    max-width: 100%;
  }

  .action-right {
    justify-content: space-between;
  }

  .write-card,
  .detail-card {
    padding: 24px;
  }

  .detail-title {
    font-size: 24px;
  }

  .detail-meta {
    flex-direction: column;
    gap: 12px;
  }

  .detail-actions {
    flex-direction: column;
    gap: 12px;
  }

  .detail-actions .btn {
    width: 100%;
  }

  .action-group {
    width: 100%;
  }

  .action-group .btn {
    flex: 1;
  }
}
</style>