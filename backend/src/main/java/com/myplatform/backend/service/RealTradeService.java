package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.PaperTradingDto.*;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.VirtualTradeHistory;
import com.myplatform.backend.repository.VirtualTradeHistoryRepository;
import com.myplatform.backend.service.KoreaInvestmentService.BalanceInfo;
import com.myplatform.backend.service.KoreaInvestmentService.HoldingStock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 실전 매매 서비스
 * - KIS API를 통한 실제 주식 매수/매도
 * - 지정가 주문 사용 (슬리피지 방지)
 */
@Service("realTradeService")
@Transactional
@RequiredArgsConstructor
@Slf4j
public class RealTradeService implements TradeService {

    private final KoreaInvestmentService kisService;
    private final StockPriceService stockPriceService;
    private final TelegramNotificationService telegramService;
    private final VirtualTradeHistoryRepository tradeHistoryRepository;

    // 실전매매용 계좌 ID (가상 ID - 실제 계좌와 구분)
    private static final Long REAL_ACCOUNT_ID = 999999L;

    // 캐시된 잔고 정보
    private volatile BalanceInfo cachedBalance;
    private volatile LocalDateTime lastBalanceUpdate;
    private static final long BALANCE_CACHE_SECONDS = 30;

    /**
     * 실전 매수 처리
     */
    @Override
    public TradeHistoryDto buy(String stockCode, BigDecimal price, Integer quantity, String reason) {
        log.info("[실전매매] 매수 주문 시작: {} x {} @ {}원", stockCode, quantity, price);

        // KIS API 설정 확인
        if (!kisService.isRealTradingConfigured()) {
            throw new IllegalStateException("실전매매 API가 설정되지 않았습니다. 계좌 정보를 확인하세요.");
        }

        // 종목명 조회
        String stockName = getStockName(stockCode);

        // KIS API 매수 주문 (지정가 - 슬리피지 방지)
        JsonNode orderResult = kisService.buyStock(stockCode, quantity, price);
        if (orderResult == null) {
            throw new IllegalStateException("매수 주문 API 호출 실패");
        }

        // 주문 결과 확인
        String rtCd = orderResult.has("rt_cd") ? orderResult.get("rt_cd").asText() : "";
        if (!"0".equals(rtCd)) {
            String msg = orderResult.has("msg1") ? orderResult.get("msg1").asText() : "알 수 없는 오류";
            throw new IllegalStateException("매수 주문 실패: " + msg);
        }

        // 주문번호 추출
        String orderNo = "";
        if (orderResult.has("output") && orderResult.get("output").has("ODNO")) {
            orderNo = orderResult.get("output").get("ODNO").asText();
        }

        // 총 금액 계산 (시장가이므로 현재가로 추정)
        BigDecimal totalAmount = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal commission = totalAmount.multiply(new BigDecimal("0.00015"))
                .setScale(0, RoundingMode.CEILING);

        // 거래 내역 저장 (DB)
        VirtualTradeHistory trade = VirtualTradeHistory.builder()
                .accountId(REAL_ACCOUNT_ID)
                .stockCode(stockCode)
                .stockName(stockName)
                .tradeType("BUY")
                .quantity(quantity)
                .price(price)
                .totalAmount(totalAmount)
                .commission(commission)
                .tax(BigDecimal.ZERO)
                .tradeReason(reason != null ? reason : "MANUAL")
                .tradeDate(LocalDateTime.now())
                .build();
        tradeHistoryRepository.save(trade);

        log.info("[실전매매] 매수 주문 완료: {} ({}) x {} @ {}원, 주문번호: {}",
                stockName, stockCode, quantity, price, orderNo);

        // 캐시 무효화
        cachedBalance = null;

        // 텔레그램 알림
        sendRealBuyAlert(stockName, stockCode, price, quantity, orderNo);

        return toTradeHistoryDto(trade);
    }

