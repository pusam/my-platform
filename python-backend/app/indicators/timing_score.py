"""매수후보 타이밍 스코어 — 지표 1~6 결합 (추세 안의 mean-reversion 타이밍).

momentum 점수가 아니다. "정배열 추세 + 눌림목 진입 타이밍"을 0~10 보조 점수로 합산.
가중치는 미검증(보조 시그널 전용) — 백테스트로 검증 전 실거래/봇 노출 금지.
위험필터(지표 5)에 걸리면 점수 미산출(None) = 매수 제외.
"""
from typing import Optional

# 보조 점수 가중치 (미검증 — VERIFICATION_BACKLOG '차트기법 스코어러 백테스트' 참조)
W_ALIGNMENT_FULL = 3      # 완전 정배열
W_ENVELOPE_TOUCH = 3      # 엔벨로프 하단 눌림목 진입
W_CENTER_REBOUND = 2      # 중심선 지지 반등
W_BOX_PULLBACK = 2        # 박스 돌파 후 눌림목
W_OVERHEAT_PENALTY = -2   # 이격도 과열(추격 방지 감점)
MAX_SCORE = 10            # 3+3+2+2 (감점 전 상한)


def timing_score(aligned: bool, alignment_strength: int, alignment_max: int,
                 envelope_touch: bool, center_rebound: bool,
                 box_signal: bool, overheated: bool,
                 risk_excluded: bool) -> dict:
    """타이밍 보조 점수 + 사람이 읽는 시그널 태그.

    risk_excluded=True 면 score=None(매수 제외) — 위장 점수 만들지 않음(§4c).
    부분 정배열은 alignment_strength/alignment_max 비율로 가산.
    """
    if risk_excluded:
        return {"score": None, "risk_excluded": True, "signals": ["위험필터제외"]}

    signals: list[str] = []
    score = 0.0

    if aligned:
        score += W_ALIGNMENT_FULL
        signals.append("정배열")
    elif alignment_max > 0 and alignment_strength > 0:
        score += W_ALIGNMENT_FULL * (alignment_strength / alignment_max)
        signals.append(f"부분정배열({alignment_strength}/{alignment_max})")

    if envelope_touch:
        score += W_ENVELOPE_TOUCH
        signals.append("엔벨로프눌림")
    if center_rebound:
        score += W_CENTER_REBOUND
        signals.append("중심선반등")
    if box_signal:
        score += W_BOX_PULLBACK
        signals.append("박스눌림목")
    if overheated:
        score += W_OVERHEAT_PENALTY
        signals.append("과열주의")

    score = max(0, min(MAX_SCORE, round(score)))
    return {"score": int(score), "max": MAX_SCORE,
            "risk_excluded": False, "signals": signals}
