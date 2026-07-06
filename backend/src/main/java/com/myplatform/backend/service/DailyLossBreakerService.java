package com.myplatform.backend.service;

import com.myplatform.backend.entity.BotConfig;
import com.myplatform.backend.entity.TradingAuditLog;
import com.myplatform.backend.entity.VirtualAccount;
import com.myplatform.backend.repository.BotConfigRepository;
import com.myplatform.backend.repository.VirtualAccountRepository;
import com.myplatform.backend.repository.VirtualTradeHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 일일 손실 한도 서킷브레이커 (V38, 2026-07-06) — 장중 누적 <b>실현손실</b> 기반 신규 진입 차단.
 *
 * <p><b>비대칭이 핵심</b>: 당일 봇 실현손익 누적 ≤ -한도 → <b>신규 진입만 차단</b>, 기존 포지션의
 * 손절/청산/모니터링은 계속(출혈 확대만 방지, 탈출은 막지 않음). 기존 -3% 자산 킬스위치는
 * botActive=false 로 매도 관리까지 세우는 것과 대비 — 브레이커가 먼저 걸려 -3% 전에 진입을 멈추는 게 목표.
 *
 * <p><b>killswitch 와 별개</b>: {@code TradingKillSwitch}(불확실성·영구·수동해제)와 상태·로직 공유 없음.
 * 자동 해제 = trippedDate ≠ 오늘(날짜 비교 — 리셋 잡 불필요, 재시작 안전). ADMIN 수동 해제 별도.
 *
 * <p><b>판정 입력</b>: {@code VirtualTradeHistory} 당일 SELL 확정 기록만 합산(§4c — 미체결/부분체결
 * 잔량의 미확정 손익은 resolveFill 재시도 후 기록 시점에 반영, 추정치 카운트 금지).
 * REAL=계정 999999 / VIRTUAL=활성 가상계좌(읽기 전용 조회 — getOrCreate 변형은 쓰기 발생이라 금지).
 *
 * <p><b>fail-open 예외</b>: 합산 조회 실패 시 pnl=null 을 {@link #judge} 에 그대로 전달 —
 * BLOCKED 판정이 null 체크보다 먼저라 <b>이미 발동된 브레이커는 DB 블립에도 유지</b>된다.
 * 미발동 상태의 조회 실패만 통과(PASS) + RISK 알림(10분 스로틀).
 *
 * <p>상태 저장 = bot_config <b>전용 행</b>(config_key='daily_loss_breaker') + 조건부 UPDATE — 상세는
 * {@link BotConfigRepository#tripDailyLossBreaker}.
 */
@Service
@Slf4j
public class DailyLossBreakerService {

    static final String BREAKER_CONFIG_KEY = "daily_loss_breaker";
    static final BigDecimal DEFAULT_LIMIT_KRW = new BigDecimal("300000");
    private static final long CHECK_FAIL_ALERT_COOLDOWN_MIN = 10;

    /** judge 판정 결과. */
    enum Decision {
        /** 브레이커 비활성(off 또는 임계 오설정) — 통과. */
        DISABLED,
        /** 오늘 이미 발동 — 신규 진입 차단(조회 실패에도 유지). */
        BLOCKED,
        /** 이번 판정으로 최초 발동 — 차단 + 1회 알림/감사. */
        TRIP,
        /** 한도 미달 또는 판정 불가(pnl null) — 통과. */
        PASS
    }

    private final BotConfigRepository botConfigRepository;
    private final VirtualTradeHistoryRepository tradeHistoryRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final TradingAuditService auditService;
    private final ObjectProvider<TelegramNotificationService> telegramProvider;
    private final Clock clock;

    /** 판정 불가(조회 실패) RISK 알림 스로틀 — 10분당 1회(notifyKillSwitchCheckFailure 패턴). */
    private volatile LocalDateTime checkFailAlertAt = null;

    public DailyLossBreakerService(BotConfigRepository botConfigRepository,
                                   VirtualTradeHistoryRepository tradeHistoryRepository,
                                   VirtualAccountRepository virtualAccountRepository,
                                   TradingAuditService auditService,
                                   ObjectProvider<TelegramNotificationService> telegramProvider,
                                   Clock clock) {
        this.botConfigRepository = botConfigRepository;
        this.tradeHistoryRepository = tradeHistoryRepository;
        this.virtualAccountRepository = virtualAccountRepository;
        this.auditService = auditService;
        this.telegramProvider = telegramProvider;
        this.clock = clock;
    }

    /**
     * 서킷브레이커 판정 — <b>순수 함수</b>(경계값 테스트 대상).
     *
     * <p>판정 순서(순서가 곧 안전 규약):
     * <ol>
     *   <li>{@code !enabled} → DISABLED</li>
     *   <li>{@code limit null/≤0} → DISABLED (오설정 가드 — 0원 한도로 상시 차단되는 사고 방지)</li>
     *   <li>{@code trippedDate == today} → <b>BLOCKED</b> — pnl null 체크보다 <b>먼저</b>:
     *       발동 후 DB 블립으로 pnl 조회가 실패해도 차단 유지(fail-open 예외)</li>
     *   <li>{@code pnl == null} → PASS (판정 불가 — 미발동 상태만 fail-open, 호출부가 RISK 알림)</li>
     *   <li>{@code pnl ≤ -limit} (등호 포함) → TRIP</li>
     *   <li>그 외 → PASS</li>
     * </ol>
     */
    static Decision judge(BigDecimal dailyRealizedPnl, BigDecimal limitKrw, boolean enabled,
                          LocalDate trippedDate, LocalDate today) {
        if (!enabled) return Decision.DISABLED;
        if (limitKrw == null || limitKrw.signum() <= 0) return Decision.DISABLED;
        if (today.equals(trippedDate)) return Decision.BLOCKED;
        if (dailyRealizedPnl == null) return Decision.PASS;
        if (dailyRealizedPnl.compareTo(limitKrw.negate()) <= 0) return Decision.TRIP;
        return Decision.PASS;
    }

    /**
     * 신규 진입 허용 여부 — 봇 진입 크론이 <b>틱당 1회</b>(메서드 상단) 호출. 매도 경로는 호출 금지(비대칭).
     *
     * @param realMode true=REAL(계정 999999) / false=VIRTUAL(활성 가상계좌)
     * @return true=진입 허용, false=차단(오늘 발동 상태)
     */
    public boolean allowEntry(boolean realMode) {
        try {
            BotConfig config = loadBreakerConfig();
            LocalDate today = LocalDate.now(clock);
            BigDecimal pnl = sumTodayRealizedPnlQuiet(realMode, today);
            boolean enabled = config.getDailyLossBreakerEnabled() == null
                    || config.getDailyLossBreakerEnabled();   // null(레거시) = ON
            BigDecimal limit = config.getDailyLossLimitKrw() != null
                    ? config.getDailyLossLimitKrw() : DEFAULT_LIMIT_KRW;

            Decision decision = judge(pnl, limit, enabled, config.getDailyLossBreakerTrippedDate(), today);
            switch (decision) {
                case DISABLED, PASS -> {
                    return true;
                }
                case BLOCKED -> {
                    log.info("[손실브레이커] 신규 진입 차단 중 (발동일={}, 당일 실현손익={})",
                            config.getDailyLossBreakerTrippedDate(), pnl);
                    return false;
                }
                case TRIP -> {
                    onTrip(realMode, pnl, limit, today);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            // 설정 행 조회 자체가 실패 — 미발동 가정 fail-open(진입 허용) + 알림.
            // (발동 상태 유지는 judge 의 BLOCKED-before-null 이 담당하나, 여기는 config 조차 못 읽은 케이스.)
            log.error("[손실브레이커] 판정 실패 (fail-open, 진입 허용): {}", e.getMessage());
            notifyCheckFailure(e);
            return true;
        }
    }

    /** TRIP 전이 — 조건부 UPDATE 가 1행일 때만(=이번 호출이 최초 발동) 알림/감사 1회. */
    private void onTrip(boolean realMode, BigDecimal pnl, BigDecimal limit, LocalDate today) {
        int updated = botConfigRepository.tripDailyLossBreaker(BREAKER_CONFIG_KEY, today);
        if (updated != 1) {
            // 경합 패자(다른 스레드가 먼저 발동) — 차단 사실만 유지, 중복 알림 없음.
            log.debug("[손실브레이커] trip 경합 no-op (이미 발동)");
            return;
        }
        TradingAuditLog.Mode mode = realMode ? TradingAuditLog.Mode.REAL : TradingAuditLog.Mode.VIRTUAL;
        String detail = String.format("일일 실현손실 한도 도달: 당일 %,d원 ≤ -%,d원 → 신규 진입 차단(매도/청산 계속, %s 자동 해제)",
                pnl.longValue(), limit.longValue(), today.plusDays(1));
        log.warn("[손실브레이커] 🚧 발동! {} (mode={})", detail, mode);

        try {
            auditService.blocked(TradingAuditLog.Action.OTHER, mode, null, null, null, null,
                    "DAILY_LOSS_BREAKER", detail);
        } catch (Exception e) {
            log.warn("[손실브레이커] 감사 로그 기록 실패: {}", e.getMessage());
        }
        TelegramNotificationService telegram = telegramProvider.getIfAvailable();
        if (telegram != null && telegram.isEnabled()) {
            try {
                telegram.sendRisk(String.format(
                        "🚧 <b>일일 손실 서킷브레이커 발동 [%s]</b>\n\n"
                                + "📉 당일 봇 실현손익: <b>%,d원</b> (한도 -%,d원)\n"
                                + "⛔ 신규 진입 전면 차단 — <b>기존 포지션 손절/청산은 계속</b>\n"
                                + "🔓 해제: 다음 거래일 자동 / ADMIN 수동(release API)",
                        realMode ? "실전" : "모의", pnl.longValue(), limit.longValue()));
            } catch (Exception e) {
                log.warn("[손실브레이커] 텔레그램 발송 실패: {}", e.getMessage());
            }
        }
    }

    /** 당일 [00:00, 익일 00:00) 실현손익 합. 조회 실패/계좌 없음 판별: 실패=null(판정불가), 계좌없음=null. */
    private BigDecimal sumTodayRealizedPnlQuiet(boolean realMode, LocalDate today) {
        try {
            Long accountId;
            if (realMode) {
                accountId = RealTradeService.REAL_ACCOUNT_ID;
            } else {
                // 읽기 전용 조회 — getOrCreateActiveAccount() 는 쓰기(계좌 생성) 발생이라 판정 경로에서 금지.
                accountId = virtualAccountRepository.findFirstByIsActiveTrueOrderByIdDesc()
                        .map(VirtualAccount::getId).orElse(null);
                if (accountId == null) return BigDecimal.ZERO;  // 계좌 없음 = 거래 없음 = 손실 0 (정직)
            }
            LocalDateTime start = today.atStartOfDay();
            return tradeHistoryRepository.sumRealizedPnlBetween(accountId, start, start.plusDays(1));
        } catch (Exception e) {
            log.error("[손실브레이커] 당일 실현손익 조회 실패: {}", e.getMessage());
            notifyCheckFailure(e);
            return null;   // 판정 불가 — judge 가 BLOCKED(발동 유지) 또는 PASS(미발동 fail-open) 결정
        }
    }

    /** 오늘 발동 상태인지 — getBotStatus 표시용 경량 읽기(합산 조회 없음, 폴링 안전). */
    public boolean isTrippedToday() {
        try {
            return botConfigRepository.findByConfigKey(BREAKER_CONFIG_KEY)
                    .map(BotConfig::getDailyLossBreakerTrippedDate)
                    .map(d -> d.equals(LocalDate.now(clock)))
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 브레이커 설정/상태 조회 (API 표시용). */
    public BotConfig getBreakerConfig() {
        return loadBreakerConfig();
    }

    /**
     * 수동 해제 (ADMIN) — trippedDate NULL. 해제됐으면 감사 + 텔레그램.
     *
     * @return true=실제 해제(발동 상태였음), false=발동 상태 아니었음(no-op)
     */
    public boolean release(String operator) {
        int updated = botConfigRepository.releaseDailyLossBreaker(BREAKER_CONFIG_KEY);
        if (updated != 1) return false;
        log.warn("[손실브레이커] 수동 해제 by {}", operator);
        try {
            auditService.blocked(TradingAuditLog.Action.OTHER, TradingAuditLog.Mode.REAL, null, null,
                    null, null, operator, "DAILY_LOSS_BREAKER_RELEASED (수동 해제)");
        } catch (Exception e) {
            log.warn("[손실브레이커] 해제 감사 기록 실패: {}", e.getMessage());
        }
        TelegramNotificationService telegram = telegramProvider.getIfAvailable();
        if (telegram != null && telegram.isEnabled()) {
            try {
                telegram.sendRisk(String.format(
                        "🔓 <b>일일 손실 서킷브레이커 수동 해제</b>\n작업자: %s — 신규 진입 재개", operator));
            } catch (Exception e) {
                log.warn("[손실브레이커] 해제 텔레그램 실패: {}", e.getMessage());
            }
        }
        return true;
    }

    /** 설정 변경 (ADMIN) — enabled/limitKrw. trippedDate 는 건드리지 않음(해제는 release 로만). */
    public BotConfig updateSettings(Boolean enabled, BigDecimal limitKrw) {
        BotConfig config = loadBreakerConfig();
        if (enabled != null) config.setDailyLossBreakerEnabled(enabled);
        if (limitKrw != null && limitKrw.signum() > 0) config.setDailyLossLimitKrw(limitKrw);
        BotConfig saved = botConfigRepository.save(config);
        log.info("[손실브레이커] 설정 변경: enabled={}, limitKrw={}",
                saved.getDailyLossBreakerEnabled(), saved.getDailyLossLimitKrw());
        return saved;
    }

    /** 전용 행 로드(없으면 기본값 행 생성) — 'trading_bot' 행과 분리(병행 쓰기 클로버 차단). */
    private BotConfig loadBreakerConfig() {
        Optional<BotConfig> existing = botConfigRepository.findByConfigKey(BREAKER_CONFIG_KEY);
        if (existing.isPresent()) return existing.get();
        try {
            return botConfigRepository.save(BotConfig.builder().configKey(BREAKER_CONFIG_KEY).build());
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // 경합 패자 — config_key UNIQUE. winner 행 재조회.
            return botConfigRepository.findByConfigKey(BREAKER_CONFIG_KEY).orElseThrow();
        }
    }

    /** 판정 불가 RISK 알림 — 10분 스로틀(안전가드의 조용한 무력화 방지, killswitch 체크 실패 패턴). */
    private void notifyCheckFailure(Exception e) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (checkFailAlertAt != null && checkFailAlertAt.plusMinutes(CHECK_FAIL_ALERT_COOLDOWN_MIN).isAfter(now)) {
            return;
        }
        checkFailAlertAt = now;
        TelegramNotificationService telegram = telegramProvider.getIfAvailable();
        if (telegram == null || !telegram.isEnabled()) return;
        try {
            telegram.sendRisk("⚠️ <b>[봇] 손실브레이커 판정 실패</b>\n조회 오류로 일일 손실 차단이 일시 무력화 상태일 수 "
                    + "있습니다(이미 발동된 차단은 유지, 매매는 계속). 반복 시 수동 점검 필요.\n오류: " + e.getMessage());
        } catch (Exception ignore) { /* 알림 실패 무시 */ }
    }
}
