<template>
  <div class="safety-widget" :class="{ 'kill-active': known && status.killSwitchEnabled }">
    <div class="safety-head">
      <!-- 상태를 못 받았으면 '매매 정상'이 아니다(2026-10-03) — 예전엔 초기값 false 가 그대로 '매매 정상'(초록)으로 보여
           비상 정지가 켜져 있어도 조회가 실패하면 정상처럼 보였다 -->
      <div class="status-badge" :class="badgeClass">
        <span class="status-dot"></span>
        {{ badgeText }}
      </div>
      <div class="safety-actions">
        <button v-if="!known || !status.killSwitchEnabled"
                class="btn-kill"
                :disabled="acting"
                @click="confirmKill">
          🛑 실전 비상 정지
        </button>
        <button v-else-if="known"
                class="btn-resume"
                :disabled="acting"
                @click="confirmResume">
          ▶ 실전 매매 재개
        </button>
        <button class="btn-refresh" :disabled="loading" @click="loadStatus" title="갱신">
          {{ loading ? '⏳' : '↻' }}
        </button>
      </div>
    </div>

    <!-- 적용 범위 — 비상 정지·일일 한도는 실전(KIS) 주문 경로(RealTradeService)만 막는다. 모의 탭 위에 '매매 정상'·'비상 정지'로만
         있어 모의 봇까지 멈추는 버튼처럼 보였다(2026-10-06 화면 점검) -->
    <p class="safety-scope">실전(KIS) 주문만 막습니다 — 모의 봇은 봇 카드의 '봇 중지'로 멈춥니다.</p>

    <!-- 비상 정지 사유 -->
    <div v-if="known && status.killSwitchEnabled" class="kill-reason">
      <span class="reason-label">정지 사유</span>
      <span class="reason-text">{{ status.killSwitchReason || '-' }}</span>
      <span v-if="status.killSwitchTriggeredBy" class="reason-by">
        by {{ status.killSwitchTriggeredBy }} · {{ formatTime(status.killSwitchChangedAt) }}
      </span>
    </div>

    <!-- 일일 매수 한도 progress -->
    <div v-if="known && status.dailyBuyLimitKrw" class="limit-row">
      <div class="limit-meta">
        <span>실전 일일 매수 한도</span>
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
import { toast } from '../../utils/toast'

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
      known: false,       // 상태를 한 번이라도 받았는가(마지막 조회 성공)
      loadFailed: false,  // 마지막 조회가 실패했는가
      loading: false,
      acting: false,
      _timer: null
    }
  },
  computed: {
    badgeText() {
      if (!this.known) return this.loadFailed ? '상태 확인 실패' : '상태 확인 중'
      return this.status.killSwitchEnabled ? '실전 비상 정지 ON' : '실전 매매 정상'
    },
    badgeClass() {
      if (!this.known) return 'unknown'
      return this.status.killSwitchEnabled ? 'active' : 'normal'
    },
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
          this.known = true
          this.loadFailed = false
        } else {
          this.markFailed()
        }
      } catch (e) {
        console.warn('[Safety] 상태 조회 실패', e?.message)
        this.markFailed()
      } finally {
        this.loading = false
      }
    },
    // 조회 실패 — 마지막으로 받은 상태를 현재처럼 보이지 않게 모름으로 둔다
    markFailed() {
      this.known = false
      this.loadFailed = true
    },
    async confirmKill() {
      const reason = window.prompt('실전 비상 정지 사유 (선택) — 실전(KIS) 주문만 막습니다:', '수동 비상 정지')
      if (reason === null) return
      this.acting = true
      try {
        await tradingSafetyAPI.enableKillSwitch(reason || '수동 비상 정지')
        await this.loadStatus()
      } catch (e) {
        toast.error('비상 정지 실패: ' + (e?.message || ''))
      } finally {
        this.acting = false
      }
    },
    async confirmResume() {
      if (!window.confirm('실전 매매를 재개하시겠습니까?\n실전 비상 정지가 해제됩니다.')) return
      this.acting = true
      try {
        await tradingSafetyAPI.disableKillSwitch('수동 해제')
        await this.loadStatus()
      } catch (e) {
        toast.error('해제 실패: ' + (e?.message || ''))
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
.status-badge.unknown {
  background: rgba(234,179,8,0.15);
  color: #eab308;
  border: 1px solid rgba(234,179,8,0.35);
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
.safety-scope {
  margin: 8px 0 0;
  font-size: 12px;
  color: rgba(255,255,255,0.7);
}
.btn-kill, .btn-resume, .btn-refresh {
  border: none;
  padding: 8px 14px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition: opacity 0.2s;
}
/* 흰 글자 대비: #ef4444 위 3.76 → 한 단계 어두운 빨강부터. 밝은 초록 위 흰 글자는 2.28 이라
   밝은 액센트 위 어두운 글자 규약(--text-on-accent)으로 뒤집는다(2026-10-01). */
.btn-kill {
  background: linear-gradient(135deg, #dc2626, #b91c1c);
  color: #fff;
}
.btn-resume {
  background: linear-gradient(135deg, #22c55e, #16a34a);
  color: var(--text-on-accent, #0b0e13);
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
.reason-by { font-size: 11px; color: rgba(255,255,255,0.5); font-family: monospace; }

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
