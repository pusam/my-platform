package com.myplatform.backend.dartfinancial;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 지배주주 순이익 — DART 정기보고서로 최근 4분기(TTM) 지배주주 순이익과 지배지분 자본을 만든다(2026-09-30).
 * 전부 부작용 없는 static 함수라 {@code ControllingEarningsTest} 로 고정한다.
 *
 * <p><b>왜</b>: KIS 손익계산서의 순이익({@code thtr_ntin})은 연결 당기순이익 — <b>비지배지분 몫까지 포함</b>이고, KIS 에는
 * 지배주주 순이익·지배지분 자본 필드가 없다(공식 샘플 필드 목록). 그 순이익으로 PER 을 만들면 지주사·그룹사가 2~4배
 * 싸 보인다(다우기술 PER 0.9 vs 지배 기준 2.1). DART 전체 재무제표({@code fnlttSinglAcntAll})는 지배주주 귀속 순이익과
 * 지배지분 자본을 XBRL 표준 계정으로 준다.
 *
 * <p><b>TTM = 최신 누적 + 직전 연도 연간 − 전년 동기 누적</b>: 분·반기 보고서 한 건이 당기 누적과 <b>전년 동기 누적</b>을
 * 같이 주므로 보고서 두 건(최신 분·반기 + 직전 사업보고서)이면 된다. 다우기술 2026 반기: 4,923.8 + 5,051.8 − 2,487.4
 * = 7,488.2억 — 네이버 분기 지배주주순이익 4개 합과 같다. 최신이 사업보고서면 그 연간값이 곧 TTM 이다.
 *
 * <p><b>§4c</b>: 필요한 값이 하나라도 없거나, 두 보고서의 연결/별도 구분이 다르거나, 최신 보고서가 오래됐으면 모른다(null)
 * — 호출부는 종전 정의로 남는다. 추정 비중을 곱해 만들지 않는다.
 */
public final class ControllingEarnings {

    private ControllingEarnings() {}

    /** 원 → 억원. */
    static final BigDecimal EOK = new BigDecimal("100000000");

    /** 최신 보고서 접수일이 이보다 오래면 TTM 을 만들지 않는다 — 분기 원본 노후 기준과 같은 200일. */
    public static final long MAX_REPORT_AGE_DAYS = 200;

    static final String ID_CTRL_NET_INCOME = "ifrs-full_ProfitLossAttributableToOwnersOfParent";
    static final String ID_NET_INCOME = "ifrs-full_ProfitLoss";
    static final String ID_CTRL_EQUITY = "ifrs-full_EquityAttributableToOwnersOfParent";
    static final String ID_EQUITY = "ifrs-full_Equity";

    /** 정기보고서 코드 — 한 회계연도 안의 순서와 12월 결산 기준 제출 기한(기간 말 + 45일 / 사업보고서 + 90일). */
    public enum ReportCode {
        Q1("11013", 1, 3, 45),
        H1("11012", 2, 6, 45),
        Q3("11014", 3, 9, 45),
        FY("11011", 4, 12, 90);

        public final String code;
        final int order;
        final int endMonth;
        final int dueDays;

        ReportCode(String code, int order, int endMonth, int dueDays) {
            this.code = code;
            this.order = order;
            this.endMonth = endMonth;
            this.dueDays = dueDays;
        }

        public boolean annual() {
            return this == FY;
        }

        public static ReportCode of(String code) {
            for (ReportCode c : values()) {
                if (c.code.equals(code)) return c;
            }
            throw new IllegalArgumentException("unknown reprt_code: " + code);
        }
    }

    /** 보고서 키 — 사업연도 + 보고서 코드. 같은 연도 안에서는 1분기 < 반기 < 3분기 < 사업보고서. */
    public record ReportKey(int year, ReportCode code) implements Comparable<ReportKey> {
        @Override
        public int compareTo(ReportKey o) {
            return year != o.year ? Integer.compare(year, o.year) : Integer.compare(code.order, o.code.order);
        }

