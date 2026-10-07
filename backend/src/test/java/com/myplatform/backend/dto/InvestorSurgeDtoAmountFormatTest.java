package com.myplatform.backend.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장중 수급 급증 카드 금액 — 억 단위도 천 단위 쉼표(2026-10-07 화면 점검).
 * 재현: '누적 순매수 +3745.4억'(같은 카드의 만 단위는 '+1,200만'으로 쉼표가 있다).
 */
class InvestorSurgeDtoAmountFormatTest {

    private static String net(String eok) {
        InvestorSurgeDto d = new InvestorSurgeDto();
        d.setNetBuyAmount(new BigDecimal(eok));
        return d.getFormattedNetBuyAmount();
    }

    @Test
    @DisplayName("재현: 1,000억 이상은 쉼표 — '+3,745.4억'")
    void thousandsSeparator() {
        assertThat(net("3745.4")).isEqualTo("+3,745.4억");
        assertThat(net("12000")).isEqualTo("+12,000억");
        assertThat(net("-1063.44")).isEqualTo("-1,063.4억");
    }

    @Test
    @DisplayName("종전 표기는 그대로 — 소수 .0 은 정수, 1억 미만은 만 단위")
    void unchangedCases() {
        assertThat(net("2.0")).isEqualTo("+2억");
        assertThat(net("1.5")).isEqualTo("+1.5억");
        assertThat(net("0.12")).isEqualTo("+1,200만");
        assertThat(net("0")).isEqualTo("-");
    }
}
