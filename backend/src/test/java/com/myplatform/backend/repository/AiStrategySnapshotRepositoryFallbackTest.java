package com.myplatform.backend.repository;

import com.myplatform.backend.entity.AiStrategySnapshot;
import com.myplatform.backend.entity.AiStrategySnapshot.StrategyType;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 은퇴한 대체 목록 행은 읽지 않는다(2026-10-02) — H2 로 실제 JPQL·네이티브 SQL 을 돌린다.
 *
 * <p>운영 SCALPING 은 30일 146회 전부가 '시총 상위 대형주' 대체 목록이었다(진짜 회차 0). 그 코드를 지운 뒤에도
 * 남은 행이 '최신'으로 읽히면 10/6 종합추천 AI 시드·테마 가산과 백테스트가 계속 가짜 종목을 쓴다.
 */
@SpringBootTest(classes = AiStrategySnapshotRepositoryFallbackTest.JpaOnly.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:aifallback;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
@Transactional
class AiStrategySnapshotRepositoryFallbackTest {

    @Configuration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class})
    @EntityScan(basePackageClasses = AiStrategySnapshot.class)
    @EnableJpaRepositories(basePackageClasses = AiStrategySnapshotRepository.class)
    static class JpaOnly {}

    @Autowired private AiStrategySnapshotRepository repository;

    private static final LocalDateTime REAL_AT = LocalDateTime.of(2026, 10, 2, 16, 0);
    private static final LocalDateTime FAKE_AT = LocalDateTime.of(2026, 10, 2, 19, 30);

    private AiStrategySnapshot row(StrategyType type, String code, String name, int rank, String reason, LocalDateTime at) {
        return repository.save(AiStrategySnapshot.builder()
                .strategyType(type).stockCode(code).stockName(name).rankNum(rank).score(70)
                .reason(reason).createdAt(at).build());
    }

    @BeforeEach
    void seed() {
        row(StrategyType.SCALPING, "196170", "알테오젠", 1, "거래량 312% 급증, +4.10% 상승", REAL_AT);
        // 운영에 남아 있는 대체 목록 회차(가장 최신) — 사유 문자열이 그 표지다
        row(StrategyType.SCALPING, "373220", "LG에너지솔루션", 1, "시총 상위 대형주 (+2.50%)", FAKE_AT);
        row(StrategyType.SCALPING, "009150", "삼성전기", 2, "시총 상위 대형주 (+1.02%)", FAKE_AT);
        row(StrategyType.VALUE, "005930", "삼성전자", 1, "시가총액 상위 대표주", FAKE_AT);
        row(StrategyType.SWING, "402340", "SK스퀘어", 1, "ROE 18.0% | 영업이익률 12.0%", REAL_AT);
    }

    @Test
    @DisplayName("재현: 대체 목록 회차가 '최신'이던 것 — 이제 그 앞의 진짜 회차가 최신이다")
    void latestSkipsFallbackBatch() {
        assertThat(repository.findLatestByStrategyType(StrategyType.SCALPING))
                .extracting(AiStrategySnapshot::getStockCode).containsExactly("196170");
        assertThat(repository.findLatestCreatedAt(StrategyType.SCALPING)).contains(REAL_AT);
    }

    @Test
    @DisplayName("진짜 회차가 하나도 없으면 비어 있다 — 대체 목록을 대신 내놓지 않는다(가치 전략)")
    void onlyFallbackMeansEmpty() {
        assertThat(repository.findLatestByStrategyType(StrategyType.VALUE)).isEmpty();
        assertThat(repository.findLatestCreatedAt(StrategyType.VALUE)).isEmpty();
    }

    @Test
    @DisplayName("전략별 최신 묶음(네이티브 SQL)·백테스트 구간·과거 시점 조회도 대체 목록을 읽지 않는다")
    void otherReadersSkipFallback() {
        assertThat(repository.findAllLatestSnapshots()).extracting(AiStrategySnapshot::getStockCode)
                .containsExactlyInAnyOrder("196170", "402340");
        assertThat(repository.findByStrategyTypeAndCreatedAtAfterOrderByCreatedAtAsc(StrategyType.SCALPING,
                REAL_AT.minusDays(1))).extracting(AiStrategySnapshot::getStockCode).containsExactly("196170");
        assertThat(repository.findNearestSnapshotBefore("009150", FAKE_AT.plusHours(1))).isEmpty();
        assertThat(repository.findLatestByStrategyTypeAndStockCode(StrategyType.SCALPING, "373220")).isEmpty();
    }

    @Test
    @DisplayName("정리 잡의 생존 판정은 전략 구분 없이 가장 최근 스냅샷 시각")
    void heartbeatIsAnyStrategy() {
        assertThat(repository.findLatestCreatedAtAnyStrategy()).contains(FAKE_AT);
    }
}
