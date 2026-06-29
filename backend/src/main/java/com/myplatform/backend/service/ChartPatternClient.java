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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 차트 추세추종 보조 시그널 클라이언트 — python-backend pykrx 지표 소비.
 *
 * POST {base-url}/api/v2/chart/timing          → 매수후보 타이밍(정배열·눌림목) 종목별
 * POST {base-url}/api/v2/chart/sector-strength → 발굴 섹터 상대강도('덜 빠지는 섹터')
 *
 * MarketRegimeClient 패턴 미러: 3s 타임아웃, best-effort(미가용 시 빈 결과/null — 위장값 금지 §4c).
 * ⚠ 산식 미검증 — 발굴 탭 보조 시그널(미검증 배지) 전용. 봇/종합추천/매수후보 랭킹 편입 금지.
 */
@Service
@Slf4j
public class ChartPatternClient {

    private static final Duration SECTOR_CACHE_TTL = Duration.ofHours(1);
    private static final int TIMEOUT_MS = 8000;  // 벌크 OHLCV 스캔 — regime(3s)보다 여유

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final PythonBackendHealthTracker health;

    // 섹터 강도는 일 단위 저속 지표 — 1시간 인메모리 캐시
    private volatile Map<String, Object> cachedSectorStrength = null;
    private volatile Instant sectorCachedAt = null;

    public ChartPatternClient(@Value("${python-backend.base-url:http://localhost:8000}") String baseUrl,
                              PythonBackendHealthTracker health) {
        this.baseUrl = baseUrl;
        this.health = health;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    /** 테스트용 — RestTemplate(mock) 직접 주입. */
    ChartPatternClient(String baseUrl, RestTemplate restTemplate, PythonBackendHealthTracker health) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
        this.health = health;
    }

    /**
     * 매수후보 타이밍 신호(종목별). 결과에 <b>가용/미가용(available)</b>을 명시 — 빈 리스트가
     * "신호 없음"인지 "python 다운"인지 호출자가 구분할 수 있게 한다(null/empty 로 뭉개지 않음).
     */
    public TimingFetch getTimingSignals(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return new TimingFetch(true, List.of());  // 빈 입력=요청 안 함=가용
        try {
            String body = restTemplate.postForObject(
                    baseUrl + "/api/v2/chart/timing", Map.of("tickers", tickers), String.class);
            JsonNode root = body == null ? null : objectMapper.readTree(body);
            boolean ok = root != null && root.path("success").asBoolean(false);
            if (ok) {
                health.recordSuccess(PythonBackendHealthTracker.SOURCE_TIMING);
                return new TimingFetch(true, parseTimingSignals(root));
            }
            health.recordFailure(PythonBackendHealthTracker.SOURCE_TIMING, "non-success response");
            return new TimingFetch(false, List.of());
        } catch (Exception e) {
            log.debug("[ChartPattern] 타이밍 조회 실패: {}", e.getMessage());
            health.recordFailure(PythonBackendHealthTracker.SOURCE_TIMING, e.getMessage());
            return new TimingFetch(false, List.of());
        }
    }

    /**
     * 섹터 상대강도 랭킹 — python data 그대로 전달(ranked/marketReturn/asOf). 1시간 캐시.
     * 미가용 시 null(스냅샷 미수집 의미 — 발굴 배지 생략).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSectorStrength(Map<String, List<String>> sectors) {
        Instant now = Instant.now();
        if (cachedSectorStrength != null && sectorCachedAt != null
                && Duration.between(sectorCachedAt, now).compareTo(SECTOR_CACHE_TTL) < 0) {
            return cachedSectorStrength;
        }
        if (sectors == null || sectors.isEmpty()) return cachedSectorStrength;
        try {
            String body = restTemplate.postForObject(
                    baseUrl + "/api/v2/chart/sector-strength", Map.of("sectors", sectors), String.class);
            JsonNode root = body == null ? null : objectMapper.readTree(body);
            if (root != null && root.path("success").asBoolean(false)) {
                Map<String, Object> data = objectMapper.convertValue(root.path("data"), Map.class);
                if (data != null && data.get("ranked") != null) {
                    cachedSectorStrength = data;
                    sectorCachedAt = now;
                    health.recordSuccess(PythonBackendHealthTracker.SOURCE_SECTOR);
                    return data;
                }
            }
            health.recordFailure(PythonBackendHealthTracker.SOURCE_SECTOR, "non-success/empty response");
            return cachedSectorStrength;  // 일시 실패 시 직전 성공값 폴백
        } catch (Exception e) {
            log.debug("[ChartPattern] 섹터강도 조회 실패: {}", e.getMessage());
            health.recordFailure(PythonBackendHealthTracker.SOURCE_SECTOR, e.getMessage());
            return cachedSectorStrength;
        }
    }

    /** python results 배열 → TimingSignal 리스트. 순수 파싱(테스트 대상). */
    static List<TimingSignal> parseTimingSignals(JsonNode root) {
        List<TimingSignal> out = new ArrayList<>();
        if (root == null || !root.path("success").asBoolean(false)) return out;
        JsonNode results = root.path("data").path("results");
        if (!results.isArray()) return out;
        for (JsonNode n : results) {
            List<String> signals = new ArrayList<>();
            n.path("signals").forEach(s -> signals.add(s.asText()));
            out.add(new TimingSignal(
                    n.path("ticker").asText(null),
                    n.path("available").asBoolean(false),
                    n.path("timingScore").isNull() || n.path("timingScore").isMissingNode()
                            ? null : n.path("timingScore").asInt(),
                    n.path("riskExcluded").asBoolean(false),
                    signals
            ));
        }
        return out;
    }

    /** python 타이밍 결과 1건 (보조 시그널). score=null 이면 미산출(위험필터 제외 등). */
    public record TimingSignal(String ticker, boolean available,
                               Integer timingScore, boolean riskExcluded,
                               List<String> signals) {}

    /** 타이밍 조회 결과 묶음 — available=python 가용 여부(빈 signals 가 '신호 없음'인지 '다운'인지 구분). */
    public record TimingFetch(boolean available, List<TimingSignal> signals) {}
}
