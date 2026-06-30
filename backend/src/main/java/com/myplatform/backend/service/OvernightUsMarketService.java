package com.myplatform.backend.service;

import com.myplatform.backend.service.GlobalFuturesService.FuturesQuote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 간밤 미국장 국면 보조(tilt) — '오늘' 탭 참고 컨텍스트 (작업3, 미검증).
 *
 * <p>S&amp;P500(ES)·나스닥100(NQ)·필라델피아 반도체(SOX) 등락률 + VIX 레벨로 BULL/NEUTRAL/BEAR tilt 산출.
 * GlobalFuturesService 의 Yahoo 시세를 재사용한다(별도 fetch 없음).
 *
 * <p><b>불변식</b>:
 * <ul>
 *   <li>이 tilt 는 python regime(KOSPI MA60)·RecommendationService 점수 산식에 <b>입력으로 들어가지 않는다</b>
 *       — '오늘' 탭에서 regime 과 <b>나란히 별개</b>로만 표시.
 *   <li>{@code unverified=true} — 봇/종합추천/매수후보 산식 미편입(차트타이밍·섹터강도와 동일 게이팅).
 *   <li>임계값은 전부 <b>임시값</b> — 추후 KOSPI 익일 시초가 대비 적중률로 캘리브레이션.
 *   <li>지수는 Yahoo marketState 를 None 으로 줄 수 있어 <b>marketState 에 의존하지 않는다</b>(등락률+VIX 레벨만).
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OvernightUsMarketService {

    private final GlobalFuturesService futures;

    /** 간밤 미국장 보조 뷰 — tilt + drivers + 가용성. '오늘' 탭 표시 전용(미검증). */
    public Map<String, Object> getOvernightView() {
        FuturesQuote es = futures.getFuturesQuote("ES");
        FuturesQuote nq = futures.getFuturesQuote("NQ");
        FuturesQuote sox = futures.getFuturesQuote("SOX");
        FuturesQuote vix = futures.getFuturesQuote("VIX");

        Double esRate = rate(es), nqRate = rate(nq), soxRate = rate(sox);
        Double vixLevel = level(vix);

        String tilt = classifyOvernight(esRate, nqRate, soxRate, vixLevel);
        boolean dataAvailable = esRate != null || nqRate != null || soxRate != null || vixLevel != null;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tilt", tilt);                 // BULL / NEUTRAL / BEAR
        m.put("drivers", buildDrivers(es, nq, sox, vix));
        m.put("dataAvailable", dataAvailable);   // false=Yahoo 미가용(빈 결과가 '중립'과 구분)
        m.put("asOf", firstTradingTime(nq, es, sox, vix));
        m.put("unverified", true);
        m.put("note", "간밤 미국장 보조 tilt(미검증) — regime/봇/추천 산식 미편입, 참고 표시 전용");
        return m;
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

    private static List<String> buildDrivers(FuturesQuote es, FuturesQuote nq, FuturesQuote sox, FuturesQuote vix) {
        List<String> d = new ArrayList<>();
        addRate(d, "S&P500", es);
        addRate(d, "나스닥", nq);
        addRate(d, "SOX", sox);
        if (vix != null && vix.isSuccess() && vix.getCurrentPrice() != null) {
            double v = vix.getCurrentPrice().doubleValue();
            String zone = v >= 30 ? "극심한 공포" : v >= 25 ? "공포" : v >= 20 ? "경계" : "안정";
            d.add(String.format("VIX %.1f(%s)", v, zone));
        }
        return d;
    }

    private static void addRate(List<String> d, String name, FuturesQuote q) {
        if (q != null && q.isSuccess() && q.getChangeRate() != null) {
            d.add(String.format("%s %+.2f%%", name, q.getChangeRate().doubleValue()));
        }
    }

    private static String firstTradingTime(FuturesQuote... qs) {
        for (FuturesQuote q : qs) {
            if (q != null && q.isSuccess() && q.getTradingTime() != null) return q.getTradingTime();
        }
        return null;
    }
}
