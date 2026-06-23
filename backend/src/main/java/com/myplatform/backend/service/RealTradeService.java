package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.PaperTradingDto.*;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.TradingAuditLog;
import com.myplatform.backend.entity.VirtualTradeHistory;
import com.myplatform.backend.repository.VirtualTradeHistoryRepository;
import com.myplatform.backend.service.KoreaInvestmentService.BalanceInfo;
import com.myplatform.backend.service.KoreaInvestmentService.HoldingStock;
import com.myplatform.core.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
@Transactional(rollbackFor = Exception.class)
@RequiredArgsConstructor
@Slf4j
public class RealTradeService implements TradeService {

    private final KoreaInvestmentService kisService;
    private final StockPriceService stockPriceService;
    private final TelegramNotificationService telegramService;
    private final VirtualTradeHistoryRepository tradeHistoryRepository;
    private final TradingSafetyService safetyService;
    private final TradingAuditService auditService;

    // 실전매매용 계좌 ID (가상 ID - 실제 계좌와 구분)
    private static final Long REAL_ACCOUNT_ID = 999999L;

    // ==================== 주문 체결 확인 (B2-A Phase 1) ====================

    /** 주문 체결 상태. UNKNOWN=체결조회 실패(보수적으로 현행 동작 유지). */
    enum FillStatus { FULL, PARTIAL, NONE, UNKNOWN }

    /** 체결 확인 결과 — filledQty=실체결수량, status=판정. */
    record FillResult(int filledQty, FillStatus status) {
        boolean isFull() { return status == FillStatus.FULL; }
        boolean isConfirmedShort() { return status == FillStatus.PARTIAL || status == FillStatus.NONE; }
    }

    /**
     * 주문 체결 판정 (순수 함수 — 테스트 대상).
     * <p>지정가 주문은 부분/미체결 가능. KIS 체결조회(총체결수량)로 실제 체결을 판정한다.
     * 조회 실패(null)는 UNKNOWN → 호출측은 보수적으로 "요청수량 체결"로 간주해 현행 동작을 보존
     * (체결조회가 틀려도 최악이 현행과 동일하도록).
     *
     * @param requestedQty 주문 수량
     * @param totCcldQty   KIS 총체결수량 (조회 실패 시 null)
     */
    static FillResult resolveFill(int requestedQty, Integer totCcldQty) {
        if (totCcldQty == null) return new FillResult(requestedQty, FillStatus.UNKNOWN);
        if (totCcldQty <= 0) return new FillResult(0, FillStatus.NONE);
        if (totCcldQty >= requestedQty) return new FillResult(requestedQty, FillStatus.FULL);
        return new FillResult(totCcldQty, FillStatus.PARTIAL);
    }

    /**
     * 주문 체결 확인 — KIS 체결조회(inquireDailyCcld)를 짧게 폴링(최대 3회, ~1.4s)해 실체결 판정.
     * <p>봇이 매도/매수 직후 호출. 클래스가 @Transactional 이라 <b>NOT_SUPPORTED</b> 로 트랜잭션 밖에서
     * 실행 — 폴링 sleep 이 DB 트랜잭션을 잡지 않도록. 조회 실패는 UNKNOWN(보수적, 현행 동작 보존).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public FillResult confirmFill(String stockCode, String orderNo, int requestedQty) {
        Integer ccld = null;
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                try { Thread.sleep(700L); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            ccld = kisService.inquireDailyCcld(stockCode, orderNo);
            if (ccld != null && ccld >= requestedQty) break;  // 전량 확인되면 즉시 종료
        }
        return resolveFill(requestedQty, ccld);
    }

    // 캐시된 잔고 정보 — 매수/매도 직전엔 force=true 로 항상 재조회.
    // 표시·통계 용도는 30초 캐시 (KIS rate limit 완화).
    private volatile BalanceInfo cachedBalance;
    private volatile LocalDateTime lastBalanceUpdate;
    private static final long BALANCE_CACHE_SECONDS = 30;

    /**
     * KIS 응답이 불확실 (timeout / null / RuntimeException) 한 경우, 주문이 실제로
     * 들어갔을 가능성을 배제할 수 없으므로 비상 정지를 자동 발동.
     * 봇/사용자가 KIS 화면에서 실제 주문 상태를 확인 후 수동으로 해제해야 함.
     */
    private void triggerKillSwitchOnUncertainty(String action, String stockName, String stockCode, String detail) {
        try {
            if (!safetyService.isKilled()) {
                String reason = String.format("KIS %s 응답 불확실 — %s(%s) — %s. 실제 주문 여부 확인 후 해제하세요.",
                        action, stockName, stockCode, detail);
                safetyService.enable(reason, "system-auto");
                log.error("⛔ 자동 비상정지 발동: {}", reason);
                if (telegramService.isEnabled()) {
                    telegramService.sendRisk(String.format(
                            "🚨 <b>자동 비상정지 발동</b>\n\nKIS %s 응답이 불확실합니다.\n종목: %s (%s)\n사유: %s\n\n실제 주문 여부 확인 후 마이페이지에서 해제하세요.",
                            action, stockName, stockCode, detail));
                }
            }
        } catch (Exception ex) {
            log.error("[킬스위치 발동 실패] {}", ex.getMessage(), ex);
        }
    }

