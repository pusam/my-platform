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

import jakarta.annotation.PostConstruct;

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
    private static final BigDecimal TAX_RATE = new BigDecimal("0.0018"); // 0.18% (2023년~ 증권거래세)
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000000"); // 1,000만원

    /**
     * 서버 시작 시 계좌 상태 확인 (디버깅용)
     */
    @PostConstruct
    public void checkAccountStatusOnStartup() {
        long totalAccounts = accountRepository.count();
        Optional<VirtualAccount> activeAccount = accountRepository.findFirstByIsActiveTrueOrderByIdDesc();

        log.info("========== 모의투자 계좌 상태 확인 ==========");
        log.info("전체 계좌 수: {}개", totalAccounts);

        if (activeAccount.isPresent()) {
            VirtualAccount account = activeAccount.get();
            BigDecimal totalProfit = account.getCurrentBalance().subtract(account.getInitialBalance());
            log.info("✅ 활성 계좌 존재: ID={}, 초기자본={}원, 현재잔액={}원, 총손익={}원, 생성일={}",
                    account.getId(),
                    account.getInitialBalance(),
                    account.getCurrentBalance(),
                    totalProfit,
                    account.getCreatedAt());

            // 포트폴리오 수도 확인
            long portfolioCount = portfolioRepository.countByAccountId(account.getId());
            long tradeCount = tradeHistoryRepository.countByAccountId(account.getId());
            log.info("   보유종목: {}개, 거래내역: {}건", portfolioCount, tradeCount);
        } else {
            log.warn("⚠️ 활성 계좌 없음! (전체 {}개 계좌 중 is_active=true인 계좌 없음)", totalAccounts);

            // 활성 계좌가 없는 이유 분석
            if (totalAccounts == 0) {
                log.warn("   → 원인: DB에 계좌 데이터가 없음 (첫 실행 또는 DB 초기화됨)");
            } else {
                log.warn("   → 원인: 모든 계좌가 비활성화됨 (is_active=false)");
                // 가장 최근 계좌 정보 출력
                accountRepository.findAll().stream()
                        .max((a, b) -> a.getId().compareTo(b.getId()))
                        .ifPresent(lastAccount -> {
                            log.warn("   → 마지막 계좌: ID={}, is_active={}, 생성일={}",
                                    lastAccount.getId(), lastAccount.getIsActive(), lastAccount.getCreatedAt());
                        });
            }

            // 텔레그램 알림 (계좌 없음 경고)
            if (telegramService.isEnabled()) {
                telegramService.sendSignal(
                        "<b>⚠️ [모의투자] 서버 시작 - 활성 계좌 없음</b>\n\n" +
                        "전체 계좌 수: " + totalAccounts + "개\n" +
                        "활성 계좌: 없음\n\n" +
                        "첫 거래 시 새 계좌가 자동 생성됩니다.\n" +
                        "⏰ " + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n\n" +
                        "━━━━━━━━━━━━━━━━\n" +
                        "🤖 MyPlatform 모의투자"
                );
            }
        }
        log.info("=============================================");
    }

    /**
     * 계좌 초기화 (사용자 지정 금액)
     * ⚠️ 주의: 이 메서드는 새 계좌를 생성하며, 기존 거래내역/포트폴리오는 이전 계좌에 남습니다.
     * @param initialAmount 초기 자본금 (null이면 기본값 1,000만원)
     */
    public AccountSummaryDto initializeAccount(BigDecimal initialAmount) {
        // 기본값 처리
        BigDecimal balance = (initialAmount != null && initialAmount.compareTo(BigDecimal.ZERO) > 0)
                ? initialAmount
                : INITIAL_BALANCE;

        // 기존 활성 계좌 정보 로깅 (디버깅용)
        accountRepository.findFirstByIsActiveTrueOrderByIdDesc().ifPresent(oldAccount -> {
            log.warn("⚠️ [계좌초기화] 기존 계좌 비활성화: ID={}, 잔액={}원, 총손익={}원",
                    oldAccount.getId(),
                    oldAccount.getCurrentBalance(),
                    oldAccount.getCurrentBalance().subtract(oldAccount.getInitialBalance()));
            oldAccount.setIsActive(false);
            accountRepository.save(oldAccount);
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
        log.warn("⚠️ [계좌초기화] 새 계좌 생성 완료: ID={}, 초기자본={}원 (기존 거래내역은 이전 계좌에 보존됨)",
                account.getId(), balance);

        // 텔레그램 알림 (초기화 추적용)
        if (telegramService.isEnabled()) {
            telegramService.sendSignal(
                    "<b>⚠️ [모의투자] 계좌 초기화됨</b>\n\n" +
                    "새 계좌 ID: " + account.getId() + "\n" +
                    "초기 자본금: " + String.format("%,d", balance.longValue()) + "원\n" +
                    "⏰ " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n\n" +
                    "━━━━━━━━━━━━━━━━\n" +
                    "🤖 MyPlatform 모의투자"
            );
        }

        return getAccountSummary();
    }

    /**
     * 활성 계좌 조회 (없으면 자동 생성)
     */
    public VirtualAccount getOrCreateActiveAccount() {
        return accountRepository.findFirstByIsActiveTrueOrderByIdDesc()
                .orElseGet(() -> {
                    log.warn("⚠️ [자동생성] 활성 계좌가 없어 새로 생성합니다. (DB 초기화 또는 첫 실행)");
                    VirtualAccount account = VirtualAccount.builder()
                            .accountName("모의투자 계좌")
                            .initialBalance(INITIAL_BALANCE)
                            .currentBalance(INITIAL_BALANCE)
                            .totalInvested(BigDecimal.ZERO)
                            .totalEvaluation(BigDecimal.ZERO)
                            .isActive(true)
                            .build();
                    VirtualAccount saved = accountRepository.save(account);
                    log.warn("⚠️ [자동생성] 새 계좌 생성 완료: ID={}, 초기자본={}원", saved.getId(), INITIAL_BALANCE);
                    return saved;
                });
    }

    /**
     * 활성 계좌 조회 - 비관적 락 적용 (동시성 제어)
     * 매수/매도 등 잔고 변경 시 사용
     */
    private VirtualAccount getOrCreateActiveAccountWithLock() {
        return accountRepository.findFirstByIsActiveTrueWithLock()
                .orElseGet(() -> {
                    log.warn("⚠️ [자동생성-Lock] 활성 계좌가 없어 새로 생성합니다. (DB 초기화 또는 첫 실행)");
                    VirtualAccount account = VirtualAccount.builder()
                            .accountName("모의투자 계좌")
                            .initialBalance(INITIAL_BALANCE)
                            .currentBalance(INITIAL_BALANCE)
                            .totalInvested(BigDecimal.ZERO)
                            .totalEvaluation(BigDecimal.ZERO)
                            .isActive(true)
                            .build();
                    VirtualAccount saved = accountRepository.save(account);
                    log.warn("⚠️ [자동생성-Lock] 새 계좌 생성 완료: ID={}, 초기자본={}원", saved.getId(), INITIAL_BALANCE);
                    return saved;
                });
    }

    /**
     * 매수 처리 (종목명 포함 버전)
     */
    @Override
    public TradeHistoryDto buy(String stockCode, String stockName, BigDecimal price, Integer quantity, String reason) {
        // 비관적 락으로 계좌 조회 (동시 접근 차단)
        VirtualAccount account = getOrCreateActiveAccountWithLock();

        // 종목명이 없거나 종목코드와 같으면 조회 시도
        if (stockName == null || stockName.trim().isEmpty() || stockName.equals(stockCode)) {
            stockName = getStockName(stockCode);
        }

        return executeBuy(account, stockCode, stockName, price, quantity, reason);
    }

    /**
     * 매수 처리 (비관적 락 적용으로 동시성 문제 해결)
     */
    @Override
    public TradeHistoryDto buy(String stockCode, BigDecimal price, Integer quantity, String reason) {
        // 비관적 락으로 계좌 조회 (동시 접근 차단)
        VirtualAccount account = getOrCreateActiveAccountWithLock();

        // 종목명 조회
        String stockName = getStockName(stockCode);

        return executeBuy(account, stockCode, stockName, price, quantity, reason);
    }

    /**
     * 매수 실행 (공통 로직)
     */
    private TradeHistoryDto executeBuy(VirtualAccount account, String stockCode, String stockName, BigDecimal price, Integer quantity, String reason) {

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

        // 포트폴리오 업데이트 (비관적 락 적용)
        Optional<VirtualPortfolio> existingPortfolio = portfolioRepository
                .findByAccountIdAndStockCodeWithLock(account.getId(), stockCode);

        if (existingPortfolio.isPresent()) {
            // 기존 보유 종목 - 평균 매입가 계산 (매수 수수료 포함)
            VirtualPortfolio portfolio = existingPortfolio.get();
            BigDecimal existingTotal = portfolio.getAveragePrice()
                    .multiply(BigDecimal.valueOf(portfolio.getQuantity()));
            BigDecimal newTotal = existingTotal.add(totalAmount).add(commission);
            int newQuantity = portfolio.getQuantity() + quantity;
            BigDecimal newAvgPrice = newTotal.divide(BigDecimal.valueOf(newQuantity), 0, RoundingMode.HALF_UP);

            portfolio.setQuantity(newQuantity);
            portfolio.setAveragePrice(newAvgPrice);
            portfolio.setCurrentPrice(price);
            portfolioRepository.save(portfolio);
        } else {
            // 신규 종목 - 평단가에 매수 수수료 포함
            BigDecimal avgPriceWithCommission = totalAmount.add(commission)
                    .divide(BigDecimal.valueOf(quantity), 0, RoundingMode.HALF_UP);
            VirtualPortfolio portfolio = VirtualPortfolio.builder()
                    .accountId(account.getId())
                    .stockCode(stockCode)
                    .stockName(stockName)
                    .quantity(quantity)
                    .averagePrice(avgPriceWithCommission)
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
     * 매도 처리 (비관적 락 적용으로 동시성 문제 해결)
     */
    @Override
    public TradeHistoryDto sell(String stockCode, BigDecimal price, Integer quantity, String reason) {
        // 비관적 락으로 계좌 조회 (동시 접근 차단)
        VirtualAccount account = getOrCreateActiveAccountWithLock();

        // 보유 종목 확인 (비관적 락 적용)
        VirtualPortfolio portfolio = portfolioRepository
                .findByAccountIdAndStockCodeWithLock(account.getId(), stockCode)
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

        // 거래 통계 통합 조회 (6개 쿼리 → 1개 쿼리로 최적화)
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIDNIGHT);
        List<Object[]> statsResult = tradeHistoryRepository.getTradeStatistics(account.getId(), todayStart);
        Object[] stats = statsResult.isEmpty() ? new Object[6] : statsResult.get(0);

        long buyCount = stats[0] != null ? ((Number) stats[0]).longValue() : 0;
        long sellCount = stats[1] != null ? ((Number) stats[1]).longValue() : 0;
        long winCount = stats[2] != null ? ((Number) stats[2]).longValue() : 0;
        long loseCount = stats[3] != null ? ((Number) stats[3]).longValue() : 0;
        BigDecimal realizedProfitLoss = stats[4] != null ? new BigDecimal(stats[4].toString()) : BigDecimal.ZERO;
        long todayTradeCount = stats[5] != null ? ((Number) stats[5]).longValue() : 0;

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

        // 승률 계산
        long totalTradeCount = sellCount;
        BigDecimal winRate = totalTradeCount > 0
                ? BigDecimal.valueOf(winCount).divide(BigDecimal.valueOf(totalTradeCount), 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

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
     * 거래 통계 조회 (6개 쿼리 → 1개 쿼리로 최적화)
     */
    @Transactional(readOnly = true)
    public TradeStatisticsDto getStatistics() {
        VirtualAccount account = getOrCreateActiveAccount();

        // 거래 통계 통합 조회
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIDNIGHT);
        List<Object[]> statsResult = tradeHistoryRepository.getTradeStatistics(account.getId(), todayStart);
        Object[] stats = statsResult.isEmpty() ? new Object[6] : statsResult.get(0);

        long buyCount = stats[0] != null ? ((Number) stats[0]).longValue() : 0;
        long sellCount = stats[1] != null ? ((Number) stats[1]).longValue() : 0;
        long winCount = stats[2] != null ? ((Number) stats[2]).longValue() : 0;
        long loseCount = stats[3] != null ? ((Number) stats[3]).longValue() : 0;
        BigDecimal totalRealizedProfitLoss = stats[4] != null ? new BigDecimal(stats[4].toString()) : BigDecimal.ZERO;
        long todayTrades = stats[5] != null ? ((Number) stats[5]).longValue() : 0;

        BigDecimal winRate = sellCount > 0
                ? BigDecimal.valueOf(winCount).divide(BigDecimal.valueOf(sellCount), 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        return TradeStatisticsDto.builder()
                .totalTrades(buyCount + sellCount)
                .buyCount(buyCount)
                .sellCount(sellCount)
                .winCount(winCount)
                .loseCount(loseCount)
                .winRate(winRate)
                .totalRealizedProfitLoss(totalRealizedProfitLoss)
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
     * - 빈 문자열도 체크하여 종목코드를 반환하지 않도록 함
     */
    private String getStockName(String stockCode) {
        try {
            StockPriceDto priceDto = stockPriceService.getStockPrice(stockCode);
            if (priceDto != null && priceDto.getStockName() != null && !priceDto.getStockName().trim().isEmpty()) {
                return priceDto.getStockName();
            }
            log.warn("종목명 조회 실패 (빈 값): {} - StockPriceService에서 종목명 없음", stockCode);
        } catch (Exception e) {
            log.warn("종목명 조회 실패: {} - {}", stockCode, e.getMessage());
        }
        return stockCode;
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

        telegramService.sendSignal(message);
    }

    /**
     * 매도 알림 발송
     */
    private void sendSellAlert(String stockName, String stockCode, BigDecimal price, Integer quantity, BigDecimal profitLoss, String reason) {
        if (!telegramService.isEnabled()) return;

        String reasonText = switch (reason) {
            case "STOP_LOSS" -> "🔻 손절";
            case "TAKE_PROFIT", "TAKE_PROFIT_HALF" -> "🔺 익절";
            case "TRAILING_STOP" -> "📊 트레일링스탑";
            case "TIME_CUT" -> "⏱️ 타임컷";
            case "END_OF_DAY" -> "🔔 장마감청산";
            case "AUTO_SELL" -> "🤖 자동매도";
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

        telegramService.sendSignal(message);
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
            case "AUTO_BUY", "SCALPING_ENTRY" -> "자동매수";
            case "STOP_LOSS" -> "손절";
            case "TAKE_PROFIT" -> "익절";
            case "TAKE_PROFIT_HALF" -> "1차익절(절반)";
            case "TRAILING_STOP" -> "트레일링스탑";
            case "TIME_CUT" -> "타임컷";
            case "END_OF_DAY" -> "장마감청산";
            case "AUTO_SELL" -> "자동매도";
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
