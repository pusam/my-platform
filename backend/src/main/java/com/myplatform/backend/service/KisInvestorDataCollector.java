package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 한국투자증권 API를 통한 투자자별 매매 데이터 수집 서비스
 * KoreaInvestmentService를 통해 API 호출
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KisInvestorDataCollector {

    private final InvestorDailyTradeRepository investorTradeRepository;
    private final KoreaInvestmentService koreaInvestmentService;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 장 마감 후 자동 수집 (평일 16:00)
     * 15:30 장 마감 후 30분 여유를 두고 수집
     */
    @Scheduled(cron = "0 0 16 * * MON-FRI")
    @Transactional
    public void scheduledDailyCollection() {
        LocalDate today = LocalDate.now();

        // 주말 체크 (cron에서도 체크하지만 이중 확인)
        if (today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return;
        }

        log.info("=== 장 마감 후 자동 수집 시작 ===");

        // 오늘 데이터가 이미 있는지 확인
        boolean hasData = investorTradeRepository.existsByMarketTypeAndInvestorTypeAndTradeDate(
                "KOSPI", "FOREIGN", today);

        if (hasData) {
            log.info("오늘({}) 데이터가 이미 존재합니다. 기존 데이터 삭제 후 재수집합니다.", today);
            investorTradeRepository.deleteByTradeDate(today);
        }

        Map<String, Integer> result = collectDailyInvestorTrades(today);
        int total = result.values().stream().mapToInt(Integer::intValue).sum();
        log.info("=== 장 마감 후 자동 수집 완료: {}건 ===", total);
    }

    /**
     * 특정 일자의 투자자별 매매 데이터 수집
     * KOSPI 상위 50종목의 투자자별 매매 데이터를 수집합니다.
     * KoreaInvestmentService를 통해 토큰 관리
     */
    public Map<String, Integer> collectDailyInvestorTrades(LocalDate tradeDate) {
        Map<String, Integer> result = new HashMap<>();

        try {
            String dateStr = tradeDate.format(DATE_FORMATTER);

            // 외국인 순매수 상위
            int foreignBuy = collectInvestorRanking("KOSPI", "FOREIGN", "BUY", dateStr, 50);
            result.put("KOSPI_FOREIGN_BUY", foreignBuy);

            // 외국인 순매도 상위
            int foreignSell = collectInvestorRanking("KOSPI", "FOREIGN", "SELL", dateStr, 50);
            result.put("KOSPI_FOREIGN_SELL", foreignSell);

            // 기관 순매수 상위
            int institutionBuy = collectInvestorRanking("KOSPI", "INSTITUTION", "BUY", dateStr, 50);
            result.put("KOSPI_INSTITUTION_BUY", institutionBuy);

            // 기관 순매도 상위
            int institutionSell = collectInvestorRanking("KOSPI", "INSTITUTION", "SELL", dateStr, 50);
            result.put("KOSPI_INSTITUTION_SELL", institutionSell);

            // 연기금 순매수 상위 (기관 API 응답에서 연기금 필드 추출)
            int pensionBuy = collectPensionRanking("KOSPI", "BUY", dateStr, 50);
            result.put("KOSPI_PENSION_BUY", pensionBuy);

            // 연기금 순매도 상위
            int pensionSell = collectPensionRanking("KOSPI", "SELL", dateStr, 50);
            result.put("KOSPI_PENSION_SELL", pensionSell);

            log.info("투자자별 매매 데이터 수집 완료: {}", result);

        } catch (Exception e) {
            log.error("투자자별 매매 데이터 수집 실패", e);
        }

        return result;
    }

    /**
     * 특정 투자자 유형의 순위 데이터 수집
     * KoreaInvestmentService를 통해 API 호출
     *
     * 주의: 이 API는 외국인(1)과 기관(2)만 지원합니다.
     *       개인(INDIVIDUAL)은 별도 처리가 필요합니다.
     */
    private int collectInvestorRanking(String market, String investorType, String tradeType,
                                       String dateStr, int limit) {
        // 개인(INDIVIDUAL)은 이 API에서 지원하지 않음
        if ("INDIVIDUAL".equals(investorType)) {
            log.info("개인 투자자 데이터는 foreign-institution-total API에서 지원하지 않습니다. 건너뜁니다.");
            return 0;
        }

        if (!koreaInvestmentService.isConfigured()) {
            log.warn("한국투자증권 API 키가 설정되지 않았습니다. 수집을 건너뜁니다.");
            return 0;
        }

        try {
            // 투자자 구분: 1=외국인, 2=기관계
            String investorCode = "FOREIGN".equals(investorType) ? "1" : "2";
            boolean isBuy = "BUY".equals(tradeType);

            log.info("API 호출 시작: {} {} {} (investorCode={}, isBuy={})",
                    market, investorType, tradeType, investorCode, isBuy);

            // KoreaInvestmentService를 통해 API 호출
            JsonNode response = koreaInvestmentService.getForeignInstitutionTotal(investorCode, isBuy, true);

            if (response == null) {
                log.error("API 응답이 null입니다: {} {} {}", market, investorType, tradeType);
                return 0;
            }

            log.info("API 응답 수신: rt_cd={}, msg1={}",
                    response.has("rt_cd") ? response.get("rt_cd").asText() : "없음",
                    response.has("msg1") ? response.get("msg1").asText() : "없음");

            return parseAndSaveRankingData(response, market, investorType, tradeType,
                    LocalDate.parse(dateStr, DATE_FORMATTER), limit);

        } catch (Exception e) {
            log.error("순위 데이터 수집 실패: {} {} {} - {}", market, investorType, tradeType, e.getMessage(), e);
        }

        return 0;
    }

    /**
     * API 응답을 파싱하고 DB에 저장
     * InvestorDailyTradeService와 동일한 필드명 사용
     */
    private int parseAndSaveRankingData(JsonNode response, String market, String investorType,
                                        String tradeType, LocalDate tradeDate, int limit) {
        try {
            // 응답 코드 확인
            String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText() : "";
            if (!"0".equals(rtCd)) {
                log.error("API 오류: {} - {}", rtCd, response.has("msg1") ? response.get("msg1").asText() : "");
                return 0;
            }

            JsonNode output = response.get("output");

            if (output == null || !output.isArray() || output.size() == 0) {
                log.warn("응답 데이터가 비어있습니다: {} {} {} (output size: {})",
                    market, investorType, tradeType, output != null ? output.size() : 0);
                return 0;
            }

            log.info("파싱할 데이터 개수: {}", output.size());

            List<InvestorDailyTrade> trades = new ArrayList<>();
            BigDecimal divider = BigDecimal.valueOf(100000000); // 1억
            int rank = 1;

            for (JsonNode item : output) {
                if (rank > limit) break;

                try {
                    // 첫 번째 항목에서 사용 가능한 필드 로깅
                    if (rank == 1) {
                        StringBuilder fields = new StringBuilder("API 응답 필드: ");
                        item.fieldNames().forEachRemaining(f -> fields.append(f).append("=").append(item.get(f).asText()).append(", "));
                        log.info(fields.toString());
                    }

                    // foreign-institution-total API 필드명 (InvestorDailyTradeService와 동일)
                    String stockCode = getJsonValue(item, "mksc_shrn_iscd");
                    String stockName = getJsonValue(item, "hts_kor_isnm");

                    if (stockCode == null || stockCode.isEmpty() || stockName == null || stockName.isEmpty()) {
                        log.debug("필수 필드 누락: rank {}", rank);
                        continue;
                    }

                    // 순매수 금액 - 투자자 유형에 따라 다른 필드 사용
                    // frgn_ntby_tr_pbmn: 외국인, orgn_ntby_tr_pbmn: 기관 (백만원 단위)
                    String netBuyField = "FOREIGN".equals(investorType) ? "frgn_ntby_tr_pbmn" : "orgn_ntby_tr_pbmn";
                    BigDecimal netBuyAmount = getJsonBigDecimal(item, netBuyField);
                    // 백만원 단위 -> 억원 단위 (/100)
                    netBuyAmount = netBuyAmount.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

                    // 매수/매도 금액은 API에서 제공하지 않으므로 순매수 금액으로 대체
                    BigDecimal buyAmount = netBuyAmount.compareTo(BigDecimal.ZERO) > 0 ? netBuyAmount : BigDecimal.ZERO;
                    BigDecimal sellAmount = netBuyAmount.compareTo(BigDecimal.ZERO) < 0 ? netBuyAmount.abs() : BigDecimal.ZERO;

                    // 현재가, 등락률
                    BigDecimal currentPrice = getJsonBigDecimal(item, "stck_prpr");
                    BigDecimal changeRate = getJsonBigDecimal(item, "prdy_ctrt");

                    // 거래량
                    Long tradeVolume = getJsonLong(item, "acml_vol");

                    log.debug("종목 {}: netBuyAmount={} (억원)", stockCode, netBuyAmount);

                    InvestorDailyTrade trade = InvestorDailyTrade.builder()
                            .marketType(market)
                            .tradeDate(tradeDate)
                            .investorType(investorType)
                            .tradeType(tradeType)
                            .rankNum(rank)
                            .stockCode(stockCode)
                            .stockName(stockName)
                            .netBuyAmount(netBuyAmount)
                            .buyAmount(buyAmount)
                            .sellAmount(sellAmount)
                            .currentPrice(currentPrice)
                            .changeRate(changeRate)
                            .tradeVolume(tradeVolume)
                            .build();

                    trades.add(trade);
                    rank++;

                } catch (Exception e) {
                    log.warn("데이터 파싱 실패: rank {} - {}", rank, e.getMessage());
                }
            }

            if (!trades.isEmpty()) {
                investorTradeRepository.saveAll(trades);
                log.info("저장 완료: {} {} {} - {}건", market, investorType, tradeType, trades.size());
                return trades.size();
            } else {
                log.warn("파싱된 데이터가 없습니다: {} {} {}", market, investorType, tradeType);
            }

        } catch (Exception e) {
            log.error("응답 파싱 실패", e);
        }

        return 0;
    }

    /**
     * 연기금 순위 데이터 수집
     * 기관 API 응답에서 연기금 필드(fund_ntby_tr_pbmn)를 추출하여 저장
     */
    private int collectPensionRanking(String market, String tradeType, String dateStr, int limit) {
        if (!koreaInvestmentService.isConfigured()) {
            log.warn("한국투자증권 API 키가 설정되지 않았습니다. 연기금 수집을 건너뜁니다.");
            return 0;
        }

        try {
            boolean isBuy = "BUY".equals(tradeType);

            log.info("연기금 API 호출 시작: {} {} (기관 API에서 연기금 필드 추출)", market, tradeType);

            // 기관계(2) API 호출 - 응답에 연기금 필드 포함
            JsonNode response = koreaInvestmentService.getForeignInstitutionTotal("2", isBuy, true);

            if (response == null) {
                log.error("연기금 API 응답이 null입니다: {} {}", market, tradeType);
                return 0;
            }

            return parseAndSavePensionData(response, market, tradeType,
                    LocalDate.parse(dateStr, DATE_FORMATTER), limit);

        } catch (Exception e) {
            log.error("연기금 데이터 수집 실패: {} {} - {}", market, tradeType, e.getMessage(), e);
        }

        return 0;
    }

    /**
     * 연기금 데이터 파싱 및 저장
     * API 응답에서 fund_ntby_tr_pbmn(연기금 순매수 대금) 필드 추출
     *
     * [KIS API 연기금 관련 필드]
     * - fund_ntby_qty: 연기금 순매수 수량
     * - fund_ntby_tr_pbmn: 연기금 순매수 거래대금 (백만원 단위)
     */
    private int parseAndSavePensionData(JsonNode response, String market, String tradeType,
                                        LocalDate tradeDate, int limit) {
        try {
            String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText() : "";
            if (!"0".equals(rtCd)) {
                log.error("연기금 API 오류: {} - {}", rtCd, response.has("msg1") ? response.get("msg1").asText() : "");
                return 0;
            }

            JsonNode output = response.get("output");
            if (output == null || !output.isArray() || output.size() == 0) {
                log.warn("연기금 응답 데이터가 비어있습니다: {} {}", market, tradeType);
                return 0;
            }

            // 연기금 순매수 금액이 있는 항목만 필터링 후 정렬
            // fund_ntby_tr_pbmn: 연기금 순매수 거래대금 (올바른 필드명)
            List<JsonNode> pensionItems = new ArrayList<>();
            for (JsonNode item : output) {
                BigDecimal pensionAmount = getJsonBigDecimal(item, "fund_ntby_tr_pbmn");
                if (pensionAmount.abs().compareTo(BigDecimal.ZERO) > 0) {
                    pensionItems.add(item);
                }
            }

            // 디버깅: 첫 번째 항목의 연기금 관련 필드 확인
            if (output.size() > 0 && pensionItems.isEmpty()) {
                JsonNode firstItem = output.get(0);
                log.warn("연기금 데이터 파싱 실패 - 첫 번째 항목의 필드 확인: " +
                        "fund_ntby_tr_pbmn={}, fund_ntby_qty={}",
                        getJsonValue(firstItem, "fund_ntby_tr_pbmn"),
                        getJsonValue(firstItem, "fund_ntby_qty"));
            }

            log.info("연기금 필드 체크: output 항목 {}, fund_ntby_tr_pbmn 있는 항목 {}",
                    output.size(), pensionItems.size());

            // 순매수/순매도 금액 기준 정렬
            boolean isBuy = "BUY".equals(tradeType);
            pensionItems.sort((a, b) -> {
                BigDecimal amountA = getJsonBigDecimal(a, "fund_ntby_tr_pbmn");
                BigDecimal amountB = getJsonBigDecimal(b, "fund_ntby_tr_pbmn");
                if (isBuy) {
                    return amountB.compareTo(amountA);  // 내림차순 (순매수 상위)
                } else {
                    return amountA.compareTo(amountB);  // 오름차순 (순매도 상위)
                }
            });

            List<InvestorDailyTrade> trades = new ArrayList<>();
            int rank = 1;

            for (JsonNode item : pensionItems) {
                if (rank > limit) break;

                String stockCode = getJsonValue(item, "mksc_shrn_iscd");
                String stockName = getJsonValue(item, "hts_kor_isnm");

                if (stockCode == null || stockCode.isEmpty()) {
                    continue;
                }

                // 연기금 순매수 금액 (백만원 단위 -> 억원 단위)
                BigDecimal pensionAmount = getJsonBigDecimal(item, "fund_ntby_tr_pbmn");
                BigDecimal netBuyAmount = pensionAmount.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

                // 매수/매도 조건에 맞지 않으면 건너뛰기
                if (isBuy && netBuyAmount.compareTo(BigDecimal.ZERO) <= 0) continue;
                if (!isBuy && netBuyAmount.compareTo(BigDecimal.ZERO) >= 0) continue;

                BigDecimal buyAmount = netBuyAmount.compareTo(BigDecimal.ZERO) > 0 ? netBuyAmount : BigDecimal.ZERO;
                BigDecimal sellAmount = netBuyAmount.compareTo(BigDecimal.ZERO) < 0 ? netBuyAmount.abs() : BigDecimal.ZERO;

                BigDecimal currentPrice = getJsonBigDecimal(item, "stck_prpr");
                BigDecimal changeRate = getJsonBigDecimal(item, "prdy_ctrt");
                Long tradeVolume = getJsonLong(item, "acml_vol");

                InvestorDailyTrade trade = InvestorDailyTrade.builder()
                        .marketType(market)
                        .tradeDate(tradeDate)
                        .investorType("PENSION")
                        .tradeType(tradeType)
                        .rankNum(rank)
                        .stockCode(stockCode)
                        .stockName(stockName)
                        .netBuyAmount(netBuyAmount)
                        .buyAmount(buyAmount)
                        .sellAmount(sellAmount)
                        .currentPrice(currentPrice)
                        .changeRate(changeRate)
                        .tradeVolume(tradeVolume)
                        .build();

                trades.add(trade);
                rank++;
            }

            if (!trades.isEmpty()) {
                investorTradeRepository.saveAll(trades);
                log.info("연기금 저장 완료: {} {} - {}건", market, tradeType, trades.size());
                return trades.size();
            } else {
                log.warn("파싱된 연기금 데이터가 없습니다: {} {}", market, tradeType);
            }

        } catch (Exception e) {
            log.error("연기금 응답 파싱 실패", e);
        }

        return 0;
    }

    /**
     * JSON 필드값 가져오기
     */
    private String getJsonValue(JsonNode node, String fieldName) {
        if (node.has(fieldName) && !node.get(fieldName).isNull()) {
            return node.get(fieldName).asText().trim();
        }
        return null;
    }

    /**
     * JSON BigDecimal 값 가져오기
     */
    private BigDecimal getJsonBigDecimal(JsonNode node, String fieldName) {
        if (node.has(fieldName) && !node.get(fieldName).isNull()) {
            String value = node.get(fieldName).asText().replace(",", "").trim();
            if (value.isEmpty() || value.equals("-")) {
                return BigDecimal.ZERO;
            }
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * JSON Long 값 가져오기
     */
    private Long getJsonLong(JsonNode node, String fieldName) {
        if (node.has(fieldName) && !node.get(fieldName).isNull()) {
            String value = node.get(fieldName).asText().replace(",", "").trim();
            if (value.isEmpty()) {
                return 0L;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }
}
