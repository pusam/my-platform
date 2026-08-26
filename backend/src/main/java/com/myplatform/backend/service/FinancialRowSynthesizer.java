package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockFinancialData;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 한 종목의 최근 재무 행 여러 개를 <b>필드별로 합성</b>해 "쓸 수 있는 한 행"을 만든다 — 순수 함수.
 *
 * <p><b>왜 필요한가</b>(AUDIT 2026-08-21 R4): {@code stock_financial_data} 는 일별 스냅샷이고,
 * <b>같은 종목인데 행마다 일부 컬럼만 채워지는</b> 패턴이 실재한다. 수집기가 API 결측을
 * {@code BigDecimal.ZERO} 로 저장하고(그 자리에선 의도된 동작), 성장률은 배치 4단계가 나중에
 * 최신 행에만 덧쓰기 때문이다. 그래서 <b>가장 최신 행 하나만</b> 집으면 0 투성이 행을 물 수 있다.
 *
 * <p>실측 예(005930 삼성전자): 어떤 행엔 PBR/ROE 만, 다른 행엔 영업이익만, 최신 행엔
 * {@code debt_ratio=0.00} placeholder. 단일 행 픽이면 18점이어야 할 종목이 5점으로 떨어졌다.
 *
 * <p><b>규칙은 필드 성격에 따라 둘</b>:
 * <ul>
 *   <li><b>firstPositive</b> — 0·음수가 비현실적인 필드(PBR·부채비율·자본총계·PEG 등).
 *       0 은 무조건 placeholder 라 건너뛴다.</li>
 *   <li><b>firstNonZero</b> — 음수가 <b>의미를 갖는</b> 필드(ROE·영업이익·순이익·성장률).
 *       0 만 건너뛰고 <b>음수(적자·역성장)는 그대로 살린다</b> — 적자를 "값 없음"으로 지워
 *       흑자처럼 보이게 하면 안 된다(§4c).</li>
 * </ul>
 *
 * <p>성장률에 firstNonZero 를 쓰는 근거는 코드 안에 이미 있다 —
 * {@code StockFinancialDataCollector.calculateAndUpdateGrowthRates} 자체가
 * {@code (growth == null || growth == 0)} 를 "아직 계산 안 됨"으로 보고 재계산한다.
 * 즉 이 저장소에서 성장률 0 은 이미 placeholder 의미다.
 *
 * <p><b>⚠ composite 의 {@code scoreValueStability} 는 이 클래스를 쓰지 않는다.</b> 거기엔 같은 규칙이
 * 인라인으로 들어가 있고(2026-07 수정), 그쪽을 건드리면 <b>점수가 움직인다</b> — R4 는 "트랙만 잔존"
 * 이므로 의도적으로 트랙에만 적용한다. 규칙을 바꿀 일이 생기면 <b>두 곳을 같이</b> 바꿔야 한다.
 */
public final class FinancialRowSynthesizer {

    private FinancialRowSynthesizer() {}

    /**
     * 합성에 쓸 최근 행 수 — composite({@code findTop10ByStockCodeOrderByReportDateDesc})와 <b>동일하게 10</b>.
     *
     * <p>일부러 맞춘 값이다. 트랙이 5행만 보면 "7행 전에 진짜 값이 있는 종목"에서 composite 와 결론이
     * 갈린다 — 같은 종목을 두 화면이 다르게 채점하는 것이 이 저장소가 반복해서 겪은 결함 유형이다.
     */
    public static final int SYNTHESIS_ROWS = 10;

