<template>
  <div class="page-container">
    <div class="page-content">
      <header class="common-header">
        <h1>📁 파일 관리</h1>
        <div class="header-actions">
          <button @click="goBack" class="btn btn-back">← 돌아가기</button>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

    <div class="file-manager-content">

    <!-- Breadcrumb 경로 -->
    <div class="breadcrumb" v-if="content">
      <span @click="navigateToRoot" class="breadcrumb-item">
        🏠 내 파일
      </span>
      <span v-for="(folder, index) in content.breadcrumbs" :key="folder.id" class="breadcrumb-item">
        <span class="separator">›</span>
        <span @click="navigateToFolder(folder.id)" :class="{ active: index === content.breadcrumbs.length - 1 }">
          {{ folder.name }}
        </span>
      </span>
    </div>

    <!-- 액션 바 -->
    <div class="action-bar">
      <button @click="showCreateFolderModal = true" class="btn btn-primary">
        📁 새 폴더
      </button>
      <button @click="showUploadModal = true" class="btn btn-success">
        📤 파일 업로드
      </button>
    </div>

    <!-- 로딩 -->
    <div v-if="loading" class="loading">로딩 중...</div>

    <!-- 에러 메시지 -->
    <div v-if="errorMessage" class="error-message">{{ errorMessage }}</div>

    <!-- 폴더 및 파일 목록 -->
    <div v-if="!loading && content" class="file-grid">
      <!-- 폴더 목록 -->
      <div
        v-for="folder in content.folders"
        :key="'folder-' + folder.id"
        class="file-item folder-item"
        @click="navigateToFolder(folder.id)"
        @contextmenu.prevent="showFolderContextMenu(folder, $event)"
      >
        <div class="icon">📁</div>
        <div class="name">{{ folder.name }}</div>
        <div class="meta">{{ formatDate(folder.createdAt) }}</div>
      </div>

      <!-- 파일 목록 -->
      <div
        v-for="file in content.files"
        :key="'file-' + file.id"
        class="file-item"
        @click="viewFile(file)"
        @contextmenu.prevent="showFileContextMenu(file, $event)"
      >
        <div class="icon">{{ getFileIcon(file.fileExtension) }}</div>
        <div class="name">{{ file.originalName }}</div>
        <div class="meta">
          {{ formatFileSize(file.fileSize) }} · {{ formatDate(file.uploadDate) }}
        </div>
      </div>

      <!-- 빈 폴더 메시지 -->
      <div v-if="content.folders.length === 0 && content.files.length === 0" class="empty-message">
        이 폴더는 비어 있습니다. 파일을 업로드하거나 새 폴더를 만들어보세요.
      </div>
    </div>

    <!-- 폴더 생성 모달 -->
    <div v-if="showCreateFolderModal" class="modal-overlay" @click="closeCreateFolderModal">
      <div class="modal-content" @click.stop>
        <h2>새 폴더 만들기</h2>
        <form @submit.prevent="createFolder">
          <div class="form-group">
            <label>폴더 이름</label>
            <input
              type="text"
              v-model="newFolderName"
              placeholder="폴더 이름을 입력하세요"
              required
              autofocus
            />
          </div>
          <div v-if="modalError" class="error-message">{{ modalError }}</div>
          <div class="modal-actions">
            <button type="submit" class="btn btn-primary" :disabled="processing">생성</button>
            <button type="button" @click="closeCreateFolderModal" class="btn btn-secondary">취소</button>
          </div>
        </form>
      </div>
    </div>

    <!-- 파일 업로드 모달 -->
    <div v-if="showUploadModal" class="modal-overlay" @click="closeUploadModal">
      <div class="modal-content" @click.stop>
        <h2>파일 업로드</h2>
        <form @submit.prevent="uploadFile">
          <div class="form-group">
            <label>파일 선택</label>
            <input
              type="file"
              @change="onFileSelected"
              required
            />
            <p class="file-info">※ 최대 100MB까지 업로드 가능 (이미지, 영상, 문서 등)</p>
          </div>
          <div class="form-group">
            <label>업로드 날짜 (선택)</label>
            <input
              type="date"
              v-model="uploadDate"
              :max="today"
            />
          </div>
          <div v-if="selectedFile" class="file-preview">
            <strong>선택된 파일:</strong> {{ selectedFile.name }} ({{ formatFileSize(selectedFile.size) }})
          </div>
          <div v-if="modalError" class="error-message">{{ modalError }}</div>
          <div class="modal-actions">
            <button type="submit" class="btn btn-success" :disabled="processing || !selectedFile">
              {{ processing ? '업로드 중...' : '업로드' }}
            </button>
            <button type="button" @click="closeUploadModal" class="btn btn-secondary">취소</button>
          </div>
        </form>
      </div>
    </div>

    <!-- 컨텍스트 메뉴 -->
    <div
      v-if="contextMenu.show"
      class="context-menu"
      :style="{ top: contextMenu.y + 'px', left: contextMenu.x + 'px' }"
      @click="closeContextMenu"
    >
      <div class="context-menu-item" @click="deleteItem">
        🗑️ 삭제
      </div>
    </div>

    <!-- 이미지 뷰어 모달 -->
    <div v-if="imageViewer.show" class="modal-overlay" @click="closeImageViewer">
      <div class="image-viewer" @click.stop>
        <button @click="closeImageViewer" class="close-btn">✕</button>
        <img :src="imageViewer.url" :alt="imageViewer.name" />
        <div class="image-info">{{ imageViewer.name }}</div>
      </div>
    </div>

    <!-- 비디오 뷰어 모달 -->
    <div v-if="videoViewer.show" class="modal-overlay" @click="closeVideoViewer">
      <div class="video-viewer" @click.stop>
        <button @click="closeVideoViewer" class="close-btn">✕</button>
        <video :src="videoViewer.url" controls autoplay>
          브라우저가 비디오 재생을 지원하지 않습니다.
        </video>
        <div class="video-info">{{ videoViewer.name }}</div>
      </div>
    </div>
    </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { fileAPI } from '../utils/api';
