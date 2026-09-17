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
    // 노후 판정 — "언제 기준인가"를 거래일로 본다(F5). 표시 계약용이며 게이트 극성은 바꾸지 않는다.
    private final MarketCalendarService marketCalendar;
    private final java.time.Clock clock;

    // 임계값 — 봇 룰과 동기화 필요.
    private static final BigDecimal SHORT_SELLING_LIMIT = new BigDecimal("5.0");
    private static final int CONSECUTIVE_BUY_MIN_DAYS = 3;
    private static final int COMPOSITE_SIGNAL_MIN_MATCHES = 3;

    /**
     * 연속매수 데이터 허용 지연(거래일) — 투자자 매매 집계는 장 마감 후 그날치가 들어온다.
     * 1 은 "수집이 하루 늦어도 통과"라는 뜻이고, 그보다 오래면 수집 정체로 본다(§4c 노후 가드와 같은 정신).
     */
    static final int CONSECUTIVE_BUY_MAX_LAG_TRADING_DAYS = 1;
    /**
     * 공매도 잔고 허용 지연(거래일) — <b>공시 지연이 있는 데이터</b>다. 잔고는 발생일로부터 며칠 뒤
     * 공표되므로 수급(당일 기준)과 같은 신선도를 적용하면 상시 "노후"가 된다. 공시 지연 + 수집 여유를
     * 합쳐 3 거래일로 둔다. ⚠ 이 값은 실측으로 다시 잡을 대상이다 — 운영에서 최신 기준일 분포를
     * 확인한 뒤 조정할 것(지금은 "당일 기준을 무작정 적용하지 않는다"가 목적).
     */
    static final int SHORT_SELLING_MAX_LAG_TRADING_DAYS = 3;

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

        // 판정 불가(미수집) 항목은 분모에서 제외한다(2026-08-05 감사) — 예전엔 미수집을 분모에
        // 남긴 채 "필수+가산 전부 통과"라고 써서 "4/5 충족 — 전부 통과" 같은 자기모순이 났다.
        // decideRecommendation 은 이미 dataMissing 을 판정에서 빼고 있어(§4c) 문구만 어긋나 있었다.
        int passed = (int) items.stream().filter(ChecklistItem::isPassed).count();
        int decidable = (int) items.stream().filter(i -> !i.isDataMissing()).count();
        int missing = items.size() - decidable;
        Recommendation recommendation = decideRecommendation(items);
        String summary = summary(recommendation, passed, decidable, missing);

        return BuyChecklistDto.builder()
                .stockCode(stockCode)
                .stockName(stockName)
                .items(items)
                .passedCount(passed)
                .totalCount(decidable)
                .recommendation(recommendation)
                .summary(summary)
                .build();
    }

    // ============================== 개별 체크 ==============================

    /**
     * 거래 가능 상태 — <b>확인됨·정지·확인 전</b>을 구분한다(F5, 2026-09-17 감사).
     *
     * <p>예전엔 {@code isActive()} 의 true 를 그대로 "정상"으로 적었다. 그 true 에는 <b>마스터 동기화 전
     * fail-open</b>이 섞여 있어, 동기화가 안 된 상태에서 전 종목이 "거래 가능 확인"으로 보였다.
     * 게이트로는 통과가 맞지만(막을 근거가 없음) <b>표시로는 미확인</b>이다 — 판정에서 뺀다.
     */
    private ChecklistItem checkTradable(String stockCode) {
        try {
            StockStatusService.ActiveStatus status = stockStatusService.activeStatus(stockCode);
            if (status == StockStatusService.ActiveStatus.UNVERIFIED) {
                return ChecklistItem.builder()
                        .key("tradable")
                        .label("거래 가능 상태")
                        .passed(false)
                        .dataMissing(true)
                        .value("확인 전")
                        .threshold("정상 거래")
                        .note("종목 마스터 동기화 전 — 거래 가능 여부 미확인. 판정에서 제외.")
                        .asOf(asOfText(stockStatusService.lastSyncAt()))
                        .dimension("META")
                        .build();
            }
            boolean active = status == StockStatusService.ActiveStatus.ACTIVE;
            return ChecklistItem.builder()
                    .key("tradable")
                    .label("거래 가능 상태")
                    .passed(active)
                    .value(active ? "정상" : "거래정지/상폐")
                    .threshold("정상 거래")
                    .note(active ? "" : "거래정지 또는 상폐 종목 — 매수 불가.")
                    .asOf(asOfText(stockStatusService.lastSyncAt()))
                    .dimension("META")
                    .build();
        } catch (Exception e) {
            return errorItem("tradable", "거래 가능 상태", e);
        }
    }

    private static String asOfText(java.time.LocalDateTime at) {
        return at == null ? null : at.toLocalDate().toString() + " 동기화";
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
            // ★ F5: 기준일을 함께 본다. 死피드의 낮은 비율이 "공매도 낮음 충족"으로 보이면 안 된다.
            //    ⚠ 공매도 잔고는 공시 지연이 있어 수급(당일 기준)과 같은 신선도를 적용하지 않는다.
            java.time.LocalDate asOf = shortSellingService.getShortSellingAsOf();
            boolean fresh = marketCalendar.isFreshWithin(
                    asOf, java.time.LocalDateTime.now(clock), SHORT_SELLING_MAX_LAG_TRADING_DAYS);
            if (!fresh) {
                return ChecklistItem.builder()
                        .key("shortSelling")
                        .label("공매도 비율")
                        .passed(false)
                        .dataMissing(true)
                        .value(ratio.setScale(2, java.math.RoundingMode.HALF_UP) + "%")
                        .threshold("< 5%")
                        .note(asOf == null
                                ? "공매도 기준일 미상 — 노후 여부를 알 수 없어 판정에서 제외."
                                : "공매도 데이터 노후(" + asOf + " 기준) — 판정에서 제외.")
                        .asOf(asOf == null ? null : asOf + " 기준")
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
                    .asOf(asOf + " 기준")
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
            ConsecutiveBuyDto foreign = findConsecutive(stockCode, "FOREIGN");
            ConsecutiveBuyDto institution = findConsecutive(stockCode, "INSTITUTION");
            boolean matched = foreign != null || institution != null;
            String who = foreign != null && institution != null ? "외국인+기관"
                    : foreign != null ? "외국인"
                    : institution != null ? "기관"
                    : "없음";
            // ★ F5: 목록에 있는지만 보면 안 된다. 목록은 DB 최신일 기준이라 수집이 멈추면 며칠 전
            //    연속매수가 계속 통과한다 — endDate 가 최근 거래일인지 확인한다.
            java.time.LocalDate endDate = latestEndDate(foreign, institution);
            boolean fresh = !matched || marketCalendar.isFreshWithin(
                    endDate, java.time.LocalDateTime.now(clock), CONSECUTIVE_BUY_MAX_LAG_TRADING_DAYS);
            if (matched && !fresh) {
                return ChecklistItem.builder()
                        .key("consecutiveBuy")
                        .label("외국인/기관 연속매수")
                        .passed(false)
                        .dataMissing(true)
                        .value(who)
                        .threshold("≥ 3일")
                        .note(endDate == null
                                ? "연속매수 종료일 미상 — 노후 여부를 알 수 없어 판정에서 제외."
                                : "연속매수 데이터 노후(" + endDate + " 종료) — 수집 정체 의심, 판정에서 제외.")
                        .asOf(endDate == null ? null : endDate + " 기준")
                        .dimension("SHORT")
                        .build();
            }
            return ChecklistItem.builder()
                    .key("consecutiveBuy")
                    .label("외국인/기관 연속매수")
                    .passed(matched)
                    .value(who)
                    .threshold("≥ 3일")
                    .note(matched ? "" : "수급 주체 진입 신호 없음 — 추세 형성 전.")
                    .asOf(endDate == null ? null : endDate + " 기준")
                    .dimension("SHORT")
                    .build();
        } catch (Exception e) {
            return errorItem("consecutiveBuy", "외국인/기관 연속매수", e);
        }
    }

    /** 해당 투자자 유형의 연속매수 행 — 없으면 null. endDate 신선도를 보려면 행 자체가 필요하다(F5). */
    private ConsecutiveBuyDto findConsecutive(String stockCode, String investorType) {
        List<ConsecutiveBuyDto> list = investorTradeService.getConsecutiveBuyStocks(investorType, CONSECUTIVE_BUY_MIN_DAYS);
        if (list == null) return null;
        return list.stream().filter(dto -> stockCode.equals(dto.getStockCode())).findFirst().orElse(null);
    }

    /** 둘 중 더 최근 종료일 — 순수. 둘 다 없거나 날짜가 없으면 null. */
    static java.time.LocalDate latestEndDate(ConsecutiveBuyDto a, ConsecutiveBuyDto b) {
        java.time.LocalDate da = a == null ? null : a.getEndDate();
        java.time.LocalDate db = b == null ? null : b.getEndDate();
        if (da == null) return db;
        if (db == null) return da;
        return da.isAfter(db) ? da : db;
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
                    .dataMissing(true)
                    .value("데이터 부족")
                    .threshold("BUY 이상")
                    .note("종합 추천 스냅샷에 포함되지 않은 종목 — 판정 불가(다음 스냅샷까지 대기).")
                    .dimension("MID")
                    .build();
        }
        // ★ F5/F4: 노후·거래정지로 현재 판단에 못 쓰는 결론은 '미충족'이 아니라 '판정 불가'다.
        //    WAIT 로 내려온 값을 미충족으로 세면 "오래된 데이터라 모른다"가 "매수 단계 미달"로 둔갑한다.
        if (!conclusion.isCurrentlyValid()) {
            return ChecklistItem.builder()
                    .key("conclusion")
                    .label("종합 결론")
                    .passed(false)
                    .dataMissing(true)
                    .value("판정 불가")
                    .threshold("BUY 이상")
                    .note(conclusion.getStaleReason() == null
                            ? "현재 판단에 쓸 수 있는 추천 스냅샷이 아님 — 판정에서 제외."
                            : conclusion.getStaleReason())
                    .asOf(conclusion.getDataSessionDate() == null ? null
                            : conclusion.getDataSessionDate() + " 기준")
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

    /**
     * 조회 실패 항목 — <b>판정 불가</b>다(F5, 2026-09-17 감사).
     *
     * <p>예전엔 {@code dataMissing} 없이 {@code passed=false} 라, 분모에서 "실제 미충족"과 같이 세어
     * "N/5 충족"이 실패 개수만큼 낮아 보였다. 실패는 못 센 것이지 미달이 아니다(§4c).
     * ⚠ 결측을 <b>매수 차단</b>으로 바꾸지는 않는다 — {@code decideRecommendation} 은 이미 결측을 판정에서 뺀다.
     */
    private ChecklistItem errorItem(String key, String label, Exception e) {
        log.warn("[BuyChecklist] {} 체크 실패: {}", label, e.getMessage());
        return ChecklistItem.builder()
                .key(key)
                .label(label)
                .passed(false)
                .dataMissing(true)
                .value("체크 불가")
                .threshold("")
                .note("일시적 데이터 조회 실패 — 판정 불가(잠시 후 재시도).")
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

    /**
     * 요약 문구 — <b>판정 가능한 항목</b>만 분모로 쓰고, 미수집이 있으면 그 사실을 함께 밝힌다.
     *
     * <p>예전엔 미수집 항목을 분모에 남긴 채 "필수+가산 전부 통과"라고 써서, 공매도 미수집 시
     * <b>"4/5 충족 — 필수+가산 전부 통과"</b> 같은 자기모순이 화면에 떴다(2026-08-05 감사).
     * 판정 자체는 이미 미수집을 제외하고 있었으므로(§4c) 문구·분모만 사실에 맞춘다.
     */
    private String summary(Recommendation r, int passed, int decidable, int missing) {
        String base = passed + "/" + decidable + " 충족";
        String tail = missing > 0 ? " (판정 불가 " + missing + "개 제외)" : "";
        return switch (r) {
            case STRONG -> base + tail + " — 판정 가능한 필수+가산 전부 통과. 진입 권장.";
            case MODERATE -> base + tail + " — 가산 2/3. 포지션 분할 권장.";
            case CAUTION -> base + tail + " — 가산 1/3. 미충족 항목 확인 후 신중 진입.";
            case NOT_RECOMMENDED -> base + tail + " — 필수 미통과 또는 가산 0/3. 진입 비권장.";
        };
    }
}
