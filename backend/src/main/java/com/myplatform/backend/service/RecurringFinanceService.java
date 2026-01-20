package com.myplatform.backend.service;

import com.myplatform.backend.dto.*;
import com.myplatform.backend.entity.RecurringFinance;
import com.myplatform.backend.entity.RecurringFinanceHistory;
import com.myplatform.backend.repository.RecurringFinanceHistoryRepository;
import com.myplatform.backend.repository.RecurringFinanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RecurringFinanceService {

    private final RecurringFinanceRepository recurringFinanceRepository;
    private final RecurringFinanceHistoryRepository historyRepository;

    public RecurringFinanceService(RecurringFinanceRepository recurringFinanceRepository,
                                   RecurringFinanceHistoryRepository historyRepository) {
        this.recurringFinanceRepository = recurringFinanceRepository;
        this.historyRepository = historyRepository;
    }

    /**
     * 고정 수입/지출 등록
     */
    @Transactional
    public RecurringFinanceDto addRecurring(String username, RecurringFinanceRequest request) {
        RecurringFinance entity = new RecurringFinance();
        entity.setUsername(username);
        entity.setType(request.getType());
        entity.setCategory(request.getCategory());
        entity.setName(request.getName());
        entity.setAmount(request.getAmount());
        entity.setDayOfMonth(request.getDayOfMonth() != null ? request.getDayOfMonth() : 1);
        entity.setStartDate(request.getStartDate() != null ? request.getStartDate() : LocalDate.now());
        entity.setEndDate(request.getEndDate());
        entity.setMemo(request.getMemo());
        entity.setIsActive(true);

        RecurringFinance saved = recurringFinanceRepository.save(entity);
        return RecurringFinanceDto.fromEntity(saved);
    }

    /**
     * 사용자의 모든 고정 수입/지출 조회
     */
    public List<RecurringFinanceDto> getAllRecurring(String username) {
        return recurringFinanceRepository.findByUsernameOrderByTypeAscCategoryAsc(username)
                .stream()
                .map(entity -> {
                    RecurringFinanceDto dto = RecurringFinanceDto.fromEntity(entity);
                    // 변경 이력 포함
                    List<RecurringFinanceHistoryDto> history = historyRepository
                            .findByRecurringIdOrderByEffectiveDateDesc(entity.getId())
                            .stream()
                            .map(RecurringFinanceHistoryDto::fromEntity)
                            .collect(Collectors.toList());
                    dto.setHistory(history);
                    return dto;
                })
                .collect(Collectors.toList());
    }

    /**
     * 활성화된 고정 수입/지출만 조회
     */
    public List<RecurringFinanceDto> getActiveRecurring(String username) {
        return recurringFinanceRepository.findByUsernameAndIsActiveTrueOrderByTypeAscCategoryAsc(username)
                .stream()
                .map(RecurringFinanceDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 특정 유형(수입/지출)의 고정 항목 조회
     */
    public List<RecurringFinanceDto> getRecurringByType(String username, String type) {
        return recurringFinanceRepository.findByUsernameAndTypeAndIsActiveTrueOrderByCategoryAsc(username, type)
                .stream()
                .map(RecurringFinanceDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 특정 월에 적용되는 고정 수입/지출 조회
     */
    public List<RecurringFinanceDto> getRecurringForMonth(String username, int year, int month) {
        LocalDate targetDate = LocalDate.of(year, month, 1);
        return recurringFinanceRepository.findActiveRecurringForMonth(username, targetDate)
                .stream()
                .map(RecurringFinanceDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 고정 수입/지출 금액 수정 (히스토리 기록)
     */
    @Transactional
    public RecurringFinanceDto updateRecurring(String username, Long id, RecurringFinanceUpdateRequest request) {
        RecurringFinance entity = recurringFinanceRepository.findByIdAndUsername(id, username)
                .orElseThrow(() -> new RuntimeException("항목을 찾을 수 없습니다."));

        // 금액이 변경된 경우 히스토리 기록
        if (request.getAmount() != null && !request.getAmount().equals(entity.getAmount())) {
            RecurringFinanceHistory history = new RecurringFinanceHistory();
            history.setRecurringId(entity.getId());
            history.setPreviousAmount(entity.getAmount());
            history.setNewAmount(request.getAmount());
            history.setEffectiveDate(request.getEffectiveDate() != null ?
                    request.getEffectiveDate() : LocalDate.now());
            history.setChangeReason(request.getChangeReason());
            historyRepository.save(history);

            entity.setAmount(request.getAmount());
        }

        // 기타 필드 업데이트
        if (request.getName() != null) {
            entity.setName(request.getName());
        }
        if (request.getMemo() != null) {
            entity.setMemo(request.getMemo());
        }

        RecurringFinance saved = recurringFinanceRepository.save(entity);
        RecurringFinanceDto dto = RecurringFinanceDto.fromEntity(saved);

        // 변경 이력 포함
        List<RecurringFinanceHistoryDto> historyList = historyRepository
                .findByRecurringIdOrderByEffectiveDateDesc(saved.getId())
                .stream()
                .map(RecurringFinanceHistoryDto::fromEntity)
                .collect(Collectors.toList());
        dto.setHistory(historyList);

        return dto;
    }

    /**
     * 고정 수입/지출 비활성화 (종료)
     */
    @Transactional
    public void deactivateRecurring(String username, Long id) {
        RecurringFinance entity = recurringFinanceRepository.findByIdAndUsername(id, username)
                .orElseThrow(() -> new RuntimeException("항목을 찾을 수 없습니다."));

        entity.setIsActive(false);
        entity.setEndDate(LocalDate.now());
        recurringFinanceRepository.save(entity);
    }

    /**
     * 고정 수입/지출 삭제
     */
    @Transactional
    public void deleteRecurring(String username, Long id) {
        RecurringFinance entity = recurringFinanceRepository.findByIdAndUsername(id, username)
                .orElseThrow(() -> new RuntimeException("항목을 찾을 수 없습니다."));

        recurringFinanceRepository.delete(entity);
    }

    /**
     * 특정 월의 고정 수입 합계
     */
    public BigDecimal getRecurringIncomeForMonth(String username, int year, int month) {
        LocalDate targetDate = LocalDate.of(year, month, 1);
        return recurringFinanceRepository.findActiveRecurringForMonth(username, targetDate)
                .stream()
                .filter(r -> "INCOME".equals(r.getType()))
                .map(RecurringFinance::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 특정 월의 고정 지출 합계
     */
    public BigDecimal getRecurringExpenseForMonth(String username, int year, int month) {
        LocalDate targetDate = LocalDate.of(year, month, 1);
        return recurringFinanceRepository.findActiveRecurringForMonth(username, targetDate)
                .stream()
                .filter(r -> "EXPENSE".equals(r.getType()))
                .map(RecurringFinance::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
