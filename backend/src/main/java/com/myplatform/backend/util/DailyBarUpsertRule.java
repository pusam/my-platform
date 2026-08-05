package com.myplatform.backend.util;

import com.myplatform.backend.entity.StockPriceHistory;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일봉 저장 시 "덮어쓸 것인가" 판정 — <b>순수 함수</b>(P2-19 ①).
 *
 * <p><b>고치는 문제</b>: 기존 저장은 {@code existsByStockCodeAndTradeDate} 로 <b>존재하면 무조건 스킵</b>했다.
 * 일봉 수집은 60봉을 한 번에 받는데 장중에 돌면 <b>맨 앞 1봉(당일)이 미확정</b>(고가·저가·거래량이 진행 중)이다.
 * 그 미확정 봉이 저장된 뒤 다음날부터는 "이미 존재"라 영원히 갱신되지 않아 <b>영구 확정</b>됐다.
 * RSI·이동평균·5일 누적·낙폭과대가 전부 이 히스토리 위에서 계산되므로 기술 점수가 오염된다.
 *
 * <p><b>판정 규칙</b>:
 * <ol>
 *   <li>기존 행 없음 → 저장(신규)</li>
 *   <li>거래일이 <b>오늘 이후</b>(=당일 미확정 봉) → 항상 덮어쓰기. 장중엔 부분값끼리 갱신되고,
 *       장 마감 후 배치가 확정값으로 마무리한다.</li>
 *   <li>과거 봉 → <b>값이 다를 때만</b> 덮어쓰기. 이미 저장된 장중 동결분을 확정값으로 <b>복구</b>하되,
 *       정상 상태(값 동일)에선 쓰기가 0이 되어 배치가 조용하다.</li>
 * </ol>
 *
 * <p><b>§4c</b>: 들어온 데이터의 종가가 없거나 0 이하면 저장 대상이 아니다(호출부에서 먼저 거른다).
 * 결측 필드로 기존 정상 값을 덮어쓰지 않도록, 비교는 "들어온 값이 있는 필드"만 본다.
 */
public final class DailyBarUpsertRule {

    private DailyBarUpsertRule() {}

    /** 들어온 일봉 한 건의 값 — 엔티티/DTO 의존을 끊기 위한 최소 표현. */
    public record Bar(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume) {}

    /**
     * 덮어쓸지 판정.
     *
     * @param existing  DB 의 기존 행(없으면 null)
     * @param fresh     새로 받은 값
     * @param tradeDate 해당 봉의 거래일
     * @param today     오늘(KST) — 테스트 결정성을 위해 주입
     */
    public static boolean shouldWrite(StockPriceHistory existing, Bar fresh, LocalDate tradeDate, LocalDate today) {
        if (fresh == null || fresh.close() == null || fresh.close().signum() <= 0) return false;
        if (existing == null) return true;                       // 신규
        if (tradeDate == null || today == null) return false;    // 판정 불가 — 기존 값 보존
        if (!tradeDate.isBefore(today)) return true;             // 당일(미확정) — 항상 최신화
        return differs(existing, fresh);                         // 과거 봉은 값이 다를 때만(동결분 복구)
    }

    /**
     * 저장된 봉과 새로 받은 봉이 실질적으로 다른지 — 순수 함수.
     *
     * <p>새 값이 null 인 필드는 <b>비교하지 않는다</b>(결측을 "다름"으로 보면 정상 값을 결측으로 덮어쓴다 — §4c).
     * BigDecimal 은 scale 이 달라도 같은 수면 같게 보도록 {@code compareTo} 로 비교한다
     * (예: 저장된 {@code 1000.00} vs 새로 받은 {@code 1000}).
     */
    public static boolean differs(StockPriceHistory existing, Bar fresh) {
        if (existing == null || fresh == null) return true;
        return neq(existing.getOpenPrice(), fresh.open())
                || neq(existing.getHighPrice(), fresh.high())
                || neq(existing.getLowPrice(), fresh.low())
                || neq(existing.getClosePrice(), fresh.close())
                || neq(existing.getVolume(), fresh.volume());
    }

    /** 새 값이 있을 때만 비교 — 새 값 null 은 "변화 없음"으로 취급(기존 값 보존). */
    private static boolean neq(BigDecimal stored, BigDecimal fresh) {
        if (fresh == null) return false;
        if (stored == null) return true;
        return stored.compareTo(fresh) != 0;
    }
}
