<template>
  <div class="section-card">
    <div class="section-title-row">
      <h2><span class="section-icon">📋</span> 실적공시</h2>
      <button class="refresh-btn" @click="collectNow" :disabled="collecting">
        {{ collecting ? '수집중...' : '🔄 수집' }}
      </button>
    </div>

    <!-- 내부 탭 -->
    <div class="inner-tabs">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        :class="['tab-btn', { active: activeTab === tab.key }]"
        @click="switchTab(tab.key)"
      >{{ tab.label }}</button>
    </div>

    <!-- ═══ 탭1: 최근 공시 목록 ═══ -->
    <div v-if="activeTab === 'recent'">
      <!-- 검색 -->
      <div class="search-bar">
        <input
          v-model="searchQuery"
          type="text"
          placeholder="종목명 검색..."
          class="search-input"
          @keyup.enter="doSearch"
        />
        <button class="search-btn" @click="doSearch">검색</button>
        <select v-model="filterType" class="filter-select" @change="applyFilter">
          <option value="ALL">전체</option>
          <option value="PRELIMINARY">잠정실적</option>
          <option value="QUARTERLY">분기보고서</option>
          <option value="SEMI_ANNUAL">반기보고서</option>
          <option value="ANNUAL">사업보고서</option>
        </select>
      </div>

      <div v-if="loading" class="loading-area">
        <div class="spinner"></div>
        <span>실적공시 불러오는 중...</span>
      </div>

      <div v-else-if="filteredList.length === 0" class="empty-msg">
        실적공시 데이터가 없습니다. 수집 버튼을 눌러주세요.
      </div>

      <div v-else class="disclosure-list">
        <div
          v-for="(item, i) in filteredList.slice(0, displayCount)"
          :key="'ed-' + i"
          class="disclosure-row"
          @click="openDart(item.rceptNo)"
        >
          <div class="d-left">
            <span class="d-badge" :class="badgeClass(item.disclosureType)">
              {{ typeLabel(item.disclosureType) }}
            </span>
            <span class="d-corp">{{ item.corpName }}</span>
          </div>
          <div class="d-right">
            <span class="d-report">{{ truncate(item.reportNm, 40) }}</span>
            <span class="d-date">{{ formatDate(item.rceptDt) }}</span>
          </div>
        </div>

        <button
          v-if="filteredList.length > displayCount"
          class="load-more-btn"
          @click="displayCount += 20"
        >
          더 보기 ({{ filteredList.length - displayCount }}건 남음)
        </button>
      </div>
    </div>

    <!-- ═══ 탭2: 캘린더 ═══ -->
    <div v-if="activeTab === 'calendar'">
      <div class="calendar-header">
        <button class="cal-nav" @click="prevMonth">&lt;</button>
        <span class="cal-title">{{ calYear }}년 {{ calMonth }}월</span>
        <button class="cal-nav" @click="nextMonth">&gt;</button>
      </div>

      <div v-if="calLoading" class="loading-area">
        <div class="spinner"></div>
      </div>

      <div v-else class="calendar-grid">
        <div class="cal-weekday" v-for="d in weekdays" :key="d">{{ d }}</div>
        <div
          v-for="(cell, i) in calendarCells"
          :key="'cal-' + i"
          :class="['cal-cell', { 'has-data': cell.count > 0, 'today': cell.isToday, 'other-month': !cell.currentMonth }]"
          @click="cell.count > 0 && showDayDetail(cell)"
        >
          <span class="cal-day">{{ cell.day }}</span>
          <span v-if="cell.count > 0" class="cal-count">{{ cell.count }}건</span>
        </div>
      </div>

      <!-- 선택된 날짜의 공시 목록 -->
      <div v-if="selectedDay" class="day-detail">
        <h4>{{ selectedDay.dateStr }} 공시 ({{ selectedDay.items.length }}건)</h4>
        <div
          v-for="(item, i) in selectedDay.items"
          :key="'dd-' + i"
          class="disclosure-row compact"
          @click="openDart(item.rceptNo)"
        >
          <span class="d-badge" :class="badgeClass(item.disclosureType)">
            {{ typeLabel(item.disclosureType) }}
          </span>
          <span class="d-corp">{{ item.corpName }}</span>
          <span class="d-report">{{ truncate(item.reportNm, 30) }}</span>
        </div>
      </div>
    </div>

    <!-- ═══ 탭3: 관심종목 ═══ -->
    <div v-if="activeTab === 'watchlist'">
      <div v-if="wlLoading" class="loading-area">
        <div class="spinner"></div>
      </div>

      <div v-else-if="watchlistData.length === 0" class="empty-msg">
        관심종목의 실적공시가 없습니다.
      </div>

      <div v-else class="disclosure-list">
        <div
          v-for="(item, i) in watchlistData"
          :key="'wl-' + i"
          class="disclosure-row highlight"
          @click="openDart(item.rceptNo)"
        >
          <div class="d-left">
            <span class="d-badge" :class="badgeClass(item.disclosureType)">
              {{ typeLabel(item.disclosureType) }}
            </span>
            <span class="d-corp">{{ item.corpName }}</span>
          </div>
          <div class="d-right">
            <span class="d-report">{{ truncate(item.reportNm, 40) }}</span>
            <span class="d-date">{{ formatDate(item.rceptDt) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 통계 요약 -->
    <div v-if="stats && Object.keys(stats).length > 0" class="stats-bar">
      <span v-for="(count, type) in stats" :key="type" class="stat-item">
        <span class="stat-badge" :class="badgeClass(type)">{{ typeLabel(type) }}</span>
        {{ count }}건
      </span>
    </div>
  </div>
</template>

<script>
import { earningsAPI } from '../../utils/api'

export default {
  name: 'SectionEarnings',
  data() {
    return {
      activeTab: 'recent',
      tabs: [
        { key: 'recent', label: '최근 공시' },
        { key: 'calendar', label: '캘린더' },
        { key: 'watchlist', label: '관심종목' }
      ],
      // 최근 공시
      loading: false,
      disclosures: [],
      searchQuery: '',
      searchResults: null,
      filterType: 'ALL',
      displayCount: 20,
      // 캘린더
      calLoading: false,
      calYear: new Date().getFullYear(),
      calMonth: new Date().getMonth() + 1,
      calendarData: {},
      selectedDay: null,
      weekdays: ['일', '월', '화', '수', '목', '금', '토'],
      // 관심종목
      wlLoading: false,
      watchlistData: [],
      // 통계
      stats: {},
      // 수집
      collecting: false
    }
  },
  computed: {
    filteredList() {
      let list = this.searchResults || this.disclosures
      if (this.filterType !== 'ALL') {
        list = list.filter(d => d.disclosureType === this.filterType)
      }
      return list
    },
    calendarCells() {
      const cells = []
      const firstDay = new Date(this.calYear, this.calMonth - 1, 1)
      const lastDay = new Date(this.calYear, this.calMonth, 0)
      const startDow = firstDay.getDay()
      const today = new Date()
      const todayStr = `${today.getFullYear()}${String(today.getMonth() + 1).padStart(2, '0')}${String(today.getDate()).padStart(2, '0')}`

      // 이전 달 빈 칸
      const prevLastDay = new Date(this.calYear, this.calMonth - 1, 0)
      for (let i = startDow - 1; i >= 0; i--) {
        cells.push({ day: prevLastDay.getDate() - i, currentMonth: false, count: 0, isToday: false, items: [] })
      }

      // 현재 달
      for (let d = 1; d <= lastDay.getDate(); d++) {
        const dateKey = `${this.calYear}${String(this.calMonth).padStart(2, '0')}${String(d).padStart(2, '0')}`
        const items = this.calendarData[dateKey] || []
        cells.push({
          day: d,
          currentMonth: true,
          count: items.length,
          isToday: dateKey === todayStr,
          items: items,
          dateStr: `${this.calMonth}/${d}`
        })
      }

      // 다음 달 빈 칸 (6주 맞춤)
      const remaining = 42 - cells.length
      for (let i = 1; i <= remaining; i++) {
        cells.push({ day: i, currentMonth: false, count: 0, isToday: false, items: [] })
      }

      return cells
    }
  },
  mounted() {
    this.loadRecent()
    this.loadStats()
  },
  methods: {
    switchTab(key) {
      this.activeTab = key
      if (key === 'calendar' && Object.keys(this.calendarData).length === 0) {
        this.loadCalendar()
      }
      if (key === 'watchlist' && this.watchlistData.length === 0) {
        this.loadWatchlist()
      }
    },

    async loadRecent() {
      this.loading = true
      try {
        const res = await earningsAPI.getRecent(3)
        if (res.data.success) {
          this.disclosures = res.data.data || []
        }
      } catch (e) {
        console.error('실적공시 로딩 실패:', e)
      } finally {
        this.loading = false
      }
    },

    async loadCalendar() {
      this.calLoading = true
      this.selectedDay = null
      try {
        const res = await earningsAPI.getCalendar(this.calYear, this.calMonth)
        if (res.data.success) {
          this.calendarData = res.data.data || {}
        }
      } catch (e) {
        console.error('캘린더 로딩 실패:', e)
      } finally {
        this.calLoading = false
      }
    },

    async loadWatchlist() {
      this.wlLoading = true
      try {
        const res = await earningsAPI.getWatchlist()
        if (res.data.success) {
          this.watchlistData = res.data.data || []
        }
      } catch (e) {
        console.error('관심종목 공시 로딩 실패:', e)
      } finally {
        this.wlLoading = false
      }
    },

    async loadStats() {
      try {
        const res = await earningsAPI.getStats(3)
        if (res.data.success) {
          this.stats = res.data.data || {}
        }
      } catch (e) {
        console.error('통계 로딩 실패:', e)
      }
    },

    async doSearch() {
      if (!this.searchQuery.trim()) {
        this.searchResults = null
        return
      }
      this.loading = true
      try {
        const res = await earningsAPI.search(this.searchQuery.trim())
        if (res.data.success) {
          this.searchResults = res.data.data || []
        }
      } catch (e) {
        console.error('검색 실패:', e)
      } finally {
        this.loading = false
      }
    },

    applyFilter() {
      this.displayCount = 20
    },

    async collectNow() {
      this.collecting = true
      try {
        const res = await earningsAPI.collect()
        if (res.data.success) {
          await this.loadRecent()
          await this.loadStats()
          if (this.activeTab === 'calendar') await this.loadCalendar()
        }
      } catch (e) {
        console.error('수집 실패:', e)
      } finally {
        this.collecting = false
      }
    },

    prevMonth() {
      if (this.calMonth === 1) {
        this.calMonth = 12
        this.calYear--
      } else {
        this.calMonth--
      }
      this.loadCalendar()
    },

    nextMonth() {
      if (this.calMonth === 12) {
        this.calMonth = 1
        this.calYear++
      } else {
        this.calMonth++
      }
      this.loadCalendar()
    },

    showDayDetail(cell) {
      this.selectedDay = cell
    },

    openDart(rceptNo) {
      if (rceptNo) {
        window.open(`https://dart.fss.or.kr/dsaf001/main.do?rcpNo=${rceptNo}`, '_blank')
      }
    },

    typeLabel(type) {
      const labels = {
        PRELIMINARY: '잠정',
        QUARTERLY: '분기',
        SEMI_ANNUAL: '반기',
        ANNUAL: '사업',
        OTHER: '기타'
      }
      return labels[type] || type
    },

    badgeClass(type) {
      return {
        PRELIMINARY: 'badge-preliminary',
        QUARTERLY: 'badge-quarterly',
        SEMI_ANNUAL: 'badge-semi',
        ANNUAL: 'badge-annual'
      }[type] || 'badge-other'
    },

    formatDate(dt) {
      if (!dt || dt.length < 8) return dt
      return `${dt.substring(4, 6)}.${dt.substring(6, 8)}`
    },

    truncate(str, len) {
      if (!str) return ''
      return str.length > len ? str.substring(0, len) + '...' : str
    }
  }
}
</script>

<style scoped>
.section-card {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 20px;
  padding: 20px;
}

.section-title-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.section-title-row h2 {
  font-size: 18px;
  color: #e0e0e0;
  margin: 0;
}

.section-icon {
  margin-right: 8px;
}

.refresh-btn {
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  color: #aaa;
  padding: 6px 14px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s;
}

.refresh-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.15);
  color: #fff;
}

.refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 탭 */
.inner-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 10px;
  padding: 3px;
}

.tab-btn {
  flex: 1;
  padding: 8px 12px;
  background: transparent;
  border: none;
  color: #888;
  font-size: 13px;
  cursor: pointer;
  border-radius: 8px;
  transition: all 0.2s;
}

.tab-btn.active {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

/* 검색 */
.search-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.search-input {
  flex: 1;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 8px;
  padding: 8px 12px;
  color: #e0e0e0;
  font-size: 13px;
  outline: none;
}

.search-input::placeholder {
  color: #666;
}

.search-input:focus {
  border-color: rgba(100, 150, 255, 0.5);
}

.search-btn {
  background: rgba(100, 150, 255, 0.2);
  border: 1px solid rgba(100, 150, 255, 0.3);
  color: #8ab4ff;
  padding: 8px 16px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
}

.search-btn:hover {
  background: rgba(100, 150, 255, 0.3);
}

.filter-select {
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 8px;
  padding: 8px 12px;
  color: #e0e0e0;
  font-size: 13px;
  outline: none;
  cursor: pointer;
}

/* 공시 목록 */
.disclosure-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.disclosure-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}

.disclosure-row:hover {
  background: rgba(255, 255, 255, 0.06);
}

.disclosure-row.highlight {
  border-left: 3px solid #ffc107;
  padding-left: 9px;
}

