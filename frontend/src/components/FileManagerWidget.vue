<template>
  <div class="file-manager-widget" @click="goToFileManager">
    <div class="widget-header">
      <h3>내 파일</h3>
    </div>

    <div class="widget-body">
      <div class="icon-container">
        <span class="folder-icon">📁</span>
      </div>
      <div class="widget-info">
        <p class="description">파일 및 폴더 관리</p>
        <p class="sub-text">클릭하여 파일 관리 페이지로 이동</p>
      </div>
    </div>

    <div class="widget-footer">
      <span class="action-text">파일 관리 바로가기 →</span>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

const goToFileManager = () => {
  router.push('/files')
}
</script>

<style scoped>
.file-manager-widget {
  background: linear-gradient(135deg, #e8f4f8 0%, #ffffff 100%);
  border: 1px solid #5dade2;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
  cursor: pointer;
  transition: all 0.3s ease;
}

.file-manager-widget:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 16px rgba(93, 173, 226, 0.3);
  border-color: #3498db;
}

.widget-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #aed6f1;
}

.widget-header h3 {
  margin: 0;
  color: #2980b9;
  font-size: 18px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.widget-header h3::before {
  content: '';
  display: inline-block;
  width: 12px;
  height: 12px;
  background: linear-gradient(135deg, #5dade2, #3498db);
  border-radius: 50%;
}

.widget-body {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 20px 0;
}

.icon-container {
  display: flex;
  align-items: center;
  justify-content: center;
}

.folder-icon {
  font-size: 64px;
  filter: drop-shadow(2px 2px 4px rgba(0, 0, 0, 0.1));
}

.widget-info {
  flex: 1;
}

.description {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #2c3e50;
}

.sub-text {
  margin: 8px 0 0;
  font-size: 13px;
  color: #7f8c8d;
}

.widget-footer {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
  border-top: 1px solid #aed6f1;
}

.action-text {
  font-size: 14px;
  color: #3498db;
  font-weight: 500;
}
</style>
