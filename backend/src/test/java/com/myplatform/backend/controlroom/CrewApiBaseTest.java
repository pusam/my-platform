package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 크루가 모델을 <b>어디로</b> 부르는지 고정한다.
 *
 * <p>기본은 Anthropic(종량제)이고, 구독으로 돌릴 때만 사내 중계
 * ({@code http://claude-gateway:8792})를 가리킨다. 설정을 안 하면 지금까지와 똑같이 돌아야 한다.
 *
 * <p><b>빈 값 함정</b>: compose 의 이중배선은 값이 없어도 이름을 주입한다. 그러면 프로퍼티가
 * "존재"하므로 Spring 기본값이 적용되지 않고, 그대로 두면 baseUrl 이 빈 문자열이 되어 크루가
 * 통째로 죽는다. 증상은 "설정을 안 했는데 크루가 안 된다" 하나뿐이라 원인을 찾기 어렵다.
 *
 * <p>Spring 컨텍스트가 필요 없는 순수 단위 테스트다.
 */
class CrewApiBaseTest {

    private static CrewProperties withApiBase(String value) {
        CrewProperties p = new CrewProperties();
        ReflectionTestUtils.setField(p, "apiBase", value);
        return p;
    }

    @Test
    @DisplayName("설정이 없으면 Anthropic — 지금까지의 동작이 기본값이다")
    void defaultsToAnthropic() {
        assertThat(withApiBase(null).getApiBase()).isEqualTo("https://api.anthropic.com");
    }

    @Test
    @DisplayName("이름만 있고 값이 빈 것은 '설정됨'이 아니다 — 빈 baseUrl 로 크루를 죽이지 않는다")
    void blankFallsBackToDefault() {
        assertThat(withApiBase("").getApiBase()).isEqualTo("https://api.anthropic.com");
        assertThat(withApiBase("   ").getApiBase()).isEqualTo("https://api.anthropic.com");
    }

    @Test
    @DisplayName("게이트웨이를 지정하면 그쪽으로 나간다 — 구독 경로")
    void gatewayWins() {
        assertThat(withApiBase("http://claude-gateway:8792").getApiBase())
                .isEqualTo("http://claude-gateway:8792");
    }

    @Test
    @DisplayName("앞뒤 공백은 털어낸다 — .env 에서 흔히 딸려온다")
    void trimsWhitespace() {
        assertThat(withApiBase("  http://claude-gateway:8792  ").getApiBase())
                .isEqualTo("http://claude-gateway:8792");
    }
}
