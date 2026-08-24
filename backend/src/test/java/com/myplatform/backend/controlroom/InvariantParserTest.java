package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 불변식 목록 파서 — {@link InvariantParser}.
 *
 * <p>크루가 초안을 대조할 기준이라 "못 찾았는데 빈 목록"이 제일 위험하다 — FIREWALL 이 아무 제약
 * 없이 승인하게 된다. 그래서 섹션 부재는 {@code dataAvailable=false} 로 구분한다(§4c).
 */
class InvariantParserTest {

    @Test
    @DisplayName("불변식 섹션의 소제목만 뽑고 다른 섹션은 섞지 않는다")
    void extractsOnlyInvariantSection() {
        String md = """
                # 문서

                ## 빌드 / 테스트 명령

                ### 이건 불변식이 아니다

                ## 절대 건드리면 안 되는 설계 불변식 (의도된 것)

                ### 1. 시세는 단일 경로
                본문 본문

                ### 4c. "데이터 없음"을 위장하지 않는다
                본문

                ## 코드 위치 힌트

                ### 이것도 불변식이 아니다
                """;

        InvariantParser.Result r = InvariantParser.parse(md);

        assertThat(r.dataAvailable()).isTrue();
        assertThat(r.invariants()).containsExactly(
                "1. 시세는 단일 경로",
                "4c. \"데이터 없음\"을 위장하지 않는다");
    }

    @Test
    @DisplayName("마크다운 장식(볼드·백틱)은 제거한다 — 프롬프트 노이즈 감소")
    void stripsMarkdownDecoration() {
        String md = """
                ## 설계 불변식

                ### 3. **가격 이상치 가드**는 `warnIfPriceOutlier` 로 로깅만
                """;

        assertThat(InvariantParser.parse(md).invariants())
                .containsExactly("3. 가격 이상치 가드는 warnIfPriceOutlier 로 로깅만");
    }

    @Test
    @DisplayName("섹션을 못 찾으면 '불변식 없음'이 아니라 데이터 없음이다")
    void missingSectionIsNotEmptyList() {
        InvariantParser.Result r = InvariantParser.parse("# 문서\n## 다른 섹션\n### 소제목\n");

        assertThat(r.dataAvailable()).isFalse();
        assertThat(r.invariants()).isEmpty();
    }

    @Test
    @DisplayName("실제 CLAUDE.md 에서 불변식 소제목을 읽어온다")
    void realClaudeMdParses() throws IOException {
        Path path = Path.of("..", "CLAUDE.md");
        assumeTrue(Files.exists(path), "레포 루트 기준 실행이 아닐 때는 건너뛴다");

        InvariantParser.Result r =
                InvariantParser.parse(Files.readString(path, StandardCharsets.UTF_8));

        assertThat(r.dataAvailable()).isTrue();
        // 현재 CLAUDE.md 의 불변식 소제목은 1 / 2 / 3 / 4 / 4b / 4c / 4d / 5 / 6 — 9개.
        assertThat(r.invariants()).hasSizeGreaterThanOrEqualTo(6);
        assertThat(r.invariants().get(0)).startsWith("1. 시세는 단일 경로");
        assertThat(r.invariants()).anySatisfy(s -> assertThat(s).contains("데이터 없음"));
    }
}
