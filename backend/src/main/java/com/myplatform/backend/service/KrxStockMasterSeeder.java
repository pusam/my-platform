package com.myplatform.backend.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * KRX 상장법인목록을 다운로드해서 stock_master 테이블에 시드.
 *
 * 소스: https://kind.krx.co.kr/corpgeneral/corpList.do
 *  - searchType=13 : 전체
 *  - marketType=stockMkt | kosdaqMkt
 *  - 응답: HTML table (XLS 형태로 떨어지지만 사실은 HTML)
 *  - 컬럼: 회사명 / 종목코드 / 업종 / 주요제품 / 상장일 / 결산월 / 대표자명 / 홈페이지 / 지역
 *
 * 동작:
 *  1) ApplicationReady 시: 마스터가 비어있으면(< 100건) 자동 시드
 *  2) @Scheduled: 매일 06:00에 KOSPI/KOSDAQ 모두 refresh
 *
 * 실패해도 앱은 살아있고, 기존 StockNameResolver 하드코딩 폴백으로 동작.
 */
@Service
@Slf4j
public class KrxStockMasterSeeder {

    private static final String KRX_URL =
            "https://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13&marketType=";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int EMPTY_THRESHOLD = 100;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final StockMasterService stockMasterService;
    private final MeterRegistry meterRegistry;
    /**
     * 표준 java.net.http.HttpClient 사용.
     * 이전: WebClient (reactor-netty) → 부팅 시 io.netty.handler.codec.quic.Quiche 가
     * libnetty_quiche42_linux_x86_64.so 로드 시도 → 컨테이너 이미지에 libgcc_s.so.1 없어
     * UnsatisfiedLinkError (fatal) → 시드 영구 실패. 시드는 단발 호출이라 reactive 불필요.
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** 부팅 자동 시드와 06:00 cron 이 동시 발화 시 중복 작업 방지. */
    private final AtomicBoolean seeding = new AtomicBoolean(false);

