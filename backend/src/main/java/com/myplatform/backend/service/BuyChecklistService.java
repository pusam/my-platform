package com.myplatform.backend.service;

import com.myplatform.backend.dto.BuyChecklistDto;
import com.myplatform.backend.dto.BuyChecklistDto.ChecklistItem;
import com.myplatform.backend.dto.BuyChecklistDto.Recommendation;
import com.myplatform.backend.dto.CompositeSignalDto;
import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.StockConclusionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 수동 매수 체크리스트 산출.
 *
 * 자동매매 봇(AutoTradingBotService)이 적용하는 hard rule 을 그대로 사용자 화면에 노출:
 *  1. 거래 가능 상태 — StockStatusService.isActive
 *  2. 공매도 비율 < 5% — ShortSellingService.getShortSellingRatio
 *  3. 외국인/기관 3일+ 연속매수 — InvestorTradeService.getConsecutiveBuyStocks
 *  4. 복합 신호 3/5 이상 — CompositeSignalService.evaluate
 *  5. 종합 결론 BUY 이상 — StockConclusionService
 *
 * 의존 서비스가 실패해도 한 항목만 "체크 불가" 로 표시하고 나머지는 정상 계산.
 * 미충족 항목 수에 따라 4단계 권고: STRONG / MODERATE / CAUTION / NOT_RECOMMENDED.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BuyChecklistService {

    private final StockStatusService stockStatusService;
    private final ShortSellingService shortSellingService;
    private final InvestorTradeService investorTradeService;
    private final CompositeSignalService compositeSignalService;
    private final StockConclusionService stockConclusionService;

    // 임계값 — 봇 룰과 동기화 필요.
    private static final BigDecimal SHORT_SELLING_LIMIT = new BigDecimal("5.0");
    private static final int CONSECUTIVE_BUY_MIN_DAYS = 3;
    private static final int COMPOSITE_SIGNAL_MIN_MATCHES = 3;

    public BuyChecklistDto evaluate(String stockCode) {
        List<ChecklistItem> items = new ArrayList<>();
        String stockName = stockCode;

        // 1) 거래 가능
        items.add(checkTradable(stockCode));

        // 2) 공매도 비율
        items.add(checkShortSelling(stockCode));

        // 3) 외국인 또는 기관 연속매수
        items.add(checkConsecutiveBuy(stockCode));

        // 4) 복합 신호
        ChecklistItem composite = checkCompositeSignal(stockCode);
        items.add(composite);

        // 5) 종합 결론
        StockConclusionDto conclusion = safeConclusion(stockCode);
        if (conclusion != null && conclusion.getStockName() != null && !conclusion.getStockName().isEmpty()) {
            stockName = conclusion.getStockName();
        }
        items.add(checkConclusion(conclusion));

        int passed = (int) items.stream().filter(ChecklistItem::isPassed).count();
        int total = items.size();
        Recommendation recommendation = decideRecommendation(items);
        String summary = summary(recommendation, passed, total);

        return BuyChecklistDto.builder()
                .stockCode(stockCode)
                .stockName(stockName)
                .items(items)
                .passedCount(passed)
                .totalCount(total)
                .recommendation(recommendation)
                .summary(summary)
                .build();
    }

    // ============================== 개별 체크 ==============================

    private ChecklistItem checkTradable(String stockCode) {
        try {
            boolean active = stockStatusService.isActive(stockCode);
            return ChecklistItem.builder()
                    .key("tradable")
                    .label("거래 가능 상태")
                    .passed(active)
                    .value(active ? "정상" : "거래정지/상폐")
                    .threshold("정상 거래")
                    .note(active ? "" : "거래정지 또는 상폐 종목 — 매수 불가.")
                    .dimension("META")
                    .build();
        } catch (Exception e) {
            return errorItem("tradable", "거래 가능 상태", e);
        }
    }

    private ChecklistItem checkShortSelling(String stockCode) {
        try {
            BigDecimal ratio = shortSellingService.getShortSellingRatio(stockCode);
            // §4c: 결측(null)은 "0.00% 충족"으로 위장하지 않는다(AUDIT 2026-07-07 P1-3).
            if (ratio == null) {
                return ChecklistItem.builder()
                        .key("shortSelling")
                        .label("공매도 비율")
                        .passed(false)
                        .dataMissing(true)
                        .value("미수집")
                        .threshold("< 5%")
                        .note("공매도 데이터 미수집 — 충족/미충족 판정 불가. 이 항목은 권고 산출에서 제외.")
                        .dimension("SHORT")
                        .build();
            }
            boolean passed = ratio.compareTo(SHORT_SELLING_LIMIT) < 0;
            return ChecklistItem.builder()
                    .key("shortSelling")
                    .label("공매도 비율")
                    .passed(passed)
                    .value(ratio.setScale(2, java.math.RoundingMode.HALF_UP) + "%")
                    .threshold("< 5%")
                    .note(passed ? "" : "공매도 압력 높음 — 진입 시 손절선 짧게.")
                    .dimension("SHORT")
                    .build();
        } catch (Exception e) {
            return errorItem("shortSelling", "공매도 비율", e);
        }
    }

    private ChecklistItem checkConsecutiveBuy(String stockCode) {
        try {
            // 외국인 또는 기관 중 어느 한쪽이라도 3일 연속매수면 통과.
            // 각자 독립 판정 — 단락평가(foreignMatch ||)로 묶으면 외국인만 매칭돼도 "외국인+기관"으로 표시됐다.
            boolean foreignMatch = matchesConsecutive(stockCode, "FOREIGN");
            boolean institutionMatch = matchesConsecutive(stockCode, "INSTITUTION");
            boolean passed = foreignMatch || institutionMatch;
            String who = foreignMatch && institutionMatch ? "외국인+기관"
                    : foreignMatch ? "외국인"
                    : institutionMatch ? "기관"
                    : "없음";
            return ChecklistItem.builder()
                    .key("consecutiveBuy")
                    .label("외국인/기관 연속매수")
                    .passed(passed)
                    .value(who)
                    .threshold("≥ 3일")
                    .note(passed ? "" : "수급 주체 진입 신호 없음 — 추세 형성 전.")
                    .dimension("SHORT")
                    .build();
        } catch (Exception e) {
            return errorItem("consecutiveBuy", "외국인/기관 연속매수", e);
        }
    }

    private boolean matchesConsecutive(String stockCode, String investorType) {
        List<ConsecutiveBuyDto> list = investorTradeService.getConsecutiveBuyStocks(investorType, CONSECUTIVE_BUY_MIN_DAYS);
        if (list == null) return false;
        return list.stream().anyMatch(dto -> stockCode.equals(dto.getStockCode()));
    }

    private ChecklistItem checkCompositeSignal(String stockCode) {
        try {
            CompositeSignalDto signal = compositeSignalService.evaluate(stockCode);
            int matched = signal == null ? 0 : signal.getMatchedCount();
            int total = signal == null ? 5 : signal.getTotalCount();
            boolean passed = matched >= COMPOSITE_SIGNAL_MIN_MATCHES;
            return ChecklistItem.builder()
                    .key("compositeSignal")
                    .label("복합 신호 매칭")
                    .passed(passed)
                    .value("신호 " + matched + "/" + total)
                    .threshold("≥ 3/5")
                    .note(passed ? "" : "차트·지지·가치·수급·AI 중 매칭 부족 — 후보 검증 더 필요.")
                    .dimension("MID")
                    .build();
        } catch (Exception e) {
            return errorItem("compositeSignal", "복합 신호 매칭", e);
        }
    }

    private StockConclusionDto safeConclusion(String stockCode) {
        try {
            return stockConclusionService.getConclusion(stockCode);
        } catch (Exception e) {
            log.warn("[BuyChecklist] 결론 조회 실패 - {}: {}", stockCode, e.getMessage());
            return null;
        }
    }

    private ChecklistItem checkConclusion(StockConclusionDto conclusion) {
        if (conclusion == null || !conclusion.isDataAvailable()) {
            return ChecklistItem.builder()
                    .key("conclusion")
                    .label("종합 결론")
                    .passed(false)
                    .value("데이터 부족")
                    .threshold("BUY 이상")
                    .note("종합 추천 스냅샷에 포함되지 않은 종목 — 다음 스냅샷까지 대기.")
                    .dimension("MID")
                    .build();
        }
        StockConclusionDto.Level level = conclusion.getLevel();
        boolean passed = level == StockConclusionDto.Level.STRONG_BUY || level == StockConclusionDto.Level.BUY;
        return ChecklistItem.builder()
                .key("conclusion")
                .label("종합 결론")
                .passed(passed)
                .value(level == null ? "N/A" : level.name())
                .threshold("BUY 이상")
                .note(passed ? "" : "종합 결론이 매수 단계 미달 — 분할 매수 또는 관망 권장.")
                .dimension("MID")
                .build();
    }

    private ChecklistItem errorItem(String key, String label, Exception e) {
        log.warn("[BuyChecklist] {} 체크 실패: {}", label, e.getMessage());
        return ChecklistItem.builder()
                .key(key)
                .label(label)
                .passed(false)
                .value("체크 불가")
                .threshold("")
                .note("일시적 데이터 조회 실패 — 잠시 후 재시도.")
                .build();
    }

    // ============================== 권고 산출 (가중치 차등) ==============================

    /**
     * 필수 항목 + 가산 항목 차등 룰 (phase 19).
     *
     * 필수 항목 (1개라도 미충족 → 즉시 NOT_RECOMMENDED, 가산 점수 무관):
     *   - tradable (거래정지/상폐 아님)
     *   - shortSelling (공매도 < 5%)
     *
     * 가산 항목 (3개 중 충족 개수에 따라 등급):
     *   - consecutiveBuy / compositeSignal / conclusion
     *
     * 등급:
     *   - 가산 3/3 → STRONG (필수 통과 + 모든 가산)
     *   - 가산 2/3 → MODERATE
     *   - 가산 1/3 → CAUTION
     *   - 가산 0/3 → NOT_RECOMMENDED (필수만 통과는 진입 근거 부족)
     */
    private static final java.util.Set<String> REQUIRED_KEYS = java.util.Set.of("tradable", "shortSelling");

    private Recommendation decideRecommendation(List<ChecklistItem> items) {
        // 필수 항목 검사 — 1개라도 fail 이면 즉시 NOT_RECOMMENDED.
        // 단 dataMissing(미수집)은 판정 불가이지 미충족이 아님 — 결측을 근거로 차단하지 않는다
        // (§4c, 봇 isHighShortSellingStock 결측=통과와 동일 극성).
        boolean requiredAllPassed = items.stream()
                .filter(i -> REQUIRED_KEYS.contains(i.getKey()))
                .filter(i -> !i.isDataMissing())
                .allMatch(ChecklistItem::isPassed);
        if (!requiredAllPassed) {
            return Recommendation.NOT_RECOMMENDED;
        }
        // 가산 항목 충족 개수
        long bonusPassed = items.stream()
                .filter(i -> !REQUIRED_KEYS.contains(i.getKey()))
                .filter(ChecklistItem::isPassed)
                .count();
        if (bonusPassed >= 3) return Recommendation.STRONG;
        if (bonusPassed >= 2) return Recommendation.MODERATE;
        if (bonusPassed >= 1) return Recommendation.CAUTION;
        return Recommendation.NOT_RECOMMENDED;
    }

    private String summary(Recommendation r, int passed, int total) {
        return switch (r) {
            case STRONG -> passed + "/" + total + " 충족 — 필수+가산 전부 통과. 진입 권장.";
            case MODERATE -> passed + "/" + total + " 충족 — 가산 2/3. 포지션 분할 권장.";
            case CAUTION -> passed + "/" + total + " 충족 — 가산 1/3. 미충족 항목 확인 후 신중 진입.";
            case NOT_RECOMMENDED -> passed + "/" + total + " 충족 — 필수 미통과 또는 가산 0/3. 진입 비권장.";
        };
    }
}
