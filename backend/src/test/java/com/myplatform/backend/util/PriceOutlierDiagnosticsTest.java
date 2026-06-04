package com.myplatform.backend.util;

import com.myplatform.backend.util.PriceOutlierDiagnostics.Kind;
import com.myplatform.backend.util.PriceOutlierDiagnostics.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-2: ×10 근본원인 진단 분류기 테스트.
 *
 * <p>두 가설을 응답 필드 관계로 구분하는지 고정한다:
 * <ul>
 *   <li><b>응답 자체가 ×10</b> → {@code BATCH_SCALED} (현재가·고·저가 통배수, 등락률 정상, DB 앵커 배수 ~10)</li>
 *   <li><b>필드 매핑 오류</b> → {@code CURRENT_FIELD_OUTLIER} (현재가만 당일 밴드 밖)</li>
 * </ul>
 */
class PriceOutlierDiagnosticsTest {

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    @Nested
    @DisplayName("가설 B: 응답 자체가 ×10 (전체 통배수)")
    class BatchScaled {
        @Test
        @DisplayName("현재가·고·저가 모두 ×10 + 등락률 정상 + DB앵커 70000 → BATCH_SCALED(x10)")
        void allFieldsScaled_isBatchScaled() {
            Result r = PriceOutlierDiagnostics.classify(
                    bd("700000"), bd("710000"), bd("690000"), bd("0.5"), bd("70000"));
            assertThat(r.kind()).isEqualTo(Kind.BATCH_SCALED);
            assertThat(r.multiple()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("×5 통배수(350000 vs 70000) → BATCH_SCALED(x5)")
        void fiveTimes_isBatchScaled() {
            Result r = PriceOutlierDiagnostics.classify(
                    bd("350000"), bd("352000"), bd("348000"), bd("0.3"), bd("70000"));
            assertThat(r.kind()).isEqualTo(Kind.BATCH_SCALED);
            assertThat(r.multiple()).isEqualByComparingTo("5");
        }
    }

    @Nested
    @DisplayName("가설 A: 필드 매핑 오류 (현재가 단독)")
    class CurrentFieldOutlier {
        @Test
        @DisplayName("고·저가는 정상인데 현재가만 ×10 (밴드 밖) → CURRENT_FIELD_OUTLIER")
        void onlyCurrentScaled_isFieldOutlier() {
            // 고가 71000 / 저가 70000 은 정상, 현재가만 700000 → 밴드 밖
            Result r = PriceOutlierDiagnostics.classify(
                    bd("700000"), bd("71000"), bd("70000"), bd("0.5"), bd("70000"));
            assertThat(r.kind()).isEqualTo(Kind.CURRENT_FIELD_OUTLIER);
            assertThat(r.multiple()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("밴드 밖 판정이 DB앵커 배수보다 우선 (현재가 단독 신호가 더 강함)")
        void bandOutOfRange_takesPrecedenceOverAnchor() {
            // 앵커 대비도 ×10 이지만 밴드 밖이므로 단독 오염으로 분류
            Result r = PriceOutlierDiagnostics.classify(
                    bd("700000"), bd("71000"), bd("70000"), bd("0.5"), bd("70000"));
            assertThat(r.kind()).isEqualTo(Kind.CURRENT_FIELD_OUTLIER);
        }
    }

    @Nested
    @DisplayName("정상 / 모호")
    class NormalAndAmbiguous {
        @Test
        @DisplayName("정상 소폭 등락 → NORMAL")
        void normal() {
            Result r = PriceOutlierDiagnostics.classify(
                    bd("70500"), bd("71000"), bd("70000"), bd("0.71"), bd("70000"));
            assertThat(r.kind()).isEqualTo(Kind.NORMAL);
        }

        @Test
        @DisplayName("DB 앵커 없음 + 밴드 안 + 등락률 정상 → 응답만으론 확정 불가, NORMAL")
        void noAnchor_selfConsistent_isNormal() {
            Result r = PriceOutlierDiagnostics.classify(
                    bd("700000"), bd("710000"), bd("690000"), bd("0.5"), null);
            assertThat(r.kind()).isEqualTo(Kind.NORMAL);
        }

        @Test
        @DisplayName("밴드 정상인데 등락률만 제한 초과 → AMBIGUOUS")
        void bandOk_butCtrtAbnormal_isAmbiguous() {
            Result r = PriceOutlierDiagnostics.classify(
                    bd("70500"), bd("71000"), bd("70000"), bd("50"), bd("70000"));
            assertThat(r.kind()).isEqualTo(Kind.AMBIGUOUS);
        }

        @Test
        @DisplayName("통배수 + 등락률 비정상 → AMBIGUOUS (통배수/매핑 혼재)")
        void scaledButCtrtAbnormal_isAmbiguous() {
            Result r = PriceOutlierDiagnostics.classify(
                    bd("700000"), bd("710000"), bd("690000"), bd("99"), bd("70000"));
            assertThat(r.kind()).isEqualTo(Kind.AMBIGUOUS);
        }
    }

    @Nested
    @DisplayName("경계값: DB 앵커 배수 임계 5")
    class AnchorBoundary {
        @Test
        @DisplayName("×4.9 (490000 vs 100000), 자기일관 → NORMAL (임계 미만)")
        void ratio4_9_isNormal() {
            Result r = PriceOutlierDiagnostics.classify(
                    bd("490000"), bd("495000"), bd("485000"), bd("0.4"), bd("100000"));
            assertThat(r.kind()).isEqualTo(Kind.NORMAL);
        }

        @Test
        @DisplayName("×5.1 (510000 vs 100000), 자기일관 → BATCH_SCALED")
        void ratio5_1_isBatchScaled() {
            Result r = PriceOutlierDiagnostics.classify(
                    bd("510000"), bd("515000"), bd("505000"), bd("0.4"), bd("100000"));
            assertThat(r.kind()).isEqualTo(Kind.BATCH_SCALED);
            assertThat(r.multiple()).isEqualByComparingTo("5");
        }
    }

    @Nested
    @DisplayName("방어: null/0 입력")
    class Defensive {
        @Test
        @DisplayName("현재가 null → NORMAL(skip), 예외 없음")
        void nullCurrent_isNormalSkip() {
            Result r = PriceOutlierDiagnostics.classify(
                    null, bd("71000"), bd("70000"), bd("0.5"), bd("70000"));
            assertThat(r.kind()).isEqualTo(Kind.NORMAL);
        }

        @Test
        @DisplayName("밴드 없음(고저가 0) + 앵커 통배수 → BATCH_SCALED")
        void noBand_anchorScaled_isBatchScaled() {
            Result r = PriceOutlierDiagnostics.classify(
                    bd("700000"), bd("0"), bd("0"), bd("0.5"), bd("70000"));
            assertThat(r.kind()).isEqualTo(Kind.BATCH_SCALED);
        }
    }
}
