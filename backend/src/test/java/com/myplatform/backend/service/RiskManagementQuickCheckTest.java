package com.myplatform.backend.service;

import com.myplatform.backend.dto.RiskAnalysisDto.DartDisclosure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * {@link RiskManagementService#quickDangerCheck(String, String)} — 실패/안전 구분.
 *
 * <p>버그 재현: DART 장애·corpCode 미해결 시 빈 목록이 반환돼 "안전(false)"으로 1시간 캐시,
 * 장애 동안 위험 종목이 무페널티 고정되던 문제. 수정 후: 조회 실패(null)는 fail-open 하되
 * <b>캐시하지 않아</b> 다음 호출에서 재시도한다. 성공 결과만 캐시.
 */
class RiskManagementQuickCheckTest {

    private DartService dartService;
    private RiskManagementService service;

    @BeforeEach
    void setUp() {
        dartService = mock(DartService.class);
        service = new RiskManagementService(
                dartService,
                mock(NaverSearchService.class),
                mock(GoogleNewsService.class),
                Runnable::run);
    }

    @Test
    @DisplayName("조회 실패(null) → false 반환하되 캐시 안 함 (다음 호출 재시도)")
    void failureIsNotCached() {
        when(dartService.searchDisclosuresOrNull("005930", "삼성전자")).thenReturn(null);

        assertThat(service.quickDangerCheck("005930", "삼성전자")).isFalse();
        assertThat(service.quickDangerCheck("005930", "삼성전자")).isFalse();

        // 캐시됐다면 2번째 호출은 DART 를 다시 안 두드림 — 실패는 캐시 금지라 2회 모두 조회해야 함
        verify(dartService, times(2)).searchDisclosuresOrNull("005930", "삼성전자");
    }

    @Test
    @DisplayName("조회 성공 + 위험 공시 → true, 결과는 캐시됨")
    void dangerousResultIsCached() {
        DartDisclosure danger = DartDisclosure.builder()
                .reportNm("관리종목지정").isDangerous(true).build();
        when(dartService.searchDisclosuresOrNull("123456", "위험종목")).thenReturn(List.of(danger));
        when(dartService.hasDangerousDisclosure(anyList())).thenReturn(true);

        assertThat(service.quickDangerCheck("123456", "위험종목")).isTrue();
        assertThat(service.quickDangerCheck("123456", "위험종목")).isTrue();

        // 성공 결과는 1시간 캐시 — 2번째 호출은 DART 미조회
        verify(dartService, times(1)).searchDisclosuresOrNull("123456", "위험종목");
    }

    @Test
    @DisplayName("조회 성공 + 공시 없음(빈 목록) → false, 정상 캐시")
    void safeResultIsCached() {
        when(dartService.searchDisclosuresOrNull("654321", "안전종목")).thenReturn(Collections.emptyList());
        when(dartService.hasDangerousDisclosure(anyList())).thenReturn(false);

        assertThat(service.quickDangerCheck("654321", "안전종목")).isFalse();
        assertThat(service.quickDangerCheck("654321", "안전종목")).isFalse();

        verify(dartService, times(1)).searchDisclosuresOrNull("654321", "안전종목");
    }

    @Test
    @DisplayName("조회 중 예외 → false, 캐시 안 함")
    void exceptionIsNotCached() {
        when(dartService.searchDisclosuresOrNull(any(), any())).thenThrow(new RuntimeException("boom"));

        assertThat(service.quickDangerCheck("111111", "예외종목")).isFalse();
        assertThat(service.quickDangerCheck("111111", "예외종목")).isFalse();

        verify(dartService, times(2)).searchDisclosuresOrNull("111111", "예외종목");
    }
}