    /**
     * KIS 주문 성공 + DB save 실패 → 가장 위험한 비일관 상태.
     * 운영자가 즉시 인지하도록 별도 critical 알림. (kill switch 는 호출자에서 발동)
     */
    private void alertDbInconsistency(String action, String stockName, String stockCode,
                                       Integer quantity, BigDecimal price, String orderNo, Throwable err) {
        log.error("⛔⛔ [DB 불일치] KIS {} 성공했으나 DB save 실패 — {}({}) {}주 @ {}원, 주문번호: {}",
                action, stockName, stockCode, quantity, price, orderNo, err);
        if (telegramService.isEnabled()) {
            try {
                telegramService.sendRisk(String.format(
                        "⛔⛔ <b>[CRITICAL] DB 불일치 감지</b>\n\n" +
                        "KIS %s 주문은 <b>성공</b> 했으나 로컬 DB 저장 실패.\n" +
                        "→ KIS 잔고와 우리 DB 가 어긋난 상태\n\n" +
                        "📌 종목: %s (%s)\n" +
                        "📌 수량: %d주 @ %s원\n" +
                        "📌 KIS 주문번호: %s\n" +
                        "📌 에러: %s\n\n" +
                        "⚠️ 봇 자동 정지됨. KIS HTS 에서 실제 잔고 확인 후\n" +
                        "    DB 수동 보정 → 비상정지 해제 필요.",
                        action, stockName, stockCode, quantity, price, orderNo,
                        err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage()));
            } catch (Exception telegramErr) {
                log.warn("[CRITICAL DB 불일치] 텔레그램 알림 실패: {}", telegramErr.getMessage());
            }
        }
    }

    /** 주문번호는 일반 로그에선 마지막 4자리만. 감사 로그/텔레그램은 전체 사용. */
    private static String maskOrderNo(String orderNo) {
        if (orderNo == null || orderNo.length() <= 4) return orderNo;
        return "****" + orderNo.substring(orderNo.length() - 4);
    }

