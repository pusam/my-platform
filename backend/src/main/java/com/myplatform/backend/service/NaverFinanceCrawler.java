package com.myplatform.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 네이버 금융 공매도 데이터 크롤러
 *
 * 크롤링 대상:
 * - 일별 공매도 거래량/비중
 * - 일별 공매도 잔고
 * - 대차거래 현황
 *
 * URL 형식:
 * - 공매도 매매비중: https://finance.naver.com/item/short_trade.naver?code=005930
 * - 대차거래: https://finance.naver.com/item/lending.naver?code=005930
 *
 * 네이버 차단 방지:
 * - 요청 간 1~3초 랜덤 딜레이 적용
 * - Chrome User-Agent 헤더 설정
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NaverFinanceCrawler {

    private static final String BASE_URL = "https://finance.naver.com";
    private static final String SHORT_SELLING_URL = BASE_URL + "/item/short_trade.naver?code=%s";
    private static final String LENDING_URL = BASE_URL + "/item/lending.naver?code=%s";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private static final int CONNECTION_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 15000;

    // 네이버 차단 방지용 설정
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int MIN_DELAY_MS = 1000;  // 최소 1초
    private static final int MAX_DELAY_MS = 3000;  // 최대 3초

    // 대차잔고 페이지 404 발생 시 더 이상 시도하지 않음 (네이버 페이지 폐지 대응)
    private static final AtomicBoolean lendingPageUnavailable = new AtomicBoolean(false);

    /**
     * 공매도 일별 데이터 크롤링
     *
     * @param stockCode 종목코드 (6자리)
     * @param days 조회할 일수 (기본 30일)
     * @return 일별 공매도 데이터 리스트
     */
    public List<ShortSellingData> crawlShortSellingData(String stockCode, int days) {
        List<ShortSellingData> result = new ArrayList<>();

        try {
            // 네이버 차단 방지: 1~3초 랜덤 딜레이
            randomDelay();

            String url = String.format(SHORT_SELLING_URL, stockCode);
            log.debug("공매도 데이터 크롤링: {}", url);

            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Referer", "https://finance.naver.com/")
                    .timeout(CONNECTION_TIMEOUT)
                    .get();

            // 공매도 거래 테이블 파싱
            // 테이블 구조: 날짜 | 공매도량 | 거래량 | 공매도비율 | 공매도거래대금 | 종가
            Element table = doc.selectFirst("table.type2");
            if (table == null) {
                log.warn("공매도 테이블을 찾을 수 없습니다: {}", stockCode);
                return result;
            }

            Elements rows = table.select("tbody tr");
            int count = 0;

            for (Element row : rows) {
                if (count >= days) break;

                Elements cells = row.select("td");
                if (cells.size() < 6) continue;

                try {
                    String dateStr = cells.get(0).text().trim();
                    if (dateStr.isEmpty() || !dateStr.contains(".")) continue;

                    LocalDate tradeDate = LocalDate.parse(dateStr, DATE_FORMATTER);
                    BigDecimal shortVolume = parseBigDecimal(cells.get(1).text());
                    BigDecimal totalVolume = parseBigDecimal(cells.get(2).text());
                    BigDecimal shortRatio = parseBigDecimal(cells.get(3).text().replace("%", ""));
                    BigDecimal shortTradingValue = parseBigDecimal(cells.get(4).text());
                    BigDecimal closePrice = parseBigDecimal(cells.get(5).text());

                    ShortSellingData data = new ShortSellingData();
                    data.setTradeDate(tradeDate);
                    data.setShortVolume(shortVolume);
                    data.setTotalVolume(totalVolume);
                    data.setShortRatio(shortRatio);
                    data.setShortTradingValue(shortTradingValue);
                    data.setClosePrice(closePrice);

                    result.add(data);
                    count++;

                } catch (Exception e) {
                    log.debug("행 파싱 실패: {}", e.getMessage());
                }
            }

            log.info("공매도 데이터 크롤링 완료 [{}]: {}건", stockCode, result.size());

        } catch (Exception e) {
            log.error("공매도 데이터 크롤링 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return result;
    }

    /**
     * 대차잔고 일별 데이터 크롤링
     *
     * 주의: 네이버 금융 lending.naver 페이지가 404를 반환하면
     * 해당 세션 동안 더 이상 시도하지 않음 (API 폐지 대응)
     *
     * @param stockCode 종목코드 (6자리)
     * @param days 조회할 일수 (기본 30일)
     * @return 일별 대차잔고 데이터 리스트
     */
    public List<LoanBalanceData> crawlLoanBalanceData(String stockCode, int days) {
        List<LoanBalanceData> result = new ArrayList<>();

        // 이미 페이지가 없다고 확인된 경우 스킵
        if (lendingPageUnavailable.get()) {
            return result;
        }

        try {
            // 네이버 차단 방지: 1~3초 랜덤 딜레이
            randomDelay();

            String url = String.format(LENDING_URL, stockCode);
            log.debug("대차잔고 데이터 크롤링: {}", url);

            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Referer", "https://finance.naver.com/")
                    .timeout(CONNECTION_TIMEOUT)
                    .get();

            // 대차거래 테이블 파싱
            // 테이블 구조: 날짜 | 신규 | 상환 | 잔고 | 잔고금액 | 공시율
            Element table = doc.selectFirst("table.type2");
            if (table == null) {
                log.warn("대차거래 테이블을 찾을 수 없습니다: {}", stockCode);
                return result;
            }

            Elements rows = table.select("tbody tr");
            int count = 0;

            for (Element row : rows) {
                if (count >= days) break;

                Elements cells = row.select("td");
                if (cells.size() < 6) continue;

                try {
                    String dateStr = cells.get(0).text().trim();
                    if (dateStr.isEmpty() || !dateStr.contains(".")) continue;

                    LocalDate tradeDate = LocalDate.parse(dateStr, DATE_FORMATTER);
                    BigDecimal newLending = parseBigDecimal(cells.get(1).text());
                    BigDecimal repayment = parseBigDecimal(cells.get(2).text());
                    BigDecimal loanBalance = parseBigDecimal(cells.get(3).text());
                    BigDecimal loanBalanceValue = parseBigDecimal(cells.get(4).text());
                    BigDecimal loanRatio = parseBigDecimal(cells.get(5).text().replace("%", ""));

                    LoanBalanceData data = new LoanBalanceData();
                    data.setTradeDate(tradeDate);
                    data.setNewLending(newLending);
                    data.setRepayment(repayment);
                    data.setLoanBalance(loanBalance);
                    data.setLoanBalanceValue(loanBalanceValue);
                    data.setLoanRatio(loanRatio);

                    result.add(data);
                    count++;

                } catch (Exception e) {
                    log.debug("행 파싱 실패: {}", e.getMessage());
                }
            }

            log.info("대차잔고 데이터 크롤링 완료 [{}]: {}건", stockCode, result.size());

        } catch (HttpStatusException e) {
            // 404 에러면 페이지가 폐지된 것으로 판단하고 더 이상 시도하지 않음
            if (e.getStatusCode() == 404) {
                if (lendingPageUnavailable.compareAndSet(false, true)) {
                    log.warn("네이버 금융 대차잔고 페이지가 폐지되었습니다 (404). 대차잔고 크롤링을 중단합니다.");
                }
            } else {
                log.debug("대차잔고 데이터 크롤링 HTTP 오류 [{}]: {}", stockCode, e.getMessage());
            }
        } catch (Exception e) {
            log.debug("대차잔고 데이터 크롤링 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return result;
    }

    /**
     * 공매도 + 대차잔고 통합 조회
     *
     * @param stockCode 종목코드
     * @param days 조회 일수
     * @return 통합 데이터 맵 (날짜 -> 데이터)
     */
    public Map<LocalDate, CombinedShortData> crawlCombinedData(String stockCode, int days) {
        Map<LocalDate, CombinedShortData> result = new LinkedHashMap<>();

        // 공매도 데이터 크롤링
        List<ShortSellingData> shortData = crawlShortSellingData(stockCode, days);
        for (ShortSellingData data : shortData) {
            CombinedShortData combined = new CombinedShortData();
            combined.setTradeDate(data.getTradeDate());
            combined.setShortVolume(data.getShortVolume());
            combined.setShortRatio(data.getShortRatio());
            combined.setClosePrice(data.getClosePrice());
            result.put(data.getTradeDate(), combined);
        }

        // 대차잔고 데이터 병합
        List<LoanBalanceData> loanData = crawlLoanBalanceData(stockCode, days);
        for (LoanBalanceData data : loanData) {
            CombinedShortData combined = result.get(data.getTradeDate());
            if (combined != null) {
                combined.setLoanBalance(data.getLoanBalance());
                combined.setLoanBalanceValue(data.getLoanBalanceValue());
                combined.setLoanRatio(data.getLoanRatio());
            } else {
                combined = new CombinedShortData();
                combined.setTradeDate(data.getTradeDate());
                combined.setLoanBalance(data.getLoanBalance());
                combined.setLoanBalanceValue(data.getLoanBalanceValue());
                combined.setLoanRatio(data.getLoanRatio());
                result.put(data.getTradeDate(), combined);
            }
        }

        return result;
    }

    /**
     * 대차잔고 페이지가 사용 가능한지 확인
     * @return true if available, false if 404 detected
     */
    public boolean isLendingPageAvailable() {
        return !lendingPageUnavailable.get();
    }

    /**
     * 대차잔고 페이지 상태 플래그 리셋 (테스트용)
     */
    public void resetLendingPageStatus() {
        lendingPageUnavailable.set(false);
        log.info("대차잔고 페이지 상태 플래그가 리셋되었습니다.");
    }

    /**
     * 숫자 문자열을 BigDecimal로 변환
     */
    private BigDecimal parseBigDecimal(String text) {
        if (text == null || text.trim().isEmpty() || text.equals("-") || text.equals("N/A")) {
            return BigDecimal.ZERO;
        }

        try {
            // 콤마, 공백 제거
            String cleaned = text.replace(",", "").replace(" ", "").trim();
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 네이버 차단 방지를 위한 랜덤 딜레이 (1~3초)
     */
    private void randomDelay() {
        try {
            int delay = ThreadLocalRandom.current().nextInt(MIN_DELAY_MS, MAX_DELAY_MS + 1);
            log.debug("네이버 요청 딜레이: {}ms", delay);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("딜레이 중단됨");
        }
    }

    // ========== 데이터 클래스 ==========

    /**
     * 공매도 거래 데이터
     */
    @lombok.Data
    public static class ShortSellingData {
        private LocalDate tradeDate;
        private BigDecimal shortVolume;      // 공매도량
        private BigDecimal totalVolume;      // 총 거래량
        private BigDecimal shortRatio;       // 공매도 비율 (%)
        private BigDecimal shortTradingValue; // 공매도 거래대금
        private BigDecimal closePrice;       // 종가
    }

    /**
     * 대차잔고 데이터
     */
    @lombok.Data
    public static class LoanBalanceData {
        private LocalDate tradeDate;
        private BigDecimal newLending;       // 신규 대차
        private BigDecimal repayment;        // 상환
        private BigDecimal loanBalance;      // 대차잔고 (주)
        private BigDecimal loanBalanceValue; // 대차잔고 (금액)
        private BigDecimal loanRatio;        // 대차비율 (%)
    }

    /**
     * 공매도 + 대차잔고 통합 데이터
     */
    @lombok.Data
    public static class CombinedShortData {
        private LocalDate tradeDate;
        private BigDecimal closePrice;
        private BigDecimal shortVolume;
        private BigDecimal shortRatio;
        private BigDecimal loanBalance;
        private BigDecimal loanBalanceValue;
        private BigDecimal loanRatio;
    }
}
