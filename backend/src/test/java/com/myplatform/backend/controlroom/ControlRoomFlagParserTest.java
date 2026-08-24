package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FLAGGED 파서 — {@link ControlRoomFlagParser}.
 *
 * <p>가장 중요한 구분은 "플래그 0건"과 "플래그 데이터 없음"이다(§4c). 파일을 못 읽었는데 0건으로
 * 보여주면 관제실이 "이상 없음"을 주장하게 되고, 크루(FIREWALL)도 그걸 근거로 승인해 버린다.
 */
class ControlRoomFlagParserTest {

    private static String yaml(String body) {
        return "```yaml\nflags:\n" + body + "\n```\n";
    }

    @Test
    @DisplayName("정상 플래그를 읽고 critical 을 센다")
    void parsesFlags() {
        ControlRoomFlagParser.Result r = ControlRoomFlagParser.parse(yaml("""
                  - id: one
                    severity: critical
                    title: 심각한 것
                    key: R1
                    body: 본문
                    recorded_on: 2026-08-21
                  - id: two
                    severity: info
                    title: 참고
                    body: 본문
                    recorded_on: 2026-08-05
                """));

        assertThat(r.dataAvailable()).isTrue();
        assertThat(r.parseErrors()).isEmpty();
        assertThat(r.flags()).hasSize(2);
        assertThat(r.criticalCount()).isEqualTo(1);
        assertThat(r.flags().get(0).recordedOn()).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(r.flags().get(1).key()).isNull();   // key 생략 = 배지 없음
    }

    @Test
    @DisplayName("severity 오타는 조용히 넘기지 않고 파싱 오류로 보고한다")
    void reportsInvalidSeverity() {
        ControlRoomFlagParser.Result r = ControlRoomFlagParser.parse(yaml("""
                  - id: bad
                    severity: urgent
                    title: 오타
                    body: 본문
                    recorded_on: 2026-08-21
                """));

        assertThat(r.flags()).isEmpty();
        assertThat(r.dataAvailable()).isTrue();        // 블록은 있었다 — "데이터 없음"이 아니다
        assertThat(r.parseErrors()).singleElement().asString().contains("파싱 오류: bad");
    }

    @Test
    @DisplayName("필수 필드 누락도 파싱 오류로 보고한다")
    void reportsMissingRequiredField() {
        ControlRoomFlagParser.Result r = ControlRoomFlagParser.parse(yaml("""
                  - id: no-body
                    severity: warning
                    title: 본문 없음
                    recorded_on: 2026-08-21
                """));

        assertThat(r.parseErrors()).singleElement().asString()
                .contains("파싱 오류: no-body").contains("body");
    }

    @Test
    @DisplayName("파일이 없거나 블록이 없으면 '0건'이 아니라 데이터 없음이다")
    void missingBlockIsNotZeroFlags() {
        assertThat(ControlRoomFlagParser.parse(null).dataAvailable()).isFalse();
        assertThat(ControlRoomFlagParser.parse("").dataAvailable()).isFalse();
        assertThat(ControlRoomFlagParser.parse("# 제목만\n본문\n").dataAvailable()).isFalse();
    }

    @Test
    @DisplayName("실제 docs/CONTROL_ROOM_FLAGS.md 가 오류 없이 파싱된다")
    void realDocumentParses() throws IOException {
        Path path = Path.of("..", "docs", "CONTROL_ROOM_FLAGS.md");
        assumeTrue(Files.exists(path), "레포 루트 기준 실행이 아닐 때는 건너뛴다");

        ControlRoomFlagParser.Result r =
                ControlRoomFlagParser.parse(Files.readString(path, StandardCharsets.UTF_8));

        assertThat(r.dataAvailable()).isTrue();
        assertThat(r.parseErrors()).isEmpty();
        assertThat(r.flags()).isNotEmpty();
        assertThat(r.flags()).allSatisfy(f -> {
            assertThat(f.id()).isNotBlank();
            assertThat(f.body()).isNotBlank();
            assertThat(f.recordedOn()).isNotNull();
        });
    }
}
