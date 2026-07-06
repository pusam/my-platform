package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 한국은행 ECOS OpenAPI 클라이언트 (매크로 tilt 금리 축 전용, P3-7).
 *
 * <p>StatisticSearch 로 기준금리(722Y001/0101000)·국고채3년(817Y002/010200000) 일별 시계열을 읽는다.
 * 호출량 ~2/일 — 무료 쿼터(1만/일) 대비 무시 가능.
 *
 * <p><b>함정 방어 4종</b>:
 * <ul>
 *   <li><b>키가 URL path 에 포함</b>되는 API — 실패 로그에 URI 절대 미출력(키 유출 방지). 메시지만 남긴다.
 *   <li>ECOS 는 "데이터 없음(INFO-200)"·"키 오류(INFO-100)"를 <b>HTTP 200 + RESULT body</b> 로 반환
 *       → 파서가 조용히 빈 리스트(키 발급 전 매일 ERROR 스팸 방지, §4c 미수집=null 강등).
 *   <li>bp 변환(×100)은 명명된 순수 헬퍼 {@link #trendBp} 로 분리(단위 무음 버그 방지).
 *   <li>키 미설정({@link #isAvailable}) 시 호출 자체를 생략 — null 반환(§4c, NaverSearchService 선례).
 * </ul>
 */
@Service
@Slf4j
public class EcosClient {

    private static final String BASE_URL = "https://ecos.bok.or.kr/api/StatisticSearch";
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 한은 기준금리 (일별 스텝 함수 — 스냅샷 참고 맥락 전용, classify 입력 아님). */
    static final String STAT_BASE_RATE = "722Y001";
    static final String ITEM_BASE_RATE = "0101000";
    /** 시장금리(일별) — 국고채 3년. */
    static final String STAT_KTB = "817Y002";
    static final String ITEM_KTB_3Y = "010200000";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ecos.api.key:}")
    private String apiKey;

    public EcosClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    /** 키 설정 여부 — 미설정이면 금리 축 전체 null(§4c). 키는 .env ECOS_API_KEY + compose 이중배선. */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty();
    }

    /** 일별 시계열 한 점 — date(yyyy-MM-dd) + 값. */
    public record EcosPoint(String date, double value) {}

    /**
     * 국고채 3년 일별 시계열 (최근 {@code calendarDays}일, 과거→최신 오름차순).
     * 미가용/실패/데이터없음 → 빈 리스트(§4c — 호출부가 축 null 로 강등).
     */
    public List<EcosPoint> getKtb3ySeries(int calendarDays) {
        return fetchSeries(STAT_KTB, ITEM_KTB_3Y, calendarDays);
    }

    /** 한은 기준금리 최신값 (스냅샷 참고 맥락). 미가용/실패 → null. */
    public Double getBaseRate() {
        List<EcosPoint> series = fetchSeries(STAT_BASE_RATE, ITEM_BASE_RATE, 40);
        return series.isEmpty() ? null : series.get(series.size() - 1).value();
    }

    private List<EcosPoint> fetchSeries(String statCode, String itemCode, int calendarDays) {
        if (!isAvailable()) {
            return List.of();   // 키 미발급 — 조용히 미수집(§4c). 로그 스팸 금지.
        }
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(calendarDays);
        try {
            // §16-10: 완성 URI 를 URI 타입으로 전달(String 재인코딩 함정 회피).
            // ⚠ 키가 path 에 들어감 — 이 URI 를 로그에 출력하지 말 것(키 유출).
            URI uri = UriComponentsBuilder.fromUriString(BASE_URL)
                    .pathSegment(apiKey, "json", "kr", "1", "100",
                            statCode, "D", start.format(YMD), end.format(YMD), itemCode)
                    .build()
                    .toUri();
            String body = restTemplate.getForObject(uri, String.class);
            return parseStatisticSearch(body == null ? null : objectMapper.readTree(body));
        } catch (Exception e) {
            log.warn("ECOS {} 조회 실패: {}", statCode, e.getMessage());   // URI 미출력(키 보호)
            return List.of();
        }
    }

    /**
     * StatisticSearch 응답 파싱 — 순수 함수(테스트 대상).
     *
     * <p>정상 = {@code {"StatisticSearch":{"row":[{TIME, DATA_VALUE, ...}]}}}.
     * INFO-100(키 오류)/INFO-200(데이터 없음)은 <b>HTTP 200 + {"RESULT":{...}}</b> — 빈 리스트로
     * 조용히 처리(throw 금지). TIME=yyyyMMdd → yyyy-MM-dd, 값 파싱 불가 행은 skip(보정 금지).
     */
    static List<EcosPoint> parseStatisticSearch(JsonNode response) {
        List<EcosPoint> out = new ArrayList<>();
        if (response == null) return out;
        JsonNode rows = response.path("StatisticSearch").path("row");
        if (!rows.isArray()) return out;   // RESULT(INFO-100/200) 포함 — 조용히 빈 리스트
        for (JsonNode row : rows) {
            String time = row.path("TIME").asText("");
            String value = row.path("DATA_VALUE").asText("");
            if (time.length() != 8 || value.isEmpty()) continue;
            try {
                double v = Double.parseDouble(value);
                String iso = time.substring(0, 4) + "-" + time.substring(4, 6) + "-" + time.substring(6, 8);
                out.add(new EcosPoint(iso, v));
            } catch (NumberFormatException ignored) {
                // 파싱 불가 행 skip — 위장값 금지(§4c)
            }
        }
        out.sort(java.util.Comparator.comparing(EcosPoint::date));   // 과거→최신
        return out;
    }

    /**
     * 금리 추세(bp) — 순수 함수(테스트 대상). 최신 vs ~{@code lookback}거래일 전, <b>%→bp 는 ×100</b>.
     * 예: 2.855% → 2.705% = −15.0bp. 시계열이 {@code lookback+1}점 미만이면 null(미수집).
     */
    static Double trendBp(List<EcosPoint> ascSeries, int lookback) {
        if (ascSeries == null || ascSeries.size() < lookback + 1) return null;
        double latest = ascSeries.get(ascSeries.size() - 1).value();
        double past = ascSeries.get(ascSeries.size() - 1 - lookback).value();
        return (latest - past) * 100.0;
    }
}
