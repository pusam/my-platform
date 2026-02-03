package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.util.StockNameResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 퀀트 스크리너 서비스
 * - 마법의 공식 (Magic Formula) 스크리닝
 * - PEG 기반 저평가 성장주 스크리닝
 * - 분기 실적 턴어라운드 스크리닝
 *
 * [성능 최적화]
 * - N+1 문제 해결: Bulk 조회 후 메모리에서 groupingBy 처리
 * - 적자 기업 필터링: 마법의 공식에서 PER <= 0 기업 사전 제외
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuantScreenerService {

    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final TelegramNotificationService telegramNotificationService;
    private final KoreaInvestmentService koreaInvestmentService;
    private final StockPriceService stockPriceService;

    private static final BigDecimal MAX_DEBT_RATIO = new BigDecimal("200"); // 부채비율 상한 200%
    private static final BigDecimal MIN_NET_INCOME = new BigDecimal("30");  // 최소 순이익 30억원

    // 데이터 클렌징 상수 (PEG 스크리너용)
    private static final BigDecimal MIN_MARKET_CAP_FOR_PEG = new BigDecimal("500");  // 최소 시가총액 500억원 (동전주 제외)

    // 모멘텀 스크리너 상수
    private static final BigDecimal MIN_VOLUME_RATIO = new BigDecimal("150");        // 최소 거래량 비율 150% (완화)
    private static final BigDecimal MIN_MARKET_CAP_FOR_MOMENTUM = new BigDecimal("500"); // 최소 시가총액 500억원 (완화)

    /**
     * 마법의 공식 스크리너
     * - (영업이익률 순위 + ROE 순위 + PER 순위) 합산으로 종합 순위 계산
     * - 영업이익률이 없으면 ROE + PER만으로 계산 (2지표 모드)
     *
     * [필터 조건]
     * - PER > 0 (적자 기업 제외)
     * - ROE > 0 (수익성 있는 기업)
     * - 부채비율 <= 200% (ROE 왜곡 방지, 재무 건전성 확보)
     */
    public List<ScreenerResultDto> getMagicFormulaStocks(Integer limit, BigDecimal minMarketCap) {
        log.info("마법의 공식 스크리닝 시작 - limit: {}, minMarketCap: {}", limit, minMarketCap);

        // 1. 조건에 맞는 종목 조회 (Repository에서 이미 PER > 0 필터 적용됨)
        List<StockFinancialData> allStocks = stockFinancialDataRepository.findForMagicFormula(minMarketCap);

        // 2. 기본 필터: PER > 0, ROE > 0, 부채비율 <= 200%
        List<StockFinancialData> stocks = allStocks.stream()
                .filter(s -> s.getPer() != null && s.getPer().compareTo(BigDecimal.ZERO) > 0)
                .filter(s -> s.getRoe() != null && s.getRoe().compareTo(BigDecimal.ZERO) > 0)
                // 부채비율 필터: null이면 통과, 있으면 200% 이하만 포함
                .filter(s -> s.getDebtRatio() == null || s.getDebtRatio().compareTo(MAX_DEBT_RATIO) <= 0)
                .collect(Collectors.toList());

        if (stocks.isEmpty()) {
            log.info("마법의 공식 조건에 맞는 종목이 없습니다.");
            return Collections.emptyList();
        }

        // 영업이익률이 있는 종목 수 확인
        long withOperatingMargin = stocks.stream()
                .filter(s -> s.getOperatingMargin() != null && s.getOperatingMargin().compareTo(BigDecimal.ZERO) > 0)
                .count();
        boolean useOperatingMargin = withOperatingMargin > stocks.size() / 2; // 절반 이상 있으면 사용

        log.info("마법의 공식 후보 종목 수: {} (영업이익률 있는 종목: {}, 사용여부: {})",
                stocks.size(), withOperatingMargin, useOperatingMargin);

        // ⭐ 데이터 품질 개선: 종목명/시가총액 보완
        enrichStockDataBatch(stocks);

        // 3. 각 지표별 순위 계산
        int totalStocks = stocks.size();

        // 영업이익률 순위 (있는 경우만, 없으면 최하위 순위 부여)
        Map<String, Integer> operatingMarginRanks = new HashMap<>();
        if (useOperatingMargin) {
            List<StockFinancialData> withOpMargin = stocks.stream()
                    .filter(s -> s.getOperatingMargin() != null && s.getOperatingMargin().compareTo(BigDecimal.ZERO) > 0)
                    .sorted(Comparator.comparing(StockFinancialData::getOperatingMargin).reversed())
                    .collect(Collectors.toList());
            for (int i = 0; i < withOpMargin.size(); i++) {
                operatingMarginRanks.put(withOpMargin.get(i).getStockCode(), i + 1);
            }
        }

        // ROE 순위 (높을수록 좋음 = 낮은 순위)
        Map<String, Integer> roeRanks = calculateRanks(stocks,
                Comparator.comparing(StockFinancialData::getRoe).reversed());

        // PER 순위 (낮을수록 좋음 = 낮은 순위)
        Map<String, Integer> perRanks = calculateRanks(stocks,
                Comparator.comparing(StockFinancialData::getPer));

        // 4. 종합 순위 계산
        final boolean finalUseOperatingMargin = useOperatingMargin;
        List<ScreenerResultDto> results = stocks.stream()
                .map(stock -> {
                    Integer roeRank = roeRanks.getOrDefault(stock.getStockCode(), totalStocks);
                    Integer perRank = perRanks.getOrDefault(stock.getStockCode(), totalStocks);
                    Integer opMarginRank = totalStocks; // 기본값: 최하위

                    int totalScore;
                    if (finalUseOperatingMargin) {
                        opMarginRank = operatingMarginRanks.getOrDefault(stock.getStockCode(), totalStocks);
                        totalScore = opMarginRank + roeRank + perRank;
                    } else {
                        // 영업이익률 없으면 ROE + PER만으로 계산 (가중치 1.5배)
                        totalScore = (int) ((roeRank + perRank) * 1.5);
                    }

                    return ScreenerResultDto.builder()
                            .stockCode(stock.getStockCode())
                            .stockName(stock.getStockName())
                            .market(stock.getMarket())
                            .sector(stock.getSector())
                            .currentPrice(stock.getCurrentPrice())
                            .marketCap(stock.getMarketCap())
                            .per(stock.getPer())
                            .pbr(stock.getPbr())
                            .roe(stock.getRoe())
                            .operatingMargin(stock.getOperatingMargin())
                            .netMargin(stock.getNetMargin())
                            .eps(stock.getEps())
                            .epsGrowth(stock.getEpsGrowth())
                            .peg(stock.getPeg())
                            .magicFormulaScore(BigDecimal.valueOf(totalScore))
                            .operatingMarginRank(opMarginRank)
                            .roeRank(roeRank)
                            .perRank(perRank)
                            .revenueGrowth(stock.getRevenueGrowth())
                            .profitGrowth(stock.getProfitGrowth())
                            .build();
                })
                .sorted(Comparator.comparing(ScreenerResultDto::getMagicFormulaScore))
                .collect(Collectors.toList());

        // 5. 최종 순위 부여
        for (int i = 0; i < results.size(); i++) {
            results.get(i).setMagicFormulaRank(i + 1);
        }

        // 6. limit 적용
        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }

        log.info("마법의 공식 스크리닝 완료 - 결과 {}건", results.size());
        return results;
    }

    /**
     * 순위 계산 헬퍼 메서드
     */
    private Map<String, Integer> calculateRanks(List<StockFinancialData> stocks,
                                                 Comparator<StockFinancialData> comparator) {
        List<StockFinancialData> sorted = stocks.stream()
                .sorted(comparator)
                .collect(Collectors.toList());

        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            ranks.put(sorted.get(i).getStockCode(), i + 1);
        }
        return ranks;
    }

    /**
     * PEG 스크리너
     * - PEG = PER / EPS성장률 (또는 profitGrowth로 대체)
     * - PEG < maxPeg (기본 1.0)인 저평가 성장주 발굴
     *
     * [로직]
     * 1. DB에 PEG가 이미 계산된 종목 조회
     * 2. 없으면 epsGrowth 또는 profitGrowth로 PEG 계산
     * 3. 그래도 없으면 분기별 데이터에서 직접 EPS 성장률 계산
     *
     * [데이터 품질 개선]
     * - 종목명이 코드와 같거나 비어있으면 KIS API로 조회하여 보완
     * - 시가총액이 0이면 현재가로 계산하여 보완
     * - 시가총액 500억 미만 동전주/관리종목 제외
     */
    @Transactional
    public List<ScreenerResultDto> getLowPegStocks(BigDecimal maxPeg, BigDecimal minEpsGrowth, Integer limit) {
        log.info("PEG 스크리닝 시작 - maxPeg: {}, minEpsGrowth: {}, limit: {}", maxPeg, minEpsGrowth, limit);

        // 기본값 설정
        if (maxPeg == null) {
            maxPeg = new BigDecimal("1.0");
        }
        if (minEpsGrowth == null) {
            minEpsGrowth = new BigDecimal("10.0"); // 최소 10% 성장
        }

        final BigDecimal finalMaxPeg = maxPeg;
        final BigDecimal finalMinGrowth = minEpsGrowth;

        // 1차: PEG가 이미 계산된 종목 조회
        List<StockFinancialData> stocks = stockFinancialDataRepository.findLowPegStocks(maxPeg, minEpsGrowth);
        log.info("DB에서 PEG 있는 종목: {}건", stocks.size());

        // 2차: 성장률 데이터가 있는 종목으로 PEG 계산
        if (stocks.isEmpty()) {
            log.info("PEG 데이터가 없어 성장률 기반으로 계산합니다.");
            stocks = stockFinancialDataRepository.findStocksWithGrowthData();
            log.info("성장률 데이터 있는 종목: {}건", stocks.size());
        }

        // 3차: 분기별 데이터에서 직접 EPS 성장률 계산 (fallback)
        if (stocks.isEmpty()) {
            log.info("성장률 데이터 없음 - 분기별 데이터에서 직접 계산합니다.");
            return calculatePegFromQuarterlyData(finalMaxPeg, finalMinGrowth, limit);
        }

        // ⭐ 데이터 품질 개선: 종목명/시가총액 보완
        enrichStockDataBatch(stocks);

        List<ScreenerResultDto> results = stocks.stream()
                .map(stock -> {
                    // PEG 계산: 기존 PEG 사용 또는 epsGrowth/profitGrowth로 계산
                    BigDecimal peg = stock.getPeg();
                    BigDecimal growthRate = stock.getEpsGrowth();

                    // PEG가 없으면 계산
                    if (peg == null && stock.getPer() != null && stock.getPer().compareTo(BigDecimal.ZERO) > 0) {
                        // epsGrowth 우선, 없으면 profitGrowth 사용
                        if (growthRate != null && growthRate.compareTo(BigDecimal.ZERO) > 0) {
                            peg = stock.getPer().divide(growthRate, 2, RoundingMode.HALF_UP);
                        } else if (stock.getProfitGrowth() != null && stock.getProfitGrowth().compareTo(BigDecimal.ZERO) > 0) {
                            peg = stock.getPer().divide(stock.getProfitGrowth(), 2, RoundingMode.HALF_UP);
                            growthRate = stock.getProfitGrowth();
                        }
                    }

                    return ScreenerResultDto.builder()
                            .stockCode(stock.getStockCode())
                            .stockName(stock.getStockName())
                            .market(stock.getMarket())
                            .sector(stock.getSector())
                            .currentPrice(stock.getCurrentPrice())
                            .marketCap(stock.getMarketCap())
                            .per(stock.getPer())
                            .pbr(stock.getPbr())
                            .roe(stock.getRoe())
                            .operatingMargin(stock.getOperatingMargin())
                            .netMargin(stock.getNetMargin())
                            .eps(stock.getEps())
                            .epsGrowth(growthRate != null ? growthRate : stock.getEpsGrowth())
                            .peg(peg)
                            .revenueGrowth(stock.getRevenueGrowth())
                            .profitGrowth(stock.getProfitGrowth())
                            .build();
                })
                // PEG 필터: 0 < PEG <= maxPeg, 성장률 >= minGrowth
                .filter(dto -> dto.getPeg() != null && dto.getPeg().compareTo(BigDecimal.ZERO) > 0)
                .filter(dto -> dto.getPeg().compareTo(finalMaxPeg) <= 0)
                .filter(dto -> dto.getEpsGrowth() != null && dto.getEpsGrowth().compareTo(finalMinGrowth) >= 0)
                // ⭐ 데이터 클렌징: 시가총액 500억 이상만 (동전주/관리종목 제외)
                .filter(dto -> dto.getMarketCap() != null && dto.getMarketCap().compareTo(MIN_MARKET_CAP_FOR_PEG) >= 0)
                .sorted(Comparator.comparing(ScreenerResultDto::getPeg))
                .collect(Collectors.toList());

        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }

        log.info("PEG 스크리닝 완료 - 결과 {}건 (시가총액 500억 이상만)", results.size());
        return results;
    }

    /**
     * profitGrowth 기반으로 PEG 계산 (fallback)
     * - DB에 PEG가 없고 epsGrowth도 없을 때 profitGrowth로 대체
     * - 데이터 클렌징: 시가총액 500억 이상만 포함
     */
    private List<ScreenerResultDto> calculatePegFromQuarterlyData(BigDecimal maxPeg, BigDecimal minGrowth, Integer limit) {
        // profitGrowth가 있는 종목들 조회
        List<StockFinancialData> allStocks = stockFinancialDataRepository.findStocksWithGrowthData();
        log.info("성장률 데이터 있는 종목 (fallback): {}건", allStocks.size());

        // ⭐ 데이터 품질 개선
        enrichStockDataBatch(allStocks);

        List<ScreenerResultDto> results = new ArrayList<>();

        for (StockFinancialData stock : allStocks) {
            if (stock.getPer() == null || stock.getPer().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // ⭐ 데이터 클렌징: 시가총액 500억 이상만
            if (stock.getMarketCap() == null || stock.getMarketCap().compareTo(MIN_MARKET_CAP_FOR_PEG) < 0) {
                continue;
            }

            // profitGrowth로 PEG 계산
            BigDecimal growthRate = stock.getProfitGrowth();
            if (growthRate == null || growthRate.compareTo(minGrowth) < 0) {
                continue;
            }

            BigDecimal peg = stock.getPer().divide(growthRate, 2, RoundingMode.HALF_UP);
            if (peg.compareTo(BigDecimal.ZERO) <= 0 || peg.compareTo(maxPeg) > 0) {
                continue;
            }

            results.add(ScreenerResultDto.builder()
                    .stockCode(stock.getStockCode())
                    .stockName(stock.getStockName())
                    .market(stock.getMarket())
                    .sector(stock.getSector())
                    .currentPrice(stock.getCurrentPrice())
                    .marketCap(stock.getMarketCap())
                    .per(stock.getPer())
                    .pbr(stock.getPbr())
                    .roe(stock.getRoe())
                    .operatingMargin(stock.getOperatingMargin())
                    .netMargin(stock.getNetMargin())
                    .eps(stock.getEps())
                    .epsGrowth(growthRate) // profitGrowth를 epsGrowth로 사용
                    .peg(peg)
                    .revenueGrowth(stock.getRevenueGrowth())
                    .profitGrowth(stock.getProfitGrowth())
                    .build());
        }

        // PEG 기준 정렬
        results.sort(Comparator.comparing(ScreenerResultDto::getPeg));

        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }

        log.info("profitGrowth 기반 PEG 계산 완료 - 결과 {}건 (시가총액 500억 이상만)", results.size());
        return results;
    }

    /**
     * 턴어라운드 스크리너
     * - 직전 분기 적자 → 당 분기 흑자 전환 종목
     * - 순이익 개선률 계산
     *
     * [개선 1] N+1 문제 해결
     * - 기존: findAllStockCodes() 후 2,000번 개별 쿼리 (서버 뻗음)
     * - 개선: findAllRecentData()로 한 번에 조회 → 메모리에서 groupingBy 처리
     *
     * [개선 2] 여러 분기 데이터가 없는 경우 profitGrowth 기반으로 대체
     *
     * [개선 3] 데이터 품질 개선
     * - 종목명이 코드와 같거나 비어있으면 KIS API로 조회하여 보완
     * - 시가총액/현재가/PER/PBR이 null이면 API로 조회하여 보완
     * - 시가총액 500억 미만 동전주/관리종목 제외
     */
    @Transactional
    public List<ScreenerResultDto> getTurnaroundStocks(Integer limit) {
        log.info("턴어라운드 스크리닝 시작 - limit: {}", limit);

        LocalDate minDate = LocalDate.now().minusMonths(12);
        List<StockFinancialData> allRecentData = stockFinancialDataRepository.findAllRecentData(minDate);
        log.info("최근 12개월 데이터: {}건 (minDate: {})", allRecentData.size(), minDate);

        List<ScreenerResultDto> results = new ArrayList<>();

        // 데이터 품질 개선을 위한 종목 데이터 맵 (DB 업데이트용)
        Map<String, StockFinancialData> stockDataForUpdate = new HashMap<>();

        if (!allRecentData.isEmpty()) {
            log.info("턴어라운드 분석 대상 데이터: {}건", allRecentData.size());

            // [성능 최적화] 메모리에서 종목별로 그룹화 (날짜 내림차순 정렬 유지)
            Map<String, List<StockFinancialData>> stockDataMap = allRecentData.stream()
                    .collect(Collectors.groupingBy(
                            StockFinancialData::getStockCode,
                            LinkedHashMap::new,  // 순서 유지
                            Collectors.toList()
                    ));

            // 각 종목별로 턴어라운드 여부 판단
            for (Map.Entry<String, List<StockFinancialData>> entry : stockDataMap.entrySet()) {
                List<StockFinancialData> historicalData = entry.getValue();

                // 최소 2개 분기 데이터 필요
                if (historicalData.size() < 2) {
                    continue;
                }

                // 이미 날짜 내림차순 정렬되어 있음 (쿼리에서 ORDER BY)
                StockFinancialData current = historicalData.get(0);
                StockFinancialData previous = historicalData.get(1);

                // 순이익 데이터 확인
                if (current.getNetIncome() == null || previous.getNetIncome() == null) {
                    continue;
                }

                BigDecimal currentNetIncome = current.getNetIncome();
                BigDecimal previousNetIncome = previous.getNetIncome();

                // 잡주 필터링: 당 분기 순이익이 최소 30억원 이상이어야 함
                if (currentNetIncome.compareTo(MIN_NET_INCOME) < 0) {
                    continue;
                }

                String turnaroundType = null;
                BigDecimal changeRate = null;

                // 적자 → 흑자 전환 (단, 흑자 전환 후 30억원 이상)
                if (previousNetIncome.compareTo(BigDecimal.ZERO) < 0 &&
                    currentNetIncome.compareTo(BigDecimal.ZERO) > 0) {
                    turnaroundType = "LOSS_TO_PROFIT";
                    changeRate = new BigDecimal("999.99"); // 흑자전환 특별 표기
                }
                // 흑자 → 흑자 (이익 증가 50% 이상)
                else if (previousNetIncome.compareTo(BigDecimal.ZERO) > 0 &&
                         currentNetIncome.compareTo(BigDecimal.ZERO) > 0 &&
                         currentNetIncome.compareTo(previousNetIncome) > 0) {

                    changeRate = currentNetIncome.subtract(previousNetIncome)
                            .divide(previousNetIncome.abs(), 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));

                    if (changeRate.compareTo(new BigDecimal("50")) >= 0) {
                        turnaroundType = "PROFIT_GROWTH";
                    }
                }

                if (turnaroundType != null) {
                    // 데이터 품질 개선 대상 저장
                    stockDataForUpdate.put(current.getStockCode(), current);

                    results.add(ScreenerResultDto.builder()
                            .stockCode(current.getStockCode())
                            .stockName(current.getStockName())
                            .market(current.getMarket())
                            .sector(current.getSector())
                            .currentPrice(current.getCurrentPrice())
                            .marketCap(current.getMarketCap())
                            .per(current.getPer())
                            .pbr(current.getPbr())
                            .roe(current.getRoe())
                            .operatingMargin(current.getOperatingMargin())
                            .netMargin(current.getNetMargin())
                            .eps(current.getEps())
                            .epsGrowth(current.getEpsGrowth())
                            .peg(current.getPeg())
                            .turnaroundType(turnaroundType)
                            .previousNetIncome(previousNetIncome)
                            .currentNetIncome(currentNetIncome)
                            .netIncomeChangeRate(changeRate)
                            .revenueGrowth(current.getRevenueGrowth())
                            .profitGrowth(current.getProfitGrowth())
                            .build());
                }
            }

            log.info("분기 비교 기반 턴어라운드 결과: {}건", results.size());
        }

        // 분기 비교 결과가 없으면 profitGrowth 기반으로 대체
        if (results.isEmpty()) {
            log.info("분기 비교 데이터가 없어 profitGrowth 기반으로 턴어라운드 종목을 조회합니다.");
            results = findTurnaroundByProfitGrowth(limit);
        } else {
            // ⭐ 데이터 품질 개선: 종목명/시가총액/PER/PBR 보완
            enrichScreenerResults(results, new ArrayList<>(stockDataForUpdate.values()));

            // ⭐ 데이터 클렌징: 시가총액 500억 이상만 (동전주/관리종목 제외)
            results = results.stream()
                    .filter(dto -> dto.getMarketCap() != null && dto.getMarketCap().compareTo(MIN_MARKET_CAP_FOR_PEG) >= 0)
                    .collect(Collectors.toList());

            // 적자→흑자 전환 우선, 그 다음 변화율 높은 순으로 정렬
            results.sort((a, b) -> {
                if ("LOSS_TO_PROFIT".equals(a.getTurnaroundType()) && !"LOSS_TO_PROFIT".equals(b.getTurnaroundType())) {
                    return -1;
                }
                if (!"LOSS_TO_PROFIT".equals(a.getTurnaroundType()) && "LOSS_TO_PROFIT".equals(b.getTurnaroundType())) {
                    return 1;
                }
                return b.getNetIncomeChangeRate().compareTo(a.getNetIncomeChangeRate());
            });

            if (limit != null && limit > 0) {
                results = results.stream().limit(limit).collect(Collectors.toList());
            }
        }

        log.info("턴어라운드 스크리닝 완료 - 결과 {}건 (시가총액 500억 이상)", results.size());
        return results;
    }

    /**
     * profitGrowth 기반 턴어라운드 종목 조회
     * - 분기 비교 데이터가 없을 때 사용
     * - profitGrowth가 높은 종목 = 실적 개선 종목으로 간주
     * - 순이익 30억원 이상 필터 적용 (잡주 제외)
     * - 데이터 품질 개선 및 시가총액 500억 미만 제외
     */
    private List<ScreenerResultDto> findTurnaroundByProfitGrowth(Integer limit) {
        // 성장률 데이터가 있는 최신 데이터 조회
        List<StockFinancialData> allStocks = stockFinancialDataRepository.findStocksWithGrowthData();
        log.info("성장률 데이터 있는 종목: {}건", allStocks.size());

        // ⭐ 데이터 품질 개선
        enrichStockDataBatch(allStocks);

        // profitGrowth가 50% 이상이고 순이익 30억원 이상인 종목 조회
        List<ScreenerResultDto> results = allStocks.stream()
                .filter(s -> s.getProfitGrowth() != null && s.getProfitGrowth().compareTo(new BigDecimal("50")) >= 0)
                .filter(s -> s.getNetIncome() != null && s.getNetIncome().compareTo(MIN_NET_INCOME) >= 0)
                // ⭐ 데이터 클렌징: 시가총액 500억 이상만
                .filter(s -> s.getMarketCap() != null && s.getMarketCap().compareTo(MIN_MARKET_CAP_FOR_PEG) >= 0)
                .sorted((a, b) -> b.getProfitGrowth().compareTo(a.getProfitGrowth()))
                .map(stock -> ScreenerResultDto.builder()
                        .stockCode(stock.getStockCode())
                        .stockName(stock.getStockName())
                        .market(stock.getMarket())
                        .sector(stock.getSector())
                        .currentPrice(stock.getCurrentPrice())
                        .marketCap(stock.getMarketCap())
                        .per(stock.getPer())
                        .pbr(stock.getPbr())
                        .roe(stock.getRoe())
                        .operatingMargin(stock.getOperatingMargin())
                        .netMargin(stock.getNetMargin())
                        .eps(stock.getEps())
                        .epsGrowth(stock.getEpsGrowth())
                        .peg(stock.getPeg())
                        .turnaroundType("PROFIT_GROWTH")
                        .netIncomeChangeRate(stock.getProfitGrowth())
                        .revenueGrowth(stock.getRevenueGrowth())
                        .profitGrowth(stock.getProfitGrowth())
                        .build())
                .collect(Collectors.toList());

        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }

        log.info("profitGrowth 기반 턴어라운드 결과: {}건 (시가총액 500억 이상)", results.size());
        return results;
    }

    /**
     * 스크리너 요약 정보
     * - 각 스크리너의 상위 종목 요약
     */
    public Map<String, Object> getScreenerSummary() {
        Map<String, Object> summary = new HashMap<>();

        // 마법의 공식 Top 5
        List<ScreenerResultDto> magicFormula = getMagicFormulaStocks(5, null);
        summary.put("magicFormula", magicFormula);
        summary.put("magicFormulaCount", magicFormula.size());

        // PEG 스크리너 Top 5
        List<ScreenerResultDto> lowPeg = getLowPegStocks(new BigDecimal("1.0"), new BigDecimal("10"), 5);
        summary.put("lowPeg", lowPeg);
        summary.put("lowPegCount", lowPeg.size());

        // 턴어라운드 Top 5
        List<ScreenerResultDto> turnaround = getTurnaroundStocks(5);
        summary.put("turnaround", turnaround);
        summary.put("turnaroundCount", turnaround.size());

        return summary;
    }

    // ========== 텔레그램 알림 연동 메서드 ==========

    /**
     * 마법의 공식 상위 종목 알림 발송
     * - 상위 N개 종목을 텔레그램으로 알림
     * - 스케줄러나 외부에서 호출하여 사용
     *
     * @param topN 알림 발송할 종목 수 (기본 3)
     * @return 알림 발송된 종목 수
     */
    public int sendMagicFormulaAlerts(Integer topN) {
        if (topN == null) topN = 3;

        log.info("마법의 공식 상위 종목 알림 발송 시작 - top: {}", topN);

        List<ScreenerResultDto> topStocks = getMagicFormulaStocks(topN, null);

        int sentCount = 0;
        for (ScreenerResultDto stock : topStocks) {
            telegramNotificationService.sendMagicFormulaAlert(
                    stock.getStockName(),
                    stock.getStockCode(),
                    stock.getMagicFormulaRank(),
                    stock.getPer(),
                    stock.getRoe(),
                    stock.getOperatingMargin(),
                    stock.getCurrentPrice()
            );
            sentCount++;

            log.info("마법의 공식 알림 발송 - {} ({}), 순위: #{}",
                    stock.getStockName(), stock.getStockCode(), stock.getMagicFormulaRank());
        }

        log.info("마법의 공식 알림 발송 완료 - {}건", sentCount);
        return sentCount;
    }

    /**
     * 턴어라운드 종목 알림 발송
     * - 적자→흑자 전환 또는 이익 급증 종목 알림
     *
     * @param topN 알림 발송할 종목 수 (기본 3)
     * @return 알림 발송된 종목 수
     */
    public int sendTurnaroundAlerts(Integer topN) {
        if (topN == null) topN = 3;

        log.info("턴어라운드 종목 알림 발송 시작 - top: {}", topN);

        List<ScreenerResultDto> turnaroundStocks = getTurnaroundStocks(topN);

        int sentCount = 0;
        for (ScreenerResultDto stock : turnaroundStocks) {
            telegramNotificationService.sendTurnaroundAlert(
                    stock.getStockName(),
                    stock.getStockCode(),
                    stock.getTurnaroundType(),
                    stock.getNetIncomeChangeRate(),
                    stock.getCurrentPrice()
            );
            sentCount++;

            log.info("턴어라운드 알림 발송 - {} ({}), 유형: {}",
                    stock.getStockName(), stock.getStockCode(), stock.getTurnaroundType());
        }

        log.info("턴어라운드 알림 발송 완료 - {}건", sentCount);
        return sentCount;
    }

    // ========== 데이터 품질 개선 메서드 ==========

    /**
     * 종목 데이터 품질 개선 (배치 처리)
     * - 종목명이 코드와 같거나 비어있으면 KIS API로 조회하여 보완
     * - 시가총액이 0이면 현재가 기반으로 계산하여 보완
     */
    private void enrichStockDataBatch(List<StockFinancialData> stocks) {
        if (stocks == null || stocks.isEmpty()) {
            return;
        }

        int enrichedNameCount = 0;
        int enrichedMarketCapCount = 0;

        // 종목명 또는 시가총액 보완이 필요한 종목 추출
        List<String> stockCodesToEnrich = stocks.stream()
                .filter(s -> isStockNameMissing(s) || isMarketCapMissing(s))
                .map(StockFinancialData::getStockCode)
                .distinct()
                .collect(Collectors.toList());

        if (stockCodesToEnrich.isEmpty()) {
            return;
        }

        log.info("[데이터 품질 개선] 보완이 필요한 종목: {}건", stockCodesToEnrich.size());

        // StockPriceService로 배치 조회 (시세 + 종목명)
        Map<String, StockPriceDto> priceMap = stockPriceService.getStockPrices(stockCodesToEnrich);

        for (StockFinancialData stock : stocks) {
            boolean updated = false;
            StockPriceDto priceDto = priceMap.get(stock.getStockCode());

            // 1. 종목명 보완
            if (isStockNameMissing(stock)) {
                String newName = null;

                // StockPriceService에서 가져온 이름 사용
                if (priceDto != null && priceDto.getStockName() != null && !priceDto.getStockName().isEmpty()) {
                    newName = priceDto.getStockName();
                }

                // 여전히 없으면 KIS API 직접 호출
                if (newName == null || newName.isEmpty() || newName.equals(stock.getStockCode())) {
                    newName = fetchStockNameFromKis(stock.getStockCode());
                }

                // 최후의 폴백: 주요 종목 맵에서 조회
                if (newName == null || newName.isEmpty() || newName.equals(stock.getStockCode())) {
                    newName = StockNameResolver.getName(stock.getStockCode());
                }

                if (newName != null && !newName.isEmpty() && !newName.equals(stock.getStockCode())) {
                    log.debug("[종목명 보완] {} -> {}", stock.getStockCode(), newName);
                    stock.setStockName(newName);
                    updated = true;
                    enrichedNameCount++;
                }
            }

            // 2. 시가총액 보완
            if (isMarketCapMissing(stock)) {
                BigDecimal newMarketCap = null;

                // StockPriceService에서 현재가 기반으로 계산
                if (priceDto != null && priceDto.getCurrentPrice() != null) {
                    // 시가총액 직접 조회 시도
                    newMarketCap = fetchMarketCapFromKis(stock.getStockCode());

                    // 없으면 현재가만이라도 업데이트
                    if (newMarketCap == null && stock.getCurrentPrice() == null) {
                        stock.setCurrentPrice(priceDto.getCurrentPrice());
                        updated = true;
                    }
                }

                if (newMarketCap != null && newMarketCap.compareTo(BigDecimal.ZERO) > 0) {
                    log.debug("[시가총액 보완] {} -> {}억", stock.getStockCode(), newMarketCap);
                    stock.setMarketCap(newMarketCap);
                    updated = true;
                    enrichedMarketCapCount++;
                }
            }

            // DB 업데이트
            if (updated) {
                try {
                    stockFinancialDataRepository.save(stock);
                } catch (Exception e) {
                    log.warn("[데이터 품질 개선] DB 저장 실패 - {}: {}", stock.getStockCode(), e.getMessage());
                }
            }
        }

        if (enrichedNameCount > 0 || enrichedMarketCapCount > 0) {
            log.info("[데이터 품질 개선 완료] 종목명: {}건, 시가총액: {}건 보완됨",
                    enrichedNameCount, enrichedMarketCapCount);
        }
    }

    /**
     * 스크리너 결과 데이터 품질 개선 (ScreenerResultDto용)
     * - 종목명, 현재가, 시가총액, PER, PBR 보완
     * - 원본 StockFinancialData도 함께 업데이트
     */
    private void enrichScreenerResults(List<ScreenerResultDto> results, List<StockFinancialData> originalStocks) {
        if (results == null || results.isEmpty()) {
            return;
        }

        // 보완이 필요한 종목 추출
        List<String> stockCodesToEnrich = results.stream()
                .filter(r -> isResultDataMissing(r))
                .map(ScreenerResultDto::getStockCode)
                .distinct()
                .collect(Collectors.toList());

        if (stockCodesToEnrich.isEmpty()) {
            return;
        }

        log.info("[턴어라운드 데이터 품질 개선] 보완이 필요한 종목: {}건", stockCodesToEnrich.size());

        // StockPriceService로 배치 조회
        Map<String, StockPriceDto> priceMap = stockPriceService.getStockPrices(stockCodesToEnrich);

        // 원본 데이터 맵 생성
        Map<String, StockFinancialData> originalMap = originalStocks.stream()
                .collect(Collectors.toMap(StockFinancialData::getStockCode, s -> s, (a, b) -> a));

        int enrichedCount = 0;

        for (ScreenerResultDto result : results) {
            String stockCode = result.getStockCode();
            StockPriceDto priceDto = priceMap.get(stockCode);
            StockFinancialData original = originalMap.get(stockCode);
            boolean updated = false;

            // 1. 종목명 보완
            if (isStockNameMissingInResult(result)) {
                String newName = null;

                if (priceDto != null && priceDto.getStockName() != null && !priceDto.getStockName().isEmpty()) {
                    newName = priceDto.getStockName();
                }

                if (newName == null || newName.isEmpty() || newName.equals(stockCode)) {
                    newName = fetchStockNameFromKis(stockCode);
                }

                if (newName != null && !newName.isEmpty() && !newName.equals(stockCode)) {
                    result.setStockName(newName);
                    if (original != null) {
                        original.setStockName(newName);
                        updated = true;
                    }
                }
            }

            // 2. 현재가 보완
            if (result.getCurrentPrice() == null && priceDto != null && priceDto.getCurrentPrice() != null) {
                result.setCurrentPrice(priceDto.getCurrentPrice());
                if (original != null) {
                    original.setCurrentPrice(priceDto.getCurrentPrice());
                    updated = true;
                }
            }

            // 3. 시가총액 보완
            if (result.getMarketCap() == null || result.getMarketCap().compareTo(BigDecimal.ZERO) <= 0) {
                BigDecimal newMarketCap = fetchMarketCapFromKis(stockCode);
                if (newMarketCap != null && newMarketCap.compareTo(BigDecimal.ZERO) > 0) {
                    result.setMarketCap(newMarketCap);
                    if (original != null) {
                        original.setMarketCap(newMarketCap);
                        updated = true;
                    }
                }
            }

            // 4. PER/PBR 보완 (KIS API에서 조회)
            if (result.getPer() == null || result.getPbr() == null) {
                try {
                    JsonNode response = koreaInvestmentService.getStockInfo(stockCode);
                    if (response != null && response.has("output")) {
                        JsonNode output = response.get("output");

                        // PER
                        if (result.getPer() == null && output.has("per")) {
                            String perStr = output.get("per").asText();
                            if (perStr != null && !perStr.isEmpty()) {
                                try {
                                    BigDecimal per = new BigDecimal(perStr);
                                    if (per.compareTo(BigDecimal.ZERO) > 0) {
                                        result.setPer(per);
                                        if (original != null) {
                                            original.setPer(per);
                                            updated = true;
                                        }
                                    }
                                } catch (NumberFormatException ignored) {}
                            }
                        }

                        // PBR
                        if (result.getPbr() == null && output.has("pbr")) {
                            String pbrStr = output.get("pbr").asText();
                            if (pbrStr != null && !pbrStr.isEmpty()) {
                                try {
                                    BigDecimal pbr = new BigDecimal(pbrStr);
                                    if (pbr.compareTo(BigDecimal.ZERO) > 0) {
                                        result.setPbr(pbr);
                                        if (original != null) {
                                            original.setPbr(pbr);
                                            updated = true;
                                        }
                                    }
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("PER/PBR 조회 실패 - {}: {}", stockCode, e.getMessage());
                }
            }

            // DB 업데이트
            if (updated && original != null) {
                try {
                    stockFinancialDataRepository.save(original);
                    enrichedCount++;
                } catch (Exception e) {
                    log.warn("[데이터 품질 개선] DB 저장 실패 - {}: {}", stockCode, e.getMessage());
                }
            }
        }

        if (enrichedCount > 0) {
            log.info("[턴어라운드 데이터 품질 개선 완료] {}건 보완됨", enrichedCount);
        }
    }

    /**
     * 스크리너 결과 DTO의 데이터가 누락되었는지 확인
     */
    private boolean isResultDataMissing(ScreenerResultDto result) {
        return isStockNameMissingInResult(result) ||
               result.getCurrentPrice() == null ||
               result.getMarketCap() == null || result.getMarketCap().compareTo(BigDecimal.ZERO) <= 0 ||
               result.getPer() == null ||
               result.getPbr() == null;
    }

    /**
     * 스크리너 결과 DTO의 종목명이 누락되었는지 확인
     */
    private boolean isStockNameMissingInResult(ScreenerResultDto result) {
        String name = result.getStockName();
        String code = result.getStockCode();
        return name == null || name.trim().isEmpty() || name.equals(code);
    }

    /**
     * 종목명이 누락되었는지 확인
     * - null, 빈 문자열, 또는 종목코드와 동일하면 누락으로 판단
     */
    private boolean isStockNameMissing(StockFinancialData stock) {
        String name = stock.getStockName();
        String code = stock.getStockCode();
        return name == null || name.trim().isEmpty() || name.equals(code);
    }

    /**
     * 시가총액이 누락되었는지 확인
     * - null 또는 0이면 누락으로 판단
     */
    private boolean isMarketCapMissing(StockFinancialData stock) {
        BigDecimal marketCap = stock.getMarketCap();
        return marketCap == null || marketCap.compareTo(BigDecimal.ZERO) <= 0;
    }

    /**
     * KIS API에서 종목명 조회
     */
    private String fetchStockNameFromKis(String stockCode) {
        try {
            JsonNode response = koreaInvestmentService.getStockInfo(stockCode);
            if (response != null && response.has("output")) {
                JsonNode output = response.get("output");
                // prdt_abrv_name: 상품약어명, hts_kor_isnm: HTS 한글 종목명
                if (output.has("prdt_abrv_name")) {
                    String name = output.get("prdt_abrv_name").asText();
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
                if (output.has("hts_kor_isnm")) {
                    String name = output.get("hts_kor_isnm").asText();
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("KIS API 종목명 조회 실패 - {}: {}", stockCode, e.getMessage());
        }
        return null;
    }

    /**
     * KIS API에서 시가총액 조회
     * - 억원 단위로 반환
     */
    private BigDecimal fetchMarketCapFromKis(String stockCode) {
        try {
            JsonNode response = koreaInvestmentService.getStockInfo(stockCode);
            if (response != null && response.has("output")) {
                JsonNode output = response.get("output");
                // hts_avls: 시가총액 (원 단위)
                if (output.has("hts_avls")) {
                    String avlsStr = output.get("hts_avls").asText();
                    if (avlsStr != null && !avlsStr.isEmpty()) {
                        BigDecimal avls = new BigDecimal(avlsStr);
                        // 원 -> 억원 변환
                        return avls.divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("KIS API 시가총액 조회 실패 - {}: {}", stockCode, e.getMessage());
        }
        return null;
    }

    // ========== 모멘텀 스크리너 (수급 주도형 단타용) ==========

    /**
     * 모멘텀 스크리너 - 수급 주도형 단타 전략용
     *
     * [선정 조건]
     * 1. 거래량 급증: 전일 대비 150% 이상
     * 2. 시가총액: 500억원 이상 (유동성 확보)
     * 3. 등락률: >= -2% (과도한 음봉 제외)
     *
     * [정렬 기준]
     * - 거래량 비율 높은 순 (거래량이 터지면서 주가가 움직이는 종목 우선)
     *
     * @param limit 조회할 종목 수
     * @return 모멘텀 종목 리스트
     */
    public List<ScreenerResultDto> getMomentumStocks(int limit) {
        log.info("[모멘텀 스크리너] 시작 - limit: {}", limit);

        List<ScreenerResultDto> results = new ArrayList<>();

        try {
            // KIS API를 통해 거래량 급증 종목 조회
            JsonNode response = koreaInvestmentService.getVolumeRankStocks();

            if (response == null) {
                log.warn("[모멘텀 스크리너] API 응답 없음");
                return results;
            }

            String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText() : "";
            if (!"0".equals(rtCd)) {
                log.warn("[모멘텀 스크리너] API 오류: {} - {}",
                        rtCd, response.has("msg1") ? response.get("msg1").asText() : "");
                return results;
            }

            JsonNode output = response.get("output");
            if (output == null || !output.isArray()) {
                log.warn("[모멘텀 스크리너] 데이터 없음");
                return results;
            }

            log.info("[모멘텀 스크리너] 거래량 상위 종목 {}건 조회됨", output.size());

            for (JsonNode item : output) {
                try {
                    String stockCode = getJsonText(item, "mksc_shrn_iscd");
                    String stockName = getJsonText(item, "hts_kor_isnm");

                    if (stockCode == null || stockName == null) continue;

                    // 현재가
                    BigDecimal currentPrice = getJsonBigDecimal(item, "stck_prpr");
                    if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) continue;

                    // 등락률 (완화: >= -2% 허용, 장 초반 눌림 감안)
                    BigDecimal changeRate = getJsonBigDecimal(item, "prdy_ctrt");
                    if (changeRate == null || changeRate.compareTo(new BigDecimal("-2")) < 0) {
                        log.debug("[모멘텀 스크리너] {} - 과도한 음봉 스킵 (등락률: {}%)", stockName, changeRate);
                        continue;
                    }

                    // 거래량 비율 (전일 대비 %)
                    BigDecimal volumeRatio = getJsonBigDecimal(item, "vol_inrt");
                    if (volumeRatio == null || volumeRatio.compareTo(MIN_VOLUME_RATIO) < 0) {
                        log.debug("[모멘텀 스크리너] {} - 거래량 비율 부족 ({}%)", stockName, volumeRatio);
                        continue;
                    }

                    // 거래량
                    BigDecimal volume = getJsonBigDecimal(item, "acml_vol");

                    // 시가총액 조회 (억원 단위) - 조회 실패 시 통과 (나중에 수급으로 필터링)
                    BigDecimal marketCap = null;
                    try {
                        marketCap = fetchMarketCapFromKis(stockCode);
                    } catch (Exception e) {
                        log.debug("[모멘텀 스크리너] {} - 시가총액 조회 실패, 일단 통과", stockName);
                    }
                    if (marketCap != null && marketCap.compareTo(MIN_MARKET_CAP_FOR_MOMENTUM) < 0) {
                        log.debug("[모멘텀 스크리너] {} - 시가총액 부족 ({}억)", stockName, marketCap);
                        continue;
                    }

                    ScreenerResultDto dto = ScreenerResultDto.builder()
                            .stockCode(stockCode)
                            .stockName(stockName)
                            .currentPrice(currentPrice)
                            .marketCap(marketCap)
                            .changeRate(changeRate)
                            .volumeRatio(volumeRatio)
                            .volume(volume)
                            .build();

                    results.add(dto);

                    log.debug("[모멘텀 스크리너] 후보 추가: {} - 등락률 {}%, 거래량비율 {}%, 시총 {}억",
                            stockName, changeRate, volumeRatio, marketCap);

                    // API 호출 제한 방지
                    Thread.sleep(100);

                } catch (Exception e) {
                    log.debug("[모멘텀 스크리너] 종목 처리 실패: {}", e.getMessage());
                }
            }

            // 거래량 비율 높은 순으로 정렬
            results.sort((a, b) -> {
                BigDecimal volA = a.getVolumeRatio() != null ? a.getVolumeRatio() : BigDecimal.ZERO;
                BigDecimal volB = b.getVolumeRatio() != null ? b.getVolumeRatio() : BigDecimal.ZERO;
                return volB.compareTo(volA);
            });

            // limit 적용
            if (limit > 0 && results.size() > limit) {
                results = results.subList(0, limit);
            }

            log.info("[모멘텀 스크리너] 완료 - 최종 {}건 (거래량 {}% 이상, 시총 {}억 이상, 양봉만)",
                    results.size(), MIN_VOLUME_RATIO, MIN_MARKET_CAP_FOR_MOMENTUM);

        } catch (Exception e) {
            log.error("[모멘텀 스크리너] 오류: {}", e.getMessage(), e);
        }

        return results;
    }

    /**
     * JSON 노드에서 텍스트 값 추출
     */
    private String getJsonText(JsonNode node, String fieldName) {
        if (node.has(fieldName)) {
            String value = node.get(fieldName).asText();
            return (value != null && !value.isEmpty()) ? value : null;
        }
        return null;
    }

    /**
     * JSON 노드에서 BigDecimal 값 추출
     */
    private BigDecimal getJsonBigDecimal(JsonNode node, String fieldName) {
        if (node.has(fieldName)) {
            String value = node.get(fieldName).asText();
            if (value != null && !value.isEmpty()) {
                try {
                    return new BigDecimal(value.replace(",", ""));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

}
