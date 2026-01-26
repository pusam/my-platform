<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>자동차 관리</h1>
        <div class="header-actions">
          <button @click="goBack" class="btn btn-back">돌아가기</button>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

      <!-- 컨텐츠 영역 -->
      <div class="car-content">
        <!-- 정비 등록 버튼 -->
        <div class="action-bar">
          <button @click="showAddModal = true" class="btn btn-primary">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19"/>
              <line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            정비 기록 등록
          </button>
        </div>

        <!-- 로딩 상태 -->
        <LoadingSpinner v-if="loading" message="정비 기록을 불러오는 중..." />

        <!-- 요약 카드 -->
        <section v-if="!loading && summary" class="summary-section">
          <div class="summary-grid">
            <div class="summary-card mileage">
              <div class="card-icon">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                  <circle cx="12" cy="12" r="10"/>
                  <polyline points="12,6 12,12 16,14"/>
                </svg>
              </div>
              <div class="card-info">
                <span class="label">현재 주행거리</span>
                <span class="value">{{ formatNumber(summary.currentMileage) }} km</span>
              </div>
            </div>

            <div class="summary-card records">
              <div class="card-icon">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                  <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
                  <polyline points="14,2 14,8 20,8"/>
                  <line x1="16" y1="13" x2="8" y2="13"/>
                  <line x1="16" y1="17" x2="8" y2="17"/>
                </svg>
              </div>
              <div class="card-info">
                <span class="label">총 정비 기록</span>
                <span class="value">{{ summary.totalRecords }}건</span>
              </div>
            </div>

            <div class="summary-card cost">
              <div class="card-icon">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                  <line x1="12" y1="1" x2="12" y2="23"/>
                  <path d="M17 5H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6"/>
                </svg>
              </div>
              <div class="card-info">
                <span class="label">총 정비 비용</span>
                <span class="value">{{ formatCurrency(summary.totalCost) }}</span>
              </div>
            </div>
          </div>

          <!-- 정비 필요 알림 -->
          <div v-if="summary.dueMaintenances && summary.dueMaintenances.length > 0" class="due-alert">
            <div class="alert-icon">⚠️</div>
            <div class="alert-content">
              <h4>정비가 필요합니다!</h4>
              <ul>
                <li v-for="due in summary.dueMaintenances" :key="due.id">
                  <strong>{{ due.recordTypeName }}</strong> -
                  예정 {{ formatNumber(due.nextMileage) }}km (현재 {{ formatNumber(summary.currentMileage) }}km)
                </li>
              </ul>
            </div>
          </div>
        </section>

        <!-- 필터 -->
        <div class="filter-bar">
          <label>정비 유형:</label>
          <select v-model="filterType" @change="loadRecords">
            <option value="">전체</option>
            <option value="ENGINE_OIL">엔진오일</option>
            <option value="TIRE">타이어</option>
            <option value="BRAKE">브레이크</option>
            <option value="FILTER">필터</option>
            <option value="BATTERY">배터리</option>
            <option value="INSPECTION">정기점검</option>
            <option value="WIPER">와이퍼</option>
            <option value="COOLANT">냉각수</option>
            <option value="TRANSMISSION">미션오일</option>
            <option value="OTHER">기타</option>
          </select>
        </div>

        <!-- 정비 기록 목록 -->
        <section class="records-section">
          <div class="section-header">
            <h2>정비 기록</h2>
          </div>

          <div v-if="records.length === 0" class="empty-state">
            <div class="empty-icon">🚗</div>
            <h3>정비 기록이 없습니다</h3>
            <p>첫 번째 정비 기록을 등록해보세요!</p>
            <button @click="showAddModal = true" class="btn btn-primary">정비 기록 등록</button>
          </div>

          <div v-else class="records-list">
            <div v-for="record in records" :key="record.id" class="record-card">
              <div class="record-type" :class="record.recordType.toLowerCase()">
                {{ getTypeIcon(record.recordType) }}
              </div>
              <div class="record-info">
                <div class="record-header">
                  <span class="type-badge" :class="record.recordType.toLowerCase()">{{ record.recordTypeName }}</span>
                  <span class="record-date">{{ formatDate(record.recordDate) }}</span>
                </div>
                <div class="record-details">
                  <div class="detail-item">
                    <span class="detail-label">주행거리</span>
                    <span class="detail-value">{{ formatNumber(record.mileage) }} km</span>
                  </div>
                  <div v-if="record.nextMileage" class="detail-item">
                    <span class="detail-label">다음 정비</span>
                    <span class="detail-value next">{{ formatNumber(record.nextMileage) }} km</span>
                  </div>
                  <div v-if="record.cost" class="detail-item">
                    <span class="detail-label">비용</span>
                    <span class="detail-value cost">{{ formatCurrency(record.cost) }}</span>
                  </div>
                  <div v-if="record.shop" class="detail-item">
                    <span class="detail-label">정비소</span>
                    <span class="detail-value">{{ record.shop }}</span>
                  </div>
                </div>
                <div v-if="record.memo" class="record-memo">
                  {{ record.memo }}
                </div>
              </div>
              <button @click="deleteRecord(record.id)" class="btn-delete">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <polyline points="3,6 5,6 21,6"/>
                  <path d="M19,6v14a2,2 0 01-2,2H7a2,2 0 01-2-2V6m3,0V4a2,2 0 012-2h4a2,2 0 012,2v2"/>
                </svg>
              </button>
            </div>
          </div>
        </section>
      </div>

      <!-- 정비 기록 등록 모달 -->
      <div v-if="showAddModal" class="modal-overlay" @click="closeModal">
        <div class="modal-content" @click.stop>
          <div class="modal-header">
            <h2>정비 기록 등록</h2>
            <button @click="closeModal" class="modal-close">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>

          <form @submit.prevent="addRecord" class="modal-form">
            <div class="form-row">
              <div class="form-group">
                <label>차량명 (선택)</label>
                <input type="text" v-model="newRecord.carName" placeholder="예: 아반떼"/>
              </div>
              <div class="form-group">
                <label>차량번호 (선택)</label>
                <input type="text" v-model="newRecord.plateNumber" placeholder="예: 12가 3456"/>
              </div>
            </div>

            <div class="form-group">
              <label>정비 유형 *</label>
              <div class="type-grid">
                <button
                  type="button"
                  v-for="type in recordTypes"
                  :key="type.value"
                  class="type-btn"
                  :class="{ selected: newRecord.recordType === type.value }"
                  @click="newRecord.recordType = type.value"
                >
                  <span class="type-icon">{{ type.icon }}</span>
                  <span class="type-name">{{ type.label }}</span>
                </button>
              </div>
            </div>

            <div class="form-row">
              <div class="form-group">
                <label>정비일 *</label>
                <input type="date" v-model="newRecord.recordDate" required/>
              </div>
              <div class="form-group">
                <label>주행거리 (km) *</label>
                <input type="number" v-model="newRecord.mileage" placeholder="예: 50000" required/>
              </div>
            </div>

            <div class="form-row">
              <div class="form-group">
                <label>다음 정비 주행거리 (km)</label>
                <input type="number" v-model="newRecord.nextMileage" placeholder="예: 55000"/>
                <small class="hint">엔진오일: +5,000km / 타이어: +40,000km</small>
              </div>
              <div class="form-group">
                <label>비용 (원)</label>
                <input type="number" v-model="newRecord.cost" placeholder="예: 80000"/>
              </div>
            </div>

            <div class="form-group">
              <label>정비소</label>
              <input type="text" v-model="newRecord.shop" placeholder="예: OO카센터"/>
            </div>

            <div class="form-group">
              <label>메모</label>
              <textarea v-model="newRecord.memo" placeholder="추가 메모 입력" rows="2"></textarea>
            </div>

            <div v-if="errorMessage" class="alert alert-error">{{ errorMessage }}</div>

            <div class="modal-actions">
              <button type="button" @click="closeModal" class="btn btn-secondary">취소</button>
              <button type="submit" class="btn btn-primary">등록</button>
            </div>
          </form>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { carAPI } from '../utils/api';