import { UserManager } from '../utils/auth';

const router = useRouter();

const content = ref(null);
const loading = ref(true);
const errorMessage = ref('');
const currentFolderId = ref(null);

const showCreateFolderModal = ref(false);
const showUploadModal = ref(false);
const newFolderName = ref('');
const selectedFile = ref(null);
const uploadDate = ref('');
const modalError = ref('');
const processing = ref(false);

const contextMenu = ref({
  show: false,
  x: 0,
  y: 0,
  item: null,
  type: null // 'folder' or 'file'
});

const imageViewer = ref({
  show: false,
  url: '',
  name: ''
});

const videoViewer = ref({
  show: false,
  url: '',
  name: ''
});

const today = computed(() => {
  const date = new Date();
  return date.toISOString().split('T')[0];
});

const loadFolder = async (folderId = null) => {
  try {
    loading.value = true;
    errorMessage.value = '';
    const response = await fileAPI.getFolderContent(folderId);
    content.value = response.data.data;
    currentFolderId.value = folderId;
  } catch (error) {
    console.error('Failed to load folder:', error);
    errorMessage.value = '폴더를 불러오는데 실패했습니다.';
  } finally {
    loading.value = false;
  }
};

const navigateToRoot = () => {
  loadFolder(null);
};

const navigateToFolder = (folderId) => {
  loadFolder(folderId);
};

const createFolder = async () => {
  if (!newFolderName.value.trim()) {
    modalError.value = '폴더 이름을 입력하세요.';
    return;
  }

  try {
    processing.value = true;
    modalError.value = '';
    await fileAPI.createFolder(currentFolderId.value, newFolderName.value);
    closeCreateFolderModal();
    await loadFolder(currentFolderId.value);
  } catch (error) {
    console.error('Failed to create folder:', error);
    modalError.value = error.response?.data?.message || '폴더 생성에 실패했습니다.';
  } finally {
    processing.value = false;
  }
};

const onFileSelected = (event) => {
  const file = event.target.files[0];
  if (file) {
    // 100MB = 100 * 1024 * 1024 bytes
    const maxSize = 100 * 1024 * 1024;
    if (file.size > maxSize) {
      modalError.value = '파일 크기는 100MB를 초과할 수 없습니다.';
      event.target.value = ''; // 파일 선택 초기화
      return;
    }
    selectedFile.value = file;
    modalError.value = '';
  }
};

