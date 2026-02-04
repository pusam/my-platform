package com.myplatform.backend.repository;

import com.myplatform.backend.entity.LottoDraw;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LottoDrawRepository extends JpaRepository<LottoDraw, Long> {

    Optional<LottoDraw> findByDrawNo(Integer drawNo);

    @Query("SELECT l FROM LottoDraw l ORDER BY l.drawNo DESC LIMIT 1")
    Optional<LottoDraw> findLatestDraw();

    @Query("SELECT l FROM LottoDraw l ORDER BY l.drawNo DESC")
    List<LottoDraw> findAllOrderByDrawNoDesc();

    @Query("SELECT l FROM LottoDraw l ORDER BY l.drawNo DESC LIMIT :count")
    List<LottoDraw> findRecentDraws(int count);

    boolean existsByDrawNo(Integer drawNo);

    @Query("SELECT MAX(l.drawNo) FROM LottoDraw l")
    Optional<Integer> findMaxDrawNo();
}