.disclosure-row.compact {
  padding: 6px 10px;
  gap: 8px;
  justify-content: flex-start;
}

.d-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.d-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.d-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 600;
  white-space: nowrap;
}

.badge-preliminary {
  background: rgba(255, 107, 107, 0.2);
  color: #ff6b6b;
}

.badge-quarterly {
  background: rgba(100, 150, 255, 0.2);
  color: #8ab4ff;
}

.badge-semi {
  background: rgba(255, 193, 7, 0.2);
  color: #ffc107;
}

.badge-annual {
  background: rgba(76, 175, 80, 0.2);
  color: #66bb6a;
}

.badge-other {
  background: rgba(255, 255, 255, 0.1);
  color: #999;
}

.d-corp {
  font-size: 14px;
  color: #e0e0e0;
  font-weight: 500;
  white-space: nowrap;
}

.d-report {
  font-size: 12px;
  color: #888;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 280px;
}

.d-date {
  font-size: 12px;
  color: #666;
  white-space: nowrap;
}

/* 더 보기 */
.load-more-btn {
  margin-top: 8px;
  padding: 10px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px dashed rgba(255, 255, 255, 0.15);
  border-radius: 8px;
  color: #888;
  font-size: 13px;
  cursor: pointer;
  text-align: center;
  width: 100%;
}

