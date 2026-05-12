package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.RiskAnalysisDto.DartDisclosure;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * DART(전자공시시스템) API 연동 서비스
 *
 * [기능]
 * - 특정 기업의 최근 공시 조회
 * - 위험 키워드 필터링
 * - DANGER 신호 반환
 *
 * [위험 키워드]
 * 유상증자, 무상감자, 배임, 횡령, 거래정지, 불성실공시, 최대주주변경
 */
@Service
@Slf4j
public class DartService {

    @Value("${dart.api.key:}")
    private String dartApiKey;

    private static final String DART_BASE_URL = "https://opendart.fss.or.kr/api";

    // 위험 키워드 목록
    private static final List<String> DANGER_KEYWORDS = Arrays.asList(
            "유상증자", "무상감자", "배임", "횡령", "거래정지",
            "불성실공시", "최대주주변경", "상장폐지", "감사의견거절",
            "자본잠식", "부도", "파산", "회생절차", "워크아웃"
    );

    private final RestTemplate restTemplate;
    private final RestTemplate corpCodeRestTemplate;  // zip 다운로드용 — 더 긴 timeout
    private final ObjectMapper objectMapper;

    // ========== corpCode 캐시 ==========
    // DART 의 모든 기업 매핑 (corpCode.xml). 시작 시 다운로드 + 매일 06:00 갱신.
    // 기존엔 19개 종목만 하드코딩 매핑이라 매핑 실패 폭발 → 캐시로 정상화.
    private final ConcurrentHashMap<String, String> corpCodeByStockCode = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> corpCodeByName = new ConcurrentHashMap<>();
    private volatile LocalDate corpCodeLoadedDate = null;

