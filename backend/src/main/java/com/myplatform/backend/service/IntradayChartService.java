package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 당일 분봉 차트(종목상세 '1일' 보기) — KIS 당일분봉(FHKST03010200)은 호출당 앵커 시각 이전
 * <b>최근 30건(1분봉)</b>만 반환하므로, 앵커를 09:00 까지 되감으며 페이지네이션 수집 후
 * <b>5분봉으로 합성</b>해 내려준다(1분봉 ~390개는 차트 표시에 과밀).
 *
 * <p><b>§4c</b>: 장전/휴장/수집 실패로 분봉이 없으면 빈 리스트 + {@code dataAvailable=false} —
 * 그럴듯한 값 생성 없음(프론트가 "당일 분봉 없음" 안내). 시세 단일 경로(getStockPrice)와 무관한
 * 별도 차트 조회 경로(기존 VWAP·봇 스캘핑과 같은 분봉 API 소비자).
 *
 * <p>KIS 호출 부담: 풀 세션 기준 최대 {@link #MAX_PAGES}콜(rate limiter 경유) — 종목·2분 캐시로
 * 반복 조회 흡수.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntradayChartService {

    /** 페이지네이션 상한 — 09:00~20:00 = 660분 / 30건 = 22 + 여유. 무한루프 백스톱. */
    static final int MAX_PAGES = 25;
    /** 합성 봉 간격(분). */
    static final int AGG_MINUTES = 5;
    private static final long CACHE_TTL_MS = 120_000L;
    static final LocalTime SESSION_START = LocalTime.of(9, 0);
    private static final LocalTime SESSION_END = LocalTime.of(20, 0);   // NXT 연장 — 분봉 API 가드와 동일

    private final KoreaInvestmentService kisService;

    private record CacheEntry(long fetchedAt, IntradayChartDto dto) {}
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 1분봉 원자료(파싱 결과). time = 봉 시각(stck_cntg_hour). */
    record MinuteBar(LocalTime time, BigDecimal open, BigDecimal high, BigDecimal low,
                     BigDecimal close, BigDecimal volume) {}

    /** 프론트 ChartData 규약 동형(candles 최신→과거) — date 는 "HH:mm" 봉 구간 시작. */
    public record IntradayCandle(String date, BigDecimal open, BigDecimal high,
                                 BigDecimal low, BigDecimal close) {}
    public record IntradayVolume(BigDecimal volume) {}
    public record IntradayChartDto(List<IntradayCandle> candles, List<IntradayVolume> volumes,
                                   boolean dataAvailable) {}

    /** 당일 5분봉 차트 — 종목·2분 캐시. 미가용이면 빈 DTO(§4c). */
    public IntradayChartDto getIntradayCandles(String stockCode) {
        CacheEntry entry = cache.get(stockCode);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.fetchedAt() < CACHE_TTL_MS) {
            return entry.dto();
        }
        IntradayChartDto dto = toDto(aggregateToBuckets(fetchDayMinuteBars(stockCode), AGG_MINUTES));
        cache.put(stockCode, new CacheEntry(now, dto));
        return dto;
    }

    /** 당일 1분봉 전체 수집 — 앵커를 09:00 까지 되감는 페이지네이션. 과거→최신 정렬 반환. */
    private List<MinuteBar> fetchDayMinuteBars(String stockCode) {
        if (!kisService.isConfigured()) return List.of();
        TreeMap<LocalTime, MinuteBar> byTime = new TreeMap<>();
        LocalTime nowTime = LocalTime.now();
        LocalTime anchor = nowTime.isAfter(SESSION_END) ? SESSION_END : nowTime;
        if (anchor.isBefore(SESSION_START)) return List.of();   // 장전 — 당일 분봉 없음

        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode resp = kisService.getStockMinuteChartAt(stockCode,
                    String.format("%02d%02d%02d", anchor.getHour(), anchor.getMinute(), 0),
                    KisApiRateLimiter.Priority.NORMAL);
            List<MinuteBar> pageBars = parseMinuteBars(resp);
            if (pageBars.isEmpty()) break;
            int before = byTime.size();
            for (MinuteBar b : pageBars) byTime.putIfAbsent(b.time(), b);
            if (byTime.size() == before) break;                 // 새 봉 없음 → 종료(무한루프 방지)
            LocalTime earliest = byTime.firstKey();
            if (!earliest.isAfter(SESSION_START)) break;        // 09:00 도달
            anchor = earliest.minusMinutes(1);
        }
        return new ArrayList<>(byTime.values());
    }

    /**
     * KIS 분봉 응답 파싱 — 순수 함수(테스트 대상). rt_cd≠0/결측/비정상 봉은 건너뛴다.
     * output2 필드: stck_cntg_hour(HHMMSS)·stck_oprc·stck_hgpr·stck_lwpr·stck_prpr(종가)·cntg_vol.
     */
    static List<MinuteBar> parseMinuteBars(JsonNode resp) {
        List<MinuteBar> out = new ArrayList<>();
        if (resp == null || !"0".equals(resp.path("rt_cd").asText(""))) return out;
        JsonNode output2 = resp.get("output2");
        if (output2 == null || !output2.isArray()) return out;
        for (JsonNode row : output2) {
            LocalTime time = parseHhmmss(row.path("stck_cntg_hour").asText(""));
            BigDecimal open = dec(row, "stck_oprc");
            BigDecimal high = dec(row, "stck_hgpr");
            BigDecimal low = dec(row, "stck_lwpr");
            BigDecimal close = dec(row, "stck_prpr");
            BigDecimal vol = dec(row, "cntg_vol");
            if (time == null || open == null || high == null || low == null || close == null
                    || close.signum() <= 0) {
                continue;
            }
            out.add(new MinuteBar(time, open, high, low, close, vol != null ? vol : BigDecimal.ZERO));
        }
        return out;
    }

    /**
     * 1분봉(과거→최신) → N분봉 합성 — 순수 함수(테스트 대상).
     * 버킷 = 분을 N 배수로 내림(09:00~09:04 → 09:00). open=첫 봉, close=마지막 봉, high/low=극값, vol=합.
     */
    static List<MinuteBar> aggregateToBuckets(List<MinuteBar> ascending, int minutes) {
        if (ascending == null || ascending.isEmpty()) return List.of();
        LinkedHashMap<LocalTime, MinuteBar> buckets = new LinkedHashMap<>();
        for (MinuteBar b : ascending) {
            LocalTime key = b.time().withMinute(b.time().getMinute() / minutes * minutes).withSecond(0);
            MinuteBar cur = buckets.get(key);
            if (cur == null) {
                buckets.put(key, new MinuteBar(key, b.open(), b.high(), b.low(), b.close(), b.volume()));
            } else {
                buckets.put(key, new MinuteBar(key, cur.open(),
                        cur.high().max(b.high()), cur.low().min(b.low()),
                        b.close(), cur.volume().add(b.volume())));
            }
        }
        return new ArrayList<>(buckets.values());
    }

    /** 합성봉(과거→최신) → DTO(candles 최신→과거 — 프론트 ChartData 규약). 순수 함수. */
    static IntradayChartDto toDto(List<MinuteBar> ascending) {
        List<IntradayCandle> candles = new ArrayList<>();
        List<IntradayVolume> volumes = new ArrayList<>();
        for (int i = ascending.size() - 1; i >= 0; i--) {
            MinuteBar b = ascending.get(i);
            candles.add(new IntradayCandle(
                    String.format("%02d:%02d", b.time().getHour(), b.time().getMinute()),
                    b.open(), b.high(), b.low(), b.close()));
            volumes.add(new IntradayVolume(b.volume()));
        }
        return new IntradayChartDto(candles, volumes, !candles.isEmpty());
    }

    private static LocalTime parseHhmmss(String s) {
        if (s == null || s.length() < 4) return null;
        try {
            int hour = Integer.parseInt(s.substring(0, 2));
            int minute = Integer.parseInt(s.substring(2, 4));
            if (hour > 23 || minute > 59) return null;
            return LocalTime.of(hour, minute);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal dec(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        try {
            String text = v.asText().replace(",", "").trim();
            return text.isEmpty() ? null : new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
