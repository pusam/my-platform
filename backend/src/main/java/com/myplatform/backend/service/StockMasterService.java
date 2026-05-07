package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockMaster;
import com.myplatform.backend.repository.StockMasterRepository;
import com.myplatform.backend.util.StockNameResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 종목코드 ↔ 종목명 마스터 서비스
 * - 부팅 시 DB에서 메모리 캐시(ConcurrentHashMap) 워밍
 * - getName() 은 캐시 우선, miss 시 null
 * - upsert 계열은 DB 기록 + 캐시 동기화
 *
 * 호출 순서 (조회):
 *   StockMasterService.getName(code)
 *     ↓ miss
 *   StockNameResolver(static fallback)  ← 기존 하드코딩 맵
 */
@Service
@Slf4j
public class StockMasterService {

    private final StockMasterRepository repository;
    private final MeterRegistry meterRegistry;

    // stock_code -> stock_name (가장 많이 쓰이는 조회)
    private final Map<String, String> nameCache = new ConcurrentHashMap<>();
    // stock_code -> 시장구분
    private final Map<String, String> marketCache = new ConcurrentHashMap<>();

    // KIS 응답 → lazy upsert 결과별 카운터 (메트릭으로 자동 노출).
    private Counter lazyUpsertNew;     // 캐시에 없던 신규 종목
    private Counter lazyUpsertChanged; // 캐시에 있지만 이름 변경
    private Counter lazyUpsertFail;    // DB write 실패

