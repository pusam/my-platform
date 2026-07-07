package com.myplatform.backend.repository;

import com.myplatform.backend.entity.SignalOutcome;
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

    /** 평가 대상 — signalDate 가 cutoff 이하이고 아직 평가 안 된 항목. */
    @Query("SELECT s FROM SignalOutcome s WHERE s.signalDate <= :cutoff AND s.evaluatedAt IS NULL")
    List<SignalOutcome> findPendingEvaluation(@Param("cutoff") LocalDate cutoff);

    /** 시그널별 통계 — 지정 기간 내. [signalType, total, hitCount, avgPctChange] */
    @Query("""
        SELECT s.signalType,
               COUNT(s),
               SUM(CASE WHEN s.hit = TRUE THEN 1 ELSE 0 END),
               AVG(s.pctChange3d)
          FROM SignalOutcome s
         WHERE s.evaluatedAt IS NOT NULL
           AND s.signalDate >= :from
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
         GROUP BY s.stockCode
        """)
    List<Object[]> aggregateTrackRecordByCodes(@Param("codes") java.util.Collection<String> codes,
                                               @Param("from") LocalDate from);

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