    /**
     * 실전 매도 처리
     */
    @Override
    public TradeHistoryDto sell(String stockCode, BigDecimal price, Integer quantity, String reason) {
        log.info("[실전매매] 매도 주문 시작: {} x {} @ {}원", stockCode, quantity, price);

        // KIS API 설정 확인
        if (!kisService.isRealTradingConfigured()) {
            throw new IllegalStateException("실전매매 API가 설정되지 않았습니다. 계좌 정보를 확인하세요.");
        }

        // 종목명 조회
        String stockName = getStockName(stockCode);

        // 보유 확인
        BalanceInfo balance = getBalanceInfo();
        HoldingStock holding = null;
        if (balance != null && balance.getHoldings() != null) {
            holding = balance.getHoldings().stream()
                    .filter(h -> stockCode.equals(h.getStockCode()))
                    .findFirst()
                    .orElse(null);
        }

        if (holding == null || holding.getQuantity() < quantity) {
            int availableQty = holding != null ? holding.getQuantity() : 0;
            throw new IllegalStateException(
                    String.format("보유 수량 부족: 보유 %d주, 매도 요청 %d주", availableQty, quantity));
        }

        // 평균 매입가 (손익 계산용)
        BigDecimal avgPrice = holding.getAveragePrice() != null
                ? holding.getAveragePrice()
                : BigDecimal.ZERO;

        // KIS API 매도 주문 (지정가 - 슬리피지 방지)
        JsonNode orderResult = kisService.sellStock(stockCode, quantity, price);
        if (orderResult == null) {
            throw new IllegalStateException("매도 주문 API 호출 실패");
        }

        // 주문 결과 확인
        String rtCd = orderResult.has("rt_cd") ? orderResult.get("rt_cd").asText() : "";
        if (!"0".equals(rtCd)) {
            String msg = orderResult.has("msg1") ? orderResult.get("msg1").asText() : "알 수 없는 오류";
            throw new IllegalStateException("매도 주문 실패: " + msg);
        }

        // 주문번호 추출
        String orderNo = "";
        if (orderResult.has("output") && orderResult.get("output").has("ODNO")) {
            orderNo = orderResult.get("output").get("ODNO").asText();
        }

        // 총 금액 및 손익 계산
        BigDecimal totalAmount = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal commission = totalAmount.multiply(new BigDecimal("0.00015"))
                .setScale(0, RoundingMode.CEILING);
        BigDecimal tax = totalAmount.multiply(new BigDecimal("0.002"))
                .setScale(0, RoundingMode.CEILING);
        BigDecimal netAmount = totalAmount.subtract(commission).subtract(tax);
        BigDecimal investedAmount = avgPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal profitLoss = netAmount.subtract(investedAmount);

        // 거래 내역 저장 (DB)
        VirtualTradeHistory trade = VirtualTradeHistory.builder()
                .accountId(REAL_ACCOUNT_ID)
                .stockCode(stockCode)
                .stockName(stockName)
                .tradeType("SELL")
                .quantity(quantity)
                .price(price)
                .totalAmount(totalAmount)
                .commission(commission)
                .tax(tax)
                .profitLoss(profitLoss)
                .tradeReason(reason != null ? reason : "MANUAL")
                .tradeDate(LocalDateTime.now())
                .build();
        tradeHistoryRepository.save(trade);

        log.info("[실전매매] 매도 주문 완료: {} ({}) x {} @ {}원, 손익: {}원, 주문번호: {}",
                stockName, stockCode, quantity, price, profitLoss, orderNo);

        // 캐시 무효화
        cachedBalance = null;

        // 텔레그램 알림
        sendRealSellAlert(stockName, stockCode, price, quantity, profitLoss, reason, orderNo);

        return toTradeHistoryDto(trade);
    }

