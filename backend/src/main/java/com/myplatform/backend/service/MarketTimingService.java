package com.myplatform.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.dto.MarketTimingDto.*;
import com.myplatform.backend.entity.MarketDailyStatus;
import com.myplatform.backend.repository.MarketDailyStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 시장 타이밍 분석 서비스
 * - ADR (등락비율) 계산
 * - 시장 상태 판단
 * - 데이터 수집 (네이버 금융)
 */
@Service
@Slf4j
public class MarketTimingService {

    private final MarketDailyStatusRepository marketDailyStatusRepository;
    private final TelegramNotificationService telegramNotificationService;
    private final RedisCacheService redisCacheService;

    // 시장 상태 Redis L2 캐시 — 60초 폴링 부담 완화
    private static final String CACHE_MARKET_STATUS = "marketStatus";
    private static final String KEY_CURRENT = "current";
    private static final Duration TTL_MARKET_STATUS = Duration.ofMinutes(2);

    // 휴장일 가드 — MON-FRI cron 만으론 평일 공휴일에 '오늘' 행을 만든다(2026-09-28)
    private final MarketCalendarService marketCalendar;

    // 등락 종목 수 출처 — KIS 국내업종 현재지수(2026-10-02, 죽은 네이버 시세 크롤 대체). MarketBreadth 참조.
    private final KoreaInvestmentService koreaInvestmentService;

    private static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");

    public MarketTimingService(MarketDailyStatusRepository marketDailyStatusRepository,
                               TelegramNotificationService telegramNotificationService,
                               RedisCacheService redisCacheService,
                               MarketCalendarService marketCalendar,
                               KoreaInvestmentService koreaInvestmentService) {
        this.marketDailyStatusRepository = marketDailyStatusRepository;
        this.telegramNotificationService = telegramNotificationService;
        this.redisCacheService = redisCacheService;
        this.marketCalendar = marketCalendar;
        this.koreaInvestmentService = koreaInvestmentService;
    }

    // ADR 기준값
    // ADR 임계점 — 120 이상 과열, 80 이하 공포. NORMAL 구간은 두 임계 사이.
    private static final BigDecimal ADR_OVERHEATED = new BigDecimal("120");
    private static final BigDecimal ADR_OVERSOLD = new BigDecimal("80");
    private static final BigDecimal ADR_EXTREME_FEAR = new BigDecimal("60");

    private static final int ADR_PERIOD = 20;  // ADR 계산 기간

    // === 네이버 모바일 API (지수 폴백) ===
    private static final String NAVER_INDEX_API = "https://m.stock.naver.com/api/index/%s/basic";
    private static final String NAVER_INDEX_REFERER = "https://m.stock.naver.com/";
    private static final String NAVER_INDEX_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final RestTemplate restTemplate = createRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static RestTemplate createRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    /**
     * 서버 시작 시 오늘 ADR 데이터가 없으면 자동 수집
     * - 45초 지연으로 다른 초기화 작업과 리소스 경합 방지
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void initializeDataIfEmpty() {
        try {
            Thread.sleep(45000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        initializeIfEmptyNow(LocalDate.now());
    }

    /** {@link #initializeDataIfEmpty} 본체 — 45초 지연 없이 테스트하려고 분리. */
    void initializeIfEmptyNow(LocalDate today) {
        // 휴장일(주말·평일 공휴일)이면 수집하지 않는다 — collectMarketData 는 '오늘' 날짜로 저장한다.
        // 예전엔 주말에 금요일 행 유무를 보고 수집해 그 값이 주말 날짜 행으로 남을 수 있었고,
        // 평일 공휴일은 아예 못 걸렀다(2026-09-28).
        if (marketCalendar.isMarketClosed(today)) {
            log.info("휴장일 — ADR 시장 데이터 초기 수집 스킵 (날짜: {})", today);
            return;
        }

        // 오늘 데이터가 없으면 수집
        boolean kospiExists = marketDailyStatusRepository.findByMarketTypeAndTradeDate("KOSPI", today).isPresent();
        boolean kosdaqExists = marketDailyStatusRepository.findByMarketTypeAndTradeDate("KOSDAQ", today).isPresent();

        if (!kospiExists || !kosdaqExists) {
            // 장중 부팅이면 수집하지 않는다 — 등락 종목 수는 장 마감 확정치만 쓴다(MarketBreadth.COUNTS_SETTLED_AT).
            // 저녁 배포처럼 마감 뒤 부팅이면 여기서 그날 값을 채운다.
            if (!MarketBreadth.countsSettled(java.time.LocalTime.now(KST))) {
                log.info("ADR 시장 데이터 — 장 마감 전 부팅이라 수집하지 않는다(16:30 크론이 확정치를 쓴다, 날짜: {})", today);
                return;
            }
            log.info("ADR 시장 데이터가 없습니다. 초기 데이터 수집을 시작합니다... (날짜: {})", today);
            try {
                collectMarketData();
                log.info("ADR 시장 데이터 초기 수집 완료");
            } catch (Exception e) {
                log.warn("ADR 시장 데이터 초기 수집 실패 (나중에 스케줄러가 재시도): {}", e.getMessage());
            }
        } else {
            log.info("ADR 시장 데이터 존재 확인됨 (날짜: {})", today);
        }
    }

