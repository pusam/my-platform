package com.myplatform.backend.repository;

import com.myplatform.backend.entity.SignalOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findD3Pending} — 재시도 가능 여부와 순서를 <b>DB 에서</b> 정한 뒤 상한을 거는지(2026-09-17 코덱스 2차 ①).
 *
 * <p>인메모리 H2(MySQL 모드)에서 실제 JPQL 을 돌린다. Spring Boot 4 는 {@code @DataJpaTest} 가 별도 모듈
 * (spring-boot-data-jpa-test)이라 이 저장소에 없다 — 새 의존성 대신 JPA 만 띄우는 최소 컨텍스트를
 * 직접 조립한다(엔티티 스캔·리포지토리·H2, 그 외 빈 없음).
 *
 * <p>자바에서 먼저 자르고 정렬하던 구현은 앞쪽 영구 결측 종목이 상한을 다 먹어 뒤쪽 신규 종목이
 * 계속 밀렸다 — 여기서 그 굶주림이 재현되지 않음을 고정한다.
 */
@SpringBootTest(classes = SignalOutcomeRepositoryD3Test.JpaOnly.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:d3pending;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
@Transactional
class SignalOutcomeRepositoryD3Test {

    @Configuration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class})
    @EntityScan(basePackageClasses = SignalOutcome.class)
    @EnableJpaRepositories(basePackageClasses = SignalOutcomeRepository.class)
    static class JpaOnly {}

    private static final LocalDate FROM = LocalDate.of(2026, 6, 25);
    private static final LocalDate LATEST_DUE = LocalDate.of(2026, 9, 14);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 19, 45);
    private static final Set<String> RETRYABLE = Set.of("MISSING_BARS", "NO_INDEX");

    @Autowired
    SignalOutcomeRepository repo;

    private SignalOutcome insert(String code, LocalDate date, String status, LocalDateTime attemptedAt) {
        SignalOutcome s = SignalOutcome.builder()
                .signalType("BUY").stockCode(code).stockName(code).signalDate(date)
                .priceAtSignal(new BigDecimal("10000"))
                .d3Status(status).d3EvaluatedAt(attemptedAt)
                .build();
        return repo.saveAndFlush(s);
    }

    private List<SignalOutcome> pending(int limit) {
        return repo.findD3Pending(FROM, LATEST_DUE, RETRYABLE,
                NOW.minusDays(7), "FETCH_FAILED", NOW.minusDays(1), PageRequest.of(0, limit));
    }

    @Test
    @DisplayName("굶주림 방지: 오래된 실패 종목이 앞에 있어도 상한이 걸리기 전에 시도 안 한 신규 행이 먼저 온다")
    void newRowsAreNotStarvedByOldFailures() {
        for (int i = 0; i < 5; i++) {
            insert("OLD" + i, LocalDate.of(2026, 7, 1).plusDays(i), "MISSING_BARS", NOW.minusDays(8));
        }
        insert("NEW1", LocalDate.of(2026, 9, 7), null, null);
        insert("NEW2", LocalDate.of(2026, 9, 8), null, null);
        insert("NEW3", LocalDate.of(2026, 9, 9), null, null);

        assertThat(pending(3)).extracting(SignalOutcome::getStockCode).containsExactly("NEW1", "NEW2", "NEW3");
    }

    @Test
    @DisplayName("재시도 간격: 봉 없음은 7일, 수집 실패는 1일 — 간격 안이면 대상에서 빠진다")
    void retryIntervalsAreAppliedInTheQuery() {
        insert("BARS_RECENT", LocalDate.of(2026, 9, 1), "MISSING_BARS", NOW.minusDays(3));
        insert("BARS_STALE", LocalDate.of(2026, 9, 2), "MISSING_BARS", NOW.minusDays(8));
        insert("FETCH_RECENT", LocalDate.of(2026, 9, 3), "FETCH_FAILED", NOW.minusHours(2));
        insert("FETCH_STALE", LocalDate.of(2026, 9, 4), "FETCH_FAILED", NOW.minusDays(2));
        insert("IDX_STALE", LocalDate.of(2026, 9, 5), "NO_INDEX", NOW.minusDays(9));

        assertThat(pending(100)).extracting(SignalOutcome::getStockCode)
                .containsExactlyInAnyOrder("BARS_STALE", "FETCH_STALE", "IDX_STALE");
    }

    @Test
    @DisplayName("순서: 시도 안 한 행 → 가장 오래전 시도 → 오래된 시그널")
    void orderIsNeverAttemptedThenOldestAttemptThenOldestSignal() {
        insert("ATTEMPT_9D", LocalDate.of(2026, 7, 10), "MISSING_BARS", NOW.minusDays(9));
        insert("ATTEMPT_20D", LocalDate.of(2026, 7, 20), "MISSING_BARS", NOW.minusDays(20));
        insert("NEVER_LATE", LocalDate.of(2026, 9, 10), null, null);
        insert("NEVER_EARLY", LocalDate.of(2026, 9, 1), null, null);

        assertThat(pending(100)).extracting(SignalOutcome::getStockCode)
                .containsExactly("NEVER_EARLY", "NEVER_LATE", "ATTEMPT_20D", "ATTEMPT_9D");
    }

    @Test
    @DisplayName("OK·비재시도 상태(단위 어긋남 등)는 대상이 아니다 — 멱등")
    void okAndTerminalStatusesAreExcluded() {
        insert("OK1", LocalDate.of(2026, 9, 1), "OK", NOW.minusDays(30));
        insert("UNIT", LocalDate.of(2026, 9, 2), "UNIT_MISMATCH_SUSPECT", NOW.minusDays(30));
        insert("HALT", LocalDate.of(2026, 9, 3), "HALTED_IN_WINDOW", NOW.minusDays(30));
        insert("NEW", LocalDate.of(2026, 9, 4), null, null);

        assertThat(pending(100)).extracting(SignalOutcome::getStockCode).containsExactly("NEW");
    }

    @Test
    @DisplayName("force: 전 상태 + 먼 미래 기준이면 OK 행도 돌아온다(비교표 재작성)")
    void forceIncludesEverything() {
        insert("OK1", LocalDate.of(2026, 9, 1), "OK", NOW.minusDays(1));
        insert("NEW", LocalDate.of(2026, 9, 4), null, null);

        Set<String> all = Set.of("OK", "MISSING_BARS", "NO_INDEX", "UNIT_MISMATCH_SUSPECT",
                "HALTED_IN_WINDOW", "CORPORATE_ACTION_SUSPECT", "NO_START_PRICE", "FETCH_FAILED", "NOT_DUE");
        List<SignalOutcome> page = repo.findD3Pending(FROM, LATEST_DUE, all,
                NOW.plusYears(100), "FETCH_FAILED", NOW.plusYears(100), PageRequest.of(0, 100));

        assertThat(page).extracting(SignalOutcome::getStockCode).containsExactly("NEW", "OK1");
    }

    @Test
    @DisplayName("미도래 상한: 시그널일이 latestDue 보다 뒤면 상한을 먹지 않는다")
    void notYetDueRowsDoNotConsumeTheLimit() {
        insert("FUTURE", LocalDate.of(2026, 9, 16), null, null);
        insert("DUE", LocalDate.of(2026, 9, 10), null, null);

        assertThat(pending(1)).extracting(SignalOutcome::getStockCode).containsExactly("DUE");
    }

    // ==================== findD3OkSince — 게이트(⑦) 입력, 2026-09-21 전환 ====================

    private SignalOutcome insertGateRow(String code, LocalDate date, String d3Status, String d3Pct,
                                        boolean legacyEvaluated) {
        SignalOutcome s = SignalOutcome.builder()
                .signalType("BUY").stockCode(code).stockName(code).signalDate(date)
                .priceAtSignal(new BigDecimal("10000"))
                .pctChange3d(legacyEvaluated ? new BigDecimal("5.0000") : null)
                .evaluatedAt(legacyEvaluated ? date.plusDays(3).atTime(19, 30) : null)
                .d3Status(d3Status)
                .d3PctChange(d3Pct == null ? null : new BigDecimal(d3Pct))
                .d3EvaluatedAt(d3Status == null ? null : date.plusDays(3).atTime(19, 45))
                .build();
        return repo.saveAndFlush(s);
    }

    @Test
    @DisplayName("findD3OkSince — 교정 OK 행만: 구 평가 없는 OK 행은 포함, 구 평가 있는 비-OK/미평가 행은 제외, from 미만 제외")
    void findD3OkSince_selectsByCorrectedStatusOnly() {
        LocalDate d = LocalDate.of(2026, 9, 1);
        SignalOutcome okNoLegacy   = insertGateRow("A1", d, "OK", "-2.0000", false);   // 15일 give-up 뒤 백필된 행
        SignalOutcome okWithLegacy = insertGateRow("A2", d, "OK", "-1.0000", true);
        insertGateRow("B1", d, "MISSING_BARS", null, true);       // 구값은 있지만 교정 봉 결측 — 제외
        insertGateRow("B2", d, "NO_INDEX", null, true);           // 미평가 — 제외
        insertGateRow("B3", d, null, null, true);                 // 교정 시도 전 — 제외
        insertGateRow("B4", d, "OK", null, true);                 // OK 인데 값 없음(저장 결함) — 방어적 제외
        insertGateRow("B5", FROM.minusDays(1), "OK", "-3.0000", true);   // 시작일 이전 — 제외

        List<SignalOutcome> rows = repo.findD3OkSince(FROM, "OK");

        assertThat(rows).extracting(SignalOutcome::getStockCode)
                .containsExactlyInAnyOrder(okNoLegacy.getStockCode(), okWithLegacy.getStockCode());
    }
}
