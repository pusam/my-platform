<template>
  <div class="page-container">
    <div class="page-content">
      <header class="common-header">
        <h1>가계부</h1>
        <div class="header-actions">
          <button @click="goBack" class="btn btn-back">돌아가기</button>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

      <div class="finance-content">
        <!-- 월 선택 & 등록 버튼 -->
        <section class="top-bar">
          <div class="month-selector-wrap">
            <select v-model="selectedYear" @change="loadTransactions" class="select-box">
              <option v-for="y in yearOptions" :key="y" :value="y">{{ y }}년</option>
            </select>
            <select v-model="selectedMonth" @change="loadTransactions" class="select-box">
              <option v-for="m in 12" :key="m" :value="m">{{ m }}월</option>
            </select>
          </div>
          <div class="action-buttons">
            <button @click="openAddModal('INCOME')" class="btn btn-income">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="12" y1="5" x2="12" y2="19"/>
                <line x1="5" y1="12" x2="19" y2="12"/>
              </svg>
              수입 등록
            </button>
            <button @click="openAddModal('EXPENSE')" class="btn btn-expense">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="12" y1="5" x2="12" y2="19"/>
                <line x1="5" y1="12" x2="19" y2="12"/>
              </svg>
              지출 등록
            </button>
          </div>
        </section>

        <!-- 컨텐츠 영역 -->
        <div class="content-area">
          <LoadingSpinner v-if="loading" message="가계부를 불러오는 중..." />

          <template v-else>
            <!-- 요약 카드 -->
            <section class="summary-cards">
              <article class="stat-card income">
                <div class="stat-icon">
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="23 6 13.5 15.5 8.5 10.5 1 18"/>
                    <polyline points="17 6 23 6 23 12"/>
                  </svg>
                </div>
                <div class="stat-info">
                  <span class="stat-label">총 수입</span>
                  <span class="stat-value">{{ formatCurrency(summary.totalIncome) }}</span>
                </div>
              </article>

              <article class="stat-card expense">
                <div class="stat-icon">
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="23 18 13.5 8.5 8.5 13.5 1 6"/>
                    <polyline points="17 18 23 18 23 12"/>
                  </svg>
                </div>
                <div class="stat-info">
                  <span class="stat-label">총 지출</span>
                  <span class="stat-value">{{ formatCurrency(summary.totalExpense) }}</span>
                </div>
              </article>

              <article class="stat-card balance" :class="balanceClass">
                <div class="stat-icon">
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="12" y1="1" x2="12" y2="23"/>
                    <path d="M17 5H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6"/>
                  </svg>
                </div>
                <div class="stat-info">
                  <span class="stat-label">잔액</span>
                  <span class="stat-value">{{ formatCurrency(summary.balance) }}</span>
                </div>
              </article>
            </section>

            <!-- 거래 내역 -->
            <section class="transactions-section">
              <div class="section-header">
                <h2>{{ selectedMonth }}월 거래 내역</h2>
                <span class="transaction-count">{{ transactions.length }}건</span>
              </div>

              <div v-if="transactions.length === 0" class="empty-state">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                  <rect x="3" y="4" width="18" height="18" rx="2" ry="2"/>
                  <line x1="16" y1="2" x2="16" y2="6"/>
                  <line x1="8" y1="2" x2="8" y2="6"/>
                  <line x1="3" y1="10" x2="21" y2="10"/>
                </svg>
                <p>이번 달 거래 내역이 없습니다</p>
                <p class="sub-text">수입 또는 지출을 등록해보세요</p>
              </div>

              <div v-else class="transactions-list">
                <div v-for="tx in transactions" :key="tx.id"
                     class="transaction-item" :class="tx.type.toLowerCase()">
                  <div class="tx-left">
                    <div class="tx-icon" :class="tx.type.toLowerCase()">
                      <svg v-if="tx.type === 'INCOME'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polyline points="23 6 13.5 15.5 8.5 10.5 1 18"/>
                      </svg>
                      <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polyline points="23 18 13.5 8.5 8.5 13.5 1 6"/>
                      </svg>
                    </div>
                    <div class="tx-info">
                      <span class="tx-category">{{ tx.category }}</span>
                      <span class="tx-date">{{ formatDate(tx.transactionDate) }}</span>
                      <span v-if="tx.memo" class="tx-memo">{{ tx.memo }}</span>
                    </div>
                  </div>
                  <div class="tx-right">
                    <span class="tx-amount" :class="tx.type.toLowerCase()">
                      {{ tx.type === 'INCOME' ? '+' : '-' }}{{ formatCurrency(tx.amount) }}
                    </span>
                    <button @click="deleteTransaction(tx.id)" class="btn-delete" title="삭제">
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polyline points="3 6 5 6 21 6"/>
                        <path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/>
                      </svg>
                    </button>
                  </div>
                </div>
              </div>
            </section>
          </template>
        </div>
      </div>

      <!-- 수입/지출 등록 모달 -->
      <div v-if="showAddModal" class="modal-overlay" @click="closeModal">
        <div class="modal-content" @click.stop>
          <div class="modal-header" :class="modalType.toLowerCase()">
            <h2>{{ modalType === 'INCOME' ? '수입 등록' : '지출 등록' }}</h2>
            <button @click="closeModal" class="btn-close">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
          <div class="modal-body">
            <div class="form-group">
              <label>카테고리</label>
              <select v-model="form.category" class="form-input">
                <option value="">카테고리 선택</option>
                <template v-if="modalType === 'INCOME'">
                  <option value="급여">급여</option>
                  <option value="보너스">보너스</option>
                  <option value="부수입">부수입</option>
                  <option value="이자">이자</option>
                  <option value="배당금">배당금</option>
                  <option value="용돈">용돈</option>
                  <option value="환급금">환급금</option>
                  <option value="기타수입">기타수입</option>
                </template>
                <template v-else>
                  <option value="월세">월세/주거비</option>
                  <option value="공과금">공과금</option>
                  <option value="통신비">통신비</option>
                  <option value="보험">보험</option>
                  <option value="대출상환">대출상환</option>
                  <option value="교통비">교통비</option>
                  <option value="식비">식비</option>
                  <option value="생활용품">생활용품</option>
                  <option value="의류/미용">의류/미용</option>
                  <option value="의료비">의료비</option>
                  <option value="교육비">교육비</option>
                  <option value="경조사">경조사</option>
                  <option value="여가/문화">여가/문화</option>
                  <option value="구독료">구독료</option>
                  <option value="기타지출">기타지출</option>
                </template>
              </select>
            </div>

            <div class="form-group">
              <label>금액</label>
              <div class="input-wrap">
                <input type="number" v-model.number="form.amount" placeholder="0" min="0" class="form-input" />
                <span class="unit">원</span>
              </div>
            </div>

            <div class="form-group">
              <label>날짜</label>
              <input type="date" v-model="form.transactionDate" class="form-input" />
            </div>

            <div class="form-group">
              <label>메모 (선택)</label>
              <input type="text" v-model="form.memo" placeholder="메모를 입력하세요" class="form-input" />
            </div>
          </div>
          <div class="modal-footer">
            <button @click="closeModal" class="btn btn-cancel">취소</button>
            <button @click="submitTransaction" class="btn btn-submit" :class="modalType.toLowerCase()" :disabled="saving">
              {{ saving ? '저장 중...' : '등록하기' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { financeAPI } from '../utils/api';
import { UserManager } from '../utils/auth';
import LoadingSpinner from '../components/LoadingSpinner.vue';

const router = useRouter();
const loading = ref(false);
const saving = ref(false);
const showAddModal = ref(false);
const modalType = ref('INCOME');

const currentDate = new Date();
const selectedYear = ref(currentDate.getFullYear());
const selectedMonth = ref(currentDate.getMonth() + 1);

const summary = ref({
  totalIncome: 0,
  totalExpense: 0,
  balance: 0
});
const transactions = ref([]);

const form = ref({
  category: '',
  amount: null,
  transactionDate: '',
  memo: ''
});

const yearOptions = computed(() => {
  const years = [];
  const currentYear = new Date().getFullYear();
  for (let i = currentYear - 5; i <= currentYear + 1; i++) {
    years.push(i);
  }
  return years;
});

const balanceClass = computed(() => {
  if (summary.value.balance > 0) return 'positive';
  if (summary.value.balance < 0) return 'negative';
  return 'neutral';
});

const loadTransactions = async () => {
  try {
    loading.value = true;
    const response = await financeAPI.getMonthlyTransactions(selectedYear.value, selectedMonth.value);
    if (response.data.success) {
      const data = response.data.data;
      summary.value = {
        totalIncome: data.totalIncome || 0,
        totalExpense: data.totalExpense || 0,
        balance: data.balance || 0
      };
      transactions.value = data.transactions || [];
    }
  } catch (error) {
    console.error('Failed to load transactions:', error);
  } finally {
    loading.value = false;
  }
};

const openAddModal = (type) => {
  modalType.value = type;
  form.value = {
    category: '',
    amount: null,
    transactionDate: new Date().toISOString().split('T')[0],
    memo: ''
  };
  showAddModal.value = true;
};

const closeModal = () => {
  showAddModal.value = false;
};

const submitTransaction = async () => {
  if (!form.value.category || !form.value.amount || !form.value.transactionDate) {
    alert('카테고리, 금액, 날짜는 필수 입력입니다.');
    return;
  }

  try {
    saving.value = true;
    const data = {
      type: modalType.value,
      category: form.value.category,
      amount: form.value.amount,
      transactionDate: form.value.transactionDate,
      memo: form.value.memo || null
    };

    const response = await financeAPI.addTransaction(data);
    if (response.data.success) {
      closeModal();
      await loadTransactions();
    }
  } catch (error) {
    console.error('Failed to add transaction:', error);
    alert('등록에 실패했습니다.');
  } finally {
    saving.value = false;
  }
};

const deleteTransaction = async (id) => {
  if (!confirm('이 거래를 삭제하시겠습니까?')) return;

  try {
    await financeAPI.deleteTransaction(id);
    await loadTransactions();
  } catch (error) {
    console.error('Failed to delete transaction:', error);
    alert('삭제에 실패했습니다.');
  }
};

const formatCurrency = (value) => {
  if (!value) return '0원';
  return new Intl.NumberFormat('ko-KR').format(value) + '원';
};

const formatDate = (dateStr) => {
  if (!dateStr) return '';
  const date = new Date(dateStr);
  return `${date.getMonth() + 1}/${date.getDate()}`;
};

const goBack = () => {
  router.back();
};

const logout = () => {
  UserManager.logout();
  router.push('/login');
};

onMounted(() => {
  loadTransactions();
});
</script>

<style scoped>
@import '../assets/css/common.css';

.finance-content {
  max-width: 900px;
  margin: 0 auto;
}

/* 상단 바 */
.top-bar {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: 20px 24px;
  margin-bottom: 24px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
}

.month-selector-wrap {
  display: flex;
  gap: 10px;
}

.select-box {
  padding: 10px 20px;
  border: 2px solid var(--border-color);
  border-radius: 10px;
  font-size: 14px;
  font-weight: 500;
  background: white;
  cursor: pointer;
  transition: all 0.2s;
  min-width: 100px;
  appearance: none;
  -webkit-appearance: none;
  -moz-appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%23666' stroke-width='2'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 12px center;
  padding-right: 36px;
}

.select-box:focus {
  outline: none;
  border-color: var(--primary-start);
}

.action-buttons {
  display: flex;
  gap: 10px;
}

.btn-income {
  background: linear-gradient(135deg, #4caf50 0%, #45a049 100%);
  color: white;
  border: none;
  padding: 10px 20px;
  border-radius: 10px;
  font-weight: 600;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: all 0.2s;
}

.btn-income:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 15px rgba(76, 175, 80, 0.3);
}

.btn-expense {
  background: linear-gradient(135deg, #f44336 0%, #d32f2f 100%);
  color: white;
  border: none;
  padding: 10px 20px;
  border-radius: 10px;
  font-weight: 600;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: all 0.2s;
}

.btn-expense:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 15px rgba(244, 67, 54, 0.3);
}

/* 컨텐츠 영역 */
.content-area {
  position: relative;
  min-height: 400px;
}

/* 요약 카드 */
.summary-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  border: 2px solid transparent;
}

.stat-card.income {
  border-color: rgba(76, 175, 80, 0.2);
}

.stat-card.income .stat-icon {
  background: linear-gradient(135deg, rgba(76, 175, 80, 0.15) 0%, rgba(129, 199, 132, 0.15) 100%);
  color: #4caf50;
}

.stat-card.expense {
  border-color: rgba(244, 67, 54, 0.2);
}

.stat-card.expense .stat-icon {
  background: linear-gradient(135deg, rgba(244, 67, 54, 0.15) 0%, rgba(239, 83, 80, 0.15) 100%);
  color: #f44336;
}

.stat-card.balance {
  border-color: rgba(102, 126, 234, 0.2);
}

.stat-card.balance .stat-icon {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.15) 0%, rgba(118, 75, 162, 0.15) 100%);
  color: var(--primary-start);
}

