"""거래비용 — BacktestService 모델 미러 + 슬리피지를 진입/청산 '가격'에 적용(보수적).

순수함수(테스트 대상). hit/alpha 는 raw(비용 미반영, SignalOutcome 정의 일관), 성과는 net(비용 차감).
"""

COMMISSION_RATE = 0.00015     # 매수·매도 각 0.015% (BacktestService.COMMISSION_RATE 미러)
TAX_RATE = 0.0018            # 매도 세금 0.18% (BacktestService.TAX_RATE 미러)
DEFAULT_SLIPPAGE_PCT = 0.15  # 보수적: swing 0.1% / scalping 0.2% 사이 상단


def commission_tax_pct() -> float:
    """왕복 수수료(×2) + 매도 세금, % 단위(flat 차감분) = 0.21%."""
    return (COMMISSION_RATE * 2 + TAX_RATE) * 100.0


def gross_return_pct(entry: float, exit_: float) -> float:
    """비용 미반영 raw 수익률(%) — hit/alpha 판정용(SignalOutcome pct 정의와 동일)."""
    return (exit_ / entry - 1) * 100.0


def net_return_pct(entry_open: float, exit_close: float,
                   slippage_pct: float = DEFAULT_SLIPPAGE_PCT) -> float:
    """순손익(%) — 슬리피지를 진입/청산 가격에 적용(체결 불리) 후 수수료·세금 차감.

    진입: entry_open*(1+slip/2) (매수는 시가보다 위에서 체결), 청산: exit_close*(1-slip/2) (매도는 아래서).
    → 슬리피지가 시가/종가를 파고들어 보수적. slippage_pct=0 이면 비용 = 수수료·세금만(더 좋음).
    """
    slip = slippage_pct / 100.0
    entry_fill = entry_open * (1 + slip / 2)
    exit_fill = exit_close * (1 - slip / 2)
    gross = (exit_fill / entry_fill - 1) * 100.0
    return gross - commission_tax_pct()
