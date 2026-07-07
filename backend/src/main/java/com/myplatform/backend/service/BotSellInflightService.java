package com.myplatform.backend.service;

import com.myplatform.backend.entity.BotSellInflight;
import com.myplatform.backend.repository.BotSellInflightRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * SELL in-flight 마커 서비스 — 동시 부분청산 과청산 방지 (P3-1 잔여 ③ B안, V45).
 *
 * <p>매도 <b>시도 창(TTL 60초)</b>만 잠근다. 리더 A가 매도 발사 직후 死 → B 승계 시 마커가
 * TTL 동안 SKIP → 다음 사이클엔 마커 만료 + KIS 잔고 재조회가 A 의 체결을 반영 → B 의 재계산이
 * 잔여 기준으로 축소 → 과청산 없음. 부분체결 잔여분 재시도(S3, 정당 동작)는 다음 사이클(마커
 * 만료 후) 정상 통과 — confirmFill/reconcileSellFill 무접촉.
 *
 * <p><b>극성(비대칭, §4d)</b>: BUY 멱등키({@link BotOrderIntentService#tryAcquire} — DB 오류 throw
 * = fail-closed)와 <b>반대</b>로, 여기는 DB 오류 = {@code PROCEED_UNGUARDED}(fail-open) + WARN +
 * RISK 알림(10분 스로틀). 매도 가드가 손절을 지연시키면 손실이 확대되기 때문 — 극성을 fail-closed
 * 로 바꾸지 말 것.
 *
 * <p>tx: 서비스 레벨 @Transactional 없음 — 쓰기는 리포지토리 레벨 tx 로 독립 커밋(호출측 sell 이
 * NOT_SUPPORTED). DB 예외를 여기서 잡아 fail-open 반환해도 rollback-only 오염이 없다.
 */
@Service
@Slf4j
public class BotSellInflightService {

    public enum SellGate { PROCEED, SKIP_CONCURRENT, PROCEED_UNGUARDED }

    /** 마커 TTL — "동시" 창만 잠근다. 늘리면 정당한 다음 사이클 재시도(S3)까지 지연됨. */
    static final int TTL_SECONDS = 60;
    private static final String MODE_REAL = "REAL";
    private static final Duration UNGUARDED_ALERT_THROTTLE = Duration.ofMinutes(10);

    private final BotSellInflightRepository repository;
    private final TelegramNotificationService telegramService;
    private final Clock clock;
    private final String holder;

    private volatile LocalDateTime lastUnguardedAlertAt;

    public BotSellInflightService(BotSellInflightRepository repository,
                                  TelegramNotificationService telegramService,
                                  Clock clock) {
        this.repository = repository;
        this.telegramService = telegramService;
        this.clock = clock;
        this.holder = resolveHolder();
    }

    /**
     * 매도 게이트 — KIS 매도 호출 직전 호출.
     * PROCEED=마커 획득(진행) / SKIP_CONCURRENT=유효 마커 존재(이번 사이클만 양보) /
     * PROCEED_UNGUARDED=DB 오류(fail-open — 가드 없이 진행 + 경고).
     */
    public SellGate tryAcquire(String stockCode) {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            Optional<BotSellInflight> existing =
                    repository.findByStockCodeAndTradingMode(stockCode, MODE_REAL);
            if (decideSellGate(existing.map(BotSellInflight::getExpiresAt), now) == SellGate.SKIP_CONCURRENT) {
                log.warn("[SellInflight] 동시 매도 감지 → 이번 사이클 양보: {} (expiresAt={})",
                        stockCode, existing.map(BotSellInflight::getExpiresAt).orElse(null));
                return SellGate.SKIP_CONCURRENT;
            }
            LocalDateTime expiresAt = now.plusSeconds(TTL_SECONDS);
            if (existing.isPresent()) {
                // 만료 행 재사용 — 조건부 UPDATE 로 동시 재획득 경쟁을 DB 레벨에서 판정
                int updated = repository.reacquireExpired(stockCode, MODE_REAL, now, expiresAt, holder);
                if (updated != 1) {
                    log.warn("[SellInflight] 만료 행 재획득 경쟁 패배 → 양보: {}", stockCode);
                    return SellGate.SKIP_CONCURRENT;
                }
                return SellGate.PROCEED;
            }
            try {
                repository.save(BotSellInflight.builder()
                        .stockCode(stockCode).tradingMode(MODE_REAL)
                        .acquiredAt(now).expiresAt(expiresAt).holder(holder)
                        .build());
            } catch (DataIntegrityViolationException dup) {
                // 동시 insert 경쟁(다른 인스턴스가 선점) → 양보
                log.warn("[SellInflight] 동시 선점 감지(UNIQUE) → 양보: {}", stockCode);
                return SellGate.SKIP_CONCURRENT;
            }
            return SellGate.PROCEED;
        } catch (Exception e) {
            // fail-open — BUY 멱등키(throw=fail-closed)와 의도적 극성 반대(매도 지연=손실 확대)
            log.warn("[SellInflight] 마커 DB 오류 → 가드 없이 진행(fail-open): {} - {}",
                    stockCode, e.getMessage());
            alertUnguardedThrottled(stockCode, e);
            return SellGate.PROCEED_UNGUARDED;
        }
    }

    /** 마커 해제 — best-effort(실패 무해, TTL 만료가 백스톱). */
    public void release(String stockCode) {
        try {
            repository.deleteByStockCodeAndTradingMode(stockCode, MODE_REAL);
        } catch (Exception e) {
            log.warn("[SellInflight] 마커 해제 실패(TTL 만료로 자연 해제 예정): {} - {}",
                    stockCode, e.getMessage());
        }
    }

    /**
     * 게이트 판정 — 순수 함수(테스트 대상).
     * 마커 없음/만료(now ≥ expiresAt) → PROCEED, 유효(now < expiresAt) → SKIP_CONCURRENT.
     */
    static SellGate decideSellGate(Optional<LocalDateTime> existingExpiresAt, LocalDateTime now) {
        if (existingExpiresAt.isEmpty()) return SellGate.PROCEED;
        return existingExpiresAt.get().isAfter(now)
                ? SellGate.SKIP_CONCURRENT   // 유효 마커 — 다른 주체가 매도 시도 중
                : SellGate.PROCEED;          // 만료(경계 now==expiresAt 포함) — 재획득 가능
    }

    private void alertUnguardedThrottled(String stockCode, Exception e) {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            if (lastUnguardedAlertAt != null
                    && Duration.between(lastUnguardedAlertAt, now).compareTo(UNGUARDED_ALERT_THROTTLE) < 0) {
                return;
            }
            lastUnguardedAlertAt = now;
            if (telegramService.isEnabled()) {
                telegramService.sendRisk(String.format(
                        "⚠️ <b>SELL in-flight 마커 DB 오류 — 가드 없이 매도 진행(fail-open)</b>\n\n"
                                + "종목: %s\n에러: %s\n\n"
                                + "매도 기회 보존을 위해 진행하나, DB 복구 전까지 동시 부분청산 방어가 꺼진 상태입니다.",
                        stockCode, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        } catch (Exception alertErr) {
            log.warn("[SellInflight] fail-open 경고 알림 실패: {}", alertErr.getMessage());
        }
    }

    private static String resolveHolder() {
        try {
            String h = java.net.InetAddress.getLocalHost().getHostName();
            return h.length() > 64 ? h.substring(0, 64) : h;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
