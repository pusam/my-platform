"""60일선-240일선 이격도 + 과열 판정 (지표 2)."""
from typing import Optional


def ma60_ma240_disparity(ma60: Optional[float], ma240: Optional[float]) -> Optional[float]:
    """이격도 = MA60 / MA240 * 100. 결측/0분모면 None(§4c)."""
    if ma60 is None or ma240 is None or ma240 == 0:
        return None
    return ma60 / ma240 * 100.0


def is_overheated(disparity: Optional[float], threshold: float) -> bool:
    """이격도가 과열 임계 이상인지. None(결측)은 과열로 보지 않음(False)."""
    if disparity is None:
        return False
    return disparity >= threshold
