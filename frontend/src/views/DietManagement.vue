<template>
  <div class="management-page">
    <GlobalNav subtitle="식단 관리" />
    <header class="page-header">
      <BackButton :dark="true" />
      <h1>식단 관리</h1>
      <button class="add-btn" @click="showModal = true">+ 식단 등록</button>
    </header>

    <!-- 요약 카드 -->
    <div class="summary-cards">
      <div class="summary-card">
        <span class="card-label">오늘 식사</span>
        <span class="card-value">{{ summary.todayMeals || 0 }}끼</span>
      </div>
      <div class="summary-card">
        <span class="card-label">오늘 칼로리</span>
        <span class="card-value calories">{{ (summary.todayCalories || 0).toLocaleString() }}kcal</span>
      </div>
      <div class="summary-card">
        <span class="card-label">총 기록</span>
        <span class="card-value">{{ summary.totalRecords || 0 }}건</span>
      </div>
    </div>

    <!-- 필터 -->
    <div class="filter-bar">
      <button v-for="t in dietTypes" :key="t.value"
        class="filter-btn" :class="{ active: filter === t.value }"
        @click="filter = t.value; loadRecords()">{{ t.label }}</button>
    </div>

    <!-- 기록 리스트 -->
    <div v-if="loading" class="loading-wrap"><div class="spinner"></div></div>
    <div v-else-if="records.length === 0" class="empty-state">
      <p>식단 기록이 없습니다.</p>
    </div>
    <div v-else class="record-list">
      <div v-for="r in records" :key="r.id" class="record-card">
        <div class="record-header">
          <span class="type-badge" :class="r.dietType.toLowerCase()">{{ r.dietTypeName }}</span>
          <span class="record-date">{{ r.mealDate }}</span>
          <div class="record-actions">
            <button class="edit-btn" @click="openEdit(r)">수정</button>
            <button class="del-btn" @click="deleteRecord(r.id)">삭제</button>
          </div>
        </div>
        <div class="record-body">
          <strong class="food-name">{{ r.foodName }}</strong>
          <span v-if="r.portion" class="portion">({{ r.portion }})</span>
        </div>
        <div class="nutrition-row">
          <span class="nut-item"><b>{{ r.calories || 0 }}</b>kcal</span>
          <span class="nut-item">탄 <b>{{ r.carbs || 0 }}</b>g</span>
          <span class="nut-item">단 <b>{{ r.protein || 0 }}</b>g</span>
          <span class="nut-item">지 <b>{{ r.fat || 0 }}</b>g</span>
        </div>
        <p v-if="r.memo" class="memo">{{ r.memo }}</p>
      </div>
    </div>

    <!-- 등록/수정 모달 -->
    <div v-if="showModal" class="modal-overlay" @click.self="closeModal">
      <div class="modal-content">
        <h2>{{ editId ? '식단 수정' : '식단 등록' }}</h2>
        <form @submit.prevent="submitForm">
          <div class="form-group">
            <label>식사 유형</label>
            <div class="type-selector">
              <button v-for="t in dietTypes.filter(x => x.value)" :key="t.value" type="button"
                class="type-btn" :class="{ active: form.dietType === t.value }"
                @click="form.dietType = t.value">{{ t.label }}</button>
            </div>
          </div>
          <div class="form-group">
            <label>음식명 *</label>
            <input v-model="form.foodName" required placeholder="예: 현미밥, 닭가슴살 샐러드" />
          </div>
          <div class="form-row">
            <div class="form-group half">
              <label>칼로리 (kcal)</label>
              <input v-model.number="form.calories" type="number" min="0" />
            </div>
            <div class="form-group half">
              <label>양 (portion)</label>
              <input v-model="form.portion" placeholder="예: 1인분, 200g" />
            </div>
          </div>
          <div class="form-row">
            <div class="form-group third">
              <label>탄수화물 (g)</label>
              <input v-model.number="form.carbs" type="number" min="0" step="0.1" />
            </div>
            <div class="form-group third">
              <label>단백질 (g)</label>
              <input v-model.number="form.protein" type="number" min="0" step="0.1" />
            </div>
            <div class="form-group third">
              <label>지방 (g)</label>
              <input v-model.number="form.fat" type="number" min="0" step="0.1" />
            </div>
          </div>
          <div class="form-group">
            <label>날짜</label>
            <input v-model="form.mealDate" type="date" />
          </div>
          <div class="form-group">
            <label>메모</label>
            <textarea v-model="form.memo" rows="2" placeholder="메모"></textarea>
          </div>
          <div class="modal-actions">
            <button type="button" class="cancel-btn" @click="closeModal">취소</button>
            <button type="submit" class="submit-btn">{{ editId ? '수정' : '등록' }}</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import GlobalNav from '../components/GlobalNav.vue';
