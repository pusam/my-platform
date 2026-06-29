"""이동평균 + 정배열 (지표 1)."""
from typing import Optional


def sma(values: list[float], n: int) -> Optional[float]:
    """단순이동평균(최근 n개). 데이터 부족이면 None(§4c)."""
    if n <= 0 or len(values) < n:
        return None
    window = values[-n:]
    return sum(window) / n


def is_aligned(mas: list[Optional[float]]) -> bool:
    """정배열 여부 — MA 들이 strictly 내림차순(단기>장기)인지.

    mas 순서는 단기→장기 (예: [MA5, MA20, MA60, MA120, MA240]).
    하나라도 None 이면 판정 불가 → False(결측을 정배열로 위장하지 않음).
    """
    if not mas or any(m is None for m in mas):
        return False
    return all(mas[i] > mas[i + 1] for i in range(len(mas) - 1))


def alignment_strength(mas: list[Optional[float]]) -> int:
    """정배열 강도 — 인접 MA 쌍 중 단기>장기 를 만족하는 개수(0..len-1).

    완전 정배열이면 len(mas)-1. None 이 끼면 그 쌍은 미충족으로 셈.
    부분 정배열을 점수에 반영하기 위한 보조 지표.
    """
    if not mas or len(mas) < 2:
        return 0
    cnt = 0
    for i in range(len(mas) - 1):
        a, b = mas[i], mas[i + 1]
        if a is not None and b is not None and a > b:
            cnt += 1
    return cnt
