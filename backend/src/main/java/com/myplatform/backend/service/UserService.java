package com.myplatform.backend.service;

import com.myplatform.backend.dto.ChangePasswordRequest;
import com.myplatform.backend.dto.UpdateProfileRequest;
import com.myplatform.backend.dto.UserDto;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public UserDto getUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
        return convertToDto(user);
    }

    public UserDto updateProfile(String username, UpdateProfileRequest request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        if (request.getName() != null && !request.getName().isBlank()) {
            user.setName(request.getName());
        }

        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            // 다른 사용자가 같은 이메일을 사용하는지 확인
            userRepository.findByEmail(request.getEmail())
                    .filter(u -> !u.getId().equals(user.getId()))
                    .ifPresent(u -> {
                        throw new RuntimeException("이미 사용 중인 이메일입니다.");
                    });
            user.setEmail(request.getEmail());
        }

        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            user.setPhone(request.getPhone());
        }

        User savedUser = userRepository.save(user);
        return convertToDto(savedUser);
    }

    public UserDto uploadProfileImage(String username, MultipartFile file) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        try {
            // 업로드 디렉토리 생성
            Path profileDir = Paths.get(uploadDir, "profiles");
            if (!Files.exists(profileDir)) {
                Files.createDirectories(profileDir);
            }

            // 기존 프로필 이미지 삭제
            if (user.getProfileImage() != null) {
                Path oldFile = Paths.get(uploadDir, user.getProfileImage());
                Files.deleteIfExists(oldFile);
            }

            // 새 파일명 생성
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : ".jpg";
            String newFilename = "profile_" + user.getId() + "_" + UUID.randomUUID().toString().substring(0, 8) + extension;
            String relativePath = "profiles/" + newFilename;

            // 파일 저장
            Path targetPath = profileDir.resolve(newFilename);
            Files.copy(file.getInputStream(), targetPath);

            // DB 업데이트
            user.setProfileImage(relativePath);
            User savedUser = userRepository.save(user);
            return convertToDto(savedUser);

        } catch (IOException e) {
            throw new RuntimeException("프로필 이미지 업로드에 실패했습니다.", e);
        }
    }

    public UserDto deleteProfileImage(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        if (user.getProfileImage() != null) {
            try {
                Path filePath = Paths.get(uploadDir, user.getProfileImage());
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                // 파일 삭제 실패해도 계속 진행
            }
            user.setProfileImage(null);
            userRepository.save(user);
        }

        return convertToDto(user);
    }

    public void changePassword(String username, ChangePasswordRequest request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        // 현재 비밀번호 확인
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new RuntimeException("현재 비밀번호가 일치하지 않습니다.");
        }

        // 새 비밀번호 유효성 검사
        if (request.getNewPassword() == null || request.getNewPassword().length() < 4) {
            throw new RuntimeException("새 비밀번호는 4자 이상이어야 합니다.");
        }

        // 비밀번호 변경
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public List<UserDto> getPendingUsers() {
        List<User> pendingUsers = userRepository.findByStatus("PENDING");
        return pendingUsers.stream()
                .map(this::convertToDto)
                .toList();
    }

    public void approveUser(Long userId, String status) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        if ("APPROVED".equals(status) || "REJECTED".equals(status)) {
            user.setStatus(status);
            userRepository.save(user);
        } else {
            throw new RuntimeException("유효하지 않은 상태값입니다.");
        }
    }

    private UserDto convertToDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setName(user.getName());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        dto.setProfileImage(user.getProfileImage());
        dto.setRole(user.getRole());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setUpdatedAt(user.getUpdatedAt());
        return dto;
    }
}