        /** 12월 결산 기준 제출 기한 — 조회 계획용(이 날 이후에 조회한다). */
        public LocalDate dueDate() {
            LocalDate periodEnd = YearMonth.of(year, code.endMonth).atEndOfMonth();
            return periodEnd.plusDays(code.dueDays);
        }

        /** TTM 에 필요한 직전 사업보고서 — 사업보고서 자신이면 null. */
        public ReportKey priorAnnual() {
            return code.annual() ? null : new ReportKey(year - 1, ReportCode.FY);
        }
    }

    /** 보고서 한 건에서 뽑은 값(억원). 누적 = 분·반기는 연초부터, 사업보고서는 연간. */
    public record Figures(BigDecimal ctrlNetIncome, BigDecimal ctrlNetIncomePrev, BigDecimal totalNetIncome,
                          BigDecimal ctrlEquity, BigDecimal totalEquity, LocalDate filedOn) {}

    /** 저장된 보고서 한 건 — TTM 계산의 입력. */
    public record Report(ReportKey key, String fsDiv, BigDecimal ctrlNetIncome, BigDecimal ctrlNetIncomePrev,
                         BigDecimal ctrlEquity, LocalDate filedOn) {}

    /** 최근 4분기 지배주주 순이익(억원)·최신 지배지분 자본(억원). 둘 중 하나만 알 수 있으면 나머지는 null. */
    public record Ttm(BigDecimal netIncome, BigDecimal equity, ReportKey basedOn) {}

    // ==================== 파싱 ====================

    /**
     * {@code fnlttSinglAcntAll} 응답의 {@code list} → 값. 손익 계정은 손익계산서(IS)를 먼저 보고 없으면 포괄손익계산서(CIS) —
     * 다우기술·F&F홀딩스는 지배주주 귀속 순이익이 CIS 에만 있다(삼성전자는 IS). 연결({@code consolidated})이 아니면
     * 비지배지분이 없으므로 지배주주 = 당기순이익·자본총계다. 표준 계정 ID 가 없으면(비표준 계정) 그 값은 모른다(null).
     */
    public static Figures parse(JsonNode list, ReportCode code, boolean consolidated) {
        if (list == null || !list.isArray() || list.isEmpty()) {
            return new Figures(null, null, null, null, null, null);
        }
        JsonNode ctrlNi = incomeRow(list, consolidated ? ID_CTRL_NET_INCOME : ID_NET_INCOME);
        JsonNode totalNi = incomeRow(list, ID_NET_INCOME);
        JsonNode ctrlEq = balanceRow(list, consolidated ? ID_CTRL_EQUITY : ID_EQUITY);
        JsonNode totalEq = balanceRow(list, ID_EQUITY);
        return new Figures(
                cumulative(ctrlNi, code), previousCumulative(ctrlNi, code), cumulative(totalNi, code),
                ctrlEq == null ? null : eok(ctrlEq.path("thstrm_amount").asText(null)),
                totalEq == null ? null : eok(totalEq.path("thstrm_amount").asText(null)),
                filedOn(list.get(0).path("rcept_no").asText(null)));
    }

    private static JsonNode incomeRow(JsonNode list, String accountId) {
        JsonNode cis = null;
        for (JsonNode row : list) {
            if (!accountId.equals(row.path("account_id").asText())) continue;
            String sj = row.path("sj_div").asText();
            if ("IS".equals(sj)) return row;
            if ("CIS".equals(sj) && cis == null) cis = row;
        }
        return cis;
    }

    private static JsonNode balanceRow(JsonNode list, String accountId) {
        for (JsonNode row : list) {
            if ("BS".equals(row.path("sj_div").asText()) && accountId.equals(row.path("account_id").asText())) {
                return row;
            }
        }
        return null;
    }

    /** 당기 누적 — 사업보고서는 연간(thstrm_amount), 분·반기는 누적(thstrm_add_amount), 1분기는 3개월이 곧 누적. */
    static BigDecimal cumulative(JsonNode row, ReportCode code) {
        if (row == null) return null;
        if (code.annual()) return eok(row.path("thstrm_amount").asText(null));
        BigDecimal add = eok(row.path("thstrm_add_amount").asText(null));
        if (add != null || code != ReportCode.Q1) return add;
        return eok(row.path("thstrm_amount").asText(null));
    }

