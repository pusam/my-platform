package com.myplatform.backend.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 시세 캐시 표 {@code stock_price} 보존 잡(2026-11-10, 개선 후보 6번) — <b>기본 꺼짐</b>.
 *
 * <p>이 표는 2026-01-14 부터 한 번도 지운 적이 없다(11/10 운영 약 399만 행·484MB, 하루 약 2만 행). 읽는 곳을 전수로
 * 보니 옛 행이 필요한 소비처는 없다 — 전부 <b>종목별 최신 1행</b>이거나 <b>최근 7일</b>이다
 * (docs/STOCK_PRICE_RETENTION.md 의 소비처 표). 그래서 보존 기간이 지난 행을 지우되 다음 둘은 지키지 않는다:
 *
 * <ul>
 *   <li><b>종목별 최신 행은 기간과 무관하게 남긴다.</b> 거래정지 종목처럼 오래 시세가 안 들어온 종목의 마지막 행이
 *       지워지면 가격 이상치 그물(저장가 배수)·DB 폴백·액면변경 감지·종목명 보충이 기준을 잃는다. '최신'의 정의가 두 가지라
 *       둘 다 지킨다 — {@code findTopByStockCodeOrderByFetchedAtDesc}(fetched_at 최대)와
 *       {@code findLatestByStockCodes}(id 최대). 지키는 행 목록은 회차 시작에 한 번 만든다 — 회차 중 들어오는 새 행은
 *       기준일보다 새로워 지울 대상이 아니고, 옛 최신 행을 더 지키는 것은 무해하다.</li>
 *   <li><b>장이 열린 시간(NXT 08:00~20:00, 장이 열린 날)엔 지우지 않는다.</b> 묶음마다 다시 본다.</li>
 * </ul>
 *
 * <p>작은 묶음(기본 2,000행)으로 fetched_at·id 순서를 따라가며 지운다(묶음마다 자동 커밋 — 긴 트랜잭션·긴 잠금 없음).
 * 회차 상한(행 수·시간)에 닿으면 멈추고 다음 날 이어서 한다. 회차 끝에 요약 한 줄(삭제 수·남은 행). 실패는 ERROR 로 남기고
 * 다시 던진다 — 배치 모니터({@code ScheduledJobMonitorAspect})가 FAILED 로 기록한다(§4c 침묵 금지).
 *
 * <p>시세 조회·저장 경로({@code StockPriceService}, §1 단일 경로)는 건드리지 않는다. 행 삭제는 이 잡뿐이고, 이 잡은
 * 사용자가 {@code STOCK_PRICE_RETENTION_ENABLED=true} 로 켤 때만 돈다. 꺼져 있으면 부팅 때 WARN 한 번(§4b).
 */
@Service
public class StockPriceRetentionService {

    private static final Logger log = LoggerFactory.getLogger(StockPriceRetentionService.class);

    /**
     * 보존 기간 하한(일). 기간 조회 소비처 중 가장 긴 창이 7일이다(findTopVolumeStockCodes 7일·진단 스캔 168시간).
     * 이보다 짧게 설정하면 그 조회가 빈 결과를 낸다 — 지우지 않고 WARN 으로 거절한다.
     */
    public static final int MIN_RETENTION_DAYS = 8;

    /** 회차 시간 상한 — 05:10 에 시작해 NXT 시작(08:00) 훨씬 전에 끝난다. 400만 행 실측은 회차당 수 분이다. */
    static final Duration MAX_RUN_DURATION = Duration.ofMinutes(30);

    /** 지우기 대상 순회의 시작 커서 — fetched_at 이 이보다 이른 행은 없다. */
    private static final LocalDateTime CURSOR_START = LocalDateTime.of(1970, 1, 1, 0, 0);

    private static final String PROTECT_MAX_ID =
            "SELECT MAX(id) FROM stock_price GROUP BY stock_code";
    private static final String PROTECT_MAX_FETCHED =
            "SELECT sp.id FROM stock_price sp JOIN ("
                    + "SELECT stock_code, MAX(fetched_at) AS mf FROM stock_price GROUP BY stock_code"
                    + ") m ON sp.stock_code = m.stock_code AND sp.fetched_at = m.mf";
    /** (fetched_at, id) 순서로 기준일 이전 행을 앞에서부터 — V75 인덱스(fetched_at, 뒤에 PK id) 범위 읽기. */
    private static final String NEXT_CANDIDATES =
            "SELECT id, fetched_at FROM stock_price "
                    + "WHERE fetched_at < :cutoff "
                    + "AND (fetched_at > :afterAt OR (fetched_at = :afterAt AND id > :afterId)) "
                    + "ORDER BY fetched_at, id LIMIT :limit";
    /** 기준일 조건을 한 번 더 건다 — 후보를 고른 뒤 바뀐 것이 있어도 기준일 이후 행은 지우지 않는다. */
    private static final String DELETE_IDS =
            "DELETE FROM stock_price WHERE id IN (:ids) AND fetched_at < :cutoff";

