<template>
  <div class="safety-widget" :class="{ 'kill-active': status.killSwitchEnabled }">
    <div class="safety-head">
      <div class="status-badge" :class="status.killSwitchEnabled ? 'active' : 'normal'">
        <span class="status-dot"></span>
        {{ status.killSwitchEnabled ? '비상 정지 ON' : '매매 정상' }}
      </div>
      <div class="safety-actions">
        <button v-if="!status.killSwitchEnabled"
                class="btn-kill"
                :disabled="acting"
                @click="confirmKill">
          🛑 비상 정지
        </button>
        <button v-else
                class="btn-resume"
                :disabled="acting"
                @click="confirmResume">
          ▶ 매매 재개
        </button>
        <button class="btn-refresh" :disabled="loading" @click="loadStatus" title="갱신">
          {{ loading ? '⏳' : '↻' }}
        </button>
      </div>
    </div>

    <!-- 비상 정지 사유 -->
    <div v-if="status.killSwitchEnabled" class="kill-reason">
      <span class="reason-label">정지 사유</span>
      <span class="reason-text">{{ status.killSwitchReason || '-' }}</span>
      <span v-if="status.killSwitchTriggeredBy" class="reason-by">
        by {{ status.killSwitchTriggeredBy }} · {{ formatTime(status.killSwitchChangedAt) }}
      </span>
    </div>

    <!-- 일일 매수 한도 progress -->
    <div v-if="status.dailyBuyLimitKrw" class="limit-row">
      <div class="limit-meta">
        <span>일일 매수 한도</span>
        <span class="limit-amount" :class="limitClass">
          {{ formatKrw(status.todayBuyAmountKrw) }} / {{ formatKrw(status.dailyBuyLimitKrw) }}
        </span>
      </div>
      <div class="limit-track">
        <div class="limit-fill" :class="limitClass" :style="{ width: limitPercent + '%' }"></div>
      </div>
      <div class="limit-meta-sub">
        <span>잔여 {{ formatKrw(status.remainingKrw) }}</span>
        <span v-if="overAlertThreshold" class="alert-text">⚠️ 경고 임계점 초과</span>
      </div>
    </div>
  </div>
</template>

<script>
import { tradingSafetyAPI } from '../../utils/api'

export default {
  name: 'TradingSafetyWidget',
  data() {
    return {
      status: {
        killSwitchEnabled: false,
        killSwitchReason: null,
        killSwitchTriggeredBy: null,
        killSwitchChangedAt: null,
        dailyBuyLimitKrw: 0,
        alertThresholdKrw: 0,
        todayBuyAmountKrw: 0,
        remainingKrw: 0
      },
      loading: false,
      acting: false,
      _timer: null
    }
  },
  computed: {
    limitPercent() {
      const limit = Number(this.status.dailyBuyLimitKrw) || 0
      const used = Number(this.status.todayBuyAmountKrw) || 0
      if (limit === 0) return 0
      return Math.min(100, Math.round((used / limit) * 100))
    },
    overAlertThreshold() {
      const used = Number(this.status.todayBuyAmountKrw) || 0
      const alertTh = Number(this.status.alertThresholdKrw) || 0
      return alertTh > 0 && used >= alertTh
    },
    limitClass() {
      const p = this.limitPercent
      if (p >= 90) return 'level-danger'
      if (p >= 70) return 'level-warning'
      return 'level-normal'
    }
  },
  mounted() {
    this.loadStatus()
    // 30초마다 갱신
    this._timer = setInterval(this.loadStatus, 30000)
  },
  beforeUnmount() {
    if (this._timer) clearInterval(this._timer)
  },
  methods: {
    async loadStatus() {
      this.loading = true
      try {
        const res = await tradingSafetyAPI.getStatus()
        const body = res?.data || res
        if (body?.success && body?.data) {
          this.status = { ...this.status, ...body.data }
        }
      } catch (e) {
        console.warn('[Safety] 상태 조회 실패', e?.message)
      } finally {
        this.loading = false
      }
    },
    async confirmKill() {
      const reason = window.prompt('비상 정지 사유 (선택):', '수동 비상 정지')
      if (reason === null) return
      this.acting = true
      try {
        await tradingSafetyAPI.enableKillSwitch(reason || '수동 비상 정지')
        await this.loadStatus()
      } catch (e) {
        alert('비상 정지 실패: ' + (e?.message || ''))
      } finally {
        this.acting = false
      }
    },
    async confirmResume() {
      if (!window.confirm('매매를 재개하시겠습니까?\n비상 정지가 해제됩니다.')) return
      this.acting = true
      try {
        await tradingSafetyAPI.disableKillSwitch('수동 해제')
        await this.loadStatus()
      } catch (e) {
        alert('해제 실패: ' + (e?.message || ''))
      } finally {
        this.acting = false
      }
    },
    formatKrw(v) {
      const n = Number(v) || 0
      if (Math.abs(n) >= 100_000_000) return (n / 100_000_000).toFixed(2) + '억'
      if (Math.abs(n) >= 10_000) return (n / 10_000).toFixed(0) + '만'
      return n.toLocaleString('ko-KR')
    },
    formatTime(dt) {
      if (!dt) return ''
      try {
        const d = new Date(dt)
        return d.toLocaleString('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
      } catch { return '' }
    }
  }
}
</script>

<style scoped>
.safety-widget {
  background: linear-gradient(135deg, rgba(34,197,94,0.05), rgba(102,126,234,0.05));
  border: 1px solid rgba(34,197,94,0.2);
  border-radius: 12px;
  padding: 14px 18px;
  margin-bottom: 16px;
  color: #fff;
}
.safety-widget.kill-active {
  background: linear-gradient(135deg, rgba(239,68,68,0.12), rgba(239,68,68,0.04));
  border-color: rgba(239,68,68,0.5);
  animation: alert-pulse 2s ease-in-out infinite;
}
@keyframes alert-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(239,68,68,0.4); }
  50% { box-shadow: 0 0 0 6px rgba(239,68,68,0.15); }
}

