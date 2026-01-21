package com.myplatform.backend.controller;

import com.myplatform.backend.dto.InvestorTradingDto;
import com.myplatform.backend.service.InvestorTradingService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.myplatform.backend.config.SectorStockConfig;

import java.util.*;
import java.util.stream.Collectors;

@Tag(name = "투자자 매매동향", description = "외국인/기관/프로그램 순매수 추적 API")
@RestController
@RequestMapping("/api/investor")
@SecurityRequirement(name = "JWT Bearer")
public class InvestorTradingController {

    private final InvestorTradingService investorTradingService;
    private final SectorStockConfig sectorConfig;

    // 기본 관심 종목 (대형주 위주)
    private static final List<String> DEFAULT_STOCKS = Arrays.asList(
            "005930", // 삼성전자
            "000660", // SK하이닉스
            "373220", // LG에너지솔루션
            "207940", // 삼성바이오로직스
            "006400", // 삼성SDI
            "005380", // 현대차
            "035420", // NAVER
            "000270", // 기아
            "068270", // 셀트리온
            "012330"  // 현대모비스
    );

    public InvestorTradingController(InvestorTradingService investorTradingService, SectorStockConfig sectorConfig) {
        this.investorTradingService = investorTradingService;
        this.sectorConfig = sectorConfig;
    }

