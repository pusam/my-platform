package com.myplatform.backend.util;

import com.myplatform.backend.util.ChartNarrativeBuilder.Narrative;
import com.myplatform.backend.util.ChartNarrativeBuilder.Section;
import com.myplatform.backend.util.ChartNarrativeBuilder.Verdict;
import com.myplatform.backend.util.PullbackEntryCalculator.Metrics;
import com.myplatform.backend.util.PullbackEntryCalculator.OverheadSupply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 차트 해설 문장 생성 검증 — 관찰용 해설(매수 신호 아님) 전제의 문장/판정 정확성만 본다.
 *
 * <p>결측 규약(§4c): 근거 없는 섹션은 조용히 생략, 지표 자체가 없으면 판단보류.
 * 판정 임계는 잠정값이라 "숫자"가 아니라 <b>어떤 조건에서 결론이 갈리는가</b>를 고정한다.
 */
class ChartNarrativeBuilderTest {

    private static String allText(Narrative n) {
        StringBuilder sb = new StringBuilder();
        for (Section s : n.sections()) {
            sb.append(s.title()).append('\n');
            s.lines().forEach(l -> sb.append(l).append('\n'));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("지표 없음 → 판단보류. 그럴듯한 결론을 만들지 않는다")
    void noMetrics_unknown() {
        Narrative n = ChartNarrativeBuilder.build(null, null, null);
        assertThat(n.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(n.sections()).isEmpty();
        assertThat(n.verdictReason()).contains("충분하지 않");
    }

    @Test
    @DisplayName("하단 미터치 + 첫 반등 → 관망, 근거 문장에 그 이유가 그대로 드러난다")
    void noTouch_firstBounce_wait() {
        Metrics m = new Metrics(0.42, null, 1, null);
        Narrative n = ChartNarrativeBuilder.build(m, null, -3.1);

        assertThat(n.verdict()).isEqualTo(Verdict.WAIT);
        assertThat(n.verdictReason()).contains("하단 터치 없이 첫 반등");

        String text = allText(n);
        assertThat(text).contains("하단을 터치한 적이 없습니다");
        assertThat(text).contains("첫 반등");
        assertThat(text).contains("-3.1%");
    }

    @Test
    @DisplayName("하단 받아낸 뒤 2차 반등 → 조건부 관심")
    void touched_secondBounce_watch() {
        Metrics m = new Metrics(0.55, 4, 2, null);
        Narrative n = ChartNarrativeBuilder.build(m, null, 1.2);

        assertThat(n.verdict()).isEqualTo(Verdict.WATCH);
        assertThat(allText(n)).contains("4봉 전에 하단을 터치하고 올라온 자리");
        assertThat(allText(n)).contains("2번째 반등");
    }

    @Test
    @DisplayName("밴드 상단 이탈 → 과열 경계 (다른 조건보다 우선)")
    void aboveUpperBand_overheated() {
        Metrics m = new Metrics(1.15, 3, 2, null);
        Narrative n = ChartNarrativeBuilder.build(m, null, 8.0);

        assertThat(n.verdict()).isEqualTo(Verdict.OVERHEATED);
        assertThat(n.verdictReason()).contains("추격");
        assertThat(allText(n)).contains("볼린저 상단을 이탈");
    }

    @Test
    @DisplayName("장대음봉 쿨다운 중이면 2차 반등이어도 관망")
    void bigBearCooldown_overridesWatch() {
        Metrics m = new Metrics(0.5, 3, 2, 1);          // 1봉 전 장대음봉 = 쿨다운(3봉) 내
        Narrative n = ChartNarrativeBuilder.build(m, null, 0.0);

        assertThat(n.verdict()).isEqualTo(Verdict.WAIT);
        assertThat(n.verdictReason()).contains("장대음봉");
        assertThat(allText(n)).contains("1봉 전에 꽉 찬 장대음봉");
    }

    @Test
    @DisplayName("장대음봉 없으면 '최근 캔들' 섹션 자체를 만들지 않는다")
    void noBigBear_sectionOmitted() {
        Narrative n = ChartNarrativeBuilder.build(new Metrics(0.5, 3, 2, null), null, null);
        assertThat(n.sections()).extracting(Section::title).doesNotContain("최근 캔들");
    }

    @Test
    @DisplayName("매물벽 없으면 '위쪽 저항' 섹션 생략 — 벽 없음을 0으로 위장하지 않는다")
    void noOverhead_sectionOmitted() {
        Narrative n = ChartNarrativeBuilder.build(new Metrics(0.5, 3, 2, null), null, null);
        assertThat(n.sections()).extracting(Section::title).doesNotContain("위쪽 저항");
    }

    @Test
    @DisplayName("가깝고 두꺼운 매물벽 → 통과 어렵다는 문장 추가")
    void nearThickWall_warned() {
        OverheadSupply os = new OverheadSupply(3.2, 18);
        Narrative n = ChartNarrativeBuilder.build(new Metrics(0.5, 3, 2, null), os, null);

        String text = allText(n);
        assertThat(text).contains("+3.2% 지점에 매물벽");
        assertThat(text).contains("전체 거래의 18%");
        assertThat(text).contains("한 번에 통과하기 어려운");
    }

    @Test
    @DisplayName("먼 매물벽 → 당장 부딪히지 않는다고만 말한다")
    void farWall_notBlocking() {
        OverheadSupply os = new OverheadSupply(14.0, 25);
        Narrative n = ChartNarrativeBuilder.build(new Metrics(0.5, 3, 2, null), os, null);
        assertThat(allText(n)).contains("당장 부딪히는 거리는 아닙니다");
    }

    @Test
    @DisplayName("가깝지만 얇은 벽도 해석 문장을 붙인다 — 사실만 던지고 비워두지 않는다")
    void nearThinWall_stillExplained() {
        // 실제 화면 케이스: +0.5% 거리 · 두께 5% → 어느 분기에도 안 걸려 설명이 비어 있었음
        Narrative n = ChartNarrativeBuilder.build(
                new Metrics(0.51, 1, 1, 3), new OverheadSupply(2.0, 5), 0.3);
        String text = allText(n);

        assertThat(text).contains("가깝지만 두께가 크지 않아");
    }

    @Test
    @DisplayName("장대음봉 쿨다운이 끝났으면 '지난 시점'이라고 말해준다")
    void bigBearCooldownPassed_stated() {
        Narrative n = ChartNarrativeBuilder.build(new Metrics(0.51, 1, 1, 3), null, 0.3);
        assertThat(allText(n)).contains("거래일은 지난 시점입니다");
    }

    @Test
    @DisplayName("한참 위(+55%)의 벽은 지금 자리의 저항이 아니므로 언급하지 않는다")
    void veryFarWall_sectionOmitted() {
        // 실제 삼성전기 케이스 — 20일선 -16.8% 로 빠져 가까운 구간이 전부 얇을 때
        OverheadSupply os = new OverheadSupply(55.6, 10);
        Narrative n = ChartNarrativeBuilder.build(new Metrics(0.27, 1, 14, 5), os, -16.8);
        assertThat(n.sections()).extracting(Section::title).doesNotContain("위쪽 저항");
    }

    @Test
    @DisplayName("이격도 없으면 그 문장만 빠지고 나머지는 그대로")
    void nullDisparity_lineOmitted() {
        Narrative n = ChartNarrativeBuilder.build(new Metrics(0.5, 3, 2, null), null, null);
        assertThat(allText(n)).doesNotContain("이격");
        assertThat(n.sections()).extracting(Section::title).contains("지금 위치", "반등의 성격");
    }

    @Test
    @DisplayName("결론 어휘에 '매수'를 쓰지 않는다 — 거르는 관점만 말한다")
    void verdictNeverSaysBuy() {
        for (Verdict v : Verdict.values()) {
            assertThat(v.label()).doesNotContain("매수");
        }
    }

    @Test
    @DisplayName("하단 미터치 + 2차 반등 → 관망이고, 근거도 관망 사유여야 한다 (결론↔근거 불일치 회귀)")
    void noTouch_secondBounce_reasonMatchesVerdict() {
        // 실제 카카오(035720) 케이스: %B 0.87 · 하단 미터치 · 5번째 반등
        Metrics m = new Metrics(0.87, null, 5, null);
        Narrative n = ChartNarrativeBuilder.build(m, null, 3.8);

        assertThat(n.verdict()).isEqualTo(Verdict.WAIT);
        assertThat(n.verdictReason()).doesNotContain("지켜볼 만합니다");
        assertThat(n.verdictReason()).contains("하단");
    }

    @Test
    @DisplayName("반등이 3회 이상이면 숫자만 던지지 않고 '여러 차례'로 설명한다")
    void manyBounces_describedNotJustCounted() {
        // 실제 삼성전기(009150) 케이스: 14번째 반등
        Narrative n = ChartNarrativeBuilder.build(new Metrics(0.27, 1, 14, 5), null, -16.8);
        String text = allText(n);

        assertThat(text).contains("여러 차례");
        assertThat(text).doesNotContain("14번째 반등 구간입니다");
    }

    @Test
    @DisplayName("반등 미성립(하락 중) → 바닥 확인 단계라고 말한다")
    void stillFalling_reason() {
        Narrative n = ChartNarrativeBuilder.build(new Metrics(-0.2, 0, 0, null), null, -12.0);
        assertThat(n.verdict()).isEqualTo(Verdict.WAIT);
        assertThat(n.verdictReason()).contains("바닥을 확인");
        assertThat(allText(n)).contains("볼린저 하단 아래");
    }

    // ===== 자주 닿은 가격대 한 줄 (2026-11-13) =====

    private static final Metrics PLAIN = new Metrics(0.55, 4, 2, null);

    private static ChartNarrativeBuilder.PriceLevel level(double price, int touches, double distancePct) {
        return new ChartNarrativeBuilder.PriceLevel(price, touches, distancePct);
    }

    @Test
    @DisplayName("가까운 저항(여러 번 닿은 자리)이 있으면 '지금 위치'에 한 줄로 말한다")
    void nearResistance_oneLine() {
        Narrative n = ChartNarrativeBuilder.build(PLAIN, null, 1.2, "오늘 시세 기준", level(12300, 3, 1.2), null);

        assertThat(n.sections().get(0).title()).isEqualTo("지금 위치");
        assertThat(n.sections().get(0).lines())
                .contains("저항 12,300원 부근(3회 닿고 돌아선 자리)의 1.2% 아래에 있습니다(오늘 시세 기준).");
    }

    @Test
    @DisplayName("가까운 지지만 있으면 지지 위라고 말하고, 기준(종가 날짜)을 그대로 붙인다")
    void nearSupport_oneLine() {
        Narrative n = ChartNarrativeBuilder.build(PLAIN, null, 1.2, "11/12 종가 기준", null, level(11800, 2, -2.1));

        assertThat(allText(n)).contains("지지 11,800원 부근(2회 닿고 돌아선 자리)의 2.1% 위에 있습니다(11/12 종가 기준).");
    }

    @Test
    @DisplayName("위아래 둘 다 가까우면 한 줄에 '사이'로 묶는다")
    void betweenLevels_oneLine() {
        Narrative n = ChartNarrativeBuilder.build(PLAIN, null, 1.2, "오늘 시세 기준", level(12300, 3, 1.2), level(11800, 2, -2.1));

        String text = allText(n);
        assertThat(text).contains("지지 11,800원 부근(2회, 2.1% 아래)과 저항 12,300원 부근(3회, 1.2% 위) 사이에 있습니다(오늘 시세 기준).");
        assertThat(text.split("부근\\(", -1)).hasSize(3);   // 한 줄에 두 가격대만
    }

    @Test
    @DisplayName("가까운 가격대가 없으면 줄을 만들지 않고, 판정은 가격대와 무관하다")
    void noLevel_noLine_verdictUnchanged() {
        Narrative without = ChartNarrativeBuilder.build(PLAIN, null, 1.2);
        Narrative with = ChartNarrativeBuilder.build(PLAIN, null, 1.2, "오늘 시세 기준", level(12300, 3, 1.2), level(11800, 2, -2.1));

        assertThat(allText(without)).doesNotContain("닿고 돌아선").doesNotContain("사이에 있습니다");
        assertThat(with.verdict()).isEqualTo(without.verdict());
        assertThat(with.verdictReason()).isEqualTo(without.verdictReason());
    }

    @Test
    @DisplayName("가격대 고르기 — 한 번 닿은 자리·먼 자리는 빼고 남은 것 중 가장 가까운 것")
    void nearestLevel_filters() {
        var once = level(12100, 1, 0.5);        // 한 번 닿은 자리는 그냥 고점·저점이다
        var near = level(12300, 3, 1.2);
        var far = level(13000, 4, 6.0);         // 지금 자리의 가격대가 아니다

        assertThat(ChartNarrativeBuilder.nearestLevel(java.util.List.of(once, near, far))).isEqualTo(near);
        assertThat(ChartNarrativeBuilder.nearestLevel(java.util.List.of(once, far))).isNull();
        assertThat(ChartNarrativeBuilder.nearestLevel(java.util.List.of())).isNull();
        assertThat(ChartNarrativeBuilder.nearestLevel(null)).isNull();
    }

    @Test
    @DisplayName("가격대에 닿아 있으면(거리 0) '0.0% 위'라 하지 않고 닿아 있다고 말한다")
    void atLevel_saysTouching() {
        Narrative n = ChartNarrativeBuilder.build(PLAIN, null, 1.2, "오늘 시세 기준", null, level(12000, 2, 0.0));

        assertThat(allText(n)).contains("지지 12,000원 부근(2회 닿고 돌아선 자리)에 닿아 있습니다(오늘 시세 기준).");
    }
}
