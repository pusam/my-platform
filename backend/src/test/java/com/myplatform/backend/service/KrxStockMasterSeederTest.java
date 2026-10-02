package com.myplatform.backend.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 영숫자 종목코드가 기존 상장사의 숫자 코드와 겹쳐 그 회사 이름을 덮던 버그(2026-10-02 화면 점검).
 *
 * <p>KIND 상장법인목록의 신규 상장 코드는 영문이 섞인다("0009K0", "0039P0"). 예전 {@code padCode} 는 숫자 아닌 글자를
 * 지우고 zero-pad 해서 "0039P0" → "000390"(삼화페인트), "0008Z0" → "000080"(하이트진로) 같은 <b>남의 코드</b>를 만들었다.
 * 마스터 upsert 는 코드가 같으면 이름·시장을 덮어쓰므로 운영 42행이 신규 상장사 이름으로 바뀌었고, KIS 가 종목명을 빈 값으로 줘서
 * 이름을 마스터에서 보충하는 화면(시세 단일 경로 §1)이 하이트진로 시세 옆에 다른 회사 이름을 띄웠다.
 */
class KrxStockMasterSeederTest {

    @Nested
    @DisplayName("padCode — 영숫자 코드는 그대로, 숫자 코드만 zero-pad")
    class PadCode {

        @Test
        @DisplayName("재현: 영숫자 코드가 숫자만 남아 기존 상장사 코드(000390 삼화페인트)가 되던 것")
        void alphanumericCodeIsKeptAsIs() {
            assertThat(KrxStockMasterSeeder.padCode("0039P0")).isEqualTo("0039P0").isNotEqualTo("000390");
            assertThat(KrxStockMasterSeeder.padCode("0009K0")).isEqualTo("0009K0").isNotEqualTo("000090");
            assertThat(KrxStockMasterSeeder.padCode(" 0039p0 ")).isEqualTo("0039P0");
        }

        @Test
        @DisplayName("숫자만인 코드는 종전대로 6자리 zero-pad(엑셀이 앞자리 0 을 떨군 경우)")
        void numericCodeIsZeroPadded() {
            assertThat(KrxStockMasterSeeder.padCode("5930")).isEqualTo("005930");
            assertThat(KrxStockMasterSeeder.padCode("005930")).isEqualTo("005930");
            assertThat(KrxStockMasterSeeder.padCode("660")).isEqualTo("000660");
        }

        @Test
        @DisplayName("형식이 아니면 건너뛴다(빈 문자열) — 억지로 코드를 만들지 않는다")
        void invalidFormatIsSkipped() {
            assertThat(KrxStockMasterSeeder.padCode(null)).isEmpty();
            assertThat(KrxStockMasterSeeder.padCode("")).isEmpty();
            assertThat(KrxStockMasterSeeder.padCode("1234567")).isEmpty();
            assertThat(KrxStockMasterSeeder.padCode("A12-34")).isEmpty();
            assertThat(KrxStockMasterSeeder.padCode("삼성전자")).isEmpty();
        }
    }

    @Nested
    @DisplayName("legacyMangledCodes — 예전 정규화가 만든 유령 코드")
    class LegacyMangledCodes {

        @Test
        @DisplayName("목록의 어떤 종목도 쓰지 않는 뭉갠 코드만 유령 — 실제 상장사가 쓰는 코드(000390)는 지우지 않는다")
        void onlyCodesNoListedCompanyUses() {
            Set<String> seen = Set.of("000080", "0009K0", "0039P0", "000390");
            assertThat(KrxStockMasterSeeder.legacyMangledCodes(seen)).containsExactly("000090");
        }

        @Test
        @DisplayName("숫자 코드만 있으면 유령 없음")
        void numericOnlyListHasNoGhosts() {
            assertThat(KrxStockMasterSeeder.legacyMangledCodes(Set.of("005930", "000660"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("유령 행 정리 — 두 시장 목록이 다 받아졌을 때만")
    class Prune {

        private final StockMasterService master = mock(StockMasterService.class);
        private final KrxStockMasterSeeder seeder = new KrxStockMasterSeeder(master, new SimpleMeterRegistry());
        private final Set<String> seen = Set.of("000080", "0009K0", "0039P0", "000390");

        @Test
        @DisplayName("목록이 덜 받아진 날(KOSPI 일부)엔 지우지 않는다 — 진짜 상장사 행을 유령으로 오판하지 않게")
        void skipsWhenListIsPartial() {
            seeder.pruneLegacyMangledRows(120, 1800, seen);
            seeder.pruneLegacyMangledRows(840, 0, seen);
            verify(master, never()).removeKrxRows(any());
        }

        @Test
        @DisplayName("다 받아졌으면 유령 코드만 지운다")
        void removesGhostsWhenComplete() {
            when(master.removeKrxRows(any())).thenReturn(1);
            seeder.pruneLegacyMangledRows(840, 1800, seen);
            verify(master).removeKrxRows(Set.of("000090"));
        }
    }
}