import BackButton from '../components/BackButton.vue';
import { dietAPI } from '../utils/api';

const loading = ref(false);
const records = ref([]);
const summary = ref({});
const filter = ref('');
const showModal = ref(false);
const editId = ref(null);

const dietTypes = [
  { value: '', label: '전체' },
  { value: 'BREAKFAST', label: '아침' },
  { value: 'LUNCH', label: '점심' },
  { value: 'DINNER', label: '저녁' },
  { value: 'SNACK', label: '간식' }
];

const today = new Date().toISOString().slice(0, 10);
const defaultForm = () => ({
  dietType: 'LUNCH', foodName: '', calories: null, protein: null,
  carbs: null, fat: null, portion: '', mealDate: today, memo: ''
});
const form = ref(defaultForm());

const loadRecords = async () => {
  loading.value = true;
  try {
    const [recRes, sumRes] = await Promise.all([
      dietAPI.getRecords(filter.value || null),
      dietAPI.getSummary()
    ]);
    if (recRes.data.success) records.value = recRes.data.data;
    if (sumRes.data.success) summary.value = sumRes.data.data;
  } catch (e) { console.error(e); }
  finally { loading.value = false; }
};

const submitForm = async () => {
  try {
    if (editId.value) {
      await dietAPI.updateRecord(editId.value, form.value);
    } else {
      await dietAPI.addRecord(form.value);
    }
    closeModal();
    loadRecords();
  } catch (e) { alert('저장 실패: ' + e.message); }
};

const openEdit = (r) => {
  editId.value = r.id;
  form.value = {
    dietType: r.dietType, foodName: r.foodName, calories: r.calories,
    protein: r.protein, carbs: r.carbs, fat: r.fat,
    portion: r.portion, mealDate: r.mealDate, memo: r.memo
  };
  showModal.value = true;
};

const closeModal = () => {
  showModal.value = false;
  editId.value = null;
  form.value = defaultForm();
};

const deleteRecord = async (id) => {
  if (!confirm('삭제하시겠습니까?')) return;
  try { await dietAPI.deleteRecord(id); loadRecords(); }
  catch (e) { alert('삭제 실패'); }
};

onMounted(loadRecords);
</script>

