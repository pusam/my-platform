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
 * <p>0 은 세 가지가 겹쳐 보인다 — ① 진짜 0건 ② {@code JudgmentBoardService} 가 조회 실패를 삼키고
 * 빈 목록 반환 ③ 입력 노후로 미채점. ②는 그 서비스를 고쳐야 구분되므로 범위 밖이고, 여기서는
 * <b>구분되지 않는다는 사실 자체를 문구로 남긴다.</b>
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
    @DisplayName("스냅샷이 최신인데 0건이면 '조회 실패와 구분되지 않는다'를 명시한다")
    void freshSnapshotStillAmbiguousAgainstFailure() {
        String reason = ControlRoomSnapshotService.emptyReason(true, FRESH, false);

        assertThat(reason)
                .contains("후보 0건")
                .contains("조회 실패")
                .contains("구분되지 않는다");
    }

    @Test
    @DisplayName("어떤 경우에도 0건을 '이상 없음'으로 읽히게 두지 않는다")
    void neverSilentAboutZero() {
        assertThat(ControlRoomSnapshotService.emptyReason(true, STALE, true)).isNotBlank();
        assertThat(ControlRoomSnapshotService.emptyReason(true, FRESH, false)).isNotBlank();
        assertThat(ControlRoomSnapshotService.emptyReason(true, null, null)).isNotBlank();
    }
}