    /**
     * 최근 행들을 합성해 새 {@link StockFinancialData} 를 만든다. <b>입력 엔티티는 변경하지 않는다</b>
     * (JPA 영속 객체를 건드리면 의도치 않은 UPDATE 가 나간다).
     *
     * @param rows 한 종목의 행들. 순서 무관 — 내부에서 reportDate 내림차순 정렬한다.
     * @return 합성된 새 행. 입력이 비면 null.
     */
    public static StockFinancialData synthesize(List<StockFinancialData> rows) {
        if (rows == null || rows.isEmpty()) return null;

        List<StockFinancialData> desc = rows.stream()
                .filter(r -> r != null && r.getReportDate() != null)
                .sorted(Comparator.comparing(StockFinancialData::getReportDate).reversed())
                .toList();
        if (desc.isEmpty()) return null;

        StockFinancialData latest = desc.get(0);
        StockFinancialData out = new StockFinancialData();

        // 식별·메타는 최신 행 것을 그대로 — 합성 대상이 아니다.
        out.setId(latest.getId());
        out.setStockCode(latest.getStockCode());
        out.setStockName(latest.getStockName());
        out.setMarket(latest.getMarket());
        out.setReportDate(latest.getReportDate());
        out.setSector(latest.getSector());
        out.setMagicFormulaRank(latest.getMagicFormulaRank());

        // 0·음수가 비현실적 → firstPositive
        fill(desc, out, StockFinancialData::getCurrentPrice, StockFinancialData::setCurrentPrice, true);
        fill(desc, out, StockFinancialData::getMarketCap, StockFinancialData::setMarketCap, true);
        fill(desc, out, StockFinancialData::getPer, StockFinancialData::setPer, true);
        fill(desc, out, StockFinancialData::getPbr, StockFinancialData::setPbr, true);
        fill(desc, out, StockFinancialData::getPcr, StockFinancialData::setPcr, true);
        fill(desc, out, StockFinancialData::getPsr, StockFinancialData::setPsr, true);
        fill(desc, out, StockFinancialData::getPeg, StockFinancialData::setPeg, true);
        fill(desc, out, StockFinancialData::getBps, StockFinancialData::setBps, true);
        fill(desc, out, StockFinancialData::getDebtRatio, StockFinancialData::setDebtRatio, true);
        fill(desc, out, StockFinancialData::getCurrentRatio, StockFinancialData::setCurrentRatio, true);
        fill(desc, out, StockFinancialData::getTotalAssets, StockFinancialData::setTotalAssets, true);
        fill(desc, out, StockFinancialData::getTotalEquity, StockFinancialData::setTotalEquity, true);
        fill(desc, out, StockFinancialData::getTotalDebt, StockFinancialData::setTotalDebt, true);
        fill(desc, out, StockFinancialData::getDividendYield, StockFinancialData::setDividendYield, true);
        fill(desc, out, StockFinancialData::getDividendPayoutRatio, StockFinancialData::setDividendPayoutRatio, true);

        // 음수가 의미를 갖는다(적자·역성장) → firstNonZero
        fill(desc, out, StockFinancialData::getRoe, StockFinancialData::setRoe, false);
        fill(desc, out, StockFinancialData::getRoa, StockFinancialData::setRoa, false);
        fill(desc, out, StockFinancialData::getEps, StockFinancialData::setEps, false);
        fill(desc, out, StockFinancialData::getOperatingMargin, StockFinancialData::setOperatingMargin, false);
        fill(desc, out, StockFinancialData::getNetMargin, StockFinancialData::setNetMargin, false);
        fill(desc, out, StockFinancialData::getRevenue, StockFinancialData::setRevenue, false);
        fill(desc, out, StockFinancialData::getOperatingProfit, StockFinancialData::setOperatingProfit, false);
        fill(desc, out, StockFinancialData::getNetIncome, StockFinancialData::setNetIncome, false);
        fill(desc, out, StockFinancialData::getRevenueGrowth, StockFinancialData::setRevenueGrowth, false);
        fill(desc, out, StockFinancialData::getProfitGrowth, StockFinancialData::setProfitGrowth, false);
        fill(desc, out, StockFinancialData::getEpsGrowth, StockFinancialData::setEpsGrowth, false);

        return out;
    }

    private static void fill(List<StockFinancialData> desc, StockFinancialData out,
                             Function<StockFinancialData, BigDecimal> getter,
                             BiConsumer<StockFinancialData, BigDecimal> setter,
                             boolean positiveOnly) {
        setter.accept(out, positiveOnly ? firstPositive(desc, getter) : firstNonZero(desc, getter));
    }

    /** 첫 번째 양수 — 0·음수 placeholder 를 건너뛴다. 못 찾으면 null(0 으로 만들지 않는다). */
    static BigDecimal firstPositive(List<StockFinancialData> desc,
                                    Function<StockFinancialData, BigDecimal> getter) {
        for (StockFinancialData r : desc) {
            BigDecimal v = getter.apply(r);
            if (v != null && v.signum() > 0) return v;
        }
        return null;
    }

    /** 첫 번째 0 아닌 값 — 0 placeholder 만 건너뛰고 음수(적자·역성장)는 보존한다. */
    static BigDecimal firstNonZero(List<StockFinancialData> desc,
                                   Function<StockFinancialData, BigDecimal> getter) {
        for (StockFinancialData r : desc) {
            BigDecimal v = getter.apply(r);
            if (v != null && v.signum() != 0) return v;
        }
        return null;
    }
}
