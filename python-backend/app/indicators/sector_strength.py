"""섹터 상대강도 — '덜 빠지는 섹터' 선별 (지표 7).

발굴 유니버스 필터용. 섹터지수 = 기존 16섹터 구성원(SectorStockConfig, Java 소유)의
동일가중 평균 수익률(합성지수). 상대강도 = 섹터수익률 - 시장(KOSPI)수익률.
rel 이 높을수록(= 덜 빠지거나 더 오른) 상위.

순수함수: 수익률 입력만 받음. pykrx OHLCV 조회는 services 가 담당.
"""
from typing import Optional


def pct_return(first: float, last: float) -> Optional[float]:
    """기간수익률(%) = (last/first - 1)*100. first<=0 이면 None."""
    if first is None or last is None or first <= 0:
        return None
    return (last / first - 1) * 100.0


def equal_weight_return(member_returns: list[Optional[float]]) -> Optional[float]:
    """섹터 동일가중 평균수익률 — 결측(None) 종목은 제외. 전부 결측이면 None."""
    vals = [r for r in member_returns if r is not None]
    if not vals:
        return None
    return sum(vals) / len(vals)


def relative_strength(sector_return: Optional[float],
                      market_return: Optional[float]) -> Optional[float]:
    """상대강도 = 섹터수익률 - 시장수익률(%p). 둘 중 결측이면 None."""
    if sector_return is None or market_return is None:
        return None
    return sector_return - market_return


def rank_sectors(sector_rel: dict[str, Optional[float]]) -> list[dict]:
    """섹터→상대강도 dict 를 rel desc(덜 빠지는 섹터 우선)로 랭킹.

    rel 결측 섹터는 랭킹에서 제외(위장 0 금지). 반환: [{sector, rel_strength, rank}, ...].
    """
    valid = [(s, r) for s, r in sector_rel.items() if r is not None]
    valid.sort(key=lambda x: x[1], reverse=True)
    return [{"sector": s, "rel_strength": round(r, 2), "rank": i + 1}
            for i, (s, r) in enumerate(valid)]
