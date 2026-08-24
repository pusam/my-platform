package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 에렌 결론의 액션 줄 파서 — {@link CrewActionParser}.
 *
 * <p>버튼은 <b>새 지시를 보내는 것뿐</b>이고 아무것도 실행하지 않는다. 파싱이 실패해도 결론 텍스트는
 * 그대로 보여야 하므로, 실패는 예외가 아니라 빈 목록이다.
 */
class CrewActionParserTest {

    @Test
    @DisplayName("액션 2개를 뽑는다")
    void extractsTwoActions() {
        String text = """
                결론: 지금 확정 2건, 나머지는 9/16 한 세션으로 몬다.
                액션: SCHEDULE_DECISIONS.md 초안 만들어 | V52 등록 프롬프트 뽑아줘""";

        assertThat(CrewActionParser.extract(text))
                .containsExactly("SCHEDULE_DECISIONS.md 초안 만들어", "V52 등록 프롬프트 뽑아줘");
    }

    @Test
    @DisplayName("3개 이상 적어도 2개까지만 쓴다")
    void capsAtTwo() {
        assertThat(CrewActionParser.extract("액션: A | B | C | D")).containsExactly("A", "B");
    }

    @Test
    @DisplayName("액션 줄이 없으면 빈 목록 — 예외를 던지지 않는다")
    void noActionLineIsEmpty() {
        assertThat(CrewActionParser.extract("결론: 그냥 결론만 있음")).isEmpty();
        assertThat(CrewActionParser.extract(null)).isEmpty();
        assertThat(CrewActionParser.extract("")).isEmpty();
    }

    @Test
    @DisplayName("액션 줄은 본문에서 걷어낸다 — 버튼과 중복 노출 방지")
    void stripsActionLineFromBody() {
        String text = """
                결론: 요약 한 줄.
                액션: A | B""";

        assertThat(CrewActionParser.stripActionLine(text))
                .isEqualTo("결론: 요약 한 줄.")
                .doesNotContain("액션:");
    }

    @Test
    @DisplayName("전각 콜론도 받는다 (모델이 종종 쓴다)")
    void acceptsFullWidthColon() {
        assertThat(CrewActionParser.extract("액션： 가 | 나")).containsExactly("가", "나");
    }
}
