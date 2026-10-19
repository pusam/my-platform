package com.myplatform.backend.service;

import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 교정 D+3 평가 배치(19:45)도 dead-man switch 감시를 받는다(2026-10-19, 리뷰 에이전트 지적).
 *
 * <p>재현: 심박은 레거시 '시그널 평가(19:30)'만 남겼는데, 그 잡은 평가 0건이어도 심박을 남긴다. 게이트와 모든 화면 성적이 읽는
 * 교정 평가(V59 d3_*)는 심박도 이상 점검 규칙도 없어 — 19:45 배치가 죽으면 게이트는 평범한 '표본 수집 중', 화면은 옛 실측으로
 * 남는데 심박은 '정상'이라고 말했다.
 */
class SignalD3HeartbeatTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-17T10:45:00Z"), ZoneId.of("Asia/Seoul"));

    private SignalOutcomeRepository repo;
    private SignalD3EvaluationService svc;
    private BatchHeartbeatService heartbeat;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(SignalOutcomeRepository.class);
        when(repo.findD3Pending(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        svc = new SignalD3EvaluationService(repo, mock(StockPriceHistoryRepository.class), new MarketCalendarService(),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class), CLOCK);
        heartbeat = mock(BatchHeartbeatService.class);
        ReflectionTestUtils.setField(svc, "heartbeat", heartbeat);
    }

    @Test
    @DisplayName("재현: 감시 대상에 교정 D+3 평가가 있다 — 레거시 19:30 평가만 있었다")
    void d3EvaluationIsWatched() {
        assertThat(BatchHeartbeatService.JOBS).anyMatch(j -> j.key().equals("d3-evaluation"));
    }

    @Test
    @DisplayName("야간 실행이 끝나면 심박을 남긴다")
    void nightlyRecordsHeartbeat() {
        svc.nightly();

        verify(heartbeat).recordSuccess("d3-evaluation");
    }

    @Test
    @DisplayName("실행이 예외로 끝나면 심박을 남기지 않는다 — 그래야 죽은 배치가 경보로 드러난다")
    void failedRunRecordsNoHeartbeat() {
        when(repo.findD3Pending(any(), any(), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("DB 다운"));

        svc.nightly();

        verify(heartbeat, never()).recordSuccess(any());
    }
}
