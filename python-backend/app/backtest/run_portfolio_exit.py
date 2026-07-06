"""포트폴리오 레벨 러너 — 고정 -3/+5 vs ATR세트(x2.5 + 리스크 균등) 비교 리포트 (측정 전용).

핵심 질문: "세트가 일일 손실 브레이커 발동을 늘리지 않는가" (docs/ATR_TRADING_SET.md Phase 3).
신호셋은 run_exit_backtest 와 동일(기술 미러 >=13 proxy, --snapshot-csv 시 total>=55).

사용: cd python-backend && python -m app.backtest.run_portfolio_exit \
        --start 20260102 --end 20260630 [--capital 500000] [--out ...]
"""
import argparse
import json
import logging
from datetime import date
from pathlib import Path

from app.backtest.composite_backtest_service import (fetch_market_data, load_snapshot_csv,
                                                     replay_composite)
from app.backtest.portfolio_backtest_service import (DEFAULT_BREAKER_LIMIT_KRW,
                                                     DEFAULT_CAPITAL_KRW,
                                                     DEFAULT_RISK_BUDGET_KRW, compare_verdict,
                                                     replay_portfolio)
from app.backtest.universe import DEPLOYED_UNIVERSE

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

BUY_CUT = 55


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", required=True)
    ap.add_argument("--end", required=True)
    ap.add_argument("--snapshot-csv", default=None)
    ap.add_argument("--flows-cache", default=".backtest_cache")
    ap.add_argument("--universe-cap", type=int, default=None)
    ap.add_argument("--capital", type=float, default=DEFAULT_CAPITAL_KRW)
    ap.add_argument("--breaker-limit", type=float, default=DEFAULT_BREAKER_LIMIT_KRW)
    ap.add_argument("--risk-budget", type=float, default=DEFAULT_RISK_BUDGET_KRW)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    universe = DEPLOYED_UNIVERSE[: args.universe_cap] if args.universe_cap else DEPLOYED_UNIVERSE
    market = fetch_market_data(universe, args.start, args.end,
                               flows_cache_dir=args.flows_cache,
                               hold_days=10, with_supply=False)
    price, _flows, _kospi = market

    if args.snapshot_csv:
        rows = load_snapshot_csv(args.snapshot_csv)
        signals = [r for r in rows if r.get("totalScore", 0) >= BUY_CUT]
        signal_desc = f"운영 스냅샷 total>={BUY_CUT} (STRONG_BUY/BUY)"
    else:
        replayed = replay_composite(universe, args.start, args.end,
                                    with_supply=False, market_data=market)
        signals = [s for s in replayed["signals"]
                   if s["scores"].get("technical") is not None and s["scores"]["technical"] >= 13]
        signal_desc = ("기술 미러 >=13 강세 신호(proxy) — 운영 STRONG_BUY/BUY 아님. "
                       "스냅샷 CSV 주입 시 total>=55 로 측정.")

    common = dict(capital=args.capital, breaker_limit=args.breaker_limit,
                  risk_budget=args.risk_budget)
    fixed = replay_portfolio(signals, price, "fixed", **common)
    atr_set = replay_portfolio(signals, price, "atr_set", **common)

    report = {
        "meta": {
            "hypothesis": "포트폴리오 — 고정 -3/+5 vs ATR세트(x2.5 청산 + 리스크 균등 사이징)",
            "coreQuestion": "세트가 일일 손실 브레이커 발동을 늘리지 않는가",
            "period": {"start": args.start, "end": args.end},
            "universeSize": len(universe),
            "signalSet": signal_desc,
            "botMirror": ("SWING_INVESTMENT_RATIO 50% / MAX_HOLDING 2 / MAX_HOLD_DAYS 5(타임컷) / "
                          "브레이커 = 당일 실현 <= -한도 -> 그날 진입 차단(다음날 자동 해제) / "
                          "PositionSizer(축소 전용)·AtrExitRule(RR 5/3) Java 미러"),
            "caveats": [
                "트레일링 스탑(+2% 후 -2%)은 일봉 근사 불가 — 두 변형 모두 미적용",
                "신호셋 proxy(기술>=13) — 운영 스윙(수급 연속일) 모집단과 상이",
                "생존편향(현 유니버스)·동시신호 슬롯 경합은 ticker 순 결정론 처리",
            ],
        },
        "results": {"fixed": fixed, "atr_set": atr_set,
                    "verdict": compare_verdict(fixed, atr_set)},
    }
    out = args.out or str(Path("..") / "docs" / "backtest_reports"
                          / f"portfolio_exit_backtest_{date.today().isoformat()}.json")
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8") as fp:
        json.dump(report, fp, ensure_ascii=False, indent=1)
    print(f"saved: {out}")   # ASCII only — cp949 콘솔 안전


if __name__ == "__main__":
    main()
