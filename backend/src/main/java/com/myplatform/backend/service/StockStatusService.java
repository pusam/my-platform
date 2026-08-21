package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.core.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KRX 상장종목 상태 관리 서비스
 * - 매일 08:30 KRX에서 KOSPI+KOSDAQ 상장종목 목록 동기화
 * - 거래정지/상장폐지 종목 자동 감지
 * - SectorStockConfig의 종목 필터링에 사용
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StockStatusService {

    // ⚠ OTP 2단계 필수(2026-08-21 전환): getJsonData 단발 POST(Referer 만)는 KRX 가 세션 거부로
    // 본문 "LOGOUT" 을 돌려주는 死패턴이다 — SHORT_SELLING_DEAD_FEED_DIAGNOSIS §2 에서 실측 확정
    // (날짜·bld 무관 구조적 거부). 이 서비스가 그 패턴이라 activeStockCodes 가 영구 빈 집합
    // = isActive/filterActiveStocks 전면 fail-open(거래정지·상폐 제외 게이트 무력) 상태였다.
    // MarketTimingService.getKrxOtp 의 살아있는 2단계 패턴(getOtp → code=otp)으로 전환.
    private static final String KRX_OTP_URL = "http://data.krx.co.kr/comm/bldAttendant/getOtp.cmd";
    private static final String KRX_DATA_URL = "http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd";
    private static final String KRX_REFERER = "http://data.krx.co.kr/contents/MDC/MDI/mdiLoader/index.cmd?menuId=MDC0201";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 전종목 시세(시장구분 mktId 파라미터로 KOSPI/KOSDAQ 선택) — bld 는 시장 공통. */
    private static final String BLD_STOCK_LIST = "dbms/MDC/STAT/standard/MDCSTAT01501";

    private final RestTemplate restTemplate;
    private final TelegramNotificationService telegramService;
    private final com.myplatform.backend.config.SectorStockConfig sectorStockConfig;
    private final MarketCalendarService marketCalendar;

    // 정상 거래 가능 종목 코드 셋 (KRX 기준)
    private final Set<String> activeStockCodes = ConcurrentHashMap.newKeySet();

    // 거래정지/상폐 감지된 종목 (코드 → 사유)
    private final ConcurrentHashMap<String, String> suspendedStocks = new ConcurrentHashMap<>();

    private volatile LocalDateTime lastSyncTime = null;

    /**
     * 종목이 정상 거래 가능한지 확인
     * - KRX 동기화 전이면 true 반환 (안전 모드)
     * - 동기화 후에는 KRX 목록에 있는 종목만 true
     */
    public boolean isActive(String stockCode) {
        if (activeStockCodes.isEmpty()) return true; // 동기화 전 안전 모드
        return activeStockCodes.contains(stockCode);
    }

    /**
     * 종목 목록에서 거래정지/상폐 종목 필터링
     */
    public List<String> filterActiveStocks(List<String> stockCodes) {
        if (activeStockCodes.isEmpty()) return stockCodes; // 동기화 전 안전 모드
        return stockCodes.stream()
                .filter(activeStockCodes::contains)
                .toList();
    }

    /**
     * 거래정지/상폐 감지된 종목 맵 반환
     */
    public Map<String, String> getSuspendedStocks() {
        return Collections.unmodifiableMap(suspendedStocks);
    }

    public LocalDateTime getLastSyncTime() {
        return lastSyncTime;
    }

    /**
     * 서버 시작 시 초기 동기화 (비동기)
     */
    @PostConstruct
    public void init() {
        // PostConstruct에서는 간단 로그만, 실제 동기화는 스케줄러 or 수동 호출
        log.info("[종목상태] StockStatusService 초기화 완료 - 08:30 자동 동기화 예정");
    }

    /**
     * 매일 08:30 KRX 상장종목 동기화 (장 시작 전)
     * + 섹터 종목 중 거래정지/상폐 감지 → 텔레그램 알림
     */
    /** 동기화 노후 경보 임계 — 이보다 오래면 게이트 데이터가 죽은 것으로 보고 경보. */
    private static final java.time.Duration SYNC_STALE_ALERT = java.time.Duration.ofHours(48);

    @Scheduled(scheduler = "batchScheduler", cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledSync() {
        syncFromKrx();
        // 경보 조건은 isEmpty 가 아니라 lastSyncTime 노후(2026-08-21 리뷰 B-6) — syncFromKrx 는 실패 시
        // 기존 목록을 유지하므로, 한 번이라도 성공한 뒤 피드가 죽으면 목록은 영원히 비지 않는다.
        // isEmpty 로만 경보하면 그 가장 흔한 시나리오(운영 중 사망)에서 경보가 영영 안 울리고,
        // 신규 상장은 전부 fail-closed·신규 거래정지는 전부 통과인 채 몇 주가 조용히 지나간다.
        boolean stale = lastSyncTime == null
                || lastSyncTime.isBefore(DateTimeUtil.kstNow().minus(SYNC_STALE_ALERT));
        if (!stale) {
            detectSuspendedInSectors(new ArrayList<>(sectorStockConfig.getAllStockCodes()));
            return;
        }
        String since = lastSyncTime == null ? "부팅 후 성공 0회"
                : "마지막 성공 " + lastSyncTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        log.error("[종목상태] KRX 동기화 노후({}) — 거래정지/상폐 제외 게이트가 옛 목록/무필터로 동작 중", since);
        try {
            telegramService.sendRisk("<b>⚠️ 종목상태 동기화 노후</b>\n\n"
                    + "KRX 상장종목 목록 동기화: " + since + "\n"
                    + "거래정지/상폐 제외 게이트(추천·발굴·봇)가 옛 목록 또는 무필터(fail-open)로 동작 중.\n\n"
                    + "━━━━━━━━━━━━━━━━\n🤖 MyPlatform 종목 상태 알림");
        } catch (Exception ignore) { /* 알림은 best-effort */ }
    }

    /**
     * KRX에서 상장종목 목록 동기화
     */
    public void syncFromKrx() {
        log.info("[종목상태] KRX 상장종목 동기화 시작...");
        Set<String> newActiveCodes = ConcurrentHashMap.newKeySet();

        try {
            // KOSPI 종목 수집
            Set<String> kospiCodes = fetchKrxStockList("STK");
            log.info("[종목상태] KOSPI 종목 {}건 수집", kospiCodes.size());

            // 1초 딜레이 (KRX 차단 방지)
            Thread.sleep(1000);

            // KOSDAQ 종목 수집
            Set<String> kosdaqCodes = fetchKrxStockList("KSQ");
            log.info("[종목상태] KOSDAQ 종목 {}건 수집", kosdaqCodes.size());

            // ⚠ 시장별 게이트(2026-08-21 리뷰 B-5) — 아래 합산 <100 게이트는 "한 시장만 실패"를 원리적으로
            // 못 잡는다: KOSPI ~950건 성공 + KOSDAQ 0건이어도 950>100 으로 통과 → activeStockCodes 가
            // KOSPI 전용이 되어 KOSDAQ ~1,700종목 전체가 무음 fail-CLOSED(발굴·봇·대조군 유니버스에서 소멸)
            // + detectSuspendedInSectors 가 KOSDAQ 섹터 종목을 전부 "상폐 의심"으로 오탐. 한쪽이라도
            // 비면 전체 취소(기존 목록 유지)가 안전.
            if (kospiCodes.isEmpty() || kosdaqCodes.isEmpty()) {
                log.error("[종목상태] 시장 단위 수집 실패(KOSPI {}건 / KOSDAQ {}건) — 동기화 취소(기존 목록 유지)",
                        kospiCodes.size(), kosdaqCodes.size());
                return;
            }
            newActiveCodes.addAll(kospiCodes);
            newActiveCodes.addAll(kosdaqCodes);

        } catch (Exception e) {
            log.error("[종목상태] KRX 동기화 실패: {}", e.getMessage());
            return; // 실패 시 기존 데이터 유지
        }

        if (newActiveCodes.size() < 100) {
            log.warn("[종목상태] KRX 응답 종목 수 {}건 — 비정상적으로 적어 동기화 취소", newActiveCodes.size());
            return;
        }

        activeStockCodes.clear();
        activeStockCodes.addAll(newActiveCodes);
        lastSyncTime = DateTimeUtil.kstNow();
        log.info("[종목상태] KRX 동기화 완료 — 총 {}건 ({})", activeStockCodes.size(), lastSyncTime);
    }

    /**
     * SectorStockConfig의 종목 중 KRX에 없는 종목 감지
     * syncFromKrx() 이후 호출
     */
    public List<String> detectSuspendedInSectors(List<String> sectorStockCodes) {
        if (activeStockCodes.isEmpty()) return Collections.emptyList();

        suspendedStocks.clear();
        List<String> suspended = new ArrayList<>();

        for (String code : sectorStockCodes) {
            if (!activeStockCodes.contains(code)) {
                suspended.add(code);
                suspendedStocks.put(code, "KRX 상장 목록에 없음");
            }
        }

        if (!suspended.isEmpty()) {
            String msg = String.format(
                    "<b>⚠️ 거래정지/상폐 종목 감지</b>\n\n" +
                    "섹터 설정 종목 중 KRX 상장 목록에 없는 종목 %d건:\n%s\n\n" +
                    "⏰ %s\n━━━━━━━━━━━━━━━━\n🤖 MyPlatform 종목 상태 알림",
                    suspended.size(),
                    suspended.stream()
                            .map(c -> "  • " + c)
                            .reduce((a, b) -> a + "\n" + b)
                            .orElse(""),
                    DateTimeUtil.kstNow().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            );
            telegramService.sendRisk(msg);
            log.warn("[종목상태] 거래정지/상폐 감지 {}건: {}", suspended.size(), suspended);
        } else {
            log.info("[종목상태] 모든 섹터 종목이 KRX 상장 목록에 존재 — 정상");
        }

        return suspended;
    }

    /**
     * KRX 전종목 시세 조회 — <b>OTP 2단계</b>(getOtp → code=otp 로 getJsonData).
     * 단발 getJsonData 는 세션 거부("LOGOUT")로 死 — 클래스 상단 상수 주석 참조.
     *
     * <p>trdDd 는 <b>직전 거래일</b>: 08:30 크론 시점엔 당일 시세가 아직 없고, 상장 목록 동기화
     * 목적엔 전일 목록으로 충분하다(상폐·정지는 전일 목록에서도 이미 빠져 있음). 트레이드오프:
     * 신규 상장 종목은 상장 첫날 하루 isActive=false 로 게이트에 안 잡힘 — 인지된 사각
     * (PriceSanityGuard 의 신규 상장 사각과 동일 계열).
     *
     * @param mktId STK(KOSPI) or KSQ(KOSDAQ)
     */
    private Set<String> fetchKrxStockList(String mktId) {
        try {
            String trdDd = marketCalendar.minusTradingDays(LocalDate.now(), 1)
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String otp = fetchOtp(mktId, trdDd);
            if (otp == null) {
                log.error("[종목상태] KRX {} OTP 획득 실패 — 동기화 스킵", mktId);
                return Collections.emptySet();
            }
            String body = fetchDataWithOtp(otp);
            Set<String> codes = parseStockCodes(body);
            if (codes.isEmpty()) {
                // "LOGOUT"/HTML/OutBlock 부재 전부 여기로 — 원인 구분은 로그 본문 앞부분으로.
                log.error("[종목상태] KRX {} 응답 파싱 0건 — 본문: {}", mktId,
                        body == null ? "null" : body.substring(0, Math.min(80, body.length())));
            }
            return codes;
        } catch (Exception e) {
            log.error("[종목상태] KRX {} 종목 조회 실패: {}", mktId, e.getMessage());
            return Collections.emptySet();
        }
    }

    /** 1단계 — OTP 발급. MarketTimingService.getKrxOtp 와 동일 패턴(살아있는 유일한 KRX 경로). */
    private String fetchOtp(String mktId, String trdDd) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("User-Agent", USER_AGENT);
            headers.set("Referer", KRX_REFERER);
            headers.set("Origin", "http://data.krx.co.kr");

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("bld", BLD_STOCK_LIST);
            params.add("locale", "ko_KR");
            params.add("mktId", mktId);
            params.add("trdDd", trdDd);
            params.add("share", "1");
            params.add("money", "1");
            params.add("csvxls_isNo", "false");

            ResponseEntity<String> response = restTemplate.exchange(
                    KRX_OTP_URL, HttpMethod.POST, new HttpEntity<>(params, headers), String.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                String otp = response.getBody().trim();
                if (!otp.isEmpty() && !otp.contains("<html>")) return otp;
            }
        } catch (Exception e) {
            log.debug("[종목상태] KRX OTP 획득 실패: {}", e.getMessage());
        }
        return null;
    }

    /** 2단계 — OTP 로 데이터 요청. */
    private String fetchDataWithOtp(String otp) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("User-Agent", USER_AGENT);
        headers.set("Referer", KRX_REFERER);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("code", otp);

        ResponseEntity<String> response = restTemplate.exchange(
                KRX_DATA_URL, HttpMethod.POST, new HttpEntity<>(params, headers), String.class);
        return response.getBody();
    }

    private static final ObjectMapper PARSE_MAPPER = new ObjectMapper();

    /**
     * KRX 응답 → 6자리 종목코드 집합. <b>순수 함수(테스트 대상)</b>.
     * "LOGOUT"(세션 거부)·HTML·OutBlock_1 부재·null 은 전부 빈 집합 — 호출측이 실패로 처리
     * (§4c: 거부 응답을 "종목 0건"으로 위장하지 않도록 <100 게이트가 뒤에서 이중 방어).
     */
    static Set<String> parseStockCodes(String body) {
        Set<String> codes = new HashSet<>();
        if (body == null || body.isBlank()) return codes;
        try {
            JsonNode root = PARSE_MAPPER.readTree(body);
            JsonNode dataArray = root.get("OutBlock_1");
            if (dataArray == null || !dataArray.isArray()) return codes;
            for (JsonNode item : dataArray) {
                String code = item.path("ISU_SRT_CD").asText("");
                if (code.length() == 6 && code.matches("\\d{6}")) {
                    codes.add(code);
                }
            }
        } catch (Exception e) {
            return codes;   // "LOGOUT"/HTML 등 JSON 아님 → 빈 집합(실패)
        }
        return codes;
    }
}
