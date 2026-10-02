package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.GoldApiResponse;
import com.myplatform.backend.dto.GoldPriceDto;
import com.myplatform.backend.dto.SilverPriceDto;
import com.myplatform.backend.repository.GoldPriceRepository;
import com.myplatform.backend.repository.SilverPriceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 금·은 시세 — 출처가 주지 않은 등락률·시고저가를 지어내지 않는다(2026-10-02 화면 점검, §4c).
 *
 * <p>재현: 운영 저장 행 금 725·은 722 전부(2026-01 ~ 10-02)가 change_rate=0 · 시가=고가=저가=현재가였다. 가격은 움직이는데
 * (10/2 09:00 686,194 → 12:00 682,444) 화면은 늘 "0.00%" 보합이었다 — 이 API 의 KRW 응답엔 chp·open/high/low 가 오지 않고,
 * 수집기가 없는 값을 0·현재가로 채웠다.
 */
class GoldSilverPriceMissingFieldsTest {

    private final ObjectMapper om = new ObjectMapper();

    /** 운영 응답 모양 — 그램당 가격·시각만 있다 */
    private GoldApiResponse priceOnly() throws Exception {
        return om.readValue("{\"timestamp\":1790906400,\"price_gram_24k\":181985.12}", GoldApiResponse.class);
    }

    private GoldPriceService gold() {
        GoldPriceService svc = new GoldPriceService(mock(RestTemplate.class), mock(GoldPriceRepository.class));
        ReflectionTestUtils.setField(svc, "gramPerDon", new BigDecimal("3.75"));
        return svc;
    }

    private SilverPriceService silver() {
        SilverPriceService svc = new SilverPriceService(mock(RestTemplate.class), mock(SilverPriceRepository.class));
        ReflectionTestUtils.setField(svc, "gramPerDon", new BigDecimal("3.75"));
        return svc;
    }

    @Test
    @DisplayName("금 — 등락률·시고저가가 없으면 null(0·현재가로 채우지 않는다), 1돈 가격은 그대로")
    void goldMissingFieldsStayNull() throws Exception {
        GoldPriceDto dto = gold().convertToDto(priceOnly());

        assertThat(dto.getPricePerDon()).isEqualByComparingTo("682444");
        assertThat(dto.getClosePrice()).isEqualByComparingTo("682444");
        assertThat(dto.getChangeRate()).isNull();
        assertThat(dto.getOpenPrice()).isNull();
        assertThat(dto.getHighPrice()).isNull();
        assertThat(dto.getLowPrice()).isNull();
    }

    @Test
    @DisplayName("은 — 같은 규칙")
    void silverMissingFieldsStayNull() throws Exception {
        SilverPriceDto dto = silver().convertToDto(priceOnly());

        assertThat(dto.getChangeRate()).isNull();
        assertThat(dto.getOpenPrice()).isNull();
        assertThat(dto.getHighPrice()).isNull();
        assertThat(dto.getLowPrice()).isNull();
    }

    @Test
    @DisplayName("출처가 등락률을 주면 그대로 쓴다 — 진짜 0 도 0")
    void providedChangeRateIsKept() throws Exception {
        GoldApiResponse withChp = om.readValue(
                "{\"timestamp\":1790906400,\"price_gram_24k\":181985.12,\"chp\":0}", GoldApiResponse.class);

        assertThat(gold().convertToDto(withChp).getChangeRate()).isEqualByComparingTo("0");
    }
}