    /**
     * 계좌 요약 조회 (KIS API 잔고 기반)
     */
    @Override
    public AccountSummaryDto getAccountSummary() {
        BalanceInfo balance = getBalanceInfo();
        if (balance == null) {
            throw new IllegalStateException("잔고 정보를 조회할 수 없습니다.");
        }

        // 총 투자금액 계산
        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalEvaluation = BigDecimal.ZERO;

        if (balance.getHoldings() != null) {
            for (HoldingStock holding : balance.getHoldings()) {
                if (holding.getAveragePrice() != null && holding.getQuantity() > 0) {
                    totalInvested = totalInvested.add(
                            holding.getAveragePrice().multiply(BigDecimal.valueOf(holding.getQuantity())));
                }
                if (holding.getCurrentPrice() != null && holding.getQuantity() > 0) {
                    totalEvaluation = totalEvaluation.add(
                            holding.getCurrentPrice().multiply(BigDecimal.valueOf(holding.getQuantity())));
                }
            }
        }

        // 총 자산 = 예수금 + 평가금액
        BigDecimal currentBalance = balance.getAvailableBalance() != null
                ? balance.getAvailableBalance()
                : BigDecimal.ZERO;
        BigDecimal totalAsset = currentBalance.add(totalEvaluation);

        // 평가손익
        BigDecimal unrealizedProfitLoss = totalEvaluation.subtract(totalInvested);

        // 실현손익 (DB에서 조회)
        BigDecimal realizedProfitLoss = tradeHistoryRepository.sumRealizedProfitLoss(REAL_ACCOUNT_ID);
        if (realizedProfitLoss == null) realizedProfitLoss = BigDecimal.ZERO;

        // 총 손익
        BigDecimal totalProfitLoss = realizedProfitLoss.add(unrealizedProfitLoss);

        int holdingCount = balance.getHoldings() != null ? balance.getHoldings().size() : 0;

        return AccountSummaryDto.builder()
                .accountId(REAL_ACCOUNT_ID)
                .accountName("실전투자 계좌")
                .initialBalance(BigDecimal.ZERO)  // 실전계좌는 초기자본 개념 없음
                .currentBalance(currentBalance)
                .totalInvested(totalInvested)
                .totalEvaluation(totalEvaluation)
                .totalProfitLoss(totalProfitLoss)
                .totalProfitRate(BigDecimal.ZERO)  // 초기자본 없으므로 수익률 계산 불가
                .realizedProfitLoss(realizedProfitLoss)
                .unrealizedProfitLoss(unrealizedProfitLoss)
                .holdingCount(holdingCount)
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 포트폴리오 조회 (KIS API 잔고 기반)
     */
    @Override
    public List<PortfolioItemDto> getPortfolio() {
        BalanceInfo balance = getBalanceInfo();
        if (balance == null || balance.getHoldings() == null) {
            return new ArrayList<>();
        }

        List<PortfolioItemDto> portfolio = new ArrayList<>();
        for (HoldingStock holding : balance.getHoldings()) {
            BigDecimal avgPrice = holding.getAveragePrice() != null ? holding.getAveragePrice() : BigDecimal.ZERO;
            BigDecimal currentPrice = holding.getCurrentPrice() != null ? holding.getCurrentPrice() : BigDecimal.ZERO;
            int qty = holding.getQuantity();

            BigDecimal totalInvested = avgPrice.multiply(BigDecimal.valueOf(qty));
            BigDecimal totalEvaluation = currentPrice.multiply(BigDecimal.valueOf(qty));
            BigDecimal profitLoss = holding.getProfitLoss() != null ? holding.getProfitLoss() : totalEvaluation.subtract(totalInvested);
            BigDecimal profitRate = holding.getProfitRate();

            if (profitRate == null && totalInvested.compareTo(BigDecimal.ZERO) > 0) {
                profitRate = profitLoss.divide(totalInvested, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }

            portfolio.add(PortfolioItemDto.builder()
                    .id(0L)
                    .stockCode(holding.getStockCode())
                    .stockName(holding.getStockName())
                    .quantity(qty)
                    .averagePrice(avgPrice)
                    .currentPrice(currentPrice)
                    .totalInvested(totalInvested)
                    .totalEvaluation(totalEvaluation)
                    .profitLoss(profitLoss)
                    .profitRate(profitRate)
                    .updatedAt(LocalDateTime.now())
                    .build());
        }

        return portfolio;
    }

    /**
     * 포트폴리오 현재가 업데이트 (잔고 캐시 무효화)
     */
    @Override
    public void updatePortfolioPrices() {
        // 캐시 무효화하여 다음 조회 시 최신 데이터 가져옴
        cachedBalance = null;
        log.debug("[실전매매] 잔고 캐시 무효화");
    }

    /**
     * 매매 모드 반환
     */
    @Override
    public String getTradeMode() {
        return "REAL";
    }

    /**
     * 잔고 정보 조회 (캐싱)
     */
    private BalanceInfo getBalanceInfo() {
        // 캐시 유효성 확인
        if (cachedBalance != null && lastBalanceUpdate != null) {
            long elapsed = java.time.Duration.between(lastBalanceUpdate, LocalDateTime.now()).getSeconds();
            if (elapsed < BALANCE_CACHE_SECONDS) {
                return cachedBalance;
            }
        }

        // KIS API 호출
        JsonNode balanceResponse = kisService.getBalance();
        if (balanceResponse == null) {
            return cachedBalance;  // 실패 시 기존 캐시 반환
        }

        // 파싱 및 캐시 업데이트
        BalanceInfo balance = kisService.parseBalance(balanceResponse);
        if (balance != null) {
            cachedBalance = balance;
            lastBalanceUpdate = LocalDateTime.now();
        }

        return balance;
    }

    /**
     * 종목명 조회
     */
    private String getStockName(String stockCode) {
        try {
            StockPriceDto priceDto = stockPriceService.getStockPrice(stockCode);
            return priceDto != null && priceDto.getStockName() != null
                    ? priceDto.getStockName()
                    : stockCode;
        } catch (Exception e) {
            log.warn("종목명 조회 실패: {}", stockCode);
            return stockCode;
        }
    }

    /**
     * DTO 변환
     */
    private TradeHistoryDto toTradeHistoryDto(VirtualTradeHistory trade) {
        String tradeTypeName = "BUY".equals(trade.getTradeType()) ? "매수" : "매도";
        String tradeReasonName = switch (trade.getTradeReason()) {
            case "AUTO_BUY" -> "자동매수";
            case "STOP_LOSS" -> "손절";
            case "TAKE_PROFIT" -> "익절";
            case "AUTO_SELL" -> "자동매도";
            case "TIME_CUT" -> "장마감청산";
            default -> "수동";
        };

        return TradeHistoryDto.builder()
                .id(trade.getId())
                .stockCode(trade.getStockCode())
                .stockName(trade.getStockName())
                .tradeType(trade.getTradeType())
                .tradeTypeName(tradeTypeName)
                .quantity(trade.getQuantity())
                .price(trade.getPrice())
                .totalAmount(trade.getTotalAmount())
                .commission(trade.getCommission())
                .tax(trade.getTax())
                .profitLoss(trade.getProfitLoss())
                .tradeReason(trade.getTradeReason())
                .tradeReasonName(tradeReasonName)
                .tradeDate(trade.getTradeDate())
                .build();
    }

    /**
     * 실전매수 텔레그램 알림
     */
    private void sendRealBuyAlert(String stockName, String stockCode, BigDecimal price, Integer quantity, String orderNo) {
        if (!telegramService.isEnabled()) return;

        String message = String.format(
                """
                <b>💰 [실전투자] 매수 주문!</b>

                📊 <b>%s</b> (%s)
                💰 %s원 x %d주
                💵 총 금액: %s원
                📋 주문번호: %s

                ⏰ %s
                ━━━━━━━━━━━━━━━━
                🔴 MyPlatform 실전투자
                """,
                stockName, stockCode,
                formatNumber(price), quantity,
                formatNumber(price.multiply(BigDecimal.valueOf(quantity))),
                orderNo,
                LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        );

        telegramService.sendMessage(message);
    }

    /**
     * 실전매도 텔레그램 알림
     */
    private void sendRealSellAlert(String stockName, String stockCode, BigDecimal price, Integer quantity,
                                    BigDecimal profitLoss, String reason, String orderNo) {
        if (!telegramService.isEnabled()) return;

        String reasonText = switch (reason) {
            case "STOP_LOSS" -> "🔻 손절";
            case "TAKE_PROFIT" -> "🔺 익절";
            case "AUTO_SELL" -> "🤖 자동매도";
            case "TIME_CUT" -> "🔔 장마감청산";
            default -> "📝 수동매도";
        };

        String profitEmoji = profitLoss.compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉";
        String profitSign = profitLoss.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";

        String message = String.format(
                """
                <b>💸 [실전투자] 매도 주문!</b>

                📊 <b>%s</b> (%s)
                💰 %s원 x %d주
                📋 사유: %s
                📋 주문번호: %s

                %s 손익: <b>%s%s원</b>

                ⏰ %s
                ━━━━━━━━━━━━━━━━
                🔴 MyPlatform 실전투자
                """,
                stockName, stockCode,
                formatNumber(price), quantity,
                reasonText,
                orderNo,
                profitEmoji, profitSign, formatNumber(profitLoss),
                LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        );

        telegramService.sendMessage(message);
    }

    private String formatNumber(BigDecimal value) {
        if (value == null) return "0";
        return String.format("%,.0f", value);
    }
}
