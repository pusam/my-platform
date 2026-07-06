"""가설 B — ATR 동적 손절/익절 vs 고정 -3%/+5% 청산 비교 (측정 전용).

현행 매매계획(StockConclusionService.PLAN_* = 스윙 봇 동기 -3%/+5%)과 ATR(14) 기반
동적 청산(손절 = 진입가 − k×ATR, 익절 = 진입가 + k×ATR×(5/3) — 현행 손익비 1:1.67 유지,
k ∈ {1.5, 2.0, 2.5})을 동일 신호셋에 적용해 avgNet/winRate/MDD/profitFactor 비교.

청산 시뮬 규약(보수적 — 리포트 명시):
  - 진입 D+1 시가(신호 D 의 ATR 은 df.loc[:D] point-in-time — look-ahead 차단).
  - 일봉 고저가 판정: 같은 날 손절가·익절가 동시 터치 시 **손절 우선**(비관적 가정 —
    장중 순서를 알 수 없으므로 유리한 해석 금지).
  - 진입 당일(D+1)도 판정 대상(시가 진입 후 당일 저가가 손절가를 뚫을 수 있음).
  - 어느 쪽도 미도달 시 max_hold 거래일 종가 청산(두 규칙 공통 — 공정 비교용 시간 상한).
  - 비용: cost.net_return_pct 와 동일 모델(슬리피지 가격 적용 + 수수료·세금 flat).

산식/봇 편입 없음 — "고정이 이기면 현행 유지" 결론까지 리포트만.
"""
import logging
from typing import Optional

import pandas as pd

from app.backtest import cost, metrics

logger = logging.getLogger(__name__)

FIXED_STOP_PCT = -3.0      # StockConclusionService.PLAN_STOP 미러
FIXED_TARGET_PCT = 5.0     # PLAN_TARGET 미러
RISK_REWARD = FIXED_TARGET_PCT / -FIXED_STOP_PCT   # 1.667 — ATR 규칙에도 동일 손익비 적용
ATR_MULTIPLIERS = (1.5, 2.0, 2.5)
DEFAULT_MAX_HOLD_DAYS = 10


# ==================== 순수: ATR / 청산 시뮬 (테스트 대상) ====================

def wilder_atr(highs: list, lows: list, closes: list, period: int = 14) -> Optional[float]:
    """ATR(Wilder) — 시간순(과거→현재) 입력, 마지막 봉 기준. 데이터 < period+1 → None(§4c)."""
    n = len(closes)
    if n < period + 1 or len(highs) != n or len(lows) != n:
        return None
    trs = []
    for i in range(1, n):
        h, lo, pc = highs[i], lows[i], closes[i - 1]
        if h is None or lo is None or pc is None:
            return None
        trs.append(max(h - lo, abs(h - pc), abs(lo - pc)))
    atr = sum(trs[:period]) / period
    for tr in trs[period:]:
        atr = (atr * (period - 1) + tr) / period
    return atr


def simulate_exit(bars: list, entry_price: float, stop_price: float, target_price: float,
                  max_hold_days: int = DEFAULT_MAX_HOLD_DAYS) -> Optional[dict]:
    """일봉 고저가 보수적 청산 — bars = [(high, low, close), ...] 진입일부터 시간순.

    같은 날 손절·익절 동시 터치 → **손절 우선**(비관적). 미도달 → max_hold 째 종가.
    반환: {exitPrice, exitDayIdx, reason(stop|target|time)} — bars 부족 시 None.
    """
    if not bars or entry_price <= 0:
        return None
    horizon = min(len(bars), max_hold_days)
    for i in range(horizon):
        high, low, close = bars[i]
        if low is None or high is None:
            continue
        if low <= stop_price:                       # 손절 우선 — 동시 터치 시 비관적
            return {"exitPrice": stop_price, "exitDayIdx": i, "reason": "stop"}
        if high >= target_price:
            return {"exitPrice": target_price, "exitDayIdx": i, "reason": "target"}
    close = bars[horizon - 1][2]
    if close is None:
        return None
    return {"exitPrice": float(close), "exitDayIdx": horizon - 1, "reason": "time"}


def fixed_rule_prices(entry: float) -> tuple:
    """현행 고정 규칙 — (손절가, 익절가) = (-3%, +5%)."""
    return entry * (1 + FIXED_STOP_PCT / 100.0), entry * (1 + FIXED_TARGET_PCT / 100.0)


def atr_rule_prices(entry: float, atr: float, k: float) -> tuple:
    """ATR 규칙 — 손절 = entry − k×ATR, 익절 = entry + k×ATR×손익비(1.667, 현행과 동일 RR)."""
    return entry - k * atr, entry + k * atr * RISK_REWARD


# ==================== 재생 ====================

