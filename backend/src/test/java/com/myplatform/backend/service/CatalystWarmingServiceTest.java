package com.myplatform.backend.service;

import com.myplatform.backend.service.RecommendationService.RecommendationDto;
import com.myplatform.backend.service.RecommendationService.Top5Response;
import com.myplatform.backend.service.StockCatalystService.StockRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * union 재료 일괄 워밍 (P2-CAT3) — 5트랙 dedup·상한 cap·null 스킵 + classifyBatch 위임.
 */
@ExtendWith(MockitoExtension.class)
class CatalystWarmingServiceTest {

    @Mock private RecommendationService recommendationService;
    @Mock private StockCatalystService stockCatalystService;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private com.myplatform.backend.repository.StockWatchlistRepository watchlistRepository;
    @Mock private com.myplatform.backend.repository.BotTradingPositionRepository botPositionRepository;
    @Mock private org.springframework.beans.factory.ObjectProvider<RealTradeService> realTradeProvider;
    @Mock private org.springframework.beans.factory.ObjectProvider<KoreaInvestmentService> kisProvider;

    // ⚠ @InjectMocks 금지 — ObjectProvider<X> 2개가 타입 소거로 같은 raw 타입이라 생성자 주입이
    // 어긋날 수 있다(CatalystRiskAlertServiceTest 에서 실측). 수동 생성으로 파라미터 순서 명시.
    private CatalystWarmingService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new CatalystWarmingService(recommendationService, stockCatalystService,
                schedulerLockService, watchlistRepository, botPositionRepository,
                realTradeProvider, kisProvider);
    }

    @Captor private ArgumentCaptor<List<StockRef>> refsCaptor;

    private Top5Response top5(RecommendationDto... items) {
        return new Top5Response(List.of(items), "", false, Map.of());
    }

    private RecommendationDto dto(String code, String name) {
        return RecommendationDto.builder().stockCode(code).stockName(name).build();
    }

    private RecommendationDto scored(String code, String name, int totalScore) {
        return RecommendationDto.builder().stockCode(code).stockName(name).totalScore(totalScore).build();
    }

    private List<String> codesOf(List<StockRef> refs) {
        return refs.stream().map(StockRef::code).toList();
    }

    @Test
    @DisplayName("collectUnionRefs — 5트랙 수집 + 트랙 간 중복 dedup(첫 등장 순서)")
    void collectUnionRefs_dedupesAcrossTracks() {
        when(recommendationService.getValueTop10()).thenReturn(top5(dto("005930", "삼성전자"), dto("000660", "SK하이닉스")));
        when(recommendationService.getGrowthTop10()).thenReturn(top5(dto("000660", "SK하이닉스"), dto("035420", "NAVER")));
        // 나머지 트랙(oversold/earnings/smartmoney)은 미스텁 → null → 빈 리스트(best-effort)

        List<StockRef> refs = service.collectUnionRefs();

        assertThat(codesOf(refs)).containsExactly("005930", "000660", "035420");   // 000660 중복 제거
    }

    @Test
    @DisplayName("collectUnionRefs — 상한 UNION_WARM_MAX(25)에서 컷")
    void collectUnionRefs_capsAtMax() {
        RecommendationDto[] thirty = IntStream.rangeClosed(1, 30)
                .mapToObj(i -> dto(String.format("%06d", i), "종목" + i))
                .toArray(RecommendationDto[]::new);
        when(recommendationService.getValueTop10()).thenReturn(top5(thirty));

        List<StockRef> refs = service.collectUnionRefs();

        assertThat(refs).hasSize(CatalystWarmingService.UNION_WARM_MAX);   // 30 중 25만
    }

    @Test
    @DisplayName("collectUnionRefs — null/blank code·name 스킵, 트랙 예외는 best-effort 무시")
    void collectUnionRefs_skipsInvalidAndTrackFailure() {
        when(recommendationService.getValueTop10()).thenReturn(top5(
                dto("005930", "삼성전자"),
                dto(null, "노코드"),
                dto("000660", "  "),        // blank name
                dto("035420", "NAVER")));
        when(recommendationService.getGrowthTop10()).thenThrow(new RuntimeException("트랙 장애"));

        List<StockRef> refs = service.collectUnionRefs();

        assertThat(codesOf(refs)).containsExactly("005930", "035420");   // null/blank 제외, 트랙 예외 무시
    }

    @Test
    @DisplayName("collectUnionRefs — 라운드로빈 균등: 뒤 트랙(수급) top이 앞 트랙(저평가) 2번째보다 앞 (급등주 포함)")
    void collectUnionRefs_roundRobinInterleave() {
        // 저평가 6종목 + 낙폭(급등) 2종목. 순차면 낙폭은 저평가 6개 뒤로 밀림 → 라운드로빈은 앞으로.
        when(recommendationService.getValueTop10()).thenReturn(top5(
                dto("V00001", "저평가1"), dto("V00002", "저평가2"), dto("V00003", "저평가3"),
                dto("V00004", "저평가4"), dto("V00005", "저평가5"), dto("V00006", "저평가6")));
        when(recommendationService.getOversoldTop10()).thenReturn(top5(dto("O00001", "낙폭1"), dto("O00002", "낙폭2")));

        List<String> codes = codesOf(service.collectUnionRefs());

        assertThat(codes).contains("O00001", "O00002");                 // 급등주 포함
        assertThat(codes.indexOf("O00001")).isLessThan(codes.indexOf("V00002"));   // 낙폭#1 이 저평가#2 보다 앞
        assertThat(codes.indexOf("O00002")).isLessThan(codes.indexOf("V00003"));   // 낙폭#2 이 저평가#3 보다 앞
    }

    @Test
    @DisplayName("collectUnionRefs — 소진 트랙 롤오버: 짧은 트랙 소진 후 남은 칸은 긴 트랙이 채움")
    void collectUnionRefs_rolloverWhenTrackExhausted() {
        RecommendationDto[] ten = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> dto(String.format("V%05d", i), "저평가" + i))
                .toArray(RecommendationDto[]::new);
        when(recommendationService.getValueTop10()).thenReturn(top5(ten));
        when(recommendationService.getSmartMoneyTop10()).thenReturn(top5(dto("S00001", "수급1"), dto("S00002", "수급2")));

        List<String> codes = codesOf(service.collectUnionRefs());

        assertThat(codes).hasSize(12);                          // 10 + 2, 상한 25 미만이라 전부
        assertThat(codes).contains("S00001", "S00002");         // 짧은 트랙 top 포함
        assertThat(codes).contains("V00001", "V00010");         // 소진 후 긴 트랙이 나머지 채움(롤오버)
    }

    // ================================================================
    // 2026-07-07 — 관심/보유 트랙 확장: 우선순위 병합(관심>보유>발굴) + 전체 40 컷
    // ================================================================

    private StockRef ref(String code, String name) { return new StockRef(code, name); }

    @Test
    @DisplayName("mergeWarmTargets — 우선순위 관심>보유>오늘후보>발굴 + code 첫 등장 dedup")
    void mergeWarmTargets_priorityAndDedup() {
        List<StockRef> merged = CatalystWarmingService.mergeWarmTargets(
                List.of(ref("W00001", "관심1"), ref("D00001", "관심겸발굴")),
                List.of(ref("H00001", "보유1"), ref("W00001", "관심겸보유")),   // W00001 중복 → 관심 순서 유지
                List.of(ref("T00001", "오늘후보1"), ref("H00001", "보유겸오늘")),   // H00001 중복 → 보유 순서 유지
                List.of(ref("D00001", "발굴1"), ref("D00002", "발굴2")),
                40);

        assertThat(codesOf(merged)).containsExactly("W00001", "D00001", "H00001", "T00001", "D00002");
    }

    @Test
    @DisplayName("mergeWarmTargets — 전체 상한 컷: 초과 시 뒤 그룹(발굴)부터 잘림")
    void mergeWarmTargets_capCutsLowerPriorityFirst() {
        List<StockRef> watch = IntStream.rangeClosed(1, 3).mapToObj(i -> ref("W" + i, "관심" + i)).toList();
        List<StockRef> held = IntStream.rangeClosed(1, 2).mapToObj(i -> ref("H" + i, "보유" + i)).toList();
        List<StockRef> today = IntStream.rangeClosed(1, 2).mapToObj(i -> ref("T" + i, "오늘" + i)).toList();
        List<StockRef> discover = IntStream.rangeClosed(1, 5).mapToObj(i -> ref("D" + i, "발굴" + i)).toList();

        List<StockRef> merged = CatalystWarmingService.mergeWarmTargets(watch, held, today, discover, 6);

        assertThat(codesOf(merged)).containsExactly("W1", "W2", "W3", "H1", "H2", "T1");   // 오늘1+발굴5 잘림
    }

    @Test
    @DisplayName("mergeWarmTargets — null 그룹/blank 원소 안전, 관심 단독으로도 상한 컷")
    void mergeWarmTargets_nullSafeAndWatchOnlyCap() {
        List<StockRef> watch = IntStream.rangeClosed(1, 45).mapToObj(i -> ref("W" + i, "관심" + i)).toList();

        List<StockRef> merged = CatalystWarmingService.mergeWarmTargets(watch, null, null, null,
                CatalystWarmingService.TOTAL_WARM_MAX);

        assertThat(merged).hasSize(CatalystWarmingService.TOTAL_WARM_MAX);   // 관심만으로 40 컷
        assertThat(CatalystWarmingService.mergeWarmTargets(
                List.of(ref(null, "노코드"), ref("W1", "  ")), null, null, null, 40)).isEmpty();
    }

    @Test
    @DisplayName("collectWarmRefs — 관심(watchlist)+봇 포지션이 발굴보다 앞 + dedup")
    void collectWarmRefs_watchAndPositionsFirst() {
        when(watchlistRepository.findByIsActiveTrue()).thenReturn(List.of(
                com.myplatform.backend.entity.StockWatchlist.builder()
                        .username("u").stockCode("111111").stockName("관심주").build()));
        when(botPositionRepository.findAll()).thenReturn(List.of(
                com.myplatform.backend.entity.BotTradingPosition.builder()
                        .stockCode("222222").stockName("보유주")
                        .strategy(com.myplatform.backend.entity.BotTradingPosition.Strategy.SWING)
                        .buyPrice(java.math.BigDecimal.ONE).highPrice(java.math.BigDecimal.ONE)
                        .buyTime(java.time.LocalDateTime.now()).build()));
        when(kisProvider.getIfAvailable()).thenReturn(null);   // KIS 미설정 → 실잔고 생략(best-effort)
        when(recommendationService.getValueTop10()).thenReturn(top5(dto("111111", "관심주"), dto("333333", "발굴주")));

        List<StockRef> refs = service.collectWarmRefs();

        assertThat(codesOf(refs)).containsExactly("111111", "222222", "333333");   // 관심>보유>발굴, 중복 제거
    }

    @Test
    @DisplayName("collectWarmRefs — 오늘 매수후보(getTop5) 포함: BUY컷(55) 미달 제외 + 상한 5 + 발굴보다 앞 (2026-08-20)")
    void collectWarmRefs_includesTodayCandidates() {
        when(watchlistRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(botPositionRepository.findAll()).thenReturn(List.of());
        when(kisProvider.getIfAvailable()).thenReturn(null);
        // 오늘 후보: 60/55(포함) + 54(컷 미달 제외). 오늘 탭 표시 규약(BUY컷 55)과 동기.
        when(recommendationService.getTop5()).thenReturn(top5(
                scored("T00001", "오늘1", 60), scored("T00002", "오늘컷미달", 54), scored("T00003", "오늘2", 55)));
        when(recommendationService.getValueTop10()).thenReturn(top5(dto("D00001", "발굴1")));

        List<String> codes = codesOf(service.collectWarmRefs());

        assertThat(codes).containsExactly("T00001", "T00003", "D00001");   // 컷 미달 제외, 오늘후보 > 발굴
    }

    @Test
    @DisplayName("collectWarmRefs — 오늘 후보 상한 5: 6번째 이후는 잘림(발굴 몫 보존)")
    void collectWarmRefs_todayCandidatesCappedAtFive() {
        when(watchlistRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(botPositionRepository.findAll()).thenReturn(List.of());
        when(kisProvider.getIfAvailable()).thenReturn(null);
        RecommendationDto[] seven = IntStream.rangeClosed(1, 7)
                .mapToObj(i -> scored("T0000" + i, "오늘" + i, 60))
                .toArray(RecommendationDto[]::new);
        when(recommendationService.getTop5()).thenReturn(top5(seven));

        List<String> codes = codesOf(service.collectWarmRefs());

        assertThat(codes).hasSize(CatalystWarmingService.TODAY_CANDIDATE_MAX);
        assertThat(codes).containsExactly("T00001", "T00002", "T00003", "T00004", "T00005");
    }

    @Test
    @DisplayName("warmUnionCatalysts — 수집한 union refs 로 classifyBatch 위임 + 저장수 반환")
    void warmUnionCatalysts_delegatesToClassifyBatch() {
        when(recommendationService.getValueTop10()).thenReturn(top5(dto("005930", "삼성전자"), dto("000660", "SK하이닉스")));
        when(stockCatalystService.classifyBatch(any())).thenReturn(2);

        int warmed = service.warmUnionCatalysts();

        assertThat(warmed).isEqualTo(2);
        verify(stockCatalystService).classifyBatch(refsCaptor.capture());
        assertThat(codesOf(refsCaptor.getValue())).containsExactly("005930", "000660");
    }

    @Test
    @DisplayName("warmUnionCatalysts — 대상 없음 → 0, classifyBatch 미호출")
    void warmUnionCatalysts_emptyNoBatch() {
        when(recommendationService.getValueTop10()).thenReturn(top5());

        int warmed = service.warmUnionCatalysts();

        assertThat(warmed).isZero();
        verify(stockCatalystService, never()).classifyBatch(any());
    }
}
