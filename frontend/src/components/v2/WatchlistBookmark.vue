<template>
  <button
    class="bookmark-btn"
    :class="{ active: bookmarked }"
    @click.stop="toggle"
    :title="bookmarked ? '관심종목 해제' : '관심종목 추가'"
  >
    <svg width="16" height="16" viewBox="0 0 24 24" :fill="bookmarked ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2">
      <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>
    </svg>
  </button>
</template>

<script>
import { watchlistAPI } from '@/utils/api'

export default {
  name: 'WatchlistBookmark',
  props: {
    stockCode: { type: String, required: true },
    stockName: { type: String, default: '' }
  },
  data() {
    return {
      bookmarked: false,
      loading: false
    }
  },
  mounted() {
    this.checkBookmark()
  },
  watch: {
    stockCode() {
      this.checkBookmark()
    }
  },
  methods: {
    async checkBookmark() {
      try {
        const res = await watchlistAPI.checkBookmark(this.stockCode)
        this.bookmarked = res.data?.data === true
      } catch {
        this.bookmarked = false
      }
    },
    async toggle() {
      if (this.loading) return
      this.loading = true
      try {
        if (this.bookmarked) {
          // 삭제를 위해 목록에서 찾기
          const listRes = await watchlistAPI.getList()
          const list = listRes.data?.data || []
          const item = list.find(w => w.stockCode === this.stockCode)
          if (item) {
            await watchlistAPI.delete(item.id)
            this.bookmarked = false
          }
        } else {
          await watchlistAPI.add(this.stockCode, this.stockName)
          this.bookmarked = true
        }
      } catch (e) {
        console.error('북마크 토글 실패:', e)
      } finally {
        this.loading = false
      }
    }
  }
}
</script>

<style scoped>
.bookmark-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: rgba(255,255,255,0.3);
  cursor: pointer;
  border-radius: 6px;
  transition: all 0.2s;
  padding: 0;
  flex-shrink: 0;
}
.bookmark-btn:hover {
  color: #f59e0b;
  background: rgba(245,158,11,0.1);
}
.bookmark-btn.active {
  color: #f59e0b;
}
</style>
