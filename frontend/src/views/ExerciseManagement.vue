<template>
  <div class="management-page">
    <GlobalNav subtitle="운동 관리" />
    <header class="page-header">
      <BackButton :dark="true" />
      <h1>운동 관리</h1>
      <button class="add-btn" @click="showModal = true">+ 운동 등록</button>
    </header>

    <!-- 요약 카드 -->
    <div class="summary-cards">
      <div class="summary-card">
        <span class="card-label">오늘 운동</span>
        <span class="card-value">{{ summary.todayExercises || 0 }}회</span>
      </div>
      <div class="summary-card">
        <span class="card-label">오늘 시간</span>
        <span class="card-value time">{{ summary.todayMinutes || 0 }}분</span>
      </div>
      <div class="summary-card">
        <span class="card-label">오늘 소모</span>
        <span class="card-value burn">{{ (summary.todayCaloriesBurned || 0).toLocaleString() }}kcal</span>
      </div>
      <div class="summary-card">
        <span class="card-label">총 기록</span>
        <span class="card-value">{{ summary.totalRecords || 0 }}건</span>
      </div>
    </div>

    <!-- 필터 -->
    <div class="filter-bar">
      <button v-for="t in exerciseTypes" :key="t.value"
        class="filter-btn" :class="{ active: filter === t.value }"
        @click="filter = t.value; loadRecords()">{{ t.label }}</button>
    </div>

    <!-- 기록 리스트 -->
    <div v-if="loading" class="loading-wrap"><div class="spinner"></div></div>
    <div v-else-if="records.length === 0" class="empty-state">
      <p>운동 기록이 없습니다.</p>
    </div>
    <div v-else class="record-list">
      <div v-for="r in records" :key="r.id" class="record-card">
        <div class="record-header">
          <span class="type-badge" :class="r.exerciseType.toLowerCase()">{{ r.exerciseTypeName }}</span>
          <span class="intensity-badge" :class="(r.intensity || '').toLowerCase()">{{ r.intensityName }}</span>
          <span class="record-date">{{ r.exerciseDate }}</span>
          <div class="record-actions">
            <button class="edit-btn" @click="openEdit(r)">수정</button>
            <button class="del-btn" @click="deleteRecord(r.id)">삭제</button>
          </div>
        </div>
        <div class="record-body">
          <strong class="exercise-name">{{ r.exerciseName }}</strong>
        </div>
        <div class="stats-row">
          <span v-if="r.durationMinutes" class="stat-item"><b>{{ r.durationMinutes }}</b>분</span>
          <span v-if="r.sets" class="stat-item"><b>{{ r.sets }}</b>세트</span>
          <span v-if="r.reps" class="stat-item"><b>{{ r.reps }}</b>회</span>
          <span v-if="r.weight" class="stat-item"><b>{{ r.weight }}</b>kg</span>
          <span v-if="r.caloriesBurned" class="stat-item burn"><b>{{ r.caloriesBurned }}</b>kcal</span>
        </div>
        <p v-if="r.memo" class="memo">{{ r.memo }}</p>
      </div>
    </div>

    <!-- 등록/수정 모달 -->
    <div v-if="showModal" class="modal-overlay" @click.self="closeModal">
      <div class="modal-content">
        <h2>{{ editId ? '운동 수정' : '운동 등록' }}</h2>
        <form @submit.prevent="submitForm">
          <div class="form-group">
            <label>운동 유형</label>
            <div class="type-selector">
              <button v-for="t in exerciseTypes.filter(x => x.value)" :key="t.value" type="button"
                class="type-btn" :class="{ active: form.exerciseType === t.value }"
                @click="form.exerciseType = t.value">{{ t.label }}</button>
            </div>
          </div>
          <div class="form-group">
            <label>운동명 *</label>
            <input v-model="form.exerciseName" required placeholder="예: 러닝, 벤치프레스, 요가" />
          </div>
          <div class="form-group">
            <label>강도</label>
            <div class="type-selector">
              <button v-for="i in intensities" :key="i.value" type="button"
                class="type-btn" :class="{ active: form.intensity === i.value }"
                @click="form.intensity = i.value">{{ i.label }}</button>
            </div>
          </div>
          <div class="form-row">
            <div class="form-group half">
              <label>시간 (분)</label>
              <input v-model.number="form.durationMinutes" type="number" min="0" />
            </div>
            <div class="form-group half">
              <label>소모 칼로리</label>
              <input v-model.number="form.caloriesBurned" type="number" min="0" />
            </div>
          </div>
          <div class="form-row">
            <div class="form-group third">
              <label>세트</label>
              <input v-model.number="form.sets" type="number" min="0" />
            </div>
            <div class="form-group third">
              <label>횟수</label>
              <input v-model.number="form.reps" type="number" min="0" />
            </div>
            <div class="form-group third">
              <label>무게 (kg)</label>
              <input v-model.number="form.weight" type="number" min="0" step="0.5" />
            </div>
          </div>
          <div class="form-group">
            <label>날짜</label>
            <input v-model="form.exerciseDate" type="date" />
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
import { exerciseAPI } from '../utils/api';

const loading = ref(false);
const records = ref([]);
const summary = ref({});
const filter = ref('');
const showModal = ref(false);
const editId = ref(null);

const exerciseTypes = [
  { value: '', label: '전체' },
  { value: 'CARDIO', label: '유산소' },
  { value: 'STRENGTH', label: '근력' },
  { value: 'FLEXIBILITY', label: '유연성' },
  { value: 'SPORTS', label: '스포츠' }
];

const intensities = [
  { value: 'LOW', label: '낮음' },
  { value: 'MEDIUM', label: '보통' },
  { value: 'HIGH', label: '높음' }
];

