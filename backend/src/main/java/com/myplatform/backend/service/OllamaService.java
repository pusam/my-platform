package com.myplatform.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
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

    public OllamaService() {
        this.restTemplate = new RestTemplate();
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
     * 주식 리스크 분석 (공시 + 뉴스 기반)
     *
     * @param stockName 종목명
     * @param disclosures 공시 정보 (텍스트)
     * @param news 뉴스 정보 (텍스트)
     * @return AI 분석 결과 (JSON 형식)
     */
    public String analyzeRisk(String stockName, String disclosures, String news) {
        String systemPrompt = """
            당신은 전문 주식 리스크 분석가입니다.
            제공된 공시 정보와 뉴스를 분석하여 해당 종목의 투자 위험도를 평가해 주세요.

            [분석 기준]
            1. 치명적 악재 (점수 80-100, DANGER):
               - 횡령, 배임, 분식회계 등 범죄 혐의
               - 상장폐지 가능성
               - 거래정지
               - 대규모 유상증자 (자본 희석)
               - 무상감자 (손실 보전)
               - 검찰 수사, 기소

            2. 주의 필요 (점수 31-79, WARNING):
               - 실적 악화
               - 최대주주 변경
               - 대규모 손실 발생
               - 부정적 뉴스 다수

            3. 안전 (점수 0-30, SAFE):
               - 특별한 악재 없음
               - 정상적인 경영 활동

            [출력 형식]
            반드시 다음 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요:
            {
              "riskScore": 숫자(0-100),
              "status": "SAFE" 또는 "WARNING" 또는 "DANGER",
              "reason": "주요 위험 요인 1-2문장",
              "analysis": "상세 분석 내용 3-5문장"
            }
            """;

        String userMessage = String.format("""
            [분석 대상 종목]: %s

            === 최근 공시 정보 ===
            %s

            === 최근 뉴스 ===
            %s

            위 정보를 바탕으로 이 종목의 투자 리스크를 분석해 주세요.
            """, stockName, disclosures, news);

        return chat(userMessage, systemPrompt);
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
