package com.myplatform.backend.service;

import com.myplatform.backend.service.RealTradeService.FillStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 — 분할익절도 <b>체결</b>을 확인한다(2026-09-17 감사).
 *
 * <p><b>상태 전이 정리</b> (주문 접수 / 실제 체결 / 목표 완료를 구분)
 * <pre>
 *   없음 ──익절조건&분할가능──▶ 접수(orderNo, target=N, filled=0)
 *   접수 ──FULL/UNKNOWN──────▶ 완료(orderNo=null, filled=N)      ← 1차 익절 끝
 *   접수 ──PARTIAL/NONE─────▶ 접수 유지(filled=k&lt;N) + 이력 정정
 *   접수(미완) ──다음 틱────▶ CONFIRM_EXISTING (새 주문 금지)
 * </pre>
 *
 * <p>⚠ {@code halfSold=false} 로 되돌려 재주문하는 수정은 금지 — 기존 주문의 잔량이 뒤늦게 체결되면
 * 중복 청산이 된다. 그래서 미체결 주문이 살아 있으면 <b>확인만</b> 한다.
 */
class PartialSellFillTest {

    private static PartialSellTracker.State placed(int target, int filled) {
        return new PartialSellTracker.State("ODNO-1", target, filled, 77L);
    }

    // ==================== 상태 구분 ====================

    @Nested
    @DisplayName("주문 접수 · 실제 체결 · 목표 완료는 다른 것이다")
    class Distinctions {

        @Test
        @DisplayName("접수됐지만 0주 체결이면 '완료'가 아니다 — 예전엔 접수만으로 halfSold=true 였다")
        void acceptedWithZeroFillIsNotComplete() {
            PartialSellTracker.State s = placed(5, 0);

            assertThat(s.isComplete()).isFalse();
            assertThat(s.isOutstanding()).isTrue();
        }

        @Test
        @DisplayName("2/5 부분체결도 완료가 아니다")
        void partialIsNotComplete() {
            assertThat(placed(5, 2).isComplete()).isFalse();
            assertThat(placed(5, 2).isOutstanding()).isTrue();
        }

        @Test
        @DisplayName("목표만큼 체결돼야 완료 — 그때 주문 추적이 닫힌다")
        void targetReachedIsComplete() {
            PartialSellTracker.Outcome o = PartialSellTracker.apply(placed(5, 0), FillStatus.FULL, 5);

            assertThat(o.state().isComplete()).isTrue();
            assertThat(o.state().isOutstanding()).isFalse();
            assertThat(o.state().orderNo()).isNull();
            assertThat(o.shouldReconcileHistory()).isFalse();   // 요청수량 그대로라 정정 불필요
        }
    }

    // ==================== 중복 주문 금지 ====================

    @Nested
    @DisplayName("미체결 주문이 살아 있으면 새 주문을 내지 않는다")
    class NoDuplicateOrder {

        @Test
        @DisplayName("다음 틱에 익절 조건을 다시 만족해도 CONFIRM_EXISTING — PLACE_NEW 가 아니다")
        void nextTickConfirmsInsteadOfPlacing() {
            PartialSellTracker.Action a = PartialSellTracker.decide(placed(5, 2), true, true);

            assertThat(a).isEqualTo(PartialSellTracker.Action.CONFIRM_EXISTING);
        }

        @Test
        @DisplayName("0주 체결로 남아 있어도 새 주문을 내지 않는다 — 잔량이 뒤늦게 체결되면 중복 청산")
        void zeroFilledStillBlocksNewOrder() {
            assertThat(PartialSellTracker.decide(placed(5, 0), true, true))
                    .isEqualTo(PartialSellTracker.Action.CONFIRM_EXISTING);
        }

        @Test
        @DisplayName("익절 조건이 사라져도 살아 있는 주문 확인이 먼저다")
        void outstandingWinsOverEligibility() {
            assertThat(PartialSellTracker.decide(placed(5, 1), false, true))
                    .isEqualTo(PartialSellTracker.Action.CONFIRM_EXISTING);
        }

        @Test
        @DisplayName("완료된 뒤에는 건너뛴다 — 1차 익절을 두 번 하지 않는다")
        void completedSkips() {
            assertThat(PartialSellTracker.decide(new PartialSellTracker.State(null, 5, 5, 77L), true, true))
                    .isEqualTo(PartialSellTracker.Action.SKIP_COMPLETE);
        }

        @Test
        @DisplayName("주문이 없고 조건을 만족하면 새로 낸다")
        void freshEligiblePlaces() {
            assertThat(PartialSellTracker.decide(PartialSellTracker.State.none(), true, true))
                    .isEqualTo(PartialSellTracker.Action.PLACE_NEW);
        }

        @Test
        @DisplayName("1주 포지션(분할 불가)은 이 분기를 건너뛴다 — 트레일링/타임컷의 몫")
        void unsplittableSkips() {
            assertThat(PartialSellTracker.decide(PartialSellTracker.State.none(), true, false))
                    .isEqualTo(PartialSellTracker.Action.SKIP_NOT_ELIGIBLE);
        }
    }

