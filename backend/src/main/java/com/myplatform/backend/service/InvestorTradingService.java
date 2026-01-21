package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.InvestorTradingDto;
import com.myplatform.backend.dto.InvestorTradingDto.TimeSeriesData;
import com.myplatform.backend.dto.StockPriceDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 투자자별 매매동향 서비스
 * - 외국인/기관/개인 순매수 추적
 * - 프로그램 매매 추이 분석
 * - 수급 신호 판단
 */
@Service
public class InvestorTradingService {

    private static final Logger log = LoggerFactory.getLogger(InvestorTradingService.class);

    private final KoreaInvestmentService kisService;
    private final StockPriceService stockPriceService;

    // 관심종목별 시계열 데이터 캐시 (종목코드 -> 시간대별 데이터)
    private final Map<String, List<TimeSeriesData>> timeSeriesCache = new ConcurrentHashMap<>();

    // 캐시 (1분)
    private final Map<String, InvestorTradingDto> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheTime = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 60 * 1000;

    public InvestorTradingService(KoreaInvestmentService kisService, StockPriceService stockPriceService) {
        this.kisService = kisService;
        this.stockPriceService = stockPriceService;
    }

    /**
     * 종목의 투자자별 매매동향 조회
     */
    public InvestorTradingDto getInvestorTrading(String stockCode) {
        // 캐시 확인
        Long lastTime = cacheTime.get(stockCode);
        if (lastTime != null && System.currentTimeMillis() - lastTime < CACHE_DURATION_MS) {
            return cache.get(stockCode);
        }

        InvestorTradingDto dto = new InvestorTradingDto();
        dto.setStockCode(stockCode);
        dto.setFetchedAt(LocalDateTime.now());

        // 1. 현재가 정보 조회
        StockPriceDto priceInfo = stockPriceService.getStockPrice(stockCode);
        if (priceInfo != null) {
            dto.setStockName(priceInfo.getStockName());
            dto.setCurrentPrice(priceInfo.getCurrentPrice());
            dto.setChangeRate(priceInfo.getChangeRate());
        }

        // 2. 투자자별 매매동향 조회 (한투 API)
        if (kisService.isConfigured()) {
            fetchInvestorData(stockCode, dto);
            fetchProgramData(stockCode, dto);
        } else {
            // API 미설정 시 더미 데이터
            setDummyData(dto);
        }

        // 3. 수급 신호 판단
        analyzeSignal(dto);

        // 4. 시계열 데이터 업데이트
        updateTimeSeries(stockCode, dto);
        dto.setTimeSeries(timeSeriesCache.getOrDefault(stockCode, new ArrayList<>()));

        // 캐시 저장
        cache.put(stockCode, dto);
        cacheTime.put(stockCode, System.currentTimeMillis());

        return dto;
    }

    /**
     * 투자자별 매매동향 API 데이터 파싱
     */
    private void fetchInvestorData(String stockCode, InvestorTradingDto dto) {
        try {
            JsonNode response = kisService.getInvestorTrading(stockCode);
            if (response == null) return;

            String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText() : "";
            if (!"0".equals(rtCd)) {
                log.warn("투자자 동향 API 오류: {}", response.has("msg1") ? response.get("msg1").asText() : "");
                return;
            }

            JsonNode output = response.get("output");
            if (output == null || !output.isArray() || output.size() == 0) return;

            // output 배열에서 투자자별 데이터 추출
            // 투자자 구분: 1=개인, 2=외국인, 3=기관
            for (JsonNode item : output) {
                String invtType = getTextValue(item, "prsn_ntby_qty") != null ? "data" : "";

                // 개인 순매수량/금액
                dto.setIndividualNetVolume(getLongValue(item, "prsn_ntby_qty"));
                dto.setIndividualNetBuy(getBigDecimalValue(item, "prsn_ntby_tr_pbmn").divide(BigDecimal.valueOf(100000000), 2, RoundingMode.HALF_UP));

                // 외국인 순매수량/금액
                dto.setForeignNetVolume(getLongValue(item, "frgn_ntby_qty"));
                dto.setForeignNetBuy(getBigDecimalValue(item, "frgn_ntby_tr_pbmn").divide(BigDecimal.valueOf(100000000), 2, RoundingMode.HALF_UP));

                // 기관 순매수량/금액
                dto.setInstitutionNetVolume(getLongValue(item, "orgn_ntby_qty"));
                dto.setInstitutionNetBuy(getBigDecimalValue(item, "orgn_ntby_tr_pbmn").divide(BigDecimal.valueOf(100000000), 2, RoundingMode.HALF_UP));

                break; // 첫 번째 데이터만 사용
            }

        } catch (Exception e) {
            log.error("투자자 동향 파싱 실패 [{}]: {}", stockCode, e.getMessage());
        }
    }

