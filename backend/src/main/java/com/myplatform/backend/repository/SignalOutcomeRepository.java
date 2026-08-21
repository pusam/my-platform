package com.myplatform.backend.repository;

import com.myplatform.backend.entity.SignalOutcome;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SignalOutcomeRepository extends JpaRepository<SignalOutcome, Long> {

    /** 같은 날 같은 시그널/종목 중복 INSERT 방지용 lookup. */
    @Query("SELECT s FROM SignalOutcome s WHERE s.signalType = :type AND s.stockCode = :code AND s.signalDate = :date")
    List<SignalOutcome> findExisting(@Param("type") String signalType,
                                     @Param("code") String stockCode,
                                     @Param("date") LocalDate signalDate);

    /**
     * 평가 대상 — signalDate 가 [oldestAllowed, cutoff] 이고 아직 평가 안 된 항목. 오래된 것부터(백로그 순차 소진).
     * <p>Pageable 로 <b>상한</b>을 받는다: 평가 루프가 종목당 KIS 2콜을 트랜잭션 안에서 돌기 때문에,
     * KIS 장애로 미평가분이 수백 건 쌓이면 한 번의 배치가 DB 커넥션을 장시간 점유한다(풀 고갈).
     * 초과분은 다음 실행에서 이어서 처리된다(evaluatedAt 이 채워진 건 pending 에서 빠짐).
     * <p><b>oldestAllowed(2026-07-28)</b>: 영구 평가 불가 행(상폐·정지로 시세 없음)이 ASC 정렬 + 상한의
     * 머리를 차지하면, 죽은 행 300개가 상한을 다 먹어 <b>신규 시그널이 영영 평가 안 되는 고사(starvation)</b>가
     * 생긴다. give-up 창(서비스 EVAL_GIVE_UP_DAYS)보다 오래된 행은 큐에서 제외 — evaluatedAt=NULL 로 남아
     * 집계에선 원래대로 빠지고(§4c: 미평가=제외 의미 유지), 잔량은 {@link #countAbandonedPending} 으로 가시화.
     */
    @Query("SELECT s FROM SignalOutcome s WHERE s.signalDate <= :cutoff AND s.signalDate >= :oldestAllowed "
            + "AND s.evaluatedAt IS NULL ORDER BY s.signalDate ASC, s.id ASC")
    List<SignalOutcome> findPendingEvaluation(@Param("cutoff") LocalDate cutoff,
                                              @Param("oldestAllowed") LocalDate oldestAllowed,
                                              Pageable pageable);

    /** give-up 창을 넘겨 평가를 포기한 미평가 행 수 — 배치 로그 가시화용(§4c: 조용한 소실 금지). */
    @Query("SELECT COUNT(s) FROM SignalOutcome s WHERE s.signalDate < :oldestAllowed AND s.evaluatedAt IS NULL")
    long countAbandonedPending(@Param("oldestAllowed") LocalDate oldestAllowed);

    /**
     * 시그널별 통계 — 지정 기간 내. [signalType, total, hitCount, avgPctChange]
     * <p>CONTROL_RANDOM(무작위 대조군, {@code ControlGroupService.CONTROL_SIGNAL_TYPE}) 제외 —
     * 대조군은 controlComparison 전용 비교축이지 "시그널 타입"이 아니다. 무필터면 화면 타입 목록에
     * 무작위 표본이 하나의 시그널처럼 섞여 나온다(2026-08-21 리뷰 P3-K).
     */
    @Query("""
        SELECT s.signalType,
               COUNT(s),
               SUM(CASE WHEN s.hit = TRUE THEN 1 ELSE 0 END),
               AVG(s.pctChange3d)
          FROM SignalOutcome s
         WHERE s.evaluatedAt IS NOT NULL
           AND s.signalDate >= :from
           AND s.signalType <> 'CONTROL_RANDOM'
         GROUP BY s.signalType
         ORDER BY s.signalType
        """)
    List<Object[]> aggregateStats(@Param("from") LocalDate from);

    /**
     * 시그널별 확장 통계 — 지정 기간 [from, to). alpha / MFE / MAE 포함.
     * phase 32: phase 31 추격매수 방지 변경 전후 비교에 사용.
     * <p>리턴: [signalType, total, hitCount, avgPctChange, avgAlpha, avgMfe, avgMae]
     */
    @Query("""
        SELECT s.signalType,
               COUNT(s),
               SUM(CASE WHEN s.hit = TRUE THEN 1 ELSE 0 END),
               AVG(s.pctChange3d),
               AVG(s.alpha3d),
               AVG(s.mfePct3d),
               AVG(s.maePct3d)
          FROM SignalOutcome s
         WHERE s.evaluatedAt IS NOT NULL
           AND s.signalDate >= :from
           AND s.signalDate < :to
           AND s.signalType <> 'CONTROL_RANDOM'
         GROUP BY s.signalType
         ORDER BY s.signalType
        """)
    List<Object[]> aggregateStatsBetween(@Param("from") LocalDate from,
                                         @Param("to") LocalDate to);

    /**
     * 일별 시그널별 시계열 — phase 33. 그래프 표시용.
     * <p>리턴: [signalDate, signalType, total, hitCount, avgPctChange, avgAlpha]
     */
    @Query("""
        SELECT s.signalDate,
               s.signalType,
               COUNT(s),
               SUM(CASE WHEN s.hit = TRUE THEN 1 ELSE 0 END),
               AVG(s.pctChange3d),
               AVG(s.alpha3d)
          FROM SignalOutcome s
         WHERE s.evaluatedAt IS NOT NULL
           AND s.signalDate >= :from
           AND s.signalType <> 'CONTROL_RANDOM'
         GROUP BY s.signalDate, s.signalType
         ORDER BY s.signalDate ASC, s.signalType ASC
        """)
    List<Object[]> aggregateDailyTimeseries(@Param("from") LocalDate from);

    /**
     * 최근 N일 특정 시그널의 평균 alpha — phase 33 헬스 가드.
     * <p>리턴: 단일 BigDecimal (없으면 null).
     */
    @Query("""
        SELECT AVG(s.alpha3d)
          FROM SignalOutcome s
         WHERE s.evaluatedAt IS NOT NULL
           AND s.signalType = :type
           AND s.signalDate >= :from
           AND s.alpha3d IS NOT NULL
        """)
    java.math.BigDecimal averageAlphaSince(@Param("type") String signalType,
                                            @Param("from") LocalDate from);

    /**
     * 특정 시그널 타입의 MFE / MAE 평균 — 매매 계획(손절/목표) 현실성 표시용.
     * <p>리턴: [count, avgMfe, avgMae]. MFE/MAE 둘 다 채워진(평가 + KIS OHLC 성공) 행만 집계.
     */
    @Query("""
        SELECT COUNT(s),
               AVG(s.mfePct3d),
               AVG(s.maePct3d)
          FROM SignalOutcome s
         WHERE s.evaluatedAt IS NOT NULL
           AND s.signalType = :type
           AND s.signalDate >= :from
           AND s.mfePct3d IS NOT NULL
           AND s.maePct3d IS NOT NULL
        """)
    List<Object[]> aggregateMfeMae(@Param("type") String signalType,
                                   @Param("from") LocalDate from);

    /** 평가 완료된 시그널 전체 — 점수 구간/카테고리 조건부 적중률 집계 입력 (서비스에서 순수 함수로 집계). */
    @Query("""
        SELECT s FROM SignalOutcome s
         WHERE s.evaluatedAt IS NOT NULL
           AND s.signalDate >= :from
        """)
    List<SignalOutcome> findEvaluatedSince(@Param("from") LocalDate from);

    /**
     * 평가 완료된 시그널 — signalDate 가 [from, to] 닫힌 구간. 주간 측정(P1-6 상설화) 입력.
     * 서비스에서 순수 함수(regime 파티션별 카테고리/밴드)로 집계.
     */
    @Query("""
        SELECT s FROM SignalOutcome s
         WHERE s.evaluatedAt IS NOT NULL
           AND s.signalDate >= :from
           AND s.signalDate <= :to
        """)
    List<SignalOutcome> findEvaluatedBetween(@Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    /**
     * 종목별 신호 이력 (최근 N일) — 종목 상세 "📜 신호 이력" 입력. 평가 전(pending) 행 포함
     * (§4c: 미평가를 미스로 위장하지 않고 "평가 대기"로 구분 표시). 최신순.
     */
    List<SignalOutcome> findByStockCodeAndSignalDateGreaterThanEqualOrderBySignalDateDesc(
            String stockCode, LocalDate from);

    /**
     * 종목별 이력 실적 일괄 집계 — 종합 판단 보드 signalTrackRecord 컬럼용.
     * <b>IN 절 1쿼리</b>(보드 행별 개별 조회 N+1 금지 — 보드 로딩 시간 보호). 평가 완료 행만.
     *
     * <p><b>types 필터 필수(2026-08-21 리뷰 F15)</b>: 이전엔 타입 무필터라 그 종목이 <b>무작위로
     * 뽑힌 대조군(CONTROL_RANDOM) 행</b>·타 엔진(AI/COMPOSITE/SURGE) 시그널까지 "이력 N회 중 M회
     * 적중"에 합산됐다 — 대조군은 성능 없음이 설계 목적인 데이터인데 종목 실적으로 표시·정렬됐다.
     * 호출부는 {@code SignalOutcomeService.BOARD_SIGNAL_TYPES} 를 넘길 것.
     * (승격일 BUY+STRONG_BUY 2행이 2회로 계상되는 잔여는 SQL 집계 한계 — AUDIT R10)
     * <p>리턴: [stockCode, total, hitCount, avgAlpha]
     */
    @Query("""
        SELECT s.stockCode,
               COUNT(s),
               SUM(CASE WHEN s.hit = TRUE THEN 1 ELSE 0 END),
               AVG(s.alpha3d)
          FROM SignalOutcome s
         WHERE s.evaluatedAt IS NOT NULL
           AND s.stockCode IN :codes
           AND s.signalDate >= :from
           AND s.signalType IN :types
         GROUP BY s.stockCode
        """)
    List<Object[]> aggregateTrackRecordByCodes(@Param("codes") java.util.Collection<String> codes,
                                               @Param("from") LocalDate from,
                                               @Param("types") java.util.Collection<String> types);

    /** phase 35b 진단 — 마지막 signal_date. */
    @Query("SELECT MAX(s.signalDate) FROM SignalOutcome s")
    java.util.Optional<LocalDate> findMaxSignalDate();

    /** phase 35b 진단 — 평가 완료 건수. */
    @Query("SELECT COUNT(s) FROM SignalOutcome s WHERE s.evaluatedAt IS NOT NULL")
    long countEvaluated();

    /** phase 35b 진단 — 시그널 type 별 카운트 [type, total]. */
    @Query("""
        SELECT s.signalType, COUNT(s)
          FROM SignalOutcome s
         GROUP BY s.signalType
         ORDER BY s.signalType
        """)
    List<Object[]> countByType();
}
