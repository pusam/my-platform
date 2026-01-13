<template>
  <div class="board-page">
    <div class="board-header">
      <h1>📋 게시판</h1>
      <div class="header-actions">
        <input
          v-model="searchKeyword"
          @keyup.enter="searchBoards"
          type="text"
          placeholder="검색어를 입력하세요"
          class="search-input"
        >
        <button @click="searchBoards" class="search-btn">🔍 검색</button>
        <button @click="showWriteForm" class="write-btn">✍️ 글쓰기</button>
        <button @click="goBack" class="back-btn">← 돌아가기</button>
      </div>
    </div>

    <!-- 게시글 목록 -->
    <div v-if="!isWriting && !selectedBoard" class="board-list">
      <table class="board-table">
        <thead>
          <tr>
            <th width="60">번호</th>
            <th>제목</th>
            <th width="100">작성자</th>
            <th width="80">조회수</th>
            <th width="150">작성일</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="boards.length === 0">
            <td colspan="5" class="no-data">게시글이 없습니다.</td>
          </tr>
          <tr v-for="board in boards" :key="board.id" @click="viewBoard(board.id)" class="board-row">
            <td>{{ board.id }}</td>
            <td class="title-cell">
              {{ board.title }}
              <span v-if="board.files && board.files.length > 0" class="file-icon">📎</span>
            </td>
            <td>{{ board.authorName }}</td>
            <td>{{ board.views }}</td>
            <td>{{ formatDate(board.createdAt) }}</td>
          </tr>
        </tbody>
      </table>

      <!-- 페이지네이션 -->
      <div class="pagination">
        <button @click="changePage(currentPage - 1)" :disabled="currentPage === 0">이전</button>
        <span>{{ currentPage + 1 }} / {{ totalPages }}</span>
        <button @click="changePage(currentPage + 1)" :disabled="currentPage >= totalPages - 1">다음</button>
      </div>
    </div>

    <!-- 글쓰기/수정 폼 -->
    <div v-if="isWriting" class="write-form">
      <h2>{{ isEditing ? '게시글 수정' : '게시글 작성' }}</h2>
      <div class="form-group">
        <label>제목</label>
        <input v-model="form.title" type="text" placeholder="제목을 입력하세요" class="form-input">
      </div>
      <div class="form-group">
        <label>내용</label>
        <QuillEditor
          v-model:content="form.content"
          contentType="html"
          theme="snow"
          :toolbar="editorToolbar"
          class="quill-editor"
        />
      </div>
      <div class="form-group">
        <label>파일 첨부 (최대 10MB/파일)</label>
        <input type="file" @change="handleFileChange" multiple class="form-file" accept="*/*">
        <div v-if="selectedFiles.length > 0" class="file-list">
          <div v-for="(file, index) in selectedFiles" :key="index" class="file-item">
            <span>{{ file.name }} ({{ formatFileSize(file.size) }})</span>
            <button @click="removeFile(index)" class="remove-file-btn">×</button>
          </div>
        </div>
        <p class="file-info">※ 파일당 최대 10MB, 총 50MB까지 첨부 가능</p>
      </div>
      <div class="form-actions">
        <button @click="submitForm" class="submit-btn">{{ isEditing ? '수정' : '작성' }}</button>
        <button @click="cancelWrite" class="cancel-btn">취소</button>
      </div>
    </div>

    <!-- 게시글 상세 -->
    <div v-if="selectedBoard" class="board-detail">
      <div class="detail-header">
        <h2>{{ selectedBoard.title }}</h2>
        <div class="detail-info">
          <span>작성자: {{ selectedBoard.authorName }}</span>
          <span>조회수: {{ selectedBoard.views }}</span>
          <span>작성일: {{ formatDate(selectedBoard.createdAt) }}</span>
        </div>
      </div>
      <div class="detail-content" v-html="selectedBoard.content">
      </div>
      <div v-if="selectedBoard.files && selectedBoard.files.length > 0" class="detail-files">
        <h3>첨부파일</h3>
        <div v-for="file in selectedBoard.files" :key="file.id" class="file-download">
          <a @click.prevent="downloadFile(file.id, file.originalFilename)" href="#">
            📎 {{ file.originalFilename }} ({{ formatFileSize(file.fileSize) }})
          </a>
          <button v-if="canEdit(selectedBoard)" @click="deleteFile(selectedBoard.id, file.id)" class="delete-file-btn">삭제</button>
        </div>
      </div>
      <div class="detail-actions">
        <button @click="backToList" class="back-btn">목록</button>
        <template v-if="canEdit(selectedBoard)">
          <button @click="editBoard" class="edit-btn">수정</button>
          <button @click="deleteBoard" class="delete-btn">삭제</button>
        </template>
      </div>
    </div>
  </div>
