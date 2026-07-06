"""가설 A — 수급 '연속 순매수일' vs '금액' 예측력 비교 (P1-6 후속, 측정 전용).

배경: prod 실측(n=88)에서 수급 점수(금액 가점 중심)의 단조 역상관 확정 → composite 캡 10(A안).
대안 가설: "외인+기관 합산 연속 순매수 '일수'"가 금액보다 예측력 있는가.

비교 대상 4종(동일 forward 평가 — D+1 시가 / +3거래일 종가 / 비용 / hit 미러):
  - streak3 / streak5: 합산 순매수 연속일이 D 에 '정확히' 3/5 에 도달(신규 트리거 — 매일 재발화 방지)
  - amount50: 당일 합산 순매수 >= 50억(순수 금액 기준, 연속성 무관)
  - current_formula_strong: 현행 scoreSupplyDemand 미러 >= 15 (금액 가점 포함 현행 산식)

산식 편입 없음 — 리포트 비교만. 데이터 소스/근사 한계는 investor_flows 참조.
"""
import logging
from typing import Optional

import pandas as pd

from app.backtest import metrics
from app.backtest.composite_backtest_service import (_forward_eval, aggregate_by)

logger = logging.getLogger(__name__)

AMOUNT_THRESHOLD_EOK = 50.0


# ==================== 순수: 트리거 (테스트 대상) ====================

def streak_lengths(net_series: list) -> list:
    """일별 '해당일 포함 연속 순매수(>0) 일수' 시퀀스 — None/음수는 0 으로 리셋."""
    out = []
    run = 0
    for v in net_series:
        run = run + 1 if (v is not None and v > 0) else 0
        out.append(run)
    return out


def streak_trigger_indices(net_series: list, k: int) -> list:
    """연속일이 '정확히 k 에 도달'한 날 인덱스 — 신규 트리거만(k 초과 지속일은 재발화 아님)."""
    return [i for i, run in enumerate(streak_lengths(net_series)) if run == k]


def amount_trigger_indices(net_series: list, threshold_eok: float) -> list:
    """당일 순매수 금액 >= threshold(억) 인 날 인덱스 — 연속성 무관 순수 금액 기준."""
    return [i for i, v in enumerate(net_series) if v is not None and v >= threshold_eok]


# ==================== 재생 ====================

def replay_supply_hypothesis(price: dict, flows: dict, kospi: Optional[pd.DataFrame],
                             start_dt, end_dt, current_strong_signals: list,
                             hold_days: int = 3) -> dict:
    """flows 기반 트리거 3종 재생 + 현행 산식 강세(외부 주입) — 신호셋 4종 비교 집계.

    price/flows: {ticker: DataFrame} (composite 재생과 동일 객체 재사용 — fetch 중복 없음).
    current_strong_signals: composite 재생 결과 중 supplyDemand>=15 행(현행 산식 대조군).
    """
    variants = {"streak3": [], "streak5": [], f"amount{int(AMOUNT_THRESHOLD_EOK)}": []}
    for t, f in flows.items():
        df = price.get(t)
        if df is None or f.empty:
            continue
        combined = (f["frgn_net_eok"] + f["inst_net_eok"]).tolist()
        dates = list(f.index)
        triggers = {
            "streak3": streak_trigger_indices(combined, 3),
            "streak5": streak_trigger_indices(combined, 5),
            f"amount{int(AMOUNT_THRESHOLD_EOK)}": amount_trigger_indices(combined, AMOUNT_THRESHOLD_EOK),
        }
        for name, idxs in triggers.items():
            for i in idxs:
                D = dates[i]
                if not (start_dt <= D <= end_dt) or D not in df.index:
                    continue
                fwd = _forward_eval(df, D, kospi, hold_days)
                if fwd is None:
                    continue
                variants[name].append({"ticker": t, "date": D.strftime("%Y-%m-%d"),
                                       "netAmountEok": round(combined[i], 1), **fwd})

    out = {}
    for name, rows in variants.items():
        out[name] = _summarize(rows)
    out["current_formula_strong"] = _summarize(current_strong_signals)
    out["comparison_note"] = ("streak* 가 amount*/current 보다 hitRate·avgAlpha 에서 유의하게 높으면 "
                              "'연속일이 금액보다 낫다' 가설 지지 — 단 산식 편입은 별도 결정(표본·regime 확인)")
    return out


def _summarize(rows: list) -> dict:
    alphas = [r["alpha"] for r in rows if r.get("alpha") is not None]
    nets = [r["netPct"] for r in rows]
    return {
        "n": len(rows),
        "hitRate": round(metrics.hit_rate([r["hit"] for r in rows]), 2) if rows else None,
        "avgAlpha": round(sum(alphas) / len(alphas), 3) if alphas else None,
        "alphaN": len(alphas),
        "avgNet": round(sum(nets) / len(nets), 3) if nets else None,
        "perTrade": metrics.per_trade_stats(nets),
        "portfolioMdd10": round(metrics.portfolio_mdd(
            [(r["exitDate"], r["netPct"]) for r in rows], 10), 3) if rows else None,
        "insufficientSample": len(rows) < 10,
        "byRegimeNote": None,   # regime 분해는 composite 리포트 쪽에 있음 — 여기선 총괄만
    }