    public DartService() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);

        org.springframework.http.client.SimpleClientHttpRequestFactory corpFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        corpFactory.setConnectTimeout(10000);
        corpFactory.setReadTimeout(60000);  // zip 다운로드 — 큰 파일이라 1분
        this.corpCodeRestTemplate = new RestTemplate(corpFactory);

        this.objectMapper = new ObjectMapper();
    }

    // ========== corpCode 캐시 로드/갱신 ==========

    /**
     * 컨테이너 시작 시 corpCode 캐시 로드 — 다른 초기화와 경합 방지 위해 20초 지연.
     * 비동기 — 시작 차단 없음.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void loadCorpCodesOnStartup() {
        try {
            Thread.sleep(20000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        loadCorpCodes();
    }

    /** 매일 06:00 — 신규 상장/상호 변경 반영. */
    @Scheduled(scheduler = "batchScheduler", cron = "0 0 6 * * *", zone = "Asia/Seoul")
    public void refreshCorpCodesScheduled() {
        loadCorpCodes();
    }

    /**
     * DART corpCode.xml 다운로드 → ZIP 압축 해제 → 파싱.
     * stock_code 가 있는 상장 종목만 인덱싱 (비상장 수만 개는 노이즈).
     */
    public synchronized void loadCorpCodes() {
        if (dartApiKey == null || dartApiKey.isEmpty()) {
            log.warn("[DART] API Key 없음 — corpCode 캐시 로드 스킵");
            return;
        }
        long startMs = System.currentTimeMillis();
        try {
            String url = DART_BASE_URL + "/corpCode.xml?crtfc_key=" + dartApiKey;
            byte[] zipBytes = corpCodeRestTemplate.getForObject(url, byte[].class);
            if (zipBytes == null || zipBytes.length == 0) {
                log.error("[DART] corpCode.xml 다운로드 응답 없음");
                return;
            }
            log.info("[DART] corpCode.xml 다운로드 완료 — {}KB, 파싱 시작", zipBytes.length / 1024);

            Map<String, String> nameMap = new HashMap<>();
            Map<String, String> stockMap = new HashMap<>();

            try (ByteArrayInputStream bis = new ByteArrayInputStream(zipBytes);
                 ZipInputStream zis = new ZipInputStream(bis)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (!entry.getName().toUpperCase().endsWith(".XML")) continue;
                    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                    // XXE 방어
                    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                    Document doc = dbf.newDocumentBuilder().parse(zis);
                    NodeList list = doc.getElementsByTagName("list");
                    for (int i = 0; i < list.getLength(); i++) {
                        Element el = (Element) list.item(i);
                        String corpCode = textOf(el, "corp_code");
                        String corpName = textOf(el, "corp_name");
                        String stockCode = textOf(el, "stock_code");
                        if (corpCode == null || corpCode.isBlank()) continue;
                        // stock_code 가 있는 상장 종목만 인덱싱
                        if (stockCode != null && !stockCode.isBlank() && !stockCode.equals(" ")) {
                            String normCode = stockCode.trim();
                            if (normCode.length() == 6) {
                                stockMap.put(normCode, corpCode.trim());
                                if (corpName != null && !corpName.isBlank()) {
                                    nameMap.put(corpName.trim(), corpCode.trim());
                                }
                            }
                        }
                    }
                    break;  // 첫 XML 만 처리
                }
            }

            corpCodeByStockCode.clear();
            corpCodeByStockCode.putAll(stockMap);
            corpCodeByName.clear();
            corpCodeByName.putAll(nameMap);
            corpCodeLoadedDate = LocalDate.now();
            log.info("[DART] corpCode 캐시 로드 완료 — 상장코드 {}개, 회사명 {}개 ({}ms)",
                    stockMap.size(), nameMap.size(), System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            log.error("[DART] corpCode 캐시 로드 실패: {}", e.getMessage(), e);
        }
    }

    private static String textOf(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        return nl.item(0).getTextContent();
    }

    /**
     * 종목코드(6자리) → DART corpCode. 매핑 실패 시 null.
     * stockCode 매칭이 stockName 매칭보다 정확 (회사명 표기 차이 영향 없음).
     */
    public String getCorpCodeByStockCode(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) return null;
        return corpCodeByStockCode.get(stockCode.trim());
    }

    /**
     * 기업코드로 최근 3개월 공시 조회
     *
     * @param corpCode DART 기업코드 (8자리)
     * @return 공시 목록
     */
    @CircuitBreaker(name = "dartApi", fallbackMethod = "disclosuresFallback")
    public List<DartDisclosure> getRecentDisclosures(String corpCode) {
        if (dartApiKey == null || dartApiKey.isEmpty()) {
            log.warn("[DART] API Key가 설정되지 않았습니다.");
            return Collections.emptyList();
        }

        try {
            // 최근 3개월 기간 설정
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusMonths(3);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

            String url = UriComponentsBuilder.fromUriString(DART_BASE_URL + "/list.json")
                    .queryParam("crtfc_key", dartApiKey)
                    .queryParam("corp_code", corpCode)
                    .queryParam("bgn_de", startDate.format(formatter))
                    .queryParam("end_de", endDate.format(formatter))
                    .queryParam("page_count", 100)
                    .build()
                    .toUriString();

            log.info("[DART] 공시 조회: corpCode={}, period={} ~ {}", corpCode, startDate, endDate);

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseDisclosures(response.getBody());
            }

        } catch (Exception e) {
            log.error("[DART] 공시 조회 실패: {}", e.getMessage(), e);
        }

        return Collections.emptyList();
    }

    /**
     * 종목명으로 기업코드 조회 후 공시 검색
     *
     * @param stockName 종목명 (예: 삼성전자)
     * @return 공시 목록 (위험 키워드 체크 완료)
     */
    public List<DartDisclosure> searchDisclosuresByName(String stockName) {
        // 기업코드 조회 — 캐시(이름) → 하드코딩 fallback
        String corpCode = getCorpCodeByName(stockName);
        if (corpCode == null) {
            log.warn("[DART] 기업코드를 찾을 수 없음: {}", stockName);
            // 기업코드 없이 전체 공시에서 검색 시도
            return searchAllDisclosures(stockName);
        }

        List<DartDisclosure> disclosures = getRecentDisclosures(corpCode);

        // 위험 키워드 체크
        for (DartDisclosure disclosure : disclosures) {
            checkDangerKeywords(disclosure);
        }

        return disclosures;
    }

    /**
     * 종목코드(6자리) 기반 공시 검색 — 가장 정확한 매핑.
     * DART corpCode.xml 의 stock_code 인덱스로 매번 정확히 매칭.
     * stockName 은 매핑 실패 시 fallback (캐시 미로드 등).
     */
    public List<DartDisclosure> searchDisclosuresByStockCode(String stockCode, String stockName) {
        String corpCode = getCorpCodeByStockCode(stockCode);
        if (corpCode == null) {
            // 캐시 미로드 또는 신규 상장 → 이름 fallback
            return searchDisclosuresByName(stockName);
        }
        List<DartDisclosure> disclosures = getRecentDisclosures(corpCode);
        for (DartDisclosure d : disclosures) checkDangerKeywords(d);
        return disclosures;
    }

    /**
     * 종목명으로 전체 공시 검색 (기업코드 없이)
     */
    private List<DartDisclosure> searchAllDisclosures(String stockName) {
        if (dartApiKey == null || dartApiKey.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusMonths(3);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

            String url = UriComponentsBuilder.fromUriString(DART_BASE_URL + "/list.json")
                    .queryParam("crtfc_key", dartApiKey)
                    .queryParam("bgn_de", startDate.format(formatter))
                    .queryParam("end_de", endDate.format(formatter))
                    .queryParam("corp_cls", "Y")  // 유가증권시장
                    .queryParam("page_count", 100)
                    .build()
                    .toUriString();

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                List<DartDisclosure> allDisclosures = parseDisclosures(response.getBody());

                // 종목명으로 필터링
                List<DartDisclosure> filtered = new ArrayList<>();
                for (DartDisclosure d : allDisclosures) {
                    if (d.getCorpName() != null && d.getCorpName().contains(stockName)) {
                        checkDangerKeywords(d);
                        filtered.add(d);
                    }
                }
                return filtered;
            }

        } catch (Exception e) {
            log.error("[DART] 전체 공시 검색 실패: {}", e.getMessage());
        }

        return Collections.emptyList();
    }

    /**
     * 종목명으로 DART 기업코드 조회 — 메모리 캐시 우선 + 하드코딩 fallback.
     * 캐시는 corpCode.xml 다운로드(시작 시 + 매일 06:00) 로 채워짐.
     * 정확한 매핑은 종목코드(stockCode) 가 더 안정적이므로 가능하면 getCorpCodeByStockCode 사용 권장.
     */
    public String getCorpCodeByName(String stockName) {
        if (stockName == null || stockName.isBlank()) return null;
        String trimmed = stockName.trim();
        // 1차: corpCode 캐시
        String cached = corpCodeByName.get(trimmed);
        if (cached != null) return cached;
        // 2차: 하드코딩 매핑 (캐시 미로드 시 안전망)
        return getCorpCodeFromMapping(trimmed);
    }

    /**
     * 주요 기업 코드 매핑 (실제 운영 시 DB나 캐시로 관리)
     */
    private String getCorpCodeFromMapping(String stockName) {
        Map<String, String> corpCodeMap = new HashMap<>();
        // 주요 대형주 매핑
        corpCodeMap.put("삼성전자", "00126380");
        corpCodeMap.put("SK하이닉스", "00164779");
        corpCodeMap.put("LG에너지솔루션", "01711413");
        corpCodeMap.put("삼성바이오로직스", "00917503");
        corpCodeMap.put("현대차", "00164742");
        corpCodeMap.put("현대자동차", "00164742");
        corpCodeMap.put("기아", "00164529");
        corpCodeMap.put("셀트리온", "00421045");
        corpCodeMap.put("POSCO홀딩스", "00117631");
        corpCodeMap.put("포스코홀딩스", "00117631");
        corpCodeMap.put("NAVER", "00266961");
        corpCodeMap.put("네이버", "00266961");
        corpCodeMap.put("카카오", "01011885");
        corpCodeMap.put("삼성SDI", "00126186");
        corpCodeMap.put("LG화학", "00356361");
        corpCodeMap.put("현대모비스", "00164788");
        corpCodeMap.put("삼성물산", "00126263");
        corpCodeMap.put("KB금융", "00688996");
        corpCodeMap.put("신한지주", "00382199");
        corpCodeMap.put("하나금융지주", "00547583");

        return corpCodeMap.get(stockName);
    }

    /**
     * DART API 응답 파싱
     */
    private List<DartDisclosure> parseDisclosures(String jsonResponse) {
        List<DartDisclosure> disclosures = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(jsonResponse);

            String status = root.has("status") ? root.get("status").asText() : "";
            if (!"000".equals(status)) {
                String message = root.has("message") ? root.get("message").asText() : "Unknown error";
                log.warn("[DART] API 응답 오류: {} - {}", status, message);
                return disclosures;
            }

            JsonNode list = root.get("list");
            if (list != null && list.isArray()) {
                for (JsonNode item : list) {
                    DartDisclosure disclosure = DartDisclosure.builder()
                            .corpName(getTextValue(item, "corp_name"))
                            .reportNm(getTextValue(item, "report_nm"))
                            .rceptNo(getTextValue(item, "rcept_no"))
                            .rceptDt(getTextValue(item, "rcept_dt"))
                            .flrNm(getTextValue(item, "flr_nm"))
                            .rmk(getTextValue(item, "rm"))
                            .isDangerous(false)
                            .build();

                    disclosures.add(disclosure);
                }
            }

            log.info("[DART] 공시 {}건 조회됨", disclosures.size());

        } catch (Exception e) {
            log.error("[DART] 응답 파싱 실패: {}", e.getMessage());
        }

        return disclosures;
    }

    /**
     * 위험 키워드 체크
     */
    // 종속회사/자회사 경유 공시 + 담보 제공 등 일반 상행위는 본체 위험에서 제외.
    // 운영 로그에서 "최대주주변경을수반하는주식담보제공계약체결" 11건 false positive 발견 — 일반 담보계약은
    // 실제 최대주주 변경 위험이 아니라 자금 조달 행위이므로 제외.
    private static final List<String> EXCLUDE_PATTERNS = Arrays.asList(
            "종속회사", "자회사", "타법인", "주식담보제공"
    );

    private void checkDangerKeywords(DartDisclosure disclosure) {
        String reportNm = disclosure.getReportNm();
        if (reportNm == null) return;

        // 종속회사/자회사 공시는 위험 제외
        for (String exclude : EXCLUDE_PATTERNS) {
            if (reportNm.contains(exclude)) return;
        }

        for (String keyword : DANGER_KEYWORDS) {
            if (reportNm.contains(keyword)) {
                disclosure.setDangerous(true);
                disclosure.setMatchedKeyword(keyword);
                log.warn("[DART] 위험 공시 발견: {} - {} (키워드: {})",
                        disclosure.getCorpName(), reportNm, keyword);
                return;
            }
        }
    }

    /**
     * 위험 공시 존재 여부 확인
     */
    public boolean hasDangerousDisclosure(List<DartDisclosure> disclosures) {
        return disclosures.stream().anyMatch(DartDisclosure::isDangerous);
    }

    /**
     * 위험 공시만 필터링
     */
    public List<DartDisclosure> filterDangerousDisclosures(List<DartDisclosure> disclosures) {
        return disclosures.stream()
                .filter(DartDisclosure::isDangerous)
                .toList();
    }

    /** CircuitBreaker OPEN / 모든 retry 실패 시 fallback — 빈 리스트로 호출자가 안전하게 진행 */
    @SuppressWarnings("unused")
    private List<DartDisclosure> disclosuresFallback(String corpCode, Throwable t) {
        log.warn("[DART CircuitBreaker] 공시 조회 fallback — corpCode: {}, cause: {}",
                corpCode, t.getClass().getSimpleName() + ": " + t.getMessage());
        return Collections.emptyList();
    }

    private String getTextValue(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    /**
     * DART API 사용 가능 여부
     */
    public boolean isAvailable() {
        return dartApiKey != null && !dartApiKey.isEmpty();
    }
}
