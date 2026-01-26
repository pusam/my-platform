package com.myplatform.backend.repository;

import com.myplatform.backend.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 사용자 알림 목록 조회 (최신순)
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);

    // 사용자 알림 페이지네이션
    Page<Notification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // 읽지 않은 알림 수 조회
    Long countByUserIdAndIsRead(Long userId, Boolean isRead);

    // 읽지 않은 알림 목록
    List<Notification> findByUserIdAndIsReadOrderByCreatedAtDesc(Long userId, Boolean isRead);

    // 모든 알림 읽음 처리
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.userId = :userId")
    int markAllAsRead(@Param("userId") Long userId);

    // 특정 알림 읽음 처리
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.id = :id AND n.userId = :userId")
    int markAsRead(@Param("id") Long id, @Param("userId") Long userId);

    // 오래된 알림 삭제 (30일 이상)
    @Modifying
    @Query("DELETE FROM Notification n WHERE n.userId = :userId AND n.createdAt < :date")
    int deleteOldNotifications(@Param("userId") Long userId, @Param("date") java.time.LocalDateTime date);
}
