package com.myplatform.backend.repository;

import com.myplatform.backend.entity.ManualTradeJournal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 수동 매매 저널 (V43) 리포지토리 — read/write 모두 수동 저널 전용, 봇 경로와 분리.
 */
@Repository
public interface ManualTradeJournalRepository extends JpaRepository<ManualTradeJournal, Long> {

    /** 사용자 소유 리스트(최신 매수순). */
    List<ManualTradeJournal> findByUsernameOrderByBuyAtDesc(String username);

    /** 사용자 소유 단건(소유 검증 겸용) — 다른 사용자 id 접근 시 empty. */
    Optional<ManualTradeJournal> findByIdAndUsername(Long id, String username);

    /** 특정 종목의 사용자 기록(종목상세 신호 이력 마커용). */
    List<ManualTradeJournal> findByUsernameAndStockCodeOrderByBuyAtDesc(String username, String stockCode);

    /** 평가 대기(3거래일 미도래) — 자동 평가 배치 대상. buyAt 이 기준일 이전인 미평가 건. */
    @Query("SELECT j FROM ManualTradeJournal j WHERE j.evaluatedAt IS NULL AND j.buyAt < :beforeAt")
    List<ManualTradeJournal> findPendingEvaluation(@Param("beforeAt") LocalDateTime beforeAt);
}
