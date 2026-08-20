package com.myplatform.backend.service;

import com.myplatform.backend.dto.RecentDisclosuresDto;
import com.myplatform.backend.dto.RiskAnalysisDto.DartDisclosure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 최근 공시 목록 조립 (2026-08-20) — 순수 함수 테스트.
 *
 * §4c 핵심: 조회 실패(null)는 dataAvailable=false — '공시 없음(빈 목록)'과 구분.
 * 뷰어 URL 은 접수번호가 숫자일 때만(오염 방지), 상한 컷은 totalCount 로 가시화.
 */
class RecentDisclosureServiceTest {

    private static DartDisclosure disc(String reportNm, String rceptNo, String rceptDt) {
        return DartDisclosure.builder().reportNm(reportNm).rceptNo(rceptNo).rceptDt(rceptDt).build();
    }

    @Test
    @DisplayName("assemble — null(미확인) → dataAvailable=false + 빈 목록 ('공시 없음'과 구분, §4c)")
    void assemble_nullMeansUnavailable() {
        RecentDisclosuresDto dto = RecentDisclosureService.assemble("005930", null);

        assertThat(dto.isDataAvailable()).isFalse();
        assertThat(dto.getItems()).isEmpty();
        assertThat(dto.getTotalCount()).isZero();
    }

    @Test
    @DisplayName("assemble — 빈 목록(조회 성공) → dataAvailable=true + 0건 (진짜 '공시 없음')")
    void assemble_emptyMeansNoDisclosures() {
        RecentDisclosuresDto dto = RecentDisclosureService.assemble("005930", List.of());

        assertThat(dto.isDataAvailable()).isTrue();
        assertThat(dto.getItems()).isEmpty();
    }

    @Test
    @DisplayName("assemble — 접수일 내림차순 정렬 + 날짜 포맷 + 뷰어 URL + 위험 표시")
    void assemble_sortsAndMaps() {
        DartDisclosure danger = disc("상장폐지 사유 발생", "20260819000002", "20260819");
        danger.setDangerous(true);
        danger.setMatchedKeyword("상장폐지");
        List<DartDisclosure> raw = new ArrayList<>(List.of(
                disc("단일판매ㆍ공급계약체결", "20260801000001", "20260801"),
                danger,
                disc("주요사항보고서", "20260810000003", "20260810")));

        RecentDisclosuresDto dto = RecentDisclosureService.assemble("005930", raw);

        assertThat(dto.isDataAvailable()).isTrue();
        assertThat(dto.getItems()).extracting(RecentDisclosuresDto.Item::getRceptDt)
                .containsExactly("2026-08-19", "2026-08-10", "2026-08-01");   // 최신순
        RecentDisclosuresDto.Item first = dto.getItems().get(0);
        assertThat(first.getViewerUrl()).isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260819000002");
        assertThat(first.isDangerous()).isTrue();
        assertThat(first.getMatchedKeyword()).isEqualTo("상장폐지");
        assertThat(dto.getItems().get(1).isDangerous()).isFalse();
        assertThat(dto.getItems().get(1).getMatchedKeyword()).isNull();
    }

    @Test
    @DisplayName("assemble — 상한 15 컷 + totalCount 는 전체 수 (조용한 절단 금지)")
    void assemble_capsWithTotalCount() {
        List<DartDisclosure> raw = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> disc("공시" + i, null, String.format("202608%02d", i)))
                .toList();

        RecentDisclosuresDto dto = RecentDisclosureService.assemble("005930", raw);

        assertThat(dto.getItems()).hasSize(15);
        assertThat(dto.getTotalCount()).isEqualTo(20);
    }

    @Test
    @DisplayName("assemble — reportNm 없는 행 제외, rceptDt null 은 뒤로")
    void assemble_skipsBlankAndNullDatesLast() {
        List<DartDisclosure> raw = new ArrayList<>();
        raw.add(disc(null, "1", "20260810"));
        raw.add(disc("  ", "2", "20260810"));
        raw.add(disc("날짜없음", "3", null));
        raw.add(disc("정상", "4", "20260810"));

        RecentDisclosuresDto dto = RecentDisclosureService.assemble("005930", raw);

        assertThat(dto.getItems()).extracting(RecentDisclosuresDto.Item::getReportNm)
                .containsExactly("정상", "날짜없음");
    }

    @Test
    @DisplayName("viewerUrl — 숫자 접수번호만 URL 생성, 그 외 null (링크 생략)")
    void viewerUrl_digitsOnly() {
        assertThat(RecentDisclosureService.viewerUrl("20260819000002"))
                .isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260819000002");
        assertThat(RecentDisclosureService.viewerUrl(" 20260819000002 ")).isNotNull();   // trim 허용
        assertThat(RecentDisclosureService.viewerUrl(null)).isNull();
        assertThat(RecentDisclosureService.viewerUrl("")).isNull();
        assertThat(RecentDisclosureService.viewerUrl("abc123\"onclick")).isNull();   // 비숫자 → 링크 생략
    }

    @Test
    @DisplayName("formatRceptDt — yyyyMMdd 만 변환, 그 외 raw 유지(위장 금지 §4c)")
    void formatRceptDt_strict() {
        assertThat(RecentDisclosureService.formatRceptDt("20260819")).isEqualTo("2026-08-19");
        assertThat(RecentDisclosureService.formatRceptDt("2026-08-19")).isEqualTo("2026-08-19");
        assertThat(RecentDisclosureService.formatRceptDt("이상값")).isEqualTo("이상값");
        assertThat(RecentDisclosureService.formatRceptDt(null)).isNull();
    }
}
