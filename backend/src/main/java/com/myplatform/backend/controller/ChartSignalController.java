package com.myplatform.backend.controller;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.service.ChartPatternClient;
import com.myplatform.backend.service.ChartPatternClient.TimingSignal;
import com.myplatform.backend.service.ChartSignalRanker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 차트 추세추종 보조 시그널 컨트롤러 — 발굴 탭 보조 트랙 전용.
 *
 * 종합추천(momentum) 스코어러(RecommendationService)와 의도적으로 분리:
 * 이 신호는 별도 모듈(python chart_patterns)이 출처이며, ⚠ 산식 미검증이라
 * "보조 시그널(미검증 배지)"로만 노출한다. 봇/종합추천/매수후보 랭킹에 편입 금지
 * (VERIFICATION_BACKLOG '차트기법 스코어러 백테스트' 통과 전).
 */
@RestController
@RequestMapping("/api/recommendation")
@RequiredArgsConstructor
public class ChartSignalController {

    private static final int TOP_N = 10;

    private final ChartPatternClient chartPatternClient;
    private final SectorStockConfig sectorStockConfig;

    /**
     * 추세눌림(보조) TOP10 — 섹터 유니버스(SectorStockConfig 전 종목)를 정배열+눌림목
     * 타이밍으로 스캔해 상위 10. 미검증 보조 시그널(unverified=true).
     */
    @GetMapping("/trend-pullback-top10")
    public ResponseEntity<?> getTrendPullbackTop10() {
        List<String> universe = new ArrayList<>(sectorStockConfig.getAllStockCodes());
        ChartPatternClient.TimingFetch fetch = chartPatternClient.getTimingSignals(universe);
        List<Map<String, Object>> items = ChartSignalRanker.topByScore(fetch.signals(), TOP_N).stream()
                .map(this::toItem)
                .toList();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", items,
                "unverified", true,            // 미검증 보조 시그널 — 실거래/봇 신호 아님
                "dataAvailable", fetch.available()  // false=분석서버 다운(빈 결과가 '신호 없음'과 구분)
        ));
    }

    /**
     * 섹터 상대강도('덜 빠지는 섹터') — SectorStockConfig 16섹터 구성원 합성지수 vs KOSPI.
     * 발굴 유니버스 필터/배지용. 미검증 보조 시그널.
     */
    @GetMapping("/sector-strength")
    public ResponseEntity<?> getSectorStrength() {
        Map<String, List<String>> sectors = new LinkedHashMap<>();
        sectorStockConfig.getAllSectors().forEach(
                s -> sectors.put(s.getName(), s.getStockCodes()));
        Map<String, Object> data = chartPatternClient.getSectorStrength(sectors);
        boolean available = data != null && data.get("ranked") != null;
        return ResponseEntity.ok(Map.of(
                "success", available,
                "data", data == null ? Map.of() : data,
                "unverified", true,
                "dataAvailable", available   // false=분석서버 미가용
        ));
    }

    private Map<String, Object> toItem(TimingSignal s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", s.ticker());
        m.put("name", sectorStockConfig.getStockName(s.ticker()));
        m.put("timingScore", s.timingScore());
        m.put("signals", s.signals());
        m.put("unverified", true);
        return m;
    }
}
