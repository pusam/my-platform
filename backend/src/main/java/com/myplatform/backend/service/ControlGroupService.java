package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 무작위 대조군(base rate) 기록 — <b>"적중률 35%가 잘한 건가"에 답하기 위한 기준선</b>.
 *
 * <p><b>왜 필요한가</b>: 시그널 적중률은 비교 대상이 없으면 해석 불가능하다. 같은 기간 같은 유니버스에서
 * 아무 종목이나 찍어도 35%가 나온다면 점수는 아무것도 더하지 않은 것이다. P2-19 ④ "무작위 대조군(base rate)
 * 부재"가 이 서비스로 해소된다.
 *
 * <p><b>공정한 비교를 위한 동일 조건</b>(하나라도 어긋나면 비교가 무의미해진다):
 * <ul>
 *   <li><b>같은 날</b>(signalDate 동일) — 시장 전체 등락을 상쇄. 하락장에 시그널만 몰리면 비교가 깨진다.</li>
 *   <li><b>같은 가격 기준</b> — {@link StockPriceService#getStockPrice}(시세 단일 경로 §16)로 기록 시점
 *       장중가를 쓴다. 시그널은 장중가인데 대조군만 종가를 쓰면 체결가 편향이 한쪽에만 걸린다.</li>
 *   <li><b>같은 벤치마크</b> — 시그널이 쓴 KOSPI 가격을 그대로 넘겨받아 alpha 를 동일 정의로 계산.</li>
 *   <li><b>같은 유니버스</b> — 추천이 스캔하는 풀(일봉 {@value #UNIVERSE_MIN_HISTORY}일 이상 + 마지막 봉
 *       {@value #UNIVERSE_RECENT_DAYS}일 이내 + 거래정지·상폐 제외)에서만 뽑고, 시세도 시그널과 같은
 *       <b>5분 신선 캐시</b>만 쓴다(신규 KIS 호출 금지 — AUDIT R2 대칭화, 2026-08-31). 추천이 볼 수 없던
 *       종목을 대조군에 넣으면 "점수의 기여"가 아니라 "유니버스 차이"를 재게 된다.
 *       <b>⚠ 측정 표본 경계 = 2026-09-01</b>: 그 전 대조군엔 저유동·수집중단 종목이 섞여 base rate 가
 *       낮다(edge 과대 방향) — 비교창을 섞지 말 것.</li>
 *   <li><b>같은 평가 경로</b> — signal_outcome 에 저장하므로 기존 평가 배치가 동일한 hit 정의로 채운다.</li>
 * </ul>
 *
 * <p><b>기존 집계 오염 방지(중요)</b>: signalType 을 {@value #CONTROL_SIGNAL_TYPE} 로 두면
 * {@code SignalOutcomeService.filterBoardSignals}(STRONG_BUY/BUY 만 통과)에서 <b>자동으로 제외</b>된다.
 * 밴드/카테고리/regime 집계는 전부 그 필터를 거치므로 대조군 행이 섞이지 않는다. 추가로 signalScore 를
 * <b>null</b> 로 둬서, 설령 필터가 바뀌어도 점수 밴드에 편입될 수 없게 이중 방어한다.
 *
 * <p><b>best-effort</b>: 대조군 기록 실패는 절대 실제 시그널 기록을 방해하지 않는다(호출부에서 catch).
 * 롤백 = {@code signal-outcome.control-group.enabled=false} — 끄면 기존 동작과 byte-identical.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ControlGroupService {

    /** 대조군 전용 시그널 타입 — BOARD_SIGNAL_TYPES 에 없으므로 기존 집계에서 자동 제외. */
    public static final String CONTROL_SIGNAL_TYPE = "CONTROL_RANDOM";

    /** 유니버스 최소 일봉 수 — 추천 트랙(낙폭과대 등)이 요구하는 수준과 맞춘다. */
    static final int UNIVERSE_MIN_HISTORY = 60;

    /**
     * 유니버스 최근성(달력일) — 마지막 봉이 이 안에 있어야 한다(AUDIT R2, 2026-08-31).
     * 수집이 끊긴 종목은 시그널이 나올 수 없는데 대조군에는 뽑혔다 — base rate 를 끌어내려
     * edge 가 좋아 보이는 방향의 편향. 7일 = 주말+연휴를 정상으로 흡수하는 여유값.
     */
    static final int UNIVERSE_RECENT_DAYS = 7;

    /**
     * 같은 날 이미 대조군으로 쓰인 종목을 피하기 위한 재시도 횟수.
     * <p>8→12 상향(2026-08-31): 시세 게이트가 캐시 전용으로 엄격해져 미스가 늘 수 있다 —
     * 시도를 늘려 표본 손실을 완화한다(시드 결정성은 attempt 별로 유지되므로 재현성 불변).
     */
    static final int PICK_ATTEMPTS = 12;

    /** 시드 흩뜨림용 큰 소수 — 인접 날짜/종목이 비슷한 시드를 갖지 않게. */
    private static final long SEED_MIX = 2654435761L;

    private final SignalOutcomeRepository repository;
    private final StockPriceHistoryRepository priceHistoryRepository;
    private final ObjectProvider<StockPriceService> priceProvider;
    private final ObjectProvider<StockStatusService> statusProvider;
    private final ObjectProvider<ControlGroupService> selfProvider;

    @Value("${signal-outcome.control-group.enabled:true}")
    private boolean enabled;

    /** 유니버스 일캐시 — findStockCodesWithMinHistory 는 전체 GROUP BY 라 시그널마다 돌리면 안 된다. */
    private volatile LocalDate universeDate;
    private volatile List<String> universeCache;

    /**
     * 시그널 하나에 대응하는 대조군 1건 기록.
     *
     * @param signalStockCode 짝이 되는 시그널의 종목(대조군에서 제외 — 같은 종목이면 비교가 성립 안 함)
     * @param signalDate      시그널과 동일 일자
     * @param bmPrice         시그널이 사용한 KOSPI 가격(동일 벤치마크로 alpha 계산)
     * @param regime          시그널 시점 국면 스냅샷(이미 조회된 값 재사용 — 국면별 base rate 비교용)
     */
    public void recordControlFor(String signalStockCode, LocalDate signalDate,
                                 BigDecimal bmPrice, String regime) {
        if (!enabled || signalStockCode == null || signalDate == null) return;
        try {
            List<String> universe = resolveUniverse(signalDate);
            if (universe.isEmpty()) {
                log.debug("[대조군] 유니버스 비어있음 — 스킵");
                return;
            }
            StockPriceService priceService = priceProvider.getIfAvailable();
            if (priceService == null) return;

            // 같은 날 이미 쓰인 대조군 종목은 UNIQUE 로 막히므로, 몇 번 다시 뽑아 표본 손실을 줄인다.
            for (int attempt = 0; attempt < PICK_ATTEMPTS; attempt++) {
                String code = pickControlStock(universe, signalStockCode,
                        seedFor(signalDate, signalStockCode, attempt));
                if (code == null) return;
                if (!repository.findExisting(CONTROL_SIGNAL_TYPE, code, signalDate).isEmpty()) {
                    continue;   // 오늘 이미 대조군으로 사용됨 — 다른 종목으로
                }
                // ★ 캐시 전용(AUDIT R2, 2026-08-31): getStockPrice 는 미스 시 KIS 신규 호출까지
                //   가는데, 시그널 종목은 기록 시점에 반드시 살아있는 가격(파이프라인이 방금 쓴 값)을
                //   갖고 있었다. 대조군만 죽은/저유동 종목의 가격을 새로 떠오면 "지금 시장에 보이지
                //   않는 종목"이 대조군에만 들어가 base rate 가 내려간다(edge 과대 편향).
                //   같은 5분 신선 창의 캐시/DB 만 보고, 미스면 그 종목은 못 뽑은 것으로 처리한다.
                StockPriceDto price = priceService.getStockPricesFromCacheOnly(List.of(code)).get(code);
                if (price == null || price.getCurrentPrice() == null
                        || price.getCurrentPrice().signum() <= 0) {
                    continue;   // 신선 시세 없음 — 시그널이라면 기록되지 못했을 종목, 다른 종목으로
                }
                SignalOutcome control = SignalOutcome.builder()
                        .signalType(CONTROL_SIGNAL_TYPE)
                        .stockCode(code)
                        .stockName(price.getStockName())
                        .signalDate(signalDate)
                        .signalScore(null)          // 점수 없음 — 밴드 집계 편입 원천 차단(이중 방어)
                        .priceAtSignal(price.getCurrentPrice())
                        .bmPriceAtSignal(bmPrice)   // 시그널과 동일 벤치마크
                        .regimeAtSignal(regime)     // 국면별 base rate 비교용(추가 조회 없음)
                        .build();
                selfProvider.getObject().insertControlIsolated(control);
                log.debug("[대조군] 기록 {} (짝: {}, {})", code, signalStockCode, signalDate);
                return;
            }
            log.debug("[대조군] {}회 시도에도 사용 가능한 종목 없음 — 스킵({})", PICK_ATTEMPTS, signalDate);
        } catch (DataIntegrityViolationException dup) {
            log.debug("[대조군] 중복(경합 UNIQUE) 무시");
        } catch (Exception e) {
            log.debug("[대조군] 기록 실패({}): {}", signalStockCode, e.getMessage());
        }
    }

    /**
     * 대조군 INSERT — REQUIRES_NEW 로 격리. UNIQUE 위반이 호출부(실제 시그널 기록) 트랜잭션을
     * rollback-only 로 오염시키지 않게 한다({@code SignalOutcomeService.insertOutcomeIsolated} 와 동일 이유).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertControlIsolated(SignalOutcome control) {
        repository.save(control);
    }

    /** 유니버스(일캐시) — 일봉 충분 + 거래정지/상폐 제외. 추천이 스캔하는 풀과 같은 조건. */
    private List<String> resolveUniverse(LocalDate date) {
        List<String> cached = universeCache;
        if (cached != null && date.equals(universeDate)) return cached;

        // 최근성 조건(R2): 마지막 봉이 7일 이내 — 수집 끊긴 종목은 시그널 유니버스에 없다.
        List<String> codes = priceHistoryRepository.findActiveStockCodesWithMinHistory(
                UNIVERSE_MIN_HISTORY, date.minusDays(UNIVERSE_RECENT_DAYS));
        StockStatusService status = statusProvider.getIfAvailable();
        if (status != null) {
            try {
                codes = status.filterActiveStocks(codes);
            } catch (Exception e) {
                // 동기화 전 fail-open — 기존 semantics 유지(정지 종목이 섞이면 대조군이 약간 보수적으로 나옴)
                log.debug("[대조군] 활성 필터 실패(전체 사용): {}", e.getMessage());
            }
        }
        List<String> snapshot = List.copyOf(codes);
        universeCache = snapshot;
        universeDate = date;
        log.info("[대조군] 유니버스 갱신 {}종목 ({})", snapshot.size(), date);
        return snapshot;
    }

    /**
     * 대조군 종목 선택 — 순수 함수(테스트 대상). 시그널 종목은 제외하고 시드로 결정적 선택.
     *
     * <p>결정적으로 뽑는 이유는 <b>재현성</b>이다. 같은 (일자, 시그널종목, 시도횟수)면 항상 같은 대조군이
     * 나오므로 사후에 "이 대조군이 어떻게 뽑혔나"를 검증할 수 있다 — 무작위 대조군의 신뢰는 그것이
     * 사후에 고를 수 없었음을 보일 수 있느냐에 달려 있다.
     */
    static String pickControlStock(List<String> universe, String excludeCode, long seed) {
        if (universe == null || universe.isEmpty()) return null;
        List<String> pool = new ArrayList<>(universe.size());
        for (String c : universe) {
            if (c != null && !c.equals(excludeCode)) pool.add(c);
        }
        if (pool.isEmpty()) return null;
        int idx = (int) Math.floorMod(mix(seed), pool.size());
        return pool.get(idx);
    }

    /** (일자, 종목, 시도) → 시드. 순수 함수. */
    static long seedFor(LocalDate date, String stockCode, int attempt) {
        long h = date.toEpochDay() * SEED_MIX;
        h = h * 31 + (stockCode == null ? 0 : stockCode.hashCode());
        h = h * 31 + attempt;
        return h;
    }

    /** splitmix64 finalizer — 인접 시드가 인접 인덱스로 가지 않게 흩뜨린다. */
    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 테스트/진단용 — 현재 유니버스 크기(미갱신이면 -1). */
    public int currentUniverseSize() {
        List<String> u = universeCache;
        return u == null ? -1 : u.size();
    }

    /** 대조군 타입인지 — 집계 필터에서 사용. */
    public static boolean isControl(String signalType) {
        return CONTROL_SIGNAL_TYPE.equals(signalType);
    }

    /** 기존 집계가 대조군을 걸러내는지 검증하기 위한 상수 노출(테스트 가드용). */
    public static Set<String> excludedFromBoardAggregation() {
        return Set.of(CONTROL_SIGNAL_TYPE);
    }
}
