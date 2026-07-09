package com.myplatform.backend.service;

import com.myplatform.backend.service.KoreaInvestmentService.IndexOhlcvData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

/**
 * VKOSPI 변동성 국면 게이트 — 고변동 장세에서 봇 <b>신규 진입</b>을 사전 회피(서킷브레이커의 사후 방어 보완).
 *
 * <p>VKOSPI(KIS 업종코드 0503) 최신 종가가 최근 {@code lookback}거래일 분포의 <b>상위 {@code topPercent}%</b>
 * 이상이면 국면 = HIGH_VOL. 게이트 모드(OFF/REDUCED/BLOCK)에 따라 진입을 차단하거나 사이즈를 축소한다.
 *
 * <p><b>불변식</b>:
 * <ul>
 *   <li><b>기본 OFF</b> — mode=OFF 면 {@link #evaluateEntry}가 항상 PROCEED → 봇 매매 동작 byte-identical.</li>
 *   <li><b>§4c</b>: VKOSPI 데이터 없음/표본 부족 → {@link VolRegime#UNKNOWN} → <b>게이트 skip(PROCEED)+로그</b>.
 *       가짜 NORMAL 로 처리하지 않는다(missing≠정상).</li>
 *   <li><b>진입 게이트만</b> — 청산에는 관여하지 않는다(일일손실 브레이커 비대칭 원칙과 동일).</li>
 *   <li><b>§16</b>: VKOSPI 조회는 기존 {@link KoreaInvestmentService#getIndexDailyOhlcv} 재사용(신규 데이터 경로 없음).
 *       국면 계산은 {@link VolRegime#UNKNOWN} 판정과 무관하게 시그널 스냅샷(V46)에도 쓰인다(게이트 모드와 독립).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VolatilityRegimeService {

    /** VKOSPI = KIS 업종코드 0503 (= {@link MacroTiltService#VKOSPI_INDEX_CODE}, idxcode.mst 확인). */
    static final String VKOSPI_INDEX_CODE = "0503";
    /** 시리즈 캐시 TTL — 일 단위 데이터라 자주 재조회 불필요(페이지네이션 KIS 호출 절약). */
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    /** 페이지네이션 최대 콜 수(KIS 지수 TR 은 한 번에 ~100건) — 100×4=400 > 252 여유. */
    private static final int MAX_PAGES = 4;
    /** 콜당 캘린더 조회폭(~100 거래일/콜 상한에 맞춤). */
    private static final int PAGE_CALENDAR_DAYS = 150;

    public enum VolRegime { NORMAL, HIGH_VOL, UNKNOWN }
    public enum GateMode { OFF, REDUCED, BLOCK }
    public enum Decision { PROCEED, REDUCE, BLOCK }

    @Value("${bot.vol-regime-gate.mode:OFF}")
    private volatile String gateModeRaw;
    @Value("${bot.vol-regime.lookback-days:252}")
    private volatile int lookbackDays;
    @Value("${bot.vol-regime.top-percent:10.0}")
    private volatile double topPercent;
    @Value("${bot.vol-regime.min-samples:120}")
    private volatile int minSamples;
    @Value("${bot.vol-regime.reduced-factor:0.5}")
    private volatile double reducedFactorRaw;
    /** UNKNOWN(VKOSPI 미수집)이 연속 N일 지속되면 경보 1회 — 게이트가 조용히 보호 못 하는 걸 사람이 인지. */
    @Value("${bot.vol-regime.unknown-alert-days:3}")
    private volatile int unknownAlertDays;

    private final KoreaInvestmentService kis;
    // 기존 알림 경로 재사용(risk 채널) — 미가용/미설정 환경 null-safe. best-effort(게이트 동작 무영향).
    private final ObjectProvider<TelegramNotificationService> telegramProvider;

    private volatile Series cached;
    private volatile Instant cachedAt;

    // UNKNOWN 연속일 경보 상태 — 게이트 판정(mode≠OFF)에서만 갱신. 게이트 동작은 그대로 fail-open.
    private volatile int unknownStreak;
    private volatile LocalDate lastUnknownEvalDate;
    private volatile LocalDate lastUnknownAlertDate;

    record Series(List<Double> closes, Double current) {}

    // ==================== 순수 판정 (테스트 대상) ====================

    /** 게이트 모드 파싱 — 결측/오설정은 안전 폴백 OFF. */
    static GateMode parseMode(String raw) {
        if (raw == null) return GateMode.OFF;
        try {
            return GateMode.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return GateMode.OFF;
        }
    }

    /**
     * 변동성 국면 판정 — 순수 함수. {@code current} 가 {@code history} 분포의 상위 {@code topPercent}%
     * 임계(=(100−topPercent)th 백분위) 이상이면 HIGH_VOL. 결측/표본부족/설정오류는 UNKNOWN(§4c).
     */
    static VolRegime classifyVolRegime(Double current, List<Double> history, double topPercent, int minSamples) {
        if (current == null || history == null) return VolRegime.UNKNOWN;
        if (topPercent <= 0 || topPercent >= 100) return VolRegime.UNKNOWN;   // 잘못된 설정 = 판정 불가
        List<Double> s = new ArrayList<>(history.size());
        for (Double d : history) {
            if (d != null) s.add(d);
        }
        if (s.size() < Math.max(1, minSamples)) return VolRegime.UNKNOWN;     // 표본 부족 = 미수집
        Collections.sort(s);
        double p = 100.0 - topPercent;                                        // 상위 10% → 90th 백분위 임계
        int rank = (int) Math.ceil(p / 100.0 * s.size());                     // nearest-rank
        rank = Math.max(1, Math.min(rank, s.size()));
        double threshold = s.get(rank - 1);
        return current >= threshold ? VolRegime.HIGH_VOL : VolRegime.NORMAL;
    }

    /** 게이트 결정 — 순수 함수. OFF/NORMAL/UNKNOWN → PROCEED. HIGH_VOL 만 모드별(REDUCE/BLOCK). */
    static Decision decideGate(GateMode mode, VolRegime regime) {
        if (mode == GateMode.OFF) return Decision.PROCEED;
        if (regime != VolRegime.HIGH_VOL) return Decision.PROCEED;   // NORMAL 정상 · UNKNOWN skip(§4c)
        return mode == GateMode.BLOCK ? Decision.BLOCK : Decision.REDUCE;
    }

    /** UNKNOWN 연속일 스트릭·경보 결정 — 순수 함수(테스트 대상). 게이트 동작엔 무관(경보 판단만). */
    record UnknownAlertDecision(int newStreak, LocalDate newEvalDate, boolean fireAlert) {}

    /**
     * @param unknown 이번 판정이 UNKNOWN 인가. false(NORMAL/HIGH_VOL 확인) 면 스트릭 리셋.
     * @param today   오늘(KST). 스트릭은 <b>날짜 단위</b> — 같은 날 반복 호출은 증가 안 함(장중 틱마다 안 셈).
     * @param threshold 연속 며칠이면 경보. 경보는 lastAlertDate 로 <b>하루 1회</b> 쿨다운.
     */
    static UnknownAlertDecision decideUnknownAlert(int prevStreak, LocalDate prevEvalDate, LocalDate prevAlertDate,
                                                   boolean unknown, LocalDate today, int threshold) {
        if (!unknown) {
            return new UnknownAlertDecision(0, prevEvalDate, false);   // 국면 확인 → 스트릭 리셋
        }
        int streak = today.equals(prevEvalDate) ? prevStreak : prevStreak + 1;   // 새 날에만 증가
        boolean fire = threshold > 0 && streak >= threshold && !today.equals(prevAlertDate);   // 하루 1회 쿨다운
        return new UnknownAlertDecision(streak, today, fire);
    }

    // ==================== 공개 API ====================

    public GateMode gateMode() {
        return parseMode(gateModeRaw);
    }

    /** REDUCED 모드 사이즈 배수(0<f<1, 폴백 0.5). */
    public double reducedFactor() {
        return (reducedFactorRaw > 0 && reducedFactorRaw < 1) ? reducedFactorRaw : 0.5;
    }

    /**
     * 현재 변동성 국면 — 캐시된 VKOSPI 시리즈 기반. <b>게이트 모드와 독립</b>(스냅샷·게이트 공용).
     * 데이터 미수집이면 {@link VolRegime#UNKNOWN}(§4c — 스냅샷은 이걸 null 로 저장).
     */
    public VolRegime currentVolRegime() {
        Series s = cachedSeries();
        if (s == null || s.current() == null) return VolRegime.UNKNOWN;
        return classifyVolRegime(s.current(), s.closes(), topPercent, minSamples);
    }

    /**
     * 봇 진입 게이트 판정 + 로그. mode=OFF 면 즉시 PROCEED(byte-identical). UNKNOWN 은 skip(PROCEED)하되
     * 로그로 NORMAL 과 구분(§4c: 가짜 NORMAL 금지). 호출부는 BLOCK=진입 취소 / REDUCE=사이즈 축소.
     */
    public Decision evaluateEntry(String botLabel) {
        GateMode mode = gateMode();
        if (mode == GateMode.OFF) return Decision.PROCEED;   // flag OFF → 봇 동작 byte-identical
        VolRegime regime = currentVolRegime();
        if (regime == VolRegime.UNKNOWN) {
            trackUnknownStreak(true);   // 연속 N일 지속 시 경보 1회(게이트는 그대로 skip=fail-open)
            log.warn("[{}] 변동성 게이트 skip — VKOSPI 데이터 미수집(§4c, 가짜 NORMAL 처리 안 함)", botLabel);
            return Decision.PROCEED;
        }
        trackUnknownStreak(false);   // 국면 확인됨 → UNKNOWN 스트릭 리셋
        Decision d = decideGate(mode, regime);
        if (d == Decision.BLOCK) {
            log.info("[{}] SKIP: 고변동 국면(HIGH_VOL, VKOSPI 상위 {}% 이상) — 신규 진입 차단(mode=BLOCK)",
                    botLabel, topPercent);
        } else if (d == Decision.REDUCE) {
            log.info("[{}] 고변동 국면(HIGH_VOL) — 신규 진입 사이즈 {}배 축소(mode=REDUCED)", botLabel, reducedFactor());
        }
        return d;
    }

    /**
     * UNKNOWN 연속일 추적 + 경보. 게이트 판정(mode≠OFF)에서만 호출 — 게이트가 켜졌는데 데이터가 없어
     * 조용히 skip(fail-open) 하는 걸 사람이 인지하게. <b>게이트 동작(PROCEED)은 그대로</b> — 알림만 추가.
     * synchronized: 스트릭 상태 복합 갱신을 틱 동시성으로부터 보호.
     */
    private synchronized void trackUnknownStreak(boolean unknown) {
        LocalDate today = LocalDate.now();
        UnknownAlertDecision d = decideUnknownAlert(
                unknownStreak, lastUnknownEvalDate, lastUnknownAlertDate, unknown, today, unknownAlertDays);
        unknownStreak = d.newStreak();
        lastUnknownEvalDate = d.newEvalDate();
        if (d.fireAlert()) {
            lastUnknownAlertDate = today;   // 하루 1회 쿨다운
            sendUnknownAlert(unknownStreak);
        }
    }

    /** 기존 risk 알림 경로 재사용 — best-effort(미설정/실패 무시, 게이트 동작 무영향). */
    private void sendUnknownAlert(int streak) {
        try {
            TelegramNotificationService tg = telegramProvider.getIfAvailable();
            if (tg != null && tg.isEnabled()) {
                tg.sendRisk(String.format(
                        "⚠️ [봇] VKOSPI 변동성 게이트 데이터 미수집 %d일 연속 — 고변동 진입 보호가 작동하지 않습니다"
                        + "(fail-open, 진입은 계속 허용). VKOSPI(KIS 업종 0503) 소스 점검 필요.", streak));
            }
        } catch (Exception e) {
            log.warn("[VolRegime] UNKNOWN 연속 경보 전송 실패: {}", e.getMessage());
        }
        log.warn("[VolRegime] VKOSPI 미수집 {}일 연속 — 게이트 보호 미작동(fail-open) 경보", streak);
    }

    // ==================== 시리즈 수집 (페이지네이션 + 캐시) ====================

    private Series cachedSeries() {
        Series c = cached;
        Instant at = cachedAt;
        if (c != null && at != null && Duration.between(at, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return c;
        }
        Series fresh = fetchSeries();
        if (fresh != null) {
            cached = fresh;
            cachedAt = Instant.now();
            return fresh;
        }
        return c;   // 조회 실패 → 기존 캐시 유지(있으면), 없으면 null → UNKNOWN(§4c)
    }

    /**
     * VKOSPI ~{@code lookback}거래일 종가 시리즈 — {@code getIndexDailyOhlcv} 앵커 페이지네이션.
     * KIS 지수 TR 이 한 콜에 ~100건만 주므로 anchorEnd 를 과거로 밀며 여러 번 호출해 병합(날짜 오름차순).
     * 실패/빈 응답이면 null(§4c 위장값 금지).
     */
    private Series fetchSeries() {
        int target = Math.max(minSamples, Math.min(lookbackDays, 600));   // 안전 상한
        try {
            TreeMap<String, Double> byDate = new TreeMap<>();
            LocalDate anchor = LocalDate.now();
            for (int i = 0; i < MAX_PAGES && byDate.size() < target; i++) {
                List<IndexOhlcvData> batch = kis.getIndexDailyOhlcv(VKOSPI_INDEX_CODE, PAGE_CALENDAR_DAYS, anchor);
                if (batch == null || batch.isEmpty()) break;
                for (IndexOhlcvData d : batch) {
                    if (d.close() != null) byDate.putIfAbsent(d.date(), d.close().doubleValue());
                }
                LocalDate oldest = LocalDate.parse(batch.get(0).date());   // batch 는 오름차순
                LocalDate next = oldest.minusDays(1);
                if (!next.isBefore(anchor)) break;   // 진전 없음 방어(무한루프 차단)
                anchor = next;
            }
            if (byDate.isEmpty()) return null;
            List<Double> closes = new ArrayList<>(byDate.values());   // 날짜 오름차순 → 마지막이 최신
            if (closes.size() > target) {
                closes = new ArrayList<>(closes.subList(closes.size() - target, closes.size()));
            }
            return new Series(closes, closes.get(closes.size() - 1));
        } catch (Exception e) {
            log.warn("[VolRegime] VKOSPI 시리즈 수집 실패: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 테스트 seam ====================

    /** 테스트 전용 — 캐시 시리즈 주입(KIS 호출 우회). */
    void primeCacheForTest(List<Double> closes, Double current) {
        this.cached = new Series(closes, current);
        this.cachedAt = Instant.now();
    }
}
