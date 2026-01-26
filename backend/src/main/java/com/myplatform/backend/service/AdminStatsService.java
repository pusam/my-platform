package com.myplatform.backend.service;

import com.myplatform.backend.dto.SystemStatsDto;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 관리자 통계 서비스
 */
@Service
@Transactional(readOnly = true)
public class AdminStatsService {

    private final UserRepository userRepository;
    private final BoardRepository boardRepository;
    private final UserFileRepository userFileRepository;
    private final UserAssetRepository userAssetRepository;
    private final FinanceTransactionRepository financeTransactionRepository;

    public AdminStatsService(UserRepository userRepository,
                            BoardRepository boardRepository,
                            UserFileRepository userFileRepository,
                            UserAssetRepository userAssetRepository,
                            FinanceTransactionRepository financeTransactionRepository) {
        this.userRepository = userRepository;
        this.boardRepository = boardRepository;
        this.userFileRepository = userFileRepository;
        this.userAssetRepository = userAssetRepository;
        this.financeTransactionRepository = financeTransactionRepository;
    }

    /**
     * 시스템 전체 통계 조회
     */
    public SystemStatsDto getSystemStats() {
        SystemStatsDto stats = new SystemStatsDto();

        // 사용자 통계
        stats.setTotalUsers(userRepository.count());
        stats.setActiveUsers(userRepository.countByStatus("APPROVED"));
        stats.setPendingUsers(userRepository.countByStatus("PENDING"));
        stats.setAdminCount(userRepository.countByRole("ADMIN"));

        // 게시판 통계
        stats.setTotalBoards(boardRepository.count());
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        stats.setTodayBoards(boardRepository.countByCreatedAtAfter(todayStart));

        // 파일 통계
        stats.setTotalFiles(userFileRepository.count());
        Long totalSize = userFileRepository.sumFileSize();
        if (totalSize != null) {
            stats.setTotalFileSize(totalSize / (1024.0 * 1024.0)); // MB 단위
        } else {
            stats.setTotalFileSize(0.0);
        }

        // 자산 & 거래 통계
        stats.setTotalAssets(userAssetRepository.count());
        stats.setTotalTransactions(financeTransactionRepository.count());

        // 최근 가입일
        stats.setLastSignupDate(userRepository.findTopByOrderByCreatedAtDesc()
            .map(User::getCreatedAt)
            .orElse(null));

        // 서버 가동 시간
        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        stats.setServerUptime(formatUptime(uptimeMillis));

        // 시스템 상태
        stats.setSystemStatus("HEALTHY");

        // 오늘 로그인 수 (향후 로그 테이블 추가 시 구현)
        stats.setTodayLogins(0L);

        return stats;
    }

    /**
     * 사용자별 통계 조회
     */
    public Map<String, Object> getUserStats(Long userId) {
        Map<String, Object> stats = new HashMap<>();

        // 사용자 정보 조회
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            stats.put("boardCount", 0L);
            stats.put("fileCount", 0L);
            stats.put("assetCount", 0L);
            stats.put("transactionCount", 0L);
            return stats;
        }

        String username = user.getUsername();

        // 사용자 게시글 수 (author는 username)
        stats.put("boardCount", boardRepository.countByAuthor(username));

        // 사용자 파일 수
        stats.put("fileCount", userFileRepository.countByUserId(userId));

        // 사용자 자산 수
        stats.put("assetCount", userAssetRepository.countByUserId(userId));

        // 사용자 거래 내역 수
        stats.put("transactionCount", financeTransactionRepository.countByUsername(username));

        return stats;
    }

    /**
     * 가동 시간 포맷팅
     */
    private String formatUptime(long uptimeMillis) {
        long seconds = uptimeMillis / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;

        if (days > 0) {
            return String.format("%d일 %d시간 %d분", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%d시간 %d분", hours, minutes);
        } else {
            return String.format("%d분", minutes);
        }
    }
}

