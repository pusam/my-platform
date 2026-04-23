package com.myplatform.backend.service;

import com.myplatform.backend.entity.TradingAuditLog;
import com.myplatform.backend.entity.TradingKillSwitch;
import com.myplatform.backend.repository.TradingAuditLogRepository;
import com.myplatform.backend.repository.TradingKillSwitchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 매매 안전장치 통합 서비스.
 *
 * 매매 호출 전에 반드시 {@link #checkBuy(BigDecimal)} 또는 {@link #checkSell()} 를 호출.
 * 차단 사유가 있으면 {@link Decision#allowed} = false 로 반환되며,
 * 호출자는 즉시 거래를 중단하고 사유를 사용자/감사로그에 남겨야 한다.
 */
@Service
public class TradingSafetyService {

    private static final Logger log = LoggerFactory.getLogger(TradingSafetyService.class);

    private final TradingKillSwitchRepository killSwitchRepository;
    private final TradingAuditLogRepository auditLogRepository;

    /** 일일 매수 누적 금액 한도 (원). 0 이하면 비활성. */
    @Value("${trading.daily-buy-limit-krw:5000000}")
    private BigDecimal dailyBuyLimitKrw;

    /** 1회 매수 금액이 이 값을 넘으면 알림 메시지에 ⚠️ 강조 표시. */
    @Value("${trading.alert-threshold-krw:1000000}")
    private BigDecimal alertThresholdKrw;

    public TradingSafetyService(TradingKillSwitchRepository killSwitchRepository,
                                TradingAuditLogRepository auditLogRepository) {
        this.killSwitchRepository = killSwitchRepository;
        this.auditLogRepository = auditLogRepository;
    }

    public record Decision(boolean allowed, String reason) {
        public static Decision allow() { return new Decision(true, null); }
        public static Decision block(String reason) { return new Decision(false, reason); }
    }

    /** 매수 시도 전 호출. amount = 시도 금액(원). */
    public Decision checkBuy(BigDecimal amount) {
        Decision killCheck = checkKillSwitch();
        if (!killCheck.allowed) return killCheck;

        if (dailyBuyLimitKrw != null && dailyBuyLimitKrw.signum() > 0) {
            BigDecimal todaySum = todayBuyAmount();
            BigDecimal projected = todaySum.add(amount == null ? BigDecimal.ZERO : amount);
            if (projected.compareTo(dailyBuyLimitKrw) > 0) {
                return Decision.block(String.format(
                        "일일 매수 한도 초과: 누적 %,.0f원 + 시도 %,.0f원 > 한도 %,.0f원",
                        todaySum, amount, dailyBuyLimitKrw));
            }
        }
        return Decision.allow();
    }

    /** 매도 시도 전 호출. (매도는 한도 적용 X — 손절은 항상 가능해야 함) */
    public Decision checkSell() {
        return checkKillSwitch();
    }

    public Decision checkKillSwitch() {
        TradingKillSwitch latest = currentState();
        if (latest != null && latest.isEnabled()) {
            return Decision.block("매매 비상 정지 중: " + (latest.getReason() == null ? "사유 미기재" : latest.getReason()));
        }
        return Decision.allow();
    }

    public TradingKillSwitch currentState() {
        return killSwitchRepository.findFirstByOrderByCreatedAtDesc().orElse(null);
    }

    public boolean isKilled() {
        TradingKillSwitch s = currentState();
        return s != null && s.isEnabled();
    }

    /** 비상 정지 ON. 새 row 를 INSERT 해서 이력 보존. */
    @Transactional
    public TradingKillSwitch enable(String reason, String triggeredBy) {
        TradingKillSwitch ks = new TradingKillSwitch();
        ks.setEnabled(true);
        ks.setReason(reason);
        ks.setTriggeredBy(triggeredBy);
        TradingKillSwitch saved = killSwitchRepository.save(ks);
        log.warn("⛔ 매매 비상 정지 ON — by={} reason={}", triggeredBy, reason);
        return saved;
    }

    @Transactional
    public TradingKillSwitch disable(String reason, String triggeredBy) {
        TradingKillSwitch ks = new TradingKillSwitch();
        ks.setEnabled(false);
        ks.setReason(reason);
        ks.setTriggeredBy(triggeredBy);
        TradingKillSwitch saved = killSwitchRepository.save(ks);
        log.info("✅ 매매 비상 정지 OFF — by={} reason={}", triggeredBy, reason);
        return saved;
    }

    public BigDecimal todayBuyAmount() {
        LocalDateTime startOfDay = LocalDate.now(ZoneId.of("Asia/Seoul"))
                .atStartOfDay();
        BigDecimal sum = auditLogRepository.sumSuccessfulRealBuyAmountSince(startOfDay);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    public BigDecimal getDailyBuyLimitKrw() { return dailyBuyLimitKrw; }
    public BigDecimal getAlertThresholdKrw() { return alertThresholdKrw; }

    public boolean isLargeTrade(BigDecimal amount) {
        return amount != null && alertThresholdKrw != null
                && amount.compareTo(alertThresholdKrw) >= 0;
    }
}
