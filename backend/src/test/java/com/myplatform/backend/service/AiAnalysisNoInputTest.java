package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockDetailDto;
import com.myplatform.backend.dto.StockDetailDto.ChartData;
import com.myplatform.backend.dto.StockDetailDto.FinancialInfo;
import com.myplatform.backend.dto.StockDetailDto.PriceInfo;
import com.myplatform.backend.dto.StockDetailDto.SupplyDemand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 입력이 없으면 <b>투자 의견을 만들지 않는다</b> — 순수 함수(2026-09-21 데이터 점검).
 *
 * <p><b>무엇이 틀렸나</b>: {@code generateAiAnalysis} 는 기본 점수 50 에서 시작해 가감하는 구조라,
 * 수급·재무·차트가 <b>전부 없어도</b> 50점이 남아 `HOLD` 로 분류됐다. 존재하지도 않는 종목코드
 * {@code /stock/999999} 가 이렇게 답했다:
 *
 * <pre>
 *   overallScore    50
 *   recommendation  HOLD
 *   strategy        "관망 또는 소규모 진입 구간입니다. 추가 확인 후 결정하세요."
 *   priceGuide      "보유 지속, 0원 하회 시 비중 축소, 0원 돌파 시 추가 매수 검토"
 * </pre>
 *
 * <p>이건 결측을 그럴듯한 값으로 위장한 정도가 아니라 <b>없는 종목에 매매 조언을 생성</b>한 것이다.
 * 0원을 지지·저항으로 제시하기까지 했다. §4c 중에서도 가장 나쁜 쪽이다.
 *
 * <p><b>고친 방식</b>: 판정에 쓸 입력이 하나도 없으면 분석 자체를 만들지 않는다(null).
 * "중립 50점"은 중립이 아니라 <b>모른다</b>이고, 모르는 것은 화면에서 비어 있어야 한다.
 */
class AiAnalysisNoInputTest {

    private static StockDetailDto dto(PriceInfo price, SupplyDemand supply,
                                      FinancialInfo financial, ChartData chart) {
        return StockDetailDto.builder()
                .stockCode("999999").price(price).supplyDemand(supply)
                .financial(financial).chartData(chart).build();
    }

    @Test
    @DisplayName("입력이 전부 없으면 판정 불가 — 없는 종목에 HOLD 를 주지 않는다")
    void noInputMeansNoVerdict() {
        assertThat(StockDetailService.hasAnalyzableInput(dto(null, null, null, null))).isFalse();
    }

    @Test
    @DisplayName("가격이 0 이면 입력으로 치지 않는다 — 없는 종목의 시세 응답이 0 으로 온다")
    void zeroPriceIsNotInput() {
        PriceInfo zero = PriceInfo.builder().currentPrice(BigDecimal.ZERO).build();
        assertThat(StockDetailService.hasAnalyzableInput(dto(zero, null, null, null))).isFalse();
    }

    @Test
    @DisplayName("하나라도 실제 값이 있으면 분석한다 — 기존 동작을 좁히지 않는다")
    void anyRealInputIsEnough() {
        PriceInfo p = PriceInfo.builder().currentPrice(new BigDecimal("72000")).build();
        assertThat(StockDetailService.hasAnalyzableInput(dto(p, null, null, null))).isTrue();

        SupplyDemand s = SupplyDemand.builder().volumePower(new BigDecimal("131")).build();
        assertThat(StockDetailService.hasAnalyzableInput(dto(null, s, null, null))).isTrue();

        FinancialInfo f = FinancialInfo.builder().per(new BigDecimal("12.3")).build();
        assertThat(StockDetailService.hasAnalyzableInput(dto(null, null, f, null))).isTrue();

        ChartData c = ChartData.builder().ma20(new BigDecimal("70000")).build();
        assertThat(StockDetailService.hasAnalyzableInput(dto(null, null, null, c))).isTrue();
    }

    @Test
    @DisplayName("빈 껍데기 객체는 값이 아니다 — 필드가 전부 null 이면 없는 것과 같다")
    void emptyShellsAreNotInput() {
        assertThat(StockDetailService.hasAnalyzableInput(
                dto(PriceInfo.builder().build(), SupplyDemand.builder().build(),
                    FinancialInfo.builder().build(), ChartData.builder().build()))).isFalse();
    }

    @Test
    @DisplayName("dto 가 null 이어도 터지지 않는다")
    void nullDtoIsSafe() {
        assertThat(StockDetailService.hasAnalyzableInput(null)).isFalse();
    }

    /**
     * ⚠ <b>첫 구현이 이 케이스를 놓쳤다</b>(2026-09-21, 배포 후 실측으로 잡음).
     *
     * <p>가드를 {@code != null} 로만 썼는데, 없는 종목의 응답은 <b>null 이 아니라 placeholder</b> 였다:
     * <pre>
     *   financial     per 0 · pbr 0 · eps 0        ← 파싱 실패가 0 으로 적힌다
     *   supplyDemand  volumeSignal "NEUTRAL" · programTrend "FLAT"   ← 계산된 기본 라벨
     * </pre>
     * 그래서 가드를 통과해 여전히 50점/HOLD 가 나왔다. §4c 가 <b>"`!= null` 만으로는 부족하다"</b>고
     * 명시해 둔 바로 그 지점이다(비율 컬럼의 0 은 결측일 수 있다).
     *
     * <p>지금은 비율·EPS 는 {@code signum() > 0} 을 요구하고, 수급은 <b>숫자 필드</b>만 본다 —
     * "NEUTRAL"·"FLAT" 같은 기본 라벨은 값이 아니다.
     */
    @Test
    @DisplayName("0 은 값이 아니다 — 파싱 실패가 0 으로 적히는 컬럼들(§4c)")
    void zeroRatiosAreNotInput() {
        FinancialInfo zeros = FinancialInfo.builder()
                .per(BigDecimal.ZERO).pbr(BigDecimal.ZERO).eps(BigDecimal.ZERO).build();
        assertThat(StockDetailService.hasAnalyzableInput(dto(null, null, zeros, null))).isFalse();
    }

    @Test
    @DisplayName("계산된 기본 라벨은 값이 아니다 — volumeSignal NEUTRAL · programTrend FLAT")
    void defaultLabelsAreNotInput() {
        SupplyDemand labelsOnly = SupplyDemand.builder()
                .volumeSignal("NEUTRAL").programTrend("FLAT").build();
        assertThat(StockDetailService.hasAnalyzableInput(dto(null, labelsOnly, null, null))).isFalse();
    }

    @Test
    @DisplayName("없는 종목의 실제 응답 형태 — 전부 합쳐도 판정 불가")
    void realNonexistentStockShape() {
        // prod 실측(/api/stock/999999/summary): price 0 · financial 0 · 라벨만 있는 수급
        assertThat(StockDetailService.hasAnalyzableInput(dto(
                PriceInfo.builder().currentPrice(BigDecimal.ZERO).build(),
                SupplyDemand.builder().volumeSignal("NEUTRAL").programTrend("FLAT").build(),
                FinancialInfo.builder().per(BigDecimal.ZERO).pbr(BigDecimal.ZERO).eps(BigDecimal.ZERO).build(),
                null))).isFalse();
    }

    @Test
    @DisplayName("수급은 숫자가 하나라도 있으면 값이다")
    void numericSupplyIsInput() {
        assertThat(StockDetailService.hasAnalyzableInput(dto(null,
                SupplyDemand.builder().volumeSignal("NEUTRAL").foreignNetBuy(new BigDecimal("120")).build(),
                null, null))).isTrue();
    }
}
