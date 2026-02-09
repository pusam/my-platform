package com.myplatform.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OllamaService {

    @Value("${ollama.url:http://localhost:11434}")
    private String ollamaUrl;

    @Value("${ollama.model:llama3.2:3b}")
    private String modelName;

    private final RestTemplate restTemplate;
    private final RestTemplate fastRestTemplate; // 빠른 응답용 (타임아웃 짧음)

    // 타임아웃 설정 (밀리초)
    private static final int DEFAULT_CONNECT_TIMEOUT = 5000;   // 연결 타임아웃 5초
    private static final int DEFAULT_READ_TIMEOUT = 60000;     // 읽기 타임아웃 60초
    private static final int FAST_READ_TIMEOUT = 30000;        // 빠른 읽기 타임아웃 30초

    public OllamaService() {
        // 기본 RestTemplate (60초 타임아웃)
        SimpleClientHttpRequestFactory defaultFactory = new SimpleClientHttpRequestFactory();
        defaultFactory.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        defaultFactory.setReadTimeout(DEFAULT_READ_TIMEOUT);
        this.restTemplate = new RestTemplate(defaultFactory);

        // 빠른 RestTemplate (30초 타임아웃) - 리스크 분석용
        SimpleClientHttpRequestFactory fastFactory = new SimpleClientHttpRequestFactory();
        fastFactory.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        fastFactory.setReadTimeout(FAST_READ_TIMEOUT);
        this.fastRestTemplate = new RestTemplate(fastFactory);
    }

    /**
     * Ollama API를 호출하여 AI 응답을 생성합니다.
     */
    public String chat(String userMessage, String systemPrompt) {
        try {
            String url = ollamaUrl + "/api/generate";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("prompt", buildPrompt(userMessage, systemPrompt));
            requestBody.put("stream", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return (String) response.getBody().get("response");
            }

            return "죄송합니다. 응답을 생성하는데 문제가 발생했습니다.";

        } catch (Exception e) {
            log.error("Ollama AI 서버 연결 실패: {}", e.getMessage(), e);
            return "AI 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.";
        }
    }

    /**
     * 사용자 데이터를 포함한 맞춤형 상담을 제공합니다.
     */
    public String consultWithContext(String userMessage, String userContext) {
        String systemPrompt = """
            [중요] 반드시 한국어로만 답변하세요. 영어, 중국어, 한자 등 다른 언어를 절대 사용하지 마세요.

            당신은 친절하고 전문적인 재무 상담사입니다.
            사용자의 자산 현황과 가계부 데이터를 바탕으로 맞춤형 조언을 제공합니다.

            [규칙]
            - 오직 한국어만 사용
            - 한자, 영어, 중국어 사용 금지
            - 간결하고 실용적으로 답변
            - 사용자 데이터 기반으로 구체적 조언 제공

            사용자 재무 현황:
            """ + userContext;

        return chat(userMessage, systemPrompt);
    }

    /**
     * 일반 대화용 채팅
     */
    public String generalChat(String userMessage) {
        String systemPrompt = """
            [중요] 반드시 한국어로만 답변하세요. 영어, 중국어 등 다른 언어를 절대 사용하지 마세요.

            당신은 친절한 AI 재무 상담사입니다.
            재무, 자산 관리, 가계부, 저축, 투자, 지출 관리 관련 질문에 전문적으로 답변합니다.

            [규칙]
            - 오직 한국어만 사용
            - 한자, 영어, 중국어 사용 금지
            - 간결하고 명확하게 답변
            - 재무/자산/가계부 관련 질문에만 답변
            - 관련 없는 질문(정치, 연예, 게임, 코딩 등)은 정중히 거절하고 재무 상담으로 안내

            [관련 없는 질문 예시 답변]
            "저는 재무 상담 전문 AI입니다. 자산 관리, 가계부, 저축, 투자 관련 질문을 도와드릴 수 있어요. 재무 관련 궁금한 점이 있으신가요?"
            """;

        return chat(userMessage, systemPrompt);
    }

    private String buildPrompt(String userMessage, String systemPrompt) {
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            return systemPrompt + "\n\n사용자: " + userMessage + "\n\n상담사:";
        }
        return "사용자: " + userMessage + "\n\n상담사:";
    }

    /**
     * Ollama 서버 상태 확인
     */
    public boolean isAvailable() {
        try {
            String url = ollamaUrl + "/api/tags";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 주식 리스크 분석 (공시 + 뉴스 기반) - 빠른 응답용
     *
     * @param stockName 종목명
     * @param disclosures 공시 정보 (텍스트)
     * @param news 뉴스 정보 (텍스트)
     * @return AI 분석 결과 (JSON 형식)
     */
    public String analyzeRisk(String stockName, String disclosures, String news) {
        // 간결한 프롬프트로 빠른 응답 유도
        String systemPrompt = """
            주식 리스크 분석가. 간결하게 JSON만 응답.
            치명적 악재(횡령,상폐,감자)=80-100점/DANGER
            주의 필요(실적악화,부정뉴스)=31-79점/WARNING
            안전=0-30점/SAFE
            """;

        // 공시/뉴스를 요약하여 입력 크기 줄임
        String shortDisclosures = truncateText(disclosures, 500);
        String shortNews = truncateText(news, 500);

        String userMessage = String.format("""
            종목: %s
            공시: %s
            뉴스: %s
            JSON응답: {"riskScore":숫자,"status":"상태","reason":"사유","analysis":"분석"}
            """, stockName, shortDisclosures, shortNews);

        return chatFast(userMessage, systemPrompt);
    }

    /**
     * 텍스트를 지정된 길이로 자르기
     */
    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 빠른 AI 응답 (30초 타임아웃)
     */
    public String chatFast(String userMessage, String systemPrompt) {
        try {
            String url = ollamaUrl + "/api/generate";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("prompt", buildPrompt(userMessage, systemPrompt));
            requestBody.put("stream", false);
            // 토큰 수 제한으로 빠른 응답 유도
            requestBody.put("options", Map.of(
                "num_predict", 200,  // 최대 200 토큰
                "temperature", 0.3   // 낮은 온도로 일관된 응답
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("[Ollama] 빠른 리스크 분석 요청 시작");
            long startTime = System.currentTimeMillis();

            ResponseEntity<Map> response = fastRestTemplate.postForEntity(url, entity, Map.class);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[Ollama] 리스크 분석 완료: {}ms", elapsed);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return (String) response.getBody().get("response");
            }

            return null;

        } catch (Exception e) {
            log.warn("[Ollama] 빠른 분석 실패 (타임아웃 또는 오류): {}", e.getMessage());
            return null; // null 반환 시 규칙 기반 분석으로 폴백
        }
    }

    /**
     * 리스크 분석 결과에서 점수 추출
     */
    public int extractRiskScore(String aiResponse) {
        try {
            // JSON에서 riskScore 추출 시도
            if (aiResponse.contains("\"riskScore\"")) {
                int start = aiResponse.indexOf("\"riskScore\"");
                int colonIndex = aiResponse.indexOf(":", start);
                int commaIndex = aiResponse.indexOf(",", colonIndex);
                if (commaIndex == -1) commaIndex = aiResponse.indexOf("}", colonIndex);

                String scoreStr = aiResponse.substring(colonIndex + 1, commaIndex).trim();
                return Integer.parseInt(scoreStr.replaceAll("[^0-9]", ""));
            }
        } catch (Exception e) {
            log.warn("리스크 점수 추출 실패: {}", e.getMessage());
        }
        return 50; // 기본값
    }

    /**
     * 리스크 분석 결과에서 상태 추출
     */
    public String extractRiskStatus(String aiResponse) {
        if (aiResponse.contains("\"DANGER\"")) return "DANGER";
        if (aiResponse.contains("\"WARNING\"")) return "WARNING";
        if (aiResponse.contains("\"SAFE\"")) return "SAFE";
        return "WARNING"; // 기본값
    }

    /**
     * 리스크 분석 결과에서 사유 추출
     */
    public String extractRiskReason(String aiResponse) {
        try {
            if (aiResponse.contains("\"reason\"")) {
                int start = aiResponse.indexOf("\"reason\"");
                int colonIndex = aiResponse.indexOf(":", start);
                int quoteStart = aiResponse.indexOf("\"", colonIndex + 1);
                int quoteEnd = aiResponse.indexOf("\"", quoteStart + 1);

                if (quoteStart != -1 && quoteEnd != -1) {
                    return aiResponse.substring(quoteStart + 1, quoteEnd);
                }
            }
        } catch (Exception e) {
            log.warn("리스크 사유 추출 실패: {}", e.getMessage());
        }
        return "분석 결과를 확인해 주세요.";
    }
}
