package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 크롤 배치 tx 경계 회귀 가드 — 클래스 @Transactional 재추가 방지.
 * 전종목 크롤은 sleep+Jsoup 를 수천 회 반복: 클래스 tx 면 커넥션 1개를 수십 분~시간 pin
 * + 배치 끝 일괄 커밋(도중 크래시 시 진행분 전부 유실). 각 save 는 자체 짧은 tx 가 맞다.
 */
class FinancialDataCrawlerServiceTest {

    @Test
    @DisplayName("클래스/크롤 메서드에 @Transactional 없음 — 장시간 크롤이 DB 커넥션 pin 금지")
    void crawler_mustNotBeTransactional() throws Exception {
        assertThat(FinancialDataCrawlerService.class.isAnnotationPresent(Transactional.class))
                .as("클래스 @Transactional 재추가 금지 — 전종목 크롤 tx 롱홀드")
                .isFalse();
        assertThat(FinancialDataCrawlerService.class.getMethod("crawlAllOperatingMargin", boolean.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(FinancialDataCrawlerService.class.getMethod("collectQuarterlyFinancialStatements")
                .isAnnotationPresent(Transactional.class)).isFalse();
    }
}
