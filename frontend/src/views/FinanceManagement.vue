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
        <!-- 탭 네비게이션 -->
        <section class="tab-navigation">
          <button class="tab-btn" :class="{ active: activeTab === 'transactions' }" @click="activeTab = 'transactions'">
            거래 내역
          </button>
          <button class="tab-btn" :class="{ active: activeTab === 'recurring' }" @click="activeTab = 'recurring'; loadRecurring()">
            고정 수입/지출
          </button>
        </section>

        <!-- 거래 내역 탭 -->
        <template v-if="activeTab === 'transactions'">
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
              <div class="export-dropdown" v-if="transactions.length > 0">
                <button @click="showExportMenu = !showExportMenu" class="btn btn-export">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                    <polyline points="7 10 12 15 17 10"/>
                    <line x1="12" y1="15" x2="12" y2="3"/>
                  </svg>
                  내보내기
                </button>
                <div v-if="showExportMenu" class="export-menu">
                  <button @click="exportData('xlsx')">Excel (.xlsx)</button>
                  <button @click="exportData('csv')">CSV (.csv)</button>
                </div>
              </div>
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
                  <span class="stat-detail" v-if="summary.recurringIncome > 0">
                    고정 {{ formatCurrency(summary.recurringIncome) }} + 변동 {{ formatCurrency(summary.variableIncome) }}
                  </span>
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
                  <span class="stat-detail" v-if="summary.recurringExpense > 0">
                    고정 {{ formatCurrency(summary.recurringExpense) }} + 변동 {{ formatCurrency(summary.variableExpense) }}
                  </span>
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

            <!-- 수입/지출 원형 그래프 -->
            <section class="charts-section">
              <div class="chart-card">
                <h3 class="chart-title">
                  <span class="chart-icon income">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <polyline points="23 6 13.5 15.5 8.5 10.5 1 18"/>
                    </svg>
                  </span>
                  수입 분포
                </h3>
                <div class="chart-container">
                  <canvas ref="incomeChartRef"></canvas>
                </div>
                <div v-if="!hasIncomeData" class="no-chart-data">
                  이번 달 수입 데이터가 없습니다
                </div>
              </div>
              <div class="chart-card">
                <h3 class="chart-title">
                  <span class="chart-icon expense">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <polyline points="23 18 13.5 8.5 8.5 13.5 1 6"/>
                    </svg>
                  </span>
                  지출 분포
                </h3>
                <div class="chart-container">
                  <canvas ref="expenseChartRef"></canvas>
                </div>
                <div v-if="!hasExpenseData" class="no-chart-data">
                  이번 달 지출 데이터가 없습니다
                </div>
              </div>
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
        </template>

        <!-- 고정 수입/지출 탭 -->
        <template v-if="activeTab === 'recurring'">
          <section class="recurring-header">
            <h2>고정 수입/지출 관리</h2>
            <p>매월 반복되는 수입과 지출을 등록하면 자동으로 월별 계산에 포함됩니다.</p>
            <div class="recurring-actions">
              <button @click="openRecurringModal('INCOME')" class="btn btn-income">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="12" y1="5" x2="12" y2="19"/>
                  <line x1="5" y1="12" x2="19" y2="12"/>
                </svg>
                고정 수입 등록
              </button>
              <button @click="openRecurringModal('EXPENSE')" class="btn btn-expense">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="12" y1="5" x2="12" y2="19"/>
                  <line x1="5" y1="12" x2="19" y2="12"/>
                </svg>
                고정 지출 등록
              </button>
            </div>
          </section>

          <LoadingSpinner v-if="loadingRecurring" message="고정 항목을 불러오는 중..." />

          <template v-else>
            <!-- 고정 수입 목록 -->
            <section class="recurring-section">
              <h3 class="section-title income">고정 수입</h3>
              <div v-if="recurringIncomes.length === 0" class="empty-recurring">
                등록된 고정 수입이 없습니다.
              </div>
              <div v-else class="recurring-list">
                <div v-for="item in recurringIncomes" :key="item.id" class="recurring-item income">
                  <div class="recurring-info">
                    <div class="recurring-main">
                      <span class="recurring-name">{{ item.name }}</span>
                      <span class="recurring-category">{{ item.category }}</span>
                    </div>
                    <span class="recurring-amount income">+{{ formatCurrency(item.amount) }}</span>
                  </div>
                  <div class="recurring-meta">
                    <span>{{ formatDateFull(item.startDate) }}부터</span>
                    <span v-if="!item.isActive" class="inactive-badge">비활성</span>
                  </div>
                  <div v-if="item.history && item.history.length > 0" class="history-toggle" @click="toggleHistory(item.id)">
                    <span>변경 이력 ({{ item.history.length }}건)</span>
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                         :class="{ rotated: expandedHistory === item.id }">
                      <polyline points="6 9 12 15 18 9"/>
                    </svg>
                  </div>
                  <div v-if="expandedHistory === item.id && item.history" class="history-list">
                    <div v-for="h in item.history" :key="h.id" class="history-item">
                      <span>{{ formatDateFull(h.effectiveDate) }}</span>
                      <span>{{ formatCurrency(h.previousAmount) }} → {{ formatCurrency(h.newAmount) }}</span>
                      <span v-if="h.changeReason" class="history-reason">{{ h.changeReason }}</span>
                    </div>
                  </div>
                  <div class="recurring-actions-row">
                    <button @click="openEditRecurringModal(item)" class="btn-edit">수정</button>
                    <button @click="deactivateRecurring(item.id)" v-if="item.isActive" class="btn-deactivate">비활성화</button>
                    <button @click="deleteRecurring(item.id)" class="btn-delete-small">삭제</button>
                  </div>
                </div>
              </div>
            </section>

            <!-- 고정 지출 목록 -->
            <section class="recurring-section">
              <h3 class="section-title expense">고정 지출</h3>
              <div v-if="recurringExpenses.length === 0" class="empty-recurring">
                등록된 고정 지출이 없습니다.
              </div>
              <div v-else class="recurring-list">
                <div v-for="item in recurringExpenses" :key="item.id" class="recurring-item expense">
                  <div class="recurring-info">
                    <div class="recurring-main">
                      <span class="recurring-name">{{ item.name }}</span>
                      <span class="recurring-category">{{ item.category }}</span>
                    </div>
                    <span class="recurring-amount expense">-{{ formatCurrency(item.amount) }}</span>
                  </div>
                  <div class="recurring-meta">
                    <span>{{ formatDateFull(item.startDate) }}부터</span>
                    <span v-if="!item.isActive" class="inactive-badge">비활성</span>
                  </div>
                  <div v-if="item.history && item.history.length > 0" class="history-toggle" @click="toggleHistory(item.id)">
                    <span>변경 이력 ({{ item.history.length }}건)</span>
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                         :class="{ rotated: expandedHistory === item.id }">
                      <polyline points="6 9 12 15 18 9"/>
                    </svg>
                  </div>
                  <div v-if="expandedHistory === item.id && item.history" class="history-list">
                    <div v-for="h in item.history" :key="h.id" class="history-item">
                      <span>{{ formatDateFull(h.effectiveDate) }}</span>
                      <span>{{ formatCurrency(h.previousAmount) }} → {{ formatCurrency(h.newAmount) }}</span>
                      <span v-if="h.changeReason" class="history-reason">{{ h.changeReason }}</span>
                    </div>
                  </div>
                  <div class="recurring-actions-row">
                    <button @click="openEditRecurringModal(item)" class="btn-edit">수정</button>
                    <button @click="deactivateRecurring(item.id)" v-if="item.isActive" class="btn-deactivate">비활성화</button>
                    <button @click="deleteRecurring(item.id)" class="btn-delete-small">삭제</button>
                  </div>
                </div>
              </div>
            </section>
          </template>
        </template>
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

      <!-- 고정 수입/지출 등록/수정 모달 -->
      <div v-if="showRecurringModal" class="modal-overlay" @click="closeRecurringModal">
        <div class="modal-content" @click.stop>
          <div class="modal-header" :class="recurringForm.type.toLowerCase()">
            <h2>{{ editingRecurringId ? '고정 항목 수정' : (recurringForm.type === 'INCOME' ? '고정 수입 등록' : '고정 지출 등록') }}</h2>
            <button @click="closeRecurringModal" class="btn-close">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
          <div class="modal-body">
            <div class="form-group">
              <label>항목명 *</label>
              <input type="text" v-model="recurringForm.name" placeholder="예: 회사 급여, 월세" class="form-input" />
            </div>

            <div class="form-group" v-if="!editingRecurringId">
              <label>카테고리</label>
              <select v-model="recurringForm.category" class="form-input">
                <option value="">카테고리 선택</option>
                <template v-if="recurringForm.type === 'INCOME'">
                  <option value="급여">급여</option>
                  <option value="보너스">보너스</option>
                  <option value="부수입">부수입</option>
                  <option value="이자">이자</option>
                  <option value="배당금">배당금</option>
                  <option value="기타수입">기타수입</option>
                </template>
                <template v-else>
                  <option value="월세">월세/주거비</option>
                  <option value="공과금">공과금</option>
                  <option value="통신비">통신비</option>
                  <option value="보험">보험</option>
                  <option value="대출상환">대출상환</option>
                  <option value="교통비">교통비</option>
                  <option value="구독료">구독료</option>
                  <option value="기타지출">기타지출</option>
                </template>
              </select>
            </div>

            <div class="form-group">
              <label>금액 *</label>
              <div class="input-wrap">
                <input type="number" v-model.number="recurringForm.amount" placeholder="0" min="0" class="form-input" />
                <span class="unit">원</span>
              </div>
            </div>

            <div class="form-group" v-if="!editingRecurringId">
              <label>시작일</label>
              <input type="date" v-model="recurringForm.startDate" class="form-input" />
            </div>

            <div class="form-group" v-if="editingRecurringId">
              <label>적용일 (금액 변경 시)</label>
              <input type="date" v-model="recurringForm.effectiveDate" class="form-input" />
            </div>

            <div class="form-group" v-if="editingRecurringId">
              <label>변경 사유 (선택)</label>
              <input type="text" v-model="recurringForm.changeReason" placeholder="예: 연봉 인상" class="form-input" />
            </div>

            <div class="form-group">
              <label>메모 (선택)</label>
              <input type="text" v-model="recurringForm.memo" placeholder="메모를 입력하세요" class="form-input" />
            </div>
          </div>
          <div class="modal-footer">
            <button @click="closeRecurringModal" class="btn btn-cancel">취소</button>
            <button @click="submitRecurring" class="btn btn-submit" :class="recurringForm.type.toLowerCase()" :disabled="savingRecurring">
              {{ savingRecurring ? '저장 중...' : (editingRecurringId ? '수정하기' : '등록하기') }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue';
import { useRouter } from 'vue-router';
import { financeAPI, exportAPI } from '../utils/api';
import { UserManager } from '../utils/auth';
import LoadingSpinner from '../components/LoadingSpinner.vue';
import { Chart, ArcElement, Tooltip, Legend, DoughnutController } from 'chart.js';

// Chart.js 컴포넌트 등록
Chart.register(ArcElement, Tooltip, Legend, DoughnutController);

const router = useRouter();

// 탭 상태
const activeTab = ref('transactions');

// 거래 내역 탭 관련
const loading = ref(false);
const saving = ref(false);
const showAddModal = ref(false);
const modalType = ref('INCOME');
const showExportMenu = ref(false);

const currentDate = new Date();
const selectedYear = ref(currentDate.getFullYear());
const selectedMonth = ref(currentDate.getMonth() + 1);

const summary = ref({
  totalIncome: 0,
  totalExpense: 0,
  balance: 0,
  recurringIncome: 0,
  recurringExpense: 0,
  variableIncome: 0,
  variableExpense: 0
});
const transactions = ref([]);

const form = ref({
  category: '',
  amount: null,
  transactionDate: '',
  memo: ''
});

// 고정 수입/지출 탭 관련
const loadingRecurring = ref(false);
const savingRecurring = ref(false);
const showRecurringModal = ref(false);
const recurringItems = ref([]);
const editingRecurringId = ref(null);
const expandedHistory = ref(null);

const recurringForm = ref({
  type: 'INCOME',
  name: '',
  category: '',
  amount: null,
  startDate: '',
  effectiveDate: '',
  changeReason: '',
  memo: ''
});

// 차트 관련
const incomeChartRef = ref(null);
const expenseChartRef = ref(null);
let incomeChart = null;
let expenseChart = null;

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

// 고정 수입 목록
const recurringIncomes = computed(() => {
  return recurringItems.value.filter(item => item.type === 'INCOME');
});

// 고정 지출 목록
const recurringExpenses = computed(() => {
  return recurringItems.value.filter(item => item.type === 'EXPENSE');
});

// 차트 데이터 계산 - 수입
const incomeChartData = computed(() => {
  const categoryMap = new Map();

  // 일반 수입 거래 집계
  transactions.value
    .filter(tx => tx.type === 'INCOME')
    .forEach(tx => {
      const current = categoryMap.get(tx.category) || 0;
      categoryMap.set(tx.category, current + tx.amount);
    });

  // 고정 수입 추가 (recurringItems에서)
  if (summary.value.recurringIncome > 0) {
    categoryMap.set('고정 수입', summary.value.recurringIncome);
  }

  const labels = Array.from(categoryMap.keys());
  const data = Array.from(categoryMap.values());

  return { labels, data };
});

// 차트 데이터 계산 - 지출
const expenseChartData = computed(() => {
  const categoryMap = new Map();

  // 일반 지출 거래 집계
  transactions.value
    .filter(tx => tx.type === 'EXPENSE')
    .forEach(tx => {
      const current = categoryMap.get(tx.category) || 0;
      categoryMap.set(tx.category, current + tx.amount);
    });

  // 고정 지출 추가
  if (summary.value.recurringExpense > 0) {
    categoryMap.set('고정 지출', summary.value.recurringExpense);
  }

  const labels = Array.from(categoryMap.keys());
  const data = Array.from(categoryMap.values());

  return { labels, data };
});

// 차트 데이터 존재 여부
const hasIncomeData = computed(() => {
  return incomeChartData.value.data.length > 0 && incomeChartData.value.data.some(d => d > 0);
});

const hasExpenseData = computed(() => {
  return expenseChartData.value.data.length > 0 && expenseChartData.value.data.some(d => d > 0);
});

// 수입 차트 색상 (녹색 계열)
const incomeColors = [
  '#4caf50', '#66bb6a', '#81c784', '#a5d6a7', '#c8e6c9',
  '#2e7d32', '#388e3c', '#43a047', '#1b5e20', '#00c853'
];

// 지출 차트 색상 (빨간색 계열)
const expenseColors = [
  '#f44336', '#ef5350', '#e57373', '#ef9a9a', '#ffcdd2',
  '#c62828', '#d32f2f', '#e53935', '#b71c1c', '#ff5252'
];

// 차트 렌더링
const renderCharts = () => {
  nextTick(() => {
    // 수입 차트
    if (incomeChartRef.value && hasIncomeData.value) {
      if (incomeChart) {
        incomeChart.destroy();
      }

      const ctx = incomeChartRef.value.getContext('2d');
      incomeChart = new Chart(ctx, {
        type: 'doughnut',
        data: {
          labels: incomeChartData.value.labels,
          datasets: [{
            data: incomeChartData.value.data,
            backgroundColor: incomeColors.slice(0, incomeChartData.value.data.length),
            borderWidth: 2,
            borderColor: '#fff',
            hoverBorderWidth: 3,
            hoverOffset: 8
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          cutout: '60%',
          plugins: {
            legend: {
              position: 'bottom',
              labels: {
                padding: 16,
                usePointStyle: true,
                font: {
                  size: 12,
                  family: "'Pretendard', -apple-system, sans-serif"
                }
              }
            },
            tooltip: {
              backgroundColor: 'rgba(255, 255, 255, 0.95)',
              titleColor: '#1a1a2e',
              bodyColor: '#1a1a2e',
              borderColor: '#4caf50',
              borderWidth: 1,
              padding: 12,
              displayColors: true,
              callbacks: {
                label: function(context) {
                  const label = context.label || '';
                  const value = context.raw || 0;
                  const total = context.dataset.data.reduce((a, b) => a + b, 0);
                  const percentage = ((value / total) * 100).toFixed(1);
                  return `${label}: ${formatCurrency(value)} (${percentage}%)`;
                },
                title: function(context) {
                  return '수입 상세';
                }
              }
            }
          }
        }
      });
    }

    // 지출 차트
    if (expenseChartRef.value && hasExpenseData.value) {
      if (expenseChart) {
        expenseChart.destroy();
      }

      const ctx = expenseChartRef.value.getContext('2d');
      expenseChart = new Chart(ctx, {
        type: 'doughnut',
        data: {
          labels: expenseChartData.value.labels,
          datasets: [{
            data: expenseChartData.value.data,
            backgroundColor: expenseColors.slice(0, expenseChartData.value.data.length),
            borderWidth: 2,
            borderColor: '#fff',
            hoverBorderWidth: 3,
            hoverOffset: 8
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          cutout: '60%',
          plugins: {
            legend: {
              position: 'bottom',
              labels: {
                padding: 16,
                usePointStyle: true,
                font: {
                  size: 12,
                  family: "'Pretendard', -apple-system, sans-serif"
                }
              }
            },
            tooltip: {
              backgroundColor: 'rgba(255, 255, 255, 0.95)',
              titleColor: '#1a1a2e',
              bodyColor: '#1a1a2e',
              borderColor: '#f44336',
              borderWidth: 1,
              padding: 12,
              displayColors: true,
              callbacks: {
                label: function(context) {
                  const label = context.label || '';
                  const value = context.raw || 0;
                  const total = context.dataset.data.reduce((a, b) => a + b, 0);
                  const percentage = ((value / total) * 100).toFixed(1);
                  return `${label}: ${formatCurrency(value)} (${percentage}%)`;
                },
                title: function(context) {
                  return '지출 상세';
                }
              }
            }
          }
        }
      });
    }
  });
};

// 데이터 변경 시 차트 업데이트
watch([transactions, summary], () => {
  if (activeTab.value === 'transactions') {
    renderCharts();
  }
}, { deep: true });

const loadTransactions = async () => {
  try {
    loading.value = true;
    const response = await financeAPI.getMonthlyTransactions(selectedYear.value, selectedMonth.value);
    if (response.data.success) {
      const data = response.data.data;
      summary.value = {
        totalIncome: data.totalIncome || 0,
        totalExpense: data.totalExpense || 0,
        balance: data.balance || 0,
        recurringIncome: data.recurringIncome || 0,
        recurringExpense: data.recurringExpense || 0,
        variableIncome: data.variableIncome || 0,
        variableExpense: data.variableExpense || 0
      };
      transactions.value = data.transactions || [];
    }
    // 차트 렌더링
    renderCharts();
  } catch (error) {
    console.error('Failed to load transactions:', error);
  } finally {
    loading.value = false;
  }
};

// 고정 수입/지출 목록 로드
const loadRecurring = async () => {
  try {
    loadingRecurring.value = true;
    const response = await financeAPI.getRecurring();
    if (response.data.success) {
      recurringItems.value = response.data.data || [];
    }
  } catch (error) {
    console.error('Failed to load recurring:', error);
  } finally {
    loadingRecurring.value = false;
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

// 고정 수입/지출 모달 열기
const openRecurringModal = (type) => {
  editingRecurringId.value = null;
  recurringForm.value = {
    type: type,
    name: '',
    category: '',
    amount: null,
    startDate: new Date().toISOString().split('T')[0],
    effectiveDate: '',
    changeReason: '',
    memo: ''
  };
  showRecurringModal.value = true;
};

// 고정 수입/지출 수정 모달 열기
const openEditRecurringModal = (item) => {
  editingRecurringId.value = item.id;
  recurringForm.value = {
    type: item.type,
    name: item.name,
    category: item.category,
    amount: item.amount,
    startDate: item.startDate,
    effectiveDate: new Date().toISOString().split('T')[0],
    changeReason: '',
    memo: item.memo || ''
  };
  showRecurringModal.value = true;
};

const closeRecurringModal = () => {
  showRecurringModal.value = false;
  editingRecurringId.value = null;
};

// 고정 수입/지출 등록/수정
const submitRecurring = async () => {
  if (!recurringForm.value.name || !recurringForm.value.amount) {
    alert('항목명과 금액은 필수 입력입니다.');
    return;
  }

  try {
    savingRecurring.value = true;

    if (editingRecurringId.value) {
      // 수정
      const data = {
        amount: recurringForm.value.amount,
        effectiveDate: recurringForm.value.effectiveDate || null,
        changeReason: recurringForm.value.changeReason || null,
        memo: recurringForm.value.memo || null
      };
      const response = await financeAPI.updateRecurring(editingRecurringId.value, data);
      if (response.data.success) {
        closeRecurringModal();
        await loadRecurring();
      }
    } else {
      // 신규 등록
      if (!recurringForm.value.category || !recurringForm.value.startDate) {
        alert('카테고리와 시작일은 필수 입력입니다.');
        return;
      }
      const data = {
        type: recurringForm.value.type,
        name: recurringForm.value.name,
        category: recurringForm.value.category,
        amount: recurringForm.value.amount,
        startDate: recurringForm.value.startDate,
        memo: recurringForm.value.memo || null
      };
      const response = await financeAPI.addRecurring(data);
      if (response.data.success) {
        closeRecurringModal();
        await loadRecurring();
      }
    }
  } catch (error) {
    console.error('Failed to save recurring:', error);
    alert('저장에 실패했습니다.');
  } finally {
    savingRecurring.value = false;
  }
};

// 고정 수입/지출 비활성화
const deactivateRecurring = async (id) => {
  if (!confirm('이 항목을 비활성화하시겠습니까? 비활성화된 항목은 이후 월별 계산에 포함되지 않습니다.')) return;

  try {
    const response = await financeAPI.deactivateRecurring(id);
    if (response.data.success) {
      await loadRecurring();
    }
  } catch (error) {
    console.error('Failed to deactivate recurring:', error);
    alert('비활성화에 실패했습니다.');
  }
};

// 고정 수입/지출 삭제
const deleteRecurring = async (id) => {
  if (!confirm('이 항목을 삭제하시겠습니까? 삭제하면 모든 이력이 함께 삭제됩니다.')) return;

  try {
    const response = await financeAPI.deleteRecurring(id);
    if (response.data.success) {
      await loadRecurring();
    }
  } catch (error) {
    console.error('Failed to delete recurring:', error);
    alert('삭제에 실패했습니다.');
  }
};

// 변경 이력 토글
const toggleHistory = (itemId) => {
  if (expandedHistory.value === itemId) {
    expandedHistory.value = null;
  } else {
    expandedHistory.value = itemId;
  }
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

const formatDateFull = (dateStr) => {
  if (!dateStr) return '';
  const date = new Date(dateStr);
  return `${date.getFullYear()}.${String(date.getMonth() + 1).padStart(2, '0')}.${String(date.getDate()).padStart(2, '0')}`;
};

const goBack = () => {
  router.back();
};

const logout = () => {
  UserManager.logout();
  router.push('/login');
};

const exportData = async (format) => {
  showExportMenu.value = false;
  try {
    let response;
    if (format === 'xlsx') {
      response = await exportAPI.exportFinanceExcel(selectedYear.value, selectedMonth.value);
    } else {
      response = await exportAPI.exportFinanceCsv(selectedYear.value, selectedMonth.value);
    }

    const blob = new Blob([response.data]);
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    const date = new Date().toISOString().split('T')[0].replace(/-/g, '');
    link.download = `가계부_${selectedYear.value}년${selectedMonth.value}월_${date}.${format}`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
  } catch (error) {
    console.error('Export failed:', error);
    alert('내보내기에 실패했습니다.');
  }
};

onMounted(() => {
  loadTransactions();
});
</script>

<style scoped>
@import '../assets/css/common.css';

.finance-content {
  max-width: var(--content-max-width);
  margin: 0 auto;
}

/* 상단 바 */
.top-bar {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: 20px 24px;
  margin-bottom: var(--section-gap);
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
  align-items: center;
}

/* 내보내기 드롭다운 */
.export-dropdown {
  position: relative;
}

.btn-export {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  border: 2px solid var(--border-color);
  border-radius: 10px;
  font-weight: 600;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s;
}

.btn-export:hover {
  border-color: var(--primary-start);
  color: var(--primary-start);
}

[data-theme="dark"] .btn-export {
  background: linear-gradient(135deg, #27272a 0%, #1f1f23 100%);
}

.export-menu {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 8px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.15);
  overflow: hidden;
  z-index: 100;
  min-width: 150px;
}

[data-theme="dark"] .export-menu {
  background: #1f1f23;
}

.export-menu button {
  display: block;
  width: 100%;
  padding: 12px 16px;
  background: none;
  border: none;
  text-align: left;
  font-size: 14px;
  color: var(--text-primary);
  cursor: pointer;
  transition: background 0.2s;
}

.export-menu button:hover {
  background: rgba(102, 126, 234, 0.1);
}

.export-menu button:not(:last-child) {
  border-bottom: 1px solid var(--border-light);
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
  margin-bottom: var(--section-gap);
}

.stat-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: var(--card-padding);
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
  padding: var(--card-padding);
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--section-gap);
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
  padding: var(--card-padding);
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
  padding: var(--card-padding);
}

.form-group {
  margin-bottom: var(--section-gap);
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

/* 탭 네비게이션 */
.tab-navigation {
  display: flex;
  gap: 8px;
  margin-bottom: var(--section-gap);
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 12px;
  padding: 6px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.tab-btn {
  flex: 1;
  padding: 12px 20px;
  border: none;
  background: transparent;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.2s;
}

.tab-btn.active {
  background: linear-gradient(135deg, var(--primary-start) 0%, var(--primary-end) 100%);
  color: white;
}

.tab-btn:hover:not(.active) {
  background: rgba(102, 126, 234, 0.1);
  color: var(--primary-start);
}

/* 고정 수입/지출 탭 */
.recurring-header {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: var(--card-padding);
  margin-bottom: var(--section-gap);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.recurring-header h2 {
  margin: 0 0 8px 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}

.recurring-header p {
  margin: 0 0 20px 0;
  font-size: 14px;
  color: var(--text-muted);
}

.recurring-actions {
  display: flex;
  gap: 12px;
}

/* 고정 수입/지출 섹션 */
.recurring-section {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: var(--card-padding);
  margin-bottom: var(--section-gap);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.section-title {
  font-size: 16px;
  font-weight: 700;
  margin: 0 0 16px 0;
  padding-bottom: 12px;
  border-bottom: 2px solid var(--border-light);
}

.section-title.income {
  color: #4caf50;
}

.section-title.expense {
  color: #f44336;
}

.empty-recurring {
  text-align: center;
  padding: 40px 20px;
  color: var(--text-muted);
  font-size: 14px;
}

.recurring-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.recurring-item {
  background: #f8f9fa;
  border-radius: 12px;
  padding: 16px;
  border-left: 4px solid transparent;
}

.recurring-item.income {
  border-left-color: #4caf50;
}

.recurring-item.expense {
  border-left-color: #f44336;
}

.recurring-info {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 8px;
}

.recurring-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.recurring-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.recurring-category {
  font-size: 12px;
  color: var(--text-muted);
  background: rgba(0, 0, 0, 0.05);
  padding: 2px 8px;
  border-radius: 4px;
  display: inline-block;
  width: fit-content;
}

.recurring-amount {
  font-size: 16px;
  font-weight: 700;
}

.recurring-amount.income {
  color: #4caf50;
}

.recurring-amount.expense {
  color: #f44336;
}

.recurring-meta {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 8px;
}

.inactive-badge {
  background: #ff9800;
  color: white;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}

/* 변경 이력 */
.history-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--primary-start);
  cursor: pointer;
  margin-bottom: 8px;
  padding: 4px 0;
}

.history-toggle:hover {
  text-decoration: underline;
}

.history-toggle svg {
  transition: transform 0.2s;
}

.history-toggle svg.rotated {
  transform: rotate(180deg);
}

.history-list {
  background: rgba(102, 126, 234, 0.05);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}

.history-item {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  padding: 8px 0;
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
}

.history-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.history-item:first-child {
  padding-top: 0;
}

.history-reason {
  flex-basis: 100%;
  color: var(--text-muted);
  font-style: italic;
}

/* 고정 항목 액션 버튼들 */
.recurring-actions-row {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  padding-top: 12px;
  border-top: 1px solid rgba(0, 0, 0, 0.05);
}

.btn-edit {
  padding: 6px 12px;
  background: var(--primary-start);
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-edit:hover {
  background: var(--primary-end);
}

.btn-deactivate {
  padding: 6px 12px;
  background: #ff9800;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-deactivate:hover {
  background: #f57c00;
}

.btn-delete-small {
  padding: 6px 12px;
  background: transparent;
  color: #f44336;
  border: 1px solid #f44336;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-delete-small:hover {
  background: #f44336;
  color: white;
}

/* 요약 카드 상세 정보 */
.stat-detail {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 2px;
}

/* 차트 섹션 */
.charts-section {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--section-gap);
  margin-bottom: var(--section-gap);
}

.chart-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: var(--card-padding);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

[data-theme="dark"] .chart-card {
  background: var(--card-bg);
}

.chart-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 16px 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.chart-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chart-icon.income {
  background: rgba(76, 175, 80, 0.15);
  color: #4caf50;
}

.chart-icon.expense {
  background: rgba(244, 67, 54, 0.15);
  color: #f44336;
}

.chart-container {
  position: relative;
  height: 280px;
}

.no-chart-data {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  text-align: center;
  color: var(--text-muted);
  font-size: 14px;
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

  .charts-section {
    grid-template-columns: 1fr;
  }

  .chart-container {
    height: 240px;
  }

  .recurring-actions {
    flex-direction: column;
  }

  .recurring-actions .btn-income,
  .recurring-actions .btn-expense {
    justify-content: center;
  }

  .recurring-info {
    flex-direction: column;
    gap: 8px;
  }

  .recurring-amount {
    align-self: flex-start;
  }
}
</style>