const today = new Date().toISOString().slice(0, 10);
const defaultForm = () => ({
  exerciseType: 'CARDIO', exerciseName: '', durationMinutes: null,
  sets: null, reps: null, weight: null, intensity: 'MEDIUM',
  caloriesBurned: null, exerciseDate: today, memo: ''
});
const form = ref(defaultForm());

const loadRecords = async () => {
  loading.value = true;
  try {
    const [recRes, sumRes] = await Promise.all([
      exerciseAPI.getRecords(filter.value || null),
      exerciseAPI.getSummary()
    ]);
    if (recRes.data.success) records.value = recRes.data.data;
    if (sumRes.data.success) summary.value = sumRes.data.data;
  } catch (e) { console.error(e); }
  finally { loading.value = false; }
};

const submitForm = async () => {
  try {
    if (editId.value) {
      await exerciseAPI.updateRecord(editId.value, form.value);
    } else {
      await exerciseAPI.addRecord(form.value);
    }
    closeModal();
    loadRecords();
  } catch (e) { alert('저장 실패: ' + e.message); }
};

const openEdit = (r) => {
  editId.value = r.id;
  form.value = {
    exerciseType: r.exerciseType, exerciseName: r.exerciseName,
    durationMinutes: r.durationMinutes, sets: r.sets, reps: r.reps,
    weight: r.weight, intensity: r.intensity,
    caloriesBurned: r.caloriesBurned, exerciseDate: r.exerciseDate, memo: r.memo
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
  try { await exerciseAPI.deleteRecord(id); loadRecords(); }
  catch (e) { alert('삭제 실패'); }
};

onMounted(loadRecords);
</script>

<style scoped>
.management-page { min-height: 100vh; background: linear-gradient(135deg, #0f0f23 0%, #1a1a3e 100%); color: #fff; padding: 20px; }
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.page-header h1 { flex: 1; font-size: 20px; margin: 0; }
.add-btn { background: #10b981; color: #fff; border: none; border-radius: 8px; padding: 8px 16px; cursor: pointer; font-size: 13px; }
.add-btn:hover { background: #059669; }

.summary-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 20px; }
.summary-card { background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.1); border-radius: 12px; padding: 16px; text-align: center; }
.card-label { display: block; font-size: 12px; color: rgba(255,255,255,0.5); margin-bottom: 4px; }
.card-value { font-size: 22px; font-weight: 700; }
.card-value.time { color: #6366f1; }
.card-value.burn { color: #ef4444; }

.filter-bar { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
.filter-btn { background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.1); color: rgba(255,255,255,0.6); border-radius: 20px; padding: 6px 14px; cursor: pointer; font-size: 13px; }
.filter-btn.active { background: #10b981; color: #fff; border-color: #10b981; }

.loading-wrap { text-align: center; padding: 40px; }
.spinner { width: 32px; height: 32px; border: 3px solid rgba(255,255,255,0.1); border-top-color: #10b981; border-radius: 50%; animation: spin 0.7s linear infinite; margin: 0 auto; }
@keyframes spin { to { transform: rotate(360deg); } }
.empty-state { text-align: center; padding: 60px 20px; color: rgba(255,255,255,0.4); }

.record-list { display: flex; flex-direction: column; gap: 10px; }
.record-card { background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.08); border-radius: 12px; padding: 14px; }
.record-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.type-badge { font-size: 11px; padding: 2px 8px; border-radius: 10px; font-weight: 600; }
.type-badge.cardio { background: rgba(239,68,68,0.2); color: #f87171; }
.type-badge.strength { background: rgba(99,102,241,0.2); color: #818cf8; }
.type-badge.flexibility { background: rgba(34,197,94,0.2); color: #22c55e; }
.type-badge.sports { background: rgba(251,191,36,0.2); color: #fbbf24; }
.intensity-badge { font-size: 10px; padding: 2px 6px; border-radius: 8px; }
.intensity-badge.low { background: rgba(34,197,94,0.15); color: #4ade80; }
.intensity-badge.medium { background: rgba(251,191,36,0.15); color: #fbbf24; }
.intensity-badge.high { background: rgba(239,68,68,0.15); color: #f87171; }
.record-date { font-size: 12px; color: rgba(255,255,255,0.4); }
.record-actions { margin-left: auto; display: flex; gap: 6px; }
.edit-btn, .del-btn { background: none; border: 1px solid rgba(255,255,255,0.15); color: rgba(255,255,255,0.5); border-radius: 6px; padding: 3px 8px; cursor: pointer; font-size: 11px; }
.edit-btn:hover { border-color: #10b981; color: #34d399; }
.del-btn:hover { border-color: #ef4444; color: #ef4444; }
.exercise-name { font-size: 15px; }
.stats-row { display: flex; gap: 12px; margin-top: 8px; font-size: 13px; color: rgba(255,255,255,0.5); }
.stat-item b { color: #fff; }
.stat-item.burn b { color: #f87171; }
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
.type-btn.active { background: #10b981; color: #fff; border-color: #10b981; }
.modal-actions { display: flex; gap: 10px; margin-top: 16px; }
.cancel-btn { flex: 1; background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.12); color: rgba(255,255,255,0.6); border-radius: 8px; padding: 10px; cursor: pointer; }
.submit-btn { flex: 1; background: #10b981; color: #fff; border: none; border-radius: 8px; padding: 10px; cursor: pointer; font-weight: 600; }
.submit-btn:hover { background: #059669; }

@media (max-width: 600px) {
  .summary-cards { grid-template-columns: repeat(2, 1fr); }
  .form-row { flex-direction: column; gap: 0; }
}
</style>
