package com.myplatform.backend.controller;

import com.myplatform.backend.service.SseEmitterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * SSE(Server-Sent Events) 엔드포인트 컨트롤러
 * - 실시간 진행률 및 로그 스트리밍
 * - 장시간 크롤링/수집 작업의 상태 모니터링
 */
@RestController
@RequestMapping("/api/sse")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "SSE", description = "Server-Sent Events 실시간 스트리밍 API")
public class SseController {

    private final SseEmitterService sseEmitterService;

    /**
     * SSE 구독 엔드포인트
     *
     * @param taskType 구독할 작업 유형
     *                 - crawl-operating-margin: 영업이익률 크롤링
     *                 - collect-finance: 분기별 재무제표 수집
     *                 - collect-all: 전 종목 재무 데이터 수집
     * @param clientId 클라이언트 ID (미지정 시 자동 생성)
     * @return SseEmitter 스트림
     */
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "SSE 구독",
               description = "작업 진행률 및 로그를 실시간으로 수신합니다.\n\n" +
                           "**지원 작업 유형:**\n" +
                           "- `crawl-operating-margin`: 영업이익률 크롤링\n" +
                           "- `collect-finance`: 분기별 재무제표 수집\n" +
                           "- `collect-all`: 전 종목 재무 데이터 수집\n" +
                           "- `fix-stock-names`: 종목명 일괄 수정\n\n" +
                           "**이벤트 타입:**\n" +
                           "- `CONNECTED`: 연결 성공\n" +
                           "- `START`: 작업 시작\n" +
                           "- `PROGRESS`: 진행률 업데이트\n" +
                           "- `LOG`: 로그 메시지\n" +
                           "- `COMPLETE`: 작업 완료\n" +
                           "- `ERROR`: 오류 발생")
    public SseEmitter subscribe(
            @Parameter(description = "작업 유형", example = "crawl-operating-margin")
            @RequestParam String taskType,
            @Parameter(description = "클라이언트 ID (자동 생성됨)")
            @RequestParam(required = false) String clientId) {

        if (clientId == null || clientId.isEmpty()) {
            clientId = UUID.randomUUID().toString();
        }

        log.info("SSE 구독 요청 - taskType: {}, clientId: {}", taskType, clientId);

        return sseEmitterService.subscribe(clientId, taskType);
    }

    /**
     * SSE 연결 상태 확인
     */
    @GetMapping("/status")
    @Operation(summary = "SSE 연결 상태", description = "현재 SSE 연결 수와 구독자 정보를 조회합니다.")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "connectedClients", sseEmitterService.getConnectedClientCount(),
                "crawlOperatingMarginSubscribers", sseEmitterService.getSubscriberCount("crawl-operating-margin"),
                "collectFinanceSubscribers", sseEmitterService.getSubscriberCount("collect-finance"),
                "collectAllSubscribers", sseEmitterService.getSubscriberCount("collect-all")
        ));
    }
}
