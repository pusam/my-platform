package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 후보 0 건일 때의 사유 문구 — {@link ControlRoomSnapshotService#emptyReason}.
 *
 * <p><b>왜 이게 필요했나</b>: 2026-08-24 배포 직후 관제실에 "종합판단 후보 0종목"만 떴다. 서버가 4일
 * 다운돼 수급·가격이 8/20 에 멈춰 있었고, 노후 가드가 §4c 대로 채점을 거부해 후보가 0 이 된 것이었다.
 * 시스템은 정상이었지만 <b>화면이 이유를 말하지 않아</b> 운영자가 진단 API 를 직접 쳐야 원인을 알 수 있었다.
 *
 * <p>0 은 두 가지가 겹쳐 보인다 — ① 진짜 0건(컷 통과 0건) ② 입력 노후로 미채점. 조회 실패는
 * 2026-09-16 부터 {@code JudgmentBoardService} 가 예외로 올려 {@code dataAvailable=false} 로 갈라지므로
 * 이 문구가 다루지 않는다. ①②는 <b>스냅샷 신선도</b>로 가른다.
 */
class CandidatesEmptyReasonTest {

    private static final LocalDateTime STALE = LocalDateTime.of(2026, 8, 20, 11, 30);
    private static final LocalDateTime FRESH = LocalDateTime.of(2026, 8, 24, 11, 30);

    @Test
    @DisplayName("후보가 있으면 사유 문구를 붙이지 않는다")
    void noReasonWhenNotEmpty() {
        assertThat(ControlRoomSnapshotService.emptyReason(false, FRESH, false)).isNull();
    }

    @Test
    @DisplayName("스냅샷이 노후면 '노후 가드가 채점을 거부' 를 사유로 적고 날짜를 명시한다")
    void staleSnapshotExplainsGuard() {
        String reason = ControlRoomSnapshotService.emptyReason(true, STALE, true);

        assertThat(reason)
                .contains("후보 0건")
                .contains("2026-08-20")
                .contains("노후")
                .contains("채점을 거부");
    }

    @Test
    @DisplayName("스냅샷이 아예 없으면 '후보 없음'과 '아직 계산 안 됨'을 구분 못 한다고 적는다")
    void missingSnapshotIsAmbiguous() {
        String reason = ControlRoomSnapshotService.emptyReason(true, null, null);

        assertThat(reason).contains("스냅샷이 아예 없다").contains("구분할 수 없다");
    }

    @Test
    @DisplayName("스냅샷이 최신인데 0건이면 '컷 통과 0건'이다 — 조회 실패는 보드가 예외로 올린다(2026-09-16)")
    void freshSnapshotEmptyMeansNoCandidatePassedTheCut() {
        String reason = ControlRoomSnapshotService.emptyReason(true, FRESH, false);

        assertThat(reason)
                .contains("후보 0건")
                .contains("컷 통과 0건")
                .doesNotContain("구분되지 않는다");
    }

    @Test
    @DisplayName("어떤 경우에도 0건을 '이상 없음'으로 읽히게 두지 않는다")
    void neverSilentAboutZero() {
        assertThat(ControlRoomSnapshotService.emptyReason(true, STALE, true)).isNotBlank();
        assertThat(ControlRoomSnapshotService.emptyReason(true, FRESH, false)).isNotBlank();
        assertThat(ControlRoomSnapshotService.emptyReason(true, null, null)).isNotBlank();
    }
}
