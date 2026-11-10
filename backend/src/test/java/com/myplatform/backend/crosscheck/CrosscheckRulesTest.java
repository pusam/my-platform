package com.myplatform.backend.crosscheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 대조 순수 규칙. 응답 고정값은 2026-11-10 16:33 KST 삼성전자 실제 응답에서 쓰는 칸만 옮겼다
 * (같은 시각 basic 응답: 현재가 198,900 · 전일 대비 +9,000 → 전일 189,900).
 */
class CrosscheckRulesTest {

    static final String SAMSUNG_INTEGRATION = """
            {"stockEndType":"stock","itemCode":"005930",
             "totalInfos":[
               {"code":"lastClosePrice","key":"전일","value":"189,900"},
               {"code":"openPrice","key":"시가","value":"197,600"},
               {"code":"highPrice","key":"고가","value":"203,500"},
               {"code":"lowPrice","key":"저가","value":"196,400"},
               {"code":"per","key":"PER","value":"8.92배","valueDesc":"2026.06."},
               {"code":"eps","key":"EPS","value":"22,292원","valueDesc":"2026.06."},
               {"code":"pbr","key":"PBR","value":"2.31배","valueDesc":"2026.06."},
               {"code":"bps","key":"BPS","value":"86,052원","valueDesc":"2026.06."}],
             "dealTrendInfos":[{"bizdate":"20261109","closePrice":"187,000"}]}
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Nested
    @DisplayName("응답 해석")
    class Parse {

        @Test
        @DisplayName("실제 응답 — 전일 189,900 · EPS 22,292(2026.06.) · BPS 86,052. 투자자 동향 표의 종가(187,000)는 쓰지 않는다")
        void realResponse() throws Exception {
            CrosscheckRules.NaverQuote q = CrosscheckRules.parseIntegration(MAPPER.readTree(SAMSUNG_INTEGRATION));
            assertThat(q.lastClose().value()).isEqualByComparingTo("189900");
            assertThat(q.lastClose().raw()).isEqualTo("189,900");
            assertThat(q.eps().value()).isEqualByComparingTo("22292");
            assertThat(q.eps().basis()).isEqualTo("2026.06.");
            assertThat(q.bps().value()).isEqualByComparingTo("86052");
        }

        @Test
        @DisplayName("칸이 없거나 숫자가 아니거나 0 이면 값 없음 — 0 원을 종가로 견주지 않는다(§4c)")
        void missingIsNull() throws Exception {
            assertThat(CrosscheckRules.parseIntegration(MAPPER.readTree("{}")).lastClose().value()).isNull();
            for (String v : new String[]{"N/A", "-", "", "0", "abc"}) {
                String json = "{\"totalInfos\":[{\"code\":\"lastClosePrice\",\"value\":\"" + v + "\"}]}";
                assertThat(CrosscheckRules.parseIntegration(MAPPER.readTree(json)).lastClose().value()).as(v).isNull();
            }
        }

        @Test
        @DisplayName("음수 EPS 는 그대로 숫자다(적자)")
        void negativeEps() {
            assertThat(CrosscheckRules.number("-1,234원")).isEqualByComparingTo("-1234");
            assertThat(CrosscheckRules.number("8.92배")).isEqualByComparingTo("8.92");
        }
    }

    @Nested
    @DisplayName("종가 판정 — 정확히 같아야 한다")
    class Classify {

        @Test
        void exactMatch() {
            assertThat(CrosscheckRules.classifyClose(d("189900"), d("187000"), d("189900"))).isEqualTo(CrosscheckRules.MATCH);
        }

        @Test
        @DisplayName("1원이라도 다르면 불일치 — 허용 폭 없음")
        void oneWonOff() {
            assertThat(CrosscheckRules.classifyClose(d("189900"), d("187000"), d("189800"))).isEqualTo(CrosscheckRules.MISMATCH);
        }

        @Test
        @DisplayName("저녁 값이 남은 봉(11/3 삼성전자 222,000 vs KRX 219,500)은 불일치")
        void eveningValue() {
            assertThat(CrosscheckRules.classifyClose(d("222000"), d("217500"), d("219500"))).isEqualTo(CrosscheckRules.MISMATCH);
        }

        @Test
        @DisplayName("외부 값이 우리 앞 봉과 같으면 '하루 늦음' — 대조 못 함")
        void externalStale() {
            String s = CrosscheckRules.classifyClose(d("198900"), d("189900"), d("189900"));
            assertThat(s).isEqualTo(CrosscheckRules.EXTERNAL_STALE);
            assertThat(CrosscheckRules.isCompared(s)).isFalse();
        }

        @Test
        @DisplayName("보합(앞 봉과 같은 값)은 하루 늦음이 아니라 일치")
        void flatIsMatch() {
            assertThat(CrosscheckRules.classifyClose(d("1000"), d("1000"), d("1000"))).isEqualTo(CrosscheckRules.MATCH);
        }

        @Test
        @DisplayName("우리 봉이 없거나 외부 값이 없으면 대조 못 함 — 일치로 세지 않는다")
        void missing() {
            assertThat(CrosscheckRules.classifyClose(null, null, d("1000"))).isEqualTo(CrosscheckRules.NO_OUR_BAR);
            assertThat(CrosscheckRules.classifyClose(d("1000"), null, null)).isEqualTo(CrosscheckRules.NO_EXTERNAL_VALUE);
            assertThat(CrosscheckRules.isCompared(CrosscheckRules.NO_OUR_BAR)).isFalse();
            assertThat(CrosscheckRules.isCompared(CrosscheckRules.FETCH_FAILED)).isFalse();
        }

        @Test
        void diffPct() {
            assertThat(CrosscheckRules.diffPct(d("222000"), d("219500"))).isEqualByComparingTo("-1.1261");
            assertThat(CrosscheckRules.diffPct(null, d("1"))).isNull();
            assertThat(CrosscheckRules.diffPct(BigDecimal.ZERO, d("1"))).isNull();
        }
    }

    @Nested
    @DisplayName("표본")
    class Sample {

        private List<String> codes(String prefix, int n) {
            return java.util.stream.IntStream.range(0, n).mapToObj(i -> String.format("%s%05d", prefix, i)).toList();
        }

        @Test
        @DisplayName("그룹마다 최대 10, 보유+관심은 합쳐 10, 나머지는 무작위로 40 까지")
        void caps() {
            Map<String, String> s = CrosscheckRules.selectSample(codes("1", 15), codes("2", 7), codes("3", 7),
                    codes("4", 15), codes("9", 100), 20401L, c -> false);
            assertThat(s).hasSize(40);
            assertThat(s.values().stream().filter(CrosscheckRules.REASON_CANDIDATE::equals)).hasSize(10);
            assertThat(s.values().stream().filter(v -> v.equals(CrosscheckRules.REASON_HOLDING)
                    || v.equals(CrosscheckRules.REASON_WATCH))).hasSize(10);
            assertThat(s.values().stream().filter(CrosscheckRules.REASON_MCAP::equals)).hasSize(10);
            assertThat(s.values().stream().filter(CrosscheckRules.REASON_RANDOM::equals)).hasSize(10);
        }

        @Test
        @DisplayName("같은 날(같은 시드)이면 입력 순서와 무관하게 같은 무작위 표본, 다른 날이면 다르다")
        void deterministic() {
            List<String> pool = codes("9", 200);
            List<String> reversed = new java.util.ArrayList<>(pool);
            java.util.Collections.reverse(reversed);
            Map<String, String> a = CrosscheckRules.selectSample(List.of(), List.of(), List.of(), List.of(), pool, 7L, c -> false);
            Map<String, String> b = CrosscheckRules.selectSample(List.of(), List.of(), List.of(), List.of(), reversed, 7L, c -> false);
            Map<String, String> c = CrosscheckRules.selectSample(List.of(), List.of(), List.of(), List.of(), pool, 8L, x -> false);
            assertThat(a.keySet()).containsExactlyElementsOf(b.keySet());
            assertThat(a.keySet()).isNotEqualTo(c.keySet());
        }

        @Test
        @DisplayName("거래정지·코드 모양이 아닌 값·중복은 뺀다")
        void excludes() {
            Map<String, String> s = CrosscheckRules.selectSample(List.of("005930", "005930", "bad", "0039P0"),
                    List.of("000660"), List.of(), List.of("005930"), List.of(), 1L, Set.of("000660")::contains);
            assertThat(s).containsOnlyKeys("005930", "0039P0");
            assertThat(s.get("005930")).isEqualTo(CrosscheckRules.REASON_CANDIDATE);
        }

        @Test
        @DisplayName("하루 표본은 40 을 넘지 않는다(외부 호출 상한)")
        void neverAbove40() {
            Map<String, String> s = CrosscheckRules.selectSample(codes("1", 100), codes("2", 100), codes("3", 100),
                    codes("4", 100), codes("9", 1000), 3L, c -> false);
            assertThat(s).hasSize(CrosscheckRules.MAX_SAMPLE);
        }
    }

    @Nested
    @DisplayName("실행 메모 — '하루 늦음'이 넘어간 종목과 섞이면 진짜 불일치일 수 있다")
    class Note {

        @Test
        @DisplayName("하루 늦음 + 앞 봉과 다른데 일치한 종목 → 섞임 경고")
        void mixed() {
            List<CrosscheckRules.CloseOutcome> o = List.of(
                    new CrosscheckRules.CloseOutcome("005930", CrosscheckRules.MATCH, d("189900"), d("187000"), d("189900")),
                    new CrosscheckRules.CloseOutcome("000660", CrosscheckRules.EXTERNAL_STALE, d("100"), d("90"), d("90")));
            assertThat(CrosscheckRules.staleMixedWithMatch(o)).isTrue();
            assertThat(CrosscheckRules.runNote(o, null)).contains("EXTERNAL_STALE 1").contains("진짜 불일치일 수 있다");
        }

        @Test
        @DisplayName("일치한 종목이 보합뿐이면 섞임이 아니다 — 넘어갔는지 알 수 없다")
        void flatMatchesDoNotCount() {
            List<CrosscheckRules.CloseOutcome> o = List.of(
                    new CrosscheckRules.CloseOutcome("005930", CrosscheckRules.MATCH, d("1000"), d("1000"), d("1000")),
                    new CrosscheckRules.CloseOutcome("000660", CrosscheckRules.EXTERNAL_STALE, d("100"), d("90"), d("90")));
            assertThat(CrosscheckRules.staleMixedWithMatch(o)).isFalse();
        }

        @Test
        @DisplayName("전부 일치면 메모가 없다")
        void cleanRun() {
            List<CrosscheckRules.CloseOutcome> o = List.of(
                    new CrosscheckRules.CloseOutcome("005930", CrosscheckRules.MATCH, d("1"), d("2"), d("1")));
            assertThat(CrosscheckRules.runNote(o, null)).isNull();
        }
    }
}