const uploadFile = async () => {
  if (!selectedFile.value) {
    modalError.value = '파일을 선택하세요.';
    return;
  }

  try {
    processing.value = true;
    modalError.value = '';
    await fileAPI.uploadFile(currentFolderId.value, selectedFile.value, uploadDate.value);
    closeUploadModal();
    await loadFolder(currentFolderId.value);
  } catch (error) {
    console.error('Failed to upload file:', error);
    modalError.value = error.response?.data?.message || '파일 업로드에 실패했습니다.';
  } finally {
    processing.value = false;
  }
};

const showFolderContextMenu = (folder, event) => {
  contextMenu.value = {
    show: true,
    x: event.clientX,
    y: event.clientY,
    item: folder,
    type: 'folder'
  };
};

const showFileContextMenu = (file, event) => {
  contextMenu.value = {
    show: true,
    x: event.clientX,
    y: event.clientY,
    item: file,
    type: 'file'
  };
};

const closeContextMenu = () => {
  contextMenu.value.show = false;
};

const deleteItem = async () => {
  if (!confirm(`정말 삭제하시겠습니까?`)) {
    return;
  }

  try {
    if (contextMenu.value.type === 'folder') {
      await fileAPI.deleteFolder(contextMenu.value.item.id);
    } else {
      await fileAPI.deleteFile(contextMenu.value.item.id);
    }
    await loadFolder(currentFolderId.value);
  } catch (error) {
    console.error('Failed to delete:', error);
    alert('삭제에 실패했습니다.');
  } finally {
    closeContextMenu();
  }
};

const viewFile = (file) => {
  if (file.fileType && file.fileType.startsWith('image/')) {
    imageViewer.value = {
      show: true,
      url: `http://localhost:8080${file.downloadUrl}`,
      name: file.originalName
    };
  } else if (file.fileType && file.fileType.startsWith('video/')) {
    videoViewer.value = {
      show: true,
      url: `http://localhost:8080${file.downloadUrl}`,
      name: file.originalName
    };
  } else {
    // 다운로드
    window.open(`http://localhost:8080${file.downloadUrl}`, '_blank');
  }
};

const closeImageViewer = () => {
  imageViewer.value.show = false;
};

const closeVideoViewer = () => {
  videoViewer.value.show = false;
};

const closeCreateFolderModal = () => {
  showCreateFolderModal.value = false;
  newFolderName.value = '';
  modalError.value = '';
};

const closeUploadModal = () => {
  showUploadModal.value = false;
  selectedFile.value = null;
  uploadDate.value = '';
  modalError.value = '';
};

const formatFileSize = (bytes) => {
  if (!bytes) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
};

const formatDate = (dateString) => {
  if (!dateString) return '';
  const date = new Date(dateString);
  return date.toLocaleDateString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' });
};

const getFileIcon = (extension) => {
  if (!extension) return '📄';
  const ext = extension.toLowerCase();

  const iconMap = {
    '.jpg': '🖼️', '.jpeg': '🖼️', '.png': '🖼️', '.gif': '🖼️', '.bmp': '🖼️',
    '.pdf': '📕',
    '.doc': '📘', '.docx': '📘',
    '.xls': '📗', '.xlsx': '📗',
    '.ppt': '📙', '.pptx': '📙',
    '.zip': '🗜️', '.rar': '🗜️', '.7z': '🗜️',
    '.mp3': '🎵', '.wav': '🎵',
    '.mp4': '🎬', '.avi': '🎬', '.mkv': '🎬',
    '.txt': '📝',
    '.js': '💻', '.java': '💻', '.py': '💻', '.html': '💻', '.css': '💻'
  };

  return iconMap[ext] || '📄';
};

const goBack = () => {
  router.back();
};

const logout = () => {
  UserManager.logout();
  router.push('/login');
};

// 전역 클릭 이벤트로 컨텍스트 메뉴 닫기
onMounted(() => {
  loadFolder();
  document.addEventListener('click', closeContextMenu);
});
</script>

<style scoped>
@import '../assets/css/common.css';

.file-manager-content {
  max-width: 1400px;
  margin: 0 auto;
}

.breadcrumb {
  background: #f5f5f5;
  padding: 12px 20px;
  border-radius: 8px;
  margin-bottom: 20px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}

.breadcrumb-item {
  display: inline-flex;
  align-items: center;
  color: #666;
  cursor: pointer;
  transition: color 0.2s;
}

