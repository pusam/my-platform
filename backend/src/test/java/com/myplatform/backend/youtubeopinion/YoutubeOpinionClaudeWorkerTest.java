package com.myplatform.backend.youtubeopinion;

import com.myplatform.backend.entity.StockMaster;
import com.myplatform.backend.service.GeminiService;
import com.myplatform.backend.service.StockMasterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.*;

import static com.myplatform.backend.youtubeopinion.YoutubeOpinionDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Claude 로컬 작업자 경로(2026-10-01) — 서버는 Claude 를 부르지 않고 대기열에 올리며, 작업자가 돌려준 응답 원문을 Gemini 와
 * <b>같은</b> 해석·검증·저장 경로로 처리한다. 대기·실패를 '의견 없음'이나 성공으로 저장하지 않고, 실패하면 직전 성공 결과를 유지한다.
 *
 * <p>인메모리 H2 + 실제 리포지토리(유튜브 의견 흐름 테스트와 같은 구성). 작업자 시계는 고정해 임대 만료·대기 시각을 시험한다.
 *
 * <p>⚠ {@code @ExtendWith(SpringExtension.class)} 를 <b>직접</b> 단 것은 중복이 아니다 — 테스트가 전부 {@code @Nested} 라 바깥
 * 클래스에 {@code @Test} 가 없으면 앱 스캔의 테스트 제외 필터가 이 클래스를 테스트로 못 알아보고, 안쪽 {@code JpaOnly}
 * ({@code @Configuration})를 {@code ApplicationContextSmokeTest} 가 집어 들어 JPA 저장소를 이 패키지로 좁혀 앱 부팅이 깨진다
 * (2026-10-01 로컬 전체 실행에서 실발생). 필터는 직접 붙은 {@code @ExtendWith} 만 본다({@code @SpringBootTest} 안의 것은 못 본다).
 * {@code @TestConfiguration} 으로 바꾸면 이번엔 {@code @SpringBootTest} 가 앱 전체 설정을 같이 올려 이 테스트가 깨진다.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = YoutubeOpinionClaudeWorkerTest.JpaOnly.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ytclaudeworker;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
class YoutubeOpinionClaudeWorkerTest {

