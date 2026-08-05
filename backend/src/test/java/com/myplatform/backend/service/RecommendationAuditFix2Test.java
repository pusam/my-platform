package com.myplatform.backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-05 감사 2차 수정분 — 측정 오염 차단 + 명백한 버그 3건.
 *
 * <p>고치기 전에는 실패해야 하는 케이스들이다.
 */
class RecommendationAuditFix2Test {

    // ==================== ① 스냅샷: 컷 0건이면 어제 목록 재발행 금지 ====================

    /**
     * {@code saveSnapshotInternal} 이 0건일 때 {@code cachedTop5}(어제 목록)로 폴백해
     * <b>오늘 타임스탬프로 저장 + signal_outcome 에 오늘자 STRONG_BUY/BUY 기록</b>을 했다.
     * 존재하지 않은 시그널이 적중률 측정 테이블에 들어가고, 컷 0건은 보통 하락일이라 계통 편향이다.
     * (커밋 16a1589 는 조회 경로만 고쳤다.)
     */
    @Test
    void 정상계산인데_컷통과_0건이면_스냅샷을_저장하지_않는다() {
        assertThat(RecommendationService.shouldSkipSnapshotOnEmpty(true, 120))
                .as("scoreMap 이 충분한데 0건 = 진짜 '관망' — 어제 목록 재발행 금지").isTrue();
    }

    @Test
    void 입력데이터가_몰락했으면_기존_스냅샷을_유지한다() {
        // scoreMap 자체가 빈약하면 계산 실패 의심 — 빈 결과 발행이 아니라 기존 유지가 안전
        assertThat(RecommendationService.shouldSkipSnapshotOnEmpty(true, 3)).isFalse();
    }

    @Test
    void 결과가_있으면_당연히_저장한다() {
        assertThat(RecommendationService.shouldSkipSnapshotOnEmpty(false, 120)).isFalse();
        assertThat(RecommendationService.shouldSkipSnapshotOnEmpty(false, 3)).isFalse();
    }

    @Test
    void 스냅샷_판정은_조회경로와_같은_임계를_쓴다() {
        // 두 경로가 다른 임계를 쓰면 "화면은 관망인데 스냅샷엔 어제 목록" 같은 불일치가 생긴다
        assertThat(RecommendationService.shouldSkipSnapshotOnEmpty(true, 10))
                .isEqualTo(RecommendationService.shouldPublishEmptyResult(10));
        assertThat(RecommendationService.shouldSkipSnapshotOnEmpty(true, 9))
                .isEqualTo(RecommendationService.shouldPublishEmptyResult(9));
    }

    // ==================== ② tie-break: changeRate 결측을 "0% 상승"으로 보지 않는다 ====================

    /**
     * 3순위 tie-break 는 {@code changeRate asc}(덜 오른 종목 우선 = 추격 완화)인데, null 을 0.0 으로
     * 대체해 <b>등락률을 모르는 종목이 "0% 상승"으로 최상위</b>에 놓였다. changeRate 는 일부 진입
     * 경로에서만 채워지므로, 실제 +12% 급등주가 +0.8% 종목을 이기는 <b>의도 정반대</b> 결과가 났다.
     */
    @Test
    void 등락률_결측_종목이_덜오른_종목보다_우선되지_않는다() {
        StubScore unknown = score("000001", 72, null);       // 등락률 미상(실제 +12%일 수 있음)
        StubScore mild = score("000002", 72, 0.8);           // +0.8% 확인됨

        List<StubScore> sorted = sortByComparator(List.of(unknown, mild));

        assertThat(sorted.get(0).code).as("확인된 소폭 상승이 미상보다 앞").isEqualTo("000002");
    }

    @Test
    void 등락률이_모두_확인되면_덜_오른_종목이_우선이다() {
        StubScore hot = score("000001", 72, 9.0);
        StubScore calm = score("000002", 72, 0.5);
        assertThat(sortByComparator(List.of(hot, calm)).get(0).code).isEqualTo("000002");
    }

    @Test
    void 점수가_다르면_등락률과_무관하게_점수가_이긴다() {
        StubScore high = score("000001", 80, 9.0);
        StubScore low = score("000002", 60, 0.1);
        assertThat(sortByComparator(List.of(low, high)).get(0).code).isEqualTo("000001");
    }

    // ==================== ③ 리스크 공시 이중 계상 ====================

    /**
     * {@code applyRiskPenalty} 가 {@code valueStability −5} 와 {@code riskPenalty=5} 를 <b>둘 다</b>
     * 걸었다. valueStability 는 STRONG+VALUE 보너스 게이트(≥12)라 no-op 이 아니었고, 결과적으로
     * 공시 1건이 raw −5 와 보너스 −2 로 <b>이중 계상</b>됐다.
     * CLAUDE.md 가 명시한 "카테고리 표시값 불변" 규약과도 어긋난다.
     */
    @Test
    void 리스크_공시는_raw에서만_차감하고_표시값은_건드리지_않는다() {
        assertThat(RecommendationService.riskPenaltyTouchesValueStability())
                .as("valueStability 는 표시값이자 보너스 게이트 — 리스크 페널티가 건드리면 이중 계상")
                .isFalse();
    }

    // ==================== helper ====================

    /** comparator 테스트용 최소 스텁 — 실제 StockScore 를 쓰되 필요한 필드만 채운다. */
    private static class StubScore {
        final String code;
        final RecommendationService.StockScore inner;
        StubScore(String code, RecommendationService.StockScore inner) {
            this.code = code; this.inner = inner;
        }
    }

    private static StubScore score(String code, int normalizedTarget, Double changeRate) {
        RecommendationService.StockScore s = new RecommendationService.StockScore(code, "종목" + code);
        // normalizedTotal = raw*100/80 이므로 raw 를 역산해 4카테고리에 균등 배분
        int raw = normalizedTarget * 80 / 100;
        s.earnings = Math.min(20, raw / 4);
        s.supplyDemand = Math.min(20, raw / 4);
        s.technical = Math.min(20, raw / 4);
        s.sectorMomentum = Math.min(20, raw - 3 * (raw / 4));
        s.changeRate = changeRate == null ? null : BigDecimal.valueOf(changeRate);
        return new StubScore(code, s);
    }

    private static List<StubScore> sortByComparator(List<StubScore> input) {
        List<StubScore> copy = new ArrayList<>(input);
        var cmp = RecommendationService.recommendationComparator(Map.of());
        copy.sort((a, b) -> cmp.compare(a.inner, b.inner));
        return copy;
    }
}
