package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockConclusionDto;
import com.myplatform.backend.dto.StockConclusionDto.Factor;
import com.myplatform.backend.dto.StockConclusionDto.Level;
import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 종목별 룰 기반 결론 한 줄 산출.
 *
 * 입력: RecommendationSnapshot (종목별 카테고리 점수 5종 — earnings / supplyDemand / technical /
 *       sectorMomentum / valueStability + totalScore).
 *
 * 출력: 4-level 결론 + 한 줄 헤드라인 + factor 목록.
 *
 * 룰 (우선순위 순):
 *  1) total ≥ 75 + AI 양수: STRONG_BUY — "단기 모멘텀 + 다수 시그널 합의"
 *  2) value ≥ 12 + total < 55: HOLD — "장기 저평가 우량주이나 단기 추세 약함, 분할 매수 후보"
 *  3) supplyDemand ≥ 15 + technical < 8: BUY (caution) — "수급 강하나 기술적 신호 부족, 추격 신중"
 *  4) total ≥ 55: BUY — "매수 신호 양호"
 *  5) total < 55: WAIT — "현재 진입 신호 약함"
 *
 * Phase 5 범위: RecommendationSnapshot 만 입력. Phase 7 에서 AI 점수 / 수급 급증 / 복합 신호 추가.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StockConclusionService {

    private final RecommendationSnapshotRepository snapshotRepository;

    // 결론 임계값 — RecommendationService 상수와 동기화 필요.
    private static final int STRONG_BUY_THRESHOLD = 75;
    private static final int BUY_THRESHOLD = 55;
    private static final int VALUE_STRONG_THRESHOLD = 12;       // calculateValueTop10 의 cap 20점 기준
    private static final int SUPPLY_DEMAND_STRONG = 15;
    private static final int TECHNICAL_WEAK = 8;

    public StockConclusionDto getConclusion(String stockCode) {
        Optional<RecommendationSnapshot> snapshotOpt = snapshotRepository.findLatestByStockCode(stockCode);
        if (snapshotOpt.isEmpty()) {
            return notAvailable(stockCode);
        }
        return build(snapshotOpt.get());
    }

    private StockConclusionDto notAvailable(String stockCode) {
        return StockConclusionDto.builder()
                .stockCode(stockCode)
                .stockName("")
                .level(Level.WAIT)
                .headline("종합 추천 스냅샷에 포함되지 않은 종목입니다 — 시그널 정보 부족.")
                .guidance("관심종목으로 등록하면 다음 스냅샷부터 시그널이 누적됩니다.")
                .factors(List.of())
                .dataAt(null)
                .dataAvailable(false)
                .build();
    }

    private StockConclusionDto build(RecommendationSnapshot s) {
        int total = s.getTotalScore();
        int value = s.getValueStability();
        int supplyDemand = s.getSupplyDemand();
        int technical = s.getTechnical();

        List<Factor> factors = buildFactors(s);

        // 룰 우선순위
        Level level;
        String headline;
        String guidance;

        if (total >= STRONG_BUY_THRESHOLD) {
            level = Level.STRONG_BUY;
            headline = "단기 모멘텀 + 다수 시그널 합의 — 매수 적기로 평가.";
            guidance = value >= VALUE_STRONG_THRESHOLD
                    ? "장기 가치도 양호 — 전량 진입 고려."
                    : "단기 추세 강하지만 가치 점수는 보통 — 익절 가까이 잡고 진입.";
        } else if (value >= VALUE_STRONG_THRESHOLD && total < BUY_THRESHOLD) {
            level = Level.HOLD;
            headline = "장기 저평가 우량주이나 단기 추세 약함 — 분할 매수 후보.";
            guidance = "외국인/기관 순매수 전환 또는 20일선 지지 확인 후 진입 권장.";
        } else if (supplyDemand >= SUPPLY_DEMAND_STRONG && technical < TECHNICAL_WEAK) {
            level = Level.BUY;
            headline = "수급은 강하나 기술적 신호 부족 — 추격 매수 신중.";
            guidance = "단기 눌림목 또는 RSI 조정 시 분할 진입.";
        } else if (total >= BUY_THRESHOLD) {
            level = Level.BUY;
            headline = "매수 신호 양호 — 다수 카테고리 점수 충족.";
            guidance = "리스크 카드(공시/공매도) 확인 후 진입.";
        } else {
            level = Level.WAIT;
            headline = "현재 진입 신호 약함 — 관망 권장.";
            guidance = "수급/기술 신호 회복 또는 가치 점수 상승 대기.";
        }

        return StockConclusionDto.builder()
                .stockCode(s.getStockCode())
                .stockName(s.getStockName())
                .level(level)
                .headline(headline)
                .guidance(guidance)
                .conflictNote(detectConflicts(s))
                .factors(factors)
                .dataAt(s.getSnapshotAt())
                .dataAvailable(true)
                .build();
    }

    /**
     * 시그널 간 충돌 / 주의 사항 감지 (phase 22b).
     *
     * 4-level 헤드라인이 단일 결론을 주는 반면, 이 메서드는 카테고리 간 충돌이나 특이 조합을
     * 따로 짚어준다. 사용자가 "왜 BUY 인데 익절 짧게?" 같은 의문에 답이 된다.
     *
     * 룰 (첫 매칭만 반환 — 가장 중요한 충돌 1개):
     *  1) 단기 강 + 장기 매우 약 → 익절 짧게
     *  2) 단기 강 + 기술 약 → 고점 추격 가능성
     *  3) 장기+수급 동조 + 단기 차트 약 → 분할 매수 / 눌림목 대기
     *  4) 실적 강 + 시장 관심 없음 → 매집 후보
     *  5) 섹터 강 + 종목 차트 약 → 섹터 ETF 대안
     *  6) 모든 카테고리 평범 → 더 매력적 후보 우선
     */
    private String detectConflicts(RecommendationSnapshot s) {
        int total = s.getTotalScore();
        int earnings = s.getEarnings();
        int supplyDemand = s.getSupplyDemand();
        int technical = s.getTechnical();
        int sector = s.getSectorMomentum();
        int value = s.getValueStability();

        // 1. 단기 강 + 장기 매우 약 — 펀더멘털 받쳐주지 않는 모멘텀 진입은 익절 짧게.
        if (total >= STRONG_BUY_THRESHOLD && value >= 0 && value < 4) {
            return "⚠️ 단기 모멘텀 강함 + 장기 가치 매우 낮음 — 익절 3% 내, 손절 타이트.";
        }
        // 2. 단기 강 + 기술 약 — 거래량/모멘텀은 좋은데 차트 기준이 안 받쳐줌 → 고점 추격 위험.
        if (total >= STRONG_BUY_THRESHOLD && technical > 0 && technical < 6) {
            return "⚠️ 종합 점수 높으나 기술적 지표 약함 — 고점 추격 가능성, RSI 과열 확인 권장.";
        }
        // 3. 장기+수급 동조 + 단기 차트 약 — 매집 진행 중이나 기술적 진입 타이밍 미성숙.
        if (value >= VALUE_STRONG_THRESHOLD && supplyDemand >= 12 && technical > 0 && technical < 8) {
            return "💡 장기 저평가 + 수급 강 + 단기 차트 약함 — 분할 매수 또는 20일선 지지 대기.";
        }
        // 4. 실적 강 + 시장 관심 없음 — 컨센서스 형성 전 매집 기회.
        if (earnings >= 15 && total < BUY_THRESHOLD) {
            return "💡 실적 우수하나 시장 관심 부족 — 컨센서스 형성 전 매집 후보.";
        }
        // 5. 섹터 강 + 종목 차트 약 — 종목보다 섹터 ETF 가 더 효율적일 수 있음.
        if (sector >= 15 && technical > 0 && technical < 6) {
            return "💡 섹터 흐름 강하나 종목 차트 약함 — 섹터 ETF 대안 고려.";
        }
        // 6. 모든 카테고리 평범 (각각 6~10점) — 뚜렷한 강점 없는 평균 종목.
        if (allMidRange(earnings, supplyDemand, technical, sector)) {
            return "⚪ 모든 카테고리 평범 — 뚜렷한 강점 없음, 더 매력적인 후보 우선 검토.";
        }
        return null;
    }

    private boolean allMidRange(int... scores) {
        for (int s : scores) {
            if (s < 6 || s > 10) return false;
        }
        return true;
    }

    private List<Factor> buildFactors(RecommendationSnapshot s) {
        List<Factor> list = new ArrayList<>();
        list.add(Factor.builder()
                .key("total")
                .label("종합 점수")
                .dimension("MID")
                .score(s.getTotalScore())
                .verdict(verdictFor(s.getTotalScore(), BUY_THRESHOLD, STRONG_BUY_THRESHOLD))
                .note("실적·수급·기술·섹터·가치 5카테고리 합산 (100 만점)")
                .build());
        list.add(Factor.builder()
                .key("earnings")
                .label("실적")
                .dimension("LONG")
                .score(s.getEarnings())
                .verdict(verdictFor(s.getEarnings(), 8, 15))
                .note("어닝 서프라이즈 + 매출/영업이익 추세")
                .build());
        list.add(Factor.builder()
                .key("supplyDemand")
                .label("수급")
                .dimension("SHORT")
                .score(s.getSupplyDemand())
                .verdict(verdictFor(s.getSupplyDemand(), 8, SUPPLY_DEMAND_STRONG))
                .note("외국인/기관 순매수 추세")
                .build());
        list.add(Factor.builder()
                .key("technical")
                .label("기술적")
                .dimension("SHORT")
                .score(s.getTechnical())
                .verdict(verdictFor(s.getTechnical(), TECHNICAL_WEAK, 15))
                .note("RSI / 이동평균선 / 모멘텀")
                .build());
        list.add(Factor.builder()
                .key("sectorMomentum")
                .label("섹터 흐름")
                .dimension("SHORT")
                .score(s.getSectorMomentum())
                .verdict(verdictFor(s.getSectorMomentum(), 8, 15))
                .note("섹터 거래대금 INFLOW/OUTFLOW")
                .build());
        list.add(Factor.builder()
                .key("valueStability")
                .label("장기 가치")
                .dimension("LONG")
                .score(s.getValueStability())
                .verdict(verdictFor(s.getValueStability(), 8, VALUE_STRONG_THRESHOLD))
                .note("PBR / ROE / 부채비율 / 영업흑자")
                .build());
        return list;
    }

    private String verdictFor(int score, int neutralMin, int positiveMin) {
        if (score >= positiveMin) return "POSITIVE";
        if (score >= neutralMin) return "NEUTRAL";
        return "NEGATIVE";
    }
}
