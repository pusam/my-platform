package com.myplatform.backend.service;

import com.myplatform.core.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 상장종목 상태 관리 서비스
 * - 부팅 직후 + 매일 08:30 KIS 종목마스터에서 KOSPI+KOSDAQ 상장종목 목록 동기화
 * - 거래정지/상장폐지 종목 자동 감지
 * - SectorStockConfig의 종목 필터링에 사용
 *
 * <p>소스는 2026-08-31 에 KRX → KIS 종목마스터로 교체했다. 사유·실측은 아래 상수 주석.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StockStatusService {

    // ⚠⚠ KRX data.krx.co.kr 은 이 용도로 死다 — 되돌리지 말 것 (2026-08-31 실측 확정).
    //
    //  ① 단발 getJsonData(Referer 만) → 본문 "LOGOUT"(세션 거부)
    //     — SHORT_SELLING_DEAD_FEED_DIAGNOSIS §2(2026-07-07)에서 이미 확정된 死패턴.
    //  ② 그 문서의 권고대로 2026-08-21 에 "OTP 2단계"로 전환했는데, 주소를
    //     /comm/bldAttendant/getOtp.cmd 로 적었다. **그런 엔드포인트는 없다.**
    //     존재하지 않는 경로와 이 경로의 응답이 200 / 2952 바이트 에러페이지로 **완전히 동일**하다
    //     (대조군 POST /comm/bldAttendant/thisDoesNotExist.cmd 와 바이트 일치).
    //     즉 이 서비스는 전환 이후 단 한 번도 동기화에 성공한 적이 없다.
    //  ③ 진짜 OTP 엔드포인트(/comm/fileDn/GenerateOTP/generate.cmd)도 이제 "LOGOUT" 이다.
    //     홈+로더 페이지로 JSESSIONID·__smVisitorID 를 확보한 세션으로 재요청해도 동일.
    //     → KRX 쪽엔 되살릴 경로가 남아 있지 않다. 여기서 KRX 를 다시 시도하지 말 것.
    //
    // 대체 소스 = KIS 종목마스터 파일(아래). 고른 이유:
    //  - **전 종목이 들어 있다.** KIND corpList(=KrxStockMasterSeeder 소스)는 회사 단위라
    //    우선주가 빠진다(KOSPI 848행). 축소된 집합을 이 게이트에 넣으면 우선주 전체가
    //    무음 fail-CLOSED 로 사라진다 — 아래 시장별 게이트 주석이 경고하는 바로 그 사고.
    //    KIS 마스터는 6자리 코드 3,934개(005935 삼성전자우 포함)로 **상위집합**이라
    //    잘못돼도 fail-open 방향이다.
    //  - 이 플랫폼이 이미 KIS 에 전면 의존한다(시세·주문). 새 벤더가 늘지 않는다.
    //  - 매일 갱신된다(실측: 당일 07:35 타임스탬프).
    // 검증(2026-08-31): SectorStockConfig 139종목 전부 마스터에 존재 — 누락 0건.
    private static final String KIS_MASTER_URL =
            "https://new.real.download.dws.co.kr/common/master/%s_code.mst.zip";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 마스터 파일 이름 조각 — KRX 의 mktId(STK/KSQ) 자리를 대신한다. */
    private static final String MARKET_KOSPI = "kospi";
    private static final String MARKET_KOSDAQ = "kosdaq";

    private final RestTemplate restTemplate;
    private final TelegramNotificationService telegramService;
    private final com.myplatform.backend.config.SectorStockConfig sectorStockConfig;
    private final com.myplatform.backend.repository.StockPriceHistoryRepository priceHistoryRepository;

    // 정상 거래 가능 종목 코드 셋 (KIS 종목마스터 기준)
    private final Set<String> activeStockCodes = ConcurrentHashMap.newKeySet();

    // 거래정지/상폐 감지된 종목 (코드 → 사유)
    private final ConcurrentHashMap<String, String> suspendedStocks = new ConcurrentHashMap<>();

    // 거래량 기반 거래정지 감지(2026-09-07): 마스터는 상폐(목록 제거)만 잡는다 — 거래정지 종목은 상장이 유지돼
    // 마스터에 남는다(이오플로우 294090 이 is_active=1 로 마법의공식 #1). 최근 봉 전부 volume=0 이 실측 신호.
    private final Set<String> volumeHaltedCodes = ConcurrentHashMap.newKeySet();
    static final int HALT_WINDOW_DAYS = 7;
    static final long HALT_MIN_BARS = 3;
    static final String HALT_REASON = "최근 " + HALT_WINDOW_DAYS + "일 봉 " + HALT_MIN_BARS + "개 이상 전부 거래량 0";

    private volatile LocalDateTime lastSyncTime = null;

    /**
     * 종목이 정상 거래 가능한지 확인
     * - 동기화 전이면 true 반환 (안전 모드)
     * - 동기화 후에는 상장 목록에 있는 종목만 true
     */
    public boolean isActive(String stockCode) {
        if (volumeHaltedCodes.contains(stockCode)) return false; // 거래정지(마스터엔 남음) — 실측 volume=0 감지
        if (activeStockCodes.isEmpty()) return true; // 동기화 전 안전 모드
        return activeStockCodes.contains(stockCode);
    }

    /**
     * 종목 목록에서 거래정지/상폐 종목 필터링 — 마스터 fail-open(빈 목록=통과) semantics 는 그대로,
     * 거래량 기반 정지 감지만 그 앞에서 항상 적용된다.
     */
    public List<String> filterActiveStocks(List<String> stockCodes) {
        return stockCodes.stream()
                .filter(this::isActive)
                .toList();
    }

    /**
     * 거래량 기반 거래정지 감지 갱신 — {@link #syncListedStocks()} 끝에서 호출(부팅 1회 + 08:30).
     * 조회 실패는 이전 감지 목록 유지(fail-open, §4c) — 빈 결과로 위장하지 않는다.
     */
    public void refreshVolumeHalts() {
        try {
            List<String> halted = priceHistoryRepository.findCodesWithAllZeroVolumeSince(
                    DateTimeUtil.kstNow().toLocalDate().minusDays(HALT_WINDOW_DAYS), HALT_MIN_BARS);
            Set<String> next = new HashSet<>(halted);
            if (!next.equals(volumeHaltedCodes)) {
                log.info("[종목상태] 거래량 기반 거래정지 {}건 (이전 {}건): {}",
                        next.size(), volumeHaltedCodes.size(), next.stream().sorted().limit(20).toList());
            }
            volumeHaltedCodes.retainAll(next);
            volumeHaltedCodes.addAll(next);
            suspendedStocks.entrySet().removeIf(e -> HALT_REASON.equals(e.getValue()) && !next.contains(e.getKey()));
            next.forEach(c -> suspendedStocks.putIfAbsent(c, HALT_REASON));
        } catch (Exception e) {
            log.warn("[종목상태] 거래량 기반 정지 감지 실패 — 이전 감지 목록({}건) 유지: {}",
                    volumeHaltedCodes.size(), e.getMessage());
        }
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
     * 부팅 직후 1회 동기화(비동기 — 부팅을 막지 않는다).
     *
     * <p><b>왜 필요한가</b>: 목록은 메모리에만 있고 재시작하면 사라진다. 예전엔 여기서 로그만 찍고
     * 실제 동기화는 08:30 크론뿐이었다 — 즉 <b>08:30 이후에 재시작하면 그날 하루 종일</b>
     * {@code activeStockCodes} 가 비어 게이트가 전면 fail-open(거래정지·상폐 종목이 추천·발굴·봇에
     * 그대로 통과) 이었고, 아무 로그도 남지 않았다. 부팅 시 채우면 그 창이 사라진다.
     *
     * <p>실패해도 앱은 정상 기동한다(빈 목록 = 종전과 같은 fail-open, 08:30 크론이 재시도).
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void syncOnStartup() {
        try {
            syncListedStocks();
        } catch (Exception e) {
            log.warn("[종목상태] 부팅 시 동기화 실패 — 08:30 크론이 재시도한다: {}", e.getMessage());
        }
    }

    /**
     * 매일 08:30 상장종목 동기화 (장 시작 전)
     * + 섹터 종목 중 거래정지/상폐 감지 → 텔레그램 알림
     */
    /** 동기화 노후 경보 임계 — 이보다 오래면 게이트 데이터가 죽은 것으로 보고 경보. */
    private static final java.time.Duration SYNC_STALE_ALERT = java.time.Duration.ofHours(48);

    @Scheduled(scheduler = "batchScheduler", cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledSync() {
        syncListedStocks();
        // 경보 조건은 isEmpty 가 아니라 lastSyncTime 노후(2026-08-21 리뷰 B-6) — syncListedStocks 는 실패 시
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
        log.error("[종목상태] 상장종목 동기화 노후({}) — 거래정지/상폐 제외 게이트가 옛 목록/무필터로 동작 중", since);
        try {
            telegramService.sendRisk("<b>⚠️ 종목상태 동기화 노후</b>\n\n"
                    + "상장종목 목록 동기화(KIS 종목마스터): " + since + "\n"
                    + "거래정지/상폐 제외 게이트(추천·발굴·봇)가 옛 목록 또는 무필터(fail-open)로 동작 중.\n\n"
                    + "━━━━━━━━━━━━━━━━\n🤖 MyPlatform 종목 상태 알림");
        } catch (Exception ignore) { /* 알림은 best-effort */ }
    }

    /**
     * KIS 종목마스터에서 상장종목 목록 동기화.
     *
     * <p>이름이 {@code syncFromKrx} 였다가 소스 교체(2026-08-31)와 함께 바뀌었다 —
     * 죽은 소스 이름을 남겨두면 다음 사람이 KRX 를 되살리려 든다.
     */
    public void syncListedStocks() {
        log.info("[종목상태] 상장종목 동기화 시작(KIS 종목마스터)...");
        Set<String> newActiveCodes = ConcurrentHashMap.newKeySet();

        try {
            // KOSPI 종목 수집
            Set<String> kospiCodes = fetchMarketStockList(MARKET_KOSPI);
            log.info("[종목상태] KOSPI 종목 {}건 수집", kospiCodes.size());

            // 1초 딜레이 (연속 다운로드 부하 완화)
            Thread.sleep(1000);

            // KOSDAQ 종목 수집
            Set<String> kosdaqCodes = fetchMarketStockList(MARKET_KOSDAQ);
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
            log.error("[종목상태] 동기화 실패: {}", e.getMessage());
            return; // 실패 시 기존 데이터 유지
        }

        if (newActiveCodes.size() < 100) {
            log.warn("[종목상태] 수집 종목 수 {}건 — 비정상적으로 적어 동기화 취소", newActiveCodes.size());
            return;
        }

        activeStockCodes.clear();
        activeStockCodes.addAll(newActiveCodes);
        lastSyncTime = DateTimeUtil.kstNow();
        log.info("[종목상태] 동기화 완료 — 총 {}건 ({})", activeStockCodes.size(), lastSyncTime);

        refreshVolumeHalts();
    }

    /**
     * SectorStockConfig의 종목 중 상장 목록에 없는 종목 감지
     * syncListedStocks() 이후 호출
     */
    public List<String> detectSuspendedInSectors(List<String> sectorStockCodes) {
        if (activeStockCodes.isEmpty()) return Collections.emptyList();

        // 마스터 사유만 지운다 — 거래량 기반 정지 항목(HALT_REASON)은 refreshVolumeHalts 가 관리
        suspendedStocks.entrySet().removeIf(e -> !HALT_REASON.equals(e.getValue()));
        List<String> suspended = new ArrayList<>();

        for (String code : sectorStockCodes) {
            if (!activeStockCodes.contains(code)) {
                suspended.add(code);
                suspendedStocks.put(code, "상장 목록에 없음");
            }
        }

        if (!suspended.isEmpty()) {
            String msg = String.format(
                    "<b>⚠️ 거래정지/상폐 종목 감지</b>\n\n" +
                    "섹터 설정 종목 중 상장 목록에 없는 종목 %d건:\n%s\n\n" +
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
            log.info("[종목상태] 모든 섹터 종목이 상장 목록에 존재 — 정상");
        }

        return suspended;
    }

    /**
     * 시장별 상장종목 코드 수집 — KIS 종목마스터 파일(zip) 다운로드 → 파싱.
     *
     * <p>KRX 경로가 왜 사라졌는지는 클래스 상단 상수 주석에 실측과 함께 적혀 있다(되돌리지 말 것).
     *
     * <p>실패는 전부 <b>빈 집합</b>으로 수렴하고, 호출측 시장별 게이트가 그걸 보고 동기화를
     * 취소하고 기존 목록을 유지한다. "수집 0건"을 정상 결과로 흘려보내지 않는다(§4c).
     *
     * @param market {@link #MARKET_KOSPI} 또는 {@link #MARKET_KOSDAQ}
     */
    private Set<String> fetchMarketStockList(String market) {
        String url = String.format(KIS_MASTER_URL, market);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

            byte[] zip = response.getBody();
            if (response.getStatusCode() != HttpStatus.OK || zip == null || zip.length == 0) {
                log.warn("[종목상태] KIS 마스터 {} 응답 비정상 — status={}, bytes={}",
                        market, response.getStatusCode(), zip == null ? "null" : zip.length);
                return Collections.emptySet();
            }
            Set<String> codes = readZippedMaster(zip);
            if (codes.isEmpty()) {
                // 내려받긴 했는데 코드가 0건 = 레코드 포맷 변경. 조용히 넘어가면 게이트가 다시 죽는다.
                log.warn("[종목상태] KIS 마스터 {} 파싱 0건 — 레코드 포맷 변경 의심(zip {}바이트)",
                        market, zip.length);
            }
            return codes;
        } catch (Exception e) {
            log.warn("[종목상태] KIS 마스터 {} 조회 실패 — {}: {}",
                    market, e.getClass().getSimpleName(), e.getMessage());
            return Collections.emptySet();
        }
    }

    /** zip 안 첫 파일 엔트리를 종목코드 집합으로. <b>순수 함수(테스트 대상)</b>. */
    static Set<String> readZippedMaster(byte[] zipBytes) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                return parseMasterCodes(zis);
            }
        }
        return Collections.emptySet();
    }

    /**
     * KIS 종목마스터(.mst) → 6자리 종목코드 집합. <b>순수 함수(테스트 대상)</b>.
     *
     * <p>레코드는 고정폭이고 <b>앞 9바이트가 단축코드</b>(좌측정렬·공백패딩)다. 뒤쪽 필드는
     * 한글 종목명이 EUC-KR 가변 바이트라 바이트 오프셋으로 셀 수 없지만, 여기서 쓰는 앞 9바이트는
     * 전부 ASCII 라 <b>인코딩과 무관하게</b> 읽힌다 — 그래서 ISO-8859-1(바이트 1:1 매핑)로 읽는다.
     * 나중에 종목명을 쓰게 되면 그때는 EUC-KR 디코딩이 필요하다.
     *
     * <p><b>6자리 영숫자를 전부 받는다</b>(숫자만이 아니다). 실측(2026-08-31) 4,390개 단축코드 중
     * 순수 숫자 3,553 · <b>영문 섞인 6자리 381</b>(0000D0 같은 종류주식·신주인수권) · ELW 등 456.
     * 옛 KRX 파서는 {@code \d{6}} 만 받았는데, 이 게이트는 "목록에 없으면 제외"라 그 381개가
     * 조용히 fail-CLOSED 된다. 반대로 넉넉히 받으면 틀려도 통과(fail-open) 방향이라
     * <b>상위집합이 안전</b>하다 — ETF/ETN 이 섞여 들어오는 것도 같은 이유로 무해하다.
     * 9자리 ELW(F…)는 길이에서 걸러진다.
     *
     * <p>스트림을 닫지 않는다 — zip 스트림 수명은 호출측 try-with-resources 가 관리한다.
     */
    static Set<String> parseMasterCodes(InputStream mstStream) throws IOException {
        Set<String> codes = new HashSet<>();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(mstStream, StandardCharsets.ISO_8859_1));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.length() < 9) continue;
            String code = line.substring(0, 9).trim();
            if (isShortCode(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** 상장 단축코드 모양 = 6자리 영숫자(대문자). 종류주식(0000D0)까지 포함하려는 의도. */
    private static boolean isShortCode(String code) {
        if (code.length() != 6) return false;
        return code.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z'));
    }
}
