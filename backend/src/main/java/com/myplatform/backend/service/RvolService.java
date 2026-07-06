package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RVOL(Relative Volume) = 당일 거래대금 ÷ 직전 20거래일 평균 거래대금 — <b>표시·스냅샷 전용, 산식 미편입</b>.
 *
 * <p>분자(당일)는 시세 캐시의 누적거래대금(또는 현재가×거래량 폴백 — {@link JudgmentBoardService#resolveTradingValue}),
 * 분모는 {@code stock_price_history} 20거래일의 종가×거래량 근사. 히스토리에 거래대금 컬럼이 없어 근사이며,
 * 분자·분모 산출 방식이 달라 장중엔 당일 누적이 하루치보다 작게 잡힌다(과대 아님 — 참고 표시로 충분).
 *
 * <p>§4c: 20거래일 미만·시세캐시 미스·계산 불가는 전부 <b>null(미수집)</b> — 임시값 생성 금지.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RvolService {

    /** 분모 표본 거래일 수 — 미만이면 null(§4c). */
    static final int RVOL_WINDOW_DAYS = 20;
    /** 20거래일 확보용 캘린더 조회 범위(주말·공휴일 마진). */
    private static final int LOOKBACK_CALENDAR_DAYS = 45;

    private final StockPriceHistoryRepository historyRepository;
    private final StockPriceService stockPriceService;

    /**
     * 단일 종목 RVOL — 시그널 스냅샷(V41)용. 시세는 <b>cache-only</b>(단일 시세경로 — KIS 신규 호출 0).
     * 실패·결측 시 null(미수집).
     */
    public BigDecimal getRvolQuiet(String stockCode) {
        if (stockCode == null) return null;
        try {
            Map<String, StockPriceDto> quotes = stockPriceService.getStockPricesFromCacheOnly(List.of(stockCode));
            BigDecimal todayValue = JudgmentBoardService.resolveTradingValue(quotes.get(stockCode));
            if (todayValue == null) return null;
            List<StockPriceHistory> rows = historyRepository.findByStockCodeOrderByTradeDateDesc(
                    stockCode, PageRequest.of(0, RVOL_WINDOW_DAYS + 5));
            return computeRvol(todayValue, pastDailyValues(rows, LocalDate.now()));
        } catch (Exception e) {
            log.debug("[RVOL] 산출 실패({}): {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * 여러 종목 벌크 RVOL — 종합판단 보드용. 분자는 보드가 이미 조립한 거래대금
     * (같은 시세 스냅샷 재사용 — 이중 조회 방지)을 그대로 받는다. 산출 불가 종목은 결과에서 생략.
     */
    public Map<String, BigDecimal> getRvolBulk(Map<String, BigDecimal> todayValueByCode) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (todayValueByCode == null || todayValueByCode.isEmpty()) return out;
        try {
            LocalDate today = LocalDate.now();
            List<String> codes = new ArrayList<>(todayValueByCode.keySet());
            // code asc, tradeDate desc 정렬 보장(findByStockCodesSince) → 코드별 그룹핑
            Map<String, List<StockPriceHistory>> byCode = new LinkedHashMap<>();
            for (StockPriceHistory h : historyRepository.findByStockCodesSince(
                    codes, today.minusDays(LOOKBACK_CALENDAR_DAYS))) {
                byCode.computeIfAbsent(h.getStockCode(), k -> new ArrayList<>()).add(h);
            }
            for (Map.Entry<String, BigDecimal> e : todayValueByCode.entrySet()) {
                BigDecimal rvol = computeRvol(e.getValue(), pastDailyValues(byCode.get(e.getKey()), today));
                if (rvol != null) out.put(e.getKey(), rvol);
            }
        } catch (Exception e) {
            log.debug("[RVOL] 벌크 산출 실패(생략): {}", e.getMessage());
        }
        return out;
    }

    /**
     * 히스토리(최신순) → <b>오늘 이전</b> 일별 거래대금 근사(종가×거래량) 목록 — 순수 함수.
     * 오늘 행은 제외(분자와 이중 계상 방지), 종가/거래량 결측일은 skip(§4c — 0으로 위장 안 함).
     */
    static List<BigDecimal> pastDailyValues(List<StockPriceHistory> rows, LocalDate today) {
        List<BigDecimal> values = new ArrayList<>();
        if (rows == null) return values;
        for (StockPriceHistory h : rows) {
            if (h == null || h.getTradeDate() == null) continue;
            if (today != null && !h.getTradeDate().isBefore(today)) continue;
            BigDecimal close = h.getClosePrice();
            BigDecimal vol = h.getVolume();
            if (close == null || vol == null || close.signum() <= 0 || vol.signum() <= 0) continue;
            values.add(close.multiply(vol));
        }
        return values;
    }

    /**
     * RVOL = 당일 거래대금 ÷ 직전 {@value #RVOL_WINDOW_DAYS}거래일 평균 — 순수 함수.
     * 표본이 {@value #RVOL_WINDOW_DAYS}일 미만이거나 분자·분모가 무의미하면 null(§4c).
     * 표본이 더 많으면 최신 {@value #RVOL_WINDOW_DAYS}개만 사용(호출부 방어).
     */
    static BigDecimal computeRvol(BigDecimal todayValue, List<BigDecimal> pastValues) {
        if (todayValue == null || todayValue.signum() <= 0) return null;
        if (pastValues == null || pastValues.size() < RVOL_WINDOW_DAYS) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < RVOL_WINDOW_DAYS; i++) {
            sum = sum.add(pastValues.get(i));
        }
        BigDecimal avg = sum.divide(BigDecimal.valueOf(RVOL_WINDOW_DAYS), 6, RoundingMode.HALF_UP);
        if (avg.signum() <= 0) return null;
        return todayValue.divide(avg, 2, RoundingMode.HALF_UP);
    }
}