.breadcrumb-item:hover {
  color: #667eea;
}

.breadcrumb-item .separator {
  margin: 0 10px;
  color: #999;
}

.breadcrumb-item.active,
.breadcrumb-item .active {
  color: #667eea;
  font-weight: 600;
}

.action-bar {
  margin-bottom: 20px;
  display: flex;
  gap: 10px;
}

.loading {
  text-align: center;
  padding: 60px;
  font-size: 18px;
  color: #666;
}

.error-message {
  background: #fee;
  color: #c33;
  padding: 15px;
  border-radius: 6px;
  margin-bottom: 20px;
  text-align: center;
}

.file-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 15px;
}

.file-item {
  background: white;
  border: 2px solid #e0e0e0;
  border-radius: 8px;
  padding: 20px;
  text-align: center;
  cursor: pointer;
  transition: all 0.2s;
}

.file-item:hover {
  border-color: #667eea;
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.2);
}

.file-item.folder-item {
  background: linear-gradient(135deg, #f5f7fa 0%, #e8ebf0 100%);
}

.file-item .icon {
  font-size: 48px;
  margin-bottom: 10px;
}

.file-item .name {
  font-weight: 500;
  color: #333;
  margin-bottom: 5px;
  word-break: break-word;
}

.file-item .meta {
  font-size: 12px;
  color: #999;
}

.empty-message {
  grid-column: 1 / -1;
  text-align: center;
  padding: 60px 20px;
  color: #999;
  background: #f9f9f9;
  border-radius: 8px;
}

.btn {
  padding: 10px 20px;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-primary {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 5px 15px rgba(102, 126, 234, 0.4);
}

.btn-success {
  background: linear-gradient(135deg, #11998e 0%, #38ef7d 100%);
  color: white;
}

.btn-success:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 5px 15px rgba(17, 153, 142, 0.4);
}

.btn-secondary {
  background: #ccc;
  color: #333;
}

.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: white;
  border-radius: 12px;
  padding: 30px;
  width: 90%;
  max-width: 500px;
  max-height: 90vh;
  overflow-y: auto;
}

.modal-content h2 {
  margin-bottom: 20px;
  color: #333;
}

.form-group {
  margin-bottom: 20px;
}

.form-group label {
  display: block;
  margin-bottom: 8px;
  font-weight: 500;
  color: #555;
}

.file-info {
  margin-top: 5px;
  font-size: 12px;
  color: #666;
  font-style: italic;
}

.form-group input {
  width: 100%;
  padding: 10px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
  box-sizing: border-box;
}

.form-group input:focus {
  outline: none;
  border-color: #667eea;
}

.file-preview {
  background: #f5f5f5;
  padding: 15px;
  border-radius: 6px;
  margin-bottom: 15px;
  word-break: break-word;
}

.modal-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}

.context-menu {
  position: fixed;
  background: white;
  border: 1px solid #ddd;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  z-index: 2000;
  min-width: 150px;
}

.context-menu-item {
  padding: 12px 20px;
  cursor: pointer;
  transition: background 0.2s;
}

.context-menu-item:hover {
  background: #f5f5f5;
}

.image-viewer {
  position: relative;
  max-width: 90vw;
  max-height: 90vh;
  background: white;
  border-radius: 12px;
  padding: 20px;
}

.image-viewer img {
  max-width: 100%;
  max-height: 80vh;
  display: block;
  margin: 0 auto;
  border-radius: 8px;
}

.image-info {
  text-align: center;
  margin-top: 15px;
  color: #333;
  font-weight: 500;
}

.close-btn {
  position: absolute;
  top: 10px;
  right: 10px;
  background: #e74c3c;
  color: white;
  border: none;
  border-radius: 50%;
  width: 32px;
  height: 32px;
  font-size: 20px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.close-btn:hover {
  background: #c0392b;
}

.video-viewer {
  position: relative;
  max-width: 90vw;
  max-height: 90vh;
  background: white;
  border-radius: 12px;
  padding: 20px;
}

.video-viewer video {
  max-width: 100%;
  max-height: 80vh;
  display: block;
  margin: 0 auto;
  border-radius: 8px;
}

.video-info {
  text-align: center;
  margin-top: 15px;
  color: #333;
  font-weight: 500;
}
</style>

