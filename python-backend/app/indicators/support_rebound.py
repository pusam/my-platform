"""중심선(MA20) 지지 반등 감지 (지표 4).

엔벨로프 중심선(MA20)까지 장중 눌렸다가(저가가 중심선 이하) 종가가
중심선 위로 회복하면 '지지 반등'. 추세 안에서 눌림목을 받아내는 신호.
"""
from typing import Optional


def center_support_rebound(low: float, close: float, ma20: Optional[float],
                           band: float = 0.0) -> bool:
    """중심선 지지 반등 여부.

    조건: 저가가 중심선 근처(<= MA20*(1+band))까지 눌렸고,
          종가는 중심선 위(>= MA20)로 회복.
    band 는 '근처' 허용 오차(0 이면 정확히 중심선 이하 터치). MA20 결측이면 False.
    """
    if ma20 is None:
        return False
    touched = low <= ma20 * (1 + band)
    recovered = close >= ma20
    return touched and recovered
