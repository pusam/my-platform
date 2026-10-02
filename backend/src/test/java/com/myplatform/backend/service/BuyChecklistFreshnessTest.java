package com.myplatform.backend.service;

import com.myplatform.backend.dto.BuyChecklistDto;
import com.myplatform.backend.dto.BuyChecklistDto.ChecklistItem;
import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.StockConclusionDto;
import com.myplatform.backend.shortselling.ShortSellingTradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F5 — 체크리스트가 <b>노후·미확인</b>을 정상 통과로 표시하지 않는다(2026-09-17 감사).
 *
 * <p><b>고치려는 결함 네 가지</b>
 * <ul>
 *   <li>{@code isActive()} 의 true 를 그대로 "정상"으로 표시했다. 그 true 에는 <b>마스터 동기화 전
 *       fail-open</b>이 섞여 있어 "차단할 근거 없음"과 "거래 가능 확인"이 구분되지 않았다.</li>
 *   <li>연속매수는 목록에 코드가 있는지만 봤다. 목록은 <b>DB 최신일</b> 기준이라 수집이 멈추면 며칠 전
 *       연속매수가 계속 통과한다.</li>
 *   <li>공매도는 비율만 보고 <b>기준일</b>을 몰랐다. 死피드의 낮은 비율이 통과로 표시된다.
 *       (2026-10-02 부터 공매도는 KIS 거래 비중 <b>참고 항목</b>이다 — 판정하지 않고 값과 기준일만 보인다.)</li>
 *   <li>조회 실패({@code errorItem})가 {@code dataMissing} 없이 {@code passed=false} 라 "미확인"과
 *       "실제 미충족"이 분모에서 같게 취급됐다.</li>
 * </ul>
 *
 * <p>⚠ 결측을 <b>새 매수 차단 정책</b>으로 바꾸지 않는다 — 판정에서 빼고 그렇게 표시할 뿐이다.
 */
class BuyChecklistFreshnessTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String CODE = "005930";
    /** 2026-09-17(목) 14:00 — 마지막 마감 거래일은 9/16(수). */
    private static final Clock NOW = Clock.fixed(
            ZonedDateTime.of(2026, 9, 17, 14, 0, 0, 0, KST).toInstant(), KST);

    private StockStatusService statusService;
    private ShortSellingTradeService shortSellingTradeService;
    private InvestorTradeService investorTradeService;
    private CompositeSignalService compositeSignalService;
    private StockConclusionService conclusionService;
    private BuyChecklistService service;

    @BeforeEach
    void setUp() {
        statusService = mock(StockStatusService.class);
        shortSellingTradeService = mock(ShortSellingTradeService.class);
        investorTradeService = mock(InvestorTradeService.class);
        compositeSignalService = mock(CompositeSignalService.class);
        conclusionService = mock(StockConclusionService.class);

        // 기본은 전부 "정상·신선" — 각 테스트가 관심 항목만 바꾼다.
        when(statusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.ACTIVE);
        when(shortSellingTradeService.stockShare(anyString())).thenReturn(share("1.00", LocalDate.of(2026, 9, 16)));
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(List.of(consecutive(LocalDate.of(2026, 9, 16))));
        when(compositeSignalService.evaluate(anyString())).thenReturn(null);
        when(conclusionService.getConclusion(anyString())).thenReturn(
                StockConclusionDto.builder().stockCode(CODE).stockName("삼성전자")
                        .level(StockConclusionDto.Level.BUY).dataAvailable(true).currentlyValid(true).build());

        service = new BuyChecklistService(statusService, shortSellingTradeService, investorTradeService,
                compositeSignalService, conclusionService, new MarketCalendarService(), NOW);
    }

    private static ShortSellingTradeService.StockShare share(String volumeShare, LocalDate asOf) {
        return new ShortSellingTradeService.StockShare(true, asOf,
                volumeShare == null ? null : new BigDecimal(volumeShare), 1_000L, null, null);
    }

    private static ConsecutiveBuyDto consecutive(LocalDate endDate) {
        return ConsecutiveBuyDto.builder()
                .stockCode(CODE).investorType("FOREIGN").consecutiveDays(3)
                .startDate(endDate.minusDays(3)).endDate(endDate).build();
    }

    private ChecklistItem item(String key) {
        BuyChecklistDto dto = service.evaluate(CODE);
        return dto.getItems().stream().filter(i -> key.equals(i.getKey())).findFirst().orElseThrow();
    }

    // ==================== 거래 가능 상태 ====================

    @Nested
    @DisplayName("거래 가능 상태")
    class Tradable {

        @Test
        @DisplayName("마스터 동기화 전(UNVERIFIED)은 '정상'이 아니라 '확인 전' — 판정에서 제외한다")
        void unverifiedIsNotShownAsNormal() {
            when(statusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.UNVERIFIED);

            ChecklistItem it = item("tradable");

            assertThat(it.isDataMissing()).isTrue();
            assertThat(it.isPassed()).isFalse();
            assertThat(it.getValue()).doesNotContain("정상");
            assertThat(it.getNote()).contains("동기화");
        }

        @Test
        @DisplayName("동기화 후 확인되면 '정상' 통과")
        void activeIsNormal() {
            ChecklistItem it = item("tradable");

            assertThat(it.isPassed()).isTrue();
            assertThat(it.isDataMissing()).isFalse();
            assertThat(it.getValue()).isEqualTo("정상");
        }

        @Test
        @DisplayName("거래정지는 미충족(판정 불가가 아니다)")
        void haltedIsFailNotMissing() {
            when(statusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.HALTED);

            ChecklistItem it = item("tradable");

            assertThat(it.isPassed()).isFalse();
            assertThat(it.isDataMissing()).isFalse();
            assertThat(it.getValue()).contains("거래정지");
        }
    }

    // ==================== 연속매수 ====================

    @Nested
    @DisplayName("외국인/기관 연속매수")
    class ConsecutiveBuy {

        @Test
        @DisplayName("최신 거래일 기준이면 통과하고 기준일을 함께 알린다")
        void freshPasses() {
            ChecklistItem it = item("consecutiveBuy");

            assertThat(it.isPassed()).isTrue();
            assertThat(it.getAsOf()).contains("2026-09-16");
        }

        @Test
        @DisplayName("endDate 가 며칠 전이면 통과로 치지 않는다 — 수집 정체를 '연속매수 중'으로 표시하지 않는다")
        void staleEndDateIsNotPassed() {
            when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                    .thenReturn(List.of(consecutive(LocalDate.of(2026, 9, 8))));

            ChecklistItem it = item("consecutiveBuy");

            assertThat(it.isPassed()).isFalse();
            assertThat(it.isDataMissing()).isTrue();
            assertThat(it.getAsOf()).contains("2026-09-08");
            assertThat(it.getNote()).contains("노후");
        }

        @Test
        @DisplayName("전일(1거래일 지연)은 수집 주기 안이라 통과 — 당일치를 강요하지 않는다")
        void oneTradingDayLagStillPasses() {
            when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                    .thenReturn(List.of(consecutive(LocalDate.of(2026, 9, 15))));

            assertThat(item("consecutiveBuy").isPassed()).isTrue();
        }

        @Test
        @DisplayName("연속매수 종목이 아예 없으면 미충족이지 판정 불가가 아니다")
        void noMatchIsFailNotMissing() {
            when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt())).thenReturn(List.of());

            ChecklistItem it = item("consecutiveBuy");

            assertThat(it.isPassed()).isFalse();
            assertThat(it.isDataMissing()).isFalse();
        }
    }

    // ==================== 공매도 (거래 비중 · 참고) ====================

    @Nested
    @DisplayName("공매도 거래 비중 — 참고 항목(2026-10-02)")
    class ShortSelling {

        @Test
        @DisplayName("값과 기준일을 보여 주되 판정하지 않는다 — 언제 기준인지 숨기지 않는다")
        void showsValueAndAsOfWithoutJudging() {
            ChecklistItem it = item("shortSelling");

            assertThat(it.isInformational()).isTrue();
            assertThat(it.isPassed()).isFalse();
            assertThat(it.isDataMissing()).isFalse();
            assertThat(it.getValue()).isEqualTo("1.00%");
            assertThat(it.getAsOf()).contains("2026-09-16");
            assertThat(it.getNote()).contains("잔고가 아니다").contains("판정에 쓰지 않는다");
        }

        @Test
        @DisplayName("오래된 값도 그대로 기준일과 함께 — 참고 표시라 노후로 '판정 불가'를 늘리지 않는다")
        void oldValueKeepsItsDate() {
            when(shortSellingTradeService.stockShare(anyString())).thenReturn(share("3.00", LocalDate.of(2026, 8, 20)));

            ChecklistItem it = item("shortSelling");

            assertThat(it.isInformational()).isTrue();
            assertThat(it.getAsOf()).contains("2026-08-20");
            assertThat(it.isDataMissing()).isFalse();
        }

        @Test
        @DisplayName("실측 0% 는 0.00% — 결측과 다르게 보인다")
        void measuredZeroIsShown() {
            when(shortSellingTradeService.stockShare(anyString())).thenReturn(share("0", LocalDate.of(2026, 9, 16)));

            assertThat(item("shortSelling").getValue()).isEqualTo("0.00%");
        }

        @Test
        @DisplayName("조회는 됐는데 값이 없으면 '값 없음' — 0% 로 보이지 않게")
        void noValueIsSaidSo() {
            when(shortSellingTradeService.stockShare(anyString())).thenReturn(share(null, null));

            ChecklistItem it = item("shortSelling");

            assertThat(it.getValue()).isEqualTo("값 없음");
            assertThat(it.getAsOf()).isNull();
        }

        @Test
        @DisplayName("조회 실패는 '조회 실패' — 판정 불가 개수에는 안 들어간다(참고 항목)")
        void failureIsSaidSo() {
            when(shortSellingTradeService.stockShare(anyString())).thenReturn(
                    new ShortSellingTradeService.StockShare(false, null, null, null, null, "KIS 응답 없음"));

            BuyChecklistDto dto = service.evaluate(CODE);
            ChecklistItem it = dto.getItems().stream().filter(i -> "shortSelling".equals(i.getKey())).findFirst().orElseThrow();

            assertThat(it.getValue()).isEqualTo("조회 실패");
            assertThat(dto.getSummary()).doesNotContain("판정 불가");
        }
    }

    // ==================== 조회 실패 / 분모 일관성 ====================

    @Nested
    @DisplayName("조회 실패와 분모")
    class FailureAndDenominator {

        @Test
        @DisplayName("예외로 체크 불가면 dataMissing — '미확인'과 '실제 미충족'을 분모에서 구분한다")
        void exceptionBecomesDataMissing() {
            when(compositeSignalService.evaluate(anyString()))
                    .thenThrow(new IllegalStateException("DB down"));

            ChecklistItem it = item("compositeSignal");

            assertThat(it.isDataMissing()).isTrue();
            assertThat(it.isPassed()).isFalse();
            assertThat(it.getValue()).contains("체크 불가");
        }

        @Test
        @DisplayName("판정 불가 항목은 분모에서 빠지고 요약 문구와 숫자가 일치한다")
        void summaryMatchesDenominator() {
            when(statusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.UNVERIFIED);
            when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                    .thenReturn(List.of(consecutive(LocalDate.of(2026, 8, 20))));

            BuyChecklistDto dto = service.evaluate(CODE);
            long missing = dto.getItems().stream().filter(ChecklistItem::isDataMissing).count();
            long informational = dto.getItems().stream().filter(ChecklistItem::isInformational).count();

            assertThat(missing).isEqualTo(2);
            assertThat(informational).isEqualTo(1);
            assertThat(dto.getTotalCount()).isEqualTo(dto.getItems().size() - (int) missing - (int) informational);
            assertThat(dto.getSummary()).contains("판정 불가 2개 제외");
            assertThat(dto.getPassedCount()).isLessThanOrEqualTo(dto.getTotalCount());
        }

        @Test
        @DisplayName("결측이 새 매수 차단이 되지는 않는다 — 판정에서 빠질 뿐")
        void missingDoesNotBecomeBlockingPolicy() {
            when(statusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.UNVERIFIED);

            BuyChecklistDto dto = service.evaluate(CODE);

            assertThat(dto.getRecommendation()).isNotNull();
            assertThat(dto.getItems()).anyMatch(ChecklistItem::isPassed);
        }
    }
}
