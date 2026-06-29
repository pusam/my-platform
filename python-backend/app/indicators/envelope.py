"""엔벨로프 하단 터치 (지표 3) + 위험필터 터치횟수 (지표 5).

엔벨로프 하단 = MA20 * (1 - k). 저가가 하단 이하면 '터치'(눌림목 진입 후보).
같은 window 안에서 하단을 너무 자주(risk_touch_max 이상) 두드리면
추세 훼손 신호 → 매수 제외(위험필터).
"""
from typing import Optional


def lower_band(ma20: Optional[float], k: float) -> Optional[float]:
    """엔벨로프 하단 = MA20*(1-k). MA20 결측이면 None."""
    if ma20 is None:
        return None
    return ma20 * (1 - k)


def lower_touch(low: float, lower: Optional[float]) -> bool:
    """저가가 엔벨로프 하단 이하인지(터치). 하단 결측이면 False."""
    if lower is None:
        return False
    return low <= lower


def touch_count_in_window(lows: list[float], ma20s: list[Optional[float]],
                          k: float, window: int) -> int:
    """최근 window 봉 내 엔벨로프 하단 터치 횟수.

    lows / ma20s 는 같은 길이·정렬(과거→현재). ma20 결측 봉은 미터치로 셈.
    """
    if window <= 0 or not lows:
        return 0
    n = min(window, len(lows), len(ma20s))
    cnt = 0
    for i in range(len(lows) - n, len(lows)):
        band = lower_band(ma20s[i], k)
        if lower_touch(lows[i], band):
            cnt += 1
    return cnt


def is_risk_excluded(touch_count: int, risk_touch_max: int) -> bool:
    """window 내 하단 터치가 risk_touch_max 이상이면 매수 제외(True).

    예: risk_touch_max=2 → 2회 이상 터치 시 제외(잦은 하단 이탈 = 추세 훼손).
    """
    return touch_count >= risk_touch_max
