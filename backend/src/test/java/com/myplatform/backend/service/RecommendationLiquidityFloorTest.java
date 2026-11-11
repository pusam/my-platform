package com.myplatform.backend.service;

import com.myplatform.backend.service.RecommendationService.LiquidityStats;
import com.myplatform.backend.service.RecommendationService.RecommendationDto;
import com.myplatform.backend.service.RecommendationService.StockScore;
import com.myplatform.backend.service.RecommendationService.Top5Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * 거래대금 하한(2026-11-11) — 컷(55) 통과 종목 중 20일 평균 거래대금 5억 미만은 순위를 자르기 전에 뺀다.
 *
 * <p>재현: 그날 장중 후보 10건 중 5건이 5억 미만(0.23~3.22억)이었다(한일화학 0.23억·제이엔비 0.24억·디씨엠 1.47억·수산세보틱스 2.91억·파세코 3.22억).
 */
class RecommendationLiquidityFloorTest {

    private static StockScore score(String code, int technical, Double eok) {
        StockScore s = new StockScore(code, code);
        // 네 항목 채점 — 점수가 기술 점수만큼 서로 달라야 정렬이 실제로 검사된다(세 항목이면 74 상한에 다 붙는다)
        s.earnings = 20; s.supplyDemand = 10; s.technical = technical; s.sectorMomentum = 8;
        s.avgTradedValueEok = eok == null ? null : BigDecimal.valueOf(eok);
        return s;
    }

    private static RecommendationService service(double floor) {
        RecommendationService service = mock(RecommendationService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "minAvgTradedValueEok", floor);
        return service;
    }

    private static LiquidityStats stats(RecommendationService service) {
        return (LiquidityStats) ReflectionTestUtils.getField(service, "lastLiquidityStats");
    }

    /**
     * 11개 — T0~T4 는 그날 미달 종목(점수 1~5위: 100·98·96·94·93), L6~L11 은 하한 통과(6~11위: 91·89·87·86·84·82).
     * 입력은 거꾸로 넣는다 — 정렬을 지우면 순서 단언이 깨지게.
     */
    private static List<StockScore> candidates() {
        List<StockScore> out = new ArrayList<>();
        double[] thin = {0.23, 0.24, 1.47, 2.91, 3.22};
        for (int i = 0; i < thin.length; i++) out.add(score("T" + i, 20 - i, thin[i]));
        double[] liquid = {5.25, 19.7, 29.3, 536, 810, 6.0};
        for (int i = 0; i < liquid.length; i++) out.add(score("L" + (6 + i), 15 - i, liquid[i]));
        java.util.Collections.reverse(out);
        return out;
    }

    private static List<String> codes(List<RecommendationDto> r) {
        return r.stream().map(RecommendationDto::getStockCode).toList();
    }