.load-more-btn:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #aaa;
}

/* 캘린더 */
.calendar-header {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 20px;
  margin-bottom: 16px;
}

.cal-title {
  font-size: 16px;
  font-weight: 600;
  color: #e0e0e0;
}

.cal-nav {
  background: rgba(255, 255, 255, 0.1);
  border: none;
  color: #ccc;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cal-nav:hover {
  background: rgba(255, 255, 255, 0.15);
}

.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 2px;
}

.cal-weekday {
  text-align: center;
  font-size: 12px;
  color: #666;
  padding: 6px 0;
  font-weight: 600;
}

.cal-cell {
  aspect-ratio: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  font-size: 13px;
  color: #aaa;
  position: relative;
  cursor: default;
  min-height: 44px;
}

.cal-cell.other-month {
  color: #444;
}

.cal-cell.today {
  background: rgba(100, 150, 255, 0.15);
  border: 1px solid rgba(100, 150, 255, 0.3);
}

.cal-cell.has-data {
  cursor: pointer;
  background: rgba(255, 193, 7, 0.1);
}

.cal-cell.has-data:hover {
  background: rgba(255, 193, 7, 0.2);
}

.cal-day {
  font-size: 13px;
}

.cal-count {
  font-size: 10px;
  color: #ffc107;
  font-weight: 600;
}

.day-detail {
  margin-top: 16px;
  padding: 12px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 12px;
}

.day-detail h4 {
  font-size: 14px;
  color: #e0e0e0;
  margin: 0 0 10px 0;
}

/* 통계 바 */
.stats-bar {
  display: flex;
  gap: 12px;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  flex-wrap: wrap;
}

.stat-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #888;
}

.stat-badge {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 600;
}

/* 로딩 & 빈 상태 */
.loading-area {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 40px 0;
  color: #888;
  font-size: 13px;
}

.spinner {
  width: 20px;
  height: 20px;
  border: 2px solid rgba(255, 255, 255, 0.1);
  border-top-color: #8ab4ff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.empty-msg {
  text-align: center;
  padding: 30px 0;
  color: #666;
  font-size: 13px;
}

/* 반응형 */
@media (max-width: 768px) {
  .search-bar {
    flex-wrap: wrap;
  }

  .search-input {
    width: 100%;
  }

  .d-right {
    flex-direction: column;
    align-items: flex-end;
    gap: 2px;
  }

  .d-report {
    max-width: 150px;
  }

  .disclosure-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }
}
</style>
