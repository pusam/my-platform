package com.myplatform.backend.service;

import com.myplatform.backend.service.KoreaInvestmentService.IndexOhlcvData;
import com.myplatform.backend.util.TrendChannelCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * KOSPI 종합지수(0001) 추세 채널(회귀 채널) 스냅샷 소스 — <b>측정 전용</b>(V50).
 *
 * <p>{@code getIndexDailyOhlcv("0001", 60)} 단일 콜(§16 재사용, 신규 소스 0) → {@link TrendChannelCalculator}
 * (종목 채널 V49·차트·보드와 <b>동일 순수 산식</b>, 입력만 지수) → 방향/위치/폭. 6h 캐시로 배치 내 재사용.
 *
 * <p><b>⚠ 소비자 제약(불변식)</b>: 이 서비스는 오직 {@link SignalOutcomeService#record}에서만 호출한다.
 * 보드/화면 등 <b>장중에 실행되는 다른 소비자를 붙이지 말 것</b> — 장중 호출이 <b>미확정 당일봉(forming bar)</b>
 * 으로 6h 캐시를 채우면, 그 뒤 시그널 record 가 미확정 봉으로 계산된 채널을 읽게 된다. record() 경로로 한정해야
 * 캐시가 "시그널 기록 시점"의 일관된 상태만 담는다.
 *
 * <p><b>§4c</b>: 봉&lt;10 / 지수 고저가 결측 / 조회 실패 = {@code null}(미수집). 가짜 FLAT·중앙값으로 위장하지
 * 않는다. 실패/미확정도 6h 캐시에 담아(=아래 currentKospiChannel 이 매 시도마다 stamp) <b>배치당 KIS 콜 1회</b>
 * 를 보장하고, 다음 배치(6h 경과·익일)에 재시도한다.
 *
 * <p><b>미편입</b>: 점수/시그널/봇/추천/python regime v1 어디에도 쓰지 않는다 — 스냅샷 축적 + on-demand 집계 전용.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KospiChannelService {

    /** KOSPI 종합지수 = KIS 업종코드 0001(ETF 프록시 아님, §4c). */
    static final String KOSPI_INDEX_CODE = "0001";
    /** 채널 계산 봉 수 — V49 종목 채널({@code CHANNEL_SNAPSHOT_BARS})·차트 기본 표시와 동기. */
    static final int SNAPSHOT_BARS = 30;
    /**
     * 단일 콜 캘린더 조회폭 — 30거래일(≈44 캘린더)에 여유. KIS 지수 TR 은 한 콜에 ~100건 반환하므로
     * VKOSPI(252일)와 달리 <b>페이지네이션 불필요</b>(30봉은 단일 콜로 충분).
     */
    static final int LOOKBACK_CALENDAR_DAYS = 60;
    /** 캐시 TTL — 일 단위 데이터라 6h 면 배치 재실행에도 KIS 콜 1회. */
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    private final KoreaInvestmentService kis;

    // 6h 캐시 — VolatilityRegimeService 패턴 복제(서로 독립 전용 필드). cached=마지막 계산 결과(null=미확정),
    // cachedAt=마지막 '시도' 시각(null=미시도). 매 시도마다 stamp 하므로 실패/미확정도 6h 재사용 → 배치당 1콜.
    private volatile TrendChannelCalculator.Channel cached;
    private volatile Instant cachedAt;

    /**
     * 현재 KOSPI 지수 채널 — 6h 캐시. 데이터 미수집이면 {@code null}(§4c — 스냅샷은 이걸 그대로 NULL 저장).
     * <b>{@link SignalOutcomeService#record} 전용</b>(클래스 javadoc 소비자 제약 참조).
     */
    public TrendChannelCalculator.Channel currentKospiChannel() {
        Instant at = cachedAt;
        if (at != null && Duration.between(at, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cached;   // TTL 내 — 계산 결과(미확정 null 포함) 재사용, KIS 콜 안 함
        }
        TrendChannelCalculator.Channel fresh = fetchChannel();
        cached = fresh;              // null 가능(미확정) — §4c 위장 금지
        cachedAt = Instant.now();    // 시도 시각 stamp — 실패/미확정도 6h 재사용(배치당 1콜 보장)
        return fresh;
    }

    /**
     * 지수 일봉 단일 콜 → 최근 {@link #SNAPSHOT_BARS}봉 → 채널. 실패/빈 응답/봉 부족/고저가 결측 = null(§4c).
     * KIS 지수 응답은 과거→최신 오름차순({@code getIndexDailyOhlcv} 규약). 고가/저가가 null 인 봉은
     * {@link TrendChannelCalculator#compute}가 자체적으로 null 처리하므로 여기선 그대로 넘긴다.
     */
    TrendChannelCalculator.Channel fetchChannel() {
        try {
            List<IndexOhlcvData> rows = kis.getIndexDailyOhlcv(KOSPI_INDEX_CODE, LOOKBACK_CALENDAR_DAYS);
            if (rows == null || rows.isEmpty()) {
                log.debug("[KospiChannel] 지수 일봉 빈 응답 — 채널 미수집(NULL, §4c)");
                return null;
            }
            List<TrendChannelCalculator.Bar> bars = toBars(rows, SNAPSHOT_BARS);
            TrendChannelCalculator.Channel ch = TrendChannelCalculator.compute(bars);
            if (ch == null) {
                log.debug("[KospiChannel] 채널 미산출(봉 {}개·고저가 결측 등) — NULL(§4c)", bars.size());
            }
            return ch;
        } catch (Exception e) {
            log.warn("[KospiChannel] 지수 채널 수집 실패 — NULL(§4c): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 지수 OHLCV(과거→최신) → 최근 {@code maxBars}개 {@link TrendChannelCalculator.Bar}(과거→최신 순) — 순수 함수(테스트 대상).
     * 고가/저가 중 하나라도 null 인 봉은 그대로 담되(compute 가 null 판정), 여기선 종가 존재 봉만 취한다.
     * (지수 응답은 종가 결측 행이 이미 제외되어 오지만 방어적으로 확인.)
     */
    static List<TrendChannelCalculator.Bar> toBars(List<IndexOhlcvData> oldestFirst, int maxBars) {
        List<TrendChannelCalculator.Bar> bars = new ArrayList<>();
        if (oldestFirst == null) return bars;
        int from = Math.max(0, oldestFirst.size() - maxBars);   // 최근 maxBars개(오름차순이라 뒤쪽)
        for (int i = from; i < oldestFirst.size(); i++) {
            IndexOhlcvData d = oldestFirst.get(i);
            if (d == null || d.close() == null) continue;
            // 고/저 결측은 NaN 으로 넘겨 compute 가 위장 없이 null 판정하게 한다(§4c). 종가는 존재 보장.
            double high = d.high() != null ? d.high().doubleValue() : Double.NaN;
            double low = d.low() != null ? d.low().doubleValue() : Double.NaN;
            bars.add(new TrendChannelCalculator.Bar(high, low, d.close().doubleValue()));
        }
        return bars;
    }

    /** 테스트 전용 — 캐시 채널 주입(KIS 호출 우회). */
    void primeCacheForTest(TrendChannelCalculator.Channel channel) {
        this.cached = channel;
        this.cachedAt = Instant.now();
    }
}
