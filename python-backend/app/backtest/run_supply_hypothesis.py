"""가설 A 러너 — 수급 '연속 순매수일' vs '금액' 비교 리포트 (측정 전용).

사용: cd python-backend && python -m app.backtest.run_supply_hypothesis \
        --start 20260102 --end 20260630 [--flows-csv ...] [--out ...]
"""
import argparse
import json
import logging
from datetime import date, datetime
from pathlib import Path

from app.backtest.composite_backtest_service import fetch_market_data, replay_composite
from app.backtest.investor_flows import load_flows_csv
from app.backtest.supply_hypothesis import AMOUNT_THRESHOLD_EOK, replay_supply_hypothesis
from app.backtest.universe import DEPLOYED_UNIVERSE

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", required=True)
    ap.add_argument("--end", required=True)
    ap.add_argument("--flows-csv", default=None)
    ap.add_argument("--flows-cache", default=".backtest_cache")
    ap.add_argument("--universe-cap", type=int, default=None)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    universe = DEPLOYED_UNIVERSE[: args.universe_cap] if args.universe_cap else DEPLOYED_UNIVERSE
    csv_flows = load_flows_csv(args.flows_csv) if args.flows_csv else None

    market = fetch_market_data(universe, args.start, args.end,
                               csv_flows=csv_flows, flows_cache_dir=args.flows_cache)
    price, flows, kospi = market

    # 현행 산식 대조군 — composite 재생에서 supplyDemand>=15 행(금액 가점 포함 현행 미러)
    replayed = replay_composite(universe, args.start, args.end, market_data=market)
    current_strong = [s for s in replayed["signals"]
                      if s["scores"].get("supplyDemand") is not None
                      and s["scores"]["supplyDemand"] >= 15]

    start_dt = datetime.strptime(args.start, "%Y%m%d")
    end_dt = datetime.strptime(args.end, "%Y%m%d")
    result = replay_supply_hypothesis(price, flows, kospi, start_dt, end_dt, current_strong)

    report = {
        "meta": {
            "hypothesis": "A — 수급 '연속 순매수일(합산 외인+기관)' vs '금액' 예측력",
            "period": {"start": args.start, "end": args.end},
            "universeSize": len(universe),
            "kospiSource": kospi.attrs.get("source") if kospi is not None else None,
            "supplySource": "investor_daily_trade_csv" if csv_flows else "naver-approx(수량×종가)",
            "variants": {
                "streak3/streak5": "합산 순매수 연속일이 정확히 3/5 도달(신규 트리거)",
                f"amount{int(AMOUNT_THRESHOLD_EOK)}": f"당일 합산 순매수 >= {int(AMOUNT_THRESHOLD_EOK)}억(연속성 무관)",
                "current_formula_strong": "현행 scoreSupplyDemand 미러 >= 15",
            },
            "forwardEval": "D+1 시가 → +3거래일 종가, 비용 차감, hit=SignalOutcome 미러",
            "caveat": "금액=네이버 수량×종가 근사(CSV 주입 시 정밀) · 산식 편입 없음(리포트만)",
        },
        "results": result,
    }
    out = args.out or str(Path("..") / "docs" / "backtest_reports"
                          / f"supply_hypothesis_{date.today().isoformat()}.json")
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8") as fp:
        json.dump(report, fp, ensure_ascii=False, indent=1)
    print(f"saved: {out}")


if __name__ == "__main__":
    main()
