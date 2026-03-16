package com.myplatform.backend.service;

import com.myplatform.backend.entity.ShortSellingBalance;
import com.myplatform.backend.repository.ShortSellingBalanceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 공매도 잔고 서비스
 * - KRX(한국거래소) 공식 데이터에서 공매도 잔고 수집 (1차)
 * - 네이버 금융 크롤링 (KRX 실패 시 fallback)
 * - 공매도 비율 상위 종목 알림
 * - 자동매매봇 연동 (고공매도 종목 진입 차단)
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ShortSellingService {

    private final ShortSellingBalanceRepository repository;
    private final TelegramNotificationService telegramService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final String KRX_DATA_URL = "http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd";
    private static final String KRX_REFERER = "http://data.krx.co.kr/contents/MDC/MDI/mdiIO/MDIO1301";
    private static final String NAVER_SHORT_SELLING_URL = "https://finance.naver.com/sise/sise_short_balance.naver";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final BigDecimal HIGH_SHORT_RATIO = new BigDecimal("5.0");    // 5% 이상 = 높은 공매도
    private static final BigDecimal DANGER_SHORT_RATIO = new BigDecimal("10.0"); // 10% 이상 = 위험
    private static final BigDecimal BILLION_DIVISOR = new BigDecimal("100000000"); // 억원 변환용

    // 캐시
    private volatile Map<String, BigDecimal> shortSellingRatioCache = new ConcurrentHashMap<>();
    private volatile LocalDate cacheDate = null;

    /**
     * 공매도 잔고 데이터 수집
     * 1차: KRX 한국거래소 공식 데이터
     * 2차: 네이버 금융 크롤링 (KRX 실패 시 fallback)
     */
    public void collectShortSellingData() {
        log.info("========== 공매도 잔고 수집 시작 ==========");
        long startTime = System.currentTimeMillis();

        try {
            // KRX 데이터 수집 시도
            List<ShortSellingBalance> data = fetchKrxShortSellingData();

            if (data.isEmpty()) {
                // KRX 실패 시 네이버 금융 fallback
                log.warn("KRX 데이터 없음 — 네이버 금융 크롤링으로 fallback");
                data = fetchNaverShortSellingData();
            }

            if (data.isEmpty()) {
                log.warn("공매도 잔고 데이터를 수집하지 못했습니다 (KRX, 네이버 모두 실패)");
                return;
            }

            // DB 저장
            int totalSaved = 0;
            int totalFailed = 0;

            for (ShortSellingBalance item : data) {
                try {
                    Optional<ShortSellingBalance> existing =
                            repository.findByStockCodeAndTradeDate(item.getStockCode(), item.getTradeDate());

                    if (existing.isPresent()) {
                        ShortSellingBalance existingData = existing.get();
                        existingData.setStockName(item.getStockName());
                        existingData.setShortSellingVolume(item.getShortSellingVolume());
                        existingData.setShortSellingAmount(item.getShortSellingAmount());
                        existingData.setShortSellingRatio(item.getShortSellingRatio());
                        existingData.setListedShares(item.getListedShares());
                        repository.save(existingData);
                    } else {
                        repository.save(item);
                    }
                    totalSaved++;
                } catch (Exception e) {
                    totalFailed++;
                    log.warn("공매도 잔고 저장 실패 - {} ({}): {}",
                            item.getStockName(), item.getStockCode(), e.getMessage());
                }
            }

            // 캐시 갱신
            refreshCache();

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("========== 공매도 잔고 수집 완료 ({}ms) ==========", elapsed);
            log.info("  저장: {}건, 실패: {}건", totalSaved, totalFailed);

        } catch (Exception e) {
            log.warn("공매도 잔고 수집 전체 실패: {}", e.getMessage());
        }
    }

    /**
     * KRX 한국거래소에서 공매도 잔고 데이터 조회
     * - 오늘 날짜로 시도 → 데이터 없으면 직전 영업일로 재시도
     */
    private List<ShortSellingBalance> fetchKrxShortSellingData() {
        // 오늘부터 최대 5일 전까지 시도 (주말/공휴일 고려)
        LocalDate date = LocalDate.now();
        for (int attempt = 0; attempt < 5; attempt++) {
            // 주말 스킵
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY) {
                date = date.minusDays(1);
                continue;
            }
            if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                date = date.minusDays(2);
                continue;
            }

            List<ShortSellingBalance> result = fetchKrxDataForDate(date);
            if (!result.isEmpty()) {
                log.info("KRX 공매도 잔고 수집 성공 — 기준일: {}, {}건", date, result.size());
                return result;
            }

            log.info("KRX 공매도 잔고 {} 데이터 없음 — 이전 날짜 시도", date);
            date = date.minusDays(1);
        }

        log.warn("KRX 공매도 잔고 데이터 없음 (최근 5일간)");
        return Collections.emptyList();
    }

    /**
     * KRX 특정 날짜의 공매도 잔고 데이터 조회
     */
    private List<ShortSellingBalance> fetchKrxDataForDate(LocalDate tradeDate) {
        List<ShortSellingBalance> result = new ArrayList<>();

        try {
            // HTTP Headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("User-Agent", USER_AGENT);
            headers.set("Referer", KRX_REFERER);

            // Form parameters
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("bld", "dbms/MDC/STAT/srt/MDCSTAT030100");
            params.add("locale", "ko_KR");
            params.add("searchType", "1");
            params.add("mktId", "ALL");
            params.add("trdDd", tradeDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")));

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    KRX_DATA_URL, HttpMethod.POST, request, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.warn("KRX API 응답 오류 — status: {}", response.getStatusCode());
                return result;
            }

            // JSON 파싱
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode dataArray = root.get("OutBlock_1");

            if (dataArray == null || !dataArray.isArray() || dataArray.isEmpty()) {
                return result;
            }

            for (JsonNode item : dataArray) {
                try {
                    String stockCode = getJsonText(item, "ISU_SRT_CD");
                    String stockName = getJsonText(item, "ISU_ABBRV");

                    if (stockCode == null || stockCode.isEmpty() || stockName == null || stockName.isEmpty()) {
                        continue;
                    }

                    BigDecimal balanceVolume = parseCommaNumber(getJsonText(item, "BAL_QTY"));
                    BigDecimal balanceAmountWon = parseCommaNumber(getJsonText(item, "BAL_AMT"));
                    BigDecimal balanceRatio = parseCommaNumber(getJsonText(item, "BAL_RTO"));
                    BigDecimal listedShares = parseCommaNumber(getJsonText(item, "LIST_SHRS"));

                    if (balanceRatio == null || balanceRatio.compareTo(BigDecimal.ZERO) == 0) {
                        continue;
                    }

                    // 금액: 원 → 억원 변환
                    BigDecimal balanceAmountBillion = null;
                    if (balanceAmountWon != null) {
                        balanceAmountBillion = balanceAmountWon.divide(BILLION_DIVISOR, 2, RoundingMode.HALF_UP);
                    }

                    ShortSellingBalance balance = ShortSellingBalance.builder()
                            .stockCode(stockCode)
                            .stockName(stockName)
                            .tradeDate(tradeDate)
                            .shortSellingVolume(balanceVolume)
                            .shortSellingAmount(balanceAmountBillion)
                            .shortSellingRatio(balanceRatio)
                            .listedShares(listedShares)
                            .build();

                    result.add(balance);

                } catch (Exception e) {
                    log.debug("KRX 공매도 잔고 항목 파싱 실패: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("KRX 공매도 잔고 조회 실패 ({}): {}", tradeDate, e.getMessage());
        }

        return result;
    }

    /**
     * JsonNode에서 텍스트 값 안전하게 추출
     */
    private String getJsonText(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) return null;
        return field.asText().trim();
    }

    /**
     * 콤마가 포함된 숫자 문자열 파싱
     * 예: "1,234,567" → 1234567, "0.15" → 0.15
     */
    private BigDecimal parseCommaNumber(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            String cleaned = text.trim().replace(",", "");
            if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals("N/A")) {
                return null;
            }
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ================================================================
    // 네이버 금융 크롤링 (fallback)
    // ================================================================

    /**
     * 네이버 금융에서 공매도 잔고 크롤링 (KRX 실패 시 fallback)
     */
    private List<ShortSellingBalance> fetchNaverShortSellingData() {
        log.info("네이버 금융 공매도 잔고 크롤링 시작 (fallback)");
        List<ShortSellingBalance> allData = new ArrayList<>();
        LocalDate today = LocalDate.now();

        try {
            for (int page = 1; page <= 5; page++) {
                try {
                    List<ShortSellingBalance> pageData = crawlNaverShortSellingPage(page, today);

                    if (pageData.isEmpty()) {
                        log.info("네이버 공매도 잔고 {}페이지: 데이터 없음 — 크롤링 종료", page);
                        break;
                    }

                    allData.addAll(pageData);
                    log.info("네이버 공매도 잔고 {}페이지 처리 완료: {}건", page, pageData.size());

                    if (page < 5) {
                        Thread.sleep(500);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("네이버 공매도 잔고 크롤링 중단됨 (인터럽트)");
                    break;
                } catch (Exception e) {
                    log.warn("네이버 공매도 잔고 {}페이지 크롤링 실패: {}", page, e.getMessage());
                }
            }

            if (!allData.isEmpty()) {
                log.info("네이버 금융 공매도 잔고 수집 성공 (fallback) — {}건", allData.size());
            }

        } catch (Exception e) {
            log.warn("네이버 금융 공매도 잔고 크롤링 전체 실패: {}", e.getMessage());
        }

        return allData;
    }

    /**
     * 네이버 금융 공매도 잔고 페이지 크롤링
     */
    private List<ShortSellingBalance> crawlNaverShortSellingPage(int page, LocalDate tradeDate) {
        List<ShortSellingBalance> result = new ArrayList<>();

        try {
            String url = NAVER_SHORT_SELLING_URL + "?page=" + page;
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(10000)
                    .get();

            Elements rows = doc.select("table.type_1 tbody tr");
            if (rows.isEmpty()) {
                rows = doc.select("table.type_2 tbody tr");
            }
            if (rows.isEmpty()) {
                rows = doc.select("table[class*=type] tbody tr");
            }

            for (Element row : rows) {
                try {
                    Elements tds = row.select("td");
                    if (tds.size() < 5) {
                        continue;
                    }

                    Element nameLink = row.selectFirst("a[href*=main.naver]");
                    if (nameLink == null) {
                        nameLink = row.selectFirst("a[href*=main.nhn]");
                    }
                    if (nameLink == null) {
                        continue;
                    }

                    String stockName = nameLink.text().trim();
                    String href = nameLink.attr("href");
                    String stockCode = extractStockCode(href);

                    if (stockCode == null || stockCode.isEmpty() || stockName.isEmpty()) {
                        continue;
                    }

                    BigDecimal shortSellingVolume = parseNumber(tds.get(1).text());
                    BigDecimal listedShares = parseNumber(tds.get(2).text());
                    BigDecimal shortSellingRatio = parseNumber(tds.get(3).text());

                    BigDecimal shortSellingAmount = null;
                    if (tds.size() > 4) {
                        shortSellingAmount = parseNumber(tds.get(4).text());
                    }

                    if (shortSellingRatio == null || shortSellingRatio.compareTo(BigDecimal.ZERO) == 0) {
                        continue;
                    }

                    ShortSellingBalance balance = ShortSellingBalance.builder()
                            .stockCode(stockCode)
                            .stockName(stockName)
                            .tradeDate(tradeDate)
                            .shortSellingVolume(shortSellingVolume)
                            .shortSellingAmount(shortSellingAmount)
                            .shortSellingRatio(shortSellingRatio)
                            .listedShares(listedShares)
                            .build();

                    result.add(balance);

                } catch (Exception e) {
                    log.debug("네이버 공매도 잔고 행 파싱 실패: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("네이버 공매도 잔고 페이지 크롤링 실패 (page={}): {}", page, e.getMessage());
        }

        return result;
    }

    /**
     * URL에서 종목코드 추출
     * 예: /item/main.naver?code=005930 → 005930
     */
    private String extractStockCode(String href) {
        if (href == null) return null;
        int idx = href.indexOf("code=");
        if (idx < 0) return null;
        String code = href.substring(idx + 5);
        int ampIdx = code.indexOf('&');
        if (ampIdx > 0) {
            code = code.substring(0, ampIdx);
        }
        return code.trim();
    }

    /**
     * 숫자 파싱 (콤마, %, 억, 만 제거) — 네이버 fallback용
     */
    private BigDecimal parseNumber(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            String cleaned = text.trim()
                    .replace(",", "")
                    .replace("%", "")
                    .replace("억", "")
                    .replace("만", "")
                    .replace(" ", "");
            if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals("N/A")) {
                return null;
            }
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ================================================================
    // 공개 API (기존 시그니처 유지)
    // ================================================================

    /**
     * 공매도 비율 상위 종목 텔레그램 알림
     */
    public void sendHighShortSellingAlert() {
        try {
            Optional<LocalDate> latestDateOpt = repository.findLatestTradeDate();
            if (latestDateOpt.isEmpty()) {
                log.info("공매도 잔고 데이터 없음 — 알림 생략");
                return;
            }

            LocalDate latestDate = latestDateOpt.get();
            List<ShortSellingBalance> highStocks = repository.findHighShortSellingStocks(latestDate, HIGH_SHORT_RATIO);

            if (highStocks.isEmpty()) {
                log.info("공매도 비율 {}% 이상 종목 없음 — 알림 생략", HIGH_SHORT_RATIO);
                return;
            }

            List<ShortSellingBalance> topStocks = highStocks.stream()
                    .limit(20)
                    .collect(Collectors.toList());

            long dangerCount = topStocks.stream()
                    .filter(s -> s.getShortSellingRatio().compareTo(DANGER_SHORT_RATIO) >= 0)
                    .count();

            StringBuilder sb = new StringBuilder();
            sb.append("<b>\uD83D\uDCC9 공매도 잔고 경보</b>\n\n");
            sb.append(String.format("\uD83D\uDCC5 기준일: <b>%s</b>\n", latestDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))));
            sb.append(String.format("\u26A0\uFE0F 공매도 비율 %s%% 이상: <b>%d종목</b>\n", HIGH_SHORT_RATIO, highStocks.size()));

            if (dangerCount > 0) {
                sb.append(String.format("\uD83D\uDED1 위험 수준 (%s%% 이상): <b>%d종목</b>\n", DANGER_SHORT_RATIO, dangerCount));
            }

            sb.append("\n<b>[ 공매도 비율 TOP ]</b>\n");
            sb.append("━━━━━━━━━━━━━━━━\n");

            for (int i = 0; i < topStocks.size(); i++) {
                ShortSellingBalance stock = topStocks.get(i);
                String dangerIcon = stock.getShortSellingRatio().compareTo(DANGER_SHORT_RATIO) >= 0 ? "\uD83D\uDED1" : "\u26A0\uFE0F";
                sb.append(String.format("%s <b>%s</b> (%s)\n", dangerIcon, stock.getStockName(), stock.getStockCode()));
                sb.append(String.format("   공매도 비율: <b>%.2f%%</b>", stock.getShortSellingRatio()));

                if (stock.getShortSellingVolume() != null) {
                    sb.append(String.format(" | 잔고: %,.0f주", stock.getShortSellingVolume()));
                }
                sb.append("\n");

                if (i < topStocks.size() - 1) {
                    sb.append("\n");
                }
            }

            sb.append("\n━━━━━━━━━━━━━━━━\n");
            sb.append(String.format("\u23F0 %s\n", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
            sb.append("\uD83E\uDD16 MyPlatform 공매도 알림");

            telegramService.sendRisk(sb.toString());
            log.info("공매도 잔고 경보 발송 완료 — {}종목 (위험: {}종목)", highStocks.size(), dangerCount);

        } catch (Exception e) {
            log.warn("공매도 잔고 알림 발송 실패: {}", e.getMessage());
        }
    }

    /**
     * 특정 종목의 공매도 비율 조회 (봇 연동용)
     */
    @Transactional(readOnly = true)
    public BigDecimal getShortSellingRatio(String stockCode) {
        if (cacheDate != null && cacheDate.equals(LocalDate.now()) && shortSellingRatioCache.containsKey(stockCode)) {
            return shortSellingRatioCache.get(stockCode);
        }

        try {
            Optional<LocalDate> latestDateOpt = repository.findLatestTradeDate();
            if (latestDateOpt.isEmpty()) {
                return BigDecimal.ZERO;
            }

            Optional<ShortSellingBalance> balance =
                    repository.findByStockCodeAndTradeDate(stockCode, latestDateOpt.get());

            return balance.map(ShortSellingBalance::getShortSellingRatio).orElse(BigDecimal.ZERO);
        } catch (Exception e) {
            log.warn("공매도 비율 조회 실패 - {}: {}", stockCode, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * 공매도 비율 높은 종목 Set (전략 차단용)
     */
    @Transactional(readOnly = true)
    public Set<String> getHighShortSellingStockCodes() {
        try {
            if (cacheDate != null && cacheDate.equals(LocalDate.now()) && !shortSellingRatioCache.isEmpty()) {
                return shortSellingRatioCache.entrySet().stream()
                        .filter(e -> e.getValue().compareTo(HIGH_SHORT_RATIO) >= 0)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toSet());
            }

            Optional<LocalDate> latestDateOpt = repository.findLatestTradeDate();
            if (latestDateOpt.isEmpty()) {
                return Collections.emptySet();
            }

            List<ShortSellingBalance> highStocks =
                    repository.findHighShortSellingStocks(latestDateOpt.get(), HIGH_SHORT_RATIO);

            return highStocks.stream()
                    .map(ShortSellingBalance::getStockCode)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("고공매도 종목 조회 실패: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * 공매도 비율 상위 종목 조회 (API용)
     */
    @Transactional(readOnly = true)
    public List<ShortSellingBalance> getTopShortSellingStocks(int limit) {
        Optional<LocalDate> latestDateOpt = repository.findLatestTradeDate();
        if (latestDateOpt.isEmpty()) {
            return Collections.emptyList();
        }
        return repository.findTopShortSellingByDate(latestDateOpt.get(), PageRequest.of(0, limit));
    }

    /**
     * 특정 종목 공매도 이력 조회 (API용)
     */
    @Transactional(readOnly = true)
    public List<ShortSellingBalance> getStockShortSellingHistory(String stockCode) {
        return repository.findByStockCodeOrderByTradeDateDesc(stockCode);
    }

    /**
     * 고공매도 종목인지 확인 (봇 연동용)
     */
    @Transactional(readOnly = true)
    public boolean isHighShortSellingStock(String stockCode) {
        BigDecimal ratio = getShortSellingRatio(stockCode);
        return ratio.compareTo(HIGH_SHORT_RATIO) >= 0;
    }

    /**
     * 캐시 갱신
     */
    private void refreshCache() {
        try {
            Optional<LocalDate> latestDateOpt = repository.findLatestTradeDate();
            if (latestDateOpt.isEmpty()) return;

            LocalDate latestDate = latestDateOpt.get();
            List<ShortSellingBalance> allData =
                    repository.findTopShortSellingByDate(latestDate, PageRequest.of(0, 500));

            Map<String, BigDecimal> newCache = new ConcurrentHashMap<>();
            for (ShortSellingBalance data : allData) {
                if (data.getShortSellingRatio() != null) {
                    newCache.put(data.getStockCode(), data.getShortSellingRatio());
                }
            }

            shortSellingRatioCache = newCache;
            cacheDate = latestDate;
            log.info("공매도 비율 캐시 갱신 완료 — {}종목 (기준일: {})", newCache.size(), latestDate);
        } catch (Exception e) {
            log.warn("공매도 비율 캐시 갱신 실패: {}", e.getMessage());
        }
    }
}