    /**
     * 현재 시장 타이밍 분석 — Redis L2 캐시 우선 (TTL 2분).
     * 워머({@code MarketCacheWarmerService.warmMarketStatus})가 60초 마다 직접 채워두므로
     * 프론트 60초 폴링은 대부분 캐시 hit. miss 시 즉시 계산 후 반환(저장하지 않음 — 워머가 마스터).
     */
    public MarketTimingDto getCurrentMarketTiming() {
        MarketTimingDto cached = redisCacheService.get(
                CACHE_MARKET_STATUS, KEY_CURRENT,
                new TypeReference<MarketTimingDto>() {});
        if (cached != null) {
            return cached;
        }
        return computeCurrentMarketTiming();
    }

    /**
     * 워머 전용 — 외부 소스에서 새로 계산 후 Redis 갱신.
     */
    public void refreshMarketTimingToCache() {
        try {
            MarketTimingDto fresh = computeCurrentMarketTiming();
            if (fresh != null) {
                redisCacheService.put(CACHE_MARKET_STATUS, KEY_CURRENT, fresh, TTL_MARKET_STATUS);
            }
        } catch (Exception e) {
            log.warn("[MarketTiming] 캐시 갱신 실패: {}", e.getMessage());
        }
    }

    /**
     * 분당 1회 도는 경로에서 <b>같은 경고를 반복해 찍지 않기 위한</b> 직전 상태.
     *
     * <p>반복 로그는 정보가 아니라 잡음이다 — 사람이 읽지 않게 되고, 사고 조사 때
     * 다른 로그를 뒤로 밀어낸다(2026-08-31 종목상태 진단에서 실제로 겪음).
     * <b>경보를 끄는 게 아니라 상태가 바뀐 순간만 남긴다.</b>
     */
    private final AtomicReference<String> lastOverrideKey = new AtomicReference<>("");
    private final AtomicReference<String> lastStaleWarnKey = new AtomicReference<>("");

    /** 직전 값과 다르면 true(그리고 현재 값을 기억). 판정이 아니라 로그 억제 전용. */
    private static boolean changedSinceLast(AtomicReference<String> holder, String current) {
        return !current.equals(holder.getAndSet(current));
    }

