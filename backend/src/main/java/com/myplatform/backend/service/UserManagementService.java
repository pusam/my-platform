package com.myplatform.backend.service;

import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class UserManagementService {

    private final UserRepository userRepository;
    private final EmailService emailService;

    public UserManagementService(UserRepository userRepository, EmailService emailService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    /**
     * 승인 대기 중인 사용자 목록 조회
     */
    public List<Map<String, Object>> getPendingUsers() {
        return userRepository.findByStatus("PENDING").stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * 전체 사용자 목록 조회
     */
    public List<Map<String, Object>> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * 회원가입 승인
     */
    public void approveUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        if (!"PENDING".equals(user.getStatus())) {
            throw new RuntimeException("승인 대기 중인 사용자가 아닙니다.");
        }

        user.setStatus("ACTIVE");
        userRepository.save(user);

        // 승인 완료 이메일 발송
        try {
            emailService.sendApprovalEmail(user.getEmail(), user.getName());
        } catch (Exception e) {
            // 이메일 발송 실패해도 승인은 완료
            System.err.println("승인 이메일 발송 실패: " + e.getMessage());
        }
    }

    /**
     * 회원가입 거부 (계정 삭제)
     */
    public void rejectUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        if (!"PENDING".equals(user.getStatus())) {
            throw new RuntimeException("승인 대기 중인 사용자가 아닙니다.");
        }

        userRepository.delete(user);

        // 거부 이메일 발송
        try {
            emailService.sendRejectionEmail(user.getEmail(), user.getName());
        } catch (Exception e) {
            System.err.println("거부 이메일 발송 실패: " + e.getMessage());
        }
    }

    /**
     * 계정 비활성화
     */
    public void deactivateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        user.setStatus("INACTIVE");
        userRepository.save(user);
    }

    /**
     * 계정 활성화
     */
    public void activateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        user.setStatus("ACTIVE");
        userRepository.save(user);
    }

    /**
     * 사용자 삭제
     */
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        if ("ADMIN".equals(user.getRole())) {
            throw new RuntimeException("관리자 계정은 삭제할 수 없습니다.");
        }

        userRepository.delete(user);
    }

    /**
     * 사용자 권한 변경
     */
    public void changeUserRole(Long userId, String role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        user.setRole(role);
        userRepository.save(user);
    }

    /**
     * User Entity를 Map으로 변환 (비밀번호 제외)
     */
    private Map<String, Object> convertToMap(User user) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("name", user.getName());
        map.put("email", user.getEmail());
        map.put("phone", user.getPhone());
        map.put("role", user.getRole());
        map.put("status", user.getStatus());
        map.put("createdAt", user.getCreatedAt());
        return map;
    }
}

