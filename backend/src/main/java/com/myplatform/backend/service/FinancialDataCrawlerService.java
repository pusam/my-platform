package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockShortDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 재무 데이터 크롤링 서비스
 * - 네이버 금융에서 영업이익률, 순이익률 등 재무 지표 크롤링
 * - 분기별 재무제표 크롤링 (PEG, 턴어라운드 스크리너용)
 * - KIS API로 수집할 수 없는 데이터 보완용
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class FinancialDataCrawlerService {

    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final StockShortDataRepository stockShortDataRepository;

    private static final String NAVER_FINANCE_URL = "https://finance.naver.com/item/main.naver?code=";
    private static final String NAVER_FINANCE_DETAIL_URL = "https://finance.naver.com/item/coinfo.naver?code=";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * 전 종목 영업이익률 크롤링
     * - StockFinancialData 테이블에 있는 종목 대상
     * - 이미 operatingMargin이 있는 종목은 스킵 (forceUpdate=false 시)
     *
     * @param forceUpdate true면 이미 데이터가 있어도 업데이트
     * @return 크롤링 결과
     */
    public Map<String, Object> crawlAllOperatingMargin(boolean forceUpdate) {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        log.info("========== 영업이익률 크롤링 시작 (forceUpdate: {}) ==========", forceUpdate);

        // 오늘 날짜의 재무 데이터가 있는 종목 조회
        LocalDate today = LocalDate.now();
        List<StockFinancialData> allData = stockFinancialDataRepository.findByReportDate(today);

        if (allData.isEmpty()) {
            // 오늘 날짜 데이터가 없으면 최신 데이터 조회
            List<String> stockCodes = stockShortDataRepository.findDistinctStockCodes();
            log.info("오늘 날짜 데이터 없음. StockShortData에서 {}개 종목 코드 조회", stockCodes.size());

            for (String code : stockCodes) {
                stockFinancialDataRepository.findTopByStockCodeOrderByReportDateDesc(code)
                        .ifPresent(allData::add);
            }
        }

        int totalCount = allData.size();
        log.info("크롤링 대상 종목 수: {}", totalCount);

        if (totalCount == 0) {
            result.put("success", false);
            result.put("message", "크롤링할 종목이 없습니다. 먼저 /api/screener/collect-all을 호출하세요.");
            return result;
        }

        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;
        int progressInterval = Math.max(totalCount / 20, 1);

        for (int i = 0; i < allData.size(); i++) {
            StockFinancialData data = allData.get(i);

            // 이미 영업이익률이 있고 forceUpdate가 false면 스킵
            if (!forceUpdate && data.getOperatingMargin() != null
                    && data.getOperatingMargin().compareTo(BigDecimal.ZERO) != 0) {
                skipCount++;
                continue;
            }

            try {
                // Rate Limit: 500ms 대기 (네이버 차단 방지)
                if (i > 0) {
                    Thread.sleep(500);
                }

                Map<String, BigDecimal> financials = crawlFinancialRatios(data.getStockCode());

                if (financials != null && !financials.isEmpty()) {
                    // 영업이익률 업데이트
                    if (financials.containsKey("operatingMargin")) {
                        data.setOperatingMargin(financials.get("operatingMargin"));
                    }
                    // 순이익률 업데이트
                    if (financials.containsKey("netMargin")) {
                        data.setNetMargin(financials.get("netMargin"));
                    }
                    // ROE 업데이트 (크롤링 값이 더 정확할 수 있음)
                    if (financials.containsKey("roe")) {
                        data.setRoe(financials.get("roe"));
                    }
                    // 부채비율 업데이트
                    if (financials.containsKey("debtRatio")) {
                        data.setDebtRatio(financials.get("debtRatio"));
                    }

                    // 종목명이 없거나 종목코드와 같은 경우 수정
                    fixStockNameIfNeeded(data);

                    stockFinancialDataRepository.save(data);
                    successCount++;
                    log.debug("크롤링 성공: {} ({}) - 영업이익률: {}%",
                            data.getStockName(), data.getStockCode(), financials.get("operatingMargin"));
                } else {
                    failCount++;
                    log.debug("크롤링 실패 (데이터 없음): {}", data.getStockCode());
                }

                // 진행률 로깅
                if ((i + 1) % progressInterval == 0 || i == totalCount - 1) {
                    int progress = (int) (((i + 1) * 100.0) / totalCount);
                    log.info("진행률: {}/{} ({}%) - 성공: {}, 실패: {}, 스킵: {}",
                            i + 1, totalCount, progress, successCount, failCount, skipCount);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("크롤링 중단됨");
                break;
            } catch (Exception e) {
                log.error("크롤링 실패 [{}]: {}", data.getStockCode(), e.getMessage());
                failCount++;
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("========== 영업이익률 크롤링 완료 ==========");
        log.info("총 {}개 종목 중 성공: {}, 실패: {}, 스킵: {}, 소요시간: {}초",
                totalCount, successCount, failCount, skipCount, elapsedTime / 1000);

        result.put("success", true);
        result.put("total", totalCount);
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("skipCount", skipCount);
        result.put("elapsedSeconds", elapsedTime / 1000);
        result.put("message", String.format("영업이익률 크롤링 완료 (성공: %d, 실패: %d, 스킵: %d)",
                successCount, failCount, skipCount));

        return result;
    }

    /**
     * 단일 종목 재무비율 크롤링
     * - 네이버 금융에서 영업이익률, 순이익률, ROE, 부채비율 추출
     *
     * @param stockCode 종목코드
     * @return 재무비율 Map (operatingMargin, netMargin, roe, debtRatio)
     */
    public Map<String, BigDecimal> crawlFinancialRatios(String stockCode) {
        Map<String, BigDecimal> ratios = new HashMap<>();

        try {
            String url = NAVER_FINANCE_URL + stockCode;
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(10000)
                    .get();

            // 네이버 금융 재무정보 테이블에서 데이터 추출
            // 투자지표 섹션의 테이블 파싱
            Elements tables = doc.select("div.section.cop_analysis table");

            for (Element table : tables) {
                Elements rows = table.select("tr");

                for (Element row : rows) {
                    String header = row.select("th").text().trim();
                    Elements tds = row.select("td");

                    if (tds.isEmpty()) continue;

                    // 첫 번째 td가 최근 데이터
                    String value = tds.first().text().trim();

                    if (header.contains("영업이익률")) {
                        BigDecimal margin = parsePercentage(value);
                        if (margin != null) {
                            ratios.put("operatingMargin", margin);
                        }
                    } else if (header.contains("순이익률")) {
                        BigDecimal margin = parsePercentage(value);
                        if (margin != null) {
                            ratios.put("netMargin", margin);
                        }
                    } else if (header.contains("ROE")) {
                        BigDecimal roe = parsePercentage(value);
                        if (roe != null) {
                            ratios.put("roe", roe);
                        }
                    } else if (header.contains("부채비율")) {
                        BigDecimal debt = parsePercentage(value);
                        if (debt != null) {
                            ratios.put("debtRatio", debt);
                        }
                    }
                }
            }

            // 투자지표 테이블에서 추가 정보 추출
            Elements investTables = doc.select("table.per_table");
            for (Element table : investTables) {
                Elements rows = table.select("tr");
                for (Element row : rows) {
                    String header = row.select("th, em").text().trim();
                    String value = row.select("td").text().trim();

                    if (header.contains("ROE") && !ratios.containsKey("roe")) {
                        BigDecimal roe = parsePercentage(value);
                        if (roe != null) {
                            ratios.put("roe", roe);
                        }
                    }
                }
            }

            // 기업현황 테이블에서도 시도
            Element corpSection = doc.selectFirst("div.corp_group2");
            if (corpSection != null) {
                Elements dlItems = corpSection.select("dl");
                for (Element dl : dlItems) {
                    String dt = dl.select("dt").text().trim();
                    String dd = dl.select("dd").text().trim();

                    if (dt.contains("영업이익률") && !ratios.containsKey("operatingMargin")) {
                        BigDecimal margin = parsePercentage(dd);
                        if (margin != null) {
                            ratios.put("operatingMargin", margin);
                        }
                    }
                }
            }

            // FnGuide 스타일 테이블에서 추출 시도
            Elements fnTables = doc.select("table.tb_type1");
            for (Element table : fnTables) {
                Elements thElements = table.select("thead th");
                Elements rows = table.select("tbody tr");

                for (Element row : rows) {
                    String rowHeader = row.select("th").text().trim();
                    Elements tds = row.select("td");

                    if (tds.isEmpty()) continue;

                    // 최근 연간 또는 분기 데이터 (보통 마지막 또는 마지막에서 두 번째)
                    String value = tds.size() > 1 ? tds.get(tds.size() - 2).text().trim()
                                                  : tds.first().text().trim();

                    if (rowHeader.contains("영업이익률") && !ratios.containsKey("operatingMargin")) {
                        BigDecimal margin = parsePercentage(value);
                        if (margin != null) {
                            ratios.put("operatingMargin", margin);
                        }
                    } else if (rowHeader.contains("순이익률") && !ratios.containsKey("netMargin")) {
                        BigDecimal margin = parsePercentage(value);
                        if (margin != null) {
                            ratios.put("netMargin", margin);
                        }
                    } else if (rowHeader.contains("ROE") && !ratios.containsKey("roe")) {
                        BigDecimal roe = parsePercentage(value);
                        if (roe != null) {
                            ratios.put("roe", roe);
                        }
                    }
                }
            }

            log.trace("종목 {} 크롤링 결과: {}", stockCode, ratios);
            return ratios;

        } catch (Exception e) {
            log.debug("크롤링 실패 [{}]: {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * 단일 종목 영업이익률 크롤링 및 저장
     */
    public boolean crawlSingleStock(String stockCode) {
        try {
            Map<String, BigDecimal> financials = crawlFinancialRatios(stockCode);
            if (financials == null || financials.isEmpty()) {
                return false;
            }

            Optional<StockFinancialData> dataOpt = stockFinancialDataRepository
                    .findTopByStockCodeOrderByReportDateDesc(stockCode);

            if (dataOpt.isEmpty()) {
                log.warn("종목 {} 의 재무 데이터가 없습니다. 먼저 collect를 실행하세요.", stockCode);
                return false;
            }

            StockFinancialData data = dataOpt.get();
            if (financials.containsKey("operatingMargin")) {
                data.setOperatingMargin(financials.get("operatingMargin"));
            }
            if (financials.containsKey("netMargin")) {
                data.setNetMargin(financials.get("netMargin"));
            }
            if (financials.containsKey("roe")) {
                data.setRoe(financials.get("roe"));
            }
            if (financials.containsKey("debtRatio")) {
                data.setDebtRatio(financials.get("debtRatio"));
            }

            // 종목명이 없거나 종목코드와 같은 경우 수정
            fixStockNameIfNeeded(data);

            stockFinancialDataRepository.save(data);
            log.info("크롤링 저장 완료: {} ({}) - 영업이익률: {}%",
                    data.getStockName(), stockCode, financials.get("operatingMargin"));
            return true;

        } catch (Exception e) {
            log.error("크롤링 저장 실패 [{}]: {}", stockCode, e.getMessage());
            return false;
        }
    }

    /**
     * 백분율 문자열 파싱
     * - "12.34%", "12.34", "-5.67" 등 처리
     */
    private BigDecimal parsePercentage(String value) {
        if (value == null || value.isEmpty() || "-".equals(value) || "N/A".equalsIgnoreCase(value)) {
            return null;
        }

        try {
            // % 기호 제거
            value = value.replace("%", "").trim();
            // 콤마 제거
            value = value.replace(",", "");
            // 공백 제거
            value = value.replaceAll("\\s+", "");

            if (value.isEmpty() || "-".equals(value)) {
                return null;
            }

            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            log.trace("숫자 파싱 실패: {}", value);
            return null;
        }
    }

    /**
     * 영업이익률이 없는 종목 수 조회
     */
    public long countMissingOperatingMargin() {
        List<StockFinancialData> allData = stockFinancialDataRepository.findAll();
        return allData.stream()
                .filter(d -> d.getOperatingMargin() == null || d.getOperatingMargin().compareTo(BigDecimal.ZERO) == 0)
                .count();
    }

    /**
     * 영업이익률이 있는 종목 수 조회
     */
    public long countWithOperatingMargin() {
        List<StockFinancialData> allData = stockFinancialDataRepository.findAll();
        return allData.stream()
                .filter(d -> d.getOperatingMargin() != null && d.getOperatingMargin().compareTo(BigDecimal.ZERO) != 0)
                .count();
    }

    /**
     * 성장률 데이터가 있는 종목 수 조회 (PEG 스크리너용)
     * - epsGrowth 또는 profitGrowth가 있는 종목
     */
    public long countWithGrowthData() {
        List<StockFinancialData> allData = stockFinancialDataRepository.findAll();
        return allData.stream()
                .filter(d -> (d.getEpsGrowth() != null && d.getEpsGrowth().compareTo(BigDecimal.ZERO) > 0) ||
                             (d.getProfitGrowth() != null && d.getProfitGrowth().compareTo(BigDecimal.ZERO) > 0))
                .count();
    }

    /**
     * 종목명이 없거나 종목코드와 같은 경우 수정
     * 1. StockShortData에서 조회
     * 2. 네이버 금융에서 크롤링
     */
    private void fixStockNameIfNeeded(StockFinancialData data) {
        String stockCode = data.getStockCode();
        String stockName = data.getStockName();

        // 종목명이 유효하면 스킵
        if (stockName != null && !stockName.isEmpty()
                && !stockName.equals(stockCode) && !stockName.matches("^\\d{6}$")) {
            return;
        }

        // 1. StockShortData에서 조회
        String nameFromShortData = stockShortDataRepository
                .findByStockCodeOrderByTradeDateDesc(stockCode, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(s -> s.getStockName())
                .orElse(null);

        if (nameFromShortData != null && !nameFromShortData.isEmpty()
                && !nameFromShortData.equals(stockCode)) {
            data.setStockName(nameFromShortData);
            log.debug("종목명 수정 (ShortData): {} -> {}", stockCode, nameFromShortData);
            return;
        }

        // 2. 네이버 금융에서 크롤링
        String nameFromNaver = crawlStockName(stockCode);
        if (nameFromNaver != null && !nameFromNaver.isEmpty()) {
            data.setStockName(nameFromNaver);
            log.debug("종목명 수정 (네이버): {} -> {}", stockCode, nameFromNaver);
        }
    }

    /**
     * 네이버 금융에서 종목명 크롤링
     */
    public String crawlStockName(String stockCode) {
        try {
            String url = NAVER_FINANCE_URL + stockCode;
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(10000)
                    .get();

            // 방법 1: 페이지 타이틀에서 추출 (예: "삼성전자 : 네이버 금융")
            String title = doc.title();
            if (title != null && title.contains(":")) {
                String name = title.split(":")[0].trim();
                if (!name.isEmpty() && !name.equals(stockCode)) {
                    return name;
                }
            }

            // 방법 2: wrap_company h2에서 추출
            Element h2 = doc.selectFirst("div.wrap_company h2 a");
            if (h2 != null) {
                String name = h2.text().trim();
                if (!name.isEmpty() && !name.equals(stockCode)) {
                    return name;
                }
            }

            // 방법 3: 종목명 span에서 추출
            Element nameSpan = doc.selectFirst("div.rate_info span.blind");
            if (nameSpan != null) {
                String name = nameSpan.text().trim();
                if (name.contains("현재가")) {
                    // "삼성전자 현재가" 형태에서 종목명 추출
                    name = name.replace("현재가", "").trim();
                    if (!name.isEmpty() && !name.equals(stockCode)) {
                        return name;
                    }
                }
            }

            return null;
        } catch (Exception e) {
            log.debug("종목명 크롤링 실패 [{}]: {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * 종목명이 잘못된(종목코드와 같은) 데이터 수정
     * - 기존 데이터 일괄 수정용
     */
    public Map<String, Object> fixAllStockNames() {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        log.info("========== 종목명 일괄 수정 시작 ==========");

        List<StockFinancialData> allData = stockFinancialDataRepository.findAll();
        int totalCount = allData.size();
        int fixedCount = 0;
        int failCount = 0;
        int skipCount = 0;

        for (int i = 0; i < allData.size(); i++) {
            StockFinancialData data = allData.get(i);
            String stockCode = data.getStockCode();
            String stockName = data.getStockName();

            // 종목명이 유효하면 스킵
            if (stockName != null && !stockName.isEmpty()
                    && !stockName.equals(stockCode) && !stockName.matches("^\\d{6}$")) {
                skipCount++;
                continue;
            }

            try {
                // Rate limit
                if (fixedCount > 0 && fixedCount % 10 == 0) {
                    Thread.sleep(100);
                }

                fixStockNameIfNeeded(data);

                if (data.getStockName() != null && !data.getStockName().equals(stockCode)) {
                    stockFinancialDataRepository.save(data);
                    fixedCount++;
                } else {
                    failCount++;
                }

                // 진행률
                if ((i + 1) % 100 == 0) {
                    log.info("진행률: {}/{} - 수정: {}, 실패: {}, 스킵: {}",
                            i + 1, totalCount, fixedCount, failCount, skipCount);
                }

            } catch (Exception e) {
                log.error("종목명 수정 실패 [{}]: {}", stockCode, e.getMessage());
                failCount++;
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("========== 종목명 일괄 수정 완료 ==========");
        log.info("총 {}개 중 수정: {}, 실패: {}, 스킵: {}, 소요시간: {}초",
                totalCount, fixedCount, failCount, skipCount, elapsedTime / 1000);

        result.put("success", true);
        result.put("total", totalCount);
        result.put("fixedCount", fixedCount);
        result.put("failCount", failCount);
        result.put("skipCount", skipCount);
        result.put("elapsedSeconds", elapsedTime / 1000);

        return result;
    }

    // ========== 분기별 재무제표 크롤링 (PEG, 턴어라운드용) ==========

    /**
     * 전 종목 분기별 재무제표 수집
     * - 네이버 금융에서 최근 4개 분기 데이터 크롤링
     * - 매출액, 영업이익, 당기순이익, EPS 수집
     * - EPS 성장률 계산 및 저장
     * - 과거 분기 데이터를 별도 레코드로 저장 (턴어라운드 분석용)
     *
     * @return 수집 결과
     */
    public Map<String, Object> collectQuarterlyFinancialStatements() {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        log.info("========== 분기별 재무제표 수집 시작 ==========");

        // StockFinancialData에 있는 종목 대상
        List<String> stockCodes = stockFinancialDataRepository.findAllStockCodes();

        if (stockCodes.isEmpty()) {
            // 종목 코드가 없으면 StockShortData에서 조회
            stockCodes = stockShortDataRepository.findDistinctStockCodes();
        }

        int totalCount = stockCodes.size();
        log.info("수집 대상 종목 수: {}", totalCount);

        if (totalCount == 0) {
            result.put("success", false);
            result.put("message", "수집할 종목이 없습니다.");
            return result;
        }

        int successCount = 0;
        int failCount = 0;
        int progressInterval = Math.max(totalCount / 20, 1);

        for (int i = 0; i < stockCodes.size(); i++) {
            String stockCode = stockCodes.get(i);

            try {
                // Rate Limit: 600ms 대기 (네이버 차단 방지)
                if (i > 0) {
                    Thread.sleep(600);
                }

                boolean collected = collectSingleStockQuarterlyData(stockCode);
                if (collected) {
                    successCount++;
                } else {
                    failCount++;
                }

                // 진행률 로깅
                if ((i + 1) % progressInterval == 0 || i == totalCount - 1) {
                    int progress = (int) (((i + 1) * 100.0) / totalCount);
                    log.info("진행률: {}/{} ({}%) - 성공: {}, 실패: {}",
                            i + 1, totalCount, progress, successCount, failCount);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("수집 중단됨");
                break;
            } catch (Exception e) {
                log.error("종목 {} 분기 데이터 수집 실패: {}", stockCode, e.getMessage());
                failCount++;
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("========== 분기별 재무제표 수집 완료 ==========");
        log.info("총 {}개 종목 중 성공: {}, 실패: {}, 소요시간: {}초",
                totalCount, successCount, failCount, elapsedTime / 1000);

        result.put("success", true);
        result.put("total", totalCount);
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("elapsedSeconds", elapsedTime / 1000);
        result.put("message", String.format("분기별 재무제표 수집 완료 (성공: %d, 실패: %d)", successCount, failCount));

        return result;
    }

    /**
     * 단일 종목 분기별 재무제표 수집
     * - 네이버 금융 종목분석 페이지에서 분기별 데이터 크롤링
     *
     * @param stockCode 종목코드
     * @return 성공 여부
     */
    public boolean collectSingleStockQuarterlyData(String stockCode) {
        try {
            // 네이버 금융 종목분석 페이지 크롤링
            List<QuarterlyFinancialData> quarterlyData = crawlQuarterlyFinancials(stockCode);

            if (quarterlyData == null || quarterlyData.isEmpty()) {
                log.debug("종목 {} 분기 데이터 없음", stockCode);
                return false;
            }

            // 기존 종목 정보 조회 (종목명, 시장 등)
            Optional<StockFinancialData> existingOpt = stockFinancialDataRepository
                    .findTopByStockCodeOrderByReportDateDesc(stockCode);

            String stockName = stockCode;
            String market = "KOSPI";
            BigDecimal currentPrice = null;
            BigDecimal marketCap = null;
            BigDecimal per = null;
            BigDecimal pbr = null;
            BigDecimal roe = null;

            if (existingOpt.isPresent()) {
                StockFinancialData existing = existingOpt.get();
                stockName = existing.getStockName() != null ? existing.getStockName() : stockCode;
                market = existing.getMarket() != null ? existing.getMarket() : "KOSPI";
                currentPrice = existing.getCurrentPrice();
                marketCap = existing.getMarketCap();
                per = existing.getPer();
                pbr = existing.getPbr();
                roe = existing.getRoe();
            }

            // EPS 성장률 계산 (최신 분기 vs 전년 동기)
            BigDecimal epsGrowth = calculateEpsGrowth(quarterlyData);

            // 각 분기별 데이터 저장
            for (QuarterlyFinancialData qData : quarterlyData) {
                LocalDate reportDate = qData.getReportDate();

                // 해당 날짜에 이미 데이터가 있는지 확인
                Optional<StockFinancialData> existingQOpt = stockFinancialDataRepository
                        .findByStockCodeAndReportDate(stockCode, reportDate);

                StockFinancialData financialData;
                if (existingQOpt.isPresent()) {
                    financialData = existingQOpt.get();
                } else {
                    financialData = new StockFinancialData();
                    financialData.setStockCode(stockCode);
                    financialData.setReportDate(reportDate);
                }

                financialData.setStockName(stockName);
                financialData.setMarket(market);

                // 분기별 재무 데이터 설정
                financialData.setRevenue(qData.getRevenue());
                financialData.setOperatingProfit(qData.getOperatingProfit());
                financialData.setNetIncome(qData.getNetIncome());
                financialData.setEps(qData.getEps());

                // 가격 정보는 최신 데이터인 경우만 설정
                if (reportDate.equals(LocalDate.now()) || quarterlyData.indexOf(qData) == 0) {
                    if (currentPrice != null) financialData.setCurrentPrice(currentPrice);
                    if (marketCap != null) financialData.setMarketCap(marketCap);
                    if (per != null) financialData.setPer(per);
                    if (pbr != null) financialData.setPbr(pbr);
                    if (roe != null) financialData.setRoe(roe);

                    // EPS 성장률과 PEG 설정 (최신 데이터에만)
                    if (epsGrowth != null) {
                        financialData.setEpsGrowth(epsGrowth);
                        // PEG 계산
                        if (per != null && per.compareTo(BigDecimal.ZERO) > 0 &&
                            epsGrowth.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal peg = per.divide(epsGrowth, 2, java.math.RoundingMode.HALF_UP);
                            financialData.setPeg(peg);
                        }
                    }
                }

                // 영업이익률 계산
                if (qData.getRevenue() != null && qData.getRevenue().compareTo(BigDecimal.ZERO) > 0 &&
                    qData.getOperatingProfit() != null) {
                    BigDecimal operatingMargin = qData.getOperatingProfit()
                            .divide(qData.getRevenue(), 4, java.math.RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));
                    financialData.setOperatingMargin(operatingMargin);
                }

                // 순이익률 계산
                if (qData.getRevenue() != null && qData.getRevenue().compareTo(BigDecimal.ZERO) > 0 &&
                    qData.getNetIncome() != null) {
                    BigDecimal netMargin = qData.getNetIncome()
                            .divide(qData.getRevenue(), 4, java.math.RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));
                    financialData.setNetMargin(netMargin);
                }

                // 순이익 증가율 계산 (직전 분기 대비)
                int idx = quarterlyData.indexOf(qData);
                if (idx < quarterlyData.size() - 1) {
                    QuarterlyFinancialData prevQ = quarterlyData.get(idx + 1);
                    if (prevQ.getNetIncome() != null && prevQ.getNetIncome().compareTo(BigDecimal.ZERO) != 0) {
                        BigDecimal profitGrowth = qData.getNetIncome()
                                .subtract(prevQ.getNetIncome())
                                .divide(prevQ.getNetIncome().abs(), 4, java.math.RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"));
                        financialData.setProfitGrowth(profitGrowth);
                    }
                }

                stockFinancialDataRepository.save(financialData);
            }

            log.debug("분기 데이터 저장 완료: {} ({}) - {}개 분기", stockName, stockCode, quarterlyData.size());
            return true;

        } catch (Exception e) {
            log.debug("분기 데이터 수집 실패 [{}]: {}", stockCode, e.getMessage());
            return false;
        }
    }

    /**
     * 네이버 금융에서 분기별 재무제표 크롤링
     * - 종목분석 > 기업현황 > 투자지표 테이블에서 분기 데이터 추출
     *
     * @param stockCode 종목코드
     * @return 분기별 재무 데이터 리스트 (최신순)
     */
    private List<QuarterlyFinancialData> crawlQuarterlyFinancials(String stockCode) {
        List<QuarterlyFinancialData> result = new ArrayList<>();

        try {
            // 1. 메인 페이지에서 기본 분기 데이터 조회
            String url = NAVER_FINANCE_URL + stockCode;
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(15000)
                    .get();

            // 투자지표 테이블 (분기 데이터 포함)
            // tb_type1 tb_num 클래스를 가진 테이블에서 분기별 데이터 추출
            Elements tables = doc.select("table.tb_type1.tb_num");

            // 분기 헤더 파싱 (예: 2024.12, 2024.09, 2024.06, 2024.03)
            List<LocalDate> quarterDates = new ArrayList<>();

            for (Element table : tables) {
                // thead에서 분기 정보 추출
                Elements thElements = table.select("thead th");
                for (Element th : thElements) {
                    String text = th.text().trim();
                    // "2024.12(E)" 또는 "2024.09" 형태
                    if (text.matches("\\d{4}\\.\\d{2}.*")) {
                        String dateStr = text.replaceAll("\\(.*\\)", "").trim();
                        try {
                            String[] parts = dateStr.split("\\.");
                            int year = Integer.parseInt(parts[0]);
                            int month = Integer.parseInt(parts[1]);
                            // 분기 마지막 날로 설정
                            LocalDate qDate = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
                            quarterDates.add(qDate);
                        } catch (Exception ignored) {}
                    }
                }

                // 분기별 실적 데이터 파싱
                if (!quarterDates.isEmpty()) {
                    Elements rows = table.select("tbody tr");

                    Map<String, List<BigDecimal>> dataMap = new HashMap<>();

                    for (Element row : rows) {
                        String rowHeader = row.select("th").text().trim();
                        Elements tds = row.select("td");

                        List<BigDecimal> values = new ArrayList<>();
                        for (Element td : tds) {
                            String val = td.text().trim();
                            values.add(parseAmount(val));
                        }

                        if (!values.isEmpty()) {
                            dataMap.put(rowHeader, values);
                        }
                    }

                    // 분기별 데이터 객체 생성
                    for (int i = 0; i < quarterDates.size() && i < 4; i++) {
                        QuarterlyFinancialData qData = new QuarterlyFinancialData();
                        qData.setReportDate(quarterDates.get(i));

                        // 매출액
                        if (dataMap.containsKey("매출액") && dataMap.get("매출액").size() > i) {
                            qData.setRevenue(dataMap.get("매출액").get(i));
                        }
                        // 영업이익
                        if (dataMap.containsKey("영업이익") && dataMap.get("영업이익").size() > i) {
                            qData.setOperatingProfit(dataMap.get("영업이익").get(i));
                        }
                        // 당기순이익
                        if (dataMap.containsKey("당기순이익") && dataMap.get("당기순이익").size() > i) {
                            qData.setNetIncome(dataMap.get("당기순이익").get(i));
                        }
                        // EPS (주당순이익)
                        if (dataMap.containsKey("EPS(원)") && dataMap.get("EPS(원)").size() > i) {
                            qData.setEps(dataMap.get("EPS(원)").get(i));
                        } else if (dataMap.containsKey("EPS") && dataMap.get("EPS").size() > i) {
                            qData.setEps(dataMap.get("EPS").get(i));
                        }

                        // 데이터가 하나라도 있으면 추가
                        if (qData.getRevenue() != null || qData.getNetIncome() != null || qData.getEps() != null) {
                            result.add(qData);
                        }
                    }

                    if (!result.isEmpty()) {
                        break; // 데이터를 찾았으면 종료
                    }
                }
            }

            // 2. 기업실적분석 iframe에서 더 정확한 데이터 조회 (fallback)
            if (result.isEmpty()) {
                result = crawlFromFnGuide(stockCode);
            }

            return result;

        } catch (Exception e) {
            log.debug("분기 재무제표 크롤링 실패 [{}]: {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * FnGuide 스타일 테이블에서 분기 데이터 크롤링 (fallback)
     */
    private List<QuarterlyFinancialData> crawlFromFnGuide(String stockCode) {
        List<QuarterlyFinancialData> result = new ArrayList<>();

        try {
            // 네이버 금융 종목분석 페이지
            String url = "https://navercomp.wisereport.co.kr/v2/company/c1010001.aspx?cmp_cd=" + stockCode;
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(15000)
                    .referrer("https://finance.naver.com/")
                    .get();

            // 분기 실적 테이블 찾기
            Elements tables = doc.select("table.gHead01");

            for (Element table : tables) {
                // 테이블 컬럼 헤더에서 분기 정보 추출
                Elements headers = table.select("thead th");
                List<LocalDate> quarterDates = new ArrayList<>();

                for (Element th : headers) {
                    String text = th.text().trim();
                    // "2024/12" 또는 "24/12" 형태
                    if (text.matches("\\d{2,4}/\\d{2}.*")) {
                        try {
                            String[] parts = text.replaceAll("\\(.*\\)", "").split("/");
                            int year = Integer.parseInt(parts[0]);
                            if (year < 100) year += 2000;
                            int month = Integer.parseInt(parts[1].substring(0, 2));
                            LocalDate qDate = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
                            quarterDates.add(qDate);
                        } catch (Exception ignored) {}
                    }
                }

                if (quarterDates.isEmpty()) continue;

                // 실적 데이터 파싱
                Elements rows = table.select("tbody tr");
                Map<String, List<BigDecimal>> dataMap = new HashMap<>();

                for (Element row : rows) {
                    String rowHeader = row.select("th, td.bg").text().trim();
                    Elements tds = row.select("td:not(.bg)");

                    List<BigDecimal> values = new ArrayList<>();
                    for (Element td : tds) {
                        values.add(parseAmount(td.text().trim()));
                    }

                    if (!values.isEmpty() && !rowHeader.isEmpty()) {
                        dataMap.put(rowHeader, values);
                    }
                }

                // 분기별 데이터 객체 생성
                for (int i = 0; i < quarterDates.size() && i < 4; i++) {
                    QuarterlyFinancialData qData = new QuarterlyFinancialData();
                    qData.setReportDate(quarterDates.get(i));

                    // 매출액
                    for (String key : Arrays.asList("매출액", "영업수익", "순영업수익")) {
                        if (dataMap.containsKey(key) && dataMap.get(key).size() > i) {
                            qData.setRevenue(dataMap.get(key).get(i));
                            break;
                        }
                    }
                    // 영업이익
                    for (String key : Arrays.asList("영업이익", "영업손익")) {
                        if (dataMap.containsKey(key) && dataMap.get(key).size() > i) {
                            qData.setOperatingProfit(dataMap.get(key).get(i));
                            break;
                        }
                    }
                    // 당기순이익
                    for (String key : Arrays.asList("당기순이익", "지배주주순이익", "순이익")) {
                        if (dataMap.containsKey(key) && dataMap.get(key).size() > i) {
                            qData.setNetIncome(dataMap.get(key).get(i));
                            break;
                        }
                    }
                    // EPS
                    for (String key : Arrays.asList("EPS(원)", "EPS", "주당순이익")) {
                        if (dataMap.containsKey(key) && dataMap.get(key).size() > i) {
                            qData.setEps(dataMap.get(key).get(i));
                            break;
                        }
                    }

                    if (qData.getRevenue() != null || qData.getNetIncome() != null) {
                        result.add(qData);
                    }
                }

                if (!result.isEmpty()) break;
            }

        } catch (Exception e) {
            log.trace("FnGuide 크롤링 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return result;
    }

    /**
     * EPS 성장률 계산
     * - 최신 분기 EPS vs 전년 동기 EPS 비교
     * - 전년 동기 데이터가 없으면 직전 분기 대비 계산
     *
     * @param quarterlyData 분기별 데이터 리스트 (최신순)
     * @return EPS 성장률 (%)
     */
    private BigDecimal calculateEpsGrowth(List<QuarterlyFinancialData> quarterlyData) {
        if (quarterlyData == null || quarterlyData.size() < 2) {
            return null;
        }

        // 최신 분기 EPS
        BigDecimal currentEps = quarterlyData.get(0).getEps();
        if (currentEps == null) {
            return null;
        }

        // 전년 동기 EPS (4분기 전)
        BigDecimal previousEps = null;
        if (quarterlyData.size() >= 5) {
            previousEps = quarterlyData.get(4).getEps();
        }

        // 전년 동기 데이터가 없으면 직전 분기 대비
        if (previousEps == null || previousEps.compareTo(BigDecimal.ZERO) == 0) {
            previousEps = quarterlyData.get(1).getEps();
        }

        // 성장률 계산
        if (previousEps != null && previousEps.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal growth = currentEps.subtract(previousEps)
                    .divide(previousEps.abs(), 4, java.math.RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            return growth.setScale(2, java.math.RoundingMode.HALF_UP);
        }

        return null;
    }

    /**
     * 금액 문자열 파싱 (억원 단위)
     * - "1,234", "-567", "N/A" 등 처리
     */
    private BigDecimal parseAmount(String value) {
        if (value == null || value.isEmpty() || "-".equals(value) ||
            "N/A".equalsIgnoreCase(value) || "적자".equals(value)) {
            return null;
        }

        try {
            // 콤마, 공백 제거
            value = value.replace(",", "").replaceAll("\\s+", "").trim();

            // 적자전환 등 텍스트 처리
            if (value.contains("적자") || value.contains("흑자")) {
                return null;
            }

            if (value.isEmpty() || "-".equals(value)) {
                return null;
            }

            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 분기별 재무 데이터 내부 클래스
     */
    @lombok.Data
    public static class QuarterlyFinancialData {
        private LocalDate reportDate;    // 분기 기준일
        private BigDecimal revenue;      // 매출액 (억원)
        private BigDecimal operatingProfit; // 영업이익 (억원)
        private BigDecimal netIncome;    // 당기순이익 (억원)
        private BigDecimal eps;          // EPS (원)
    }
}
