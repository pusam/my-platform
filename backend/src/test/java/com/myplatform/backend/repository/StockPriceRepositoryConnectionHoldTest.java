package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockPrice;
import com.zaxxer.hikari.HikariDataSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 일괄 시세 조회 안에서 종목별 '직전 저장가' 조회가 DB 연결을 놓는지(2026-11-03).
 *
 * <p>{@code StockPriceService.getStockPrices} 는 KIS 호출을 종목 수만큼 도는 동안 연결을 잡지 않으려고
 * {@code NOT_SUPPORTED} 로 돈다. 그런데 그 안의 가격 이상치 그물이 부르는 {@code findTopByStockCodeOrderByFetchedAtDesc}
 * 는 트랜잭션 설정이 없는 조회 메서드라, 빈 트랜잭션 범위(동기화는 켜져 있다)에 EntityManager 가 묶이고 처음 잡은 연결을
 * 그 범위가 끝날 때까지 쥐고 있었다 — 운영 11/3 섹터 3분 수집(한 바퀴 100~140초)에서 Hikari 누수 경고 7건, 전부 그 조회가
 * 연결을 잡은 자리였다. 같은 저장소의 묶음 조회({@code findLatestByStockCodes})는 이미 자기 짧은 트랜잭션이 있어 놓는다.
 *
 * <p>인메모리 H2 + 운영과 같은 Hikari 풀·JPA 트랜잭션 매니저로, 그 범위 안에서 쓰는 연결 수를 직접 센다.
 */
@SpringBootTest(classes = StockPriceRepositoryConnectionHoldTest.JpaOnly.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:pricehold;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
class StockPriceRepositoryConnectionHoldTest {

    @Configuration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class})
    @EntityScan(basePackageClasses = StockPrice.class)
    @EnableJpaRepositories(basePackageClasses = StockPriceRepository.class)
    static class JpaOnly {}

    private static final String CODE = "005930";

    @Autowired
    StockPriceRepository repository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    DataSource dataSource;

    /** 일괄 시세 조회와 같은 범위 — 트랜잭션은 없고 동기화만 켜진 빈 범위. */
    private TransactionTemplate batchScope() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return t;
    }

    private int activeConnections() {
        try {
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void storeOnePrice() {
        StockPrice p = new StockPrice();
        p.setStockCode(CODE);
        p.setStockName("삼성전자");
        p.setCurrentPrice(new BigDecimal("71000"));
        repository.saveAndFlush(p);
    }

    @Test
    @DisplayName("재현: 종목별 직전 저장가 조회 뒤에도 연결을 쥐고 있으면 안 된다 — 범위가 끝날 때까지(KIS 호출 내내) 1개를 잡고 있었다")
    void latestPriceLookupReleasesTheConnectionInsideTheBatchScope() {
        storeOnePrice();

        Integer held = batchScope().execute(status -> {
            assertThat(repository.findTopByStockCodeOrderByFetchedAtDesc(CODE)).isPresent();
            return activeConnections();   // 이 뒤로 KIS 호출이 종목 수만큼 이어진다
        });

        assertThat(held).as("조회가 끝난 뒤 범위 안에서 쓰는 중인 연결 수").isZero();
    }

    @Test
    @DisplayName("대조: 묶음 조회는 이미 자기 짧은 트랜잭션으로 놓는다")
    void batchLookupAlreadyReleases() {
        storeOnePrice();

        Integer held = batchScope().execute(status -> {
            assertThat(repository.findLatestByStockCodes(List.of(CODE))).isNotEmpty();
            return activeConnections();
        });

        assertThat(held).isZero();
    }
}
