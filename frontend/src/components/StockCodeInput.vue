<template>
  <div class="stock-code-input" :class="{ 'has-selection': !!selectedName }">
    <input
      ref="inputRef"
      type="text"
      :value="displayValue"
      :placeholder="placeholder"
      @input="onInput"
      @focus="onFocus($event)"
      @blur="onBlur"
      @keydown.down.prevent="moveCursor(1)"
      @keydown.up.prevent="moveCursor(-1)"
      @keydown.enter.prevent="onEnter"
      @keydown.escape="closeDropdown"
      class="stock-input-field"
    />
    <span v-if="selectedName" class="selection-code">{{ modelValue }}</span>
    <button v-if="selectedName" type="button" class="clear-btn" @mousedown.prevent="clear">×</button>

    <div v-if="open && (loading || results.length > 0 || showEmpty)" class="dropdown">
      <div v-if="loading" class="dropdown-loading">검색 중...</div>
      <div v-else-if="showEmpty" class="dropdown-empty">검색 결과가 없습니다</div>
      <div
        v-for="(s, idx) in results"
        :key="s.stockCode"
        class="dropdown-item"
        :class="{ active: idx === cursor }"
        @mousedown.prevent="select(s)"
        @mouseenter="cursor = idx"
      >
        <span class="item-name">{{ s.stockName }}</span>
        <span class="item-code">{{ s.stockCode }}</span>
        <span class="item-market" v-if="s.market">{{ s.market }}</span>
      </div>
    </div>
  </div>
</template>

<script>
import { stockAPI } from '../utils/api'

export default {
  name: 'StockCodeInput',
  props: {
    modelValue: { type: String, default: '' },
    placeholder: { type: String, default: '종목명 또는 종목코드' }
  },
  emits: ['update:modelValue', 'enter', 'select'],
  beforeUnmount() {
    if (this.debounceTimer) clearTimeout(this.debounceTimer)
  },
  data() {
    return {
      keyword: '',
      selectedName: '',
      results: [],
      open: false,
      loading: false,
      cursor: -1,
      debounceTimer: null,
      // 우리가 emit 한 값을 watch 에서 echo 로 무시하기 위한 가드.
      // 이게 없으면 onInput 의 emit('') → watch → keyword='' 로 사용자 입력이 지워짐.
      lastEmitted: null
    }
  },
  computed: {
    displayValue() {
      // 선택된 종목이 있고, keyword 가 비어있으면 종목명을 표시
      if (this.selectedName && !this.keyword) return this.selectedName
      return this.keyword
    },
    showEmpty() {
      // 검색 끝났는데 결과 0건 → "결과 없음" 표시
      return !this.loading && this.keyword.trim().length >= 1 && this.results.length === 0
    }
  },
  watch: {
    modelValue: {
      immediate: true,
      handler(val) {
        // 우리가 방금 emit 한 값이 prop 으로 돌아온 경우 → 무시 (echo).
        // 그렇지 않으면 onInput 직후의 emit('') 가 watch 를 발동시켜
        // 사용자가 입력 중인 keyword 를 지워버림.
        if (val === this.lastEmitted) return
        this.lastEmitted = val
        if (val) {
          this.resolveNameForCode(val)
        } else {
          this.selectedName = ''
          this.keyword = ''
        }
      }
    }
  },
  methods: {
    onInput(e) {
      const v = e.target.value
      this.keyword = v
      this.selectedName = '' // 새로 입력하면 이전 선택 무효화
      // 확정 전엔 빈값 (부모가 빈 값일 때 버튼 disable 처리되도록).
      // emit 전에 lastEmitted 를 먼저 세팅 → watch 가 이걸 echo 로 인식하고 무시.
      if (this.modelValue !== '') {
        this.lastEmitted = ''
        this.$emit('update:modelValue', '')
      }
      this.cursor = -1
      this.open = true
      this.scheduleSearch()
    },
    onFocus(e) {
      if (this.results.length > 0) this.open = true
      // 선택된 종목이 표시 중이면 텍스트 전체 선택 → 사용자가 한 글자 치면 즉시 대체됨
      if (this.selectedName) {
        this.$nextTick(() => e.target?.select?.())
      }
    },
    onBlur() {
      // mousedown.prevent 가 select/clear 처리하므로 여기는 짧은 지연 후 닫기만
      setTimeout(() => { this.open = false }, 100)
    },
    closeDropdown() {
      this.open = false
    },
    scheduleSearch() {
      clearTimeout(this.debounceTimer)
      const kw = this.keyword.trim()
      if (kw.length < 1) {
        this.results = []
        return
      }
      this.debounceTimer = setTimeout(() => this.doSearch(kw), 200)
    },
    async doSearch(kw) {
      this.loading = true
      try {
        const res = await stockAPI.searchStocks(kw)
        if (res.data?.success) {
          this.results = (res.data.data || []).slice(0, 10)
        } else {
          this.results = []
        }
      } catch (e) {
        console.error('종목 검색 실패:', e)
        this.results = []
      } finally {
        this.loading = false
      }
    },
    moveCursor(dir) {
      if (this.results.length === 0) return
      this.open = true
      const next = this.cursor + dir
      if (next < 0) this.cursor = this.results.length - 1
      else if (next >= this.results.length) this.cursor = 0
      else this.cursor = next
    },
    onEnter() {
      if (this.open && this.cursor >= 0 && this.results[this.cursor]) {
        this.select(this.results[this.cursor])
      } else if (this.results.length > 0 && this.keyword) {
        // 첫 결과 자동 선택
        this.select(this.results[0])
      } else if (this.modelValue) {
        // 이미 선택된 종목 — 그대로 enter 동작
        this.$emit('enter', this.modelValue)
      }
    },
    select(stock) {
      this.selectedName = stock.stockName
      this.keyword = ''
      this.lastEmitted = stock.stockCode
      this.$emit('update:modelValue', stock.stockCode)
      this.$emit('select', stock)
      this.open = false
      this.results = []
      this.$emit('enter', stock.stockCode)
    },
    clear() {
      this.keyword = ''
      this.selectedName = ''
      this.lastEmitted = ''
      this.$emit('update:modelValue', '')
      this.results = []
      this.$nextTick(() => this.$refs.inputRef?.focus())
    },
    async resolveNameForCode(code) {
      // 외부에서 코드만 들어왔을 때 종목명 매핑 — 검색해서 찾으면 표시 보강
      try {
        const res = await stockAPI.searchStocks(code)
        if (res.data?.success) {
          const exact = (res.data.data || []).find(s => s.stockCode === code)
          if (exact) this.selectedName = exact.stockName
        }
      } catch (_) {}
    }
  }
}
</script>

