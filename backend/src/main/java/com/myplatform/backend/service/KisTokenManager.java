package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.core.util.DateTimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * KIS OAuth 토큰 <b>단일 출처</b> (P3-8 선택B, 2026-07-10) — 발급·캐시·갱신·쿨다운·401 무효화를
 * 3서비스({@link KoreaInvestmentService}·{@link KisApiService}·{@link MarketIndicatorService})가 공유한다.
 *
 * <p><b>왜</b>: 이전엔 3서비스가 같은 앱키로 <b>각자</b> 토큰을 발급·캐시했다(재시작 직후 3서비스 동시
 * 첫 호출이면 발급 3중 경합 가능). 이 컴포넌트로 발급을 <b>단일 진입점 + {@code synchronized}</b> 직렬화해
 * 앱 전체가 토큰 1개를 공유한다(발급 수·경합 최소화). expires_in 준수·1h 전 갱신·65초 쿨다운은
 * 기존 {@code KoreaInvestmentService} 로직을 그대로 옮긴 것(권위 구현).
 *
 * <p><b>401 무효화 = 값 기반 CAS</b>({@link #invalidateOnAuthFailure}): 한 서비스의 401 이 <b>방금 다른
 * 서비스가 재발급한 새 토큰</b>을 죽이지 않게, 실패한 호출이 쓴 토큰이 현재 캐시와 <b>동일할 때만</b> 무효화한다
 * (토큰 문자열 = 세대 식별자 — 재발급은 항상 새 문자열이라 stale 참조는 no-op). 이미 무효화(null)면 no-op
 * (2연속 401 이 발급 rate 를 태우지 않게 — 1회성). <b>§4d: 무효화까지만, 주문 재시도는 절대 없음</b>
 * (KIS 비멱등 — 401=미접수 확실, 호출부는 기존대로 null/거부바디 반환).
 */
@Component
public class KisTokenManager {

    private static final Logger log = LoggerFactory.getLogger(KisTokenManager.class);

    /** 토큰 발급 실패 시 쿨다운 (KIS 발급 분당 1회 제한 방어). 1분 + 여유 5초. */
    private static final int TOKEN_COOLDOWN_SECONDS = 65;

    @Value("${kis.api.app-key:}")
    private String appKey;

    @Value("${kis.api.app-secret:}")
    private String appSecret;

    @Value("${kis.api.base-url:https://openapi.koreainvestment.com:9443}")
    private String baseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // 공유 토큰 캐시 (전 서비스 단일 출처). 쓰기는 synchronized(발급 직렬화·CAS 무효화)로 보호하고,
    // volatile 로 두어 isTokenAvailable() 이 락 없이 읽는다 — 발급 POST(최악 ~8초)가 락을 쥔 동안
    // 시세 단일 경로(StockPriceService→isTokenAvailable)가 블로킹되지 않게(2026-07-14 점검 P2).
    private volatile String accessToken;
    private volatile LocalDateTime tokenExpireTime;
    private volatile LocalDateTime tokenCooldownUntil;

    public KisTokenManager(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /** API 키 설정 여부 — 발급/가용 판정 공통 게이트. */
    public boolean isConfigured() {
        return appKey != null && !appKey.isEmpty()
                && appSecret != null && !appSecret.isEmpty();
    }

    /**
     * 토큰 발급 시도 없이 상태만 빠르게 확인 (유효 토큰 or 발급 가능하면 true, 쿨다운 중이면 false).
     * {@code KoreaInvestmentService.isTokenAvailable()} 위임 대상.
     *
     * <p><b>의도적으로 비-synchronized</b>: {@link #getAccessToken()} 이 락을 쥔 채 발급 POST(최악 ~8초)를
     * 수행하므로, 같은 락을 쓰면 발급 중 시세 단일 경로가 통째로 대기한다("발급 시도 없이 빠르게" 계약 위반).
     * volatile 스냅샷 읽기라 필드 쌍이 찰나에 어긋날 수 있으나 advisory 판정(true/false 가 한 틱 이르거나
     * 늦을 뿐)이라 무해 — 실제 토큰 사용은 getAccessToken() 이 락 안에서 정합 보장.
     */
    public boolean isTokenAvailable() {
        String token = accessToken;
        LocalDateTime expire = tokenExpireTime;
        if (token != null && expire != null
                && DateTimeUtil.kstNow().isBefore(expire.minusHours(1))) {
            return true;
        }
        LocalDateTime cooldown = tokenCooldownUntil;
        if (cooldown != null && DateTimeUtil.kstNow().isBefore(cooldown)) {
            return false;
        }
        return isConfigured();
    }

    /**
     * 공유 Access Token — 유효하면 캐시 반환, 아니면 발급(쿨다운/미설정이면 null).
     * <b>발급은 {@code synchronized} 로 전역 직렬화</b> — 동시 호출자가 몰려도 발급 POST 는 1회.
     */
    public synchronized String getAccessToken() {
        // 토큰이 유효하면 재사용 (만료 1시간 전까지)
        if (accessToken != null && tokenExpireTime != null
                && DateTimeUtil.kstNow().isBefore(tokenExpireTime.minusHours(1))) {
            return accessToken;
        }

        // 쿨다운 중이면 null 반환 (Rate Limit 방지)
        if (tokenCooldownUntil != null && DateTimeUtil.kstNow().isBefore(tokenCooldownUntil)) {
            log.debug("토큰 발급 쿨다운 중 ({}까지 대기)", tokenCooldownUntil);
            return null;
        }

        if (!isConfigured()) {
            log.warn("한국투자증권 API 키가 설정되지 않았습니다. (appKey 길이: {}, appSecret 길이: {})",
                    appKey != null ? appKey.length() : 0,
                    appSecret != null ? appSecret.length() : 0);
            return null;
        }

        String maskedKey = appKey.length() > 4
                ? appKey.substring(0, 4) + "****" : "****";

        try {
            String url = baseUrl + "/oauth2/tokenP";
            log.info("KIS 토큰 발급 시도 - baseUrl: {}, appKey: {}", baseUrl, maskedKey);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = new HashMap<>();
            body.put("grant_type", "client_credentials");
            body.put("appkey", appKey);
            body.put("appsecret", appSecret);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            log.info("KIS 토큰 응답 - HTTP {}, body 길이: {}",
                    response.getStatusCode(),
                    response.getBody() != null ? response.getBody().length() : 0);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());

                if (root.has("access_token")) {
                    accessToken = root.get("access_token").asText();
                    // 토큰 만료시간 = 응답의 expires_in(초) 기준. 갱신 1시간 전 판정은
                    // getAccessToken 상단의 tokenExpireTime.minusHours(1) 로 유지(불변).
                    long expiresIn = parseExpiresInSeconds(root);
                    if (expiresIn > 0) {
                        tokenExpireTime = DateTimeUtil.kstNow().plusSeconds(expiresIn);
                    } else {
                        // §4c: 결측을 근거로 더 짧게 잡아 갱신을 폭주시키지 않는다 — 보수적 24h 폴백.
                        tokenExpireTime = DateTimeUtil.kstNow().plusHours(24);
                        log.warn("KIS 토큰 응답에 유효한 expires_in 없음 — 폴백 24h 적용");
                    }
                    tokenCooldownUntil = null;
                    log.info("KIS Access Token 발급 성공 (만료: {})", tokenExpireTime);
                    return accessToken;
                } else {
                    String errorCode = root.has("error_code") ? root.get("error_code").asText() : "";
                    String errorMsg = root.has("msg") ? root.get("msg").asText() : "";
                    String errorDesc = root.has("error_description") ? root.get("error_description").asText() : "";
                    log.error("KIS 토큰 발급 실패 - code: {}, msg: {}, desc: {}, 전체 응답: {}",
                            errorCode, errorMsg, errorDesc, response.getBody());
                    tokenCooldownUntil = DateTimeUtil.kstNow().plusSeconds(TOKEN_COOLDOWN_SECONDS);
                    log.info("KIS 토큰 쿨다운 설정: {}까지 대기", tokenCooldownUntil);
                }
            } else {
                log.error("KIS 토큰 비정상 응답 - HTTP {}", response.getStatusCode());
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            log.error("KIS 토큰 발급 HTTP {} - appKey: {}, baseUrl: {}, 응답: {}",
                    statusCode, maskedKey, baseUrl, responseBody, e);
            if (statusCode == 401) {
                log.error("KIS 토큰 401 Unauthorized - appKey/appSecret 확인 필요");
            } else if (statusCode == 403) {
                log.error("KIS 토큰 403 Forbidden - API 권한 또는 IP 접근 제한 확인");
            } else if (statusCode == 429) {
                log.error("KIS 토큰 429 Too Many Requests - 분당 요청 한도 초과");
            }
            tokenCooldownUntil = DateTimeUtil.kstNow().plusSeconds(TOKEN_COOLDOWN_SECONDS);
            log.info("KIS 토큰 쿨다운 설정: {}초 ({}까지)", TOKEN_COOLDOWN_SECONDS, tokenCooldownUntil);
        } catch (Exception e) {
            log.error("KIS 토큰 발급 예외 - appKey: {}, baseUrl: {}", maskedKey, baseUrl, e);
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("Connection refused") || msg.contains("Connect timed out")) {
                tokenCooldownUntil = DateTimeUtil.kstNow().plusSeconds(TOKEN_COOLDOWN_SECONDS * 2);
                log.error("KIS API 서버 연결 불가 - {}초 쿨다운", TOKEN_COOLDOWN_SECONDS * 2);
            }
        }

        return null;
    }

    /**
     * API 호출이 401(인증 실패)이면 공유 토큰 캐시를 <b>1회</b> 무효화한다 (값 기반 CAS).
     * → 다음 {@link #getAccessToken()} 호출이 재발급(65초 쿨다운 존중).
     *
     * <p><b>CAS(세대/버전 비교)</b>: {@code usedToken}(실패한 호출이 실제 쓴 토큰)이 현재 캐시와 동일할 때만
     * 무효화한다. 다른 스레드/서비스가 이미 재발급했으면 현재 토큰은 다른 문자열이라 no-op —
     * 한 서비스의 stale 401 이 방금 재발급된 새 토큰을 죽이는 레이스를 막는다.
     * <p><b>1회성</b>: 이미 무효화(null)면 no-op — 401 폭주가 발급 rate(분당 1회)를 태우지 않게.
     * <p><b>§4d</b>: 여기까지만 — 실패한 호출/주문을 재시도하지 않는다(KIS 비멱등, 호출부가 null/거부 반환).
     *
     * @param usedToken 실패한 호출이 헤더에 실었던 토큰(호출부 로컬). null 이면 무효화 안 함(비교 불가).
     */
    public synchronized void invalidateOnAuthFailure(Exception e, String usedToken) {
        if (!isAuthFailure(e)) {
            return;
        }
        if (accessToken == null) {
            return;   // 이미 무효화됨 (1회성)
        }
        if (usedToken == null || !usedToken.equals(accessToken)) {
            // 이미 재발급된 새 토큰(다른 세대) — stale 참조로 죽이지 않는다.
            log.debug("KIS 401 — 사용 토큰이 현재 캐시와 불일치(이미 재발급됨), 무효화 스킵(CAS no-op)");
            return;
        }
        accessToken = null;
        tokenExpireTime = null;
        log.warn("KIS API 401 인증 실패 — 공유 토큰 캐시 무효화(다음 호출 재발급, 쿨다운 존중). 주문 재시도 없음.");
    }

    /**
     * KIS API 호출 예외가 인증 실패(HTTP 401)인지 판정 — <b>단일 출처</b>(순수 함수, 테스트 대상).
     * 401 = 토큰 만료/무효 신호. 403(권한/IP)·429(rate)·5xx·IO 는 false.
     */
    static boolean isAuthFailure(Exception e) {
        return e instanceof org.springframework.web.client.HttpClientErrorException
                && ((org.springframework.web.client.HttpClientErrorException) e).getStatusCode().value() == 401;
    }

    /**
     * KIS 토큰 응답의 expires_in(초)을 추출한다 — <b>단일 출처</b>(순수 함수, 테스트 대상).
     * 응답값을 신뢰하되 결측/비정상은 폴백 신호(-1)로 넘겨 호출부가 보수적 24h 를 쓰게 한다(§4c).
     *
     * @return 유효한 만료초(&gt;0)이면 그 값, 결측/비숫자/0 이하이면 -1
     */
    static long parseExpiresInSeconds(JsonNode root) {
        if (root == null) {
            return -1L;
        }
        JsonNode node = root.get("expires_in");
        if (node == null || node.isNull()) {
            return -1L;
        }
        long secs = node.asLong(-1L);
        return secs > 0 ? secs : -1L;
    }
}
