package com.myplatform.backend.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * stock_price 보존 잡(2026-11-10, 개선 후보 6번) — 인메모리 H2(MariaDB 모드)에서 실제 SQL 을 돌린다.
 *
 * <p>핵심 불변식: <b>종목별 최신 행은 보존 기간과 무관하게 남는다</b> — 거래정지 종목처럼 오래 시세가 안 들어온 종목의
 * 마지막 행이 지워지면 가격 이상치 그물·DB 폴백·액면변경 감지·종목명 보충이 기준을 잃는다. '최신'의 정의가 두 가지
 * (fetched_at 최대 = findTopByStockCodeOrderByFetchedAtDesc, id 최대 = findLatestByStockCodes)라 둘 다 지킨다.
 */
class StockPriceRetentionServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-11-14 토요일 05:10 — 장이 닫힌 날. 보존 90일이면 기준 2026-08-16 05:10. */
    private static final LocalDateTime SAT_0510 = LocalDateTime.of(2026, 11, 14, 5, 10);
    private static final LocalDateTime OLD = LocalDateTime.of(2026, 3, 2, 10, 0);
    private static final LocalDateTime RECENT = LocalDateTime.of(2026, 11, 13, 15, 0);

    private static final AtomicLong DB_SEQ = new AtomicLong();

    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        ds = new DriverManagerDataSource(
                "jdbc:h2:mem:sp_retention_" + DB_SEQ.incrementAndGet() + ";MODE=MariaDB;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE stock_price (id BIGINT AUTO_INCREMENT PRIMARY KEY, stock_code VARCHAR(20) NOT NULL, "
                + "current_price DECIMAL(15,2) NOT NULL, fetched_at TIMESTAMP NULL)");
        jdbc.execute("CREATE INDEX idx_stock_price_fetched_at ON stock_price (fetched_at)");
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("SHUTDOWN");
    }

    private long insert(String code, LocalDateTime at) {
        jdbc.update("INSERT INTO stock_price (stock_code, current_price, fetched_at) VALUES (?, 1000, ?)", code, at);
        return jdbc.queryForObject("SELECT MAX(id) FROM stock_price", Long.class);
    }

    private long insertWithId(long id, String code, LocalDateTime at) {
        jdbc.update("INSERT INTO stock_price (id, stock_code, current_price, fetched_at) VALUES (?, ?, 1000, ?)", id, code, at);
        return id;
    }

    private List<Long> ids() {
        return jdbc.queryForList("SELECT id FROM stock_price ORDER BY id", Long.class);
    }

    private StockPriceRetentionService service(Clock clock, boolean enabled, int days, int batch, int maxRows) {
        StockPriceRetentionService s = new StockPriceRetentionService(new NamedParameterJdbcTemplate(ds),
                new MarketCalendarService(), clock, enabled, days, batch, maxRows);
        s.pauseMillis = 0;
        return s;
    }

    private static Clock fixed(LocalDateTime t) {
        return Clock.fixed(t.atZone(KST).toInstant(), KST);
    }

    @Test
    @DisplayName("기준일 이전 행은 지우고, 기준일 이후 행과 종목별 최신 행(오래돼도)은 남긴다")
    void deletesOldRowsButKeepsLatestPerStock() {
        long a1 = insert("005930", OLD);
        long a2 = insert("005930", OLD.plusDays(1));
        long a3 = insert("005930", RECENT);
        // 거래정지 종목 — 시세가 3월 이후 안 들어왔다. 마지막 행은 기간이 지나도 남아야 한다.
        long h1 = insert("294090", OLD);
        long h2 = insert("294090", OLD.plusDays(2));

        var report = service(fixed(SAT_0510), true, 90, 2, 1000).run();

        assertThat(ids()).containsExactlyInAnyOrder(a3, h2);
        assertThat(report.deleted()).isEqualTo(3);
        assertThat(report.remainingOlder()).as("기준일 이전 남은 행 = 정지 종목의 최신 행").isEqualTo(1L);
        assertThat(report.totalRows()).isEqualTo(2L);
        assertThat(report.stopReason()).isEqualTo("기준일 이전 행을 다 지움");
        assertThat(List.of(a1, a2, h1)).doesNotContainAnyElementsOf(ids());
    }

    @Test
    @DisplayName("'최신'이 두 정의로 갈리는 종목(id 최대 ≠ fetched_at 최대)은 두 행을 다 남긴다")
    void keepsBothLatestDefinitions() {
        long older = insertWithId(10, "000660", OLD);              // id 가 더 작지만 fetched_at 이 최신
        jdbc.update("UPDATE stock_price SET fetched_at = ? WHERE id = 10", OLD.plusDays(5));
        long maxIdButEarlier = insertWithId(20, "000660", OLD.plusDays(1)); // id 최대
        long plainOld = insertWithId(5, "000660", OLD);

        service(fixed(SAT_0510), true, 90, 100, 1000).run();

        assertThat(ids()).containsExactlyInAnyOrder(older, maxIdButEarlier);
        assertThat(ids()).doesNotContain(plainOld);
    }

    @Test
    @DisplayName("묶음이 전부 보호 행이어도 커서가 앞으로 나간다 — 뒤의 지울 행까지 간다")
    void cursorAdvancesPastProtectedRows() {
        // 오래된 정지 종목 5개의 최신 행(보호)이 가장 앞에 있다
        for (int i = 0; i < 5; i++) insert("H0000" + i, OLD.minusDays(30));
        long deletable = insert("005930", OLD);
        insert("005930", RECENT);

        service(fixed(SAT_0510), true, 90, 1, 1000).run();

        assertThat(ids()).doesNotContain(deletable).hasSize(6);
    }

    @Test
    @DisplayName("회차 상한에 닿으면 멈추고 다음 회차가 이어서 지운다")
    void stopsAtRunCapAndContinuesNextRun() {
        for (int i = 0; i < 7; i++) insert("005930", OLD.plusMinutes(i));
        insert("005930", RECENT);

        var first = service(fixed(SAT_0510), true, 90, 2, 5).run();
        assertThat(first.deleted()).isEqualTo(5);
        assertThat(first.stopReason()).isEqualTo("회차 상한 5행");
        assertThat(first.remainingOlder()).isEqualTo(2L);

        var second = service(fixed(SAT_0510), true, 90, 2, 5).run();
        assertThat(second.deleted()).isEqualTo(2);
        assertThat(ids()).hasSize(1);
    }

    @Test
    @DisplayName("장이 열린 시간(거래일 NXT 08:00~20:00)엔 지우지 않는다")
    void doesNotRunDuringMarketHours() {
        insert("005930", OLD);
        insert("005930", RECENT);

        var report = service(fixed(LocalDateTime.of(2026, 11, 10, 10, 0)), true, 90, 100, 1000).run();

        assertThat(report.deleted()).isZero();
        assertThat(report.stopReason()).isEqualTo("장이 열린 시간");
        assertThat(ids()).hasSize(2);
    }

    @Test
    @DisplayName("회차 중에 장이 열리면(08:00) 그 자리에서 멈춘다")
    void stopsWhenMarketOpensMidRun() {
        for (int i = 0; i < 20; i++) insert("005930", OLD.plusMinutes(i));
        insert("005930", RECENT);
        // 2026-11-10(화, 거래일) 07:55 에서 시작해 시계를 볼 때마다 1분씩 간다
        Clock ticking = new Clock() {
            final AtomicLong calls = new AtomicLong();
            final Instant start = LocalDateTime.of(2026, 11, 10, 7, 55).atZone(KST).toInstant();
            @Override public ZoneId getZone() { return KST; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return start.plusSeconds(60 * calls.getAndIncrement()); }
        };

        var report = service(ticking, true, 90, 1, 1000).run();

        assertThat(report.stopReason()).isEqualTo("장 시작");
        assertThat(report.deleted()).isBetween(1L, 5L);
        assertThat(ids().size()).isGreaterThan(15);
    }

    @Test
    @DisplayName("보존 기간이 하한(8일) 미만이면 지우지 않는다 — 최근 7일을 보는 조회가 있다")
    void refusesRetentionBelowMinimum() {
        insert("005930", SAT_0510.minusDays(5));
        insert("005930", SAT_0510.minusDays(1));

        var report = service(fixed(SAT_0510), true, 3, 100, 1000).run();

        assertThat(report.deleted()).isZero();
        assertThat(ids()).hasSize(2);
    }

    @Test
    @DisplayName("꺼져 있으면(기본) 크론이 아무것도 지우지 않는다")
    void disabledByDefaultDeletesNothing() {
        insert("005930", OLD);
        insert("005930", RECENT);

        service(fixed(SAT_0510), false, 90, 100, 1000).purgeOldStockPrices();

        assertThat(ids()).hasSize(2);
    }

    @Test
    @DisplayName("실패는 삼키지 않는다 — 다시 던져 배치 모니터가 FAILED 로 기록한다")
    void failureIsRethrown() {
        jdbc.execute("DROP TABLE stock_price");

        assertThatThrownBy(() -> service(fixed(SAT_0510), true, 90, 100, 1000).run())
                .isInstanceOf(RuntimeException.class);
    }
}
