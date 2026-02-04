package com.myplatform.backend.repository;

import com.myplatform.backend.entity.AlertHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface AlertHistoryRepository extends JpaRepository<AlertHistory, Long> {

    @Query("SELECT a FROM AlertHistory a WHERE a.alertKey = :alertKey ORDER BY a.sentAt DESC LIMIT 1")
    Optional<AlertHistory> findLatestByAlertKey(String alertKey);

    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END FROM AlertHistory a " +
           "WHERE a.alertKey = :alertKey AND a.sentAt > :cutoffTime")
    boolean existsRecentAlert(String alertKey, LocalDateTime cutoffTime);

    @Modifying
    @Query("DELETE FROM AlertHistory a WHERE a.sentAt < :cutoffTime")
    void deleteOldAlerts(LocalDateTime cutoffTime);
}
