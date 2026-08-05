package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.PatternDetection;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.PatternDetectionRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.util.CandlePatternDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 캔들 패턴 shadow 감지 배치 — <b>기록만 남긴다</b>. 봇/게이트/점수/추천 어디에도 연결 금지
 * (§4c 검증 안 된 지표 합산 금지 — P2-12 교훈). 4주+ 쌓인 뒤 백테스트 승률 검증 통과 시에만 합산 논의.
 *
 * <p><b>데이터 소스</b>: {@code stock_price_history} 일봉만 — 시세 단일 경로에서 적재된 테이블 재사용,
 * KIS 신규 호출 0(새 가격 조회 경로를 만들지 않는다). 히스토리가 하루 늦게 유입돼도 최근 3봉 재스캔 +
 * UNIQUE(stock, type, date) + 쿨다운으로 놓침/중복 없이 수렴한다. detected_date 는 <b>확정 봉의 거래일</b>.
 *
 * <p><b>평가</b>: 같은 배치의 2단계가 확정 후 10거래일 종가가 쌓인 행의 1/3/5/10일 수익률을 일괄 기록.
 * 히스토리가 영영 안 쌓이는 행(상폐·정지)은 {@value #EVAL_GIVE_UP_DAYS}일 give-up 창으로 큐에서 제외하고
 * warn 로그로 가시화(§4c 생존편향 신호 — SignalOutcome 과 동일 패턴).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatternDetectionService {

    /** universe 최소 일봉 수 — 미만이면 어차피 감지 불가(수렴 15 + RVOL 분모 20 + 박스/마진). */
    static final int UNIVERSE_MIN_HISTORY = 45;
    /** 일봉 벌크 로드 범위(캘린더 일) — 최대 수렴 40봉 + 박스 7봉 + RVOL/MA 표본 + 주말·공휴일 마진. */
    static final int LOAD_WINDOW_CALENDAR_DAYS = 160;
    /** 최근 N봉을 확정 봉 후보로 재스캔 — 히스토리 지연 유입 대비. */
    static final int RECENT_CONFIRM_BARS = 3;
    /** 같은 (종목, 패턴) 재기록 쿨다운(캘린더 일) — 박스 성장 등 연일 재확정 스팸 방지. */
    static final int COOLDOWN_DAYS = 7;
    /** 평가 수익률 지평(거래일). */
    static final int[] EVAL_HORIZONS = {1, 3, 5, 10};
    /** 미평가 give-up 창(캘린더 일) — 초과 행은 평가 큐에서 제외(고사 방지). */
    static final int EVAL_GIVE_UP_DAYS = 45;
    /** 배치당 평가 상한 — 백로그가 쌓여도 한 배치가 커넥션을 장시간 물지 않게. */
    static final int MAX_EVAL_PER_RUN = 500;

    private final StockPriceHistoryRepository historyRepository;
    private final PatternDetectionRepository detectionRepository;
    private final SchedulerLockService schedulerLockService;
    private final MarketCalendarService marketCalendar;
    private final ObjectMapper objectMapper;

    @Value("${pattern-detection.enabled:true}")
    private boolean enabled;

    /**
     * 일일 shadow 배치 — 평일 20:20 KST. 20:10 차트워밍/스냅샷 배치들 뒤, NXT 마감(20:00) 후 일봉 확정 시점.
     * 감지 → 기록, 이어서 미평가 행 수익률 평가. 텔레그램 알림 없음(관찰 전용 — 스팸 방지).
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 20 20 * * MON-FRI", zone = "Asia/Seoul")
    public void runDailyShadowBatch() {
        if (!enabled) return;
        if (marketCalendar.isMarketClosed()) { log.debug("[패턴감지] 휴장일 — 스킵"); return; }
        if (!schedulerLockService.tryLock("pattern.detection.daily", Duration.ofMinutes(30))) {
            log.debug("[패턴감지] 다른 인스턴스에서 진행 중 — 스킵");
            return;
        }
        try {
            int recorded = detectAndRecord();
            int evaluated = evaluatePendingReturns();
            log.info("[패턴감지] shadow 배치 완료 — 신규 기록 {}건, 수익률 평가 {}건", recorded, evaluated);
        } catch (Exception e) {
            log.error("[패턴감지] shadow 배치 실패: {}", e.getMessage(), e);
        }
    }

    /** universe 전 종목 일봉을 벌크 로드해 최근 봉에서 확정된 패턴을 기록. 반환 = 신규 기록 수. */
    int detectAndRecord() {
        List<String> universe = historyRepository.findStockCodesWithMinHistory(UNIVERSE_MIN_HISTORY);
        if (universe.isEmpty()) {
            log.info("[패턴감지] universe 비어있음 — 일봉 히스토리 미적재");
            return 0;
        }
        LocalDate since = LocalDate.now().minusDays(LOAD_WINDOW_CALENDAR_DAYS);
        // code asc, tradeDate desc 정렬 보장(findByStockCodesSince) → 코드별 그룹핑 (QuantTaService 와 동일 골격)
        Map<String, List<StockPriceHistory>> byCode = new LinkedHashMap<>();
        for (StockPriceHistory h : historyRepository.findByStockCodesSince(universe, since)) {
            byCode.computeIfAbsent(h.getStockCode(), k -> new ArrayList<>()).add(h);
        }

        int recorded = 0;
        CandlePatternDetector.RejectionStats stats = new CandlePatternDetector.RejectionStats();
        for (Map.Entry<String, List<StockPriceHistory>> e : byCode.entrySet()) {
            List<StockPriceHistory> rowsDesc = e.getValue();
            List<CandlePatternDetector.Detection> detections;
            try {
                detections = CandlePatternDetector.detectRecent(rowsDesc, RECENT_CONFIRM_BARS, stats);
            } catch (Exception ex) {
                log.warn("[패턴감지] 감지 실패({}) — 스킵: {}", e.getKey(), ex.getMessage());
                continue;
            }
            for (CandlePatternDetector.Detection d : detections) {
                if (saveDetection(e.getKey(), rowsDesc.get(0).getStockName(), d)) recorded++;
            }
        }
        // sanity 집계 — 감지 0건 지속/특정 사유 100% 기각(=임계 사망) 조기 발견용(첫 주 임계 1차 조정 근거).
        // 위치필터(MA/신고가) 대량 기각은 정상 — 그 아래 단계 도달 수가 봐야 할 숫자.
        log.info("[패턴감지] 기각 집계 — 후보 {}건({}종목 × 최근 {}봉 × 2패턴), 사유별: {}",
                stats.candidates(), byCode.size(), RECENT_CONFIRM_BARS, stats.summary());
        return recorded;
    }

    /** 쿨다운/UNIQUE dedup 후 저장. 반환 = 실제 신규 저장 여부. */
    private boolean saveDetection(String stockCode, String stockName, CandlePatternDetector.Detection d) {
        if (d.close == null || d.close.signum() <= 0) return false;
        String type = d.type.name();
        // 쿨다운 — 박스 성장(3→4→5봉) 등으로 연일 재확정되는 같은 패턴의 중복 행 방지
        if (detectionRepository.existsByStockCodeAndPatternTypeAndDetectedDateGreaterThanEqual(
                stockCode, type, d.tradeDate.minusDays(COOLDOWN_DAYS))) {
            return false;
        }
        try {
            detectionRepository.save(PatternDetection.builder()
                    .stockCode(stockCode)
                    .stockName(stockName)
                    .patternType(type)
                    .detectedDate(d.tradeDate)
                    .closeAtDetection(d.close)
                    .paramsSnapshot(toJsonOrNull(d.params))
                    .build());
            log.info("[패턴감지] {} {} @{} (close {})", stockCode, type, d.tradeDate, d.close);
            return true;
        } catch (DataIntegrityViolationException dup) {
            return false;   // UNIQUE 경합 — 동시/재실행 dedup, 정상
        }
    }

    /**
     * 미평가 행 수익률 평가 — 확정 후 10거래일 종가가 쌓인 행만 1/3/5/10일을 일괄 기록(부분 기록 없음
     * — 지평별 표본이 어긋나면 백테스트 비교가 왜곡된다). 미달 행은 다음 배치에서 재시도.
     */
    @Transactional
    int evaluatePendingReturns() {
        LocalDate oldestAllowed = LocalDate.now().minusDays(EVAL_GIVE_UP_DAYS);
        long abandoned = detectionRepository.countByEvaluatedAtIsNullAndDetectedDateBefore(oldestAllowed);
        if (abandoned > 0) {
            log.warn("[패턴감지] 평가 포기(미평가 {}일 초과) 잔량 {}건 — 상폐/정지 의심, "
                    + "백테스트 표본에서 빠짐(생존편향 참고)", EVAL_GIVE_UP_DAYS, abandoned);
        }

        List<PatternDetection> pending =
                detectionRepository.findByEvaluatedAtIsNullAndDetectedDateBetweenOrderByDetectedDateAsc(
                        oldestAllowed, LocalDate.now(), PageRequest.of(0, MAX_EVAL_PER_RUN));
        if (pending.isEmpty()) return 0;

        List<String> codes = pending.stream().map(PatternDetection::getStockCode).distinct().toList();
        LocalDate minDetected = pending.stream()
                .map(PatternDetection::getDetectedDate).min(Comparator.naturalOrder()).orElseThrow();
        Map<String, List<StockPriceHistory>> byCode = new LinkedHashMap<>();
        for (StockPriceHistory h : historyRepository.findByStockCodesSince(codes, minDetected)) {
            byCode.computeIfAbsent(h.getStockCode(), k -> new ArrayList<>()).add(h);
        }

        int evaluated = 0;
        for (PatternDetection pd : pending) {
            Map<Integer, BigDecimal> returns = forwardReturns(
                    pd.getCloseAtDetection(), pd.getDetectedDate(), byCode.get(pd.getStockCode()));
            if (returns == null) continue;   // 10거래일 미확보 — 다음 배치 재시도
            pd.setReturn1d(returns.get(1));
            pd.setReturn3d(returns.get(3));
            pd.setReturn5d(returns.get(5));
            pd.setReturn10d(returns.get(10));
            pd.setEvaluatedAt(LocalDateTime.now());
            detectionRepository.save(pd);
            evaluated++;
        }
        return evaluated;
    }

    /**
     * 확정일 이후 1/3/5/10거래일 종가 수익률(%) — 순수 함수. rowsDesc 는 리포지토리 표준 정렬(최신순).
     * 확정일 이후 유효 종가 봉이 10개 미만이거나 기준가가 무의미하면 null(미평가 유지 — §4c).
     */
    static Map<Integer, BigDecimal> forwardReturns(
            BigDecimal baseClose, LocalDate detectedDate, List<StockPriceHistory> rowsDesc) {
        if (baseClose == null || baseClose.signum() <= 0 || detectedDate == null || rowsDesc == null) return null;

        List<BigDecimal> closesAfterAsc = new ArrayList<>();
        for (int i = rowsDesc.size() - 1; i >= 0; i--) {   // 최신순 → 시간순
            StockPriceHistory h = rowsDesc.get(i);
            if (h == null || h.getTradeDate() == null || !h.getTradeDate().isAfter(detectedDate)) continue;
            BigDecimal c = h.getClosePrice();
            if (c == null || c.signum() <= 0) return null; // 중간 결측 — 지평 어긋남 방지, 평가 보류(§4c)
            closesAfterAsc.add(c);
        }
        int maxHorizon = EVAL_HORIZONS[EVAL_HORIZONS.length - 1];
        if (closesAfterAsc.size() < maxHorizon) return null;

        Map<Integer, BigDecimal> out = new LinkedHashMap<>();
        for (int h : EVAL_HORIZONS) {
            BigDecimal close = closesAfterAsc.get(h - 1);
            out.put(h, close.subtract(baseClose)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(baseClose, 4, RoundingMode.HALF_UP));
        }
        return out;
    }

    private String toJsonOrNull(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return null;   // 스냅샷은 best-effort — 감지 기록 자체를 막지 않는다
        }
    }
}
