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
    private final GeminiService geminiService;

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
            TelegramNotificationService telegramService,
            GeminiService geminiService) {
        this.earningsRepository = earningsRepository;
        this.watchlistRepository = watchlistRepository;
        this.telegramService = telegramService;
        this.geminiService = geminiService;

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

    // ======================== 실적 요약 ========================

    /**
     * DART 재무 수치 조회 + Gemini AI 요약
     * - /fnlttSinglAcnt.json 으로 핵심 재무 수치 조회
     * - Gemini AI 로 투자자 관점 코멘트 생성
     */
    public Map<String, Object> getEarningsSummary(String corpCode, String corpName, String reportType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("corpName", corpName);

        // 1. DART 재무 수치 조회
        List<Map<String, String>> financials = fetchFinancialAccounts(corpCode, reportType);
        result.put("financials", financials);

        // 2. 재무 데이터가 있으면 Gemini AI 요약
        if (!financials.isEmpty() && geminiService != null) {
            String aiComment = generateAiComment(corpName, financials);
            result.put("aiComment", aiComment);
        } else if (financials.isEmpty()) {
            result.put("aiComment", "DART에서 재무 수치를 조회할 수 없습니다. (기업코드 매핑 없음 또는 아직 공시 전)");
        }

        return result;
    }

    /**
     * DART 단일회사 주요계정 조회 (/fnlttSinglAcnt.json)
     */
    private List<Map<String, String>> fetchFinancialAccounts(String corpCode, String reportType) {
        if (dartApiKey == null || dartApiKey.isEmpty() || corpCode == null) {
            return Collections.emptyList();
        }

        try {
            // reprt_code: 11013=1분기, 11012=반기, 11014=3분기, 11011=사업보고서
            String reprtCode = switch (reportType) {
                case "QUARTERLY" -> "11013";
                case "SEMI_ANNUAL" -> "11012";
                case "ANNUAL" -> "11011";
                default -> "11014"; // 3분기 or 잠정
            };

            // 최근 연도
            int bsnsYear = LocalDate.now().getYear();
            // 현재가 1~3월이면 전년도 데이터 우선 조회
            if (LocalDate.now().getMonthValue() <= 3) {
                bsnsYear--;
            }

            List<Map<String, String>> allAccounts = new ArrayList<>();

            // 최근 2개 연도 시도 (당년 데이터 없으면 전년도)
            for (int year = bsnsYear; year >= bsnsYear - 1; year--) {
                List<Map<String, String>> accounts = callDartFinancialApi(corpCode, String.valueOf(year), reprtCode);
                if (!accounts.isEmpty()) {
                    allAccounts = accounts;
                    break;
                }
                Thread.sleep(300);
            }

            return allAccounts;

        } catch (Exception e) {
            log.error("[실적요약] DART 재무 조회 실패: corpCode={}, error={}", corpCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Map<String, String>> callDartFinancialApi(String corpCode, String bsnsYear, String reprtCode) {
        List<Map<String, String>> accounts = new ArrayList<>();

        try {
            String url = UriComponentsBuilder.fromUriString(DART_BASE_URL + "/fnlttSinglAcnt.json")
                    .queryParam("crtfc_key", dartApiKey)
                    .queryParam("corp_code", corpCode)
                    .queryParam("bsns_year", bsnsYear)
                    .queryParam("reprt_code", reprtCode)
                    .queryParam("fs_div", "CFS") // 연결재무제표
                    .build()
                    .toUriString();

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String status = root.has("status") ? root.get("status").asText() : "";

                if (!"000".equals(status)) {
                    // 연결재무제표 없으면 별도재무제표 시도
                    url = UriComponentsBuilder.fromUriString(DART_BASE_URL + "/fnlttSinglAcnt.json")
                            .queryParam("crtfc_key", dartApiKey)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bsns_year", bsnsYear)
                            .queryParam("reprt_code", reprtCode)
                            .queryParam("fs_div", "OFS")
                            .build()
                            .toUriString();

                    response = restTemplate.getForEntity(url, String.class);
                    if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                        return accounts;
                    }
                    root = objectMapper.readTree(response.getBody());
                    status = root.has("status") ? root.get("status").asText() : "";
                    if (!"000".equals(status)) {
                        return accounts;
                    }
                }

                JsonNode list = root.get("list");
                if (list != null && list.isArray()) {
                    // 핵심 계정과목만 필터
                    Set<String> keyAccounts = Set.of(
                            "매출액", "영업이익", "당기순이익", "자산총계",
                            "부채총계", "자본총계", "영업활동현금흐름"
                    );

                    for (JsonNode item : list) {
                        String accountNm = getTextValue(item, "account_nm");
                        if (accountNm != null && keyAccounts.contains(accountNm)) {
                            Map<String, String> account = new LinkedHashMap<>();
                            account.put("accountNm", accountNm);
                            account.put("thstrmAmount", getTextValue(item, "thstrm_amount")); // 당기
                            account.put("frmtrmAmount", getTextValue(item, "frmtrm_amount")); // 전기
                            account.put("bfefrmtrmAmount", getTextValue(item, "bfefrmtrm_amount")); // 전전기
                            account.put("bsnsYear", bsnsYear);
                            account.put("reprtCode", reprtCode);
                            accounts.add(account);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[실적요약] DART API 호출 실패: {}", e.getMessage());
        }

        return accounts;
    }

    /**
     * Gemini AI 실적 코멘트 생성
     */
    private String generateAiComment(String corpName, List<Map<String, String>> financials) {
        try {
            StringBuilder dataStr = new StringBuilder();
            for (Map<String, String> account : financials) {
                String name = account.get("accountNm");
                String current = account.get("thstrmAmount");
                String previous = account.get("frmtrmAmount");
                dataStr.append(String.format("- %s: 당기 %s / 전기 %s\n", name,
                        current != null ? current : "N/A",
                        previous != null ? previous : "N/A"));
            }

            String prompt = String.format("""
                    당신은 한국 주식시장 전문 애널리스트입니다.

                    아래는 '%s'의 최근 실적 공시 핵심 재무 수치입니다.

                    [재무 수치 (단위: 원)]
                    %s

                    위 실적을 투자자 관점에서 분석해주세요:
                    1. 매출/영업이익/순이익 전기 대비 증감 평가 (구체적 %% 포함)
                    2. 실적이 좋은지/나쁜지 한 줄 판단
                    3. 투자자에게 주는 시사점 (1-2문장)

                    반드시 한국어로, 200자 이내로 간결하게 답변해주세요.
                    숫자는 억원 단위로 환산해서 표시해주세요.
                    """, corpName, dataStr.toString());

            return geminiService.chat(prompt);
        } catch (Exception e) {
            log.error("[실적요약] AI 코멘트 생성 실패: {}", e.getMessage());
            return "AI 분석을 생성할 수 없습니다.";
        }
    }

    /**
     * corpCode를 corpName으로부터 찾기 (역매핑 + DartService 매핑 활용)
     */
    public String findCorpCodeByName(String corpName) {
        // STOCK_TO_CORP의 역매핑에서 기업명으로 찾기 위해 DartService 매핑 활용
        Map<String, String> nameToCorpCode = new LinkedHashMap<>();
        nameToCorpCode.put("삼성전자", "00126380");
        nameToCorpCode.put("SK하이닉스", "00164779");
        nameToCorpCode.put("LG에너지솔루션", "01711413");
        nameToCorpCode.put("삼성바이오로직스", "00917503");
        nameToCorpCode.put("현대자동차", "00164742");
        nameToCorpCode.put("현대차", "00164742");
        nameToCorpCode.put("기아", "00164529");
        nameToCorpCode.put("셀트리온", "00421045");
        nameToCorpCode.put("POSCO홀딩스", "00117631");
        nameToCorpCode.put("포스코홀딩스", "00117631");
        nameToCorpCode.put("NAVER", "00266961");
        nameToCorpCode.put("네이버", "00266961");
        nameToCorpCode.put("카카오", "01011885");
        nameToCorpCode.put("삼성SDI", "00126186");
        nameToCorpCode.put("LG화학", "00356361");
        nameToCorpCode.put("현대모비스", "00164788");
        nameToCorpCode.put("삼성물산", "00126263");
        nameToCorpCode.put("KB금융", "00688996");
        nameToCorpCode.put("신한지주", "00382199");
        nameToCorpCode.put("하나금융지주", "00547583");
        nameToCorpCode.put("LG", "00155856");
        nameToCorpCode.put("SK", "00401731");
        nameToCorpCode.put("LG전자", "00401731");
        nameToCorpCode.put("KT&G", "00401335");
        nameToCorpCode.put("삼성에스디에스", "00126571");
        nameToCorpCode.put("삼성전기", "00164645");
        nameToCorpCode.put("SK이노베이션", "00631518");
        nameToCorpCode.put("한국전력", "00155913");
        nameToCorpCode.put("한국전력공사", "00155913");

        // 정확 매칭
        if (nameToCorpCode.containsKey(corpName)) {
            return nameToCorpCode.get(corpName);
        }

        // 부분 매칭
        for (Map.Entry<String, String> entry : nameToCorpCode.entrySet()) {
            if (corpName.contains(entry.getKey()) || entry.getKey().contains(corpName)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private String getTextValue(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    public boolean isAvailable() {
        return dartApiKey != null && !dartApiKey.isEmpty();
    }
}
