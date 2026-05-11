package com.myplatform.backend.controller;

import com.myplatform.backend.dto.AdrHistoryResponse;
import com.myplatform.backend.dto.InvestorSurgeDto;
import com.myplatform.backend.dto.MarketDataCollectionResult;
import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.dto.NewsSummaryDto;
import com.myplatform.backend.dto.SimpleMarketStatusResponse;
import com.myplatform.backend.service.GeminiService;
import com.myplatform.backend.service.InvestorSurgeService;
import com.myplatform.backend.service.MarketTimingService;
import com.myplatform.backend.service.NewsService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 시장 타이밍 API 컨트롤러
 * - ADR (등락비율) 기반 시장 상태 분석
 * - 시장 데이터 수집
 */
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "시장 지표", description = "ADR 기반 시장 타이밍 분석 API")
public class MarketTimingController {

    private final MarketTimingService marketTimingService;
    private final GeminiService geminiService;
    private final InvestorSurgeService investorSurgeService;
    private final NewsService newsService;

    /**
     * 현재 시장 상태 조회 (대시보드용)
     */
    @GetMapping("/status")
    @Operation(
        summary = "시장 상태 조회",
        description = "현재 시장의 ADR(등락비율)을 기반으로 시장 상태를 분석합니다.\n\n" +
                     "**ADR 해석:**\n" +
                     "- ADR >= 120: 과열 (현금 확보 필요)\n" +
                     "- 80 < ADR < 120: 보통 (정상 범위)\n" +
                     "- ADR <= 80: 침체 (저점 매수 기회)\n" +
                     "- ADR <= 60: 극심한 공포 (적극 매수 검토)"
    )
    public ResponseEntity<ApiResponse<MarketTimingDto>> getMarketStatus() {
        log.info("시장 상태 조회 API 호출");

        try {
            MarketTimingDto timing = marketTimingService.getCurrentMarketTiming();
            return ResponseEntity.ok(ApiResponse.success("시장 상태 조회 완료", timing));

        } catch (Exception e) {
            log.error("시장 상태 조회 오류: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new ApiResponse<>(false, "시장 상태 조회 실패: " + e.getMessage()));
        }
    }

    /**
     * ADR 히스토리 조회 (차트용)
     */
    @GetMapping("/adr/history")
    @Operation(
        summary = "ADR 히스토리 조회",
        description = "과거 N일간의 ADR 추이를 조회합니다. 차트 표시용."
    )
    public ResponseEntity<AdrHistoryResponse> getAdrHistory(
            @Parameter(description = "조회할 일수 (기본 60일)")
            @RequestParam(defaultValue = "60") int days) {

        log.info("ADR 히스토리 조회 API 호출: {}일", days);

        try {
            List<MarketTimingDto.AdrHistoryDto> history = marketTimingService.getAdrHistory(days);

            AdrHistoryResponse response = AdrHistoryResponse.builder()
                    .success(true)
                    .data(history)
                    .count(history.size())
                    .message("ADR 히스토리 조회 완료")
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("ADR 히스토리 조회 오류: {}", e.getMessage(), e);
            AdrHistoryResponse response = AdrHistoryResponse.builder()
                    .success(false)
                    .message("ADR 히스토리 조회 실패: " + e.getMessage())
                    .build();
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 시장 데이터 수집 (관리자용)
     */
    @PostMapping("/collect")
    @Operation(
        summary = "시장 데이터 수집",
        description = "네이버 금융에서 오늘의 코스피/코스닥 상승·하락·보합 종목 수를 수집합니다."
    )
    public ResponseEntity<ApiResponse<MarketTimingDto>> collectMarketData() {
        log.info("시장 데이터 수집 API 호출");

        try {
            marketTimingService.collectMarketData();

            // 수집 후 최신 상태 반환
            MarketTimingDto timing = marketTimingService.getCurrentMarketTiming();
            return ResponseEntity.ok(ApiResponse.success("시장 데이터 수집 완료", timing));

        } catch (Exception e) {
            log.error("시장 데이터 수집 오류: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new ApiResponse<>(false, "시장 데이터 수집 실패: " + e.getMessage()));
        }
    }

    /**
     * 기간별 시장 데이터 수집 (Backfill)
     */
    @PostMapping("/collect/period")
    @Operation(
        summary = "기간별 시장 데이터 수집",
        description = "특정 기간의 시장 데이터를 수집합니다 (Backfill).\n\n" +
                     "**주의사항:**\n" +
                     "- 네이버 금융 차단 방지를 위해 요청 간 1초 딜레이 적용\n" +
                     "- 주말(토/일)은 자동으로 스킵\n" +
                     "- 과거 데이터의 경우 지수 정보만 수집 가능 (상승/하락 종목 수는 제한적)"
    )
    public ResponseEntity<ApiResponse<MarketDataCollectionResult>> collectMarketDataForPeriod(
            @Parameter(description = "시작 날짜 (yyyy-MM-dd)", example = "2024-01-01")
            @RequestParam String startDate,
            @Parameter(description = "종료 날짜 (yyyy-MM-dd)", example = "2024-01-31")
            @RequestParam String endDate) {

        log.info("기간별 시장 데이터 수집 API 호출: {} ~ {}", startDate, endDate);

        try {
            LocalDate start = LocalDate.parse(startDate, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate end = LocalDate.parse(endDate, DateTimeFormatter.ISO_LOCAL_DATE);

            // 유효성 검증
            if (start.isAfter(end)) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse<>(false, "시작 날짜가 종료 날짜보다 늦습니다."));
            }

            if (end.isAfter(LocalDate.now())) {
                end = LocalDate.now();
            }

            // 수집 실행
            MarketDataCollectionResult result = marketTimingService.collectMarketDataForPeriod(start, end);

            String message = String.format("기간별 수집 완료 (성공: %d, 실패: %d, 스킵: %d)",
                    result.getSuccessCount(), result.getFailCount(), result.getSkipCount());

            return ResponseEntity.ok(ApiResponse.success(message, result));

        } catch (Exception e) {
            log.error("기간별 시장 데이터 수집 오류: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new ApiResponse<>(false, "기간별 수집 실패: " + e.getMessage()));
        }
    }

    /**
     * AI 시장 예측 (향후 5일간 KOSPI 예측)
     *
     * NOTE: 이 엔드포인트는 GeminiService.generateMarketForecast() 가 반환하는
     * 동적 키 구조의 Map (forecasts, scenarios, summary, baseIndex, fallback ...) 을
     * 그대로 응답에 머지하므로 DTO화에서 제외함.
     */
    @GetMapping("/forecast")
    @Operation(
        summary = "AI 시장 예측",
        description = "Gemini AI가 현재 시장 데이터를 분석하여 향후 5거래일간 KOSPI 예측을 제공합니다.\n\n" +
                     "Bull/Base/Bear 3개 시나리오와 확률, 근거를 포함합니다."
    )
    public ResponseEntity<Map<String, Object>> getMarketForecast() {
        log.info("AI 시장 예측 API 호출");

        Map<String, Object> response = new HashMap<>();
        try {
            MarketTimingDto timing = marketTimingService.getCurrentMarketTiming();

            // 외국인 순매수 Top 5
            List<InvestorSurgeDto> foreignBuys = Collections.emptyList();
            try {
                foreignBuys = investorSurgeService.getSurgeStocks("FOREIGN", BigDecimal.valueOf(10));
                if (foreignBuys.size() > 5) foreignBuys = foreignBuys.subList(0, 5);
            } catch (Exception e) {
                log.warn("외국인 수급 데이터 수집 실패 (무시): {}", e.getMessage());
            }

            // 기관 순매수 Top 5
            List<InvestorSurgeDto> instBuys = Collections.emptyList();
            try {
                instBuys = investorSurgeService.getSurgeStocks("INSTITUTION", BigDecimal.valueOf(10));
                if (instBuys.size() > 5) instBuys = instBuys.subList(0, 5);
            } catch (Exception e) {
                log.warn("기관 수급 데이터 수집 실패 (무시): {}", e.getMessage());
            }

            // 최근 뉴스 5건
            List<NewsSummaryDto> recentNews = Collections.emptyList();
            try {
                recentNews = newsService.getRecentNews();
                if (recentNews.size() > 5) recentNews = recentNews.subList(0, 5);
            } catch (Exception e) {
                log.warn("뉴스 데이터 수집 실패 (무시): {}", e.getMessage());
            }

            Map<String, Object> forecast = geminiService.generateMarketForecast(
                    timing, foreignBuys, instBuys, recentNews);

            response.put("success", true);
            response.putAll(forecast);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.warn("AI 시장 예측 실패: {}", e.getMessage());
            response.put("success", false);
            response.put("message", "AI 시장 예측 실패: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 간단 시장 상태 조회 (위젯용)
     */
    @GetMapping("/status/simple")
    @Operation(
        summary = "간단 시장 상태 조회",
        description = "대시보드 위젯용 간단한 시장 상태 정보"
    )
    public ResponseEntity<ApiResponse<SimpleMarketStatusResponse>> getSimpleStatus() {
        log.info("간단 시장 상태 조회 API 호출");

        try {
            MarketTimingDto timing = marketTimingService.getCurrentMarketTiming();

            SimpleMarketStatusResponse.SimpleMarketStatusResponseBuilder builder = SimpleMarketStatusResponse.builder()
                    .analysisDate(timing.getAnalysisDate())
                    .combinedAdr(timing.getCombinedAdr())
                    .condition(timing.getOverallCondition())
                    .conditionEmoji(timing.getOverallCondition() != null
                            ? timing.getOverallCondition().getEmoji() : "❓ 데이터 없음")
                    .diagnosis(timing.getDiagnosis());

            // KOSPI/KOSDAQ 지수 정보 추가
            if (timing.getKospi() != null) {
                builder.kospiIndex(timing.getKospi().getIndexClose())
                        .kospiChange(timing.getKospi().getIndexChangeRate());
            }
            if (timing.getKosdaq() != null) {
                builder.kosdaqIndex(timing.getKosdaq().getIndexClose())
                        .kosdaqChange(timing.getKosdaq().getIndexChangeRate());
            }

            return ResponseEntity.ok(new ApiResponse<>(true, null, builder.build()));

        } catch (Exception e) {
            log.error("간단 시장 상태 조회 오류: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new ApiResponse<>(false, e.getMessage()));
        }
    }
}
