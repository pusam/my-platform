package com.myplatform.backend.service;

import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장중·마감 스냅샷 크론은 계산 전체를 한 트랜잭션으로 묶지 않는다(2026-11-04).
 *
 * <p>재현: 11/4 운영 종합추천 '가치' 단계가 11:30:00·14:00:00 만 33초(그 외 회차 2초)였다. 그 두 회차만 크론 메서드의 {@code @Transactional}
 * 안에서 돌아 636종목 × 재무 10행이 한 세션에 쌓이고, 다음 조회마다 Hibernate 가 쌓인 행 전부를 변경 검사했다(쿼리 수 × 행 수).
 * 그동안 DB 연결도 계산 끝까지(DART·KIS 호출 포함) 잡혀 있었다. 저장(saveAll)·시그널 기록은 각자 트랜잭션이 있고,
 * 트랜잭션 없는 계산 경로는 이미 매 30분 도는 그 경로(백그라운드 계산)다.
 */
class RecommendationSnapshotTransactionTest {

    private static boolean transactional(String method) throws Exception {
        return RecommendationService.class.getMethod(method).isAnnotationPresent(Transactional.class);
    }

    @Test
    @DisplayName("재현: 장중 스냅샷 크론에 트랜잭션이 없다")
    void intradaySnapshotIsNotOneTransaction() throws Exception {
        assertThat(transactional("saveIntradaySnapshot")).isFalse();
    }

    @Test
    @DisplayName("재현: 마감 스냅샷 크론에 트랜잭션이 없다")
    void closingSnapshotIsNotOneTransaction() throws Exception {
        assertThat(transactional("saveClosingSnapshot")).isFalse();
    }

    @Test
    @DisplayName("마감 크론의 오래된 스냅샷 삭제는 자기 트랜잭션이 있다 — 없으면 @Modifying 쿼리가 실패한다")
    void cleanupHasItsOwnTransaction() throws Exception {
        assertThat(RecommendationSnapshotRepository.class.getMethod("deleteOlderThan", LocalDateTime.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
