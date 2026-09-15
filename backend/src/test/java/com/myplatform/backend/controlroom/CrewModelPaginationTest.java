package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 모델 목록 페이지네이션은 <b>유한하게</b> 끝나야 한다 — {@code CrewModelAvailability.scanBounded}.
 *
 * <p>고치려는 결함(2026-09-15 실사고): 이전 코드는 {@code autoPager().forEach(m -> available.add(m.id()))}
 * 로 페이지를 무제한 따라갔다. 2026-09-11 구독 게이트웨이({@code api-base=http://claude-gateway:8792})로
 * 전환하자 그 게이트웨이의 {@code /v1/models} 가 커서를 진전시키지 않아 <b>같은 페이지를 영원히 반복</b>했고,
 * 모델 ID 문자열이 힙에 쌓여 OOM 이 났다 — 힙 덤프 실측 {@code "claude-opus-5"}/{@code "claude-sonnet-5"}
 * 문자열 <b>3,818만 개(힙의 83%, 2.2GB)</b>, 원소 4,672만 개짜리 배열.
 *
 * <p>그 OOM 이 Tomcat Acceptor 스레드만 죽이고 JVM 은 살아남아 <b>API 가 3.5일간 조용히 죽어 있었다</b>.
 * 이 테스트는 고치기 전이라면 <b>실패가 아니라 영원히 멈춘다</b> — 그래서 타임아웃으로 감싼다.
 */
class CrewModelPaginationTest {

    /** 커서가 안 도는 게이트웨이 — 같은 모델 ID 를 영원히 돌려준다(실사고 재현). */
    private static Iterator<String> brokenPager(String... cycle) {
        return new Iterator<>() {
            private int i = 0;
            @Override public boolean hasNext() { return true; }
            @Override public String next() { return cycle[i++ % cycle.length]; }
        };
    }

    @Test
    @DisplayName("무한 페이지네이션도 상한에서 끝난다 — 고치기 전엔 여기서 영원히 멈췄다")
    void infinitePaginationTerminates() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var scan = CrewModelAvailability.scanBounded(
                    brokenPager("claude-opus-5", "claude-sonnet-5"), 500);

            assertThat(scan.truncated()).as("상한에 걸린 건 게이트웨이 이상 신호라 호출부가 WARN 을 남긴다").isTrue();
            assertThat(scan.ids()).containsExactly("claude-opus-5", "claude-sonnet-5");
        });
    }

    @Test
    @DisplayName("상한은 '수집된 개수'가 아니라 '반복 횟수'로 건다 — 중복만 오면 개수가 안 늘어난다")
    void capCountsIterationsNotDistinct() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var scan = CrewModelAvailability.scanBounded(brokenPager("claude-opus-5"), 10);

            assertThat(scan.ids()).hasSize(1);        // distinct 는 1 뿐이지만
            assertThat(scan.truncated()).isTrue();    // 반복 10회에서 끊겼다
        });
    }

    @Test
    @DisplayName("정상 목록은 그대로 다 담고 truncated=false")
    void normalListIsNotTruncated() {
        var scan = CrewModelAvailability.scanBounded(
                List.of("claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5-20251001").iterator(), 500);

        assertThat(scan.truncated()).isFalse();
        assertThat(scan.ids()).containsExactly(
                "claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5-20251001");
    }

    @Test
    @DisplayName("빈 목록·공백 ID 는 걸러지되 예외는 없다 — 호출부가 '비어있음'으로 크루를 끈다")
    void blanksAreDropped() {
        var scan = CrewModelAvailability.scanBounded(
                java.util.Arrays.asList("", "  ", null, "claude-opus-5").iterator(), 500);

        assertThat(scan.ids()).containsExactly("claude-opus-5");
        assertThat(scan.truncated()).isFalse();

        assertThat(CrewModelAvailability.scanBounded(List.<String>of().iterator(), 500).ids()).isEmpty();
    }

    @Test
    @DisplayName("상한 경계: 정확히 상한만큼이면 끊지 않는다")
    void exactlyAtCapIsNotTruncated() {
        Iterator<String> three = List.of("a", "b", "c").iterator();

        var scan = CrewModelAvailability.scanBounded(three, 3);

        assertThat(scan.truncated()).isFalse();
        assertThat(scan.ids()).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("실제 상한값이 모델 종 수보다 충분히 크다 — 정상 목록을 자르면 안 된다")
    void productionCapIsGenerous() {
        assertThat(CrewModelAvailability.MAX_MODEL_ITEMS).isGreaterThanOrEqualTo(100);
    }

    /** next() 가 터지는 페이저라도 스캔이 예외를 삼키지 않는다(호출부 catch 가 크루만 끈다). */
    @Test
    @DisplayName("페이저가 예외를 던지면 그대로 전파 — 조용히 '모델 없음'으로 위장하지 않는다(§4c)")
    void pagerFailurePropagates() {
        Iterator<String> boom = new Iterator<>() {
            @Override public boolean hasNext() { return true; }
            @Override public String next() { throw new NoSuchElementException("gateway down"); }
        };

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> CrewModelAvailability.scanBounded(boom, 500))
                .isInstanceOf(NoSuchElementException.class);
    }
}
