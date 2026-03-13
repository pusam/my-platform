package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.EarningsDisclosure;
import com.myplatform.backend.entity.StockWatchlist;
import com.myplatform.backend.repository.EarningsDisclosureRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 실적공시 서비스
 *
 * [기능]
 * - DART API로 실적 관련 공시 수집 (잠정실적, 분기/반기/사업보고서)
 * - DB 저장 및 조회
 * - 관심종목 필터링
 * - 캘린더 데이터 제공
 */
@Service
@Slf4j
public class EarningsDisclosureService {

    private final EarningsDisclosureRepository earningsRepository;
    private final StockWatchlistRepository watchlistRepository;
    private final TelegramNotificationService telegramService;

    @Value("${dart.api.key:}")
    private String dartApiKey;

    private static final String DART_BASE_URL = "https://opendart.fss.or.kr/api";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // 실적공시 키워드
    private static final List<String> EARNINGS_KEYWORDS = Arrays.asList(
            "영업(잠정)실적", "잠정실적", "매출액또는손익구조",
            "사업보고서", "반기보고서", "분기보고서",
            "연결재무제표기준영업", "별도재무제표기준영업",
            "영업실적", "실적"
    );

    // 종목코드 ↔ DART 기업코드 매핑 (주요 종목)
    private static final Map<String, String> STOCK_TO_CORP = new LinkedHashMap<>();
    static {
        STOCK_TO_CORP.put("005930", "00126380");  // 삼성전자
        STOCK_TO_CORP.put("000660", "00164779");  // SK하이닉스
        STOCK_TO_CORP.put("373220", "01711413");  // LG에너지솔루션
        STOCK_TO_CORP.put("207940", "00917503");  // 삼성바이오로직스
        STOCK_TO_CORP.put("005380", "00164742");  // 현대차
        STOCK_TO_CORP.put("000270", "00164529");  // 기아
        STOCK_TO_CORP.put("068270", "00421045");  // 셀트리온
        STOCK_TO_CORP.put("005490", "00117631");  // POSCO홀딩스
        STOCK_TO_CORP.put("035420", "00266961");  // NAVER
        STOCK_TO_CORP.put("035720", "01011885");  // 카카오
        STOCK_TO_CORP.put("006400", "00126186");  // 삼성SDI
        STOCK_TO_CORP.put("051910", "00356361");  // LG화학
        STOCK_TO_CORP.put("012330", "00164788");  // 현대모비스
        STOCK_TO_CORP.put("028260", "00126263");  // 삼성물산
        STOCK_TO_CORP.put("105560", "00688996");  // KB금융
        STOCK_TO_CORP.put("055550", "00382199");  // 신한지주
        STOCK_TO_CORP.put("086790", "00547583");  // 하나금융지주
        STOCK_TO_CORP.put("003550", "00155856");  // LG
        STOCK_TO_CORP.put("034730", "00401731");  // SK
        STOCK_TO_CORP.put("066570", "00401731");  // LG전자
        STOCK_TO_CORP.put("033780", "00401335");  // KT&G
        STOCK_TO_CORP.put("018260", "00126571");  // 삼성에스디에스
        STOCK_TO_CORP.put("009150", "00164645");  // 삼성전기
        STOCK_TO_CORP.put("096770", "00631518");  // SK이노베이션
        STOCK_TO_CORP.put("015760", "00155913");  // 한국전력
    }

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public EarningsDisclosureService(
            EarningsDisclosureRepository earningsRepository,
            StockWatchlistRepository watchlistRepository,
            TelegramNotificationService telegramService) {
        this.earningsRepository = earningsRepository;
        this.watchlistRepository = watchlistRepository;
        this.telegramService = telegramService;

        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * DART에서 최근 실적공시 수집 (전체 시장)
     * - 유가증권(Y) + 코스닥(K)
     * - 실적 관련 키워드 필터
     */
    public int collectEarningsDisclosures() {
        if (dartApiKey == null || dartApiKey.isEmpty()) {
            log.warn("[실적공시] DART API Key 미설정");
            return 0;
        }

        int totalCollected = 0;
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(3);

        // 유가증권(Y) + 코스닥(K) 모두 수집
        for (String corpCls : Arrays.asList("Y", "K")) {
            try {
                totalCollected += collectByMarket(corpCls, startDate, endDate);
                Thread.sleep(500); // DART API rate limit 방지
            } catch (Exception e) {
                log.error("[실적공시] {} 시장 수집 실패: {}", corpCls, e.getMessage());
            }
        }

        log.info("[실적공시] 수집 완료 - 총 {}건 저장", totalCollected);
        return totalCollected;
    }

    private int collectByMarket(String corpCls, LocalDate startDate, LocalDate endDate) {
        int collected = 0;
        int pageNo = 1;
        int totalPage = 1;

        while (pageNo <= totalPage) {
            try {
                String url = UriComponentsBuilder.fromUriString(DART_BASE_URL + "/list.json")
                        .queryParam("crtfc_key", dartApiKey)
                        .queryParam("bgn_de", startDate.format(DATE_FMT))
                        .queryParam("end_de", endDate.format(DATE_FMT))
                        .queryParam("corp_cls", corpCls)
                        .queryParam("page_no", pageNo)
                        .queryParam("page_count", 100)
                        .build()
                        .toUriString();

                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    String status = root.has("status") ? root.get("status").asText() : "";

                    if (!"000".equals(status)) {
                        log.warn("[실적공시] DART API 오류: {}", root.has("message") ? root.get("message").asText() : status);
                        break;
                    }

                    totalPage = root.has("total_page") ? root.get("total_page").asInt() : 1;

                    JsonNode list = root.get("list");
                    if (list != null && list.isArray()) {
                        for (JsonNode item : list) {
                            String reportNm = getTextValue(item, "report_nm");
                            if (reportNm != null && isEarningsRelated(reportNm)) {
                                collected += saveDisclosure(item);
                            }
                        }
                    }
                }

                pageNo++;
                if (pageNo <= totalPage) {
                    Thread.sleep(300); // rate limit
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[실적공시] 페이지 {} 조회 실패: {}", pageNo, e.getMessage());
                break;
            }
        }

        return collected;
    }

    private int saveDisclosure(JsonNode item) {
        String rceptNo = getTextValue(item, "rcept_no");
        if (rceptNo == null || earningsRepository.existsByRceptNo(rceptNo)) {
            return 0;
        }

        String reportNm = getTextValue(item, "report_nm");
        String corpName = getTextValue(item, "corp_name");
        String corpCode = getTextValue(item, "corp_code");

        EarningsDisclosure disclosure = EarningsDisclosure.builder()
                .corpCode(corpCode)
                .corpName(corpName)
                .stockCode(findStockCode(corpCode))
                .reportNm(reportNm)
                .rceptNo(rceptNo)
                .rceptDt(getTextValue(item, "rcept_dt"))
                .flrNm(getTextValue(item, "flr_nm"))
                .rmk(getTextValue(item, "rm"))
                .disclosureType(classifyDisclosureType(reportNm))
                .fiscalYear(extractFiscalYear(reportNm))
                .fiscalQuarter(extractFiscalQuarter(reportNm))
                .build();

        earningsRepository.save(disclosure);
        return 1;
    }

    /**
     * 실적 관련 공시인지 판별
     */
    private boolean isEarningsRelated(String reportNm) {
        return EARNINGS_KEYWORDS.stream().anyMatch(reportNm::contains);
    }

    /**
     * 공시 유형 분류
     */
    private String classifyDisclosureType(String reportNm) {
        if (reportNm.contains("잠정실적") || reportNm.contains("영업(잠정)실적")
                || reportNm.contains("매출액또는손익구조")) {
            return "PRELIMINARY";
        } else if (reportNm.contains("분기보고서")) {
            return "QUARTERLY";
        } else if (reportNm.contains("반기보고서")) {
            return "SEMI_ANNUAL";
        } else if (reportNm.contains("사업보고서")) {
            return "ANNUAL";
        }
        return "OTHER";
    }

    /**
     * 사업연도 추출 (공시 제목에서)
     */
    private String extractFiscalYear(String reportNm) {
        // "제55기 사업보고서" → 제목에서 연도 추출 시도
        var matcher = java.util.regex.Pattern.compile("(\\d{4})").matcher(reportNm);
        if (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            if (year >= 2020 && year <= 2030) {
                return String.valueOf(year);
            }
        }
        return null;
    }

    /**
     * 분기 추출
     */
    private String extractFiscalQuarter(String reportNm) {
        if (reportNm.contains("1분기")) return "Q1";
        if (reportNm.contains("반기") || reportNm.contains("2분기")) return "Q2";
        if (reportNm.contains("3분기")) return "Q3";
        if (reportNm.contains("사업보고서") || reportNm.contains("4분기")) return "Q4";
        return null;
    }

    /**
     * 기업코드로 종목코드 역매핑
     */
    private String findStockCode(String corpCode) {
        if (corpCode == null) return null;
        return STOCK_TO_CORP.entrySet().stream()
                .filter(e -> e.getValue().equals(corpCode))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    // ======================== 조회 API ========================

    /**
     * 최근 실적공시 목록 (전체)
     */
    public List<EarningsDisclosure> getRecentDisclosures(int months) {
        String startDate = LocalDate.now().minusMonths(months).format(DATE_FMT);
        return earningsRepository.findRecentDisclosures(startDate);
    }

    /**
     * 날짜 범위로 조회 (캘린더용)
     */
    public List<EarningsDisclosure> getDisclosuresByDateRange(String startDate, String endDate) {
        return earningsRepository.findByRceptDtBetweenOrderByRceptDtDesc(startDate, endDate);
    }

    /**
     * 관심종목의 실적공시만 필터링
     */
    public List<EarningsDisclosure> getWatchlistDisclosures(String username) {
        List<StockWatchlist> watchlist = watchlistRepository.findByUsernameOrderByCreatedAtDesc(username);
        if (watchlist.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> stockCodes = watchlist.stream()
                .map(StockWatchlist::getStockCode)
                .collect(Collectors.toList());

        // stockCode로 매칭 + corpName으로 매칭 병합
        List<EarningsDisclosure> byCode = earningsRepository.findByStockCodeInOrderByRceptDtDesc(stockCodes);

        // stockCode가 null인 공시도 종목명으로 매칭
        List<String> stockNames = watchlist.stream()
                .map(StockWatchlist::getStockName)
                .collect(Collectors.toList());

        String startDate = LocalDate.now().minusMonths(3).format(DATE_FMT);
        List<EarningsDisclosure> all = earningsRepository.findRecentDisclosures(startDate);

        Set<String> existingRceptNos = byCode.stream()
                .map(EarningsDisclosure::getRceptNo)
                .collect(Collectors.toSet());

        // corpName 매칭 추가
        for (EarningsDisclosure d : all) {
            if (!existingRceptNos.contains(d.getRceptNo())
                    && stockNames.stream().anyMatch(name -> d.getCorpName().contains(name))) {
                byCode.add(d);
            }
        }

        byCode.sort((a, b) -> b.getRceptDt().compareTo(a.getRceptDt()));
        return byCode;
    }

    /**
     * 종목명으로 검색
     */
    public List<EarningsDisclosure> searchByCorpName(String corpName) {
        return earningsRepository.findByCorpNameContainingOrderByRceptDtDesc(corpName);
    }

    /**
     * 캘린더 데이터 (월별 그룹핑)
     */
    public Map<String, List<EarningsDisclosure>> getCalendarData(int year, int month) {
        String startDate = String.format("%04d%02d01", year, month);
        String endDate;
        if (month == 12) {
            endDate = String.format("%04d0101", year + 1);
        } else {
            endDate = String.format("%04d%02d01", year, month + 1);
        }

        List<EarningsDisclosure> disclosures = earningsRepository
                .findByRceptDtBetweenOrderByRceptDtDesc(startDate, endDate);

        return disclosures.stream()
                .collect(Collectors.groupingBy(EarningsDisclosure::getRceptDt));
    }

    /**
     * 공시 유형별 통계
     */
    public Map<String, Long> getTypeStats(int months) {
        String startDate = LocalDate.now().minusMonths(months).format(DATE_FMT);
        List<EarningsDisclosure> disclosures = earningsRepository.findRecentDisclosures(startDate);

        return disclosures.stream()
                .collect(Collectors.groupingBy(EarningsDisclosure::getDisclosureType, Collectors.counting()));
    }

    // ======================== 알림 ========================

    /**
     * 관심종목 실적공시 알림 발송
     * - 스케줄러에서 호출
     */
    public int checkAndNotifyWatchlist() {
        // 오늘 날짜 공시 확인
        String today = LocalDate.now().format(DATE_FMT);
        List<EarningsDisclosure> todayDisclosures = earningsRepository
                .findByRceptDtBetweenOrderByRceptDtDesc(today, today);

        if (todayDisclosures.isEmpty()) {
            return 0;
        }

        // 모든 사용자의 관심종목 조회
        List<StockWatchlist> allWatchlists = watchlistRepository
                .findByIsActiveAndAlertTriggeredAndTargetPriceIsNotNull(true, false);

        // 관심종목 이름 Set
        Set<String> watchlistNames = allWatchlists.stream()
                .map(StockWatchlist::getStockName)
                .collect(Collectors.toSet());

        Set<String> watchlistCodes = allWatchlists.stream()
                .map(StockWatchlist::getStockCode)
                .collect(Collectors.toSet());

        int notified = 0;
        for (EarningsDisclosure d : todayDisclosures) {
            boolean isWatchlistStock = watchlistNames.stream().anyMatch(name -> d.getCorpName().contains(name))
                    || (d.getStockCode() != null && watchlistCodes.contains(d.getStockCode()));

            if (isWatchlistStock) {
                sendEarningsAlert(d);
                notified++;
            }
        }

        return notified;
    }

    private void sendEarningsAlert(EarningsDisclosure d) {
        String typeLabel = switch (d.getDisclosureType()) {
            case "PRELIMINARY" -> "잠정실적";
            case "QUARTERLY" -> "분기보고서";
            case "SEMI_ANNUAL" -> "반기보고서";
            case "ANNUAL" -> "사업보고서";
            default -> "실적공시";
        };

        String formattedDate = d.getRceptDt().substring(0, 4) + "." +
                d.getRceptDt().substring(4, 6) + "." +
                d.getRceptDt().substring(6, 8);

        String message = String.format(
            """
            <b>📋 실적공시 알림</b>

            🏢 <b>%s</b>
            📄 %s
            🏷️ 유형: <b>%s</b>
            📅 공시일: %s

            🔗 <a href="https://dart.fss.or.kr/dsaf001/main.do?rcpNo=%s">DART에서 보기</a>

            ━━━━━━━━━━━━━━━━
            🤖 MyPlatform 실적공시 알림
            """,
            d.getCorpName(), d.getReportNm(), typeLabel, formattedDate, d.getRceptNo()
        );

        telegramService.sendMessage(message);
    }

    private String getTextValue(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    public boolean isAvailable() {
        return dartApiKey != null && !dartApiKey.isEmpty();
    }
}