    private MarketTimingDto computeCurrentMarketTiming() {
        // 캐시 워머가 분당 1회 호출한다 — 정상 동작 로그는 DEBUG (2026-08-31 로그 잡음 정리).
        log.debug("시장 타이밍 분석 시작");

        // 최신 데이터 조회
        List<MarketDailyStatus> latestData = marketDailyStatusRepository.findLatestAll();

        if (latestData.isEmpty()) {
            log.warn("시장 데이터가 없습니다. 먼저 데이터를 수집해주세요.");
            return createEmptyTiming();
        }

        LocalDate analysisDate = latestData.get(0).getTradeDate();

        // ★ Freshness check: 데이터 경과일 계산
        long dataAgeDays = java.time.temporal.ChronoUnit.DAYS.between(analysisDate, LocalDate.now());
        Integer dataAge = null;
        if (dataAgeDays > 0) {
            log.debug("DB 최신 데이터가 과거임 ({}), {}일 전 데이터, 실시간 지수로 보충 예정", analysisDate, dataAgeDays);
            dataAge = (int) dataAgeDays;
            // 경보 자체는 유지하되 분당 반복은 없앤다 — 같은 경고가 하루 1,400줄 쌓이면
            // 사람이 읽지 않게 되고, 정작 다른 로그를 뒤로 밀어낸다(2026-08-31).
            if (dataAgeDays >= 2 && changedSinceLast(lastStaleWarnKey, analysisDate + "/" + dataAgeDays)) {
                log.warn("[MarketTiming] ⚠ 시장 데이터 오래됨! 최신 거래일: {} ({}일 전) - ADR 분석 신뢰도 저하", analysisDate, dataAgeDays);
            }
        } else {
            lastStaleWarnKey.set("");   // 데이터가 최신으로 돌아오면 다음 노후는 다시 알린다
        }

        // 코스피/코스닥 데이터 분리
        MarketStatusDto kospiStatus = null;
        MarketStatusDto kosdaqStatus = null;

        for (MarketDailyStatus data : latestData) {
            MarketStatusDto status = convertToStatusDto(data);
            if ("KOSPI".equals(data.getMarketType())) {
                kospiStatus = status;
            } else if ("KOSDAQ".equals(data.getMarketType())) {
                kosdaqStatus = status;
            }
        }

        // 지수/등락률이 없으면 실시간 네이버 API로 보충
        kospiStatus = refreshIndexIfMissing(kospiStatus, "KOSPI");
        kosdaqStatus = refreshIndexIfMissing(kosdaqStatus, "KOSDAQ");

        // ADR 은 저장된 adr20 을 믿지 않고 매번 등락 수에서 다시 계산한다(2026-10-02) — 크롤 사망 기간에 저장된 adr20 은
        // 0 행이 섞인 창으로 계산돼 "3주 전 6일치"가 정상값처럼 남아 있다. 창 안 유효일이 부족하면 null(판단 보류).
        // (장중 등락비 실시간 보충은 죽은 네이버 크롤이었다 — 분당 헛요청만 남겨 제거. 당일 등락비는 16:30 확정치로 채운다.)
        applyAdr(kospiStatus, "KOSPI");
        applyAdr(kosdaqStatus, "KOSDAQ");

        // 종합 ADR 계산
        BigDecimal combinedAdr = calculateCombinedAdr(kospiStatus, kosdaqStatus);
        MarketCondition overallCondition = determineCondition(combinedAdr);

        // 진단 및 전략 생성
        String diagnosis = generateDiagnosis(kospiStatus, kosdaqStatus, combinedAdr);
        String strategy = overallCondition != null ? overallCondition.getSuggestion()
                : "시장 폭 데이터가 다시 쌓일 때까지 ADR 기반 판단은 쓰지 않습니다.";

        // ★ 당일 급락/폭락 오버라이드: ADR이 정상이더라도 당일 등락률이 크면 상태 보정
        BigDecimal kospiRate = (kospiStatus != null) ? kospiStatus.getIndexChangeRate() : null;
        BigDecimal kosdaqRate = (kosdaqStatus != null) ? kosdaqStatus.getIndexChangeRate() : null;
        BigDecimal worstRate = null;
        if (kospiRate != null && kosdaqRate != null) {
            worstRate = kospiRate.min(kosdaqRate);
        } else if (kospiRate != null) {
            worstRate = kospiRate;
        } else if (kosdaqRate != null) {
            worstRate = kosdaqRate;
        }

        boolean crashOverride = false;
        boolean dropOverride = false;
        StringBuilder overrideReasons = new StringBuilder();

        // -3% 이하: 폭락/패닉
        if (kospiRate != null && kospiRate.compareTo(new BigDecimal("-3.0")) <= 0) {
            crashOverride = true;
            overrideReasons.append(String.format("KOSPI %+.2f%% 폭락", kospiRate));
        }
        if (kosdaqRate != null && kosdaqRate.compareTo(new BigDecimal("-3.0")) <= 0) {
            if (crashOverride) overrideReasons.append(" + ");
            crashOverride = true;
            overrideReasons.append(String.format("KOSDAQ %+.2f%% 폭락", kosdaqRate));
        }

        // -2% 이하 (양쪽 모두 또는 한쪽 -2.5% 이하): 급락 → 침체 이상으로 보정
        if (!crashOverride && worstRate != null) {
            boolean bothDown2 = kospiRate != null && kosdaqRate != null
                    && kospiRate.compareTo(new BigDecimal("-2.0")) <= 0
                    && kosdaqRate.compareTo(new BigDecimal("-2.0")) <= 0;
            boolean oneDown25 = worstRate.compareTo(new BigDecimal("-2.5")) <= 0;

            if (bothDown2 || oneDown25) {
                dropOverride = true;
                if (kospiRate != null && kospiRate.compareTo(new BigDecimal("-2.0")) <= 0) {
                    overrideReasons.append(String.format("KOSPI %+.2f%%", kospiRate));
                }
                if (kosdaqRate != null && kosdaqRate.compareTo(new BigDecimal("-2.0")) <= 0) {
                    if (overrideReasons.length() > 0) overrideReasons.append(" + ");
                    overrideReasons.append(String.format("KOSDAQ %+.2f%%", kosdaqRate));
                }
            }
        }

        if (crashOverride) {
            overallCondition = MarketCondition.CRASH;
            diagnosis = String.format("폭락/패닉 — %s. 관망 및 하방 리스크 관리 필수.", overrideReasons);
            strategy = MarketCondition.CRASH.getSuggestion();
            if (changedSinceLast(lastOverrideKey, "CRASH|" + overrideReasons)) {
                log.warn("[시장 타이밍] 폭락 오버라이드 발동: {}", overrideReasons);
            }
        } else if (dropOverride) {
            if (overallCondition == null) {
                // ADR 판단 보류 중 — 보정할 ADR 이 없다. 상태는 모름 그대로 두고 사실(당일 낙폭)만 말한다.
                // 예전엔 아래 getSuggestion() 이 null 에서 NPE → /market/status 500·모닝브리핑 '조회 실패'(2026-10-03).
                diagnosis = String.format("당일 급락 — %s. 시장 폭(ADR)은 판단 보류.", overrideReasons);
            } else {
                // ADR이 정상이더라도 당일 급락 시 최소 OVERSOLD로 보정
                if (overallCondition == MarketCondition.NORMAL || overallCondition == MarketCondition.OVERHEATED) {
                    overallCondition = MarketCondition.OVERSOLD;
                }
                diagnosis = String.format("당일 급락 — %s. %s", overrideReasons, overallCondition.getSuggestion());
                strategy = overallCondition.getSuggestion();
            }
            if (changedSinceLast(lastOverrideKey, "DROP|" + overrideReasons + "|" + overallCondition)) {
                log.warn("[시장 타이밍] 급락 보정 발동: {} → {}", overrideReasons, overallCondition);
            }
        } else {
            lastOverrideKey.set("");    // 오버라이드 해제 — 다음 발동은 다시 알린다
        }

        return MarketTimingDto.builder()
                .analysisDate(analysisDate)
                .kospi(kospiStatus)
                .kosdaq(kosdaqStatus)
                .combinedAdr(combinedAdr)
                .overallCondition(overallCondition)
                .diagnosis(diagnosis)
                .strategy(strategy)
                .dataAge(dataAge)
                .build();
    }

