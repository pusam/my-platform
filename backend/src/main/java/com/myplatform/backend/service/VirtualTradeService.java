package com.myplatform.backend.service;

import com.myplatform.backend.dto.PaperTradingDto.*;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.VirtualAccount;
import com.myplatform.backend.entity.VirtualPortfolio;
import com.myplatform.backend.entity.VirtualTradeHistory;
import com.myplatform.backend.repository.VirtualAccountRepository;
import com.myplatform.backend.repository.VirtualPortfolioRepository;
import com.myplatform.backend.repository.VirtualTradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 가상 거래 서비스 (모의투자)
 * - 모의투자 매수/매도 처리
 * - 포트폴리오 관리
 * - 거래 내역 관리
 */
@Service("virtualTradeService")
@Transactional
@RequiredArgsConstructor
@Slf4j
public class VirtualTradeService implements TradeService {

    private final VirtualAccountRepository accountRepository;
    private final VirtualPortfolioRepository portfolioRepository;
    private final VirtualTradeHistoryRepository tradeHistoryRepository;
    private final StockPriceService stockPriceService;
    private final TelegramNotificationService telegramService;

    // 수수료율 및 세율
    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.00015"); // 0.015%
    private static final BigDecimal TAX_RATE = new BigDecimal("0.002"); // 0.2%
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000000"); // 1,000만원

    /**
     * 계좌 초기화 (사용자 지정 금액)
     * @param initialAmount 초기 자본금 (null이면 기본값 1,000만원)
     */
    public AccountSummaryDto initializeAccount(BigDecimal initialAmount) {
        // 기본값 처리
        BigDecimal balance = (initialAmount != null && initialAmount.compareTo(BigDecimal.ZERO) > 0)
                ? initialAmount
                : INITIAL_BALANCE;

        // 기존 활성 계좌 비활성화
        accountRepository.findByIsActiveTrue().ifPresent(account -> {
            account.setIsActive(false);
            accountRepository.save(account);
        });

        // 새 계좌 생성
        VirtualAccount account = VirtualAccount.builder()
                .accountName("모의투자 계좌")
                .initialBalance(balance)
                .currentBalance(balance)
                .totalInvested(BigDecimal.ZERO)
                .totalEvaluation(BigDecimal.ZERO)
                .isActive(true)
                .build();

        accountRepository.save(account);
        log.info("가상 계좌 초기화 완료: {} - 초기자본 {}원", account.getId(), balance);

        return getAccountSummary();
    }

    /**
     * 활성 계좌 조회 (없으면 자동 생성)
     */
    public VirtualAccount getOrCreateActiveAccount() {
        return accountRepository.findByIsActiveTrue()
                .orElseGet(() -> {
                    log.info("활성 계좌가 없어 새로 생성합니다.");
                    VirtualAccount account = VirtualAccount.builder()
                            .accountName("모의투자 계좌")
                            .initialBalance(INITIAL_BALANCE)
                            .currentBalance(INITIAL_BALANCE)
                            .totalInvested(BigDecimal.ZERO)
                            .totalEvaluation(BigDecimal.ZERO)
                            .isActive(true)
                            .build();
                    return accountRepository.save(account);
                });
    }

