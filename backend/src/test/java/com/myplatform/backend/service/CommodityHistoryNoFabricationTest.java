package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.repository.GoldPriceRepository;
import com.myplatform.backend.repository.OilPriceRepository;
import com.myplatform.backend.repository.SilverPriceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 원자재 '최근 한 달' 이력 — 저장된 이력이 없으면 빈 목록(2026-10-03 화면 점검).
 *
 * <p>예전엔 금·은 ±5%, 원유 ±4% 난수로 30일치를 지어내 차트로 그렸다(Math.random) — 실패·이력 없음이 '시세 추이'로 보였다.
 */
class CommodityHistoryNoFabricationTest {

    @Test
    @DisplayName("재현: 금 — 이력 없음이면 난수 30일치가 아니라 빈 목록")
    void goldEmpty() {
        GoldPriceRepository repo = mock(GoldPriceRepository.class);
        when(repo.findByFetchedAtAfterOrderByFetchedAtAsc(any())).thenReturn(List.of());
        assertThat(new GoldPriceService(mock(RestTemplate.class), repo).getMonthlyHistory()).isEmpty();
    }

    @Test
    @DisplayName("재현: 은 — 같은 규칙")
    void silverEmpty() {
        SilverPriceRepository repo = mock(SilverPriceRepository.class);
        when(repo.findByFetchedAtAfterOrderByFetchedAtAsc(any())).thenReturn(List.of());
        assertThat(new SilverPriceService(mock(RestTemplate.class), repo).getMonthlyHistory()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("재현: 원유 — 같은 규칙")
    void oilEmpty() {
        OilPriceRepository repo = mock(OilPriceRepository.class);
        when(repo.findByFetchedAtAfterOrderByFetchedAtAsc(any())).thenReturn(List.of());
        OilPriceService svc = new OilPriceService(mock(RestTemplate.class), new ObjectMapper(), repo,
                mock(ObjectProvider.class));
        assertThat(svc.getMonthlyHistory()).isEmpty();
    }
}
