package com.myplatform.backend.service;

import com.myplatform.backend.dto.ExchangeRateDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 환율 정보 서비스 — USD/KRW 매매기준율은 한국수출입은행 Open API 단일 출처(2026-10-02).
 * - 도메인은 {@code oapi.koreaexim.go.kr} — 구 {@code www.koreaexim.go.kr} API 는 2026-04-30 종료 공지대로 응답이 없다(10/2 실측).
 * - 키({@code KOREAEXIM_API_KEY})가 없으면 출처가 없다 — 첫 조회 때 WARN 1회, 응답은 rate=null 이라 화면이 숨긴다(§4c).
 * - 네이버 폴백은 은퇴했다: 그 페이지가 stock.naver.com SPA 로 302 되어 매번 rate=null 을 "조회 완료"로 남겼다
 *   (9/23 네이버 레거시 크롤 은퇴와 같은 원인, {@code NaverLegacyCrawlRetiredTest}).
 * - 외국인 수급 신호 분석
 */
@Service
@Slf4j
public class ExchangeRateService {

    @Value("${koreaexim.api.key:}")
    private String koreaeximApiKey;

    @Value("${koreaexim.api.url:https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON}")
    private String koreaeximApiUrl;

    /** 키 미설정 경고는 프로세스당 한 번 — 조회마다 찍으면 같은 경고가 쌓여 읽히지 않는다(§5). */
    private final AtomicBoolean missingKeyWarned = new AtomicBoolean();