    // 메트릭 — 마지막 시드 결과를 Gauge 로 노출 (Prometheus 가 폴링 시 평가)
    private volatile long lastSeedEpochSeconds = 0;
    private final java.util.concurrent.atomic.AtomicInteger lastKospiCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger lastKosdaqCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public KrxStockMasterSeeder(StockMasterService stockMasterService, MeterRegistry meterRegistry) {
        this.stockMasterService = stockMasterService;
        this.meterRegistry = meterRegistry;

        // Gauge 등록 — 마지막 시드 시각(epoch) / 시장별 적재 수
        meterRegistry.gauge("stock_master.last_seed_time_seconds", this,
                self -> (double) self.lastSeedEpochSeconds);
        meterRegistry.gauge("stock_master.last_seed_count",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("market", "KOSPI")),
                lastKospiCount, java.util.concurrent.atomic.AtomicInteger::get);
        meterRegistry.gauge("stock_master.last_seed_count",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("market", "KOSDAQ")),
                lastKosdaqCount, java.util.concurrent.atomic.AtomicInteger::get);
    }

    /** Health indicator / 메트릭에서 마지막 시드 시각 조회. 0 = 시드 한 번도 안 됨. */
    public long getLastSeedEpochSeconds() {
        return lastSeedEpochSeconds;
    }

    /** 부팅 시 비어있으면 자동 시드 (비동기로 부팅 차단 방지). */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void seedOnStartupIfEmpty() {
        try {
            if (stockMasterService.cachedCount() < EMPTY_THRESHOLD) {
                log.info("StockMaster 비어있음({}) — KRX 시드 시작", stockMasterService.cachedCount());
                seedAll();
            }
        } catch (Exception e) {
            log.warn("KRX 자동 시드 실패: {}", e.getMessage());
        }
    }

    /**
     * 월~토 06:20 한국시간에 KRX 마스터 갱신.
     *
     * 부하 분산(2026-08-24): 이전 06:00 정각 — DART corpCode 갱신과 같은 초에 시작해
     * KIND corpList HTML 2회 수집(KOSPI+KOSDAQ, Jsoup 파싱) + 전 종목(~2,800) upsert 가
     * DART 파싱 피크와 그대로 겹쳤다.
     * DART 를 06:00 에 두고 이 잡을 20분 뒤로 옮긴다(상세 근거는 DartService 쪽 동일 주석).
     *
     * 일요일 제외: 주말엔 상장/폐지가 없어 일요일 실행분은 토요일분과 동일.
     * 아래 retryIfEmpty 워처(매시간, 임계 미만일 때만)가 자가 치유를 계속 담당한다.
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 20 6 * * MON-SAT", zone = "Asia/Seoul")
    public void refreshDaily() {
        try {
            log.info("KRX 마스터 일일 갱신 시작");
            seedAll();
        } catch (Exception e) {
            log.warn("KRX 일일 갱신 실패: {}", e.getMessage());
        }
    }

    /**
     * 빈 상태 watcher — 매시간 EMPTY_THRESHOLD 미만이면 재시도.
     * 이전: 부팅 시 ApplicationReadyEvent 1회만 시도 → 실패하면 다음 06:00 cron 까지 대기.
     * 운영 사고: 66 종목만 캐시된 상태로 종일 머묾. 매시간 watcher 가 자가 치유.
     * initialDelay 20분: 부팅 시드와 충돌 방지.
     */
    @Scheduled(scheduler = "batchScheduler", fixedDelay = 3_600_000L, initialDelay = 1_200_000L)
    public void retryIfEmpty() {
        try {
            int count = stockMasterService.cachedCount();
            if (count < EMPTY_THRESHOLD) {
                log.info("StockMaster 여전히 비어있음({}) — KRX 시드 재시도", count);
                seedAll();
            }
        } catch (Exception e) {
            log.warn("KRX 시드 재시도 실패: {}", e.getMessage());
        }
    }

    public int seedAll() {
        if (!seeding.compareAndSet(false, true)) {
            log.info("KRX 시드가 이미 실행 중 — skip");
            return 0;
        }
        try {
            java.util.Set<String> seen = new java.util.HashSet<>();
            int kospi = seedMarket("stockMkt", "KOSPI", seen);
            int kosdaq = seedMarket("kosdaqMkt", "KOSDAQ", seen);
            pruneLegacyMangledRows(kospi, kosdaq, seen);
            lastKospiCount.set(kospi);
            lastKosdaqCount.set(kosdaq);
            lastSeedEpochSeconds = System.currentTimeMillis() / 1000;
            int total = kospi + kosdaq;
            log.info("KRX 시드 완료 — 총 {} 종목 (KOSPI {} / KOSDAQ {})", total, kospi, kosdaq);
            Counter.builder("stock_master.seed").tag("outcome", "success")
                    .register(meterRegistry).increment();
            return total;
        } catch (RuntimeException e) {
            Counter.builder("stock_master.seed").tag("outcome", "failure")
                    .register(meterRegistry).increment();
            throw e;
        } finally {
            seeding.set(false);
        }
    }

    private int seedMarket(String marketType, String marketLabel, java.util.Set<String> seen) {
        // KRX corpList 응답은 charset 헤더가 일관적이지 않아 한글이 깨질 수 있음 → byte 받아 EUC-KR 디코드.
        // EUC-KR 로 깨진 문자가 보이면 UTF-8 폴백.
        byte[] bytes;
        try {
            log.info("KRX {} 다운로드 시작: {}{}", marketLabel, KRX_URL, marketType);
            HttpRequest req = HttpRequest.newBuilder(URI.create(KRX_URL + marketType))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) {
                log.warn("KRX {} HTTP {} — body {} bytes", marketLabel, res.statusCode(),
                        res.body() == null ? 0 : res.body().length);
                return 0;
            }
            bytes = res.body();
        } catch (Exception e) {
            log.warn("KRX {} 다운로드 실패 [{}]: {}", marketLabel,
                    e.getClass().getSimpleName(), e.getMessage());
            return 0;
        }
        if (bytes == null || bytes.length == 0) {
            log.warn("KRX {} 응답이 비어있음 (bytes={})", marketLabel,
                    bytes == null ? "null" : 0);
            return 0;
        }
        log.info("KRX {} 응답 수신: {} bytes", marketLabel, bytes.length);
        // EUC-KR / UTF-8 둘 다 디코드해서 깨진 char 가 적은 쪽 채택 (KRX 가 charset 정책을 바꿔도 안전).
        String html = pickBetterDecode(bytes);

        Document doc = Jsoup.parse(html);
        Elements rows = doc.select("table tr");
        if (rows.size() < 2) {
            log.warn("KRX {} 파싱 실패 — row {} 개", marketLabel, rows.size());
            return 0;
        }

        // 헤더 인덱스 매핑 (KRX가 컬럼 순서를 바꿀 수 있어 이름으로 매핑)
        Map<String, Integer> col = new HashMap<>();
        Elements headerCells = rows.first().select("th, td");
        for (int i = 0; i < headerCells.size(); i++) {
            col.put(headerCells.get(i).text().trim(), i);
        }
        Integer iName = col.get("회사명");
        Integer iCode = col.get("종목코드");
        Integer iSector = col.get("업종");
        Integer iListed = col.get("상장일");
        if (iName == null || iCode == null) {
            log.warn("KRX {} 헤더 매핑 실패 — 컬럼: {}", marketLabel, col.keySet());
            return 0;
        }

        // 파싱 → 행 리스트 (네트워크 I/O 끝난 뒤 단일 tx 로 일괄 upsert)
        List<StockMasterService.KrxRow> batch = new ArrayList<>(rows.size());
        for (int r = 1; r < rows.size(); r++) {
            Elements cells = rows.get(r).select("td");
            if (cells.size() <= Math.max(iName, iCode)) continue;

            String name = cells.get(iName).text().trim();
            String code = padCode(cells.get(iCode).text().trim());
            if (name.isEmpty() || code.isEmpty()) continue;

            String sector = (iSector != null && cells.size() > iSector)
                    ? cells.get(iSector).text().trim() : null;
            LocalDate listed = parseDate(
                    (iListed != null && cells.size() > iListed) ? cells.get(iListed).text().trim() : null);

            batch.add(new StockMasterService.KrxRow(code, name, marketLabel,
                    emptyToNull(sector), listed));
            seen.add(code);
        }

        int upserted = stockMasterService.upsertBatchFromKrx(batch);
        log.info("KRX {} 시드: {} 종목 (파싱 {})", marketLabel, upserted, batch.size());
        return upserted;
    }

    private static String pickBetterDecode(byte[] bytes) {
        String euc = new String(bytes, java.nio.charset.Charset.forName("EUC-KR"));
        String utf = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return countReplacementChars(euc) <= countReplacementChars(utf) ? euc : utf;
    }

    private static int countReplacementChars(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '�') n++;
        }
        return n;
    }

    /** KRX는 종목코드를 정수형으로 떨굴 때가 있어서 6자리 zero-pad(숫자만인 코드). */
    static String padCode(String code) {
        // 2026-10-02: 예전엔 숫자 아닌 글자를 지운 뒤 zero-pad 해서 영숫자 코드 "0009K0" → "000090", "0039P0" → "000390" 이
        // 됐다. 그 숫자 코드를 이미 쓰는 상장사(000390 삼화페인트·000080 하이트진로·000880 한화 등 42행)의 마스터 행이 신규
        // 상장사의 이름·시장으로 덮였고, 이름 폴백을 쓰는 화면이 그 회사 시세 옆에 다른 회사 이름을 띄웠다.
        // 숫자만이면 6자리 zero-pad, 영문이 섞인 6자리 코드는 대문자로 그대로, 그 밖의 형식은 건너뛴다("").
        if (code == null) return "";
        String c = code.trim().toUpperCase();
        if (c.matches("\\d{1,6}")) return String.format("%6s", c).replace(' ', '0');
        if (c.matches("[0-9A-Z]{6}")) return c;
        return "";
    }

    /** 유령 행 정리는 두 시장 목록이 다 받아졌을 때만(일부만 받은 날 진짜 상장사 행을 지우지 않게). 실측 KOSPI ~840 / KOSDAQ ~1,800. */
    static final int PRUNE_MIN_KOSPI = 500;
    static final int PRUNE_MIN_KOSDAQ = 1000;

    /**
     * 예전 {@code padCode} 가 영숫자 코드에서 만들었을 숫자 코드 중, 이번 목록의 어떤 종목도 실제로 쓰지 않는 것(순수).
     * 그 코드의 KRX 행은 존재하지 않는 종목(유령)이라 지운다 — 실제 상장사가 쓰는 코드는 시드가 이미 바른 이름으로 덮었다.
     */
    static java.util.Set<String> legacyMangledCodes(java.util.Set<String> seenCodes) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String c : seenCodes) {
            if (c == null || !c.matches("[0-9A-Z]{6}") || c.matches("\\d{6}")) continue;   // 영숫자 코드만
            String digits = c.replaceAll("\\D", "");
            if (digits.isEmpty()) continue;
            String mangled = digits.length() >= 6 ? digits : String.format("%6s", digits).replace(' ', '0');
            if (!seenCodes.contains(mangled)) out.add(mangled);
        }
        return out;
    }

    void pruneLegacyMangledRows(int kospi, int kosdaq, java.util.Set<String> seen) {
        if (kospi < PRUNE_MIN_KOSPI || kosdaq < PRUNE_MIN_KOSDAQ) {
            log.info("KRX 유령 행 정리 생략 — 목록이 덜 받아짐(KOSPI {} / KOSDAQ {})", kospi, kosdaq);
            return;
        }
        java.util.Set<String> ghosts = legacyMangledCodes(seen);
        if (ghosts.isEmpty()) return;
        int removed = stockMasterService.removeKrxRows(ghosts);
        if (removed > 0) {
            log.warn("KRX 유령 행 {}건 정리 — 예전 코드 정규화가 영숫자 코드를 숫자로 뭉갠 행: {}", removed, ghosts);
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim(), DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
