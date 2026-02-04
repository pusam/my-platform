package com.myplatform.backend.repository;

import com.myplatform.backend.entity.PensionLotteryDraw;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PensionLotteryDrawRepository extends JpaRepository<PensionLotteryDraw, Long> {

    Optional<PensionLotteryDraw> findByDrawNo(Integer drawNo);

    @Query("SELECT p FROM PensionLotteryDraw p ORDER BY p.drawNo DESC LIMIT 1")
    Optional<PensionLotteryDraw> findLatestDraw();

    @Query("SELECT p FROM PensionLotteryDraw p ORDER BY p.drawNo DESC")
    List<PensionLotteryDraw> findAllOrderByDrawNoDesc();

    @Query("SELECT p FROM PensionLotteryDraw p ORDER BY p.drawNo DESC LIMIT :count")
    List<PensionLotteryDraw> findRecentDraws(int count);

    boolean existsByDrawNo(Integer drawNo);

    @Query("SELECT MAX(p.drawNo) FROM PensionLotteryDraw p")
    Optional<Integer> findMaxDrawNo();
}
