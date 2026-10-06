package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * '상장폐지'는 온전한 KIS 종목마스터에 없는 것만(2026-10-07).
 *
 * <p>재무 수집 유니버스는 줄지 않게 만든 합집합이라(R5) 상폐 코드도 영영 남았다 — 그걸 빼려면 '목록에 없다'를 믿을 수 있어야 한다.
 * 동기화 전(빈 목록)이나 잘린 목록(시장 하나가 일부만 내려받힘 — 동기화 게이트는 '빈 시장'과 '100건 미만'만 막는다)에서 '없다'는
 * 상폐가 아니라 모르는 것이다(§4c). 거래량 정지는 상장이 유지되므로 상폐가 아니다 — 재개하면 바로 다시 수집돼야 한다.
 */
class StockStatusDelistedTest {

    private static Set<String> master(int size, String... include) {
        Set<String> codes = new HashSet<>(Set.of(include));
        for (int i = 0; codes.size() < size; i++) codes.add(String.format("9%05d", i));
        return codes;
    }

    @Test
    @DisplayName("온전한 마스터(3,944건)에 없으면 상폐, 있으면 아니다")
    void absentFromCompleteMasterIsDelisted() {
        Set<String> complete = master(3_944, "005930");
        assertThat(StockStatusService.knownDelisted(complete, "000010")).isTrue();
        assertThat(StockStatusService.knownDelisted(complete, "005930")).isFalse();
    }

    @Test
    @DisplayName("동기화 전(빈 목록)·잘린 목록이면 모른다(false) — 없다는 이유로 빼지 않는다")
    void incompleteMasterIsUnknown() {
        assertThat(StockStatusService.knownDelisted(Set.of(), "000010")).isFalse();
        assertThat(StockStatusService.knownDelisted(master(2_110, "005930"), "000010")).isFalse();   // KOSPI 만 받힌 꼴
    }
}
