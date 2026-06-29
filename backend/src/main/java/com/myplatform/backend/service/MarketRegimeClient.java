package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * 시장 국면(레짐) 클라이언트 — python-backend pykrx 국면 분류 소비 (V32).
 *
 * GET {base-url}/api/v2/regime/current → {success, data:{regime: BULL/BEAR/SIDEWAYS, ...}}
 *
 * best-effort: python-backend 미기동/장애 시 null (시그널 스냅샷은 미수집 NULL — §4c).
 * 국면은 저속 지표라 인메모리 1시간 캐시 — 시그널 record 루프에서 호출돼도 HTTP 1회.
 */
@Service
@Slf4j
public class MarketRegimeClient {

    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final int TIMEOUT_MS = 3000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final PythonBackendHealthTracker health;

    private volatile String cachedRegime = null;
    private volatile Instant cachedAt = null;

    public MarketRegimeClient(@Value("${python-backend.base-url:http://localhost:8000}") String baseUrl,
                              PythonBackendHealthTracker health) {
        this.baseUrl = baseUrl;
        this.health = health;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 현재 시장 국면 — BULL / BEAR / SIDEWAYS. 미가용이면 null (위장값 금지).
     * 실패도 TTL 동안 캐시하지 않음 — 다음 호출에서 재시도하되, 성공 캐시가 있으면 그것을 우선.
     */
    public String getCurrentRegimeQuiet() {
        Instant now = Instant.now();
        if (cachedRegime != null && cachedAt != null
                && Duration.between(cachedAt, now).compareTo(CACHE_TTL) < 0) {
            return cachedRegime;
        }
        try {
            String body = restTemplate.getForObject(baseUrl + "/api/v2/regime/current", String.class);
            String regime = parseRegime(body == null ? null : objectMapper.readTree(body));
            if (regime != null) {
                cachedRegime = regime;
                cachedAt = now;
                health.recordSuccess(PythonBackendHealthTracker.SOURCE_REGIME);
            }
            // regime==null(응답은 왔으나 국면 산출 불가)은 python 다운이 아니므로 실패로 집계하지 않음.
            return regime != null ? regime : cachedRegime; // 일시 실패 시 직전 성공값 폴백 (있으면)
        } catch (Exception e) {
            log.debug("[Regime] python-backend 국면 조회 실패: {}", e.getMessage());
            health.recordFailure(PythonBackendHealthTracker.SOURCE_REGIME, e.getMessage());
            return cachedRegime; // 미기동 시 null — 스냅샷 미수집 처리
        }
    }

    /** 응답 파싱 — 순수 함수 (테스트 대상). 유효 국면 어휘 밖이면 null. */
    static String parseRegime(JsonNode response) {
        if (response == null || !response.path("success").asBoolean(false)) return null;
        String regime = response.path("data").path("regime").asText("");
        return switch (regime) {
            case "BULL", "BEAR", "SIDEWAYS" -> regime;
            default -> null;
        };
    }
}