    public StockMasterService(StockMasterRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void warmup() {
        // 정적 유틸 (StockNameResolver) 이 우리를 통해 조회하도록 등록
        StockNameResolver.setMasterLookup(this::getName);

        // 메트릭 초기화
        lazyUpsertNew = Counter.builder("stock_master.lazy_upsert")
                .description("KIS 응답에서 종목명 lazy upsert (신규)")
                .tag("outcome", "new").register(meterRegistry);
        lazyUpsertChanged = Counter.builder("stock_master.lazy_upsert")
                .description("KIS 응답에서 종목명 lazy upsert (변경)")
                .tag("outcome", "changed").register(meterRegistry);
        lazyUpsertFail = Counter.builder("stock_master.lazy_upsert")
                .description("KIS 응답에서 종목명 lazy upsert (실패)")
                .tag("outcome", "fail").register(meterRegistry);
        // 메모리 캐시 종목 수 Gauge — Prometheus 가 폴링 시점에 size() 평가
        meterRegistry.gauge("stock_master.cached_count", nameCache, Map::size);

        try {
            int count = 0;
            for (StockMaster m : repository.findAll()) {
                if (Boolean.FALSE.equals(m.getIsActive())) continue;
                nameCache.put(m.getStockCode(), m.getStockName());
                if (m.getMarket() != null) marketCache.put(m.getStockCode(), m.getMarket());
                count++;
            }
            log.info("StockMaster 캐시 워밍 완료 — {} 종목", count);
        } catch (Exception e) {
            // 부팅 시 DB가 아직 없거나(테스트 등) 실패해도 앱이 죽으면 안 됨
            log.warn("StockMaster 캐시 워밍 실패 — fallback 모드로 동작: {}", e.getMessage());
        }
    }

    /** 메모리 캐시 우선 조회. miss 시 null. */
    public String getName(String stockCode) {
        if (stockCode == null || stockCode.isEmpty()) return null;
        return nameCache.get(stockCode);
    }

    public String getNameOrDefault(String stockCode, String fallback) {
        String name = getName(stockCode);
        return name != null ? name : fallback;
    }

    public String getMarket(String stockCode) {
        if (stockCode == null) return null;
        return marketCache.get(stockCode);
    }

    public int cachedCount() {
        return nameCache.size();
    }

    /**
     * 이름이 새로 들어왔을 때 (KIS 응답 등) — DB upsert + 캐시 갱신.
     * 동일하면 no-op (DB write 생략).
     */
    @Transactional
    public void cacheName(String stockCode, String stockName, String source) {
        if (stockCode == null || stockCode.isEmpty()) return;
        String name = sanitizeName(stockName);
        if (name == null) return;
        // 종목코드와 동일한 이름이 들어오는 케이스 (이름 못 가져온 폴백) — 무시
        if (name.equals(stockCode)) return;

        String existing = nameCache.get(stockCode);
        if (name.equals(existing)) return; // 캐시와 동일 → DB 안 건드림

        try {
            repository.upsertNameOnly(stockCode, name, source != null ? source : "KIS");
            nameCache.put(stockCode, name);
            if (existing == null) lazyUpsertNew.increment();
            else lazyUpsertChanged.increment();
        } catch (Exception e) {
            lazyUpsertFail.increment();
            log.warn("StockMaster upsert 실패 stockCode={}: {}", stockCode, e.getMessage());
        }
    }

    /** KRX 시드용 — full row upsert (단건). */
    @Transactional
    public void upsertFromKrx(String stockCode, String stockName, String market,
                              String sector, LocalDate listedDate) {
        if (stockCode == null) return;
        String name = sanitizeName(stockName);
        if (name == null) return;
        repository.upsert(stockCode, name, market, sanitizeName(sector), listedDate, "KRX");
        nameCache.put(stockCode, name);
        if (market != null) marketCache.put(stockCode, market);
    }

    /** KRX 시드용 — 다건 일괄. 단일 트랜잭션으로 묶어 commit fsync 비용 절감. */
    @Transactional
    public int upsertBatchFromKrx(List<KrxRow> rows) {
        int n = 0;
        for (KrxRow r : rows) {
            if (r.stockCode() == null) continue;
            String name = sanitizeName(r.stockName());
            if (name == null) continue;
            try {
                repository.upsert(r.stockCode(), name, r.market(),
                        sanitizeName(r.sector()), r.listedDate(), "KRX");
                nameCache.put(r.stockCode(), name);
                if (r.market() != null) marketCache.put(r.stockCode(), r.market());
                n++;
            } catch (Exception e) {
                log.debug("KRX 행 upsert 실패 {}: {}", r.stockCode(), e.getMessage());
            }
        }
        return n;
    }

    /** KRX 시드 row carrier. */
    public static record KrxRow(String stockCode, String stockName, String market,
                                String sector, LocalDate listedDate) {}

    public Optional<StockMaster> findByCode(String stockCode) {
        return repository.findById(stockCode);
    }

    /** 검색 키워드 길이 cap — 큰 입력으로 캐시키 메모리 / DB LIKE 부하 회피. */
    private static final int MAX_KEYWORD_LEN = 50;
    /** 종목명 길이 cap — 컬럼은 200, 안전마진 두고 절단. */
    private static final int MAX_NAME_LEN = 150;

    /**
     * 종목명 정규화: 제어문자(\r\n\t 등) 제거 + 공백 압축 + 길이 cap.
     * 외부 응답(KRX/KIS/Naver)을 신뢰하지 않고 적재 직전 통일 처리.
     */
    private static String sanitizeName(String name) {
        if (name == null) return null;
        // ASCII / Unicode 제어 문자 제거 (U+0000~U+001F, U+007F)
        String cleaned = name.replaceAll("\\p{Cntrl}", "").trim();
        // 연속 공백 → 단일 공백
        cleaned = cleaned.replaceAll("\\s{2,}", " ");
        if (cleaned.isEmpty()) return null;
        if (cleaned.length() > MAX_NAME_LEN) cleaned = cleaned.substring(0, MAX_NAME_LEN);
        return cleaned;
    }

    /** 자동완성 검색 — 종목명 또는 종목코드.
     *  사용자 입력에 LIKE 와일드카드(%, _) 가 들어오면 의도치 않은 매치가 되므로 escape.
     *  과도한 길이는 잘라냄 (DoS 방지). */
    public List<StockMaster> search(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) return List.of();
        String trimmed = keyword.trim();
        if (trimmed.length() > MAX_KEYWORD_LEN) {
            trimmed = trimmed.substring(0, MAX_KEYWORD_LEN);
        }
        String escaped = trimmed
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return repository.search(escaped, limit);
    }
}
