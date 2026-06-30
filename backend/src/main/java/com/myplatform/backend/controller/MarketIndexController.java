package com.myplatform.backend.controller;

import com.myplatform.backend.service.KoreaInvestmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 시장 지수 내부 제공 — python regime/sector_strength 가 KOSPI 일봉을 받아간다.
 *
 * <p>배경(2026-06-30): pykrx 지수 엔드포인트가 KRX 포맷 변경으로 전구간 빈값이 되어
 * python 의 regime(시장국면)/sector_strength(섹터 상대강도)가 깨졌다. KIS 일봉 지수
 * (KoreaInvestmentService.getIndexDailyOhlcv, TR FHPUP02120000, 진짜 종합지수 0001)로
 * 데이터 소스만 전환한다 — 국면 규칙/분류 로직은 python 에 그대로(CLAUDE.md 10번/4c 불변식).
 *
 * <p>공개 시장데이터(KOSPI 지수)라 SecurityConfig permitAll. 컨테이너 네트워크 내부
 * (python-backend → backend:8080) 호출이 주용도. KIS 미설정/미수집 시 success=false + 빈 배열
 * (위장값 금지 — python 이 graceful null 로 처리).
 */
@RestController
@RequestMapping("/api/market/index")
@RequiredArgsConstructor
public class MarketIndexController {

    private static final String KOSPI_INDEX_CODE = "0001";   // KIS 업종코드: 0001=코스피
    private static final int DEFAULT_DAYS = 130;             // 캘린더일 ~ 90 거래일 (MA60+슬로프 충분)

    private final ObjectProvider<KoreaInvestmentService> kisProvider;

    /**
     * KOSPI 종합지수 일봉(과거→최신). days=캘린더일.
     * @return {success, data:[{date,open,high,low,close}], count}
     */
    @GetMapping("/kospi-daily")
    public ResponseEntity<?> kospiDaily(@RequestParam(defaultValue = "" + DEFAULT_DAYS) int days) {
        KoreaInvestmentService kis = kisProvider.getIfAvailable();
        if (kis == null || !kis.isConfigured()) {
            return ResponseEntity.ok(Map.of(
                    "success", false, "data", List.of(), "count", 0, "reason", "KIS 미설정"));
        }
        List<KoreaInvestmentService.IndexOhlcvData> rows =
                kis.getIndexDailyOhlcv(KOSPI_INDEX_CODE, Math.max(1, days));
        return ResponseEntity.ok(Map.of(
                "success", !rows.isEmpty(),
                "data", rows,
                "count", rows.size()));
    }
}
