package com.myplatform.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            e.printStackTrace();
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
}