    /**
     * 프로그램 매매 데이터 파싱
     */
    private void fetchProgramData(String stockCode, InvestorTradingDto dto) {
        try {
            JsonNode response = kisService.getProgramTrading(stockCode);
            if (response == null) return;

            String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText() : "";
            if (!"0".equals(rtCd)) return;

            JsonNode output = response.get("output");
            if (output == null) return;

            // 프로그램 순매수 금액 (억원 단위 변환)
            BigDecimal programNetAmt = getBigDecimalValue(output, "ntby_qty");
            if (dto.getCurrentPrice() != null && programNetAmt != null) {
                BigDecimal programNetBuy = programNetAmt.multiply(dto.getCurrentPrice())
                        .divide(BigDecimal.valueOf(100000000), 2, RoundingMode.HALF_UP);
                dto.setProgramNetBuy(programNetBuy);
            }
            dto.setProgramNetVolume(getLongValue(output, "ntby_qty"));

        } catch (Exception e) {
            log.error("프로그램 매매 파싱 실패 [{}]: {}", stockCode, e.getMessage());
        }
    }

    /**
     * 수급 신호 분석
     */
    private void analyzeSignal(InvestorTradingDto dto) {
        BigDecimal foreign = dto.getForeignNetBuy() != null ? dto.getForeignNetBuy() : BigDecimal.ZERO;
        BigDecimal institution = dto.getInstitutionNetBuy() != null ? dto.getInstitutionNetBuy() : BigDecimal.ZERO;
        BigDecimal program = dto.getProgramNetBuy() != null ? dto.getProgramNetBuy() : BigDecimal.ZERO;

        // 외국인 + 기관 합산
        BigDecimal bigMoney = foreign.add(institution);

        List<String> reasons = new ArrayList<>();

        // 매수 신호 조건
        if (bigMoney.compareTo(BigDecimal.valueOf(10)) > 0) {
            reasons.add("외국인+기관 " + bigMoney.setScale(0, RoundingMode.HALF_UP) + "억 순매수");
        }
        if (program.compareTo(BigDecimal.valueOf(5)) > 0) {
            reasons.add("프로그램 " + program.setScale(0, RoundingMode.HALF_UP) + "억 순매수");
        }
        if (foreign.compareTo(BigDecimal.valueOf(20)) > 0) {
            reasons.add("외국인 대량 매집 중");
        }

        // 매도 신호 조건
        List<String> sellReasons = new ArrayList<>();
        if (bigMoney.compareTo(BigDecimal.valueOf(-10)) < 0) {
            sellReasons.add("외국인+기관 " + bigMoney.abs().setScale(0, RoundingMode.HALF_UP) + "억 순매도");
        }
        if (foreign.compareTo(BigDecimal.valueOf(-20)) < 0) {
            sellReasons.add("외국인 대량 이탈 중");
        }

        // 신호 결정
        if (!reasons.isEmpty()) {
            dto.setSignal("BUY_SIGNAL");
            dto.setSignalReason(String.join(", ", reasons));
        } else if (!sellReasons.isEmpty()) {
            dto.setSignal("SELL_SIGNAL");
            dto.setSignalReason(String.join(", ", sellReasons));
        } else {
            dto.setSignal("NEUTRAL");
            dto.setSignalReason("뚜렷한 수급 신호 없음");
        }
    }

