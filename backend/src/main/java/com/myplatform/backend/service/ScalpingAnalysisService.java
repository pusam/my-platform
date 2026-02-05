package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.ScalpingAnalysisDto;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 단타(스캘핑) 분석 서비스
 * KIS API를 호출하여 체결강도, 프로그램 매매, 투자자 매매 정보를 조회
 */
@Service
@RequiredArgsConstructor
public class ScalpingAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ScalpingAnalysisService.class);

    private final KoreaInvestmentService kisService;

    /**
     * 종목 단타 분석 정보 조회 (전체)
     * @param stockCode 종목코드 (6자리)
     * @return 단타 분석 정보
     */
    public ScalpingAnalysisDto getScalpingAnalysis(String stockCode) {
        log.info("[단타분석] 종목 {} 분석 시작", stockCode);

        ScalpingAnalysisDto.ScalpingAnalysisDtoBuilder builder = ScalpingAnalysisDto.builder()
                .stockCode(stockCode)
                .fetchedAt(LocalDateTime.now());

        // 1. 현재가 및 체결강도 조회 (FHKST01010100)
        JsonNode priceData = kisService.getStockPrice(stockCode);
        if (priceData != null && "0".equals(getFieldValue(priceData, "rt_cd"))) {
            parseStockPrice(priceData, builder);
        } else {
            log.warn("[단타분석] 현재가 조회 실패: {}", stockCode);
        }

        // 2. 프로그램 매매 조회 (FHKST01010700)
        JsonNode programData = kisService.getProgramTrading(stockCode);
        if (programData != null && "0".equals(getFieldValue(programData, "rt_cd"))) {
            parseProgramTrading(programData, builder);
        } else {
            log.warn("[단타분석] 프로그램 매매 조회 실패: {}", stockCode);
        }

        // 3. 투자자별 매매동향 조회 (FHKST01010900)
        JsonNode investorData = kisService.getInvestorTrading(stockCode);
        if (investorData != null && "0".equals(getFieldValue(investorData, "rt_cd"))) {
            parseInvestorTrading(investorData, builder);
        } else {
            log.warn("[단타분석] 투자자 매매 조회 실패: {}", stockCode);
        }

        ScalpingAnalysisDto result = builder.build();

        // 신호 및 추세 계산
        result.setVolumeSignal(ScalpingAnalysisDto.calculateVolumeSignal(result.getVolumePower()));
        result.setProgramTrend(ScalpingAnalysisDto.calculateProgramTrend(result.getProgramNetBuy()));

        log.info("[단타분석] 종목 {} 분석 완료 - 체결강도: {}%, 프로그램: {}억",
                stockCode, result.getVolumePower(), result.getProgramNetBuy());

        return result;
    }

    /**
     * 체결강도만 빠르게 갱신 (자동 갱신용)
     * @param stockCode 종목코드
     * @return 체결강도 정보
     */
    public ScalpingAnalysisDto getVolumePowerRefresh(String stockCode) {
        log.debug("[단타분석] 종목 {} 체결강도 갱신", stockCode);

        ScalpingAnalysisDto.ScalpingAnalysisDtoBuilder builder = ScalpingAnalysisDto.builder()
                .stockCode(stockCode)
                .fetchedAt(LocalDateTime.now());

        // 현재가 및 체결강도만 조회
        JsonNode priceData = kisService.getStockPrice(stockCode);
        if (priceData != null && "0".equals(getFieldValue(priceData, "rt_cd"))) {
            parseStockPrice(priceData, builder);
        }

        ScalpingAnalysisDto result = builder.build();
        result.setVolumeSignal(ScalpingAnalysisDto.calculateVolumeSignal(result.getVolumePower()));

        return result;
    }

    /**
     * 현재가 데이터 파싱
     */
    private void parseStockPrice(JsonNode data, ScalpingAnalysisDto.ScalpingAnalysisDtoBuilder builder) {
        JsonNode output = data.get("output");
        if (output == null) {
            return;
        }

        // 종목명
        String stockName = getFieldValue(output, "hts_kor_isnm");
        if (stockName != null) {
            builder.stockName(stockName);
        }

        // 현재가
        String priceStr = getFieldValue(output, "stck_prpr");
        if (priceStr != null && !priceStr.isEmpty()) {
            builder.currentPrice(new BigDecimal(priceStr));
        }

        // 전일대비
        String changeStr = getFieldValue(output, "prdy_vrss");
        if (changeStr != null && !changeStr.isEmpty()) {
            builder.changePrice(new BigDecimal(changeStr));
        }

        // 등락률
        String rateStr = getFieldValue(output, "prdy_ctrt");
        if (rateStr != null && !rateStr.isEmpty()) {
            builder.changeRate(new BigDecimal(rateStr));
        }

        // 거래량
        String volumeStr = getFieldValue(output, "acml_vol");
        if (volumeStr != null && !volumeStr.isEmpty()) {
            builder.tradingVolume(Long.parseLong(volumeStr));
        }

        // 체결강도 (vol_tnrt: 체결강도)
        String volumePowerStr = getFieldValue(output, "vol_tnrt");
        if (volumePowerStr != null && !volumePowerStr.isEmpty()) {
            try {
                builder.volumePower(new BigDecimal(volumePowerStr));
            } catch (NumberFormatException e) {
                log.warn("[단타분석] 체결강도 파싱 실패: {}", volumePowerStr);
            }
        }
    }

    /**
     * 프로그램 매매 데이터 파싱
     */
    private void parseProgramTrading(JsonNode data, ScalpingAnalysisDto.ScalpingAnalysisDtoBuilder builder) {
        // output1: 당일 누적 정보
        JsonNode output1 = data.get("output1");
        if (output1 != null) {
            // 프로그램 순매수 금액 (ntby_tr_pbmn: 순매수거래대금)
            String netBuyStr = getFieldValue(output1, "ntby_tr_pbmn");
            if (netBuyStr != null && !netBuyStr.isEmpty()) {
                try {
                    // 원 단위 -> 억원 단위 변환
                    BigDecimal netBuy = new BigDecimal(netBuyStr)
                            .divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP);
                    builder.programNetBuy(netBuy);
                } catch (NumberFormatException e) {
                    log.warn("[단타분석] 프로그램 순매수 파싱 실패: {}", netBuyStr);
                }
            }
        }

        // output2: 시간대별 프로그램 매매 시계열
        JsonNode output2 = data.get("output2");
        if (output2 != null && output2.isArray()) {
            List<ScalpingAnalysisDto.ProgramTradingPoint> series = new ArrayList<>();

            for (JsonNode item : output2) {
                String timeStr = getFieldValue(item, "stck_cntg_hour");  // 체결시간 (HHMMSS)
                String netBuyStr = getFieldValue(item, "ntby_tr_pbmn");  // 순매수거래대금

                if (timeStr != null && timeStr.length() >= 4 && netBuyStr != null) {
                    try {
                        // 시간 포맷 변환 (HHMMSS -> HH:mm)
                        String formattedTime = timeStr.substring(0, 2) + ":" + timeStr.substring(2, 4);

                        // 원 단위 -> 억원 단위 변환
                        BigDecimal netBuy = new BigDecimal(netBuyStr)
                                .divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP);

                        series.add(ScalpingAnalysisDto.ProgramTradingPoint.builder()
                                .time(formattedTime)
                                .netBuyAmount(netBuy)
                                .build());
                    } catch (Exception e) {
                        // 파싱 실패는 무시
                    }
                }
            }

            // 시간순 정렬 (오래된 것부터)
            series.sort((a, b) -> a.getTime().compareTo(b.getTime()));
            builder.programTradingSeries(series);
        }
    }

    /**
     * 투자자 매매 데이터 파싱
     */
    private void parseInvestorTrading(JsonNode data, ScalpingAnalysisDto.ScalpingAnalysisDtoBuilder builder) {
        JsonNode output = data.get("output");
        if (output == null) {
            return;
        }

        // 외국인 순매수 (frgn_ntby_tr_pbmn)
        String foreignStr = getFieldValue(output, "frgn_ntby_tr_pbmn");
        if (foreignStr != null && !foreignStr.isEmpty()) {
            try {
                BigDecimal foreign = new BigDecimal(foreignStr)
                        .divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP);
                builder.foreignNetBuy(foreign);
            } catch (NumberFormatException e) {
                log.warn("[단타분석] 외국인 순매수 파싱 실패: {}", foreignStr);
            }
        }

        // 기관 순매수 (orgn_ntby_tr_pbmn)
        String instStr = getFieldValue(output, "orgn_ntby_tr_pbmn");
        if (instStr != null && !instStr.isEmpty()) {
            try {
                BigDecimal inst = new BigDecimal(instStr)
                        .divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP);
                builder.instNetBuy(inst);
            } catch (NumberFormatException e) {
                log.warn("[단타분석] 기관 순매수 파싱 실패: {}", instStr);
            }
        }
    }

    /**
     * JSON 노드에서 필드 값 추출
     */
    private String getFieldValue(JsonNode node, String fieldName) {
        if (node == null || !node.has(fieldName)) {
            return null;
        }
        return node.get(fieldName).asText();
    }
}
