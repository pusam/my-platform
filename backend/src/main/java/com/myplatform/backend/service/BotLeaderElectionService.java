package com.myplatform.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 봇 리더 선출 — Redis 리스(SET NX EX) + 하트비트로 멀티 인스턴스 중 <b>단일 리더만</b> 봇 실주문 크론을 실행한다.
 *
 * <p>⚠ <b>fail-CLOSED</b>: Redis 미가용/장애 시 {@link #isLeaderForBot()} = false → 봇은 주문하지 않는다.
 * 이는 {@link SchedulerLockService}(배치용, <b>fail-OPEN</b>: Redis 죽으면 통과)와 <b>의도적으로 정반대</b>다.
 * 실돈 봇은 "확실히 리더일 때만" 주문해야 KIS 비멱등 중복 주문(금전 손실)을 막는다. 인스턴스 2개가 동시에
 * 같은 종목을 주문하는 사고를 차단하는 게 목적. {@code SchedulerLockService}는 이 클래스와 무관하며 변경하지 않는다.
 *
 * <p>killswitch(DB, {@code TradingSafetyService})와 <b>독립</b> — 봇 크론은 <b>리더 AND killswitch</b>를 모두
 * 통과해야 주문한다.
 *
 * <p>Redis 접근은 조건부 빈 {@code cacheRedisTemplate}(cache.redis.enabled)에 의존하지 않고
 * {@link RedisConnectionFactory}에서 자체 {@link StringRedisTemplate}를 만든다(JWT용 Redis 연결은 운영에 항상 존재).
 */
@Service
@Slf4j
public class BotLeaderElectionService {

    static final String LEADER_KEY = "bot:leader";

    private final StringRedisTemplate redis;   // null = Redis 미가용 → fail-closed
    private final boolean enabled;
    private final Duration leaseTtl;
    private final String instanceId;

    private volatile boolean leader = false;

    /** 운영 생성자 — RedisConnectionFactory 에서 자체 StringRedisTemplate 생성. */
    public BotLeaderElectionService(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            @Value("${bot.leader-election.enabled:true}") boolean enabled,
            @Value("${bot.leader-election.lease-ttl-seconds:30}") long leaseTtlSeconds) {
        this(buildTemplate(connectionFactoryProvider.getIfAvailable()), enabled, leaseTtlSeconds,
                UUID.randomUUID().toString());
        if (!enabled) {
            log.warn("[BotLeader] 리더 선출 비활성 — 단일 인스턴스 전제. 멀티 인스턴스 배포면 반드시 켜라.");
        } else if (redis == null) {
            log.warn("[BotLeader] Redis 연결 없음 — fail-CLOSED: 봇은 리더를 확보 못 해 주문하지 않는다.");
        } else {
            log.info("[BotLeader] 리더 선출 활성 (instanceId={}, leaseTtl={}s)", instanceId, leaseTtlSeconds);
        }
    }

    /** 테스트용 — StringRedisTemplate(mock) · 고정 instanceId 직접 주입. */
    BotLeaderElectionService(StringRedisTemplate redis, boolean enabled, long leaseTtlSeconds, String instanceId) {
        this.redis = redis;
        this.enabled = enabled;
        this.leaseTtl = Duration.ofSeconds(leaseTtlSeconds);
        this.instanceId = instanceId;
    }

    private static StringRedisTemplate buildTemplate(RedisConnectionFactory cf) {
        return cf != null ? new StringRedisTemplate(cf) : null;
    }

    /**
     * 봇 실주문 크론 게이트.
     * 비활성=항상 통과(단일 인스턴스 탈출구) · Redis 미가용=차단(fail-closed) · 그 외 현재 리스 보유 시에만 true.
     */
    public boolean isLeaderForBot() {
        if (!enabled) return true;          // 단일 인스턴스 운영 탈출구
        if (redis == null) return false;    // fail-CLOSED
        if (!leader && log.isDebugEnabled()) {
            log.debug("[BotLeader] 비리더 — 봇 크론 skip (self={})", instanceId);
        }
        return leader;
    }

    /** 하트비트 — 리스 획득/갱신. fixedDelay(기본 10s) &lt; leaseTtl(기본 30s) 이어야 리더가 리스를 안 잃는다. */
    @Scheduled(fixedDelayString = "${bot.leader-election.heartbeat-ms:10000}")
    public void heartbeat() {
        if (!enabled || redis == null) { leader = false; return; }
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(LEADER_KEY, instanceId, leaseTtl);
            if (Boolean.TRUE.equals(acquired)) {
                if (!leader) log.info("[BotLeader] 리더 획득 (instanceId={})", instanceId);
                leader = true;
                return;
            }
            String holder = redis.opsForValue().get(LEADER_KEY);
            boolean own = decideLeadership(false, holder, instanceId);
            if (own) {
                redis.opsForValue().set(LEADER_KEY, instanceId, leaseTtl);   // 리스 갱신
            } else if (leader) {
                log.warn("[BotLeader] 리더 상실 — 현재 리더={} (self={})", holder, instanceId);
            }
            leader = own;
        } catch (Exception e) {
            // fail-CLOSED: Redis 장애 시 리더 자격 포기 → 봇 주문 중단
            if (leader) log.warn("[BotLeader] Redis 예외 — 리더 자격 포기(fail-closed): {}", e.getMessage());
            leader = false;
        }
    }

    /** 리더십 판정 순수 헬퍼 — 테스트 대상. 막 획득했거나(acquired) 현재 보유자가 자기 자신이면 리더. */
    static boolean decideLeadership(boolean acquired, String currentHolder, String selfId) {
        if (acquired) return true;
        return selfId != null && selfId.equals(currentHolder);
    }

    String instanceId() { return instanceId; }

    boolean isLeaderNow() { return leader; }
}
