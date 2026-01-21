package com.myplatform.backend.repository;

import com.myplatform.backend.entity.ActivityLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    Page<ActivityLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ActivityLog> findByUsernameOrderByCreatedAtDesc(String username, Pageable pageable);

    Page<ActivityLog> findByActionTypeOrderByCreatedAtDesc(String actionType, Pageable pageable);

    @Query("SELECT a FROM ActivityLog a WHERE a.createdAt >= :startDate ORDER BY a.createdAt DESC")
    Page<ActivityLog> findByCreatedAtAfter(@Param("startDate") LocalDateTime startDate, Pageable pageable);

    @Query("SELECT a FROM ActivityLog a WHERE " +
           "(:username IS NULL OR a.username = :username) AND " +
           "(:actionType IS NULL OR a.actionType = :actionType) " +
           "ORDER BY a.createdAt DESC")
    Page<ActivityLog> findByFilters(@Param("username") String username,
                                    @Param("actionType") String actionType,
                                    Pageable pageable);

    List<ActivityLog> findTop100ByOrderByCreatedAtDesc();
}
