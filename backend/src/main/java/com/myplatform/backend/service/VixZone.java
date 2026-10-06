package com.myplatform.backend.service;

/**
 * VIX 구간 이름 — 단일 출처(2026-10-06 화면 점검). 화면 공용 기준(frontend composables/useMarketStatus.js VIX_TIERS)과 같은
 * 5단계다. 예전엔 같은 VIX 15.52 가 글로벌 화면 배지 '보통', 종합 방향 문장·간밤 미국장 카드 '안정'으로 갈렸다 — 백엔드 문장
 * 두 곳이 20 미만을 전부 '안정'이라 했다. 표시용 이름이고 점수 기여(GlobalFuturesService 의 VIX 가감)와는 별개다.
 */
final class VixZone {
    private VixZone() {}

    static String label(double vix) {
        if (vix >= 30) return "극심한 공포";
        if (vix >= 25) return "공포";
        if (vix >= 20) return "경계";
        if (vix >= 15) return "보통";
        return "안정";
    }
}
