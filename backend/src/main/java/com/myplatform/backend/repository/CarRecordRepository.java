package com.myplatform.backend.repository;

import com.myplatform.backend.entity.CarRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CarRecordRepository extends JpaRepository<CarRecord, Long> {

    // 사용자의 모든 정비 기록 (최신순)
    List<CarRecord> findByUserIdOrderByRecordDateDesc(Long userId);

    // 사용자의 특정 정비 유형 기록
    List<CarRecord> findByUserIdAndRecordTypeOrderByRecordDateDesc(Long userId, String recordType);

    // 사용자의 특정 기록 조회
    Optional<CarRecord> findByIdAndUserId(Long id, Long userId);

    // 사용자의 가장 최근 정비 기록 (정비 유형별, 주행거리 기준)
    Optional<CarRecord> findFirstByUserIdAndRecordTypeOrderByMileageDesc(Long userId, String recordType);

    // 사용자의 현재 주행거리 (가장 최근 기록 기준)
    @Query("SELECT MAX(c.mileage) FROM CarRecord c WHERE c.userId = :userId")
    Optional<Integer> findMaxMileageByUserId(@Param("userId") Long userId);

    // 다음 정비 필요한 항목들 조회 (현재 주행거리 기준)
    @Query("SELECT c FROM CarRecord c WHERE c.userId = :userId AND c.nextMileage IS NOT NULL AND c.nextMileage <= :currentMileage ORDER BY c.nextMileage ASC")
    List<CarRecord> findDueMaintenanceByUserId(@Param("userId") Long userId, @Param("currentMileage") Integer currentMileage);
}
