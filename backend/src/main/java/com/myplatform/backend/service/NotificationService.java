package com.myplatform.backend.service;

import com.myplatform.backend.dto.NotificationDto;
import com.myplatform.backend.entity.Notification;
import com.myplatform.backend.repository.NotificationRepository;
import com.myplatform.backend.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository notificationRepository, UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> getNotifications(String username, int page, int size) {
        Long userId = getUserId(username);
        Page<Notification> notifications = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
        return notifications.getContent().stream()
                .map(NotificationDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> getAllNotifications(String username) {
        Long userId = getUserId(username);
        List<Notification> notifications = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId);
        return notifications.stream()
                .map(NotificationDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Long getUnreadCount(String username) {
        Long userId = getUserId(username);
        return notificationRepository.countByUserIdAndIsRead(userId, false);
    }

    public void markAsRead(String username, Long notificationId) {
        Long userId = getUserId(username);
        notificationRepository.markAsRead(notificationId, userId);
    }

    public void markAllAsRead(String username) {
        Long userId = getUserId(username);
        notificationRepository.markAllAsRead(userId);
    }

    public NotificationDto createNotification(String username, String type, String title, String message, String link) {
        Long userId = getUserId(username);

        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type != null ? type : "INFO");
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setLink(link);
        notification.setIsRead(false);

        Notification saved = notificationRepository.save(notification);
        return new NotificationDto(saved);
    }

    public void createNotificationForUser(Long userId, String type, String title, String message, String link) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type != null ? type : "INFO");
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setLink(link);
        notification.setIsRead(false);

        notificationRepository.save(notification);
    }

    public void deleteOldNotifications(String username, int daysOld) {
        Long userId = getUserId(username);
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysOld);
        notificationRepository.deleteOldNotifications(userId, cutoffDate);
    }

    private Long getUserId(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."))
                .getId();
    }
}
