package com.myplatform.backend.leaders;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 주도주 목록 저장 — 한 기준일의 행을 한 트랜잭션에서 통째로 바꾼다(지우고 넣는 사이에 반쪽 목록이 보이지 않게).
 * 계산(KIS 호출 수 분)은 트랜잭션 밖에서 끝내고 이 메서드만 연결을 잡는다.
 */
@Service
@RequiredArgsConstructor
public class LeaderStockStore {

    private final LeaderStockDailyRepository repository;

    @Transactional
    public int replace(LocalDate basisDate, List<LeaderStockDaily> rows) {
        repository.deleteAllOfBasisDate(basisDate);
        return repository.saveAll(rows).size();
    }
}
