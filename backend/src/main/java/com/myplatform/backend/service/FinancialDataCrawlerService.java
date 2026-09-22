package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 재무 데이터 크롤링 서비스
 * - 네이버 금융에서 영업이익률, 순이익률 등 재무 지표 크롤링
 * - 분기별 재무제표 크롤링 (PEG, 턴어라운드 스크리너용)
 * - KIS API로 수집할 수 없는 데이터 보완용
 *
 * <p>⚠ <b>클래스 @Transactional 금지</b>: 전종목 크롤(crawlAllOperatingMargin)은
 * 종목마다 Thread.sleep(500~600) + Jsoup HTTP(15s timeout) 를 수천 회 반복 — 클래스 tx 로 감싸면 DB 커넥션
 * 1개를 수십 분~시간 pin(+ 전체 save 가 배치 끝 일괄 커밋이라 도중 크래시 시 진행분 전부 유실).
 * 원자성 요구 없음: 모든 쓰기는 독립 단건 upsert(save 명시, dirty-checking 의존 없음) + 종목별 try/catch 로
 * 부분 성공이 정상 동작. 각 save 는 Spring Data 자체 짧은 tx 로 즉시 커밋(진행분 보존).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinancialDataCrawlerService {

    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final SseEmitterService sseEmitterService;

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
            // 오늘 날짜 데이터가 없으면 종목별 최신 데이터 일괄 조회 (N+1 방지)
            allData = stockFinancialDataRepository.findLatestPerStock();
            log.info("오늘 날짜 데이터 없음. 종목별 최신 데이터 일괄 조회: {}개", allData.size());
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

                    // SSE 진행률 전송
                    String stockName = data.getStockName() != null ? data.getStockName() : data.getStockCode();
                    sseEmitterService.sendProgress("collect-all-in-one", i + 1, totalCount,
                            successCount, failCount, stockName);
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
    // 아래 3개는 예전에 findAll() 로 전 테이블(종목×분기 ≈ 2만 행)을 힙에 올린 뒤 세었다 —
    // 진행률 UI 폴링과 겹치면 1536M 컨테이너에서 GC 압박/커넥션 점유. COUNT 쿼리로 대체
    // (fixAllStockNames 가 이미 같은 이유로 findAll 을 걷어낸 것과 동일 원칙).
    public long countMissingOperatingMargin() {
        return stockFinancialDataRepository.countMissingOperatingMargin();
    }

    /**
     * 영업이익률이 있는 종목 수 조회
     */
    public long countWithOperatingMargin() {
        return stockFinancialDataRepository.countWithOperatingMargin();
    }

    /**
     * 성장률 데이터가 있는 종목 수 조회 (PEG 스크리너용)
     * - epsGrowth 또는 profitGrowth가 있는 종목
     */
    public long countWithGrowthData() {
        return stockFinancialDataRepository.countWithGrowthData();
    }

    /**
     * 종목명이 없거나 종목코드와 같은 경우 수정
     * - 네이버 금융에서 크롤링
     */
    private void fixStockNameIfNeeded(StockFinancialData data) {
        String stockCode = data.getStockCode();
        String stockName = data.getStockName();

        // 종목명이 유효하면 스킵
        if (stockName != null && !stockName.isEmpty()
                && !stockName.equals(stockCode) && !stockName.matches("^\\d{6}$")) {
            return;
        }

        // 네이버 금융에서 크롤링
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

        // 수정 대상만 DB에서 필터링해서 가져오기 (findAll 대용량 로딩 방지)
        List<StockFinancialData> candidates = stockFinancialDataRepository.findByInvalidStockName();
        // 6자리 숫자 형태 stockName은 DB 쿼리에서 안 잡히므로 추가 보강
        List<StockFinancialData> targetData = new ArrayList<>();
        int skipCount = 0;
        for (StockFinancialData d : candidates) {
            String name = d.getStockName();
            if (name == null || name.isEmpty() || name.equals(d.getStockCode())) {
                targetData.add(d);
            } else {
                skipCount++;
            }
        }
        int totalCount = targetData.size();
        int fixedCount = 0;
        int failCount = 0;

        log.info("종목명 수정 대상: {}건 (전체 스캔 대신 수정 필요 레코드만 조회)", totalCount);

        for (int i = 0; i < targetData.size(); i++) {
            StockFinancialData data = targetData.get(i);
            String stockCode = data.getStockCode();

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
                    log.info("진행률: {}/{} - 수정: {}, 실패: {}",
                            i + 1, totalCount, fixedCount, failCount);
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

    // ========== 분기별 재무제표 크롤링 — 2026-09-23 은퇴 ==========
    //
    // 소스가 죽었다. 네이버가 finance.naver.com/item/main.naver 를 stock.naver.com SPA 로
    // 302 이전했고(새 HTML 에 '영업이익' 0회, JS 렌더링), 폴백이던 navercomp.wisereport.co.kr 도
    // 값이 JS 로드라 파싱이 빈다. 2026-09-22 15:38 배치 실측: 분기 수집 성공 0 / 실패 2,662.
    // 네이버 분기 행의 마지막 대량 적재는 2026-08-27(8,542행)이고 9/4 이후 0건이었다.
    //
    // 분기 재무 단일 출처는 KIS 분기 원본 stock_quarterly_financial(V55)이다 —
    // StockFinancialDataCollector.persistQuarterlyRows 가 적재하고(2026-09-22 23:00 기준
    // 70,797행 / 2,621종목 / 2004년부터), EarningSurpriseService 가 detectFromQuarterly 로 읽는다.
    // 누적(YTD) 판정과 개별 분기 환산은 순수함수 QuarterlyFinancials 가 담당한다.
    //
    // 지웠으므로 되살리지 말 것 — 같은 URL 로 복구해도 0건이다. 새 소스가 생기면
    // V55 와 같은 테이블로 모으고, 이 클래스에 두 번째 분기 경로를 다시 만들지 않는다.
}