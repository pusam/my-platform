package com.myplatform.backend.util;

import com.myplatform.backend.entity.InvestorStockDaily;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * 연속 순매수를 세는 날 — 확정치가 있는 날은 확정치로 정한다(2026-11-06). 순수.
 *
 * <p>순위 행은 그 종목이 그날 상위 30 에 든 날만 있어 순위 밖으로 밀린 날 연속이 끊겼다 — 11/5 기관 신한지주는 실제 7일 연속인데 1일, 외국인 '2일 이상'이
 * 5종목(확정치로는 19종목)이었다. 확정치 표는 순매수·순매도를 날마다 담으므로 순매수면 그날을 넣고 아니면 뺀다. 칸이 비었거나 그날 행이 없으면 모르는 것이라
 * 순위 행을 그대로 둔다.
 */
public final class ConfirmedNetBuys {

    private ConfirmedNetBuys() {
    }

    /** 그 투자자의 확정 순매수(억원) — 칸이 비었거나 확정치 표에 없는 투자자(개인)면 null. */
    public static BigDecimal netOf(InvestorStockDaily row, String investorType) {
        return switch (investorType) {
            case "FOREIGN" -> row.getForeignNet();
            case "INSTITUTION" -> row.getInstitutionNet();
            case "PENSION" -> row.getPensionNet();
            default -> null;
        };
    }

    /** 순매수한 날 모음에 확정치 한 행을 덮는다 — 순매수면 넣고 아니면 뺀다, 모르면 그대로. */
    public static void overlay(Set<LocalDate> buyDates, InvestorStockDaily row, String investorType) {
        BigDecimal net = netOf(row, investorType);
        if (net == null) return;
        if (net.signum() > 0) {
            buyDates.add(row.getTradeDate());
        } else {
            buyDates.remove(row.getTradeDate());
        }
    }
}