    /**
     * ADR 히스토리 조회 (차트용)
     */
    public List<AdrHistoryDto> getAdrHistory(int days) {
        List<AdrHistoryDto> history = new ArrayList<>();

        LocalDate endDate = LocalDate.now();
        // 각 날짜의 ADR 창(20행)까지 담으려고 넉넉히 — 저장된 adr20 대신 등락 수에서 다시 계산한다(MarketBreadth).
        LocalDate startDate = endDate.minusDays(days + ADR_PERIOD * 2L + 20);

        List<MarketDailyStatus> kospiData = marketDailyStatusRepository
                .findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc("KOSPI", startDate, endDate);
        List<MarketDailyStatus> kosdaqData = marketDailyStatusRepository
                .findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc("KOSDAQ", startDate, endDate);

        // 날짜별로 매칭
        for (int i = 0; i < Math.min(days, kospiData.size()); i++) {
            MarketDailyStatus kospi = kospiData.get(i);
            LocalDate date = kospi.getTradeDate();

            BigDecimal kospiAdr = MarketBreadth.adr(kospiData.subList(i, kospiData.size())).value();
            BigDecimal kosdaqAdr = null;

            // 코스닥 데이터 찾기
            for (int j = 0; j < kosdaqData.size(); j++) {
                if (kosdaqData.get(j).getTradeDate().equals(date)) {
                    kosdaqAdr = MarketBreadth.adr(kosdaqData.subList(j, kosdaqData.size())).value();
                    break;
                }
            }

            BigDecimal combined = calculateCombinedAdrFromValues(kospiAdr, kosdaqAdr);

            history.add(AdrHistoryDto.builder()
                    .date(date)
                    .kospiAdr(kospiAdr)
                    .kosdaqAdr(kosdaqAdr)
                    .combinedAdr(combined)
                    .build());
        }

        return history;
    }

