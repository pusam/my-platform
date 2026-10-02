package com.myplatform.backend.service;

import com.myplatform.backend.controller.MarketTimingController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시장 폭(ADR) 기간 백필 은퇴 가드(2026-10-02).
 *
 * <p><b>무엇이 있었나</b>: {@code POST /api/market/collect/period} → {@code MarketTimingService.collectMarketDataForPeriod}
 * 가 과거 날짜마다 KRX 데이터 포털({@code getKrxOtp} — 존재하지 않는 경로)에서 등락 종목 수를 받으려 했다.
 * KRX 는 이 용도로 죽어 있고(CLAUDE.md §4 상장목록 항목), KIS 일자별지수(065)에는 등락 수 필드가 없다 — 과거 날짜의
 * 시장 폭은 어떤 소스로도 받을 수 없어 이 경로는 매번 '실패 N일'만 돌려줬다. 화면의 '기간 수집' 버튼은 같은 날 먼저 뺐다.
 *
 * <p>⚠ 이 테스트가 깨지면 <b>그게 의도다.</b> 받을 수 없는 과거를 '수집'하는 경로가 되살아나면 0/0/0 위장 저장(§4c)이나
 * 늘 실패하는 버튼이 다시 생긴다. 시장 폭은 KIS 국내업종 현재지수로 매 거래일 16:30 에 그날 값만 쌓는다({@link MarketBreadth}).
 */
class AdrBackfillRetiredTest {

    @Test
    @DisplayName("백필·KRX 메서드와 KRX 상수가 MarketTimingService 에 다시 생기지 않는다")
    void serviceHasNoBackfillOrKrxPath() {
        assertThat(Arrays.stream(MarketTimingService.class.getDeclaredMethods()).map(Method::getName).toList())
                .doesNotContain("collectMarketDataForPeriod", "collectMarketDataForDate",
                        "collectHistoricalMarketData", "collectFromKrxApi", "getKrxOtp", "requestKrxData");
        assertThat(Arrays.stream(MarketTimingService.class.getDeclaredFields()).map(Field::getName).toList())
                .noneMatch(name -> name.startsWith("KRX_"));
    }

    @Test
    @DisplayName("기간 수집 엔드포인트(/collect/period)와 결과 DTO 도 없다")
    void endpointAndDtoAreGone() {
        assertThat(Arrays.stream(MarketTimingController.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(PostMapping.class))
                .filter(Objects::nonNull)
                .flatMap(a -> Stream.concat(Arrays.stream(a.value()), Arrays.stream(a.path())))
                .toList())
                .doesNotContain("/collect/period");
        assertThatThrownBy(() -> Class.forName("com.myplatform.backend.dto.MarketDataCollectionResult"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("오늘 값 수집 경로는 그대로 있다 — 은퇴한 것은 과거 백필뿐")
    void todayCollectionStays() throws Exception {
        assertThat(MarketTimingService.class.getMethod("collectMarketData")).isNotNull();
        assertThat(MarketTimingController.class.getMethod("collectMarketData")).isNotNull();
    }
}
