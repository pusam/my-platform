package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockShortDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 주식 재무 데이터 수집 서비스
 * - KIS API를 통해 PER, PBR, ROE 등 재무 지표 수집
 * - 투자자 매매 상위 종목 기준으로 수집
 * - 각 종목별 수집은 독립 트랜잭션으로 처리 (StockFinancialDataCollector)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockFinancialDataService {

    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final StockShortDataRepository stockShortDataRepository;
    private final KoreaInvestmentService koreaInvestmentService;
    private final StockFinancialDataCollector collector;

    /**
     * 매일 밤 재무 데이터 업데이트 체크 (23:00)
     */
    @Scheduled(cron = "0 0 23 * * MON-FRI", zone = "Asia/Seoul")
    public void collectDailyFinancialData() {
        log.info("=== 재무 데이터 일일 수집 시작 (23:00) ===");
        collectFinancialDataFromTopStocks();
        log.info("=== 재무 데이터 일일 수집 완료 ===");
    }

    /**
     * 외국인/기관 순매수 상위 종목의 재무 데이터 수집
     */
    public Map<String, Integer> collectFinancialDataFromTopStocks() {
        Map<String, Integer> result = new HashMap<>();
        Set<String> collectedCodes = new HashSet<>();
        int successCount = 0;
        int failCount = 0;

        try {
            // 외국인 순매수 상위 종목
            JsonNode foreignBuy = koreaInvestmentService.getForeignNetBuyTop();
            if (foreignBuy != null && foreignBuy.has("output")) {
                for (JsonNode item : foreignBuy.get("output")) {
                    String stockCode = item.has("mksc_shrn_iscd") ? item.get("mksc_shrn_iscd").asText() : null;
                    if (stockCode != null && !collectedCodes.contains(stockCode)) {
                        collectedCodes.add(stockCode);
                    }
                }
            }

            // 기관 순매수 상위 종목
            Thread.sleep(500);
            JsonNode instBuy = koreaInvestmentService.getInstitutionNetBuyTop();
            if (instBuy != null && instBuy.has("output")) {
                for (JsonNode item : instBuy.get("output")) {
                    String stockCode = item.has("mksc_shrn_iscd") ? item.get("mksc_shrn_iscd").asText() : null;
                    if (stockCode != null && !collectedCodes.contains(stockCode)) {
                        collectedCodes.add(stockCode);
                    }
                }
            }

            log.info("수집 대상 종목 수: {}", collectedCodes.size());

            // 각 종목별 재무 데이터 수집 (독립 트랜잭션)
            for (String stockCode : collectedCodes) {
                try {
                    Thread.sleep(200);
                    if (collector.collectStockFinancialData(stockCode)) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                } catch (Exception e) {
                    log.error("종목 {} 재무 데이터 수집 실패: {}", stockCode, e.getMessage());
                    failCount++;
                }
            }

        } catch (Exception e) {
            log.error("재무 데이터 수집 중 오류", e);
        }

        result.put("total", collectedCodes.size());
        result.put("success", successCount);
        result.put("fail", failCount);
        log.info("재무 데이터 수집 완료 - 성공: {}, 실패: {}", successCount, failCount);

        return result;
    }

    /**
     * 단일 종목 재무 데이터 수집
     */
    public boolean collectStockFinancialData(String stockCode) {
        return collector.collectStockFinancialData(stockCode);
    }

    /**
     * 수동으로 재무 데이터 수집 트리거
     */
    public Map<String, Integer> collectManually() {
        return collectFinancialDataFromTopStocks();
    }

    /**
     * 특정 종목 재무 데이터 수동 수집
     */
    public boolean collectSingleStock(String stockCode) {
        return collector.collectStockFinancialData(stockCode);
    }

    /**
     * 수집된 재무 데이터 건수 조회
     */
    public long getDataCount() {
        return stockFinancialDataRepository.count();
    }

    /**
     * 전체 재무 데이터 삭제 후 재수집
     * - 삭제와 수집을 분리하여 트랜잭션 오류 방지
     */
    public Map<String, Object> deleteAndRecollect() {
        Map<String, Object> result = new HashMap<>();

        // 기존 데이터 삭제 (별도 트랜잭션)
        long deletedCount = deleteAllFinancialData();
        result.put("deleted", deletedCount);

        // 재수집 (각 종목별 독립 트랜잭션)
        Map<String, Integer> collectResult = collectFinancialDataFromTopStocks();
        result.putAll(collectResult);

        return result;
    }

    /**
     * 전체 재무 데이터 삭제 (독립 트랜잭션)
     */
    @Transactional
    public long deleteAllFinancialData() {
        long count = stockFinancialDataRepository.count();
        stockFinancialDataRepository.deleteAll();
        log.info("재무 데이터 {}건 삭제 완료", count);
        return count;
    }

    /**
     * 전 종목 재무 데이터 수집
     */
    public Map<String, Object> collectAllStocksFinancialData() {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        log.info("========== 전 종목 재무 데이터 수집 시작 ==========");

        List<String> allStockCodes = stockShortDataRepository.findDistinctStockCodes();
        int totalCount = allStockCodes.size();
        log.info("수집 대상 종목 수: {}", totalCount);

        if (totalCount == 0) {
            result.put("success", false);
            result.put("message", "수집할 종목이 없습니다. StockShortData 테이블에 데이터가 필요합니다.");
            return result;
        }

        int successCount = 0;
        int failCount = 0;
        int progressInterval = Math.max(totalCount / 20, 1);

        for (int i = 0; i < allStockCodes.size(); i++) {
            String stockCode = allStockCodes.get(i);

            try {
                if (i > 0) {
                    Thread.sleep(300);
                }

                // 각 종목 독립 트랜잭션으로 수집
                boolean collected = collector.collectStockFinancialDataSimple(stockCode);
                if (collected) {
                    successCount++;
                } else {
                    failCount++;
                }

                if ((i + 1) % progressInterval == 0 || i == totalCount - 1) {
                    int progress = (int) (((i + 1) * 100.0) / totalCount);
                    log.info("진행률: {}/{} ({}%) - 성공: {}, 실패: {}",
                            i + 1, totalCount, progress, successCount, failCount);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("수집 중단됨");
                break;
            } catch (Exception e) {
                log.error("종목 {} 수집 실패: {}", stockCode, e.getMessage());
                failCount++;
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("========== 전 종목 재무 데이터 수집 완료 ==========");
        log.info("총 {}개 종목 중 성공: {}, 실패: {}, 소요시간: {}초",
                totalCount, successCount, failCount, elapsedTime / 1000);

        result.put("success", true);
        result.put("total", totalCount);
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("elapsedSeconds", elapsedTime / 1000);
        result.put("message", String.format("전 종목 수집 완료 (성공: %d, 실패: %d)", successCount, failCount));

        return result;
    }

    /**
     * 특정 종목 목록의 재무 데이터 수집
     */
    public Map<String, Object> collectStocksFinancialData(List<String> stockCodes) {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        log.info("지정 종목 재무 데이터 수집 시작: {}개", stockCodes.size());

        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < stockCodes.size(); i++) {
            String stockCode = stockCodes.get(i);

            try {
                if (i > 0) {
                    Thread.sleep(300);
                }

                if (collector.collectStockFinancialDataSimple(stockCode)) {
                    successCount++;
                } else {
                    failCount++;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                failCount++;
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;

        result.put("success", true);
        result.put("total", stockCodes.size());
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("elapsedSeconds", elapsedTime / 1000);

        return result;
    }
}
