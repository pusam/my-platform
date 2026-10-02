package com.myplatform.backend.controller;

import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.service.GeminiService;
import com.myplatform.backend.service.InvestorSurgeService;
import com.myplatform.backend.service.MarketTimingService;
import com.myplatform.backend.service.NewsService;
import com.myplatform.core.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 수동 '📥 시장 데이터 수집' — 저장하지 않은 이유가 화면까지 닿는다(2026-10-02).
 *
 * <p>재현: 시장 폭 수집이 장 마감 확정치만 저장하게 바뀐 뒤(15:40 전엔 {@code IllegalStateException} 으로 이유를 알림),
 * 컨트롤러가 그걸 500 + ERROR 스택으로 바꿔 화면은 "시장 데이터 수집에 실패했습니다"만 보였다.
 */
class MarketTimingControllerCollectTest {

    private final MarketTimingService service = mock(MarketTimingService.class);
    private final MarketTimingController controller = new MarketTimingController(
            service, mock(GeminiService.class), mock(InvestorSurgeService.class), mock(NewsService.class));

    @Test
    @DisplayName("장 마감 전·등락 수 조회 실패는 200 + success:false + 그 이유 — 화면 토스트가 문장을 그대로 보인다")
    void expectedRefusalCarriesReason() {
        String reason = "장 마감(15:40) 전에는 등락 종목 수를 저장하지 않습니다 — 장중 잠정치입니다. 16:30 에 자동 수집됩니다.";
        doThrow(new IllegalStateException(reason)).when(service).collectMarketData();

        ResponseEntity<ApiResponse<MarketTimingDto>> res = controller.collectMarketData();

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().isSuccess()).isFalse();
        assertThat(res.getBody().getMessage()).isEqualTo(reason);
    }

    @Test
    @DisplayName("예상 못 한 오류는 종전대로 500 — 거절과 장애를 섞지 않는다")
    void unexpectedErrorStays500() {
        doThrow(new RuntimeException("DB down")).when(service).collectMarketData();

        ResponseEntity<ApiResponse<MarketTimingDto>> res = controller.collectMarketData();

        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().isSuccess()).isFalse();
    }
}
