package com.myplatform.backend.shortselling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.service.KoreaInvestmentService;
import com.myplatform.backend.service.MarketCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공매도 거래 비중 수집·조회(2026-10-02, 사용자 결정 "거래 비중으로, 표시만").
 *
 * <p>잔고 출처(KRX·네이버)는 죽었고 KIS 에는 잔고 API 가 없다 — KIS 공매도 상위종목(국내주식-133)을 매일 장 마감 뒤
 * 쌓고, 종목별 값은 일별추이(국내주식-134)로 직전 마감일 것을 읽는다. 판정·봇·경보에는 쓰지 않는다.
 */
class ShortSellingTradeServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-10-02(금) 10:00 — 장중이라 마지막 마감 거래일은 10/1(목). */
    private static final Clock NOW = Clock.fixed(ZonedDateTime.of(2026, 10, 2, 10, 0, 0, 0, KST).toInstant(), KST);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ShortSellingTradeRepository repository;
    private KoreaInvestmentService kis;
    private ShortSellingTradeService service;

    @BeforeEach
    void setUp() {
        repository = mock(ShortSellingTradeRepository.class);
        kis = mock(KoreaInvestmentService.class);
        service = new ShortSellingTradeService(repository, kis, new MarketCalendarService(), NOW,
                mock(PlatformTransactionManager.class));
    }

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String row(String code, String share) {
        return "{'mksc_shrn_iscd':'" + code + "','hts_kor_isnm':'종목" + code + "','ssts_cntg_qty':'1000',"
                + "'ssts_vol_rlim':'" + share + "','stnd_date1':'20261001','stnd_date2':'20261001'}";
    }

    private static KoreaInvestmentService.KisPage page(String trCont, String... rows) {
        return new KoreaInvestmentService.KisPage(
                json("{'rt_cd':'0','msg1':'정상','output':[" + String.join(",", rows) + "]}"), trCont);
    }

    @SuppressWarnings("unchecked")
    private List<ShortSellingTrade> savedRows() {
        ArgumentCaptor<List<ShortSellingTrade>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("수집 — 공매도 상위종목")
    class Collect {

        @Test
        @DisplayName("연속조회(M)는 다음 페이지를 받고, 순위는 페이지를 이어 센다 — 그날 행은 갈아 끼운다")
        void followsContinuationAndReplacesTheDay() {
            when(kis.getShortSaleRankingPage(false)).thenReturn(page("M", row("005930", "8.00"), row("000660", "6.10")));
            when(kis.getShortSaleRankingPage(true)).thenReturn(page("D", row("035720", "5.00")));

            ShortSellingTradeService.CollectionStatus status = service.collect();

            assertThat(status.ok()).isTrue();
            assertThat(status.stored()).isEqualTo(3);
            verify(repository).deleteByTradeDate(LocalDate.of(2026, 10, 1));
            List<ShortSellingTrade> saved = savedRows();
            assertThat(saved).extracting(ShortSellingTrade::getStockCode).containsExactly("005930", "000660", "035720");
            assertThat(saved).extracting(ShortSellingTrade::getRankNo).containsExactly(1, 2, 3);
            assertThat(saved.get(0).getShortVolumeShare()).isEqualByComparingTo("8.00");
            assertThat(saved.get(0).getCollectedAt()).isNotNull();
        }

        @Test
        @DisplayName("KIS 오류(rt_cd≠0)면 아무것도 지우거나 쓰지 않고 사유를 남긴다 — '0건'으로 위장하지 않는다")
        void kisErrorWritesNothing() {
            when(kis.getShortSaleRankingPage(false)).thenReturn(new KoreaInvestmentService.KisPage(
                    json("{'rt_cd':'1','msg1':'INPUT FIELD NOT FOUND [FID_APLY_RANG_PRC_1]'}"), null));

            ShortSellingTradeService.CollectionStatus status = service.collect();

            assertThat(status.ok()).isFalse();
            assertThat(status.message()).contains("INPUT FIELD NOT FOUND");
            verify(repository, never()).deleteByTradeDate(any());
            verify(repository, never()).saveAll(any());
        }

        @Test
        @DisplayName("응답이 없으면(네트워크·토큰) 실패로 남긴다")
        void noResponseIsFailure() {
            when(kis.getShortSaleRankingPage(anyBoolean())).thenReturn(null);

            ShortSellingTradeService.CollectionStatus status = service.collect();

            assertThat(status.ok()).isFalse();
            verify(repository, never()).saveAll(any());
        }

        @Test
        @DisplayName("같은 페이지가 되풀이되면 거기서 끊는다 — 커서가 안 나가는 페이지네이션을 무한히 따라가지 않는다")
        void repeatedPageStops() {
            when(kis.getShortSaleRankingPage(anyBoolean())).thenReturn(page("M", row("005930", "8.00")));

            ShortSellingTradeService.CollectionStatus status = service.collect();

            assertThat(status.stored()).isEqualTo(1);
            verify(kis, times(2)).getShortSaleRankingPage(anyBoolean());
        }

        @Test
        @DisplayName("계속 새 행이 와도 페이지 상한에서 멈춘다")
        void pageCapStops() {
            final int[] n = {0};
            when(kis.getShortSaleRankingPage(anyBoolean())).thenAnswer(inv ->
                    page("M", row(String.format("%06d", ++n[0]), "1.00")));

            service.collect();

            verify(kis, times(ShortSellingTradeService.MAX_RANKING_PAGES)).getShortSaleRankingPage(anyBoolean());
        }

        @Test
        @DisplayName("뒤 페이지가 실패하면 받은 앞부분은 저장하되 '부분 수집'으로 남긴다")
        void laterPageFailureKeepsThePrefix() {
            when(kis.getShortSaleRankingPage(false)).thenReturn(page("M", row("005930", "8.00")));
            when(kis.getShortSaleRankingPage(true)).thenReturn(null);

            ShortSellingTradeService.CollectionStatus status = service.collect();

            assertThat(status.ok()).isFalse();
            assertThat(status.stored()).isEqualTo(1);
            assertThat(status.message()).contains("부분");
            assertThat(savedRows()).hasSize(1);
        }

        @Test
        @DisplayName("재현: 기준일이 첫 행에만 있어도 응답 전체를 저장한다 — 10/2·10/6 수집이 30건 중 1건만 남았다")
        void dateOnFirstRowStoresWholeResponse() {
            String noDate1 = "{'mksc_shrn_iscd':'000660','hts_kor_isnm':'SK하이닉스','ssts_vol_rlim':'3.1'}";
            String noDate2 = "{'mksc_shrn_iscd':'005930','hts_kor_isnm':'삼성전자','ssts_vol_rlim':'2.2'}";
            when(kis.getShortSaleRankingPage(anyBoolean())).thenReturn(page("D", row("018880", "5.09"), noDate1, noDate2));

            ShortSellingTradeService.CollectionStatus status = service.collect();

            assertThat(status.ok()).isTrue();
            assertThat(status.stored()).isEqualTo(3);
            assertThat(savedRows()).extracting(ShortSellingTrade::getTradeDate).containsOnly(LocalDate.of(2026, 10, 1));
            assertThat(status.message()).contains("2건은 응답 기준일");
        }

        @Test
        @DisplayName("건너뛴 행은 이유별로 센다 — 종목코드 없음·기준일 없음을 한데 묶지 않는다")
        void skippedRowsAreReportedByReason() {
            String noCode = "{'mksc_shrn_iscd':'','hts_kor_isnm':'?','ssts_vol_rlim':'3.1','stnd_date2':'20261001'}";
            when(kis.getShortSaleRankingPage(anyBoolean())).thenReturn(page("D", row("018880", "5.09"), noCode));

            ShortSellingTradeService.CollectionStatus status = service.collect();

            assertThat(status.ok()).isTrue();
            assertThat(status.stored()).isEqualTo(1);
            assertThat(status.message()).contains("응답 2건 중 1건 건너뜀").contains("종목코드 없음 1");
        }

        @Test
        @DisplayName("행이 하나도 없으면 지우지 않는다 — 빈 응답으로 어제까지의 기록을 날리지 않게")
        void emptyResponseDeletesNothing() {
            when(kis.getShortSaleRankingPage(false)).thenReturn(page("D"));

            ShortSellingTradeService.CollectionStatus status = service.collect();

            assertThat(status.ok()).isFalse();
            verify(repository, never()).deleteByTradeDate(any());
            verify(repository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("상위 목록 조회")
    class LatestRanking {

        @Test
        @DisplayName("최신 기준일의 행을 순위대로 — 기준일을 함께 준다")
        void latestDateRows() {
            LocalDate d = LocalDate.of(2026, 10, 1);
            when(repository.findLatestTradeDate()).thenReturn(Optional.of(d));
            when(repository.findByTradeDateOrderByRankNoAsc(eq(d), any()))
                    .thenReturn(List.of(ShortSellingTrade.builder().stockCode("005930").tradeDate(d).rankNo(1).build()));

            ShortSellingTradeService.Ranking ranking = service.latestRanking(30);

            assertThat(ranking.dataAvailable()).isTrue();
            assertThat(ranking.asOf()).isEqualTo(d);
            assertThat(ranking.rows()).hasSize(1);
        }

        @Test
        @DisplayName("한 번도 수집된 적 없으면 기준일 없이 빈 목록(조회는 성공)")
        void neverCollected() {
            when(repository.findLatestTradeDate()).thenReturn(Optional.empty());

            ShortSellingTradeService.Ranking ranking = service.latestRanking(30);

            assertThat(ranking.dataAvailable()).isTrue();
            assertThat(ranking.asOf()).isNull();
            assertThat(ranking.rows()).isEmpty();
        }

        @Test
        @DisplayName("조회 실패는 dataAvailable=false — '데이터 없음'과 구분한다")
        void failureIsNotEmpty() {
            when(repository.findLatestTradeDate()).thenThrow(new IllegalStateException("DB down"));

            assertThat(service.latestRanking(30).dataAvailable()).isFalse();
        }
    }

    @Nested
    @DisplayName("종목별 값 — 공매도 일별추이")
    class StockShare {

        private final JsonNode daily = json("{'rt_cd':'0','output1':{},'output2':["
                + "{'stck_bsop_date':'20261002','ssts_cntg_qty':'10','ssts_vol_rlim':'0.50'},"
                + "{'stck_bsop_date':'20261001','ssts_cntg_qty':'960000','ssts_vol_rlim':'8.00',"
                + "'ssts_tr_pbmn_rlim':'7.90'}]}");

        @Test
        @DisplayName("직전 마감일(10/1) 값 — 장중 형성 중인 오늘(10/2) 행은 쓰지 않는다")
        void usesTheLastClosedDay() {
            when(kis.getDailyShortSale(eq("005930"), any(), any())).thenReturn(daily);

            ShortSellingTradeService.StockShare share = service.stockShare("005930");

            assertThat(share.dataAvailable()).isTrue();
            assertThat(share.asOf()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(share.shortVolumeShare()).isEqualByComparingTo("8.00");
            assertThat(share.shortVolume()).isEqualTo(960_000L);
            assertThat(share.shortAmountShare()).isEqualByComparingTo("7.90");
            verify(kis).getDailyShortSale(eq("005930"), any(), eq(LocalDate.of(2026, 10, 1)));
        }

        @Test
        @DisplayName("같은 마감일 안에서는 한 번만 KIS 를 부른다")
        void cachedPerClosedDay() {
            when(kis.getDailyShortSale(anyString(), any(), any())).thenReturn(daily);

            service.stockShare("005930");
            service.stockShare("005930");

            verify(kis, times(1)).getDailyShortSale(anyString(), any(), any());
        }

        @Test
        @DisplayName("조회 실패는 캐시하지 않는다 — 실패를 '값 없음'으로 하루 동안 굳히지 않게")
        void failureIsNotCached() {
            when(kis.getDailyShortSale(anyString(), any(), any())).thenReturn(null);

            ShortSellingTradeService.StockShare first = service.stockShare("005930");
            service.stockShare("005930");

            assertThat(first.dataAvailable()).isFalse();
            verify(kis, times(2)).getDailyShortSale(anyString(), any(), any());
        }

        @Test
        @DisplayName("KIS 오류(rt_cd≠0)도 조회 실패 — 사유를 남긴다")
        void kisErrorIsFailure() {
            when(kis.getDailyShortSale(anyString(), any(), any()))
                    .thenReturn(json("{'rt_cd':'1','msg1':'조회 오류'}"));

            ShortSellingTradeService.StockShare share = service.stockShare("005930");

            assertThat(share.dataAvailable()).isFalse();
            assertThat(share.message()).contains("조회 오류");
        }

        @Test
        @DisplayName("조회는 됐는데 비중 값이 없으면 값 없음(null) — 0% 로 보이지 않게")
        void noShareValueIsNull() {
            when(kis.getDailyShortSale(anyString(), any(), any()))
                    .thenReturn(json("{'rt_cd':'0','output2':[{'stck_bsop_date':'20261001','ssts_vol_rlim':''}]}"));

            ShortSellingTradeService.StockShare share = service.stockShare("005930");

            assertThat(share.dataAvailable()).isTrue();
            assertThat(share.shortVolumeShare()).isNull();
            assertThat(share.asOf()).isNull();
        }
    }

    @Test
    @DisplayName("빈 종목코드는 KIS 를 부르지 않는다")
    void blankCodeDoesNotCallKis() {
        assertThat(service.stockShare(" ").dataAvailable()).isFalse();
        verify(kis, never()).getDailyShortSale(anyString(), any(), any());
        verify(repository, never()).findByTradeDateOrderByRankNoAsc(any(), any());
        verify(kis, never()).getShortSaleRankingPage(anyBoolean());
    }
}
