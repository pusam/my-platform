package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.ScalpingAnalysisDto;
import com.myplatform.core.util.DateTimeUtil;
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
        log.info("[단타분석] ========== 종목 {} 분석 시작 ==========", stockCode);
        long startTime = System.currentTimeMillis();

        ScalpingAnalysisDto.ScalpingAnalysisDtoBuilder builder = ScalpingAnalysisDto.builder()
                .stockCode(stockCode)
                .fetchedAt(DateTimeUtil.kstNow());

        // 1. 현재가 및 체결강도 조회 (FHKST01010100)
        log.debug("[단타분석] 1. 현재가 조회 시작");
        JsonNode priceData = kisService.getStockPrice(stockCode);
        if (priceData != null && "0".equals(getFieldValue(priceData, "rt_cd"))) {
            parseStockPrice(priceData, builder);
        } else {
            String errCode = priceData != null ? getFieldValue(priceData, "rt_cd") : "null";
            String errMsg = priceData != null ? getFieldValue(priceData, "msg1") : "응답 없음";
            log.warn("[단타분석] 현재가 조회 실패: {} - 코드: {}, 메시지: {}", stockCode, errCode, errMsg);
        }

        // 2. 프로그램 매매 조회 (종목별 프로그램매매추이(체결) FHPPG04650101 — 2026-10-07 정정)
        log.debug("[단타분석] 2. 프로그램 매매 조회 시작");
        JsonNode programData = kisService.getProgramTrading(stockCode);
        if (programData != null && "0".equals(getFieldValue(programData, "rt_cd"))) {
            parseProgramTrading(programData, builder);
        } else {
            log.debug("[단타분석] 프로그램 매매 데이터 없음: {} (KIS API 미지원 가능)", stockCode);
        }

        // 3. 투자자별 매매동향 조회 (FHKST01010900)
        log.debug("[단타분석] 3. 투자자 매매 조회 시작");
        JsonNode investorData = kisService.getInvestorTrading(stockCode);
        if (investorData != null && "0".equals(getFieldValue(investorData, "rt_cd"))) {
            parseInvestorTrading(investorData, builder);
        } else {
            String errCode = investorData != null ? getFieldValue(investorData, "rt_cd") : "null";
            String errMsg = investorData != null ? getFieldValue(investorData, "msg1") : "응답 없음";
            log.warn("[단타분석] 투자자 매매 조회 실패: {} - 코드: {}, 메시지: {}", stockCode, errCode, errMsg);
        }

        ScalpingAnalysisDto result = builder.build();

        // 체결강도 폴백 — 현재가 시세(FHKST01010100) 응답엔 체결강도 필드가 없어 항상 null 이던
        // 근본 원인 수정: 체결 API(FHKST01010300)의 tday_rltv 로 보충. 실패 시 null 유지(100 위장 금지).
        if (result.getVolumePower() == null) {
            result.setVolumePower(getCcnlVolumePower(stockCode));
        }

        // 신호 및 추세 계산
        result.setVolumeSignal(ScalpingAnalysisDto.calculateVolumeSignal(result.getVolumePower()));
        result.setProgramTrend(ScalpingAnalysisDto.calculateProgramTrend(result.getProgramNetBuy()));

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[단타분석] ========== 종목 {} 분석 완료 ({}ms) ==========", stockCode, elapsed);
        log.info("[단타분석] 결과 - 체결강도: {}%, 외인: {}억, 기관: {}억, 프로그램: {}억, 시계열: {}건",
                result.getVolumePower(),
                result.getForeignNetBuy(),
                result.getInstNetBuy(),
                result.getProgramNetBuy(),
                result.getProgramTradingSeries() != null ? result.getProgramTradingSeries().size() : 0);

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
                .fetchedAt(DateTimeUtil.kstNow());

        // 현재가 및 체결강도만 조회
        JsonNode priceData = kisService.getStockPrice(stockCode);
        if (priceData != null && "0".equals(getFieldValue(priceData, "rt_cd"))) {
            parseStockPrice(priceData, builder);
        }

        ScalpingAnalysisDto result = builder.build();
        if (result.getVolumePower() == null) {
            result.setVolumePower(getCcnlVolumePower(stockCode));
        }
        result.setVolumeSignal(ScalpingAnalysisDto.calculateVolumeSignal(result.getVolumePower()));

        return result;
    }

    /**
     * 체결강도 단독 조회 — 체결 API(FHKST01010300) 의 tday_rltv(당일 체결강도).
     * 현재가 시세 응답에 체결강도 필드가 없을 때의 폴백 + 장 마감 후 당일 최종 체결강도 조회용.
     * 실패/데이터 없음이면 null (호출측은 "데이터 없음"으로 표시 — 100 위장 금지).
     */
    public BigDecimal getCcnlVolumePower(String stockCode) {
        try {
            JsonNode ccnl = kisService.getStockCcnl(stockCode);
            if (ccnl == null || !"0".equals(getFieldValue(ccnl, "rt_cd"))) {
                return null;
            }
            return parseCcnlVolumePower(ccnl);
        } catch (Exception e) {
            log.debug("[단타분석] 체결 API 체결강도 조회 실패 ({}): {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * 체결 API 응답에서 최신 체결강도(tday_rltv) 추출 — output 배열(최신순) 첫 유효값.
     * 순수 함수 (테스트 대상).
     */
    static BigDecimal parseCcnlVolumePower(JsonNode response) {
        if (response == null) return null;
        JsonNode output = response.get("output");
        if (output == null || !output.isArray()) return null;
        for (JsonNode tick : output) {
            JsonNode node = tick.get("tday_rltv");
            if (node == null || node.asText().isEmpty()) continue;
            try {
                BigDecimal power = new BigDecimal(node.asText());
                if (power.signum() > 0) return power;
            } catch (NumberFormatException ignore) {
                // 다음 체결 항목 시도
            }
        }
        return null;
    }

    /**
     * 현재가 데이터 파싱
     */
    private void parseStockPrice(JsonNode data, ScalpingAnalysisDto.ScalpingAnalysisDtoBuilder builder) {
        JsonNode output = data.get("output");
        if (output == null) {
            log.warn("[단타분석] output이 null");
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

        // ★★★ 체결강도 파싱 (수정됨) ★★★
        // 1순위: tday_rltv (당일 상대강도 = 체결강도)
        // 2순위: seln_cnqn_smtn, shnu_cnqn_smtn으로 계산
        // 3순위: vol_tnrt는 거래량회전율이므로 사용하지 않음
        BigDecimal volumePower = parseVolumePower(output);
        if (volumePower != null) {
            builder.volumePower(volumePower);
            log.debug("[단타분석] 체결강도: {}%", volumePower);
        }
    }

    /**
     * 체결강도 계산 (매수체결량 / 매도체결량 * 100)
     *
     * KIS API 필드:
     * - seln_cnqn_smtn: 매도 체결수량 합계
     * - shnu_cnqn_smtn: 매수 체결수량 합계
     * - tday_rltv: 당일 상대강도 (이미 계산된 값)
     */
    private BigDecimal parseVolumePower(JsonNode output) {
        // 1순위: 당일 상대강도 (tday_rltv) - 이미 계산된 체결강도
        String relativeStrength = getFieldValue(output, "tday_rltv");
        if (relativeStrength != null && !relativeStrength.isEmpty()) {
            try {
                BigDecimal power = new BigDecimal(relativeStrength);
                if (power.compareTo(BigDecimal.ZERO) > 0) {
                    log.debug("[단타분석] tday_rltv 사용: {}", power);
                    return power;
                }
            } catch (NumberFormatException e) {
                log.debug("[단타분석] tday_rltv 파싱 실패: {}", relativeStrength);
            }
        }

        // 2순위: 매수/매도 체결수량으로 직접 계산
        String buyVolumeStr = getFieldValue(output, "shnu_cnqn_smtn");  // 매수체결수량
        String sellVolumeStr = getFieldValue(output, "seln_cnqn_smtn"); // 매도체결수량

        if (buyVolumeStr != null && sellVolumeStr != null &&
            !buyVolumeStr.isEmpty() && !sellVolumeStr.isEmpty()) {
            try {
                BigDecimal buyVolume = new BigDecimal(buyVolumeStr);
                BigDecimal sellVolume = new BigDecimal(sellVolumeStr);

                if (sellVolume.compareTo(BigDecimal.ZERO) > 0) {
                    // 체결강도 = (매수체결량 / 매도체결량) * 100
                    BigDecimal power = buyVolume
                            .divide(sellVolume, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"))
                            .setScale(2, RoundingMode.HALF_UP);
                    log.debug("[단타분석] 체결강도 계산: 매수={}, 매도={}, 강도={}%",
                            buyVolume, sellVolume, power);
                    return power;
                }
            } catch (NumberFormatException e) {
                log.debug("[단타분석] 체결수량 파싱 실패");
            }
        }

        // 3순위: 체결강도 데이터 없음 → null 반환 (0이 아님!)
        log.debug("[단타분석] 체결강도 데이터 없음 - 매수: {}, 매도: {}", buyVolumeStr, sellVolumeStr);
        return null;
    }

    /** 프로그램 매매 금액 단위 로그 — 처음 판정했을 때·판정 못 했을 때 한 번씩(§5 상태가 바뀐 순간만). */
    private final java.util.concurrent.atomic.AtomicReference<String> programUnitLogged =
            new java.util.concurrent.atomic.AtomicReference<>("");

    /**
     * 프로그램 매매 데이터 파싱 — 해석은 {@link ProgramTradeRows}(공식 샘플 필드, 단위는 응답 안에서 판정).
     * 예전엔 응답에 없는 output1.ntby_tr_pbmn·output2[] 를 읽어 값이 한 번도 나오지 않았다(2026-10-07).
     */
    private void parseProgramTrading(JsonNode data, ScalpingAnalysisDto.ScalpingAnalysisDtoBuilder builder) {
        ProgramTradeRows.Parsed parsed = ProgramTradeRows.parse(data);
        if (parsed.netBuyEok() != null) builder.programNetBuy(parsed.netBuyEok());
        if (!parsed.series().isEmpty()) builder.programTradingSeries(parsed.series());
        if (parsed.unitRatio() == null) return;
        String state = parsed.netBuyEok() != null ? "OK" : "UNKNOWN";
        if (!state.equals(programUnitLogged.getAndSet(state))) {
            if (parsed.netBuyEok() != null) {
                log.info("[단타분석] 프로그램 매매 금액 단위 판정 — 매수 거래대금 ÷ (매수 거래량 × 현재가) = {} ({})",
                        parsed.unitRatio(), parsed.unitRatio() > 0.1 ? "원" : "백만원");
            } else {
                // 모르는 값은 만들지 않는다(§4c) — 대신 조용히 넘기지 않는다
                log.warn("[단타분석] 프로그램 매매 금액 단위를 판정하지 못해 값을 비운다 — 매수 거래대금 ÷ (매수 거래량 × 현재가) = {}",
                        parsed.unitRatio());
            }
        }
    }

    /**
     * 투자자 매매 데이터 파싱 — 오늘 행만, 장 마감 뒤에만(2026-10-03, {@link #todayInvestorNetEok}).
     */
    private void parseInvestorTrading(JsonNode data, ScalpingAnalysisDto.ScalpingAnalysisDtoBuilder builder) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean afterClose = now.toLocalTime().isAfter(INVESTOR_DATA_READY_AFTER);
        InvestorNet net = todayInvestorNetEok(data.get("output"), now.toLocalDate(), afterClose);
        builder.foreignNetBuy(net.foreignEok());
        builder.instNetBuy(net.institutionEok());
        log.debug("[단타분석] 투자자 순매수(오늘·마감 후만) 외국인 {}억 · 기관 {}억", net.foreignEok(), net.institutionEok());
    }

    /** 당일 투자자 데이터가 나오는 시각 — KIS: "당일 데이터는 장 종료 후 제공"(공식 샘플 inquire_investor.py). KRX 종료 15:40 기준. */
    static final java.time.LocalTime INVESTOR_DATA_READY_AFTER = java.time.LocalTime.of(15, 40);

    /** 외국인·기관 순매수(억원). 모르면 null. */
    record InvestorNet(BigDecimal foreignEok, BigDecimal institutionEok) {}

    /**
     * 주식현재가 투자자(FHKST01010900) 응답의 오늘 순매수 — 순수(테스트 대상).
     *
     * <p>응답 {@code output} 은 <b>일자별 행 배열</b>(최근순, 행마다 {@code stck_bsop_date} — 공식 샘플
     * chk_inquire_investor.py COLUMN_MAPPING)이다. 예전엔 객체로 읽어 필드를 못 찾아 외국인·기관이 늘 null 이었다.
     * 금액({@code *_ntby_tr_pbmn})은 <b>백만원</b> 단위라 억원은 ÷100 — 예전엔 원으로 보고 ÷1억 했다(여러 구현이 KIS 문서의
     * '단위: 백만원'을 인용, 운영 응답으로 대조는 아직).
     * <ul>
     *   <li>오늘({@code today}) 행만 쓴다 — 어제 행을 오늘처럼 쓰지 않는다.</li>
     *   <li>장 마감 전이면 모름 — 당일 데이터는 장 종료 후 제공된다(장중의 0 은 '균형'이 아니다).</li>
     *   <li>칸이 비었거나 숫자가 아니면 그 칸만 모름.</li>
     * </ul>
     */
    static InvestorNet todayInvestorNetEok(JsonNode output, java.time.LocalDate today, boolean afterClose) {
        if (output == null || !output.isArray() || !afterClose || today == null) return new InvestorNet(null, null);
        String ymd = today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        for (JsonNode row : output) {
            if (!ymd.equals(row.path("stck_bsop_date").asText(""))) continue;
            return new InvestorNet(millionWonToEok(row.path("frgn_ntby_tr_pbmn").asText("")),
                    millionWonToEok(row.path("orgn_ntby_tr_pbmn").asText("")));
        }
        return new InvestorNet(null, null);
    }

    private static BigDecimal millionWonToEok(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.trim()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
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
