package com.myplatform.backend.service;

import com.myplatform.backend.dto.AiAnalysisResponseDto;
import com.myplatform.backend.dto.AiStockRecommendationDto;
import com.myplatform.backend.dto.ChartPatternDto;
import com.myplatform.backend.dto.CompositeSignalDto;
import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.dto.SupportResistanceDto;
import com.myplatform.backend.dto.VolumeProfileDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 종합 신호 평가 — 5개 신호의 합산.
 *
 * 사용자 의사결정 단순화 — 5곳 보고 종합하지 말고 한 점수로.
 * 단, 단일 신호 기반 매매 X. 3-4개 동시 충족 시 적중률 살짝 ↑.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompositeSignalService {

    private final ChartPatternService chartPatternService;
    private final StockPriceService stockPriceService;
    private final InvestorTradeService investorTradeService;
    private final AiStockAnalysisService aiStockAnalysisService;
    private final StockMasterService stockMasterService;
    private final com.myplatform.backend.repository.StockPriceRepository stockPriceRepository;
    private final CacheManager cacheManager;
    /** scanTopRanked 백그라운드 평가 동시 실행 방지. */
    private final java.util.concurrent.atomic.AtomicBoolean rankingComputing =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** self-injection — evaluateBatch 에서 self.evaluate() 호출 시 @Cacheable 동작 위함. */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private CompositeSignalService self;

    /** 지지선 근처로 판정할 거리 — 현재가 -5% 이내 + HIGH/MEDIUM 강도. */
    private static final BigDecimal SUPPORT_NEAR_PCT = new BigDecimal("-5");
    /** AI 추천 매칭 — TOP 픽 안에 있거나 점수 60+. */
    private static final int AI_SCORE_MIN = 60;

    /**
     * 단일 종목 5개 신호 평가. 30분 캐시.
     */
    @Cacheable(value = "chartPatterns", key = "'cs:' + #stockCode",
            condition = "#stockCode != null && !#stockCode.isEmpty()")
    public CompositeSignalDto evaluate(String stockCode) {
        if (stockCode == null || stockCode.isEmpty()) {
            return empty(stockCode);
        }

        List<CompositeSignalDto.Signal> signals = new ArrayList<>();
        signals.add(evalPattern(stockCode));
        signals.add(evalSupport(stockCode));
        signals.add(evalValueArea(stockCode));
        signals.add(evalSupply(stockCode));
        signals.add(evalAiRecommend(stockCode));

        int matched = (int) signals.stream().filter(CompositeSignalDto.Signal::isMatched).count();

        return CompositeSignalDto.builder()
                .stockCode(stockCode)
                .stockName(stockMasterService.getNameOrDefault(stockCode, stockCode))
                .matchedCount(matched)
                .totalCount(signals.size())
                .signals(signals)
                .build();
    }

    /** 다종목 일괄 평가 — 종목당 캐시 활용. */
    public List<CompositeSignalDto> evaluateBatch(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) return Collections.emptyList();
        return stockCodes.stream().distinct().limit(50)
                .map(code -> {
                    try { return self.evaluate(code); }  // proxy 경유 — 캐시 hit
                    catch (Exception e) {
                        log.debug("composite eval 실패 {}: {}", code, e.getMessage());
                        return empty(code);
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * 거래량 상위 universe 에서 종합 점수 desc 정렬 — "종합 추천 리서치" 용.
     * 5개 신호 매칭 개수 기준. 동점이면 BULLISH 패턴 가중.
     *
     * UX:
     * - 캐시 hit → 즉시 반환.
     * - 캐시 miss → 빈 리스트 즉시 반환 + 백그라운드 평가 시작.
     *   (50 종목 × 5 신호 평가는 1~3분 걸려 사용자 입장에서 timeout. nginx/cloudflare 도 끊어짐.)
     * - 백그라운드 워머가 부팅 + 매 25분 자동 갱신해서 hit 률 상승.
     */
    public List<CompositeSignalDto> scanTopRanked(int limit) {
        int safeLimit = Math.min(Math.max(limit, 10), 100);
        String cacheKey = "rank:" + safeLimit;
        Cache cache = cacheManager.getCache("chartPatterns");
        if (cache != null) {
            Cache.ValueWrapper hit = cache.get(cacheKey);
            // 빈 리스트는 hit 으로 보지 않음 — polling 무한 회피
            if (hit != null && hit.get() instanceof List<?> list && !list.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<CompositeSignalDto> typed = (List<CompositeSignalDto>) list;
                return typed;
            }
        }
        // miss → 백그라운드 시작, 빈 결과 즉시 반환
        triggerRankingComputation(safeLimit);
        return Collections.emptyList();
    }

    /**
     * 백그라운드 평가 트리거. 동시 1건만 진행 (nFlag).
     * 결과는 cache 에 직접 put → 다음 호출이 hit.
     */
    @Async
    public void triggerRankingComputation(int limit) {
        if (!rankingComputing.compareAndSet(false, true)) {
            log.debug("[종합추천] 이미 백그라운드 평가 중 — skip");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            List<CompositeSignalDto> ranked = computeRanking(limit);
            // 빈 리스트는 cache put 안 함 — 다음 호출이 또 미스로 인식, retry 가능.
            if (!ranked.isEmpty()) {
                Cache cache = cacheManager.getCache("chartPatterns");
                if (cache != null) {
                    cache.put("rank:" + limit, ranked);
                }
            }
            log.info("[종합추천] 백그라운드 평가 완료 — {} 종목, {}ms",
                    ranked.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[종합추천] 백그라운드 평가 실패: {}", e.getMessage());
        } finally {
            rankingComputing.set(false);
        }
    }

    /** 부팅 5분 후 + 매 25분 자동 워밍 (캐시 30분 TTL 살짝 안쪽). */
    @Scheduled(scheduler = "cacheScheduler", initialDelay = 300_000L, fixedDelay = 1_500_000L)
    public void scheduledWarmup() {
        log.info("[종합추천] 스케줄 워밍 시작 (limit=30)");
        triggerRankingComputation(30);
    }

    private List<CompositeSignalDto> computeRanking(int safeLimit) {
        // universe 합집합: 거래량 상위 + AI 추천 picks + 외국인/기관 연속매수 풀.
        // 이전: 거래량만 사용 → AI/SUPPLY 신호 매칭 거의 0 → 3점+ 종목 안 나옴.
        // 변경: 5개 신호의 source 가 모두 universe 에 들어와 자연스럽게 다신호 매칭 종목 발견.
        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();

        // 1) 거래량 상위 (시장 주목도 — 25개)
        try {
            List<String> volume = stockPriceRepository.findTopVolumeStockCodes(
                    org.springframework.data.domain.PageRequest.of(0, 25));
            uniq.addAll(volume);
        } catch (Exception e) {
            log.debug("[종합추천] 거래량 universe 조회 실패: {}", e.getMessage());
        }

        // 2) AI 추천 picks (단기 + 장기)
        try {
            AiAnalysisResponseDto analysis = aiStockAnalysisService.getAnalysis();
            if (analysis != null) {
                if (analysis.getShortTermPicks() != null) {
                    analysis.getShortTermPicks().forEach(r -> {
                        if (r.getStockCode() != null) uniq.add(r.getStockCode());
                    });
                }
                if (analysis.getLongTermPicks() != null) {
                    analysis.getLongTermPicks().forEach(r -> {
                        if (r.getStockCode() != null) uniq.add(r.getStockCode());
                    });
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] AI picks universe 조회 실패: {}", e.getMessage());
        }

        // 3) 외국인/기관 3일+ 연속 매수 풀
        try {
            investorTradeService.getConsecutiveBuyStocks("FOREIGN", 3)
                    .forEach(d -> { if (d.getStockCode() != null) uniq.add(d.getStockCode()); });
        } catch (Exception ignore) {}
        try {
            investorTradeService.getConsecutiveBuyStocks("INSTITUTION", 3)
                    .forEach(d -> { if (d.getStockCode() != null) uniq.add(d.getStockCode()); });
        } catch (Exception ignore) {}

        if (uniq.isEmpty()) {
            log.warn("[종합추천] universe 비어있음 — 시세/AI/수급 모두 비어있음");
            return Collections.emptyList();
        }

        // 평가 비용 통제 — 합집합 너무 크지 않게 cap (35개)
        List<String> codes = uniq.stream().limit(35).collect(Collectors.toList());
        log.info("[종합추천] 평가 시작 — universe {} 종목 (합집합)", codes.size());
        List<CompositeSignalDto> all = evaluateBatch(codes);
        all.sort((a, b) -> {
            int cmp = Integer.compare(b.getMatchedCount(), a.getMatchedCount());
            if (cmp != 0) return cmp;
            int aBull = countBullish(a);
            int bBull = countBullish(b);
            return Integer.compare(bBull, aBull);
        });
        return all.stream().limit(safeLimit).collect(Collectors.toList());
    }

    private static int countBullish(CompositeSignalDto dto) {
        if (dto.getSignals() == null) return 0;
        return (int) dto.getSignals().stream()
                .filter(s -> s.isMatched()
                        && ("PATTERN".equals(s.getId()) || "VALUE_AREA".equals(s.getId())
                            || "SUPPORT".equals(s.getId())))
                .count();
    }

    private CompositeSignalDto empty(String code) {
        return CompositeSignalDto.builder()
                .stockCode(code).matchedCount(0).totalCount(5)
                .signals(Collections.emptyList()).build();
    }

    // ==================== 개별 신호 평가 ====================

    private CompositeSignalDto.Signal evalPattern(String stockCode) {
        try {
            List<ChartPatternDto> patterns = chartPatternService.detectPatterns(stockCode);
            ChartPatternDto bullish = patterns.stream()
                    .filter(p -> "BULLISH".equals(p.getSignal()))
                    .findFirst().orElse(null);
            return CompositeSignalDto.Signal.builder()
                    .id("PATTERN").label("차트 패턴 ↑상승")
                    .matched(bullish != null)
                    .detail(bullish != null ? bullish.getLabel() : null)
                    .build();
        } catch (Exception e) {
            return signalFalse("PATTERN", "차트 패턴 ↑상승");
        }
    }

    private CompositeSignalDto.Signal evalSupport(String stockCode) {
        try {
            SupportResistanceDto sr = chartPatternService.detectSupportResistance(stockCode);
            // 첫 번째 지지선 (가장 가까운) 평가
            SupportResistanceDto.Level first = sr.getSupport() != null && !sr.getSupport().isEmpty()
                    ? sr.getSupport().get(0) : null;
            boolean matched = first != null
                    && first.getDistancePct() != null
                    && first.getDistancePct().compareTo(SUPPORT_NEAR_PCT) >= 0  // -5% 이내 (거리 % 음수)
                    && !"LOW".equals(first.getStrength());
            String detail = matched
                    ? String.format("지지선 %s원 (%s%%)",
                        first.getPrice(), first.getDistancePct().setScale(1, RoundingMode.HALF_UP))
                    : null;
            return CompositeSignalDto.Signal.builder()
                    .id("SUPPORT").label("강한 지지선 근처")
                    .matched(matched).detail(detail).build();
        } catch (Exception e) {
            return signalFalse("SUPPORT", "강한 지지선 근처");
        }
    }

    private CompositeSignalDto.Signal evalValueArea(String stockCode) {
        try {
            VolumeProfileDto vp = chartPatternService.computeVolumeProfile(stockCode);
            if (vp == null || vp.getVal() == null) return signalFalse("VALUE_AREA", "저평가 영역");

            // 현재가 — stockPriceService 캐시/DB
            StockPriceDto price = stockPriceService.getStockPrice(stockCode);
            if (price == null || price.getCurrentPrice() == null
                    || price.getCurrentPrice().signum() <= 0) {
                return signalFalse("VALUE_AREA", "저평가 영역");
            }
            boolean matched = price.getCurrentPrice().compareTo(vp.getVal()) <= 0;
            String detail = matched
                    ? String.format("현재 %s ≤ VAL %s",
                        price.getCurrentPrice(), vp.getVal())
                    : null;
            return CompositeSignalDto.Signal.builder()
                    .id("VALUE_AREA").label("저평가 영역 (≤ VAL)")
                    .matched(matched).detail(detail).build();
        } catch (Exception e) {
            return signalFalse("VALUE_AREA", "저평가 영역");
        }
    }

    private CompositeSignalDto.Signal evalSupply(String stockCode) {
        try {
            // 외국인 + 기관 연속매수 종목 (3일+) 풀 — 둘 중 하나라도 포함되면 매칭
            Set<String> codes = new HashSet<>();
            try {
                List<ConsecutiveBuyDto> foreign = investorTradeService
                        .getConsecutiveBuyStocks("FOREIGN", 3);
                foreign.forEach(d -> codes.add(d.getStockCode()));
            } catch (Exception ignore) {}
            try {
                List<ConsecutiveBuyDto> inst = investorTradeService
                        .getConsecutiveBuyStocks("INSTITUTION", 3);
                inst.forEach(d -> codes.add(d.getStockCode()));
            } catch (Exception ignore) {}

            boolean matched = codes.contains(stockCode);
            return CompositeSignalDto.Signal.builder()
                    .id("SUPPLY").label("외국인/기관 순매수")
                    .matched(matched)
                    .detail(matched ? "3일+ 연속 매수 풀" : null).build();
        } catch (Exception e) {
            return signalFalse("SUPPLY", "외국인/기관 순매수");
        }
    }

    private CompositeSignalDto.Signal evalAiRecommend(String stockCode) {
        try {
            AiAnalysisResponseDto analysis = aiStockAnalysisService.getAnalysis();
            if (analysis == null) return signalFalse("AI_RECOMMEND", "AI 추천");

            // 단기 + 중장기 TOP 픽 합쳐서 종목 매칭
            List<AiStockRecommendationDto> all = new ArrayList<>();
            if (analysis.getShortTermPicks() != null) all.addAll(analysis.getShortTermPicks());
            if (analysis.getLongTermPicks() != null) all.addAll(analysis.getLongTermPicks());

            AiStockRecommendationDto match = all.stream()
                    .filter(r -> stockCode.equals(r.getStockCode()))
                    .findFirst().orElse(null);
            boolean matched = match != null
                    && (match.getTotalScore() == null
                        || match.getTotalScore() >= AI_SCORE_MIN);
            String detail = matched
                    ? (match.getTotalScore() != null
                        ? String.format("AI 점수 %d", match.getTotalScore())
                        : "AI 추천 풀 포함")
                    : null;
            return CompositeSignalDto.Signal.builder()
                    .id("AI_RECOMMEND").label("AI 추천")
                    .matched(matched).detail(detail).build();
        } catch (Exception e) {
            return signalFalse("AI_RECOMMEND", "AI 추천");
        }
    }

    private CompositeSignalDto.Signal signalFalse(String id, String label) {
        return CompositeSignalDto.Signal.builder()
                .id(id).label(label).matched(false).build();
    }
}