    private static void validateTradeInput(BigDecimal price, Integer quantity) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("가격은 0보다 커야 합니다: " + price);
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다: " + quantity);
        }
    }

    /**
     * 실전 매수 처리 (종목명 포함 버전)
     */
    @Override
    public TradeHistoryDto buy(String stockCode, String stockName, BigDecimal price, Integer quantity, String reason) {
        log.info("[실전매매] 매수 주문 시작: {} ({}) x {} @ {}원", stockName, stockCode, quantity, price);

        // KIS API 설정 확인
        if (!kisService.isRealTradingConfigured()) {
            throw new IllegalStateException("실전매매 API가 설정되지 않았습니다. 계좌 정보를 확인하세요.");
        }

        // 종목명이 없거나 종목코드와 같으면 조회 시도
        if (stockName == null || stockName.trim().isEmpty() || stockName.equals(stockCode)) {
            stockName = getStockName(stockCode);
        }

        return executeBuy(stockCode, stockName, price, quantity, reason);
    }

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

        return executeBuy(stockCode, stockName, price, quantity, reason);
    }

    /**
     * 매수 실행 (공통 로직)
     */
    private TradeHistoryDto executeBuy(String stockCode, String stockName, BigDecimal price, Integer quantity, String reason) {
        // 입력값 방어 (KIS API 전에 필수 1차 검증)
        validateTradeInput(price, quantity);

        BigDecimal totalCheck = price.multiply(BigDecimal.valueOf(quantity));

        // 1) 안전장치 체크 (킬스위치 + 일일 한도)
        TradingSafetyService.Decision decision = safetyService.checkBuy(totalCheck);
        if (!decision.allowed()) {
            auditService.blocked(TradingAuditLog.Action.BUY, TradingAuditLog.Mode.REAL,
                    stockCode, stockName, quantity, price, reason, decision.reason());
            log.warn("[실전매매] 매수 차단: {} ({}) — {}", stockName, stockCode, decision.reason());
            throw new IllegalStateException("매수 차단: " + decision.reason());
        }

        // 2) 매수 직전 KIS 실시간 잔고 확인 — 캐시로 인한 중복 매수 방지
        BalanceInfo realtime = getBalanceInfo(true);
        if (realtime == null) {
            auditService.blocked(TradingAuditLog.Action.BUY, TradingAuditLog.Mode.REAL,
                    stockCode, stockName, quantity, price, reason, "잔고 조회 실패");
            throw new IllegalStateException("잔고 조회 실패 — 안전을 위해 매수 중단");
        }
        BigDecimal available = realtime.getAvailableBalance() != null
                ? realtime.getAvailableBalance() : BigDecimal.ZERO;
        if (available.compareTo(totalCheck) < 0) {
            String why = String.format("실시간 잔고 부족: 가용 %,.0f원 < 시도 %,.0f원", available, totalCheck);
            auditService.blocked(TradingAuditLog.Action.BUY, TradingAuditLog.Mode.REAL,
                    stockCode, stockName, quantity, price, reason, why);
            throw new IllegalStateException(why);
        }

        // 3) 감사 로그 시작
        TradingAuditService.Ctx audit = auditService.start(
                TradingAuditLog.Action.BUY, TradingAuditLog.Mode.REAL,
                stockCode, stockName, quantity, price, reason);

        // 3) KIS API 매수 주문
        //    예외/null 응답은 "주문이 들어갔는지 알 수 없음" 상태 → 봇 재시도 시 중복 주문 위험.
        //    이 경우 자동 킬스위치 발동해서 재시도 자체를 차단한다 (수동 확인 후 해제).
        //
        //    [의도적으로 backoff 재시도 안 함]
        //    KIS 주문 API 는 멱등 보장 안 됨 — timeout 후 재시도하면 같은 주문 2번 체결 위험.
        //    네트워크 일시 장애와 서버 처리 후 응답 손실은 클라이언트에서 구분 불가능하므로,
        //    "확실히 안 들어간 케이스"(HttpStatusCodeException → rt_cd 파싱)만 KoreaInvestmentService
        //    가 정상 응답으로 변환해서 돌려주고, 그 외엔 모두 불확실로 간주해 kill switch.
        JsonNode orderResult;
        try {
            orderResult = kisService.buyStock(stockCode, quantity, price);
        } catch (RuntimeException e) {
            auditService.failure(audit, null, "EXCEPTION", e);
            triggerKillSwitchOnUncertainty("매수", stockName, stockCode, e.getMessage());
            throw e;
        }

        if (orderResult == null) {
            auditService.failure(audit, null, "API 응답 null", null);
            triggerKillSwitchOnUncertainty("매수", stockName, stockCode, "API 응답 null");
            throw new IllegalStateException("매수 주문 API 호출 실패");
        }

        String rtCd = orderResult.has("rt_cd") ? orderResult.get("rt_cd").asText() : "";
        String msg = orderResult.has("msg1") ? orderResult.get("msg1").asText() : "";
        if (!"0".equals(rtCd)) {
            // KIS 가 명시적으로 거부 — 주문 안 들어간 게 확실하므로 킬스위치는 발동하지 않음
            auditService.failure(audit, rtCd, msg, null);
            throw new IllegalStateException("매수 주문 실패: " + msg);
        }

        // 주문번호 추출
        String orderNo = "";
        if (orderResult.has("output") && orderResult.get("output").has("ODNO")) {
            orderNo = orderResult.get("output").get("ODNO").asText();
        }

        // 감사 로그 success
        auditService.success(audit, rtCd, msg, orderNo);

        // 총 금액 계산
        BigDecimal totalAmount = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal commission = totalAmount.multiply(new BigDecimal("0.00015"))
                .setScale(0, RoundingMode.CEILING);

        // 거래 내역 저장 (DB)
        // ★ KIS 주문 성공 후 DB save 실패는 가장 위험한 상태:
        //   - KIS 에 실제 주문 들어감 (rollback 불가)
        //   - DB 는 트랜잭션 rollback 으로 비어있음
        //   - 봇이 다음 사이클에 같은 종목 평가 → 또 매수 발사 (이중 주문 위험)
        //   → 즉시 kill switch + RISK 알림으로 봇 정지. 운영자 수동 보정 필요.
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
                .tradeDate(DateTimeUtil.kstNow())
                .build();
        try {
            tradeHistoryRepository.save(trade);
        } catch (RuntimeException dbErr) {
            alertDbInconsistency("매수", stockName, stockCode, quantity, price, orderNo, dbErr);
            triggerKillSwitchOnUncertainty("매수-DB저장",
                    stockName, stockCode, "DB save 실패: " + dbErr.getMessage());
            throw dbErr;
        }

        log.info("[실전매매] 매수 주문 완료: {} ({}) x {} @ {}원, 주문번호: {}",
                stockName, stockCode, quantity, price, maskOrderNo(orderNo));

        // 캐시 무효화
        cachedBalance = null;

        // 텔레그램 알림
        sendRealBuyAlert(stockName, stockCode, price, quantity, orderNo);

        TradeHistoryDto dto = toTradeHistoryDto(trade);
        dto.setOrderNo(orderNo);  // B2-A: 봇이 체결조회(confirmFill)에 사용
        return dto;
    }

    /**
     * 실전 매도 처리
     */
    @Override
    public TradeHistoryDto sell(String stockCode, BigDecimal price, Integer quantity, String reason) {
        log.info("[실전매매] 매도 주문 시작: {} x {} @ {}원", stockCode, quantity, price);

        // 입력값 방어 (음수 수량/가격 거부)
        validateTradeInput(price, quantity);

        // KIS API 설정 확인
        if (!kisService.isRealTradingConfigured()) {
            throw new IllegalStateException("실전매매 API가 설정되지 않았습니다. 계좌 정보를 확인하세요.");
        }

        // 종목명 조회
        String stockName = getStockName(stockCode);

        // 보유 확인 — 매도는 항상 캐시 무시하고 KIS 에서 실시간 조회
        BalanceInfo balance = getBalanceInfo(true);
        if (balance == null) {
            throw new IllegalStateException("잔고 조회 실패 — 안전을 위해 매도 중단");
        }
        HoldingStock holding = balance.getHoldings() == null ? null
                : balance.getHoldings().stream()
                    .filter(h -> stockCode.equals(h.getStockCode()))
                    .findFirst()
                    .orElse(null);

        if (holding == null || holding.getQuantity() < quantity) {
            int availableQty = holding != null ? holding.getQuantity() : 0;
            throw new IllegalStateException(
                    String.format("보유 수량 부족: 보유 %d주, 매도 요청 %d주", availableQty, quantity));
        }

        // 평균 매입가 (손익 계산용)
        BigDecimal avgPrice = holding.getAveragePrice() != null
                ? holding.getAveragePrice()
                : BigDecimal.ZERO;

        // 1) 안전장치 체크 (매도는 한도 적용 X — 손절은 항상 가능. 킬스위치만 체크)
        TradingSafetyService.Decision decision = safetyService.checkSell();
        if (!decision.allowed()) {
            auditService.blocked(TradingAuditLog.Action.SELL, TradingAuditLog.Mode.REAL,
                    stockCode, stockName, quantity, price, reason, decision.reason());
            log.warn("[실전매매] 매도 차단: {} ({}) — {}", stockName, stockCode, decision.reason());
            throw new IllegalStateException("매도 차단: " + decision.reason());
        }

        // 2) 감사 로그 시작
        TradingAuditService.Ctx audit = auditService.start(
                TradingAuditLog.Action.SELL, TradingAuditLog.Mode.REAL,
                stockCode, stockName, quantity, price, reason);

        // 3) KIS API 매도 주문 — 매수와 동일한 안전장치
        JsonNode orderResult;
        try {
            orderResult = kisService.sellStock(stockCode, quantity, price);
        } catch (RuntimeException e) {
            auditService.failure(audit, null, "EXCEPTION", e);
            triggerKillSwitchOnUncertainty("매도", stockName, stockCode, e.getMessage());
            throw e;
        }

        if (orderResult == null) {
            auditService.failure(audit, null, "API 응답 null", null);
            triggerKillSwitchOnUncertainty("매도", stockName, stockCode, "API 응답 null");
            throw new IllegalStateException("매도 주문 API 호출 실패");
        }

        String rtCd = orderResult.has("rt_cd") ? orderResult.get("rt_cd").asText() : "";
        String msg = orderResult.has("msg1") ? orderResult.get("msg1").asText() : "";
        if (!"0".equals(rtCd)) {
            // KIS 명시적 거부 — 주문 안 들어감
            auditService.failure(audit, rtCd, msg, null);
            throw new IllegalStateException("매도 주문 실패: " + msg);
        }

        // 주문번호 추출
        String orderNo = "";
        if (orderResult.has("output") && orderResult.get("output").has("ODNO")) {
            orderNo = orderResult.get("output").get("ODNO").asText();
        }

        auditService.success(audit, rtCd, msg, orderNo);

        // 총 금액 및 손익 계산
        BigDecimal totalAmount = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal commission = totalAmount.multiply(new BigDecimal("0.00015"))
                .setScale(0, RoundingMode.CEILING);
        BigDecimal tax = totalAmount.multiply(new BigDecimal("0.002"))
                .setScale(0, RoundingMode.CEILING);
        BigDecimal netAmount = totalAmount.subtract(commission).subtract(tax);
        BigDecimal investedAmount = avgPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal profitLoss = netAmount.subtract(investedAmount);

        // 거래 내역 저장 (DB) — 매수와 동일한 정책: KIS 성공 + DB 실패 = 즉시 kill switch
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
                .tradeDate(DateTimeUtil.kstNow())
                .build();
        try {
            tradeHistoryRepository.save(trade);
        } catch (RuntimeException dbErr) {
            alertDbInconsistency("매도", stockName, stockCode, quantity, price, orderNo, dbErr);
            triggerKillSwitchOnUncertainty("매도-DB저장",
                    stockName, stockCode, "DB save 실패: " + dbErr.getMessage());
            throw dbErr;
        }

        log.info("[실전매매] 매도 주문 완료: {} ({}) x {} @ {}원, 손익: {}원, 주문번호: {}",
                stockName, stockCode, quantity, price, profitLoss, maskOrderNo(orderNo));

        // 캐시 무효화
        cachedBalance = null;

        // 텔레그램 알림
        sendRealSellAlert(stockName, stockCode, price, quantity, profitLoss, reason, orderNo);

        TradeHistoryDto dto = toTradeHistoryDto(trade);
        dto.setOrderNo(orderNo);  // B2-A: 봇이 체결조회(confirmFill)에 사용
        return dto;
    }

    /**
     * 계좌 요약 조회 (KIS API 잔고 기반)
     */
    @Override
    public AccountSummaryDto getAccountSummary() {
        BalanceInfo balance = getBalanceInfo();
        if (balance == null && cachedBalance != null) {
            // 직전 성공 캐시로 폴백 — UI 가 "0원" 으로 플리킹하지 않게
            balance = cachedBalance;
        }
        if (balance == null) {
            // 정말 한 번도 성공 못했으면 빈 DTO (예외 대신) — UI 가 최소한 구조는 받음
            log.warn("[실전매매] 잔고 정보 조회 실패 — 빈 요약 반환");
            return AccountSummaryDto.builder()
                    .accountId(REAL_ACCOUNT_ID)
                    .accountName("실전투자 계좌 (조회 중)")
                    .initialBalance(BigDecimal.ZERO)
                    .currentBalance(BigDecimal.ZERO)
                    .totalInvested(BigDecimal.ZERO)
                    .totalEvaluation(BigDecimal.ZERO)
                    .totalProfitLoss(BigDecimal.ZERO)
                    .totalProfitRate(BigDecimal.ZERO)
                    .realizedProfitLoss(BigDecimal.ZERO)
                    .unrealizedProfitLoss(BigDecimal.ZERO)
                    .holdingCount(0)
                    .isActive(true)
                    .createdAt(DateTimeUtil.kstNow())
                    .updatedAt(DateTimeUtil.kstNow())
                    .build();
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

        // updatedAt 은 실제 KIS 잔고가 성공적으로 조회된 시점(lastBalanceUpdate) 을 반환한다.
        // 캐시 폴백인 경우에도 "마지막 성공 시각" 이 찍혀야 UI 에서 Live/Cached 구분 가능.
        LocalDateTime dataTimestamp = lastBalanceUpdate != null ? lastBalanceUpdate : DateTimeUtil.kstNow();

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
                .createdAt(DateTimeUtil.kstNow())
                .updatedAt(dataTimestamp)
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
                    .updatedAt(DateTimeUtil.kstNow())
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
     * 잔고 정보 조회 (캐싱). 표시·통계 등 비매매 용도.
     */
    private BalanceInfo getBalanceInfo() {
        return getBalanceInfo(false);
    }

    /**
     * 매매 직전엔 반드시 force=true 로 호출. 캐시 무시 + 실패 시 캐시 반환 안 함 (null).
     */
    private BalanceInfo getBalanceInfo(boolean force) {
        if (!force && cachedBalance != null && lastBalanceUpdate != null) {
            long elapsed = java.time.Duration.between(lastBalanceUpdate, DateTimeUtil.kstNow()).getSeconds();
            if (elapsed < BALANCE_CACHE_SECONDS) {
                return cachedBalance;
            }
        }

        JsonNode balanceResponse = kisService.getBalance();
        if (balanceResponse == null) {
            // 매매 직전에는 stale 캐시로 결정 내리지 않도록 null 반환 → 호출자가 거래 중단.
            return force ? null : cachedBalance;
        }

        BalanceInfo balance = kisService.parseBalance(balanceResponse);
        if (balance != null) {
            cachedBalance = balance;
            lastBalanceUpdate = DateTimeUtil.kstNow();
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
     * 실전매수 텔레그램 알림
     */
    private void sendRealBuyAlert(String stockName, String stockCode, BigDecimal price, Integer quantity, String orderNo) {
        if (!telegramService.isEnabled()) return;

        BigDecimal total = price.multiply(BigDecimal.valueOf(quantity));
        boolean large = safetyService.isLargeTrade(total);
        String header = large
                ? "<b>⚠️ [실전투자] 대형 매수 주문!</b>"
                : "<b>💰 [실전투자] 매수 주문!</b>";

        String message = String.format(
                """
                %s

                📊 <b>%s</b> (%s)
                💰 %s원 x %d주
                💵 총 금액: %s원
                📋 주문번호: %s

                ⏰ %s
                ━━━━━━━━━━━━━━━━
                🔴 MyPlatform 실전투자
                """,
                header,
                stockName, stockCode,
                formatNumber(price), quantity,
                formatNumber(total),
                orderNo,
                DateTimeUtil.kstNow().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        );

        if (large) {
            // 대형 거래는 RISK 채널로
            telegramService.sendRisk(message);
        } else {
            telegramService.sendSignal(message);
        }
    }

    /**
     * 실전매도 텔레그램 알림
     */
    private void sendRealSellAlert(String stockName, String stockCode, BigDecimal price, Integer quantity,
                                    BigDecimal profitLoss, String reason, String orderNo) {
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
                DateTimeUtil.kstNow().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        );

        telegramService.sendSignal(message);
    }

    private String formatNumber(BigDecimal value) {
        if (value == null) return "0";
        return String.format("%,.0f", value);
    }
}
