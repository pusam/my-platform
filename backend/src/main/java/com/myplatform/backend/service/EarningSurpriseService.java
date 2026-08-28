package com.myplatform.backend.service;

import com.myplatform.backend.dto.EarningSurpriseDto;
import com.myplatform.backend.dto.EarningSurpriseDto.SurpriseType;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.entity.StockQuarterlyFinancial;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockQuarterlyFinancialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * 어닝 서프라이즈 감지 서비스 — 영업이익(없으면 순이익) 전분기 대비 ±20% / 적자→흑자 전환.
 *
 * <h3>입력 경로가 둘이다 (AUDIT 2026-08-21 R1)</h3>
 * <ul>
 *   <li><b>레거시</b> {@code stock_financial_data} — 이름과 달리 <b>일별 스냅샷</b>이다.
 *       매일 {@code reportDate=오늘} 로 TTM 합이 한 행씩 쌓이므로 "최신 2행"은 사실
 *       <b>오늘 vs 어제</b>이고, TTM 은 하루 사이 거의 안 변해 변화율 ≈ 0 →
 *       서프라이즈가 사실상 발생하지 않는다(= earnings 카테고리 死). 인접분기 가드(≤120일)는
 *       gap=1 이라 그대로 통과하고, {@code isEarningsReportFresh(200일)} 도
 *       reportDate 가 항상 오늘이라 no-op 이었다.</li>
 *   <li><b>분기</b> {@code stock_quarterly_financial} — KIS 손익계산서의 {@code stac_yymm}
 *       단위 원본. 비교가 진짜 "전분기 대비"가 되고 신선도 가드도 의미를 갖는다.</li>
 * </ul>
 *
 * <p><b>전환은 설정 한 줄</b>({@code recommendation.earnings.quarterly-source}, 기본 false).
 * 켜면 composite 의 earnings 입력이 실제로 살아나 <b>점수·후보 수가 움직인다</b> — 그래서
 * 수급 캡(P1-6)과 같은 방식으로 가역 플래그를 두고, 켜는 시점을 사람이 정해 기록한다
 * (측정 표본의 경계가 되기 때문). 판정 산식(임계 ±20%, POSITIVE 는 흑자 필수)은 두 경로가
 * <b>같은 함수</b>({@code classify})를 쓴다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EarningSurpriseService {

    private final StockFinancialDataRepository financialDataRepository;
    private final StockQuarterlyFinancialRepository quarterlyRepository;
    private final StockMasterService stockMasterService;
    private final TelegramNotificationService telegramService;

    /**
     * true 면 분기 원본 테이블을 입력으로 쓴다(R1 의 실제 수정). 기본 false = 레거시 경로 유지.
     *
     * <p>가역 플래그인 이유: 켜는 순간 earnings 가 살아나 composite 총점·validCount·후보 수가
     * 동시에 움직인다. 되돌릴 수 없는 변경으로 배포하면 "언제부터의 표본이 현재 산식인가"를
     * 나중에 특정할 수 없다.
     */
    @Value("${recommendation.earnings.quarterly-source:false}")
    private boolean useQuarterlySource;

    // 서프라이즈 임계값: 영업이익 변화율 ±20%
    private static final BigDecimal SURPRISE_THRESHOLD = new BigDecimal("20");
    private static final BigDecimal NEGATIVE_SURPRISE_THRESHOLD = new BigDecimal("-20");

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    // 캐시: 일별 갱신
    private volatile List<EarningSurpriseDto> cachedSurprises = new ArrayList<>();
    private volatile LocalDate cacheDate = null;

    /**
     * 어닝 서프라이즈 감지 — 소스는 {@code useQuarterlySource} 가 고른다(클래스 주석 참조).
     *
     * <p>당일 캐시. 단 <b>빈 결과는 캐시로 서빙하지 않는다</b>(위 조건의 {@code !isEmpty()}) —
     * "감지 실패"와 "서프라이즈 없음"이 하루 종일 구분 안 되는 상태로 굳는 것을 막는다(§4c).
     */
    public List<EarningSurpriseDto> detectEarningSurprises() {
        // 당일 캐시 사용
        if (cacheDate != null && cacheDate.equals(LocalDate.now()) && !cachedSurprises.isEmpty()) {
            log.debug("[어닝서프라이즈] 캐시 사용 - {}건", cachedSurprises.size());
            return cachedSurprises;
        }

        log.info("[어닝서프라이즈] 감지 시작 (소스: {})", useQuarterlySource ? "분기원본" : "일별스냅샷(레거시)");
        List<EarningSurpriseDto> surprises =
                useQuarterlySource ? detectFromQuarterly() : detectFromDailySnapshots();

        // 영업이익 변화율 절대값 기준 내림차순 정렬
        surprises.sort((a, b) -> {
            BigDecimal absA = a.getOperatingProfitChangeRate() != null
                    ? a.getOperatingProfitChangeRate().abs() : BigDecimal.ZERO;
            BigDecimal absB = b.getOperatingProfitChangeRate() != null
                    ? b.getOperatingProfitChangeRate().abs() : BigDecimal.ZERO;
            return absB.compareTo(absA);
        });

        cachedSurprises = surprises;
        cacheDate = LocalDate.now();

        log.info("[어닝서프라이즈] 감지 완료 - 총 {}건 (포지티브: {}, 네거티브: {}, 턴어라운드: {})",
                surprises.size(),
                surprises.stream().filter(x -> x.getSurpriseType() == SurpriseType.POSITIVE).count(),
                surprises.stream().filter(x -> x.getSurpriseType() == SurpriseType.NEGATIVE).count(),
                surprises.stream().filter(x -> x.getSurpriseType() == SurpriseType.TURNAROUND).count());
        return surprises;
    }

    /**
     * 분기 원본 경로 (R1 수정본) — {@code stock_quarterly_financial} 기준.
     *
     * <p>레거시와 다른 점: ① 비교 대상이 <b>정확히 3개월 차이</b>인 인접 분기(≤120일 근사 아님)
     * ② 누적(YTD) 원본은 개별 분기로 환산하되 <b>환산 불가면 제외</b>
     * ③ 최신 분기가 {@link #QUARTER_MAX_AGE_DAYS} 보다 오래면 제외(수집 중단 종목의 옛 실적이
     * 매일 "오늘의 흑자전환"으로 붙는 것 차단 — 레거시에선 reportDate 가 항상 오늘이라 불가능했다).
     */
    List<EarningSurpriseDto> detectFromQuarterly() {
        List<EarningSurpriseDto> surprises = new ArrayList<>();
        LocalDate today = LocalDate.now();

        List<StockQuarterlyFinancial> rows = quarterlyRepository.findAllSince(today.minusMonths(QUARTER_LOOKBACK_MONTHS));
        if (rows == null || rows.isEmpty()) {
            log.warn("[어닝서프라이즈] 분기 재무 데이터 없음 — 수집 배치 확인 필요(§4c: '서프라이즈 0건'과 다름)");
            return surprises;
        }

        Map<String, List<StockQuarterlyFinancial>> byStock = rows.stream()
                .collect(Collectors.groupingBy(StockQuarterlyFinancial::getStockCode));

        int stale = 0, notEnough = 0;
        for (Map.Entry<String, List<StockQuarterlyFinancial>> e : byStock.entrySet()) {
            List<QuarterlyFinancials.Figures> raw = e.getValue().stream()
                    .map(q -> new QuarterlyFinancials.Figures(
                            q.getFiscalPeriod(), q.getPeriodEnd(),
                            q.getRevenue(), q.getOperatingProfit(), q.getNetIncome(), q.isCumulative()))
                    .collect(Collectors.toList());

            List<QuarterlyFinancials.Figures> individuals = QuarterlyFinancials.toIndividualQuarters(raw);
            QuarterlyFinancials.Figures[] pair = QuarterlyFinancials.latestAdjacentPair(individuals);
            if (pair == null) { notEnough++; continue; }

            if (pair[0].periodEnd().isBefore(today.minusDays(QUARTER_MAX_AGE_DAYS))) { stale++; continue; }

            // 연속 적자 판정용 — 3분기 연속을 못 구하면 null(실측 0건)
            QuarterlyFinancials.Figures[] triple = QuarterlyFinancials.latestAdjacentTriple(individuals);
            BigDecimal prev2Op = (triple == null) ? null : triple[2].operatingProfit();

            String code = e.getKey();
            String name = stockMasterService.getNameOrDefault(code, code);
            String market = stockMasterService.getMarket(code);
            EarningSurpriseDto dto = classifyQuarterly(
                    toPeriod(code, name, market, pair[0]),
                    toPeriod(code, name, market, pair[1]),
                    prev2Op);
            if (dto != null) surprises.add(dto);
        }

        log.info("[어닝서프라이즈] 분기원본 - 종목 {}개 중 판정 {}건 (인접2분기 미확보 {}, 노후 {})",
                byStock.size(), surprises.size(), notEnough, stale);
        return surprises;
    }

    private static Period toPeriod(String code, String name, String market,
                                   QuarterlyFinancials.Figures f) {
        return new Period(code, name, market, f.periodEnd(),
                f.revenue(), f.operatingProfit(), f.netIncome());
    }

    /** 레거시 경로 — 일별 스냅샷 테이블(위 클래스 주석 참조. R1 로 사실상 死). */
    private List<EarningSurpriseDto> detectFromDailySnapshots() {
        List<EarningSurpriseDto> surprises = new ArrayList<>();
        try {
            // 최근 2개 분기 데이터 조회
            List<StockFinancialData> allData = financialDataRepository.findLatestTwoQuartersPerStock();

            if (allData == null || allData.isEmpty()) {
                log.warn("[어닝서프라이즈] 재무 데이터 없음");
                return surprises;
            }

            // 종목별 그룹화
            Map<String, List<StockFinancialData>> groupedByStock = allData.stream()
                    .collect(Collectors.groupingBy(StockFinancialData::getStockCode));

            for (Map.Entry<String, List<StockFinancialData>> entry : groupedByStock.entrySet()) {
                List<StockFinancialData> quarters = entry.getValue();

                // 2개 분기 데이터가 있어야 비교 가능
                if (quarters.size() < 2) continue;

                // reportDate 내림차순 정렬 (최신이 먼저)
                quarters.sort((a, b) -> b.getReportDate().compareTo(a.getReportDate()));

                StockFinancialData latest = quarters.get(0);
                StockFinancialData previous = quarters.get(1);

                EarningSurpriseDto surprise = analyzeQuarters(latest, previous);
                if (surprise != null) {
                    surprises.add(surprise);
                }
            }

        } catch (Exception e) {
            log.error("[어닝서프라이즈] 감지 실패: {}", e.getMessage(), e);
        }

        return surprises;
    }

    /** 분기 원본을 거슬러 올릴 개월 수 — 누적 환산엔 직전 분기가 필요해 넉넉히 잡는다. */
    private static final int QUARTER_LOOKBACK_MONTHS = 18;

    /**
     * 최신 분기가 이보다 오래면 판정 제외.
     *
     * <p>{@code RecommendationService.EARNINGS_MAX_AGE_DAYS} 와 같은 값(200)으로 맞춰 뒀다 —
     * 한국 분기 공시 주기(분기말 + 45일 내외) 상 200일이면 정상 종목은 절대 안 걸리고,
     * 수집이 끊긴 종목만 걸린다. 이 서비스에도 두는 이유는 텔레그램 알림·
     * {@code getPositiveSurpriseStockCodes()} 처럼 <b>추천 경로를 안 거치는 소비자</b>가 있기 때문.
     */
    static final int QUARTER_MAX_AGE_DAYS = 200;

    /**
     * 두 경로가 공유하는 비교 단위 — 어느 테이블에서 왔는지와 무관한 "한 시점의 실적 3종".
     */
    record Period(String stockCode, String stockName, String market, LocalDate periodEnd,
                  BigDecimal revenue, BigDecimal operatingProfit, BigDecimal netIncome) {}

    /** "전분기 대비" 비교로 인정하는 최대 reportDate 간격(일). 인접 분기 ≈ 90~92일. */
    private static final long MAX_QUARTER_GAP_DAYS = 120;

    /**
     * 두 분기 데이터 비교 분석 — package-private (EarningSurpriseClassifyTest).
     */
    EarningSurpriseDto analyzeQuarters(StockFinancialData latest, StockFinancialData previous) {
        // 인접 분기 가드(2026-07-28): 간격이 분기(≤120일)를 넘으면 연간 행(레거시 365일)이나
        // 결측 분기 건너뛴 비교 — "전분기 대비" 가 아니고 변화율이 뻥튀기되므로 스킵(§4c).
        if (latest.getReportDate() == null || previous.getReportDate() == null) return null;
        long gapDays = java.time.temporal.ChronoUnit.DAYS.between(
                previous.getReportDate(), latest.getReportDate());
        if (gapDays <= 0 || gapDays > MAX_QUARTER_GAP_DAYS) return null;

        return classify(
                new Period(latest.getStockCode(), latest.getStockName(), latest.getMarket(),
                        latest.getReportDate(), latest.getRevenue(),
                        latest.getOperatingProfit(), latest.getNetIncome()),
                new Period(previous.getStockCode(), previous.getStockName(), previous.getMarket(),
                        previous.getReportDate(), previous.getRevenue(),
                        previous.getOperatingProfit(), previous.getNetIncome()));
    }

    /**
     * 분기 경로 전용 분류 — <b>TURNAROUND 에 연속 적자 조건</b>을 건다(2026-08-28, 후보안 (나)).
     *
     * <h4>왜</h4>
     * 실측: TURNAROUND 209건 중 <b>111건(53%)이 "한 분기만 적자"</b>였다. 그건 턴어라운드가 아니라
     * 실적이 들쭉날쭉한 회사고, 그런데도 <b>최고점 20점</b>을 받고 있었다
     * (임계 20%를 겨우 넘긴 POSITIVE 는 8점인데 그 2.5배).
     *
     * <h4>왜 "조건 추가"가 아니라 "제외"인가</h4>
     * TURNAROUND 만 막으면 효과가 없다. {@code prevOp < 0} 이면 변화율이
     * {@code (latest − prev) / |prev|} 라 <b>적자 규모가 분모</b>가 되어,
     * 100억 적자 → 1억 흑자가 +101% 로 나오고 POSITIVE 경로에서 똑같이 20점을 받는다.
     * <b>적자를 기준으로 한 변화율은 성장률이 아니다</b> — 얼마나 깊이 잃었는지의 함수일 뿐이다.
     * 그래서 연속성 미달이면 변화율로 재분류하지 않고 <b>서프라이즈에서 뺀다.</b>
     *
     * <h4>레거시 경로는 무변경</h4>
     * 일별 스냅샷 경로는 3분기를 못 구하므로 {@link #classify} 를 그대로 쓴다.
     * 지금 살아 있는 건 레거시라, 이 변경은 <b>플래그를 켤 때만</b> 효과가 생긴다.
     *
     * @param prev2Op 직전의 직전 분기 영업이익. null = 3분기 연속 미확보(실측 0건)
     */
    EarningSurpriseDto classifyQuarterly(Period latest, Period previous, BigDecimal prev2Op) {
        BigDecimal latestOp = latest.operatingProfit();
        BigDecimal prevOp = previous.operatingProfit();

        boolean lossToProfit = latestOp != null && prevOp != null
                && prevOp.signum() < 0 && latestOp.signum() > 0;
        if (lossToProfit && (prev2Op == null || prev2Op.signum() >= 0)) {
            // 한 분기 삐끗이거나 연속성 확인 불가 — 근거 없이 최고점을 주지 않는다(§4c).
            return null;
        }
        return classify(latest, previous);
    }

    /**
     * 서프라이즈 분류 — <b>두 입력 경로(일별 스냅샷 / 분기 원본)가 공유하는 단일 산식</b>.
     *
     * <p>여기엔 "비교해도 되는 두 시점인가" 판단이 없다. 그건 호출부의 책임이다
     * (레거시는 ≤120일 근사, 분기 경로는 정확히 3개월). 산식만 한 곳에 두어야
     * 소스를 바꿔도 임계·부호 규칙이 갈라지지 않는다.
     *
     * <p>임계 ±20%, POSITIVE 는 흑자(latest&gt;0) 필수 — 2026-07-28 회귀
     * ({@code EarningSurpriseClassifyTest}) 그대로다.
     */
    EarningSurpriseDto classify(Period latest, Period previous) {
        BigDecimal latestOp = latest.operatingProfit();
        BigDecimal prevOp = previous.operatingProfit();
        BigDecimal latestNet = latest.netIncome();
        BigDecimal prevNet = previous.netIncome();
        BigDecimal latestRev = latest.revenue();
        BigDecimal prevRev = previous.revenue();
        // 영업이익이 둘 다 없으면 비교 불가
        if (latestOp == null && latestNet == null) return null;

        SurpriseType surpriseType = null;
        BigDecimal opChangeRate = null;
        BigDecimal netChangeRate = null;
        BigDecimal revChangeRate = null;
        String summary;

        // 1. 적자→흑자 전환 체크 (영업이익 기준)
        if (latestOp != null && prevOp != null
                && prevOp.compareTo(BigDecimal.ZERO) < 0
                && latestOp.compareTo(BigDecimal.ZERO) > 0) {
            surpriseType = SurpriseType.TURNAROUND;
            opChangeRate = calculateChangeRate(latestOp, prevOp);
            summary = String.format("영업이익 적자→흑자 전환! (%.0f억 → %.0f억)", prevOp, latestOp);
        }
        // 2. 영업이익 변화율 계산
        else if (latestOp != null && prevOp != null
                && prevOp.compareTo(BigDecimal.ZERO) != 0) {
            opChangeRate = calculateChangeRate(latestOp, prevOp);

            // POSITIVE 는 흑자(latest>0) 필수(2026-07-28): 분모가 |prev| 라 적자 축소
            // (-1000억→-100억)도 +90% 로 나와 "실적개선"으로 오분류되던 버그 — 여전히 적자다.
            if (opChangeRate.compareTo(SURPRISE_THRESHOLD) >= 0
                    && latestOp.compareTo(BigDecimal.ZERO) > 0) {
                surpriseType = SurpriseType.POSITIVE;
                summary = String.format("영업이익 %.1f%% 증가 (%.0f억 → %.0f억)",
                        opChangeRate, prevOp, latestOp);
            } else if (opChangeRate.compareTo(NEGATIVE_SURPRISE_THRESHOLD) <= 0) {
                surpriseType = SurpriseType.NEGATIVE;
                summary = String.format("영업이익 %.1f%% 감소 (%.0f억 → %.0f억)",
                        opChangeRate, prevOp, latestOp);
            } else {
                return null; // 임계값 미달 또는 적자 지속(서프라이즈 아님)
            }
        }
        // 3. 영업이익 없으면 순이익으로 대체
        else if (latestNet != null && prevNet != null) {
            // 적자→흑자 전환 (순이익 기준)
            if (prevNet.compareTo(BigDecimal.ZERO) < 0
                    && latestNet.compareTo(BigDecimal.ZERO) > 0) {
                surpriseType = SurpriseType.TURNAROUND;
                netChangeRate = calculateChangeRate(latestNet, prevNet);
                summary = String.format("순이익 적자→흑자 전환! (%.0f억 → %.0f억)", prevNet, latestNet);
            } else if (prevNet.compareTo(BigDecimal.ZERO) != 0) {
                netChangeRate = calculateChangeRate(latestNet, prevNet);
                // 영업이익 경로와 동일 — POSITIVE 는 흑자(latest>0) 필수(적자 축소 오분류 방지)
                if (netChangeRate.compareTo(SURPRISE_THRESHOLD) >= 0
                        && latestNet.compareTo(BigDecimal.ZERO) > 0) {
                    surpriseType = SurpriseType.POSITIVE;
                    summary = String.format("순이익 %.1f%% 증가 (%.0f억 → %.0f억)",
                            netChangeRate, prevNet, latestNet);
                } else if (netChangeRate.compareTo(NEGATIVE_SURPRISE_THRESHOLD) <= 0) {
                    surpriseType = SurpriseType.NEGATIVE;
                    summary = String.format("순이익 %.1f%% 감소 (%.0f억 → %.0f억)",
                            netChangeRate, prevNet, latestNet);
                } else {
                    return null;
                }
            } else {
                return null;
            }
        } else {
            return null;
        }

        // 순이익 변화율 (아직 계산 안 한 경우)
        if (netChangeRate == null && latestNet != null && prevNet != null
                && prevNet.compareTo(BigDecimal.ZERO) != 0) {
            netChangeRate = calculateChangeRate(latestNet, prevNet);
        }

        // 매출 변화율
        if (latestRev != null && prevRev != null && prevRev.compareTo(BigDecimal.ZERO) != 0) {
            revChangeRate = calculateChangeRate(latestRev, prevRev);
        }

        return EarningSurpriseDto.builder()
                .stockCode(latest.stockCode())
                .stockName(latest.stockName())
                .market(latest.market())
                .latestOperatingProfit(latestOp)
                .previousOperatingProfit(prevOp)
                .operatingProfitChangeRate(opChangeRate)
                .latestNetIncome(latestNet)
                .previousNetIncome(prevNet)
                .netIncomeChangeRate(netChangeRate)
                .latestRevenue(latestRev)
                .revenueChangeRate(revChangeRate)
                .latestReportDate(latest.periodEnd())
                .previousReportDate(previous.periodEnd())
                .surpriseType(surpriseType)
                .summary(summary)
                .build();
    }

    /**
     * 변화율 계산 (%)
     */
    private BigDecimal calculateChangeRate(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return current.subtract(previous)
                .divide(previous.abs(), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * 어닝 서프라이즈 텔레그램 알림
     */
    public void sendEarningSurpriseAlert() {
        log.info("[어닝서프라이즈] 텔레그램 알림 시작");

        List<EarningSurpriseDto> surprises = detectEarningSurprises();

        // 포지티브 + 턴어라운드만 알림
        List<EarningSurpriseDto> positiveSurprises = surprises.stream()
                .filter(s -> s.getSurpriseType() == SurpriseType.POSITIVE
                        || s.getSurpriseType() == SurpriseType.TURNAROUND)
                .limit(10)
                .collect(Collectors.toList());

        if (positiveSurprises.isEmpty()) {
            log.info("[어닝서프라이즈] 포지티브 서프라이즈 없음 - 알림 생략");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<b>\uD83C\uDFAF 어닝 서프라이즈 감지</b>\n\n");

        int idx = 1;
        for (EarningSurpriseDto s : positiveSurprises) {
            String typeEmoji = s.getSurpriseType() == SurpriseType.TURNAROUND ? "\uD83D\uDD04" : "\uD83D\uDCC8";
            sb.append(String.format("%s <b>%d. %s</b> (%s)\n",
                    typeEmoji, idx++, s.getStockName(), s.getStockCode()));
            sb.append(String.format("   %s\n", s.getSummary()));

            if (s.getRevenueChangeRate() != null) {
                sb.append(String.format("   매출 변화: %+.1f%%\n", s.getRevenueChangeRate()));
            }
            sb.append("\n");
        }

        sb.append(String.format("⏰ %s\n", LocalDateTime.now().format(TIME_FORMATTER)));
        sb.append("━━━━━━━━━━━━━━━━\n");
        sb.append("\uD83E\uDD16 MyPlatform 어닝 서프라이즈 알림");

        telegramService.sendMessage(sb.toString());
        log.info("[어닝서프라이즈] 텔레그램 알림 발송 - {}건", positiveSurprises.size());
    }

    /**
     * 서프라이즈 종목의 stockCode Set 반환 (전략 스코어 연동용)
     */
    public Set<String> getPositiveSurpriseStockCodes() {
        return detectEarningSurprises().stream()
                .filter(s -> s.getSurpriseType() == SurpriseType.POSITIVE
                        || s.getSurpriseType() == SurpriseType.TURNAROUND)
                .map(EarningSurpriseDto::getStockCode)
                .collect(Collectors.toSet());
    }

    /**
     * 서프라이즈 유형별 stockCode Map 반환 (전략 스코어 세분화용)
     */
    public Map<String, SurpriseType> getSurpriseTypeMap() {
        return detectEarningSurprises().stream()
                .filter(s -> s.getSurpriseType() == SurpriseType.POSITIVE
                        || s.getSurpriseType() == SurpriseType.TURNAROUND)
                .collect(Collectors.toMap(
                        EarningSurpriseDto::getStockCode,
                        EarningSurpriseDto::getSurpriseType,
                        (a, b) -> a // 중복 시 첫 번째 유지
                ));
    }

    /**
     * 분기 소스 전환 판단용 진단 — <b>집계값만</b> 낸다(종목 코드·이름 미노출).
     *
     * <p>{@code /api/diagnostics/**} 는 permitAll 이고 그 계약이 "시스템 상태 메타데이터만"이다.
     * 서프라이즈 종목 목록은 매매 정보라 여기 실으면 그 계약이 깨진다 — 건수만 낸다.
     *
     * <p>이 진단이 필요한 이유: 플래그를 켜면 composite 의 earnings 입력이 살아나 총점·validCount·
     * 후보 수가 동시에 움직인다. "얼마나 움직이는지"를 <b>켜기 전에</b> 숫자로 보고 결정하려는 것이다.
     *
     * @param compare true 면 두 경로를 실제로 돌려 건수를 비교한다(전 종목 스캔이라 무겁다).
     *                false 면 수집 커버리지만 본다.
     */
    public Map<String, Object> diagnostics(boolean compare) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quarterlySourceEnabled", useQuarterlySource);
        out.put("activeSource", useQuarterlySource ? "QUARTERLY" : "DAILY_SNAPSHOT_LEGACY");
        out.put("flagKey", "recommendation.earnings.quarterly-source");

        Map<String, Object> coverage = new LinkedHashMap<>();
        try {
            LocalDate since = LocalDate.now().minusMonths(QUARTER_LOOKBACK_MONTHS);
            long stocks = quarterlyRepository.countDistinctStocksSince(since);
            coverage.put("dataAvailable", true);
            coverage.put("distinctStocks", stocks);
            coverage.put("lookbackMonths", QUARTER_LOOKBACK_MONTHS);
            coverage.put("maxPeriodEnd", quarterlyRepository.findMaxPeriodEnd().orElse(null));
            coverage.put("lastCollectedAt", quarterlyRepository.findMaxCollectedAt().orElse(null));
            if (stocks == 0) {
                coverage.put("note", "분기 행 0건 — 수집 배치(08:30/15:38)가 한 번도 안 돌았거나 적재 실패. "
                        + "'서프라이즈 없음'이 아니라 '데이터 없음'이다(§4c). 이 상태로 플래그를 켜면 earnings 는 전멸한다.");
            }
        } catch (Exception e) {
            // §4c — 조회 실패를 0 으로 위장하지 않는다
            coverage.put("dataAvailable", false);
            coverage.put("error", String.valueOf(e.getMessage()));
        }
        out.put("coverage", coverage);

        if (!compare) {
            out.put("comparison", Map.of("skipped", true,
                    "hint", "?compare=true 로 두 경로 건수 비교 (전 종목 스캔이라 수 초 걸린다)"));
            return out;
        }

        Map<String, Object> cmp = new LinkedHashMap<>();
        try {
            List<EarningSurpriseDto> legacy = detectFromDailySnapshots();
            List<EarningSurpriseDto> quarterly = detectFromQuarterly();
            cmp.put("legacyTotal", legacy.size());
            cmp.put("legacyScoring", countScoring(legacy));
            cmp.put("quarterlyTotal", quarterly.size());
            cmp.put("quarterlyScoring", countScoring(quarterly));
            long covered = quarterlyRepository.countDistinctStocksSince(
                    LocalDate.now().minusMonths(QUARTER_LOOKBACK_MONTHS));
            cmp.put("quarterlyThreshold", thresholdSweep(quarterly, covered));
            cmp.put("turnaroundBreakdown", turnaroundBreakdown());
            cmp.put("note", "scoring = POSITIVE+TURNAROUND (composite earnings 8~20점이 붙는 부류). "
                    + "레거시가 0 에 가깝다면 그것이 R1 이 말한 'earnings 死'의 실측이다.");
        } catch (Exception e) {
            cmp.put("error", String.valueOf(e.getMessage()));
        }
        out.put("comparison", cmp);
        return out;
    }

    /**
     * 임계 스윕 — "임계를 X% 로 올리면 몇 종목이 남나".
     *
     * <p><b>왜 필요한가</b>(2026-08-27): 입력이 복구되자 quarterlyScoring 이 916/2,289 = <b>40%</b> 로
     * 나왔다. 40% 에 붙는 신호는 변별력이 없다. ±20% 임계는 <b>거의 안 터지던 입력</b>(~90종목) 위에서
     * 정해진 값이고 제대로 도는 상태로는 튜닝된 적이 없다 — 한국 주식 분기 영업이익은 계절성 때문에
     * QoQ ±20% 를 일상적으로 넘나든다.
     *
     * <p>임계를 추측으로 올리지 않기 위해 분포를 낸다. <b>올리는 방향만</b> 계산 가능하다 —
     * 현재 임계 미달 건은 {@code classify} 가 이미 null 로 버려서 표본에 없다.
     *
     * <p>TURNAROUND(적자→흑자)는 임계와 무관하므로 따로 센다. 이 값이 크면 임계를 올려도
     * 안 줄어든다는 뜻이고, 그때는 TURNAROUND 규칙 자체를 봐야 한다.
     */
    /**
     * TURNAROUND 연속성 분해 — <b>"직전 2분기 연속 적자 조건을 걸면 몇 건 남나"</b>(2026-08-28).
     *
     * <p><b>왜</b>: 현재 규칙은 {@code prevOp < 0 && latestOp > 0}(직전 1분기만)이라
     * 실측 226건(커버리지 2,289의 9.9%)이 나왔고, 임계와 무관해 earnings 변별력의 바닥을 만든다.
     * 변동성 큰 중소형주가 분기마다 적자·흑자를 오가는 것을 턴어라운드로 잡는 게 원인 후보다.
     *
     * <p>규칙을 추측으로 바꾸지 않기 위해 <b>먼저 잰다</b> — 임계 스윕을 만들었을 때와 같은 순서다.
     * 그때 임계 20%가 근거 없는 상수였던 것을 스윕이 드러냈다.
     *
     * <p><b>절대액 하한은 여기서 재지 않는다</b> — 재무 금액 단위가 100배 어긋나 있는 것이
     * 미해결이라(FLAGGED financial-unit-100x) 절대액 기준은 지금 의미가 없다. 연속성만 본다.
     */
    private Map<String, Object> turnaroundBreakdown() {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        long consecutive = 0;   // 직전 2분기 연속 적자 → 진짜 턴어라운드 후보
        long blip = 0;          // 직전 1분기만 적자 → 한 분기 삐끗(변동성)
        long undecidable = 0;   // 3분기 연속 확보 실패 → 판정 불가(§4c)

        try {
            List<StockQuarterlyFinancial> rows =
                    quarterlyRepository.findAllSince(today.minusMonths(QUARTER_LOOKBACK_MONTHS));
            Map<String, List<StockQuarterlyFinancial>> byStock = rows.stream()
                    .collect(Collectors.groupingBy(StockQuarterlyFinancial::getStockCode));

            for (Map.Entry<String, List<StockQuarterlyFinancial>> e : byStock.entrySet()) {
                List<QuarterlyFinancials.Figures> raw = e.getValue().stream()
                        .map(q -> new QuarterlyFinancials.Figures(
                                q.getFiscalPeriod(), q.getPeriodEnd(),
                                q.getRevenue(), q.getOperatingProfit(), q.getNetIncome(), q.isCumulative()))
                        .collect(Collectors.toList());
                List<QuarterlyFinancials.Figures> ind = QuarterlyFinancials.toIndividualQuarters(raw);

                QuarterlyFinancials.Figures[] pair = QuarterlyFinancials.latestAdjacentPair(ind);
                if (pair == null) continue;
                if (pair[0].periodEnd().isBefore(today.minusDays(QUARTER_MAX_AGE_DAYS))) continue;

                // 현재 규칙이 TURNAROUND 로 잡는 조건과 동일하게 필터
                BigDecimal latestOp = pair[0].operatingProfit();
                BigDecimal prevOp = pair[1].operatingProfit();
                if (latestOp == null || prevOp == null) continue;
                if (!(prevOp.signum() < 0 && latestOp.signum() > 0)) continue;

                QuarterlyFinancials.Figures[] triple = QuarterlyFinancials.latestAdjacentTriple(ind);
                BigDecimal prev2Op = (triple == null) ? null : triple[2].operatingProfit();
                if (prev2Op == null) undecidable++;
                else if (prev2Op.signum() < 0) consecutive++;
                else blip++;
            }
        } catch (Exception e) {
            out.put("error", String.valueOf(e.getMessage()));
            return out;
        }

        out.put("current", consecutive + blip + undecidable);
        out.put("consecutiveLoss", consecutive);
        out.put("singleQuarterBlip", blip);
        out.put("undecidable", undecidable);
        out.put("note", "current = 현재 규칙(직전 1분기 적자)이 잡는 건수. "
                + "consecutiveLoss = 직전 2분기 연속 적자였던 건 — '(나) 연속성 하한' 안을 적용하면 이만큼 남는다. "
                + "singleQuarterBlip = 한 분기만 적자였던 건(변동성으로 의심되는 부류). "
                + "undecidable = 3분기 연속을 확보 못해 판정 불가 — 0 으로 세지 말 것(§4c). "
                + "절대액 하한은 재무 금액 단위 100배 문제가 미해결이라 여기서 재지 않는다.");
        return out;
    }

    private static Map<String, Object> thresholdSweep(List<EarningSurpriseDto> list, long coveredStocks) {
        Map<String, Object> out = new LinkedHashMap<>();

        long positive = list.stream().filter(d -> d.getSurpriseType() == SurpriseType.POSITIVE).count();
        long negative = list.stream().filter(d -> d.getSurpriseType() == SurpriseType.NEGATIVE).count();
        long turnaround = list.stream().filter(d -> d.getSurpriseType() == SurpriseType.TURNAROUND).count();
        out.put("byType", Map.of("POSITIVE", positive, "NEGATIVE", negative, "TURNAROUND", turnaround));

        List<Map<String, Object>> sweep = new ArrayList<>();
        for (int t : new int[]{20, 30, 50, 100, 200}) {
            long pos = list.stream()
                    .filter(d -> d.getSurpriseType() == SurpriseType.POSITIVE)
                    .filter(d -> d.getOperatingProfitChangeRate() != null
                            && d.getOperatingProfitChangeRate().doubleValue() >= t)
                    .count();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("threshold", t);
            row.put("positive", pos);
            row.put("turnaround", turnaround);              // 임계 무관
            row.put("scoring", pos + turnaround);
            row.put("shareOfCoveredPct", coveredStocks > 0
                    ? Math.round((pos + turnaround) * 1000.0 / coveredStocks) / 10.0 : null);
            sweep.add(row);
        }
        out.put("sweep", sweep);
        out.put("coveredStocks", coveredStocks);
        out.put("note", "shareOfCoveredPct = 분기 데이터가 있는 종목 중 earnings 점수를 받는 비율. "
                + "너무 높으면(예: 30%+) 신호가 아니라 상수에 가깝다 — 변별력이 없다. "
                + "turnaround 는 임계와 무관하므로, 임계를 올려도 안 줄면 TURNAROUND 규칙을 봐야 한다. "
                + "현재 임계(20) 미달 건은 표본에 없어 임계를 낮추는 방향은 계산할 수 없다.");
        return out;
    }

    private static long countScoring(List<EarningSurpriseDto> list) {
        return list.stream()
                .filter(d -> d.getSurpriseType() == SurpriseType.POSITIVE
                        || d.getSurpriseType() == SurpriseType.TURNAROUND)
                .count();
    }

    /**
     * 캐시 강제 갱신
     */
    public void refreshCache() {
        cacheDate = null;
        cachedSurprises = new ArrayList<>();
        detectEarningSurprises();
    }
}
