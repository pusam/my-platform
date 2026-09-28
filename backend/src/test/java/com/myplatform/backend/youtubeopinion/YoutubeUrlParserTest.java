package com.myplatform.backend.youtubeopinion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 허용된 YouTube 주소에서만 영상 ID 를 뽑는다 — 서버는 주소를 가져오지 않는다(SSRF 없음). */
class YoutubeUrlParserTest {

    private static final String ID = "dQw4w9WgXcQ";

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?v=dQw4w9WgXcQ&t=30s",
            "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abc",
            "http://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ?feature=share",
            "  https://www.youtube.com/watch?v=dQw4w9WgXcQ  "
    })
    @DisplayName("허용된 주소 모양이면 영상 ID 를 뽑는다")
    void accepts(String url) {
        assertThat(YoutubeUrlParser.extractVideoId(url)).contains(ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
            "https://evil.example/?u=https://youtu.be/dQw4w9WgXcQ",
            "https://user@www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com:8443/watch?v=dQw4w9WgXcQ",
            "ftp://youtu.be/dQw4w9WgXcQ",
            "javascript:alert(1)",
            "https://www.youtube.com/watch?v=short",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ1",
            "https://www.youtube.com/channel/UCabcdefghijklmn",
            "https://www.youtube.com/watch?list=PL123",
            "not a url",
            ""
    })
    @DisplayName("닮은꼴 호스트·사용자정보·포트·다른 스킴·형식이 틀린 ID 는 거절한다")
    void rejects(String url) {
        assertThat(YoutubeUrlParser.extractVideoId(url)).isEmpty();
    }

    @Test
    @DisplayName("null·과도하게 긴 입력도 거절한다")
    void rejectsNullAndHuge() {
        assertThat(YoutubeUrlParser.extractVideoId(null)).isEmpty();
        assertThat(YoutubeUrlParser.extractVideoId("https://youtu.be/" + ID + "?x=" + "a".repeat(400))).isEmpty();
    }

    @Test
    @DisplayName("원문 링크는 발언 시작 초로 간다 — 음수는 0초, ID 형식이 틀리면 만들지 않는다")
    void timestampUrl() {
        assertThat(YoutubeUrlParser.timestampUrl(ID, 754)).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=754s");
        assertThat(YoutubeUrlParser.timestampUrl(ID, -3)).endsWith("&t=0s");
        assertThatThrownBy(() -> YoutubeUrlParser.timestampUrl("../etc", 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
