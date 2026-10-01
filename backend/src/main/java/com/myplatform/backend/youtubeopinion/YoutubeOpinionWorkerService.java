package com.myplatform.backend.youtubeopinion;

import com.myplatform.core.util.DateTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static com.myplatform.backend.youtubeopinion.YoutubeOpinionDtos.*;

/**
 * Claude 로컬 작업자 대기열(2026-10-01) — 서버는 Claude 를 부르지 않는다. 구독 로그인이 된 로컬 PC 의 작업자 프로세스
 * ({@code tools/youtube-claude-worker})가 관리자 API 로 작업을 <b>임대</b>해 가서 같은 프롬프트로 Claude 를 돌리고, 모델 응답 원문만
 * 돌려준다. 해석·검증·저장은 {@link YoutubeOpinionAnalysisService#completeFromWorker} — Gemini 와 같은 경로다.
 *
 * <p>새 인프라 없이 기존 실행 기록({@link YtAnalysisRun})으로 상태를 관리한다.
 * <ul>
 *   <li><b>중복 실행 방지</b> — 임대는 상태·토큰 조건부 UPDATE 한 번({@link YtAnalysisRunRepository#claim})이라 둘이 동시에 집어도
 *       하나만 얻는다. 결과·실패 보고는 임대 토큰을 든 작업자만 낼 수 있다.</li>
 *   <li><b>동시 실행 상한</b>({@code youtube-opinion.claude.max-concurrent}, 기본 1) · <b>임대 시간</b>(기본 30분, 작업자가 구간마다
 *       연장) · <b>제한된 재시도</b>(시도 기본 3회 — 시간 초과·CLI 오류·중단만, 사용량 한도·로그인 대기는 세지 않는다).</li>
 *   <li><b>중단 후 복구</b> — 작업자가 죽어 임대가 지나면 다음 임대 요청 때 대기열로 돌린다(시도 상한이면 FAILED). 서버 재시작은
 *       CLAUDE 실행을 건드리지 않는다.</li>
 *   <li><b>대기·실패를 정상으로 위장하지 않는다(§4c)</b> — 사용량 한도·로그인 만료는 WAITING(사유·다시 시도 시각), 잘못된 JSON·
 *       모델 불일치·구독이 아닌 인증 경로는 즉시 FAILED. 어느 쪽도 '의견 없음'이나 성공으로 저장하지 않고, 실패해도 직전 성공
 *       결과(current_run_id)는 그대로 보인다.</li>
 *   <li><b>다른 모델로 조용히 바꾸지 않는다</b> — 작업자가 보고한 실제 모델이 요청 모델과 맞지 않으면 FAILED.</li>
 * </ul>
 */
@Service
@Slf4j
public class YoutubeOpinionWorkerService {

    /** 작업자가 보고하는 실패 종류 — 어느 것이 대기이고 어느 것이 실패인지는 서버가 정한다. */
    public enum FailureType {
        /** 구독 사용량 한도 — 대기(재시도 시각까지), 시도 수 안 셈. */
        RATE_LIMITED,
        /** 로그인 만료·미로그인 — 대기(사람이 로그인할 때까지), 시도 수 안 셈. */
        AUTH_REQUIRED,
        /** 호출 시간 초과 — 재시도(시도 상한까지). */
        TIMEOUT,
        /** CLI 실행 오류(일시) — 재시도. */
        CLI_ERROR,
        /** 작업자 중단(Ctrl+C 등) — 재시도. */
        CANCELLED,
        /** 응답이 JSON 배열이 아님(작업자 쪽 재시도 후에도) — 즉시 실패. */
        INVALID_OUTPUT,
        /** 요청과 다른 모델로 실행됨 — 즉시 실패(자동 전환 금지). */
        MODEL_MISMATCH,
        /** API 키 등 구독이 아닌 인증 경로 — 즉시 실패(별도 과금 경로로 돌지 않는다). */
        AUTH_PATH_NOT_SUBSCRIPTION
    }

    static final String WAIT_QUOTA = "QUOTA";
    static final String WAIT_LOGIN = "LOGIN";
    static final long DEFAULT_QUOTA_WAIT_SECONDS = 3600;
    static final long MAX_QUOTA_WAIT_SECONDS = 24 * 3600;
    static final long LOGIN_WAIT_SECONDS = 10 * 60;
    private static final Pattern WORKER_ID = Pattern.compile("[^A-Za-z0-9._@-]");

