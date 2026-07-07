package com.myplatform.backend.service;

import com.myplatform.backend.dto.CatalystHistoryDto;
import com.myplatform.backend.dto.StockCatalystDto;
import com.myplatform.backend.entity.StockCatalyst;
import com.myplatform.backend.entity.StockCatalyst.CatalystType;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.StockCatalystRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 종목별 재료 이력 — 기존 {@code stock_catalyst}(V31 일캐시) <b>재사용·read-only</b>.
 *
 * <p>신규 분류(Gemini classify) 호출 없음 — 캐시된 재료만 조회한다(§4b: 보드/이력 표시는 read-only 소비).
 * 각 재료 날짜에 {@code stock_price_history} 의 일봉 등락률을 병기(없으면 null — §4c).
 * 조립은 순수 함수 {@link #assemble}(테스트 대상)로 분리.
 */
@Service
@RequiredArgsConstructor
public class CatalystHistoryService {

    /** 이력 윈도우(일) — 최근 30일. */
    public static final int WINDOW_DAYS = 30;

    private final StockCatalystRepository catalystRepository;
    private final StockPriceHistoryRepository priceHistoryRepository;

    @Transactional(readOnly = true)
    public CatalystHistoryDto getHistory(String stockCode) {
        LocalDate today = LocalDate.now();
        LocalDate minDate = today.minusDays(WINDOW_DAYS);

        List<StockCatalyst> rows = catalystRepository
                .findByStockCodeAndCatalystDateGreaterThanEqualOrderByCatalystDateDesc(stockCode, minDate);

        // 재료 날짜별 등락률 병기용 — 같은 창의 일봉을 한 번에 로드해 map 으로. (N+1 없음)
        Map<LocalDate, BigDecimal> changeByDate = new HashMap<>();
        for (StockPriceHistory h : priceHistoryRepository.findByStockCodeAndDateRange(stockCode, minDate, today)) {
            if (h != null && h.getTradeDate() != null && h.getChangeRate() != null) {
                changeByDate.putIfAbsent(h.getTradeDate(), h.getChangeRate());
            }
        }

        return assemble(stockCode, rows, changeByDate);
    }

    /**
     * 이력 행 → DTO 조립. <b>순수 함수(테스트 대상)</b>.
     * type=NONE(재료 없음) 행은 제외(실제 포착된 재료만). 등락률은 map lookup — 없으면 null(§4c).
     */
    static CatalystHistoryDto assemble(String stockCode, List<StockCatalyst> rows,
                                       Map<LocalDate, BigDecimal> changeByDate) {
        List<CatalystHistoryDto.Item> items = new ArrayList<>();
        if (rows != null) {
            for (StockCatalyst c : rows) {
                if (c == null || c.getCatalystType() == null) continue;
                if (c.getCatalystType() == CatalystType.NONE) continue;   // 재료 없음 = 타임라인 제외
                BigDecimal changeRate = changeByDate == null ? null : changeByDate.get(c.getCatalystDate());
                items.add(CatalystHistoryDto.Item.builder()
                        .catalystDate(c.getCatalystDate())
                        .catalystType(c.getCatalystType().name())
                        .typeLabel(StockCatalystDto.labelOf(c.getCatalystType()))
                        .direction(c.getDirection() == null ? null : c.getDirection().name())
                        .headline(c.getHeadline())
                        .summary(c.getSummary())
                        .changeRate(changeRate)
                        .build());
            }
        }
        return CatalystHistoryDto.builder()
                .stockCode(stockCode)
                .windowDays(WINDOW_DAYS)
                .items(items)
                .build();
    }
}
