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

    /** 매일 06:00 한국시간에 KRX 마스터 갱신. */
    @Scheduled(scheduler = "batchScheduler", cron = "0 0 6 * * *", zone = "Asia/Seoul")
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
            int kospi = seedMarket("stockMkt", "KOSPI");
            int kosdaq = seedMarket("kosdaqMkt", "KOSDAQ");
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

    private int seedMarket(String marketType, String marketLabel) {
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

    /** KRX는 종목코드를 정수형으로 떨굴 때가 있어서 6자리 zero-pad. */
    private static String padCode(String code) {
        if (code == null) return "";
        String digits = code.replaceAll("\\D", "");
        if (digits.isEmpty()) return "";
        if (digits.length() >= 6) return digits;
        return String.format("%6s", digits).replace(' ', '0');
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
