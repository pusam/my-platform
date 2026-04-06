package com.myplatform.backend.service;

import com.myplatform.backend.dto.DietDto;
import com.myplatform.backend.dto.DietRequest;
import com.myplatform.backend.entity.DietRecord;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.DietRepository;
import com.myplatform.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DietService 단위 테스트
 *
 * 검증 포인트:
 * 1. CRUD 정상 동작
 * 2. userId 기반 소유권 검증 (다른 사용자 기록 접근 차단)
 * 3. 요약 데이터 (오늘 칼로리/끼니 수) 정확성
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class DietServiceTest {

    @Mock private DietRepository dietRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private DietService dietService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
    }

    private DietRecord buildRecord(Long id, Long userId, String type, String food, int calories) {
        DietRecord r = new DietRecord();
        r.setId(id);
        r.setUserId(userId);
        r.setDietType(type);
        r.setFoodName(food);
        r.setCalories(calories);
        r.setMealDate(LocalDate.now());
        return r;
    }

    @Nested
    @DisplayName("식단 등록")
    class AddTests {

        @Test
        @DisplayName("정상 등록 → DTO 반환 + userId 자동 설정")
        void add_setsUserIdAndReturns() {
            DietRequest req = new DietRequest();
            req.setDietType("LUNCH");
            req.setFoodName("닭가슴살 샐러드");
            req.setCalories(350);
            req.setProtein(new BigDecimal("30.5"));

            when(dietRepository.save(any(DietRecord.class))).thenAnswer(inv -> {
                DietRecord saved = inv.getArgument(0);
                saved.setId(1L);
                return saved;
            });

            DietDto result = dietService.add("testuser", req);

            assertThat(result).isNotNull();
            assertThat(result.getFoodName()).isEqualTo("닭가슴살 샐러드");
            verify(dietRepository).save(argThat(r -> r.getUserId().equals(1L)));
        }
    }

    @Nested
    @DisplayName("식단 삭제")
    class DeleteTests {

        @Test
        @DisplayName("본인 기록 삭제 → 정상")
        void deleteOwnRecord_success() {
            DietRecord record = buildRecord(1L, 1L, "LUNCH", "밥", 500);
            when(dietRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(record));

            dietService.delete("testuser", 1L);

            verify(dietRepository).delete(record);
        }

        @Test
        @DisplayName("타인 기록 삭제 시도 → 예외 발생")
        void deleteOtherUserRecord_throws() {
            when(dietRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> dietService.delete("testuser", 99L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("찾을 수 없습니다");
        }
    }

    @Nested
    @DisplayName("식단 조회")
    class ReadTests {

        @Test
        @DisplayName("전체 조회 → 본인 기록만 반환")
        void getRecords_returnsOnlyOwnRecords() {
            List<DietRecord> records = List.of(
                    buildRecord(1L, 1L, "BREAKFAST", "토스트", 250),
                    buildRecord(2L, 1L, "LUNCH", "비빔밥", 600)
            );
            when(dietRepository.findByUserIdOrderByMealDateDesc(1L)).thenReturn(records);

            List<DietDto> result = dietService.getRecords("testuser");

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getDietTypeName()).isEqualTo("아침");
        }

        @Test
        @DisplayName("유형별 필터 → 해당 유형만 반환")
        void getByType_filtersCorrectly() {
            List<DietRecord> records = List.of(
                    buildRecord(1L, 1L, "DINNER", "스테이크", 800)
            );
            when(dietRepository.findByUserIdAndDietTypeOrderByMealDateDesc(1L, "DINNER"))
                    .thenReturn(records);

            List<DietDto> result = dietService.getByType("testuser", "DINNER");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDietType()).isEqualTo("DINNER");
        }
    }

    @Nested
    @DisplayName("요약 데이터")
    class SummaryTests {

        @Test
        @DisplayName("오늘 칼로리 합산 정확성")
        void summary_calculatesTodayCalories() {
            List<DietRecord> records = List.of(
                    buildRecord(1L, 1L, "BREAKFAST", "토스트", 250),
                    buildRecord(2L, 1L, "LUNCH", "비빔밥", 600),
                    buildRecord(3L, 1L, "DINNER", "샐러드", 300)
            );
            when(dietRepository.findByUserIdOrderByMealDateDesc(1L)).thenReturn(records);

            var summary = dietService.getSummary("testuser");

            assertThat(summary.get("todayCalories")).isEqualTo(1150);
            assertThat(summary.get("todayMeals")).isEqualTo(3L);
            assertThat(summary.get("totalRecords")).isEqualTo(3);
        }
    }
}