    /**
     * 시계열 데이터 업데이트 (차트용)
     */
    private void updateTimeSeries(String stockCode, InvestorTradingDto dto) {
        List<TimeSeriesData> series = timeSeriesCache.computeIfAbsent(stockCode, k -> new ArrayList<>());

        // 현재 시간 데이터 추가
        String now = String.format("%02d:%02d",
                LocalDateTime.now().getHour(),
                LocalDateTime.now().getMinute());

        TimeSeriesData data = new TimeSeriesData(
                now,
                dto.getForeignNetBuy(),
                dto.getInstitutionNetBuy(),
                dto.getProgramNetBuy(),
                dto.getCurrentPrice()
        );

        // 같은 시간대 데이터가 있으면 갱신, 없으면 추가
        boolean updated = false;
        for (int i = 0; i < series.size(); i++) {
            if (series.get(i).getTime().equals(now)) {
                series.set(i, data);
                updated = true;
                break;
            }
        }
        if (!updated) {
            series.add(data);
        }

        // 최대 60개 유지 (약 1시간치)
        while (series.size() > 60) {
            series.remove(0);
        }
    }

    /**
     * API 미설정 시 더미 데이터
     */
    private void setDummyData(InvestorTradingDto dto) {
        Random random = new Random();
        dto.setForeignNetBuy(BigDecimal.valueOf(random.nextInt(100) - 50));
        dto.setInstitutionNetBuy(BigDecimal.valueOf(random.nextInt(60) - 30));
        dto.setIndividualNetBuy(BigDecimal.valueOf(random.nextInt(80) - 40));
        dto.setProgramNetBuy(BigDecimal.valueOf(random.nextInt(40) - 20));
        dto.setForeignNetVolume((long) (random.nextInt(1000000) - 500000));
        dto.setInstitutionNetVolume((long) (random.nextInt(500000) - 250000));
        dto.setIndividualNetVolume((long) (random.nextInt(800000) - 400000));
        dto.setProgramNetVolume((long) (random.nextInt(300000) - 150000));
    }

    /**
     * 여러 종목의 투자자 동향 조회
     */
    public List<InvestorTradingDto> getMultipleStocksTrading(List<String> stockCodes) {
        List<InvestorTradingDto> results = new ArrayList<>();
        for (String code : stockCodes) {
            InvestorTradingDto dto = getInvestorTrading(code);
            if (dto != null) {
                results.add(dto);
            }
        }
        // 외국인+기관 순매수 금액 순 정렬
        results.sort((a, b) -> {
            BigDecimal aSum = (a.getForeignNetBuy() != null ? a.getForeignNetBuy() : BigDecimal.ZERO)
                    .add(a.getInstitutionNetBuy() != null ? a.getInstitutionNetBuy() : BigDecimal.ZERO);
            BigDecimal bSum = (b.getForeignNetBuy() != null ? b.getForeignNetBuy() : BigDecimal.ZERO)
                    .add(b.getInstitutionNetBuy() != null ? b.getInstitutionNetBuy() : BigDecimal.ZERO);
            return bSum.compareTo(aSum);
        });
        return results;
    }

    /**
     * 시계열 캐시 초기화
     */
    public void clearTimeSeriesCache(String stockCode) {
        timeSeriesCache.remove(stockCode);
    }

    private String getTextValue(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    private BigDecimal getBigDecimalValue(JsonNode node, String field) {
        if (!node.has(field)) return BigDecimal.ZERO;
        String value = node.get(field).asText().replace(",", "");
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private Long getLongValue(JsonNode node, String field) {
        if (!node.has(field)) return 0L;
        String value = node.get(field).asText().replace(",", "");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
