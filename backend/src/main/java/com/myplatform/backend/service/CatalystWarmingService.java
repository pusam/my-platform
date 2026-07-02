package com.myplatform.backend.service;

import com.myplatform.backend.service.RecommendationService.RecommendationDto;
import com.myplatform.backend.service.RecommendationService.Top5Response;
import com.myplatform.backend.service.StockCatalystService.StockRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * 종합판단 보드 union 재료 일괄 워밍 (P2-CAT3) — 발굴 5트랙 상위 종목 재료를 배치로 미리 분류해
 * 보드의 "—"(재료 미포착)를 채운다. {@link StockCatalystService#classifyBatch}(5씩 청킹·캐시히트 스킵·
 * 전역 rate 게이트) 위에 얹어 quota 안전(25종목 ≈ 5 Gemini 콜).
 *
 * <p>momentum 워밍(MorningBriefingService 07:30, 상한 5)과 <b>별개</b> — 이건 union 상위
 * {@value #UNION_WARM_MAX}종목, 1×/day 08:00(주중). §4b 상한·rate 게이트 준수, 재료 산식 미편입.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CatalystWarmingService {

    /** union 워밍 상한 — 상위 25(≈5 Gemini 콜). 보는 종목 커버 + quota 최안전. 하위 필요 시 상향. */
    static final int UNION_WARM_MAX = 25;

    private final RecommendationService recommendationService;
    private final StockCatalystService stockCatalystService;
    private final SchedulerLockService schedulerLockService;

    @Value("${catalyst.union-warm.enabled:true}")
    private boolean unionWarmEnabled;

    /** 08:00 주중 — momentum 워밍(07:30) 뒤, KRX 개장(09:00) 전 → 개장 시 보드 재료 fresh. */
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
     * union 상위 재료 워밍 실행 — 크론/수동트리거(admin) 공용. classifyBatch 가 캐시 히트(momentum 워밍分
     * 포함) 자동 스킵 + 5씩 청킹 + rate 게이트. @return 신규 분류 저장 종목 수.
     */
    public int warmUnionCatalysts() {
        List<StockRef> refs = collectUnionRefs();
        if (refs.isEmpty()) {
            log.info("[재료워밍] union — 대상 없음");
            return 0;
        }
        int warmed = stockCatalystService.classifyBatch(refs);   // 실제 Gemini 콜 수는 classifyBatch 총계 로그
        log.info("[재료워밍] union 완료 — 대상 {}종목 → {}건 신규분류 (나머지 캐시히트/뉴스없음)", refs.size(), warmed);
        return warmed;
    }

    /**
     * 발굴 5트랙(저평가/성장/낙폭/실적/수급) 상위 → code+name dedup, 상한 {@value #UNION_WARM_MAX} cap.
     * 순수 조립(테스트 대상). 트랙 조회 실패는 best-effort 무시(빈 리스트). null/blank 종목 스킵.
     */
    List<StockRef> collectUnionRefs() {
        List<Supplier<Top5Response>> tracks = List.of(
                recommendationService::getValueTop10,
                recommendationService::getGrowthTop10,
                recommendationService::getOversoldTop10,
                recommendationService::getEarningsTop10,
                recommendationService::getSmartMoneyTop10);

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<StockRef> refs = new ArrayList<>();
        for (Supplier<Top5Response> track : tracks) {
            for (RecommendationDto d : safeItems(track)) {
                if (refs.size() >= UNION_WARM_MAX) return refs;
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
