"""첫 박스 돌파 후 눌림목 higher-low 셋업 (지표 6, A안: 변동성 박스).

승인된 정량화(Plan Mode A안):
  박스   = box_len 봉 구간의 (고가-저가)/저가 <= box_range_max 인 '횡보'
  돌파   = 돌파탐색 구간(최근 lookahead_m 봉)의 종가가 box_high*(1+breakout_buf) 상회
  눌림목 = 현재(마지막 봉) 저가가
            box_high*(1-pullback_tol) 위에서 지지(박스상단 지지)
            AND  저가 > box_low                (higher-low)
  신호   = 박스 AND 돌파 AND 눌림목지지

산식 미검증(보조 시그널 전용) — VERIFICATION_BACKLOG 참조.
모든 임계값은 ChartPatternConfig 파라미터(하드코딩 없음).
"""
from typing import Optional


def box_metrics(highs_win: list[float], lows_win: list[float]) -> Optional[dict]:
    """박스 구간 고가/저가/변동성. 빈 구간이거나 저가<=0 이면 None."""
    if not highs_win or not lows_win:
        return None
    box_high = max(highs_win)
    box_low = min(lows_win)
    if box_low <= 0:
        return None
    range_pct = (box_high - box_low) / box_low
    return {"box_high": box_high, "box_low": box_low, "range_pct": range_pct}


def is_box(range_pct: Optional[float], box_range_max: float) -> bool:
    """변동성이 임계 이하면 '박스'(횡보). range_pct 결측이면 False."""
    if range_pct is None:
        return False
    return range_pct <= box_range_max


def evaluate(highs: list[float], lows: list[float], closes: list[float],
             box_len: int, box_range_max: float, breakout_buf: float,
             pullback_tol: float, lookahead_m: int) -> dict:
    """박스 돌파 후 눌림목 셋업 평가(시리즈 꼬리 기준).

    구간 분해(과거→현재 정렬 가정):
      박스창   = bars[-(box_len+lookahead_m) : -lookahead_m]  (lookahead_m 봉 전에 끝난 횡보)
      돌파탐색 = bars[-lookahead_m:]                          (그 이후 ~ 현재)
    데이터 부족 시 signal=False, reason='insufficient_data'.
    """
    need = box_len + lookahead_m
    if min(len(highs), len(lows), len(closes)) < need or lookahead_m <= 0 or box_len <= 0:
        return {"signal": False, "reason": "insufficient_data"}

    box_h = highs[-need:-lookahead_m]
    box_l = lows[-need:-lookahead_m]
    metrics = box_metrics(box_h, box_l)
    if metrics is None:
        return {"signal": False, "reason": "insufficient_data"}

    box_high = metrics["box_high"]
    box_low = metrics["box_low"]
    box = is_box(metrics["range_pct"], box_range_max)

    # 돌파: 탐색구간 종가가 박스상단+버퍼 상회
    breakout_level = box_high * (1 + breakout_buf)
    breakout = any(c > breakout_level for c in closes[-lookahead_m:])

    # 눌림목 지지(현재 봉): 박스상단 지지 + higher-low
    support_level = box_high * (1 - pullback_tol)
    cur_low = lows[-1]
    pullback = (cur_low >= support_level) and (cur_low > box_low)

    signal = box and breakout and pullback
    return {
        "signal": signal,
        "box": box,
        "breakout": breakout,
        "pullback": pullback,
        "box_high": round(box_high, 2),
        "box_low": round(box_low, 2),
        "range_pct": round(metrics["range_pct"], 4),
    }
