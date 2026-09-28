package com.myplatform.backend.youtubeopinion;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 유튜브 의견 API 응답·요청 모양. 화면 문구는 전부 평문으로 쓰인다(HTML 렌더링 금지). */
public final class YoutubeOpinionDtos {

    private YoutubeOpinionDtos() {}

    /** 화면 범위 표기 — 전체 유튜브 여론처럼 보이지 않게 모든 응답에 싣는다. */
    public static final String SCOPE = "분석된 영상 기준";

    // ------------------------------------------------------------------ 조회(로그인 사용자)

    public record OpinionDto(long id, String videoId, String videoTitle, String channelName,
                             LocalDateTime publishedAt, LocalDateTime analyzedAt,
                             String speakerName, String speakerRole, String stance,
                             String claimSummary, String rationale, String conditions, String horizon,
                             BigDecimal targetPrice, String evidenceQuote, int startSec, String startLabel,
                             String sourceUrl, String model, String promptVersion) {}

    public record PersonDto(long personId, String name, String role, String stance, List<String> sequence,
                            int statements, LocalDateTime lastPublishedAt) {}

    /** 집계 창 안의 영상 상태 — 미수집·분석 중·실패·분석 대기를 "의견 없음"과 구분하기 위해. */
    public record CoverageDto(int analyzedVideos, int analyzingVideos, int failedVideos,
                              int awaitingAnalysisVideos, int noTranscriptVideos) {}

    public record SummaryDto(Map<String, Integer> statementCounts, Map<String, Integer> personCounts,
                             int totalPersons, int totalStatements, int unknownSpeakerStatements,
                             LocalDateTime latestPublishedAt) {}

    /**
     * @param status NO_ANALYZED_VIDEOS(창 안에 분석된 영상 없음) · NO_OPINION(분석했지만 이 종목 의견 없음) · HAS_OPINIONS
     */
    public record StockViewDto(boolean enabled, boolean dataAvailable, String status, String scope,
                               int windowDays, LocalDateTime windowFrom, LocalDateTime windowTo,
                               CoverageDto coverage, SummaryDto summary, List<PersonDto> persons,
                               List<OpinionDto> opinions, List<OpinionDto> older) {}

    public record SummaryItemDto(String status, SummaryDto summary) {}

    public record SummaryViewDto(boolean enabled, boolean dataAvailable, String scope, int windowDays,
                                 CoverageDto coverage, Map<String, SummaryItemDto> items) {}

    // ------------------------------------------------------------------ 관리자

    public record ParticipantInput(String name, String role) {}

    public record RegisterRequest(String url, String title, String channelId, String publishedAt,
                                  String sourceNote, String duplicateOf, List<ParticipantInput> participants) {}

    public record TranscriptRequest(String format, String content) {}

    public record ReviewRequest(String decision, String stockCode) {}

    public record ParticipantDto(long personId, String name, String role) {}

    public record VideoRowDto(String videoId, String title, String channelId, String channelName,
                              LocalDateTime publishedAt, LocalDateTime createdAt, String sourceNote,
                              String duplicateOf, String status, Long currentRunId,
                              LocalDateTime lastAttemptAt, LocalDateTime lastSuccessAt, String lastError,
                              long transcriptVersions, List<ParticipantDto> participants, String videoUrl) {}

    public record RegisterResult(boolean alreadyRegistered, VideoRowDto video) {}

    public record TranscriptResult(boolean duplicate, int version, int cueCount, int durationSec,
                                   int labeledCues, String format) {}

    public record StartResult(long runId, int chunkCount) {}

    public record RunDto(long id, long transcriptId, String model, String promptVersion, String status,
                         int chunkCount, int statementCount, int droppedCount, String error, String requestedBy,
                         LocalDateTime startedAt, LocalDateTime finishedAt, boolean current) {}

    public record ReviewRowDto(long id, String videoId, String videoTitle, String speakerName,
                               String stockNameRaw, String stockCode, String mappingStatus, String stance,
                               String statementType, String claimSummary, String conditions,
                               String evidenceQuote, String reviewStatus, String reviewReasons,
                               int startSec, String sourceUrl) {}

    public record ConfigDto(boolean enabled, boolean autoCollection, List<YoutubeOpinionSettings.Channel> channels,
                            boolean geminiConfigured, int windowDays, int maxChunksPerRun, int dailyCallLimit,
                            long dailyCallsUsed, String quietWindow, int maxTranscriptKb, String model,
                            String promptVersion) {}

    /** 처리량 제한(자막 등록·분석 요청) 초과 — 429. */
    public static class TooManyRequestsException extends RuntimeException {
        public TooManyRequestsException(String message) { super(message); }
    }

    /** 기능이 꺼져 있을 때 관리자 API 가 503 으로 알린다. */
    public static class FeatureDisabledException extends RuntimeException {
        public FeatureDisabledException() {
            super("유튜브 의견 기능이 꺼져 있습니다(YOUTUBE_OPINION_ENABLED=true 로 켠 뒤 backend 재생성).");
        }
    }
}