def replay_exit_rules(signals: list, price: dict, max_hold_days: int = DEFAULT_MAX_HOLD_DAYS,
                      slippage_pct: float = cost.DEFAULT_SLIPPAGE_PCT) -> dict:
    """신호셋(composite 재생 행 — ticker/date 필요)에 고정/ATR 그리드 청산을 적용해 비교.

    ATR 은 신호일 D 까지(df.loc[:D])로 산출 — ATR 미산출(상장 초기 등) 신호는 해당 규칙에서
    제외하고 제외 수를 리포트(§4c: 0 으로 위장 금지).
    """
    rules = {"fixed_-3/+5": None} | {f"atr_x{k}": k for k in ATR_MULTIPLIERS}
    trades: dict = {name: [] for name in rules}
    atr_missing = 0
    evaluated = 0

    for s in signals:
        df = price.get(s["ticker"])
        if df is None:
            continue
        D = pd.Timestamp(s["date"])
        if D not in df.index:
            continue
        past = df.loc[:D]
        fwd = df.loc[df.index > D]
        if fwd.empty:
            continue
        entry = float(fwd.iloc[0]["시가"])
        if entry <= 0:
            continue
        bars = list(zip(fwd["고가"].astype(float), fwd["저가"].astype(float),
                        fwd["종가"].astype(float)))
        atr = wilder_atr(past["고가"].astype(float).tolist(),
                         past["저가"].astype(float).tolist(),
                         past["종가"].astype(float).tolist(), 14)
        evaluated += 1
        if atr is None:
            atr_missing += 1

        for name, k in rules.items():
            if k is None:
                stop, target = fixed_rule_prices(entry)
            else:
                if atr is None:
                    continue
                stop, target = atr_rule_prices(entry, atr, k)
            r = simulate_exit(bars, entry, stop, target, max_hold_days)
            if r is None:
                continue
            net = cost.net_return_pct(entry, r["exitPrice"], slippage_pct)
            exit_date = fwd.index[r["exitDayIdx"]].strftime("%Y-%m-%d")
            trades[name].append({"netPct": round(net, 3), "reason": r["reason"],
                                 "holdDays": r["exitDayIdx"] + 1, "exitDate": exit_date})

    out = {"signalCount": len(signals), "evaluated": evaluated, "atrMissing": atr_missing,
           "maxHoldDays": max_hold_days,
           "assumptions": {
               "entry": "D+1 시가", "sameDayTie": "손절 우선(비관적)",
               "atr": "ATR14(Wilder), 신호일 D 까지 point-in-time",
               "riskReward": f"ATR 익절 = k×ATR×{round(RISK_REWARD, 3)} — 현행 -3/+5 손익비 유지",
               "timeExit": f"{max_hold_days}거래일 종가(두 규칙 공통)",
               "costModel": "수수료 0.03%+세금 0.18% flat + 슬리피지 0.15% 가격 적용",
           },
           "rules": {}}
    for name, rows in trades.items():
        nets = [r["netPct"] for r in rows]
        reasons = [r["reason"] for r in rows]
        out["rules"][name] = {
            "n": len(rows),
            "avgNet": round(sum(nets) / len(nets), 3) if nets else None,
            "perTrade": metrics.per_trade_stats(nets),
            "portfolioMdd10": round(metrics.portfolio_mdd(
                [(r["exitDate"], r["netPct"]) for r in rows], 10), 3) if rows else None,
            "avgHoldDays": round(sum(r["holdDays"] for r in rows) / len(rows), 2) if rows else None,
            "exitBreakdown": {rz: round(reasons.count(rz) / len(reasons) * 100, 1)
                              for rz in ("stop", "target", "time")} if reasons else None,
            "insufficientSample": len(rows) < 10,
        }
    return out


def conclusion(rules_summary: dict) -> str:
    """고정 vs 최적 ATR 결론 문자열 — avgNet·MDD 종합. '고정이 이기면 현행 유지' 원칙."""
    fixed = rules_summary.get("fixed_-3/+5")
    if not fixed or fixed.get("avgNet") is None:
        return "표본 부족 — 결론 유보(§4c)"
    best_name, best = "fixed_-3/+5", fixed
    for name, s in rules_summary.items():
        if name.startswith("atr_") and s.get("avgNet") is not None and s["avgNet"] > best["avgNet"]:
            best_name, best = name, s
    if best_name == "fixed_-3/+5":
        return "고정 -3%/+5% 가 ATR 그리드 전부보다 avgNet 우위 또는 동률 — 현행 유지 권고"
    margin = round(best["avgNet"] - fixed["avgNet"], 3)
    return (f"{best_name} 가 avgNet +{margin}%p 우위 — 단 MDD({best.get('portfolioMdd10')} vs "
            f"{fixed.get('portfolioMdd10')})·표본·regime 확인 후 별도 세션에서 판단(자동 편입 금지)")
