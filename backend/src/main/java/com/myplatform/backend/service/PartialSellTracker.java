package com.myplatform.backend.service;

/**
 * 분할익절(1차 절반 매도) 주문의 <b>상태 전이</b> — 순수 함수(2026-09-17 감사 F1).
 *
 * <p><b>고치려는 결함</b>: 스캘핑 1차 익절은 주문 <b>접수</b>만 확인하고 <b>체결</b>은 확인하지 않았다.
 * {@code executeScalpingSell} 의 체결 확인 분기가 {@code !isPartialSell} 조건이라 분할매도는 통째로
 * 건너뛰었고, 주문 직전에 {@code halfSold=true} 를 영속화했다. 그래서 0주 체결이어도
 * ① 1차 익절이 "완료"로 남아 다음 평가에서 그 분기를 건너뛰고
 * ② {@code RealTradeService.sell} 이 <b>요청 수량</b>으로 저장한 매도 이력·실현손익이 그대로 남았다
 * (일일손실 브레이커 입력이 체결되지도 않은 이익으로 상쇄될 수 있다).
 *
 * <p><b>세 가지를 구분한다</b> — 주문 접수({@code orderNo}), 실제 체결({@code filledQty}),
 * 목표 분할익절 완료({@code isComplete()}). 예전 {@code halfSold} 하나로는 이 셋이 뭉개져 있었다.
 *
 * <p>⚠ <b>{@code halfSold=false} 로 되돌려 다음 틱에 새 주문을 넣는 수정은 금지</b>다(감사 명시).
 * 기존 주문의 미체결 잔량이 뒤늦게 체결되면 중복 청산이 된다. 그래서 미체결 주문이 살아 있는 동안은
 * <b>새 주문을 내지 않고 기존 주문을 다시 확인</b>한다({@link Action#CONFIRM_EXISTING}).
 *
 * <p>⚠ <b>취소·정정 API 는 쓰지 않는다</b>(이번 범위 밖). 그래서 영영 체결되지 않는 주문은 이 상태에
 * 머문 채 그 종목의 매도가 보류된다 — <b>현재 API 로 해결할 수 없는 한계</b>이며 호출부가 경보로 드러낸다.
 * 임의 재주문으로 메우지 않는다.
 */
public final class PartialSellTracker {

    private PartialSellTracker() {}

    /**
     * 추적 상태. {@code orderNo == null} 이면 진행 중인 분할익절 주문이 없다.
     *
     * @param orderNo   접수된 주문번호(KIS). null = 주문 없음
     * @param targetQty 목표 분할 수량
     * @param filledQty <b>관측된 체결 누계</b> — 단조 증가(늦은 추가 체결 반영)
     * @param tradeId   매도 이력 행 id(정정용). null 가능
     */
    public record State(String orderNo, int targetQty, int filledQty, Long tradeId) {

        public static State none() {
            return new State(null, 0, 0, null);
        }

        /** 목표만큼 체결됐는가 — 이것이 "1차 익절 완료"다(주문 접수가 아니라). */
        public boolean isComplete() {
            return targetQty > 0 && filledQty >= targetQty;
        }

        /** 접수됐는데 아직 목표에 못 미친 주문이 살아 있는가 — 이 동안 새 매도 주문을 내지 않는다. */
        public boolean isOutstanding() {
            return orderNo != null && !isComplete();
        }
    }

    public enum Action {
        /** 새 분할익절 주문을 낸다. */
        PLACE_NEW,
        /** 살아 있는 주문의 체결을 다시 확인한다 — <b>새 주문을 내지 않는다</b>. */
        CONFIRM_EXISTING,
        /** 이미 목표 분할익절이 끝났다 — 이 분기를 건너뛴다. */
        SKIP_COMPLETE,
        /** 익절 조건 미달 또는 나눌 수 없는 수량. */
        SKIP_NOT_ELIGIBLE
    }

    /**
     * 지금 무엇을 할 것인가 — 순수.
     *
     * @param eligible    익절 조건(수익률 등)을 만족하는가
     * @param splittable  절반이 1주 이상인가(1주 포지션은 분할 불가 — 트레일링/타임컷의 몫)
     */
    public static Action decide(State state, boolean eligible, boolean splittable) {
        State s = state == null ? State.none() : state;
        if (s.isOutstanding()) return Action.CONFIRM_EXISTING;   // 조건과 무관하게 기존 주문이 먼저다
        if (s.isComplete()) return Action.SKIP_COMPLETE;
        if (!eligible || !splittable) return Action.SKIP_NOT_ELIGIBLE;
        return Action.PLACE_NEW;
    }

    /** 체결 관측 결과 반영 뒤 할 일. */
    public record Outcome(State state, boolean shouldReconcileHistory, String alert) {}

    /**
     * 체결 관측을 반영한다 — 순수.
     *
     * <p><b>UNKNOWN 은 기존 정책 그대로</b>(§4d): 조회 실패는 전량체결로 보고 주문을 닫는다. 이 극성을
     * 뒤집으면(미체결로 간주) 조회가 흔들릴 때마다 주문이 영원히 열린 상태로 남아 매도가 멈춘다.
     *
     * <p>체결 누계는 <b>단조 증가</b>다 — 일시적 조회 실패로 관측값이 줄어도 이전 관측을 지우지 않는다
     * (그러면 PARTIAL 이 다시 NONE 이 되어 잔량이 KIS orphan 이 된다).
     *
     * @param observedFilled 이번 관측 체결 수량(UNKNOWN 이면 무시)
     */
    public static Outcome apply(State state, RealTradeService.FillStatus status, int observedFilled) {
        State s = state == null ? State.none() : state;
        if (status == RealTradeService.FillStatus.UNKNOWN) {
            // 기존 정책 보존 — 조회 불가는 현행(전량체결 가정)으로 닫는다.
            return new Outcome(new State(null, s.targetQty(), s.targetQty(), s.tradeId()), false,
                    "체결 조회 불가 — 기존 정책대로 전량체결로 간주하고 1차 익절을 닫는다(주문번호 " + s.orderNo() + ").");
        }
        int filled = Math.max(s.filledQty(), Math.max(0, observedFilled));
        if (filled >= s.targetQty() && s.targetQty() > 0) {
            // 전량 체결 — 기록은 요청 수량 그대로라 정정 불필요.
            return new Outcome(new State(null, s.targetQty(), s.targetQty(), s.tradeId()), false, null);
        }
        // 확정 부분/미체결 — 기록을 실체결로 정정하고 주문은 살아 있는 채로 둔다.
        State next = new State(s.orderNo(), s.targetQty(), filled, s.tradeId());
        String alert = "분할익절 " + filled + "/" + s.targetQty() + "주만 체결 — 주문("
                + s.orderNo() + ")이 남아 있어 새 매도를 내지 않는다. 취소·정정은 자동으로 하지 않는다.";
        return new Outcome(next, true, alert);
    }
}
