package com.myplatform.backend.controller;

import com.myplatform.backend.dto.TradingIndicatorDto.*;
import com.myplatform.backend.service.GlobalMarketService;
import com.myplatform.backend.service.SectorAnalysisService;
import com.myplatform.backend.service.TechnicalIndicatorService;
import com.myplatform.backend.service.TechnicalIndicatorService.DivergenceResult;
import com.myplatform.backend.service.VwapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 트레이딩 핵심 지표 API Controller
 *
 * - VWAP (거래량 가중 평균 가격)
 * - 나스닥 선물 (글로벌 시장)
 * - 주도 섹터 랭킹
 * - RSI 다이버전스
 */
@RestController
@RequestMapping("/api/trading-indicators")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "트레이딩 지표", description = "전문 트레이더용 핵심 지표 API")
public class TradingIndicatorController {

    private final VwapService vwapService;
    private final GlobalMarketService globalMarketService;
    private final SectorAnalysisService sectorAnalysisService;
    private final TechnicalIndicatorService technicalIndicatorService;

    // ========== 1. VWAP ==========

    @GetMapping("/vwap/{stockCode}")
    @Operation(
        summary = "VWAP 계산",
        description = "당일 분봉 데이터 기반 VWAP(거래량 가중 평균 가격) 계산.\n\n" +
                     "- 현재가 > VWAP: 매수 우위\n" +
                     "- 현재가 < VWAP: 매도 우위\n\n" +
                     "⚠️ 09:10분 이후부터 신뢰도 높음"
    )
    public ResponseEntity<Map<String, Object>> getVwap(
            @Parameter(description = "종목코드 (6자리)", example = "005930")
            @PathVariable String stockCode) {

        log.info("VWAP 조회 API 호출: {}", stockCode);
        VwapResult result = vwapService.calculateVwap(stockCode);
        return ResponseEntity.ok(buildSuccessResponse(result));
    }

    // ========== 2. 나스닥 선물 (글로벌 시장) ==========

    @GetMapping("/global/nasdaq-futures")
    @Operation(
        summary = "나스닥 100 선물 지수",
        description = "Yahoo Finance에서 나스닥 100 선물(NQ=F) 실시간 지수 조회.\n\n" +
                     "- 등락률 -0.5% 이하: 글로벌 악재, 매수 보류 권장\n" +
                     "- 등락률 -1.0% 이하: 강한 리스크 오프"
    )
    public ResponseEntity<Map<String, Object>> getNasdaqFutures() {
        log.info("나스닥 선물 조회 API 호출");
        NasdaqFuturesResult result = globalMarketService.getNasdaqFutures();
        return ResponseEntity.ok(buildSuccessResponse(result));
    }

    @GetMapping("/global/sp500-futures")
    @Operation(
        summary = "S&P 500 선물 지수",
        description = "Yahoo Finance에서 S&P 500 E-mini 선물(ES=F) 실시간 지수 조회"
    )
    public ResponseEntity<Map<String, Object>> getSP500Futures() {
        log.info("S&P 500 선물 조회 API 호출");
        NasdaqFuturesResult result = globalMarketService.getSP500Futures();
        return ResponseEntity.ok(buildSuccessResponse(result));
    }

    @GetMapping("/global/halt-check")
    @Operation(
        summary = "글로벌 악재 필터 체크",
        description = "나스닥 선물 기준 매수 보류 여부 확인\n\n" +
                     "- true: 매수 보류 (나스닥 -0.5% 이하)\n" +
                     "- false: 정상 (매수 허용)"
    )
    public ResponseEntity<Map<String, Object>> checkGlobalHalt() {
        log.info("글로벌 악재 필터 체크 API 호출");
        boolean shouldHalt = globalMarketService.shouldHaltBuying();
        NasdaqFuturesResult nasdaq = globalMarketService.getNasdaqFutures();

        Map<String, Object> result = new HashMap<>();
        result.put("shouldHaltBuying", shouldHalt);
        result.put("nasdaqData", nasdaq);
        result.put("message", shouldHalt
                ? "⚠️ 글로벌 리스크 감지! 매수 보류 권장"
                : "✅ 글로벌 시장 정상. 매수 가능");

        return ResponseEntity.ok(buildSuccessResponse(result));
    }

    // ========== 3. 주도 섹터 랭킹 ==========

    @GetMapping("/sectors/leading")
    @Operation(
        summary = "주도 섹터 랭킹",
        description = "실시간 섹터별 평균 등락률 기반 상위/하위 섹터 조회.\n\n" +
                     "- 상위 3개 섹터: 시장 주도 테마\n" +
                     "- 하위 3개 섹터: 약세 섹터"
    )
    public ResponseEntity<Map<String, Object>> getLeadingSectors() {
        log.info("주도 섹터 랭킹 조회 API 호출");
        LeadingSectorResult result = sectorAnalysisService.getLeadingSectorRanking();
        return ResponseEntity.ok(buildSuccessResponse(result));
    }

