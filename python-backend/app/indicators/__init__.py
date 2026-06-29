"""차트 추세추종 지표 — 순수함수 패키지.

설계 원칙:
- 모든 함수는 순수(부수효과·I/O 없음). 입력은 float / list[float] 등 단순 타입 →
  pandas 없이 단위테스트 가능(Java computeOversoldScoreParts 패턴 미러).
- pykrx OHLCV 조회/캐시 등 I/O 는 services/chart_pattern_service 가 담당.
- 결측은 None 으로 정직하게(§4c) — 가짜값 생성 금지.
- 산식/가중치는 미검증(보조 시그널 전용). VERIFICATION_BACKLOG 참조.
"""
