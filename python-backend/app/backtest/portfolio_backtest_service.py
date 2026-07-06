"""포트폴리오 레벨 재생 — 고정 -3/+5 vs ATR세트(x2.5 청산 + 리스크 균등 사이징) (측정 전용).

핵심 질문: "ATR 세트가 일일 손실 브레이커 발동을 늘리지 않는가".
봇 V38 브레이커를 가상 재현 — 당일 실현손실 누적 <= -한도 → 그날 신규 진입만 차단(청산 계속),
다음 거래일 자동 해제. Java 미러:
  - PositionSizer.judge: 수량 = riskBudget / (진입가 x 손절폭%), 현행 상한(min(현금, 자산x50%)) 캡
    — 수량 축소 전용, ATR 결측이면 그 종목은 완전 현행(고정 사이징 + 고정 청산, §4c 폴백 동일).
  - AtrExitRule: 손절 = k x ATR / 진입가, 익절 = 손절폭 x 5/3 (k=2.5).
  - 스윙 봇 상수: SWING_INVESTMENT_RATIO 50% / SWING_MAX_HOLDING 2 / SWING_MAX_HOLD_DAYS 5(타임컷).
청산 시뮬 규약은 exit_backtest_service 와 동일(손절 우선 비관적, D+1 시가 진입, 비용 모델 공유).
한계: 트레일링 스탑(+2% 후 고점 -2%)은 일봉으로 근사 불가 — 두 변형 모두 미적용(리포트 명시).
산식/봇 편입 없음 — 리포트만.
"""
import logging
import math
from typing import Optional

import pandas as pd

from app.backtest import cost
from app.backtest.exit_backtest_service import (atr_rule_prices, fixed_rule_prices, wilder_atr)

logger = logging.getLogger(__name__)

# 스윙 봇 미러 상수 (AutoTradingBotService)
SWING_INVESTMENT_RATIO = 0.50
SWING_MAX_HOLDING = 2
SWING_MAX_HOLD_DAYS = 5
ATR_K = 2.5
# 브레이커/riskBudget 기본 (DailyLossBreakerService.DEFAULT_LIMIT_KRW / ÷6)
DEFAULT_BREAKER_LIMIT_KRW = 300_000
DEFAULT_RISK_BUDGET_KRW = 50_000
DEFAULT_CAPITAL_KRW = 500_000    # 스윙 봇 "50만 소액 운용" 주석 미러


