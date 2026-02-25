<template>
  <div v-if="visible" class="search-modal-overlay" @click.self="$emit('close')">
    <div class="search-modal">
      <div class="search-modal-header">
        <h3>종목 검색</h3>
        <button class="close-btn" @click="$emit('close')">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>
      <div class="search-input-wrapper">
        <svg class="search-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
        </svg>
        <input
          ref="searchInput"
          v-model="keyword"
          type="text"
          placeholder="종목명 또는 종목코드 입력..."
          @input="onSearch"
          @keydown.enter="selectFirst"
          @keydown.escape="$emit('close')"
        />
      </div>
      <div class="search-results">
        <div v-if="loading" class="search-loading">검색 중...</div>
        <div v-else-if="results.length === 0 && keyword.length >= 2" class="search-empty">
          검색 결과가 없습니다.
        </div>
        <div
          v-for="stock in results"
          :key="stock.stockCode"
          class="search-result-item"
          @click="selectStock(stock)"
        >
          <div class="result-info">
            <span class="result-name">{{ stock.stockName }}</span>
            <span class="result-code">{{ stock.stockCode }}</span>
          </div>
          <div class="result-market">{{ stock.market || '' }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { stockAPI } from '../../utils/api'

export default {
  name: 'StockSearchModal',
  props: {
    visible: { type: Boolean, default: false }
  },
  emits: ['close', 'select'],
  data() {
    return {
      keyword: '',
      results: [],
      loading: false,
      debounceTimer: null
    }
  },
  watch: {
    visible(val) {
      if (val) {
        this.keyword = ''
        this.results = []
        this.$nextTick(() => {
          this.$refs.searchInput?.focus()
        })
      }
    }
  },
  beforeUnmount() {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer)
    }
  },
  methods: {
    onSearch() {
      clearTimeout(this.debounceTimer)
      if (this.keyword.length < 2) {
        this.results = []
        return
      }
      this.debounceTimer = setTimeout(() => this.doSearch(), 300)
    },
    async doSearch() {
      this.loading = true
      try {
        const res = await stockAPI.searchStocks(this.keyword)
        if (res.data.success) {
          this.results = res.data.data || []
        }
      } catch (e) {
        console.error('종목 검색 실패:', e)
        this.results = []
      } finally {
        this.loading = false
      }
    },
    selectStock(stock) {
      this.$emit('select', stock)
      this.$emit('close')
    },
    selectFirst() {
      if (this.results.length > 0) {
        this.selectStock(this.results[0])
      }
    }
  }
}
</script>

<style scoped>
.search-modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 120px;
  z-index: 1000;
}

.search-modal {
  background: #1a1a2e;
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 16px;
  width: 100%;
  max-width: 520px;
  max-height: 500px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
}

.search-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}

.search-modal-header h3 {
  margin: 0;
  color: rgba(255,255,255,0.9);
  font-size: 16px;
}

.close-btn {
  background: none;
  border: none;
  color: rgba(255,255,255,0.5);
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  transition: all 0.2s;
}
.close-btn:hover {
  color: white;
  background: rgba(255,255,255,0.1);
}

.search-input-wrapper {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 20px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}

.search-icon {
  color: rgba(255,255,255,0.4);
  flex-shrink: 0;
}

.search-input-wrapper input {
  flex: 1;
  background: none;
  border: none;
  outline: none;
  color: white;
  font-size: 15px;
}
.search-input-wrapper input::placeholder {
  color: rgba(255,255,255,0.3);
}

.search-results {
  max-height: 360px;
  overflow-y: auto;
}

.search-loading,
.search-empty {
  padding: 24px;
  text-align: center;
  color: rgba(255,255,255,0.4);
  font-size: 14px;
}

.search-result-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  cursor: pointer;
  transition: background 0.2s;
}
.search-result-item:hover {
  background: rgba(255,255,255,0.06);
}

.result-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.result-name {
  color: rgba(255,255,255,0.9);
  font-size: 14px;
  font-weight: 500;
}

.result-code {
  color: rgba(255,255,255,0.4);
  font-size: 12px;
}

.result-market {
  color: rgba(255,255,255,0.3);
  font-size: 12px;
}
</style>
