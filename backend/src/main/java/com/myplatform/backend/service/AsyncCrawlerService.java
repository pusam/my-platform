package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockShortDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 비동기 크롤링 서비스
 * - SSE를 통한 실시간 진행률 전송
 * - 장시간 크롤링 작업을 백그라운드에서 처리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncCrawlerService {

    private final FinancialDataCrawlerService financialDataCrawlerService;
    private final StockFinancialDataService stockFinancialDataService;
    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final StockShortDataRepository stockShortDataRepository;
    private final SseEmitterService sseEmitterService;

    // 작업 상태 플래그
    private final Map<String, AtomicBoolean> runningTasks = new HashMap<>();

    /**
     * 전 종목 영업이익률 비동기 크롤링
     * - 즉시 작업 ID 반환, 백그라운드에서 크롤링 수행
     * - SSE로 진행률 실시간 전송
     */
    @Async("crawlerExecutor")
    public CompletableFuture<Map<String, Object>> crawlAllOperatingMarginAsync(boolean forceUpdate) {
        String taskType = "crawl-operating-margin";
        Map<String, Object> result = new HashMap<>();

        // 이미 실행 중인지 확인
        AtomicBoolean isRunning = runningTasks.computeIfAbsent(taskType, k -> new AtomicBoolean(false));
        if (!isRunning.compareAndSet(false, true)) {
            result.put("success", false);
            result.put("message", "이미 영업이익률 크롤링이 진행 중입니다.");
            sseEmitterService.sendError(taskType, "이미 크롤링이 진행 중입니다.");
            return CompletableFuture.completedFuture(result);
        }

        try {
            long startTime = System.currentTimeMillis();
            log.info("========== [Async] 영업이익률 크롤링 시작 (forceUpdate: {}) ==========", forceUpdate);

            // 대상 종목 조회
            LocalDate today = LocalDate.now();
            List<StockFinancialData> allData = stockFinancialDataRepository.findByReportDate(today);

            if (allData.isEmpty()) {
                List<String> stockCodes = stockShortDataRepository.findDistinctStockCodes();
                log.info("오늘 날짜 데이터 없음. StockShortData에서 {}개 종목 코드 조회", stockCodes.size());

                for (String code : stockCodes) {
                    stockFinancialDataRepository.findTopByStockCodeOrderByReportDateDesc(code)
                            .ifPresent(allData::add);
                }
            }

            int totalCount = allData.size();

            if (totalCount == 0) {
                result.put("success", false);
                result.put("message", "크롤링할 종목이 없습니다.");
                sseEmitterService.sendError(taskType, "크롤링할 종목이 없습니다.");
                return CompletableFuture.completedFuture(result);
            }

            // 시작 이벤트 전송
            sseEmitterService.sendStart(taskType, totalCount, "영업이익률 크롤링을 시작합니다.");
            sseEmitterService.sendLog(taskType, "INFO", String.format("총 %d개 종목 크롤링 시작", totalCount));

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            AtomicInteger skipCount = new AtomicInteger(0);
            int progressInterval = Math.max(totalCount / 100, 1);

            for (int i = 0; i < allData.size(); i++) {
                StockFinancialData data = allData.get(i);

                // 이미 영업이익률이 있고 forceUpdate가 false면 스킵
                if (!forceUpdate && data.getOperatingMargin() != null
                        && data.getOperatingMargin().compareTo(BigDecimal.ZERO) != 0) {
                    skipCount.incrementAndGet();
                    continue;
                }

                try {
                    // Rate Limit: 500ms 대기
                    if (i > 0) {
                        Thread.sleep(500);
                    }

                    Map<String, BigDecimal> financials = financialDataCrawlerService.crawlFinancialRatios(data.getStockCode());

                    if (financials != null && !financials.isEmpty()) {
                        updateFinancialData(data, financials);
                        stockFinancialDataRepository.save(data);
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }

                    // 진행률 전송 (1% 단위 또는 마지막)
                    if ((i + 1) % progressInterval == 0 || i == totalCount - 1) {
                        String stockName = data.getStockName() != null ? data.getStockName() : data.getStockCode();
                        sseEmitterService.sendProgress(taskType, i + 1, totalCount,
                                successCount.get(), failCount.get(), stockName);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("크롤링 중단됨");
                    sseEmitterService.sendError(taskType, "크롤링이 중단되었습니다.");
                    break;
                } catch (Exception e) {
                    log.debug("크롤링 실패 [{}]: {}", data.getStockCode(), e.getMessage());
                    failCount.incrementAndGet();
                }
            }

            long elapsedTime = System.currentTimeMillis() - startTime;

            result.put("success", true);
            result.put("total", totalCount);
            result.put("successCount", successCount.get());
            result.put("failCount", failCount.get());
            result.put("skipCount", skipCount.get());
            result.put("elapsedSeconds", elapsedTime / 1000);
            result.put("message", String.format("영업이익률 크롤링 완료 (성공: %d, 실패: %d, 스킵: %d)",
                    successCount.get(), failCount.get(), skipCount.get()));

            // 완료 이벤트 전송
            sseEmitterService.sendComplete(taskType, result);

            log.info("========== [Async] 영업이익률 크롤링 완료 - 성공: {}, 실패: {}, 스킵: {}, 소요시간: {}초 ==========",
                    successCount.get(), failCount.get(), skipCount.get(), elapsedTime / 1000);

            return CompletableFuture.completedFuture(result);

        } finally {
            isRunning.set(false);
        }
    }

    /**
     * 분기별 재무제표 비동기 수집
     */
    @Async("crawlerExecutor")
    public CompletableFuture<Map<String, Object>> collectQuarterlyFinanceAsync() {
        String taskType = "collect-finance";
        Map<String, Object> result = new HashMap<>();

        // 이미 실행 중인지 확인
        AtomicBoolean isRunning = runningTasks.computeIfAbsent(taskType, k -> new AtomicBoolean(false));
        if (!isRunning.compareAndSet(false, true)) {
            result.put("success", false);
            result.put("message", "이미 분기별 재무제표 수집이 진행 중입니다.");
            sseEmitterService.sendError(taskType, "이미 수집이 진행 중입니다.");
            return CompletableFuture.completedFuture(result);
        }

        try {
            long startTime = System.currentTimeMillis();
            log.info("========== [Async] 분기별 재무제표 수집 시작 ==========");

            // 대상 종목 조회
            List<String> stockCodes = stockFinancialDataRepository.findAllStockCodes();
            if (stockCodes.isEmpty()) {
                stockCodes = stockShortDataRepository.findDistinctStockCodes();
            }

            int totalCount = stockCodes.size();

            if (totalCount == 0) {
                result.put("success", false);
                result.put("message", "수집할 종목이 없습니다.");
                sseEmitterService.sendError(taskType, "수집할 종목이 없습니다.");
                return CompletableFuture.completedFuture(result);
            }

            // 시작 이벤트 전송
            sseEmitterService.sendStart(taskType, totalCount, "분기별 재무제표 수집을 시작합니다.");
            sseEmitterService.sendLog(taskType, "INFO", String.format("총 %d개 종목 수집 시작", totalCount));

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            int progressInterval = Math.max(totalCount / 100, 1);

            for (int i = 0; i < stockCodes.size(); i++) {
                String stockCode = stockCodes.get(i);

                try {
                    // Rate Limit: 600ms 대기
                    if (i > 0) {
                        Thread.sleep(600);
                    }

                    boolean collected = financialDataCrawlerService.collectSingleStockQuarterlyData(stockCode);
                    if (collected) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }

                    // 진행률 전송
                    if ((i + 1) % progressInterval == 0 || i == totalCount - 1) {
                        String stockName = getStockName(stockCode);
                        sseEmitterService.sendProgress(taskType, i + 1, totalCount,
                                successCount.get(), failCount.get(), stockName);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("수집 중단됨");
                    sseEmitterService.sendError(taskType, "수집이 중단되었습니다.");
                    break;
                } catch (Exception e) {
                    log.debug("종목 {} 수집 실패: {}", stockCode, e.getMessage());
                    failCount.incrementAndGet();
                }
            }

            long elapsedTime = System.currentTimeMillis() - startTime;

            result.put("success", true);
            result.put("total", totalCount);
            result.put("successCount", successCount.get());
            result.put("failCount", failCount.get());
            result.put("elapsedSeconds", elapsedTime / 1000);
            result.put("message", String.format("분기별 재무제표 수집 완료 (성공: %d, 실패: %d)",
                    successCount.get(), failCount.get()));

            // 완료 이벤트 전송
            sseEmitterService.sendComplete(taskType, result);

            log.info("========== [Async] 분기별 재무제표 수집 완료 - 성공: {}, 실패: {}, 소요시간: {}초 ==========",
                    successCount.get(), failCount.get(), elapsedTime / 1000);

            return CompletableFuture.completedFuture(result);

        } finally {
            isRunning.set(false);
        }
    }

    /**
     * 종목명 일괄 수정 비동기 처리
     */
    @Async("crawlerExecutor")
    public CompletableFuture<Map<String, Object>> fixAllStockNamesAsync() {
        String taskType = "fix-stock-names";
        Map<String, Object> result = new HashMap<>();

        AtomicBoolean isRunning = runningTasks.computeIfAbsent(taskType, k -> new AtomicBoolean(false));
        if (!isRunning.compareAndSet(false, true)) {
            result.put("success", false);
            result.put("message", "이미 종목명 수정이 진행 중입니다.");
            sseEmitterService.sendError(taskType, "이미 수정 작업이 진행 중입니다.");
            return CompletableFuture.completedFuture(result);
        }

        try {
            sseEmitterService.sendStart(taskType, 0, "종목명 수정을 시작합니다.");

            // 동기 메서드 호출 (내부에서 로그 출력)
            Map<String, Object> crawlerResult = financialDataCrawlerService.fixAllStockNames();

            result.putAll(crawlerResult);
            sseEmitterService.sendComplete(taskType, result);

            return CompletableFuture.completedFuture(result);

        } finally {
            isRunning.set(false);
        }
    }

    /**
     * 원버튼 전체 데이터 수집 (비동기)
     * - 1단계: 기본 재무 데이터 수집 (KIS API)
     * - 2단계: 영업이익률 크롤링 (네이버 금융)
     * - 3단계: 분기별 재무제표 수집 (네이버 금융)
     */
    @Async("crawlerExecutor")
    public CompletableFuture<Map<String, Object>> collectAllInOneAsync() {
        String taskType = "collect-all-in-one";
        Map<String, Object> result = new HashMap<>();

        AtomicBoolean isRunning = runningTasks.computeIfAbsent(taskType, k -> new AtomicBoolean(false));
        if (!isRunning.compareAndSet(false, true)) {
            result.put("success", false);
            result.put("message", "이미 전체 수집이 진행 중입니다.");
            sseEmitterService.sendError(taskType, "이미 수집이 진행 중입니다.");
            return CompletableFuture.completedFuture(result);
        }

        try {
            long startTime = System.currentTimeMillis();
            log.info("========== [Async] 원버튼 전체 데이터 수집 시작 ==========");

            // 시작 이벤트 전송 (3단계)
            sseEmitterService.sendStart(taskType, 3, "원버튼 전체 데이터 수집을 시작합니다.");

            // 1단계: 기본 재무 데이터 수집
            sseEmitterService.sendStep(taskType, 1, 3, "1️⃣ 기본 재무 데이터 수집 중...");
            Map<String, Object> step1 = stockFinancialDataService.collectAllStocksFinancialData();
            result.put("step1_basicFinancial", step1);
            sseEmitterService.sendLog(taskType, "INFO", String.format("✅ 기본 재무 데이터: 성공 %s, 실패 %s",
                    step1.get("successCount"), step1.get("failCount")));

            // 2단계: 영업이익률 크롤링
            sseEmitterService.sendStep(taskType, 2, 3, "2️⃣ 영업이익률 크롤링 중...");
            Map<String, Object> step2 = financialDataCrawlerService.crawlAllOperatingMargin(false);
            result.put("step2_operatingMargin", step2);
            sseEmitterService.sendLog(taskType, "INFO", String.format("✅ 영업이익률: 성공 %s, 실패 %s",
                    step2.get("successCount"), step2.get("failCount")));

            // 3단계: 분기별 재무제표 수집
            sseEmitterService.sendStep(taskType, 3, 3, "3️⃣ 분기별 재무제표 수집 중...");
            Map<String, Object> step3 = financialDataCrawlerService.collectQuarterlyFinancialStatements();
            result.put("step3_quarterlyFinancials", step3);
            sseEmitterService.sendLog(taskType, "INFO", String.format("✅ 분기별 재무제표: 성공 %s, 실패 %s",
                    step3.get("successCount"), step3.get("failCount")));

            long elapsedTime = System.currentTimeMillis() - startTime;

            result.put("success", true);
            result.put("elapsedSeconds", elapsedTime / 1000);
            result.put("message", String.format("전체 데이터 수집 완료 (소요시간: %d초)", elapsedTime / 1000));

            // 완료 이벤트 전송
            sseEmitterService.sendComplete(taskType, result);

            log.info("========== [Async] 원버튼 전체 데이터 수집 완료 - 소요시간: {}초 ==========", elapsedTime / 1000);

            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            log.error("원버튼 수집 오류", e);
            result.put("success", false);
            result.put("message", "수집 중 오류 발생: " + e.getMessage());
            sseEmitterService.sendError(taskType, "수집 중 오류 발생: " + e.getMessage());
            return CompletableFuture.completedFuture(result);
        } finally {
            isRunning.set(false);
        }
    }

    /**
     * 작업 실행 상태 확인
     */
    public boolean isTaskRunning(String taskType) {
        AtomicBoolean isRunning = runningTasks.get(taskType);
        return isRunning != null && isRunning.get();
    }

    /**
     * 재무 데이터 업데이트 헬퍼
     */
    @Transactional
    protected void updateFinancialData(StockFinancialData data, Map<String, BigDecimal> financials) {
        if (financials.containsKey("operatingMargin")) {
            data.setOperatingMargin(financials.get("operatingMargin"));
        }
        if (financials.containsKey("netMargin")) {
            data.setNetMargin(financials.get("netMargin"));
        }
        if (financials.containsKey("roe")) {
            data.setRoe(financials.get("roe"));
        }
        if (financials.containsKey("debtRatio")) {
            data.setDebtRatio(financials.get("debtRatio"));
        }
    }

    /**
     * 종목코드로 종목명 조회
     */
    private String getStockName(String stockCode) {
        return stockFinancialDataRepository.findTopByStockCodeOrderByReportDateDesc(stockCode)
                .map(StockFinancialData::getStockName)
                .orElse(stockCode);
    }
}
