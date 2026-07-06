package com.myplatform.backend.service;

import com.myplatform.backend.entity.MacroTiltSnapshot;
import com.myplatform.backend.repository.MacroTiltSnapshotRepository;
import com.myplatform.backend.service.GlobalFuturesService.FuturesQuote;
import com.myplatform.backend.service.KoreaInvestmentService.IndexOhlcvData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 매크로 tilt — '오늘' 탭 보조 컨텍스트 (P3-7, 미검증 · 간밤 미국장 tilt 패턴 복제).
 *
 * <p>3축: ① VKOSPI 레벨(KIS 업종코드 0503 일봉) ② 국고3년 ~20거래일 추세(ECOS, bp)
 * ③ SOX ~5거래일 추세(자체 스냅샷 축적 — 신규 소스 없음) → RISK_ON/NEUTRAL/RISK_OFF.
 *
 * <p><b>불변식</b>:
 * <ul>
 *   <li>이 tilt 는 python regime v1(BULL/BEAR/SIDEWAYS)·RecommendationService 점수 산식·봇에
 *       <b>입력으로 들어가지 않는다</b> — '오늘' 탭 표시 + 일일 스냅샷(사후검증용)뿐.
 *   <li>{@code unverified=true} — 승격 조건은 주간 리포트 기준 regime v1 대비 유의한 추가 예측력(P3-7).
 *   <li>임계값은 전부 <b>임시값</b> — 스냅샷 축적 후 KOSPI 방향 대비 캘리브레이션.
 *   <li>어휘 RISK_ON/NEUTRAL/RISK_OFF 는 regime v1 어휘와 <b>의도적으로 분리</b>(혼입 방지).
 *   <li><b>단일 compute 경로</b>: 표시 API 와 08:15 스냅샷이 같은 {@link #computeInputs} 를 사용 —
 *       따로 계산하면 "사용자가 본 tilt"와 "검증되는 tilt"가 어긋난다.
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MacroTiltService {

    /** KIS 지수 마스터(idxcode.mst) 확인 결과 — {@code 00503VKOSPI} → 업종코드 0503 (2026-07-06). */
    static final String VKOSPI_INDEX_CODE = "0503";
    /** 금리 추세 창(거래일) — 임시값. */
    static final int RATE_TREND_LOOKBACK = 20;

    private final KoreaInvestmentService kisService;
    private final GlobalFuturesService futures;
    private final EcosClient ecos;
    private final MacroTiltSnapshotRepository snapshotRepo;
    private final ObjectProvider<MarketRegimeClient> regimeProvider;

    /** 입력 30분 캐시 — 일 단위 데이터라 실시간 재조회 불필요. 스냅샷 시 강제 갱신(표시=스냅샷 수렴). */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private volatile MacroInputs cachedInputs;
    private volatile Instant cachedAt;

    /** 판정 입력 3종 + 관측일 + 참고 맥락 — 스냅샷 재현용으로 전부 유지(§4c: null=미수집). */
    record MacroInputs(Double vkospi, LocalDate vkospiDate,
                       Double ktb3y, LocalDate rateDate, Double rateTrendBp,
                       Double soxLevel, Double soxTrendPct, LocalDate soxAsof) {}

    /** 매크로 보조 뷰 — tilt + drivers + 가용성. '오늘' 탭 표시 전용(미검증). */
    public Map<String, Object> getMacroTiltView() {
        MacroInputs in = cachedOrCompute();
        String tilt = classifyMacroRegime(in.vkospi(), in.rateTrendBp(), in.soxTrendPct());
        boolean dataAvailable = in.vkospi() != null || in.rateTrendBp() != null || in.soxTrendPct() != null;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tilt", tilt);                 // RISK_ON / NEUTRAL / RISK_OFF
        m.put("drivers", buildDrivers(in));
        m.put("dataAvailable", dataAvailable);   // false=3축 전부 미수집(빈 결과가 '중립'과 구분)
        m.put("asOf", in.vkospiDate() != null ? in.vkospiDate().toString()
                : in.rateDate() != null ? in.rateDate().toString() : null);
        m.put("unverified", true);
        m.put("note", "매크로 보조 tilt(미검증) — regime v1/봇/추천 산식 미편입, 참고 표시 전용");
        return m;
    }

    /**
     * 일일 스냅샷 UPSERT (08:15 크론) — 표시와 <b>같은 compute 경로</b>를 강제 갱신 후 영속.
     * 판정 입력 3종 + 관측일 + regime v1 동시 스냅(사후검증 재현용 — 결과만 저장 금지).
     */
    public synchronized void snapshotToday() {
        MacroInputs in = computeInputs();
        cachedInputs = in;                    // 표시 캐시도 갱신 — 사용자가 본 값 = 저장된 값
        cachedAt = Instant.now();

        String tilt = classifyMacroRegime(in.vkospi(), in.rateTrendBp(), in.soxTrendPct());
        MarketRegimeClient regimeClient = regimeProvider.getIfAvailable();
        String regimeV1 = regimeClient != null ? regimeClient.getCurrentRegimeQuiet() : null;
        Double baseRate = ecos.getBaseRate();   // 참고 맥락(classify 입력 아님) — 스냅샷에만 저장

        LocalDate today = LocalDate.now();
        MacroTiltSnapshot snap = snapshotRepo.findBySnapshotDate(today)
                .orElseGet(() -> MacroTiltSnapshot.builder().snapshotDate(today).build());
        snap.setTilt(tilt);
        snap.setVkospi(toDecimal(in.vkospi(), 2));
        snap.setKtb3y(toDecimal(in.ktb3y(), 3));
        snap.setRateTrendBp(toDecimal(in.rateTrendBp(), 2));
        snap.setSoxLevel(toDecimal(in.soxLevel(), 2));
        snap.setSoxTrendPct(toDecimal(in.soxTrendPct(), 2));
        snap.setBaseRate(toDecimal(baseRate, 2));
        snap.setVkospiDate(in.vkospiDate());
        snap.setRateDate(in.rateDate());
        snap.setSoxAsof(in.soxAsof());
        snap.setRegimeV1(regimeV1);
        snap.setDrivers(String.join(" · ", buildDrivers(in)));
        snapshotRepo.save(snap);
        log.info("[매크로tilt] 스냅샷 저장: {} tilt={} vkospi={} rateTrend={}bp soxTrend={}% regimeV1={}",
                today, tilt, in.vkospi(), in.rateTrendBp(), in.soxTrendPct(), regimeV1);
    }

    /**
     * 매크로 tilt 분류 — 순수 함수(테스트 대상).
     *
     * <p>임계 전부 <b>임시값</b>(스냅샷 축적 후 KOSPI 방향 적중률로 캘리브레이션):
     * <ul>
     *   <li>VKOSPI &ge; 30 → RISK_OFF (공포 강제 — 간밤 tilt 의 VIX&ge;30 대칭)
     *   <li>축별 투표: VKOSPI &lt;18 → +1 / &ge;25 → −1 · 국고3년 20d 추세 &le;−15bp → +1 / &ge;+15bp → −1
     *       · SOX 5d 추세 &ge;+3% → +1 / &le;−3% → −1
     *   <li>null 축 = 투표 제외(§4c 미수집). 합 &ge;+2 → RISK_ON / &le;−2 → RISK_OFF / 그 외 NEUTRAL.
     * </ul>
     *
     * <p>⚠ <b>NEUTRAL 고착 비대칭(알려진 한계)</b>: ECOS 키 발급 전엔 금리 축이 상시 null 이라
     * RISK_ON 은 잔여 2축 동시 극단이 필요(RISK_OFF 는 VKOSPI 오버라이드 1축 경로 존재).
     * ±2 임계를 <b>가용 축 수로 스케일하지 말 것</b> — 소스가 늘 때 판정 의미가 조용히 변해
     * 스냅샷 시계열(사후검증 데이터)이 오염된다. null 축 여부는 drivers/스냅샷이 이미 기록.
     *
     * <p>⚠ <b>금리 부호 양면성(1순위 캘리브레이션 대상)</b>: 국고3년 하락=완화 기대(risk-on)로
     * 가정했으나, 급락은 안전자산 쏠림(risk-off)일 수도 있다 — 20거래일 창에서 두 해석이 공존.
     */
    static String classifyMacroRegime(Double vkospiLevel, Double rate3yTrendBp, Double soxTrendPct) {
        if (vkospiLevel != null && vkospiLevel >= 30.0) return "RISK_OFF";   // 공포 강제
        int sum = 0;
        if (vkospiLevel != null) sum += vkospiLevel < 18.0 ? 1 : vkospiLevel >= 25.0 ? -1 : 0;
        if (rate3yTrendBp != null) sum += rate3yTrendBp <= -15.0 ? 1 : rate3yTrendBp >= 15.0 ? -1 : 0;
        if (soxTrendPct != null) sum += soxTrendPct >= 3.0 ? 1 : soxTrendPct <= -3.0 ? -1 : 0;
        if (sum >= 2) return "RISK_ON";
        if (sum <= -2) return "RISK_OFF";
        return "NEUTRAL";
    }

    /**
     * SOX ~5거래일 추세(%) — 순수 함수(테스트 대상). 오늘 live 레벨 vs 과거 자체 스냅샷 중
     * <b>가장 오래된 유효 레벨</b>(최근 7행 창). 유효 과거점 없으면 null — <b>의도된 웜업</b>
     * (콜드스타트 ~5거래일, §4c: 축 제외로 자연 강등. 가짜 추세 생성 금지).
     */
    static SoxTrend computeSoxTrend(Double todayLevel, List<MacroTiltSnapshot> recentDesc, LocalDate today) {
        if (todayLevel == null || recentDesc == null) return SoxTrend.NONE;
        MacroTiltSnapshot oldest = null;
        for (MacroTiltSnapshot s : recentDesc) {   // 최신순 입력 — 끝까지 훑어 최고령 유효점 채택
            if (s.getSoxLevel() == null || s.getSnapshotDate() == null) continue;
            if (!s.getSnapshotDate().isBefore(today)) continue;   // 당일 행(재실행) 제외
            oldest = s;
        }
        if (oldest == null) return SoxTrend.NONE;
        double past = oldest.getSoxLevel().doubleValue();
        if (past <= 0) return SoxTrend.NONE;
        double pct = (todayLevel - past) / past * 100.0;
        return new SoxTrend(pct, oldest.getSnapshotDate());
    }

    record SoxTrend(Double pct, LocalDate asOf) {
        static final SoxTrend NONE = new SoxTrend(null, null);
    }

    // ==================== 내부: 입력 수집 ====================

    private MacroInputs cachedOrCompute() {
        MacroInputs in = cachedInputs;
        Instant at = cachedAt;
        if (in != null && at != null && Duration.between(at, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return in;
        }
        in = computeInputs();
        cachedInputs = in;
        cachedAt = Instant.now();
        return in;
    }

    /** 3축 입력 수집 — 축별 실패는 해당 축 null(§4c), 다른 축 진행. */
    private MacroInputs computeInputs() {
        // ① VKOSPI — KIS 지수 일봉(업종코드 0503) 최신 종가. 빈 응답 → null.
        Double vkospi = null;
        LocalDate vkospiDate = null;
        try {
            List<IndexOhlcvData> series = kisService.getIndexDailyOhlcv(VKOSPI_INDEX_CODE, 15);
            if (!series.isEmpty()) {
                IndexOhlcvData last = series.get(series.size() - 1);
                vkospi = last.close().doubleValue();
                vkospiDate = LocalDate.parse(last.date());
            }
        } catch (Exception e) {
            log.warn("[매크로tilt] VKOSPI(0503) 조회 실패: {}", e.getMessage());
        }

        // ② 국고3년 — ECOS. 키 미발급/실패/시계열 부족 → null.
        Double ktb3y = null;
        LocalDate rateDate = null;
        Double rateTrendBp = null;
        try {
            List<EcosClient.EcosPoint> series = ecos.getKtb3ySeries(45);
            if (!series.isEmpty()) {
                EcosClient.EcosPoint last = series.get(series.size() - 1);
                ktb3y = last.value();
                rateDate = LocalDate.parse(last.date());
                rateTrendBp = EcosClient.trendBp(series, RATE_TREND_LOOKBACK);
            }
        } catch (Exception e) {
            log.warn("[매크로tilt] ECOS 국고3년 조회 실패: {}", e.getMessage());
        }

        // ③ SOX — live 레벨(기존 Yahoo 수집 재사용) + 자체 스냅샷 대비 추세(신규 소스 없음).
        Double soxLevel = null;
        SoxTrend soxTrend = SoxTrend.NONE;
        try {
            FuturesQuote sox = futures.getFuturesQuote("SOX");
            if (sox != null && sox.isSuccess() && sox.getCurrentPrice() != null) {
                soxLevel = sox.getCurrentPrice().doubleValue();
            }
            soxTrend = computeSoxTrend(soxLevel, snapshotRepo.findTop8ByOrderBySnapshotDateDesc(), LocalDate.now());
        } catch (Exception e) {
            log.warn("[매크로tilt] SOX 추세 산출 실패: {}", e.getMessage());
        }

        return new MacroInputs(vkospi, vkospiDate, ktb3y, rateDate, rateTrendBp,
                soxLevel, soxTrend.pct(), soxTrend.asOf());
    }

    /** drivers — 가용 축만 표시, 각 축에 관측일 병기(월요일 08:15 = 전부 금요일 값 — 신선도 오해 방지). */
    private static List<String> buildDrivers(MacroInputs in) {
        List<String> d = new ArrayList<>();
        if (in.vkospi() != null) {
            String zone = in.vkospi() >= 30 ? "공포" : in.vkospi() >= 25 ? "경계" : in.vkospi() < 18 ? "안정" : "보통";
            d.add(String.format("VKOSPI %.1f(%s%s)", in.vkospi(), zone, dateSuffix(in.vkospiDate())));
        }
        if (in.ktb3y() != null) {
            String trend = in.rateTrendBp() != null ? String.format(" 20d %+.0fbp", in.rateTrendBp()) : "";
            d.add(String.format("국고3년 %.2f%%%s%s", in.ktb3y(), trend, dateSuffix(in.rateDate())));
        }
        if (in.soxTrendPct() != null) {
            d.add(String.format("SOX 5d %+.1f%%", in.soxTrendPct()));
        }
        return d;
    }

    private static String dateSuffix(LocalDate date) {
        return date == null ? "" : String.format(", %d/%d", date.getMonthValue(), date.getDayOfMonth());
    }

    private static BigDecimal toDecimal(Double v, int scale) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }
}
