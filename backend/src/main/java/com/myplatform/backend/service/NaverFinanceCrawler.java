package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

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

    // === KRX API 설정 (우선 사용) ===
    private static final String KRX_OTP_URL = "http://data.krx.co.kr/comm/bldAttendant/getOtp.cmd";
    private static final String KRX_DATA_URL = "http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd";
    private static final String KRX_REFERER = "http://data.krx.co.kr/contents/MDC/MDI/mdiLoader/index.cmd?menuId=MDC0201020101";
    private static final String KRX_SHORT_SELLING_BLD = "dbms/MDC/STAT/srt/MDCSTAT30101";  // 공매도 거래
    private static final String KRX_SHORT_BALANCE_BLD = "dbms/MDC/STAT/srt/MDCSTAT30501";  // 공매도 잔고

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // === 네이버 금융 설정 (KRX 실패 시 폴백) ===
    private static final String BASE_URL = "https://finance.naver.com";
    // 여러 URL 형식 시도 (네이버 페이지 구조 변경 대응)
    private static final String[] SHORT_SELLING_URL_PATTERNS = {
            BASE_URL + "/item/short_trade.naver?code=%s",  // 새 URL
            BASE_URL + "/item/short.naver?code=%s",        // 기존 URL
            BASE_URL + "/item/frgn.naver?code=%s"          // 외국인 거래 (폴백)
    };
    private static final String LENDING_URL = BASE_URL + "/item/lending.naver?code=%s";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private static final int CONNECTION_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 20000;

    // 네이버 차단 방지용 설정
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final int MIN_DELAY_MS = 2000;  // 최소 2초 (더 보수적)
    private static final int MAX_DELAY_MS = 4000;  // 최대 4초

    // 여러 테이블 셀렉터 시도
    private static final String[] TABLE_SELECTORS = {
            "table.type2",
            "table.type_1",
            "table.type_5",
            "table[summary*='공매도']",
            "table[summary*='거래']",
            ".section table",
            "#content table"
    };

    // 대차잔고 페이지 404 발생 시 더 이상 시도하지 않음 (네이버 페이지 폐지 대응)
    private static final AtomicBoolean lendingPageUnavailable = new AtomicBoolean(false);

    /**
     * 공매도 일별 데이터 크롤링
     * 1. KRX API 우선 사용 (안정적, 공식 데이터)
     * 2. KRX 실패 시 네이버 금융 폴백
     *
     * @param stockCode 종목코드 (6자리)
     * @param days 조회할 일수 (기본 30일)
     * @return 일별 공매도 데이터 리스트
     */
    public List<ShortSellingData> crawlShortSellingData(String stockCode, int days) {
        // 1. KRX API 우선 시도
        List<ShortSellingData> result = crawlFromKrx(stockCode, days);
        if (!result.isEmpty()) {
            log.info("KRX 공매도 데이터 수집 성공 [{}]: {}건", stockCode, result.size());
            return result;
        }

        // 2. KRX 실패 시 네이버 금융 폴백
        log.debug("KRX 실패, 네이버 금융으로 폴백 [{}]", stockCode);
        result = crawlFromNaver(stockCode, days);

        if (result.isEmpty()) {
            log.warn("공매도 데이터 수집 실패 [{}]: KRX/네이버 모두 실패", stockCode);
        }

        return result;
    }

    /**
     * KRX API로 공매도 데이터 수집 (OTP 기반 인증)
     * - data.krx.co.kr 공식 API 사용
     * - Step 1: getOtp.cmd로 OTP 코드 획득
     * - Step 2: getJsonData.cmd에 OTP 코드로 데이터 요청
     * - 종목별 공매도 거래 데이터 조회 (MDCSTAT30101)
     */
    private List<ShortSellingData> crawlFromKrx(String stockCode, int days) {
        List<ShortSellingData> result = new ArrayList<>();

        try {
            // 딜레이
            randomDelay();

            log.info("KRX API 호출 시작 [{}] - OTP 기반 인증", stockCode);

            // 조회 기간 계산 (최근 days일)
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(days + 10);

            // headers 파라미터는 이제 사용하지 않지만, 메서드 시그니처 호환성 유지
            HttpHeaders headers = new HttpHeaders();

            // 1차 시도: 종목별 공매도 조회 (MDCSTAT30101) - 시장 전체 조회 후 필터링
            result = tryKrxShortSellingByMarket(headers, stockCode, startDate, endDate, days);
            if (!result.isEmpty()) {
                return result;
            }

            // 2차 시도: 개별 종목 조회 (isuCd 파라미터 사용)
            result = tryKrxShortSellingByStock(headers, stockCode, startDate, endDate, days);

        } catch (Exception e) {
            log.warn("KRX API 호출 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return result;
    }

    /**
     * KRX OTP 코드 획득
     * Step 1: getOtp.cmd를 호출하여 암호화된 code를 획득
     *
     * @param params 데이터 요청에 사용할 파라미터 (bld, mktId, trdDd 등)
     * @return OTP 코드 문자열 (실패 시 null)
     */
    private String getKrxOtpCode(MultiValueMap<String, String> params) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Accept", "*/*");
            headers.set("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.set("User-Agent", USER_AGENT);
            headers.set("Referer", KRX_REFERER);
            headers.set("Origin", "http://data.krx.co.kr");

            // OTP 요청용 파라미터 (데이터 요청과 동일하게 구성)
            MultiValueMap<String, String> otpParams = new LinkedMultiValueMap<>(params);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(otpParams, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    KRX_OTP_URL, HttpMethod.POST, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                String otp = response.getBody().trim();
                if (!otp.isEmpty() && !otp.contains("LOGOUT") && !otp.contains("error")) {
                    log.debug("KRX OTP 획득 성공: {}...", otp.substring(0, Math.min(20, otp.length())));
                    return otp;
                }
                log.warn("KRX OTP 응답 이상: {}", otp.substring(0, Math.min(100, otp.length())));
            }
        } catch (Exception e) {
            log.warn("KRX OTP 획득 실패: {}", e.getMessage());
        }
        return null;
    }

    /**
     * KRX 데이터 요청 (OTP 사용)
     * Step 2: 획득한 OTP 코드를 사용하여 getJsonData.cmd 호출
     *
     * @param otpCode OTP 코드
     * @return JSON 응답 문자열 (실패 시 null)
     */
    private String requestKrxDataWithOtp(String otpCode) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Accept", "application/json, text/javascript, */*; q=0.01");
            headers.set("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.set("User-Agent", USER_AGENT);
            headers.set("Referer", KRX_REFERER);
            headers.set("Origin", "http://data.krx.co.kr");
            headers.set("X-Requested-With", "XMLHttpRequest");

            // 데이터 요청 파라미터 (OTP 코드만 전송)
            MultiValueMap<String, String> dataParams = new LinkedMultiValueMap<>();
            dataParams.add("code", otpCode);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(dataParams, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    KRX_DATA_URL, HttpMethod.POST, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                String body = response.getBody();
                // LOGOUT 응답 체크
                if (body.contains("LOGOUT") || body.contains("접속 권한이 없습니다")) {
                    log.warn("KRX 데이터 요청 실패: 인증 오류 - {}", body.substring(0, Math.min(100, body.length())));
                    return null;
                }
                return body;
            }
        } catch (Exception e) {
            log.warn("KRX 데이터 요청 실패: {}", e.getMessage());
        }
        return null;
    }

    /**
     * KRX 시장 전체 공매도 조회 후 특정 종목 필터링
     * OTP 기반 인증 사용
     */
    private List<ShortSellingData> tryKrxShortSellingByMarket(HttpHeaders headers, String stockCode,
                                                              LocalDate startDate, LocalDate endDate, int days) {
        List<ShortSellingData> result = new ArrayList<>();

        try {
            log.info("KRX 시장 전체 조회 시작 [{}] - KOSPI (OTP 인증)", stockCode);

            // Step 1: KOSPI용 OTP 획득
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("bld", KRX_SHORT_SELLING_BLD);  // MDCSTAT30101
            params.add("mktId", "STK");  // 유가증권시장 (KOSPI)
            params.add("trdDd", endDate.format(DateTimeFormatter.BASIC_ISO_DATE));
            params.add("csvxls_isNo", "false");

            String otpCode = getKrxOtpCode(params);
            if (otpCode == null) {
                log.warn("KRX KOSPI OTP 획득 실패 [{}]", stockCode);
                return result;
            }

            // Step 2: OTP로 데이터 요청
            String body = requestKrxDataWithOtp(otpCode);

            if (body != null) {
                log.info("KRX KOSPI 응답 [{}]: bodyLength={}", stockCode, body.length());

                // 응답 미리보기 (처음 300자)
                if (body.length() > 0) {
                    String preview = body.length() > 300 ? body.substring(0, 300) + "..." : body;
                    log.info("KRX KOSPI 응답 미리보기: {}", preview);
                }

                // 응답에서 특정 종목 필터링
                result = parseKrxMarketResponse(body, stockCode, days);
                log.info("KRX KOSPI 파싱 결과 [{}]: {}건", stockCode, result.size());

                if (result.isEmpty()) {
                    // KOSDAQ 시도
                    log.info("KRX KOSDAQ 조회 시작 [{}] (OTP 인증)", stockCode);

                    // KOSDAQ용 OTP 획득
                    params.set("mktId", "KSQ");
                    String kosdaqOtp = getKrxOtpCode(params);

                    if (kosdaqOtp != null) {
                        String kosdaqBody = requestKrxDataWithOtp(kosdaqOtp);

                        if (kosdaqBody != null) {
                            log.info("KRX KOSDAQ 응답 [{}]: bodyLength={}", stockCode, kosdaqBody.length());
                            result = parseKrxMarketResponse(kosdaqBody, stockCode, days);
                            log.info("KRX KOSDAQ 파싱 결과 [{}]: {}건", stockCode, result.size());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("KRX 시장 공매도 조회 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return result;
    }

    /**
     * KRX 개별 종목 공매도 조회
     * OTP 기반 인증 사용
     */
    private List<ShortSellingData> tryKrxShortSellingByStock(HttpHeaders headers, String stockCode,
                                                             LocalDate startDate, LocalDate endDate, int days) {
        List<ShortSellingData> result = new ArrayList<>();

        try {
            // ISIN 코드 생성 (KRX API는 ISIN 또는 A+종목코드 형식 필요)
            String isinCode = convertToIsin(stockCode);
            String aCode = "A" + stockCode;  // A005930 형식

            log.info("KRX 개별 종목 조회 시작 [{}] -> ISIN: {}, A코드: {} (OTP 인증)", stockCode, isinCode, aCode);

            // 개별 종목 조회 (MDCSTAT30301 - 종목별 일별 추이)
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("bld", "dbms/MDC/STAT/srt/MDCSTAT30301");
            params.add("isuCd", isinCode);  // ISIN 코드
            params.add("isuCd2", aCode);    // A+종목코드
            params.add("strtDd", startDate.format(DateTimeFormatter.BASIC_ISO_DATE));
            params.add("endDd", endDate.format(DateTimeFormatter.BASIC_ISO_DATE));
            params.add("csvxls_isNo", "false");

            // Step 1: OTP 획득
            String otpCode = getKrxOtpCode(params);
            if (otpCode == null) {
                log.warn("KRX 개별 종목 OTP 획득 실패 [{}]", stockCode);
                return result;
            }

            // Step 2: OTP로 데이터 요청
            String body = requestKrxDataWithOtp(otpCode);

            if (body != null) {
                log.info("KRX 개별 종목 응답 [{}]: bodyLength={}", stockCode, body.length());

                // 응답 미리보기 (항상 로깅)
                if (body.length() > 0) {
                    String preview = body.length() > 500 ? body.substring(0, 500) + "..." : body;
                    log.info("KRX 개별 종목 응답 [{}]: {}", stockCode, preview);
                }
                result = parseKrxResponse(body, days, stockCode);
            }
        } catch (Exception e) {
            log.warn("KRX 개별 종목 조회 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return result;
    }

    /**
     * 종목코드를 ISIN 코드로 변환
     * 예: 005930 -> KR7005930003
     * ISIN 형식: KR + 7(주식) + 6자리코드 + 00 + 체크디지트
     */
    private String convertToIsin(String stockCode) {
        if (stockCode == null || stockCode.length() != 6) {
            return stockCode;
        }

        // 기본 ISIN 구조: KR7 + 종목코드(6자리) + 00 + 체크디지트
        String baseIsin = "KR7" + stockCode + "00";

        // Luhn 알고리즘 변형으로 체크디지트 계산
        int checkDigit = calculateIsinCheckDigit(baseIsin);

        return baseIsin + checkDigit;
    }

    /**
     * ISIN 체크디지트 계산 (Luhn 알고리즘 변형)
     */
    private int calculateIsinCheckDigit(String baseIsin) {
        // 문자를 숫자로 변환 (A=10, B=11, ... Z=35)
        StringBuilder digits = new StringBuilder();
        for (char c : baseIsin.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (Character.isLetter(c)) {
                digits.append(Character.toUpperCase(c) - 'A' + 10);
            }
        }

        // Luhn 알고리즘
        String numStr = digits.toString();
        int sum = 0;
        boolean alternate = true;  // 오른쪽부터 시작, 첫번째는 2배

        for (int i = numStr.length() - 1; i >= 0; i--) {
            int n = Character.getNumericValue(numStr.charAt(i));
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n = (n % 10) + 1;
                }
            }
            sum += n;
            alternate = !alternate;
        }

        return (10 - (sum % 10)) % 10;
    }

    /**
     * KRX 시장 전체 응답에서 특정 종목 필터링
     */
    private List<ShortSellingData> parseKrxMarketResponse(String responseBody, String stockCode, int days) {
        List<ShortSellingData> result = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataArray = root.get("OutBlock_1");

            if (dataArray == null || !dataArray.isArray()) {
                log.info("KRX 시장 응답에 OutBlock_1 없음, keys: {}", getJsonKeys(root));
                return result;
            }

            log.info("KRX 시장 응답 데이터: {}건", dataArray.size());

            for (JsonNode item : dataArray) {
                // 종목 코드 매칭 (여러 형식 시도)
                String isuSrtCd = getFieldValue(item, "ISU_SRT_CD", "isuSrtCd", "ISU_CD");

                // A005930 -> 005930, KR7005930003 -> 005930 형식으로 정규화
                String normalizedCode = normalizeStockCode(isuSrtCd);

                if (!stockCode.equals(normalizedCode)) {
                    continue;
                }

                log.info("KRX 종목 매칭 성공 [{}]: raw={}", stockCode, isuSrtCd);

                try {
                    ShortSellingData data = new ShortSellingData();

                    // 거래일
                    String dateStr = getFieldValue(item, "TRD_DD", "trdDd", "STD_DT");
                    LocalDate tradeDate = parseKrxDate(dateStr);
                    if (tradeDate == null) {
                        tradeDate = LocalDate.now();  // 당일 데이터
                    }
                    data.setTradeDate(tradeDate);

                    // 공매도 거래량
                    data.setShortVolume(parseKrxNumber(item, "CVSRTSELL_TRDVOL"));
                    // 총 거래량
                    data.setTotalVolume(parseKrxNumber(item, "ACC_TRDVOL"));
                    // 공매도 비율
                    data.setShortRatio(parseKrxNumber(item, "CVSRTSELL_TRDVOL_WT"));
                    // 공매도 거래대금
                    data.setShortTradingValue(parseKrxNumber(item, "CVSRTSELL_TRDVAL"));
                    // 종가
                    data.setClosePrice(parseKrxNumber(item, "TDD_CLSPRC"));

                    result.add(data);
                    log.info("KRX 공매도 데이터 [{}]: date={}, shortVol={}, ratio={}%",
                            stockCode, data.getTradeDate(), data.getShortVolume(), data.getShortRatio());

                    if (result.size() >= days) break;

                } catch (Exception e) {
                    log.trace("KRX 항목 파싱 실패: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("KRX 시장 응답 파싱 실패: {}", e.getMessage());
        }

        return result;
    }

    /**
     * KRX API 응답 파싱 (개별 종목 조회)
     */
    private List<ShortSellingData> parseKrxResponse(String responseBody, int days, String stockCode) {
        List<ShortSellingData> result = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 여러 가능한 데이터 배열 이름 시도
            JsonNode dataArray = root.get("OutBlock_1");
            if (dataArray == null) dataArray = root.get("output");
            if (dataArray == null) dataArray = root.get("block1");

            if (dataArray == null || !dataArray.isArray()) {
                // JSON 구조 로깅 (디버깅)
                log.info("KRX 응답 구조 [{}]: keys={}", stockCode, getJsonKeys(root));
                return result;
            }

            log.info("KRX 응답 데이터 [{}]: {}건", stockCode, dataArray.size());

            int count = 0;
            for (JsonNode item : dataArray) {
                if (count >= days) break;

                try {
                    // 날짜 파싱 (여러 필드명 시도)
                    String dateStr = getFieldValue(item, "TRD_DD", "trdDd", "trd_dd", "STD_DT");
                    if (dateStr.isEmpty()) continue;

                    LocalDate tradeDate = parseKrxDate(dateStr);
                    if (tradeDate == null) continue;

                    ShortSellingData data = new ShortSellingData();
                    data.setTradeDate(tradeDate);

                    // 공매도량 (여러 필드명 시도)
                    data.setShortVolume(parseKrxNumberMulti(item,
                            "CVSRTSELL_TRDVOL", "cvsrtsellTrdvol", "SHTSALE_TRDVOL", "ACC_SHTSALE_VOL"));
                    // 총 거래량
                    data.setTotalVolume(parseKrxNumberMulti(item,
                            "ACC_TRDVOL", "accTrdvol", "TOTAL_TRDVOL"));
                    // 공매도 비율
                    data.setShortRatio(parseKrxNumberMulti(item,
                            "CVSRTSELL_TRDVOL_RATE", "CVSRTSELL_TRDVOL_WT", "SHTSALE_WT", "ACC_SHTSALE_RATIO"));
                    // 공매도 거래대금
                    data.setShortTradingValue(parseKrxNumberMulti(item,
                            "CVSRTSELL_TRDVAL", "cvsrtsellTrdval", "SHTSALE_TRDVAL"));
                    // 종가
                    data.setClosePrice(parseKrxNumberMulti(item,
                            "TDD_CLSPRC", "tddClsprc", "CLSPRC"));

                    result.add(data);
                    count++;

                    // 첫 번째 항목 상세 로깅
                    if (count == 1) {
                        log.info("KRX 첫 번째 데이터 [{}]: date={}, shortVol={}, ratio={}%",
                                stockCode, data.getTradeDate(), data.getShortVolume(), data.getShortRatio());
                    }

                } catch (Exception e) {
                    log.trace("KRX 항목 파싱 실패 [{}]: {}", stockCode, e.getMessage());
                }
            }

            log.info("KRX 파싱 완료 [{}]: {}건", stockCode, result.size());

        } catch (Exception e) {
            log.warn("KRX 응답 파싱 실패 [{}]: {}", stockCode, e.getMessage());
        }

        return result;
    }

    /**
     * JSON 노드의 키 목록 반환 (디버깅용)
     */
    private String getJsonKeys(JsonNode node) {
        if (node == null || !node.isObject()) return "[]";
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys.toString();
    }

    /**
     * 여러 필드명으로 값 조회
     */
    private String getFieldValue(JsonNode item, String... fieldNames) {
        for (String field : fieldNames) {
            if (item.has(field)) {
                return item.get(field).asText("");
            }
        }
        return "";
    }

    /**
     * 종목코드 정규화 (다양한 형식을 6자리 숫자로 변환)
     * A005930 -> 005930
     * KR7005930003 -> 005930
     * 005930 -> 005930
     */
    private String normalizeStockCode(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }

        // A005930 형식
        if (code.length() == 7 && code.startsWith("A")) {
            return code.substring(1);
        }

        // KR7005930003 형식 (ISIN)
        if (code.length() == 12 && code.startsWith("KR7")) {
            return code.substring(3, 9);
        }

        // 이미 6자리면 그대로 반환
        if (code.length() == 6 && code.matches("\\d{6}")) {
            return code;
        }

        return code;
    }

    /**
     * 여러 필드명으로 숫자 조회
     */
    private BigDecimal parseKrxNumberMulti(JsonNode item, String... fieldNames) {
        for (String field : fieldNames) {
            if (item.has(field)) {
                return parseBigDecimal(item.get(field).asText());
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * KRX 날짜 형식 파싱 (YYYY/MM/DD 또는 YYYYMMDD)
     */
    private LocalDate parseKrxDate(String dateStr) {
        try {
            String cleaned = dateStr.replace("/", "").replace("-", "").replace(".", "");
            if (cleaned.length() == 8) {
                return LocalDate.parse(cleaned, DateTimeFormatter.BASIC_ISO_DATE);
            }
        } catch (Exception e) {
            log.trace("KRX 날짜 파싱 실패: {}", dateStr);
        }
        return null;
    }

    /**
     * KRX JSON 숫자 필드 파싱
     */
    private BigDecimal parseKrxNumber(JsonNode item, String fieldName) {
        if (!item.has(fieldName)) return BigDecimal.ZERO;
        String value = item.get(fieldName).asText();
        return parseBigDecimal(value);
    }

    /**
     * 네이버 금융에서 공매도 데이터 크롤링 (폴백)
     */
    private List<ShortSellingData> crawlFromNaver(String stockCode, int days) {
        List<ShortSellingData> result = new ArrayList<>();

        // 여러 URL 패턴 시도
        for (String urlPattern : SHORT_SELLING_URL_PATTERNS) {
            try {
                // 네이버 차단 방지: 2~4초 랜덤 딜레이
                randomDelay();

                String url = String.format(urlPattern, stockCode);
                log.debug("네이버 공매도 크롤링 시도: {}", url);

                Document doc = Jsoup.connect(url)
                        .userAgent(USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                        .header("Referer", "https://finance.naver.com/")
                        .header("Cache-Control", "no-cache")
                        .timeout(CONNECTION_TIMEOUT)
                        .get();

                // 여러 테이블 셀렉터 시도
                Element table = findTable(doc, stockCode);
                if (table == null) {
                    log.debug("테이블을 찾지 못함 (URL: {}), 다음 URL 시도", url);
                    continue;
                }

                // 테이블 파싱
                result = parseShortSellingTable(table, days, stockCode);

                if (!result.isEmpty()) {
                    log.info("네이버 공매도 크롤링 성공 [{}]: {}건", stockCode, result.size());
                    return result;
                }

            } catch (HttpStatusException e) {
                log.debug("HTTP 오류 [{}]: {} - {}", stockCode, e.getStatusCode(), e.getMessage());
            } catch (Exception e) {
                log.debug("네이버 크롤링 실패 [{}]: {}", stockCode, e.getMessage());
            }
        }

        return result;
    }

    /**
     * 여러 셀렉터로 테이블 찾기
     */
    private Element findTable(Document doc, String stockCode) {
        for (String selector : TABLE_SELECTORS) {
            Element table = doc.selectFirst(selector);
            if (table != null) {
                // 테이블에 데이터가 있는지 확인
                Elements rows = table.select("tbody tr, tr");
                if (rows.size() > 1) {
                    log.debug("테이블 발견 [{}]: selector={}, rows={}", stockCode, selector, rows.size());
                    return table;
                }
            }
        }

        // 디버깅: 페이지 구조 로깅
        Elements allTables = doc.select("table");
        if (allTables.isEmpty()) {
            log.debug("페이지에 테이블이 없습니다 [{}]", stockCode);
        } else {
            log.debug("페이지에 {} 개의 테이블 존재, 매칭되는 셀렉터 없음 [{}]", allTables.size(), stockCode);
        }

        return null;
    }

    /**
     * 공매도 테이블 파싱
     */
    private List<ShortSellingData> parseShortSellingTable(Element table, int days, String stockCode) {
        List<ShortSellingData> result = new ArrayList<>();

        Elements rows = table.select("tbody tr");
        if (rows.isEmpty()) {
            rows = table.select("tr");
        }

        int count = 0;
        for (Element row : rows) {
            if (count >= days) break;

            Elements cells = row.select("td");
            if (cells.size() < 5) continue;  // 최소 5개 컬럼 필요

            try {
                String dateStr = cells.get(0).text().trim();
                if (dateStr.isEmpty() || !dateStr.contains(".")) continue;

                // 날짜 형식 파싱 (yyyy.MM.dd 또는 yy.MM.dd)
                LocalDate tradeDate = parseDate(dateStr);
                if (tradeDate == null) continue;

                // 데이터 파싱 (컬럼 수에 따라 유연하게 처리)
                BigDecimal shortVolume = parseBigDecimal(cells.get(1).text());
                BigDecimal totalVolume = cells.size() > 2 ? parseBigDecimal(cells.get(2).text()) : BigDecimal.ZERO;
                BigDecimal shortRatio = cells.size() > 3 ? parseBigDecimal(cells.get(3).text().replace("%", "")) : BigDecimal.ZERO;
                BigDecimal shortTradingValue = cells.size() > 4 ? parseBigDecimal(cells.get(4).text()) : BigDecimal.ZERO;
                BigDecimal closePrice = cells.size() > 5 ? parseBigDecimal(cells.get(5).text()) : BigDecimal.ZERO;

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
                log.trace("행 파싱 실패 [{}]: {}", stockCode, e.getMessage());
            }
        }

        return result;
    }

    /**
     * 날짜 문자열 파싱 (여러 형식 지원)
     */
    private LocalDate parseDate(String dateStr) {
        try {
            // yyyy.MM.dd 형식
            if (dateStr.matches("\\d{4}\\.\\d{2}\\.\\d{2}")) {
                return LocalDate.parse(dateStr, DATE_FORMATTER);
            }
            // yy.MM.dd 형식
            if (dateStr.matches("\\d{2}\\.\\d{2}\\.\\d{2}")) {
                return LocalDate.parse("20" + dateStr, DATE_FORMATTER);
            }
        } catch (Exception e) {
            log.trace("날짜 파싱 실패: {}", dateStr);
        }
        return null;
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