    /** 전년 동기 누적 — 사업보고서는 전년 연간(frmtrm_amount), 분·반기는 frmtrm_add_amount, 1분기는 frmtrm_q_amount 도 된다. */
    static BigDecimal previousCumulative(JsonNode row, ReportCode code) {
        if (row == null) return null;
        if (code.annual()) return eok(row.path("frmtrm_amount").asText(null));
        BigDecimal add = eok(row.path("frmtrm_add_amount").asText(null));
        if (add != null || code != ReportCode.Q1) return add;
        return eok(row.path("frmtrm_q_amount").asText(null));
    }

    /** 원 단위 문자열 → 억원(소수 2자리). 빈 값·"-"·숫자 아님 = 모름(null) — 0 으로 만들지 않는다. */
    static BigDecimal eok(String won) {
        if (won == null) return null;
        String s = won.replace(",", "").trim();
        if (s.isEmpty() || "-".equals(s)) return null;
        try {
            return new BigDecimal(s).divide(EOK, 2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 접수번호 앞 8자리 = 접수일(YYYYMMDD). */
    static LocalDate filedOn(String rceptNo) {
        if (rceptNo == null || rceptNo.length() < 8) return null;
        try {
            return LocalDate.parse(rceptNo.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ==================== TTM ====================

    /**
     * 저장된 보고서 → 최근 4분기 지배주주 순이익·최신 지배지분 자본. 알 수 없으면 null.
     * 최신 보고서가 분·반기면 직전 사업보고서가 있어야 하고, 둘의 연결/별도 구분이 같아야 한다(섞으면 정의가 갈린다).
     */
    public static Ttm ttm(List<Report> reports, LocalDate today) {
        if (reports == null || reports.isEmpty()) return null;
        Report latest = reports.stream().max(Comparator.comparing(Report::key)).orElseThrow();
        if (latest.filedOn() == null || latest.filedOn().isBefore(today.minusDays(MAX_REPORT_AGE_DAYS))) {
            return null;
        }
        BigDecimal netIncome;
        if (latest.key().code().annual()) {
            netIncome = latest.ctrlNetIncome();
        } else {
            ReportKey priorKey = latest.key().priorAnnual();
            Report prior = reports.stream().filter(r -> r.key().equals(priorKey)).findFirst().orElse(null);
            boolean usable = prior != null && latest.fsDiv() != null && latest.fsDiv().equals(prior.fsDiv())
                    && latest.ctrlNetIncome() != null && latest.ctrlNetIncomePrev() != null
                    && prior.ctrlNetIncome() != null;
            netIncome = usable
                    ? latest.ctrlNetIncome().add(prior.ctrlNetIncome()).subtract(latest.ctrlNetIncomePrev())
                    : null;
        }
        if (netIncome == null && latest.ctrlEquity() == null) return null;
        return new Ttm(netIncome, latest.ctrlEquity(), latest.key());
    }

    // ==================== 조회 계획 ====================

    /**
     * 조회 후보 — 제출 기한이 지난 가장 최근 보고서부터 거꾸로 {@code count} 개(12월 결산 기준). 기한 전에 미리 낸
     * 회사는 기한 다음 회차에 잡힌다(기한 전 반복 조회로 호출을 낭비하지 않는다).
     */
    public static List<ReportKey> candidates(LocalDate today, int count) {
        List<ReportKey> out = new ArrayList<>();
        ReportCode[] order = {ReportCode.FY, ReportCode.Q3, ReportCode.H1, ReportCode.Q1};
        for (int year = today.getYear(); year >= today.getYear() - 2 && out.size() < count; year--) {
            for (ReportCode code : order) {
                ReportKey key = new ReportKey(year, code);
                if (!key.dueDate().isAfter(today) && out.size() < count) {
                    out.add(key);
                }
            }
        }
        return out;
    }
}
