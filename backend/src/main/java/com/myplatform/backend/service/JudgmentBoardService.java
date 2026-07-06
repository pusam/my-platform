package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.dto.JudgmentBoardDto;
import com.myplatform.backend.dto.JudgmentBoardDto.Row;
import com.myplatform.backend.dto.StockCatalystDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.StockCatalyst;
import com.myplatform.backend.repository.StockCatalystRepository;
import com.myplatform.backend.service.RecommendationService.RecommendationDto;
import com.myplatform.backend.service.RecommendationService.StockScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    /** 보드 재료 표시 날짜창(일) — 오늘 없으면 어제까지 최신 노출(2일↑은 제외, §4c 낡음 방지). §4b 분류 일캐시 불변. */
    static final int CATALYST_DISPLAY_DAYS = 2;
    private static final String SOURCE_MOMENTUM = "momentum";
    private static final String NOTE_BASE =
            "② 차트타이밍·섹터강도·간밤 미국장·RVOL = 미검증 참고(점수 미편입). "
            + "③ 수급 고점 = 역상관 의심(표본 작음 n=88, 확정 아님). 종합점수는 ① 검증/게이트 기준.";

    private final RecommendationService recommendationService;
    private final ChartPatternClient chartPatternClient;
    private final SectorStockConfig sectorStockConfig;
    private final OvernightUsMarketService overnightService;
    private final ObjectProvider<MarketRegimeClient> regimeProvider;
    private final StockCatalystRepository catalystRepository;
    private final StockPriceService stockPriceService;
    private final RvolService rvolService;

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

        // 매매 맥락 enrich(표시 전용 — 산식 미편입): 재료 일캐시 read + 거래대금 시세 cache-only.
        enrichContext(rows);

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

    /**
     * 재료·거래대금 맥락을 행에 채운다(표시 전용 — 산식 미편입).
     * <b>재료</b>: 오늘자 {@code stock_catalyst} <b>일캐시 read 만</b> — 신규 분류(Gemini) 호출 없음(§4b quota/스팸가드).
     * <b>거래대금</b>: 시세 <b>cache-only</b>(KIS 신규 호출 0, 시세 단일경로). 실측 없으면 null(§4c 임시값 생성 금지).
     * 둘 다 best-effort — 실패해도 보드는 나머지로 렌더.
     */
    private void enrichContext(List<Row> rows) {
        List<String> codes = rows.stream().map(Row::getStockCode).filter(Objects::nonNull).toList();
        if (codes.isEmpty()) return;

        try {   // 재료: 최근 N일 중 코드별 '최신 유의미(비-NONE)' read(신규 분류 안 함). 2일↑ 제외(§4c).
            LocalDate today = LocalDate.now();
            LocalDate minDate = today.minusDays(CATALYST_DISPLAY_DAYS - 1);
            Map<String, StockCatalyst> latest = pickLatestMeaningful(
                    catalystRepository.findByCatalystDateGreaterThanEqualAndStockCodeIn(minDate, codes));
            applyCatalyst(rows, latest, today);
        } catch (Exception e) {
            log.warn("[JudgmentBoard] 재료 배지 조회 실패(생략): {}", e.getMessage());
        }

        try {   // 거래대금: 시세 cache-only(단일경로)
            Map<String, StockPriceDto> priceByCode = stockPriceService.getStockPricesFromCacheOnly(codes);
            applyTradingValue(rows, priceByCode);
        } catch (Exception e) {
            log.warn("[JudgmentBoard] 거래대금 조립 실패(생략): {}", e.getMessage());
        }

        try {   // RVOL(V41 ② 참고): 위에서 조립된 거래대금(같은 시세 스냅샷)을 분자로 재사용 — 이중 조회 없음.
            Map<String, BigDecimal> todayValueByCode = new LinkedHashMap<>();
            for (Row r : rows) {
                if (r.getStockCode() != null && r.getTradingValue() != null) {
                    todayValueByCode.put(r.getStockCode(), r.getTradingValue());
                }
            }
            applyRvol(rows, rvolService.getRvolBulk(todayValueByCode));
        } catch (Exception e) {
            log.warn("[JudgmentBoard] RVOL 조립 실패(생략): {}", e.getMessage());
        }
    }

    /** RVOL 매핑(순수) — 미산출 종목은 미설정(null=§4c). 표시 전용, 랭킹/산식 미편입. */
    static void applyRvol(List<Row> rows, Map<String, BigDecimal> rvolByCode) {
        if (rvolByCode == null || rvolByCode.isEmpty()) return;
        for (Row r : rows) {
            BigDecimal rvol = rvolByCode.get(r.getStockCode());
            if (rvol != null) r.setRvol(rvol);
        }
    }

    /**
     * 최근 재료들 중 코드별 <b>최신 유의미(비-NONE)</b> 선택 — 순수 함수.
     * ⚠ NONE 을 최신 선택에 넣으면 "오늘 NONE(오늘 뉴스 없음)"이 "어제 실재료"를 가려 배지가 다시 깜빡인다
     * (2일 백업 취지 훼손). NONE/방향NONE 은 제외하고 남은 것 중 최신 → 오늘 NONE 이어도 어제 실재료 유지,
     * 둘 다 NONE 이면 무배지.
     */
    static Map<String, StockCatalyst> pickLatestMeaningful(List<StockCatalyst> catalysts) {
        Map<String, StockCatalyst> latest = new HashMap<>();
        if (catalysts == null) return latest;
        for (StockCatalyst c : catalysts) {
            if (c == null || c.getStockCode() == null || c.getCatalystDate() == null) continue;
            if (c.getCatalystType() == null || c.getCatalystType() == StockCatalyst.CatalystType.NONE) continue;
            if (c.getDirection() == null || c.getDirection() == StockCatalyst.Direction.NONE) continue;
            StockCatalyst prev = latest.get(c.getStockCode());
            if (prev == null || c.getCatalystDate().isAfter(prev.getCatalystDate())) latest.put(c.getStockCode(), c);
        }
        return latest;
    }

    /**
     * 재료 태그 매핑(순수) — NONE/방향NONE 은 생략(배지 안 뜸). §4b 표시 전용.
     * catalystAgeDays(오늘=0/어제=1) 세팅 — §4c 낡음 위장 방지(프론트가 "어제" 표기). today=null 이면 age 미설정.
     */
    static void applyCatalyst(List<Row> rows, Map<String, StockCatalyst> catByCode, LocalDate today) {
        if (catByCode == null || catByCode.isEmpty()) return;
        for (Row r : rows) {
            StockCatalyst c = catByCode.get(r.getStockCode());
            if (c == null || c.getCatalystType() == null || c.getDirection() == null) continue;
            if (c.getCatalystType() == StockCatalyst.CatalystType.NONE) continue;
            if (c.getDirection() == StockCatalyst.Direction.NONE) continue;
            r.setCatalystType(c.getCatalystType().name());
            r.setCatalystLabel(StockCatalystDto.labelOf(c.getCatalystType()));
            r.setCatalystDirection(c.getDirection().name());
            if (today != null && c.getCatalystDate() != null) {
                r.setCatalystAgeDays((int) java.time.temporal.ChronoUnit.DAYS.between(c.getCatalystDate(), today));
            }
        }
    }

    /** 거래대금 매핑(순수) — 실측 누적 우선, 없으면 현재가×거래량 폴백, 둘 다 없으면 미설정(null). §4c. */
    static void applyTradingValue(List<Row> rows, Map<String, StockPriceDto> priceByCode) {
        if (priceByCode == null || priceByCode.isEmpty()) return;
        for (Row r : rows) {
            BigDecimal tv = resolveTradingValue(priceByCode.get(r.getStockCode()));
            if (tv != null) r.setTradingValue(tv);
        }
    }

    /** 누적거래대금(실측) → 현재가×거래량 폴백 → null. 임시값 생성 금지(§4c). 순수. */
    static BigDecimal resolveTradingValue(StockPriceDto p) {
        if (p == null) return null;
        BigDecimal acc = p.getAccumulatedTradingValue();
        if (acc != null && acc.signum() > 0) return acc;
        BigDecimal price = p.getCurrentPrice();
        BigDecimal vol = p.getVolume();
        if (price != null && vol != null && vol.signum() > 0) return price.multiply(vol);
        return null;
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