    // RestTemplate timeout 명시 — 외부 API hang 시 thread 무한 점유 방지.
    // koreaexim 응답이 5초 안에 안 오면 실패로 보고 캐시(성공분만)를 쓴다.
    private final RestTemplate restTemplate = createRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static RestTemplate createRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f
                = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(5000);
        return new RestTemplate(f);
    }

    // 캐시 (환율은 자주 안 바뀌므로 10분 캐시)
    private volatile ExchangeRateDto cachedRate;
    private volatile LocalDateTime cacheTime;
    private static final long CACHE_MINUTES = 10;

    // 전일 매매기준율 캐시(당일 내 불변) — 10분마다 되감기 재조회하지 않도록.
    private volatile BigDecimal prevRateValue;
    private volatile LocalDate prevRateDate;
    /** 연휴 대비 되감기 상한(일). */
    private static final int PREV_LOOKBACK_DAYS = 7;

    /**
     * 현재 USD/KRW 환율 정보 조회
     */
    public ExchangeRateDto getCurrentExchangeRate() {
        // 캐시 체크
        if (cachedRate != null && cacheTime != null
                && cacheTime.isAfter(LocalDateTime.now().minusMinutes(CACHE_MINUTES))) {
            return cachedRate;
        }

        ExchangeRateDto result = null;

        if (koreaeximApiKey == null || koreaeximApiKey.isBlank()) {
            if (missingKeyWarned.compareAndSet(false, true)) {
                log.warn("[환율] KOREAEXIM_API_KEY 미설정 — USD/KRW 출처가 없다(화면은 숨김). "
                        + "한국수출입은행 Open API 인증키를 .env 에 넣고 backend 를 재생성할 것");
            }
        } else {
            result = fetchFromKoreaExim();
        }

        if (result != null && result.getRate() != null) {
            cachedRate = result;
            cacheTime = LocalDateTime.now();
        }

        return result != null ? result : ExchangeRateDto.builder()
                .interpretation("환율 정보를 불러올 수 없습니다")
                .fetchedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 한국수출입은행 Open API로 환율 조회
     */
    private ExchangeRateDto fetchFromKoreaExim() {
        try {
            String searchDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String url = String.format("%s?authkey=%s&searchdate=%s&data=AP01",
                    koreaeximApiUrl, koreaeximApiKey, searchDate);

            String response = restTemplate.getForObject(url, String.class);
            if (response == null || response.isBlank()) {
                log.warn("[환율] 수출입은행 빈 응답");
                return null;
            }

            EximParse parsed = parseUsdDealBasRate(objectMapper.readTree(response));
            if (parsed.rate() == null) {
                if (parsed.problem() != null) {
                    log.warn("[환율] 수출입은행 응답 이상 — {}", parsed.problem());
                } else {
                    // 빈 배열 = 오늘 고시 전(영업일 오전)·비영업일 — 정상 상태라 반복 경고하지 않는다
                    log.debug("[환율] 수출입은행 오늘 고시 없음(고시 전·비영업일) — 환율 미표시");
                }
                return null;
            }
            BigDecimal rate = parsed.rate();

            // 전일 대비 변동 — AP01 응답에는 '전일 환율' 필드가 없다.
            // (과거엔 bkpr 을 전일가로 오인해 뺐는데, bkpr 은 '장부가격'(매매기준율의 정수부)이라
            //  change 가 항상 소수부(0~0.99)=+0.0x% 로 고정 → 급등락일에도 신호 미발화였다.)
            // 직전 영업일자로 같은 API 를 재조회해 실제 전일 매매기준율을 구한다. 못 구하면
            // change/changeRate = null(미수집, §4c) — DTO 가 FLAT/NEUTRAL 로 정직 처리.
            BigDecimal prevRate = resolvePreviousRate(LocalDate.now());
            BigDecimal change = (prevRate != null) ? rate.subtract(prevRate) : null;

            // 변동률 계산
            BigDecimal changeRate = null;
            if (change != null && prevRate.compareTo(BigDecimal.ZERO) > 0) {
                changeRate = change.divide(prevRate, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
            }

            String trend = ExchangeRateDto.determineTrend(change);
            String signal = ExchangeRateDto.determineSignal(changeRate);
            String interpretation = ExchangeRateDto.generateInterpretation(changeRate, signal);

            log.info("환율 조회 완료 (수출입은행): {} ({}%)", rate, changeRate);
            return ExchangeRateDto.builder()
                    .rate(rate).change(change).changeRate(changeRate)
                    .trend(trend).signal(signal).interpretation(interpretation)
                    .fetchedAt(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            // RestTemplate I/O 예외 메시지는 요청 URL 전체(authkey 쿼리 포함)를 담으므로 키 마스킹 후 로깅
            log.warn("수출입은행 환율 조회 실패: {}", maskAuthKey(e.getMessage(), koreaeximApiKey));
            return null;
        }
    }

    /**
     * 직전 영업일 USD 매매기준율 — 주말/공휴일은 AP01 이 빈 배열을 주므로 최대 {@link #PREV_LOOKBACK_DAYS}일
     * 되감으며 탐색. 날짜별 결과는 하루 안에 안 바뀌므로 캐시(추가 호출 억제). 못 구하면 null(§4c).
     */
    private BigDecimal resolvePreviousRate(LocalDate today) {
        LocalDate cachedFor = prevRateDate;
        if (cachedFor != null && cachedFor.equals(today) && prevRateValue != null) {
            return prevRateValue;
        }
        for (int back = 1; back <= PREV_LOOKBACK_DAYS; back++) {
            BigDecimal r = fetchRateOn(today.minusDays(back));
            if (r != null) {
                prevRateValue = r;
                prevRateDate = today;
                return r;
            }
        }
        log.warn("[환율] 직전 영업일({}일 내) 매매기준율 조회 실패 — 변동 미수집으로 처리", PREV_LOOKBACK_DAYS);
        return null;
    }

    /** 특정 일자의 USD 매매기준율 조회. 휴장/미개시일이면 null(빈 배열). 실패도 null. */
    private BigDecimal fetchRateOn(LocalDate date) {
        try {
            String url = String.format("%s?authkey=%s&searchdate=%s&data=AP01",
                    koreaeximApiUrl, koreaeximApiKey, date.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            String response = restTemplate.getForObject(url, String.class);
            if (response == null || response.isBlank()) return null;
            return parseUsdDealBasRate(objectMapper.readTree(response)).rate();
        } catch (Exception e) {
            log.debug("[환율] {} 매매기준율 조회 실패: {}", date, maskAuthKey(e.getMessage(), koreaeximApiKey));
            return null;
        }
    }

    // 예외 메시지에 섞인 authkey 를 로그 출력 전에 가린다 (EcosClient 의 URI 미출력과 같은 키 보호 원칙)
    static String maskAuthKey(String message, String key) {
        if (message == null || key == null || key.isBlank()) return message;
        return message.replace(key, "***");
    }

    /** 수출입은행 AP01 응답 한 번을 읽은 결과 — rate 가 있으면 성공, 없으면 problem 이 이유(null 이면 '고시 없음'). */
    record EximParse(BigDecimal rate, String problem) {}

    /**
     * 수출입은행 AP01 응답에서 USD 매매기준율을 읽는다(순수).
     *
     * <p>빈 배열은 오늘 고시 전(영업일 오전)·비영업일이라 정상 상태(problem=null). 원소의 {@code result} 가 1 이 아니면
     * 실패다 — 10/2 실측: 틀린 키에 {@code [{"result":3, "cur_unit":null, …}]} 를 200 으로 준다. 예전 코드는 이걸
     * 'USD 없음'으로만 남겨 키 문제인지 알 수 없었다.
     */
    static EximParse parseUsdDealBasRate(JsonNode root) {
        if (root == null || !root.isArray()) return new EximParse(null, "배열이 아닌 응답");
        if (root.isEmpty()) return new EximParse(null, null);
        for (JsonNode node : root) {
            JsonNode result = node.get("result");
            String code = result == null || result.isNull() ? "" : result.asText().trim();
            if (!code.isEmpty() && !"1".equals(code)) {
                return new EximParse(null, "result=" + code + " (1 이 아니면 실패 — 인증키·일일 호출 한도 등, 수출입은행 Open API 안내 참조)");
            }
            if (!"USD".equals(node.path("cur_unit").asText(""))) continue;
            String v = node.path("deal_bas_r").asText("").replace(",", "").trim();
            if (v.isEmpty()) return new EximParse(null, "USD 매매기준율이 비어 있음");
            try {
                return new EximParse(new BigDecimal(v), null);
            } catch (NumberFormatException e) {
                return new EximParse(null, "USD 매매기준율이 숫자가 아님: " + v);
            }
        }
        return new EximParse(null, "USD 항목 없음");
    }
}
