package com.myplatform.backend.service;

import com.myplatform.backend.dto.PaperTradingDto.PortfolioItemDto;
import com.myplatform.backend.entity.BotTradingPosition;
import com.myplatform.backend.entity.StockWatchlist;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import com.myplatform.backend.service.RecommendationService.RecommendationDto;
import com.myplatform.backend.service.RecommendationService.Top5Response;
import com.myplatform.backend.service.StockCatalystService.StockRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * 재료 일괄 워밍 (P2-CAT3 → 2026-07-07 관심/보유 확장) — 재료를 배치로 미리 분류해
 * 보드의 "—"(재료 미포착)를 채우고, <b>관심/보유 종목의 악재를 아침에 선제 포착</b>한다
 * (악재 조기경보 — 분류 저장 훅이 CatalystRiskAlertService 로 알림).
 *
 * <p>대상 = <b>관심종목(watchlist 전체 활성) &gt; 보유(봇 포지션 + KIS 실잔고) &gt; 발굴 5트랙</b>
 * 우선순위 병합·dedup, 전체 {@value #TOTAL_WARM_MAX}종목 컷. {@link StockCatalystService#classifyBatch}
 * (5씩 청킹·캐시히트 스킵·전역 rate 게이트) 위에 얹어 quota 안전(40종목 ≈ 최대 8 Gemini 콜).
 *
 * <p><b>"오늘 매수후보(getTop5)" 그룹은 넣었다가 뺐다(2026-08-20 추가 → 08-21 원복, 리뷰 C-3)</b>:
 * 08:00 에 장전 재계산은 일어나지 않는다 — 08:00 배치는 캐시 무효화뿐이고, 장전이라 백그라운드 계산도
 * 미트리거라 이 시각의 getTop5() 는 <b>전일 20:05 스냅샷</b>을 돌려준다. 즉 07:30 모닝브리핑 워밍과
 * 동일 집합(동일 소스·컷55·상한5)이라 실익이 0이고, merge 상한 40 에서 발굴 몫만 최대 5칸 잠식했다.
 * 오늘 후보 배지 공백의 진짜 해법은 <b>11:30 첫 스냅샷 이후 시점의 워밍</b>(신규 크론 필요 — quota·시각
 * 판단 선행, AUDIT R11)이다. 같은 이유로 <b>이 그룹을 08:00 워밍에 다시 넣지 말 것.</b>
 * §4b 상한·rate 게이트 준수, 재료 산식 미편입.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CatalystWarmingService {

    /** 발굴(union) 몫 상한 — 상위 25(≈5 Gemini 콜). 관심/보유가 커도 발굴 커버 유지용 서브캡. */
    static final int UNION_WARM_MAX = 25;
    /** 전체 워밍 상한 — 초과 시 관심 > 보유 > 발굴 우선(뒤가 잘림). 40종목 ≈ 최대 8 Gemini 콜. */
    static final int TOTAL_WARM_MAX = 40;

    private final RecommendationService recommendationService;
    private final StockCatalystService stockCatalystService;
    private final SchedulerLockService schedulerLockService;
    private final StockWatchlistRepository watchlistRepository;
    private final BotTradingPositionRepository botPositionRepository;
    // 실잔고는 KIS 미설정 환경(로컬 등)에서도 컨텍스트 로딩 가능하도록 ObjectProvider.
    private final ObjectProvider<RealTradeService> realTradeProvider;
    private final ObjectProvider<KoreaInvestmentService> kisProvider;

    @Value("${catalyst.union-warm.enabled:true}")
    private boolean unionWarmEnabled;

    /**
     * 08:00 주중 — momentum 워밍(07:30) 뒤, KRX 개장(09:00) 전 → 개장 시 보드/관심 재료 fresh.
     *
     * <p><b>장중 추가 워밍(12:30 등) 미도입 결정 근거(2026-07-07)</b>:
     * <ol>
     * <li><b>§4b 일캐시가 구조적으로 무효화</b> — 재료 분류는 (종목,일자) 1회 캐시라, 08:00에 분류된
     *     종목은 장중 재실행 시 전부 캐시 히트(Gemini 0콜)이고 <b>재분류 자체가 불가</b>. 장중 새로 뜬
     *     악재를 다시 잡지 못하므로 실익은 "아침에 미분류였던 종목(장애·신규 편입) 재시도"뿐.</li>
     * <li>quota 자체는 가능은 함 — 40종목 ≤8콜 ≈ 4.5s 직렬화 36초 점유, 기존 소비자(모닝브리핑 워밍
     *     07:30 ≤5콜 · AI전략 9/12/15시 · 상세 on-demand)와 겹쳐도 전역 RateLimiter(≈13RPM)가 큐잉.
     *     즉 병목이 아니라 <b>실익 부재</b>가 미도입 사유.</li>
     * <li>장중 악재 커버는 <b>DART 5분 모니터(공시) + 온디맨드 분류(상세/보드 열람 시)</b>가 담당.</li>
     * </ol>
     */
    @Scheduled(scheduler = "cacheScheduler", cron = "0 0 8 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledWarmUnion() {
        if (!unionWarmEnabled) return;
        // fail-open: 락 실패(Redis 장애)면 스킵, TTL(10분) < 크론(일)이라 중복 위험 없음. 단일 인스턴스 전제.
        if (!schedulerLockService.tryLock("catalyst.union-warm", Duration.ofMinutes(10))) {
            log.debug("[재료워밍] union 락 미획득 — 스킵");
            return;
        }
        warmUnionCatalysts();
    }

    /**
     * 재료 워밍 실행 — 크론/수동트리거(admin) 공용. 대상 = 관심 &gt; 보유 &gt; 발굴(§ 클래스 javadoc).
     * classifyBatch 가 캐시 히트 자동 스킵 + 5씩 청킹 + rate 게이트. @return 신규 분류 저장 종목 수.
     */
    public int warmUnionCatalysts() {
        List<StockRef> refs = collectWarmRefs();
        if (refs.isEmpty()) {
            log.info("[재료워밍] 대상 없음");
            return 0;
        }
        int warmed = stockCatalystService.classifyBatch(refs);   // 실제 Gemini 콜 수는 classifyBatch 총계 로그
        log.info("[재료워밍] 완료 — 대상 {}종목 → {}건 신규분류 (나머지 캐시히트/뉴스없음)", refs.size(), warmed);
        return warmed;
    }

    /** 관심(전체 활성) > 보유(봇 포지션+KIS 실잔고) > 발굴 병합, 전체 {@value #TOTAL_WARM_MAX} 컷. */
    List<StockRef> collectWarmRefs() {
        List<StockRef> watch = watchlistRefs();
        List<StockRef> held = heldRefs();
        List<StockRef> discover = collectUnionRefs();
        List<StockRef> merged = mergeWarmTargets(watch, held, discover, TOTAL_WARM_MAX);
        // 상한 컷 가시화(§ no silent caps) — 뭐가 잘렸는지 로그로 남긴다.
        log.info("[재료워밍] 대상 병합 — 관심 {} + 보유 {} + 발굴 {} → {}종목 (상한 {})",
                watch.size(), held.size(), discover.size(), merged.size(), TOTAL_WARM_MAX);
        return merged;
    }

    /**
     * 우선순위 병합 — <b>순수 함수(테스트 대상)</b>. 관심 &gt; 보유 &gt; 발굴 순서로 채우고
     * code 첫 등장 dedup, {@code totalMax} 도달 시 컷(뒤 그룹부터 잘림 = 우선순위).
     */
    static List<StockRef> mergeWarmTargets(List<StockRef> watch, List<StockRef> held,
                                           List<StockRef> discover, int totalMax) {
        List<List<StockRef>> groups = new ArrayList<>();
        groups.add(watch == null ? List.of() : watch);
        groups.add(held == null ? List.of() : held);
        groups.add(discover == null ? List.of() : discover);

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<StockRef> out = new ArrayList<>();
        for (List<StockRef> group : groups) {
            for (StockRef r : group) {
                if (out.size() >= totalMax) return out;
                if (r == null || r.code() == null || r.code().isBlank()
                        || r.name() == null || r.name().isBlank()) continue;
                if (!seen.add(r.code())) continue;
                out.add(r);
            }
        }
        return out;
    }

    /** 관심종목 전체(활성, 사용자 무관) — 조회 실패는 best-effort 빈 리스트. */
    private List<StockRef> watchlistRefs() {
        try {
            List<StockRef> out = new ArrayList<>();
            for (StockWatchlist w : watchlistRepository.findByIsActiveTrue()) {
                if (w == null || w.getStockCode() == null || w.getStockCode().isBlank()
                        || w.getStockName() == null || w.getStockName().isBlank()) continue;
                out.add(new StockRef(w.getStockCode(), w.getStockName()));
            }
            return out;
        } catch (Exception e) {
            log.debug("[재료워밍] 관심종목 조회 실패(생략): {}", e.getMessage());
            return List.of();
        }
    }

    /** 보유 — 봇 포지션(활성 행 전체, 모드 무관) + KIS 실잔고(설정 시). 실패는 best-effort. */
    private List<StockRef> heldRefs() {
        List<StockRef> out = new ArrayList<>();
        try {
            for (BotTradingPosition p : botPositionRepository.findAll()) {
                if (p == null || p.getStockCode() == null || p.getStockCode().isBlank()
                        || p.getStockName() == null || p.getStockName().isBlank()) continue;
                out.add(new StockRef(p.getStockCode(), p.getStockName()));
            }
        } catch (Exception e) {
            log.debug("[재료워밍] 봇 포지션 조회 실패(생략): {}", e.getMessage());
        }
        try {
            KoreaInvestmentService kis = kisProvider.getIfAvailable();
            RealTradeService rts = realTradeProvider.getIfAvailable();
            if (kis != null && rts != null && kis.isRealTradingConfigured()) {
                List<PortfolioItemDto> portfolio = rts.getPortfolio();
                if (portfolio != null) {
                    for (PortfolioItemDto p : portfolio) {
                        if (p == null || p.getStockCode() == null || p.getStockCode().isBlank()
                                || p.getStockName() == null || p.getStockName().isBlank()) continue;
                        out.add(new StockRef(p.getStockCode(), p.getStockName()));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[재료워밍] KIS 실잔고 조회 실패(생략): {}", e.getMessage());
        }
        return out;
    }

    /**
     * 발굴 5트랙(저평가/성장/낙폭/실적/수급) 상위 → code+name dedup, 상한 {@value #UNION_WARM_MAX} cap.
     * 순수 조립(테스트 대상). 트랙 조회 실패는 best-effort 무시(빈 리스트). null/blank 종목 스킵.
     */
    List<StockRef> collectUnionRefs() {
        // ⚠ 라운드로빈 인터리브(round r = 각 트랙 r번째) — value-first 순차면 앞 트랙이 25칸 독식해
        // 급등주(낙폭·수급 트랙)가 잘림. 트랙별 스코어 척도가 달라(PBR vs RSI vs 순매수액) 통합 정렬은
        // 사과-오렌지라 안 함. 인터리브로 각 트랙 top5 균등 + 소진 트랙 롤오버.
        List<Supplier<Top5Response>> tracks = List.of(
                recommendationService::getValueTop10,
                recommendationService::getGrowthTop10,
                recommendationService::getOversoldTop10,
                recommendationService::getEarningsTop10,
                recommendationService::getSmartMoneyTop10);
        List<List<RecommendationDto>> trackItems = new ArrayList<>();
        for (Supplier<Top5Response> track : tracks) trackItems.add(safeItems(track));
        int maxLen = trackItems.stream().mapToInt(List::size).max().orElse(0);

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<StockRef> refs = new ArrayList<>();
        for (int round = 0; round < maxLen && refs.size() < UNION_WARM_MAX; round++) {
            for (List<RecommendationDto> items : trackItems) {
                if (refs.size() >= UNION_WARM_MAX) break;
                if (round >= items.size()) continue;   // 이 트랙은 이번 라운드에 소진 → skip(롤오버)
                RecommendationDto d = items.get(round);
                if (d == null || d.getStockCode() == null || d.getStockCode().isBlank()
                        || d.getStockName() == null || d.getStockName().isBlank()) continue;
                if (!seen.add(d.getStockCode())) continue;   // 트랙 간 중복 dedup
                refs.add(new StockRef(d.getStockCode(), d.getStockName()));
            }
        }
        return refs;
    }

    private static List<RecommendationDto> safeItems(Supplier<Top5Response> track) {
        try {
            Top5Response r = track.get();
            return r != null && r.getItems() != null ? r.getItems() : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}