    // ==================== 체결 관측 반영 ====================

    @Nested
    @DisplayName("NONE / PARTIAL / FULL / UNKNOWN")
    class FillStates {

        @Test
        @DisplayName("NONE — 0주 확정. 기록을 정정하고 주문은 살아 있다. 확정 0주가 수익으로 남으면 안 된다")
        void noneKeepsOrderAndReconciles() {
            PartialSellTracker.Outcome o = PartialSellTracker.apply(placed(5, 0), FillStatus.NONE, 0);

            assertThat(o.state().filledQty()).isZero();
            assertThat(o.state().isOutstanding()).isTrue();
            assertThat(o.shouldReconcileHistory()).isTrue();
            assertThat(o.alert()).contains("0/5");
        }

        @Test
        @DisplayName("PARTIAL — 체결분만 기록에 남기고 주문 유지")
        void partialReconcilesAndKeepsOrder() {
            PartialSellTracker.Outcome o = PartialSellTracker.apply(placed(5, 0), FillStatus.PARTIAL, 2);

            assertThat(o.state().filledQty()).isEqualTo(2);
            assertThat(o.state().isOutstanding()).isTrue();
            assertThat(o.shouldReconcileHistory()).isTrue();
        }

        @Test
        @DisplayName("UNKNOWN — 기존 정책 보존: 전량체결로 간주하고 닫는다(조회 실패로 매도가 멈추지 않게)")
        void unknownPreservesExistingPolicy() {
            PartialSellTracker.Outcome o = PartialSellTracker.apply(placed(5, 0), FillStatus.UNKNOWN, 0);

            assertThat(o.state().isComplete()).isTrue();
            assertThat(o.state().isOutstanding()).isFalse();
            assertThat(o.shouldReconcileHistory()).isFalse();
            assertThat(o.alert()).contains("전량체결로 간주");
        }
    }

    // ==================== 늦은 추가 체결 · 멱등 ====================

    @Nested
    @DisplayName("늦은 추가 체결과 반복 조회")
    class LateFillAndIdempotency {

        @Test
        @DisplayName("2주 → 4주로 늘면 반영한다")
        void lateAdditionalFillIsApplied() {
            PartialSellTracker.Outcome o = PartialSellTracker.apply(placed(5, 2), FillStatus.PARTIAL, 4);

            assertThat(o.state().filledQty()).isEqualTo(4);
            assertThat(o.state().isOutstanding()).isTrue();
        }

        @Test
        @DisplayName("2주 → 5주로 채워지면 완료로 닫는다")
        void lateFillCanComplete() {
            PartialSellTracker.Outcome o = PartialSellTracker.apply(placed(5, 2), FillStatus.FULL, 5);

            assertThat(o.state().isComplete()).isTrue();
            assertThat(o.state().orderNo()).isNull();
        }

        @Test
        @DisplayName("관측값이 줄어도 누계는 줄지 않는다 — 조회 흔들림에 잔량을 잃지 않는다")
        void filledQtyIsMonotonic() {
            PartialSellTracker.Outcome o = PartialSellTracker.apply(placed(5, 3), FillStatus.NONE, 0);

            assertThat(o.state().filledQty()).isEqualTo(3);
        }

        @Test
        @DisplayName("같은 결과로 반복 조회해도 상태가 그대로다 — 멱등")
        void repeatedConfirmIsIdempotent() {
            PartialSellTracker.State s = placed(5, 2);
            PartialSellTracker.State a = PartialSellTracker.apply(s, FillStatus.PARTIAL, 2).state();
            PartialSellTracker.State b = PartialSellTracker.apply(a, FillStatus.PARTIAL, 2).state();

            assertThat(b).isEqualTo(a);
        }
    }

    // ==================== 재시작 ====================

    @Test
    @DisplayName("재시작 복원: 주문번호·목표·체결 누계가 남아 있으면 그대로 이어서 확인한다")
    void restartResumesConfirmation() {
        // DB 에서 복원된 상태 — 접수 5주 중 2주 체결
        PartialSellTracker.State restored = placed(5, 2);

        assertThat(PartialSellTracker.decide(restored, true, true))
                .isEqualTo(PartialSellTracker.Action.CONFIRM_EXISTING);
        assertThat(restored.isComplete()).isFalse();
    }

    @Test
    @DisplayName("옛 행 호환: 주문 추적 컬럼이 비어 있고 halfSold 만 true 면 완료로 본다")
    void legacyRowWithOnlyHalfSoldIsTreatedAsComplete() {
        // 마이그레이션 전 행 — orderNo 없음, target/filled 0. 호출부가 halfSold=true 를 이 상태로 복원한다.
        PartialSellTracker.State legacyComplete = new PartialSellTracker.State(null, 1, 1, null);

        assertThat(legacyComplete.isComplete()).isTrue();
        assertThat(PartialSellTracker.decide(legacyComplete, true, true))
                .isEqualTo(PartialSellTracker.Action.SKIP_COMPLETE);
    }
}