    @Test
    @DisplayName("재현: 55점을 넘어도 20일 평균 5억 미만은 후보가 아니다 — 순위를 자르기 전에 빼서 11번째였던 통과 종목이 들어온다")
    void belowFloorIsExcludedBeforeTopTen() {
        RecommendationService service = service(5);

        List<RecommendationDto> r = service.rankCandidates(candidates(), Map.of());

        assertThat(codes(r)).doesNotContain("T0", "T1", "T2", "T3", "T4");
        assertThat(codes(r)).containsExactly("L6", "L7", "L8", "L9", "L10", "L11");
        assertThat(stats(service).below()).isEqualTo(5);
        assertThat(stats(service).candidates()).isEqualTo(6);
        assertThat(stats(service).floorEok()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("상위 10 을 넘는 후보가 있어도 미달은 먼저 빠진다 — 11번째 통과 종목이 10번째 자리를 채운다")
    void eleventhLiquidFillsTheSlot() {
        RecommendationService service = service(5);
        List<StockScore> cut = new ArrayList<>(candidates());
        for (int i = 0; i < 5; i++) cut.add(score("M" + i, 5 - i, 50.0));   // 통과지만 점수가 낮은 꼬리(74·72·70·68·67)

        List<RecommendationDto> r = service.rankCandidates(cut, Map.of());

        assertThat(codes(r)).containsExactly("L6", "L7", "L8", "L9", "L10", "L11", "M0", "M1", "M2", "M3");
        assertThat(stats(service).candidates()).isEqualTo(11);
    }

    @Test
    @DisplayName("후보 카드에 20일 평균 거래대금(억, 소수 둘째 자리)을 싣는다")
    void dtoCarriesAverage() {
        List<RecommendationDto> r = service(5).rankCandidates(List.of(score("L", 20, 19.7)), Map.of());
        assertThat(r.get(0).getAvgTradedValueEok()).isEqualByComparingTo("19.70");
        assertThat(r.get(0).getAvgTradedValueEok().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("판정 불가(평균 모름)는 남기고 센다 — 결측은 차단 근거가 아니다")
    void unknownStaysAndIsCounted() {
        RecommendationService service = service(5);
        List<RecommendationDto> r = service.rankCandidates(List.of(score("U", 20, null), score("T", 20, 0.23)), Map.of());
        assertThat(codes(r)).containsExactly("U");
        assertThat(r.get(0).getAvgTradedValueEok()).isNull();
        assertThat(stats(service).unknown()).isEqualTo(1);
        assertThat(stats(service).below()).isEqualTo(1);
    }

    @Test
    @DisplayName("하한을 끄면(0 이하) 예전과 같다 — 점수 순 상위 10")
    void offIsUnchanged() {
        RecommendationService off = service(0);
        List<RecommendationDto> r = off.rankCandidates(candidates(), Map.of());
        assertThat(codes(r)).containsExactly("T0", "T1", "T2", "T3", "T4", "L6", "L7", "L8", "L9", "L10");
        assertThat(stats(off).floorEok()).isNull();
        assertThat(stats(off).below()).isZero();
    }

    @Test
    @DisplayName("응답: 하한 값·뺀 수·후보 수 — 꺼져 있으면 하한·뺀 수 null, 계산을 모르면(DB 스냅샷) 뺀 수·후보 수 null")
    void responseFields() {
        Top5Response on = new Top5Response(List.of(), "", true, Map.of());
        on.applyLiquidity(5.0, new LiquidityStats(5.0, 7, 5, 1));
        assertThat(on.getMinAvgTradedValueEok()).isEqualTo(5.0);
        assertThat(on.getBelowLiquidityFloorCount()).isEqualTo(5);
        assertThat(on.getCandidateCount()).isEqualTo(7);

        Top5Response db = new Top5Response(List.of(), "", false, Map.of());
        db.applyLiquidity(5.0, null);
        assertThat(db.getMinAvgTradedValueEok()).isEqualTo(5.0);
        assertThat(db.getBelowLiquidityFloorCount()).isNull();
        assertThat(db.getCandidateCount()).isNull();

        Top5Response off = new Top5Response(List.of(), "", true, Map.of());
        off.applyLiquidity(null, new LiquidityStats(null, 10, 0, 0));
        assertThat(off.getMinAvgTradedValueEok()).isNull();
        assertThat(off.getBelowLiquidityFloorCount()).isNull();
        assertThat(off.getCandidateCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("하한으로 전부 빠져 0건이어도 정상 채점이다 — '관망' 발행·관제실 '후보 0건 사유' 규칙은 점수표 크기·기술 노후만 본다")
    void allExcludedIsStillNormalScoring() {
        RecommendationService service = service(5);
        List<StockScore> thin = new ArrayList<>();
        for (int i = 0; i < 12; i++) thin.add(score("T" + i, 20, 0.5));
        assertThat(service.rankCandidates(thin, Map.of())).isEmpty();

        Map<String, StockScore> scoreMap = new HashMap<>();
        for (StockScore s : thin) scoreMap.put(s.stockCode, s);
        ReflectionTestUtils.setField(service, "cachedScoreMap", scoreMap);
        ReflectionTestUtils.setField(service, "lastTechnicalStale", 0);
        assertThat(service.lastCalculationScoredNormally()).isTrue();
        assertThat(RecommendationService.shouldPublishEmptyResult(scoreMap.size(), 0)).isTrue();
    }
}
