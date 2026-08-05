package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-05 4축 감사에서 확정된 결함의 재현·회귀 테스트.
 *
 * <p>고치기 전에는 전부 실패해야 하는 케이스들이다. 각 테스트는 감사 발견 번호와
 * "무엇이 잘못됐는지"를 함께 적어 나중에 왜 이 가드가 있는지 추적 가능하게 한다.
 */
class RecommendationAuditFixTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 5);

    // ==================== ① 성장 트랙: 적자 축소가 "이익급증"으로 뒤집힘 ====================

    /**
     * 성장률 분모가 |직전값| 이라 <b>적자 축소가 큰 +성장률</b>로 나온다
     * (−100억 → −10억 = +90%). 흑자 가드가 없으면 "이익급증 8점"이 붙는다.
     * 같은 함정을 실적 트랙은 {@code EarningSurpriseService} 에서 이미 막았다(latest>0 필수).
     */
    @Test
    void 적자가_지속되면_이익성장_점수를_주지_않는다() {
        BigDecimal profitGrowth = BigDecimal.valueOf(90);   // -100억 → -10억
        BigDecimal latestProfit = BigDecimal.valueOf(-10);  // 여전히 적자

        int[] parts = RecommendationService.computeGrowthScoreParts(
                null, profitGrowth, null, latestProfit);

        assertThat(parts[1]).as("적자 지속이면 이익 성장 점수 0").isZero();
    }

    @Test
    void 적자가_지속되면_이익급증_태그를_붙이지_않는다() {
        List<String> tags = RecommendationService.growthTags(
                null, BigDecimal.valueOf(90), null, BigDecimal.valueOf(-10));

        assertThat(tags).as("적자 지속 종목에 '이익급증' 배지 금지").doesNotContain("이익급증");
    }

    @Test
    void 흑자면_이익성장_점수가_정상_부여된다() {
        int[] parts = RecommendationService.computeGrowthScoreParts(
                null, BigDecimal.valueOf(90), null, BigDecimal.valueOf(120));   // 흑자
        assertThat(parts[1]).isEqualTo(8);

        assertThat(RecommendationService.growthTags(
                null, BigDecimal.valueOf(90), null, BigDecimal.valueOf(120))).contains("이익급증");
    }

    @Test
    void 이익_정보가_없으면_기존대로_채점한다() {
        // null = 미수집. 흑자 여부를 알 수 없다고 성장 점수를 지우면 기존 표본이 통째로 사라진다.
        // 결측은 '적자로 확정'이 아니므로 종전 동작을 보존한다(§4c — 결측을 불리한 값으로도 위장 안 함).
        int[] parts = RecommendationService.computeGrowthScoreParts(
                null, BigDecimal.valueOf(90), null, null);
        assertThat(parts[1]).isEqualTo(8);
    }

    // ==================== ② 기술: 노후 히스토리 채점 금지 ====================

    /**
     * 수급 채점에는 노후 가드가 있는데(최신일 &lt; 직전 거래일이면 미채점) 기술엔 없어서,
     * 수집이 끊긴 종목의 두 달 전 봉으로 RSI·MA·과열이 계산됐다.
     */
    @Test
    void 최신봉이_오래되면_기술_미채점() {
        List<StockPriceHistory> stale = bars(TODAY.minusDays(60), 30);
        assertThat(RecommendationService.isPriceHistoryFresh(stale, TODAY.minusDays(1)))
                .as("60일 전에서 끊긴 히스토리는 기술 채점 불가").isFalse();
    }

    @Test
    void 최신봉이_직전_거래일이면_기술_채점_가능() {
        List<StockPriceHistory> fresh = bars(TODAY.minusDays(1), 30);
        assertThat(RecommendationService.isPriceHistoryFresh(fresh, TODAY.minusDays(1))).isTrue();
    }

    @Test
    void 오늘_봉까지_있으면_당연히_채점_가능() {
        assertThat(RecommendationService.isPriceHistoryFresh(bars(TODAY, 30), TODAY.minusDays(1))).isTrue();
    }

    @Test
    void 히스토리가_비었거나_날짜가_없으면_채점하지_않는다() {
        assertThat(RecommendationService.isPriceHistoryFresh(List.of(), TODAY.minusDays(1))).isFalse();
        assertThat(RecommendationService.isPriceHistoryFresh(null, TODAY.minusDays(1))).isFalse();

        List<StockPriceHistory> noDate = new ArrayList<>();
        noDate.add(StockPriceHistory.builder().closePrice(BigDecimal.TEN).build());
        assertThat(RecommendationService.isPriceHistoryFresh(noDate, TODAY.minusDays(1))).isFalse();
    }

    @Test
    void 기준일을_모르면_기존대로_통과시킨다() {
        // 거래일 달력 조회 실패 시 fail-open — 가드 때문에 전 종목이 미채점되는 게 더 위험하다.
        assertThat(RecommendationService.isPriceHistoryFresh(bars(TODAY.minusDays(60), 30), null)).isTrue();
    }

    // ==================== ③ 실적: 노후 재무 채점 금지 ====================

    /**
     * {@code findLatestTwoQuartersPerStock} 는 최신 2건을 뽑을 뿐 기준일 하한이 없어,
     * 수집이 멈춘 종목의 2년 전 재무가 매일 "오늘의 흑자전환 20점"으로 붙었다.
     * (120일 가드는 두 행 <b>사이 간격</b>만 본다 — 절대 시점은 못 본다.)
     */
    @Test
    void 재무_기준일이_너무_오래되면_실적_미채점() {
        assertThat(RecommendationService.isEarningsReportFresh(TODAY.minusDays(400), TODAY)).isFalse();
    }

    @Test
    void 최근_분기_재무는_실적_채점_가능() {
        assertThat(RecommendationService.isEarningsReportFresh(TODAY.minusDays(80), TODAY)).isTrue();
    }

    @Test
    void 재무_기준일이_없으면_기존대로_통과시킨다() {
        // 결측을 '오래됨'으로 단정하면 정상 종목까지 탈락한다 — fail-open 유지
        assertThat(RecommendationService.isEarningsReportFresh(null, TODAY)).isTrue();
    }

    @Test
    void 실적_노후_임계는_두_분기_남짓이다() {
        // 분기 공시 주기(약 90일)의 2배 + 지연 마진. 경계 근처 동작 고정.
        assertThat(RecommendationService.isEarningsReportFresh(
                TODAY.minusDays(RecommendationService.EARNINGS_MAX_AGE_DAYS - 1), TODAY)).isTrue();
        assertThat(RecommendationService.isEarningsReportFresh(
                TODAY.minusDays(RecommendationService.EARNINGS_MAX_AGE_DAYS + 1), TODAY)).isFalse();
    }

    // ==================== ④ 정배열 재검증: 봉 부족 시 판정 불가 ====================

    /**
     * 재검증 블록이 히스토리를 25봉으로 자르는데 정배열(MA5&gt;MA20&gt;MA60)은 60봉이 필요해
     * <b>항상 null → false</b> 였다. 그래서 정배열 종목은 실제로 정배열이 유지 중이어도
     * 예외 없이 태그가 지워지고 technical −2 를 맞았다(데이터 무관 100% 오발화).
     */
    @Test
    void 봉이_60개_미만이면_정배열_판정을_하지_않는다() {
        assertThat(RecommendementCanVerify(25)).as("25봉으로는 MA60 산출 불가 — 판정 보류").isFalse();
        assertThat(RecommendementCanVerify(59)).isFalse();
    }

    @Test
    void 봉이_60개_이상이면_정배열_판정이_가능하다() {
        assertThat(RecommendementCanVerify(60)).isTrue();
        assertThat(RecommendementCanVerify(120)).isTrue();
    }

    private static boolean RecommendementCanVerify(int barCount) {
        return RecommendationService.canVerifyArrangement(bars(TODAY, barCount));
    }

    // ==================== helper ====================

    /** 최신순(tradeDate DESC) 봉 목록 — 리포지토리 표준 정렬과 동일. */
    private static List<StockPriceHistory> bars(LocalDate latest, int count) {
        List<StockPriceHistory> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(StockPriceHistory.builder()
                    .stockCode("005930")
                    .tradeDate(latest.minusDays(i))
                    .closePrice(BigDecimal.valueOf(70000 + i))
                    .build());
        }
        return out;
    }
}