<style scoped>
.stock-code-input {
  position: relative;
  display: inline-flex;
  align-items: center;
  flex: 1;
  min-width: 200px;
}

.stock-input-field {
  flex: 1;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid rgba(255,255,255,0.15);
  border-radius: 8px;
  background: rgba(255,255,255,0.05);
  color: white;
  font-size: 14px;
  outline: none;
  transition: border-color 0.15s;
}
.stock-input-field:focus {
  border-color: rgba(99, 102, 241, 0.6);
}
.stock-input-field::placeholder {
  color: rgba(255,255,255,0.35);
}

.has-selection .stock-input-field {
  padding-right: 90px;
}

.selection-code {
  position: absolute;
  right: 36px;
  font-size: 12px;
  color: rgba(255,255,255,0.5);
  font-variant-numeric: tabular-nums;
  pointer-events: none;
}

.clear-btn {
  position: absolute;
  right: 8px;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 50%;
  background: rgba(255,255,255,0.1);
  color: rgba(255,255,255,0.7);
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
.clear-btn:hover {
  background: rgba(255,255,255,0.2);
  color: white;
}

.dropdown {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  max-height: 280px;
  overflow-y: auto;
  background: #1a1a2e;
  border: 1px solid rgba(255,255,255,0.15);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(0,0,0,0.4);
  z-index: 100;
}

.dropdown-loading,
.dropdown-empty {
  padding: 12px;
  text-align: center;
  color: rgba(255,255,255,0.4);
  font-size: 13px;
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  cursor: pointer;
  font-size: 13px;
  border-bottom: 1px solid rgba(255,255,255,0.04);
}
.dropdown-item:last-child {
  border-bottom: none;
}
.dropdown-item.active,
.dropdown-item:hover {
  background: rgba(99, 102, 241, 0.18);
}

.item-name {
  color: rgba(255,255,255,0.92);
  font-weight: 500;
  flex: 1;
}

.item-code {
  color: rgba(255,255,255,0.45);
  font-variant-numeric: tabular-nums;
  font-size: 12px;
}

.item-market {
  color: rgba(255,255,255,0.35);
  font-size: 11px;
  padding: 2px 6px;
  background: rgba(255,255,255,0.06);
  border-radius: 4px;
}
</style>
