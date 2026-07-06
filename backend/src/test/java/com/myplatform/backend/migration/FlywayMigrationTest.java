package com.myplatform.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 MariaDB 상에서 Flyway 마이그레이션 전체 적용을 검증하는 통합 테스트.
 *
 * <p><b>왜</b>: {@code ApplicationContextSmokeTest} 는 H2 + {@code flyway.enabled=false} 라
 * 마이그레이션이 CI 에서 한 번도 실행되지 않는다 — 스모크는 빈 와이어링만 가드하고 스키마는 무방비다.
 * V36(자기조인 DELETE+UNIQUE)·V37·V38 이 연달아 붙은 지금, MariaDB 전용 DDL 오류가 있으면
 * 배포 후 헬스체크에서야 드러난다. 이 테스트가 스키마를 가드한다.
 *
 * <p><b>구조</b>: 운영을 충실히 재현한다. 이 프로젝트의 마이그레이션은 <b>빈 스키마에 self-contained 하지
 * 않다</b> — {@code V1__baseline.sql} 은 no-op 이고, 베이스 테이블은 레거시 ddl-auto 시절 Hibernate 가
 * 만들었으며 운영 Flyway 는 {@code baseline-version=14} 로 V15+ 만 적용한다(§17). 그래서:
 * <ol>
 *   <li>레거시 베이스 테이블의 <b>최소 스텁</b>({@code db/testfixture/legacy_base_stub.sql})을 Flyway 실행
 *       전에 JDBC 로 심는다 → 스키마가 non-empty 가 되어 {@code baseline-on-migrate} 가 트리거된다.</li>
 *   <li>Flyway 를 {@code baselineVersion=14} 로 실행 → 운영과 동일하게 V15→V38 을 적용한다.</li>
 * </ol>
 *
 * <p>스텁은 Testcontainers 의 {@code withInitScript}(내부 {@code ScriptUtils}) 대신 직접 JDBC 로 실행한다
 * — 해당 경로가 shaded commons-io 클래스패스에 의존해 이 버전 조합에서 깨졌고, 직접 실행이 더 견고하다.
 *
 * <p>스모크(@SpringBootTest)와 역할 중복을 피하려고 <b>컨텍스트를 로드하지 않고 Flyway Core API 를 단독
 * 실행</b>한다. {@code @Tag("migration")} 이라 기본 {@code test} 태스크에서 제외되고 {@code migrationTest}
 * 태스크로만 실행된다(로컬은 Docker Desktop, CI 는 build-backend 별도 step).
 */
@Tag("migration")
@Testcontainers
class FlywayMigrationTest {

    private static final String STUB_RESOURCE = "db/testfixture/legacy_base_stub.sql";

    // 운영과 동일한 MariaDB 버전(docker-compose.yml).
    @Container
    @SuppressWarnings("resource")
    static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>(DockerImageName.parse("mariadb:11.2"));

    @Test
    void migratesLegacyBaselineToLatestOnRealMariaDb() throws Exception {
        // Flyway 실행 전 레거시 베이스 스텁을 심어 스키마를 non-empty 로 만든다(baseline-on-migrate 트리거).
        applyLegacyBaseStub();

        Flyway flyway = Flyway.configure()
                .dataSource(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)   // 스텁으로 non-empty 인 스키마에 baseline(v14) 자동 기록
                .baselineVersion("14")     // 운영 설정과 동일 — V1~V14 는 건너뛰고 V15+ 만 적용
                .validateOnMigrate(true)
                .load();

        // --- 검증 1: 마이그레이션이 예외 없이 성공하고 실제로 무언가 적용되었다 ---
        MigrateResult result = flyway.migrate();
        assertThat(result.success).as("Flyway migrate() 성공").isTrue();
        assertThat(result.migrationsExecuted).as("V15+ 마이그레이션이 적용됨").isGreaterThan(0);

        // --- 검증 2: 최신 버전 도달 (하드코딩 대신 pending 이 없음을 확인 → 새 마이그레이션 자동 추종) ---
        MigrationInfo current = flyway.info().current();
        assertThat(current).as("적용된 마이그레이션이 존재").isNotNull();
        assertThat(flyway.info().pending()).as("미적용(pending) 마이그레이션이 없어야 최신 도달").isEmpty();

        // --- 검증 3 (V36 회귀 가드): signal_outcome 에 (signal_type, stock_code, signal_date) UNIQUE 존재 ---
        assertThat(uniqueConstraintCount("signal_outcome", "uq_so_type_code_date"))
                .as("V36 signal_outcome UNIQUE 제약(uq_so_type_code_date)이 적용되어야 함")
                .isEqualTo(1);

        // --- 검증 4 (V40 도달 확인): overnight_us_snapshot 생성 + snapshot_date UNIQUE(일 1행 UPSERT 전제) ---
        assertThat(uniqueConstraintCount("overnight_us_snapshot", "uk_ous_snapshot_date"))
                .as("V40 overnight_us_snapshot 테이블과 UNIQUE 제약(uk_ous_snapshot_date)이 적용되어야 함")
                .isEqualTo(1);

        // --- 검증 5 (V41 도달 확인): signal_outcome.rvol_at_signal 컬럼 추가(NULL=미수집 스냅샷) ---
        assertThat(columnCount("signal_outcome", "rvol_at_signal"))
                .as("V41 signal_outcome.rvol_at_signal 컬럼이 적용되어야 함")
                .isEqualTo(1);

        // --- 검증 6 (V42 도달 확인): ATR 세트 스냅샷/설정 컬럼 ---
        assertThat(columnCount("bot_trading_position", "entry_atr"))
                .as("V42 bot_trading_position.entry_atr 컬럼이 적용되어야 함")
                .isEqualTo(1);
        assertThat(columnCount("bot_config", "atr_risk_budget_krw"))
                .as("V42 bot_config.atr_risk_budget_krw 컬럼이 적용되어야 함")
                .isEqualTo(1);
    }

    /**
     * 스텁 SQL 을 클래스패스에서 읽어 문장 단위로 JDBC 실행한다.
     * 스텁은 단순 CREATE TABLE 뿐이라 {@code --} 라인 주석 제거 후 {@code ;} 로 분리하면 충분하다.
     */
    private void applyLegacyBaseStub() throws Exception {
        String script;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(STUB_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("스텁 리소스를 찾을 수 없음: " + STUB_RESOURCE);
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        StringBuilder cleaned = new StringBuilder();
        for (String line : script.split("\n")) {
            int comment = line.indexOf("--");
            cleaned.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }

        try (Connection conn = DriverManager.getConnection(
                        MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
                Statement stmt = conn.createStatement()) {
            for (String raw : cleaned.toString().split(";")) {
                String sql = raw.trim();
                if (!sql.isEmpty()) {
                    stmt.execute(sql);
                }
            }
        }
    }

    /** information_schema 로 특정 테이블의 컬럼 존재 여부(개수)를 센다 — 컬럼 추가형 마이그레이션 가드용. */
    private long columnCount(String table, String column) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (Connection conn = DriverManager.getConnection(
                        MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, MARIADB.getDatabaseName());
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** information_schema 로 특정 테이블의 명명 UNIQUE 제약 개수를 센다. */
    private long uniqueConstraintCount(String table, String constraintName) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                + "AND CONSTRAINT_TYPE = 'UNIQUE' AND CONSTRAINT_NAME = ?";
        try (Connection conn = DriverManager.getConnection(
                        MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, MARIADB.getDatabaseName());
            ps.setString(2, table);
            ps.setString(3, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