    /**
     * 매일 장 마감 후 ADR 시장 지표 자동 수집 (평일 16:30)
     * - 15:30 장 마감 후 1시간 여유를 두고 수집
     * - 초 필드 20 — 2026-10-02 부터 KIS 를 부르므로 :00 을 피한다(잔고 모니터·워머가 :00 에 몰린다, §4b)
     */
    @Scheduled(scheduler = "cacheScheduler", cron = "20 30 16 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void scheduledMarketDataCollection() {
        // 휴장일 가드 — collectMarketData 는 '오늘' 날짜로 저장한다. 평일 공휴일에 돌면 직전 거래일 값이
        // 휴장일 행으로 남고, ADR 은 "최근 20행"이라 그 값이 창 안에 중복으로 세어진다(2026-09-24·25 실측).
        if (marketCalendar.isMarketClosed()) {
            log.info("[배치] 16:30 휴장일 — ADR 시장 지표 수집 스킵");
            return;
        }
        log.info("=== ADR 시장 지표 자동 수집 시작 (16:30) ===");
        try {
            collectMarketData();
            log.info("=== ADR 시장 지표 자동 수집 완료 ===");
        } catch (Exception e) {
            log.error("ADR 시장 지표 자동 수집 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 시장 데이터 수집 — 등락 종목 수는 KIS 국내업종 현재지수(2026-10-02), 지수 종가·등락률은 네이버 모바일 지수 API.
     *
     * <p>등락 수는 장 마감 확정치만 쓴다 — 15:40 전이면 아무것도 저장하지 않고 예외로 알린다(수동 버튼이 "완료"로 속지 않게).
     * 한 시장이라도 조회에 실패하면 그 시장 행은 만들지도 고치지도 않고, 마지막에 예외로 실패를 알린다(§4c — 0 으로 저장 금지).
     * 예전 네이버 시세 크롤은 실패를 0 으로 저장해 9/11~10/2 ADR 이 3주 전 값으로 굳었다({@link MarketBreadth}).
     */
    @Transactional
    public void collectMarketData() {
        collectMarketData(java.time.LocalTime.now(KST));
    }

    /** 시각을 받는 본체 — 장 마감 전/후 동작을 테스트하려고 분리. */
    void collectMarketData(java.time.LocalTime now) {
        if (!MarketBreadth.countsSettled(now)) {
            throw new IllegalStateException("장 마감(" + MarketBreadth.COUNTS_SETTLED_AT
                    + ") 전에는 등락 종목 수를 저장하지 않습니다 — 장중 잠정치입니다. 16:30 에 자동 수집됩니다.");
        }
        log.info("시장 데이터 수집 시작");
        List<String> failed = new ArrayList<>();
        if (!collectMarketDataFor("KOSPI", "0001")) failed.add("KOSPI");
        if (!collectMarketDataFor("KOSDAQ", "1001")) failed.add("KOSDAQ");
        if (!failed.isEmpty()) {
            throw new IllegalStateException("등락 종목 수 조회 실패: " + failed + " — 저장하지 않았다(0 으로 위장하지 않음)");
        }
        log.info("시장 데이터 수집 완료");
    }

    /**
     * 한 시장의 하루 행 — 등락 수를 못 받으면 false(행을 만들지도 고치지도 않는다).
     *
     * @param indexCode KIS 업종코드 — 코스피 0001, 코스닥 1001
     */
    private boolean collectMarketDataFor(String marketType, String indexCode) {
        MarketBreadth.Counts counts;
        try {
            counts = MarketBreadth.fromKisIndexPrice(koreaInvestmentService.getIndexPrice(indexCode));
        } catch (Exception e) {
            log.warn("[시장 폭] {} KIS 등락 종목 수 조회 예외: {}", marketType, e.getMessage());
            counts = null;
        }
        if (counts == null) {
            log.warn("[시장 폭] {} 등락 종목 수 미확보 — 행을 만들지도 고치지도 않는다(0 으로 저장하지 않음, §4c)", marketType);
            return false;
        }

        // 지수 정보 수집 (네이버 모바일 API → HTML 폴백). 못 받으면 그 칸만 비운다.
        BigDecimal[] indexInfo = crawlIndexInfo(marketType);

        LocalDate today = LocalDate.now(KST);
        MarketDailyStatus status = marketDailyStatusRepository
                .findByMarketTypeAndTradeDate(marketType, today)
                .orElseGet(() -> {
                    MarketDailyStatus s = new MarketDailyStatus();
                    s.setMarketType(marketType);
                    s.setTradeDate(today);
                    return s;
                });

        status.setAdvancingCount(counts.advancing());
        status.setDecliningCount(counts.declining());
        status.setUnchangedCount(counts.unchanged());
        status.setUpperLimitCount(counts.upperLimit());
        status.setLowerLimitCount(counts.lowerLimit());
        status.setTotalCount(counts.total());
        if (indexInfo[0] != null) status.setIndexClose(indexInfo[0]);
        if (indexInfo[1] != null) status.setIndexChangeRate(indexInfo[1]);
        if (indexInfo[2] != null) status.setTradingValue(indexInfo[2]);
        status.setDailyRatio(counts.declining() > 0
                ? BigDecimal.valueOf(counts.advancing())
                        .divide(BigDecimal.valueOf(counts.declining()), 2, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                : null);
        marketDailyStatusRepository.save(status);

        // ADR 은 오늘 행까지 넣어 다시 계산(같은 트랜잭션이라 방금 저장한 행이 창에 들어간다)
        MarketBreadth.Adr adr = adrDetail(marketType, today);
        status.setAdr20(adr.value());
        marketDailyStatusRepository.save(status);
        log.info("{} 시장 데이터 저장 완료: 상승={}, 하락={}, 보합={}, ADR={} (유효 {}일/{})",
                marketType, counts.advancing(), counts.declining(), counts.unchanged(),
                adr.value(), adr.validDays(), adr.windowDays());
        return true;
    }

    /**
     * 지수 정보 조회 (네이버 모바일 API 우선 → HTML 크롤링 폴백)
     */
    // package-private — 단위 테스트가 네트워크 없이 바꿔 끼운다(MarketTimingBreadthCollectionTest)
    BigDecimal[] crawlIndexInfo(String marketType) {
        BigDecimal[] result = new BigDecimal[3];  // [종가, 등락률, 거래대금]

        // 1차: 네이버 모바일 API (JSON - 안정적)
        try {
            result = fetchIndexFromNaverApi(marketType);
            if (result[0] != null && result[1] != null) {
                log.debug("{} 지수 네이버 API 성공: 종가={}, 등락률={}%", marketType, result[0], result[1]);
                return result;
            }
        } catch (Exception e) {
            log.debug("{} 네이버 API 실패, HTML 폴백 시도: {}", marketType, e.getMessage());
        }

        // 2차: HTML 크롤링 폴백
        try {
            String url = "KOSPI".equals(marketType)
                    ? "https://finance.naver.com/sise/sise_index.naver?code=KOSPI"
                    : "https://finance.naver.com/sise/sise_index.naver?code=KOSDAQ";

            Document doc = Jsoup.connect(url)
                    .userAgent(NAVER_INDEX_USER_AGENT)
                    .timeout(10000)
                    .get();

            // 지수 종가
            Element indexElement = doc.selectFirst("#now_value");
            if (indexElement != null) {
                result[0] = new BigDecimal(indexElement.text().replace(",", ""));
            }

            // 등락률 - 여러 셀렉터 시도
            BigDecimal changeRate = parseChangeRateFromHtml(doc);
            if (changeRate != null) {
                result[1] = changeRate;
            }

            // 거래대금
            Element tradingElement = doc.selectFirst("em#quant");
            if (tradingElement != null) {
                String tradingText = tradingElement.text().replace(",", "").replace("억", "");
                result[2] = new BigDecimal(tradingText);
            }

            if (result[0] != null) {
                log.debug("{} 지수 HTML 크롤링 성공: 종가={}, 등락률={}", marketType, result[0], result[1]);
            }

        } catch (Exception e) {
            log.warn("{} 지수 정보 크롤링 실패: {}", marketType, e.getMessage());
        }

        return result;
    }

    /**
     * 네이버 모바일 API로 지수 정보 조회
     * API: https://m.stock.naver.com/api/index/{KOSPI|KOSDAQ}/basic
     * 응답 필드: closePrice, compareToPreviousClosePrice, fluctuationsRatio 등
     */
    // package-private — 단위 테스트가 네트워크 없이 바꿔 끼운다(MarketTimingBreadthCollectionTest)
    BigDecimal[] fetchIndexFromNaverApi(String marketType) throws Exception {
        BigDecimal[] result = new BigDecimal[3];
        String code = "KOSPI".equals(marketType) ? "KOSPI" : "KOSDAQ";

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", NAVER_INDEX_USER_AGENT);
        headers.set("Referer", NAVER_INDEX_REFERER);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        String url = String.format(NAVER_INDEX_API, code);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode root = objectMapper.readTree(response.getBody());

            // 종가 (closePrice)
            if (root.has("closePrice")) {
                String closeStr = root.get("closePrice").asText().replace(",", "");
                result[0] = new BigDecimal(closeStr);
            }

            // 등락률 (fluctuationsRatio)
            if (root.has("fluctuationsRatio")) {
                String ratioStr = root.get("fluctuationsRatio").asText().replace(",", "");
                result[1] = new BigDecimal(ratioStr);
            }

            // 거래대금 (accumulatedTradingValue - 백만원 단위 → 억원 변환)
            if (root.has("accumulatedTradingValue")) {
                try {
                    String tradingStr = root.get("accumulatedTradingValue").asText().replace(",", "");
                    BigDecimal tradingValue = new BigDecimal(tradingStr);
                    result[2] = tradingValue.divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
                } catch (Exception e) {
                    log.debug("거래대금 파싱 실패: {}", e.getMessage());
                }
            }
        }

        return result;
    }

    /**
     * HTML에서 등락률 추출 (여러 셀렉터 시도)
     */
    private BigDecimal parseChangeRateFromHtml(Document doc) {
        // 1차: #change_rate (기존)
        Element changeElement = doc.selectFirst("#change_rate");
        if (changeElement != null) {
            try {
                String rateText = changeElement.text().replace("%", "").replace("+", "").trim();
                if (!rateText.isEmpty()) {
                    BigDecimal rate = new BigDecimal(rateText);
                    // 하락 여부 확인 (상위 요소에 "ndown" 또는 "minus" 클래스)
                    Element parent = changeElement.parent();
                    if (parent != null && (parent.classNames().contains("ndown") ||
                            parent.classNames().contains("minus") ||
                            parent.html().contains("ico_down"))) {
                        rate = rate.negate();
                    }
                    return rate;
                }
            } catch (NumberFormatException e) {
                log.debug("change_rate 파싱 실패: {}", changeElement.text());
            }
        }

        // 2차: .subtit_sise 내 등락률
        Element subtitElement = doc.selectFirst(".subtit_sise .change_rate");
        if (subtitElement != null) {
            try {
                String rateText = subtitElement.text().replace("%", "").replace("+", "").trim();
                if (!rateText.isEmpty()) {
                    return new BigDecimal(rateText);
                }
            } catch (NumberFormatException e) {
                log.debug("subtit_sise 등락률 파싱 실패");
            }
        }

        // 3차: 전일대비 + 종가로 계산
        Element changeValueElement = doc.selectFirst("#change_value_01");
        Element nowValueElement = doc.selectFirst("#now_value");
        if (changeValueElement != null && nowValueElement != null) {
            try {
                String changeStr = changeValueElement.text().replace(",", "").trim();
                String nowStr = nowValueElement.text().replace(",", "").trim();
                if (!changeStr.isEmpty() && !nowStr.isEmpty()) {
                    BigDecimal change = new BigDecimal(changeStr);
                    BigDecimal now = new BigDecimal(nowStr);
                    BigDecimal prev = now.subtract(change);
                    if (prev.compareTo(BigDecimal.ZERO) > 0) {
                        return change.divide(prev, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"))
                                .setScale(2, RoundingMode.HALF_UP);
                    }
                }
            } catch (NumberFormatException e) {
                log.debug("전일대비 계산 폴백 실패");
            }
        }

        return null;
    }

    /**
     * DB에서 가져온 지수 데이터에 등락률이 없으면 실시간 네이버 API로 보충
     */
    private MarketStatusDto refreshIndexIfMissing(MarketStatusDto status, String marketType) {
        if (status == null) {
            // status 자체가 없으면 실시간으로 생성
            status = MarketStatusDto.builder()
                    .marketType(marketType)
                    .tradeDate(LocalDate.now())
                    .build();
        }

        // DB 데이터가 오늘이 아니면 무조건 갱신 (과거 데이터 방지)
        boolean isOldData = status.getTradeDate() != null && !status.getTradeDate().equals(LocalDate.now());
        // ★ 장중(09:00~15:30)에는 항상 실시간 갱신 (DB에 stale 데이터가 저장됐을 수 있음)
        LocalTime now = LocalTime.now();
        boolean isDuringMarketHours = !now.isBefore(LocalTime.of(9, 0)) && !now.isAfter(LocalTime.of(15, 30));
        boolean needsRefresh = isOldData
                || isDuringMarketHours
                || status.getIndexClose() == null
                || status.getIndexChangeRate() == null
                || status.getIndexChangeRate().compareTo(BigDecimal.ZERO) == 0;

        if (needsRefresh) {
            try {
                BigDecimal[] indexInfo = fetchIndexFromNaverApi(marketType);
                if (indexInfo[0] != null) {
                    status.setIndexClose(indexInfo[0]);
                }
                if (indexInfo[1] != null) {
                    status.setIndexChangeRate(indexInfo[1]);
                }
                if (indexInfo[2] != null) {
                    status.setTradingValue(indexInfo[2]);
                }
                // 분당 갱신되는 값이라 DEBUG. 결과는 화면·DB 에 그대로 남는다.
                log.debug("{} 지수 실시간 보충 완료: 종가={}, 등락률={}%", marketType, indexInfo[0], indexInfo[1]);
            } catch (Exception e) {
                log.debug("{} 지수 실시간 보충 실패: {}", marketType, e.getMessage());
            }
        }

        return status;
    }

    /**
     * 당일 등락비 계산·세팅 — 순수 로직 (테스트 대상). dailyRatio 만 채우고
     * ADR(20일) 기반 condition 은 보존한다. 분모(하락 종목 수) 0 이하이면 no-op.
     *
     * <p>★ condition 은 건드리지 않는다 (점검 수정 2026-06-11): 과거엔 당일 등락비를 determineCondition(ADR 임계
     * 120/80/60)에 넣어 ADR 기반 판정을 덮어썼는데, 하루짜리 등락비는 20일 ADR 보다 변동성이 훨씬 커서 평범한
     * 상승일(등락비 150)도 장중 '과열'로 오판됐다. (장중 실시간 보충은 죽은 네이버 크롤이라 2026-10-02 제거했다.)
     */
    static BigDecimal applyDailyRatio(MarketStatusDto status, int adv, int dec) {
        if (status == null || dec <= 0) {
            return null;
        }
        BigDecimal dailyRatio = BigDecimal.valueOf(adv)
                .divide(BigDecimal.valueOf(dec), 2, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        status.setDailyRatio(dailyRatio);
        return dailyRatio;
    }

    /**
     * ADR 계산 (20일 기준) — 창 안 '등락 수가 실제로 있는 날'만 합산, 그런 날이 15일 미만이면 null(판단 보류).
     * 규칙은 {@link MarketBreadth#adr} 단일 출처.
     */
    public BigDecimal calculateAdr(String marketType, LocalDate endDate) {
        return adrDetail(marketType, endDate).value();
    }

    /** ADR 과 그 근거(유효일·창 크기). 휴장·서버 다운으로 빈 날까지 담으려고 달력일 넉넉히 조회한다. */
    MarketBreadth.Adr adrDetail(String marketType, LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(ADR_PERIOD * 2L + 5);
        List<MarketDailyStatus> recentData = marketDailyStatusRepository
                .findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc(marketType, startDate, endDate);
        MarketBreadth.Adr adr = MarketBreadth.adr(recentData);
        if (adr.value() == null) {
            log.debug("{} ADR 판단 보류: 유효 {}일/{} (필요 {}일)", marketType,
                    adr.validDays(), adr.windowDays(), MarketBreadth.MIN_VALID_DAYS);
        }
        return adr;
    }

    /**
     * 시장 폭 수집 건강(관제실 규칙 ⑮ 입력) — 코스피 기준 마지막으로 등락 수가 있는 날과 ADR 창 유효일.
     *
     * @param latestCountedDate 등락 수가 실제로 있는 가장 최근 날(없으면 null)
     */
    public record BreadthHealth(LocalDate latestCountedDate, int validDays) {}

    public BreadthHealth breadthHealth(LocalDate today) {
        List<MarketDailyStatus> rows = marketDailyStatusRepository
                .findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc("KOSPI", today.minusDays(120), today);
        LocalDate latest = rows.stream()
                .filter(MarketBreadth::hasCounts)
                .map(MarketDailyStatus::getTradeDate)
                .findFirst()
                .orElse(null);
        return new BreadthHealth(latest, MarketBreadth.adr(rows).validDays());
    }

    /** 화면용 상태에 ADR·상태·유효일을 채운다(저장된 adr20 은 쓰지 않는다). */
    private void applyAdr(MarketStatusDto status, String marketType) {
        if (status == null) return;
        LocalDate end = status.getTradeDate() != null ? status.getTradeDate() : LocalDate.now(KST);
        MarketBreadth.Adr adr = adrDetail(marketType, end);
        status.setAdr20(adr.value());
        status.setCondition(determineCondition(adr.value()));
        status.setAdrValidDays(adr.validDays());
    }

    /**
     * 종합 ADR 계산
     */
    private BigDecimal calculateCombinedAdr(MarketStatusDto kospi, MarketStatusDto kosdaq) {
        if (kospi == null || kosdaq == null) {
            if (kospi != null) return kospi.getAdr20();
            if (kosdaq != null) return kosdaq.getAdr20();
            return null;
        }

        return calculateCombinedAdrFromValues(kospi.getAdr20(), kosdaq.getAdr20());
    }

    private BigDecimal calculateCombinedAdrFromValues(BigDecimal kospiAdr, BigDecimal kosdaqAdr) {
        if (kospiAdr == null && kosdaqAdr == null) return null;
        if (kospiAdr == null) return kosdaqAdr;
        if (kosdaqAdr == null) return kospiAdr;

        // 단순 평균
        return kospiAdr.add(kosdaqAdr)
                .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
    }

    /**
     * ADR 기반 시장 상태 판단
     */
    private MarketCondition determineCondition(BigDecimal adr) {
        if (adr == null) return null;

        if (adr.compareTo(ADR_OVERHEATED) >= 0) {
            return MarketCondition.OVERHEATED;
        } else if (adr.compareTo(ADR_EXTREME_FEAR) <= 0) {
            return MarketCondition.EXTREME_FEAR;
        } else if (adr.compareTo(ADR_OVERSOLD) <= 0) {
            return MarketCondition.OVERSOLD;
        } else {
            return MarketCondition.NORMAL;
        }
    }

    /**
     * 진단 문장의 등락비 조각 — 그 값의 거래일을 붙인다(2026-10-06 화면 점검). 등락비는 장 마감 확정치(16:30 저장)라 장 시작
     * 전·장중엔 직전 거래일 값인데 '당일 등락비'라 해서 오늘 값처럼 읽혔다. 거래일을 모르면 날짜를 지어내지 않는다. 순수 함수.
     */
    static String dailyRatioNote(String marketName, MarketStatusDto s) {
        if (s == null || s.getDailyRatio() == null) return "";
        String day = s.getTradeDate() == null ? "날짜 모름"
                : s.getTradeDate().format(java.time.format.DateTimeFormatter.ofPattern("MM/dd"));
        return String.format("| %s 등락비(%s): %.1f ", marketName, day, s.getDailyRatio());
    }

    /**
     * 시장 진단 메시지 생성
     */
    private String generateDiagnosis(MarketStatusDto kospi, MarketStatusDto kosdaq, BigDecimal combinedAdr) {
        StringBuilder sb = new StringBuilder();

        if (combinedAdr == null) {
            // 판단 보류 — 그럴듯한 값 대신 왜 없는지를 말한다(§4c). 오늘 탭·모닝브리핑·AI 분석이 이 문장을 그대로 쓴다.
            sb.append(MarketBreadth.insufficientMessage(
                    kospi == null ? null : kospi.getAdrValidDays(),
                    kosdaq == null ? null : kosdaq.getAdrValidDays()));
        }
        if (combinedAdr != null) {
            sb.append(String.format("종합 ADR(20일): %.1f ", combinedAdr));

            if (combinedAdr.compareTo(ADR_OVERHEATED) >= 0) {
                sb.append("- 시장이 과열 상태입니다. ");
            } else if (combinedAdr.compareTo(ADR_EXTREME_FEAR) <= 0) {
                sb.append("- 극심한 공포 구간입니다. ");
            } else if (combinedAdr.compareTo(ADR_OVERSOLD) <= 0) {
                sb.append("- 시장이 침체 구간입니다. ");
            } else {
                sb.append("- 시장이 정상 범위입니다. ");
            }
        }

        // 등락비 — 그 값의 거래일과 함께
        sb.append(dailyRatioNote("코스피", kospi));
        sb.append(dailyRatioNote("코스닥", kosdaq));

        return sb.toString().trim();
    }

    /**
     * Entity를 DTO로 변환
     */
    private MarketStatusDto convertToStatusDto(MarketDailyStatus entity) {
        // 0/0/0 행(크롤 사망 기간에 실패를 0 으로 저장한 행)은 '모름'으로 내보낸다 — 화면이 0 종목을 사실로 그리지 않게.
        boolean counted = MarketBreadth.hasCounts(entity);
        return MarketStatusDto.builder()
                .marketType(entity.getMarketType())
                .tradeDate(entity.getTradeDate())
                .advancingCount(counted ? entity.getAdvancingCount() : null)
                .decliningCount(counted ? entity.getDecliningCount() : null)
                .unchangedCount(counted ? entity.getUnchangedCount() : null)
                .upperLimitCount(counted ? entity.getUpperLimitCount() : null)
                .lowerLimitCount(counted ? entity.getLowerLimitCount() : null)
                .dailyRatio(counted ? entity.getDailyRatio() : null)
                .adr20(entity.getAdr20())
                .condition(determineCondition(entity.getAdr20()))
                .indexClose(entity.getIndexClose())
                .indexChangeRate(entity.getIndexChangeRate())
                .tradingValue(entity.getTradingValue())
                .build();
    }

    /**
     * 빈 타이밍 데이터 생성
     */
    private MarketTimingDto createEmptyTiming() {
        return MarketTimingDto.builder()
                .analysisDate(LocalDate.now())
                .diagnosis("시장 데이터가 없습니다. 먼저 데이터를 수집해주세요.")
                .strategy("데이터 수집 후 분석을 이용해주세요.")
                .build();
    }

    // ========== 텔레그램 알림 연동 메서드 ==========

    /**
     * 시장 상태 알림 발송
     * - 시장이 과열 또는 공포 구간일 때 알림
     * - 스케줄러에서 데이터 수집 후 호출 권장
     *
     * @param onlyExtreme true면 과열/극심한공포일 때만 알림, false면 항상 알림
     * @return 알림 발송 여부
     */
    public boolean sendMarketStatusAlert(boolean onlyExtreme) {
        log.info("시장 상태 알림 발송 시작 - onlyExtreme: {}", onlyExtreme);

        MarketTimingDto timing = getCurrentMarketTiming();

        if (timing.getCombinedAdr() == null || timing.getOverallCondition() == null) {
            log.info("시장 데이터 부족으로 알림 발송 생략");
            return false;
        }

        MarketCondition condition = timing.getOverallCondition();

        // 극단적 상황만 알림하는 경우
        if (onlyExtreme) {
            if (condition != MarketCondition.OVERHEATED && condition != MarketCondition.EXTREME_FEAR) {
                log.info("시장 상태가 정상 범위이므로 알림 발송 생략 - condition: {}", condition);
                return false;
            }
        }

        telegramNotificationService.sendMarketStatusAlert(
                condition.name(),
                timing.getCombinedAdr(),
                timing.getDiagnosis()
        );

        log.info("시장 상태 알림 발송 완료 - condition: {}, ADR: {}", condition, timing.getCombinedAdr());
        return true;
    }

    /**
     * 데이터 수집 및 알림 발송 (통합)
     * - 스케줄러에서 사용하기 좋은 통합 메서드
     */
    @Transactional
    public void collectAndNotify() {
        collectMarketData();
        sendMarketStatusAlert(true);  // 극단적 상황만 알림
    }
}
