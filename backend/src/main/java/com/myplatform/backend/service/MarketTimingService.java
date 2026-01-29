package com.myplatform.backend.service;

import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.dto.MarketTimingDto.*;
import com.myplatform.backend.entity.MarketDailyStatus;
import com.myplatform.backend.repository.MarketDailyStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 시장 타이밍 분석 서비스
 * - ADR (등락비율) 계산
 * - 시장 상태 판단
 * - 데이터 수집 (네이버 금융)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketTimingService {

    private final MarketDailyStatusRepository marketDailyStatusRepository;
    private final TelegramNotificationService telegramNotificationService;

    // ADR 기준값
    private static final BigDecimal ADR_OVERHEATED = new BigDecimal("120");
    private static final BigDecimal ADR_NORMAL_HIGH = new BigDecimal("120");
    private static final BigDecimal ADR_NORMAL_LOW = new BigDecimal("80");
    private static final BigDecimal ADR_OVERSOLD = new BigDecimal("80");
    private static final BigDecimal ADR_EXTREME_FEAR = new BigDecimal("60");

    private static final int ADR_PERIOD = 20;  // ADR 계산 기간

    /**
     * 현재 시장 타이밍 분석
     */
    public MarketTimingDto getCurrentMarketTiming() {
        log.info("시장 타이밍 분석 시작");

        // 최신 데이터 조회
        List<MarketDailyStatus> latestData = marketDailyStatusRepository.findLatestAll();

        if (latestData.isEmpty()) {
            log.warn("시장 데이터가 없습니다. 먼저 데이터를 수집해주세요.");
            return createEmptyTiming();
        }

        LocalDate analysisDate = latestData.get(0).getTradeDate();

        // 코스피/코스닥 데이터 분리
        MarketStatusDto kospiStatus = null;
        MarketStatusDto kosdaqStatus = null;

        for (MarketDailyStatus data : latestData) {
            MarketStatusDto status = convertToStatusDto(data);
            if ("KOSPI".equals(data.getMarketType())) {
                kospiStatus = status;
            } else if ("KOSDAQ".equals(data.getMarketType())) {
                kosdaqStatus = status;
            }
        }

        // ADR이 없으면 계산
        if (kospiStatus != null && kospiStatus.getAdr20() == null) {
            BigDecimal adr = calculateAdr("KOSPI", analysisDate);
            kospiStatus.setAdr20(adr);
            kospiStatus.setCondition(determineCondition(adr));
        }
        if (kosdaqStatus != null && kosdaqStatus.getAdr20() == null) {
            BigDecimal adr = calculateAdr("KOSDAQ", analysisDate);
            kosdaqStatus.setAdr20(adr);
            kosdaqStatus.setCondition(determineCondition(adr));
        }

        // 종합 ADR 계산
        BigDecimal combinedAdr = calculateCombinedAdr(kospiStatus, kosdaqStatus);
        MarketCondition overallCondition = determineCondition(combinedAdr);

        // 진단 및 전략 생성
        String diagnosis = generateDiagnosis(kospiStatus, kosdaqStatus, combinedAdr);
        String strategy = overallCondition != null ? overallCondition.getSuggestion() : "";

        return MarketTimingDto.builder()
                .analysisDate(analysisDate)
                .kospi(kospiStatus)
                .kosdaq(kosdaqStatus)
                .combinedAdr(combinedAdr)
                .overallCondition(overallCondition)
                .diagnosis(diagnosis)
                .strategy(strategy)
                .build();
    }

    /**
     * ADR 히스토리 조회 (차트용)
     */
    public List<AdrHistoryDto> getAdrHistory(int days) {
        List<AdrHistoryDto> history = new ArrayList<>();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days + ADR_PERIOD);

        List<MarketDailyStatus> kospiData = marketDailyStatusRepository
                .findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc("KOSPI", startDate, endDate);
        List<MarketDailyStatus> kosdaqData = marketDailyStatusRepository
                .findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc("KOSDAQ", startDate, endDate);

        // 날짜별로 매칭
        for (int i = 0; i < Math.min(days, kospiData.size()); i++) {
            MarketDailyStatus kospi = kospiData.get(i);
            LocalDate date = kospi.getTradeDate();

            BigDecimal kospiAdr = kospi.getAdr20() != null ? kospi.getAdr20() : calculateAdr("KOSPI", date);
            BigDecimal kosdaqAdr = null;

            // 코스닥 데이터 찾기
            for (MarketDailyStatus k : kosdaqData) {
                if (k.getTradeDate().equals(date)) {
                    kosdaqAdr = k.getAdr20() != null ? k.getAdr20() : calculateAdr("KOSDAQ", date);
                    break;
                }
            }

            BigDecimal combined = calculateCombinedAdrFromValues(kospiAdr, kosdaqAdr);

            history.add(AdrHistoryDto.builder()
                    .date(date)
                    .kospiAdr(kospiAdr)
                    .kosdaqAdr(kosdaqAdr)
                    .combinedAdr(combined)
                    .build());
        }

        return history;
    }

    /**
     * 매일 장 마감 후 ADR 시장 지표 자동 수집 (평일 16:30)
     * - 15:30 장 마감 후 1시간 여유를 두고 수집
     */
    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void scheduledMarketDataCollection() {
        log.info("=== ADR 시장 지표 자동 수집 시작 (16:30) ===");
        try {
            collectMarketData();
            log.info("=== ADR 시장 지표 자동 수집 완료 ===");
        } catch (Exception e) {
            log.error("ADR 시장 지표 자동 수집 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 시장 데이터 수집 (네이버 금융)
     */
    @Transactional
    public void collectMarketData() {
        log.info("시장 데이터 수집 시작");

        try {
            // 코스피 데이터 수집
            collectMarketDataFromNaver("KOSPI");

            // 코스닥 데이터 수집
            collectMarketDataFromNaver("KOSDAQ");

            log.info("시장 데이터 수집 완료");
        } catch (Exception e) {
            log.error("시장 데이터 수집 실패: {}", e.getMessage(), e);
            throw new RuntimeException("시장 데이터 수집 실패: " + e.getMessage());
        }
    }

    /**
     * 네이버 금융에서 시장 데이터 수집
     */
    private void collectMarketDataFromNaver(String marketType) {
        try {
            String url;
            if ("KOSPI".equals(marketType)) {
                url = "https://finance.naver.com/sise/sise_rise.naver";
            } else {
                url = "https://finance.naver.com/sise/sise_rise.naver?sosok=1";
            }

            // 상승/하락/보합 종목 수 수집
            int advancingCount = crawlStockCount(marketType, "rise");
            int decliningCount = crawlStockCount(marketType, "fall");
            int unchangedCount = crawlStockCount(marketType, "steady");
            int upperLimitCount = crawlStockCount(marketType, "upper");
            int lowerLimitCount = crawlStockCount(marketType, "lower");

            // 지수 정보 수집
            BigDecimal[] indexInfo = crawlIndexInfo(marketType);
            BigDecimal indexClose = indexInfo[0];
            BigDecimal indexChangeRate = indexInfo[1];
            BigDecimal tradingValue = indexInfo[2];

            LocalDate today = LocalDate.now();

            // 기존 데이터 확인
            Optional<MarketDailyStatus> existing = marketDailyStatusRepository
                    .findByMarketTypeAndTradeDate(marketType, today);

            MarketDailyStatus status;
            if (existing.isPresent()) {
                status = existing.get();
            } else {
                status = new MarketDailyStatus();
                status.setMarketType(marketType);
                status.setTradeDate(today);
            }

            status.setAdvancingCount(advancingCount);
            status.setDecliningCount(decliningCount);
            status.setUnchangedCount(unchangedCount);
            status.setUpperLimitCount(upperLimitCount);
            status.setLowerLimitCount(lowerLimitCount);
            status.setTotalCount(advancingCount + decliningCount + unchangedCount);
            status.setIndexClose(indexClose);
            status.setIndexChangeRate(indexChangeRate);
            status.setTradingValue(tradingValue);

            // 당일 등락비 계산
            if (decliningCount > 0) {
                BigDecimal dailyRatio = BigDecimal.valueOf(advancingCount)
                        .divide(BigDecimal.valueOf(decliningCount), 2, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                status.setDailyRatio(dailyRatio);
            }

            // ADR 계산
            BigDecimal adr = calculateAdr(marketType, today);
            status.setAdr20(adr);

            marketDailyStatusRepository.save(status);
            log.info("{} 시장 데이터 저장 완료: 상승={}, 하락={}, 보합={}, ADR={}",
                    marketType, advancingCount, decliningCount, unchangedCount, adr);

        } catch (Exception e) {
            log.error("{} 시장 데이터 수집 실패: {}", marketType, e.getMessage());
            throw new RuntimeException(marketType + " 데이터 수집 실패: " + e.getMessage());
        }
    }

    /**
     * 종목 수 크롤링
     */
    private int crawlStockCount(String marketType, String type) {
        try {
            String sosok = "KOSPI".equals(marketType) ? "0" : "1";
            String url;

            switch (type) {
                case "rise":
                    url = "https://finance.naver.com/sise/sise_rise.naver?sosok=" + sosok;
                    break;
                case "fall":
                    url = "https://finance.naver.com/sise/sise_fall.naver?sosok=" + sosok;
                    break;
                case "steady":
                    url = "https://finance.naver.com/sise/sise_steady.naver?sosok=" + sosok;
                    break;
                case "upper":
                    url = "https://finance.naver.com/sise/sise_upper.naver?sosok=" + sosok;
                    break;
                case "lower":
                    url = "https://finance.naver.com/sise/sise_lower.naver?sosok=" + sosok;
                    break;
                default:
                    return 0;
            }

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(10000)
                    .get();

            // 종목 수 추출 (예: "상승 (528)")
            Element countElement = doc.selectFirst("div.subtop_sise_graph2 span");
            if (countElement != null) {
                String text = countElement.text();
                // 괄호 안의 숫자 추출
                if (text.contains("(") && text.contains(")")) {
                    String numStr = text.substring(text.indexOf("(") + 1, text.indexOf(")"));
                    return Integer.parseInt(numStr.replace(",", ""));
                }
            }

            // 테이블에서 행 수 세기 (fallback)
            Elements rows = doc.select("table.type_2 tbody tr");
            int count = 0;
            for (Element row : rows) {
                if (row.select("td").size() > 1 && !row.select("td a").isEmpty()) {
                    count++;
                }
            }
            return count;

        } catch (Exception e) {
            log.warn("{} {} 종목 수 크롤링 실패: {}", marketType, type, e.getMessage());
            return 0;
        }
    }

    /**
     * 지수 정보 크롤링
     */
    private BigDecimal[] crawlIndexInfo(String marketType) {
        BigDecimal[] result = new BigDecimal[3];  // [종가, 등락률, 거래대금]

        try {
            String url = "KOSPI".equals(marketType)
                    ? "https://finance.naver.com/sise/sise_index.naver?code=KOSPI"
                    : "https://finance.naver.com/sise/sise_index.naver?code=KOSDAQ";

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(10000)
                    .get();

            // 지수 종가
            Element indexElement = doc.selectFirst("#now_value");
            if (indexElement != null) {
                result[0] = new BigDecimal(indexElement.text().replace(",", ""));
            }

            // 등락률
            Element changeElement = doc.selectFirst("#change_rate");
            if (changeElement != null) {
                String rateText = changeElement.text().replace("%", "").replace("+", "");
                result[1] = new BigDecimal(rateText);
            }

            // 거래대금
            Element tradingElement = doc.selectFirst("em#quant");
            if (tradingElement != null) {
                String tradingText = tradingElement.text().replace(",", "").replace("억", "");
                result[2] = new BigDecimal(tradingText);
            }

        } catch (Exception e) {
            log.warn("{} 지수 정보 크롤링 실패: {}", marketType, e.getMessage());
        }

        return result;
    }

    /**
     * ADR 계산 (20일 기준)
     * ADR = (20일 상승 종목 수 합계 / 20일 하락 종목 수 합계) * 100
     */
    public BigDecimal calculateAdr(String marketType, LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(ADR_PERIOD + 10);  // 여유있게 조회

        List<MarketDailyStatus> recentData = marketDailyStatusRepository
                .findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc(marketType, startDate, endDate);

        if (recentData.size() < ADR_PERIOD) {
            log.debug("{} ADR 계산 불가: 데이터 부족 ({}/{}일)", marketType, recentData.size(), ADR_PERIOD);
            return null;
        }

        // 최근 20일 데이터만 사용
        List<MarketDailyStatus> targetData = recentData.subList(0, Math.min(ADR_PERIOD, recentData.size()));

        long totalAdvancing = 0;
        long totalDeclining = 0;

        for (MarketDailyStatus data : targetData) {
            totalAdvancing += data.getAdvancingCount() != null ? data.getAdvancingCount() : 0;
            totalDeclining += data.getDecliningCount() != null ? data.getDecliningCount() : 0;
        }

        if (totalDeclining == 0) {
            return new BigDecimal("999.99");  // 하락 종목 없음
        }

        BigDecimal adr = BigDecimal.valueOf(totalAdvancing)
                .divide(BigDecimal.valueOf(totalDeclining), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);

        log.debug("{} ADR 계산 완료: {} (상승합계={}, 하락합계={})",
                marketType, adr, totalAdvancing, totalDeclining);

        return adr;
    }

    /**
     * 종합 ADR 계산
     */
    private BigDecimal calculateCombinedAdr(MarketStatusDto kospi, MarketStatusDto kosdaq) {
        if (kospi == null || kosdaq == null) {
            if (kospi != null) return kospi.getAdr20();
            if (kosdaq != null) return kosdaq.getAdr20();
            return null;
        }

        return calculateCombinedAdrFromValues(kospi.getAdr20(), kosdaq.getAdr20());
    }

    private BigDecimal calculateCombinedAdrFromValues(BigDecimal kospiAdr, BigDecimal kosdaqAdr) {
        if (kospiAdr == null && kosdaqAdr == null) return null;
        if (kospiAdr == null) return kosdaqAdr;
        if (kosdaqAdr == null) return kospiAdr;

        // 단순 평균
        return kospiAdr.add(kosdaqAdr)
                .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
    }

    /**
     * ADR 기반 시장 상태 판단
     */
    private MarketCondition determineCondition(BigDecimal adr) {
        if (adr == null) return null;

        if (adr.compareTo(ADR_OVERHEATED) >= 0) {
            return MarketCondition.OVERHEATED;
        } else if (adr.compareTo(ADR_EXTREME_FEAR) <= 0) {
            return MarketCondition.EXTREME_FEAR;
        } else if (adr.compareTo(ADR_OVERSOLD) <= 0) {
            return MarketCondition.OVERSOLD;
        } else {
            return MarketCondition.NORMAL;
        }
    }

    /**
     * 시장 진단 메시지 생성
     */
    private String generateDiagnosis(MarketStatusDto kospi, MarketStatusDto kosdaq, BigDecimal combinedAdr) {
        StringBuilder sb = new StringBuilder();

        if (combinedAdr != null) {
            sb.append(String.format("종합 ADR(20일): %.1f ", combinedAdr));

            if (combinedAdr.compareTo(ADR_OVERHEATED) >= 0) {
                sb.append("- 시장이 과열 상태입니다. ");
            } else if (combinedAdr.compareTo(ADR_EXTREME_FEAR) <= 0) {
                sb.append("- 극심한 공포 구간입니다. ");
            } else if (combinedAdr.compareTo(ADR_OVERSOLD) <= 0) {
                sb.append("- 시장이 침체 구간입니다. ");
            } else {
                sb.append("- 시장이 정상 범위입니다. ");
            }
        }

        // 당일 상황 추가
        if (kospi != null && kospi.getDailyRatio() != null) {
            sb.append(String.format("| 코스피 당일 등락비: %.1f ", kospi.getDailyRatio()));
        }
        if (kosdaq != null && kosdaq.getDailyRatio() != null) {
            sb.append(String.format("| 코스닥 당일 등락비: %.1f", kosdaq.getDailyRatio()));
        }

        return sb.toString();
    }

    /**
     * Entity를 DTO로 변환
     */
    private MarketStatusDto convertToStatusDto(MarketDailyStatus entity) {
        return MarketStatusDto.builder()
                .marketType(entity.getMarketType())
                .tradeDate(entity.getTradeDate())
                .advancingCount(entity.getAdvancingCount())
                .decliningCount(entity.getDecliningCount())
                .unchangedCount(entity.getUnchangedCount())
                .upperLimitCount(entity.getUpperLimitCount())
                .lowerLimitCount(entity.getLowerLimitCount())
                .dailyRatio(entity.getDailyRatio())
                .adr20(entity.getAdr20())
                .condition(determineCondition(entity.getAdr20()))
                .indexClose(entity.getIndexClose())
                .indexChangeRate(entity.getIndexChangeRate())
                .tradingValue(entity.getTradingValue())
                .build();
    }

    /**
     * 빈 타이밍 데이터 생성
     */
    private MarketTimingDto createEmptyTiming() {
        return MarketTimingDto.builder()
                .analysisDate(LocalDate.now())
                .diagnosis("시장 데이터가 없습니다. 먼저 데이터를 수집해주세요.")
                .strategy("데이터 수집 후 분석을 이용해주세요.")
                .build();
    }

    // ========== 텔레그램 알림 연동 메서드 ==========

    /**
     * 시장 상태 알림 발송
     * - 시장이 과열 또는 공포 구간일 때 알림
     * - 스케줄러에서 데이터 수집 후 호출 권장
     *
     * @param onlyExtreme true면 과열/극심한공포일 때만 알림, false면 항상 알림
     * @return 알림 발송 여부
     */
    public boolean sendMarketStatusAlert(boolean onlyExtreme) {
        log.info("시장 상태 알림 발송 시작 - onlyExtreme: {}", onlyExtreme);

        MarketTimingDto timing = getCurrentMarketTiming();

        if (timing.getCombinedAdr() == null || timing.getOverallCondition() == null) {
            log.info("시장 데이터 부족으로 알림 발송 생략");
            return false;
        }

        MarketCondition condition = timing.getOverallCondition();

        // 극단적 상황만 알림하는 경우
        if (onlyExtreme) {
            if (condition != MarketCondition.OVERHEATED && condition != MarketCondition.EXTREME_FEAR) {
                log.info("시장 상태가 정상 범위이므로 알림 발송 생략 - condition: {}", condition);
                return false;
            }
        }

        telegramNotificationService.sendMarketStatusAlert(
                condition.name(),
                timing.getCombinedAdr(),
                timing.getDiagnosis()
        );

        log.info("시장 상태 알림 발송 완료 - condition: {}, ADR: {}", condition, timing.getCombinedAdr());
        return true;
    }

    /**
     * 데이터 수집 및 알림 발송 (통합)
     * - 스케줄러에서 사용하기 좋은 통합 메서드
     */
    @Transactional
    public void collectAndNotify() {
        collectMarketData();
        sendMarketStatusAlert(true);  // 극단적 상황만 알림
    }
}
