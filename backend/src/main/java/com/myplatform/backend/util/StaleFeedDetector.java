package com.myplatform.backend.util;

import java.math.BigDecimal;
import java.util.List;

/**
 * P2-11 스테일/동결 피드 감지기 (순수 함수 — 가격 보정 없음, 관측만).
 *
 * <p>거래정지·상폐·스테일 종목은 같은 현재가가 정규장 내내 반복 적재되어 화면엔 "정상 시세"처럼 보인다.
 * (예: 001230 이 11,400 에 수주간 3분마다 고정.) ×10 가드(P0-1)는 가격이 밴드/배수를 벗어나야 잡으므로
 * 이 "변화 0" 동결은 감지하지 못한다. 이 감지기는 <b>가장 최근 값이 직전부터 몇 틱 연속 동일했는지</b>(꼬리
 * 동결 길이)만 계산한다. 임계 비교·정지종목 교차(StockStatusService)·로깅은 호출자가 수행한다.
 */
public final class StaleFeedDetector {

    private StaleFeedDetector() {}

    /**
     * 시간순(과거→현재) 가격 리스트에서, 마지막(최근) 값이 직전부터 몇 틱 연속으로 동일한지 반환.
     *
     * <p>예: [70000,70000,70000] → 3, [70000,71000,71000] → 2, [70000,71000,70000] → 1, [] → 0.
     * 정규장 한정 판정을 원하면 호출자가 정규장 구간 가격만 추려서 넘긴다(휴장/장외 동결은 정상).
     */
    public static int trailingFrozenRun(List<BigDecimal> chronoPrices) {
        if (chronoPrices == null || chronoPrices.isEmpty()) return 0;
        int n = chronoPrices.size();
        BigDecimal last = chronoPrices.get(n - 1);
        if (last == null) return 0;
        int run = 0;
        for (int i = n - 1; i >= 0; i--) {
            BigDecimal p = chronoPrices.get(i);
            if (p != null && p.compareTo(last) == 0) {
                run++;
            } else {
                break;
            }
        }
        return run;
    }
}