.safety-head {
  display: flex; justify-content: space-between; align-items: center;
  flex-wrap: wrap; gap: 10px;
}
.status-badge {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 6px 12px; border-radius: 20px;
  font-size: 13px; font-weight: 700;
}
.status-badge.normal {
  background: rgba(34,197,94,0.15);
  color: #22c55e;
  border: 1px solid rgba(34,197,94,0.3);
}
.status-badge.active {
  background: rgba(239,68,68,0.18);
  color: #ef4444;
  border: 1px solid rgba(239,68,68,0.4);
}
.status-dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: currentColor;
  animation: dot-pulse 1.5s infinite;
}
@keyframes dot-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.safety-actions { display: flex; gap: 6px; }
.btn-kill, .btn-resume, .btn-refresh {
  border: none;
  padding: 8px 14px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition: opacity 0.2s;
}
.btn-kill {
  background: linear-gradient(135deg, #ef4444, #dc2626);
  color: #fff;
}
.btn-resume {
  background: linear-gradient(135deg, #22c55e, #16a34a);
  color: #fff;
}
.btn-refresh {
  background: rgba(255,255,255,0.08);
  color: rgba(255,255,255,0.7);
  width: 34px; padding: 0;
}
.btn-kill:hover:not(:disabled),
.btn-resume:hover:not(:disabled) { opacity: 0.9; }
.btn-refresh:hover:not(:disabled) { background: rgba(255,255,255,0.14); color: #fff; }
.btn-kill:disabled, .btn-resume:disabled, .btn-refresh:disabled { opacity: 0.5; cursor: wait; }

.kill-reason {
  margin-top: 10px;
  padding: 8px 12px;
  background: rgba(239,68,68,0.08);
  border-radius: 8px;
  font-size: 12px;
  color: rgba(255,255,255,0.85);
  display: flex; gap: 8px; flex-wrap: wrap; align-items: baseline;
}
.reason-label { color: #ef4444; font-weight: 700; }
.reason-text { flex: 1; min-width: 120px; }
.reason-by { font-size: 10.5px; color: rgba(255,255,255,0.5); font-family: monospace; }

.limit-row { margin-top: 12px; }
.limit-meta {
  display: flex; justify-content: space-between;
  font-size: 12px;
  color: rgba(255,255,255,0.7);
  margin-bottom: 6px;
}
.limit-amount { font-family: monospace; font-weight: 700; }
.limit-amount.level-normal { color: rgba(255,255,255,0.85); }
.limit-amount.level-warning { color: #fbbf24; }
.limit-amount.level-danger { color: #ef4444; }

.limit-track {
  height: 6px;
  background: rgba(255,255,255,0.08);
  border-radius: 3px;
  overflow: hidden;
}
.limit-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.4s;
}
.limit-fill.level-normal {
  background: linear-gradient(90deg, #22c55e, #4ade80);
}
.limit-fill.level-warning {
  background: linear-gradient(90deg, #f59e0b, #fbbf24);
}
.limit-fill.level-danger {
  background: linear-gradient(90deg, #ef4444, #f87171);
}

.limit-meta-sub {
  display: flex; justify-content: space-between;
  font-size: 11px;
  margin-top: 4px;
  color: rgba(255,255,255,0.55);
}
.alert-text { color: #fbbf24; font-weight: 600; }

@media (max-width: 600px) {
  .safety-widget { padding: 12px 14px; }
  .status-badge { font-size: 12px; padding: 5px 10px; }
  .btn-kill, .btn-resume { padding: 6px 10px; font-size: 12px; }
  .btn-refresh { width: 30px; }
}
</style>