import { UserManager } from '../utils/auth';
import LoadingSpinner from '../components/LoadingSpinner.vue';

const router = useRouter();

const loading = ref(false);
const records = ref([]);
const summary = ref(null);
const showAddModal = ref(false);
const errorMessage = ref('');
const filterType = ref('');

const newRecord = ref({
  carName: '',
  plateNumber: '',
  recordType: '',
  recordDate: new Date().toISOString().split('T')[0],
  mileage: '',
  nextMileage: '',
  cost: '',
  shop: '',
  memo: ''
});

// 정비 유형 목록
const recordTypes = [
  { value: 'ENGINE_OIL', label: '엔진오일', icon: '🛢️' },
  { value: 'TIRE', label: '타이어', icon: '🛞' },
  { value: 'BRAKE', label: '브레이크', icon: '🛑' },
  { value: 'FILTER', label: '필터', icon: '🌬️' },
  { value: 'BATTERY', label: '배터리', icon: '🔋' },
  { value: 'INSPECTION', label: '정기점검', icon: '🔧' },
  { value: 'WIPER', label: '와이퍼', icon: '💧' },
  { value: 'COOLANT', label: '냉각수', icon: '❄️' },
  { value: 'TRANSMISSION', label: '미션오일', icon: '⚙️' },
  { value: 'OTHER', label: '기타', icon: '📝' }
];

