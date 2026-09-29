package com.myplatform.backend.youtubeopinion;

import com.myplatform.backend.service.GeminiService;
import com.myplatform.backend.service.StockMasterService;
import com.myplatform.core.util.DateTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.myplatform.backend.youtubeopinion.YoutubeOpinionDtos.*;

/**
 * 유튜브 의견 분석 실행 — 관리자가 요청할 때만 돈다(스케줄 없음, 화면 조회로 트리거되지 않음).
 *
 * <ul>
 *   <li><b>Gemini 는 기존 경로 그대로</b>({@link GeminiService#generateStructuredJson}) — 전역 4.5초 제한기·429 재시도·타임아웃·
 *       일일 집계를 공유한다. 재료 분석을 막지 않도록 동시 1건·일일 호출 상한·재료 워밍 보호 시간대를 둔다.</li>
 *   <li><b>원자적</b> — 청크 하나라도 실패하면 발언을 하나도 저장하지 않고 실행 FAILED + 원인. 이전 성공 결과(current_run_id)는
 *       그대로 보인다. 실패를 "의견 없음"이나 성공으로 캐시하지 않는다(§4c).</li>
 *   <li>Gemini 호출은 DB 트랜잭션 밖에서, 저장은 짧은 트랜잭션 하나로.</li>
 *   <li>재분석 시 이전 검토 결정(승인·거절·수동 종목)은 <b>사람이 본 내용이 그대로일 때만</b> 잇는다 — 발언 식별(키)과
 *       결정 승계(내용 지문)를 나눈 규칙은 {@link ReviewCarryOver} 한 곳에 있다.</li>
 * </ul>
 */
@Service
@Slf4j
public class YoutubeOpinionAnalysisService {

    static final int MAX_ANALYSIS_REQUESTS_PER_HOUR = 10;