    private final YoutubeOpinionSettings settings;
    private final OpinionAnalyzerSettings analyzerSettings;
    private final YtAnalysisRunRepository runRepo;
    private final YtVideoRepository videoRepo;
    private final YoutubeOpinionAnalysisService analysis;
    private final TransactionTemplate tx;
    private final Object claimLock = new Object();
    private volatile Supplier<LocalDateTime> clock = DateTimeUtil::kstNow;

    public YoutubeOpinionWorkerService(YoutubeOpinionSettings settings, OpinionAnalyzerSettings analyzerSettings,
                                       YtAnalysisRunRepository runRepo, YtVideoRepository videoRepo,
                                       YoutubeOpinionAnalysisService analysis, PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.analyzerSettings = analyzerSettings;
        this.runRepo = runRepo;
        this.videoRepo = videoRepo;
        this.analysis = analysis;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 테스트용 — 임대 만료·대기 시각을 시험하려고 시계를 고정한다. */
    void useClock(Supplier<LocalDateTime> clock) {
        this.clock = clock;
    }

    private LocalDateTime now() {
        return clock.get();
    }

    // ------------------------------------------------------------------ 임대

    /** 다음 작업을 임대한다. 없으면 빈 값 — 대기열이 비었거나 동시 실행 상한이거나 대기 시각 전. */
    public Optional<WorkerClaim> claim(String workerIdRaw) {
        if (!settings.isEnabled()) throw new FeatureDisabledException();
        String workerId = sanitizeWorkerId(workerIdRaw);
        synchronized (claimLock) {
            LocalDateTime now = now();
            expireStale(now);
            long running = runRepo.findByAnalyzerAndStatus(YtAnalysisRun.ANALYZER_CLAUDE, YtAnalysisRun.Status.RUNNING).size();
            if (running >= analyzerSettings.maxConcurrent()) return Optional.empty();
            List<YtAnalysisRun> candidates = runRepo.findByAnalyzerAndStatusInOrderByIdAsc(YtAnalysisRun.ANALYZER_CLAUDE,
                    EnumSet.of(YtAnalysisRun.Status.QUEUED, YtAnalysisRun.Status.WAITING));
            for (YtAnalysisRun c : candidates) {
                if (c.getStatus() == YtAnalysisRun.Status.WAITING
                        && c.getNextAttemptAt() != null && c.getNextAttemptAt().isAfter(now)) continue;
                String token = UUID.randomUUID().toString();
                LocalDateTime until = now.plusMinutes(analyzerSettings.leaseMinutes());
                Integer rows = tx.execute(s -> runRepo.claim(c.getId(), c.getStatus(),
                        c.getLeaseToken() == null ? "" : c.getLeaseToken(), YtAnalysisRun.Status.RUNNING, token, until, workerId));
                if (rows == null || rows != 1) continue;   // 다른 작업자가 먼저 집었다
                YtAnalysisRun run = runRepo.findById(c.getId()).orElseThrow();
                tx.executeWithoutResult(s -> videoRepo.findByVideoId(run.getVideoId()).ifPresent(v -> {
                    v.setStatus(YtVideo.Status.ANALYZING);
                    v.setLastError(null);
                    videoRepo.save(v);
                }));
                List<WorkerChunk> chunks = analysis.workerChunks(run).stream()
                        .map(ch -> new WorkerChunk(ch.index(), analysis.workerPrompt(run, ch))).toList();
                log.info("[유튜브의견] Claude 작업 임대 run={} video={} 구간 {}개 — 작업자 {} (시도 {}/{})",
                        run.getId(), run.getVideoId(), chunks.size(), workerId, run.getAttempts(), analyzerSettings.maxAttempts());
                return Optional.of(new WorkerClaim(run.getId(), run.getVideoId(), token, until, run.getRequestedModel(),
                        run.getPromptVersion(), OpinionPrompt.CLAUDE_SYSTEM, chunks, run.getAttempts(),
                        analyzerSettings.maxAttempts()));
            }
            return Optional.empty();
        }
    }

    /** 임대 연장 — 작업자가 구간을 하나 끝낼 때마다 부른다. 임대가 없으면(만료 뒤 다른 작업자가 집음) 409. */
    public LocalDateTime heartbeat(long runId, String leaseToken) {
        LocalDateTime until = now().plusMinutes(analyzerSettings.leaseMinutes());
        hold(runId, leaseToken, until);
        return until;
    }

    /** 결과 보고 — 응답 원문을 Gemini 와 같은 경로로 해석·검증·저장한다. */
    public WorkerResult complete(long runId, WorkerCompleteRequest req) {
        if (req == null) throw new IllegalArgumentException("본문이 없습니다.");
        // 처리하는 동안 다른 작업자가 집지 못하게 임대를 붙잡는다
        hold(runId, req.leaseToken(), now().plusMinutes(analyzerSettings.leaseMinutes()));
        YtAnalysisRun run = runRepo.findById(runId).orElseThrow();
        String actual = req.model() == null ? "" : req.model().trim();
        if (!modelMatches(run.getRequestedModel(), actual)) {
            failRun(runId, "요청 모델(" + run.getRequestedModel() + ")과 다른 모델(" + (actual.isEmpty() ? "미보고" : actual)
                    + ")로 실행됨 — 다른 모델로 자동 전환하지 않는다");
            return result(runId);
        }
        Map<Integer, String> replies = new HashMap<>();
        if (req.outputs() != null) {
            for (WorkerOutput o : req.outputs()) {
                if (o == null) continue;
                if (replies.putIfAbsent(o.index(), o.text()) != null) {
                    throw new IllegalArgumentException("같은 구간(" + o.index() + ") 응답이 두 번 왔습니다.");
                }
            }
        }
        tx.executeWithoutResult(s -> runRepo.findById(runId).ifPresent(r -> {
            r.setModel(OpinionValidator.cut(actual, 80));    // 실제로 응답한 모델 — 요청 별칭이 아니라
            runRepo.save(r);
        }));
        analysis.completeFromWorker(runId, replies);
        WorkerResult result = result(runId);
        log.info("[유튜브의견] Claude 작업 결과 run={} — {} (모델 {}, {}ms)", runId, result.status(), actual,
                req.durationMs() == null ? "?" : req.durationMs());
        return result;
    }

    /** 실패 보고 — 종류에 따라 대기·재시도·실패로 바꾼다. 어느 쪽도 '의견 없음'으로 저장하지 않는다. */
    public WorkerResult fail(long runId, WorkerFailRequest req) {
        if (req == null) throw new IllegalArgumentException("본문이 없습니다.");
        hold(runId, req.leaseToken(), now().plusMinutes(analyzerSettings.leaseMinutes()));
        FailureType type = parseFailure(req.errorType());
        String detail = OpinionValidator.cut(req.message() == null ? "" : req.message().trim(), 200);
        LocalDateTime now = now();
        switch (type) {
            case RATE_LIMITED -> {
                long wait = req.retryAfterSeconds() == null ? DEFAULT_QUOTA_WAIT_SECONDS
                        : Math.max(60, Math.min(MAX_QUOTA_WAIT_SECONDS, req.retryAfterSeconds()));
                waitRun(runId, WAIT_QUOTA, now.plusSeconds(wait),
                        "대기: Claude 사용량 한도 — " + now.plusSeconds(wait).toLocalTime().withNano(0) + " 이후 다시 시도");
            }
            case AUTH_REQUIRED -> waitRun(runId, WAIT_LOGIN, now.plusSeconds(LOGIN_WAIT_SECONDS),
                    "대기: Claude 로그인 필요 — 작업자 PC 에서 claude 로그인 후 자동으로 다시 시도");
            case TIMEOUT, CLI_ERROR, CANCELLED -> retryOrFail(runId, type, detail);
            default -> failRun(runId, failureMessage(type, detail));
        }
        WorkerResult result = result(runId);
        log.warn("[유튜브의견] Claude 작업 보고 run={} — {} → {}", runId, type, result.status());
        return result;
    }

    // ------------------------------------------------------------------ 상태 전이

    /** 임대 만료 정리 — 지난 RUNNING 은 시도 상한 전이면 대기열로, 넘었으면 FAILED. 너무 오래 기다린 WAITING 도 FAILED. */
    /**
     * 만료된 임대·오래된 대기를 지금 정리한다(규칙은 {@link #expireStale} 하나). 만료 처리는 원래 {@link #claim} 안에서만 돌아서,
     * 작업자가 임대 중에 사라진 뒤 다시 오지 않으면 그 실행이 RUNNING 으로 남는다 — 분석기를 GEMINI 로 되돌리면 동시 실행 1건
     * 규칙에 걸려 Gemini 분석이 계속 막혔다(2026-10-01, 서버 재시작 정리는 Claude 실행을 일부러 건드리지 않는다). 그래서 분석 요청이
     * 먼저 이걸 부른다. claim 과 같은 잠금 — 정리하는 사이 작업자가 같은 실행을 집지 않게.
     */
    public void expireStaleNow() {
        synchronized (claimLock) {
            expireStale(now());
        }
    }

    void expireStale(LocalDateTime now) {
        for (YtAnalysisRun r : runRepo.findByAnalyzerAndStatus(YtAnalysisRun.ANALYZER_CLAUDE, YtAnalysisRun.Status.RUNNING)) {
            if (r.getLeaseUntil() != null && r.getLeaseUntil().isAfter(now)) continue;
            if (r.getAttempts() >= analyzerSettings.maxAttempts()) {
                failRun(r.getId(), "작업자 응답 없이 임대 시간이 지났다(시도 " + r.getAttempts() + "/" + analyzerSettings.maxAttempts()
                        + "회) — 작업자 중단·네트워크를 확인하고 다시 분석을 요청하세요");
            } else {
                tx.executeWithoutResult(s -> runRepo.findById(r.getId()).ifPresent(run -> {
                    run.setStatus(YtAnalysisRun.Status.QUEUED);
                    run.setLeaseToken(null);
                    run.setLeaseUntil(null);
                    run.setError("작업자 응답 없이 임대 시간이 지나 다시 대기열에 올림(시도 " + run.getAttempts() + "/"
                            + analyzerSettings.maxAttempts() + ")");
                    runRepo.save(run);
                }));
            }
        }
        for (YtAnalysisRun r : runRepo.findByAnalyzerAndStatus(YtAnalysisRun.ANALYZER_CLAUDE, YtAnalysisRun.Status.WAITING)) {
            if (r.getStartedAt() != null && r.getStartedAt().plusHours(analyzerSettings.maxWaitHours()).isBefore(now)) {
                failRun(r.getId(), "대기 " + analyzerSettings.maxWaitHours() + "시간 초과("
                        + (WAIT_LOGIN.equals(r.getWaitReason()) ? "로그인" : "사용량 한도") + ") — 다시 분석을 요청하세요");
            }
        }
    }

    /** 임대 확인·붙잡기 — 상태가 RUNNING 이고 토큰이 같을 때만. 아니면 409(다른 작업자가 집었거나 이미 끝남). */
    private void hold(long runId, String leaseToken, LocalDateTime until) {
        if (leaseToken == null || leaseToken.isBlank()) throw new IllegalArgumentException("임대 토큰이 없습니다.");
        Integer rows = tx.execute(s -> {
            YtAnalysisRun run = runRepo.findById(runId).orElseThrow(() -> new NoSuchElementException("실행 기록이 없습니다: " + runId));
            if (run.getStatus() != YtAnalysisRun.Status.RUNNING || !leaseToken.equals(run.getLeaseToken())) return 0;
            run.setLeaseUntil(until);
            runRepo.save(run);
            return 1;
        });
        if (rows == null || rows != 1) {
            throw new IllegalStateException("임대가 유효하지 않습니다 — 이미 끝났거나 만료돼 다른 작업자가 가져갔습니다(run " + runId + ").");
        }
    }

    private void waitRun(long runId, String reason, LocalDateTime nextAttemptAt, String message) {
        tx.executeWithoutResult(s -> runRepo.findById(runId).ifPresent(run -> {
            run.setStatus(YtAnalysisRun.Status.WAITING);
            run.setWaitReason(reason);
            run.setNextAttemptAt(nextAttemptAt);
            run.setLeaseToken(null);
            run.setLeaseUntil(null);
            run.setAttempts(Math.max(0, run.getAttempts() - 1));   // 대기는 시도로 세지 않는다
            run.setError(message);
            runRepo.save(run);
            videoRepo.findByVideoId(run.getVideoId()).ifPresent(v -> {
                v.setStatus(YtVideo.Status.ANALYZING);               // 진행 중 — 실패도 '의견 없음'도 아니다
                v.setLastError(message);
                videoRepo.save(v);
            });
        }));
    }

    private void retryOrFail(long runId, FailureType type, String detail) {
        YtAnalysisRun run = runRepo.findById(runId).orElseThrow();
        if (run.getAttempts() >= analyzerSettings.maxAttempts()) {
            failRun(runId, failureMessage(type, detail) + " — 재시도 " + analyzerSettings.maxAttempts() + "회 소진");
            return;
        }
        tx.executeWithoutResult(s -> runRepo.findById(runId).ifPresent(r -> {
            r.setStatus(YtAnalysisRun.Status.QUEUED);
            r.setLeaseToken(null);
            r.setLeaseUntil(null);
            r.setError("재시도 대기: " + failureMessage(type, detail) + " (시도 " + r.getAttempts() + "/"
                    + analyzerSettings.maxAttempts() + ")");
            runRepo.save(r);
        }));
    }

    /** 실행 실패 — 발언 0건, 직전 성공 결과(current_run_id) 유지. */
    private void failRun(long runId, String reason) {
        String message = OpinionValidator.cut(reason, 500);
        LocalDateTime now = now();
        tx.executeWithoutResult(s -> runRepo.findById(runId).ifPresent(run -> {
            run.setStatus(YtAnalysisRun.Status.FAILED);
            run.setError(message);
            run.setFinishedAt(now);
            run.setLeaseToken(null);
            run.setLeaseUntil(null);
            run.setNextAttemptAt(null);
            runRepo.save(run);
            videoRepo.findByVideoId(run.getVideoId()).ifPresent(v -> {
                v.setStatus(YtVideo.Status.FAILED);
                v.setLastError(message);
                videoRepo.save(v);
            });
        }));
    }

    private WorkerResult result(long runId) {
        YtAnalysisRun run = runRepo.findById(runId).orElseThrow();
        return new WorkerResult(runId, run.getStatus().name(), run.getStatementCount() == null ? 0 : run.getStatementCount(),
                run.getError());
    }

    // ------------------------------------------------------------------ 순수 규칙

    /**
     * 실제 응답 모델이 요청과 맞는가. 정식 ID(claude-…)를 요청했으면 같거나 날짜 접미사(-YYYYMMDD)만 다른 것, 별칭(sonnet 등)이면
     * 그 별칭을 포함하는 모델. 보고가 없으면 맞지 않다(모르는 것을 같다고 보지 않는다). 순수 함수.
     */
    static boolean modelMatches(String requested, String actual) {
        if (actual == null || actual.isBlank()) return false;
        if (requested == null || requested.isBlank()) return true;
        String r = requested.trim().toLowerCase(Locale.ROOT);
        String a = actual.trim().toLowerCase(Locale.ROOT);
        if (r.startsWith("claude-")) return a.equals(r) || a.matches(Pattern.quote(r) + "-\\d{8}");
        return a.contains(r);
    }

    static FailureType parseFailure(String value) {
        if (value == null) return FailureType.CLI_ERROR;
        try {
            return FailureType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FailureType.CLI_ERROR;
        }
    }

    static String failureMessage(FailureType type, String detail) {
        String base = switch (type) {
            case INVALID_OUTPUT -> "Claude 응답을 JSON 배열로 읽지 못함";
            case MODEL_MISMATCH -> "요청과 다른 모델로 실행됨 — 자동 전환 금지";
            case AUTH_PATH_NOT_SUBSCRIPTION -> "구독 로그인이 아닌 인증 경로(API 키 등) — 별도 과금 경로로 실행하지 않음";
            case TIMEOUT -> "Claude 호출 시간 초과";
            case CANCELLED -> "작업자 중단";
            case CLI_ERROR -> "Claude CLI 실행 오류";
            case RATE_LIMITED -> "Claude 사용량 한도";
            case AUTH_REQUIRED -> "Claude 로그인 필요";
        };
        return detail == null || detail.isBlank() ? base : base + ": " + detail;
    }

    static String sanitizeWorkerId(String raw) {
        String s = raw == null ? "" : WORKER_ID.matcher(raw.trim()).replaceAll("");
        if (s.isEmpty()) s = "worker";
        return s.length() > 60 ? s.substring(0, 60) : s;
    }
}