    @Configuration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class})
    @EntityScan(basePackageClasses = YtVideo.class)
    @EnableJpaRepositories(basePackageClasses = YtVideoRepository.class)
    static class JpaOnly {}

    static final String VIDEO = YoutubeOpinionFlowTest.VIDEO;
    static final String VIDEO_2 = YoutubeOpinionFlowTest.VIDEO_2;
    static final String ADMIN = YoutubeOpinionFlowTest.ADMIN;
    static final String MODEL = "claude-sonnet-5";

    @Autowired YtVideoRepository videoRepo;
    @Autowired YtPersonRepository personRepo;
    @Autowired YtVideoParticipantRepository participantRepo;
    @Autowired YtTranscriptRepository transcriptRepo;
    @Autowired YtAnalysisRunRepository runRepo;
    @Autowired YtOpinionRepository opinionRepo;
    @Autowired PlatformTransactionManager transactionManager;

    GeminiService gemini;
    final List<String> geminiPrompts = new ArrayList<>();
    final List<String> geminiReplies = new ArrayList<>();
    YoutubeOpinionAdminService admin;
    YoutubeOpinionAnalysisService claudeAnalysis;
    YoutubeOpinionAnalysisService geminiAnalysis;
    YoutubeOpinionWorkerService worker;
    final LocalDateTime[] now = {LocalDateTime.of(2026, 10, 2, 10, 0)};

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        opinionRepo.deleteAll();
        runRepo.deleteAll();
        transcriptRepo.deleteAll();
        participantRepo.deleteAll();
        videoRepo.deleteAll();
        personRepo.deleteAll();
        geminiPrompts.clear();
        geminiReplies.clear();
        now[0] = LocalDateTime.of(2026, 10, 2, 10, 0);

        gemini = mock(GeminiService.class);
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.generateStructuredJson(anyString(), anyMap())).thenAnswer(inv -> {
            geminiPrompts.add(inv.getArgument(0));
            int i = geminiPrompts.size() - 1;
            return i < geminiReplies.size() ? geminiReplies.get(i) : "[]";
        });
        StockMasterService master = mock(StockMasterService.class);
        when(master.findByCode(anyString())).thenAnswer(inv -> Optional.ofNullable(
                YoutubeOpinionFlowTest.MASTER.get((String) inv.getArgument(0))).map(n -> stock(inv.getArgument(0), n)));
        when(master.search(anyString(), anyInt())).thenAnswer(inv -> YoutubeOpinionFlowTest.MASTER.entrySet().stream()
                .filter(e -> e.getValue().contains((String) inv.getArgument(0)))
                .map(e -> stock(e.getKey(), e.getValue())).toList());
        ObjectProvider<GeminiService> geminiProvider = mock(ObjectProvider.class);
        when(geminiProvider.getIfAvailable()).thenReturn(gemini);

        YoutubeOpinionSettings s = YoutubeOpinionFlowTest.settings(true, 20, 60, "");
        OpinionAnalyzerSettings claude = new OpinionAnalyzerSettings("CLAUDE", "sonnet", 30, 3, 1, 72);
        admin = new YoutubeOpinionAdminService(s, videoRepo, personRepo, participantRepo, transcriptRepo, runRepo,
                opinionRepo, master, geminiProvider);
        claudeAnalysis = new YoutubeOpinionAnalysisService(s, videoRepo, transcriptRepo, participantRepo, personRepo, runRepo,
                opinionRepo, geminiProvider, master, transactionManager, claude);
        geminiAnalysis = new YoutubeOpinionAnalysisService(s, videoRepo, transcriptRepo, participantRepo, personRepo, runRepo,
                opinionRepo, geminiProvider, master, transactionManager, OpinionAnalyzerSettings.gemini());
        geminiAnalysis.useExecutor(Runnable::run);
        worker = new YoutubeOpinionWorkerService(s, claude, runRepo, videoRepo, claudeAnalysis, transactionManager);
        worker.useClock(() -> now[0]);
        // 운영에선 스프링이 넣는 연결 — 분석 요청이 만료된 Claude 임대부터 정리한다
        ObjectProvider<YoutubeOpinionWorkerService> workerProvider = mock(ObjectProvider.class);
        when(workerProvider.getIfAvailable()).thenReturn(worker);
        claudeAnalysis.setWorkerServiceProvider(workerProvider);
        geminiAnalysis.setWorkerServiceProvider(workerProvider);
    }

    static StockMaster stock(String code, String name) {
        return StockMaster.builder().stockCode(code).stockName(name).market("KOSPI").isActive(true).build();
    }

    void registerWithTranscript(String videoId) {
        List<ParticipantInput> ps = List.of(new ParticipantInput("테스트운영자", "HOST"), new ParticipantInput("테스트출연자", "GUEST"));
        admin.register(new RegisterRequest("https://www.youtube.com/watch?v=" + videoId, "테스트 영상 " + videoId,
                YoutubeOpinionFlowTest.CHANNEL, YoutubeOpinionFlowTest.hoursAgo(5), "테스트 전용 자막 파일", null, ps), ADMIN);
        admin.uploadTranscript(videoId, new TranscriptRequest("SRT", YoutubeOpinionFlowTest.SRT), ADMIN);
    }

    YtAnalysisRun run(long id) {
        return runRepo.findById(id).orElseThrow();
    }

    YtVideo video(String id) {
        return videoRepo.findByVideoId(id).orElseThrow();
    }

    WorkerClaim claimOrFail() {
        return worker.claim("test-pc").orElseThrow(() -> new AssertionError("임대할 작업이 없다"));
    }

    WorkerResult completeWith(WorkerClaim c, String reply) {
        return worker.complete(c.runId(), new WorkerCompleteRequest(c.leaseToken(), MODEL,
                List.of(new WorkerOutput(0, reply)), 1234L));
    }

    /** 비교용 — 화면에 나가는 발언의 의미 필드만(행 id·실행 id 제외). */
    static List<String> fingerprint(List<YtOpinion> rows) {
        return rows.stream().map(o -> String.join("|", String.valueOf(o.getStockCode()), String.valueOf(o.getStance()),
                        String.valueOf(o.getStatementType()), String.valueOf(o.getSpeakerLabel()), String.valueOf(o.getStartSec()),
                        String.valueOf(o.getConditions()), String.valueOf(o.getReviewStatus()), String.valueOf(o.getMappingStatus())))
                .sorted().toList();
    }

    @Nested
    @DisplayName("대기열 등록 — 서버는 Claude 를 부르지 않는다")
    class Enqueue {

        @Test
        @DisplayName("CLAUDE 분석기면 실행이 QUEUED 로만 올라가고 어떤 모델도 호출되지 않는다 — 분석기·요청 모델·프롬프트 버전 기록")
        void queuesWithoutCallingAnyModel() {
            registerWithTranscript(VIDEO);

            StartResult r = claudeAnalysis.start(VIDEO, ADMIN);

            YtAnalysisRun run = run(r.runId());
            assertThat(run.getStatus()).isEqualTo(YtAnalysisRun.Status.QUEUED);
            assertThat(run.getAnalyzer()).isEqualTo(YtAnalysisRun.ANALYZER_CLAUDE);
            assertThat(run.getRequestedModel()).isEqualTo("sonnet");
            assertThat(run.getPromptVersion()).isEqualTo(OpinionPrompt.VERSION + "/" + OpinionPrompt.CLAUDE_SYSTEM_VERSION);
            assertThat(video(VIDEO).getStatus()).isEqualTo(YtVideo.Status.ANALYZING);
            verifyNoInteractions(gemini);
        }

        @Test
        @DisplayName("같은 영상이 대기·진행 중이면 다시 올리지 않는다(중복 실행 방지)")
        void duplicateStartRejected() {
            registerWithTranscript(VIDEO);
            claudeAnalysis.start(VIDEO, ADMIN);

            assertThatThrownBy(() -> claudeAnalysis.start(VIDEO, ADMIN))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("이미 분석 대기·진행 중");
        }
    }

    @Nested
    @DisplayName("Claude/Gemini 동일 응답 계약")
    class Contract {

        @Test
        @DisplayName("작업자가 받는 프롬프트는 Gemini 가 받는 것과 같다 — 시스템 프롬프트만 따로")
        void samePromptAsGemini() {
            registerWithTranscript(VIDEO);
            registerWithTranscript(VIDEO_2);
            geminiReplies.add(YoutubeOpinionFlowTest.REPLY);
            geminiAnalysis.start(VIDEO_2, ADMIN);
            claudeAnalysis.start(VIDEO, ADMIN);

            WorkerClaim c = claimOrFail();

            assertThat(c.chunks()).hasSize(1);
            assertThat(c.chunks().get(0).prompt()).isEqualTo(geminiPrompts.get(0));
            assertThat(c.systemPrompt()).isEqualTo(OpinionPrompt.CLAUDE_SYSTEM);
            assertThat(c.model()).isEqualTo("sonnet");
            assertThat(c.attempt()).isEqualTo(1);
        }

        @Test
        @DisplayName("같은 응답이면 같은 발언이 저장된다 — 조건부 유지·근거 대조·종목 확인이 분석기와 무관")
        void sameOutcomeAsGeminiForSameReply() {
            registerWithTranscript(VIDEO);
            registerWithTranscript(VIDEO_2);
            geminiReplies.add(YoutubeOpinionFlowTest.REPLY);
            StartResult g = geminiAnalysis.start(VIDEO_2, ADMIN);
            StartResult r = claudeAnalysis.start(VIDEO, ADMIN);

            WorkerResult result = completeWith(claimOrFail(), YoutubeOpinionFlowTest.REPLY);

            assertThat(result.status()).isEqualTo("SUCCEEDED");
            YtAnalysisRun claudeRun = run(r.runId());
            assertThat(claudeRun.getModel()).as("실제로 응답한 모델을 남긴다").isEqualTo(MODEL);
            assertThat(claudeRun.getFinishedAt()).isNotNull();
            assertThat(claudeRun.getLeaseToken()).isNull();
            assertThat(video(VIDEO).getCurrentRunId()).isEqualTo(r.runId());
            assertThat(fingerprint(opinionRepo.findByRunId(r.runId())))
                    .isEqualTo(fingerprint(opinionRepo.findByRunId(g.runId())))
                    .anyMatch(f -> f.contains("CONDITIONAL"));
        }
    }

    @Nested
    @DisplayName("임대 — 중복 실행 방지·동시 실행 상한·임대 확인")
    class Lease {

        @Test
        @DisplayName("한 번 임대되면 동시 실행 상한(1) 때문에 다음 임대는 비어 있다 — 끝나면 다음 작업")
        void claimIsExclusive() {
            registerWithTranscript(VIDEO);
            registerWithTranscript(VIDEO_2);
            claudeAnalysis.start(VIDEO, ADMIN);
            claudeAnalysis.start(VIDEO_2, ADMIN);

            WorkerClaim first = claimOrFail();
            assertThat(worker.claim("other-pc")).isEmpty();

            completeWith(first, YoutubeOpinionFlowTest.REPLY);
            WorkerClaim second = claimOrFail();
            assertThat(second.videoId()).isEqualTo(VIDEO_2);
        }

        @Test
        @DisplayName("다른 임대 토큰으로는 결과를 낼 수 없다 — 상태는 그대로")
        void wrongLeaseRejected() {
            registerWithTranscript(VIDEO);
            claudeAnalysis.start(VIDEO, ADMIN);
            WorkerClaim c = claimOrFail();

            assertThatThrownBy(() -> worker.complete(c.runId(), new WorkerCompleteRequest("not-the-lease", MODEL,
                    List.of(new WorkerOutput(0, YoutubeOpinionFlowTest.REPLY)), 1L)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(run(c.runId()).getStatus()).isEqualTo(YtAnalysisRun.Status.RUNNING);
        }

        @Test
        @DisplayName("작업자가 죽어 임대가 지나면 다시 집힌다(시도 2) — 옛 임대로는 결과를 못 낸다")
        void leaseExpiryRecovers() {
            registerWithTranscript(VIDEO);
            claudeAnalysis.start(VIDEO, ADMIN);
            WorkerClaim old = claimOrFail();

            now[0] = now[0].plusMinutes(31);
            WorkerClaim again = claimOrFail();

            assertThat(again.runId()).isEqualTo(old.runId());
            assertThat(again.leaseToken()).isNotEqualTo(old.leaseToken());
            assertThat(again.attempt()).isEqualTo(2);
            assertThatThrownBy(() -> completeWith(old, YoutubeOpinionFlowTest.REPLY)).isInstanceOf(IllegalStateException.class);
            assertThat(completeWith(again, YoutubeOpinionFlowTest.REPLY).status()).isEqualTo("SUCCEEDED");
        }

        @Test
        @DisplayName("임대가 시도 상한(3)까지 계속 지나면 FAILED — 대기열에 영원히 두지 않는다")
        void leaseExpiryAtMaxAttemptsFails() {
            registerWithTranscript(VIDEO);
            StartResult r = claudeAnalysis.start(VIDEO, ADMIN);
            for (int i = 0; i < 3; i++) {
                claimOrFail();
                now[0] = now[0].plusMinutes(31);
            }

            assertThat(worker.claim("test-pc")).isEmpty();
            assertThat(run(r.runId()).getStatus()).isEqualTo(YtAnalysisRun.Status.FAILED);
            assertThat(run(r.runId()).getError()).contains("임대 시간이 지났다");
            assertThat(video(VIDEO).getStatus()).isEqualTo(YtVideo.Status.FAILED);
        }

        @Test
        @DisplayName("임대 연장 — 구간을 끝낼 때마다 만료가 미뤄진다")
        void heartbeatExtends() {
            registerWithTranscript(VIDEO);
            claudeAnalysis.start(VIDEO, ADMIN);
            WorkerClaim c = claimOrFail();

            now[0] = now[0].plusMinutes(20);
            LocalDateTime until = worker.heartbeat(c.runId(), c.leaseToken());
            now[0] = now[0].plusMinutes(20);   // 처음 임대(30분)는 지났지만 연장분 안

            assertThat(until).isEqualTo(LocalDateTime.of(2026, 10, 2, 10, 50));
            assertThat(worker.claim("other-pc")).isEmpty();
            assertThat(run(c.runId()).getStatus()).isEqualTo(YtAnalysisRun.Status.RUNNING);
        }
    }

    @Nested
    @DisplayName("대기·실패는 '의견 없음'이 아니다 — 직전 성공 결과 유지")
    class FailuresAreExplicit {

        @Test
        @DisplayName("잘못된 JSON — 실행 FAILED(발언 0건), 직전 성공 결과가 그대로 보인다")
        void invalidJsonFailsAndKeepsPrevious() {
            registerWithTranscript(VIDEO);
            StartResult ok = claudeAnalysis.start(VIDEO, ADMIN);
            completeWith(claimOrFail(), YoutubeOpinionFlowTest.REPLY);

            StartResult bad = claudeAnalysis.start(VIDEO, ADMIN);
            WorkerResult result = completeWith(claimOrFail(), "죄송합니다. 다음은 분석 결과입니다: 삼성전자 긍정");

            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(run(bad.runId()).getError()).contains("JSON 배열로 읽지 못함");
            assertThat(opinionRepo.findByRunId(bad.runId())).isEmpty();
            assertThat(video(VIDEO).getCurrentRunId()).as("실패해도 직전 성공 결과 유지").isEqualTo(ok.runId());
            assertThat(opinionRepo.findByRunId(ok.runId())).hasSize(3);
        }

        @Test
        @DisplayName("구간 응답이 빠지면 FAILED — 일부만 저장하지 않는다")
        void missingChunkOutputFails() {
            registerWithTranscript(VIDEO);
            StartResult r = claudeAnalysis.start(VIDEO, ADMIN);
            WorkerClaim c = claimOrFail();

            WorkerResult result = worker.complete(c.runId(), new WorkerCompleteRequest(c.leaseToken(), MODEL, List.of(), 1L));

            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(run(r.runId()).getError()).contains("Claude 응답 없음");
        }

        @Test
        @DisplayName("요청과 다른 모델로 실행됐으면 FAILED — 다른 모델로 조용히 바꾸지 않는다")
        void modelMismatchFails() {
            registerWithTranscript(VIDEO);
            StartResult r = claudeAnalysis.start(VIDEO, ADMIN);
            WorkerClaim c = claimOrFail();

            WorkerResult result = worker.complete(c.runId(), new WorkerCompleteRequest(c.leaseToken(), "claude-haiku-4-5",
                    List.of(new WorkerOutput(0, YoutubeOpinionFlowTest.REPLY)), 1L));

            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(run(r.runId()).getError()).contains("다른 모델");
            assertThat(opinionRepo.findByRunId(r.runId())).isEmpty();
        }

        @Test
        @DisplayName("사용량 한도 — WAITING(사유·다시 시도 시각), 시도로 세지 않고, 시각 전엔 집히지 않는다")
        void rateLimitWaits() {
            registerWithTranscript(VIDEO);
            StartResult r = claudeAnalysis.start(VIDEO, ADMIN);
            WorkerClaim c = claimOrFail();

            WorkerResult result = worker.fail(c.runId(), new WorkerFailRequest(c.leaseToken(), "RATE_LIMITED",
                    "5-hour limit reached", 1800L));

            assertThat(result.status()).isEqualTo("WAITING");
            YtAnalysisRun run = run(r.runId());
            assertThat(run.getWaitReason()).isEqualTo("QUOTA");
            assertThat(run.getNextAttemptAt()).isEqualTo(now[0].plusMinutes(30));
            assertThat(run.getAttempts()).as("대기는 시도로 세지 않는다").isZero();
            assertThat(video(VIDEO).getStatus()).isEqualTo(YtVideo.Status.ANALYZING);
            assertThat(video(VIDEO).getLastError()).contains("사용량 한도");
            assertThat(opinionRepo.findByRunId(r.runId())).isEmpty();

            assertThat(worker.claim("test-pc")).isEmpty();
            now[0] = now[0].plusMinutes(31);
            assertThat(claimOrFail().runId()).isEqualTo(r.runId());
        }

        @Test
        @DisplayName("로그인 만료 — WAITING(LOGIN), 사람이 로그인하도록 사유를 남긴다")
        void authRequiredWaits() {
            registerWithTranscript(VIDEO);
            StartResult r = claudeAnalysis.start(VIDEO, ADMIN);
            WorkerClaim c = claimOrFail();

            worker.fail(c.runId(), new WorkerFailRequest(c.leaseToken(), "AUTH_REQUIRED",
                    "Failed to authenticate: OAuth session expired and could not be refreshed", null));

            YtAnalysisRun run = run(r.runId());
            assertThat(run.getStatus()).isEqualTo(YtAnalysisRun.Status.WAITING);
            assertThat(run.getWaitReason()).isEqualTo("LOGIN");
            assertThat(run.getError()).contains("로그인");
        }

        @Test
        @DisplayName("시간 초과는 시도 상한까지 재시도하고 그 뒤 FAILED")
        void timeoutRetriesThenFails() {
            registerWithTranscript(VIDEO);
            StartResult r = claudeAnalysis.start(VIDEO, ADMIN);
            for (int i = 1; i <= 2; i++) {
                WorkerClaim c = claimOrFail();
                assertThat(worker.fail(c.runId(), new WorkerFailRequest(c.leaseToken(), "TIMEOUT", "180초", null)).status())
                        .isEqualTo("QUEUED");
            }
            WorkerClaim last = claimOrFail();
            WorkerResult result = worker.fail(last.runId(), new WorkerFailRequest(last.leaseToken(), "TIMEOUT", "180초", null));

            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(run(r.runId()).getError()).contains("재시도 3회 소진");
        }

        @Test
        @DisplayName("구독이 아닌 인증 경로(API 키)·작업자 쪽 JSON 실패는 즉시 FAILED — 대기·재시도하지 않는다")
        void nonRetryableFailuresFailImmediately() {
            registerWithTranscript(VIDEO);
            StartResult r = claudeAnalysis.start(VIDEO, ADMIN);
            WorkerClaim c = claimOrFail();

            WorkerResult result = worker.fail(c.runId(), new WorkerFailRequest(c.leaseToken(), "AUTH_PATH_NOT_SUBSCRIPTION",
                    "apiKeySource=ANTHROPIC_API_KEY", null));

            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(run(r.runId()).getError()).contains("별도 과금 경로로 실행하지 않음");
        }

        @Test
        @DisplayName("대기가 상한(72시간)을 넘으면 FAILED — 다시 요청해야 한다")
        void waitingTooLongFails() {
            registerWithTranscript(VIDEO);
            StartResult r = claudeAnalysis.start(VIDEO, ADMIN);
            WorkerClaim c = claimOrFail();
            worker.fail(c.runId(), new WorkerFailRequest(c.leaseToken(), "AUTH_REQUIRED", "login", null));

            now[0] = run(r.runId()).getStartedAt().plusHours(73);
            worker.claim("test-pc");

            assertThat(run(r.runId()).getStatus()).isEqualTo(YtAnalysisRun.Status.FAILED);
            assertThat(run(r.runId()).getError()).contains("대기 72시간 초과");
        }
    }

    @Nested
    @DisplayName("복구·승계")
    class RecoveryAndCarryOver {

        @Test
        @DisplayName("서버 재시작 정리는 Gemini 실행만 실패시킨다 — 작업자가 돌리는 Claude 실행은 임대로 관리")
        void bootRecoveryLeavesClaudeRunsAlone() {
            registerWithTranscript(VIDEO);
            StartResult r = claudeAnalysis.start(VIDEO, ADMIN);
            claimOrFail();
            YtAnalysisRun stuckGemini = runRepo.save(YtAnalysisRun.builder().videoId(VIDEO_2).transcriptId(1L)
                    .model("gemini").promptVersion(OpinionPrompt.VERSION).status(YtAnalysisRun.Status.RUNNING)
                    .chunkCount(1).statementCount(0).droppedCount(0).startedAt(now[0]).build());

            claudeAnalysis.recoverInterrupted();

            assertThat(run(r.runId()).getStatus()).isEqualTo(YtAnalysisRun.Status.RUNNING);
            assertThat(video(VIDEO).getStatus()).isEqualTo(YtVideo.Status.ANALYZING);
            assertThat(run(stuckGemini.getId()).getStatus()).isEqualTo(YtAnalysisRun.Status.FAILED);
        }

        @Test
        @DisplayName("작업자가 임대 중에 사라진 뒤 GEMINI 로 되돌려도 — 만료된 Claude 임대가 Gemini 분석을 계속 막지 않는다")
        void expiredClaudeLeaseDoesNotBlockGeminiAfterSwitchBack() {
            registerWithTranscript(VIDEO);
            registerWithTranscript(VIDEO_2);
            StartResult stuck = claudeAnalysis.start(VIDEO, ADMIN);
            claimOrFail();                                  // 작업자가 집고 그대로 사라진다 — 다시 claim 하러 오지 않는다
            now[0] = now[0].plusMinutes(31);                // 임대(30분) 만료

            StartResult g = geminiAnalysis.start(VIDEO_2, ADMIN);

            assertThat(run(stuck.runId()).getStatus()).isEqualTo(YtAnalysisRun.Status.QUEUED);   // 시도 1/3 — 다시 대기열
            assertThat(run(g.runId()).getStatus()).isEqualTo(YtAnalysisRun.Status.SUCCEEDED);
        }

        @Test
        @DisplayName("임대가 살아 있는 Claude 실행은 그대로 — Gemini 는 여전히 기다린다(동시 실행 1건)")
        void liveClaudeLeaseStillBlocksGemini() {
            registerWithTranscript(VIDEO);
            registerWithTranscript(VIDEO_2);
            StartResult live = claudeAnalysis.start(VIDEO, ADMIN);
            claimOrFail();
            now[0] = now[0].plusMinutes(10);

            assertThatThrownBy(() -> geminiAnalysis.start(VIDEO_2, ADMIN))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("진행 중");
            assertThat(run(live.runId()).getStatus()).isEqualTo(YtAnalysisRun.Status.RUNNING);
        }

        @Test
        @DisplayName("사람이 승인한 발언은 Claude 재분석에서도 같은 내용이면 승인·수동 종목이 이어진다(승계 규칙 공유)")
        void approvalCarriesAcrossClaudeRuns() {
            registerWithTranscript(VIDEO);
            StartResult first = claudeAnalysis.start(VIDEO, ADMIN);
            completeWith(claimOrFail(), YoutubeOpinionFlowTest.AMBIGUOUS_REPLY);   // 동명회사 — 종목 확인 필요(검토)
            YtOpinion pending = opinionRepo.findByRunId(first.runId()).get(0);
            assertThat(pending.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            admin.review(pending.getId(), new ReviewRequest("APPROVE", "111111"), ADMIN);

            StartResult second = claudeAnalysis.start(VIDEO, ADMIN);
            completeWith(claimOrFail(), YoutubeOpinionFlowTest.AMBIGUOUS_REPLY);

            YtOpinion now = opinionRepo.findByRunId(second.runId()).get(0);
            assertThat(now.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.APPROVED);
            assertThat(now.getStockCode()).isEqualTo("111111");
            assertThat(now.getReviewReasons()).contains("REVIEW_CARRIED");
        }

        @Test
        @DisplayName("사람이 승인한 발언의 내용이 Claude 재분석에서 달라지면 다시 검토 — 자동 통과시키지 않는다")
        void changedContentGoesBackToReview() {
            registerWithTranscript(VIDEO);
            StartResult first = claudeAnalysis.start(VIDEO, ADMIN);
            completeWith(claimOrFail(), YoutubeOpinionFlowTest.AMBIGUOUS_REPLY);
            admin.review(opinionRepo.findByRunId(first.runId()).get(0).getId(), new ReviewRequest("APPROVE", "111111"), ADMIN);

            StartResult second = claudeAnalysis.start(VIDEO, ADMIN);
            completeWith(claimOrFail(), YoutubeOpinionFlowTest.AMBIGUOUS_REPLY.replace("지금 사도 좋다\"", "지금이 매수 기회\""));

            YtOpinion now = opinionRepo.findByRunId(second.runId()).get(0);
            assertThat(now.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            assertThat(now.getStockCode()).as("수동 종목도 바뀐 발언에는 잇지 않는다").isNull();
        }
    }

    @Nested
    @DisplayName("순수 규칙")
    class PureRules {

        @Test
        @DisplayName("모델 일치 — 별칭은 포함, 정식 ID 는 같거나 날짜 접미사만, 보고 없으면 불일치")
        void modelMatches() {
            assertThat(YoutubeOpinionWorkerService.modelMatches("sonnet", "claude-sonnet-5")).isTrue();
            assertThat(YoutubeOpinionWorkerService.modelMatches("sonnet", "claude-haiku-4-5")).isFalse();
            assertThat(YoutubeOpinionWorkerService.modelMatches("claude-sonnet-5", "claude-sonnet-5")).isTrue();
            assertThat(YoutubeOpinionWorkerService.modelMatches("claude-sonnet-5", "claude-sonnet-5-20261001")).isTrue();
            assertThat(YoutubeOpinionWorkerService.modelMatches("claude-sonnet-5", "claude-sonnet-5-5")).isFalse();
            assertThat(YoutubeOpinionWorkerService.modelMatches("sonnet", "")).isFalse();
            assertThat(YoutubeOpinionWorkerService.modelMatches("sonnet", null)).isFalse();
        }

        @Test
        @DisplayName("모르는 실패 종류는 재시도 대상(CLI 오류)으로 — 대기나 성공으로 보지 않는다")
        void unknownFailureIsRetryable() {
            assertThat(YoutubeOpinionWorkerService.parseFailure("???"))
                    .isEqualTo(YoutubeOpinionWorkerService.FailureType.CLI_ERROR);
            assertThat(YoutubeOpinionWorkerService.parseFailure("rate_limited"))
                    .isEqualTo(YoutubeOpinionWorkerService.FailureType.RATE_LIMITED);
        }

        @Test
        @DisplayName("분석기 설정 — 기본·모르는 값은 GEMINI(운영 동작 유지), CLAUDE 만 전환")
        void analyzerParse() {
            assertThat(OpinionAnalyzerSettings.parse(null)).isEqualTo(OpinionAnalyzerSettings.Analyzer.GEMINI);
            assertThat(OpinionAnalyzerSettings.parse("claude ")).isEqualTo(OpinionAnalyzerSettings.Analyzer.CLAUDE);
            assertThat(OpinionAnalyzerSettings.parse("gpt")).isEqualTo(OpinionAnalyzerSettings.Analyzer.GEMINI);
        }

        @Test
        @DisplayName("작업자 식별자는 로그용 문자만 남긴다")
        void workerIdSanitized() {
            assertThat(YoutubeOpinionWorkerService.sanitizeWorkerId("pc-1\n; rm -rf")).isEqualTo("pc-1rm-rf");
            assertThat(YoutubeOpinionWorkerService.sanitizeWorkerId(null)).isEqualTo("worker");
        }
    }
}
