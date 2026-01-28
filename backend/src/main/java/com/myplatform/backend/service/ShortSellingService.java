package com.myplatform.backend.service;

import com.myplatform.backend.dto.ShortSqueezeDto;
import com.myplatform.backend.dto.TechnicalIndicatorsDto;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.entity.StockShortData;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockShortDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 공매도 분석 서비스
 * - 숏스퀴즈 후보 종목 발굴
 * - 숏커버링 감지
 * - 대차잔고 분석
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShortSellingService {

    private final StockShortDataRepository shortDataRepository;
    private final InvestorDailyTradeRepository investorTradeRepository;
    private final TechnicalIndicatorService technicalIndicatorService;

    // 분석 기준 상수
    private static final int ANALYSIS_DAYS = 20;           // 평균 계산 기간 (거래일 기준)
    private static final int SHORT_COVERING_DAYS = 5;      // 숏커버링 판단 기간
    private static final int FOREIGN_BUY_DAYS = 3;         // 외국인 수급 판단 기간
    private static final BigDecimal MIN_LOAN_RATIO = new BigDecimal("1.0");  // 최소 대차잔고 비율 (%)
    private static final BigDecimal PRICE_RISE_THRESHOLD = new BigDecimal("3.0");  // 주가 상승 기준 (%)

    // 외국인 순매수 금액 기준 (억원 단위 - DB 저장 단위)
    // - InvestorDailyTrade.netBuyAmount는 억원 단위로 저장됨
    // - 예: 10.5 = 10억 5천만 원
    private static final BigDecimal FOREIGN_NET_BUY_5B = new BigDecimal("5");   // 5억 원
    private static final BigDecimal FOREIGN_NET_BUY_10B = new BigDecimal("10"); // 10억 원

    // 조회 기간 여유 배수 (주말/공휴일 고려하여 넉넉하게)
    private static final int QUERY_DATE_MULTIPLIER = 2;  // ANALYSIS_DAYS * 2 = 약 40일

    /**
     * 숏스퀴즈 후보 종목 조회
     *
     * 알고리즘:
     * 1. 대차잔고 과열: 20일 평균 대비 대차잔고가 높았던 종목
     * 2. 숏커버링 발생: 최근 5일간 대차잔고가 지속 감소 중
     * 3. 수급 유입: 최근 3일 외국인 순매수
     * 4. 주가 상승: 20일선 위 또는 최근 5일 3% 이상 상승
     */
    public List<ShortSqueezeDto> getShortSqueezeCandidates(Integer limit) {
        log.info("숏스퀴즈 후보 종목 분석 시작 - limit: {}", limit);

        LocalDate today = LocalDate.now();
        // 주말/공휴일 고려하여 넉넉하게 조회 (20일 * 2 = 40일)
        // 예: 20 거래일 확보를 위해 약 40일 조회 (주말/공휴일 제외)
        LocalDate startDate = today.minusDays(ANALYSIS_DAYS * QUERY_DATE_MULTIPLIER);

        // 1. [성능 최적화] 최근 데이터 Bulk 조회 (N+1 방지)
        List<StockShortData> allRecentData = shortDataRepository.findAllWithLoanBalance(startDate);

        if (allRecentData.isEmpty()) {
            log.info("공매도 데이터가 없습니다.");
            return Collections.emptyList();
        }

        // 종목별로 그룹화 (날짜 내림차순 정렬 유지)
        Map<String, List<StockShortData>> stockDataMap = allRecentData.stream()
                .collect(Collectors.groupingBy(
                        StockShortData::getStockCode,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        log.debug("분석 대상 종목 수: {}", stockDataMap.size());

        // 2. 외국인 순매수 데이터 조회
        Map<String, BigDecimal> foreignNetBuyMap = getForeignNetBuyMap(today);

        // 3. 각 종목별 분석
        List<ShortSqueezeDto> candidates = new ArrayList<>();

        for (Map.Entry<String, List<StockShortData>> entry : stockDataMap.entrySet()) {
            String stockCode = entry.getKey();
            List<StockShortData> dataList = entry.getValue();

            ShortSqueezeDto candidate = analyzeStock(stockCode, dataList, foreignNetBuyMap);
            if (candidate != null && candidate.getSqueezeScore() >= 40) {  // 최소 점수 기준
                candidates.add(candidate);
            }
        }

        // 4. 스퀴즈 점수 높은 순으로 정렬
        candidates.sort((a, b) -> b.getSqueezeScore().compareTo(a.getSqueezeScore()));

        // 5. limit 적용
        if (limit != null && limit > 0) {
            candidates = candidates.stream().limit(limit).collect(Collectors.toList());
        }

        log.info("숏스퀴즈 후보 종목 분석 완료 - 결과 {}건", candidates.size());
        return candidates;
    }

    /**
     * 개별 종목 숏스퀴즈 분석
     */
    private ShortSqueezeDto analyzeStock(String stockCode,
                                          List<StockShortData> dataList,
                                          Map<String, BigDecimal> foreignNetBuyMap) {
        if (dataList.size() < SHORT_COVERING_DAYS) {
            return null;  // 분석에 필요한 데이터 부족
        }

        // 가장 최근 데이터
        StockShortData latest = dataList.get(0);
        String stockName = latest.getStockName();

        // ========== 1. 20일 평균 대차잔고 계산 ==========
        BigDecimal avgLoanBalance20Days = calculateAverageLoanBalance(dataList, ANALYSIS_DAYS);
        BigDecimal currentLoanBalance = latest.getLoanBalanceQuantity();

        if (currentLoanBalance == null || avgLoanBalance20Days == null ||
            avgLoanBalance20Days.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        // ========== 2. 최근 5일 대차잔고 변화율 계산 (숏커버링 판단) ==========
        BigDecimal loanBalanceChange5Days = calculateLoanBalanceChange(dataList, SHORT_COVERING_DAYS);
        boolean isShortCovering = loanBalanceChange5Days != null &&
                                   loanBalanceChange5Days.compareTo(BigDecimal.ZERO) < 0;

        // ========== 3. 외국인 순매수 확인 ==========
        BigDecimal foreignNetBuy = foreignNetBuyMap.getOrDefault(stockCode, BigDecimal.ZERO);
        boolean isForeignBuying = foreignNetBuy.compareTo(BigDecimal.ZERO) > 0;

        // ========== 4. 주가 분석 (20일선, 5일 상승률) ==========
        BigDecimal ma20 = calculateMA(dataList, ANALYSIS_DAYS);
        BigDecimal priceChange5Days = calculatePriceChange(dataList, SHORT_COVERING_DAYS);
        boolean isAboveMa20 = latest.getClosePrice() != null && ma20 != null &&
                               latest.getClosePrice().compareTo(ma20) > 0;
        boolean isPriceRising = priceChange5Days != null &&
                                 priceChange5Days.compareTo(PRICE_RISE_THRESHOLD) >= 0;
        boolean isTrendReversal = isAboveMa20 || isPriceRising;

        // ========== 5. 스퀴즈 점수 계산 ==========
        int squeezeScore = calculateSqueezeScore(
                currentLoanBalance, avgLoanBalance20Days,
                loanBalanceChange5Days, isShortCovering,
                isForeignBuying, foreignNetBuy,
                isTrendReversal, isPriceRising
        );

        // ========== DTO 생성 ==========
        ShortSqueezeDto dto = ShortSqueezeDto.builder()
                .stockCode(stockCode)
                .stockName(stockName)
                .currentPrice(latest.getClosePrice())
                .changeRate(latest.getChangeRate())
                .priceChange5Days(priceChange5Days)
                .currentLoanBalance(currentLoanBalance)
                .avgLoanBalance20Days(avgLoanBalance20Days)
                .loanBalanceChange5Days(loanBalanceChange5Days)
                .loanBalanceRatio(latest.getLoanBalanceRatio())
                .shortRatio(latest.getShortRatio())
                .shortBalanceRatio(latest.getShortBalanceRatio())
                .foreignNetBuy3Days(foreignNetBuy)
                .isForeignBuying(isForeignBuying)
                .squeezeScore(squeezeScore)
                .analysisDate(latest.getTradeDate())
                .build();

        // ========== 6. 기술적 지표 분석 ==========
        // 종가 리스트 추출 (최신 → 과거 순서 유지 - TechnicalIndicatorService 요구사항)
        List<BigDecimal> prices = dataList.stream()
                .map(StockShortData::getClosePrice)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 기술적 지표 계산 및 적용
        if (prices.size() >= 5) {  // 최소 5일 데이터 필요
            TechnicalIndicatorsDto technicalIndicators = technicalIndicatorService.calculate(prices);
            dto.applyTechnicalIndicators(technicalIndicators);

            // 기술적 신호에 따라 추세 전환 판단 업데이트
            if (Boolean.TRUE.equals(technicalIndicators.getIsGoldenCross()) ||
                Boolean.TRUE.equals(technicalIndicators.getIsAboveMa20())) {
                dto.setIsTrendReversal(true);
            }
        } else {
            // 데이터 부족 시 기본값 설정
            dto.setMa20(ma20);
            dto.setIsAboveMa20(isAboveMa20);
            dto.setIsTrendReversal(isTrendReversal);
        }

        dto.calculateSqueezeLevel();

        return dto;
    }

    /**
     * 스퀴즈 점수 계산 (0~100)
     */
    private int calculateSqueezeScore(BigDecimal currentLoan, BigDecimal avgLoan,
                                       BigDecimal loanChange, boolean isShortCovering,
                                       boolean isForeignBuying, BigDecimal foreignNetBuy,
                                       boolean isTrendReversal, boolean isPriceRising) {
        int score = 0;

        // 1. 대차잔고 과열도 (30점)
        // 현재 대차잔고가 20일 평균보다 높으면 과열 상태 (공매도 세력이 많이 쌓여있음)
        if (currentLoan.compareTo(avgLoan) > 0) {
            BigDecimal overheatRatio = currentLoan.subtract(avgLoan)
                    .divide(avgLoan, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            // 20% 이상 과열 시 최대 점수
            score += Math.min(30, overheatRatio.intValue() * 3 / 2);
        }

        // 2. 숏커버링 진행도 (30점)
        if (isShortCovering && loanChange != null) {
            // 대차잔고가 많이 줄었을수록 높은 점수
            int coveringScore = Math.abs(loanChange.intValue());
            score += Math.min(30, coveringScore * 3);  // 10% 감소 = 30점
        }

        // 3. 외국인 순매수 (20점)
        // foreignNetBuy는 억원 단위로 저장됨 (DB: InvestorDailyTrade.netBuyAmount)
        // 예: foreignNetBuy = 10.5 → 10억 5천만 원
        if (isForeignBuying) {
            score += 10;
            // 순매수 규모에 따라 추가 점수 (억원 단위 비교)
            if (foreignNetBuy.compareTo(FOREIGN_NET_BUY_10B) >= 0) {
                score += 10;  // 10억 원 이상이면 추가 10점 (총 20점)
            } else if (foreignNetBuy.compareTo(FOREIGN_NET_BUY_5B) >= 0) {
                score += 5;   // 5억 원 이상이면 추가 5점 (총 15점)
            }
        }

        // 4. 추세 전환 (20점)
        if (isTrendReversal) {
            score += 10;
            if (isPriceRising) {
                score += 10;  // 실제 주가 상승 시 추가
            }
        }

        return Math.min(100, score);
    }

    /**
     * 평균 대차잔고 계산
     */
    private BigDecimal calculateAverageLoanBalance(List<StockShortData> dataList, int days) {
        return dataList.stream()
                .limit(days)
                .map(StockShortData::getLoanBalanceQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.min(days, dataList.size())), 2, RoundingMode.HALF_UP);
    }

    /**
     * 대차잔고 변화율 계산 (%)
     */
    private BigDecimal calculateLoanBalanceChange(List<StockShortData> dataList, int days) {
        if (dataList.size() < days) return null;

        BigDecimal current = dataList.get(0).getLoanBalanceQuantity();
        BigDecimal previous = dataList.get(days - 1).getLoanBalanceQuantity();

        if (current == null || previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        return current.subtract(previous)
                .divide(previous, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    /**
     * 주가 변화율 계산 (%)
     */
    private BigDecimal calculatePriceChange(List<StockShortData> dataList, int days) {
        if (dataList.size() < days) return null;

        BigDecimal current = dataList.get(0).getClosePrice();
        BigDecimal previous = dataList.get(days - 1).getClosePrice();

        if (current == null || previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        return current.subtract(previous)
                .divide(previous, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    /**
     * 이동평균 계산
     */
    private BigDecimal calculateMA(List<StockShortData> dataList, int days) {
        return dataList.stream()
                .limit(days)
                .map(StockShortData::getClosePrice)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.min(days, dataList.size())), 2, RoundingMode.HALF_UP);
    }

    /**
     * 최근 3일 외국인 순매수 데이터 조회
     */
    private Map<String, BigDecimal> getForeignNetBuyMap(LocalDate endDate) {
        Map<String, BigDecimal> result = new HashMap<>();

        try {
            LocalDate startDate = endDate.minusDays(FOREIGN_BUY_DAYS);
            List<InvestorDailyTrade> foreignTrades = investorTradeRepository
                    .findByInvestorTypeAndDateRange("FOREIGN", startDate, endDate);

            // 종목별 순매수 합계
            for (InvestorDailyTrade trade : foreignTrades) {
                if (trade.getNetBuyAmount() != null) {
                    result.merge(trade.getStockCode(), trade.getNetBuyAmount(), BigDecimal::add);
                }
            }
        } catch (Exception e) {
            log.warn("외국인 순매수 데이터 조회 실패: {}", e.getMessage());
        }

        return result;
    }

    // ========== 추가 분석 메서드 ==========

    /**
     * 대차잔고 상위 종목 조회
     */
    public List<ShortSqueezeDto> getTopLoanBalanceStocks(Integer limit) {
        log.info("대차잔고 상위 종목 조회 - limit: {}", limit);

        List<StockShortData> topStocks = shortDataRepository.findTopLoanBalanceStocks(
                PageRequest.of(0, limit != null ? limit : 50));

        return topStocks.stream()
                .map(this::toSimpleDto)
                .collect(Collectors.toList());
    }

    /**
     * 공매도 비중 상위 종목 조회
     */
    public List<ShortSqueezeDto> getTopShortRatioStocks(Integer limit) {
        log.info("공매도 비중 상위 종목 조회 - limit: {}", limit);

        List<StockShortData> topStocks = shortDataRepository.findTopShortRatioStocks(
                PageRequest.of(0, limit != null ? limit : 50));

        return topStocks.stream()
                .map(this::toSimpleDto)
                .collect(Collectors.toList());
    }

    /**
     * 특정 종목의 공매도 추이 조회
     */
    public List<StockShortData> getStockShortHistory(String stockCode, Integer days) {
        if (days == null || days <= 0) days = 30;

        return shortDataRepository.findByStockCodeOrderByTradeDateDesc(
                stockCode, PageRequest.of(0, days));
    }

    /**
     * 특정 종목의 상세 분석 조회 (기술적 지표 포함)
     */
    public ShortSqueezeDto getStockDetailedAnalysis(String stockCode) {
        log.info("종목 상세 분석 조회 - stockCode: {}", stockCode);

        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(ANALYSIS_DAYS * QUERY_DATE_MULTIPLIER);

        // 해당 종목의 최근 데이터 조회
        List<StockShortData> dataList = shortDataRepository.findByStockCodeAndDateRange(
                stockCode, startDate, today);

        if (dataList.isEmpty()) {
            log.warn("종목 데이터 없음 - stockCode: {}", stockCode);
            return null;
        }

        // 외국인 순매수 데이터 조회
        Map<String, BigDecimal> foreignNetBuyMap = getForeignNetBuyMap(today);

        // 상세 분석 수행
        return analyzeStock(stockCode, dataList, foreignNetBuyMap);
    }

    /**
     * 간단 DTO 변환 (단일 데이터 기준)
     */
    private ShortSqueezeDto toSimpleDto(StockShortData data) {
        return ShortSqueezeDto.builder()
                .stockCode(data.getStockCode())
                .stockName(data.getStockName())
                .currentPrice(data.getClosePrice())
                .changeRate(data.getChangeRate())
                .currentLoanBalance(data.getLoanBalanceQuantity())
                .loanBalanceRatio(data.getLoanBalanceRatio())
                .shortRatio(data.getShortRatio())
                .shortBalanceRatio(data.getShortBalanceRatio())
                .analysisDate(data.getTradeDate())
                .build();
    }

    /**
     * 상세 DTO 변환 (기술적 지표 포함)
     */
    private ShortSqueezeDto toDetailedDto(List<StockShortData> dataList) {
        if (dataList.isEmpty()) return null;

        StockShortData latest = dataList.get(0);
        ShortSqueezeDto dto = toSimpleDto(latest);

        // 기술적 지표 계산 (최신 → 과거 순서 유지)
        List<BigDecimal> prices = dataList.stream()
                .map(StockShortData::getClosePrice)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (prices.size() >= 5) {
            TechnicalIndicatorsDto technicalIndicators = technicalIndicatorService.calculate(prices);
            dto.applyTechnicalIndicators(technicalIndicators);
        }

        return dto;
    }

    /**
     * 가장 최근 거래일 조회
     */
    public LocalDate getLatestTradeDate() {
        return shortDataRepository.findLatestTradeDate();
    }
}
