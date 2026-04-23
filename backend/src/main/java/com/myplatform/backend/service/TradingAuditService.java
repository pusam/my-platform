package com.myplatform.backend.service;

import com.myplatform.backend.entity.TradingAuditLog;
import com.myplatform.backend.repository.TradingAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * KIS API 매매 호출의 감사 로그.
 *
 * 사용 패턴 (try-finally 권장):
 *   var ctx = auditService.start(BUY, REAL, "005930", "삼성전자", 10, 65000, "SCALPING");
 *   try {
 *     ... 실제 API 호출 ...
 *     auditService.success(ctx, response, orderNo);
 *   } catch (Exception e) {
 *     auditService.failure(ctx, e);
 *   }
 *
 * 또는 사전에 차단된 경우:
 *   auditService.blocked(BUY, REAL, "005930", ..., "킬스위치 ON");
 */
@Service
public class TradingAuditService {

    private static final Logger log = LoggerFactory.getLogger(TradingAuditService.class);

    private final TradingAuditLogRepository repository;

    public TradingAuditService(TradingAuditLogRepository repository) {
        this.repository = repository;
    }

    public static class Ctx {
        public final String requestId = UUID.randomUUID().toString();
        public final long startNanos = System.nanoTime();
        public final TradingAuditLog log;
        Ctx(TradingAuditLog log) { this.log = log; }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Ctx start(TradingAuditLog.Action action,
                     TradingAuditLog.Mode mode,
                     String stockCode,
                     String stockName,
                     Integer quantity,
                     BigDecimal price,
                     String triggeredBy) {
        TradingAuditLog entry = new TradingAuditLog();
        entry.setRequestId(UUID.randomUUID().toString());
        entry.setAction(action);
        entry.setMode(mode);
        entry.setStockCode(stockCode);
        entry.setStockName(stockName);
        entry.setQuantity(quantity);
        entry.setPrice(price);
        if (price != null && quantity != null) {
            entry.setTotalAmount(price.multiply(BigDecimal.valueOf(quantity)));
        }
        entry.setTriggeredBy(triggeredBy);
        entry.setSuccess(false);
        repository.save(entry);
        return new Ctx(entry);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(Ctx ctx, String responseCode, String responseMsg, String orderNo) {
        TradingAuditLog e = ctx.log;
        e.setSuccess(true);
        e.setResponseCode(responseCode);
        e.setResponseMsg(responseMsg);
        e.setOrderNo(orderNo);
        e.setLatencyMs(elapsedMs(ctx));
        repository.save(e);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(Ctx ctx, String responseCode, String responseMsg, Throwable t) {
        TradingAuditLog e = ctx.log;
        e.setSuccess(false);
        e.setResponseCode(responseCode);
        e.setResponseMsg(responseMsg);
        e.setErrorMessage(t == null ? null : truncate(t.toString(), 1000));
        e.setLatencyMs(elapsedMs(ctx));
        repository.save(e);
    }

    /** 매매 시도가 안전장치에 의해 차단된 경우 — KIS 호출 자체 안 일어남. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void blocked(TradingAuditLog.Action action,
                        TradingAuditLog.Mode mode,
                        String stockCode,
                        String stockName,
                        Integer quantity,
                        BigDecimal price,
                        String triggeredBy,
                        String reason) {
        TradingAuditLog e = new TradingAuditLog();
        e.setRequestId(UUID.randomUUID().toString());
        e.setAction(action);
        e.setMode(mode);
        e.setStockCode(stockCode);
        e.setStockName(stockName);
        e.setQuantity(quantity);
        e.setPrice(price);
        if (price != null && quantity != null) {
            e.setTotalAmount(price.multiply(BigDecimal.valueOf(quantity)));
        }
        e.setTriggeredBy(triggeredBy);
        e.setSuccess(false);
        e.setBlockedReason(truncate(reason, 500));
        repository.save(e);
        log.warn("[Audit] 차단됨 action={} stock={} reason={}", action, stockCode, reason);
    }

    private int elapsedMs(Ctx ctx) {
        return (int) ((System.nanoTime() - ctx.startNanos) / 1_000_000);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
