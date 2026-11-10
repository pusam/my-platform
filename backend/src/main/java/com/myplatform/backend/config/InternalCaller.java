package com.myplatform.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.net.InetAddress;
import java.util.regex.Pattern;

/**
 * 서버 안에서 온 요청인가 — 운영자 {@code curl localhost:8080/...} 점검과 python 컨테이너의 내부 호출 (2026-11-10).
 *
 * <p>두 조건을 모두 봐야 한다.
 * <ol>
 *   <li><b>연결 주소가 루프백 또는 사설 대역</b>(127/8·::1·10/8·172.16/12·192.168/16). 백엔드 8080 은 호스트의
 *       127.0.0.1 에만 열려 있고, 호스트에서 들어온 연결은 도커 게이트웨이(사설) 주소로, python 은 도커 내부망
 *       주소로 보인다.</li>
 *   <li><b>{@code X-Real-IP} 헤더가 없다</b>. nginx 는 백엔드로 넘기는 모든 location 에서 이 헤더를 붙인다
 *       ({@code nginx/default.conf}). 밖의 요청은 nginx 컨테이너(사설 주소)를 거쳐 들어오므로 주소만 보면 내부로
 *       보인다 — 운영은 {@code forward-headers-strategy: native} 라 Tomcat 이 주소를 클라이언트 것으로 바꾸지만,
 *       그 설정 하나에 기대지 않으려고 헤더를 따로 본다(바깥 사람이 이 헤더를 지울 방법은 없다 — nginx 가 덮어쓴다).</li>
 * </ol>
 * ⚠ nginx 에 백엔드로 넘기는 location 을 새로 만들면 {@code X-Real-IP} 를 반드시 붙일 것 — 빠지면 밖의 요청이
 * 내부로 보인다. 같은 호스트의 다른 컨테이너도 내부로 본다(신뢰 경계 = 이 서버).
 */
public final class InternalCaller {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private InternalCaller() {}

    public static boolean isInternal(HttpServletRequest request) {
        if (request.getHeader("X-Real-IP") != null) return false;
        return isPrivateOrLoopback(request.getRemoteAddr());
    }

    /** IP 리터럴만 판정한다(이름이면 조회하지 않고 거짓). */
    static boolean isPrivateOrLoopback(String addr) {
        if (addr == null || addr.isBlank()) return false;
        String a = addr.trim();
        if (a.startsWith("[") && a.endsWith("]")) a = a.substring(1, a.length() - 1);
        boolean literal = IPV4.matcher(a).matches() || a.indexOf(':') >= 0;
        if (!literal) return false;
        try {
            InetAddress ip = InetAddress.getByName(a);
            return ip.isLoopbackAddress() || ip.isSiteLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /** 권한 규칙에 쓰는 형태 — 서버 안이면 통과, 아니면 이 규칙만으로는 거절(다른 규칙과 anyOf 로 묶는다). */
    public static AuthorizationManager<RequestAuthorizationContext> authorizationManager() {
        return (authentication, context) -> new AuthorizationDecision(isInternal(context.getRequest()));
    }
}
