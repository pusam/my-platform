package com.myplatform.backend.youtubeopinion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 설정 — 채널은 사용자가 고른 것만, 형식 오류는 건너뛰고 경고, 모델명·보호 시간대 해석. */
class YoutubeOpinionSettingsTest {

    static YoutubeOpinionSettings settings(boolean enabled, String channels, String quiet) {
        return new YoutubeOpinionSettings(enabled, channels, 7, 20, 60, quiet,
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent");
    }

    @Test
    @DisplayName("채널 목록은 '채널ID=이름' 만 — 형식 오류·중복은 건너뛴다. 비어 있으면 등록 가능한 채널이 없다")
    void channels() {
        YoutubeOpinionSettings s = settings(true, "UC_test_ch1=테스트채널A, 잘못된 항목, UCtest2ch=테스트채널B, UC_test_ch1=중복", null);
        assertThat(s.getChannels()).extracting(YoutubeOpinionSettings.Channel::id).containsExactly("UC_test_ch1", "UCtest2ch");
        assertThat(s.channel("UC_test_ch1")).map(YoutubeOpinionSettings.Channel::name).contains("테스트채널A");
        assertThat(s.channel("UCnotConfigured")).isEmpty();
        assertThat(settings(true, "", null).getChannels()).isEmpty();
        assertThat(settings(true, "x=짧은ID", null).getChannels()).as("ID 3자 미만").isEmpty();
    }

    @Test
    @DisplayName("기본은 꺼짐")
    void disabledByDefaultFlag() {
        assertThat(settings(false, "UC_test_ch1=A", null).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("모델명은 gemini.api.url 의 models/ 세그먼트")
    void model() {
        assertThat(settings(true, "", null).getModel()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(YoutubeOpinionSettings.modelName(null)).isEqualTo("unknown");
        assertThat(YoutubeOpinionSettings.modelName("https://x/v1/models/abc?key=1")).isEqualTo("abc");
    }

    @Test
    @DisplayName("재료 워밍 보호 시간대 — 경계 포함/제외, 자정 넘김, 형식 오류는 보호 없음")
    void quietWindow() {
        YoutubeOpinionSettings s = settings(true, "", "07:20-08:40");
        assertThat(s.inQuietWindow(LocalTime.of(7, 30))).isTrue();
        assertThat(s.inQuietWindow(LocalTime.of(7, 20))).isTrue();
        assertThat(s.inQuietWindow(LocalTime.of(8, 40))).isFalse();
        assertThat(s.inQuietWindow(LocalTime.of(7, 19))).isFalse();
        assertThat(settings(true, "", "23:00-01:00").inQuietWindow(LocalTime.of(0, 30))).isTrue();
        assertThat(settings(true, "", "잘못").inQuietWindow(LocalTime.of(7, 30))).isFalse();
        assertThat(settings(true, "", "").quietWindowLabel()).isNull();
    }
}
