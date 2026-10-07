package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.RiskAnalysisDto.DartDisclosure;
import com.myplatform.backend.dto.RecentDisclosuresDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DART 공시 목록(list.json) 해석 — 오류 상태는 '공시 없음'이 아니고, 건수는 DART 가 알려준 전체 건수(2026-10-07 화면 점검).
 *
 * <p>재현 ①: 삼성전자 종목 상세가 '최근 공시 (DART) — 3개월 100건 · 외 85건'이라 했다 — 첫 페이지(page_count=100)만 받아 그
 * 개수를 셌다. DART 는 응답에 total_count 를 준다.
 * <p>재현 ②: 상태가 000(정상)·013(조회된 데이터 없음)이 아니어도(020 요청 제한 초과·010 미등록 키 등) 빈 목록을 돌려 '조회
 * 성공, 공시 없음'으로 읽혔다 — 위험 체크(quickDangerCheck)가 그걸 '안전'으로 1시간 캐시한다(§4c 실패≠안전).
 */
class DartDisclosurePageTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static String okBody(int totalCount, int rows) {
        StringBuilder sb = new StringBuilder("{\"status\":\"000\",\"message\":\"정상\",\"page_no\":1,\"page_count\":100,")
                .append("\"total_count\":").append(totalCount).append(",\"total_page\":").append((totalCount + 99) / 100)
                .append(",\"list\":[");
        for (int i = 0; i < rows; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"corp_name\":\"삼성전자\",\"report_nm\":\"임원ㆍ주요주주특정증권등소유상황보고서\",")
              .append("\"rcept_no\":\"2026100100").append(String.format("%04d", i)).append("\",\"rcept_dt\":\"20261001\",")
              .append("\"flr_nm\":\"홍길동\",\"rm\":\"\"}");
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("재현 ①: 첫 페이지 100행이어도 건수는 DART 의 total_count(185)")
    void totalCountFromDart() {
        DartService.DisclosurePage page = DartService.parseDisclosurePage(OM, okBody(185, 100));
        assertThat(page).isNotNull();
        assertThat(page.items()).hasSize(100);
        assertThat(page.totalCount()).isEqualTo(185);
        assertThat(page.items().get(0).getReportNm()).isEqualTo("임원ㆍ주요주주특정증권등소유상황보고서");
    }

    @Test
    @DisplayName("재현 ②: 오류 상태(020·010·800)는 모름(null) — 013(조회된 데이터 없음)만 진짜 '공시 없음'")
    void errorStatusIsUnknown() {
        assertThat(DartService.parseDisclosurePage(OM, "{\"status\":\"020\",\"message\":\"요청 제한을 초과하였습니다.\"}")).isNull();
        assertThat(DartService.parseDisclosurePage(OM, "{\"status\":\"010\",\"message\":\"등록되지 않은 키입니다.\"}")).isNull();
        assertThat(DartService.parseDisclosurePage(OM, "{\"status\":\"800\",\"message\":\"시스템 점검\"}")).isNull();
        assertThat(DartService.parseDisclosurePage(OM, "not json")).isNull();

        DartService.DisclosurePage none = DartService.parseDisclosurePage(OM, "{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}");
        assertThat(none).isNotNull();
        assertThat(none.items()).isEmpty();
        assertThat(none.totalCount()).isZero();
    }

    @Test
    @DisplayName("total_count 가 없으면 받은 행 수")
    void missingTotalCountFallsBackToRows() {
        String body = okBody(3, 3).replace("\"total_count\":3,", "");
        assertThat(DartService.parseDisclosurePage(OM, body).totalCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("최근 공시 화면 — 전체 건수와 받은 건수를 따로 준다(위험 키워드는 받은 건수만큼만 확인)")
    void recentDisclosuresCarryBothCounts() {
        List<DartDisclosure> raw = new ArrayList<>(DartService.parseDisclosurePage(OM, okBody(185, 100)).items());
        RecentDisclosuresDto dto = RecentDisclosureService.assemble("005930", raw, 185);
        assertThat(dto.getTotalCount()).isEqualTo(185);
        assertThat(dto.getFetchedCount()).isEqualTo(100);
        assertThat(dto.getItems()).hasSize(RecentDisclosureService.MAX_ITEMS);
    }
}
