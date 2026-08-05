package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockConclusionDto;
import com.myplatform.backend.dto.StockConclusionDto.EntryPosition;
import com.myplatform.backend.dto.StockConclusionDto.Factor;
import com.myplatform.backend.dto.StockConclusionDto.Level;
import com.myplatform.backend.dto.StockConclusionDto.TradePlan;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.dto.SupportResistanceDto;
import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.util.AtrCalculator;
import com.myplatform.backend.util.AtrExitRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 종목별 룰 기반 결론 한 줄 산출.
 *
 * 입력: RecommendationSnapshot (종목별 카테고리 점수 5종 — earnings / supplyDemand / technical /
 *       sectorMomentum / valueStability + totalScore).
 *
 * 출력: 4-level 결론 + 한 줄 헤드라인 + factor 목록.
 *
 * 룰 (우선순위 순):
 *  1) total ≥ 75 + AI 양수: STRONG_BUY — "단기 모멘텀 + 다수 시그널 합의"
 *  2) value ≥ 12 + total < 55: HOLD — "장기 저평가 우량주이나 단기 추세 약함, 분할 매수 후보"
 *  3) supplyDemand ≥ 15 + technical < 8: BUY (caution) — "수급 강하나 기술적 신호 부족, 추격 신중"
 *  4) total ≥ 55: BUY — "매수 신호 양호"
 *  5) total < 55: WAIT — "현재 진입 신호 약함"
 *
 * Phase 5 범위: RecommendationSnapshot 만 입력. Phase 7 에서 AI 점수 / 수급 급증 / 복합 신호 추가.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StockConclusionService {

    private final RecommendationSnapshotRepository snapshotRepository;
    private final SignalOutcomeRepository signalOutcomeRepository;
    private final StockPriceService stockPriceService;
    private final StockPriceHistoryRepository stockPriceHistoryRepository;
    // 지지선 거리(진입 위치 눌림 판정)용 — 기존 캐시된 SR 카드 데이터 재사용. ObjectProvider 로 순환 회피·best-effort.
    private final ObjectProvider<ChartPatternService> chartPatternProvider;

    // 결론 임계값 — RecommendationService 상수와 동기화 필요.
    private static final int STRONG_BUY_THRESHOLD = 75;
    private static final int BUY_THRESHOLD = 55;
    private static final int VALUE_STRONG_THRESHOLD = 12;       // calculateValueTop10 의 cap 20점 기준
    private static final int SUPPLY_DEMAND_STRONG = 15;
    private static final int TECHNICAL_WEAK = 8;

    // 매매 계획 % — AutoTradingBotService 스윙 기준(-3%/+5%)과 동일.
    private static final BigDecimal PLAN_STOP_PCT = new BigDecimal("-3");
    private static final BigDecimal PLAN_TARGET_PCT = new BigDecimal("5");
    // "단기 강 + 밸류 매우 약" 충돌 시 타이트 조정 — conflictNote 룰 1("익절 3% 내")과 일관.
    private static final BigDecimal PLAN_STOP_PCT_TIGHT = new BigDecimal("-2");
    private static final BigDecimal PLAN_TARGET_PCT_TIGHT = new BigDecimal("3");
    /** MFE/MAE 집계 윈도우 (일) — 표본 안정성 위해 적중률(30일)보다 길게. */
    private static final int MFE_MAE_WINDOW_DAYS = 90;
    /** ATR14 계산용 일봉 로드 행 수 — 봇 computeEntryAtrQuiet(40)과 동일. */
    private static final int ATR_HISTORY_ROWS = 40;

    public StockConclusionDto getConclusion(String stockCode) {
        Optional<RecommendationSnapshot> snapshotOpt = snapshotRepository.findLatestByStockCode(stockCode);
        if (snapshotOpt.isEmpty()) {
            return notAvailable(stockCode);
        }
        return build(snapshotOpt.get());
    }

    private StockConclusionDto notAvailable(String stockCode) {
        return StockConclusionDto.builder()
                .stockCode(stockCode)
                .stockName("")
                .level(Level.WAIT)
                .headline("종합 추천 스냅샷에 포함되지 않은 종목입니다 — 시그널 정보 부족.")
                .guidance("관심종목으로 등록하면 다음 스냅샷부터 시그널이 누적됩니다.")
                .factors(List.of())
                .dataAt(null)
                .dataAvailable(false)
                .build();
    }

    private StockConclusionDto build(RecommendationSnapshot s) {
        int total = s.getTotalScore();
        // P3-3: valueStability NULL=NA — 강가치 판정은 산출된 값이 있을 때만 true(NA=미충족, 기존 -1 동작 동일).
        boolean valueStrong = s.getValueStability() != null && s.getValueStability() >= VALUE_STRONG_THRESHOLD;
        int supplyDemand = s.getSupplyDemand();
        int technical = s.getTechnical();

        List<Factor> factors = buildFactors(s);

        // 룰 우선순위
        Level level;
        String headline;
        String guidance;

        if (total >= STRONG_BUY_THRESHOLD) {
            level = Level.STRONG_BUY;
            headline = "단기 모멘텀 + 다수 시그널 합의 — 매수 적기로 평가.";
            guidance = valueStrong
                    ? "밸류(저평가)도 양호 — 전량 진입 고려."
                    : "단기 추세 강하지만 밸류 매력도는 보통 — 익절 가까이 잡고 진입.";
        } else if (valueStrong && total < BUY_THRESHOLD) {
            level = Level.HOLD;
            headline = "장기 저평가 우량주이나 단기 추세 약함 — 분할 매수 후보.";
            guidance = "외국인/기관 순매수 전환 또는 20일선 지지 확인 후 진입 권장.";
        } else if (supplyDemand >= SUPPLY_DEMAND_STRONG && technical < TECHNICAL_WEAK) {
            level = Level.BUY;
            headline = "수급은 강하나 기술적 신호 부족 — 추격 매수 신중.";
            guidance = "단기 눌림목 또는 RSI 조정 시 분할 진입.";
        } else if (total >= BUY_THRESHOLD) {
            level = Level.BUY;
            headline = "매수 신호 양호 — 다수 카테고리 점수 충족.";
            guidance = "리스크 카드(공시/공매도) 확인 후 진입.";
        } else {
            level = Level.WAIT;
            headline = "현재 진입 신호 약함 — 관망 권장.";
            guidance = "수급/기술 신호 회복 또는 가치 점수 상승 대기.";
        }

        return StockConclusionDto.builder()
                .stockCode(s.getStockCode())
                .stockName(s.getStockName())
                .level(level)
                .headline(headline)
                .guidance(guidance)
                .conflictNote(detectConflicts(s))
                .entryPosition(buildEntryPosition(s))
                .factors(factors)
                .tradePlan(buildTradePlan(s, level))
                .dataAt(s.getSnapshotAt())
                .dataAvailable(true)
                .build();
    }

    // ==================== 진입 위치 (표시 전용 — 산식/점수 무변경) ====================

    /** 눌림 판정 지지선 근접 상한 % — 현재가가 지지선 위 이 값 이내면 "눌림 구간". */
    static final double PULLBACK_SUPPORT_MAX_PCT = 3.0;
    // overheatPenalty() 가 스냅샷에 남긴 과열 태그 파싱 — "⚠RSI78과열" / "⚠5일+18%과열" / "⚠볼린저상단돌파".
    private static final Pattern RSI_OVERHEAT_TAG = Pattern.compile("RSI(\\d+)과열");
    private static final Pattern FIVE_DAY_OVERHEAT_TAG = Pattern.compile("5일\\+(\\d+)%과열");

    /**
     * 진입 위치 한 줄 — overheatPenalty 구성 신호(스냅샷 태그 재사용, <b>재계산 없음</b>) + 지지선 거리 조립.
     * 과열 신호 0개일 때만 지지선(캐시된 SR 카드) 조회 → 눌림 판정. null=중립/판단 불가(줄 미렌더, §4c).
     */
    private EntryPosition buildEntryPosition(RecommendationSnapshot s) {
        OverheatSignals overheat = parseOverheat(s.getTags());
        Double supportDist = overheat.count() == 0 ? nearestSupportDistanceQuiet(s.getStockCode()) : null;
        return classifyEntryPosition(overheat, supportDist);
    }

    /** 과열 신호 3종(스냅샷 태그 재사용). */
    record OverheatSignals(Integer rsi, boolean bollinger, Integer fiveDayPct) {
        int count() { return (rsi != null ? 1 : 0) + (bollinger ? 1 : 0) + (fiveDayPct != null ? 1 : 0); }
    }

    /**
     * 스냅샷 태그에서 과열 신호 추출 — <b>순수 함수(테스트 대상)</b>. overheatPenalty 가 임계(RSI≥70/5일≥15/
     * 볼린저 상단) 초과 시 남긴 태그의 <b>존재</b>가 곧 신호(값은 표시용으로 함께 추출). 재계산 없음.
     */
    static OverheatSignals parseOverheat(String tagsCsv) {
        if (tagsCsv == null || tagsCsv.isBlank()) return new OverheatSignals(null, false, null);
        Integer rsi = null, five = null;
        Matcher m1 = RSI_OVERHEAT_TAG.matcher(tagsCsv);
        if (m1.find()) rsi = Integer.parseInt(m1.group(1));
        Matcher m2 = FIVE_DAY_OVERHEAT_TAG.matcher(tagsCsv);
        if (m2.find()) five = Integer.parseInt(m2.group(1));
        boolean bollinger = tagsCsv.contains("볼린저상단돌파");
        return new OverheatSignals(rsi, bollinger, five);
    }

    /**
     * 진입 위치 판정 — <b>순수 함수(테스트 대상)</b>.
     * 과열 2개↑ = OVERHEATED / 1개 = CAUTION / 0개 & 지지선 +{@value #PULLBACK_SUPPORT_MAX_PCT}% 이내 = PULLBACK /
     * 그 외(중립·지지선 결측) = null(줄 미렌더, §4c).
     * @param supportDistancePct 현재가가 최근접 지지선 위로 떨어진 거리 %(≥0). null=미상.
     */
    static EntryPosition classifyEntryPosition(OverheatSignals sig, Double supportDistancePct) {
        if (sig == null) return null;
        List<String> reasons = new ArrayList<>();
        if (sig.rsi() != null) reasons.add("RSI " + sig.rsi());
        if (sig.fiveDayPct() != null) reasons.add("5일 +" + sig.fiveDayPct() + "%");
        if (sig.bollinger()) reasons.add("볼린저 상단");

        int count = sig.count();
        if (count >= 2) {
            return EntryPosition.builder().zone("OVERHEATED")
                    .text("과열 구간: " + String.join(" · ", reasons)).build();
        }
        if (count == 1) {
            return EntryPosition.builder().zone("CAUTION")
                    .text("과열 주의: " + reasons.get(0)).build();
        }
        // count == 0 — 지지선 근접이면 눌림, 아니면 미렌더(중립/결측)
        if (supportDistancePct != null && supportDistancePct >= 0
                && supportDistancePct <= PULLBACK_SUPPORT_MAX_PCT) {
            return EntryPosition.builder().zone("PULLBACK")
                    .text(String.format("눌림 구간: 지지선 +%.1f%%", supportDistancePct)).build();
        }
        return null;
    }

    /**
     * 최근접 지지선까지 현재가 거리 %(양수 = 지지선 위) — 기존 캐시된 SR 카드 데이터 재사용(재계산 아님).
     * SR 미가용·지지선 없음·결측이면 null(§4c). best-effort.
     */
    private Double nearestSupportDistanceQuiet(String stockCode) {
        try {
            ChartPatternService cps = chartPatternProvider.getIfAvailable();
            if (cps == null) return null;
            SupportResistanceDto sr = cps.detectSupportResistance(stockCode);
            if (sr == null || sr.getSupport() == null || sr.getSupport().isEmpty()) return null;
            // support = 현재가 아래(가까운 순). distancePct 음수(아래) → |값| = 현재가가 지지선 위로 떨어진 거리.
            SupportResistanceDto.Level nearest = sr.getSupport().get(0);
            if (nearest == null || nearest.getDistancePct() == null) return null;
            return Math.abs(nearest.getDistancePct().doubleValue());
        } catch (Exception e) {
            log.debug("[Conclusion] 지지선 거리 조회 실패 {}: {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * 매매 계획 — STRONG_BUY / BUY 일 때만. 손절/목표 % 는 스윙 봇 기준(-3/+5),
     * "단기 강 + 밸류 매우 약"(conflictNote 룰 1) 조합이면 타이트(-2/+3).
     * 현재가는 공용 단일 경로(StockPriceService.getStockPrice) 경유 — 실패 시 % 만 제공.
     * 과거 동일 시그널의 MFE/MAE 평균을 함께 실어 손절/목표선의 현실성을 보여준다.
     */
    private TradePlan buildTradePlan(RecommendationSnapshot s, Level level) {
        if (level != Level.STRONG_BUY && level != Level.BUY) return null;

        // P3-3: valueStability NULL=NA(미산출) — 타이트 판정 제외(구 -1 sentinel 의 >= 0 가드와 동일 동작).
        boolean tight = s.getTotalScore() >= STRONG_BUY_THRESHOLD
                && s.getValueStability() != null && s.getValueStability() < 4;
        BigDecimal stopPct = tight ? PLAN_STOP_PCT_TIGHT : PLAN_STOP_PCT;
        BigDecimal targetPct = tight ? PLAN_TARGET_PCT_TIGHT : PLAN_TARGET_PCT;

        BigDecimal basePrice = null;
        try {
            StockPriceDto price = stockPriceService.getStockPrice(s.getStockCode());
            if (price != null && price.getCurrentPrice() != null
                    && price.getCurrentPrice().signum() > 0) {
                basePrice = price.getCurrentPrice();
            }
        } catch (Exception e) {
            log.debug("[Conclusion] 매매 계획 기준가 조회 실패 {}: {}", s.getStockCode(), e.getMessage());
        }

        BigDecimal avgMfe = null, avgMae = null;
        long sampleCount = 0;
        try {
            List<Object[]> rows = signalOutcomeRepository.aggregateMfeMae(
                    level.name(), LocalDate.now().minusDays(MFE_MAE_WINDOW_DAYS));
            if (!rows.isEmpty() && rows.get(0)[0] != null) {
                Object[] row = rows.get(0);
                sampleCount = ((Number) row[0]).longValue();
                if (sampleCount > 0) {
                    avgMfe = row[1] == null ? null
                            : new BigDecimal(row[1].toString()).setScale(2, RoundingMode.HALF_UP);
                    avgMae = row[2] == null ? null
                            : new BigDecimal(row[2].toString()).setScale(2, RoundingMode.HALF_UP);
                }
            }
        } catch (Exception e) {
            log.debug("[Conclusion] MFE/MAE 집계 실패: {}", e.getMessage());
        }

        // ATR 참고 손절/목표 (unverified 병기) — 기존 util 재사용(AtrCalculator/AtrExitRule, 봇 ATR 세트와
        // 동일 정의), PLAN_* 산식·기존 필드 무변경. 미산출(§4c)이면 null → 프론트 줄 미렌더.
        AtrExitRule.Levels atrLevels = computeAtrLevelsQuiet(s.getStockCode(), basePrice);

        return TradePlan.builder()
                .basePrice(basePrice)
                .stopLossPct(stopPct)
                .targetPct(targetPct)
                .stopLossPrice(applyPct(basePrice, stopPct))
                .targetPrice(applyPct(basePrice, targetPct))
                .avgMfePct(avgMfe)
                .avgMaePct(avgMae)
                .mfeMaeSampleCount(sampleCount)
                .atrStopPct(atrLevels == null ? null
                        : atrLevels.stopPct().setScale(1, RoundingMode.HALF_UP))
                .atrTargetPct(atrLevels == null ? null
                        : atrLevels.targetPct().setScale(1, RoundingMode.HALF_UP))
                .build();
    }

    /**
     * ATR14(Wilder, StockPriceHistory 일봉) × 2.5 참고 청산 레벨 — 신규 산식 없이 기존 util 재사용.
     * ATR null(유효 일봉 15개 미만)·basePrice null·조회 실패 = null(§4c — 위장 금지, 프론트 줄 미렌더).
     */
    private AtrExitRule.Levels computeAtrLevelsQuiet(String stockCode, BigDecimal basePrice) {
        if (basePrice == null) return null;
        try {
            BigDecimal atr = AtrCalculator.atr14(stockPriceHistoryRepository
                    .findByStockCodeOrderByTradeDateDesc(stockCode, PageRequest.of(0, ATR_HISTORY_ROWS)));
            return AtrExitRule.judge(basePrice, atr);
        } catch (Exception e) {
            log.debug("[Conclusion] ATR 참고치 산출 실패 {}: {}", stockCode, e.getMessage());
            return null;
        }
    }

    /** basePrice × (1 + pct/100), 원 단위 반올림. basePrice null 이면 null. */
    private static BigDecimal applyPct(BigDecimal basePrice, BigDecimal pct) {
        if (basePrice == null) return null;
        return basePrice.multiply(BigDecimal.ONE.add(
                        pct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                .setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * 시그널 간 충돌 / 주의 사항 감지 (phase 22b + phase 33 룰 7~8).
     *
     * 4-level 헤드라인이 단일 결론을 주는 반면, 이 메서드는 카테고리 간 충돌이나 특이 조합을
     * 따로 짚어준다. 사용자가 "왜 BUY 인데 익절 짧게?" 같은 의문에 답이 된다.
     *
     * 룰 (첫 매칭만 반환 — 가장 중요한 충돌 1개):
     *  1) 단기 강 + 장기 매우 약 → 익절 짧게
     *  2) 단기 강 + 기술 약 → 고점 추격 가능성
     *  3) 장기+수급 동조 + 단기 차트 약 → 분할 매수 / 눌림목 대기
     *  4) 실적 강 + 시장 관심 없음 → 매집 후보
     *  5) 섹터 강 + 종목 차트 약 → 섹터 ETF 대안
     *  6) 수급 후반 페이즈 + BUY 이상 → 카운터파티 만들어진 단계 (phase 33)
     *  7) AI 전략 발굴 + 객관 지표 미충족 → 후속 시그널 대기 (phase 33)
     *  8) 모든 카테고리 평범 → 더 매력적 후보 우선
     */
    private String detectConflicts(RecommendationSnapshot s) {
        int total = s.getTotalScore();
        int earnings = s.getEarnings();
        int supplyDemand = s.getSupplyDemand();
        int technical = s.getTechnical();
        int sector = s.getSectorMomentum();
        // P3-3: NULL=NA(미산출) — 밸류 룰(1·3)은 산출된 값이 있을 때만 발동(구 -1 sentinel 동작 동일).
        Integer value = s.getValueStability();
        String tags = s.getTags();

        // 1. 단기 강 + 장기 매우 약 — 펀더멘털 받쳐주지 않는 모멘텀 진입은 익절 짧게.
        if (total >= STRONG_BUY_THRESHOLD && value != null && value < 4) {
            return "⚠️ 단기 모멘텀 강함 + 밸류 매력도 매우 낮음 — 익절 3% 내, 손절 타이트.";
        }
        // 2. 단기 강 + 기술 약 — 거래량/모멘텀은 좋은데 차트 기준이 안 받쳐줌 → 고점 추격 위험.
        if (total >= STRONG_BUY_THRESHOLD && technical > 0 && technical < 6) {
            return "⚠️ 종합 점수 높으나 기술적 지표 약함 — 고점 추격 가능성, RSI 과열 확인 권장.";
        }
        // 3. 장기+수급 동조 + 단기 차트 약 — 매집 진행 중이나 기술적 진입 타이밍 미성숙.
        if (value != null && value >= VALUE_STRONG_THRESHOLD && supplyDemand >= 12 && technical > 0 && technical < 8) {
            return "💡 장기 저평가 + 수급 강 + 단기 차트 약함 — 분할 매수 또는 20일선 지지 대기.";
        }
        // 4. 실적 강 + 시장 관심 없음 — 컨센서스 형성 전 매집 기회.
        if (earnings >= 15 && total < BUY_THRESHOLD) {
            return "💡 실적 우수하나 시장 관심 부족 — 컨센서스 형성 전 매집 후보.";
        }
        // 5. 섹터 강 + 종목 차트 약 — 종목보다 섹터 ETF 가 더 효율적일 수 있음.
        if (sector >= 15 && technical > 0 && technical < 6) {
            return "💡 섹터 흐름 강하나 종목 차트 약함 — 섹터 ETF 대안 고려.";
        }
        // 6. (phase 33) 수급 후반 페이즈 + 매수 신호 — phase 31 P0-2 의 "외국인 5일+ 후반" 태그 활용.
        //    외국인/기관이 카운터파티 만든 단계로 진입했을 가능성, 추격 매수 신중.
        if (tags != null && tags.contains("후반") && total >= BUY_THRESHOLD) {
            return "⚠️ 수급 5일+ 후반 페이즈 — 외국인/기관 카운터파티 만든 단계 가능성, 분할 진입 권장.";
        }
        // 7. (phase 33) AI 전략 발굴 + 객관 지표 미충족 — AI 시드는 후보 풀에 들어왔지만
        //    4 카테고리 정량 점수가 BUY 컷에 미달. 후속 시그널(실적/수급/기술) 가속 대기.
        if (s.getAiStrategy() > 0 && total < BUY_THRESHOLD) {
            return "💡 AI 전략 발굴 후보 — 4 카테고리 객관 지표 미흡, 실적/수급/기술 가속 대기.";
        }
        // 8. 모든 카테고리 평범 (각각 6~10점) — 뚜렷한 강점 없는 평균 종목.
        if (allMidRange(earnings, supplyDemand, technical, sector)) {
            return "⚪ 모든 카테고리 평범 — 뚜렷한 강점 없음, 더 매력적인 후보 우선 검토.";
        }
        return null;
    }

    private boolean allMidRange(int... scores) {
        for (int s : scores) {
            if (s < 6 || s > 10) return false;
        }
        return true;
    }

    private List<Factor> buildFactors(RecommendationSnapshot s) {
        String tags = s.getTags();
        List<Factor> list = new ArrayList<>();
        list.add(Factor.builder()
                .key("total")
                .label("종합 점수")
                .dimension("MID")
                .score(s.getTotalScore())
                .verdict(verdictFor(s.getTotalScore(), BUY_THRESHOLD, STRONG_BUY_THRESHOLD))
                // 실제 산식과 일치시킴(2026-08-05 감사) — 예전 문구 "5카테고리 합산"은 틀렸다.
                // 총점은 4카테고리 raw 를 80으로 정규화한 값이고 가치(밸류)는 합산에 안 들어간다.
                // 사용자가 화면의 5개 숫자를 더해도 총점과 안 맞아 "밸류가 총점을 깎았다"고 오독했다.
                .note("실적·수급·기술·섹터 4카테고리 정규화 (가치는 미포함 — 75점↑·가치 12↑일 때만 +2)")
                .build());
        list.add(Factor.builder()
                .key("earnings")
                .label("실적")
                .dimension("LONG")
                .score(s.getEarnings())
                .verdict(verdictFor(s.getEarnings(), 8, 15))
                .note(withEvidence("어닝 서프라이즈 + 매출/영업이익 추세", tags, "earnings"))
                .build());
        list.add(Factor.builder()
                .key("supplyDemand")
                .label("수급")
                .dimension("SHORT")
                .score(s.getSupplyDemand())
                .verdict(verdictFor(s.getSupplyDemand(), 8, SUPPLY_DEMAND_STRONG))
                .note(withEvidence("외국인/기관 순매수 추세", tags, "supplyDemand"))
                .build());
        list.add(Factor.builder()
                .key("technical")
                .label("기술적")
                .dimension("SHORT")
                .score(s.getTechnical())
                .verdict(verdictFor(s.getTechnical(), TECHNICAL_WEAK, 15))
                .note(withEvidence("RSI / 이동평균선 / 모멘텀", tags, "technical"))
                .build());
        list.add(Factor.builder()
                .key("sectorMomentum")
                .label("섹터 흐름")
                .dimension("SHORT")
                .score(s.getSectorMomentum())
                .verdict(verdictFor(s.getSectorMomentum(), 8, 15))
                .note(withEvidence("섹터 거래대금 INFLOW/OUTFLOW", tags, "sectorMomentum"))
                .build());
        // ⚠ valueStability/growth 는 NULL=NA(데이터 없음, P3-3 — 구 -1 sentinel). NA 면 factor 자체를 숨긴다
        //   (결론카드 "데이터 없으면 조용히 숨김" 규약 — NA 를 NEGATIVE 로 오표시하지 않기 위함).
        if (s.getValueStability() != null) {
            list.add(Factor.builder()
                    .key("valueStability")
                    .label("밸류 매력도")
                    .dimension("LONG")
                    .score(s.getValueStability())
                    .verdict(verdictFor(s.getValueStability(), 8, VALUE_STRONG_THRESHOLD))
                    // 라벨/노트 정정 — "지금 싼가(저평가 정도)"를 보는 밸류 지표. 산업 전망/성장성은
                    // 아래 '성장성' factor 가 담당. 점수 낮음 = "안 싸다"이지 "장기 전망 나쁨"이 아님.
                    .note(withEvidence("저평가 정도 (PBR·ROE·부채비율·흑자) — 전망 아님", tags, "valueStability"))
                    .build());
        }
        if (s.getGrowth() != null) {
            list.add(Factor.builder()
                    .key("growth")
                    .label("성장성")
                    .dimension("LONG")
                    .score(s.getGrowth())
                    .verdict(verdictFor(s.getGrowth(), 8, VALUE_STRONG_THRESHOLD))
                    .note(withEvidence("매출·이익 성장률 + PEG (산업/실적 성장)", tags, "growth"))
                    .build());
        }
        return list;
    }

    // 카테고리별 근거 태그 키워드 — RecommendationService 가 스냅샷에 남기는 태그 어휘 기준.
    // 태그가 "왜 이 점수인지"의 실측 근거 (예: 수급 16점 ← "외국인3일연속(초기), 기관순매수").
    private static final String[] EARNINGS_KEYWORDS = {"실적", "흑자전환", "어닝"};
    private static final String[] SUPPLY_KEYWORDS = {"외국인", "기관", "수급"};
    private static final String[] TECHNICAL_KEYWORDS = {"골든크로스", "정배열", "RSI", "볼린저", "MA20", "과열", "기술"};
    private static final String[] SECTOR_KEYWORDS = {"섹터"};
    private static final String[] VALUE_KEYWORDS = {"PBR", "저부채", "흑자+자본", "우량", "STRONG+VALUE"};
    private static final String[] GROWTH_KEYWORDS = {"성장", "이익급증", "PEG"};

    /**
     * 스냅샷 태그 중 해당 카테고리 근거를 base note 에 덧붙인다 (최대 3개).
     * 매칭 태그 없으면 base 그대로 — 과거 행/태그 빈약 종목에서도 안전.
     */
    static String withEvidence(String base, String tagsCsv, String factorKey) {
        if (tagsCsv == null || tagsCsv.isBlank()) return base;
        String[] keywords = switch (factorKey) {
            case "earnings" -> EARNINGS_KEYWORDS;
            case "supplyDemand" -> SUPPLY_KEYWORDS;
            case "technical" -> TECHNICAL_KEYWORDS;
            case "sectorMomentum" -> SECTOR_KEYWORDS;
            case "valueStability" -> VALUE_KEYWORDS;
            case "growth" -> GROWTH_KEYWORDS;
            default -> null;
        };
        if (keywords == null) return base;
        List<String> matched = new ArrayList<>();
        for (String tag : tagsCsv.split(",")) {
            String t = tag.trim();
            if (t.isEmpty()) continue;
            for (String kw : keywords) {
                if (t.contains(kw)) {
                    matched.add(t);
                    break;
                }
            }
            if (matched.size() >= 3) break;
        }
        if (matched.isEmpty()) return base;
        return base + " · 근거: " + String.join(", ", matched);
    }

    /**
     * 점수 → 판정. <b>테스트 대상(static)</b>.
     * ⚠ score &lt; 0 → <b>"N/A"</b> 방어 가드 유지 — 엔티티는 P3-3(V48)로 NULL=NA 라 이 함수에 음수가
     * 오지 않지만, DTO(RecommendationDto)/표시 계층은 여전히 -1=NA sentinel 을 쓰므로 미래 호출부 보호용.
     * (-1 을 실제 NEGATIVE 로 오판하던 기존 버그의 재발 방지.)
     */
    static String verdictFor(int score, int neutralMin, int positiveMin) {
        if (score < 0) return "N/A";
        if (score >= positiveMin) return "POSITIVE";
        if (score >= neutralMin) return "NEUTRAL";
        return "NEGATIVE";
    }
}
