package com.myplatform.backend.service;

import com.myplatform.backend.entity.BotOrderIntent;
import com.myplatform.backend.repository.BotOrderIntentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 봇 실주문 멱등키 서비스 — 리더 전환 중복 BUY 방지.
 *
 * <p><b>REQUIRES_NEW</b>: 모든 쓰기는 주문 메서드(RealTradeService.executeBuy, @Transactional)의 롤백과
 * 무관하게 독립 커밋돼야 한다. 그래야 리더 A 가 선기록 후 KIS 호출 중 죽어 outer 트랜잭션이 롤백돼도
 * PENDING 키가 살아남아 B 의 재주문을 차단한다.
 *
 * <p>상태: PENDING(선기록) → DONE(KIS 성공) / FAILED(KIS 확정거부 → 재시도 허용).
 * KIS 불확실(예외/null)은 killswitch 가 봇을 정지시키므로 PENDING 유지(보수적 차단).
 */
@Service
@Slf4j
public class BotOrderIntentService {

    public enum OrderGate { PROCEED, SKIP_DUPLICATE }

    private final BotOrderIntentRepository repository;
    private final Clock clock;

    public BotOrderIntentService(BotOrderIntentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 주문 게이트 — KIS 호출 직전 호출. PROCEED 면 진행, SKIP_DUPLICATE 면 재주문 차단.
     * 없음/FAILED → PENDING 으로 선기록 후 PROCEED. PENDING/DONE → 차단. 동시 insert 경쟁도 차단.
     * DB 오류는 던진다(가드 보장 불가 → 호출측이 주문 abort).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderGate tryAcquire(String stockCode, String side, String reason) {
        LocalDate today = LocalDate.now(clock);
        Optional<BotOrderIntent> existing =
                repository.findByStockCodeAndSideAndTradeDateAndReason(stockCode, side, today, reason);
        if (decideGate(existing.map(BotOrderIntent::getStatus)) == OrderGate.SKIP_DUPLICATE) {
            log.warn("[OrderIntent] 중복 차단: {} {} {} (status={})",
                    stockCode, side, reason, existing.map(BotOrderIntent::getStatus).orElse("?"));
            return OrderGate.SKIP_DUPLICATE;
        }
        BotOrderIntent intent = existing.orElseGet(() -> BotOrderIntent.builder()
                .stockCode(stockCode).side(side).tradeDate(today).reason(reason).build());
        intent.setStatus("PENDING");
        try {
            repository.save(intent);
        } catch (DataIntegrityViolationException dup) {
            // 동시 insert 경쟁(다른 인스턴스가 선점) → 차단
            log.warn("[OrderIntent] 동시 선점 감지 → 중복 차단: {} {} {}", stockCode, side, reason);
            return OrderGate.SKIP_DUPLICATE;
        }
        return OrderGate.PROCEED;
    }

    /** KIS 성공 → DONE(재주문 영구 차단) + 주문번호 기록. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(String stockCode, String side, String reason, String orderNo) {
        update(stockCode, side, reason, i -> {
            i.setStatus("DONE");
            i.setKisOrderNo(orderNo);
        });
    }

    /** KIS 확정 거부(rt_cd≠0) → FAILED(정상 재시도 허용). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String stockCode, String side, String reason) {
        update(stockCode, side, reason, i -> i.setStatus("FAILED"));
    }

    private void update(String stockCode, String side, String reason, Consumer<BotOrderIntent> mutator) {
        try {
            repository.findByStockCodeAndSideAndTradeDateAndReason(stockCode, side, LocalDate.now(clock), reason)
                    .ifPresent(i -> {
                        mutator.accept(i);
                        repository.save(i);
                    });
        } catch (Exception e) {
            log.warn("[OrderIntent] 상태 갱신 실패 {} {} {}: {}", stockCode, side, reason, e.getMessage());
        }
    }

    /** 게이트 판정 — 순수 함수(테스트 대상). 없음/FAILED → 진행, PENDING/DONE → 차단. */
    static OrderGate decideGate(Optional<String> existingStatus) {
        if (existingStatus.isEmpty()) return OrderGate.PROCEED;       // 기록 없음 → 진행
        return "FAILED".equals(existingStatus.get())
                ? OrderGate.PROCEED                                    // 확정 실패 → 재시도 허용
                : OrderGate.SKIP_DUPLICATE;                            // PENDING/DONE → 차단
    }
}
