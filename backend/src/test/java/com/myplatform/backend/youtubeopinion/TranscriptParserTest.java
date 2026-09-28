package com.myplatform.backend.youtubeopinion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 관리자 자막 파싱 — 형식 3종, 태그 제거(평문), 크기·순서·길이 제한, 출연자 명단 안에서만 화자 확정. */
class TranscriptParserTest {

    @Nested
    @DisplayName("형식")
    class Formats {

        @Test
        @DisplayName("SRT — 번호·시각 줄을 읽고 여러 줄 본문을 잇는다(자동 판별)")
        void srt() {
            String srt = """
                    1
                    00:01:02,500 --> 00:01:05,000
                    삼성전자는
                    지금 좋습니다

                    2
                    00:01:05,000 --> 00:01:09,900
                    다음 얘기입니다
                    """;
            TranscriptParser.Parsed p = TranscriptParser.parse(srt, null);
            assertThat(p.format()).isEqualTo(YtTranscript.Format.SRT);
            assertThat(p.cues()).hasSize(2);
            assertThat(p.cues().get(0)).isEqualTo(new TranscriptParser.Cue(62, 65, null, "삼성전자는 지금 좋습니다"));
            assertThat(p.durationSec()).isEqualTo(69);
        }

        @Test
        @DisplayName("WebVTT — 머리·NOTE 를 건너뛰고 <v 이름> 화자를 읽고 모든 태그를 걷어낸다")
        void vtt() {
            String vtt = """
                    WEBVTT

                    NOTE 메모 블록

                    cue-1
                    00:10.000 --> 00:12.000 align:start
                    <v 홍길동>삼성전자 <b>좋습니다</b></v>

                    00:12.000 --> 00:15.000
                    <c.yellow>그렇죠</c>
                    """;
            TranscriptParser.Parsed p = TranscriptParser.parse(vtt, null);
            assertThat(p.format()).isEqualTo(YtTranscript.Format.VTT);
            assertThat(p.cues()).containsExactly(
                    new TranscriptParser.Cue(10, 12, "홍길동", "삼성전자 좋습니다"),
                    new TranscriptParser.Cue(12, 15, null, "그렇죠"));
        }

        @Test
        @DisplayName("타임스탬프 텍스트 — [mm:ss]·hh:mm:ss 모두, 이어지는 줄은 앞 줄에 붙이고 끝은 다음 줄 시각")
        void text() {
            String text = """
                    [00:05] 첫 줄
                    이어지는 말
                    00:00:09 둘째 줄
                    """;
            TranscriptParser.Parsed p = TranscriptParser.parse(text, null);
            assertThat(p.format()).isEqualTo(YtTranscript.Format.TEXT);
            assertThat(p.cues()).containsExactly(
                    new TranscriptParser.Cue(5, 9, null, "첫 줄 이어지는 말"),
                    new TranscriptParser.Cue(9, 9 + TranscriptParser.TEXT_LAST_CUE_SEC, null, "둘째 줄"));
        }

        @Test
        @DisplayName("태그·스크립트는 평문으로 — 결과에 꺾쇠 태그가 남지 않는다(화면에 HTML 로 나가지 않는다)")
        void stripsMarkup() {
            TranscriptParser.Parsed p = TranscriptParser.parse("[00:01] <script>alert(1)</script>매수 &amp; 보유 <img src=x onerror=y>", null);
            assertThat(p.cues().get(0).text()).isEqualTo("alert(1) 매수 & 보유");
            assertThat(p.cues().get(0).text()).doesNotContain("<", ">");
        }
    }

    @Nested
    @DisplayName("제한 — 어긋나면 이유와 함께 거절(조용히 잘라 쓰지 않는다)")
    class Limits {

        @Test
        void tooLarge() {
            String big = "[00:01] " + "가".repeat(TranscriptParser.MAX_BYTES / 3 + 10);
            assertThatThrownBy(() -> TranscriptParser.parse(big, null)).hasMessageContaining("너무 큽니다");
        }

        @Test
        void outOfOrder() {
            assertThatThrownBy(() -> TranscriptParser.parse("[00:10] 둘\n[00:05] 하나", null)).hasMessageContaining("시간 순서");
        }

        @Test
        void longCue() {
            assertThatThrownBy(() -> TranscriptParser.parse("[00:01] " + "가".repeat(TranscriptParser.MAX_CUE_CHARS + 1), null))
                    .hasMessageContaining("너무 깁니다");
        }

        @Test
        void emptyAndNoTimestamp() {
            assertThatThrownBy(() -> TranscriptParser.parse("   ", null)).hasMessageContaining("비어");
            assertThatThrownBy(() -> TranscriptParser.parse("그냥 글", YtTranscript.Format.TEXT)).hasMessageContaining("첫 줄에 시각");
            assertThatThrownBy(() -> TranscriptParser.parse("1\n본문만\n\n2\n또", YtTranscript.Format.SRT)).hasMessageContaining("시간 표기");
        }

        @Test
        void badClock() {
            assertThatThrownBy(() -> TranscriptParser.parse("[00:75] 이상", null)).hasMessageContaining("잘못된 시각");
        }
    }

    @Nested
    @DisplayName("화자 — 출연자 명단 안에서만 확정(모르면 추측하지 않는다)")
    class Speakers {

        private final java.util.Map<String, String> participants =
                TranscriptParser.participantKeyMap(List.of("홍길동", "김철수"));

        @Test
        @DisplayName("'이름: 발언' 은 명단에 있을 때만 화자로 떼어 낸다")
        void prefixOnlyForParticipants() {
            List<TranscriptParser.Cue> cues = TranscriptParser.applyParticipants(List.of(
                    new TranscriptParser.Cue(0, 5, null, "홍길동: 삼성전자 좋습니다"),
                    new TranscriptParser.Cue(5, 9, null, "주의: 투자 판단은 본인 몫"),
                    new TranscriptParser.Cue(9, 12, null, "김 철수 : 저는 반대입니다")), participants);
            assertThat(cues.get(0)).isEqualTo(new TranscriptParser.Cue(0, 5, "홍길동", "삼성전자 좋습니다"));
            assertThat(cues.get(1)).isEqualTo(new TranscriptParser.Cue(5, 9, null, "주의: 투자 판단은 본인 몫"));
            assertThat(cues.get(2).speaker()).isEqualTo("김철수");
        }

        @Test
        @DisplayName("VTT 화자 태그도 명단에 없으면 버린다")
        void voiceTagOutsideRoster() {
            List<TranscriptParser.Cue> cues = TranscriptParser.applyParticipants(List.of(
                    new TranscriptParser.Cue(0, 5, "이몽룡", "좋아요")), participants);
            assertThat(cues.get(0).speaker()).isNull();
        }
    }

    @Test
    @DisplayName("저장 JSON 은 왕복해도 같다")
    void jsonRoundTrip() {
        List<TranscriptParser.Cue> cues = List.of(new TranscriptParser.Cue(1, 2, "홍길동", "a\"b"), new TranscriptParser.Cue(2, 3, null, "c"));
        assertThat(TranscriptParser.fromJson(TranscriptParser.toJson(cues))).isEqualTo(cues);
    }
}
