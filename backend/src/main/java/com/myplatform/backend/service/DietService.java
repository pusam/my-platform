package com.myplatform.backend.service;

import com.myplatform.backend.dto.DietDto;
import com.myplatform.backend.dto.DietRequest;
import com.myplatform.backend.entity.DietRecord;
import com.myplatform.backend.repository.DietRepository;
import com.myplatform.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class DietService {

    private final DietRepository dietRepository;
    private final UserRepository userRepository;

    public DietService(DietRepository dietRepository, UserRepository userRepository) {
        this.dietRepository = dietRepository;
        this.userRepository = userRepository;
    }

    private Long getUserId(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."))
                .getId();
    }

    public List<DietDto> getRecords(String username) {
        Long userId = getUserId(username);
        return dietRepository.findByUserIdOrderByMealDateDesc(userId)
                .stream().map(DietDto::fromEntity).collect(Collectors.toList());
    }

    public List<DietDto> getByType(String username, String type) {
        Long userId = getUserId(username);
        return dietRepository.findByUserIdAndDietTypeOrderByMealDateDesc(userId, type)
                .stream().map(DietDto::fromEntity).collect(Collectors.toList());
    }

    public List<DietDto> getByDateRange(String username, LocalDate from, LocalDate to) {
        Long userId = getUserId(username);
        return dietRepository.findByUserIdAndMealDateBetweenOrderByMealDateDesc(userId, from, to)
                .stream().map(DietDto::fromEntity).collect(Collectors.toList());
    }

    @Transactional
    public DietDto add(String username, DietRequest request) {
        Long userId = getUserId(username);
        DietRecord record = new DietRecord();
        record.setUserId(userId);
        record.setDietType(request.getDietType());
        record.setFoodName(request.getFoodName());
        record.setCalories(request.getCalories());
        record.setProtein(request.getProtein());
        record.setCarbs(request.getCarbs());
        record.setFat(request.getFat());
        record.setPortion(request.getPortion());
        record.setMealDate(request.getMealDate() != null ? request.getMealDate() : LocalDate.now());
        record.setMemo(request.getMemo());
        return DietDto.fromEntity(dietRepository.save(record));
    }

    @Transactional
    public DietDto update(String username, Long id, DietRequest request) {
        Long userId = getUserId(username);
        DietRecord record = dietRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("식단 기록을 찾을 수 없습니다."));
        record.setDietType(request.getDietType());
        record.setFoodName(request.getFoodName());
        record.setCalories(request.getCalories());
        record.setProtein(request.getProtein());
        record.setCarbs(request.getCarbs());
        record.setFat(request.getFat());
        record.setPortion(request.getPortion());
        if (request.getMealDate() != null) record.setMealDate(request.getMealDate());
        record.setMemo(request.getMemo());
        return DietDto.fromEntity(dietRepository.save(record));
    }

    @Transactional
    public void delete(String username, Long id) {
        Long userId = getUserId(username);
        DietRecord record = dietRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("식단 기록을 찾을 수 없습니다."));
        dietRepository.delete(record);
    }

    public Map<String, Object> getSummary(String username) {
        Long userId = getUserId(username);
        List<DietRecord> all = dietRepository.findByUserIdOrderByMealDateDesc(userId);
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalRecords", all.size());
        summary.put("todayCalories", all.stream()
                .filter(r -> LocalDate.now().equals(r.getMealDate()))
                .mapToInt(r -> r.getCalories() != null ? r.getCalories() : 0).sum());
        summary.put("todayMeals", all.stream()
                .filter(r -> LocalDate.now().equals(r.getMealDate())).count());
        return summary;
    }
}
