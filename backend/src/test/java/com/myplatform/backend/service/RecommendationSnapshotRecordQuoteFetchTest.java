package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 시그널을 기록하는 스냅샷(11:30·14:00·17:00)은 컷 통과 후보의 시세를 그 자리에서 받는다(2026-11-10).
 *
 * <p>예전엔 '5분 안에 받아 둔 시세'만 보고, 없으면 그 후보를 기록하지 않았다. 그런데 후보 시세를 5분마다 받는
 * 가격 알림 크론과 스냅샷 크론이 같은 초(:00)에 시작한다 — 계산이 끝나는 4초 뒤엔 5분 전 시세는 막 만료됐고
 * 새 시세는 받는 중이다. 2026-11-10 17:00 운영: 후보 10건 중 그때까지 시세가 받아진 5건만 기록되고 5건은
 * '시세 캐시 미스'로 빠졌다(받는 순서가 늦은 후보부터 빠진다 — 표본이 한쪽으로 치우친다). 후보가 0~2건이던
 * 동안엔 드러나지 않았고, 100점 환산 분모를 58 로 바꿔 후보가 10건 안팎이 되면서 매 회차 절반이 빠지게 됐다.
 *
 * <p>아래 값은 그 회차의 운영 로그·시세 표 그대로다.
 */
class RecommendationSnapshotRecordQuoteFetchTest {

    /** 11/10 17:00 후보 10건 — 코드, 이름, 총점, 실적, 수급, 기술, 섹터. */
    static final Object[][] CANDIDATES = {
            {"007770", "한일화학", 74, 19, -1, 20, 4},
            {"037070", "파세코", 74, 19, -1, 20, 8},
            {"128940", "한미약품", 72, 19, 7, 9, 7},
            {"253450", "스튜디오드래곤", 70, 19, -1, 14, 8},
            {"002840", "미원상사", 68, 19, -1, 16, 5},
            {"052790", "액토즈소프트", 68, 19, -1, 14, 7},
            {"145990", "삼양사", 68, 19, -1, 14, 7},
            {"130580", "나이스디앤비", 68, 19, -1, 14, 7},
            {"452160", "제이엔비", 68, 19, -1, 13, 8},
            {"053800", "안랩", 68, 19, -1, 13, 8},
    };

    /** 계산이 끝난 17:00:04 에 이미 받아져 있던 시세(가격 알림 크론이 17:00:00~03 에 받은 것). */
    static final Map<String, String> WARM_AT_SNAPSHOT = Map.of(
            "007770", "8050", "037070", "6720", "053800", "108200", "128940", "485500", "253450", "23050");

    /** 같은 알림 회차가 17:00:04~08 에 받은 시세 — 스냅샷이 시세를 찾을 땐 아직 없었다. */
    static final Map<String, String> FETCHED_AFTER = Map.of(
            "002840", "131200", "052790", "4900", "145990", "43500", "130580", "6920", "452160", "3270");

    RecommendationService service;
    StockPriceService prices;
    SignalOutcomeService outcomes;
    RecommendationSnapshotRepository snapshots;

    @BeforeEach
    void setUp() {
        service = mock(RecommendationService.class, CALLS_REAL_METHODS);
        prices = mock(StockPriceService.class);
        outcomes = mock(SignalOutcomeService.class);
        snapshots = mock(RecommendationSnapshotRepository.class);
        MarketCalendarService calendar = mock(MarketCalendarService.class);
        when(calendar.isMarketClosed()).thenReturn(false);
        @SuppressWarnings("unchecked")
        ObjectProvider<SignalOutcomeService> outcomeProvider = mock(ObjectProvider.class);
        when(outcomeProvider.getIfAvailable()).thenReturn(outcomes);

        ReflectionTestUtils.setField(service, "stockPriceService", prices);
        ReflectionTestUtils.setField(service, "snapshotRepository", snapshots);
        ReflectionTestUtils.setField(service, "marketCalendar", calendar);
        ReflectionTestUtils.setField(service, "signalOutcomeProvider", outcomeProvider);

        List<RecommendationService.RecommendationDto> list = new ArrayList<>();
        for (Object[] c : CANDIDATES) {
            list.add(RecommendationService.RecommendationDto.builder()
                    .stockCode((String) c[0]).stockName((String) c[1]).totalScore((Integer) c[2])
                    .aiStrategy(-1).earnings((Integer) c[3]).supplyDemand((Integer) c[4])
                    .technical((Integer) c[5]).sectorMomentum((Integer) c[6])
                    .valueStability(10).growth(-1).validCount(3).tags(List.of()).build());
        }
        doReturn(list).when(service).calculate();

        // 5분 캐시에는 먼저 받아진 다섯 종목만 있다
        when(prices.getStockPricesFromCacheOnly(anyList())).thenReturn(quotes(WARM_AT_SNAPSHOT));
    }