.stat-card.balance.positive {
  border-color: rgba(76, 175, 80, 0.3);
}

.stat-card.balance.positive .stat-icon {
  background: linear-gradient(135deg, rgba(76, 175, 80, 0.15) 0%, rgba(129, 199, 132, 0.15) 100%);
  color: #4caf50;
}

.stat-card.balance.negative {
  border-color: rgba(244, 67, 54, 0.3);
}

.stat-card.balance.negative .stat-icon {
  background: linear-gradient(135deg, rgba(244, 67, 54, 0.15) 0%, rgba(239, 83, 80, 0.15) 100%);
  color: #f44336;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.stat-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stat-label {
  font-size: 13px;
  color: var(--text-muted);
  font-weight: 500;
}

.stat-value {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}

/* 거래 내역 섹션 */
.transactions-section {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 24px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 2px solid var(--border-light);
}

.section-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}

.transaction-count {
  font-size: 13px;
  color: var(--text-muted);
  background: var(--border-light);
  padding: 4px 12px;
  border-radius: 20px;
}

.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: var(--text-muted);
}

.empty-state svg {
  margin-bottom: 16px;
  opacity: 0.5;
}

.empty-state p {
  margin: 0 0 8px 0;
  font-size: 15px;
}

.empty-state .sub-text {
  font-size: 13px;
  color: var(--text-light);
}

