package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockConclusionDto;
import com.myplatform.backend.dto.StockConclusionDto.Level;
import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F4 / F6 — 오래된 추천을 현재 매수 결론으로 재사용하지 않는다(2026-09-17 감사).
 *
 * <p><b>F4 고치려는 결함</b>: {@code getConclusion} 은 종목별 <b>가장 최근</b> 스냅샷이 있기만 하면
 * 시점 검사 없이 결론을 만들었다. 스냅샷 정리는 7일이라 <b>며칠 전 80점</b>이 오늘의 "매수 적기"가 되고,
 * 목표가·손절가는 <b>오늘 시세</b>로 새로 계산돼 과거 판단과 현재 가격이 한 카드에 섞였다. 거래정지
 * 상태를 보는 게이트도 없었다.
 *
 * <p><b>신선도 기준</b>은 경과 분이 아니라 <b>거래일</b>이다 — 스냅샷은 거래일에만 만들어지므로
 * 금요일 스냅샷은 주말·월요일 장전에도 "가장 최근 장"의 결론으로 유효하다. 단순 TTL 로 무효화하면
 * 주말 내내 결론이 사라진다.
 *
 * <p><b>F6</b>: 수급≥15·기술&lt;8 만으로 총점과 무관하게 BUY 를 만들던 규칙을 55 컷과 맞춘다.
 */
class StockConclusionStalenessTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String CODE = "005930";

    private RecommendationSnapshotRepository snapshotRepository;
    private StockStatusService stockStatusService;
    private StockConclusionService service;

    @BeforeEach
    void setUp() {
        snapshotRepository = mock(RecommendationSnapshotRepository.class);
        stockStatusService = mock(StockStatusService.class);
        when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.ACTIVE);
        service = newService(at(LocalDate.of(2026, 9, 17), LocalTime.of(14, 0)));
    }

    private static Clock at(LocalDate d, LocalTime t) {
        return Clock.fixed(ZonedDateTime.of(d, t, KST).toInstant(), KST);
    }

    /** 결론 서비스는 의존이 많다 — 관심사(스냅샷·상태·시계) 외에는 전부 mock/null 로 둔다. */
    private StockConclusionService newService(Clock clock) {
        StockConclusionService s = mock(StockConclusionService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(s, "snapshotRepository", snapshotRepository);
        ReflectionTestUtils.setField(s, "stockStatusService", stockStatusService);
        ReflectionTestUtils.setField(s, "marketCalendar", new MarketCalendarService());
        ReflectionTestUtils.setField(s, "clock", clock);
        return s;
    }

    private RecommendationSnapshot snapshot(int total, LocalDateTime at) {
        return snapshot(total, 10, 10, at);
    }

    private RecommendationSnapshot snapshot(int total, int supplyDemand, int technical, LocalDateTime at) {
        RecommendationSnapshot s = new RecommendationSnapshot();
        s.setStockCode(CODE);
        s.setStockName("삼성전자");
        s.setTotalScore(total);
        s.setSupplyDemand(supplyDemand);
        s.setTechnical(technical);
        s.setEarnings(10);
        s.setSectorMomentum(10);
        s.setValueStability(8);
        s.setSnapshotAt(at);
        return s;
    }

    private StockConclusionDto conclusionFor(RecommendationSnapshot snap) {
        when(snapshotRepository.findLatestByStockCode(anyString())).thenReturn(Optional.of(snap));
        return service.getConclusion(CODE);
    }

    // ==================== F4 — 노후 스냅샷 ====================

    @Nested
    @DisplayName("노후 스냅샷")
    class Stale {

        @Test
        @DisplayName("며칠 전 80점은 오늘의 '매수 적기'가 아니다 — 현재형 권고와 신규 매매계획을 내지 않는다")
        void oldStrongBuyIsNotTodaysRecommendation() {
            var dto = conclusionFor(snapshot(80, LocalDateTime.of(2026, 9, 11, 17, 0)));

            assertThat(dto.getLevel()).isEqualTo(Level.WAIT);
            assertThat(dto.isCurrentlyValid()).isFalse();
            assertThat(dto.getStaleReason()).contains("2026-09-11");
            assertThat(dto.getTradePlan()).as("과거 판단으로 오늘 목표가·손절가를 만들지 않는다").isNull();
            assertThat(dto.getHeadline()).contains("과거");
            assertThat(dto.getDataSessionDate()).isEqualTo(LocalDate.of(2026, 9, 11));
        }

        @Test
        @DisplayName("과거 등급은 이력으로 남기되 현재형으로 말하지 않는다")
        void pastGradeIsShownAsHistory() {
            var dto = conclusionFor(snapshot(80, LocalDateTime.of(2026, 9, 11, 17, 0)));

            assertThat(dto.isDataAvailable()).isTrue();          // 이력은 있다
            assertThat(dto.getDataAt()).isEqualTo(LocalDateTime.of(2026, 9, 11, 17, 0));
            assertThat(dto.getGuidance()).contains("최신");
        }
    }

    @Nested
    @DisplayName("거래일 기준 신선도 — 주말·장전을 TTL 로 무효화하지 않는다")
    class TradingDayFreshness {

        @Test
        @DisplayName("금요일 17:00 스냅샷은 토요일에도 유효하다")
        void fridaySnapshotIsValidOnSaturday() {
            service = newService(at(LocalDate.of(2026, 9, 19), LocalTime.of(10, 0)));   // 토
            var dto = conclusionFor(snapshot(80, LocalDateTime.of(2026, 9, 18, 17, 0)));

            assertThat(dto.isCurrentlyValid()).isTrue();
            assertThat(dto.getLevel()).isEqualTo(Level.STRONG_BUY);
            assertThat(dto.getTradePlan()).isNotNull();
        }

        @Test
        @DisplayName("금요일 스냅샷은 월요일 장전(08:30)에도 유효하다 — 아직 새 스냅샷이 없다")
        void fridaySnapshotIsValidOnMondayPreMarket() {
            service = newService(at(LocalDate.of(2026, 9, 21), LocalTime.of(8, 30)));   // 월 장전
            var dto = conclusionFor(snapshot(80, LocalDateTime.of(2026, 9, 18, 17, 0)));

            assertThat(dto.isCurrentlyValid()).isTrue();
        }

        @Test
        @DisplayName("월요일 첫 스냅샷 시각(11:30)이 지나면 금요일 스냅샷은 노후다")
        void fridaySnapshotIsStaleAfterMondayFirstSnapshot() {
            service = newService(at(LocalDate.of(2026, 9, 21), LocalTime.of(14, 0)));
            var dto = conclusionFor(snapshot(80, LocalDateTime.of(2026, 9, 18, 17, 0)));

            assertThat(dto.isCurrentlyValid()).isFalse();
        }

        @Test
        @DisplayName("당일 스냅샷은 당연히 유효 — 기존 매매계획(-3/+5)이 그대로 나온다")
        void todaySnapshotIsValid() {
            var dto = conclusionFor(snapshot(80, LocalDateTime.of(2026, 9, 17, 11, 30)));

            assertThat(dto.isCurrentlyValid()).isTrue();
            assertThat(dto.getStaleReason()).isNull();
            assertThat(dto.getLevel()).isEqualTo(Level.STRONG_BUY);
        }
    }

    @Nested
    @DisplayName("거래정지")
    class Halted {

        @Test
        @DisplayName("최신 스냅샷이어도 거래정지면 현재형 매수 권고·매매계획을 내지 않는다")
        void haltedStockGetsNoBuyRecommendation() {
            when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.HALTED);

            var dto = conclusionFor(snapshot(80, LocalDateTime.of(2026, 9, 17, 11, 30)));

            assertThat(dto.getLevel()).isEqualTo(Level.WAIT);
            assertThat(dto.isCurrentlyValid()).isFalse();
            assertThat(dto.getStaleReason()).contains("거래정지");
            assertThat(dto.getTradePlan()).isNull();
        }

        @Test
        @DisplayName("동기화 전(UNVERIFIED)은 차단하지 않는다 — 게이트 fail-open 의미를 표시층이 뒤집지 않는다")
        void unverifiedDoesNotBlock() {
            when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.UNVERIFIED);

            var dto = conclusionFor(snapshot(80, LocalDateTime.of(2026, 9, 17, 11, 30)));

            assertThat(dto.isCurrentlyValid()).isTrue();
            assertThat(dto.getLevel()).isEqualTo(Level.STRONG_BUY);
        }
    }

    // ==================== F6 — 55 미만 BUY 정리 ====================

    @Nested
    @DisplayName("F6 — 수급 강세만으로 55 미만을 BUY 로 올리지 않는다")
    class SupplyStrongBelowCut {

        @Test
        @DisplayName("총점 50 + 수급 18 + 기술 5 → BUY 아님(WAIT), 수급 강세는 관찰 문구로")
        void total50IsNotBuy() {
            var dto = conclusionFor(snapshot(50, 18, 5, LocalDateTime.of(2026, 9, 17, 11, 30)));

            assertThat(dto.getLevel()).isEqualTo(Level.WAIT);
            assertThat(dto.getHeadline()).contains("수급");
        }

        @Test
        @DisplayName("총점 54 도 BUY 가 아니다 — 55 컷 경계")
        void total54IsNotBuy() {
            var dto = conclusionFor(snapshot(54, 18, 5, LocalDateTime.of(2026, 9, 17, 11, 30)));

            assertThat(dto.getLevel()).isEqualTo(Level.WAIT);
        }

        @Test
        @DisplayName("총점 55 + 수급 강 + 기술 약 → BUY(추격 신중 문구 유지)")
        void total55WithStrongSupplyIsBuy() {
            var dto = conclusionFor(snapshot(55, 18, 5, LocalDateTime.of(2026, 9, 17, 11, 30)));

            assertThat(dto.getLevel()).isEqualTo(Level.BUY);
            assertThat(dto.getHeadline()).contains("수급");
        }

        @Test
        @DisplayName("가치 강세 HOLD 분기는 보존 — 총점 50·가치 12 는 여전히 HOLD")
        void valueStrongHoldBranchPreserved() {
            RecommendationSnapshot s = snapshot(50, 5, 5, LocalDateTime.of(2026, 9, 17, 11, 30));
            s.setValueStability(12);

            var dto = conclusionFor(s);

            assertThat(dto.getLevel()).isEqualTo(Level.HOLD);
            assertThat(dto.getHeadline()).contains("저평가");
        }

        @Test
        @DisplayName("75 이상은 그대로 STRONG_BUY — 상단 경계 보존")
        void strongBuyBoundaryPreserved() {
            assertThat(conclusionFor(snapshot(75, LocalDateTime.of(2026, 9, 17, 11, 30))).getLevel())
                    .isEqualTo(Level.STRONG_BUY);
            assertThat(conclusionFor(snapshot(74, LocalDateTime.of(2026, 9, 17, 11, 30))).getLevel())
                    .isEqualTo(Level.BUY);
        }
    }
}
