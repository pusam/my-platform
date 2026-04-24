package com.myplatform.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.config.SectorStockConfig.SectorInfo;
import com.myplatform.backend.dto.InvestorTradeDto;
import com.myplatform.backend.dto.SectorOpportunityDto;
import com.myplatform.backend.dto.SectorOpportunityDto.StockPick;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.dto.TradingIndicatorDto.LeadingSectorResult;
import com.myplatform.backend.dto.TradingIndicatorDto.SectorRanking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 섹터 기회 발굴 서비스.
 *
 * 흐름:
 *   1) SectorAnalysisService.getLeadingSectorRanking() 으로 주도 섹터 선별
 *   2) 각 섹터의 종목 리스트(SectorStockConfig) 를 받아
 *   3) 종목별로 현재가·등락률(StockPriceService 캐시) + 외인/기관 순매수(InvestorTradeService 캐시)
 *      를 조합해 간단 점수화
 *   4) 섹터별 TOP N 종목만 추려 반환
 *
 * 모든 입력이 이미 Redis/메모리 캐시에서 나오므로 이 서비스는 KIS 를 새로 때리지 않는다.
 * 워머(MarketCacheWarmerService) 가 섹터 거래대금/스마트머니를 미리 적재하고,
 * 이 서비스는 그 데이터를 "섹터 ↔ 종목" 관점으로 재조립만 함.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SectorOpportunityService {

    public static final String CACHE_SECTOR_OPPORTUNITY = "sectorOpportunity";
    private static final Duration TTL = Duration.ofMinutes(3);

    private final SectorAnalysisService sectorAnalysisService;
    private final SectorStockConfig sectorStockConfig;
    private final StockPriceService stockPriceService;
    private final InvestorTradeService investorTradeService;
    private final RedisCacheService redisCacheService;

    /**
     * 주도 섹터별 유망 종목 조회.
     *
     * @param topSectorCount 반환할 섹터 수 (상위 N)
     * @param picksPerSector 섹터당 종목 수 (TOP M)
     */
    public List<SectorOpportunityDto> getSectorOpportunities(int topSectorCount, int picksPerSector) {
        String cacheKey = "top" + topSectorCount + "_pick" + picksPerSector;
        List<SectorOpportunityDto> cached = redisCacheService.get(
                CACHE_SECTOR_OPPORTUNITY, cacheKey,
                new TypeReference<List<SectorOpportunityDto>>() {});
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        List<SectorOpportunityDto> result = compute(topSectorCount, picksPerSector);
        if (!result.isEmpty()) {
            redisCacheService.put(CACHE_SECTOR_OPPORTUNITY, cacheKey, result, TTL);
        }
        return result;
    }

    private List<SectorOpportunityDto> compute(int topSectorCount, int picksPerSector) {
        LeadingSectorResult ranking = sectorAnalysisService.getLeadingSectorRanking();
        if (ranking == null || ranking.getTopSectors() == null || ranking.getTopSectors().isEmpty()) {
            log.debug("[SectorOpportunity] 주도 섹터 데이터 없음 — 빈 결과");
            return Collections.emptyList();
        }

        // 스마트머니 조회 — 종목별 외인/기관 순매수 금액 조회용 인덱스 (KIS 안 때리고 Redis/DB만 사용)
        Map<String, BigDecimal> foreignNetBuyMap = toNetBuyMap(
                investorTradeService.getTopTradesRealtime("FOREIGN", 50));
        Map<String, BigDecimal> institutionNetBuyMap = toNetBuyMap(
                investorTradeService.getTopTradesRealtime("INSTITUTION", 50));

        List<SectorOpportunityDto> result = new ArrayList<>();
        int sectorsDone = 0;

        for (SectorRanking sectorRanking : ranking.getTopSectors()) {
            if (sectorsDone >= topSectorCount) break;

            SectorInfo sectorInfo = sectorStockConfig.getSector(sectorRanking.getSectorCode());
            if (sectorInfo == null || sectorInfo.getStockCodes() == null || sectorInfo.getStockCodes().isEmpty()) {
                continue;
            }

            // 섹터 내 종목들 시세 한 번에 (배치 — 캐시/DB 경유)
            Map<String, StockPriceDto> prices = stockPriceService.getStockPricesFromCacheOnly(
                    new ArrayList<>(sectorInfo.getStockCodes()));

            List<StockPick> picks = new ArrayList<>();
            for (String stockCode : sectorInfo.getStockCodes()) {
                StockPriceDto price = prices.get(stockCode);
                // 가격 데이터 없으면 건너뜀 (KIS 호출 유발하지 않음)
                if (price == null || price.getCurrentPrice() == null) continue;

                BigDecimal foreignNB = foreignNetBuyMap.get(stockCode);
                BigDecimal instNB = institutionNetBuyMap.get(stockCode);
                BigDecimal changeRate = price.getChangeRate() != null ? price.getChangeRate() : BigDecimal.ZERO;

                ScoreResult scored = score(changeRate, foreignNB, instNB);

                picks.add(StockPick.builder()
                        .stockCode(stockCode)
                        .stockName(resolveName(stockCode, price))
                        .currentPrice(price.getCurrentPrice())
                        .changeRate(changeRate)
                        .foreignNetBuy(foreignNB)
                        .institutionNetBuy(instNB)
                        .opportunityScore(scored.score)
                        .reasons(scored.reasons)
                        .build());
            }

            // 점수 내림차순, 상위 picksPerSector 개만
            picks.sort(Comparator.comparingInt(StockPick::getOpportunityScore).reversed());
            if (picks.size() > picksPerSector) {
                picks = picks.subList(0, picksPerSector);
            }
            // rank 부여
            for (int i = 0; i < picks.size(); i++) {
                picks.get(i).setRank(i + 1);
            }

            // 섹터당 유망 종목이 하나도 없으면 섹터 자체 스킵
            if (picks.isEmpty()) continue;

            result.add(SectorOpportunityDto.builder()
                    .sectorCode(sectorRanking.getSectorCode())
                    .sectorName(sectorRanking.getSectorName())
                    .sectorAverageChangeRate(sectorRanking.getAverageChangeRate())
                    .sectorStockCount(sectorRanking.getStockCount())
                    .leadingStockCode(sectorRanking.getLeadingStockCode())
                    .leadingStockName(sectorRanking.getLeadingStockName())
                    .picks(picks)
                    .calculatedAt(LocalDateTime.now())
                    .build());
            sectorsDone++;
        }

        log.info("[SectorOpportunity] 계산 완료 — {}개 섹터, 평균 {}개 종목/섹터",
                result.size(),
                result.isEmpty() ? 0 : result.stream().mapToInt(o -> o.getPicks().size()).sum() / result.size());
        return result;
    }

    private Map<String, BigDecimal> toNetBuyMap(List<InvestorTradeDto> list) {
        if (list == null || list.isEmpty()) return Collections.emptyMap();
        return list.stream()
                .filter(t -> t.getStockCode() != null && t.getNetBuyAmount() != null)
                .collect(Collectors.toMap(
                        InvestorTradeDto::getStockCode,
                        InvestorTradeDto::getNetBuyAmount,
                        (a, b) -> a));
    }

    private String resolveName(String stockCode, StockPriceDto price) {
        if (price.getStockName() != null && !price.getStockName().isEmpty()) return price.getStockName();
        return sectorStockConfig.getStockName(stockCode);
    }

    /**
     * 간단 점수 산정 (0~100).
     *   - 등락률 +가점 (최대 40)
     *   - 외국인 순매수 +가점 (최대 30)
     *   - 기관 순매수 +가점 (최대 30)
     * 실제 운영에서 튜닝 필요할 수 있음. 지금은 직관적이면서 편향 적은 구조로.
     */
    private ScoreResult score(BigDecimal changeRate, BigDecimal foreignNB, BigDecimal instNB) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        // 등락률 점수 — 양봉이면 가점, 너무 급등은 감점
        if (changeRate != null) {
            double cr = changeRate.doubleValue();
            if (cr > 0) {
                int s = (int) Math.min(40, cr * 8);  // +1%당 8점, +5% 이상은 40 상한
                score += s;
                if (cr >= 1.0) reasons.add(String.format("당일 +%.1f%%", cr));
            } else if (cr < -2.0) {
                reasons.add(String.format("당일 %.1f%% (약세)", cr));
            }
        }

        // 외국인 순매수 — 양의 값이면 가점
        if (foreignNB != null && foreignNB.signum() > 0) {
            int s = Math.min(30, foreignNB.intValue() / 3);  // 3억당 1점
            score += s;
            reasons.add(String.format("외인 +%.0f억", foreignNB.doubleValue()));
        }

        // 기관 순매수
        if (instNB != null && instNB.signum() > 0) {
            int s = Math.min(30, instNB.intValue() / 3);
            score += s;
            reasons.add(String.format("기관 +%.0f억", instNB.doubleValue()));
        }

        // 쌍끌이 보너스
        if (foreignNB != null && foreignNB.signum() > 0
                && instNB != null && instNB.signum() > 0) {
            score = Math.min(100, score + 10);
            reasons.add("외인·기관 쌍끌이");
        }

        return new ScoreResult(Math.min(100, score), reasons);
    }

    private static final class ScoreResult {
        final int score;
        final List<String> reasons;
        ScoreResult(int score, List<String> reasons) {
            this.score = score;
            this.reasons = reasons;
        }
    }
}
