package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.SignalAccuracyDto;
import com.myplatform.backend.dto.SignalAccuracyDto.SignalStat;
import com.myplatform.backend.dto.SignalCompareDto;
import com.myplatform.backend.dto.SignalTimeseriesDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 시그널 적중률 추적.
 *
 * 1) {@link #record} — 시그널 생성 시 호출 (RecommendationService 등이 통합).
 *    동일 (시그널/종목/날짜) 중복 INSERT 방지 — 같은 날 여러 번 발생해도 첫 시점만 기록.
 * 2) {@link #evaluatePendingSignals} — 매일 batchScheduler 가 호출. 3일 이상 지난 unevaluated
 *    항목의 현재 가격을 조회해 변동률 + hit 채움. hit 기준: +3% 이상.
 * 3) {@link #getAccuracy} — 최근 N일 시그널 타입별 적중률 / 평균 변동률 통계.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SignalOutcomeService {

    private final SignalOutcomeRepository repository;
    private final StockPriceService stockPriceService;
    // V31 — 시그널 record 시 당일 재료 캐시 스냅샷 (best-effort, Gemini 호출 없음).
    private final com.myplatform.backend.repository.StockCatalystRepository catalystRepository;
    // V32 — 시장 국면 스냅샷 (python-backend pykrx, 1h 캐시·best-effort). ObjectProvider 로 null-safe.
    private final org.springframework.beans.factory.ObjectProvider<MarketRegimeClient> regimeProvider;
    // V41 — RVOL 스냅샷 (시세 cache-only + 가격히스토리, best-effort). ObjectProvider 로 null-safe.
    private final org.springframework.beans.factory.ObjectProvider<RvolService> rvolProvider;
    // V46 — 변동성 국면 스냅샷 (VKOSPI 백분위, best-effort·게이트 모드와 독립). ObjectProvider 로 null-safe.
    private final org.springframework.beans.factory.ObjectProvider<VolatilityRegimeService> volRegimeProvider;
    // V49 — 추세 채널 스냅샷 (30거래일 회귀 채널, best-effort). ObjectProvider 로 null-safe.
    private final org.springframework.beans.factory.ObjectProvider<com.myplatform.backend.repository.StockPriceHistoryRepository> priceHistoryProvider;
    // V50 — KOSPI 지수(0001) 채널 스냅샷 (6h 캐시·종목무관·best-effort). ObjectProvider 로 null-safe.
    // ⚠ KospiChannelService 는 이 record() 경로 전용 소비자다(KospiChannelService javadoc 소비자 제약).
    private final org.springframework.beans.factory.ObjectProvider<KospiChannelService> kospiChannelProvider;
    // KOSPI 지수 가격 조회용 — phase 20 alpha 계산.
    // ObjectProvider 로 받아 KIS 미설정 환경에서도 null-safe.
    private final org.springframework.beans.factory.ObjectProvider<KoreaInvestmentService> kisProvider;
    // phase 33: STRONG_BUY 평균 alpha 음수 지속 시 risk 채널 헬스 알림. ObjectProvider 로 받아
    // 텔레그램 미설정 환경(예: 로컬 테스트)에서도 null-safe.
    private final org.springframework.beans.factory.ObjectProvider<TelegramNotificationService> telegramProvider;
    // 작업2(V36) — insertOutcomeIsolated(REQUIRES_NEW) 를 프록시 경유로 호출하기 위한 자기참조.
    // 자기호출(this.method())은 트랜잭션 어드바이스를 우회하므로 ObjectProvider(lazy)로 프록시를 받는다.
    private final org.springframework.beans.factory.ObjectProvider<SignalOutcomeService> selfProvider;
    // 크론 dead-man switch — 19:30 평가 성공 심박 기록(best-effort). null-safe(단위테스트 미주입 보존).
    private final org.springframework.beans.factory.ObjectProvider<BatchHeartbeatService> heartbeatProvider;
    // "3거래일 후" 컷오프 계산용 — 달력일 minusDays(3)는 금요일 시그널이 1거래일 만에 평가되는 왜곡.
    private final org.springframework.beans.factory.ObjectProvider<MarketCalendarService> calendarProvider;
    private volatile LocalDate lastAlphaAlertDate = null;

    private static final int EVALUATION_DELAY_DAYS = 3;
    /** 1회 배치 평가 상한 — 종목당 KIS 2콜이 트랜잭션 안에서 돌아 백로그가 크면 커넥션 장기 점유. */
    private static final int MAX_EVAL_PER_RUN = 300;
    /** V49 채널 스냅샷 봉 수 — 종목상세 차트 기본 표시(30봉)·보드(JudgmentBoardService.CHANNEL_BARS)와 동기. */
    static final int CHANNEL_SNAPSHOT_BARS = 30;
    /** hit 기준 — phase 20 변경: 시장 대비 alpha 양수 + 절대 수익률 양수. */
    private static final BigDecimal HIT_ALPHA_THRESHOLD = BigDecimal.ZERO;
    /** BM(KOSPI) 데이터 없을 때 hit 폴백 임계 — 절대 수익률 +3%. */
    private static final BigDecimal FALLBACK_PCT_THRESHOLD = new BigDecimal("3.00");
    private static final String KOSPI_INDEX_CODE = "0001";

    /**
     * 시그널 hit 판정 (phase 20) — 순수 함수로 분리(P1-4 테스트 대상).
     *
     * <p>BM(KOSPI) 대비 alpha 가 있으면 <b>alpha ≥ 0 AND 절대 수익률 &gt; 0</b> 둘 다여야 hit.
     * alpha 가 없으면(BM 데이터 부재) 절대 수익률 ≥ +3% 폴백. 동작은 기존 인라인 로직과 동일.
     */
    static boolean isHit(BigDecimal alpha, BigDecimal pct) {
        if (pct == null) return false;
        if (alpha != null) {
            return alpha.compareTo(HIT_ALPHA_THRESHOLD) >= 0 && pct.signum() > 0;
        }
        return pct.compareTo(FALLBACK_PCT_THRESHOLD) >= 0;
    }

    /**
     * 시그널 발생 기록. 같은 (type/stockCode/날짜) 중복은 무시 — 첫 발생 시점만 보존.
     */
    @Transactional
    public void record(String signalType, String stockCode, String stockName,
                       Integer signalScore, BigDecimal priceAtSignal) {
        record(signalType, stockCode, stockName, signalScore, priceAtSignal,
                null, null, null, null);
    }

    /**
     * 카테고리 점수 스냅샷 포함 기록 — V30. 카테고리 조건부 적중률("수급 주도 vs 기술 주도
     * 추천 중 뭐가 먹혔나") 집계를 위해 시그널 시점의 4 카테고리 점수를 함께 저장.
     */
    @Transactional
    public void record(String signalType, String stockCode, String stockName,
                       Integer signalScore, BigDecimal priceAtSignal,
                       Integer earnings, Integer supplyDemand,
                       Integer technical, Integer sectorMomentum) {
        if (signalType == null || stockCode == null || priceAtSignal == null
                || priceAtSignal.signum() <= 0) {
            return;
        }
        LocalDate today = LocalDate.now();
        // 1차 방어: 앱레벨 dedup(findExisting). 2차 방어: DB UNIQUE(uq_so_type_code_date, V36).
        // 멀티 인스턴스/경합으로 findExisting 를 동시 통과해도 아래 INSERT(REQUIRES_NEW)가 UNIQUE 위반 → benign 처리.
        if (!repository.findExisting(signalType, stockCode, today).isEmpty()) {
            return;
        }
        try {
            // KOSPI 지수 가격을 한 번 조회 — alpha 계산용 (phase 20).
            // 실패 시 null 로 저장 (기존 evaluate 로직과 호환).
            BigDecimal bmPrice = fetchKospiPriceQuiet();

            // V31 — 당일 재료 캐시가 있으면 스냅샷 (없으면 NULL=미수집, Gemini 호출은 안 함).
            String catalystType = null, catalystDirection = null;
            try {
                var catalyst = catalystRepository.findByStockCodeAndCatalystDate(stockCode, today);
                if (catalyst.isPresent()) {
                    catalystType = catalyst.get().getCatalystType().name();
                    catalystDirection = catalyst.get().getDirection().name();
                }
            } catch (Exception ignore) { /* 재료 스냅샷은 best-effort */ }

            // V32 — 시장 국면 스냅샷 (클라이언트 1h 캐시 — record 루프에서 HTTP 부담 없음).
            String regime = null;
            try {
                MarketRegimeClient regimeClient = regimeProvider.getIfAvailable();
                if (regimeClient != null) {
                    regime = regimeClient.getCurrentRegimeQuiet();
                }
            } catch (Exception ignore) { /* 국면 스냅샷은 best-effort */ }

            // V41 — RVOL 스냅샷 (시세 cache-only + 히스토리 20거래일 — 미산출이면 NULL=미수집, §4c).
            BigDecimal rvol = null;
            try {
                RvolService rvolService = rvolProvider.getIfAvailable();
                if (rvolService != null) {
                    rvol = rvolService.getRvolQuiet(stockCode);
                }
            } catch (Exception ignore) { /* RVOL 스냅샷은 best-effort */ }

            // V46 — 변동성 국면 스냅샷 (VKOSPI 백분위, 게이트 모드와 독립·6h 캐시). UNKNOWN=미수집→NULL(§4c).
            String volRegime = null;
            try {
                VolatilityRegimeService vrs = volRegimeProvider.getIfAvailable();
                if (vrs != null) {
                    VolatilityRegimeService.VolRegime r = vrs.currentVolRegime();
                    if (r != VolatilityRegimeService.VolRegime.UNKNOWN) {
                        volRegime = r.name();   // NORMAL / HIGH_VOL 만 저장 — UNKNOWN 은 null(가짜값 금지)
                    }
                }
            } catch (Exception ignore) { /* 변동성 국면 스냅샷은 best-effort */ }

            // V49 — 추세 채널 스냅샷 (30거래일 회귀 채널 — 표시 전용 채널의 예측력 사후검증용).
            // 히스토리 <10거래일/가격 결측이면 NULL=미수집(§4c). 산식 미편입 — 데이터 축적만.
            String channelDirection = null;
            Integer channelPosition = null;
            try {
                var historyRepo = priceHistoryProvider.getIfAvailable();
                if (historyRepo != null) {
                    var ch = computeChannelFromHistory(historyRepo.findByStockCodeOrderByTradeDateDesc(
                            stockCode, org.springframework.data.domain.PageRequest.of(0, CHANNEL_SNAPSHOT_BARS)));
                    if (ch != null) {
                        channelDirection = ch.direction();
                        channelPosition = (int) Math.round(ch.position() * 100);
                    }
                }
            } catch (Exception ignore) { /* 채널 스냅샷은 best-effort */ }

            // V50 — KOSPI 지수(0001) 채널 스냅샷 (종목무관·6h 캐시 → 배치당 KIS 1콜, 전 시그널 재사용).
            // V32 regime / V46 volRegime 처럼 지수 축(같은 날 전 시그널이 동일값)이라 캐시된 결과만 읽는다.
            // 미확정/조회 실패 = NULL(§4c). 산식 미편입 — 데이터 축적만.
            String indexChannelDirection = null;
            Integer indexChannelPosition = null;
            BigDecimal indexChannelWidthPct = null;
            try {
                KospiChannelService kospiChannel = kospiChannelProvider.getIfAvailable();
                if (kospiChannel != null) {
                    var ich = kospiChannel.currentKospiChannel();
                    if (ich != null) {
                        indexChannelDirection = ich.direction();
                        indexChannelPosition = (int) Math.round(ich.position() * 100);
                        indexChannelWidthPct = BigDecimal.valueOf(ich.widthPct())
                                .setScale(2, RoundingMode.HALF_UP);
                    }
                }
            } catch (Exception ignore) { /* 지수 채널 스냅샷은 best-effort */ }

            SignalOutcome outcome = SignalOutcome.builder()
                    .signalType(signalType)
                    .stockCode(stockCode)
                    .stockName(stockName)
                    .signalDate(today)
                    .signalScore(signalScore)
                    .priceAtSignal(priceAtSignal)
                    .bmPriceAtSignal(bmPrice)
                    .earningsAtSignal(earnings)
                    .supplyDemandAtSignal(supplyDemand)
                    .technicalAtSignal(technical)
                    .sectorMomentumAtSignal(sectorMomentum)
                    .catalystTypeAtSignal(catalystType)
                    .catalystDirectionAtSignal(catalystDirection)
                    .regimeAtSignal(regime)
                    .rvolAtSignal(rvol)
                    .volRegimeAtSignal(volRegime)
                    .channelDirectionAtSignal(channelDirection)
                    .channelPositionAtSignal(channelPosition)
                    .indexChannelDirectionAtSignal(indexChannelDirection)
                    .indexChannelPositionAtSignal(indexChannelPosition)
                    .indexChannelWidthPctAtSignal(indexChannelWidthPct)
                    .build();
            // INSERT 는 REQUIRES_NEW 로 격리 — UNIQUE(uq_so_type_code_date) 위반(경합 패자)이
            // 호출부/이 메서드 tx 를 rollback-only 로 오염시키지 않게(새 tx 만 롤백).
            selfProvider.getObject().insertOutcomeIsolated(outcome);
        } catch (DataIntegrityViolationException dup) {
            // 경합 패자 — 다른 스레드/인스턴스가 같은 (type, code, date)를 먼저 기록(UNIQUE 가 차단). 정상.
            log.debug("[SignalOutcome] 중복 record 무시(경합 UNIQUE): {}/{}", signalType, stockCode);
        } catch (Exception e) {
            log.debug("[SignalOutcome] record 실패 ({}/{}): {}", signalType, stockCode, e.getMessage());
        }
    }

    /**
     * 시그널 outcome INSERT — REQUIRES_NEW 로 격리(작업2, V36).
     *
     * <p>UNIQUE(uq_so_type_code_date) 위반 시 <b>이 새 트랜잭션만 롤백</b>되고, 호출부 {@link #record}
     * 는 {@link DataIntegrityViolationException} 을 benign 처리한다(winner 가 이미 기록 = 결과 정합).
     * 같은 클래스 자기호출은 트랜잭션 어드바이스를 우회하므로 record() 는 {@code selfProvider.getObject()}
     * (프록시)로 이 메서드를 호출해야 REQUIRES_NEW 가 적용된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertOutcomeIsolated(SignalOutcome outcome) {
        repository.save(outcome);
    }

    /**
     * 시그널 이후 3거래일 OHLC 에서 max_high / max_low / MFE / MAE 계산 채움 (phase 25).
     * KIS getDailyOhlcv 가 최신 N일 반환 — signalDate 이후 항목만 필터링하여 high/low 누적.
     * KIS 미설정 또는 조회 실패 시 모든 필드 NULL 유지 (기존 평가는 영향 없음).
     */
    private void fillMfeMae(SignalOutcome outcome) {
        try {
            KoreaInvestmentService kis = kisProvider.getIfAvailable();
            if (kis == null || !kis.isConfigured()) return;
            // 시그널 이후 3거래일 + 안전 마진 = 7일 조회. 주말/공휴일 포함.
            java.util.List<KoreaInvestmentService.OhlcvData> ohlcv =
                    kis.getDailyOhlcv(outcome.getStockCode(), 7);
            if (ohlcv == null || ohlcv.isEmpty()) return;
            BigDecimal maxHigh = null;
            BigDecimal minLow = null;
            for (KoreaInvestmentService.OhlcvData candle : ohlcv) {
                if (candle.getTradeDate() == null) continue;
                if (!candle.getTradeDate().isAfter(outcome.getSignalDate())) continue; // 시그널일 이후만
                if (candle.getHigh() != null && (maxHigh == null || candle.getHigh().compareTo(maxHigh) > 0)) {
                    maxHigh = candle.getHigh();
                }
                if (candle.getLow() != null && (minLow == null || candle.getLow().compareTo(minLow) < 0)) {
                    minLow = candle.getLow();
                }
            }
            if (maxHigh != null) {
                outcome.setMaxHigh3d(maxHigh);
                outcome.setMfePct3d(maxHigh.subtract(outcome.getPriceAtSignal())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(outcome.getPriceAtSignal(), 4, RoundingMode.HALF_UP));
            }
            if (minLow != null) {
                outcome.setMaxLow3d(minLow);
                outcome.setMaePct3d(minLow.subtract(outcome.getPriceAtSignal())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(outcome.getPriceAtSignal(), 4, RoundingMode.HALF_UP));
            }
        } catch (Exception e) {
            log.debug("[SignalOutcome] MFE/MAE 채우기 실패 id={}: {}", outcome.getId(), e.getMessage());
        }
    }

    /** KOSPI 종합지수 현재가 조회 — phase 20. KIS 미설정 시 null. */
    private BigDecimal fetchKospiPriceQuiet() {
        KoreaInvestmentService kis = kisProvider.getIfAvailable();
        if (kis == null || !kis.isConfigured()) return null;
        try {
            JsonNode resp = kis.getIndexPrice(KOSPI_INDEX_CODE);
            if (resp == null || !resp.has("output")) return null;
            JsonNode output = resp.get("output");
            JsonNode priceNode = output.has("bstp_nmix_prpr") ? output.get("bstp_nmix_prpr") : null;
            if (priceNode == null || priceNode.asText().isEmpty()) return null;
            return new BigDecimal(priceNode.asText().replace(",", ""));
        } catch (Exception e) {
            log.debug("[SignalOutcome] KOSPI 가격 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 매일 19:30 — 3일 전 unevaluated 시그널 평가.
     * batchScheduler 로 트레이딩 부담 0.
     *
     * <p><b>멱등성(확인됨 2026-06-29, 작업4)</b>: {@code findPendingEvaluation(cutoff)} 로 <b>이미 존재하는
     * pending 행을 조회 → 필드 채움 → {@code save()} 로 동일 PK UPDATE</b> 한다(신규 INSERT 아님). 평가 후
     * {@code evaluatedAt} 이 채워지면 다음 조회의 pending 에서 빠지므로, 배치 락(fail-open)이 Redis 장애로
     * 중복 실행돼도 같은 행을 같은 값으로 재기록할 뿐 <b>중복 레코드가 생기지 않는다</b>. 따라서 이 잡엔 별도
     * fail-closed 락이 불필요. (단 {@code checkStrongBuyAlphaHealth()} 텔레그램은 더블런 시 1회 중복 발송 — 경미.)
     * 시그널 <b>생성</b>(record())의 중복 INSERT 방지는 앱레벨 dedup 뿐 — DB unique 제약 검토는 VERIFICATION_BACKLOG 참조.
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 30 19 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void evaluatePendingSignals() {
        LocalDate cutoff = resolveEvaluationCutoff(LocalDate.now());
        // 상한 — KIS 장애로 백로그가 쌓여도 한 배치가 커넥션을 장시간 물지 않게(초과분은 다음 실행에서 이어서).
        List<SignalOutcome> pending = repository.findPendingEvaluation(
                cutoff, org.springframework.data.domain.PageRequest.of(0, MAX_EVAL_PER_RUN));
        if (pending.isEmpty()) {
            // 평가 대상 없어도 헬스 체크는 실행 — 누적 표본 기준이라 신규 평가 0건이어도 의미 있음.
            checkStrongBuyAlphaHealth();
            // 평가 0건도 "크론이 정상 돌았다"는 심박 — dead-man switch 는 크론 사망을 감시(결과 아님).
            beatEvaluationHeartbeat();
            return;
        }

        log.info("[SignalOutcome] 평가 대상 {}건 (signalDate ≤ {})", pending.size(), cutoff);
        // KOSPI 현재가 한 번 조회 (모든 outcome에 동일 시점) — phase 20 alpha 계산.
        BigDecimal kospiNow = fetchKospiPriceQuiet();
        int evaluated = 0;
        for (SignalOutcome outcome : pending) {
            try {
                StockPriceDto current = stockPriceService.getStockPrice(outcome.getStockCode());
                if (current == null || current.getCurrentPrice() == null
                        || current.getCurrentPrice().signum() <= 0) {
                    continue;
                }
                BigDecimal priceNow = current.getCurrentPrice();
                BigDecimal pct = priceNow.subtract(outcome.getPriceAtSignal())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(outcome.getPriceAtSignal(), 4, RoundingMode.HALF_UP);

                // BM 변동률 + alpha — phase 20.
                BigDecimal bmReturn = null;
                BigDecimal alpha = null;
                BigDecimal bmAtSignal = outcome.getBmPriceAtSignal();
                if (kospiNow != null && bmAtSignal != null && bmAtSignal.signum() > 0) {
                    bmReturn = kospiNow.subtract(bmAtSignal)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(bmAtSignal, 4, RoundingMode.HALF_UP);
                    alpha = pct.subtract(bmReturn);
                }

                // hit 기준 — phase 20: alpha 양수 + 절대 수익 양수. BM 데이터 없으면 +3% 폴백. (isHit 로 분리)
                boolean hit = isHit(alpha, pct);

                outcome.setPriceAfter3d(priceNow);
                outcome.setPctChange3d(pct);
                outcome.setBmReturn3d(bmReturn);
                outcome.setAlpha3d(alpha);
                outcome.setHit(hit);

                // MFE / MAE — phase 25. 시그널일 이후 3거래일 OHLC 수집.
                // KIS getDailyOhlcv 가 최신순 반환. signalDate 이후 항목만 필터링.
                fillMfeMae(outcome);

                outcome.setEvaluatedAt(LocalDateTime.now());
                repository.save(outcome);
                evaluated++;
            } catch (org.springframework.dao.DataAccessException e) {
                // 저장 실패는 트랜잭션을 rollback-only 로 마킹해 커밋 시 전체 평가분이 소실된다 —
                // debug 로 삼키면 원인 없이 "평가 완료" 로그만 남으므로 ERROR 로 노출.
                log.error("[SignalOutcome] 저장 실패 id={} — 이번 배치 전체 롤백 가능: {}",
                        outcome.getId(), e.getMessage(), e);
            } catch (Exception e) {
                log.debug("[SignalOutcome] 평가 실패 id={}: {}", outcome.getId(), e.getMessage());
            }
        }
        if (pending.size() >= MAX_EVAL_PER_RUN) {
            log.warn("[SignalOutcome] 평가 상한({}) 도달 — 잔여 백로그는 다음 실행에서 처리", MAX_EVAL_PER_RUN);
        }
        log.info("[SignalOutcome] 평가 완료 {}/{} (BM alpha: {})",
                evaluated, pending.size(), kospiNow != null ? "활성" : "비활성(폴백)");
        checkStrongBuyAlphaHealth();
        beatEvaluationHeartbeat();
    }

    /**
     * "3거래일 경과" 평가 컷오프 — 주말·공휴일 제외 역산(문서·집계가 전제하는 "3거래일 후"와 일치).
     * 캘린더 미가용(단위테스트 미주입 등) 시 달력일 폴백 — 종전 동작 보존.
     */
    private LocalDate resolveEvaluationCutoff(LocalDate today) {
        MarketCalendarService calendar = calendarProvider != null ? calendarProvider.getIfAvailable() : null;
        if (calendar != null) {
            return calendar.minusTradingDays(today, EVALUATION_DELAY_DAYS);
        }
        return today.minusDays(EVALUATION_DELAY_DAYS);
    }

    /** 19:30 평가 크론 성공 심박 — dead-man switch(BatchHeartbeatService). best-effort, 평가 본체 무영향. */
    private void beatEvaluationHeartbeat() {
        try {
            if (heartbeatProvider == null) return;   // 단위테스트 미주입 보존
            BatchHeartbeatService heartbeat = heartbeatProvider.getIfAvailable();
            if (heartbeat != null) heartbeat.recordSuccess(BatchHeartbeatService.JOB_SIGNAL_EVALUATION);
        } catch (Exception e) {
            log.debug("[SignalOutcome] 심박 기록 실패: {}", e.getMessage());
        }
    }

    /**
     * STRONG_BUY 평균 alpha 헬스 체크 — phase 33.
     *
     * <p>최근 7일 STRONG_BUY 시그널의 평균 alpha (vs KOSPI) 가 음수면 risk 채널 알림.
     * 산식에 영향 주는 가드가 아니라 <b>관찰 가드</b>: 운영 관찰 기반 튜닝 (phase 31 등)
     * 이 over-fit 됐을 때 빠르게 감지하기 위함.
     *
     * <p>스팸 방지: 일 1회 (lastAlphaAlertDate). 표본 부족 (null) 이면 스킵.
     * 텔레그램 미설정 환경에서는 로그만 출력.
     */
    private void checkStrongBuyAlphaHealth() {
        try {
            LocalDate today = LocalDate.now();
            if (today.equals(lastAlphaAlertDate)) return;
            BigDecimal avgAlpha = repository.averageAlphaSince("STRONG_BUY", today.minusDays(7));
            if (avgAlpha == null) return;
            if (avgAlpha.signum() >= 0) return;

            BigDecimal scaled = avgAlpha.setScale(2, RoundingMode.HALF_UP);
            log.warn("[SignalOutcome] ⚠ STRONG_BUY 최근 7일 평균 alpha 음수: {}% (산식 검토 권장)", scaled);

            TelegramNotificationService telegram = telegramProvider.getIfAvailable();
            if (telegram != null) {
                try {
                    telegram.sendRisk(String.format(
                            "⚠️ <b>STRONG_BUY 평균 alpha 음수</b>\n\n"
                                    + "• 최근 7일 평균 alpha: <b>%s%%</b>\n"
                                    + "• 추천 산식이 시장 대비 underperform 중\n"
                                    + "• /api/signal-outcomes/compare 로 phase 변경 시점 전후 비교 권장",
                            scaled.toPlainString()));
                } catch (Exception e) {
                    log.debug("[SignalOutcome] alpha 헬스 텔레그램 발송 실패: {}", e.getMessage());
                }
            }
            lastAlphaAlertDate = today;
        } catch (Exception e) {
            log.debug("[SignalOutcome] STRONG_BUY 헬스 체크 실패: {}", e.getMessage());
        }
    }

    /** 시그널별 적중률 통계 — 최근 days 일 기준. */
    public SignalAccuracyDto getAccuracy(int days) {
        LocalDate from = LocalDate.now().minusDays(Math.max(1, days));
        List<Object[]> rows = repository.aggregateStats(from);
        List<SignalStat> stats = new ArrayList<>();
        for (Object[] row : rows) {
            String type = (String) row[0];
            long total = ((Number) row[1]).longValue();
            long hits = row[2] == null ? 0L : ((Number) row[2]).longValue();
            BigDecimal avg = row[3] == null ? BigDecimal.ZERO : new BigDecimal(row[3].toString());
            BigDecimal hitRate = total == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(hits)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            stats.add(SignalStat.builder()
                    .signalType(type)
                    .totalSignals(total)
                    .hitCount(hits)
                    .hitRate(hitRate)
                    .avgPctChange(avg.setScale(2, RoundingMode.HALF_UP))
                    .build());
        }
        return SignalAccuracyDto.builder()
                .daysWindow(days)
                .stats(stats)
                .build();
    }

    /**
     * cutoff 시점 전후 windowDays 일 통계 비교 — phase 32.
     *
     * <p>용도: phase 31 추격매수 방지 산식이 alpha/hit-rate 를 실제로 개선했는지 검증.
     * 운영 관찰 기반 산식 변경이 누적되면 검증 없이는 over-fit 위험이 있어 도입.
     *
     * <p>구간: <b>[cutoff − windowDays, cutoff)</b> vs <b>[cutoff, cutoff + windowDays)</b>.
     * before 윈도우는 cutoff 미포함 (반열린 구간).
     *
     * <p>표본이 너무 적으면(< 3) delta 의 의미가 약하므로 sufficientSample 플래그로 표시.
     *
     * @param signalTypeFilter null/blank 이면 전체 시그널, 값이 있으면 해당 type 만
     * @param cutoff phase 변경 적용 시점
     * @param windowDays before/after 각 윈도우의 일수 (기본 30)
     */
    public SignalCompareDto compareAroundCutoff(String signalTypeFilter,
                                                LocalDate cutoff, int windowDays) {
        if (cutoff == null) cutoff = LocalDate.now();
        int w = windowDays < 1 ? 30 : windowDays;
        LocalDate beforeFrom = cutoff.minusDays(w);
        LocalDate afterTo = cutoff.plusDays(w);

        Map<String, SignalCompareDto.Stat> beforeStats =
                toStatMap(repository.aggregateStatsBetween(beforeFrom, cutoff));
        Map<String, SignalCompareDto.Stat> afterStats =
                toStatMap(repository.aggregateStatsBetween(cutoff, afterTo));

        // signalType 필터 적용 (있으면)
        if (signalTypeFilter != null && !signalTypeFilter.isBlank()) {
            beforeStats = filterByType(beforeStats, signalTypeFilter);
            afterStats = filterByType(afterStats, signalTypeFilter);
        }

        // delta 계산 — before/after 중 하나라도 등장한 타입 모두 포함, type 정렬 보존
        Set<String> allTypes = new LinkedHashSet<>();
        allTypes.addAll(beforeStats.keySet());
        allTypes.addAll(afterStats.keySet());

        List<SignalCompareDto.Delta> deltas = new ArrayList<>();
        for (String type : allTypes) {
            SignalCompareDto.Stat b = beforeStats.get(type);
            SignalCompareDto.Stat a = afterStats.get(type);
            deltas.add(SignalCompareDto.Delta.builder()
                    .signalType(type)
                    .hitRateChange(subtractOrNull(
                            a != null ? a.getHitRate() : null,
                            b != null ? b.getHitRate() : null))
                    .avgAlphaChange(subtractOrNull(
                            a != null ? a.getAvgAlpha() : null,
                            b != null ? b.getAvgAlpha() : null))
                    .avgPctChange(subtractOrNull(
                            a != null ? a.getAvgPctChange() : null,
                            b != null ? b.getAvgPctChange() : null))
                    .avgMfeChange(subtractOrNull(
                            a != null ? a.getAvgMfe() : null,
                            b != null ? b.getAvgMfe() : null))
                    .avgMaeChange(subtractOrNull(
                            a != null ? a.getAvgMae() : null,
                            b != null ? b.getAvgMae() : null))
                    .sufficientSample(b != null && b.getTotalSignals() >= 3
                            && a != null && a.getTotalSignals() >= 3)
                    .build());
        }

        return SignalCompareDto.builder()
                .cutoff(cutoff)
                .windowDays(w)
                .signalTypeFilter(signalTypeFilter)
                .before(SignalCompareDto.Window.builder()
                        .from(beforeFrom).to(cutoff)
                        .stats(new ArrayList<>(beforeStats.values()))
                        .build())
                .after(SignalCompareDto.Window.builder()
                        .from(cutoff).to(afterTo)
                        .stats(new ArrayList<>(afterStats.values()))
                        .build())
                .deltas(deltas)
                .build();
    }

    /** aggregateStatsBetween 결과 row 를 signalType → Stat 맵으로 변환. */
    private static Map<String, SignalCompareDto.Stat> toStatMap(List<Object[]> rows) {
        Map<String, SignalCompareDto.Stat> map = new HashMap<>();
        for (Object[] row : rows) {
            String type = (String) row[0];
            long total = ((Number) row[1]).longValue();
            long hits = row[2] == null ? 0L : ((Number) row[2]).longValue();
            BigDecimal hitRate = total == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(hits)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            map.put(type, SignalCompareDto.Stat.builder()
                    .signalType(type)
                    .totalSignals(total)
                    .hitCount(hits)
                    .hitRate(hitRate)
                    .avgPctChange(scaleOrNull(row[3]))
                    .avgAlpha(scaleOrNull(row[4]))
                    .avgMfe(scaleOrNull(row[5]))
                    .avgMae(scaleOrNull(row[6]))
                    .build());
        }
        return map;
    }

    private static Map<String, SignalCompareDto.Stat> filterByType(
            Map<String, SignalCompareDto.Stat> source, String type) {
        Map<String, SignalCompareDto.Stat> filtered = new HashMap<>();
        if (source.containsKey(type)) filtered.put(type, source.get(type));
        return filtered;
    }

    private static BigDecimal scaleOrNull(Object value) {
        if (value == null) return null;
        return new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal subtractOrNull(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.subtract(b).setScale(2, RoundingMode.HALF_UP);
    }

    // ================================================================
    // 조건부 적중률 — 점수 구간별 + 카테고리 강세별 (V30)
    // ================================================================

    /** 점수 구간 정의 — BUY 컷(55)부터. [from, to] 닫힌 구간. */
    private static final int[][] SCORE_BANDS = {{55, 64}, {65, 74}, {75, 84}, {85, 100}};
    // 카테고리 "강세" 판정 임계 — 카테고리별(척도·분포 상이). 단일 15는 섹터(실측 max14→강세 0건)·실적
    // (19/20만 존재해 뭉침) 오측정이라 분리. P1-6 n=88 실측 근거(측정 전용 — getAccuracyByBand, 라이브 산식 무관).
    static final int EARNINGS_STRONG_THRESHOLD = 20;     // 20=53.8% > 19=42.7% (19/20 중 상위 격리)
    static final int SUPPLY_STRONG_THRESHOLD = 15;       // 과다매수=혼잡 tier(역상관, 20=21.4%) — 강세 bucket이 낮은 hitRate=정상
    static final int TECHNICAL_STRONG_THRESHOLD = 13;    // 11~13 표본·변별 집중(13=+7.20 최고), board TECH_STRONG=13 정합
    static final int SECTOR_STRONG_THRESHOLD = 14;       // AI테마 sweet spot(14=65%·+6.86%, ≥15 표본 없음)

    /**
     * phase-38 anti-추격 튜닝(과열 페널티 단계화·tie-break changeRate asc·BULL 섹터 승수 1.0·신규진입 감점 복원)
     * 완료일(25d4247, 2026-06-25). 이전 signalDate 는 "추격 점수"라 <b>현재 점수 예측력 측정에서 제외</b>
     * — forward 측정 창의 시작. 이전 표본은 phase 36~38 혼재라 지금 산식 성적이 아님.
     */
    static final LocalDate PHASE38_CUTOFF = LocalDate.of(2026, 6, 25);

    /** 보드 종합점수 시그널 타입 — 다른 소스(AI/Composite/Surge)와 점수 스케일이 달라 격리(혼합 집계 방지). */
    static final Set<String> BOARD_SIGNAL_TYPES = Set.of("STRONG_BUY", "BUY");

    /** 집계 시작일 — 요청 창(now-days)과 phase-38 컷오프 중 늦은 쪽(현재 점수만). 순수 함수(테스트 대상). */
    static LocalDate resolveAccuracyFrom(int days, LocalDate now) {
        int d = days < 1 ? 90 : days;
        LocalDate from = now.minusDays(d);
        return from.isBefore(PHASE38_CUTOFF) ? PHASE38_CUTOFF : from;
    }

    /** 보드 종합점수 시그널만(STRONG_BUY/BUY) 격리 — 순수 함수(테스트 대상). 다른 소스는 점수 스케일이 달라 제외. */
    static List<SignalOutcome> filterBoardSignals(List<SignalOutcome> rows) {
        return rows.stream()
                .filter(s -> BOARD_SIGNAL_TYPES.contains(s.getSignalType()))
                .collect(Collectors.toList());
    }

    /**
     * 조건부 적중률 — <b>보드 종합점수(STRONG_BUY/BUY) 격리 + phase-38 컷오프</b> 이후 평가 완료분 기준.
     * 다른 시그널 소스(AI/Composite/수급급등)는 점수 스케일이 달라 제외하고, phase-38 이전 "추격 점수" 표본도 제외해
     * "현재 산식의 종합점수가 실제 수익과 상관있나"만 측정한다.
     */
    public com.myplatform.backend.dto.SignalBandAccuracyDto getAccuracyByBand(int days) {
        int d = days < 1 ? 90 : days;
        LocalDate from = resolveAccuracyFrom(days, LocalDate.now());
        List<SignalOutcome> rows = filterBoardSignals(repository.findEvaluatedSince(from));
        return com.myplatform.backend.dto.SignalBandAccuracyDto.builder()
                .daysWindow(d)
                .since(from)
                .bands(aggregateBands(rows))
                .categories(aggregateCategories(rows))
                .catalysts(aggregateCatalysts(rows))
                .regimes(aggregateRegimes(rows))
                .channels(aggregateChannels(rows))
                .indexChannels(aggregateIndexChannels(rows))
                .build();
    }

    /**
     * 시장 국면별 집계 (V32) — 순수 함수. "하락장에서도 먹히나" 검증용.
     * regime 컬럼 NULL(미수집) 행은 제외.
     *
     * <p><b>⚠ 유효 표본 = distinctDays(고유 signal_date)</b>(P3-11): regime 은 지수 축이라 같은 날 전 시그널이
     * 동일 국면값 = 서로 독립이 아니다. rows(행 수)로 재면 표본부족을 표본충분으로 위장(§4c). 지수 채널
     * (aggregateIndexChannels)과 동일 원칙 — 유의 판정은 distinctDays &lt; {@link #MIN_DISTINCT_DAYS} 기준.
     */
    static List<com.myplatform.backend.dto.SignalBandAccuracyDto.RegimeStat> aggregateRegimes(
            List<SignalOutcome> rows) {
        String[][] defs = {
                {"BULL", "상승장"},
                {"BEAR", "하락장"},
                {"SIDEWAYS", "횡보장"},
        };
        List<com.myplatform.backend.dto.SignalBandAccuracyDto.RegimeStat> result = new ArrayList<>();
        for (String[] def : defs) {
            long total = 0, hits = 0;
            BigDecimal pctSum = BigDecimal.ZERO;
            long pctCount = 0;
            Set<LocalDate> days = new java.util.HashSet<>();   // 고유 signal_date = 진짜 독립 표본 수
            for (SignalOutcome s : rows) {
                if (!def[0].equals(s.getRegimeAtSignal())) continue;
                total++;
                if (s.getSignalDate() != null) days.add(s.getSignalDate());
                if (Boolean.TRUE.equals(s.getHit())) hits++;
                if (s.getPctChange3d() != null) { pctSum = pctSum.add(s.getPctChange3d()); pctCount++; }
            }
            long distinctDays = days.size();
            result.add(com.myplatform.backend.dto.SignalBandAccuracyDto.RegimeStat.builder()
                    .regime(def[0])
                    .label(def[1])
                    .totalSignals(total)
                    .hitCount(hits)
                    .hitRate(rate(hits, total))
                    .avgPctChange(avg(pctSum, pctCount))
                    .distinctDays(distinctDays)
                    .insufficientSample(distinctDays < MIN_DISTINCT_DAYS)
                    .build());
        }
        return result;
    }

    /**
     * 재료 방향별 집계 (V31) — 순수 함수. "재료 있는 추천이 더 먹히나" 검증용.
     * catalyst 컬럼 NULL(미수집) 행은 제외. NONE = 뉴스는 봤으나 재료 없음.
     */
    static List<com.myplatform.backend.dto.SignalBandAccuracyDto.CatalystStat> aggregateCatalysts(
            List<SignalOutcome> rows) {
        String[][] defs = {
                {"POSITIVE", "호재"},
                {"NEGATIVE", "악재"},
                {"NEUTRAL", "중립"},
                {"NONE", "재료없음"},
        };
        List<com.myplatform.backend.dto.SignalBandAccuracyDto.CatalystStat> result = new ArrayList<>();
        for (String[] def : defs) {
            long total = 0, hits = 0;
            BigDecimal pctSum = BigDecimal.ZERO;
            long pctCount = 0;
            for (SignalOutcome s : rows) {
                if (!def[0].equals(s.getCatalystDirectionAtSignal())) continue;
                total++;
                if (Boolean.TRUE.equals(s.getHit())) hits++;
                if (s.getPctChange3d() != null) { pctSum = pctSum.add(s.getPctChange3d()); pctCount++; }
            }
            result.add(com.myplatform.backend.dto.SignalBandAccuracyDto.CatalystStat.builder()
                    .direction(def[0])
                    .label(def[1])
                    .totalSignals(total)
                    .hitCount(hits)
                    .hitRate(rate(hits, total))
                    .avgPctChange(avg(pctSum, pctCount))
                    .build());
        }
        return result;
    }

    /** 점수 구간별 집계 — 순수 함수 (테스트 대상). signalScore 없는 행은 제외. */
    static List<com.myplatform.backend.dto.SignalBandAccuracyDto.BandStat> aggregateBands(
            List<SignalOutcome> rows) {
        List<com.myplatform.backend.dto.SignalBandAccuracyDto.BandStat> result = new ArrayList<>();
        for (int[] band : SCORE_BANDS) {
            long total = 0, hits = 0;
            BigDecimal pctSum = BigDecimal.ZERO, alphaSum = BigDecimal.ZERO;
            long pctCount = 0, alphaCount = 0;
            for (SignalOutcome s : rows) {
                Integer score = s.getSignalScore();
                if (score == null || score < band[0] || score > band[1]) continue;
                total++;
                if (Boolean.TRUE.equals(s.getHit())) hits++;
                if (s.getPctChange3d() != null) { pctSum = pctSum.add(s.getPctChange3d()); pctCount++; }
                if (s.getAlpha3d() != null) { alphaSum = alphaSum.add(s.getAlpha3d()); alphaCount++; }
            }
            result.add(com.myplatform.backend.dto.SignalBandAccuracyDto.BandStat.builder()
                    .band(band[0] + "~" + band[1])
                    .scoreFrom(band[0])
                    .scoreTo(band[1])
                    .totalSignals(total)
                    .hitCount(hits)
                    .hitRate(rate(hits, total))
                    .avgPctChange(avg(pctSum, pctCount))
                    .avgAlpha(avg(alphaSum, alphaCount))
                    .build());
        }
        return result;
    }

    /** 카테고리 강세 표본별 집계 — 순수 함수. <b>카테고리별 임계</b>(척도 상이, P1-6 실측). V30 NULL 행 제외. */
    static List<com.myplatform.backend.dto.SignalBandAccuracyDto.CategoryStat> aggregateCategories(
            List<SignalOutcome> rows) {
        // {key, label, strongThreshold} — 카테고리별 임계
        Object[][] defs = {
                {"earnings", "실적", EARNINGS_STRONG_THRESHOLD},
                {"supplyDemand", "수급", SUPPLY_STRONG_THRESHOLD},
                {"technical", "기술", TECHNICAL_STRONG_THRESHOLD},
                {"sectorMomentum", "섹터", SECTOR_STRONG_THRESHOLD},
        };
        List<com.myplatform.backend.dto.SignalBandAccuracyDto.CategoryStat> result = new ArrayList<>();
        for (Object[] def : defs) {
            String key = (String) def[0];
            String label = (String) def[1];
            int threshold = (int) def[2];
            long total = 0, hits = 0;
            BigDecimal pctSum = BigDecimal.ZERO, alphaSum = BigDecimal.ZERO;
            long pctCount = 0, alphaCount = 0;
            for (SignalOutcome s : rows) {
                Integer score = categoryScore(s, key);
                if (score == null || score < threshold) continue;   // 카테고리별 임계
                total++;
                if (Boolean.TRUE.equals(s.getHit())) hits++;
                if (s.getPctChange3d() != null) { pctSum = pctSum.add(s.getPctChange3d()); pctCount++; }
                if (s.getAlpha3d() != null) { alphaSum = alphaSum.add(s.getAlpha3d()); alphaCount++; }
            }
            result.add(com.myplatform.backend.dto.SignalBandAccuracyDto.CategoryStat.builder()
                    .key(key)
                    .label(label)
                    .strongThreshold(threshold)
                    .totalSignals(total)
                    .hitCount(hits)
                    .hitRate(rate(hits, total))
                    .avgPctChange(avg(pctSum, pctCount))
                    .avgAlpha(avg(alphaSum, alphaCount))
                    .build());
        }
        return result;
    }

    /**
     * 히스토리(최신→과거 순) → 회귀 채널 — 순수 함수(테스트 대상). 차트/보드와 동일 산식
     * ({@link com.myplatform.backend.util.TrendChannelCalculator}). 가격 결측 봉은 건너뛰고,
     * 유효 봉 &lt;10 이면 null(§4c — 위장 금지).
     */
    static com.myplatform.backend.util.TrendChannelCalculator.Channel computeChannelFromHistory(
            List<com.myplatform.backend.entity.StockPriceHistory> newestFirst) {
        if (newestFirst == null || newestFirst.isEmpty()) return null;
        List<com.myplatform.backend.util.TrendChannelCalculator.Bar> bars = new ArrayList<>();
        for (int i = newestFirst.size() - 1; i >= 0; i--) {   // 최신→과거 입력을 과거→최신으로
            var h = newestFirst.get(i);
            if (h == null || h.getHighPrice() == null || h.getLowPrice() == null || h.getClosePrice() == null) continue;
            bars.add(new com.myplatform.backend.util.TrendChannelCalculator.Bar(
                    h.getHighPrice().doubleValue(), h.getLowPrice().doubleValue(), h.getClosePrice().doubleValue()));
        }
        return com.myplatform.backend.util.TrendChannelCalculator.compute(bars);
    }

    /** V49 채널 위치 밴드 경계 — ≤33 하단 / 34~66 중단 / ≥67 상단. */
    static final int CHANNEL_LOW_BAND_MAX = 33;
    static final int CHANNEL_HIGH_BAND_MIN = 67;

    /**
     * 채널 상태별 집계 (V49) — 순수 함수. "상승 채널 하단(눌림목) 매수가 실제로 먹히나 /
     * 상단(추격)이 실제로 부진한가" 검증용. 방향(UP/DOWN/FLAT) × 위치 밴드(하단/중단/상단) 9칸.
     * channel 컬럼 NULL(미수집) 행은 제외. 표본 적은 칸은 n 으로 노출(§4c — 표본부족 위장 금지).
     */
    static List<com.myplatform.backend.dto.SignalBandAccuracyDto.ChannelStat> aggregateChannels(
            List<SignalOutcome> rows) {
        String[][] dirDefs = {{"UP", "상승채널"}, {"DOWN", "하락채널"}, {"FLAT", "박스권"}};
        String[][] posDefs = {{"LOW", "하단(≤33%)"}, {"MID", "중단(34~66%)"}, {"HIGH", "상단(≥67%)"}};
        List<com.myplatform.backend.dto.SignalBandAccuracyDto.ChannelStat> result = new ArrayList<>();
        for (String[] dir : dirDefs) {
            for (String[] pos : posDefs) {
                long total = 0, hits = 0;
                BigDecimal pctSum = BigDecimal.ZERO, alphaSum = BigDecimal.ZERO;
                long pctCount = 0, alphaCount = 0;
                for (SignalOutcome s : rows) {
                    if (!dir[0].equals(s.getChannelDirectionAtSignal())) continue;
                    Integer p = s.getChannelPositionAtSignal();
                    if (p == null || !pos[0].equals(positionBand(p))) continue;
                    total++;
                    if (Boolean.TRUE.equals(s.getHit())) hits++;
                    if (s.getPctChange3d() != null) { pctSum = pctSum.add(s.getPctChange3d()); pctCount++; }
                    if (s.getAlpha3d() != null) { alphaSum = alphaSum.add(s.getAlpha3d()); alphaCount++; }
                }
                result.add(com.myplatform.backend.dto.SignalBandAccuracyDto.ChannelStat.builder()
                        .direction(dir[0])
                        .positionBand(pos[0])
                        .label(dir[1] + " " + pos[1])
                        .totalSignals(total)
                        .hitCount(hits)
                        .hitRate(rate(hits, total))
                        .avgPctChange(avg(pctSum, pctCount))
                        .avgAlpha(avg(alphaSum, alphaCount))
                        .build());
            }
        }
        return result;
    }

    /**
     * 유효 표본 최소 고유일 수 (V50 지수 채널) — 지수 축은 같은 날 전 시그널이 동일값이라 <b>고유 signal_date</b>
     * 로 센다. 행 수가 아무리 많아도 distinctDays 가 이 값 미만이면 insufficientSample(§4c 위장 금지).
     */
    static final int MIN_DISTINCT_DAYS = 10;

    /**
     * KOSPI 지수 채널 상태별 집계 (V50) — 순수 함수. 방향(UP/DOWN/FLAT) × 위치 밴드(하단/중단/상단) 9칸.
     * index_channel 컬럼 NULL(미수집) 행은 제외.
     *
     * <p><b>⚠ 유효 표본 = distinct signal_date 수</b>(행 수 아님): 지수 축은 종목 무관이라 같은 날 전 시그널이
     * 동일 채널값 = 서로 독립이 아니다. rows 기준으로 재면 표본부족을 표본충분으로 위장하는 셈(§4c). 각 셀에
     * distinctDays 를 함께 반환하고 insufficientSample 판정은 distinctDays &lt; {@link #MIN_DISTINCT_DAYS} 기준.
     */
    static List<com.myplatform.backend.dto.SignalBandAccuracyDto.IndexChannelStat> aggregateIndexChannels(
            List<SignalOutcome> rows) {
        String[][] dirDefs = {{"UP", "지수 상승채널"}, {"DOWN", "지수 하락채널"}, {"FLAT", "지수 박스권"}};
        String[][] posDefs = {{"LOW", "하단(≤33%)"}, {"MID", "중단(34~66%)"}, {"HIGH", "상단(≥67%)"}};
        List<com.myplatform.backend.dto.SignalBandAccuracyDto.IndexChannelStat> result = new ArrayList<>();
        for (String[] dir : dirDefs) {
            for (String[] pos : posDefs) {
                long total = 0, hits = 0;
                BigDecimal pctSum = BigDecimal.ZERO, alphaSum = BigDecimal.ZERO;
                long pctCount = 0, alphaCount = 0;
                Set<LocalDate> days = new java.util.HashSet<>();   // 고유 signal_date = 진짜 독립 표본 수
                for (SignalOutcome s : rows) {
                    if (!dir[0].equals(s.getIndexChannelDirectionAtSignal())) continue;
                    Integer p = s.getIndexChannelPositionAtSignal();
                    if (p == null || !pos[0].equals(positionBand(p))) continue;
                    total++;
                    if (s.getSignalDate() != null) days.add(s.getSignalDate());
                    if (Boolean.TRUE.equals(s.getHit())) hits++;
                    if (s.getPctChange3d() != null) { pctSum = pctSum.add(s.getPctChange3d()); pctCount++; }
                    if (s.getAlpha3d() != null) { alphaSum = alphaSum.add(s.getAlpha3d()); alphaCount++; }
                }
                long distinctDays = days.size();
                result.add(com.myplatform.backend.dto.SignalBandAccuracyDto.IndexChannelStat.builder()
                        .direction(dir[0])
                        .positionBand(pos[0])
                        .label(dir[1] + " " + pos[1])
                        .totalSignals(total)
                        .hitCount(hits)
                        .hitRate(rate(hits, total))
                        .avgPctChange(avg(pctSum, pctCount))
                        .avgAlpha(avg(alphaSum, alphaCount))
                        .distinctDays(distinctDays)
                        .insufficientSample(distinctDays < MIN_DISTINCT_DAYS)
                        .build());
            }
        }
        return result;
    }

    /** 채널 위치(0~100) → 밴드 키. 순수 함수(테스트 대상). */
    static String positionBand(int position) {
        if (position <= CHANNEL_LOW_BAND_MAX) return "LOW";
        if (position >= CHANNEL_HIGH_BAND_MIN) return "HIGH";
        return "MID";
    }

    private static Integer categoryScore(SignalOutcome s, String key) {
        return switch (key) {
            case "earnings" -> s.getEarningsAtSignal();
            case "supplyDemand" -> s.getSupplyDemandAtSignal();
            case "technical" -> s.getTechnicalAtSignal();
            case "sectorMomentum" -> s.getSectorMomentumAtSignal();
            default -> null;
        };
    }

    private static BigDecimal rate(long hits, long total) {
        if (total == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(hits)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal avg(BigDecimal sum, long count) {
        if (count == 0) return null;
        return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    /**
     * 일별 시계열 — phase 33. 프론트 그래프용.
     * 최근 N일 (signalDate 기준) 의 시그널별 hit-rate / 평균 변동률 / 평균 alpha 를 일자별로 반환.
     */
    public SignalTimeseriesDto getTimeseries(String signalTypeFilter, int days) {
        int d = days < 1 ? 60 : days;
        LocalDate from = LocalDate.now().minusDays(d);
        List<Object[]> rows = repository.aggregateDailyTimeseries(from);
        List<SignalTimeseriesDto.Point> points = new ArrayList<>();
        boolean filterActive = signalTypeFilter != null && !signalTypeFilter.isBlank();
        for (Object[] row : rows) {
            LocalDate date = (LocalDate) row[0];
            String type = (String) row[1];
            if (filterActive && !signalTypeFilter.equals(type)) continue;
            long total = ((Number) row[2]).longValue();
            long hits = row[3] == null ? 0L : ((Number) row[3]).longValue();
            BigDecimal hitRate = total == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(hits)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            points.add(SignalTimeseriesDto.Point.builder()
                    .date(date)
                    .signalType(type)
                    .totalSignals(total)
                    .hitCount(hits)
                    .hitRate(hitRate)
                    .avgPctChange(scaleOrNull(row[4]))
                    .avgAlpha(scaleOrNull(row[5]))
                    .build());
        }
        return SignalTimeseriesDto.builder()
                .daysWindow(d)
                .signalTypeFilter(signalTypeFilter)
                .points(points)
                .build();
    }
}