    /**
     * 매수 처리
     */
    @Override
    public TradeHistoryDto buy(String stockCode, BigDecimal price, Integer quantity, String reason) {
        VirtualAccount account = getOrCreateActiveAccount();

        // 종목명 조회
        String stockName = getStockName(stockCode);

        // 총 금액 계산
        BigDecimal totalAmount = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal commission = totalAmount.multiply(COMMISSION_RATE).setScale(0, RoundingMode.CEILING);
        BigDecimal requiredAmount = totalAmount.add(commission);

        // 잔액 확인
        if (account.getCurrentBalance().compareTo(requiredAmount) < 0) {
            throw new IllegalStateException("잔액이 부족합니다. 필요: " + requiredAmount + "원, 잔액: " + account.getCurrentBalance() + "원");
        }

        // 현금 차감
        account.setCurrentBalance(account.getCurrentBalance().subtract(requiredAmount));

        // 포트폴리오 업데이트
        Optional<VirtualPortfolio> existingPortfolio = portfolioRepository
                .findByAccountIdAndStockCode(account.getId(), stockCode);

        if (existingPortfolio.isPresent()) {
            // 기존 보유 종목 - 평균 매입가 계산
            VirtualPortfolio portfolio = existingPortfolio.get();
            BigDecimal existingTotal = portfolio.getAveragePrice()
                    .multiply(BigDecimal.valueOf(portfolio.getQuantity()));
            BigDecimal newTotal = existingTotal.add(totalAmount);
            int newQuantity = portfolio.getQuantity() + quantity;
            BigDecimal newAvgPrice = newTotal.divide(BigDecimal.valueOf(newQuantity), 0, RoundingMode.HALF_UP);

            portfolio.setQuantity(newQuantity);
            portfolio.setAveragePrice(newAvgPrice);
            portfolio.setCurrentPrice(price);
            portfolioRepository.save(portfolio);
        } else {
            // 신규 종목
            VirtualPortfolio portfolio = VirtualPortfolio.builder()
                    .accountId(account.getId())
                    .stockCode(stockCode)
                    .stockName(stockName)
                    .quantity(quantity)
                    .averagePrice(price)
                    .currentPrice(price)
                    .build();
            portfolioRepository.save(portfolio);
        }

        // 거래 내역 저장
        VirtualTradeHistory trade = VirtualTradeHistory.builder()
                .accountId(account.getId())
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

        // 계좌 총 투자금액 업데이트
        updateAccountTotals(account);
        accountRepository.save(account);

        log.info("[모의투자] 매수 완료: {} ({}) x {} @ {}원, 수수료: {}원, 잔고: {}원",
                stockName, stockCode, quantity, price, commission, account.getCurrentBalance());

        // 텔레그램 알림
        sendBuyAlert(stockName, stockCode, price, quantity, account.getCurrentBalance());

        return toTradeHistoryDto(trade);
    }

    /**
     * 매도 처리
     */
    @Override
    public TradeHistoryDto sell(String stockCode, BigDecimal price, Integer quantity, String reason) {
        VirtualAccount account = getOrCreateActiveAccount();

        // 보유 종목 확인
        VirtualPortfolio portfolio = portfolioRepository
                .findByAccountIdAndStockCode(account.getId(), stockCode)
                .orElseThrow(() -> new IllegalStateException("보유하지 않은 종목입니다: " + stockCode));

        if (portfolio.getQuantity() < quantity) {
            throw new IllegalStateException("보유 수량이 부족합니다. 보유: " + portfolio.getQuantity() + ", 매도 요청: " + quantity);
        }

        // 총 금액 계산
        BigDecimal totalAmount = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal commission = totalAmount.multiply(COMMISSION_RATE).setScale(0, RoundingMode.CEILING);
        BigDecimal tax = totalAmount.multiply(TAX_RATE).setScale(0, RoundingMode.CEILING);
        BigDecimal netAmount = totalAmount.subtract(commission).subtract(tax);

        // 실현손익 계산
        BigDecimal investedAmount = portfolio.getAveragePrice().multiply(BigDecimal.valueOf(quantity));
        BigDecimal profitLoss = netAmount.subtract(investedAmount);

        // 현금 증가
        account.setCurrentBalance(account.getCurrentBalance().add(netAmount));

        // 포트폴리오 업데이트
        int remainingQuantity = portfolio.getQuantity() - quantity;
        if (remainingQuantity == 0) {
            portfolioRepository.delete(portfolio);
        } else {
            portfolio.setQuantity(remainingQuantity);
            portfolio.setCurrentPrice(price);
            portfolioRepository.save(portfolio);
        }

        // 거래 내역 저장
        VirtualTradeHistory trade = VirtualTradeHistory.builder()
                .accountId(account.getId())
                .stockCode(stockCode)
                .stockName(portfolio.getStockName())
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

        // 계좌 총 투자금액 업데이트
        updateAccountTotals(account);
        accountRepository.save(account);

        log.info("[모의투자] 매도 완료: {} ({}) x {} @ {}원, 손익: {}원, 사유: {}, 잔고: {}원",
                portfolio.getStockName(), stockCode, quantity, price, profitLoss, reason, account.getCurrentBalance());

        // 텔레그램 알림
        sendSellAlert(portfolio.getStockName(), stockCode, price, quantity, profitLoss, reason);

        return toTradeHistoryDto(trade);
    }

