package com.myplatform.backend.util;

/**
 * 종목코드 형식 판정 — 순수 함수, 단일 출처(2026-09-23).
 *
 * <p>기준은 KIS 종목마스터 규약과 같은 <b>6자리 영숫자</b>(대문자)다. CLAUDE.md §4c 의 마스터 항목대로
 * {@code \d{6}} 으로 좁히면 영문이 섞인 신형 코드(실측 {@code 0120G0} 삼양바이오팜 · {@code 0088M0} 메쥬)와
 * 종류주식이 <b>무음으로</b> 빠진다 — 형식 검사는 "틀린 걸 거르는" 쪽이지 "맞는 걸 버리는" 쪽이면 안 된다.
 *
 * <p><b>왜 만들었나</b>: 같은 규칙이 이미 여러 곳에 흩어져 있고(그중 하나는 {@code \d{6}} 이라 틀렸다),
 * 이번에 두 곳(스크리너 경로 가드·재무 수집 유니버스)을 더 붙여야 했다. 복사를 하나 더 늘리지 않으려고
 * 여기로 모았다. 기존 사본들을 이리로 옮기는 것은 별도 작업이다.
 */
public final class StockCodeFormat {

    private StockCodeFormat() {}

    /** 6자리 영숫자(대문자)면 true. null·빈 문자열·경로 조각("finance" 등)은 false. */
    public static boolean isValid(String code) {
        return code != null && code.matches("[0-9A-Z]{6}");
    }
}
