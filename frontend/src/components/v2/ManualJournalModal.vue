<template>
  <div class="modal-backdrop" @click.self="$emit('close')">
    <div class="modal-content">
      <div class="modal-header">
        <h2>📔 매수 기록</h2>
        <button class="close-btn" @click="$emit('close')">×</button>
      </div>

      <div class="modal-body">
        <p class="stock-line">
          <strong>{{ displayName }}</strong>
          <span class="code">{{ stockCode }}</span>
        </p>
        <p class="hint-line">
          실제 주문이 아니라 <b>기록</b>입니다. 매수 시점 신호(점수·RSI·재료·국면 등)가 자동 저장되고
          3거래일 후 적중 여부가 평가됩니다.
        </p>

        <!-- 섹터 집중 경고 — 경고만, 차단 없음. 매핑 밖(mapped:false)이면 미표시(§4c) -->
        <div v-for="block in exposureBlocks" :key="block.sectorCode" class="sector-warn">
          ⚠ 동일 섹터(<b>{{ block.sectorName }}</b>) 보유 <b>{{ block.count }}종목</b>:
          <span class="holdings">
            {{ block.holdings.map(h => (h.stockName || h.stockCode) + (h.source === 'BOT' ? '(봇)' : '')).join(', ') }}
          </span>
        </div>

        <form class="journal-form" @submit.prevent="submit">
          <label class="field">
            <span class="label">매수가 <em>*</em></span>
            <input v-model="buyPrice" type="number" step="any" min="1" required
                   :placeholder="priceLoading ? '현재가 조회 중...' : '매수 가격'" />
          </label>
          <label class="field">
            <span class="label">수량</span>
            <input v-model="quantity" type="number" step="1" min="1" placeholder="선택" />
          </label>
          <label class="field">
            <span class="label">메모</span>
            <textarea v-model="memo" rows="2" maxlength="500" placeholder="매수 이유 등 (선택)"></textarea>
          </label>

          <p v-if="errorMsg" class="error-line">{{ errorMsg }}</p>

          <div class="actions">
            <button type="button" class="btn ghost" @click="$emit('close')">취소</button>
            <button type="submit" class="btn primary" :disabled="saving">
              {{ saving ? '저장 중...' : '기록 저장' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import apiClient, { manualJournalAPI } from '../../utils/api';

const props = defineProps({
  stockCode: { type: String, required: true },
  stockName: { type: String, default: '' },
  // 부모가 이미 아는 현재가(선택). 없으면 /stock/{code} 로 프리필 조회.
  currentPrice: { type: [Number, String], default: null }
});
const emit = defineEmits(['close', 'saved']);

const buyPrice = ref(props.currentPrice != null ? String(props.currentPrice) : '');
const quantity = ref('');
const memo = ref('');
const saving = ref(false);
const priceLoading = ref(false);
const errorMsg = ref('');
const exposure = ref(null);
const resolvedName = ref('');   // 프리필 조회에서 확보한 종목명(프롭 없을 때)

const displayName = computed(() => props.stockName || resolvedName.value || props.stockCode);

// 매핑 밖(mapped:false)·조회 실패면 빈 배열 → 경고 미표시(§4c — "0종목" 위장 금지)
const exposureBlocks = computed(() => {
  if (!exposure.value?.mapped) return [];
  return (exposure.value.sectors || []).filter(b => b.count > 0);
});

const prefillPrice = async (code) => {
  if (buyPrice.value && props.stockName) return;   // 부모가 가격·이름 다 넘겨줌
  priceLoading.value = !buyPrice.value;
  try {
    const { data } = await apiClient.get(`/stock/${code}`);
    const p = data?.data?.currentPrice;
    if (p != null && !buyPrice.value) buyPrice.value = String(p);
    if (data?.data?.stockName) resolvedName.value = data.data.stockName;
  } catch (e) { /* 프리필 실패 — 수동 입력 */ } finally {
    priceLoading.value = false;
  }
};

const loadExposure = async (code) => {
  try {
    const { data } = await manualJournalAPI.sectorExposure(code);
    if (data?.success) exposure.value = data.data;
  } catch (e) { /* 경고는 best-effort — 실패 시 미표시 */ }
};

watch(() => props.stockCode, (code) => {
  if (!code) return;
  prefillPrice(code);
  loadExposure(code);
}, { immediate: true });

const submit = async () => {
  errorMsg.value = '';
  const price = Number(buyPrice.value);
  if (!price || price <= 0) {
    errorMsg.value = '매수가를 입력해 주세요.';
    return;
  }
  saving.value = true;
  try {
    const { data } = await manualJournalAPI.recordBuy({
      stockCode: props.stockCode,
      stockName: props.stockName || resolvedName.value || null,
      buyPrice: price,
      quantity: quantity.value ? Number(quantity.value) : null,
      memo: memo.value || null
    });
    if (data?.success) {
      emit('saved', data.data);
      emit('close');
    } else {
      errorMsg.value = data?.message || '저장에 실패했습니다.';
    }
  } catch (e) {
    errorMsg.value = '저장에 실패했습니다.';
  } finally {
    saving.value = false;
  }
};
</script>

<style scoped>
.modal-backdrop {
  position: fixed; inset: 0;
  background: rgba(0, 0, 0, 0.65);
  display: flex; align-items: center; justify-content: center;
  z-index: 10000;   /* BuyChecklistModal(9999) 위에서 열릴 수 있음 */
}
.modal-content {
  background: #1a1f2e;
  border-radius: 14px;
  width: min(440px, 92vw);
  max-height: 90vh;
  overflow-y: auto;
  color: #fff;
  box-shadow: 0 12px 36px rgba(0,0,0,0.5);
}
.modal-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}
.modal-header h2 { margin: 0; font-size: 17px; }
.close-btn {
  background: none; border: none; color: #fff;
  font-size: 26px; cursor: pointer; padding: 0; width: 32px; height: 32px;
}
.modal-body { padding: 18px; }
.stock-line { margin: 0 0 6px; font-size: 15px; }
.stock-line .code { margin-left: 8px; opacity: 0.55; font-size: 12px; }
.hint-line { margin: 0 0 12px; font-size: 12px; opacity: 0.65; line-height: 1.5; }

.sector-warn {
  background: rgba(234, 179, 8, 0.12);
  border: 1px solid rgba(234, 179, 8, 0.4);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
  margin-bottom: 10px;
  line-height: 1.5;
}
.sector-warn .holdings { opacity: 0.85; }

.journal-form .field { display: block; margin-bottom: 10px; }
.journal-form .label { display: block; font-size: 12px; opacity: 0.75; margin-bottom: 4px; }
.journal-form .label em { color: #fca5a5; font-style: normal; }
.journal-form input,
.journal-form textarea {
  width: 100%; box-sizing: border-box;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.14);
  border-radius: 8px;
  color: #fff;
  padding: 8px 10px;
  font-size: 14px;
}
.journal-form textarea { resize: vertical; }

.error-line { color: #fca5a5; font-size: 12px; margin: 4px 0 0; }

.actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.btn {
  border: none; border-radius: 8px; cursor: pointer;
  padding: 8px 16px; font-size: 13px; font-weight: 600;
}
.btn.primary { background: #3b82f6; color: #fff; }
.btn.primary:disabled { opacity: 0.6; cursor: default; }
.btn.ghost { background: rgba(255,255,255,0.08); color: #fff; }
</style>
