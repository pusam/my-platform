package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.RiskAnalysisDto.DartDisclosure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

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
    private final ObjectMapper objectMapper;

    public DartService() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 기업코드로 최근 3개월 공시 조회
     *
     * @param corpCode DART 기업코드 (8자리)
     * @return 공시 목록
     */
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
        // 기업코드 조회
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
     * 종목명으로 DART 기업코드 조회
     */
    public String getCorpCodeByName(String stockName) {
        if (dartApiKey == null || dartApiKey.isEmpty()) {
            return null;
        }

        try {
            // 기업개황 API로 검색
            String url = UriComponentsBuilder.fromUriString(DART_BASE_URL + "/corpCode.xml")
                    .queryParam("crtfc_key", dartApiKey)
                    .build()
                    .toUriString();

            // Note: 실제로는 corpCode.xml을 다운로드 후 파싱해야 함
            // 여기서는 간단히 주요 기업 매핑 테이블 사용
            return getCorpCodeFromMapping(stockName);

        } catch (Exception e) {
            log.error("[DART] 기업코드 조회 실패: {}", e.getMessage());
        }

        return null;
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
    private void checkDangerKeywords(DartDisclosure disclosure) {
        String reportNm = disclosure.getReportNm();
        if (reportNm == null) return;

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
