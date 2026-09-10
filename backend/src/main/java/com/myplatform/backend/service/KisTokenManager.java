package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.core.util.DateTimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
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

    /**
     * L2 공유(Redis) — <b>프로세스 재시작을 넘겨 토큰을 잇는다</b>. KIS 는 접근토큰 발급을 <b>분당 1회</b>로
     * 자르는데(초과 시 HTTP 403 `EGW00133`), 토큰이 프로세스 메모리에만 있으면 새 컨테이너는 직전 발급을
     * 몰라 다시 요청한다 — 2026-09-11 커밋 2개를 연달아 밀어 컨테이너가 8분 간격으로 두 번 재생성되자
     * 두 번째가 403 을 맞고 <b>90초간 전 KIS 호출 불가</b>(ERROR 11줄)였다. 여기서 읽으면 아예 요청하지 않는다.
     *
     * <p>{@code null}(Redis 미구성/비활성)이면 <b>정확히 종전 동작</b>(메모리 전용)이다. 모든 Redis 접근은
     * best-effort try/catch — Redis 장애가 매매 인증을 죽이면 안 된다(SchedulerLockService 와 같은 fail-open).
     */
    private final StringRedisTemplate tokenRedisTemplate;

    /** 공유 토큰 키. 값은 {@code {"token":..,"expireAt":..}} JSON + 남은 유효기간만큼 Redis TTL. */
    private static final String REDIS_KEY = "kis:access-token";

    public KisTokenManager(RestTemplate restTemplate, ObjectMapper objectMapper,
                           @Autowired(required = false) @Qualifier("cacheRedisTemplate")
                           StringRedisTemplate tokenRedisTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.tokenRedisTemplate = tokenRedisTemplate;
    }

    /**
     * 토큰을 지금 쓸 수 있는지 — <b>만료 1시간 전까지</b>가 유효 창(로컬·Redis 공통 판정, 단일 출처).
     * Redis 에서 채택할 때도 같은 규칙을 써야 곧 만료될 토큰을 물고 오지 않는다.
     */
    private static boolean isUsable(LocalDateTime expireAt) {
        return expireAt != null && DateTimeUtil.kstNow().isBefore(expireAt.minusHours(1));
    }

    /**
     * Redis 에 유효 토큰이 있으면 로컬 캐시로 채택하고 반환 — 없거나 만료 임박/장애면 null.
     * 호출자는 {@code synchronized} 안이다(로컬 필드 쓰기 보호).
     */
    private String adoptFromRedis() {
        if (tokenRedisTemplate == null) return null;
        try {
            String raw = tokenRedisTemplate.opsForValue().get(REDIS_KEY);
            if (raw == null || raw.isBlank()) return null;

            JsonNode node = objectMapper.readTree(raw);
            String token = node.path("token").asText(null);
            String expireText = node.path("expireAt").asText(null);
            if (token == null || token.isBlank() || expireText == null) return null;

            LocalDateTime expireAt = LocalDateTime.parse(expireText);
            if (!isUsable(expireAt)) {
                log.debug("[KIS토큰] Redis 토큰이 만료 임박/만료({}) — 채택하지 않음", expireAt);
                return null;
            }

            accessToken = token;
            tokenExpireTime = expireAt;
            tokenCooldownUntil = null;   // 유효 토큰을 얻었으니 발급 쿨다운은 의미 없다
            log.info("[KIS토큰] Redis 공유 토큰 채택 (만료: {}) — 발급 생략(분당 1회 한도 회피)", expireAt);
            return accessToken;
        } catch (Exception e) {
            log.debug("[KIS토큰] Redis 조회 실패 — 메모리 전용으로 진행: {}", e.getMessage());
            return null;
        }
    }

    /** 발급 성공분을 Redis 에 공유 — 남은 유효기간을 TTL 로 둬 죽은 값이 영원히 남지 않게. */
    private void publishToRedis(String token, LocalDateTime expireAt) {
        if (tokenRedisTemplate == null || token == null || expireAt == null) return;
        try {
            Duration ttl = Duration.between(DateTimeUtil.kstNow(), expireAt);
            if (ttl.isZero() || ttl.isNegative()) return;
            String payload = objectMapper.writeValueAsString(
                    Map.of("token", token, "expireAt", expireAt.toString()));
            tokenRedisTemplate.opsForValue().set(REDIS_KEY, payload, ttl);
        } catch (Exception e) {
            log.debug("[KIS토큰] Redis 게시 실패(무해 — 메모리 캐시는 정상): {}", e.getMessage());
        }
    }

    /**
     * 무효화된 토큰을 Redis 에서도 지운다 — <b>이게 빠지면 이 기능이 사고가 된다</b>: 죽은 토큰이 재시작을
     * 넘어 되살아나 무효화가 영구히 무의미해진다(9/8 의 20분 전멸이 영구화되는 모양).
     *
     * <p>단일 인스턴스 전제(§5)라 무조건 삭제한다. 멀티 인스턴스로 가면 "값이 같을 때만 삭제"(Lua CAS)가
     * 필요하다 — 지금 무조건 삭제의 최악은 방금 재발급된 토큰을 지워 한 번 더 발급하는 것(회복 가능)이고,
     * 반대 방향(죽은 토큰 잔존)은 회복 불가라 안전한 쪽을 택했다.
     */
    private void evictFromRedis() {
        if (tokenRedisTemplate == null) return;
        try {
            tokenRedisTemplate.delete(REDIS_KEY);
        } catch (Exception e) {
            log.warn("[KIS토큰] Redis 삭제 실패 — 다른 프로세스가 죽은 토큰을 채택할 수 있다: {}", e.getMessage());
        }
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
        if (accessToken != null && isUsable(tokenExpireTime)) {
            return accessToken;
        }

        // L2 공유 토큰(Redis) — 재시작/컨테이너 재생성 직후 여기서 얻으면 발급을 아예 하지 않는다.
        // ⚠ 쿨다운 판정보다 먼저다: 403(EGW00133)으로 쿨다운에 걸린 프로세스도 유효 공유 토큰이 있으면
        // 65초를 기다릴 이유가 없다(2026-09-11 의 90초 전멸이 여기서 0 초가 된다).
        String shared = adoptFromRedis();
        if (shared != null) {
            return shared;
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
                    publishToRedis(accessToken, tokenExpireTime);   // 다음 프로세스가 재발급 없이 잇도록
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
        evictFromRedis();   // 필수 — 안 지우면 죽은 토큰이 재시작을 넘어 되살아난다
        log.warn("KIS API 401 인증 실패 — 공유 토큰 캐시 무효화(로컬+Redis, 다음 호출 재발급, 쿨다운 존중). 주문 재시도 없음.");
    }

    /**
     * KIS API 호출 예외가 인증 실패인지 판정 — <b>단일 출처</b>(순수 함수, 테스트 대상).
     * 401 = 토큰 만료/무효 신호. 403(권한/IP)·429(rate)·바디 없는 5xx·IO 는 false.
     *
     * <p><b>HTTP 500 + EGW00123 도 인증 실패다(2026-09-08 실측)</b>: KIS 는 만료 토큰에 401 이 아니라
     * 500 + {@code "msg_cd":"EGW00123"}("기간이 만료된 token") 을 준다. 이걸 안 잡으면 로컬 만료
     * 1시간 전 갱신창이 열릴 때까지(그날 08:00~08:20, 20분) 죽은 토큰으로 전 KIS 호출이 실패한다 —
     * 잔고 모니터·공시 모니터가 그 창 동안 전멸했다. "틀린 요청에도 200/500" 부류(§4b)라 바디로 판정.
     */
    static boolean isAuthFailure(Exception e) {
        if (e instanceof org.springframework.web.client.HttpClientErrorException
                && ((org.springframework.web.client.HttpClientErrorException) e).getStatusCode().value() == 401) {
            return true;
        }
        if (e instanceof org.springframework.web.client.HttpStatusCodeException) {
            String body = ((org.springframework.web.client.HttpStatusCodeException) e).getResponseBodyAsString();
            return body != null && body.contains("EGW00123");
        }
        return false;
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