    private final NamedParameterJdbcTemplate jdbc;
    private final MarketCalendarService calendar;
    private final Clock clock;
    private final boolean enabled;
    private final int retentionDays;
    private final int batchSize;
    private final int maxRowsPerRun;

    /** 묶음 사이 쉬는 시간(ms) — 다른 쿼리에 자리를 내준다. 테스트는 0. */
    long pauseMillis = 100;

    public StockPriceRetentionService(NamedParameterJdbcTemplate jdbc,
                                      MarketCalendarService calendar,
                                      Clock clock,
                                      @Value("${stock-price.retention.enabled:false}") boolean enabled,
                                      @Value("${stock-price.retention.days:90}") int retentionDays,
                                      @Value("${stock-price.retention.batch-size:2000}") int batchSize,
                                      @Value("${stock-price.retention.max-rows-per-run:500000}") int maxRowsPerRun) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.clock = clock;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
        this.batchSize = Math.max(1, batchSize);
        this.maxRowsPerRun = Math.max(1, maxRowsPerRun);
    }

    /** 꺼져 있거나 기간이 하한 미만이면 부팅 때 한 번 알린다 — 기본 false + 조용한 스킵은 '조용히 안 도는' 조합이다(§4b). */
    @PostConstruct
    void announceState() {
        if (!enabled) {
            log.warn("[시세 보존] stock_price 보존 잡이 꺼져 있다 — 표가 하루 약 2만 행씩 계속 는다(지운 적 없음). "
                    + "켜려면 STOCK_PRICE_RETENTION_ENABLED=true (보존 {}일, STOCK_PRICE_RETENTION_DAYS). "
                    + "켜기 전에 옛 행 내보내기: docs/STOCK_PRICE_RETENTION.md", retentionDays);
        } else if (retentionDays < MIN_RETENTION_DAYS) {
            log.warn("[시세 보존] 보존 기간 {}일은 하한 {}일보다 짧아 지우지 않는다 — 최근 7일을 보는 조회가 있다. "
                    + "STOCK_PRICE_RETENTION_DAYS 를 {} 이상으로", retentionDays, MIN_RETENTION_DAYS, MIN_RETENTION_DAYS);
        } else {
            log.info("[시세 보존] 켜짐 — 매일 05:10, {}일 이전 행(종목별 최신 행 제외)을 묶음 {}행·회차 상한 {}행으로 지운다",
                    retentionDays, batchSize, maxRowsPerRun);
        }
    }

    /**
     * 매일 05:10:20 KST(주말·휴장일 포함 — 장이 닫힌 시간이라 상관없다). 03:00 DB 백업(stock_price 가 가장 큰 표라
     * 덤프 중에 지우지 않는다)·04:30·04:40 정리 잡을 지나고, 06:00 대 크론·07:30 모닝브리핑보다 앞이다.
     */
    @Scheduled(scheduler = "batchScheduler", cron = "20 10 5 * * *", zone = "Asia/Seoul")
    public void purgeOldStockPrices() {
        if (!enabled) {
            log.debug("[시세 보존] 꺼져 있음 — 건너뜀");
            return;
        }
        run();
    }

    /** 한 회차 결과. {@code stopReason} 은 왜 멈췄는가 — 다 지움·회차 상한·시간 상한·장 시작·건너뜀. */
    public record Report(LocalDateTime cutoff, long deleted, int batches, long protectedRows,
                         Long remainingOlder, Long totalRows, String stopReason, long elapsedMillis) {}

    /** 회차 한 번 — 켜짐 여부는 보지 않는다(크론 입구가 본다). */
    Report run() {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        long started = System.currentTimeMillis();
        LocalDateTime cutoff = startedAt.minusDays(retentionDays);

        if (retentionDays < MIN_RETENTION_DAYS) {
            log.warn("[시세 보존] 보존 기간 {}일 < 하한 {}일 — 이번 회차는 지우지 않는다", retentionDays, MIN_RETENTION_DAYS);
            return new Report(cutoff, 0, 0, 0, null, null, "기간 하한 미만", 0);
        }
        if (marketOpenAt(startedAt)) {
            log.info("[시세 보존] 장이 열린 시간이라 건너뜀 ({})", startedAt);
            return new Report(cutoff, 0, 0, 0, null, null, "장이 열린 시간", 0);
        }

        long deleted = 0;
        int batches = 0;
        long protectedRows = 0;
        String stopReason = "기준일 이전 행을 다 지움";
        try {
            Set<Long> protectedIds = latestRowIds();
            protectedRows = protectedIds.size();

            LocalDateTime afterAt = CURSOR_START;
            long afterId = 0;
            while (true) {
                LocalDateTime now = LocalDateTime.now(clock);
                if (marketOpenAt(now)) { stopReason = "장 시작"; break; }
                if (Duration.between(startedAt, now).compareTo(MAX_RUN_DURATION) >= 0) { stopReason = "시간 상한"; break; }
                if (deleted >= maxRowsPerRun) { stopReason = "회차 상한 " + maxRowsPerRun + "행"; break; }

                int limit = (int) Math.min(batchSize, maxRowsPerRun - deleted);
                List<Candidate> page = jdbc.query(NEXT_CANDIDATES,
                        new MapSqlParameterSource()
                                .addValue("cutoff", cutoff)
                                .addValue("afterAt", afterAt)
                                .addValue("afterId", afterId)
                                .addValue("limit", limit),
                        (rs, i) -> new Candidate(rs.getLong(1), rs.getTimestamp(2).toLocalDateTime()));
                if (page.isEmpty()) break;

                Candidate last = page.get(page.size() - 1);
                afterAt = last.fetchedAt();
                afterId = last.id();

                List<Long> ids = deletableIds(page, protectedIds);
                if (!ids.isEmpty()) {
                    deleted += jdbc.update(DELETE_IDS,
                            new MapSqlParameterSource().addValue("ids", ids).addValue("cutoff", cutoff));
                    batches++;
                }
                if (page.size() < limit) break;
                pause();
            }
        } catch (RuntimeException e) {
            log.error("[시세 보존] 실패 — 이번 회차 {}행 지운 뒤 멈춤(기준 {} 이전): {}", deleted, cutoff, e.getMessage());
            throw e;
        }

        Long remainingOlder = countOrNull("SELECT COUNT(*) FROM stock_price WHERE fetched_at < :cutoff", cutoff);
        Long totalRows = countOrNull("SELECT COUNT(*) FROM stock_price", null);
        long elapsed = System.currentTimeMillis() - started;
        log.info("[시세 보존] 삭제 {}행(묶음 {}개, {}초) · 기준 {} 이전 남은 행 {}(종목별 최신 행 보호 {}) · 표 전체 {}행 · {}",
                deleted, batches, elapsed / 1000, cutoff.toLocalDate(),
                remainingOlder == null ? "모름" : remainingOlder, protectedRows,
                totalRows == null ? "모름" : totalRows, stopReason);
        return new Report(cutoff, deleted, batches, protectedRows, remainingOlder, totalRows, stopReason, elapsed);
    }

    /** 종목별 최신 행 id — fetched_at 최대(동률이면 전부)와 id 최대를 모두. */
    Set<Long> latestRowIds() {
        Set<Long> ids = new HashSet<>();
        ids.addAll(jdbc.getJdbcTemplate().queryForList(PROTECT_MAX_ID, Long.class));
        ids.addAll(jdbc.getJdbcTemplate().queryForList(PROTECT_MAX_FETCHED, Long.class));
        return ids;
    }

    /** 순수 — 후보 중 지켜야 할 행을 뺀 id. */
    static List<Long> deletableIds(List<Candidate> page, Set<Long> protectedIds) {
        List<Long> out = new ArrayList<>(page.size());
        for (Candidate c : page) {
            if (!protectedIds.contains(c.id())) out.add(c.id());
        }
        return out;
    }

    private boolean marketOpenAt(LocalDateTime t) {
        LocalDate d = t.toLocalDate();
        return calendar.isNxtSession(d, t.toLocalTime());
    }

    /** 요약용 세기 — 실패해도 지운 결과는 이미 커밋됐으니 '모름'으로 적고 넘어간다. */
    private Long countOrNull(String sql, LocalDateTime cutoff) {
        try {
            return jdbc.queryForObject(sql, new MapSqlParameterSource("cutoff", cutoff), Long.class);
        } catch (RuntimeException e) {
            log.warn("[시세 보존] 남은 행 세기 실패: {}", e.getMessage());
            return null;
        }
    }

    private void pause() {
        if (pauseMillis <= 0) return;
        try {
            Thread.sleep(pauseMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("보존 잡 중단(인터럽트)", e);
        }
    }

    record Candidate(long id, LocalDateTime fetchedAt) {}
}