</template>

<script>
import axios from 'axios';
import { QuillEditor } from '@vueup/vue-quill';
import '@vueup/vue-quill/dist/vue-quill.snow.css';

export default {
  name: 'BoardPage',
  components: {
    QuillEditor
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
      pageSize: 10,
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
        const token = localStorage.getItem('jwt_token');
        const response = await axios.get(`/api/board?page=${page}&size=${this.pageSize}`, {
          headers: { 'Authorization': `Bearer ${token}` }
        });

        if (response.data.success) {
          this.boards = response.data.data.content;
          this.currentPage = response.data.data.number;
          this.totalPages = response.data.data.totalPages;
        }
      } catch (error) {
        console.error('게시글 로드 실패:', error);
        alert('게시글을 불러오는데 실패했습니다.');
      }
    },
    async searchBoards() {
      if (!this.searchKeyword.trim()) {
        this.loadBoards();
        return;
      }

      try {
        const token = localStorage.getItem('jwt_token');
        const response = await axios.get(
          `/api/board/search?keyword=${encodeURIComponent(this.searchKeyword)}&page=${this.currentPage}&size=${this.pageSize}`,
          { headers: { 'Authorization': `Bearer ${token}` } }
        );

        if (response.data.success) {
          this.boards = response.data.data.content;
          this.totalPages = response.data.data.totalPages;
        }
      } catch (error) {
        console.error('검색 실패:', error);
        alert('검색에 실패했습니다.');
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
    showWriteForm() {
      this.isWriting = true;
      this.isEditing = false;
      this.form = { title: '', content: '' };
      this.selectedFiles = [];
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
    async deleteBoard() {
      if (!confirm('정말 삭제하시겠습니까?')) return;

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

        // 게시글 다시 로드
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
      const maxFileSize = 10 * 1024 * 1024; // 10MB
      const maxTotalSize = 50 * 1024 * 1024; // 50MB

      // 개별 파일 크기 검증
      for (const file of files) {
        if (file.size > maxFileSize) {
          alert(`${file.name} 파일이 너무 큽니다. (최대 10MB)\n현재 크기: ${this.formatFileSize(file.size)}`);
          event.target.value = ''; // 입력 초기화
          return;
        }
      }

      // 전체 파일 크기 검증
      const newFiles = [...this.selectedFiles, ...files];
      const totalSize = newFiles.reduce((sum, file) => sum + file.size, 0);

      if (totalSize > maxTotalSize) {
        alert(`전체 파일 크기가 50MB를 초과합니다.\n현재 크기: ${this.formatFileSize(totalSize)}`);
        event.target.value = ''; // 입력 초기화
        return;
      }

      this.selectedFiles = newFiles;
      event.target.value = ''; // 다음 선택을 위해 초기화
    },
    removeFile(index) {
      this.selectedFiles.splice(index, 1);
    },
    cancelWrite() {
      this.isWriting = false;
      this.form = { title: '', content: '' };
      this.selectedFiles = [];
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
.board-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 20px;
}

.board-header {
  background: white;
  padding: 20px 30px;
  border-radius: 10px;
  margin-bottom: 20px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.board-header h1 {
  margin: 0 0 15px 0;
  color: #333;
}

.header-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.search-input {
  flex: 1;
  padding: 10px;
  border: 1px solid #ddd;
  border-radius: 5px;
  font-size: 14px;
}

.search-btn, .write-btn, .back-btn {
  padding: 10px 20px;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.3s;
}

.search-btn {
  background: #667eea;
  color: white;
}

.write-btn {
  background: #4caf50;
  color: white;
}

.back-btn {
  background: #9e9e9e;
  color: white;
}

.board-list {
  background: white;
  padding: 20px;
  border-radius: 10px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.board-table {
  width: 100%;
  border-collapse: collapse;
}

.board-table th {
  background: #f5f5f5;
  padding: 15px;
  text-align: left;
  border-bottom: 2px solid #ddd;
  font-weight: 600;
}

.board-table td {
  padding: 15px;
  border-bottom: 1px solid #eee;
}

.board-row {
  cursor: pointer;
  transition: background 0.2s;
}

.board-row:hover {
  background: #f9f9f9;
}

.title-cell {
  font-weight: 500;
}

.file-icon {
  margin-left: 5px;
  color: #666;
}

.no-data {
  text-align: center;
  color: #999;
  padding: 40px !important;
}

.pagination {
  margin-top: 20px;
  text-align: center;
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 15px;
}

.pagination button {
  padding: 8px 16px;
  border: 1px solid #ddd;
  background: white;
  border-radius: 5px;
  cursor: pointer;
}

.pagination button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.write-form, .board-detail {
  background: white;
  padding: 30px;
  border-radius: 10px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.write-form h2, .board-detail h2 {
  margin: 0 0 20px 0;
  color: #333;
}

.form-group {
  margin-bottom: 20px;
}

.form-group label {
  display: block;
  margin-bottom: 8px;
  font-weight: 600;
  color: #333;
}

.form-input {
  width: 100%;
  padding: 12px;
  border: 1px solid #ddd;
  border-radius: 5px;
  font-size: 14px;
  box-sizing: border-box;
}

.form-textarea {
  width: 100%;
  min-height: 300px;
  padding: 12px;
  border: 1px solid #ddd;
  border-radius: 5px;
  font-size: 14px;
  resize: vertical;
  box-sizing: border-box;
  font-family: inherit;
}

.quill-editor {
  background: white;
  border-radius: 5px;
}

.quill-editor :deep(.ql-container) {
  min-height: 300px;
  font-size: 14px;
  border-bottom-left-radius: 5px;
  border-bottom-right-radius: 5px;
}

.quill-editor :deep(.ql-toolbar) {
  border-top-left-radius: 5px;
  border-top-right-radius: 5px;
  background: #f5f5f5;
}

.quill-editor :deep(.ql-editor) {
  min-height: 300px;
}

.quill-editor :deep(.ql-editor.ql-blank::before) {
  color: #999;
  font-style: normal;
}

.form-file {
  width: 100%;
  padding: 10px;
  border: 1px solid #ddd;
  border-radius: 5px;
}

.file-info {
  margin-top: 8px;
  font-size: 12px;
  color: #666;
}

.file-list {
  margin-top: 10px;
}

.file-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: #f5f5f5;
  border-radius: 5px;
  margin-bottom: 5px;
}

.remove-file-btn, .delete-file-btn {
  background: #f44336;
  color: white;
  border: none;
  border-radius: 3px;
  padding: 4px 10px;
  cursor: pointer;
}

.form-actions, .detail-actions {
  display: flex;
  gap: 10px;
  margin-top: 20px;
}

.submit-btn, .edit-btn {
  padding: 12px 24px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 14px;
}

.cancel-btn {
  padding: 12px 24px;
  background: #9e9e9e;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 14px;
}

.delete-btn {
  padding: 12px 24px;
  background: #f44336;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 14px;
}

.detail-header {
  border-bottom: 2px solid #eee;
  padding-bottom: 15px;
  margin-bottom: 20px;
}

.detail-info {
  display: flex;
  gap: 20px;
  margin-top: 10px;
  color: #666;
  font-size: 14px;
}

.detail-content {
  padding: 20px 0;
  line-height: 1.8;
  white-space: pre-wrap;
  min-height: 200px;
}

.detail-content :deep(h1),
.detail-content :deep(h2),
.detail-content :deep(h3),
.detail-content :deep(h4),
.detail-content :deep(h5),
.detail-content :deep(h6) {
  margin: 20px 0 10px 0;
  font-weight: 600;
}

.detail-content :deep(p) {
  margin: 10px 0;
}

.detail-content :deep(ul),
.detail-content :deep(ol) {
  margin: 10px 0;
  padding-left: 30px;
}

.detail-content :deep(img) {
  max-width: 100%;
  height: auto;
  margin: 10px 0;
}

.detail-content :deep(a) {
  color: #667eea;
  text-decoration: underline;
}

.detail-content :deep(blockquote) {
  border-left: 4px solid #ddd;
  padding-left: 15px;
  margin: 15px 0;
  color: #666;
}

.detail-files {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #eee;
}

.detail-files h3 {
  margin: 0 0 15px 0;
  font-size: 16px;
}

.file-download {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px;
  background: #f5f5f5;
  border-radius: 5px;
  margin-bottom: 5px;
}

.file-download a {
  color: #667eea;
  text-decoration: none;
  cursor: pointer;
}

.file-download a:hover {
  text-decoration: underline;
}
</style>

