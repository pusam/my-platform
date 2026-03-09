<template>
  <div class="section-card">
    <div class="section-title-row">
      <h2><span class="section-icon">⭐</span> 관심종목</h2>
      <span class="count-badge" v-if="list.length">{{ list.length }}개</span>
    </div>

    <div v-if="loading" class="state-box">
      <p class="state-text">불러오는 중...</p>
    </div>

    <div v-else-if="list.length === 0" class="state-box">
      <span class="state-icon">📌</span>
      <p class="state-text">관심종목이 없습니다</p>
      <p class="state-sub">종목 카드의 ⭐ 버튼을 눌러 추가해보세요</p>
    </div>

    <div v-else class="watchlist-items">
      <div
        v-for="item in list"
        :key="item.id"
        class="watch-card"
        @click="goToStock(item.stockCode)"
      >
        <div class="watch-main">
          <div class="watch-name-row">
            <span class="watch-name">{{ item.stockName }}</span>
            <span class="watch-code">{{ item.stockCode }}</span>
          </div>
          <div class="watch-price-row">
            <span class="price" v-if="item.currentPrice">
              {{ formatPrice(item.currentPrice) }}원
            </span>
            <span class="change" v-if="item.changeRate != null" :class="item.changeRate >= 0 ? 'up' : 'down'">
              {{ item.changeRate >= 0 ? '+' : '' }}{{ Number(item.changeRate).toFixed(2) }}%
            </span>
          </div>
          <!-- 목표가 알림 상태 -->
          <div class="alert-row" v-if="item.targetPrice">
            <span class="alert-badge" :class="{ triggered: item.alertTriggered }">
              🎯 {{ item.alertCondition === 'ABOVE' ? '↑' : '↓' }} {{ formatPrice(item.targetPrice) }}원
            </span>
            <span v-if="item.alertTriggered" class="triggered-text">알림 완료</span>
          </div>
        </div>

        <div class="watch-actions">
          <button class="action-btn" @click.stop="openAlertModal(item)" title="목표가 설정">
            🎯
          </button>
          <button class="action-btn delete" @click.stop="removeItem(item)" title="삭제">
            ✕
          </button>
        </div>
      </div>
    </div>

    <!-- 목표가 설정 모달 -->
    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal-box">
        <h3>🎯 목표가 알림 설정</h3>
        <p class="modal-stock">{{ modalItem?.stockName }} ({{ modalItem?.stockCode }})</p>

        <div class="form-group">
          <label>목표가 (원)</label>
          <input
            type="number"
            v-model.number="modalPrice"
            placeholder="목표가를 입력하세요"
            class="form-input"
          />
        </div>

        <div class="form-group">
          <label>조건</label>
          <div class="condition-btns">
            <button
              :class="['cond-btn', { active: modalCondition === 'ABOVE' }]"
              @click="modalCondition = 'ABOVE'"
            >↑ 이상 도달</button>
            <button
              :class="['cond-btn', { active: modalCondition === 'BELOW' }]"
              @click="modalCondition = 'BELOW'"
            >↓ 이하 하락</button>
          </div>
        </div>

        <div class="modal-actions">
          <button class="btn-cancel" @click="showModal = false">취소</button>
          <button class="btn-save" @click="saveAlert" :disabled="!modalPrice">저장</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { watchlistAPI } from '@/utils/api'

export default {
  name: 'SectionWatchlist',
  inject: { openStock: { default: null } },
  data() {
    return {
      list: [],
      loading: false,
      showModal: false,
      modalItem: null,
      modalPrice: null,
      modalCondition: 'ABOVE'
    }
  },
  mounted() {
    this.fetchList()
  },
  methods: {
    async fetchList() {
      this.loading = true
      try {
        const res = await watchlistAPI.getList()
        this.list = res.data?.data || []
      } catch (e) {
        console.error('관심종목 조회 실패:', e)
      } finally {
        this.loading = false
      }
    },
    formatPrice(val) {
      if (!val) return '-'
      return Number(val).toLocaleString('ko-KR')
    },
    goToStock(code) {
      if (this.openStock) this.openStock(code)
      else this.$router.push(`/stock/${code}`)
    },
    openAlertModal(item) {
      this.modalItem = item
      this.modalPrice = item.targetPrice || null
      this.modalCondition = item.alertCondition || 'ABOVE'
      this.showModal = true
    },
    async saveAlert() {
      if (!this.modalItem || !this.modalPrice) return
      try {
        await watchlistAPI.setAlert(this.modalItem.id, this.modalPrice, this.modalCondition)
        this.showModal = false
        this.fetchList()
      } catch (e) {
        console.error('알림 설정 실패:', e)
      }
    },
    async removeItem(item) {
      try {
        await watchlistAPI.delete(item.id)
        this.list = this.list.filter(w => w.id !== item.id)
      } catch (e) {
        console.error('삭제 실패:', e)
      }
    }
  }
}
</script>

