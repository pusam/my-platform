package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockMaster;
import com.myplatform.backend.repository.StockMasterRepository;
import com.myplatform.backend.util.StockNameResolver;
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

    // stock_code -> stock_name (가장 많이 쓰이는 조회)
    private final Map<String, String> nameCache = new ConcurrentHashMap<>();
    // stock_code -> 시장구분
    private final Map<String, String> marketCache = new ConcurrentHashMap<>();

    public StockMasterService(StockMasterRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void warmup() {
        // 정적 유틸 (StockNameResolver) 이 우리를 통해 조회하도록 등록
        StockNameResolver.setMasterLookup(this::getName);
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
        if (stockName == null || stockName.isEmpty()) return;
        // 종목코드와 동일한 이름이 들어오는 케이스 (이름 못 가져온 폴백) — 무시
        if (stockName.equals(stockCode)) return;

        String existing = nameCache.get(stockCode);
        if (stockName.equals(existing)) return; // 캐시와 동일 → DB 안 건드림

        try {
            repository.upsertNameOnly(stockCode, stockName, source != null ? source : "KIS");
            nameCache.put(stockCode, stockName);
        } catch (Exception e) {
            log.warn("StockMaster upsert 실패 stockCode={}: {}", stockCode, e.getMessage());
        }
    }

    /** KRX 시드용 — full row upsert. */
    @Transactional
    public void upsertFromKrx(String stockCode, String stockName, String market,
                              String sector, LocalDate listedDate) {
        if (stockCode == null || stockName == null) return;
        repository.upsert(stockCode, stockName, market, sector, listedDate, "KRX");
        nameCache.put(stockCode, stockName);
        if (market != null) marketCache.put(stockCode, market);
    }

    public Optional<StockMaster> findByCode(String stockCode) {
        return repository.findById(stockCode);
    }

    /** 자동완성 검색 — 종목명 또는 종목코드.
     *  사용자 입력에 LIKE 와일드카드(%, _) 가 들어오면 의도치 않은 매치가 되므로 escape. */
    public List<StockMaster> search(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) return List.of();
        String escaped = keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return repository.search(escaped, limit);
    }
}