const loadData = async () => {
  try {
    loading.value = true;
    const [recordsRes, summaryRes] = await Promise.all([
      carAPI.getRecords(filterType.value || null),
      carAPI.getSummary()
    ]);
    records.value = recordsRes.data.data || [];
    summary.value = summaryRes.data.data || {};
  } catch (error) {
    console.error('Failed to load data:', error);
  } finally {
    loading.value = false;
  }
};

const loadRecords = async () => {
  try {
    const response = await carAPI.getRecords(filterType.value || null);
    records.value = response.data.data || [];
  } catch (error) {
    console.error('Failed to load records:', error);
  }
};

const addRecord = async () => {
  try {
    errorMessage.value = '';

    if (!newRecord.value.recordType || !newRecord.value.mileage) {
      errorMessage.value = '정비 유형과 주행거리는 필수입니다.';
      return;
    }

    await carAPI.addRecord(newRecord.value);
    closeModal();
    await loadData();
  } catch (error) {
    console.error('Failed to add record:', error);
    errorMessage.value = error.response?.data?.message || '정비 기록 등록에 실패했습니다.';
  }
};

const deleteRecord = async (id) => {
  if (!confirm('이 정비 기록을 삭제하시겠습니까?')) {
    return;
  }

  try {
    await carAPI.deleteRecord(id);
    await loadData();
  } catch (error) {
    console.error('Failed to delete record:', error);
    alert('삭제에 실패했습니다.');
  }
};

const closeModal = () => {
  showAddModal.value = false;
  errorMessage.value = '';
  newRecord.value = {
    carName: '',
    plateNumber: '',
    recordType: '',
    recordDate: new Date().toISOString().split('T')[0],
    mileage: '',
    nextMileage: '',
    cost: '',
    shop: '',
    memo: ''
  };
};

const getTypeIcon = (type) => {
  const icons = {
    'ENGINE_OIL': '🛢️',
    'TIRE': '🛞',
    'BRAKE': '🛑',
    'FILTER': '🌬️',
    'BATTERY': '🔋',
    'INSPECTION': '🔧',
    'WIPER': '💧',
    'COOLANT': '❄️',
    'TRANSMISSION': '⚙️',
    'OTHER': '📝'
  };
  return icons[type] || '🚗';
};

const formatNumber = (value) => {
  if (!value) return '0';
  return new Intl.NumberFormat('ko-KR').format(value);
};

const formatCurrency = (value) => {
  if (!value) return '0원';
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    minimumFractionDigits: 0
  }).format(value);
};

const formatDate = (date) => {
  if (!date) return '-';
  return new Date(date).toLocaleDateString('ko-KR');
};

const goBack = () => {
  router.back();
};

const logout = () => {
  UserManager.logout();
  router.push('/login');
};

onMounted(() => {
  loadData();
});
</script>

<style scoped>
.car-content {
  position: relative;
  min-height: 300px;
}

/* 요약 섹션 */
.summary-section {
  margin-bottom: 30px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 20px;
  margin-bottom: var(--section-gap);
}

.summary-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: 24px;
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.summary-card .card-icon {
  width: 56px;
  height: 56px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.summary-card.mileage .card-icon {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.15) 0%, rgba(118, 75, 162, 0.15) 100%);
  color: var(--primary-start);
}

.summary-card.records .card-icon {
  background: linear-gradient(135deg, rgba(46, 204, 113, 0.15) 0%, rgba(26, 188, 156, 0.15) 100%);
  color: #2ecc71;
}

.summary-card.cost .card-icon {
  background: linear-gradient(135deg, rgba(241, 196, 15, 0.15) 0%, rgba(243, 156, 18, 0.15) 100%);
  color: #f39c12;
}

.summary-card .card-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.summary-card .label {
  font-size: 13px;
  color: var(--text-muted);
}

.summary-card .value {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
}

/* 정비 필요 알림 */
.due-alert {
  background: linear-gradient(135deg, #fff5f5 0%, #fee 100%);
  border: 2px solid #fcc;
  border-radius: 16px;
  padding: 20px 24px;
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.due-alert .alert-icon {
  font-size: 28px;
}

.due-alert h4 {
  margin: 0 0 8px 0;
  color: #e74c3c;
  font-size: 16px;
}

.due-alert ul {
  margin: 0;
  padding-left: 20px;
  color: #c0392b;
  font-size: 14px;
}

.due-alert li {
  margin-bottom: 4px;
}

/* 필터 바 */
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: var(--section-gap);
  padding: 16px 20px;
  background: rgba(255, 255, 255, 0.95);
  border-radius: 12px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.05);
}

.filter-bar label {
  font-weight: 500;
  color: var(--text-muted);
}

.filter-bar select {
  padding: 10px 16px;
  border: 2px solid var(--border-color);
  border-radius: 8px;
  font-size: 14px;
  min-width: 150px;
  cursor: pointer;
}

