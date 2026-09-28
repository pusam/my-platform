package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.entity.StockQuarterlyFinancial;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockQuarterlyFinancialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 주식 재무 데이터 수집 컴포넌트
 * - 각 종목별 수집을 독립적인 트랜잭션으로 처리
 * - API 호출 실패 시 최대 3회 재시도
 * - StockFinancialDataService에서 호출
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockFinancialDataCollector {

    // ==================== KIS 재무 API TR_ID (2026-08-27 정정) ====================
    //
    // ⚠ KIS 는 **URL 경로가 아니라 tr_id 로 디스패치한다.** 경로를 income-statement 로 두고
    //    tr_id 만 다른 걸 넣으면 그 tr_id 의 리포트가 조용히 돌아온다 — 에러가 안 난다.
    //
    // 그래서 실제로 이런 일이 있었다(2026-08-27 발견, 그 전까지 몇 달):
    //    financial-ratio 경로 + FHKST66430200  → 실제로는 손익계산서가 돌아옴
    //    income-statement 경로 + FHKST66430300 → 실제로는 재무비율이 돌아옴
    //    balance-sheet   경로 + FHKST66430400 → 실제로는 수익성비율이 돌아옴
    // 각 파서는 자기가 기대한 필드를 못 찾아 전부 빈 문자열 → parseBigDecimal("")=0 →
    // 434종목 전 기간의 매출·영업이익·순이익·자본총계·재무비율이 통째로 결측이었다.
    //
    // 값 출처: KIS 공식 샘플 코드(koreainvestment/open-trading-api,
    //          examples_user/domestic_stock/domestic_stock_functions.py) — 기억이 아니라 문서 기준.
    //          실측 응답(수익성비율/재무비율 필드 조합)과도 교차 확인했다.
    // ⚠ 바꾸기 전에 반드시 위 출처를 다시 확인할 것. 숫자가 연속이라 눈으로는 안 틀린 것처럼 보인다.
    private static final String TR_INCOME_STATEMENT = "FHKST66430200";  // 손익계산서
    private static final String TR_BALANCE_SHEET    = "FHKST66430100";  // 대차대조표
    private static final String TR_FINANCIAL_RATIO  = "FHKST66430300";  // 재무비율
    // (참고) FHKST66430400 수익성비율 · 66430500 기타주요비율 · 66430600 안정성비율 · 66430800 성장성비율

    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_MS = 500;

    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final StockQuarterlyFinancialRepository quarterlyRepository;
    private final KoreaInvestmentService koreaInvestmentService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final StockMasterService stockMasterService;

    /**
     * 수집 시점의 표시용 종목명 — 순수 함수(회귀 {@code FinancialCollectorNameFallbackTest}).
     *
     * <p>KIS 현재가 응답의 {@code hts_kor_isnm} 이 <b>자주 비어 온다</b> — prod 실측 2026-09-21
     * 당일 수집분 2,660행 중 <b>1,869행(70%)</b>이 이름 없이 저장됐고, 예전 코드는 그걸 조용히
     * 종목코드로 채웠다. 그 값을 읽는 발굴 저평가·성장 트랙이 "금호석유화학" 대신 "011780" 을 보여줬다.
     *
     * <p>순서: <b>KIS 응답 → 종목마스터 → 코드</b>. 마스터에는 전 종목 이름이 있으므로 대부분 여기서 끝난다.
     * 마스터에도 없으면 <b>코드를 남긴다</b> — 빈칸으로 두면 어떤 종목인지조차 잃는다(§4c).
     * 마스터 조회 실패는 코드로 진행한다(fail-open) — 이름 때문에 재무 수집이 멈추면 안 된다.
     *
     * @param lookup 코드 → 마스터 이름(없으면 null). 예외를 던져도 수집은 계속된다.
     */
    static String resolveCollectedName(String stockCode, String fromKis,
                                       java.util.function.Function<String, String> lookup) {
        if (fromKis != null && !fromKis.isBlank()) {
            return fromKis;
        }
        if (lookup != null && stockCode != null) {
            try {
                String fromMaster = lookup.apply(stockCode);
                if (fromMaster != null && !fromMaster.isBlank() && !fromMaster.trim().equals(stockCode)) {
                    return fromMaster;
                }
            } catch (Exception e) {
                log.debug("[재무수집] 마스터 이름 조회 실패 {} — 코드로 진행: {}", stockCode, e.getMessage());
            }
        }
        return stockCode;
    }


    @Value("${kis.api.base-url:https://openapi.koreainvestment.com:9443}")
    private String baseUrl;

    @Value("${kis.api.app-key:}")
    private String appKey;

    @Value("${kis.api.app-secret:}")
    private String appSecret;

    /**
     * 단일 종목 재무 데이터 수집 - 간소화 버전 (독립 트랜잭션)
     *
     * <p><b>유일한 수집 경로다</b> — 원버튼 1단계·전 종목 수집·단일 종목 수동 수집이 전부 여기로 온다.
     * 예전 '완전판'({@code collectStockFinancialData})은 {@code hts_avls}(이미 억원)를 1e8 로 또 나눠
     * 시총 0 을 썼고, 성장률 배치가 채운 PEG 를 자기 계산값(null)으로 지웠다. 23:00 순매수 상위 재수집이
     * 그걸로 배치가 쓴 당일 행을 매일 덮어 2026-09-28 은퇴했다 — 두 번째 수집 경로를 되살리지 말 것
     * ({@code NightlyFinancialRecollectRetiredTest}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean collectStockFinancialDataSimple(String stockCode) {
        try {
            // 재시도 로직 적용된 현재가 조회
            JsonNode priceData = getStockPriceWithRetry(stockCode);
            if (priceData == null || !"0".equals(priceData.path("rt_cd").asText())) {
                log.debug("주식 현재가 조회 실패: {}", stockCode);
                return false;
            }

            JsonNode output = priceData.get("output");
            if (output == null) {
                return false;
            }

            String rawName = output.path("hts_kor_isnm").asText("");
            String stockName = resolveCollectedName(stockCode, rawName,
                    code -> stockMasterService.getNameOrDefault(code, null));
            if (!rawName.isBlank()) {
                stockMasterService.cacheName(stockCode, rawName, "KIS");
            }

            String market = "KOSPI";
            String rprs_mrkt_kor_name = output.path("rprs_mrkt_kor_name").asText("");
            if (rprs_mrkt_kor_name.contains("코스닥") || rprs_mrkt_kor_name.contains("KOSDAQ")) {
                market = "KOSDAQ";
            } else if (stockCode.startsWith("3") || stockCode.startsWith("4") || stockCode.startsWith("9")) {
                market = "KOSDAQ";
            }

            BigDecimal currentPrice = parseBigDecimal(output.path("stck_prpr").asText());

            BigDecimal marketCapRaw = parseBigDecimal(output.path("hts_avls").asText());
            BigDecimal marketCap;

            if (marketCapRaw.compareTo(BigDecimal.ZERO) > 0) {
                if (marketCapRaw.compareTo(new BigDecimal("100000000")) > 0) {
                    marketCap = marketCapRaw.divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP);
                } else {
                    marketCap = marketCapRaw;
                }
            } else {
                BigDecimal lstgStcn = parseBigDecimal(output.path("lstn_stcn").asText());
                if (lstgStcn.compareTo(BigDecimal.ZERO) > 0 && currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                    marketCap = lstgStcn.multiply(currentPrice)
                            .divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP);
                } else {
                    marketCap = BigDecimal.ZERO;
                }
            }

            BigDecimal per = parseBigDecimal(output.path("per").asText());
            BigDecimal pbr = parseBigDecimal(output.path("pbr").asText());
            BigDecimal eps = parseBigDecimal(output.path("eps").asText());
            BigDecimal bps = parseBigDecimal(output.path("bps").asText());
            BigDecimal lstnStcn = parseBigDecimal(output.path("lstn_stcn").asText());

            BigDecimal roe = BigDecimal.ZERO;
            if (bps != null && bps.compareTo(BigDecimal.ZERO) > 0 && eps != null) {
                roe = eps.divide(bps, 6, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
            }

            String token = koreaInvestmentService.getAccessToken();
            Map<String, BigDecimal> financialRatios = new HashMap<>();
            if (token != null) {
                try {
                    Thread.sleep(100);
                    financialRatios = getFinancialRatios(token, stockCode);
                } catch (Exception e) {
                    log.debug("재무비율 조회 실패 [{}]: {}", stockCode, e.getMessage());
                }
            }

            BigDecimal roeFromApi = financialRatios.get("roe");
            if (roeFromApi != null && roeFromApi.compareTo(BigDecimal.ZERO) != 0) {
                roe = roeFromApi;
            }

            BigDecimal netIncome = financialRatios.getOrDefault("netIncome", null);
            BigDecimal revenue = financialRatios.getOrDefault("revenue", null);
            BigDecimal operatingProfit = financialRatios.getOrDefault("operatingProfit", null);

            // ★ TTM 연결 당기순이익으로 EPS/PER 재계산 (별도→연결 통일)
            if (netIncome != null && lstnStcn.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal ttmEps = netIncome
                        .multiply(new BigDecimal("100000000"))
                        .divide(lstnStcn, 0, RoundingMode.HALF_UP);
                log.info("[Simple TTM EPS] {} - 별도 EPS: {} → TTM 연결 EPS: {} (순이익: {}억, 주식수: {})",
                        stockCode, eps, ttmEps, netIncome, lstnStcn);
                eps = ttmEps;

                if (currentPrice.compareTo(BigDecimal.ZERO) > 0 && eps.compareTo(BigDecimal.ZERO) != 0) {
                    per = currentPrice.divide(eps, 1, RoundingMode.HALF_UP);
                }

                // TTM EPS 기반 ROE 재계산
                // BPS가 음수(자본잠식)인 경우에도 계산하되, 부호가 순이익과 일관되도록 보정
                if (bps != null && bps.compareTo(BigDecimal.ZERO) != 0) {
                    roe = eps.divide(bps, 6, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"))
                            .setScale(2, RoundingMode.HALF_UP);
                    // 부호 보정: 순이익 양수 → ROE 양수, 순이익 음수 → ROE 음수
                    if (netIncome.compareTo(BigDecimal.ZERO) > 0 && roe.compareTo(BigDecimal.ZERO) < 0) {
                        roe = roe.abs();
                    } else if (netIncome.compareTo(BigDecimal.ZERO) < 0 && roe.compareTo(BigDecimal.ZERO) > 0) {
                        roe = roe.negate();
                    }
                }
            }

            // 성장률 4종(매출·순이익·EPS·PEG)은 여기서 쓰지 않는다 — 2단계 calculateAndUpdateGrowthRates 가 분기 원본으로
            // 계산해 단독으로 쓴다(2026-09-29). 여기서 읽던 KIS eps_cagr·sls_cagr·ntin_cagr 는 실측 전부 0(죽은 필드)이었고,
            // 같은 날 15:38 회차가 08:30 회차의 계산값을 0 으로 덮었다. 기존 행을 upsert 할 때도 그 칸은 건드리지 않는다.

            // 영업이익률: API에서 가져오거나, operatingProfit/revenue로 계산
            BigDecimal operatingMargin = financialRatios.getOrDefault("operatingMargin", null);
            if ((operatingMargin == null || operatingMargin.compareTo(BigDecimal.ZERO) == 0)
                    && operatingProfit != null && revenue != null
                    && revenue.compareTo(BigDecimal.ZERO) > 0) {
                // 영업이익률 = (영업이익 / 매출액) * 100
                operatingMargin = operatingProfit.divide(revenue, 6, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
            }

            LocalDate today = LocalDate.now();
            StockFinancialData financialData = stockFinancialDataRepository
                    .findByStockCodeAndReportDate(stockCode, today)
                    .orElse(new StockFinancialData());

            financialData.setStockCode(stockCode);
            financialData.setStockName(stockName);
            financialData.setMarket(market);
            financialData.setReportDate(today);
            financialData.setCurrentPrice(currentPrice);
            financialData.setMarketCap(marketCap);
            financialData.setPer(per);
            financialData.setPbr(pbr);
            financialData.setEps(eps);
            financialData.setBps(bps);
            financialData.setRoe(roe);
            financialData.setNetIncome(netIncome);
            financialData.setRevenue(revenue);
            financialData.setOperatingProfit(operatingProfit);
            financialData.setOperatingMargin(operatingMargin);

            // ★ 재무상태표 데이터 저장
            BigDecimal totalEquity = financialRatios.getOrDefault("totalEquity", null);
            BigDecimal totalAssets = financialRatios.getOrDefault("totalAssets", null);
            BigDecimal totalDebt = financialRatios.getOrDefault("totalDebt", null);
            if (totalEquity != null) financialData.setTotalEquity(totalEquity);
            if (totalAssets != null) financialData.setTotalAssets(totalAssets);
            if (totalDebt != null) financialData.setTotalDebt(totalDebt);

            // ★ totalEquity 기반 BPS/PBR/ROE 재계산 (연결 기준)
            if (totalEquity != null && totalEquity.compareTo(BigDecimal.ZERO) > 0
                    && lstnStcn.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal ttmBps = totalEquity
                        .multiply(new BigDecimal("100000000"))  // 억원 → 원
                        .divide(lstnStcn, 0, RoundingMode.HALF_UP);
                financialData.setBps(ttmBps);
                bps = ttmBps;  // ROE 재계산용

                if (currentPrice.compareTo(BigDecimal.ZERO) > 0 && ttmBps.compareTo(BigDecimal.ZERO) > 0) {
                    pbr = currentPrice.divide(ttmBps, 2, RoundingMode.HALF_UP);
                    financialData.setPbr(pbr);
                }

                // ROE 직접 재계산 (TTM 순이익 / 자본총계)
                if (netIncome != null && netIncome.compareTo(BigDecimal.ZERO) != 0) {
                    roe = netIncome.divide(totalEquity, 6, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"))
                            .setScale(2, RoundingMode.HALF_UP);
                    financialData.setRoe(roe);
                }
            }

            stockFinancialDataRepository.save(financialData);

            // 손익계산서 데이터 저장 여부 로깅
            if (operatingProfit != null || netIncome != null || revenue != null) {
                log.info("[Simple저장] {} ({}) - 매출: {}, 영업이익: {}, 순이익: {}, EPS(TTM): {}, PER(TTM): {}",
                        stockName, stockCode, revenue, operatingProfit, netIncome, eps, per);
            } else {
                log.warn("[Simple저장] {} ({}) - 손익계산서 데이터 없음 (영업이익률: {})",
                        stockName, stockCode, operatingMargin);
            }
            return true;

        } catch (Exception e) {
            log.debug("재무 데이터 수집 실패 [{}]: {}", stockCode, e.getMessage());
            return false;
        }
    }

    /**
     * 재무비율 조회 (KIS API)
     */
    public Map<String, BigDecimal> getFinancialRatios(String token, String stockCode) {
        Map<String, BigDecimal> ratios = new HashMap<>();

        try {
            String url = baseUrl + "/uapi/domestic-stock/v1/finance/financial-ratio"
                    + "?FID_DIV_CLS_CODE=0"
                    + "&fid_cond_mrkt_div_code=J"
                    + "&fid_input_iscd=" + stockCode;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("authorization", "Bearer " + token);
            headers.set("appkey", appKey);
            headers.set("appsecret", appSecret);
            headers.set("tr_id", TR_FINANCIAL_RATIO);
            headers.set("custtype", "P");

            HttpEntity<String> request = new HttpEntity<>(headers);
            // 재시도 로직 적용
            ResponseEntity<String> response = executeWithRetry(url, request);

            if (response != null && response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if ("0".equals(root.path("rt_cd").asText())) {
                    JsonNode output = root.get("output");
                    if (output != null && output.isArray() && output.size() > 0) {
                        JsonNode latest = output.get(0);
                        ratios.put("roe", parseBigDecimal(latest.path("roe_val").asText()));
                        ratios.put("operatingMargin", parseBigDecimal(latest.path("bsop_prfi_inrt").asText()));
                        ratios.put("netMargin", parseBigDecimal(latest.path("ntin_inrt").asText()));
                        // ★ 연간 순이익률 백업 (TTM 덮어쓰기 전에 보관 → ROE 추정용)
                        ratios.put("_annualNetMargin", parseBigDecimal(latest.path("ntin_inrt").asText()));
                        ratios.put("debtRatio", parseBigDecimal(latest.path("lblt_rate").asText()));
                        // eps_cagr·sls_cagr·ntin_cagr 는 읽지 않는다 — 실측 전부 0(죽은 필드), 성장률은 2단계 배치 단독(2026-09-29)
                    }
                }
            }

            Thread.sleep(100);
            // ★ 분기별 손익계산서 조회 → 최근 4분기 합산(TTM) 기준으로 통일
            String incomeUrl = baseUrl + "/uapi/domestic-stock/v1/finance/income-statement"
                    + "?FID_DIV_CLS_CODE=1"
                    + "&fid_cond_mrkt_div_code=J"
                    + "&fid_input_iscd=" + stockCode;

            headers.set("tr_id", TR_INCOME_STATEMENT);
            request = new HttpEntity<>(headers);
            // 재시도 로직 적용
            response = executeWithRetry(incomeUrl, request);

            if (response != null && response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String rtCd = root.path("rt_cd").asText();
                log.debug("[손익계산서 API] 종목: {}, rt_cd: {}, msg: {}",
                        stockCode, rtCd, root.path("msg1").asText());

                if ("0".equals(rtCd)) {
                    JsonNode output = root.get("output");
                    if (output != null && output.isArray() && output.size() > 0) {
                        int quarterCount = Math.min(output.size(), 4);

                        // ★ 각 분기 raw 데이터 로깅 (디버깅용)
                        for (int i = 0; i < quarterCount; i++) {
                            JsonNode q = output.get(i);
                            log.info("[손익계산서 RAW] {} Q{}: stac_yymm={}, 매출={}, 영업이익={}, 순이익={}",
                                    stockCode, i, q.path("stac_yymm").asText(),
                                    q.path("sale_account").asText(),
                                    q.path("bsop_prti").asText(),
                                    q.path("thtr_ntin").asText());
                        }

                        // ★ 응답 스키마 진단(2026-08-26) — 금액 3필드가 전부 비면 필드명이 틀린 것이다.
                        //   Jackson 의 path("없는키").asText() 가 "" 를 주기 때문에 조용히 0 으로 흐른다.
                        //   필드명을 추측으로 고치지 않기 위해 실제 응답 1건을 그대로 남긴다(JVM 당 1회).
                        logIncomeSchemaOnce(stockCode, output.get(0));

                        // ★ 누적(YTD) 판정 + TTM — 순수함수 단일 출처 (2026-08-27 전면 교체)
                        //
                        // 이전 인라인 휴리스틱은 **최신 3개**만 보고 단조증가를 확인했는데,
                        // 회계연도 경계가 그 안에 들어오면 반드시 깨진다. 8월은 FY 리셋 직후라
                        // 구조적으로 실패하는 시기다 — 실측 2,523/2,618종목(96%)이 "누적 아님"으로
                        // 오판됐고, 누적값 4개를 그냥 더해 TTM 이 2배 넘게 부풀었다
                        // (삼성전자: 101,260 vs 진짜 48,527).
                        //
                        // 새 경로는 ① 이력 전체의 인접쌍 증가비율로 누적을 판정하고
                        //          ② 개별 분기로 환산한 뒤 최근 4분기를 더한다.
                        // 삼성전자 실측으로 검산됨(QuarterlyFinancialsTest).
                        List<QuarterlyFinancials.Figures> rawFigures = parseIncomeFigures(output);
                        List<QuarterlyFinancials.Figures> figures =
                                QuarterlyFinancials.withDetectedCumulative(rawFigures);
                        boolean isCumulative = !figures.isEmpty() && figures.get(0).cumulative();

                        List<QuarterlyFinancials.Figures> individuals =
                                QuarterlyFinancials.toIndividualQuarters(figures);
                        BigDecimal[] ttm = QuarterlyFinancials.ttmSum(individuals);

                        // ★ 분기 원본 보존 (R1) — 이미 받아서 버리던 배열을 적재만 한다. 새 API 호출 없음.
                        persistQuarterlyRows(stockCode, output, isCumulative);

                        BigDecimal ttmRevenueRaw = (ttm != null && ttm[0] != null) ? ttm[0] : BigDecimal.ZERO;
                        BigDecimal ttmOperatingProfitRaw = (ttm != null && ttm[1] != null) ? ttm[1] : BigDecimal.ZERO;
                        BigDecimal ttmNetIncomeRaw = (ttm != null && ttm[2] != null) ? ttm[2] : BigDecimal.ZERO;

                        log.info("[손익계산서 TTM] {} - 원본 {}분기 → 개별 {}분기 (누적:{}): 매출액: {}, 영업이익: {}, 당기순이익: {}",
                                stockCode, rawFigures.size(), individuals.size(), isCumulative,
                                ttmRevenueRaw, ttmOperatingProfitRaw, ttmNetIncomeRaw);

                        if (ttm == null) {
                            // §4c — 12개월치를 못 만든 것이지 "실적 0" 이 아니다. 0 으로 흘러가면
                            // 아래 !=0 가드가 걸러 ratios 에 안 담기고, 그게 의도된 동작이다.
                            log.warn("[손익계산서 TTM] {} - 연속 4분기 확보 실패(원본 {} / 개별 {}) — TTM 미산출",
                                    stockCode, rawFigures.size(), individuals.size());
                        }

                        // ★ 단위: KIS 원본이 이미 **억원**이다 — /100 하지 않는다(2026-08-28 실측 정정).
                        //   기존엔 "백만원 → 억원"이라며 /100 을 해 모든 금액이 100배 작게 저장됐다.
                        //   실측 근거: 005930 자본총계/시총 = 0.00377 → PBR 265, 000660 은 477.
                        //   어떤 회사에도 불가능한 값이고, 시총 단위와 무관하게 확인된다
                        //   (삼성전자 TTM 매출이 4.85조일 수 없다).
                        //   ⚠ PBR 일관성 가드(PER×ROE/100)가 그 말도 안 되는 PBR 을 덮어써서
                        //      화면엔 정상으로 보였다 — 가드가 단위 버그를 가리고 있었다.
                        if (ttmNetIncomeRaw.compareTo(BigDecimal.ZERO) != 0) {
                            ratios.put("netIncome", ttmNetIncomeRaw);
                        }
                        if (ttmRevenueRaw.compareTo(BigDecimal.ZERO) != 0) {
                            ratios.put("revenue", ttmRevenueRaw);
                        }
                        if (ttmOperatingProfitRaw.compareTo(BigDecimal.ZERO) != 0) {
                            ratios.put("operatingProfit", ttmOperatingProfitRaw);
                        }

                        // ★ TTM 기준 영업이익률/순이익률 덮어쓰기 (연간 ratio API 값 대체)
                        if (ttmRevenueRaw.compareTo(BigDecimal.ZERO) > 0) {
                            ratios.put("operatingMargin", ttmOperatingProfitRaw
                                    .divide(ttmRevenueRaw, 6, RoundingMode.HALF_UP)
                                    .multiply(new BigDecimal("100"))
                                    .setScale(2, RoundingMode.HALF_UP));
                            ratios.put("netMargin", ttmNetIncomeRaw
                                    .divide(ttmRevenueRaw, 6, RoundingMode.HALF_UP)
                                    .multiply(new BigDecimal("100"))
                                    .setScale(2, RoundingMode.HALF_UP));
                        }

                        // ★ TTM ROE 추정: TTM순이익 / 자본총계 직접 계산
                        // (비율 보정 방식은 흑자전환 기업에서 부호 오류 발생)
                        BigDecimal annualRoe = ratios.get("roe");
                        BigDecimal ttmNetIncome = ratios.get("netIncome"); // 억원 단위
                        BigDecimal ttmNetMargin = ratios.get("netMargin");
                        if (ttmNetIncome != null && ttmNetIncome.compareTo(BigDecimal.ZERO) != 0) {
                            // TTM 당기순이익(억원)으로 EPS 산출 후 ROE = EPS/BPS*100
                            // BPS는 상위 메서드에서 사용하므로 여기서는 순이익률 기반 추정 유지하되
                            // 부호가 일관되도록: TTM순이익 > 0이면 ROE > 0, 적자면 ROE < 0
                            BigDecimal annualNetMargin = ratios.get("_annualNetMargin");
                            if (annualRoe != null && annualNetMargin != null
                                    && annualNetMargin.compareTo(BigDecimal.ZERO) != 0
                                    && ttmNetMargin != null) {
                                BigDecimal ttmRoe = annualRoe.multiply(ttmNetMargin)
                                        .divide(annualNetMargin, 2, RoundingMode.HALF_UP);
                                // 부호 검증: TTM 순이익이 양수면 ROE도 양수여야 함
                                if (ttmNetIncome.compareTo(BigDecimal.ZERO) > 0
                                        && ttmRoe.compareTo(BigDecimal.ZERO) < 0) {
                                    ttmRoe = ttmRoe.abs();
                                    log.info("[재무비율 TTM] {} ROE 부호 보정 (흑자전환): {}% → +{}%",
                                            stockCode, annualRoe, ttmRoe);
                                } else if (ttmNetIncome.compareTo(BigDecimal.ZERO) < 0
                                        && ttmRoe.compareTo(BigDecimal.ZERO) > 0) {
                                    ttmRoe = ttmRoe.negate();
                                    log.info("[재무비율 TTM] {} ROE 부호 보정 (적자전환): {}% → {}%",
                                            stockCode, annualRoe, ttmRoe);
                                }
                                ratios.put("roe", ttmRoe);
                                log.info("[재무비율 TTM] {} ROE 보정: {}% → {}% (순이익률 {}→{}, TTM순이익: {}억)",
                                        stockCode, annualRoe, ttmRoe, annualNetMargin, ttmNetMargin, ttmNetIncome);
                            }
                        }
                        ratios.remove("_annualNetMargin"); // 임시 키 제거
                    } else {
                        log.warn("[손익계산서] {} - output이 비어있음", stockCode);
                    }
                } else {
                    log.warn("[손익계산서] {} - API 오류: {}", stockCode, root.path("msg1").asText());
                }
            } else {
                log.warn("[손익계산서] {} - API 응답 없음 또는 실패", stockCode);
            }

            // ★ 재무상태표 조회 → 자본총계/총자산/부채총계 수집 (ROE 직접 계산용)
            // 분기(1) 먼저 시도, 실패 또는 데이터 없으면 연간(0) 폴백
            Thread.sleep(100);
            try {
                boolean bsDataFound = false;
                for (String divClsCode : new String[]{"1", "0"}) {
                    if (bsDataFound) break;

                    String balanceUrl = baseUrl + "/uapi/domestic-stock/v1/finance/balance-sheet"
                            + "?FID_DIV_CLS_CODE=" + divClsCode
                            + "&fid_cond_mrkt_div_code=J"
                            + "&fid_input_iscd=" + stockCode;

                    headers.set("tr_id", TR_BALANCE_SHEET);
                    request = new HttpEntity<>(headers);
                    response = executeWithRetry(balanceUrl, request);

                    if (response != null && response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                        JsonNode bsRoot = objectMapper.readTree(response.getBody());
                        if ("0".equals(bsRoot.path("rt_cd").asText())) {
                            JsonNode bsOutput = bsRoot.get("output");
                            if (bsOutput != null && bsOutput.isArray() && bsOutput.size() > 0) {
                                // 가장 최근 데이터 사용
                                JsonNode latestBs = bsOutput.get(0);
                                logBalanceSchemaOnce(stockCode, divClsCode, latestBs);
                                BigDecimal totalAset = parseBigDecimal(latestBs.path("total_aset").asText());
                                BigDecimal totalCptl = parseBigDecimal(latestBs.path("total_cptl").asText());
                                BigDecimal totalLblt = parseBigDecimal(latestBs.path("total_lblt").asText());

                                if (totalCptl.compareTo(BigDecimal.ZERO) <= 0) {
                                    log.warn("[재무상태표] {} - FID_DIV_CLS_CODE={} 자본총계 0 또는 없음, 다음 시도", stockCode, divClsCode);
                                    if ("1".equals(divClsCode)) {
                                        Thread.sleep(100);
                                    }
                                    continue;
                                }

                                bsDataFound = true;
                                log.info("[재무상태표] {} - FID_DIV_CLS_CODE={} 데이터 사용 (raw 자본총계: {})", stockCode, divClsCode, totalCptl);

                                // ★ 단위: 손익계산서와 동일하게 원본이 이미 억원 — /100 하지 않는다(2026-08-28).
                                //   ⚠ 둘을 반드시 함께 고쳐야 한다. ROE = 순이익 / 자본총계 라
                                //      둘 다 100배 작을 땐 비율이 우연히 맞았다. 한쪽만 고치면 ROE 가 100배 틀어진다.
                                if (totalAset.compareTo(BigDecimal.ZERO) > 0) {
                                    ratios.put("totalAssets", totalAset);
                                }
                                ratios.put("totalEquity", totalCptl);
                                if (totalLblt.compareTo(BigDecimal.ZERO) > 0) {
                                    ratios.put("totalDebt", totalLblt);
                                }

                                // ★ totalEquity 기반 ROE 직접 계산 (ratio 추정 대체)
                                BigDecimal ttmNetIncome = ratios.get("netIncome"); // 억원
                                BigDecimal totalEquity = ratios.get("totalEquity"); // 억원
                                if (ttmNetIncome != null && totalEquity != null
                                        && totalEquity.compareTo(BigDecimal.ZERO) > 0) {
                                    BigDecimal directRoe = ttmNetIncome
                                            .divide(totalEquity, 6, RoundingMode.HALF_UP)
                                            .multiply(new BigDecimal("100"))
                                            .setScale(2, RoundingMode.HALF_UP);
                                    log.info("[재무상태표] {} ROE 직접 계산: {}% (TTM순이익: {}억 / 자본총계: {}억), 기존 ROE: {}%",
                                            stockCode, directRoe, ttmNetIncome, totalEquity, ratios.get("roe"));
                                    ratios.put("roe", directRoe);
                                }

                                // ★ 부채비율 직접 계산
                                if (totalLblt.compareTo(BigDecimal.ZERO) > 0 && totalCptl.compareTo(BigDecimal.ZERO) > 0) {
                                    BigDecimal directDebtRatio = totalLblt
                                            .divide(totalCptl, 6, RoundingMode.HALF_UP)
                                            .multiply(new BigDecimal("100"))
                                            .setScale(2, RoundingMode.HALF_UP);
                                    ratios.put("debtRatio", directDebtRatio);
                                }

                                log.info("[재무상태표] {} - 총자산: {}억, 자본총계: {}억, 부채총계: {}억",
                                        stockCode, ratios.get("totalAssets"), ratios.get("totalEquity"), ratios.get("totalDebt"));
                            } else {
                                log.warn("[재무상태표] {} - FID_DIV_CLS_CODE={} output 비어있음", stockCode, divClsCode);
                                if ("1".equals(divClsCode)) {
                                    Thread.sleep(100);
                                }
                            }
                        } else {
                            log.warn("[재무상태표] {} - FID_DIV_CLS_CODE={} API 오류: {}", stockCode, divClsCode, bsRoot.path("msg1").asText());
                            if ("1".equals(divClsCode)) {
                                Thread.sleep(100);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[재무상태표] {} - 조회 실패: {}", stockCode, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("재무비율 조회 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return ratios;
    }

    /**
     * KIS 손익계산서 응답의 분기 행을 {@code stock_quarterly_financial} 에 그대로 적재한다(R1).
     *
     * <p><b>새 API 호출 없음</b> — 이미 받아서 TTM 합산에만 쓰고 버리던 배열이다.
     * TTM 합산 경로는 건드리지 않으므로 PER/PBR 등 밸류에이션 값은 무영향이다.
     *
     * <p><b>4분기 상한을 두지 않는다</b>: TTM 은 최근 4분기만 필요하지만 실적 비교는 이력이
     * 많을수록 좋다(전년동기 비교로 넓힐 여지). 응답이 주는 만큼 다 담는다.
     *
     * <p><b>결측은 null 로</b>: {@link #parseBigDecimal} 은 결측을 {@code ZERO} 로 바꾸는데
     * (TTM 합산에선 의도된 동작), 분기 원본에 0 을 넣으면 "영업이익 0억"이라는 <b>거짓 사실</b>이
     * 남아 다음 분기와 비교할 때 유령 변화율이 나온다. 그래서 여기선
     * {@link #parseBigDecimalOrNull} 로 결측을 null 로 보존한다(§4c).
     *
     * <p>수집 실패는 삼키고 로그만 남긴다 — 이 적재가 실패해도 기존 재무 수집은 계속돼야 한다.
     *
     * @param isCumulative 호출부가 이미 판정한 누적 여부 — 같은 응답에 대해 판정이 갈리지 않도록
     *                     새로 계산하지 않고 전달받는다.
     */
    void persistQuarterlyRows(String stockCode, JsonNode output, boolean isCumulative) {
        if (output == null || !output.isArray() || output.size() == 0) return;
        int saved = 0, skipped = 0;
        LocalDateTime now = LocalDateTime.now();
        List<String> savedPeriods = new ArrayList<>();

        for (JsonNode q : output) {
            String stacYymm = q.path("stac_yymm").asText(null);
            YearMonth ym = QuarterlyFinancials.parseFiscalPeriod(stacYymm);
            if (ym == null) { skipped++; continue; }   // 분기 정체성 불명 → 저장 안 함

            BigDecimal revenue = toEokWon(parseBigDecimalOrNull(q.path("sale_account").asText(null)));
            BigDecimal operatingProfit = toEokWon(parseBigDecimalOrNull(q.path("bsop_prti").asText(null)));
            BigDecimal netIncome = toEokWon(parseBigDecimalOrNull(q.path("thtr_ntin").asText(null)));
            if (revenue == null && operatingProfit == null && netIncome == null) { skipped++; continue; }

            String period = String.format("%04d%02d", ym.getYear(), ym.getMonthValue());
            try {
                StockQuarterlyFinancial row = quarterlyRepository
                        .findByStockCodeAndFiscalPeriod(stockCode, period)
                        .orElseGet(() -> StockQuarterlyFinancial.builder()
                                .stockCode(stockCode)
                                .fiscalPeriod(period)
                                .build());
                row.setPeriodEnd(QuarterlyFinancials.periodEnd(ym));
                row.setCumulative(isCumulative);
                row.setRevenue(revenue);
                row.setOperatingProfit(operatingProfit);
                row.setNetIncome(netIncome);
                row.setSource("KIS_INCOME_STMT");
                row.setCollectedAt(now);
                quarterlyRepository.save(row);
                saved++;
                savedPeriods.add(period);
            } catch (Exception e) {
                skipped++;
                log.debug("[분기재무] {} {} 적재 실패: {}", stockCode, period, e.getMessage());
            }
        }

        if (saved > 0) {
            log.info("[분기재무] {} - {}개 분기 적재(누적:{}) {} (스킵 {})",
                    stockCode, saved, isCumulative, savedPeriods, skipped);
        } else if (skipped > 0) {
            log.debug("[분기재무] {} - 적재 0건 (스킵 {})", stockCode, skipped);
        }
    }

    /**
     * KIS 손익계산서 응답 → {@link QuarterlyFinancials.Figures} 목록.
     *
     * <p><b>전량 파싱한다</b>(4분기 상한 없음) — 누적 판정이 이력 전체의 인접쌍을 보기 때문이다.
     * 최신 몇 개만 보면 회계연도 경계에 걸려 판정이 흔들린다(2026-08-27 사고의 원인).
     *
     * <p>결측은 {@link #parseBigDecimalOrNull} 로 null 보존 — {@code parseBigDecimal} 은 결측을
     * 0 으로 바꿔서 "매출 0인 분기"라는 거짓을 만들고 증감 판정을 오염시킨다(§4c).
     * {@code stac_yymm} 을 못 읽는 행은 분기 정체성이 없으므로 제외한다.
     */
    private List<QuarterlyFinancials.Figures> parseIncomeFigures(JsonNode output) {
        List<QuarterlyFinancials.Figures> out = new ArrayList<>();
        if (output == null || !output.isArray()) return out;
        for (JsonNode q : output) {
            YearMonth ym = QuarterlyFinancials.parseFiscalPeriod(q.path("stac_yymm").asText(null));
            if (ym == null) continue;
            out.add(new QuarterlyFinancials.Figures(
                    String.format("%04d%02d", ym.getYear(), ym.getMonthValue()),
                    QuarterlyFinancials.periodEnd(ym),
                    parseBigDecimalOrNull(q.path("sale_account").asText(null)),
                    parseBigDecimalOrNull(q.path("bsop_prti").asText(null)),
                    parseBigDecimalOrNull(q.path("thtr_ntin").asText(null)),
                    false));
        }
        return out;
    }

    /** 응답 스키마를 남기는 것은 JVM 당 1회 — 종목마다 찍으면 배치 로그가 묻힌다. */
    private static final java.util.concurrent.atomic.AtomicBoolean INCOME_SCHEMA_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean BALANCE_SCHEMA_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 손익계산서 응답의 금액 필드가 전부 비어 있으면 <b>실제 응답 원문</b>을 WARN 으로 남긴다.
     *
     * <p>필드명을 기억이나 추측으로 고치면 또 조용히 0 이 된다 — 응답 키를 눈으로 보고 고치기 위한 것.
     * 정상(금액이 하나라도 있음)이면 아무것도 안 찍는다.
     */
    private void logIncomeSchemaOnce(String stockCode, JsonNode first) {
        if (first == null || INCOME_SCHEMA_LOGGED.get()) return;
        boolean allEmpty = parseBigDecimalOrNull(first.path("sale_account").asText(null)) == null
                && parseBigDecimalOrNull(first.path("bsop_prti").asText(null)) == null
                && parseBigDecimalOrNull(first.path("thtr_ntin").asText(null)) == null;
        if (!allEmpty || !INCOME_SCHEMA_LOGGED.compareAndSet(false, true)) return;
        log.warn("[손익계산서 스키마] {} — 금액 3필드(sale_account/bsop_prti/thtr_ntin)가 모두 비었다. "
                + "실제 응답 첫 항목: {}", stockCode, first);
    }

    /** 재무상태표도 동일 — 자본총계가 비면 실제 응답 1건을 남긴다. */
    private void logBalanceSchemaOnce(String stockCode, String divClsCode, JsonNode first) {
        if (first == null || BALANCE_SCHEMA_LOGGED.get()) return;
        boolean allEmpty = parseBigDecimalOrNull(first.path("total_cptl").asText(null)) == null
                && parseBigDecimalOrNull(first.path("total_aset").asText(null)) == null
                && parseBigDecimalOrNull(first.path("total_lblt").asText(null)) == null;
        if (!allEmpty || !BALANCE_SCHEMA_LOGGED.compareAndSet(false, true)) return;
        log.warn("[재무상태표 스키마] {} (DIV_CLS={}) — 금액 3필드(total_cptl/total_aset/total_lblt)가 "
                + "모두 비었다. 실제 응답 첫 항목: {}", stockCode, divClsCode, first);
    }

    /**
     * KIS 손익계산서 금액 → 억원. <b>원본이 이미 억원이라 변환하지 않는다</b>(2026-08-28 실측 정정).
     *
     * <p>기존엔 "백만원 → 억원"이라며 /100 을 해 모든 금액이 100배 작게 저장됐다.
     * 이름을 남겨두는 이유는 <b>단위가 어디서 정해지는지</b>를 한 곳에 두기 위함이다 —
     * KIS 가 단위를 바꾸면 여기만 고친다.
     */
    private static BigDecimal toEokWon(BigDecimal raw) {
        return raw;
    }

    /**
     * {@link #parseBigDecimal} 의 <b>결측 보존</b> 판. 값이 없거나 파싱 불가면 null 을 돌려준다.
     *
     * <p>기존 {@code parseBigDecimal} 은 결측을 {@code ZERO} 로 바꾼다 — TTM 합산에선
     * "없는 분기는 안 더한다"는 뜻이라 말이 되지만, 분기 원본 저장에선 "그 분기 영업이익이 0억"이라는
     * 거짓 사실이 된다. 두 의미가 다르므로 함수를 나눈다(§4c).
     */
    static BigDecimal parseBigDecimalOrNull(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty() || "-".equals(v)) return null;
        try {
            return new BigDecimal(v.replace(",", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isEmpty() || "-".equals(value)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /** 성장률 계산에 쓸 분기 원본 창 — 1년 전 TTM 은 최신 분기 기준 8분기 전까지 필요하고, 누적 환산에 1분기, 공시 지연까지. */
    static final int GROWTH_LOOKBACK_MONTHS = 36;

    /**
     * PEG 를 만들 성장률 상한 — 이보다 크면 기저효과라 PEG 가 뜻이 없다.
     * {@code QuantScreenerService.MAX_EPS_GROWTH_FOR_PEG}("최대 EPS 성장률 200% (기저효과 제외)")와 같은 값.
     */
    static final BigDecimal PEG_MAX_GROWTH = new BigDecimal("200");

    /** 최신 일별 행에 저장할 성장률 4종 — 모르면 null(0 이 아니다, §4c). */
    record GrowthFields(BigDecimal revenueGrowth, BigDecimal profitGrowth, BigDecimal epsGrowth, BigDecimal peg) {
        boolean measured() { return revenueGrowth != null || profitGrowth != null; }
    }

    /**
     * 성장률 계산 — <b>분기 원본(V55) TTM 전년 동기 대비</b>(2026-09-29 재작성).
     *
     * <h4>왜 다시 썼나</h4>
     * 이전 배치는 최신 일별 행(TTM)을 {@code stock_financial_data} 의 "1년 전 ±30일" 아무 행과 비교했다. 그 행이
     * 한 분기짜리 행이거나 단위 오류 시절 행이면 매출이 +300% 가 됐고, 1년 전 행이 없으면 30일 전 행을 "YoY"로 썼다.
     * 운영 실측(9/28): 매출 성장률이 채워진 2,156종목 중 <b>2,048종목이 ±200% 초과</b> — "매출고성장" 태그·성장 트랙·
     * PEG·AI 가치 전략이 전부 그 값을 읽었다(베뉴지 매출 +371% → 실제 +9.3%). 그리고 값이 0 일 때만 채워서
     * 한 번 들어간 엉터리 값은 그 행에서 고쳐지지 않았다.
     *
     * <h4>지금</h4>
     * {@link QuarterlyFinancials#ttmYearOverYear} — 최근 4분기 합 vs 그 1년 전 4분기 합. 매출·순이익 모두 1년 전 값이
     * 0 이하면 null(적자 분모), 분기가 모자라거나 최신 분기가 {@link EarningSurpriseService#QUARTER_MAX_AGE_DAYS}일보다
     * 오래면 null. EPS 성장률은 순이익 성장률로 둔다(주식 수 변화 미반영 — 분기 원본에 주식 수가 없다; KIS 수집기가
     * 넣던 {@code eps_cagr} 는 실측 전부 0 이라 죽은 필드다). PEG = PER / 순이익 성장률, 성장률 0 초과 200% 이하일 때만.
     * <b>항상 덮어쓴다</b> — 분기 원본의 결정적 함수라 같은 입력이면 같은 값이고, 모르면 null 로 지운다.
     *
     * @return 값이 바뀐 행 수
     */
    @Transactional
    public int calculateAndUpdateGrowthRates() {
        log.info("성장률 계산 시작 (분기 원본 TTM 전년 동기 대비)...");
        LocalDate today = LocalDate.now();

        List<StockFinancialData> recentStocks = stockFinancialDataRepository.findAllRecentData(today.minusDays(30));
        Map<String, List<StockFinancialData>> stockDataMap = recentStocks.stream()
                .collect(java.util.stream.Collectors.groupingBy(StockFinancialData::getStockCode));
        Map<String, List<QuarterlyFinancials.Figures>> quarters = loadIndividualQuarters(today);

        int updatedCount = 0, measured = 0, unknown = 0;
        for (Map.Entry<String, List<StockFinancialData>> entry : stockDataMap.entrySet()) {
            String stockCode = entry.getKey();
            // 미래 날짜(추정치 잔여) 행 제외 — 쓰기와 읽기가 같은 규칙(FinancialRowSynthesizer.excludeFutureDated)이어야 한다(2026-09-03).
            List<StockFinancialData> dataList = FinancialRowSynthesizer.excludeFutureDated(entry.getValue(), today);
            if (dataList == null || dataList.isEmpty()) continue;

            // KIS 일별 행에만 쓴다 — 네이버 분기 행(market_cap NULL)은 writer 가 달라 섞지 않는다(V56→V57 사고).
            // 네이버 12-31 추정치 행(342종목)은 그날이 지나면 '미래'가 아니게 되어 최신 행 자리에 올라온다.
            StockFinancialData latest = latestDailyRow(dataList);
            if (latest == null) continue;
            GrowthFields g = resolveGrowth(quarters.get(stockCode), latest.getPer(), today);
            if (g.measured()) measured++; else unknown++;
            if (applyGrowth(latest, g)) {
                stockFinancialDataRepository.save(latest);
                updatedCount++;
            }
        }

        log.info("성장률 계산 완료 - 종목 {}개 중 측정 {} · 모름 {}(분기 부족·노후·적자 분모) · 값 변경 {}건",
                stockDataMap.size(), measured, unknown, updatedCount);
        return updatedCount;
    }

    /** 최신 KIS 일별 행 — 입력은 report_date 내림차순, market_cap 이 NULL 이 아닌 첫 행(KIS 행은 값 또는 0). 순수. */
    static StockFinancialData latestDailyRow(List<StockFinancialData> descRows) {
        for (StockFinancialData r : descRows) {
            if (r != null && r.getMarketCap() != null) return r;
        }
        return null;
    }

    /** 분기 원본을 종목별 개별 분기로 — 누적(YTD)은 환산, 환산 불가 행은 빠진다({@link QuarterlyFinancials#toIndividualQuarters}). */
    private Map<String, List<QuarterlyFinancials.Figures>> loadIndividualQuarters(LocalDate today) {
        List<StockQuarterlyFinancial> rows = quarterlyRepository.findAllSince(today.minusMonths(GROWTH_LOOKBACK_MONTHS));
        Map<String, List<QuarterlyFinancials.Figures>> out = new HashMap<>();
        if (rows == null) return out;
        Map<String, List<StockQuarterlyFinancial>> byStock = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(StockQuarterlyFinancial::getStockCode));
        for (Map.Entry<String, List<StockQuarterlyFinancial>> e : byStock.entrySet()) {
            List<QuarterlyFinancials.Figures> raw = e.getValue().stream()
                    .map(q -> new QuarterlyFinancials.Figures(q.getFiscalPeriod(), q.getPeriodEnd(),
                            q.getRevenue(), q.getOperatingProfit(), q.getNetIncome(), q.isCumulative()))
                    .collect(java.util.stream.Collectors.toList());
            out.put(e.getKey(), QuarterlyFinancials.toIndividualQuarters(raw));
        }
        return out;
    }

    /** 순수 — 개별 분기 → 저장할 성장률 4종. 최신 분기가 노후면 모름(수집 끊긴 종목의 옛 실적을 오늘 성장률로 쓰지 않는다). */
    static GrowthFields resolveGrowth(List<QuarterlyFinancials.Figures> individuals, BigDecimal per, LocalDate today) {
        QuarterlyFinancials.TtmGrowth t = QuarterlyFinancials.ttmYearOverYear(individuals);
        if (t == null || t.latestPeriodEnd().isBefore(today.minusDays(EarningSurpriseService.QUARTER_MAX_AGE_DAYS))) {
            return new GrowthFields(null, null, null, null);
        }
        BigDecimal profit = t.netIncomeGrowth();
        return new GrowthFields(t.revenueGrowth(), profit, profit, pegOf(per, profit));
    }

    /** PEG = PER / 성장률(%) — PER·성장률이 양수이고 성장률이 기저효과 상한 이하일 때만. 순수. */
    static BigDecimal pegOf(BigDecimal per, BigDecimal growth) {
        if (per == null || per.signum() <= 0 || growth == null || growth.signum() <= 0
                || growth.compareTo(PEG_MAX_GROWTH) > 0) {
            return null;
        }
        return per.divide(growth, 2, RoundingMode.HALF_UP);
    }

    /** 행에 성장률 4종을 쓴다 — 바뀐 게 있을 때만 true(불필요한 저장을 피한다). */
    static boolean applyGrowth(StockFinancialData row, GrowthFields g) {
        boolean changed = !sameValue(row.getRevenueGrowth(), g.revenueGrowth())
                || !sameValue(row.getProfitGrowth(), g.profitGrowth())
                || !sameValue(row.getEpsGrowth(), g.epsGrowth())
                || !sameValue(row.getPeg(), g.peg());
        row.setRevenueGrowth(g.revenueGrowth());
        row.setProfitGrowth(g.profitGrowth());
        row.setEpsGrowth(g.epsGrowth());
        row.setPeg(g.peg());
        return changed;
    }

    private static boolean sameValue(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /**
     * 주식 현재가 조회 (재시도 로직 포함)
     * - 최대 3회 재시도
     * - 재시도 간 500ms 대기
     */
    private JsonNode getStockPriceWithRetry(String stockCode) {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount < MAX_RETRY_COUNT) {
            try {
                JsonNode result = koreaInvestmentService.getStockPrice(stockCode);
                if (result != null) {
                    return result;
                }
                log.debug("주식 현재가 조회 결과 null [{}], 재시도 {}/{}", stockCode, retryCount + 1, MAX_RETRY_COUNT);
            } catch (Exception e) {
                lastException = e;
                log.debug("주식 현재가 조회 실패 [{}], 재시도 {}/{}: {}",
                        stockCode, retryCount + 1, MAX_RETRY_COUNT, e.getMessage());
            }

            retryCount++;
            if (retryCount < MAX_RETRY_COUNT) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (lastException != null) {
            log.warn("주식 현재가 조회 최종 실패 [{}]: {}", stockCode, lastException.getMessage());
        }
        return null;
    }

    /**
     * 재무비율 조회 (재시도 로직 포함)
     * - 최대 3회 재시도
     */
    private ResponseEntity<String> executeWithRetry(String url, HttpEntity<String> request) {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount < MAX_RETRY_COUNT) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
                if (response.getStatusCode() == HttpStatus.OK) {
                    return response;
                }
                log.debug("API 호출 실패 응답: {}, 재시도 {}/{}", response.getStatusCode(), retryCount + 1, MAX_RETRY_COUNT);
            } catch (Exception e) {
                lastException = e;
                log.debug("API 호출 실패, 재시도 {}/{}: {}", retryCount + 1, MAX_RETRY_COUNT, e.getMessage());
            }

            retryCount++;
            if (retryCount < MAX_RETRY_COUNT) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (lastException != null) {
            log.warn("API 호출 최종 실패: {}", lastException.getMessage());
        }
        return null;
    }
}
