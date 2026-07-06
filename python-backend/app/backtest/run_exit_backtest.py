"""가설 B 러너 — ATR 동적 청산 vs 고정 -3%/+5% 비교 리포트 (측정 전용).

신호셋: --snapshot-csv 있으면 운영 스냅샷 total>=55(STRONG_BUY/BUY 정확) —
없으면(로컬) 기술 미러 >=13 강세 신호를 대용(proxy)으로 쓰고 리포트에 명시.

사용: cd python-backend && python -m app.backtest.run_exit_backtest \
        --start 20260102 --end 20260630 [--snapshot-csv ...] [--out ...]
"""
import argparse
import json
import logging
from datetime import date
from pathlib import Path

from app.backtest.composite_backtest_service import (fetch_market_data, load_snapshot_csv,
                                                     replay_composite)
from app.backtest.exit_backtest_service import (DEFAULT_MAX_HOLD_DAYS, conclusion,
                                                replay_exit_rules)
from app.backtest.universe import DEPLOYED_UNIVERSE

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

BUY_CUT = 55   # STRONG_BUY/BUY 컷(§13) — 스냅샷 신호셋용


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", required=True)
    ap.add_argument("--end", required=True)
    ap.add_argument("--snapshot-csv", default=None)
    ap.add_argument("--flows-cache", default=".backtest_cache")
    ap.add_argument("--universe-cap", type=int, default=None)
    ap.add_argument("--max-hold", type=int, default=DEFAULT_MAX_HOLD_DAYS)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    universe = DEPLOYED_UNIVERSE[: args.universe_cap] if args.universe_cap else DEPLOYED_UNIVERSE
    # max_hold 이후 청산까지 봐야 하므로 fetch 여유를 hold 기준으로 확장
    market = fetch_market_data(universe, args.start, args.end,
                               flows_cache_dir=args.flows_cache,
                               hold_days=args.max_hold, with_supply=False)
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
                       "composite total 은 실적/섹터 없이는 계산 불가(validCount>=3)라 정직한 대용(§4c). "
                       "스냅샷 CSV 주입 시 total>=55 로 측정.")

    result = replay_exit_rules(signals, price, max_hold_days=args.max_hold)
    result["signalSet"] = signal_desc
    result["conclusion"] = conclusion(result["rules"])

    report = {"meta": {"hypothesis": "B — ATR(14)×k 동적 손절/익절 vs 고정 -3%/+5%(PLAN_* 미러)",
                       "period": {"start": args.start, "end": args.end},
                       "universeSize": len(universe)},
              "results": result}
    out = args.out or str(Path("..") / "docs" / "backtest_reports"
                          / f"exit_backtest_{date.today().isoformat()}.json")
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8") as fp:
        json.dump(report, fp, ensure_ascii=False, indent=1)
    print(f"saved: {out}  conclusion: {result['conclusion']}")


if __name__ == "__main__":
    main()
