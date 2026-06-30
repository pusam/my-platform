package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.dto.JudgmentBoardDto;
import com.myplatform.backend.dto.JudgmentBoardDto.Row;
import com.myplatform.backend.service.RecommendationService.RecommendationDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 종합 판단 보드(B안, 2026-06-30) 조립 — 매수후보 + 신호 3계층을 한 보드로.
 *
 * <p><b>산식 무변경</b>: getTop5(momentum 후보)의 점수를 그대로 쓰고, 미검증 신호(차트타이밍·섹터강도·
 * 간밤 미국장)는 <b>조인해서 표시만</b> 한다(점수 미편입, unverified). 조립은 순수함수 {@link #assembleRows}.
 * Phase1 = momentum 후보만. Phase2(예정) = 발굴 5트랙 union(출처태그 추가).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JudgmentBoardService {

    /** 수급 역상관 '의심' 하한 — P1-6 gradient(10-14=41%/15+=35% < 약세 47%). 표본 작음(n=88), 확정 아님. */
    static final int SUPPLY_INVERSE_SUSPECT_MIN = 10;
    private static final String SOURCE_MOMENTUM = "momentum";

    private final RecommendationService recommendationService;
    private final ChartPatternClient chartPatternClient;
    private final SectorStockConfig sectorStockConfig;
    private final OvernightUsMarketService overnightService;
    private final ObjectProvider<MarketRegimeClient> regimeProvider;

    public JudgmentBoardDto getBoard() {
        // 1) 행 = momentum 종합추천 후보(getTop5, 30분 캐시)
        List<RecommendationDto> candidates;
        try {
            candidates = recommendationService.getTop5().getItems();
        } catch (Exception e) {
            log.warn("[JudgmentBoard] 후보 조회 실패: {}", e.getMessage());
            candidates = List.of();
        }
        if (candidates == null) candidates = List.of();
        List<String> codes = candidates.stream()
                .map(RecommendationDto::getStockCode).filter(Objects::nonNull).toList();

        // 2) 차트타이밍 조인(code→score). best-effort.
        ChartPatternClient.TimingFetch timing = chartPatternClient.getTimingSignals(codes);
        Map<String, Integer> timingMap = new LinkedHashMap<>();
        for (ChartPatternClient.TimingSignal t : timing.signals()) {
            if (t.ticker() != null && t.timingScore() != null) timingMap.put(t.ticker(), t.timingScore());
        }

        // 3) 섹터강도 조인(섹터→rel). 종목→섹터는 SectorStockConfig.
        Map<String, String> stockSector = buildStockSectorMap();
        Map<String, List<String>> sectors = buildSectorsMap();
        Map<String, Object> ss = chartPatternClient.getSectorStrength(sectors);
        Map<String, BigDecimal> sectorRel = parseSectorRel(ss);
        boolean ssAvailable = ss != null && ss.get("ranked") != null;

        // 4) 시장 헤더 — regime + 간밤 미국장(둘 다 점수 미편입, 맥락)
        JudgmentBoardDto.Market market = buildMarket();

        // 5) 조립(순수)
        List<Row> rows = assembleRows(candidates, timingMap, stockSector, sectorRel);

        return JudgmentBoardDto.builder()
                .market(market)
                .rows(rows)
                .timingAvailable(timing.available())
                .sectorStrengthAvailable(ssAvailable)
                .note("② 차트타이밍·섹터강도·간밤 미국장 = 미검증 참고(점수 미편입). "
                        + "③ 수급 고점 = 역상관 의심(표본 작음 n=88, 확정 아님). 종합점수는 ① 검증/게이트 기준.")
                .build();
    }

    /** 후보 + 조인맵 → 보드 행. 순수 함수(테스트 대상) — 산식 변경 없이 필드 매핑/플래그만. */
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
                    // ① 점수(검증/게이트)
                    .totalScore(c.getTotalScore())
                    .technical(c.getTechnical())
                    .earnings(c.getEarnings())
                    .sectorMomentum(c.getSectorMomentum())
                    // ③ 경고(역상관 의심)
                    .supplyDemand(c.getSupplyDemand())
                    .supplyInverseSuspect(c.getSupplyDemand() >= SUPPLY_INVERSE_SUSPECT_MIN)
                    // ② 참고(미검증)
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
