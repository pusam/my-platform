package com.myplatform.backend.service;

import java.util.Map;

/**
 * 매매 사유 코드 → 화면 이름 — 모의·실전 거래 내역이 같이 쓰는 단일 출처 (2026-10-02).
 *
 * <p>예전엔 두 서비스({@code VirtualTradeService}·{@code RealTradeService})가 같은 switch 를 따로 들고 있었고, 봇이 나중에
 * 쓰기 시작한 사유(스윙 매수·종가 매수·정규장 강제청산·스캘핑 청산·갭 청산 등 8가지)가 빠져 전부 <b>"수동"</b>으로 보였다 —
 * 봇이 한 매매가 사람이 한 매매처럼 보인 것이다(화면 점검 2026-10-02: 7/27 14:00 스윙 매수·15:20 정규장 청산이 "수동").
 *
 * <p>모르는 코드는 "수동"이 아니라 <b>코드 그대로</b> 보인다 — 모르는 것을 사람이 한 매매로 단정하지 않는다(§4c).
 * 청산 사유 이름은 봇 성과 화면({@code BotPerformanceService.EXIT_REASON_LABELS})과 맞췄다.
 */
public final class TradeReasonLabels {

    private TradeReasonLabels() {}

    private static final Map<String, String> LABELS = Map.ofEntries(
            // 진입
            Map.entry("AUTO_BUY", "자동매수"),
            Map.entry("SCALPING_ENTRY", "스캘핑 매수"),
            Map.entry("SWING_FOREIGN", "스윙 매수(외국인)"),
            Map.entry("SWING_INSTITUTION", "스윙 매수(기관)"),
            Map.entry("CLOSING_BUY", "종가 매수"),
            // 청산
            Map.entry("STOP_LOSS", "손절"),
            Map.entry("TAKE_PROFIT", "익절"),
            Map.entry("TAKE_PROFIT_HALF", "1차익절(절반)"),
            Map.entry("TRAILING_STOP", "트레일링스탑"),
            Map.entry("TIME_CUT", "타임컷"),
            Map.entry("END_OF_DAY", "장마감청산"),
            Map.entry("AUTO_SELL", "자동매도"),
            Map.entry("SCALPING_CLEARANCE", "스캘핑 청산(15:10)"),
            Map.entry("REGULAR_SESSION_CLOSE", "정규장 강제청산"),
            Map.entry("NXT_SESSION_CLOSE", "NXT 방어 청산"),
            Map.entry("GAP_DOWN_EXIT", "갭하락 청산"),
            Map.entry("EARLY_EXIT", "갭업 미발생 조기청산"),
            // 사람
            Map.entry("MANUAL", "수동")
    );

    /** 화면 이름. null·빈 값은 "-", 모르는 코드는 코드 그대로. */
    public static String label(String reason) {
        if (reason == null || reason.isBlank()) return "-";
        return LABELS.getOrDefault(reason, reason);
    }
}
