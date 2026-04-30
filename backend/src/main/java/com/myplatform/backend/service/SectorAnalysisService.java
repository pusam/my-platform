package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.config.SectorStockConfig.SectorInfo;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.dto.TradingIndicatorDto.LeadingSectorResult;
import com.myplatform.backend.dto.TradingIndicatorDto.SectorRanking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 실시간 주도 섹터 분석 서비스
 *
 * - 각 섹터별 주요 종목(대장주)들의 평균 등락률 계산
 * - 상위 3개 / 하위 3개 섹터 추출
 * - 섹터 내 대장주 식별
 *
 * 활용:
 * - 시장 주도 테마 파악
 * - 상승장에서 강한 섹터 집중 투자
 * - 하락장에서 약한 섹터 회피
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SectorAnalysisService {

    private final SectorStockConfig sectorConfig;
    private final StockPriceService stockPriceService;
    private final SectorTradingService sectorTradingService;

    // 캐시 (5분)
    private volatile LeadingSectorResult cachedResult;
    private volatile LocalDateTime cacheTime;
    private static final long CACHE_DURATION_MS = 5 * 60 * 1000;  // 5분

    // 상위/하위 섹터 개수
    private static final int TOP_SECTOR_COUNT = 3;
    private static final int BOTTOM_SECTOR_COUNT = 3;

    /**
     * 실시간 주도 섹터 랭킹 조회
     *
     * @return 상위/하위 섹터 및 해석
     */
    public LeadingSectorResult getLeadingSectorRanking() {
        // 캐시 확인
        if (cachedResult != null && cacheTime != null &&
                System.currentTimeMillis() - cacheTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() < CACHE_DURATION_MS) {
            log.debug("섹터 랭킹 캐시 히트");
            return cachedResult;
        }

        log.info("섹터 랭킹 계산 시작");
        long startTime = System.currentTimeMillis();

        try {
            // 1. 모든 섹터 정보 가져오기
            List<SectorInfo> sectors = sectorConfig.getAllSectors();
            if (sectors == null || sectors.isEmpty()) {
                return createErrorResult("섹터 설정 없음");
            }

            // 2. 전체 종목 코드 수집
            Set<String> allStockCodes = new HashSet<>();
            for (SectorInfo sector : sectors) {
                allStockCodes.addAll(sector.getStockCodes());
            }

            // 3. 시세 조회 (SectorTradingService 캐시 우선 → 없으면 stockPriceService 의 메모리/DB 캐시)
            // cache-only 로 fallback: 시작 직후·캐시 콜드 시점에 워머가 N개 종목 KIS HTTP 호출하던
            // 패턴(connection leak 의 trigger) 차단. 첫 사이클은 정확도 떨어질 수 있지만 다음 사이클에
            // SectorTrading 워머가 캐시 채우면 자동 회복.
            Map<String, StockPriceDto> priceMap = sectorTradingService.getCachedPriceMap();
            if (priceMap.isEmpty()) {
                log.debug("섹터 분석: SectorTrading 캐시 없음 → StockPriceService cache-only 폴백");
                priceMap = stockPriceService.getStockPricesFromCacheOnly(new ArrayList<>(allStockCodes));
            }
            log.debug("섹터 분석용 시세 조회 완료: {}/{} 종목", priceMap.size(), allStockCodes.size());

            // 4. 섹터별 평균 등락률 계산
            List<SectorRanking> sectorRankings = new ArrayList<>();

            for (SectorInfo sectorInfo : sectors) {
                String sectorCode = sectorInfo.getCode();

                SectorRanking ranking = calculateSectorRanking(sectorCode, sectorInfo, priceMap);
                if (ranking != null) {
                    sectorRankings.add(ranking);
                }
            }

            // 5. 등락률 기준 정렬
            sectorRankings.sort((a, b) -> b.getAverageChangeRate().compareTo(a.getAverageChangeRate()));

            // 순위 부여
            for (int i = 0; i < sectorRankings.size(); i++) {
                sectorRankings.get(i).setRank(i + 1);
            }

            // 6. 상위/하위 섹터 추출
            List<SectorRanking> topSectors = sectorRankings.stream()
                    .limit(TOP_SECTOR_COUNT)
                    .collect(Collectors.toList());

            List<SectorRanking> bottomSectors = new ArrayList<>();
            if (sectorRankings.size() > BOTTOM_SECTOR_COUNT) {
                bottomSectors = sectorRankings.stream()
                        .skip(Math.max(0, sectorRankings.size() - BOTTOM_SECTOR_COUNT))
                        .collect(Collectors.toList());
                Collections.reverse(bottomSectors);  // 하위부터 표시
            }

            // 7. 시장 주도 섹터 식별
            String marketLeader = topSectors.isEmpty() ? "N/A" : topSectors.get(0).getSectorName();

            // 8. 해석 생성
            String interpretation = generateInterpretation(topSectors, bottomSectors);

            LeadingSectorResult result = LeadingSectorResult.builder()
                    .topSectors(topSectors)
                    .bottomSectors(bottomSectors)
                    .marketLeader(marketLeader)
                    .interpretation(interpretation)
                    .calculatedAt(LocalDateTime.now())
                    .build();

            // 캐시 저장
            cachedResult = result;
            cacheTime = LocalDateTime.now();

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("섹터 랭킹 계산 완료: 소요 {}ms, 주도섹터: {}, 상위 {}개, 하위 {}개",
                    elapsed, marketLeader, topSectors.size(), bottomSectors.size());

            return result;

        } catch (Exception e) {
            log.error("섹터 랭킹 계산 실패: {}", e.getMessage(), e);
            return createErrorResult(e.getMessage());
        }
    }

    /**
     * 개별 섹터 랭킹 계산
     */
    private SectorRanking calculateSectorRanking(String sectorCode, SectorInfo sectorInfo,
                                                   Map<String, StockPriceDto> priceMap) {
        List<String> stockCodes = sectorInfo.getStockCodes();
        if (stockCodes == null || stockCodes.isEmpty()) {
            return null;
        }

        BigDecimal totalChangeRate = BigDecimal.ZERO;
        int validCount = 0;

        // 대장주 추적 (가장 높은 등락률)
        String leadingStockCode = null;
        String leadingStockName = null;
        BigDecimal leadingStockChange = null;

        for (String stockCode : stockCodes) {
            String stockName = sectorConfig.getStockName(stockCode);

            StockPriceDto priceDto = priceMap.get(stockCode);
            if (priceDto == null || priceDto.getChangeRate() == null) {
                continue;
            }

            BigDecimal changeRate = priceDto.getChangeRate();
            totalChangeRate = totalChangeRate.add(changeRate);
            validCount++;

            // 대장주 업데이트 (가장 높은 등락률)
            if (leadingStockChange == null || changeRate.compareTo(leadingStockChange) > 0) {
                leadingStockCode = stockCode;
                // 종목명: priceDto → sectorConfig → 직접 조회 순으로 시도
                String name = priceDto.getStockName();
                if (name == null || name.isBlank() || name.equals(stockCode)) {
                    name = stockName;
                }
                if (name == null || name.isBlank() || name.equals(stockCode)) {
                    try {
                        StockPriceDto fresh = stockPriceService.getStockPrice(stockCode);
                        if (fresh != null && fresh.getStockName() != null) {
                            name = fresh.getStockName();
                        }
                    } catch (Exception e) {
                        log.debug("종목명 조회 실패 - {}: {}", stockCode, e.getMessage());
                    }
                }
                leadingStockName = (name != null && !name.isBlank()) ? name : stockCode;
                leadingStockChange = changeRate;
            }
        }

        if (validCount == 0) {
            return null;
        }

        BigDecimal averageChangeRate = totalChangeRate.divide(
                BigDecimal.valueOf(validCount), 2, RoundingMode.HALF_UP);

        return SectorRanking.builder()
                .sectorCode(sectorCode)
                .sectorName(sectorInfo.getName())
                .averageChangeRate(averageChangeRate)
                .leadingStockCode(leadingStockCode)
                .leadingStockName(leadingStockName)
                .leadingStockChange(leadingStockChange)
                .stockCount(validCount)
                .build();
    }

    /**
     * 해석 생성
     */
    private String generateInterpretation(List<SectorRanking> topSectors, List<SectorRanking> bottomSectors) {
        StringBuilder sb = new StringBuilder();

        if (!topSectors.isEmpty()) {
            SectorRanking leader = topSectors.get(0);
            sb.append(String.format("📈 오늘의 주도 섹터: %s (평균 %+.1f%%). ",
                    leader.getSectorName(), leader.getAverageChangeRate()));

            if (leader.getLeadingStockName() != null) {
                sb.append(String.format("대장주: %s(%+.1f%%). ",
                        leader.getLeadingStockName(), leader.getLeadingStockChange()));
            }
        }

        if (!bottomSectors.isEmpty()) {
            SectorRanking laggard = bottomSectors.get(0);
            if (laggard.getAverageChangeRate().compareTo(BigDecimal.ZERO) < 0) {
                sb.append(String.format("📉 약세 섹터: %s (평균 %.1f%%).",
                        laggard.getSectorName(), laggard.getAverageChangeRate()));
            }
        }

        // 시장 전체 분위기 판단
        if (!topSectors.isEmpty()) {
            BigDecimal topAvg = topSectors.get(0).getAverageChangeRate();
            if (topAvg.compareTo(new BigDecimal("2")) >= 0) {
                sb.append(" 🔥 강한 테마 장세!");
            } else if (topAvg.compareTo(BigDecimal.ZERO) < 0) {
                sb.append(" ⚠️ 전체적인 약세 장.");
            }
        }

        return sb.toString().trim();
    }

    /**
     * 에러 결과 생성
     */
    private LeadingSectorResult createErrorResult(String message) {
        return LeadingSectorResult.builder()
                .topSectors(Collections.emptyList())
                .bottomSectors(Collections.emptyList())
                .interpretation("섹터 분석 실패: " + message)
                .calculatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 캐시 클리어
     */
    public void clearCache() {
        cachedResult = null;
        cacheTime = null;
        log.info("섹터 분석 캐시 클리어됨");
    }

    /**
     * 특정 섹터의 상세 정보 조회
     */
    public SectorRanking getSectorDetail(String sectorCode) {
        SectorInfo sectorInfo = sectorConfig.getSector(sectorCode);
        if (sectorInfo == null) {
            return null;
        }

        List<String> stockCodes = sectorInfo.getStockCodes();
        Map<String, StockPriceDto> priceMap = stockPriceService.getStockPrices(stockCodes);

        return calculateSectorRanking(sectorCode, sectorInfo, priceMap);
    }
}
