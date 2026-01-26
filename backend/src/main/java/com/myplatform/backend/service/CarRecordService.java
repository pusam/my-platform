package com.myplatform.backend.service;

import com.myplatform.backend.dto.CarRecordDto;
import com.myplatform.backend.dto.CarRecordRequest;
import com.myplatform.backend.entity.CarRecord;
import com.myplatform.backend.repository.CarRecordRepository;
import com.myplatform.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CarRecordService {

    private final CarRecordRepository carRecordRepository;
    private final UserRepository userRepository;

    public CarRecordService(CarRecordRepository carRecordRepository, UserRepository userRepository) {
        this.carRecordRepository = carRecordRepository;
        this.userRepository = userRepository;
    }

    private Long getUserId(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."))
                .getId();
    }

    /**
     * 정비 기록 등록
     */
    @Transactional
    public CarRecordDto addRecord(String username, CarRecordRequest request) {
        Long userId = getUserId(username);

        CarRecord record = new CarRecord();
        record.setUserId(userId);
        record.setCarName(request.getCarName());
        record.setPlateNumber(request.getPlateNumber());
        record.setRecordType(request.getRecordType());
        record.setRecordDate(request.getRecordDate());
        record.setMileage(request.getMileage());
        record.setNextMileage(request.getNextMileage());
        record.setCost(request.getCost());
        record.setShop(request.getShop());
        record.setMemo(request.getMemo());

        CarRecord saved = carRecordRepository.save(record);
        return CarRecordDto.fromEntity(saved);
    }

    /**
     * 사용자의 모든 정비 기록 조회
     */
    public List<CarRecordDto> getRecords(String username) {
        Long userId = getUserId(username);
        return carRecordRepository.findByUserIdOrderByRecordDateDesc(userId)
                .stream()
                .map(CarRecordDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 특정 정비 유형 기록 조회
     */
    public List<CarRecordDto> getRecordsByType(String username, String recordType) {
        Long userId = getUserId(username);
        return carRecordRepository.findByUserIdAndRecordTypeOrderByRecordDateDesc(userId, recordType)
                .stream()
                .map(CarRecordDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 정비 기록 삭제
     */
    @Transactional
    public void deleteRecord(String username, Long recordId) {
        Long userId = getUserId(username);
        CarRecord record = carRecordRepository.findByIdAndUserId(recordId, userId)
                .orElseThrow(() -> new RuntimeException("기록을 찾을 수 없습니다."));
        carRecordRepository.delete(record);
    }

    /**
     * 정비 요약 정보 조회
     */
    public Map<String, Object> getSummary(String username) {
        Long userId = getUserId(username);
        List<CarRecord> records = carRecordRepository.findByUserIdOrderByRecordDateDesc(userId);
        Map<String, Object> summary = new HashMap<>();

        if (records.isEmpty()) {
            summary.put("totalRecords", 0);
            summary.put("totalCost", BigDecimal.ZERO);
            summary.put("currentMileage", 0);
            summary.put("latestByType", Collections.emptyMap());
            summary.put("dueMaintenances", Collections.emptyList());
            return summary;
        }

        // 총 기록 수
        summary.put("totalRecords", records.size());

        // 총 비용
        BigDecimal totalCost = records.stream()
                .map(CarRecord::getCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        summary.put("totalCost", totalCost);

        // 현재 주행거리 (가장 최근 기록)
        Integer currentMileage = carRecordRepository.findMaxMileageByUserId(userId).orElse(0);
        summary.put("currentMileage", currentMileage);

        // 정비 유형별 최근 기록
        Map<String, CarRecordDto> latestByType = new HashMap<>();
        String[] types = {"ENGINE_OIL", "TIRE", "BRAKE", "FILTER", "BATTERY", "WIPER", "COOLANT", "TRANSMISSION"};
        for (String type : types) {
            carRecordRepository.findFirstByUserIdAndRecordTypeOrderByMileageDesc(userId, type)
                    .ifPresent(r -> latestByType.put(type, CarRecordDto.fromEntity(r)));
        }
        summary.put("latestByType", latestByType);

        // 정비 필요 항목 (다음 정비 주행거리 도달)
        List<CarRecordDto> dueMaintenances = carRecordRepository.findDueMaintenanceByUserId(userId, currentMileage)
                .stream()
                .map(CarRecordDto::fromEntity)
                .collect(Collectors.toList());
        summary.put("dueMaintenances", dueMaintenances);

        return summary;
    }
}