    private final YoutubeOpinionSettings settings;
    private final YtVideoRepository videoRepo;
    private final YtTranscriptRepository transcriptRepo;
    private final YtVideoParticipantRepository participantRepo;
    private final YtPersonRepository personRepo;
    private final YtAnalysisRunRepository runRepo;
    private final YtOpinionRepository opinionRepo;
    private final ObjectProvider<GeminiService> geminiProvider;
    private final StockMasterService stockMasterService;
    private final TransactionTemplate tx;
    private final HourlyThroughputLimit requestLimit;
    private final Object startLock = new Object();
    private volatile Executor executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "yt-opinion-analysis");
        t.setDaemon(true);
        return t;
    });

    public YoutubeOpinionAnalysisService(YoutubeOpinionSettings settings, YtVideoRepository videoRepo,
                                         YtTranscriptRepository transcriptRepo, YtVideoParticipantRepository participantRepo,
                                         YtPersonRepository personRepo, YtAnalysisRunRepository runRepo,
                                         YtOpinionRepository opinionRepo, ObjectProvider<GeminiService> geminiProvider,
                                         StockMasterService stockMasterService, PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.videoRepo = videoRepo;
        this.transcriptRepo = transcriptRepo;
        this.participantRepo = participantRepo;
        this.personRepo = personRepo;
        this.runRepo = runRepo;
        this.opinionRepo = opinionRepo;
        this.geminiProvider = geminiProvider;
        this.stockMasterService = stockMasterService;
        this.tx = new TransactionTemplate(transactionManager);
        this.requestLimit = new HourlyThroughputLimit(MAX_ANALYSIS_REQUESTS_PER_HOUR, Clock.systemUTC());
    }

    /** 테스트용 — 동기 실행({@code Runnable::run}) 등. */
    void useExecutor(Executor executor) {
        this.executor = executor;
    }

    /** 부팅 시 RUNNING 으로 남은 실행 정리 — 재시작으로 끊긴 분석을 '분석 중'으로 영원히 두지 않는다. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        try {
            LocalDateTime now = DateTimeUtil.kstNow();
            tx.executeWithoutResult(s -> {
                for (YtAnalysisRun r : runRepo.findByStatus(YtAnalysisRun.Status.RUNNING)) {
                    r.setStatus(YtAnalysisRun.Status.FAILED);
                    r.setError("서버 재시작으로 중단된 분석");
                    r.setFinishedAt(now);
                    runRepo.save(r);
                }
                for (YtVideo v : videoRepo.findByStatus(YtVideo.Status.ANALYZING)) {
                    v.setStatus(YtVideo.Status.FAILED);
                    v.setLastError("서버 재시작으로 분석이 중단됐습니다 — 다시 분석하세요.");
                    videoRepo.save(v);
                }
            });
        } catch (RuntimeException e) {
            log.warn("[유튜브의견] 부팅 시 중단 실행 정리 실패(부팅은 계속): {}", e.getMessage());
        }
    }

    public StartResult start(String videoId, String admin) {
        if (!settings.isEnabled()) throw new FeatureDisabledException();
        if (!YoutubeUrlParser.isValidVideoId(videoId)) throw new IllegalArgumentException("영상 ID 형식이 아닙니다.");
        GeminiService gemini = geminiProvider.getIfAvailable();
        if (gemini == null || !gemini.isAvailable()) {
            throw new IllegalStateException("Gemini API 키가 설정되지 않아 분석할 수 없습니다.");
        }
        LocalDateTime now = DateTimeUtil.kstNow();
        if (settings.inQuietWindow(now.toLocalTime())) {
            throw new IllegalStateException("재료 분석 보호 시간대(" + settings.quietWindowLabel() + ")에는 분석을 시작하지 않습니다.");
        }
        synchronized (startLock) {
            if (runRepo.existsByStatus(YtAnalysisRun.Status.RUNNING)) {
                throw new IllegalStateException("다른 영상 분석이 진행 중입니다 — 끝난 뒤 다시 요청하세요.");
            }
            YtVideo video = videoRepo.findByVideoId(videoId)
                    .orElseThrow(() -> new NoSuchElementException("등록되지 않은 영상입니다: " + videoId));
            YtTranscript transcript = transcriptRepo.findTopByVideoIdOrderByVersionDesc(videoId)
                    .orElseThrow(() -> new IllegalStateException("자막이 없습니다 — 먼저 자막을 등록하세요."));
            List<OpinionPrompt.Chunk> chunks = OpinionPrompt.chunk(TranscriptParser.fromJson(transcript.getCuesJson()));
            if (chunks.size() > settings.getMaxChunksPerRun()) {
                throw new IllegalArgumentException("자막이 분석 상한(" + settings.getMaxChunksPerRun() + "구간)을 넘습니다 — 필요한 부분만 등록하세요.");
            }
            long used = runRepo.sumChunksSince(now.toLocalDate().atStartOfDay());
            if (used + chunks.size() > settings.getDailyCallLimit()) {
                throw new IllegalStateException("오늘 분석 호출 상한(" + settings.getDailyCallLimit() + ")을 넘습니다 — 오늘 "
                        + used + "회 사용, 이번 요청 " + chunks.size() + "회.");
            }
            if (!requestLimit.tryAcquire()) {
                throw new TooManyRequestsException("분석 요청은 한 시간에 " + requestLimit.maxPerHour() + "건까지입니다.");
            }
            YtAnalysisRun run = tx.execute(s -> {
                YtAnalysisRun r = runRepo.save(YtAnalysisRun.builder()
                        .videoId(videoId).transcriptId(transcript.getId()).model(settings.getModel())
                        .promptVersion(OpinionPrompt.VERSION).status(YtAnalysisRun.Status.RUNNING)
                        .chunkCount(chunks.size()).statementCount(0).droppedCount(0)
                        .requestedBy(admin).startedAt(now).build());
                video.setStatus(YtVideo.Status.ANALYZING);
                video.setLastAttemptAt(now);
                videoRepo.save(video);
                return r;
            });
            long runId = run.getId();
            log.info("[유튜브의견] 분석 시작 run={} video={} 구간 {}개 — by {}", runId, videoId, chunks.size(), admin);
            executor.execute(() -> execute(runId));
            return new StartResult(runId, chunks.size());
        }
    }

    /** 실행 본체 — Gemini 는 트랜잭션 밖. 실패 사유는 사람이 읽을 수 있게 남긴다. */
    void execute(long runId) {
        List<OpinionValidator.Validated> accepted = new ArrayList<>();
        List<TranscriptParser.Cue> cues = null;
        int dropped = 0;
        String error = null;
        try {
            YtAnalysisRun run = runRepo.findById(runId).orElseThrow(() -> new IllegalStateException("실행 기록이 없습니다"));
            YtTranscript transcript = transcriptRepo.findById(run.getTranscriptId())
                    .orElseThrow(() -> new IllegalStateException("자막 기록이 없습니다"));
            cues = TranscriptParser.fromJson(transcript.getCuesJson());
            List<OpinionValidator.ParticipantRef> participants = participants(run.getVideoId());
            List<OpinionPrompt.ParticipantLine> lines = participants.stream()
                    .map(p -> new OpinionPrompt.ParticipantLine(p.displayName(), p.role())).toList();
            List<OpinionPrompt.Chunk> chunks = OpinionPrompt.chunk(cues);
            GeminiService gemini = geminiProvider.getIfAvailable();
            if (gemini == null) throw new AnalysisFailure("Gemini 서비스를 쓸 수 없습니다");
            StockMentionResolver.Lookup lookup = stockLookup();
            Map<String, Object> schema = OpinionPrompt.responseSchema();
            Set<String> keys = new HashSet<>();
            for (OpinionPrompt.Chunk chunk : chunks) {
                String label = "구간 " + (chunk.index() + 1) + "/" + chunks.size();
                String reply = gemini.generateStructuredJson(OpinionPrompt.build(chunk, lines), schema);
                if (reply == null) throw new AnalysisFailure(label + ": Gemini 응답 없음(키·쿼터·일시 오류)");
                OpinionResponseParser.Parsed parsed = OpinionResponseParser.parse(reply)
                        .orElseThrow(() -> new AnalysisFailure(label + ": 응답을 JSON 배열로 읽지 못함"));
                dropped += parsed.nonObjectItems();
                OpinionValidator.Result result = OpinionValidator.validate(parsed.statements(),
                        new OpinionValidator.Context(run.getVideoId(), cues, chunk.firstCue(), chunk.lastCue(), participants, lookup));
                dropped += result.dropped();
                for (OpinionValidator.Validated v : result.accepted()) {
                    if (keys.add(v.statementKey())) accepted.add(v); else dropped++;
                }
            }
        } catch (AnalysisFailure e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            error = "분석 중 오류: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " — " + e.getMessage());
            log.warn("[유튜브의견] 분석 run={} 오류", runId, e);
        }
        finish(runId, accepted, dropped, error, cues);
    }

    private void finish(long runId, List<OpinionValidator.Validated> accepted, int dropped, String error,
                        List<TranscriptParser.Cue> cues) {
        LocalDateTime now = DateTimeUtil.kstNow();
        try {
            tx.executeWithoutResult(s -> {
                YtAnalysisRun run = runRepo.findById(runId).orElseThrow();
                YtVideo video = videoRepo.findByVideoId(run.getVideoId()).orElseThrow();
                run.setFinishedAt(now);
                run.setDroppedCount(dropped);
                if (error != null) {
                    run.setStatus(YtAnalysisRun.Status.FAILED);
                    run.setError(OpinionValidator.cut(error, 500));
                    video.setStatus(YtVideo.Status.FAILED);
                    video.setLastError(OpinionValidator.cut(error, 500));
                } else {
                    List<YtOpinion> previousRows = video.getCurrentRunId() == null ? List.of()
                            : opinionRepo.findByRunId(video.getCurrentRunId());
                    Map<String, YtOpinion> previous = previousRows.stream()
                            .collect(Collectors.toMap(YtOpinion::getStatementKey, Function.identity(), (a, b) -> a));
                    List<TranscriptParser.Cue> previousCues = previousCues(video.getCurrentRunId(), run.getTranscriptId(), cues);
                    List<YtOpinion> orphanedDecided = ReviewCarryOver.orphanedDecided(previousRows,
                            accepted.stream().map(OpinionValidator.Validated::statementKey).collect(Collectors.toSet()));
                    for (OpinionValidator.Validated v : accepted) {
                        YtOpinion same = previous.get(v.statementKey());
                        ReviewCarryOver.Outcome outcome = ReviewCarryOver.decide(v,
                                ReviewCarryOver.spanText(cues, v.startSec(), v.endSec()), same,
                                same == null ? null : ReviewCarryOver.spanText(previousCues, same.getStartSec(), same.getEndSec()),
                                orphanedDecided);
                        opinionRepo.save(toEntity(v, run, now, outcome));
                    }
                    run.setStatus(YtAnalysisRun.Status.SUCCEEDED);
                    run.setStatementCount(accepted.size());
                    video.setCurrentRunId(run.getId());
                    video.setStatus(YtVideo.Status.ANALYZED);
                    video.setLastSuccessAt(now);
                    video.setLastError(null);
                }
                runRepo.save(run);
                videoRepo.save(video);
            });
            log.info("[유튜브의견] 분석 종료 run={} — {}", runId, error == null
                    ? "성공, 발언 " + accepted.size() + "건(형식 불량 " + dropped + "건 제외)" : "실패: " + error);
        } catch (RuntimeException e) {
            log.error("[유튜브의견] 분석 결과 저장 실패 run={}", runId, e);
            markFailedQuietly(runId, "결과 저장 실패: " + e.getClass().getSimpleName());
        }
    }

    private void markFailedQuietly(long runId, String reason) {
        try {
            tx.executeWithoutResult(s -> runRepo.findById(runId).ifPresent(run -> {
                run.setStatus(YtAnalysisRun.Status.FAILED);
                run.setError(reason);
                run.setFinishedAt(DateTimeUtil.kstNow());
                runRepo.save(run);
                videoRepo.findByVideoId(run.getVideoId()).ifPresent(v -> {
                    v.setStatus(YtVideo.Status.FAILED);
                    v.setLastError(reason);
                    videoRepo.save(v);
                });
            }));
        } catch (RuntimeException ignored) {
            // 여기까지 실패하면 부팅 시 recoverInterrupted 가 RUNNING 을 정리한다
        }
    }

    /** 검증된 발언 + 승계 판정({@link ReviewCarryOver}) → 새 실행의 새 행. 지난 실행의 행은 건드리지 않는다(이력). */
    static YtOpinion toEntity(OpinionValidator.Validated v, YtAnalysisRun run, LocalDateTime now, ReviewCarryOver.Outcome outcome) {
        return YtOpinion.builder()
                .runId(run.getId()).videoId(run.getVideoId()).statementKey(v.statementKey())
                .speakerLabel(v.speakerLabel()).personId(v.personId()).speakerRole(v.speakerRole())
                .startSec(v.startSec()).endSec(v.endSec()).stockNameRaw(v.stockNameRaw()).stockCode(outcome.stockCode())
                .mappingStatus(outcome.mappingStatus()).stance(v.stance()).statementType(v.statementType())
                .claimSummary(v.claimSummary()).rationale(v.rationale()).conditions(v.conditions())
                .horizon(v.horizon()).targetPrice(v.targetPrice()).evidenceQuote(v.evidenceQuote())
                .reviewStatus(outcome.reviewStatus()).reviewReasons(outcome.reviewReasons())
                .reviewedBy(outcome.reviewedBy()).reviewedAt(outcome.reviewedAt()).analyzedAt(now).build();
    }

    /**
     * 직전 현재 실행이 쓴 자막의 큐 — 같은 자막이면 이번 큐를 그대로 쓴다. 읽지 못하면 null 이고, 그러면 이전 결정을
     * 잇지 않는다({@link ReviewCarryOver} — 모르는 것을 "같다"로 보지 않는다).
     */
    private List<TranscriptParser.Cue> previousCues(Long previousRunId, Long transcriptId, List<TranscriptParser.Cue> cues) {
        if (previousRunId == null) return null;
        try {
            Long previousTranscriptId = runRepo.findById(previousRunId).map(YtAnalysisRun::getTranscriptId).orElse(null);
            if (previousTranscriptId == null) return null;
            if (previousTranscriptId.equals(transcriptId)) return cues;
            return transcriptRepo.findById(previousTranscriptId).map(t -> TranscriptParser.fromJson(t.getCuesJson())).orElse(null);
        } catch (RuntimeException e) {
            log.warn("[유튜브의견] 직전 실행 자막을 읽지 못해 이전 검토 결정을 잇지 않는다 run={}: {}", previousRunId, e.getMessage());
            return null;
        }
    }

    private List<OpinionValidator.ParticipantRef> participants(String videoId) {
        List<YtVideoParticipant> rows = participantRepo.findByVideoId(videoId);
        Map<Long, YtPerson> persons = personRepo.findAllById(rows.stream().map(YtVideoParticipant::getPersonId).toList())
                .stream().collect(Collectors.toMap(YtPerson::getId, Function.identity()));
        List<OpinionValidator.ParticipantRef> out = new ArrayList<>();
        for (YtVideoParticipant p : rows) {
            YtPerson person = persons.get(p.getPersonId());
            if (person != null) out.add(new OpinionValidator.ParticipantRef(person.getId(), person.getDisplayName(), p.getRole()));
        }
        return out;
    }

    StockMentionResolver.Lookup stockLookup() {
        return new StockMentionResolver.Lookup() {
            @Override
            public Optional<StockMentionResolver.StockRef> byCode(String code) {
                return stockMasterService.findByCode(code)
                        .filter(m -> !Boolean.FALSE.equals(m.getIsActive()))
                        .map(m -> new StockMentionResolver.StockRef(m.getStockCode(), m.getStockName()));
            }

            @Override
            public List<StockMentionResolver.StockRef> byName(String name) {
                return stockMasterService.search(name, 30).stream()
                        .map(m -> new StockMentionResolver.StockRef(m.getStockCode(), m.getStockName())).toList();
            }
        };
    }

    /** 사람이 읽을 실패 사유 — 스택 없이 그대로 last_error 에 남긴다. */
    static final class AnalysisFailure extends RuntimeException {
        AnalysisFailure(String message) { super(message); }
    }
}