/* 거래 내역 리스트 */
.transactions-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.transaction-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: #f8f9fa;
  border-radius: 12px;
  border-left: 4px solid transparent;
  transition: all 0.2s;
}

.transaction-item.income {
  border-left-color: #4caf50;
}

.transaction-item.expense {
  border-left-color: #f44336;
}

.transaction-item:hover {
  background: #f1f3f5;
}

.tx-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.tx-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.tx-icon.income {
  background: rgba(76, 175, 80, 0.15);
  color: #4caf50;
}

.tx-icon.expense {
  background: rgba(244, 67, 54, 0.15);
  color: #f44336;
}

.tx-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.tx-category {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.tx-date {
  font-size: 12px;
  color: var(--text-muted);
}

.tx-memo {
  font-size: 12px;
  color: var(--text-light);
  margin-top: 2px;
}

.tx-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.tx-amount {
  font-size: 16px;
  font-weight: 700;
}

.tx-amount.income {
  color: #4caf50;
}

.tx-amount.expense {
  color: #f44336;
}

.btn-delete {
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: all 0.2s;
}

.transaction-item:hover .btn-delete {
  opacity: 1;
}

.btn-delete:hover {
  background: rgba(244, 67, 54, 0.1);
  color: #f44336;
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
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}

.modal-content {
  background: white;
  border-radius: 20px;
  width: 100%;
  max-width: 440px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.modal-header {
  padding: 20px 24px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: white;
}

.modal-header.income {
  background: linear-gradient(135deg, #4caf50 0%, #45a049 100%);
}

.modal-header.expense {
  background: linear-gradient(135deg, #f44336 0%, #d32f2f 100%);
}

.modal-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.btn-close {
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: white;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}

.btn-close:hover {
  background: rgba(255, 255, 255, 0.3);
}

.modal-body {
  padding: 24px;
}

.form-group {
  margin-bottom: 20px;
}

.form-group:last-child {
  margin-bottom: 0;
}

.form-group label {
  display: block;
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.form-input {
  width: 100%;
  padding: 12px 14px;
  border: 2px solid var(--border-color);
  border-radius: 10px;
  font-size: 15px;
  transition: all 0.2s;
}

.form-input:focus {
  outline: none;
  border-color: var(--primary-start);
}

.input-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.input-wrap input {
  padding-right: 40px;
}

.input-wrap .unit {
  position: absolute;
  right: 14px;
  font-size: 14px;
  color: var(--text-muted);
}

.modal-footer {
  padding: 16px 24px 24px;
  display: flex;
  gap: 12px;
}

.btn-cancel {
  flex: 1;
  padding: 14px;
  background: #f1f3f5;
  border: none;
  border-radius: 10px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s;
}

.btn-cancel:hover {
  background: #e9ecef;
}

.btn-submit {
  flex: 2;
  padding: 14px;
  border: none;
  border-radius: 10px;
  font-size: 15px;
  font-weight: 600;
  color: white;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-submit.income {
  background: linear-gradient(135deg, #4caf50 0%, #45a049 100%);
}

.btn-submit.income:hover {
  box-shadow: 0 4px 15px rgba(76, 175, 80, 0.3);
}

.btn-submit.expense {
  background: linear-gradient(135deg, #f44336 0%, #d32f2f 100%);
}

.btn-submit.expense:hover {
  box-shadow: 0 4px 15px rgba(244, 67, 54, 0.3);
}

.btn-submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 반응형 */
@media (max-width: 768px) {
  .top-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .action-buttons {
    justify-content: stretch;
  }

  .action-buttons .btn-income,
  .action-buttons .btn-expense {
    flex: 1;
    justify-content: center;
  }

  .summary-cards {
    grid-template-columns: 1fr;
  }

  .stat-value {
    font-size: 16px;
  }
}
</style>
