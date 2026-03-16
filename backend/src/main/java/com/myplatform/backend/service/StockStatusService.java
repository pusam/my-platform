package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KRX 상장종목 상태 관리 서비스
 * - 매일 08:30 KRX에서 KOSPI+KOSDAQ 상장종목 목록 동기화
 * - 거래정지/상장폐지 종목 자동 감지
 * - SectorStockConfig의 종목 필터링에 사용
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StockStatusService {

    private static final String KRX_DATA_URL = "http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd";
    private static final String KRX_REFERER = "http://data.krx.co.kr/contents/MDC/MDI/mdiIO/MDIO0101";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // KOSPI: MDCSTAT01901, KOSDAQ: MDCSTAT01902 (전종목 시세)
    private static final String BLD_KOSPI = "dbms/MDC/STAT/standard/MDCSTAT01501";
    private static final String BLD_KOSDAQ = "dbms/MDC/STAT/standard/MDCSTAT01501";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TelegramNotificationService telegramService;
    private final com.myplatform.backend.config.SectorStockConfig sectorStockConfig;

    // 정상 거래 가능 종목 코드 셋 (KRX 기준)
    private final Set<String> activeStockCodes = ConcurrentHashMap.newKeySet();

    // 거래정지/상폐 감지된 종목 (코드 → 사유)
    private final ConcurrentHashMap<String, String> suspendedStocks = new ConcurrentHashMap<>();

    private volatile LocalDateTime lastSyncTime = null;

    /**
     * 종목이 정상 거래 가능한지 확인
     * - KRX 동기화 전이면 true 반환 (안전 모드)
     * - 동기화 후에는 KRX 목록에 있는 종목만 true
     */
    public boolean isActive(String stockCode) {
        if (activeStockCodes.isEmpty()) return true; // 동기화 전 안전 모드
        return activeStockCodes.contains(stockCode);
    }

    /**
     * 종목 목록에서 거래정지/상폐 종목 필터링
     */
    public List<String> filterActiveStocks(List<String> stockCodes) {
        if (activeStockCodes.isEmpty()) return stockCodes; // 동기화 전 안전 모드
        return stockCodes.stream()
                .filter(activeStockCodes::contains)
                .toList();
    }

    /**
     * 거래정지/상폐 감지된 종목 맵 반환
     */
    public Map<String, String> getSuspendedStocks() {
        return Collections.unmodifiableMap(suspendedStocks);
    }

    public LocalDateTime getLastSyncTime() {
        return lastSyncTime;
    }

    /**
     * 서버 시작 시 초기 동기화 (비동기)
     */
    @PostConstruct
    public void init() {
        // PostConstruct에서는 간단 로그만, 실제 동기화는 스케줄러 or 수동 호출
        log.info("[종목상태] StockStatusService 초기화 완료 - 08:30 자동 동기화 예정");
    }

    /**
     * 매일 08:30 KRX 상장종목 동기화 (장 시작 전)
     * + 섹터 종목 중 거래정지/상폐 감지 → 텔레그램 알림
     */
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledSync() {
        syncFromKrx();
        if (!activeStockCodes.isEmpty()) {
            detectSuspendedInSectors(new ArrayList<>(sectorStockConfig.getAllStockCodes()));
        }
    }

    /**
     * KRX에서 상장종목 목록 동기화
     */
    public void syncFromKrx() {
        log.info("[종목상태] KRX 상장종목 동기화 시작...");
        Set<String> newActiveCodes = ConcurrentHashMap.newKeySet();

        try {
            // KOSPI 종목 수집
            Set<String> kospiCodes = fetchKrxStockList("STK");
            newActiveCodes.addAll(kospiCodes);
            log.info("[종목상태] KOSPI 종목 {}건 수집", kospiCodes.size());

            // 1초 딜레이 (KRX 차단 방지)
            Thread.sleep(1000);

            // KOSDAQ 종목 수집
            Set<String> kosdaqCodes = fetchKrxStockList("KSQ");
            newActiveCodes.addAll(kosdaqCodes);
            log.info("[종목상태] KOSDAQ 종목 {}건 수집", kosdaqCodes.size());

        } catch (Exception e) {
            log.error("[종목상태] KRX 동기화 실패: {}", e.getMessage());
            return; // 실패 시 기존 데이터 유지
        }

        if (newActiveCodes.size() < 100) {
            log.warn("[종목상태] KRX 응답 종목 수 {}건 — 비정상적으로 적어 동기화 취소", newActiveCodes.size());
            return;
        }

        activeStockCodes.clear();
        activeStockCodes.addAll(newActiveCodes);
        lastSyncTime = LocalDateTime.now();
        log.info("[종목상태] KRX 동기화 완료 — 총 {}건 ({})", activeStockCodes.size(), lastSyncTime);
    }

    /**
     * SectorStockConfig의 종목 중 KRX에 없는 종목 감지
     * syncFromKrx() 이후 호출
     */
    public List<String> detectSuspendedInSectors(List<String> sectorStockCodes) {
        if (activeStockCodes.isEmpty()) return Collections.emptyList();

        suspendedStocks.clear();
        List<String> suspended = new ArrayList<>();

        for (String code : sectorStockCodes) {
            if (!activeStockCodes.contains(code)) {
                suspended.add(code);
                suspendedStocks.put(code, "KRX 상장 목록에 없음");
            }
        }

        if (!suspended.isEmpty()) {
            String msg = String.format(
                    "<b>⚠️ 거래정지/상폐 종목 감지</b>\n\n" +
                    "섹터 설정 종목 중 KRX 상장 목록에 없는 종목 %d건:\n%s\n\n" +
                    "⏰ %s\n━━━━━━━━━━━━━━━━\n🤖 MyPlatform 종목 상태 알림",
                    suspended.size(),
                    suspended.stream()
                            .map(c -> "  • " + c)
                            .reduce((a, b) -> a + "\n" + b)
                            .orElse(""),
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            );
            telegramService.sendMessage(msg);
            log.warn("[종목상태] 거래정지/상폐 감지 {}건: {}", suspended.size(), suspended);
        } else {
            log.info("[종목상태] 모든 섹터 종목이 KRX 상장 목록에 존재 — 정상");
        }

        return suspended;
    }

    /**
     * KRX 전종목 시세 API 호출 (시장 구분별)
     * @param mktId STK(KOSPI) or KSQ(KOSDAQ)
     */
    private Set<String> fetchKrxStockList(String mktId) {
        Set<String> codes = new HashSet<>();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("User-Agent", USER_AGENT);
            headers.set("Referer", KRX_REFERER);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("bld", BLD_KOSPI);
            params.add("locale", "ko_KR");
            params.add("mktId", mktId);
            params.add("trdDd", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            params.add("share", "1");
            params.add("money", "1");
            params.add("csvxls_is498No", "");

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    KRX_DATA_URL, HttpMethod.POST, request, String.class);

            if (response.getBody() == null) return codes;

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode dataArray = root.get("OutBlock_1");
            if (dataArray == null || !dataArray.isArray()) {
                log.warn("[종목상태] KRX {} 응답에 OutBlock_1 없음", mktId);
                return codes;
            }

            for (JsonNode item : dataArray) {
                String code = item.path("ISU_SRT_CD").asText("");
                if (code.length() == 6 && code.matches("\\d{6}")) {
                    codes.add(code);
                }
            }
        } catch (Exception e) {
            log.error("[종목상태] KRX {} 종목 조회 실패: {}", mktId, e.getMessage());
        }

        return codes;
    }
}
