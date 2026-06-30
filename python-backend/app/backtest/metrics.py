"""백테스트 집계 — hit 정의(SignalOutcome 미러) + MDD/Sharpe(BacktestService 미러) + Spearman(순수)."""
import math


def is_hit(alpha, pct) -> bool:
    """SignalOutcomeService.isHit 미러: alpha 있으면 alpha>=0 AND pct>0, 없으면 pct>=3%."""
    if pct is None:
        return False
    if alpha is not None:
        return alpha >= 0 and pct > 0
    return pct >= 3.0


def hit_rate(hits) -> float:
    """적중률(%) — None 제외."""
    vals = [h for h in hits if h is not None]
    if not vals:
        return 0.0
    return sum(1 for h in vals if h) / len(vals) * 100.0


def mdd(net_returns) -> float:
    """최대낙폭(%) — net 수익률 시퀀스(날짜순)로 누적 자산곡선의 peak-to-trough 최대."""
    if not net_returns:
        return 0.0
    equity = 1.0
    peak = 1.0
    max_dd = 0.0
    for r in net_returns:
        equity *= (1 + r / 100.0)
        peak = max(peak, equity)
        if peak > 0:
            max_dd = max(max_dd, (peak - equity) / peak * 100.0)
    return max_dd


def sharpe(net_returns) -> float:
    """Sharpe = avg/stdev(n-1). 무위험·연율화 없음(BacktestService 미러). 표본<2 또는 stdev=0 → 0."""
    n = len(net_returns)
    if n < 2:
        return 0.0
    avg = sum(net_returns) / n
    var = sum((r - avg) ** 2 for r in net_returns) / (n - 1)
    sd = math.sqrt(var)
    return avg / sd if sd > 0 else 0.0


def aggregate(net_returns) -> dict:
    """성과 집계(net 수익률, per-trade 기준 — 포지션 사이징 가정 없음)."""
    n = len(net_returns)
    avg = sum(net_returns) / n if n else 0.0
    return {
        "n": n,
        "avgNetReturn": round(avg, 3),
        "sharpe": round(sharpe(net_returns), 3),
    }


def per_trade_stats(net_returns) -> dict:
    """가정-free per-trade 분포 — 다운사이드 직관(MDD 가정 논란 회피)."""
    n = len(net_returns)
    if n == 0:
        return {"n": 0}
    wins = [r for r in net_returns if r > 0]
    losses = [r for r in net_returns if r <= 0]
    gross_win = sum(wins)
    gross_loss = -sum(losses)
    return {
        "n": n,
        "winRate": round(len(wins) / n * 100, 2),     # net>0 비율(hitRate 와 별개 — 비용반영)
        "avgWin": round(sum(wins) / len(wins), 3) if wins else 0.0,
        "avgLoss": round(sum(losses) / len(losses), 3) if losses else 0.0,
        "profitFactor": round(gross_win / gross_loss, 3) if gross_loss > 0 else None,
        "worstTrade": round(min(net_returns), 3),
        "bestTrade": round(max(net_returns), 3),
        "pctLossOver5": round(sum(1 for r in net_returns if r < -5) / n * 100, 1),
    }


def portfolio_mdd(exit_net_pairs, k_slots: int = 10) -> float:
    """현실적 MDD(%) — K슬롯 균등배분(매매당 1/K), 청산일에 실현. 매매당 풀베팅(순차 복리) 아님.

    ⚠ 실현 기준이라 보유 중 장중 낙폭(intra-trade MTM)은 과소반영 — 보수적 하한. k_slots=동시보유 상한.
    exit_net_pairs: [(exit_date_str, net_pct), ...].
    """
    if not exit_net_pairs or k_slots <= 0:
        return 0.0
    equity = 1.0
    peak = 1.0
    max_dd = 0.0
    for _exit, net in sorted(exit_net_pairs, key=lambda t: t[0]):
        equity += (net / 100.0) / k_slots
        peak = max(peak, equity)
        if peak > 0:
            max_dd = max(max_dd, (peak - equity) / peak * 100.0)
    return max_dd


def _ranks(vals):
    """평균순위(1-based, 동순위 tie 평균 처리)."""
    order = sorted(range(len(vals)), key=lambda i: vals[i])
    ranks = [0.0] * len(vals)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and vals[order[j + 1]] == vals[order[i]]:
            j += 1
        avg_rank = (i + j) / 2.0 + 1
        for k in range(i, j + 1):
            ranks[order[k]] = avg_rank
        i = j + 1
    return ranks


def spearman(xs, ys):
    """Spearman 순위상관 — scipy 없이 순수. None 쌍 제외, 표본<2 또는 분산0 → None."""
    pairs = [(x, y) for x, y in zip(xs, ys) if x is not None and y is not None]
    n = len(pairs)
    if n < 2:
        return None
    xr = _ranks([p[0] for p in pairs])
    yr = _ranks([p[1] for p in pairs])
    mx = sum(xr) / n
    my = sum(yr) / n
    num = sum((a - mx) * (b - my) for a, b in zip(xr, yr))
    den = math.sqrt(sum((a - mx) ** 2 for a in xr) * sum((b - my) ** 2 for b in yr))
    return num / den if den > 0 else None
