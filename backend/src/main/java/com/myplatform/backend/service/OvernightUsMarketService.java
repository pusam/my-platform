package com.myplatform.backend.service;

import com.myplatform.backend.entity.OvernightUsSnapshot;
import com.myplatform.backend.repository.OvernightUsSnapshotRepository;
import com.myplatform.backend.service.GlobalFuturesService.FuturesQuote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 간밤 미국장 국면 보조(tilt) — '오늘' 탭 참고 컨텍스트 (작업3, 미검증).
 *
 * <p>S&amp;P500·나스닥100·필라델피아 반도체(SOX) <b>현물 지수</b>의 정규장 등락률 + VIX 레벨로 BULL/NEUTRAL/BEAR tilt 산출.
 * GlobalFuturesService 의 Yahoo 시세를 재사용한다. ⚠ 2026-10-04 전에는 S&amp;P500·나스닥에 CME 선물(ES=F·NQ=F)을 써서
 * 08시 스냅샷이 새 선물 세션 1시간치 움직임을 담았다(운영 9/15: SOX −5.86% · ES +0.05%) — 그 전 행의 es_rate·nq_rate 는
 * 이후 행과 비교할 수 없다(P3-5 캘리브레이션의 표본 경계). 지수 등락률은 <b>직전 미국 세션</b> 것일 때만 쓴다
 * ({@link #lastNightSession} — 미국 휴장 다음 날 Yahoo 는 직전 세션 등락률을 그대로 준다).
 *
 * <p><b>불변식</b>:
 * <ul>
 *   <li>이 tilt 는 python regime(KOSPI MA60)·RecommendationService 점수 산식에 <b>입력으로 들어가지 않는다</b>
 *       — '오늘' 탭에서 regime 과 <b>나란히 별개</b>로만 표시.
 *   <li>{@code unverified=true} — 봇/종합추천/매수후보 산식 미편입(차트타이밍·섹터강도와 동일 게이팅).
 *   <li>임계값은 전부 <b>임시값</b> — 추후 KOSPI 익일 시초가 대비 적중률로 캘리브레이션(P3-5).
 *   <li>지수는 Yahoo marketState 를 None 으로 줄 수 있어 <b>marketState 에 의존하지 않는다</b>(등락률+VIX 레벨만).
 *   <li><b>단일 compute 경로(V40)</b>: 표시 API({@code GET /api/global-futures/overnight-us})와 08:10 일일
 *       스냅샷이 같은 {@link #computeInputs()} + {@link #classifyOvernight} 를 사용 — 따로 계산하면
 *       "사용자가 본 tilt"와 "검증되는 tilt"가 어긋난다.
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OvernightUsMarketService {

    private final GlobalFuturesService futures;
    private final OvernightUsSnapshotRepository snapshotRepo;
    private final ObjectProvider<MarketRegimeClient> regimeProvider;

    /** 판정 입력 4종 + 참고 맥락 — 스냅샷 재현용으로 전부 유지(§4c: null=미수집). */
    record OvernightInputs(Double esRate, Double nqRate, Double soxRate, Double vixLevel,
                           Double soxLevel, String tradingTime) {}

    /** 간밤 미국장 보조 뷰 — tilt + drivers + 가용성. '오늘' 탭 표시 전용(미검증). */
    public Map<String, Object> getOvernightView() {
        OvernightInputs in = computeInputs();
        String tilt = classifyOvernight(in.esRate(), in.nqRate(), in.soxRate(), in.vixLevel());
        boolean dataAvailable = in.esRate() != null || in.nqRate() != null
                || in.soxRate() != null || in.vixLevel() != null;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tilt", tilt);                 // BULL / NEUTRAL / BEAR
        m.put("drivers", buildDrivers(in));
        m.put("dataAvailable", dataAvailable);   // false=Yahoo 미가용(빈 결과가 '중립'과 구분)
        m.put("asOf", in.tradingTime());
        m.put("unverified", true);
        m.put("note", "간밤 미국장 보조 tilt(미검증) — regime/봇/추천 산식 미편입, 참고 표시 전용");
        return m;
    }

    /**
     * 일일 스냅샷 UPSERT (08:10 크론, V40) — 표시와 <b>같은 compute 경로</b>의 결과를 영속.
     * 판정 입력 4종 + regime v1 동시 스냅(사후검증 재현용 — 결과만 저장 금지, P3-5 캘리브레이션).
     */
    public synchronized void snapshotToday() {
        OvernightInputs in = computeInputs();
        String tilt = classifyOvernight(in.esRate(), in.nqRate(), in.soxRate(), in.vixLevel());
        MarketRegimeClient regimeClient = regimeProvider.getIfAvailable();
        String regimeV1 = regimeClient != null ? regimeClient.getCurrentRegimeQuiet() : null;

        LocalDate today = LocalDate.now();
        OvernightUsSnapshot snap = snapshotRepo.findBySnapshotDate(today)
                .orElseGet(() -> OvernightUsSnapshot.builder().snapshotDate(today).build());
        snap.setTilt(tilt);
        snap.setEsRate(toDecimal(in.esRate(), 2));
        snap.setNqRate(toDecimal(in.nqRate(), 2));
        snap.setSoxRate(toDecimal(in.soxRate(), 2));
        snap.setVixLevel(toDecimal(in.vixLevel(), 2));
        snap.setSoxLevel(toDecimal(in.soxLevel(), 2));
        snap.setTradingTime(in.tradingTime());
        snap.setRegimeV1(regimeV1);
        snap.setDrivers(String.join(" · ", buildDrivers(in)));
        snapshotRepo.save(snap);
        log.info("[간밤미국장] 스냅샷 저장: {} tilt={} es={} nq={} sox={} vix={} regimeV1={}",
                today, tilt, in.esRate(), in.nqRate(), in.soxRate(), in.vixLevel(), regimeV1);
    }

    /** 4종 시세 조회 → 입력 변환 — 표시/스냅샷 공용 단일 compute 경로. 지수는 현물, 직전 세션 것만. */
    private OvernightInputs computeInputs() {
        java.time.DayOfWeek kstDay = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).getDayOfWeek();
        FuturesQuote spx = futures.getFuturesQuote("SPX");
        FuturesQuote ndx = futures.getFuturesQuote("NDX");
        FuturesQuote sox = futures.getFuturesQuote("SOX");
        FuturesQuote vix = futures.getFuturesQuote("VIX");
        return new OvernightInputs(sessionRateOrNull(spx, kstDay), sessionRateOrNull(ndx, kstDay),
                sessionRateOrNull(sox, kstDay), level(vix), level(sox), firstTradingTime(ndx, spx, sox, vix));
    }

    /**
     * 지수 등락률이 직전 미국 세션 것인가 — 순수(테스트 대상). 미국 종가는 05~06시(KST)라 평일 아침엔 몇 시간 전이다.
     * KST 월·일요일은 직전 세션이 금요일(주말)이라 72시간, 그 밖엔 24시간 안의 종가만 '간밤'이다 — 미국 휴장 다음 날
     * (예: 노동절 다음 화요일)엔 Yahoo 가 직전 세션 등락률을 그대로 줘서 같은 움직임이 두 번 들어갔다(9/7·9/8 SOX +3.38%).
     */
    static boolean lastNightSession(long dataAgeMinutes, java.time.DayOfWeek kstDay) {
        long maxHours = (kstDay == java.time.DayOfWeek.MONDAY || kstDay == java.time.DayOfWeek.SUNDAY) ? 72 : 24;
        return dataAgeMinutes >= 0 && dataAgeMinutes <= maxHours * 60;
    }

    /** 직전 세션 지수 등락률 — 실패·결측·오래된 세션이면 null(모름, §4c). 순수. */
    static Double sessionRateOrNull(FuturesQuote q, java.time.DayOfWeek kstDay) {
        Double r = rate(q);
        return (r != null && lastNightSession(q.getDataAgeMinutes(), kstDay)) ? r : null;
    }

    /** 시세 → 판정 입력 — 순수 함수(테스트 대상). 실패/결측 축은 null(§4c). */
    static OvernightInputs toInputs(FuturesQuote es, FuturesQuote nq, FuturesQuote sox, FuturesQuote vix) {
        return new OvernightInputs(rate(es), rate(nq), rate(sox), level(vix),
                level(sox), firstTradingTime(nq, es, sox, vix));
    }

    /**
     * 간밤 미국장 tilt 분류 — 순수 함수(테스트 대상).
     *
     * <p>입력 = ES/NQ/SOX 등락률(%) + VIX 레벨. <b>marketState 미사용</b>(지수는 Yahoo 가 None 으로 줄 수 있음
     * — 등락률+VIX 레벨만으로 판정). 임계 전부 <b>임시값</b>(추후 KOSPI 익일 시초가 적중률로 캘리브레이션):
     * <ul>
     *   <li>VIX &ge; 30 → BEAR (공포 강제, 지수 강세여도)
     *   <li>3지수 평균 &ge; +0.6% AND VIX &lt; 20 → BULL
     *   <li>3지수 평균 &le; -0.6% OR VIX &ge; 25 OR SOX &le; -2.0% → BEAR (약세 / 공포경계 / 반도체 급락)
     *   <li>그 외 → NEUTRAL
     * </ul>
     */
    static String classifyOvernight(Double esRate, Double nqRate, Double soxRate, Double vixLevel) {
        Double avg3 = avg(esRate, nqRate, soxRate);
        if (vixLevel != null && vixLevel >= 30.0) return "BEAR";               // 공포 강제
        if (avg3 != null && avg3 >= 0.6 && (vixLevel == null || vixLevel < 20.0)) return "BULL";
        if ((avg3 != null && avg3 <= -0.6)
                || (vixLevel != null && vixLevel >= 25.0)
                || (soxRate != null && soxRate <= -2.0)) return "BEAR";         // 약세 / 공포경계 / 반도체 급락
        return "NEUTRAL";
    }

    /** null 제외 평균(전부 null 이면 null). */
    static Double avg(Double... vals) {
        double sum = 0;
        int n = 0;
        for (Double v : vals) {
            if (v != null) { sum += v; n++; }
        }
        return n == 0 ? null : sum / n;
    }

    private static Double rate(FuturesQuote q) {
        return (q != null && q.isSuccess() && q.getChangeRate() != null) ? q.getChangeRate().doubleValue() : null;
    }

    private static Double level(FuturesQuote q) {
        return (q != null && q.isSuccess() && q.getCurrentPrice() != null) ? q.getCurrentPrice().doubleValue() : null;
    }

    /** drivers — 가용 축만 표시(§4c). 표시/스냅샷 공용(사용자가 본 그대로 저장). */
    static List<String> buildDrivers(OvernightInputs in) {
        List<String> d = new ArrayList<>();
        addRate(d, "S&P500", in.esRate());
        addRate(d, "나스닥", in.nqRate());
        addRate(d, "SOX", in.soxRate());
        if (in.vixLevel() != null) {
            double v = in.vixLevel();
            d.add(String.format("VIX %.1f(%s)", v, VixZone.label(v)));
        }
        return d;
    }

    private static void addRate(List<String> d, String name, Double rate) {
        if (rate != null) {
            d.add(String.format("%s %+.2f%%", name, rate));
        }
    }

    private static String firstTradingTime(FuturesQuote... qs) {
        for (FuturesQuote q : qs) {
            if (q != null && q.isSuccess() && q.getTradingTime() != null) return q.getTradingTime();
        }
        return null;
    }

    private static BigDecimal toDecimal(Double v, int scale) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }
}