    private static Map<String, StockPriceDto> quotes(Map<String, String> priceByCode) {
        Map<String, StockPriceDto> out = new HashMap<>();
        priceByCode.forEach((code, price) -> {
            StockPriceDto q = new StockPriceDto();
            q.setStockCode(code);
            q.setCurrentPrice(new BigDecimal(price));
            q.setChangeRate(new BigDecimal("1.23"));
            q.setFetchedAt(LocalDateTime.now());
            out.put(code, q);
        });
        return out;
    }

    private static Map<String, StockPriceDto> allQuotes() {
        Map<String, String> all = new HashMap<>(WARM_AT_SNAPSHOT);
        all.putAll(FETCHED_AFTER);
        return quotes(all);
    }

    private void verifyRecorded(String code, String price) {
        verify(outcomes).record(eq("BUY"), eq(code), anyString(), any(), eq(new BigDecimal(price)),
                any(), any(), any(), any());
    }

    private void verifyNotRecorded(String code) {
        verify(outcomes, never()).record(anyString(), eq(code), anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void everyCandidatePastTheCutIsRecorded_notOnlyTheOnesWhoseQuoteWasAlreadyWarm() {
        when(prices.getStockPrices(anyList())).thenReturn(allQuotes());

        service.saveIntradaySnapshot();

        // 고치기 전: 아래 다섯 종목(미원상사·액토즈소프트·삼양사·나이스디앤비·제이엔비)이 기록되지 않았다
        FETCHED_AFTER.forEach(this::verifyRecorded);
        WARM_AT_SNAPSHOT.forEach(this::verifyRecorded);
        verify(outcomes, times(10)).record(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void quotesAreRequestedThroughTheSingleBatchPathForTheCandidatesOnly() {
        when(prices.getStockPrices(anyList())).thenReturn(allQuotes());

        service.saveIntradaySnapshot();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> asked = ArgumentCaptor.forClass(List.class);
        verify(prices, times(1)).getStockPrices(asked.capture());   // 한 번, 일괄 경로(시세 단일 경로 §1)
        assertThat(asked.getValue()).containsExactlyInAnyOrder(
                "007770", "037070", "128940", "253450", "002840", "052790", "145990", "130580", "452160", "053800");
    }

    @Test
    void aCandidateWhoseQuoteStillCannotBeFetchedIsNotRecorded() {
        Map<String, StockPriceDto> withoutOne = allQuotes();
        withoutOne.remove("452160");   // 제이엔비 — KIS 가 끝내 값을 못 줬다
        when(prices.getStockPrices(anyList())).thenReturn(withoutOne);

        service.saveIntradaySnapshot();

        verifyNotRecorded("452160");   // 모르는 가격으로 기록하지 않는다(§4c)
        verify(outcomes, times(9)).record(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aQuoteOfZeroIsNotAPrice() {
        Map<String, StockPriceDto> withZero = allQuotes();
        withZero.get("452160").setCurrentPrice(BigDecimal.ZERO);
        when(prices.getStockPrices(anyList())).thenReturn(withZero);

        service.saveIntradaySnapshot();

        verifyNotRecorded("452160");
    }

    @Test
    void whenTheFetchFails_theWarmQuotesAreStillRecordedAndTheSnapshotIsSaved() {
        when(prices.getStockPrices(anyList())).thenThrow(new IllegalStateException("KIS 응답 없음"));

        assertThatCode(() -> service.saveIntradaySnapshot()).doesNotThrowAnyException();

        verify(snapshots).saveAll(anyList());
        WARM_AT_SNAPSHOT.forEach(this::verifyRecorded);
        FETCHED_AFTER.keySet().forEach(this::verifyNotRecorded);
    }

    @Test
    void closingSnapshotDoesNotFetchQuotes() {
        when(prices.getStockPrices(anyList())).thenReturn(allQuotes());

        service.saveClosingSnapshot();

        // 20:05 는 기록하지 않는 회차다 — 시세를 새로 받을 이유가 없다(KIS 호출 0)
        verify(prices, never()).getStockPrices(anyList());
        verify(outcomes, never()).record(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any());
    }
}
