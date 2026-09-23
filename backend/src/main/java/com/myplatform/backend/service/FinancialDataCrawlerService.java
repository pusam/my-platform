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
 * 네이버 금융 크롤링 — <b>2026-09-23 기준 남은 것은 종목명 보정과 DB 카운트뿐</b>.
 *
 * <p>이 클래스의 크롤은 전부 {@code finance.naver.com/item/main.naver} 를 읽었는데, 네이버가 그 페이지를
 * {@code stock.naver.com} SPA 로 302 이전해 새 HTML 에 값이 없다(JS 렌더링). 그래서 분기 재무제표 크롤과
 * 영업이익률 크롤을 은퇴시켰다 — 분기 재무의 단일 출처는 KIS V55({@code stock_quarterly_financial}),
 * 영업이익률은 KIS 1단계 수집기다.
 *
 * <p>⚠ <b>남은 {@link #crawlStockName} 도 같은 이유로 죽어 있다</b> — 리다이렉트된 페이지 제목이
 * {@code "Npay 증권"}(콜론 없음)이고 파싱 대상 {@code wrap_company}·{@code rate_info} 도 없어 모든 종목에서
 * null 을 돌려준다(2026-09-23 실측). 배치는 부르지 않고 수동 엔드포인트({@code fixAllStockNames})뿐이며,
 * 종목명은 {@code StockPriceService} 의 마스터 폴백이 채우고 있어 기능상 공백은 없다. 은퇴 여부는 판단 사안.
 *
 * <p>⚠ <b>클래스 @Transactional 금지</b>: 전종목 크롤(fixAllStockNames)은
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

    private static final String NAVER_FINANCE_URL = "https://finance.naver.com/item/main.naver?code=";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // ========== 영업이익률 크롤링 — 2026-09-23 은퇴 ==========
    //
    // 올인원 2단계였다. 소스(finance.naver.com/item/main.naver)가 stock.naver.com SPA 로 302 이전해
    // 새 HTML 에 값이 없다 — 2026-09-22 15:38 · 09-23 08:30 두 회차 모두 성공 0 / 실패 366~367.
    // 대상이 정확히 'operating_margin 이 없는 종목'이었는데(9/22 일별 2,662행 중 2,296행이 이미 채워짐,
    // 2,662 − 2,296 = 366) 그 값은 1단계 KIS 수집기가 매출·영업이익으로 계산해 채우고 있어 잃는 것이 없다.
    // crawlAllOperatingMargin · crawlFinancialRatios · crawlSingleStock · parsePercentage 와
    // 엔드포인트 4종(/crawl-operating-margin 동기·비동기·단일, /crawl-preview)을 지웠다.
    // 분기 크롤(위)과 같은 이유 — 되살리지 말 것. 영업이익률의 단일 출처는 KIS 1단계다.

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