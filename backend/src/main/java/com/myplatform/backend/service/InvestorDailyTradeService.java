package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 투자자별 일별 상위 매수/매도 종목 서비스
 * - 외국인, 기관, 연기금 등 투자자별 상위 종목 데이터 수집 및 저장
 */
@Service
@Transactional
public class InvestorDailyTradeService {

    private static final Logger log = LoggerFactory.getLogger(InvestorDailyTradeService.class);

    // 네이버 금융 API - 투자자별 상위 종목
    private static final String NAVER_TOP_STOCKS_API = "https://m.stock.naver.com/api/index/%s/investorNetTopN?investorType=%s&netType=%s";

    // KRX 투자자별 거래실적 API
    private static final String KRX_INVESTOR_API = "https://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd";

    // 투자자 유형 매핑
    public static final String INVESTOR_FOREIGN = "FOREIGN";
    public static final String INVESTOR_INSTITUTION = "INSTITUTION";
    public static final String INVESTOR_PENSION = "PENSION";
    public static final String INVESTOR_INDIVIDUAL = "INDIVIDUAL";
    public static final String INVESTOR_INVEST_TRUST = "INVEST_TRUST";
    public static final String INVESTOR_INSURANCE = "INSURANCE";
    public static final String INVESTOR_PRIVATE_EQUITY = "PRIVATE_EQUITY";
    public static final String INVESTOR_BANK = "BANK";

    // 거래 유형
    public static final String TRADE_BUY = "BUY";
    public static final String TRADE_SELL = "SELL";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final InvestorDailyTradeRepository tradeRepository;

