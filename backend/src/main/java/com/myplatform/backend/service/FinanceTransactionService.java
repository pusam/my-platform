package com.myplatform.backend.service;

import com.myplatform.backend.dto.FinanceSummaryDto;
import com.myplatform.backend.dto.FinanceTransactionDto;
import com.myplatform.backend.dto.FinanceTransactionRequest;
import com.myplatform.backend.entity.FinanceTransaction;
import com.myplatform.backend.repository.FinanceTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class FinanceTransactionService {

    private final FinanceTransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public List<FinanceTransactionDto> getAllTransactions(String username) {
        return transactionRepository.findByUsernameOrderByTransactionDateDesc(username)
                .stream()
                .map(FinanceTransactionDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public FinanceSummaryDto getMonthlyTransactions(String username, int year, int month) {
        List<FinanceTransaction> transactions = transactionRepository
                .findByUsernameAndYearMonth(username, year, month);

        BigDecimal totalIncome = transactions.stream()
                .filter(t -> t.getType() == FinanceTransaction.TransactionType.INCOME)
                .map(FinanceTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalExpense = transactions.stream()
                .filter(t -> t.getType() == FinanceTransaction.TransactionType.EXPENSE)
                .map(FinanceTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<FinanceTransactionDto> transactionDtos = transactions.stream()
                .map(FinanceTransactionDto::fromEntity)
                .collect(Collectors.toList());

        return FinanceSummaryDto.builder()
                .year(year)
                .month(month)
                .totalIncome(totalIncome)
                .totalExpense(totalExpense)
                .balance(totalIncome.subtract(totalExpense))
                .transactions(transactionDtos)
                .build();
    }

    public FinanceTransactionDto addTransaction(String username, FinanceTransactionRequest request) {
        FinanceTransaction transaction = FinanceTransaction.builder()
                .username(username)
                .type(FinanceTransaction.TransactionType.valueOf(request.getType()))
                .category(request.getCategory())
                .amount(request.getAmount())
                .transactionDate(request.getTransactionDate())
                .memo(request.getMemo())
                .build();

        FinanceTransaction saved = transactionRepository.save(transaction);
        return FinanceTransactionDto.fromEntity(saved);
    }

    public void deleteTransaction(String username, Long transactionId) {
        FinanceTransaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        if (!transaction.getUsername().equals(username)) {
            throw new RuntimeException("Unauthorized");
        }

        transactionRepository.delete(transaction);
    }

    @Transactional(readOnly = true)
    public List<int[]> getAvailableMonths(String username) {
        return transactionRepository.findDistinctYearMonthByUsername(username)
                .stream()
                .map(arr -> new int[]{((Number) arr[0]).intValue(), ((Number) arr[1]).intValue()})
                .collect(Collectors.toList());
    }
}
