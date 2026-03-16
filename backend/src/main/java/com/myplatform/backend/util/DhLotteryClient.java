package com.myplatform.backend.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 동행복권 API 클라이언트
 * - 세션 쿠키 선취득 후 API 호출 (302 리다이렉트 방지)
 * - LottoAnalyzerService, PensionLotteryAnalyzerService 공용
 */
@Component
@Slf4j
public class DhLotteryClient {

    private static final String DH_HOME_URL = "https://www.dhlottery.co.kr/common.do?method=main";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // 세션 쿠키 캐시 (스레드 안전)
    private final AtomicReference<String> cachedCookie = new AtomicReference<>();
    private volatile long cookieTimestamp = 0;
    private static final long COOKIE_TTL_MS = 10 * 60 * 1000; // 10분

    // 리다이렉트를 따라가지 않는 RestTemplate
    private final RestTemplate noRedirectTemplate;
    // 일반 RestTemplate (세션 취득용)
    private final RestTemplate defaultTemplate;

    public DhLotteryClient() {
        // 리다이렉트 비활성화 (302 감지용)
        SimpleClientHttpRequestFactory noRedirectFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        noRedirectFactory.setConnectTimeout(5000);
        noRedirectFactory.setReadTimeout(10000);
        this.noRedirectTemplate = new RestTemplate(noRedirectFactory);

        SimpleClientHttpRequestFactory defaultFactory = new SimpleClientHttpRequestFactory();
        defaultFactory.setConnectTimeout(5000);
        defaultFactory.setReadTimeout(10000);
        this.defaultTemplate = new RestTemplate(defaultFactory);
    }

    /**
     * 동행복권 API 호출 (세션 쿠키 자동 관리)
     * @param apiUrl 전체 API URL
     * @return JSON 응답 문자열, 실패 시 null
     */
    public String callApi(String apiUrl) {
        String cookie = getOrRefreshCookie();

        // 1차 시도: 캐시된 쿠키로 호출
        String result = doApiCall(apiUrl, cookie);
        if (result != null) return result;

        // 2차 시도: 쿠키 갱신 후 재호출
        log.info("[동행복권] 쿠키 갱신 후 재시도: {}", apiUrl);
        cookie = refreshCookie();
        if (cookie == null) return null;

        return doApiCall(apiUrl, cookie);
    }

    private String doApiCall(String apiUrl, String cookie) {
        if (cookie == null) return null;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);
            headers.set("Accept", "application/json, text/plain, */*");
            headers.set("Referer", "https://www.dhlottery.co.kr/");
            headers.set("Accept-Language", "ko-KR,ko;q=0.9");
            headers.set("Cookie", cookie);

            ResponseEntity<String> response = noRedirectTemplate.exchange(
                    apiUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            int status = response.getStatusCode().value();
            if (status == 302 || status == 301) {
                log.warn("[동행복권] API 리다이렉트 감지 ({}): {}", status, apiUrl);
                return null; // 쿠키 만료 → 재시도 트리거
            }

            String body = response.getBody();
            if (body != null && body.trim().startsWith("<")) {
                log.warn("[동행복권] HTML 응답 감지 (JSON 아님): {}", apiUrl);
                return null;
            }

            return body;
        } catch (Exception e) {
            log.warn("[동행복권] API 호출 실패: {} - {}", apiUrl, e.getMessage());
            return null;
        }
    }

    private String getOrRefreshCookie() {
        String cookie = cachedCookie.get();
        if (cookie != null && (System.currentTimeMillis() - cookieTimestamp) < COOKIE_TTL_MS) {
            return cookie;
        }
        return refreshCookie();
    }

    private synchronized String refreshCookie() {
        // 다른 스레드가 이미 갱신했는지 재확인
        if (cachedCookie.get() != null && (System.currentTimeMillis() - cookieTimestamp) < COOKIE_TTL_MS) {
            return cachedCookie.get();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);
            headers.set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

            ResponseEntity<String> response = defaultTemplate.exchange(
                    DH_HOME_URL, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
            if (setCookies == null || setCookies.isEmpty()) {
                log.error("[동행복권] 세션 쿠키 취득 실패 - Set-Cookie 헤더 없음");
                return null;
            }

            // Set-Cookie 헤더에서 쿠키 이름=값 추출
            StringBuilder cookieBuilder = new StringBuilder();
            for (String sc : setCookies) {
                String nameValue = sc.split(";")[0].trim();
                if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
                cookieBuilder.append(nameValue);
            }

            String newCookie = cookieBuilder.toString();
            cachedCookie.set(newCookie);
            cookieTimestamp = System.currentTimeMillis();
            log.info("[동행복권] 세션 쿠키 취득 성공: {}", newCookie.substring(0, Math.min(50, newCookie.length())) + "...");
            return newCookie;
        } catch (Exception e) {
            log.error("[동행복권] 세션 쿠키 취득 실패: {}", e.getMessage());
            return null;
        }
    }
}
