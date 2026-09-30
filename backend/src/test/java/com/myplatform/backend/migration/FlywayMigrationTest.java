package com.myplatform.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
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
        // V62 는 데이터 마이그레이션이다 — 행이 있어야 writer 구분(KIS 만 비우고 네이버는 그대로)을 검증할 수 있다.
        seedGrowthRowsForV62();
        // V63 도 데이터 마이그레이션 — 오독 행(TTM 매출 없음)만 비우고 TTM 행·네이버 행은 그대로인지 본다.
        seedOperatingMarginRowsForV63();

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

        // --- 검증 7 (V47 회귀 가드): catalyst_alert_dedup UNIQUE(alert_key) — 악재경보 dedup 선점의 전제 ---
        assertThat(uniqueConstraintCount("catalyst_alert_dedup", "uq_cad_alert_key"))
                .as("V47 catalyst_alert_dedup UNIQUE 제약(uq_cad_alert_key)이 적용되어야 함")
                .isEqualTo(1);

        // --- 검증 8 (V48 도달 확인): growth/value_stability nullable 전환(NULL=NA, P3-3) ---
        assertThat(isColumnNullable("recommendation_snapshot", "growth"))
                .as("V48 recommendation_snapshot.growth 가 NULL 허용이어야 함(NULL=NA)")
                .isTrue();
        assertThat(isColumnNullable("recommendation_snapshot", "value_stability"))
                .as("V48 recommendation_snapshot.value_stability 가 NULL 허용이어야 함(NULL=NA)")
                .isTrue();

        // --- 검증 9 (V50 도달 확인): signal_outcome 에 KOSPI 지수 채널 스냅샷 3컬럼(NULL=미수집) ---
        assertThat(columnCount("signal_outcome", "index_channel_direction_at_signal"))
                .as("V50 signal_outcome.index_channel_direction_at_signal 컬럼이 적용되어야 함")
                .isEqualTo(1);
        assertThat(columnCount("signal_outcome", "index_channel_position_at_signal"))
                .as("V50 signal_outcome.index_channel_position_at_signal 컬럼이 적용되어야 함")
                .isEqualTo(1);
        assertThat(columnCount("signal_outcome", "index_channel_width_pct_at_signal"))
                .as("V50 signal_outcome.index_channel_width_pct_at_signal 컬럼이 적용되어야 함")
                .isEqualTo(1);

        // --- 검증 10 (V52 도달 확인): pattern_detection 생성 + (stock_code, pattern_type, detected_date)
        //     UNIQUE — 배치 재실행/late-data 재스캔 dedup 의 전제 ---
        assertThat(uniqueConstraintCount("pattern_detection", "uq_pd_code_type_date"))
                .as("V52 pattern_detection UNIQUE 제약(uq_pd_code_type_date)이 적용되어야 함")
                .isEqualTo(1);

        // --- 검증 11 (V61 도달 확인): 유튜브 의견 테이블 — 재등록·같은 자막·재분석이 중복을 못 만드는 UNIQUE 들 ---
        assertThat(uniqueConstraintCount("yt_video", "uq_ytv_video_id"))
                .as("V61 yt_video UNIQUE(video_id) — 같은 영상 재등록 차단").isEqualTo(1);
        assertThat(uniqueConstraintCount("yt_person", "uq_ytp_name_key"))
                .as("V61 yt_person UNIQUE(name_key) — 같은 사람이 여러 명으로 부풀지 않게").isEqualTo(1);
        assertThat(uniqueConstraintCount("yt_video_participant", "uq_ytvp_video_person"))
                .as("V61 yt_video_participant UNIQUE(video_id, person_id)").isEqualTo(1);
        assertThat(uniqueConstraintCount("yt_transcript", "uq_ytt_video_version"))
                .as("V61 yt_transcript UNIQUE(video_id, version)").isEqualTo(1);
        assertThat(uniqueConstraintCount("yt_transcript", "uq_ytt_video_hash"))
                .as("V61 yt_transcript UNIQUE(video_id, content_sha256) — 같은 자막 재등록 차단").isEqualTo(1);
        assertThat(uniqueConstraintCount("yt_opinion", "uq_yto_run_key"))
                .as("V61 yt_opinion UNIQUE(run_id, statement_key) — 한 실행 안 중복 발언 차단").isEqualTo(1);
        assertThat(columnCount("yt_analysis_run", "prompt_version"))
                .as("V61 yt_analysis_run.prompt_version — 모델·프롬프트 버전 기록").isEqualTo(1);
        assertThat(isColumnNullable("yt_opinion", "stock_code"))
                .as("V61 yt_opinion.stock_code NULL 허용 — 종목 미확인 발언은 코드 없이 검토로").isTrue();
        assertThat(isColumnNullable("yt_opinion", "target_price"))
                .as("V61 yt_opinion.target_price NULL 허용 — 원문에 없으면 비워 둔다").isTrue();

        // --- 검증 12 (V61 엔티티 ↔ 스키마): 운영은 ddl-auto: validate 라 둘이 어긋나면 배포 뒤 부팅이 실패한다.
        //     스모크 테스트(H2 create-drop)는 엔티티로 스키마를 만들어 이 불일치를 못 잡는다 — 여기서 같은 판정을 한다.
        validateEntitiesAgainstSchema("com.myplatform.backend.youtubeopinion");

        // --- 검증 13 (V62): KIS 일별 행의 성장률 4종만 백업 후 비운다 — 네이버 분기 행(market_cap NULL)은 그대로 ---
        assertThat(scalarLong("SELECT COUNT(*) FROM stock_financial_data WHERE market_cap IS NOT NULL "
                + "AND (eps_growth IS NOT NULL OR profit_growth IS NOT NULL OR revenue_growth IS NOT NULL OR peg IS NOT NULL)"))
                .as("V62 KIS 일별 행 성장률이 전부 NULL — 새 배치가 분기 원본으로 다시 채운다").isZero();
        assertThat(scalarString("SELECT profit_growth FROM stock_financial_data WHERE market_cap IS NULL AND stock_code = '019010'"))
                .as("V62 네이버 분기 행은 writer 가 달라 건드리지 않는다(V56→V57 사고)").isEqualTo("55.00");
        assertThat(scalarLong("SELECT COUNT(*) FROM stock_financial_growth_backup_v62"))
                .as("V62 백업은 값이 있던 KIS 행만 — 전부 NULL 인 행·네이버 행은 제외").isEqualTo(1);
        assertThat(scalarString("SELECT CONCAT(stock_code, '/', revenue_growth, '/', peg) FROM stock_financial_growth_backup_v62"))
                .as("V62 백업에 비우기 전 값이 남아 되돌릴 수 있다").isEqualTo("019010/371.00/0.80");

        // --- 검증 14 (V63): TTM 매출 없는 KIS 행의 영업이익률(재무비율 API 오독 — 실은 영업이익 증가율)만 백업 후 비운다 ---
        assertThat(scalarLong("SELECT COUNT(*) FROM stock_financial_data WHERE market_cap IS NOT NULL "
                + "AND revenue IS NULL AND operating_margin IS NOT NULL"))
                .as("V63 TTM 매출 없는 KIS 행의 영업이익률이 전부 NULL(0 도 파싱 실패라 같이)").isZero();
        assertThat(scalarString("SELECT operating_margin FROM stock_financial_data WHERE stock_code = '019180'"))
                .as("V63 TTM 매출이 있는 행의 영업이익률(영업이익÷매출)은 그대로").isEqualTo("6.98");
        assertThat(scalarString("SELECT operating_margin FROM stock_financial_data WHERE market_cap IS NULL AND stock_code = '282620'"))
                .as("V63 네이버 분기 행은 writer 가 달라 건드리지 않는다").isEqualTo("9.29");
        assertThat(scalarLong("SELECT COUNT(*) FROM stock_financial_opm_backup_v63"))
                .as("V63 백업은 비운 행만(증가율 81.65 와 파싱 실패 0)").isEqualTo(2);
        assertThat(scalarString("SELECT operating_margin FROM stock_financial_opm_backup_v63 WHERE stock_code = '282620'"))
                .as("V63 백업에 비우기 전 값이 남아 되돌릴 수 있다").isEqualTo("81.65");

        // --- 검증 15 (V64): DART 지배주주 표 — 보고서 한 건 한 행(재수집이 중복을 못 만든다) + PER 정의 칸 ---
        assertThat(uniqueConstraintCount("dart_controlling_financial", "uq_dcf_stock_report"))
                .as("V64 dart_controlling_financial UNIQUE(stock_code, bsns_year, reprt_code)").isEqualTo(1);
        assertThat(columnCount("stock_financial_data", "per_basis"))
                .as("V64 stock_financial_data.per_basis 컬럼").isEqualTo(1);
        validateEntitiesAgainstSchema("com.myplatform.backend.dartfinancial");
    }

    /** V63 검증용 행 — 오독 행(증가율·파싱 실패 0), TTM 행, 네이버 분기 행. */
    private void seedOperatingMarginRowsForV63() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                        MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
                Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO stock_financial_data (stock_code, report_date, market_cap, revenue, operating_margin) VALUES "
                    + "('282620', '2026-09-29', 718.00, NULL, 81.65), "
                    + "('007370', '2026-09-29', 565.00, NULL, 0.00), "
                    + "('019180', '2026-09-29', 896.00, 11302.00, 6.98), "
                    + "('282620', '2026-06-30', NULL, NULL, 9.29)");
        }
    }

    /** V62 검증용 행 — KIS 행(값 있음·전부 NULL) 둘과 네이버 분기 행 하나. */
    private void seedGrowthRowsForV62() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                        MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
                Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO stock_financial_data (stock_code, report_date, market_cap, "
                    + "eps_growth, profit_growth, revenue_growth, peg) VALUES "
                    + "('019010', '2026-09-28', 1000.00, 12.00, 12.00, 371.00, 0.80), "
                    + "('005930', '2026-09-28', 5000.00, NULL, NULL, NULL, NULL), "
                    + "('019010', '2026-06-30', NULL, NULL, 55.00, NULL, NULL)");
        }
    }

    private long scalarLong(String sql) throws Exception {
        return Long.parseLong(scalarString(sql));
    }

    private String scalarString(String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                        MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /**
     * 지정 패키지의 엔티티를 마이그레이션된 실제 스키마에 Hibernate {@code validate} 로 대조한다(운영 설정과 같은 방언·명명 규칙).
     * 맞지 않으면 {@code afterPropertiesSet} 이 예외를 던진다. 레거시 베이스 테이블은 스텁이라 전 엔티티가 아니라
     * 마이그레이션이 온전히 만든 패키지만 넘긴다.
     */
    private void validateEntitiesAgainstSchema(String... packages) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(new DriverManagerDataSource(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword()));
        emf.setPackagesToScan(packages);
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emf.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.dialect", "org.hibernate.dialect.MariaDBDialect",
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        try {
            emf.afterPropertiesSet();
        } finally {
            emf.destroy();
        }
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

    /** information_schema 로 특정 컬럼의 NULL 허용 여부를 확인한다 — nullable 전환형 마이그레이션 가드용. */
    private boolean isColumnNullable(String table, String column) throws Exception {
        String sql = "SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (Connection conn = DriverManager.getConnection(
                        MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, MARIADB.getDatabaseName());
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "YES".equalsIgnoreCase(rs.getString(1));
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
