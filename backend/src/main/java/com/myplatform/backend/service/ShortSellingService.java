package com.myplatform.backend.service;

import com.myplatform.backend.entity.ShortSellingBalance;
import com.myplatform.backend.repository.ShortSellingBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 공매도 잔고 서비스
 * - 네이버 금융에서 공매도 잔고 데이터 크롤링
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

    private static final String NAVER_SHORT_SELLING_URL = "https://finance.naver.com/sise/sise_short_balance.naver";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final BigDecimal HIGH_SHORT_RATIO = new BigDecimal("5.0");    // 5% 이상 = 높은 공매도
    private static final BigDecimal DANGER_SHORT_RATIO = new BigDecimal("10.0"); // 10% 이상 = 위험

    // 캐시
    private volatile Map<String, BigDecimal> shortSellingRatioCache = new ConcurrentHashMap<>();
    private volatile LocalDate cacheDate = null;

    /**
     * 네이버 금융에서 공매도 잔고 크롤링
     * URL: https://finance.naver.com/sise/sise_short_balance.naver
     * - 여러 페이지를 순회하며 데이터 수집
     */
    public void collectShortSellingData() {
        log.info("========== 공매도 잔고 크롤링 시작 ==========");
        long startTime = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        int totalSaved = 0;
        int totalFailed = 0;

        try {
            // 최대 5페이지까지 크롤링
            for (int page = 1; page <= 5; page++) {
                try {
                    List<ShortSellingBalance> pageData = crawlShortSellingPage(page, today);

                    if (pageData.isEmpty()) {
                        log.info("공매도 잔고 {}페이지: 데이터 없음 — 크롤링 종료", page);
                        break;
                    }

                    for (ShortSellingBalance data : pageData) {
                        try {
                            Optional<ShortSellingBalance> existing =
                                    repository.findByStockCodeAndTradeDate(data.getStockCode(), data.getTradeDate());

                            if (existing.isPresent()) {
                                // 기존 데이터 업데이트
                                ShortSellingBalance existingData = existing.get();
                                existingData.setStockName(data.getStockName());
                                existingData.setShortSellingVolume(data.getShortSellingVolume());
                                existingData.setShortSellingAmount(data.getShortSellingAmount());
                                existingData.setShortSellingRatio(data.getShortSellingRatio());
                                existingData.setListedShares(data.getListedShares());
                                repository.save(existingData);
                            } else {
                                repository.save(data);
                            }
                            totalSaved++;
                        } catch (Exception e) {
                            totalFailed++;
                            log.warn("공매도 잔고 저장 실패 - {} ({}): {}",
                                    data.getStockName(), data.getStockCode(), e.getMessage());
                        }
                    }

                    log.info("공매도 잔고 {}페이지 처리 완료: {}건", page, pageData.size());

                    // Rate limit: 페이지 간 500ms 대기 (네이버 차단 방지)
                    if (page < 5) {
                        Thread.sleep(500);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("공매도 잔고 크롤링 중단됨 (인터럽트)");
                    break;
                } catch (Exception e) {
                    log.warn("공매도 잔고 {}페이지 크롤링 실패: {}", page, e.getMessage());
                }
            }

            // 캐시 갱신
            refreshCache();

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("========== 공매도 잔고 크롤링 완료 ({}ms) ==========", elapsed);
            log.info("  저장: {}건, 실패: {}건", totalSaved, totalFailed);

        } catch (Exception e) {
            log.warn("공매도 잔고 크롤링 전체 실패: {}", e.getMessage());
        }
    }

    /**
     * 네이버 금융 공매도 잔고 페이지 크롤링
     */
    private List<ShortSellingBalance> crawlShortSellingPage(int page, LocalDate tradeDate) {
        List<ShortSellingBalance> result = new ArrayList<>();

        try {
            String url = NAVER_SHORT_SELLING_URL + "?page=" + page;
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(10000)
                    .get();

            // 테이블 행 파싱 (type_1 테이블 내 tbody의 tr)
            Elements rows = doc.select("table.type_1 tbody tr");
            if (rows.isEmpty()) {
                // 대안 선택자
                rows = doc.select("table.type_2 tbody tr");
            }
            if (rows.isEmpty()) {
                rows = doc.select("table[class*=type] tbody tr");
            }

            for (Element row : rows) {
                try {
                    Elements tds = row.select("td");
                    if (tds.size() < 5) {
                        continue; // 헤더 또는 빈 행 스킵
                    }

                    // 종목명 + 종목코드 추출
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

                    // 테이블 컬럼 파싱
                    // 네이버 공매도 잔고 페이지 구조:
                    // 종목명 | 공매도잔고(주) | 상장주식수 | 공매도비중(%) | 전일대비 | ...
                    BigDecimal shortSellingVolume = parseNumber(tds.get(1).text());
                    BigDecimal listedShares = parseNumber(tds.get(2).text());
                    BigDecimal shortSellingRatio = parseNumber(tds.get(3).text());

                    // 공매도 금액 계산 (잔고수량 기반, 억원 단위는 별도)
                    BigDecimal shortSellingAmount = null;
                    if (tds.size() > 4) {
                        shortSellingAmount = parseNumber(tds.get(4).text());
                    }

                    if (shortSellingRatio == null || shortSellingRatio.compareTo(BigDecimal.ZERO) == 0) {
                        continue; // 비율이 0인 데이터 스킵
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
                    log.debug("공매도 잔고 행 파싱 실패: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("공매도 잔고 페이지 크롤링 실패 (page={}): {}", page, e.getMessage());
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
        // &가 있으면 그 앞까지만
        int ampIdx = code.indexOf('&');
        if (ampIdx > 0) {
            code = code.substring(0, ampIdx);
        }
        return code.trim();
    }

    /**
     * 숫자 파싱 (콤마 제거)
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

            // 상위 20개만
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

            telegramService.sendMessage(sb.toString());
            log.info("공매도 잔고 경보 발송 완료 — {}종목 (위험: {}종목)", highStocks.size(), dangerCount);

        } catch (Exception e) {
            log.warn("공매도 잔고 알림 발송 실패: {}", e.getMessage());
        }
    }

    /**
     * 특정 종목의 공매도 비율 조회 (봇 연동용)
     * - 캐시 우선, fallback으로 DB 조회
     */
    @Transactional(readOnly = true)
    public BigDecimal getShortSellingRatio(String stockCode) {
        // 캐시 확인
        if (cacheDate != null && cacheDate.equals(LocalDate.now()) && shortSellingRatioCache.containsKey(stockCode)) {
            return shortSellingRatioCache.get(stockCode);
        }

        // DB fallback
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
     * - HIGH_SHORT_RATIO (5%) 이상인 종목코드 반환
     */
    @Transactional(readOnly = true)
    public Set<String> getHighShortSellingStockCodes() {
        try {
            // 캐시 확인
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
     * - 5% 이상이면 true
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