    public InvestorDailyTradeService(RestTemplate restTemplate, ObjectMapper objectMapper,
                                      InvestorDailyTradeRepository tradeRepository) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.tradeRepository = tradeRepository;
    }

    /**
     * 모든 투자자 유형의 상위 종목 데이터 수집 및 저장 (당일)
     */
    public Map<String, Integer> collectAllInvestorTrades() {
        return collectAllInvestorTrades(LocalDate.now());
    }

    /**
     * 모든 투자자 유형의 상위 종목 데이터 수집 및 저장 (지정일)
     */
    public Map<String, Integer> collectAllInvestorTrades(LocalDate tradeDate) {
        Map<String, Integer> result = new HashMap<>();

        // 네이버 API에서 수집 가능한 투자자 유형
        String[] naverInvestorTypes = {INVESTOR_FOREIGN, INVESTOR_INSTITUTION};
        String[] markets = {"KOSPI", "KOSDAQ"};

        for (String market : markets) {
            for (String investorType : naverInvestorTypes) {
                int count = collectFromNaverApi(market, investorType, tradeDate);
                result.put(market + "_" + investorType, count);
            }
        }

        // KRX API에서 연기금 등 세부 투자자 데이터 수집
        for (String market : markets) {
            int count = collectPensionFromKrx(market, tradeDate);
            result.put(market + "_" + INVESTOR_PENSION, count);
        }

        log.info("투자자별 거래 데이터 수집 완료: {}", result);
        return result;
    }

    /**
     * 네이버 API에서 투자자별 상위 종목 수집
     */
    public int collectFromNaverApi(String marketType, String investorType, LocalDate tradeDate) {
        // 이미 데이터가 있으면 스킵
        if (tradeRepository.existsByMarketTypeAndInvestorTypeAndTradeDate(marketType, investorType, tradeDate)) {
            log.info("이미 수집됨: {} {} {}", marketType, investorType, tradeDate);
            return 0;
        }

        String naverInvestorType = mapToNaverInvestorType(investorType);
        if (naverInvestorType == null) {
            return 0;
        }

        int savedCount = 0;

        // 매수 상위 종목 수집
        savedCount += fetchAndSaveTopStocks(marketType, investorType, naverInvestorType, TRADE_BUY, tradeDate);

        // 매도 상위 종목 수집
        savedCount += fetchAndSaveTopStocks(marketType, investorType, naverInvestorType, TRADE_SELL, tradeDate);

        return savedCount;
    }

    /**
     * 상위 종목 조회 및 저장
     */
    private int fetchAndSaveTopStocks(String marketType, String investorType,
                                       String naverInvestorType, String tradeType, LocalDate tradeDate) {
        List<InvestorDailyTrade> trades = new ArrayList<>();

        try {
            String netType = TRADE_BUY.equals(tradeType) ? "buy" : "sell";
            String url = String.format(NAVER_TOP_STOCKS_API, marketType, naverInvestorType, netType);

            HttpEntity<String> entity = createHttpEntity();
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                BigDecimal divider = BigDecimal.valueOf(100000000); // 1억

                if (root.isArray()) {
                    int rank = 1;
                    for (JsonNode stockNode : root) {
                        if (rank > 20) break; // 상위 20개만

                        InvestorDailyTrade trade = new InvestorDailyTrade();
                        trade.setMarketType(marketType);
                        trade.setTradeDate(tradeDate);
                        trade.setInvestorType(investorType);
                        trade.setTradeType(tradeType);
                        trade.setRankNum(rank);
                        trade.setStockCode(getStringValue(stockNode, "itemCode"));
                        trade.setStockName(getStringValue(stockNode, "stockName"));

                        // 순매수 금액 (억원 단위)
                        BigDecimal netBuyAmount = getBigDecimalValue(stockNode, "netBuyingAmount");
                        trade.setNetBuyAmount(netBuyAmount.divide(divider, 2, RoundingMode.HALF_UP));

                        // 매수/매도 금액
                        BigDecimal buyAmount = getBigDecimalValue(stockNode, "buyingAmount");
                        BigDecimal sellAmount = getBigDecimalValue(stockNode, "sellingAmount");
                        trade.setBuyAmount(buyAmount.divide(divider, 2, RoundingMode.HALF_UP));
                        trade.setSellAmount(sellAmount.divide(divider, 2, RoundingMode.HALF_UP));

                        // 현재가, 등락률
                        trade.setCurrentPrice(getBigDecimalValue(stockNode, "closePrice"));
                        trade.setChangeRate(getBigDecimalValue(stockNode, "fluctuationsRatio"));

                        // 거래량
                        if (stockNode.has("accumulatedTradingVolume")) {
                            trade.setTradeVolume(stockNode.get("accumulatedTradingVolume").asLong());
                        }

                        trades.add(trade);
                        rank++;
                    }
                }
            }

            if (!trades.isEmpty()) {
                tradeRepository.saveAll(trades);
                log.info("저장 완료: {} {} {} {} - {}건", marketType, investorType, tradeType, tradeDate, trades.size());
            }

        } catch (Exception e) {
            log.error("상위 종목 수집 실패 [{}/{}/{}/{}]: {}",
                    marketType, investorType, tradeType, tradeDate, e.getMessage());
        }

        return trades.size();
    }

    /**
     * KRX에서 연기금 투자자 데이터 수집
     */
    public int collectPensionFromKrx(String marketType, LocalDate tradeDate) {
        // 이미 데이터가 있으면 스킵
        if (tradeRepository.existsByMarketTypeAndInvestorTypeAndTradeDate(marketType, INVESTOR_PENSION, tradeDate)) {
            log.info("이미 수집됨: {} {} {}", marketType, INVESTOR_PENSION, tradeDate);
            return 0;
        }

        List<InvestorDailyTrade> trades = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String dateStr = tradeDate.format(formatter);

        try {
            // KRX API 호출 - 연기금 순매수 상위
            String mktId = "KOSPI".equals(marketType) ? "STK" : "KSQ";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set("Referer", "https://data.krx.co.kr/contents/MDC/MDI/mdiLoader/index.cmd");

            // 연기금 순매수 상위 조회
            String buyRequestBody = String.format(
                    "bld=dbms/MDC/STAT/standard/MDCSTAT02303" +
                    "&locale=ko_KR" +
                    "&invstTpCd=6000" +  // 6000 = 연기금
                    "&strtDd=%s" +
                    "&endDd=%s" +
                    "&mktId=%s" +
                    "&share=1" +
                    "&money=1" +
                    "&csvxls_is498=false",
                    dateStr, dateStr, mktId
            );

            HttpEntity<String> buyEntity = new HttpEntity<>(buyRequestBody, headers);
            ResponseEntity<String> buyResponse = restTemplate.exchange(
                    KRX_INVESTOR_API, HttpMethod.POST, buyEntity, String.class);

            if (buyResponse.getStatusCode() == HttpStatus.OK && buyResponse.getBody() != null) {
                JsonNode root = objectMapper.readTree(buyResponse.getBody());
                JsonNode outBlock = root.has("OutBlock_1") ? root.get("OutBlock_1") : root.get("output");

                if (outBlock != null && outBlock.isArray()) {
                    BigDecimal divider = BigDecimal.valueOf(100000000);
                    int buyRank = 1;
                    int sellRank = 1;

                    for (JsonNode item : outBlock) {
                        if (buyRank > 20 && sellRank > 20) break;

                        String stockCode = getStringValue(item, "ISU_SRT_CD");
                        if (stockCode.isEmpty()) {
                            stockCode = getStringValue(item, "ISU_CD");
                        }
                        String stockName = getStringValue(item, "ISU_ABBRV");
                        if (stockName.isEmpty()) {
                            stockName = getStringValue(item, "ISU_NM");
                        }

                        // 순매수 금액
                        BigDecimal netBuy = getBigDecimalValue(item, "NETBID_AMT");
                        if (netBuy.equals(BigDecimal.ZERO)) {
                            netBuy = getBigDecimalValue(item, "ASK_TRDVAL")
                                    .subtract(getBigDecimalValue(item, "BID_TRDVAL"));
                        }

                        // 매수 금액
                        BigDecimal buyAmt = getBigDecimalValue(item, "BID_TRDVAL");
                        // 매도 금액
                        BigDecimal sellAmt = getBigDecimalValue(item, "ASK_TRDVAL");

                        // 순매수가 양수면 BUY, 음수면 SELL
                        if (netBuy.compareTo(BigDecimal.ZERO) > 0 && buyRank <= 20) {
                            InvestorDailyTrade trade = createTrade(
                                    marketType, tradeDate, INVESTOR_PENSION, TRADE_BUY, buyRank,
                                    stockCode, stockName, netBuy.divide(divider, 2, RoundingMode.HALF_UP),
                                    buyAmt.divide(divider, 2, RoundingMode.HALF_UP),
                                    sellAmt.divide(divider, 2, RoundingMode.HALF_UP)
                            );
                            // 현재가, 등락률
                            trade.setCurrentPrice(getBigDecimalValue(item, "TDD_CLSPRC"));
                            trade.setChangeRate(getBigDecimalValue(item, "FLUC_RT"));

                            trades.add(trade);
                            buyRank++;
                        } else if (netBuy.compareTo(BigDecimal.ZERO) < 0 && sellRank <= 20) {
                            InvestorDailyTrade trade = createTrade(
                                    marketType, tradeDate, INVESTOR_PENSION, TRADE_SELL, sellRank,
                                    stockCode, stockName, netBuy.abs().divide(divider, 2, RoundingMode.HALF_UP),
                                    buyAmt.divide(divider, 2, RoundingMode.HALF_UP),
                                    sellAmt.divide(divider, 2, RoundingMode.HALF_UP)
                            );
                            trade.setCurrentPrice(getBigDecimalValue(item, "TDD_CLSPRC"));
                            trade.setChangeRate(getBigDecimalValue(item, "FLUC_RT"));

                            trades.add(trade);
                            sellRank++;
                        }
                    }
                }
            }

            if (!trades.isEmpty()) {
                tradeRepository.saveAll(trades);
                log.info("연기금 데이터 저장 완료: {} {} - {}건", marketType, tradeDate, trades.size());
            }

        } catch (Exception e) {
            log.error("연기금 데이터 수집 실패 [{}/{}]: {}", marketType, tradeDate, e.getMessage());
        }

        return trades.size();
    }

    /**
     * 거래 엔티티 생성 헬퍼
     */
    private InvestorDailyTrade createTrade(String marketType, LocalDate tradeDate, String investorType,
                                            String tradeType, int rank, String stockCode, String stockName,
                                            BigDecimal netBuyAmount, BigDecimal buyAmount, BigDecimal sellAmount) {
        InvestorDailyTrade trade = new InvestorDailyTrade();
        trade.setMarketType(marketType);
        trade.setTradeDate(tradeDate);
        trade.setInvestorType(investorType);
        trade.setTradeType(tradeType);
        trade.setRankNum(rank);
        trade.setStockCode(stockCode);
        trade.setStockName(stockName);
        trade.setNetBuyAmount(netBuyAmount);
        trade.setBuyAmount(buyAmount);
        trade.setSellAmount(sellAmount);
        return trade;
    }

    /**
     * 특정 일자의 투자자별 거래 조회
     */
    public List<InvestorDailyTrade> getTradesByDate(LocalDate tradeDate) {
        return tradeRepository.findByTradeDateOrderByInvestorTypeAscTradeTypeAscRankNumAsc(tradeDate);
    }

    /**
     * 특정 일자, 시장의 투자자별 거래 조회
     */
    public List<InvestorDailyTrade> getTradesByMarketAndDate(String marketType, LocalDate tradeDate) {
        return tradeRepository.findByMarketTypeAndTradeDateOrderByInvestorTypeAscTradeTypeAscRankNumAsc(
                marketType, tradeDate);
    }

    /**
     * 특정 일자, 투자자 유형의 거래 조회
     */
    public List<InvestorDailyTrade> getTradesByInvestorAndDate(String investorType, LocalDate tradeDate) {
        return tradeRepository.findByInvestorTypeAndTradeDateOrderByTradeTypeAscRankNumAsc(
                investorType, tradeDate);
    }

    /**
     * 특정 일자, 시장, 투자자 유형의 거래 조회
     */
    public List<InvestorDailyTrade> getTradesByMarketInvestorAndDate(
            String marketType, String investorType, LocalDate tradeDate) {
        return tradeRepository.findByMarketTypeAndInvestorTypeAndTradeDateOrderByTradeTypeAscRankNumAsc(
                marketType, investorType, tradeDate);
    }

    /**
     * 기간별 투자자 거래 조회
     */
    public List<InvestorDailyTrade> getTradesByInvestorAndDateRange(
            String investorType, LocalDate startDate, LocalDate endDate) {
        return tradeRepository.findByInvestorTypeAndDateRange(investorType, startDate, endDate);
    }

    /**
     * 기간별 특정 종목의 투자자별 거래 조회
     */
    public List<InvestorDailyTrade> getTradesByStockAndDateRange(
            String stockCode, LocalDate startDate, LocalDate endDate) {
        return tradeRepository.findByStockCodeAndDateRange(stockCode, startDate, endDate);
    }

    /**
     * 기간별 투자자의 누적 매수/매도 종목 통계
     */
    public List<Map<String, Object>> getAccumulatedTrades(
            String investorType, String marketType, LocalDate startDate, LocalDate endDate) {
        List<Object[]> results = tradeRepository.findAccumulatedTradesByInvestor(
                investorType, marketType, startDate, endDate);

        List<Map<String, Object>> stats = new ArrayList<>();
        for (Object[] row : results) {
            Map<String, Object> stat = new HashMap<>();
            stat.put("stockCode", row[0]);
            stat.put("stockName", row[1]);
            stat.put("tradeType", row[2]);
            stat.put("totalAmount", row[3]);
            stat.put("tradeDays", row[4]);
            stats.add(stat);
        }
        return stats;
    }

    /**
     * 데이터가 있는 날짜 목록 조회
     */
    public List<LocalDate> getAvailableDates(String marketType, String investorType) {
        return tradeRepository.findDistinctTradeDatesByMarketTypeAndInvestorType(marketType, investorType);
    }

    /**
     * 특정 일자 데이터 재수집 (기존 데이터 삭제 후 재수집)
     */
    public int recollect(String marketType, String investorType, LocalDate tradeDate) {
        // 기존 데이터 삭제
        tradeRepository.deleteByMarketTypeAndInvestorTypeAndTradeDate(marketType, investorType, tradeDate);

        // 재수집
        if (INVESTOR_PENSION.equals(investorType)) {
            return collectPensionFromKrx(marketType, tradeDate);
        } else {
            return collectFromNaverApi(marketType, investorType, tradeDate);
        }
    }

    /**
     * 투자자 유형 매핑 (내부 -> 네이버 API)
     */
    private String mapToNaverInvestorType(String investorType) {
        return switch (investorType) {
            case INVESTOR_FOREIGN -> "foreigner";
            case INVESTOR_INSTITUTION -> "organ";
            case INVESTOR_INDIVIDUAL -> "individual";
            default -> null;
        };
    }

    /**
     * HTTP Entity 생성
     */
    private HttpEntity<String> createHttpEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        headers.set("Referer", "https://m.stock.naver.com");
        headers.set("Accept", "application/json");
        return new HttpEntity<>(headers);
    }

    /**
     * JsonNode에서 String 값 추출
     */
    private String getStringValue(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText().trim();
    }

    /**
     * JsonNode에서 BigDecimal 값 추출
     */
    private BigDecimal getBigDecimalValue(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return BigDecimal.ZERO;
        }
        String value = node.get(field).asText().replace(",", "").trim();
        if (value.isEmpty() || value.equals("-")) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