def atr_set_quantity(entry_price: float, stop_pct: float, risk_budget: float,
                     invest_cap: float) -> int:
    """PositionSizer.judge 미러 — 리스크 균등 수량, 현행 상한 캡(축소 전용). 순수."""
    if entry_price <= 0 or invest_cap <= 0:
        return 0
    cap_qty = int(invest_cap // entry_price)
    if stop_pct is None or stop_pct <= 0 or risk_budget is None or risk_budget <= 0:
        return cap_qty            # 결측 → 현행 폴백(§4c)
    loss_per_share = entry_price * stop_pct / 100.0
    risk_qty = int(risk_budget // loss_per_share)
    return max(0, min(risk_qty, cap_qty))


def _entry_fill(open_price: float, slippage_pct: float) -> float:
    return open_price * (1 + slippage_pct / 200.0)     # cost.net_return_pct 진입측과 동일


def _exit_fill(exit_price: float, slippage_pct: float) -> float:
    return exit_price * (1 - slippage_pct / 200.0)


def replay_portfolio(signals: list, price: dict, variant: str,
                     capital: float = DEFAULT_CAPITAL_KRW,
                     breaker_limit: float = DEFAULT_BREAKER_LIMIT_KRW,
                     risk_budget: float = DEFAULT_RISK_BUDGET_KRW,
                     max_holding: int = SWING_MAX_HOLDING,
                     max_hold_days: int = SWING_MAX_HOLD_DAYS,
                     slippage_pct: float = cost.DEFAULT_SLIPPAGE_PCT) -> dict:
    """일 단위 포트폴리오 재생. variant: 'fixed' | 'atr_set'.

    하루 처리 순서 = 봇과 동일 배열: ① 보유 포지션 청산 판정(실현손익 누적) →
    ② 가상 브레이커 판정(당일 실현 <= -한도면 그날 진입 차단) → ③ 신규 진입(D+1 시가).
    """
    assert variant in ("fixed", "atr_set")

    # 진입 예정일(각 종목 캘린더의 D 다음 거래일) → 신호 매핑
    entries_by_day: dict = {}
    for s in signals:
        df = price.get(s["ticker"])
        if df is None:
            continue
        d = pd.Timestamp(s["date"])
        if d not in df.index:
            continue
        fwd = df.loc[df.index > d]
        if fwd.empty:
            continue
        entries_by_day.setdefault(fwd.index[0], []).append(s)

    # 전 종목 거래일 합집합 캘린더
    all_days = sorted({day for df in price.values() for day in df.index})

    cash = float(capital)
    positions: dict = {}          # ticker -> position dict
    trades: list = []
    daily_realized: dict = {}     # day -> 실현손익 합(원)
    trip_days: set = set()
    atr_fallback_entries = 0
    blocked_entries = 0
    equity_curve: list = []       # (day, equity)
    last_close: dict = {}

    def tripped(day) -> bool:
        """가상 브레이커 — 당일 실현손실 누적 기준(진입 시점마다 재평가 = 봇의 '실현 즉시 차단' 미러)."""
        t = daily_realized.get(day, 0.0) <= -breaker_limit
        if t:
            trip_days.add(str(day.date()))
        return t

    def realize(day, pos, exit_price, reason):
        nonlocal cash
        fill = _exit_fill(exit_price, slippage_pct)
        proceeds = pos["qty"] * fill * (1 - cost.COMMISSION_RATE - cost.TAX_RATE)
        pnl = proceeds - pos["cost_basis"]
        cash += proceeds
        daily_realized[day] = daily_realized.get(day, 0.0) + pnl
        trades.append({"ticker": pos["ticker"], "pnlKrw": round(pnl, 1),
                       "netPct": round(pnl / pos["cost_basis"] * 100.0, 3),
                       "reason": reason, "holdDays": pos["days_held"],
                       "atrApplied": pos["atr_applied"]})

    for day in all_days:
        # ① 청산 판정 (봉이 있는 종목만 — 거래정지일은 보유 지속)
        for ticker in list(positions.keys()):
            pos = positions[ticker]
            df = price[ticker]
            if day not in df.index:
                continue
            bar = df.loc[day]
            high, low, close = float(bar["고가"]), float(bar["저가"]), float(bar["종가"])
            pos["days_held"] += 1
            last_close[ticker] = close
            if low <= pos["stop"]:                    # 손절 우선(비관적)
                realize(day, pos, pos["stop"], "stop")
                del positions[ticker]
            elif high >= pos["target"]:
                realize(day, pos, pos["target"], "target")
                del positions[ticker]
            elif pos["days_held"] >= max_hold_days:   # 봇 타임컷(5거래일)
                realize(day, pos, close, "time")
                del positions[ticker]

        # ② 가상 브레이커 발동 기록(진입 유무와 무관하게 카운트)
        tripped(day)

        # ③ 신규 진입 (D+1 시가) — 슬롯·현금·브레이커 게이트.
        #    브레이커는 진입 시점마다 재평가 — 같은 날 앞선 손절 실현이 뒤 진입을 즉시 차단(봇 미러).
        for s in sorted(entries_by_day.get(day, []), key=lambda x: x["ticker"]):
            if len(positions) >= max_holding:
                break
            ticker = s["ticker"]
            if ticker in positions:
                continue
            if tripped(day):
                blocked_entries += 1
                continue
            df = price[ticker]
            bar = df.loc[day]
            open_price = float(bar["시가"])
            if open_price <= 0:
                continue
            fill = _entry_fill(open_price, slippage_pct)

            equity = cash + sum(p["qty"] * last_close.get(t, p["entry_fill"])
                                for t, p in positions.items())
            invest_cap = min(cash, equity * SWING_INVESTMENT_RATIO)

            atr_applied = False
            if variant == "atr_set":
                past = df.loc[:pd.Timestamp(s["date"])]
                atr = wilder_atr(past["고가"].astype(float).tolist(),
                                 past["저가"].astype(float).tolist(),
                                 past["종가"].astype(float).tolist(), 14)
                if atr is not None:
                    stop, target = atr_rule_prices(fill, atr, ATR_K)
                    stop_pct = (fill - stop) / fill * 100.0
                    qty = atr_set_quantity(fill, stop_pct, risk_budget, invest_cap)
                    atr_applied = True
                else:
                    atr_fallback_entries += 1
                    stop, target = fixed_rule_prices(fill)
                    qty = atr_set_quantity(fill, None, None, invest_cap)   # 현행 폴백
            else:
                stop, target = fixed_rule_prices(fill)
                qty = atr_set_quantity(fill, None, None, invest_cap)       # 현행 산식

            if qty <= 0:
                continue
            cost_basis = qty * fill * (1 + cost.COMMISSION_RATE)
            if cost_basis > cash:
                continue
            cash -= cost_basis
            last_close[ticker] = float(bar["종가"])
            pos = {"ticker": ticker, "qty": qty, "entry_fill": fill,
                   "cost_basis": cost_basis, "stop": stop, "target": target,
                   "days_held": 1, "atr_applied": atr_applied}
            positions[ticker] = pos

            # 진입 당일(D+1) 판정 — 시가 진입 후 당일 저가가 손절가를 관통할 수 있음
            # (simulate_exit i=0 규약 미러, 손절 우선 비관적). 실현되면 같은 날 뒤 진입에 브레이커 반영.
            day_high, day_low = float(bar["고가"]), float(bar["저가"])
            if day_low <= pos["stop"]:
                realize(day, pos, pos["stop"], "stop")
                del positions[ticker]
            elif day_high >= pos["target"]:
                realize(day, pos, pos["target"], "target")
                del positions[ticker]

        # ④ 하루 마감 — 진입 당일 손절 등 늦게 실현된 손실의 발동도 기록(차단 효과는 이미 소진된 날)
        tripped(day)

        # 종가 평가 equity 곡선
        equity = cash + sum(p["qty"] * last_close.get(t, p["entry_fill"])
                            for t, p in positions.items())
        equity_curve.append((day, equity))

    # 잔여 포지션 마지막 종가 청산(공정 비교 — 두 변형 동일 처리)
    if positions and all_days:
        final_day = all_days[-1]
        for ticker, pos in list(positions.items()):
            realize(final_day, pos, last_close.get(ticker, pos["entry_fill"]), "eod_final")
            del positions[ticker]
        equity_curve[-1] = (final_day, cash)

    # ── 집계 ──
    equities = [e for _, e in equity_curve]
    peak, mdd = -math.inf, 0.0
    for e in equities:
        peak = max(peak, e)
        if peak > 0:
            mdd = max(mdd, (peak - e) / peak * 100.0)
    final_equity = equities[-1] if equities else capital
    losses = [v for v in daily_realized.values() if v < 0]
    nets = [t["netPct"] for t in trades]
    wins = [n for n in nets if n > 0]

    return {
        "variant": variant,
        "params": {"capitalKrw": capital, "breakerLimitKrw": breaker_limit,
                   "riskBudgetKrw": risk_budget if variant == "atr_set" else None,
                   "maxHolding": max_holding, "maxHoldDays": max_hold_days,
                   "investmentRatio": SWING_INVESTMENT_RATIO,
                   "atrK": ATR_K if variant == "atr_set" else None},
        "totalReturnPct": round((final_equity / capital - 1) * 100.0, 3),
        "maxDrawdownPct": round(mdd, 3),
        "maxDailyRealizedLossKrw": round(min(losses), 1) if losses else 0.0,
        "breakerTripDays": len(trip_days),
        "breakerTripDates": sorted(trip_days),
        "blockedEntriesByBreaker": blocked_entries,
        "trades": len(trades),
        "winRate": round(len(wins) / len(nets) * 100.0, 2) if nets else None,
        "avgNetPct": round(sum(nets) / len(nets), 3) if nets else None,
        "avgHoldDays": round(sum(t["holdDays"] for t in trades) / len(trades), 2) if trades else None,
        "atrFallbackEntries": atr_fallback_entries if variant == "atr_set" else None,
        "exitBreakdown": {rz: sum(1 for t in trades if t["reason"] == rz)
                          for rz in ("stop", "target", "time", "eod_final")},
        "insufficientSample": len(trades) < 10,
    }


def compare_verdict(fixed: dict, atr_set: dict) -> str:
    """핵심 질문 판정 문자열 — 브레이커 발동 동수 이하 & 수익 우위 여부. 권고까지만."""
    trips_ok = atr_set["breakerTripDays"] <= fixed["breakerTripDays"]
    ret_ok = (atr_set["totalReturnPct"] is not None and fixed["totalReturnPct"] is not None
              and atr_set["totalReturnPct"] >= fixed["totalReturnPct"])
    core = ("브레이커 발동 동수 이하 충족" if trips_ok
            else f"브레이커 발동 증가({fixed['breakerTripDays']}→{atr_set['breakerTripDays']}) — 세트 재검토 필요")
    ret = (f"총수익 {'우위' if ret_ok else '열위'}"
           f"({fixed['totalReturnPct']}% → {atr_set['totalReturnPct']}%)")
    return f"{core} · {ret} · 일일 최대 실현손실 {fixed['maxDailyRealizedLossKrw']} → " \
           f"{atr_set['maxDailyRealizedLossKrw']}원 — REAL 확장은 VIRTUAL 2주+ 실측 후 별도 결정"