<style scoped>
.section-card {
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 20px;
  padding: 24px;
  min-height: 300px;
}

.section-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.section-title-row h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: rgba(255,255,255,0.95);
}
.section-icon { margin-right: 6px; }
.count-badge {
  font-size: 12px;
  color: #667eea;
  background: rgba(102,126,234,0.15);
  padding: 2px 10px;
  border-radius: 10px;
  font-weight: 600;
}

.state-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  text-align: center;
}
.state-icon { font-size: 36px; margin-bottom: 12px; opacity: 0.6; }
.state-text { font-size: 14px; color: rgba(255,255,255,0.4); margin: 0; }
.state-sub { font-size: 12px; color: rgba(255,255,255,0.25); margin-top: 6px; }

.watchlist-items { display: flex; flex-direction: column; gap: 8px; }

.watch-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.watch-card:hover {
  background: rgba(255,255,255,0.07);
  border-color: rgba(255,255,255,0.12);
}

.watch-main { flex: 1; min-width: 0; }
.watch-name-row { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.watch-name { font-size: 14px; font-weight: 600; color: rgba(255,255,255,0.9); }
.watch-code { font-size: 11px; color: rgba(255,255,255,0.35); }

.watch-price-row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.price { font-size: 13px; color: rgba(255,255,255,0.7); }
.change { font-size: 12px; font-weight: 600; }
.change.up { color: #ef4444; }
.change.down { color: #3b82f6; }

.alert-row { display: flex; align-items: center; gap: 6px; }
.alert-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 6px;
  background: rgba(245,158,11,0.15);
  color: #f59e0b;
}
.alert-badge.triggered {
  background: rgba(16,185,129,0.15);
  color: #10b981;
}
.triggered-text { font-size: 10px; color: #10b981; }

.watch-actions { display: flex; gap: 4px; flex-shrink: 0; }
.action-btn {
  width: 28px;
  height: 28px;
  border: none;
  background: rgba(255,255,255,0.05);
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}
.action-btn:hover { background: rgba(255,255,255,0.12); }
.action-btn.delete { color: rgba(255,255,255,0.4); font-size: 11px; }
.action-btn.delete:hover { background: rgba(239,68,68,0.2); color: #ef4444; }

/* Modal */
.modal-overlay {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.modal-box {
  background: #1e1e2e;
  border: 1px solid rgba(255,255,255,0.15);
  border-radius: 16px;
  padding: 24px;
  width: 90%;
  max-width: 360px;
}
.modal-box h3 {
  margin: 0 0 8px 0;
  font-size: 16px;
  color: rgba(255,255,255,0.95);
}
.modal-stock {
  font-size: 13px;
  color: rgba(255,255,255,0.5);
  margin: 0 0 16px 0;
}
.form-group { margin-bottom: 14px; }
.form-group label {
  display: block;
  font-size: 12px;
  color: rgba(255,255,255,0.5);
  margin-bottom: 6px;
}
.form-input {
  width: 100%;
  padding: 10px 12px;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.12);
  border-radius: 8px;
  color: #fff;
  font-size: 14px;
  outline: none;
  box-sizing: border-box;
}
.form-input:focus {
  border-color: #667eea;
}
.condition-btns { display: flex; gap: 8px; }
.cond-btn {
  flex: 1;
  padding: 8px;
  border: 1px solid rgba(255,255,255,0.12);
  background: rgba(255,255,255,0.04);
  border-radius: 8px;
  color: rgba(255,255,255,0.6);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}
.cond-btn.active {
  border-color: #667eea;
  background: rgba(102,126,234,0.15);
  color: #8b9cf7;
}
.modal-actions { display: flex; gap: 8px; margin-top: 18px; }
.btn-cancel, .btn-save {
  flex: 1;
  padding: 10px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  border: none;
}
.btn-cancel {
  background: rgba(255,255,255,0.06);
  color: rgba(255,255,255,0.6);
}
.btn-save {
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
}
.btn-save:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

@media (max-width: 768px) {
  .section-card { padding: 16px; border-radius: 14px; }
  .section-title-row h2 { font-size: 14px; }
  .watch-card { padding: 10px; gap: 8px; }
  .watch-name { font-size: 13px; }
  .modal-box { padding: 18px; }
}
</style>
