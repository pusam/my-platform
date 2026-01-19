package com.myplatform.backend.service;

import com.myplatform.backend.dto.FinanceRecordDto;
import com.myplatform.backend.dto.FinanceRecordRequest;
import com.myplatform.backend.entity.FinanceRecord;
import com.myplatform.backend.repository.FinanceRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class FinanceRecordService {

    private final FinanceRecordRepository financeRecordRepository;

    @Transactional(readOnly = true)
    public List<FinanceRecordDto> getMyRecords(String username) {
        return financeRecordRepository.findByUsernameOrderByYearDescMonthDesc(username)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public FinanceRecordDto saveRecord(String username, FinanceRecordRequest request) {
        // 해당 년월의 기록이 있는지 확인
        FinanceRecord record = financeRecordRepository
                .findByUsernameAndYearAndMonth(username, request.getYear(), request.getMonth())
                .orElse(FinanceRecord.builder()
                        .username(username)
                        .year(request.getYear())
                        .month(request.getMonth())
                        .build());

        // 업데이트
        record.setSalary(request.getSalary() != null ? request.getSalary() : BigDecimal.ZERO);
        record.setBonus(request.getBonus() != null ? request.getBonus() : BigDecimal.ZERO);
        record.setRent(request.getRent() != null ? request.getRent() : BigDecimal.ZERO);
        record.setUtilities(request.getUtilities() != null ? request.getUtilities() : BigDecimal.ZERO);
        record.setInsurance(request.getInsurance() != null ? request.getInsurance() : BigDecimal.ZERO);
        record.setLoan(request.getLoan() != null ? request.getLoan() : BigDecimal.ZERO);
        record.setSubscription(request.getSubscription() != null ? request.getSubscription() : BigDecimal.ZERO);
        record.setTransportation(request.getTransportation() != null ? request.getTransportation() : BigDecimal.ZERO);
        record.setFood(request.getFood() != null ? request.getFood() : BigDecimal.ZERO);
        record.setEtc(request.getEtc() != null ? request.getEtc() : BigDecimal.ZERO);
        record.setMemo(request.getMemo());

        FinanceRecord saved = financeRecordRepository.save(record);
        return toDto(saved);
    }

    public void deleteRecord(String username, Long recordId) {
        financeRecordRepository.deleteByUsernameAndId(username, recordId);
    }

    private FinanceRecordDto toDto(FinanceRecord record) {
        BigDecimal salary = record.getSalary() != null ? record.getSalary() : BigDecimal.ZERO;
        BigDecimal bonus = record.getBonus() != null ? record.getBonus() : BigDecimal.ZERO;
        BigDecimal rent = record.getRent() != null ? record.getRent() : BigDecimal.ZERO;
        BigDecimal utilities = record.getUtilities() != null ? record.getUtilities() : BigDecimal.ZERO;
        BigDecimal insurance = record.getInsurance() != null ? record.getInsurance() : BigDecimal.ZERO;
        BigDecimal loan = record.getLoan() != null ? record.getLoan() : BigDecimal.ZERO;
        BigDecimal subscription = record.getSubscription() != null ? record.getSubscription() : BigDecimal.ZERO;
        BigDecimal transportation = record.getTransportation() != null ? record.getTransportation() : BigDecimal.ZERO;
        BigDecimal food = record.getFood() != null ? record.getFood() : BigDecimal.ZERO;
        BigDecimal etc = record.getEtc() != null ? record.getEtc() : BigDecimal.ZERO;

        BigDecimal totalIncome = salary.add(bonus);
        BigDecimal totalExpense = rent.add(utilities).add(insurance).add(loan)
                .add(subscription).add(transportation).add(food).add(etc);
        BigDecimal balance = totalIncome.subtract(totalExpense);

        return FinanceRecordDto.builder()
                .id(record.getId())
                .year(record.getYear())
                .month(record.getMonth())
                .salary(salary)
                .bonus(bonus)
                .totalIncome(totalIncome)
                .rent(rent)
                .utilities(utilities)
                .insurance(insurance)
                .loan(loan)
                .subscription(subscription)
                .transportation(transportation)
                .food(food)
                .etc(etc)
                .totalExpense(totalExpense)
                .balance(balance)
                .memo(record.getMemo())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }
}
