package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.core.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final StockFinancialDataCollector stockFinancialDataCollector;
    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final SseEmitterService sseEmitterService;
    private final BatchJobMonitorService batchMonitor;

    // 작업 상태 플래그 — @Async crawlerExecutor 에서 동시 호출 가능하므로 ConcurrentHashMap 필수.
    // 일반 HashMap 이면 computeIfAbsent 동시 호출 시 race condition (resize 도중 쓰기 충돌).
    private final Map<String, AtomicBoolean> runningTasks = new java.util.concurrent.ConcurrentHashMap<>();

    // 마지막 자동 수집 결과 저장
    private volatile LocalDateTime lastAutoCollectTime;
    private volatile boolean lastAutoCollectSuccess;
    private volatile String lastAutoCollectMessage;
    private volatile Map<String, Object> lastAutoCollectResult;


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
     * - 2단계: 성장률 계산 (PEG 스크리너용)
     *
     * ⚠ 2026-09-23 에 네이버 크롤 두 단계를 은퇴시켰다 — 소스(finance.naver.com/item/main.naver)가
     *   SPA 로 이전해 둘 다 100% 실패 중이었다. '분기별 재무제표'는 KIS V55(stock_quarterly_financial)가,
     *   '영업이익률'은 1단계 KIS 수집기가 이미 채운다. 은퇴 전 4단계 82분 → 2단계 약 38분.
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

            // ⚠ 진행상황을 SSE 로만 보내면 화면을 보고 있지 않은 한 어디서 죽었는지 알 수 없다.
            //    실측(2026-09-22): 성장률 4종 컬럼이 수집일의 27%(9/21·9/17·9/11)에서 통째로 0 인데
            //    같은 날 1~3단계 산출물은 정상이라 4단계만 빠진 것이었고, 로그엔 아무 흔적도 없었다.
            //    원인 하나는 직접 관찰했다 — 08:30 배치 도중 배포로 컨테이너가 재생성되자 그 회차가
            //    통째로 사라졌다(재시도 없음, 다음 기회는 15:38).
            //    하루 2회 도는 잡이라 단계 로그는 스팸이 아니다(§5 는 분당·30초 주기 잡의 반복 로그를 말한다).

            // 시작 이벤트 전송 (4단계)
            sseEmitterService.sendStart(taskType, 2, "원버튼 전체 데이터 수집을 시작합니다.");

            // 1단계: 기본 재무 데이터 수집
            sseEmitterService.sendStep(taskType, 1, 2, "1️⃣ 기본 재무 데이터 수집 중...");
            Map<String, Object> step1 = stockFinancialDataService.collectAllStocksFinancialData();
            result.put("step1_basicFinancial", step1);
            log.info("[Async] 1/2 기본 재무 데이터 완료 - 성공 {}, 실패 {}", step1.get("successCount"), step1.get("failCount"));
            sseEmitterService.sendLog(taskType, "INFO", String.format("✅ 기본 재무 데이터: 성공 %s, 실패 %s",
                    step1.get("successCount"), step1.get("failCount")));

            // 2단계: 성장률 계산 (PEG 스크리너용)
            sseEmitterService.sendStep(taskType, 2, 2, "2️⃣ 성장률 계산 중 (PEG 스크리너용)...");
            int growthUpdated = stockFinancialDataCollector.calculateAndUpdateGrowthRates();
            result.put("step4_growthRates", Map.of("updatedCount", growthUpdated));
            // 0건이면 그 자체가 신호다 — 이 배치가 안 돈 날은 eps/매출/순익 성장률과 PEG 가 통째로 0 이 된다.
            log.info("[Async] 2/2 성장률 계산 완료 - {}건 업데이트", growthUpdated);
            sseEmitterService.sendLog(taskType, "INFO", String.format("✅ 성장률 계산: %d건 업데이트", growthUpdated));

            long elapsedTime = System.currentTimeMillis() - startTime;

            result.put("success", true);
            result.put("elapsedSeconds", elapsedTime / 1000);
            result.put("message", String.format("전체 데이터 수집 완료 (소요시간: %d초)", elapsedTime / 1000));

            // 완료 이벤트 전송
            sseEmitterService.sendComplete(taskType, result);

            log.info("========== [Async] 원버튼 전체 데이터 수집 완료 - 소요시간: {}초 ==========", elapsedTime / 1000);

            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            // ⚠ 예전엔 여기서 로그만 남기고 끝이라 아무도 몰랐다. 스케줄러의 alertFailure 는 '트리거'
            //    실패만 잡고 비동기 본체 실패는 못 잡는다 — 그래서 여기서 직접 올린다(§4c 침묵 금지).
            //    result 에 남아 있는 stepN_* 키가 어디까지 갔는지 말해 준다.
            String reached = result.keySet().stream().filter(k -> k.startsWith("step"))
                    .sorted().reduce((a, b) -> a + "," + b).orElse("없음");
            log.error("[Async] 원버튼 수집 실패 - 완료한 단계: {}", reached, e);
            batchMonitor.alertFailure("재무데이터_올인원", "완료 단계 [" + reached + "] 이후 실패: " + e.getMessage());
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
     * 아무 작업이라도 실행 중인지 확인
     */
    public boolean isAnyTaskRunning() {
        return runningTasks.values().stream().anyMatch(AtomicBoolean::get);
    }

    /**
     * 종목코드로 종목명 조회
     */
    private String getStockName(String stockCode) {
        return stockFinancialDataRepository.findTopByStockCodeOrderByReportDateDesc(stockCode)
                .map(StockFinancialData::getStockName)
                .orElse(stockCode);
    }

    /**
     * 스케줄러용 원버튼 전체 데이터 수집 (동기 실행, SSE 없음)
     * - 08:30, 15:40 자동 수집에서 호출
     * - 결과를 메모리에 저장하여 화면에 표시
     */
    public Map<String, Object> collectAllInOneSync() {
        String taskType = "collect-all-in-one";
        Map<String, Object> result = new HashMap<>();

        AtomicBoolean isRunning = runningTasks.computeIfAbsent(taskType, k -> new AtomicBoolean(false));
        if (!isRunning.compareAndSet(false, true)) {
            result.put("success", false);
            result.put("message", "이미 전체 수집이 진행 중입니다.");
            return result;
        }

        try {
            long startTime = System.currentTimeMillis();
            log.info("========== [Scheduled] 원버튼 전체 데이터 수집 시작 ==========");

            // 1단계: 기본 재무 데이터 수집
            log.info("[Scheduled] 1/2 기본 재무 데이터 수집 중...");
            Map<String, Object> step1 = stockFinancialDataService.collectAllStocksFinancialData();
            result.put("step1_basicFinancial", step1);

            // 2단계: 성장률 계산 (PEG 스크리너용)
            log.info("[Scheduled] 2/2 성장률 계산 중...");
            int growthUpdated = stockFinancialDataCollector.calculateAndUpdateGrowthRates();
            result.put("step4_growthRates", Map.of("updatedCount", growthUpdated));

            long elapsedTime = System.currentTimeMillis() - startTime;

            result.put("success", true);
            result.put("elapsedSeconds", elapsedTime / 1000);
            result.put("message", String.format("전체 데이터 수집 완료 (소요시간: %d초)", elapsedTime / 1000));

            log.info("========== [Scheduled] 원버튼 전체 데이터 수집 완료 - 소요시간: {}초 ==========", elapsedTime / 1000);

            // 마지막 수집 결과 저장
            lastAutoCollectTime = DateTimeUtil.kstNow();
            lastAutoCollectSuccess = true;
            lastAutoCollectMessage = result.get("message").toString();
            lastAutoCollectResult = new HashMap<>(result);

            return result;

        } catch (Exception e) {
            log.error("[Scheduled] 원버튼 수집 오류", e);
            result.put("success", false);
            result.put("message", "수집 중 오류 발생: " + e.getMessage());

            // 실패 결과 저장
            lastAutoCollectTime = DateTimeUtil.kstNow();
            lastAutoCollectSuccess = false;
            lastAutoCollectMessage = "수집 실패: " + e.getMessage();
            lastAutoCollectResult = new HashMap<>(result);

            return result;
        } finally {
            isRunning.set(false);
        }
    }

    /**
     * 마지막 자동 수집 결과 조회
     */
    public Map<String, Object> getLastAutoCollectStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("lastCollectTime", lastAutoCollectTime);
        status.put("success", lastAutoCollectSuccess);
        status.put("message", lastAutoCollectMessage);
        status.put("detail", lastAutoCollectResult);
        return status;
    }
}
