package com.myplatform.backend.controller;

import com.myplatform.backend.dto.LottoAnalysisDto;
import com.myplatform.backend.dto.LottoDrawDto;
import com.myplatform.backend.service.LottoAnalyzerService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 로또 분석기 API 컨트롤러
 */
@Tag(name = "로또 분석기", description = "통계 기반 로또 번호 추천 API")
@RestController
@RequestMapping("/api/lotto")
@RequiredArgsConstructor
@Slf4j
public class LottoController {

    private final LottoAnalyzerService lottoAnalyzerService;

    @Operation(summary = "로또 번호 분석 및 추천", description = "과거 당첨 데이터를 분석하여 5게임의 번호를 추천합니다.")
    @GetMapping("/analyze")
    public ResponseEntity<ApiResponse<LottoAnalysisDto>> analyzeAndRecommend() {
        log.info("[로또API] 분석 요청");

        LottoAnalysisDto result = lottoAnalyzerService.analyzeAndRecommend();

        if (result == null) {
            return ResponseEntity.ok(ApiResponse.error("로또 데이터 분석에 실패했습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(
                String.format("%d회차 기준 분석 완료, %d게임 추천",
                        result.getLatestDrawNo(), result.getRecommendations().size()),
                result
        ));
    }

    @Operation(summary = "최신 회차 조회", description = "가장 최근 로또 당첨 회차 번호를 조회합니다.")
    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<Integer>> getLatestDrawNo() {
        Integer latestDrawNo = lottoAnalyzerService.getLatestDrawNo();
        return ResponseEntity.ok(ApiResponse.success("최신 회차 조회 완료", latestDrawNo));
    }

    @Operation(summary = "특정 회차 당첨 정보 조회", description = "특정 회차의 당첨 번호 및 상세 정보를 조회합니다.")
    @GetMapping("/draw/{drawNo}")
    public ResponseEntity<ApiResponse<LottoDrawDto>> getDrawData(
            @Parameter(description = "조회할 회차 번호") @PathVariable int drawNo) {

        log.info("[로또API] {}회차 조회", drawNo);

        LottoDrawDto result = lottoAnalyzerService.getDrawData(drawNo);

        if (result == null) {
            return ResponseEntity.ok(ApiResponse.error(drawNo + "회차 데이터를 찾을 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(drawNo + "회차 당첨 정보", result));
    }

    @Operation(summary = "최근 N회차 당첨 정보 조회", description = "최근 N회차의 당첨 번호 목록을 조회합니다.")
    @GetMapping("/recent")
    public ResponseEntity<ApiResponse<List<LottoDrawDto>>> getRecentDraws(
            @Parameter(description = "조회할 회차 수 (기본: 10)") @RequestParam(defaultValue = "10") int count) {

        log.info("[로또API] 최근 {}회차 조회", count);

        if (count < 1 || count > 100) {
            return ResponseEntity.ok(ApiResponse.error("조회 범위는 1~100 사이여야 합니다."));
        }

        List<LottoDrawDto> result = lottoAnalyzerService.getRecentDraws(count);

        return ResponseEntity.ok(ApiResponse.success(
                String.format("최근 %d회차 당첨 정보", result.size()),
                result
        ));
    }

    @Operation(summary = "새로운 추천 번호 생성", description = "새로운 5게임의 추천 번호를 생성합니다 (재분석).")
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<LottoAnalysisDto>> generateNewRecommendations() {
        log.info("[로또API] 새 추천 번호 생성 요청");

        LottoAnalysisDto result = lottoAnalyzerService.analyzeAndRecommend();

        if (result == null) {
            return ResponseEntity.ok(ApiResponse.error("번호 생성에 실패했습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(
                "새로운 추천 번호 5게임이 생성되었습니다.",
                result
        ));
    }

    @Operation(summary = "금주의 추천 번호 조회", description = "매주 월요일 자동 생성되는 금주의 추천 번호를 조회합니다.")
    @GetMapping("/weekly")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getWeeklyRecommendation() {
        log.info("[로또API] 금주의 추천 번호 조회");

        LottoAnalysisDto result = lottoAnalyzerService.getWeeklyRecommendation();
        LocalDate generatedDate = lottoAnalyzerService.getWeeklyRecommendationDate();

        if (result == null) {
            return ResponseEntity.ok(ApiResponse.error("금주의 추천 번호를 생성할 수 없습니다."));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("generatedDate", generatedDate);
        response.put("analysis", result);

        return ResponseEntity.ok(ApiResponse.success(
                String.format("금주의 추천 번호 (%s 생성)", generatedDate),
                response
        ));
    }

    @Operation(summary = "금주의 추천 번호 갱신", description = "금주의 추천 번호를 강제로 새로 생성합니다.")
    @PostMapping("/weekly/refresh")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshWeeklyRecommendation() {
        log.info("[로또API] 금주의 추천 번호 갱신 요청");

        LottoAnalysisDto result = lottoAnalyzerService.refreshWeeklyRecommendation();
        LocalDate generatedDate = lottoAnalyzerService.getWeeklyRecommendationDate();

        if (result == null) {
            return ResponseEntity.ok(ApiResponse.error("금주의 추천 번호 갱신에 실패했습니다."));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("generatedDate", generatedDate);
        response.put("analysis", result);

        return ResponseEntity.ok(ApiResponse.success(
                "금주의 추천 번호가 갱신되었습니다.",
                response
        ));
    }
}
