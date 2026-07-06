"""종합점수 4카테고리 백테스트 CLI 러너 (측정 전용).

사용(리포 루트 기준):
  cd python-backend
  python -m app.backtest.run_composite --start 20260102 --end 20260630 \
      [--snapshot-csv path.csv] [--flows-csv path.csv] [--no-supply] \
      [--out ../docs/backtest_reports/composite_backtest_YYYY-MM-DD.json]

- 로컬(운영 DB 불가): 기술(OHLCV 재계산) + 수급(네이버 flows 근사)만 측정, 실적/섹터 미측정 표기.
- 운영 export 주입: --snapshot-csv(RecommendationSnapshot) → 4카테고리 전부, --flows-csv → 수급 정밀.
"""
import argparse
import logging
from datetime import date
from pathlib import Path

from app.backtest.composite_backtest_service import (build_report, evaluate_snapshot_signals,
                                                     load_snapshot_csv, replay_composite,
                                                     save_report)
from app.backtest.investor_flows import load_flows_csv
from app.backtest.universe import DEPLOYED_UNIVERSE

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", required=True, help="YYYYMMDD")
    ap.add_argument("--end", required=True, help="YYYYMMDD")
    ap.add_argument("--snapshot-csv", default=None, help="운영 RecommendationSnapshot export CSV")
    ap.add_argument("--flows-csv", default=None, help="운영 InvestorDailyTrade export CSV")
    ap.add_argument("--flows-cache", default=".backtest_cache", help="네이버 flows 파일 캐시 디렉토리")
    ap.add_argument("--no-supply", action="store_true", help="수급 수집 생략(기술만)")
    ap.add_argument("--universe-cap", type=int, default=None, help="유니버스 상한(런타임 제한)")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    universe = DEPLOYED_UNIVERSE[: args.universe_cap] if args.universe_cap else DEPLOYED_UNIVERSE
    csv_flows = load_flows_csv(args.flows_csv) if args.flows_csv else None

    replayed = replay_composite(universe, args.start, args.end,
                                csv_flows=csv_flows, flows_cache_dir=args.flows_cache,
                                with_supply=not args.no_supply)

    snapshot_signals = None
    if args.snapshot_csv:
        rows = load_snapshot_csv(args.snapshot_csv)
        snapshot_signals = evaluate_snapshot_signals(rows)["signals"]

    supply_source = ("investor_daily_trade_csv" if csv_flows
                     else "skipped" if args.no_supply else "naver-approx")
    report = build_report(replayed, args.start, args.end, len(universe),
                          snapshot_signals=snapshot_signals, supply_source=supply_source)

    out = args.out or str(Path("..") / "docs" / "backtest_reports"
                          / f"composite_backtest_{date.today().isoformat()}.json")
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    save_report(report, out)
    print(f"saved: {out}  (signals={report['meta']['signalCount']})")


if __name__ == "__main__":
    main()
