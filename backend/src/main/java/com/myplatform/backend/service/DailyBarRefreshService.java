package com.myplatform.backend.service;

import com.myplatform.backend.repository.StockPriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 장 마감 후 당일 일봉 확정 갱신 배치 — P2-19 ① 해소.
 *
 * <p><b>고치는 문제</b>: 일봉 수집은 상세화면 방문·추천 트리거로만 일어나는데, 그게 <b>장중</b>에 돌면
 * 당일 봉이 미확정 상태(고가·저가·거래량이 진행 중)로 저장된다. 예전 저장 로직은 "이미 있으면 스킵"이라
 * 그 미확정 봉이 <b>영구 확정</b>됐다. RSI·이동평균·5일 누적·낙폭과대가 전부 이 히스토리를 읽으므로
 * 기술 점수가 조용히 오염된다. 이 배치가 마감 후 확정값으로 덮어써 그 고리를 끊는다.
 *
 * <p><b>대상 범위</b>: 오늘 봉이 있는 종목만({@code findStockCodesByTradeDate}). 장중에 수집된 종목만
 * 당일 행을 갖기 때문에 이 목록이 곧 "미확정 봉 보유 종목"이고, 전체 유니버스를 훑지 않아 KIS 호출이
 * <b>장중 활동량에 비례</b>해 bound 된다. 상한({@value #MAX_STOCKS_PER_RUN})을 두되 초과분은
 * <b>로그로 반드시 노출</b>한다 — 조용한 절단은 "다 갱신했다"는 착각을 만든다.
 *
 * <p><b>시각</b>: 평일 18:30 KST. KRX 정규장(15:30) + 종가단일가(15:40) 이후라 일봉이 확정돼 있고,
 * 이 히스토리를 읽는 시그널 평가 배치(19:30)보다 앞선다. StockPriceHistory 는 KRX 단독(J) 기준이라
 * NXT 애프터마켓(~20:00)을 기다릴 필요가 없다.
 *
 * <p><b>덮어쓰기 판정</b>은 {@code DailyBarUpsertRule}(순수) 이 갖고 있다 — 당일 봉은 항상 최신화,
 * 과거 봉은 값이 다를 때만. 그래서 이 배치는 과거에 얼어붙은 봉도 <b>확정값으로 복구</b>하며,
 * 정상 상태에선 쓰기가 0이라 조용하다.
 *
 * <p>롤백 = {@code daily-bar.refresh.enabled=false}(기존 동작과 동일 — 갱신만 멈춤).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailyBarRefreshService {

    /** 배치 1회 상한 — KIS rate 예산. 초과분은 다음 실행이 아니라 <b>다음 날</b>로 밀리므로 로그로 경고한다. */
    static final int MAX_STOCKS_PER_RUN = 400;

    /** KIS 호출 간격(ms) — QuantTaService 일괄 수집과 동일 예산. */
    static final long RATE_LIMIT_MS = 450L;

    private final StockPriceHistoryRepository historyRepository;
    private final StockAnalysisService stockAnalysisService;
    private final SchedulerLockService schedulerLockService;
    private final MarketCalendarService marketCalendar;

    @Value("${daily-bar.refresh.enabled:true}")
    private boolean enabled;

    /**
     * 마감 후 당일 일봉 확정 갱신 — 평일 18:30.
     * 배치 풀 사용(cacheScheduler/기본 taskScheduler 는 각각 캐시워밍·트레이딩 전용).
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 30 18 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshTodayBars() {
        if (!enabled) return;
        if (marketCalendar.isMarketClosed()) { log.debug("[일봉갱신] 휴장일 — 스킵"); return; }
        if (!schedulerLockService.tryLock("daily-bar.refresh", Duration.ofMinutes(30))) {
            log.debug("[일봉갱신] 다른 인스턴스에서 진행 중 — 스킵");
            return;
        }
        try {
            int refreshed = refreshBarsFor(LocalDate.now());
            log.info("[일봉갱신] 완료 — {}종목 재수집", refreshed);
        } catch (Exception e) {
            log.error("[일봉갱신] 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 지정 거래일의 봉을 가진 종목들을 재수집해 확정값으로 덮어쓴다. 반환 = 실제 재수집 시도한 종목 수.
     * (관리 수동 트리거에서도 재사용 — 배치 시각을 놓친 날 복구용)
     */
    public int refreshBarsFor(LocalDate tradeDate) {
        List<String> codes = historyRepository.findStockCodesByTradeDate(tradeDate);
        if (codes.isEmpty()) {
            log.info("[일봉갱신] {} 봉을 가진 종목 없음 — 장중 수집이 없었던 날", tradeDate);
            return 0;
        }

        // ★ 공정 선정(AUDIT R6, 2026-08-31): prod 실측 하루 대상 879~1,123종목 — 상한 400 을
        //   매일 2.8배 초과한다. 이전엔 DISTINCT 쿼리의 자연 순서(ORDER BY 없음) 그대로 앞 400개만
        //   갱신해 **매일 같은 꼬리 ~700종목이 영구 미확정**으로 굶주릴 수 있었다. 셔플이면 종목당
        //   하루 채택률 ~36%, 5일 연속 탈락 확률 11% — 그리고 뽑힐 때 60봉을 소급 재수집하므로
        //   놓친 날도 결국 확정된다. 상한 자체(KIS 호출 예산)는 불변 — 올리려면 별도 판단.
        List<String> picked = selectFairly(codes, MAX_STOCKS_PER_RUN, new java.util.Random());
        int limit = picked.size();
        if (codes.size() > MAX_STOCKS_PER_RUN) {
            // 조용한 절단 금지 — 남은 종목은 오늘 확정 갱신을 못 받는다는 뜻이라 반드시 보인다.
            log.warn("[일봉갱신] 대상 {}종목이 상한 {}을 초과 — 무작위 {}종목만 갱신, 나머지 {}종목은 "
                            + "다음 실행 후보로 남는다(상한 조정은 별도 판단)",
                    codes.size(), MAX_STOCKS_PER_RUN, limit, codes.size() - limit);
        }

        int done = 0;
        for (int i = 0; i < limit; i++) {
            String code = picked.get(i);
            try {
                // collectPriceHistory 가 60봉을 재수집 → DailyBarUpsertRule 이 당일 봉을 확정값으로 덮어쓴다.
                stockAnalysisService.collectPriceHistory(code);
                done++;
            } catch (Exception e) {
                log.debug("[일봉갱신] {} 재수집 실패(스킵): {}", code, e.getMessage());
            }
            if (i < limit - 1) {
                try {
                    Thread.sleep(RATE_LIMIT_MS);   // KIS rate limit 보호 — 배치 풀이라 트레이딩 슬롯 무영향
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("[일봉갱신] 중단됨 — {}/{}종목 처리 후 종료", done, limit);
                    break;
                }
            }
        }
        return done;
    }

    /**
     * 상한 내 공정 선정 — <b>순수 함수(테스트 대상)</b>.
     *
     * <p>상한 이하면 그대로(전수 갱신), 초과면 셔플 후 앞 {@code cap}개. 결정적 절단
     * (항상 같은 순서의 앞부분)은 같은 종목만 반복 갱신하고 나머지를 영구 굶주리게 한다 —
     * 무작위는 며칠에 걸쳐 전 종목이 돌아가며 확정을 받게 한다.
     */
    static List<String> selectFairly(List<String> codes, int cap, java.util.Random random) {
        if (codes.size() <= cap) return List.copyOf(codes);
        List<String> shuffled = new java.util.ArrayList<>(codes);
        java.util.Collections.shuffle(shuffled, random);
        return List.copyOf(shuffled.subList(0, cap));
    }
}