    @GetMapping("/sectors/{sectorCode}")
    @Operation(
        summary = "섹터 상세 정보",
        description = "특정 섹터의 상세 정보 및 대장주 조회"
    )
    public ResponseEntity<Map<String, Object>> getSectorDetail(
            @Parameter(description = "섹터 코드", example = "semiconductor")
            @PathVariable String sectorCode) {

        log.info("섹터 상세 조회 API 호출: {}", sectorCode);
        SectorRanking result = sectorAnalysisService.getSectorDetail(sectorCode);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(buildSuccessResponse(result));
    }

    // ========== 4. RSI 다이버전스 ==========

    @PostMapping("/divergence/detect")
    @Operation(
        summary = "RSI 다이버전스 탐지",
        description = "가격 데이터에서 RSI 다이버전스 감지.\n\n" +
                     "**하락 다이버전스**: 주가 신고가 + RSI 전고점 하회 → 매도 신호\n\n" +
                     "**상승 다이버전스**: 주가 신저가 + RSI 전저점 상회 → 매수 신호\n\n" +
                     "lookbackPeriod: 20~60 권장 (기본값 40)"
    )
    public ResponseEntity<Map<String, Object>> detectDivergence(
            @RequestBody DivergenceRequest request) {

        log.info("RSI 다이버전스 탐지 API 호출: 데이터 {}개, lookback={}",
                request.getPrices() != null ? request.getPrices().size() : 0,
                request.getLookbackPeriod());

        int lookback = request.getLookbackPeriod() != null ? request.getLookbackPeriod() : 40;

        DivergenceResult result = technicalIndicatorService.detectRsiDivergence(
                request.getPrices(), lookback);

        return ResponseEntity.ok(buildSuccessResponse(result));
    }

    // ========== 5. 종합 분석 ==========

    @GetMapping("/comprehensive/{stockCode}")
    @Operation(
        summary = "종합 분석",
        description = "특정 종목에 대한 VWAP + 글로벌 시장 + RSI 다이버전스 종합 분석"
    )
    public ResponseEntity<Map<String, Object>> getComprehensiveAnalysis(
            @Parameter(description = "종목코드", example = "005930")
            @PathVariable String stockCode) {

        log.info("종합 분석 API 호출: {}", stockCode);

        // 1. VWAP
        VwapResult vwap = vwapService.calculateVwap(stockCode);

        // 2. 글로벌 시장
        NasdaqFuturesResult nasdaq = globalMarketService.getNasdaqFutures();

        // 3. 섹터 랭킹
        LeadingSectorResult sectors = sectorAnalysisService.getLeadingSectorRanking();

        // 4. 종합 점수 계산
        int score = calculateOverallScore(vwap, nasdaq);
        String recommendation = generateRecommendation(score, vwap, nasdaq);

        ComprehensiveAnalysis result = ComprehensiveAnalysis.builder()
                .vwap(vwap)
                .globalMarket(nasdaq)
                .sectorRanking(sectors)
                .overallScore(score)
                .recommendation(recommendation)
                .analysisTime(LocalDateTime.now())
                .build();

        return ResponseEntity.ok(buildSuccessResponse(result));
    }

    // ========== 헬퍼 메서드 ==========

    private Map<String, Object> buildSuccessResponse(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);
        response.put("timestamp", LocalDateTime.now());
        return response;
    }

    /**
     * 종합 점수 계산 (-100 ~ +100)
     */
    private int calculateOverallScore(VwapResult vwap, NasdaqFuturesResult nasdaq) {
        int score = 0;

        // VWAP 점수 (최대 ±40점)
        if (vwap != null && vwap.getSignal() != null) {
            switch (vwap.getSignal()) {
                case STRONG_BUY: score += 40; break;
                case BUY: score += 20; break;
                case NEUTRAL: break;
                case SELL: score -= 20; break;
                case STRONG_SELL: score -= 40; break;
            }
        }

        // 글로벌 시장 점수 (최대 ±30점)
        if (nasdaq != null && nasdaq.getSignal() != null) {
            switch (nasdaq.getSignal()) {
                case POSITIVE: score += 30; break;
                case NEUTRAL: break;
                case CAUTION: score -= 15; break;
                case NEGATIVE: score -= 30; break;
            }
        }

        return Math.max(-100, Math.min(100, score));
    }

    /**
     * 종합 추천 생성
     */
    private String generateRecommendation(int score, VwapResult vwap, NasdaqFuturesResult nasdaq) {
        StringBuilder sb = new StringBuilder();

        if (nasdaq != null && nasdaq.isTradingHalt()) {
            sb.append("🚫 글로벌 악재로 매수 보류 권장. ");
        }

        if (score >= 50) {
            sb.append("✅ 강한 매수 신호. 적극 매수 고려.");
        } else if (score >= 20) {
            sb.append("👍 매수 우위. 분할 매수 고려.");
        } else if (score >= -20) {
            sb.append("➖ 중립. 관망 또는 소규모 진입.");
        } else if (score >= -50) {
            sb.append("👎 매도 우위. 신규 매수 자제.");
        } else {
            sb.append("⛔ 강한 매도 신호. 보유 종목 리스크 관리.");
        }

        return sb.toString();
    }

    // ========== 요청 DTO ==========

    @lombok.Data
    public static class DivergenceRequest {
        private List<BigDecimal> prices;
        private Integer lookbackPeriod;
    }
}
