package com.myplatform.backend.util;

import com.myplatform.backend.util.CorporateActionDetector.Kind;
import com.myplatform.backend.util.CorporateActionDetector.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 액면변경 의심 판정 — 저장 이력이 현재가와 비교 불가능해진 종목을 찾는다.
 *
 * <p>기준 사례는 2026-09-11 실측(조일알미늄 018470): 저장가 973 · 현재가 4,865 · 거래량 0 봉 6개.
 * 가격 이상치 그물은 이걸 "응답 일괄 배수 오염"으로 오진했다 — 실제는 거래정지 중 액면병합 5:1 이다.
 */
class CorporateActionDetectorTest {

    private static Verdict judge(String stored, String current, long zeroBars) {
        return CorporateActionDetector.judge(
                stored == null ? null : new BigDecimal(stored),
                current == null ? null : new BigDecimal(current),
                zeroBars);
    }

    @Nested
    @DisplayName("실측 사례")
    class RealCase {

        @Test
        @DisplayName("018470: 973 → 4,865 (정지 6봉) = 액면병합 5:1 의심")
        void joilAluminium() {
            Verdict v = judge("973.00", "4865", 6);

            assertThat(v.kind()).isEqualTo(Kind.MERGE_SUSPECTED);
            assertThat(v.factor()).isEqualByComparingTo("5");
            assertThat(v.ratio()).isEqualByComparingTo("5.00");
            assertThat(v.suspected()).isTrue();
            assertThat(v.detail()).contains("액면병합").contains("비교 불가");
        }
    }

    @Nested
    @DisplayName("정지 증거가 없으면 판정하지 않는다 — 배수 오염과 구분되지 않는다")
    class RequiresHaltEvidence {

        @Test
        @DisplayName("정지 이력 0봉이면 정확한 10배여도 INSUFFICIENT — 그건 배수 오염 쪽 신호다")
        void cleanMultipleWithoutHaltIsNotJudged() {
            Verdict v = judge("1000", "10000", 0);

            assertThat(v.kind()).as("×10 은 배수 오염의 원형 — 액면변경으로 덮으면 진짜 오염을 놓친다")
                    .isEqualTo(Kind.INSUFFICIENT);
            assertThat(v.suspected()).isFalse();
            assertThat(v.detail()).contains("거래정지 증거 부족");
        }

        @Test
        @DisplayName("정지 봉이 임계(3) 미만이면 판정 보류")
        void tooFewZeroBars() {
            assertThat(judge("1000", "5000", 2).kind()).isEqualTo(Kind.INSUFFICIENT);
            assertThat(judge("1000", "5000", 3).kind()).isEqualTo(Kind.MERGE_SUSPECTED);
        }
    }

    @Nested
    @DisplayName("병합·분할 양방향")
    class BothDirections {

        @Test
        @DisplayName("액면분할 1:5 — 5,000 → 1,000")
        void split() {
            Verdict v = judge("5000", "1000", 5);

            assertThat(v.kind()).isEqualTo(Kind.SPLIT_SUSPECTED);
            assertThat(v.factor()).isEqualByComparingTo("5");
            assertThat(v.detail()).contains("액면분할");
        }

        @Test
        @DisplayName("2:1 병합도 잡는다 — 가격 이상치 그물(5배)은 못 잡는 구간")
        void mergeTwoToOne() {
            Verdict v = judge("1000", "2000", 4);

            assertThat(v.kind()).isEqualTo(Kind.MERGE_SUSPECTED);
            assertThat(v.factor()).isEqualByComparingTo("2");
        }
    }

    @Nested
    @DisplayName("정수배가 아니면 액면변경이 아니다")
    class NotClean {

        @Test
        @DisplayName("3.7배 급등은 NOT_CLEAN")
        void nonIntegerJump() {
            assertThat(judge("1000", "3700", 5).kind()).isEqualTo(Kind.NOT_CLEAN);
        }

        @Test
        @DisplayName("허용오차 안(±5%)은 잡고, 밖은 놓는다")
        void tolerance() {
            assertThat(judge("973", "4900", 6).kind())
                    .as("5.036배 = 오차 0.7%").isEqualTo(Kind.MERGE_SUSPECTED);
            assertThat(judge("973", "5500", 6).kind())
                    .as("5.65배 = 오차 13%").isEqualTo(Kind.NOT_CLEAN);
        }

        @Test
        @DisplayName("정상 등락(1.1배)은 정지 뒤여도 액면변경이 아니다")
        void normalMove() {
            Verdict v = judge("1000", "1100", 10);

            assertThat(v.kind()).isEqualTo(Kind.NOT_CLEAN);
            assertThat(v.detail()).contains("정상 등락 범위");
        }
    }

    @Nested
    @DisplayName("입력 결측")
    class Missing {

        @Test
        @DisplayName("가격 null/0 이면 판정 skip")
        void nullOrZero() {
            assertThat(judge(null, "5000", 5).kind()).isEqualTo(Kind.INSUFFICIENT);
            assertThat(judge("1000", null, 5).kind()).isEqualTo(Kind.INSUFFICIENT);
            assertThat(judge("0", "5000", 5).kind()).isEqualTo(Kind.INSUFFICIENT);
        }
    }
}