    /**
     * 계좌 요약 조회
     */
    @Override
    @Transactional(readOnly = true)
    public AccountSummaryDto getAccountSummary() {
        VirtualAccount account = getOrCreateActiveAccount();

        // 포트폴리오 현재가 업데이트 및 평가금액 계산
        List<VirtualPortfolio> portfolios = portfolioRepository.findByAccountId(account.getId());

        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalEvaluation = BigDecimal.ZERO;

        for (VirtualPortfolio portfolio : portfolios) {
            BigDecimal invested = portfolio.getAveragePrice().multiply(BigDecimal.valueOf(portfolio.getQuantity()));
            BigDecimal evaluation = portfolio.getCurrentPrice().multiply(BigDecimal.valueOf(portfolio.getQuantity()));
            totalInvested = totalInvested.add(invested);
            totalEvaluation = totalEvaluation.add(evaluation);
        }

        // 실현손익
        BigDecimal realizedProfitLoss = tradeHistoryRepository.sumRealizedProfitLoss(account.getId());
        if (realizedProfitLoss == null) realizedProfitLoss = BigDecimal.ZERO;

        // 평가손익
        BigDecimal unrealizedProfitLoss = totalEvaluation.subtract(totalInvested);

        // 총 손익
        BigDecimal totalProfitLoss = realizedProfitLoss.add(unrealizedProfitLoss);

        // 총 자산 = 현금 + 평가금액
        BigDecimal totalAsset = account.getCurrentBalance().add(totalEvaluation);

        // 수익률
        BigDecimal totalProfitRate = BigDecimal.ZERO;
        if (account.getInitialBalance().compareTo(BigDecimal.ZERO) > 0) {
            totalProfitRate = totalAsset.subtract(account.getInitialBalance())
                    .divide(account.getInitialBalance(), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }

        // 거래 통계
        long totalTradeCount = tradeHistoryRepository.countSellTrades(account.getId());
        long winCount = tradeHistoryRepository.countWinningTrades(account.getId());
        long loseCount = tradeHistoryRepository.countLosingTrades(account.getId());
        BigDecimal winRate = totalTradeCount > 0
                ? BigDecimal.valueOf(winCount).divide(BigDecimal.valueOf(totalTradeCount), 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        // 오늘 거래 수
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIDNIGHT);
        long todayTradeCount = tradeHistoryRepository.countTodayTrades(account.getId(), todayStart);

        return AccountSummaryDto.builder()
                .accountId(account.getId())
                .accountName(account.getAccountName())
                .initialBalance(account.getInitialBalance())
                .currentBalance(account.getCurrentBalance())
                .totalInvested(totalInvested)
                .totalEvaluation(totalEvaluation)
                .totalProfitLoss(totalProfitLoss)
                .totalProfitRate(totalProfitRate)
                .realizedProfitLoss(realizedProfitLoss)
                .unrealizedProfitLoss(unrealizedProfitLoss)
                .holdingCount(portfolios.size())
                .totalTradeCount(totalTradeCount)
                .winCount(winCount)
                .loseCount(loseCount)
                .winRate(winRate)
                .todayTradeCount(todayTradeCount)
                .isActive(account.getIsActive())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }

    /**
     * 포트폴리오 조회
     */
    @Override
    @Transactional(readOnly = true)
    public List<PortfolioItemDto> getPortfolio() {
        VirtualAccount account = getOrCreateActiveAccount();
        List<VirtualPortfolio> portfolios = portfolioRepository.findByAccountId(account.getId());

        return portfolios.stream()
                .map(this::toPortfolioItemDto)
                .collect(Collectors.toList());
    }

    /**
     * 거래 내역 조회 (페이징)
     */
    @Transactional(readOnly = true)
    public Page<TradeHistoryDto> getTradeHistory(int page, int size) {
        VirtualAccount account = getOrCreateActiveAccount();
        Pageable pageable = PageRequest.of(page, size);
        Page<VirtualTradeHistory> trades = tradeHistoryRepository
                .findByAccountIdOrderByTradeDateDesc(account.getId(), pageable);

        return trades.map(this::toTradeHistoryDto);
    }

    /**
     * 거래 통계 조회
     */
    @Transactional(readOnly = true)
    public TradeStatisticsDto getStatistics() {
        VirtualAccount account = getOrCreateActiveAccount();

        long buyCount = tradeHistoryRepository.countBuyTrades(account.getId());
        long sellCount = tradeHistoryRepository.countSellTrades(account.getId());
        long winCount = tradeHistoryRepository.countWinningTrades(account.getId());
        long loseCount = tradeHistoryRepository.countLosingTrades(account.getId());
        BigDecimal totalRealizedProfitLoss = tradeHistoryRepository.sumRealizedProfitLoss(account.getId());

        BigDecimal winRate = sellCount > 0
                ? BigDecimal.valueOf(winCount).divide(BigDecimal.valueOf(sellCount), 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIDNIGHT);
        long todayTrades = tradeHistoryRepository.countTodayTrades(account.getId(), todayStart);

        return TradeStatisticsDto.builder()
                .totalTrades(buyCount + sellCount)
                .buyCount(buyCount)
                .sellCount(sellCount)
                .winCount(winCount)
                .loseCount(loseCount)
                .winRate(winRate)
                .totalRealizedProfitLoss(totalRealizedProfitLoss != null ? totalRealizedProfitLoss : BigDecimal.ZERO)
                .todayTrades(todayTrades)
                .build();
    }

    /**
     * 포트폴리오 현재가 업데이트
     */
    @Override
    public void updatePortfolioPrices() {
        VirtualAccount account = getOrCreateActiveAccount();
        List<VirtualPortfolio> portfolios = portfolioRepository.findByAccountId(account.getId());

        if (portfolios.isEmpty()) {
            return;
        }

        // 종목코드 리스트 추출
        List<String> stockCodes = portfolios.stream()
                .map(VirtualPortfolio::getStockCode)
                .collect(Collectors.toList());

        // 일괄 시세 조회
        Map<String, StockPriceDto> prices = stockPriceService.getStockPrices(stockCodes);

        for (VirtualPortfolio portfolio : portfolios) {
            StockPriceDto priceDto = prices.get(portfolio.getStockCode());
            if (priceDto != null && priceDto.getCurrentPrice() != null) {
                portfolio.setCurrentPrice(priceDto.getCurrentPrice());

                // 손익 계산
                BigDecimal invested = portfolio.getAveragePrice().multiply(BigDecimal.valueOf(portfolio.getQuantity()));
                BigDecimal evaluation = priceDto.getCurrentPrice().multiply(BigDecimal.valueOf(portfolio.getQuantity()));
                portfolio.setProfitLoss(evaluation.subtract(invested));

                // 손익률 계산
                if (invested.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal profitRate = portfolio.getProfitLoss()
                            .divide(invested, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));
                    portfolio.setProfitRate(profitRate);
                }

                portfolioRepository.save(portfolio);
            }
        }

        // 계좌 평가금액 업데이트
        updateAccountTotals(account);
        accountRepository.save(account);

        log.debug("포트폴리오 현재가 업데이트 완료: {}개 종목", portfolios.size());
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
     * 계좌 총계 업데이트
     */
    private void updateAccountTotals(VirtualAccount account) {
        List<VirtualPortfolio> portfolios = portfolioRepository.findByAccountId(account.getId());

        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalEvaluation = BigDecimal.ZERO;

        for (VirtualPortfolio portfolio : portfolios) {
            BigDecimal invested = portfolio.getAveragePrice().multiply(BigDecimal.valueOf(portfolio.getQuantity()));
            BigDecimal evaluation = portfolio.getCurrentPrice().multiply(BigDecimal.valueOf(portfolio.getQuantity()));
            totalInvested = totalInvested.add(invested);
            totalEvaluation = totalEvaluation.add(evaluation);
        }

        account.setTotalInvested(totalInvested);
        account.setTotalEvaluation(totalEvaluation);
    }

    /**
     * 매수 알림 발송
     */
    private void sendBuyAlert(String stockName, String stockCode, BigDecimal price, Integer quantity, BigDecimal balance) {
        if (!telegramService.isEnabled()) return;

        String message = String.format(
                """
                <b>📈 [모의투자] 매수 체결!</b>

                📊 <b>%s</b> (%s)
                💰 %s원 x %d주
                💵 총 금액: %s원

                🏦 잔고: <b>%s원</b>

                ⏰ %s
                ━━━━━━━━━━━━━━━━
                🤖 MyPlatform 모의투자
                """,
                stockName, stockCode,
                formatNumber(price), quantity,
                formatNumber(price.multiply(BigDecimal.valueOf(quantity))),
                formatNumber(balance),
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        );

        telegramService.sendMessage(message);
    }

    /**
     * 매도 알림 발송
     */
    private void sendSellAlert(String stockName, String stockCode, BigDecimal price, Integer quantity, BigDecimal profitLoss, String reason) {
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
                <b>📉 [모의투자] 매도 체결!</b>

                📊 <b>%s</b> (%s)
                💰 %s원 x %d주
                📋 사유: %s

                %s 손익: <b>%s%s원</b>

                ⏰ %s
                ━━━━━━━━━━━━━━━━
                🤖 MyPlatform 모의투자
                """,
                stockName, stockCode,
                formatNumber(price), quantity,
                reasonText,
                profitEmoji, profitSign, formatNumber(profitLoss),
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        );

        telegramService.sendMessage(message);
    }

    /**
     * DTO 변환 - PortfolioItem
     */
    private PortfolioItemDto toPortfolioItemDto(VirtualPortfolio portfolio) {
        BigDecimal totalInvested = portfolio.getAveragePrice().multiply(BigDecimal.valueOf(portfolio.getQuantity()));
        BigDecimal totalEvaluation = portfolio.getCurrentPrice().multiply(BigDecimal.valueOf(portfolio.getQuantity()));

        return PortfolioItemDto.builder()
                .id(portfolio.getId())
                .stockCode(portfolio.getStockCode())
                .stockName(portfolio.getStockName())
                .quantity(portfolio.getQuantity())
                .averagePrice(portfolio.getAveragePrice())
                .currentPrice(portfolio.getCurrentPrice())
                .totalInvested(totalInvested)
                .totalEvaluation(totalEvaluation)
                .profitLoss(portfolio.getProfitLoss())
                .profitRate(portfolio.getProfitRate())
                .purchaseDate(portfolio.getPurchaseDate())
                .updatedAt(portfolio.getUpdatedAt())
                .build();
    }

    /**
     * DTO 변환 - TradeHistory
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
     * 숫자 포맷팅
     */
    private String formatNumber(BigDecimal value) {
        if (value == null) return "0";
        return String.format("%,.0f", value);
    }

    /**
     * 매매 모드 반환
     */
    @Override
    public String getTradeMode() {
        return "VIRTUAL";
    }
}
