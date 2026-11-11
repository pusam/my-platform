package com.myplatform.backend.crosscheck;

import com.myplatform.backend.config.WebClientConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 대조가 쓰는 공용 RestTemplate 빈 자체에 요청을 꾸미는 장치가 없다 — 단위 테스트의 헤더 검사는 새로 만든 RestTemplate 을
 * 보므로, 실제 빈(WebClientConfig.restTemplate)에 인터셉터·요청 초기화기가 붙으면 그 검사로는 안 보인다(독립 검토 2026-11-11).
 * 나가는 것은 RestTemplate 의 기본 Accept 와 JDK 의 기본 User-Agent(Java/…)뿐이다 — 브라우저를 흉내 내지 않는다.
 */
class CrosscheckRestTemplateBeanTest {

    @Test
    @DisplayName("공용 RestTemplate 빈엔 인터셉터·요청 초기화기가 없다")
    void sharedBeanAddsNothing() {
        RestTemplate bean = new WebClientConfig().restTemplate();
        assertThat(bean.getInterceptors()).isEmpty();
        assertThat(bean.getClientHttpRequestInitializers()).isEmpty();
    }
}
