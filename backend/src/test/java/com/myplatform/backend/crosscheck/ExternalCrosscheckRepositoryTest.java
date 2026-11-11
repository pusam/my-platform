package com.myplatform.backend.crosscheck;

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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 대조 저장소 — 보존 정리의 네이티브 DELETE 두 개와 실행 조회를 실제 DB(H2, MySQL 모드)에서 그대로 돌린다.
 * 단위 테스트의 목(mock)은 호출만 확인해서 SQL 이 한 번도 돈 적이 없었다(독립 검토 2026-11-11). MariaDB 에서의 FK CASCADE 는
 * {@code FlywayMigrationTest} 검증 25 가 본다.
 */
@SpringBootTest(classes = ExternalCrosscheckRepositoryTest.JpaOnly.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:crosscheck;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
@Transactional
class ExternalCrosscheckRepositoryTest {

    @Configuration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class})
    @EntityScan(basePackageClasses = ExternalCrosscheckRun.class)
    @EnableJpaRepositories(basePackageClasses = ExternalCrosscheckRunRepository.class)
    static class JpaOnly {}

    @Autowired
    ExternalCrosscheckRunRepository runs;

    @Autowired
    ExternalCrosscheckRowRepository rows;

    private ExternalCrosscheckRun run(LocalDate runDate, String trigger, String status, LocalDateTime startedAt) {
        return runs.saveAndFlush(ExternalCrosscheckRun.builder().runDate(runDate).targetBarDate(runDate.minusDays(1))
                .source(ExternalCrosscheckRun.SOURCE_NAVER).triggerType(trigger).status(status).startedAt(startedAt).build());
    }

    private void row(Long runId, String code) {
        rows.saveAndFlush(ExternalCrosscheckRow.builder().runId(runId).stockCode(code).sampleReason("MCAP")
                .item(CrosscheckRules.ITEM_CLOSE).status(CrosscheckRules.MATCH).ourValue(BigDecimal.TEN).build());
    }

    @Test
    @DisplayName("보존 정리 — 기준일 전 실행과 그 종목 행만 지운다(같은 날·뒤 실행은 그대로)")
    void purgeDeletesOnlyOldRunsAndTheirRows() {
        ExternalCrosscheckRun old = run(LocalDate.of(2025, 11, 10), "CRON", "COMPLETE", LocalDateTime.of(2025, 11, 10, 10, 20));
        ExternalCrosscheckRun edge = run(LocalDate.of(2025, 11, 11), "CRON", "COMPLETE", LocalDateTime.of(2025, 11, 11, 10, 20));
        row(old.getId(), "005930");
        row(old.getId(), "000660");
        row(edge.getId(), "005930");

        LocalDate cutoff = LocalDate.of(2025, 11, 11);
        assertThat(runs.deleteRowsOfRunsBefore(cutoff)).isEqualTo(2);
        assertThat(runs.deleteRunsBefore(cutoff)).isEqualTo(1);

        assertThat(runs.findAll()).extracting(ExternalCrosscheckRun::getId).containsExactly(edge.getId());
        assertThat(rows.findAll()).extracting(ExternalCrosscheckRow::getRunId).containsExactly(edge.getId());
    }

    @Test
    @DisplayName("수동 실행 하루 횟수와 끝나지 못한 RUNNING 실행 조회")
    void manualCountAndStaleRunning() {
        LocalDate today = LocalDate.of(2026, 11, 11);
        run(today, "MANUAL", "COMPLETE", LocalDateTime.of(2026, 11, 11, 10, 40));
        run(today, "MANUAL", "ABORTED", LocalDateTime.of(2026, 11, 11, 11, 0));
        run(today, "CRON", "RUNNING", LocalDateTime.of(2026, 11, 11, 10, 20));
        run(today, "CRON", "RUNNING", LocalDateTime.of(2026, 11, 11, 11, 30));

        assertThat(runs.countByRunDateAndTriggerType(today, "MANUAL")).isEqualTo(2);
        assertThat(runs.findByStatusAndStartedAtBefore("RUNNING", LocalDateTime.of(2026, 11, 11, 11, 0)))
                .extracting(ExternalCrosscheckRun::getStartedAt).containsExactly(LocalDateTime.of(2026, 11, 11, 10, 20));
        assertThat(runs.findTop10ByOrderByStartedAtDesc().get(0).getStartedAt()).isEqualTo(LocalDateTime.of(2026, 11, 11, 11, 30));
    }
}
