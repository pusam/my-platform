package com.myplatform.backend.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재무 테이블의 '종목별 최신 행' 조회는 미래 날짜 행을 거른다(2026-10-07).
 *
 * <p>{@code stock_financial_data} 에는 은퇴한 네이버 크롤이 남긴 추정치 행(report_date=2026-12-31, market_cap NULL, 342개 —
 * 삼성전자 매출 7,324,732억)이 있다. 최신 행을 가드 없이 집으면 그 행을 실적으로 읽는다 — 9/21 삼성전자 EPS 성장률 315%,
 * 10/1 스크리너 251종목 탈락이 그 함정이었다. 가드 없이 최신 행을 집던 조회 8개는 호출처가 하나도 없어 지웠다.
 * 같은 모양이 다시 생기지 않게 이 저장소의 조회 전체를 본다(이 테이블을 읽는 쿼리는 이 저장소에만 있다):
 * <ul>
 *   <li>최신 행을 고르는 {@code @Query}({@code MAX(report_date)} · {@code ORDER BY report_date DESC} + LIMIT/ROW_NUMBER)는
 *       {@code report_date <= CURDATE()/CURRENT_DATE} 를 건다.</li>
 *   <li>최신 1건만 돌려주는 파생 조회({@code findTop/FirstBy…OrderByReportDateDesc} → 단건)는 두지 않는다 — 1건만 받으면
 *       호출자가 미래 행을 걸러낼 수 없다. 여러 건을 받는 조회는 소비처가 {@code FinancialRowSynthesizer.excludeFutureDated} 로 거른다.</li>
 * </ul>
 */
class StockFinancialLatestRowGuardTest {

    private static final Pattern LATEST_BY_MAX =
            Pattern.compile("(?i)MAX\\(\\s*(\\w+\\.)?(report_date|reportDate)\\s*\\)");
    private static final Pattern ORDER_BY_DATE_DESC =
            Pattern.compile("(?i)ORDER BY\\s+(\\w+\\.)?(report_date|reportDate)\\s+DESC");
    private static final Pattern LIMITED = Pattern.compile("(?i)(\\bLIMIT\\b|ROW_NUMBER\\s*\\()");
    private static final Pattern FUTURE_GUARD =
            Pattern.compile("(?i)(report_date|reportDate)\\s*<=\\s*(CURDATE\\(\\)|CURRENT_DATE)");
    private static final Pattern SINGLE_LATEST_DERIVED =
            Pattern.compile("^(find|get|read|query)(Top|First)\\d*By\\w*OrderByReportDateDesc$");

    @Test
    @DisplayName("최신 행을 고르는 @Query 는 모두 미래 날짜를 거른다")
    void latestRowQueriesGuardFutureRows() {
        List<String> unguarded = new ArrayList<>();
        for (Method m : StockFinancialDataRepository.class.getDeclaredMethods()) {
            Query q = m.getAnnotation(Query.class);
            if (q == null) continue;
            String sql = q.value();
            boolean picksLatest = LATEST_BY_MAX.matcher(sql).find()
                    || (ORDER_BY_DATE_DESC.matcher(sql).find() && LIMITED.matcher(sql).find());
            if (picksLatest && !FUTURE_GUARD.matcher(sql).find()) unguarded.add(m.getName());
        }
        assertThat(unguarded).isEmpty();
    }

    @Test
    @DisplayName("최신 1건만 주는 파생 조회는 두지 않는다 — 단건이면 호출자가 미래 행을 거를 수 없다")
    void noSingleRowLatestDerivedQuery() {
        List<String> found = new ArrayList<>();
        for (Method m : StockFinancialDataRepository.class.getDeclaredMethods()) {
            if (m.getAnnotation(Query.class) != null) continue;
            if (SINGLE_LATEST_DERIVED.matcher(m.getName()).matches()
                    && !Collection.class.isAssignableFrom(m.getReturnType())) {
                found.add(m.getName());
            }
        }
        assertThat(found).isEmpty();
    }
}
