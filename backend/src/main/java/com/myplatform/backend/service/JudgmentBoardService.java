package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.dto.JudgmentBoardDto;
import com.myplatform.backend.dto.JudgmentBoardDto.Row;
import com.myplatform.backend.service.RecommendationService.RecommendationDto;
import com.myplatform.backend.service.RecommendationService.StockScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 종합 판단 보드(B안, P2-14) 조립 — 매수후보 + 신호 3계층을 한 보드로. <b>산식 무변경(조립·표시 전용)</b>.
 *
 * <p>scope=momentum(기본): getTop5 후보만(Phase1). scope=union(Phase2): 발굴 5트랙까지 합집합.
 * union 종목의 4카테고리는 <b>momentum scoreMap lookup</b>(재점수 없음) — seed(AI/실적/수급) 밖 순수
 * 저평가/성장주는 "—"(scored=false, 출처 태그로 맥락). "기술/실적/수급 강한데 컷 못 든 종목" 발견이 목적.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JudgmentBoardService {

    /** 수급 역상관 '의심' 하한 — P1-6 gradient(10-14=41%/15+=35% < 약세 47%). 표본 작음(n=88), 확정 아님. */
    static final int SUPPLY_INVERSE_SUSPECT_MIN = 10;
    private static final String SOURCE_MOMENTUM = "momentum";
    private static final String NOTE_BASE =
            "② 차트타이밍·섹터강도·간밤 미국장 = 미검증 참고(점수 미편입). "
            + "③ 수급 고점 = 역상관 의심(표본 작음 n=88, 확정 아님). 종합점수는 ① 검증/게이트 기준.";

    private final RecommendationService recommendationService;
    private final ChartPatternClient chartPatternClient;
    private final SectorStockConfig sectorStockConfig;
    private final OvernightUsMarketService overnightService;
    private final ObjectProvider<MarketRegimeClient> regimeProvider;

    /** 종합 판단 보드. scope=momentum(기본)|union. */
    public JudgmentBoardDto getBoard(String scope) {
        boolean union = "union".equalsIgnoreCase(scope);

        List<RecommendationDto> momentum;
        try {
            momentum = recommendationService.getTop5().getItems();
        } catch (Exception e) {
            log.warn("[JudgmentBoard] 후보 조회 실패: {}", e.getMessage());
            momentum = List.of();
        }
        if (momentum == null) momentum = List.of();

        // 발굴 5트랙 수집(union) — code→트랙목록 + 대표 DTO
        LinkedHashMap<String, List<String>> trackSources = new LinkedHashMap<>();
        Map<String, RecommendationDto> trackDto = new LinkedHashMap<>();
        if (union) {
            collectTrack(safeItems(recommendationService::getValueTop10), "value", trackSources, trackDto);
            collectTrack(safeItems(recommendationService::getGrowthTop10), "growth", trackSources, trackDto);
            collectTrack(safeItems(recommendationService::getOversoldTop10), "oversold", trackSources, trackDto);
            collectTrack(safeItems(recommendationService::getEarningsTop10), "earnings", trackSources, trackDto);
            collectTrack(safeItems(recommendationService::getSmartMoneyTop10), "smartmoney", trackSources, trackDto);
        }

        // 조인용 전체 코드(momentum + 발굴-only)
        Set<String> momentumCodes = new LinkedHashSet<>();
        List<String> allCodes = new ArrayList<>();
        for (RecommendationDto d : momentum) {
            if (d.getStockCode() != null) { momentumCodes.add(d.getStockCode()); allCodes.add(d.getStockCode()); }
        }
        for (String c : trackSources.keySet()) if (!momentumCodes.contains(c)) allCodes.add(c);

        // 신호 조인(best-effort)
        ChartPatternClient.TimingFetch timing = chartPatternClient.getTimingSignals(allCodes);
        Map<String, Integer> timingMap = new LinkedHashMap<>();
        for (ChartPatternClient.TimingSignal t : timing.signals()) {
            if (t.ticker() != null && t.timingScore() != null) timingMap.put(t.ticker(), t.timingScore());
        }
        Map<String, String> stockSector = buildStockSectorMap();
        Map<String, Object> ss = chartPatternClient.getSectorStrength(buildSectorsMap());
        Map<String, BigDecimal> sectorRel = parseSectorRel(ss);
        boolean ssAvailable = ss != null && ss.get("ranked") != null;

        // momentum 행(scored=true) — Phase1 조립 재사용
        List<Row> rows = new ArrayList<>(assembleRows(momentum, timingMap, stockSector, sectorRel));
        int scoredCount = rows.size();
        int unscoredCount = 0;

        if (union) {
            Map<String, Row> byCode = new LinkedHashMap<>();
            for (Row r : rows) byCode.put(r.getStockCode(), r);
            Map<String, StockScore> snap = recommendationService.categoryScoreSnapshot();

            for (Map.Entry<String, List<String>> e : trackSources.entrySet()) {
                String code = e.getKey();
                Row existing = byCode.get(code);
                if (existing != null) {   // momentum(또는 앞 트랙)에 이미 있음 → 출처만 병합
                    for (String t : e.getValue()) if (!existing.getSources().contains(t)) existing.getSources().add(t);
                    continue;
                }
                // 발굴-only 행 — 4-cat 은 scoreMap lookup(없으면 "—")
                RecommendationDto d = trackDto.get(code);
                StockScore sc = snap.get(code);
                boolean scored = sc != null;
                String sector = stockSector.get(code);
                int supply = scored ? sc.supplyDemand : 0;
                rows.add(Row.builder()
                        .stockCode(code)
                        .stockName(d != null ? d.getStockName() : code)
                        .currentPrice(d != null ? d.getCurrentPrice() : null)
                        .changeRate(d != null ? d.getChangeRate() : null)
                        .sources(new ArrayList<>(e.getValue()))
                        .scored(scored)
                        .totalScore(scored ? sc.getNormalizedTotal() : 0)
                        .technical(scored ? sc.technical : 0)
                        .earnings(scored ? sc.earnings : 0)
                        .sectorMomentum(scored ? sc.sectorMomentum : 0)
                        .supplyDemand(supply)
                        .supplyInverseSuspect(supply >= SUPPLY_INVERSE_SUSPECT_MIN)
                        .timingScore(timingMap.get(code))
                        .sector(sector)
                        .sectorStrengthRel(sector == null ? null : sectorRel.get(sector))
                        .tags(d != null ? d.getTags() : null)
                        .build());
                if (scored) scoredCount++; else unscoredCount++;
            }
            // 정렬: 채점(scored) 우선 → 종합점수 desc. 순수 발굴주("—")는 하단.
            rows.sort(Comparator.comparing(Row::isScored).reversed()
                    .thenComparing(Comparator.comparingInt(Row::getTotalScore).reversed()));
        }

        return JudgmentBoardDto.builder()
                .market(buildMarket())
                .rows(rows)
                .timingAvailable(timing.available())
                .sectorStrengthAvailable(ssAvailable)
                .scope(union ? "union" : "momentum")
                .unionStats(union
                        ? JudgmentBoardDto.UnionStats.builder()
                            .totalRows(rows.size()).scoredRows(scoredCount).unscoredRows(unscoredCount).build()
                        : null)
                .note(union
                        ? "union: 발굴 5트랙 합침. \"—\"=순수 발굴주(momentum 신호 없어 4-cat 미계산 — 출처 태그로 맥락). " + NOTE_BASE
                        : NOTE_BASE)
                .build();
    }

    private interface ItemsSupplier { RecommendationService.Top5Response get(); }

    private static List<RecommendationDto> safeItems(ItemsSupplier s) {
        try {
            var resp = s.get();
            return resp != null && resp.getItems() != null ? resp.getItems() : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void collectTrack(List<RecommendationDto> items, String track,
                                     Map<String, List<String>> trackSources,
                                     Map<String, RecommendationDto> trackDto) {
        for (RecommendationDto d : items) {
            String code = d.getStockCode();
            if (code == null) continue;
            trackSources.computeIfAbsent(code, k -> new ArrayList<>()).add(track);
            trackDto.putIfAbsent(code, d);
        }
    }

    /** momentum 후보 → 보드 행(scored=true). 순수 함수(테스트 대상) — 산식 변경 없이 필드 매핑/플래그만. */
    static List<Row> assembleRows(List<RecommendationDto> candidates,
                                  Map<String, Integer> timingMap,
                                  Map<String, String> stockSector,
                                  Map<String, BigDecimal> sectorRel) {
        List<Row> rows = new ArrayList<>();
        if (candidates == null) return rows;
        for (RecommendationDto c : candidates) {
            if (c == null || c.getStockCode() == null) continue;
            String code = c.getStockCode();
            String sector = stockSector.get(code);
            rows.add(Row.builder()
                    .stockCode(code)
                    .stockName(c.getStockName())
                    .currentPrice(c.getCurrentPrice())
                    .changeRate(c.getChangeRate())
                    .sources(new ArrayList<>(List.of(SOURCE_MOMENTUM)))
                    .scored(true)
                    .totalScore(c.getTotalScore())
                    .technical(c.getTechnical())
                    .earnings(c.getEarnings())
                    .sectorMomentum(c.getSectorMomentum())
                    .supplyDemand(c.getSupplyDemand())
                    .supplyInverseSuspect(c.getSupplyDemand() >= SUPPLY_INVERSE_SUSPECT_MIN)
                    .timingScore(timingMap.get(code))
                    .sector(sector)
                    .sectorStrengthRel(sector == null ? null : sectorRel.get(sector))
                    .tags(c.getTags())
                    .build());
        }
        return rows;
    }

    private JudgmentBoardDto.Market buildMarket() {
        String regime = null;
        try {
            MarketRegimeClient rc = regimeProvider.getIfAvailable();
            if (rc != null) regime = rc.getCurrentRegimeQuiet();
        } catch (Exception ignore) { /* best-effort */ }

        String tilt = null;
        List<String> drivers = null;
        try {
            Map<String, Object> ov = overnightService.getOvernightView();
            if (ov != null && Boolean.TRUE.equals(ov.get("dataAvailable"))) {
                Object t = ov.get("tilt");
                tilt = t == null ? null : t.toString();
                Object d = ov.get("drivers");
                if (d instanceof List<?> list) {
                    drivers = new ArrayList<>();
                    for (Object o : list) drivers.add(String.valueOf(o));
                }
            }
        } catch (Exception ignore) { /* best-effort */ }

        return JudgmentBoardDto.Market.builder()
                .regime(regime).overnightTilt(tilt).overnightDrivers(drivers).build();
    }

    private Map<String, String> buildStockSectorMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (SectorStockConfig.SectorInfo s : sectorStockConfig.getAllSectors()) {
            if (s.getStockCodes() == null) continue;
            for (String code : s.getStockCodes()) map.putIfAbsent(code, s.getName());
        }
        return map;
    }

    private Map<String, List<String>> buildSectorsMap() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (SectorStockConfig.SectorInfo s : sectorStockConfig.getAllSectors()) {
            map.put(s.getName(), s.getStockCodes());
        }
        return map;
    }

    /** python sector-strength data.ranked([{sector, rel_strength, rank}]) → 섹터명→rel. 순수. */
    @SuppressWarnings("unchecked")
    static Map<String, BigDecimal> parseSectorRel(Map<String, Object> sectorStrength) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (sectorStrength == null) return out;
        Object ranked = sectorStrength.get("ranked");
        if (!(ranked instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> row)) continue;
            Object name = row.get("sector");
            Object rel = row.get("rel_strength");
            if (name == null || rel == null) continue;
            try {
                out.put(name.toString(), new BigDecimal(rel.toString()));
            } catch (NumberFormatException ignore) { /* 결측 skip */ }
        }
        return out;
    }
}
