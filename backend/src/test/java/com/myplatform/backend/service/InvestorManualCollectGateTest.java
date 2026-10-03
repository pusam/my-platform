package com.myplatform.backend.service;

import com.myplatform.backend.controller.InvestorTradeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 투자자 매매 수동 수집 — 정규 수집과 같은 문을 지난다(2026-10-03 화면 점검).
 *
 * <p>정규 수집은 휴장일·장 마감(15:50) 전을 거르는데 수동 경로(/investor/collect, /collect/recent)는 주말만 봤다.
 * 매매 동향 화면은 조회 결과가 비면 이 수집을 자동으로 불러 화면을 연 것만으로 장중 잠정치가 그날 기록이 될 수 있었다.
 */
class InvestorManualCollectGateTest {

    @Test
    @DisplayName("재현: 평일 장중(11:00) 수동 수집 — 잠정치라 저장하지 않고 이유를 말한다")
    void refusesBeforeConfirmedWindow() {
        assertThat(InvestorTradeService.manualCollectRefusal(LocalDateTime.of(2026, 10, 6, 11, 0), false))
                .contains("장 마감 집계 전").contains("15:50");
    }

    @Test
    @DisplayName("휴장일(10/5 대체공휴일)엔 시각과 무관하게 거절 — KIS 는 직전 거래일 값을 오늘 날짜로 준다")
    void refusesOnHoliday() {
        assertThat(InvestorTradeService.manualCollectRefusal(LocalDateTime.of(2026, 10, 5, 17, 0), true))
                .contains("휴장일");
    }

    @Test
    @DisplayName("거래일 15:50 이후면 허용(null)")
    void allowsAfterConfirmedWindow() {
        assertThat(InvestorTradeService.manualCollectRefusal(LocalDateTime.of(2026, 10, 6, 16, 5), false)).isNull();
    }

    @Test
    @DisplayName("전량 삭제 후 오늘만 재수집하던 /investor/recollect 는 은퇴 — KIS 로 과거를 못 받으니 누르면 이력이 사라진다")
    void recollectIsRetired() {
        assertThat(Arrays.stream(InvestorTradeController.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(PostMapping.class))
                .filter(a -> a != null)
                .flatMap(a -> Arrays.stream(a.value()))
                .toList()).doesNotContain("/recollect");
        assertThat(Arrays.stream(InvestorTradeService.class.getDeclaredMethods()).map(Method::getName).toList())
                .doesNotContain("deleteAllAndRecollect", "deleteAllData");
    }
}
