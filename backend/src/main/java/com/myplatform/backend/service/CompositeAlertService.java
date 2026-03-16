package com.myplatform.backend.service;

import com.myplatform.backend.dto.InvestorSurgeDto;
import com.myplatform.backend.dto.MarketIndicatorStockDto;
import com.myplatform.backend.entity.AlertHistory;
import com.myplatform.backend.repository.AlertHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 복합 조건 주식 알림 서비스
 * - 여러 조건을 동시에 충족하는 종목을 감지하여 텔레그램 알림 발송
 * - 2개 이상 조건 충족 시 알림 트리거
 * - 60분 내 동일 종목 중복 알림 방지
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompositeAlertService {

    private final MarketIndicatorService marketIndicatorService;
    private final InvestorSurgeService investorSurgeService;
    private final StockPriceService stockPriceService;
    private final TelegramNotificationService telegramNotificationService;
    private final AlertHistoryRepository alertHistoryRepository;

    // 복합 조건 기준값
    private static final BigDecimal VOLUME_SURGE_THRESHOLD = new BigDecimal("300");  // 거래량 전일대비 300%
    private static final BigDecimal PRICE_MOMENTUM_THRESHOLD = new BigDecimal("3");   // 등락률 +3% 이상
    private static final int MIN_CONDITIONS_TO_TRIGGER = 2;                           // 최소 충족 조건 수
    private static final long COMPOSITE_ALERT_COOLDOWN_MINUTES = 60;                  // 중복 알림 방지 (60분)

    private static final String ALERT_TYPE_COMPOSITE = "COMPOSITE";

    /**
     * 복합 신호 체크 (스케줄러에서 10분 간격으로 호출)
     * - 외국인/기관 수급 데이터, 52주 신고가, 거래량/등락률 등 종합 분석
     */
    @Transactional
    public void checkCompositeSignals() {
        log.info("복합 조건 알림 체크 시작");

        try {
            // 1. 각 데이터 소스에서 종목 정보 수집
            Map<String, StockSignalData> signalMap = new HashMap<>();

            // 외국인 수급 데이터 수집
            collectForeignData(signalMap);

            // 기관 수급 데이터 수집
            collectInstitutionData(signalMap);

            // 52주 신고가 종목 수집
            collect52WeekHighData(signalMap);

            if (signalMap.isEmpty()) {
                log.info("복합 조건 체크할 종목이 없습니다.");
                return;
            }

            // 2. 각 종목별 충족 조건 분석 및 알림 발송
            int alertCount = 0;
            for (Map.Entry<String, StockSignalData> entry : signalMap.entrySet()) {
                String stockCode = entry.getKey();
                StockSignalData data = entry.getValue();

                // 충족된 조건 리스트 생성
                List<String> matchedConditions = evaluateConditions(data);

                // 2개 이상 조건 충족 시 알림 발송
                if (matchedConditions.size() >= MIN_CONDITIONS_TO_TRIGGER) {
                    // 60분 내 중복 알림 체크
                    if (isDuplicateAlert(stockCode)) {
                        log.debug("복합 알림 쿨다운 중: {} ({})", data.stockName, stockCode);
                        continue;
                    }

                    // 텔레그램 알림 발송
                    BigDecimal price = data.currentPrice != null ? data.currentPrice : BigDecimal.ZERO;
                    telegramNotificationService.sendCompositeAlert(
                            data.stockName, stockCode, price, matchedConditions);

                    // 알림 이력 저장
                    saveAlertHistory(stockCode, data.stockName);

                    alertCount++;
                    log.info("복합 조건 알림 발송: {} ({}) - 충족 조건: {}",
                            data.stockName, stockCode, matchedConditions);
                }
            }

            log.info("복합 조건 알림 체크 완료 - 알림 발송: {}건", alertCount);

        } catch (Exception e) {
            log.error("복합 조건 알림 체크 중 오류 발생: {}", e.getMessage(), e);
        }
    }

    /**
     * 외국인 수급 데이터 수집
     */
    private void collectForeignData(Map<String, StockSignalData> signalMap) {
        try {
            List<InvestorSurgeDto> foreignStocks = investorSurgeService.getSurgeStocks("FOREIGN", BigDecimal.ZERO);
            for (InvestorSurgeDto dto : foreignStocks) {
                if (dto.getStockCode() == null) continue;

                StockSignalData data = signalMap.computeIfAbsent(
                        dto.getStockCode(), k -> new StockSignalData());
                data.stockCode = dto.getStockCode();
                data.stockName = dto.getStockName();
                data.currentPrice = dto.getCurrentPrice();
                data.changeRate = dto.getChangeRate();

                // 외국인 순매수 금액 설정
                data.foreignNetBuy = dto.getNetBuyAmount();
            }
            log.debug("외국인 수급 데이터 수집: {}건", foreignStocks.size());
        } catch (Exception e) {
            log.error("외국인 수급 데이터 수집 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 기관 수급 데이터 수집
     */
    private void collectInstitutionData(Map<String, StockSignalData> signalMap) {
        try {
            List<InvestorSurgeDto> instStocks = investorSurgeService.getSurgeStocks("INSTITUTION", BigDecimal.ZERO);
            for (InvestorSurgeDto dto : instStocks) {
                if (dto.getStockCode() == null) continue;

                StockSignalData data = signalMap.computeIfAbsent(
                        dto.getStockCode(), k -> new StockSignalData());
                // 이름/가격이 아직 없으면 기관 데이터에서 설정
                if (data.stockName == null) {
                    data.stockCode = dto.getStockCode();
                    data.stockName = dto.getStockName();
                    data.currentPrice = dto.getCurrentPrice();
                    data.changeRate = dto.getChangeRate();
                }

                // 기관 순매수 금액 설정
                data.instNetBuy = dto.getNetBuyAmount();
            }
            log.debug("기관 수급 데이터 수집: {}건", instStocks.size());
        } catch (Exception e) {
            log.error("기관 수급 데이터 수집 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 52주 신고가 종목 수집
     */
    private void collect52WeekHighData(Map<String, StockSignalData> signalMap) {
        try {
            List<MarketIndicatorStockDto> highStocks = marketIndicatorService.get52WeekHighStocks();
            for (MarketIndicatorStockDto dto : highStocks) {
                if (dto.getStockCode() == null) continue;

                StockSignalData data = signalMap.computeIfAbsent(
                        dto.getStockCode(), k -> new StockSignalData());
                if (data.stockName == null) {
                    data.stockCode = dto.getStockCode();
                    data.stockName = dto.getStockName();
                    data.currentPrice = dto.getCurrentPrice();
                    data.changeRate = dto.getChangeRate();
                }

                // 52주 신고가 플래그 설정
                data.is52WeekHigh = true;
            }
            log.debug("52주 신고가 종목 수집: {}건", highStocks.size());
        } catch (Exception e) {
            log.error("52주 신고가 종목 수집 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 종목별 복합 조건 평가
     * @return 충족된 조건 리스트 (한글)
     */
    private List<String> evaluateConditions(StockSignalData data) {
        List<String> matched = new ArrayList<>();

        // 조건 1: 외국인 순매수 positive
        if (data.foreignNetBuy != null && data.foreignNetBuy.compareTo(BigDecimal.ZERO) > 0) {
            matched.add("외국인 순매수 +" + formatAmount(data.foreignNetBuy) + "억");
        }

        // 조건 2: 기관 순매수 positive
        if (data.instNetBuy != null && data.instNetBuy.compareTo(BigDecimal.ZERO) > 0) {
            matched.add("기관 순매수 +" + formatAmount(data.instNetBuy) + "억");
        }

        // 조건 3: 52주 신고가 돌파
        if (data.is52WeekHigh) {
            matched.add("52주 신고가 돌파");
        }

        // 조건 4: 가격 모멘텀 (등락률 +3% 이상)
        if (data.changeRate != null && data.changeRate.compareTo(PRICE_MOMENTUM_THRESHOLD) >= 0) {
            matched.add("등락률 +" + data.changeRate.toPlainString() + "%");
        }

        return matched;
    }

    /**
     * 60분 내 동일 종목 중복 알림 여부 확인
     */
    private boolean isDuplicateAlert(String stockCode) {
        String alertKey = stockCode + "_" + ALERT_TYPE_COMPOSITE;
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(COMPOSITE_ALERT_COOLDOWN_MINUTES);
        return alertHistoryRepository.existsRecentAlert(alertKey, cutoffTime);
    }

    /**
     * 알림 이력 저장
     */
    private void saveAlertHistory(String stockCode, String stockName) {
        AlertHistory alert = new AlertHistory();
        alert.setAlertKey(stockCode + "_" + ALERT_TYPE_COMPOSITE);
        alert.setStockCode(stockCode);
        alert.setStockName(stockName);
        alert.setInvestorType("COMPOSITE");
        alert.setAlertType(ALERT_TYPE_COMPOSITE);
        alert.setSentAt(LocalDateTime.now());
        alertHistoryRepository.save(alert);
    }

    /**
     * 금액 포맷팅 (억원)
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "0";
        return String.format("%,.1f", amount);
    }

    /**
     * 종목별 신호 데이터 (내부 집계용)
     */
    private static class StockSignalData {
        String stockCode;
        String stockName;
        BigDecimal currentPrice;
        BigDecimal changeRate;
        BigDecimal foreignNetBuy;       // 외국인 순매수 (억원)
        BigDecimal instNetBuy;          // 기관 순매수 (억원)
        boolean is52WeekHigh = false;   // 52주 신고가 돌파 여부
    }
}
