package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매매 사유 이름 — {@link TradeReasonLabels}(2026-10-02).
 *
 * <p>재현: 모의 거래 내역에서 7/27 14:00 스윙 매수(SWING_FOREIGN)·15:20 정규장 청산(REGULAR_SESSION_CLOSE)이 "수동"으로
 * 보였다 — 예전 switch 에 봇 사유 8가지가 없어서 default 로 떨어졌다. 봇이 한 매매가 사람이 한 매매로 보이면 안 된다.
 */
class TradeReasonLabelsTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "SWING_FOREIGN, 스윙 매수(외국인)",
            "SWING_INSTITUTION, 스윙 매수(기관)",
            "CLOSING_BUY, 종가 매수",
            "SCALPING_ENTRY, 스캘핑 매수",
            "REGULAR_SESSION_CLOSE, 정규장 강제청산",
            "SCALPING_CLEARANCE, 스캘핑 청산(15:10)",
            "NXT_SESSION_CLOSE, NXT 방어 청산",
            "GAP_DOWN_EXIT, 갭하락 청산",
            "EARLY_EXIT, 갭업 미발생 조기청산",
            "STOP_LOSS, 손절",
            "TIME_CUT, 타임컷",
            "MANUAL, 수동"
    })
    void botReasonsAreNotManual(String code, String label) {
        assertThat(TradeReasonLabels.label(code)).isEqualTo(label);
    }

    @Test
    @DisplayName("모르는 코드는 '수동'이 아니라 코드 그대로 — 모르는 것을 사람이 한 매매로 단정하지 않는다")
    void unknownCodeIsShownAsIs() {
        assertThat(TradeReasonLabels.label("SOME_NEW_EXIT")).isEqualTo("SOME_NEW_EXIT");
    }

    @Test
    @DisplayName("null·빈 사유는 '-' — 예전 switch 는 null 에 NPE 를 냈다")
    void nullIsDash() {
        assertThat(TradeReasonLabels.label(null)).isEqualTo("-");
        assertThat(TradeReasonLabels.label(" ")).isEqualTo("-");
    }
}
