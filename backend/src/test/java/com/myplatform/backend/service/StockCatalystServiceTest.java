package com.myplatform.backend.service;

import com.myplatform.backend.dto.RiskAnalysisDto.NewsItem;
import com.myplatform.backend.entity.StockCatalyst.CatalystType;
import com.myplatform.backend.entity.StockCatalyst.Direction;
import com.myplatform.backend.service.StockCatalystService.ParsedCatalyst;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재료 분류 (V31) — Gemini 응답 파싱 순수 함수 테스트.
 *
 * Gemini 는 JSON 펜스/설명 문구를 섞어 답하는 경우가 있어 파서가 관용적이어야 하고,
 * enum 어휘 밖 응답은 null (캐시 안 함 → 다음 기회 재시도) 이어야 한다.
 */
class StockCatalystServiceTest {

    @Test
    @DisplayName("정상 JSON → 유형/방향/제목/요약 파싱")
    void parse_validJson() {
        ParsedCatalyst p = StockCatalystService.parseCatalystResponse(
                "{\"type\":\"ORDER_WIN\",\"direction\":\"POSITIVE\"," +
                "\"headline\":\"OO전자 2조원 수주\",\"summary\":\"대형 공급계약 체결\"}");

        assertThat(p).isNotNull();
        assertThat(p.type()).isEqualTo(CatalystType.ORDER_WIN);
        assertThat(p.direction()).isEqualTo(Direction.POSITIVE);
        assertThat(p.headline()).isEqualTo("OO전자 2조원 수주");
        assertThat(p.summary()).isEqualTo("대형 공급계약 체결");
    }

    @Test
    @DisplayName("```json 펜스 + 설명 문구 섞인 응답 → 첫 { } 블록 추출")
    void parse_fencedJson() {
        ParsedCatalyst p = StockCatalystService.parseCatalystResponse(
                "분류 결과입니다:\n```json\n{\"type\":\"EARNINGS\",\"direction\":\"NEGATIVE\"," +
                "\"headline\":\"실적 쇼크\",\"summary\":\"영업이익 컨센 하회\"}\n```");

        assertThat(p).isNotNull();
        assertThat(p.type()).isEqualTo(CatalystType.EARNINGS);
        assertThat(p.direction()).isEqualTo(Direction.NEGATIVE);
    }

    @Test
    @DisplayName("type=NONE 이면 direction 도 NONE 으로 강제 (집계 일관성)")
    void parse_noneForcesDirectionNone() {
        ParsedCatalyst p = StockCatalystService.parseCatalystResponse(
                "{\"type\":\"NONE\",\"direction\":\"NEUTRAL\",\"headline\":\"\",\"summary\":\"\"}");

        assertThat(p).isNotNull();
        assertThat(p.type()).isEqualTo(CatalystType.NONE);
        assertThat(p.direction()).isEqualTo(Direction.NONE);
        assertThat(p.headline()).isNull();   // 빈 문자열 → null 정규화
    }

    @Test
    @DisplayName("enum 어휘 밖 type / JSON 아님 / null → null (캐시 안 함)")
    void parse_invalidInputs() {
        assertThat(StockCatalystService.parseCatalystResponse(
                "{\"type\":\"MOON_SHOT\",\"direction\":\"POSITIVE\"}")).isNull();
        assertThat(StockCatalystService.parseCatalystResponse("재료가 없습니다.")).isNull();
        assertThat(StockCatalystService.parseCatalystResponse(null)).isNull();
        assertThat(StockCatalystService.parseCatalystResponse("")).isNull();
    }

    @Test
    @DisplayName("프롬프트 — 종목명 + 뉴스 제목 최대 5건 + JSON 형식 지시 포함")
    void buildPrompt_containsTitlesAndFormat() {
        List<NewsItem> news = List.of(
                NewsItem.builder().title("뉴스1").build(),
                NewsItem.builder().title("뉴스2").build(),
                NewsItem.builder().title("뉴스3").build(),
                NewsItem.builder().title("뉴스4").build(),
                NewsItem.builder().title("뉴스5").build(),
                NewsItem.builder().title("뉴스6 (제외)").build());

        String prompt = StockCatalystService.buildPrompt("삼성전자", news);

        assertThat(prompt).contains("삼성전자").contains("뉴스1").contains("뉴스5");
        assertThat(prompt).doesNotContain("뉴스6");
        assertThat(prompt).contains("ORDER_WIN").contains("\"direction\"");
    }
}