<style scoped>
.management-page { min-height: 100vh; background: linear-gradient(135deg, #0f0f23 0%, #1a1a3e 100%); color: #fff; padding: 20px; }
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.page-header h1 { flex: 1; font-size: 20px; margin: 0; }
.add-btn { background: #6366f1; color: #fff; border: none; border-radius: 8px; padding: 8px 16px; cursor: pointer; font-size: 13px; }
.add-btn:hover { background: #5558e6; }

.summary-cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 20px; }
.summary-card { background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.1); border-radius: 12px; padding: 16px; text-align: center; }
.card-label { display: block; font-size: 12px; color: rgba(255,255,255,0.5); margin-bottom: 4px; }
.card-value { font-size: 22px; font-weight: 700; }
.card-value.calories { color: #f59e0b; }

.filter-bar { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
.filter-btn { background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.1); color: rgba(255,255,255,0.6); border-radius: 20px; padding: 6px 14px; cursor: pointer; font-size: 13px; }
.filter-btn.active { background: #6366f1; color: #fff; border-color: #6366f1; }

.loading-wrap { text-align: center; padding: 40px; }
.spinner { width: 32px; height: 32px; border: 3px solid rgba(255,255,255,0.1); border-top-color: #6366f1; border-radius: 50%; animation: spin 0.7s linear infinite; margin: 0 auto; }
@keyframes spin { to { transform: rotate(360deg); } }
.empty-state { text-align: center; padding: 60px 20px; color: rgba(255,255,255,0.4); }

.record-list { display: flex; flex-direction: column; gap: 10px; }
.record-card { background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.08); border-radius: 12px; padding: 14px; }
.record-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.type-badge { font-size: 11px; padding: 2px 8px; border-radius: 10px; font-weight: 600; }
.type-badge.breakfast { background: rgba(251,191,36,0.2); color: #fbbf24; }
.type-badge.lunch { background: rgba(34,197,94,0.2); color: #22c55e; }
.type-badge.dinner { background: rgba(99,102,241,0.2); color: #818cf8; }
.type-badge.snack { background: rgba(244,114,182,0.2); color: #f472b6; }
.record-date { font-size: 12px; color: rgba(255,255,255,0.4); }
.record-actions { margin-left: auto; display: flex; gap: 6px; }
.edit-btn, .del-btn { background: none; border: 1px solid rgba(255,255,255,0.15); color: rgba(255,255,255,0.5); border-radius: 6px; padding: 3px 8px; cursor: pointer; font-size: 11px; }
.edit-btn:hover { border-color: #6366f1; color: #818cf8; }
.del-btn:hover { border-color: #ef4444; color: #ef4444; }
.food-name { font-size: 15px; }
.portion { font-size: 13px; color: rgba(255,255,255,0.4); margin-left: 4px; }
.nutrition-row { display: flex; gap: 12px; margin-top: 8px; font-size: 13px; color: rgba(255,255,255,0.5); }
.nut-item b { color: #fff; }
.memo { font-size: 12px; color: rgba(255,255,255,0.35); margin-top: 6px; }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.7); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal-content { background: #1e1e3a; border-radius: 16px; padding: 24px; width: 90%; max-width: 480px; max-height: 85vh; overflow-y: auto; }
.modal-content h2 { margin: 0 0 16px; font-size: 18px; }
.form-group { margin-bottom: 12px; }
.form-group label { display: block; font-size: 12px; color: rgba(255,255,255,0.5); margin-bottom: 4px; }
.form-group input, .form-group textarea { width: 100%; background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.12); border-radius: 8px; padding: 8px 10px; color: #fff; font-size: 14px; box-sizing: border-box; }
.form-row { display: flex; gap: 10px; }
.form-group.half { flex: 1; }
.form-group.third { flex: 1; }
.type-selector { display: flex; gap: 6px; flex-wrap: wrap; }
.type-btn { background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.12); color: rgba(255,255,255,0.6); border-radius: 8px; padding: 6px 12px; cursor: pointer; font-size: 13px; }
.type-btn.active { background: #6366f1; color: #fff; border-color: #6366f1; }
.modal-actions { display: flex; gap: 10px; margin-top: 16px; }
.cancel-btn { flex: 1; background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.12); color: rgba(255,255,255,0.6); border-radius: 8px; padding: 10px; cursor: pointer; }
.submit-btn { flex: 1; background: #6366f1; color: #fff; border: none; border-radius: 8px; padding: 10px; cursor: pointer; font-weight: 600; }
.submit-btn:hover { background: #5558e6; }

@media (max-width: 600px) {
  .summary-cards { grid-template-columns: 1fr; }
  .form-row { flex-direction: column; gap: 0; }
}
</style>