.filter-bar select:focus {
  outline: none;
  border-color: var(--primary-start);
}

/* 기록 섹션 */
.records-section {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: var(--card-padding);
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
}

.section-header {
  margin-bottom: var(--section-gap);
}

.section-header h2 {
  margin: 0;
  font-size: 20px;
  color: var(--text-primary);
  font-weight: 600;
}

/* 빈 상태 */
.empty-state {
  text-align: center;
  padding: 60px 20px;
}

.empty-icon {
  font-size: 64px;
  margin-bottom: 16px;
}

.empty-state h3 {
  color: var(--text-primary);
  margin: 0 0 8px 0;
}

.empty-state p {
  color: var(--text-muted);
  margin: 0 0 20px 0;
}

/* 기록 리스트 */
.records-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.record-card {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: var(--card-padding);
  background: linear-gradient(135deg, #f8f9fa 0%, #fff 100%);
  border-radius: 16px;
  border: 1px solid var(--border-light);
  transition: all 0.2s;
}

.record-card:hover {
  border-color: var(--primary-start);
  box-shadow: 0 4px 15px rgba(102, 126, 234, 0.1);
}

.record-type {
  width: 50px;
  height: 50px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  flex-shrink: 0;
  background: rgba(102, 126, 234, 0.1);
}

.record-type.engine_oil { background: rgba(139, 69, 19, 0.15); }
.record-type.tire { background: rgba(0, 0, 0, 0.1); }
.record-type.brake { background: rgba(231, 76, 60, 0.15); }
.record-type.battery { background: rgba(46, 204, 113, 0.15); }
.record-type.filter { background: rgba(52, 152, 219, 0.15); }
.record-type.inspection { background: rgba(155, 89, 182, 0.15); }
.record-type.wiper { background: rgba(52, 152, 219, 0.15); }
.record-type.coolant { background: rgba(41, 128, 185, 0.15); }
.record-type.transmission { background: rgba(127, 140, 141, 0.15); }

.record-info {
  flex: 1;
  min-width: 0;
}

.record-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.type-badge {
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 600;
  background: var(--primary-gradient);
  color: white;
}

.record-date {
  font-size: 13px;
  color: var(--text-muted);
}

.record-details {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 8px;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.detail-label {
  font-size: 11px;
  color: var(--text-muted);
  text-transform: uppercase;
}

.detail-value {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.detail-value.next {
  color: var(--primary-start);
}

.detail-value.cost {
  color: #e67e22;
}

.record-memo {
  font-size: 13px;
  color: var(--text-muted);
  padding: 8px 12px;
  background: rgba(0, 0, 0, 0.03);
  border-radius: 8px;
  margin-top: 8px;
}

.btn-delete {
  padding: 8px;
  background: transparent;
  border: none;
  color: #ccc;
  cursor: pointer;
  border-radius: 8px;
  transition: all 0.2s;
}

.btn-delete:hover {
  background: #fee;
  color: var(--danger);
}

/* 액션 바 */
.action-bar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: var(--section-gap);
}

/* 모달 폼 */
.modal-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.form-group textarea {
  width: 100%;
  padding: 12px 16px;
  border: 2px solid var(--border-color);
  border-radius: 12px;
  font-size: 14px;
  font-family: inherit;
  resize: vertical;
  transition: border-color 0.2s;
}

.form-group textarea:focus {
  outline: none;
  border-color: var(--primary-start);
}

.form-group .hint {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-muted);
}

/* 정비 유형 그리드 */
.type-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 10px;
}

.type-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 14px 8px;
  background: #f8f9fa;
  border: 2px solid #e9ecef;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.type-btn:hover {
  background: #e9ecef;
  border-color: #ced4da;
}

.type-btn.selected {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.1) 0%, rgba(118, 75, 162, 0.1) 100%);
  border-color: var(--primary-start);
  box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.2);
}

.type-btn .type-icon {
  font-size: 24px;
}

.type-btn .type-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-primary);
}

.type-btn.selected .type-name {
  color: var(--primary-start);
  font-weight: 600;
}

/* 반응형 */
@media (max-width: 768px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }

  .form-row {
    grid-template-columns: 1fr;
  }

  .type-grid {
    grid-template-columns: repeat(3, 1fr);
    gap: 8px;
  }

  .type-btn {
    padding: 10px 6px;
  }

  .type-btn .type-icon {
    font-size: 20px;
  }

  .type-btn .type-name {
    font-size: 11px;
  }

  .record-card {
    flex-direction: column;
  }

  .record-type {
    width: 100%;
    height: 40px;
    border-radius: 8px;
  }

  .record-details {
    flex-direction: column;
    gap: 8px;
  }

  .detail-item {
    flex-direction: row;
    justify-content: space-between;
  }

  .filter-bar {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }

  .filter-bar select {
    width: 100%;
  }
}
</style>
