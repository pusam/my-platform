package com.myplatform.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** 서버 안 요청 판정 — 주소는 루프백·사설만, 그리고 nginx 가 붙이는 X-Real-IP 가 없어야 한다. */
class InternalCallerTest {

    @Test
    @DisplayName("루프백·사설 대역(도커 내부망 포함)만 안쪽 주소다")
    void addresses() {
        assertThat(InternalCaller.isPrivateOrLoopback("127.0.0.1")).isTrue();
        assertThat(InternalCaller.isPrivateOrLoopback("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(InternalCaller.isPrivateOrLoopback("[::1]")).isTrue();
        assertThat(InternalCaller.isPrivateOrLoopback("172.18.0.1")).isTrue();
        assertThat(InternalCaller.isPrivateOrLoopback("10.0.0.5")).isTrue();
        assertThat(InternalCaller.isPrivateOrLoopback("192.168.0.10")).isTrue();
        assertThat(InternalCaller.isPrivateOrLoopback("::ffff:172.18.0.3")).isTrue();

        assertThat(InternalCaller.isPrivateOrLoopback("203.0.113.7")).isFalse();
        assertThat(InternalCaller.isPrivateOrLoopback("172.32.0.1")).as("172.16/12 밖").isFalse();
        assertThat(InternalCaller.isPrivateOrLoopback("8.8.8.8")).isFalse();
        assertThat(InternalCaller.isPrivateOrLoopback("localhost")).as("이름은 조회하지 않는다").isFalse();
        assertThat(InternalCaller.isPrivateOrLoopback("")).isFalse();
        assertThat(InternalCaller.isPrivateOrLoopback(null)).isFalse();
    }

    @Test
    @DisplayName("nginx 를 거친 요청(X-Real-IP 있음)은 연결 주소가 사설이어도 밖이다")
    void nginxHeaderMeansExternal() {
        MockHttpServletRequest viaNginx = new MockHttpServletRequest();
        viaNginx.setRemoteAddr("172.18.0.9");
        viaNginx.addHeader("X-Real-IP", "203.0.113.7");
        assertThat(InternalCaller.isInternal(viaNginx)).isFalse();

        MockHttpServletRequest curl = new MockHttpServletRequest();
        curl.setRemoteAddr("172.18.0.1");
        assertThat(InternalCaller.isInternal(curl)).isTrue();
    }
}