    @Operation(summary = "종목 투자자 동향 조회", description = "특정 종목의 외국인/기관/프로그램 순매수를 조회합니다.")
    @GetMapping("/{stockCode}")
    public ResponseEntity<ApiResponse<InvestorTradingDto>> getInvestorTrading(
            @Parameter(description = "종목코드 (예: 005930)")
            @PathVariable String stockCode) {
        try {
            InvestorTradingDto result = investorTradingService.getInvestorTrading(stockCode);
            if (result == null) {
                return ResponseEntity.ok(ApiResponse.fail("종목 정보를 찾을 수 없습니다."));
            }
            return ResponseEntity.ok(ApiResponse.success("투자자 동향 조회 성공", result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "주요 종목 수급 현황", description = "주요 종목들의 외국인/기관 순매수 현황을 조회합니다.")
    @GetMapping("/top")
    public ResponseEntity<ApiResponse<List<InvestorTradingDto>>> getTopStocksTrading() {
        try {
            List<InvestorTradingDto> results = investorTradingService.getMultipleStocksTrading(DEFAULT_STOCKS);
            return ResponseEntity.ok(ApiResponse.success("수급 현황 조회 성공", results));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "관심 종목 수급 조회", description = "지정한 종목들의 수급 현황을 조회합니다.")
    @PostMapping("/watchlist")
    public ResponseEntity<ApiResponse<List<InvestorTradingDto>>> getWatchlistTrading(
            @Parameter(description = "종목코드 목록")
            @RequestBody List<String> stockCodes) {
        try {
            if (stockCodes == null || stockCodes.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.fail("종목 코드를 입력해주세요."));
            }
            if (stockCodes.size() > 20) {
                return ResponseEntity.ok(ApiResponse.fail("최대 20개 종목까지 조회 가능합니다."));
            }
            List<InvestorTradingDto> results = investorTradingService.getMultipleStocksTrading(stockCodes);
            return ResponseEntity.ok(ApiResponse.success("관심 종목 수급 조회 성공", results));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "시계열 캐시 초기화", description = "특정 종목의 시계열 데이터를 초기화합니다.")
    @DeleteMapping("/{stockCode}/cache")
    public ResponseEntity<ApiResponse<Void>> clearCache(
            @PathVariable String stockCode) {
        investorTradingService.clearTimeSeriesCache(stockCode);
        return ResponseEntity.ok(ApiResponse.success("캐시 초기화 완료", null));
    }

    @Operation(summary = "수급 순위 조회", description = "전체 섹터 종목의 외국인+기관 순매수 순위를 조회합니다.")
    @GetMapping("/ranking")
    public ResponseEntity<ApiResponse<List<InvestorTradingDto>>> getInvestorRanking(
            @Parameter(description = "섹터코드 (미지정 시 전체)")
            @RequestParam(required = false) String sector,
            @Parameter(description = "정렬기준 (TOTAL: 외국인+기관, FOREIGN: 외국인, INSTITUTION: 기관)")
            @RequestParam(defaultValue = "TOTAL") String sortBy) {
        try {
            // 종목 코드 수집
            Set<String> stockCodes = new LinkedHashSet<>();
            if (sector != null && !sector.isEmpty()) {
                var sectorInfo = sectorConfig.getSector(sector);
                if (sectorInfo != null) {
                    stockCodes.addAll(sectorInfo.getStockCodes());
                }
            } else {
                // 전체 섹터 종목 (중복 제거)
                for (var sectorInfo : sectorConfig.getAllSectors()) {
                    stockCodes.addAll(sectorInfo.getStockCodes());
                }
            }

            // 수급 데이터 조회
            List<InvestorTradingDto> results = investorTradingService.getMultipleStocksTrading(new ArrayList<>(stockCodes));

            // 정렬
            results.sort((a, b) -> {
                double aVal = 0, bVal = 0;
                switch (sortBy.toUpperCase()) {
                    case "FOREIGN":
                        aVal = a.getForeignNetBuy() != null ? a.getForeignNetBuy().doubleValue() : 0;
                        bVal = b.getForeignNetBuy() != null ? b.getForeignNetBuy().doubleValue() : 0;
                        break;
                    case "INSTITUTION":
                        aVal = a.getInstitutionNetBuy() != null ? a.getInstitutionNetBuy().doubleValue() : 0;
                        bVal = b.getInstitutionNetBuy() != null ? b.getInstitutionNetBuy().doubleValue() : 0;
                        break;
                    default: // TOTAL
                        aVal = (a.getForeignNetBuy() != null ? a.getForeignNetBuy().doubleValue() : 0)
                             + (a.getInstitutionNetBuy() != null ? a.getInstitutionNetBuy().doubleValue() : 0);
                        bVal = (b.getForeignNetBuy() != null ? b.getForeignNetBuy().doubleValue() : 0)
                             + (b.getInstitutionNetBuy() != null ? b.getInstitutionNetBuy().doubleValue() : 0);
                }
                return Double.compare(bVal, aVal); // 내림차순
            });

            return ResponseEntity.ok(ApiResponse.success("수급 순위 조회 성공", results));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "수급 이상 종목 탐지", description = "프로그램 순매수가 쌓이는데 주가 횡보인 종목 등 이상 패턴을 탐지합니다.")
    @GetMapping("/anomaly")
    public ResponseEntity<ApiResponse<Map<String, List<InvestorTradingDto>>>> getAnomalyStocks() {
        try {
            // 전체 섹터 종목
            Set<String> stockCodes = new LinkedHashSet<>();
            for (var sectorInfo : sectorConfig.getAllSectors()) {
                stockCodes.addAll(sectorInfo.getStockCodes());
            }

            List<InvestorTradingDto> all = investorTradingService.getMultipleStocksTrading(new ArrayList<>(stockCodes));

            Map<String, List<InvestorTradingDto>> anomalies = new LinkedHashMap<>();

            // 1. 프로그램 순매수 쌓이는데 주가 횡보 (-1% ~ 1%)
            List<InvestorTradingDto> programAccumulating = all.stream()
                .filter(s -> s.getProgramNetBuy() != null && s.getProgramNetBuy().doubleValue() > 5) // 5억 이상 순매수
                .filter(s -> s.getChangeRate() != null && Math.abs(s.getChangeRate().doubleValue()) < 1.5) // 주가 횡보
                .sorted((a, b) -> b.getProgramNetBuy().compareTo(a.getProgramNetBuy()))
                .limit(10)
                .collect(Collectors.toList());
            anomalies.put("programAccumulating", programAccumulating);

            // 2. 외국인+기관 쌍끌이 매수
            List<InvestorTradingDto> dualBuying = all.stream()
                .filter(s -> s.getForeignNetBuy() != null && s.getForeignNetBuy().doubleValue() > 5)
                .filter(s -> s.getInstitutionNetBuy() != null && s.getInstitutionNetBuy().doubleValue() > 5)
                .sorted((a, b) -> {
                    double aSum = a.getForeignNetBuy().doubleValue() + a.getInstitutionNetBuy().doubleValue();
                    double bSum = b.getForeignNetBuy().doubleValue() + b.getInstitutionNetBuy().doubleValue();
                    return Double.compare(bSum, aSum);
                })
                .limit(10)
                .collect(Collectors.toList());
            anomalies.put("dualBuying", dualBuying);

            // 3. 외국인 대량 매집 (20억 이상)
            List<InvestorTradingDto> foreignHeavy = all.stream()
                .filter(s -> s.getForeignNetBuy() != null && s.getForeignNetBuy().doubleValue() > 20)
                .sorted((a, b) -> b.getForeignNetBuy().compareTo(a.getForeignNetBuy()))
                .limit(10)
                .collect(Collectors.toList());
            anomalies.put("foreignHeavy", foreignHeavy);

            // 4. 개인 역행 (개인은 파는데 외국인+기관 매수)
            List<InvestorTradingDto> retailContrarian = all.stream()
                .filter(s -> s.getIndividualNetBuy() != null && s.getIndividualNetBuy().doubleValue() < -10)
                .filter(s -> {
                    double bigMoney = (s.getForeignNetBuy() != null ? s.getForeignNetBuy().doubleValue() : 0)
                                    + (s.getInstitutionNetBuy() != null ? s.getInstitutionNetBuy().doubleValue() : 0);
                    return bigMoney > 5;
                })
                .sorted((a, b) -> a.getIndividualNetBuy().compareTo(b.getIndividualNetBuy()))
                .limit(10)
                .collect(Collectors.toList());
            anomalies.put("retailContrarian", retailContrarian);

            return ResponseEntity.ok(ApiResponse.success("이상 종목 탐지 완료", anomalies));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("조회 실패: " + e.getMessage()));
        }
    }
}
