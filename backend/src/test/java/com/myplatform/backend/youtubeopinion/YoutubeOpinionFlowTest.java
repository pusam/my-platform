package com.myplatform.backend.youtubeopinion;

import com.myplatform.backend.entity.StockMaster;
import com.myplatform.backend.service.GeminiService;
import com.myplatform.backend.service.StockMasterService;
import com.myplatform.core.util.DateTimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.myplatform.backend.youtubeopinion.YoutubeOpinionDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 관리자 등록 → 자막 → 분석 → 조회 전 과정을 실제 JPA(H2, MySQL 모드)로 돌린다. Gemini·종목 마스터만 가짜.
 *
 * <p>여기서 고정하는 것: 미수집·분석 대기·실패·"분석했지만 의견 없음" 구분(§4c), 재등록·같은 자막·재분석이 중복을 만들지 않음,
 * 재분석 실패 시 이전 결과 유지, 재업로드 제외, 사람 검토 결정 승계, 타임스탬프 링크, 기능 꺼짐, 조회 실패가 화면을 막지 않음.
 *
 * <p>서비스는 테스트마다 새로 만든다 — 시간당 처리량 제한이 테스트 사이에 새지 않게. 영상·인물·발언은 전부 테스트 전용 가상 값이다.
 */
@SpringBootTest(classes = YoutubeOpinionFlowTest.JpaOnly.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ytopinionflow;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
class YoutubeOpinionFlowTest {

    @Configuration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class})
    @EntityScan(basePackageClasses = YtVideo.class)
    @EnableJpaRepositories(basePackageClasses = YtVideoRepository.class)
    static class JpaOnly {}

    static final String CHANNEL = "UC_test_ch1";
    static final String VIDEO = "TESTvid0001";
    static final String VIDEO_2 = "TESTvid0002";
    static final String ADMIN = "test-admin";

    static final Map<String, String> MASTER = Map.of(
            "005930", "삼성전자", "005935", "삼성전자우", "000660", "SK하이닉스", "066570", "LG전자",
            "111111", "동명회사", "222222", "동명회사");

    /** 운영자·출연자 두 사람이 나오는 가상 자막 — 큐 번호 c0..c4. */
    static final String SRT = """
            1
            00:00:01,000 --> 00:00:09,000
            테스트운영자: 오늘은 반도체 얘기를 해 보겠습니다

            2
            00:01:05,000 --> 00:01:12,000
            테스트운영자: 삼성전자는 지금 사도 좋다고 봅니다 실적이 좋아요

            3
            00:01:12,000 --> 00:01:20,000
            테스트출연자: SK하이닉스는 조정 오면 매수하겠습니다

            4
            00:01:20,000 --> 00:01:30,000
            테스트출연자: 삼성전자는 저도 긍정적으로 봅니다

            5
            00:01:30,000 --> 00:01:40,000
            테스트운영자: 동명회사는 지금 사도 좋습니다
            """;

    static final String REPLY = """
            [
              {"stockName":"삼성전자","stockCode":"005930","statementType":"CURRENT_VIEW","stance":"POSITIVE",
               "speaker":"테스트운영자","claimSummary":"지금 매수해도 좋다","rationale":"실적이 좋다",
               "startCue":1,"endCue":1,"evidenceQuote":"삼성전자는 지금 사도 좋다고 봅니다"},
              {"stockName":"SK하이닉스","statementType":"CURRENT_VIEW","stance":"CONDITIONAL","speaker":"테스트출연자",
               "conditions":"조정이 오면","claimSummary":"조정 시 매수","startCue":2,"endCue":2,
               "evidenceQuote":"SK하이닉스는 조정 오면 매수하겠습니다"},
              {"stockName":"삼성전자","statementType":"CURRENT_VIEW","stance":"POSITIVE","speaker":"테스트출연자",
               "claimSummary":"긍정적","startCue":3,"endCue":3,"evidenceQuote":"삼성전자는 저도 긍정적으로 봅니다"}
            ]
            """;

    static final String AMBIGUOUS_REPLY = """
            [{"stockName":"동명회사","statementType":"CURRENT_VIEW","stance":"POSITIVE","speaker":"테스트운영자",
              "claimSummary":"지금 사도 좋다","startCue":4,"endCue":4,"evidenceQuote":"동명회사는 지금 사도 좋습니다"}]
            """;

    @Autowired YtVideoRepository videoRepo;
    @Autowired YtPersonRepository personRepo;
    @Autowired YtVideoParticipantRepository participantRepo;
    @Autowired YtTranscriptRepository transcriptRepo;
    @Autowired YtAnalysisRunRepository runRepo;
    @Autowired YtOpinionRepository opinionRepo;
    @Autowired PlatformTransactionManager transactionManager;

    GeminiService gemini;
    StockMasterService master;
    ObjectProvider<GeminiService> geminiProvider;
    /** Gemini 가짜 응답 — 호출 순서대로. null 은 "응답 없음", 소진되면 빈 배열. */
    final List<String> replies = new ArrayList<>();
    int geminiCalls;

    YoutubeOpinionAdminService admin;
    YoutubeOpinionAnalysisService analysis;
    YoutubeOpinionQueryService query;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        opinionRepo.deleteAll();
        runRepo.deleteAll();
        transcriptRepo.deleteAll();
        participantRepo.deleteAll();
        videoRepo.deleteAll();
        personRepo.deleteAll();
        replies.clear();
        geminiCalls = 0;

        gemini = mock(GeminiService.class);
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.generateStructuredJson(anyString(), anyMap())).thenAnswer(inv -> {
            int i = geminiCalls++;
            return i < replies.size() ? replies.get(i) : "[]";
        });
        master = mock(StockMasterService.class);
        when(master.findByCode(anyString())).thenAnswer(inv -> Optional.ofNullable(MASTER.get((String) inv.getArgument(0)))
                .map(name -> stock(inv.getArgument(0), name)));
        when(master.search(anyString(), anyInt())).thenAnswer(inv -> MASTER.entrySet().stream()
                .filter(e -> e.getValue().contains((String) inv.getArgument(0)))
                .map(e -> stock(e.getKey(), e.getValue())).toList());
        geminiProvider = mock(ObjectProvider.class);
        when(geminiProvider.getIfAvailable()).thenReturn(gemini);
        useSettings(settings(true, 20, 60, ""));
    }

    static StockMaster stock(String code, String name) {
        return StockMaster.builder().stockCode(code).stockName(name).market("KOSPI").isActive(true).build();
    }

    static YoutubeOpinionSettings settings(boolean enabled, int maxChunks, int dailyLimit, String quietWindow) {
        return new YoutubeOpinionSettings(enabled, CHANNEL + "=테스트채널A", 7, maxChunks, dailyLimit, quietWindow,
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent");
    }

    void useSettings(YoutubeOpinionSettings s) {
        admin = new YoutubeOpinionAdminService(s, videoRepo, personRepo, participantRepo, transcriptRepo, runRepo,
                opinionRepo, master, geminiProvider);
        analysis = new YoutubeOpinionAnalysisService(s, videoRepo, transcriptRepo, participantRepo, personRepo, runRepo,
                opinionRepo, geminiProvider, master, transactionManager);
        analysis.useExecutor(Runnable::run);   // 동기 실행 — start() 가 돌아오면 분석이 끝나 있다
        query = new YoutubeOpinionQueryService(s, videoRepo, opinionRepo, personRepo, runRepo);
    }

    static String hoursAgo(int h) {
        return DateTimeUtil.kstNow().minusHours(h).truncatedTo(ChronoUnit.MINUTES).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    RegisterResult register(String videoId, String duplicateOf, String... hostThenGuests) {
        List<ParticipantInput> ps = new ArrayList<>();
        for (int i = 0; i < hostThenGuests.length; i++) ps.add(new ParticipantInput(hostThenGuests[i], i == 0 ? "HOST" : "GUEST"));
        return admin.register(new RegisterRequest("https://www.youtube.com/watch?v=" + videoId, "테스트 영상 " + videoId,
                CHANNEL, hoursAgo(5), "테스트 전용 자막 파일", duplicateOf, ps), ADMIN);
    }

    void registerWithTranscript(String videoId) {
        register(videoId, null, "테스트운영자", "테스트출연자");
        admin.uploadTranscript(videoId, new TranscriptRequest("SRT", SRT), ADMIN);
    }

    YtVideo video(String id) {
        return videoRepo.findByVideoId(id).orElseThrow();
    }

    // ------------------------------------------------------------------ 전체 흐름

    @Test
    @DisplayName("등록 → 자막 → 분석 → 종목 조회: 인물별 의견·조건부 유지·타임스탬프 링크·범위 표기")
    void fullFlow() {
        registerWithTranscript(VIDEO);
        replies.add(REPLY);

        StartResult started = analysis.start(VIDEO, ADMIN);

        assertThat(started.chunkCount()).isEqualTo(1);
        YtVideo v = video(VIDEO);
        assertThat(v.getStatus()).isEqualTo(YtVideo.Status.ANALYZED);
        assertThat(v.getCurrentRunId()).isEqualTo(started.runId());
        assertThat(v.getLastSuccessAt()).isNotNull();
        YtAnalysisRun run = runRepo.findById(started.runId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(YtAnalysisRun.Status.SUCCEEDED);
        assertThat(run.getStatementCount()).isEqualTo(3);
        assertThat(run.getModel()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(run.getPromptVersion()).isEqualTo(OpinionPrompt.VERSION);

        StockViewDto samsung = query.stockView("005930");
        assertThat(samsung.enabled()).isTrue();
        assertThat(samsung.dataAvailable()).isTrue();
        assertThat(samsung.status()).isEqualTo(YoutubeOpinionQueryService.HAS_OPINIONS);
        assertThat(samsung.scope()).isEqualTo("분석된 영상 기준");
        assertThat(samsung.windowDays()).isEqualTo(7);
        assertThat(samsung.summary().statementCounts()).containsEntry("POSITIVE", 2);
        assertThat(samsung.summary().personCounts()).containsEntry("POSITIVE", 2);
        assertThat(samsung.persons()).extracting(PersonDto::name).containsExactlyInAnyOrder("테스트운영자", "테스트출연자");
        assertThat(samsung.persons()).filteredOn(p -> p.name().equals("테스트운영자")).extracting(PersonDto::role).containsExactly("HOST");
        OpinionDto hostView = samsung.opinions().stream().filter(o -> "테스트운영자".equals(o.speakerName())).findFirst().orElseThrow();
        assertThat(hostView.sourceUrl()).isEqualTo("https://www.youtube.com/watch?v=" + VIDEO + "&t=65s");
        assertThat(hostView.startLabel()).isEqualTo("01:05");
        assertThat(hostView.evidenceQuote()).isEqualTo("삼성전자는 지금 사도 좋다고 봅니다");
        assertThat(hostView.publishedAt()).isNotNull();
        assertThat(hostView.analyzedAt()).isNotNull();

        StockViewDto hynix = query.stockView("000660");
        assertThat(hynix.opinions()).singleElement().satisfies(o -> {
            assertThat(o.stance()).as("'조정 오면 매수' 는 지금 매수가 아니다").isEqualTo("CONDITIONAL");
            assertThat(o.conditions()).isEqualTo("조정이 오면");
            assertThat(o.speakerRole()).isEqualTo("GUEST");
        });
    }

    @Test
    @DisplayName("여러 종목 요약은 한 번에 — 분석은 됐지만 언급 없는 종목은 NO_OPINION('의견 없음')")
    void summaryBatch() {
        registerWithTranscript(VIDEO);
        replies.add(REPLY);
        analysis.start(VIDEO, ADMIN);

        SummaryViewDto s = query.summary(List.of("005930", "000660", "066570"));

        assertThat(s.enabled()).isTrue();
        assertThat(s.dataAvailable()).isTrue();
        assertThat(s.coverage().analyzedVideos()).isEqualTo(1);
        assertThat(s.items().get("005930").status()).isEqualTo(YoutubeOpinionQueryService.HAS_OPINIONS);
        assertThat(s.items().get("005930").summary().totalPersons()).isEqualTo(2);
        assertThat(s.items().get("066570").status()).isEqualTo(YoutubeOpinionQueryService.NO_OPINION);
        assertThat(s.items().get("066570").summary().totalStatements()).isZero();
    }

    // ------------------------------------------------------------------ 상태 구분(§4c)

    @Nested
    @DisplayName("미수집 · 분석 대기 · 실패 · 분석했지만 없음 — 서로 다른 상태로 보인다")
    class States {

        @Test
        @DisplayName("영상이 하나도 없으면 NO_ANALYZED_VIDEOS — '의견 없음'이 아니다")
        void nothingCollected() {
            StockViewDto v = query.stockView("005930");
            assertThat(v.status()).isEqualTo(YoutubeOpinionQueryService.NO_ANALYZED_VIDEOS);
            assertThat(v.coverage()).isEqualTo(new CoverageDto(0, 0, 0, 0, 0));
        }

        @Test
        @DisplayName("등록만 한 영상은 자막 없음, 자막만 올린 영상은 분석 대기로 센다")
        void registeredAndAwaiting() {
            register(VIDEO, null, "테스트운영자");
            assertThat(query.stockView("005930").coverage().noTranscriptVideos()).isEqualTo(1);

            admin.uploadTranscript(VIDEO, new TranscriptRequest("SRT", SRT), ADMIN);
            StockViewDto v = query.stockView("005930");
            assertThat(v.status()).isEqualTo(YoutubeOpinionQueryService.NO_ANALYZED_VIDEOS);
            assertThat(v.coverage().awaitingAnalysisVideos()).isEqualTo(1);
            assertThat(v.coverage().noTranscriptVideos()).isZero();
        }

        @Test
        @DisplayName("Gemini 응답 없음 → 실행 FAILED + 원인, 영상 FAILED — 빈 결과를 성공으로 저장하지 않는다")
        void geminiNoReply() {
            registerWithTranscript(VIDEO);
            replies.add(null);

            StartResult r = analysis.start(VIDEO, ADMIN);

            YtAnalysisRun run = runRepo.findById(r.runId()).orElseThrow();
            assertThat(run.getStatus()).isEqualTo(YtAnalysisRun.Status.FAILED);
            assertThat(run.getError()).contains("Gemini 응답 없음");
            YtVideo v = video(VIDEO);
            assertThat(v.getStatus()).isEqualTo(YtVideo.Status.FAILED);
            assertThat(v.getLastError()).contains("Gemini 응답 없음");
            assertThat(v.getCurrentRunId()).isNull();
            assertThat(opinionRepo.count()).isZero();
            StockViewDto view = query.stockView("005930");
            assertThat(view.status()).isEqualTo(YoutubeOpinionQueryService.NO_ANALYZED_VIDEOS);
            assertThat(view.coverage().failedVideos()).isEqualTo(1);
        }

        @Test
        @DisplayName("응답이 JSON 배열이 아니면 실패 — '의견 0건'으로 읽지 않는다")
        void unreadableReply() {
            registerWithTranscript(VIDEO);
            replies.add("죄송하지만 분석할 수 없습니다");

            StartResult r = analysis.start(VIDEO, ADMIN);

            assertThat(runRepo.findById(r.runId()).orElseThrow().getError()).contains("JSON 배열로 읽지 못함");
            assertThat(video(VIDEO).getStatus()).isEqualTo(YtVideo.Status.FAILED);
        }

        @Test
        @DisplayName("진짜 빈 배열은 성공 — 분석했지만 의견 없음(NO_OPINION)")
        void analyzedEmpty() {
            registerWithTranscript(VIDEO);
            replies.add("[]");

            analysis.start(VIDEO, ADMIN);

            assertThat(video(VIDEO).getStatus()).isEqualTo(YtVideo.Status.ANALYZED);
            StockViewDto v = query.stockView("005930");
            assertThat(v.status()).isEqualTo(YoutubeOpinionQueryService.NO_OPINION);
            assertThat(v.coverage().analyzedVideos()).isEqualTo(1);
        }

        @Test
        @DisplayName("재분석이 실패해도 이전 성공 결과는 그대로 보이고, 실패 사실도 함께 보인다")
        void failedReanalysisKeepsPrevious() {
            registerWithTranscript(VIDEO);
            replies.add(REPLY);
            long firstRun = analysis.start(VIDEO, ADMIN).runId();
            admin.uploadTranscript(VIDEO, new TranscriptRequest("SRT", SRT + "\n6\n00:01:40,000 --> 00:01:45,000\n테스트운영자: 오늘은 여기까지\n"), ADMIN);
            assertThat(query.stockView("005930").status()).as("새 자막 등록 직후에도 이전 결과 유지")
                    .isEqualTo(YoutubeOpinionQueryService.HAS_OPINIONS);
            replies.add(null);

            analysis.start(VIDEO, ADMIN);

            YtVideo v = video(VIDEO);
            assertThat(v.getCurrentRunId()).isEqualTo(firstRun);
            assertThat(v.getStatus()).isEqualTo(YtVideo.Status.FAILED);
            StockViewDto view = query.stockView("005930");
            assertThat(view.status()).isEqualTo(YoutubeOpinionQueryService.HAS_OPINIONS);
            assertThat(view.summary().totalStatements()).isEqualTo(2);
            assertThat(view.coverage().analyzedVideos()).isEqualTo(1);
            assertThat(view.coverage().failedVideos()).isEqualTo(1);
            assertThat(admin.runs(VIDEO)).extracting(RunDto::status).containsExactly("FAILED", "SUCCEEDED");
        }

        @Test
        @DisplayName("재시작으로 끊긴 RUNNING 실행은 부팅 시 FAILED 로 정리 — '분석 중'에 영원히 머물지 않는다")
        void recoverInterrupted() {
            registerWithTranscript(VIDEO);
            YtVideo v = video(VIDEO);
            v.setStatus(YtVideo.Status.ANALYZING);
            videoRepo.save(v);
            YtAnalysisRun stuck = runRepo.save(YtAnalysisRun.builder().videoId(VIDEO).transcriptId(1L).model("m")
                    .promptVersion("v").status(YtAnalysisRun.Status.RUNNING).chunkCount(1).statementCount(0)
                    .droppedCount(0).startedAt(DateTimeUtil.kstNow()).build());

            analysis.recoverInterrupted();

            assertThat(runRepo.findById(stuck.getId()).orElseThrow().getStatus()).isEqualTo(YtAnalysisRun.Status.FAILED);
            assertThat(video(VIDEO).getStatus()).isEqualTo(YtVideo.Status.FAILED);
            assertThat(video(VIDEO).getLastError()).contains("재시작");
        }
    }

    // ------------------------------------------------------------------ 중복 방지

    @Nested
    @DisplayName("재등록·같은 자막·재분석은 중복을 만들지 않는다")
    class Dedup {

        @Test
        @DisplayName("같은 영상을 다른 주소 형식으로 다시 등록해도 한 건 — 출연자도 늘지 않는다")
        void reRegister() {
            register(VIDEO, null, "테스트운영자", "테스트출연자");
            RegisterResult again = admin.register(new RegisterRequest("https://youtu.be/" + VIDEO, "다른 제목", CHANNEL,
                    hoursAgo(1), "다른 출처", null, List.of(new ParticipantInput("테스트운영자", "HOST"))), ADMIN);

            assertThat(again.alreadyRegistered()).isTrue();
            assertThat(again.video().title()).isEqualTo("테스트 영상 " + VIDEO);
            assertThat(videoRepo.count()).isEqualTo(1);
            assertThat(participantRepo.count()).isEqualTo(2);
            assertThat(personRepo.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("같은 자막(줄바꿈만 다름)은 새 버전을 만들지 않는다")
        void sameTranscript() {
            registerWithTranscript(VIDEO);
            TranscriptResult again = admin.uploadTranscript(VIDEO, new TranscriptRequest("AUTO", SRT.replace("\n", "\r\n")), ADMIN);

            assertThat(again.duplicate()).isTrue();
            assertThat(again.version()).isEqualTo(1);
            assertThat(transcriptRepo.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("재분석은 이력(실행 2건)을 남기되 화면 집계는 현재 실행만 — 발언이 두 배가 되지 않는다")
        void reanalysis() {
            registerWithTranscript(VIDEO);
            replies.add(REPLY);
            replies.add(REPLY);
            analysis.start(VIDEO, ADMIN);
            long second = analysis.start(VIDEO, ADMIN).runId();

            assertThat(runRepo.count()).isEqualTo(2);
            assertThat(opinionRepo.count()).as("실행별 이력 보존").isEqualTo(6);
            assertThat(video(VIDEO).getCurrentRunId()).isEqualTo(second);
            StockViewDto v = query.stockView("005930");
            assertThat(v.summary().totalStatements()).isEqualTo(2);
            assertThat(v.summary().totalPersons()).isEqualTo(2);
            assertThat(admin.runs(VIDEO)).filteredOn(RunDto::current).extracting(RunDto::id).containsExactly(second);
        }

        @Test
        @DisplayName("재업로드·편집본(duplicateOf)은 같은 발언을 담아도 세지 않는다")
        void reupload() {
            registerWithTranscript(VIDEO);
            register(VIDEO_2, VIDEO, "테스트운영자", "테스트출연자");
            admin.uploadTranscript(VIDEO_2, new TranscriptRequest("SRT", SRT), ADMIN);
            replies.add(REPLY);
            replies.add(REPLY);
            analysis.start(VIDEO, ADMIN);
            analysis.start(VIDEO_2, ADMIN);

            StockViewDto v = query.stockView("005930");
            assertThat(v.summary().totalStatements()).isEqualTo(2);
            assertThat(v.coverage().analyzedVideos()).as("재업로드는 커버리지에서도 제외").isEqualTo(1);
        }

        @Test
        @DisplayName("같은 사람이 다른 영상에서 또 말하면 발언은 늘고 인물은 그대로(이름 표기 차이는 같은 사람)")
        void samePersonAcrossVideos() {
            registerWithTranscript(VIDEO);
            register(VIDEO_2, null, "테스트 운영자");
            admin.uploadTranscript(VIDEO_2, new TranscriptRequest("TEXT",
                    "[00:10] 테스트 운영자: 삼성전자는 지금 사도 좋다고 봅니다\n"), ADMIN);
            replies.add(REPLY);
            replies.add("""
                    [{"stockName":"삼성전자","statementType":"CURRENT_VIEW","stance":"POSITIVE","speaker":"테스트운영자",
                      "claimSummary":"지금 매수","startCue":0,"endCue":0,"evidenceQuote":"삼성전자는 지금 사도 좋다고 봅니다"}]
                    """);
            analysis.start(VIDEO, ADMIN);
            analysis.start(VIDEO_2, ADMIN);

            StockViewDto v = query.stockView("005930");
            assertThat(personRepo.count()).isEqualTo(2);
            assertThat(v.summary().totalStatements()).isEqualTo(3);
            assertThat(v.summary().totalPersons()).isEqualTo(2);
            assertThat(v.persons()).filteredOn(p -> p.name().equals("테스트운영자")).singleElement()
                    .satisfies(p -> assertThat(p.statements()).isEqualTo(2));
        }
    }

    // ------------------------------------------------------------------ 검토

    @Nested
    @DisplayName("검토 — 종목이 모호하면 화면에 안 나가고, 사람이 정한 결정은 재분석에도 유지된다")
    class Review {

        @Test
        @DisplayName("동명 종목 → 검토 목록. 코드 없이 승인 불가, 마스터 코드로 승인하면 그 종목에 보이고 재분석해도 유지")
        void ambiguousStockReviewCarriesOver() {
            registerWithTranscript(VIDEO);
            replies.add(AMBIGUOUS_REPLY);
            replies.add(AMBIGUOUS_REPLY);
            analysis.start(VIDEO, ADMIN);

            List<ReviewRowDto> queue = admin.reviewQueue();
            assertThat(queue).singleElement().satisfies(r -> {
                assertThat(r.mappingStatus()).isEqualTo("AMBIGUOUS");
                assertThat(r.reviewReasons()).contains("STOCK_AMBIGUOUS");
                assertThat(r.sourceUrl()).endsWith("&t=90s");
            });
            assertThat(query.stockView("111111").status()).isNotEqualTo(YoutubeOpinionQueryService.HAS_OPINIONS);
            long id = queue.get(0).id();
            assertThatThrownBy(() -> admin.review(id, new ReviewRequest("APPROVE", null), ADMIN))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("종목코드");
            assertThatThrownBy(() -> admin.review(id, new ReviewRequest("APPROVE", "999999"), ADMIN))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("마스터에 없는");

            ReviewRowDto approved = admin.review(id, new ReviewRequest("APPROVE", "111111"), ADMIN);
            assertThat(approved.mappingStatus()).isEqualTo("MANUAL");
            assertThat(query.stockView("111111").status()).isEqualTo(YoutubeOpinionQueryService.HAS_OPINIONS);

            analysis.start(VIDEO, ADMIN);

            assertThat(admin.reviewQueue()).as("재분석이 사람 결정을 되돌리지 않는다").isEmpty();
            StockViewDto v = query.stockView("111111");
            assertThat(v.summary().totalStatements()).isEqualTo(1);
            YtOpinion carried = opinionRepo.findByRunId(video(VIDEO).getCurrentRunId()).get(0);
            assertThat(carried.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.APPROVED);
            assertThat(carried.getReviewReasons()).contains("REVIEW_CARRIED");
            assertThat(carried.getStockCode()).isEqualTo("111111");
        }

        @Test
        @DisplayName("거절한 발언은 화면·집계에서 빠진다")
        void reject() {
            registerWithTranscript(VIDEO);
            replies.add(REPLY);
            analysis.start(VIDEO, ADMIN);
            YtOpinion hostSamsung = opinionRepo.findAll().stream()
                    .filter(o -> "005930".equals(o.getStockCode()) && "테스트운영자".equals(o.getSpeakerLabel())).findFirst().orElseThrow();

            admin.review(hostSamsung.getId(), new ReviewRequest("REJECT", null), ADMIN);

            assertThat(query.stockView("005930").summary().totalStatements()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ 검토 결정 승계

    /** 동명회사 발언의 의견 방향·요약이 바뀐 재분석 — 종목 표기·발언자·시작 줄·유형이 같아 발언 키는 같다. */
    static final String AMBIGUOUS_CHANGED_REPLY = """
            [{"stockName":"동명회사","statementType":"CURRENT_VIEW","stance":"NEGATIVE","speaker":"테스트운영자",
              "claimSummary":"지금은 팔아야 한다","startCue":4,"endCue":4,"evidenceQuote":"동명회사는 지금 사도 좋습니다"}]
            """;

    /** 내용은 같고 모델이 댄 발언자만 자막 표기와 다르다 — 새 검토 사유(SPEAKER_CONFLICT)만 생긴다. */
    static final String AMBIGUOUS_SPEAKER_CONFLICT_REPLY = """
            [{"stockName":"동명회사","statementType":"CURRENT_VIEW","stance":"POSITIVE","speaker":"테스트출연자",
              "claimSummary":"지금 사도 좋다","startCue":4,"endCue":4,"evidenceQuote":"동명회사는 지금 사도 좋습니다"}]
            """;

    /** REPLY 에서 운영자의 삼성전자 발언 요약·근거 설명만 바뀐 재분석. */
    static final String REPLY_HOST_SUMMARY_CHANGED = REPLY.replace(
            "\"claimSummary\":\"지금 매수해도 좋다\",\"rationale\":\"실적이 좋다\"",
            "\"claimSummary\":\"실적 개선이 이어져 비중을 늘린다\",\"rationale\":\"실적 개선\"");

    /** c4 원문이 바뀐 자막 v2 — 같은 줄 번호가 다른 말을 담는다. */
    static final String SRT_C4_REVISED = SRT.replace("테스트운영자: 동명회사는 지금 사도 좋습니다",
            "테스트운영자: 동명회사는 지금 사도 좋습니다 다만 저는 이미 다 팔았어요");

    /** 발언과 무관한 c0 만 고친 자막 v2. */
    static final String SRT_C0_REVISED = SRT.replace("오늘은 반도체 얘기를 해 보겠습니다", "오늘은 반도체와 전지 얘기를 해 보겠습니다");

    /** 운영자 한 사람이 두 줄(c1~c2)에 걸쳐 말하는 가상 자막 — 같은 발언의 시작 줄을 모델이 재분석마다 다르게 고를 수 있다. */
    static final String SRT_SPAN = """
            1
            00:00:01,000 --> 00:00:09,000
            테스트운영자: 오늘은 반도체 얘기를 해 보겠습니다

            2
            00:01:05,000 --> 00:01:12,000
            테스트운영자: 삼성전자는 지금 사도 좋다고 봅니다

            3
            00:01:12,000 --> 00:01:20,000
            테스트운영자: 실적이 계속 좋아지고 있어요
            """;

    static final String SPAN_REPLY_FROM_C1 = """
            [{"stockName":"삼성전자","stockCode":"005930","statementType":"CURRENT_VIEW","stance":"POSITIVE",
              "speaker":"테스트운영자","claimSummary":"지금 매수해도 좋다","startCue":1,"endCue":2,
              "evidenceQuote":"삼성전자는 지금 사도 좋다고 봅니다"}]
            """;

    static final String SPAN_REPLY_FROM_C2 = SPAN_REPLY_FROM_C1.replace("\"startCue\":1,\"endCue\":2", "\"startCue\":2,\"endCue\":2");

    YtOpinion currentRow(String stockNameRaw) {
        return opinionRepo.findByRunId(video(VIDEO).getCurrentRunId()).stream()
                .filter(o -> stockNameRaw.equals(o.getStockNameRaw())).findFirst().orElseThrow();
    }

    YtOpinion currentRow(String stockCode, String speaker) {
        return opinionRepo.findByRunId(video(VIDEO).getCurrentRunId()).stream()
                .filter(o -> stockCode.equals(o.getStockCode()) && speaker.equals(o.getSpeakerLabel())).findFirst().orElseThrow();
    }

    /** 동명회사 발언을 검토 목록에서 111111 로 승인한 상태까지. */
    YtOpinion approveAmbiguousAsManual() {
        long id = admin.reviewQueue().get(0).id();
        admin.review(id, new ReviewRequest("APPROVE", "111111"), ADMIN);
        assertThat(query.stockView("111111").status()).isEqualTo(YoutubeOpinionQueryService.HAS_OPINIONS);
        return opinionRepo.findById(id).orElseThrow();
    }

    @Nested
    @DisplayName("검토 결정 승계 — 발언 식별(같은 발언인가)과 결정 승계(사람이 본 내용 그대로인가)는 다르다")
    class ReviewCarry {

        @Test
        @DisplayName("승인한 발언의 의견이 바뀌면 승인·수동 종목을 잇지 않는다 — 다시 검토 목록으로, 이전 승인 행은 이력으로 남는다")
        void changedOpinionDoesNotInheritApproval() {
            registerWithTranscript(VIDEO);
            replies.add(AMBIGUOUS_REPLY);
            replies.add(AMBIGUOUS_CHANGED_REPLY);
            analysis.start(VIDEO, ADMIN);
            YtOpinion approved = approveAmbiguousAsManual();

            analysis.start(VIDEO, ADMIN);

            YtOpinion now = currentRow("동명회사");
            assertThat(now.getStatementKey()).as("같은 발언으로 식별은 된다").isEqualTo(approved.getStatementKey());
            assertThat(now.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            assertThat(now.getStockCode()).as("수동 종목도 바뀐 발언에는 잇지 않는다").isNull();
            assertThat(now.getMappingStatus()).isEqualTo(YtOpinion.MappingStatus.AMBIGUOUS);
            assertThat(now.getReviewedBy()).isNull();
            assertThat(now.getReviewReasons()).contains("STOCK_AMBIGUOUS", "REVIEW_NOT_CARRIED").doesNotContain("REVIEW_CARRIED");
            assertThat(admin.reviewQueue()).singleElement().satisfies(r -> assertThat(r.id()).isEqualTo(now.getId()));
            assertThat(query.stockView("111111").status()).isNotEqualTo(YoutubeOpinionQueryService.HAS_OPINIONS);
            YtOpinion history = opinionRepo.findById(approved.getId()).orElseThrow();
            assertThat(history.getReviewStatus()).as("이전 승인은 지우지 않고 이력으로").isEqualTo(YtOpinion.ReviewStatus.APPROVED);
            assertThat(history.getStockCode()).isEqualTo("111111");
            assertThat(history.getReviewedBy()).isEqualTo(ADMIN);
        }

        @Test
        @DisplayName("내용이 같아도 새 검토 사유가 생기면 이전 승인으로 덮지 않는다")
        void newReviewReasonIsNotOverriddenByOldApproval() {
            registerWithTranscript(VIDEO);
            replies.add(AMBIGUOUS_REPLY);
            replies.add(AMBIGUOUS_SPEAKER_CONFLICT_REPLY);
            analysis.start(VIDEO, ADMIN);
            approveAmbiguousAsManual();

            analysis.start(VIDEO, ADMIN);

            YtOpinion now = currentRow("동명회사");
            assertThat(now.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            assertThat(now.getReviewReasons()).contains("SPEAKER_CONFLICT", "REVIEW_NOT_CARRIED");
            assertThat(now.getStockCode()).isNull();
            assertThat(query.stockView("111111").status()).isNotEqualTo(YoutubeOpinionQueryService.HAS_OPINIONS);
        }

        @Test
        @DisplayName("같은 내용 재분석은 다시 검토시키지 않는다 — 승인·수동 종목·검토자·시각을 그대로 잇는다")
        void identicalReanalysisKeepsApproval() {
            registerWithTranscript(VIDEO);
            replies.add(AMBIGUOUS_REPLY);
            replies.add(AMBIGUOUS_REPLY);
            analysis.start(VIDEO, ADMIN);
            YtOpinion approved = approveAmbiguousAsManual();

            analysis.start(VIDEO, ADMIN);

            YtOpinion now = currentRow("동명회사");
            assertThat(now.getId()).as("새 실행의 새 행").isNotEqualTo(approved.getId());
            assertThat(now.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.APPROVED);
            assertThat(now.getStockCode()).isEqualTo("111111");
            assertThat(now.getMappingStatus()).isEqualTo(YtOpinion.MappingStatus.MANUAL);
            assertThat(now.getReviewedBy()).isEqualTo(approved.getReviewedBy());
            assertThat(now.getReviewedAt()).isEqualTo(approved.getReviewedAt());
            assertThat(now.getReviewReasons()).contains("REVIEW_CARRIED");
            assertThat(admin.reviewQueue()).isEmpty();
        }

        @Test
        @DisplayName("자막이 고쳐져 같은 줄이 다른 말을 담으면 승인을 잇지 않는다")
        void revisedCueTextDoesNotInheritApproval() {
            registerWithTranscript(VIDEO);
            replies.add(AMBIGUOUS_REPLY);
            replies.add(AMBIGUOUS_REPLY);
            analysis.start(VIDEO, ADMIN);
            approveAmbiguousAsManual();
            assertThat(admin.uploadTranscript(VIDEO, new TranscriptRequest("SRT", SRT_C4_REVISED), ADMIN).version()).isEqualTo(2);

            analysis.start(VIDEO, ADMIN);

            YtOpinion now = currentRow("동명회사");
            assertThat(now.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            assertThat(now.getReviewReasons()).contains("REVIEW_NOT_CARRIED");
            assertThat(now.getStockCode()).isNull();
        }

        @Test
        @DisplayName("발언과 무관한 줄만 고친 자막이면 승인을 잇는다 — 불필요한 재검토 없음")
        void unrelatedTranscriptFixKeepsApproval() {
            registerWithTranscript(VIDEO);
            replies.add(AMBIGUOUS_REPLY);
            replies.add(AMBIGUOUS_REPLY);
            analysis.start(VIDEO, ADMIN);
            approveAmbiguousAsManual();
            assertThat(admin.uploadTranscript(VIDEO, new TranscriptRequest("SRT", SRT_C0_REVISED), ADMIN).version()).isEqualTo(2);

            analysis.start(VIDEO, ADMIN);

            YtOpinion now = currentRow("동명회사");
            assertThat(now.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.APPROVED);
            assertThat(now.getStockCode()).isEqualTo("111111");
            assertThat(admin.reviewQueue()).isEmpty();
        }

        @Test
        @DisplayName("거절한 발언이 같은 내용으로 다시 나오면 거절 유지 — 화면에 안 나간다")
        void identicalRejectedStaysRejected() {
            registerWithTranscript(VIDEO);
            replies.add(REPLY);
            replies.add(REPLY);
            analysis.start(VIDEO, ADMIN);
            admin.review(currentRow("005930", "테스트운영자").getId(), new ReviewRequest("REJECT", null), ADMIN);

            analysis.start(VIDEO, ADMIN);

            YtOpinion now = currentRow("005930", "테스트운영자");
            assertThat(now.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.REJECTED);
            assertThat(now.getReviewReasons()).contains("REVIEW_CARRIED");
            assertThat(admin.reviewQueue()).isEmpty();
            assertThat(query.stockView("005930").summary().totalStatements()).isEqualTo(1);
        }

        @Test
        @DisplayName("거절한 발언이 바뀌어 다시 나오면 자동 공개하지 않고 검토 목록으로")
        void changedRejectedGoesBackToReview() {
            assertThat(REPLY_HOST_SUMMARY_CHANGED).isNotEqualTo(REPLY);
            registerWithTranscript(VIDEO);
            replies.add(REPLY);
            replies.add(REPLY_HOST_SUMMARY_CHANGED);
            analysis.start(VIDEO, ADMIN);
            admin.review(currentRow("005930", "테스트운영자").getId(), new ReviewRequest("REJECT", null), ADMIN);

            analysis.start(VIDEO, ADMIN);

            YtOpinion now = currentRow("005930", "테스트운영자");
            assertThat(now.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            assertThat(now.getReviewReasons()).contains("PREVIOUSLY_REJECTED");
            assertThat(admin.reviewQueue()).extracting(ReviewRowDto::id).containsExactly(now.getId());
            assertThat(query.stockView("005930").summary().totalStatements()).as("출연자 발언만").isEqualTo(1);
        }

        /** 운영자 삼성전자 발언(자동 통과)을 관리자가 명시적으로 승인한 상태까지. */
        YtOpinion approveHostSamsung() {
            YtOpinion row = currentRow("005930", "테스트운영자");
            assertThat(row.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.AUTO_OK);
            admin.review(row.getId(), new ReviewRequest("APPROVE", null), ADMIN);
            return opinionRepo.findById(row.getId()).orElseThrow();
        }

        /** 승인한 운영자 발언의 한 항목만 바꾼 재분석 — 바뀐 발언은 이번 검증에 걸릴 게 없어도(자동 통과감이어도) 검토로. */
        void assertApprovedChangeGoesToReview(String changedReply, String stockCodeNow) {
            assertThat(changedReply).isNotEqualTo(REPLY);
            registerWithTranscript(VIDEO);
            replies.add(REPLY);
            replies.add(changedReply);
            analysis.start(VIDEO, ADMIN);
            YtOpinion approved = approveHostSamsung();

            analysis.start(VIDEO, ADMIN);

            YtOpinion now = currentRow(stockCodeNow, "테스트운영자");
            assertThat(now.getId()).isNotEqualTo(approved.getId());
            assertThat(now.getReviewStatus()).as("사람이 승인한 발언이 바뀌면 자동 통과가 아니라 검토").isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            assertThat(now.getReviewReasons()).contains("REVIEW_NOT_CARRIED");
            assertThat(now.getReviewedBy()).isNull();
            assertThat(admin.reviewQueue()).extracting(ReviewRowDto::id).contains(now.getId());
            assertThat(query.stockView(stockCodeNow).opinions()).as("검토 전에는 화면·집계에 안 나간다")
                    .noneMatch(o -> "테스트운영자".equals(o.speakerName()));
            assertThat(opinionRepo.findById(approved.getId()).orElseThrow().getReviewStatus())
                    .as("이전 승인은 이력으로 남는다").isEqualTo(YtOpinion.ReviewStatus.APPROVED);
        }

        @Test
        @DisplayName("승인한 발언의 의견(방향)이 바뀌면 NEEDS_REVIEW — 새 검증에 걸릴 게 없어도")
        void approvedStanceChangeGoesToReview() {
            assertApprovedChangeGoesToReview(REPLY.replace("\"stockCode\":\"005930\",\"statementType\":\"CURRENT_VIEW\",\"stance\":\"POSITIVE\"",
                    "\"stockCode\":\"005930\",\"statementType\":\"CURRENT_VIEW\",\"stance\":\"NEGATIVE\""), "005930");
        }

        @Test
        @DisplayName("승인한 발언에 조건이 붙으면 NEEDS_REVIEW")
        void approvedConditionChangeGoesToReview() {
            assertApprovedChangeGoesToReview(REPLY.replace("\"stockCode\":\"005930\",\"statementType\":\"CURRENT_VIEW\",\"stance\":\"POSITIVE\"",
                    "\"stockCode\":\"005930\",\"statementType\":\"CURRENT_VIEW\",\"stance\":\"CONDITIONAL\",\"conditions\":\"실적 발표 뒤\""), "005930");
        }

        @Test
        @DisplayName("승인한 발언의 근거 발췌가 바뀌면 NEEDS_REVIEW")
        void approvedEvidenceChangeGoesToReview() {
            assertApprovedChangeGoesToReview(REPLY.replace("\"evidenceQuote\":\"삼성전자는 지금 사도 좋다고 봅니다\"",
                    "\"evidenceQuote\":\"실적이 좋아요\""), "005930");
        }

        @Test
        @DisplayName("승인한 발언의 종목이 바뀌면(발언 키가 달라져도) NEEDS_REVIEW — 같은 구간을 사람이 본 적이 있다")
        void approvedStockChangeGoesToReview() {
            assertApprovedChangeGoesToReview(REPLY.replace("{\"stockName\":\"삼성전자\",\"stockCode\":\"005930\"",
                    "{\"stockName\":\"SK하이닉스\",\"stockCode\":\"000660\""), "000660");
        }

        @Test
        @DisplayName("검토를 받은 적 없는 발언은 바뀌어도 새 발언과 같은 규칙 — 걸릴 게 없으면 자동 통과")
        void unreviewedChangedStatementFollowsAutoRules() {
            registerWithTranscript(VIDEO);
            replies.add(REPLY);
            replies.add(REPLY.replace("\"stockCode\":\"005930\",\"statementType\":\"CURRENT_VIEW\",\"stance\":\"POSITIVE\"",
                    "\"stockCode\":\"005930\",\"statementType\":\"CURRENT_VIEW\",\"stance\":\"NEGATIVE\""));
            analysis.start(VIDEO, ADMIN);

            analysis.start(VIDEO, ADMIN);

            YtOpinion now = currentRow("005930", "테스트운영자");
            assertThat(now.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.AUTO_OK);
            assertThat(now.getReviewReasons()).isNull();
            assertThat(now.getStance()).isEqualTo(YtOpinion.Stance.NEGATIVE);
        }

        @Test
        @DisplayName("사람이 본 구간과 겹치지 않는 새 발언은 자동 통과 — 승인한 발언은 그대로 잇는다")
        void newStatementOutsideReviewedSpanIsAutoOk() {
            registerWithTranscript(VIDEO);
            replies.add(AMBIGUOUS_REPLY);
            replies.add(REPLY_WITH_AMBIGUOUS);
            analysis.start(VIDEO, ADMIN);
            approveAmbiguousAsManual();

            analysis.start(VIDEO, ADMIN);

            assertThat(currentRow("005930", "테스트운영자").getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.AUTO_OK);
            assertThat(currentRow("005930", "테스트출연자").getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.AUTO_OK);
            assertThat(currentRow("000660", "테스트출연자").getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.AUTO_OK);
            assertThat(currentRow("동명회사").getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.APPROVED);
            assertThat(admin.reviewQueue()).isEmpty();
        }

        @Test
        @DisplayName("거절한 발언을 모델이 다른 시작 줄로 다시 잡아도(발언 키가 달라도) 자동 공개하지 않는다")
        void rejectedStatementWithDriftedStartCueIsNotAutoPublished() {
            register(VIDEO, null, "테스트운영자", "테스트출연자");
            admin.uploadTranscript(VIDEO, new TranscriptRequest("SRT", SRT_SPAN), ADMIN);
            replies.add(SPAN_REPLY_FROM_C1);
            replies.add(SPAN_REPLY_FROM_C2);
            analysis.start(VIDEO, ADMIN);
            YtOpinion rejected = currentRow("005930", "테스트운영자");
            assertThat(rejected.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.AUTO_OK);
            admin.review(rejected.getId(), new ReviewRequest("REJECT", null), ADMIN);

            analysis.start(VIDEO, ADMIN);

            YtOpinion now = currentRow("005930", "테스트운영자");
            assertThat(now.getStatementKey()).as("시작 줄이 달라 키는 다르다").isNotEqualTo(rejected.getStatementKey());
            assertThat(now.getReviewStatus()).isEqualTo(YtOpinion.ReviewStatus.NEEDS_REVIEW);
            assertThat(now.getReviewReasons()).contains("PREVIOUSLY_REJECTED");
            assertThat(query.stockView("005930").status()).isNotEqualTo(YoutubeOpinionQueryService.HAS_OPINIONS);
        }
    }

    // ------------------------------------------------------------------ 가드

    @Nested
    @DisplayName("분석 시작 가드 — 비용·동시성·보호 시간대")
    class StartGuards {

        @Test
        void geminiUnavailable() {
            registerWithTranscript(VIDEO);
            when(gemini.isAvailable()).thenReturn(false);
            assertThatThrownBy(() -> analysis.start(VIDEO, ADMIN)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Gemini");
            assertThat(runRepo.count()).isZero();
        }

        @Test
        void anotherRunInProgress() {
            registerWithTranscript(VIDEO);
            runRepo.save(YtAnalysisRun.builder().videoId(VIDEO).transcriptId(1L).model("m").promptVersion("v")
                    .status(YtAnalysisRun.Status.RUNNING).chunkCount(1).statementCount(0).droppedCount(0)
                    .startedAt(DateTimeUtil.kstNow()).build());
            assertThatThrownBy(() -> analysis.start(VIDEO, ADMIN)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("진행 중");
        }

        @Test
        void noTranscript() {
            register(VIDEO, null, "테스트운영자");
            assertThatThrownBy(() -> analysis.start(VIDEO, ADMIN)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("자막이 없습니다");
        }

        @Test
        @DisplayName("일일 호출 상한 — 오늘 쓴 구간 수 + 이번 요청이 넘으면 거절(Gemini 를 부르지 않는다)")
        void dailyLimit() {
            useSettings(settings(true, 20, 1, ""));
            registerWithTranscript(VIDEO);
            analysis.start(VIDEO, ADMIN);
            assertThatThrownBy(() -> analysis.start(VIDEO, ADMIN)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("호출 상한");
            assertThat(geminiCalls).isEqualTo(1);
        }

        @Test
        @DisplayName("한 실행의 구간 상한을 넘는 긴 자막은 거절 — 일부만 분석해 '전체'인 척하지 않는다")
        void chunkLimit() {
            useSettings(settings(true, 1, 60, ""));
            register(VIDEO, null, "테스트운영자");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 80; i++) {
                text.append(String.format("[%02d:%02d] ", i / 60, i % 60)).append("긴 발언 ".repeat(15)).append('\n');
            }
            admin.uploadTranscript(VIDEO, new TranscriptRequest("TEXT", text.toString()), ADMIN);
            assertThatThrownBy(() -> analysis.start(VIDEO, ADMIN)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("상한");
            assertThat(geminiCalls).isZero();
        }

        @Test
        @DisplayName("재료 워밍 보호 시간대에는 시작하지 않는다")
        void quietWindow() {
            LocalDateTime now = DateTimeUtil.kstNow();
            DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
            useSettings(settings(true, 20, 60, now.minusHours(1).format(hm) + "-" + now.plusHours(1).format(hm)));
            registerWithTranscript(VIDEO);
            assertThatThrownBy(() -> analysis.start(VIDEO, ADMIN)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("보호 시간대");
        }

        @Test
        @DisplayName("분석 중에는 자막을 바꿀 수 없다")
        void transcriptLockedWhileAnalyzing() {
            registerWithTranscript(VIDEO);
            YtVideo v = video(VIDEO);
            v.setStatus(YtVideo.Status.ANALYZING);
            videoRepo.save(v);
            assertThatThrownBy(() -> admin.uploadTranscript(VIDEO, new TranscriptRequest("SRT", SRT + "\n"), ADMIN))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("분석 중");
        }
    }

    @Nested
    @DisplayName("등록 입력 검증 — 허용 채널·주소·시각·출처만")
    class RegisterValidation {

        RegisterRequest req(String url, String channel, String publishedAt, String source, String duplicateOf) {
            return new RegisterRequest(url, "제목", channel, publishedAt, source, duplicateOf,
                    List.of(new ParticipantInput("테스트운영자", "HOST")));
        }

        @Test
        void rejectsBadInput() {
            String ok = "https://www.youtube.com/watch?v=" + VIDEO;
            assertThatThrownBy(() -> admin.register(req("https://example.com/watch?v=" + VIDEO, CHANNEL, hoursAgo(1), "출처 메모", null), ADMIN))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("YouTube");
            assertThatThrownBy(() -> admin.register(req(ok, "UCnotConfigured", hoursAgo(1), "출처 메모", null), ADMIN))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("허용 채널");
            assertThatThrownBy(() -> admin.register(req(ok, CHANNEL, hoursAgo(-2), "출처 메모", null), ADMIN))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("미래");
            assertThatThrownBy(() -> admin.register(req(ok, CHANNEL, hoursAgo(1), " ", null), ADMIN))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("출처");
            assertThatThrownBy(() -> admin.register(req(ok, CHANNEL, hoursAgo(1), "출처 메모", VIDEO_2), ADMIN))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("먼저 등록");
            assertThatThrownBy(() -> admin.register(new RegisterRequest(ok, "제목", CHANNEL, hoursAgo(1), "출처 메모", null,
                    List.of(new ParticipantInput("테스트운영자", "HOST"), new ParticipantInput("테스트 운영자", "GUEST"))), ADMIN))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("두 번");
            assertThat(videoRepo.count()).isZero();
        }
    }

    // ------------------------------------------------------------------ 꺼짐 · 조회 실패

    @Test
    @DisplayName("기능이 꺼져 있으면 조회는 enabled=false, 관리 작업은 거절 — 기존 화면은 그대로")
    void disabled() {
        useSettings(settings(false, 20, 60, ""));

        assertThat(query.stockView("005930").enabled()).isFalse();
        assertThat(query.summary(List.of("005930")).enabled()).isFalse();
        assertThatThrownBy(() -> register(VIDEO, null, "테스트운영자")).isInstanceOf(FeatureDisabledException.class);
        assertThatThrownBy(() -> analysis.start(VIDEO, ADMIN)).isInstanceOf(FeatureDisabledException.class);
    }

    @Test
    @DisplayName("저장소 조회가 실패해도 예외 대신 dataAvailable=false — 종목 상세·매수 후보 화면을 막지 않는다")
    void queryFailureDoesNotBlockScreens() {
        YtVideoRepository broken = mock(YtVideoRepository.class);
        when(broken.findByPublishedAtGreaterThanEqual(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("DB 끊김"));
        YoutubeOpinionQueryService q = new YoutubeOpinionQueryService(settings(true, 20, 60, ""), broken, opinionRepo, personRepo, runRepo);

        StockViewDto v = q.stockView("005930");
        SummaryViewDto s = q.summary(List.of("005930"));

        assertThat(v.enabled()).isTrue();
        assertThat(v.dataAvailable()).isFalse();
        assertThat(v.status()).isNull();
        assertThat(s.dataAvailable()).isFalse();
    }

    @Test
    @DisplayName("조회 입력 검증 — 종목코드 형식, 요약은 최대 20종목")
    void queryInputValidation() {
        assertThatThrownBy(() -> query.stockView("../etc")).isInstanceOf(IllegalArgumentException.class);
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) many.add(String.format("%06d", i));
        assertThatThrownBy(() -> query.summary(many)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20");
    }

    // ------------------------------------------------------------------ HTTP — 화면이 읽는 응답 모양 그대로

    /** REPLY 세 발언 + 동명회사(검토 대상) — 한 구간 안. */
    static final String REPLY_WITH_AMBIGUOUS = REPLY.trim().replaceFirst("]$", ","
            + AMBIGUOUS_REPLY.trim().substring(1));

    /** 위와 같되 동명회사 발언만 의견이 바뀐 재분석. */
    static final String REPLY_WITH_AMBIGUOUS_CHANGED = REPLY.trim().replaceFirst("]$", ","
            + AMBIGUOUS_CHANGED_REPLY.trim().substring(1));

    static final org.springframework.security.core.Authentication ADMIN_AUTH =
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(ADMIN, "n/a", List.of());

    /**
     * 관리자 등록 → 자막 → 분석(진행 중·성공·실패) → 검토(승인·거절) → 종목 상세 → '오늘' 후보 요약 → 재분석(실패·같은 내용·바뀐 내용)을
     * <b>실제 컨트롤러</b>로 돌려, 화면(관리자 패널·종목 상세·'오늘' 후보)이 읽는 필드가 그대로 오는지 본다. JSON 설정은 운영과 같게
     * null 을 생략한다(application.yml {@code default-property-inclusion: non_null}) — 화면은 없는 필드와 null 을 같게 다뤄야 한다.
     * 권한은 {@link YoutubeOpinionSecurityTest} 가 실제 필터 체인으로 따로 고정한다.
     *
     * <p>{@code -Dyt.fixtureDir=경로} 를 주면 각 응답을 파일로 남긴다 — 로컬 화면 확인용(CI 에선 쓰지 않는다).
     */
    @Nested
    @DisplayName("HTTP — 관리자 흐름부터 종목 상세·'오늘' 후보 표시까지, 화면이 읽는 응답 모양 그대로")
    class HttpFlow {

        org.springframework.test.web.servlet.MockMvc mvc;

        @BeforeEach
        void build() {
            tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder()
                    .changeDefaultPropertyInclusion(v -> v.withValueInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL))
                    .build();
            mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                    .standaloneSetup(new YoutubeOpinionAdminController(admin, analysis), new YoutubeOpinionController(query))
                    .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(mapper))
                    .defaultResponseCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8)   // 운영(Boot)과 같게 — 없으면 한글을 Latin-1 로 읽는다
                    .build();
        }

        org.springframework.test.web.servlet.ResultActions call(String fixture,
                org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
            org.springframework.test.web.servlet.ResultActions r = mvc.perform(req.principal(ADMIN_AUTH));
            String dir = System.getProperty("yt.fixtureDir");
            if (dir != null && fixture != null) {
                java.nio.file.Path out = java.nio.file.Path.of(dir, fixture + ".json");
                java.nio.file.Files.createDirectories(out.getParent());
                java.nio.file.Files.writeString(out, r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            }
            return r;
        }

        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder get(String url) {
            return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
        }

        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(String url, String json) {
            return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).characterEncoding("UTF-8")
                    .content(json == null ? "" : json);
        }

        static org.springframework.test.web.servlet.ResultMatcher json(String path, Object value) {
            return org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(path).value(value);
        }

        static org.springframework.test.web.servlet.ResultMatcher status(int code) {
            return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(code);
        }

        static String jsonString(String s) throws Exception {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(s);
        }

        static final String BASE = "/api/admin/youtube-opinions";

        @Test
        @DisplayName("등록 → 자막 → 분석 중 → 성공 → 검토 → 종목 상세·요약 → 재분석 실패·같은 내용·바뀐 내용")
        void endToEnd() throws Exception {
            // ① 설정 — 켜짐·허용 채널·Gemini·자동 수집 미연결
            call("config", get(BASE + "/config")).andExpect(status(200))
                    .andExpect(json("$.success", true)).andExpect(json("$.data.enabled", true))
                    .andExpect(json("$.data.autoCollection", false)).andExpect(json("$.data.geminiConfigured", true))
                    .andExpect(json("$.data.channels[0].id", CHANNEL));
            call("stock-none", get("/api/youtube-opinions/stocks/005930")).andExpect(status(200))
                    .andExpect(json("$.data.status", YoutubeOpinionQueryService.NO_ANALYZED_VIDEOS))
                    .andExpect(json("$.data.dataAvailable", true));

            // ② 영상·출연자·출처 등록 — 다른 주소 형식으로 다시 등록해도 한 건
            String reg = "{\"url\":\"https://www.youtube.com/watch?v=" + VIDEO + "\",\"title\":\"테스트 영상\",\"channelId\":\"" + CHANNEL
                    + "\",\"publishedAt\":\"" + hoursAgo(5) + "\",\"sourceNote\":\"테스트 전용 자막 파일\","
                    + "\"participants\":[{\"name\":\"테스트운영자\",\"role\":\"HOST\"},{\"name\":\"테스트출연자\",\"role\":\"GUEST\"}]}";
            call("register", post(BASE + "/videos", reg)).andExpect(status(200))
                    .andExpect(json("$.data.alreadyRegistered", false)).andExpect(json("$.data.video.status", "REGISTERED"))
                    .andExpect(json("$.data.video.participants.length()", 2))
                    .andExpect(json("$.data.video.sourceNote", "테스트 전용 자막 파일"));
            call(null, post(BASE + "/videos", reg.replace("https://www.youtube.com/watch?v=", "https://youtu.be/")))
                    .andExpect(status(200)).andExpect(json("$.data.alreadyRegistered", true));
            call("videos-registered", get(BASE + "/videos")).andExpect(json("$.data.length()", 1))
                    .andExpect(json("$.data[0].transcriptVersions", 0));
            call("stock-no-transcript", get("/api/youtube-opinions/stocks/005930"))
                    .andExpect(json("$.data.coverage.noTranscriptVideos", 1));

            // ③ 타임스탬프 자막 — 같은 자막은 새 버전 없음
            String transcript = "{\"format\":\"SRT\",\"content\":" + jsonString(SRT) + "}";
            call("transcript", post(BASE + "/videos/" + VIDEO + "/transcript", transcript)).andExpect(status(200))
                    .andExpect(json("$.data.version", 1)).andExpect(json("$.data.cueCount", 5))
                    .andExpect(json("$.data.labeledCues", 5)).andExpect(json("$.data.duplicate", false));
            call(null, post(BASE + "/videos/" + VIDEO + "/transcript", transcript)).andExpect(json("$.data.duplicate", true));
            call("stock-awaiting", get("/api/youtube-opinions/stocks/005930"))
                    .andExpect(json("$.data.coverage.awaitingAnalysisVideos", 1));

            // ④ 분석 — 진행 중 상태가 보이고, 그동안 다른 분석·자막 교체는 409
            List<Runnable> held = new ArrayList<>();
            analysis.useExecutor(held::add);
            replies.add(REPLY_WITH_AMBIGUOUS);
            call("analyze", post(BASE + "/videos/" + VIDEO + "/analyze", null)).andExpect(status(202))
                    .andExpect(json("$.data.chunkCount", 1));
            call("videos-analyzing", get(BASE + "/videos")).andExpect(json("$.data[0].status", "ANALYZING"));
            call("runs-running", get(BASE + "/videos/" + VIDEO + "/runs")).andExpect(json("$.data[0].status", "RUNNING"));
            call("stock-analyzing", get("/api/youtube-opinions/stocks/005930"))
                    .andExpect(json("$.data.coverage.analyzingVideos", 1))
                    .andExpect(json("$.data.status", YoutubeOpinionQueryService.NO_ANALYZED_VIDEOS));
            call(null, post(BASE + "/videos/" + VIDEO + "/analyze", null)).andExpect(status(409))
                    .andExpect(json("$.success", false));
            call(null, post(BASE + "/videos/" + VIDEO + "/transcript", transcript.replace("반도체", "메모리")))
                    .andExpect(status(409));
            held.remove(0).run();
            analysis.useExecutor(Runnable::run);

            call("videos-analyzed", get(BASE + "/videos")).andExpect(json("$.data[0].status", "ANALYZED"));
            call("runs-1", get(BASE + "/videos/" + VIDEO + "/runs")).andExpect(json("$.data[0].status", "SUCCEEDED"))
                    .andExpect(json("$.data[0].current", true)).andExpect(json("$.data[0].statementCount", 4));

            // ⑤ 검토 — 동명 종목은 코드 없이 승인 불가, 코드로 승인. 조건부 발언 하나는 거절.
            call("review-1", get(BASE + "/review")).andExpect(json("$.data.length()", 1))
                    .andExpect(json("$.data[0].mappingStatus", "AMBIGUOUS"))
                    .andExpect(json("$.data[0].reviewReasons", "STOCK_AMBIGUOUS"))
                    .andExpect(json("$.data[0].sourceUrl", "https://www.youtube.com/watch?v=" + VIDEO + "&t=90s"));
            long ambiguousId = opinionRepo.findByRunId(video(VIDEO).getCurrentRunId()).stream()
                    .filter(o -> "동명회사".equals(o.getStockNameRaw())).findFirst().orElseThrow().getId();
            call(null, post(BASE + "/opinions/" + ambiguousId + "/review", "{\"decision\":\"APPROVE\"}")).andExpect(status(400))
                    .andExpect(json("$.success", false));
            call("review-approved", post(BASE + "/opinions/" + ambiguousId + "/review", "{\"decision\":\"APPROVE\",\"stockCode\":\"111111\"}"))
                    .andExpect(status(200)).andExpect(json("$.data.reviewStatus", "APPROVED"))
                    .andExpect(json("$.data.mappingStatus", "MANUAL"));
            long hynixId = currentRow("000660", "테스트출연자").getId();
            call(null, post(BASE + "/opinions/" + hynixId + "/review", "{\"decision\":\"REJECT\"}")).andExpect(status(200))
                    .andExpect(json("$.data.reviewStatus", "REJECTED"));
            call("review-empty", get(BASE + "/review")).andExpect(json("$.data.length()", 0));

            // ⑥ 종목 상세 — 인물별·발언별, 원문 링크는 발언 시점으로
            call("stock-has", get("/api/youtube-opinions/stocks/005930"))
                    .andExpect(json("$.data.status", YoutubeOpinionQueryService.HAS_OPINIONS))
                    .andExpect(json("$.data.summary.personCounts.POSITIVE", 2))
                    .andExpect(json("$.data.persons.length()", 2))
                    // 같은 영상 안에서는 발언 시각 순 — 운영자(01:05) 다음 출연자(01:20)
                    .andExpect(json("$.data.opinions[0].speakerName", "테스트운영자"))
                    .andExpect(json("$.data.opinions[0].sourceUrl", "https://www.youtube.com/watch?v=" + VIDEO + "&t=65s"))
                    .andExpect(json("$.data.opinions[0].startLabel", "01:05"))
                    .andExpect(json("$.data.opinions[1].sourceUrl", "https://www.youtube.com/watch?v=" + VIDEO + "&t=80s"));
            call("stock-manual", get("/api/youtube-opinions/stocks/111111"))
                    .andExpect(json("$.data.status", YoutubeOpinionQueryService.HAS_OPINIONS));
            call("stock-no-opinion", get("/api/youtube-opinions/stocks/000660"))
                    .andExpect(json("$.data.status", YoutubeOpinionQueryService.NO_OPINION));

            // ⑦ '오늘' 후보 요약 — 한 번에
            call("summary", get("/api/youtube-opinions/summary?codes=005930,000660,066570,111111"))
                    .andExpect(json("$.data.items['005930'].status", YoutubeOpinionQueryService.HAS_OPINIONS))
                    .andExpect(json("$.data.items['000660'].status", YoutubeOpinionQueryService.NO_OPINION))
                    .andExpect(json("$.data.items['066570'].status", YoutubeOpinionQueryService.NO_OPINION))
                    .andExpect(json("$.data.items['111111'].status", YoutubeOpinionQueryService.HAS_OPINIONS));

            // ⑧ 재분석 실패 — 이전 결과는 그대로 보이고 실패 사유가 남는다
            replies.add(null);
            call(null, post(BASE + "/videos/" + VIDEO + "/analyze", null)).andExpect(status(202));
            call("videos-failed", get(BASE + "/videos")).andExpect(json("$.data[0].status", "FAILED"))
                    .andExpect(json("$.data[0].lastError", "구간 1/1: Gemini 응답 없음(키·쿼터·일시 오류)"));
            call("runs-failed", get(BASE + "/videos/" + VIDEO + "/runs")).andExpect(json("$.data[0].status", "FAILED"))
                    .andExpect(json("$.data[0].current", false)).andExpect(json("$.data[1].current", true));
            call("stock-after-failure", get("/api/youtube-opinions/stocks/005930"))
                    .andExpect(json("$.data.status", YoutubeOpinionQueryService.HAS_OPINIONS))
                    .andExpect(json("$.data.coverage.failedVideos", 1));

            // ⑨ 재분석 성공(같은 내용) — 승인·거절이 이어져 다시 검토할 것이 없다
            replies.add(REPLY_WITH_AMBIGUOUS);
            call(null, post(BASE + "/videos/" + VIDEO + "/analyze", null)).andExpect(status(202));
            call(null, get(BASE + "/review")).andExpect(json("$.data.length()", 0));
            call(null, get("/api/youtube-opinions/stocks/111111")).andExpect(json("$.data.status", YoutubeOpinionQueryService.HAS_OPINIONS));
            call(null, get("/api/youtube-opinions/stocks/000660")).andExpect(json("$.data.status", YoutubeOpinionQueryService.NO_OPINION));

            // ⑩ 재분석 성공(바뀐 내용) — 승인을 잇지 않고 다시 검토 목록으로, 이유가 보인다
            replies.add(REPLY_WITH_AMBIGUOUS_CHANGED);
            call(null, post(BASE + "/videos/" + VIDEO + "/analyze", null)).andExpect(status(202));
            call("review-not-carried", get(BASE + "/review")).andExpect(json("$.data.length()", 1))
                    .andExpect(json("$.data[0].reviewReasons", "REVIEW_NOT_CARRIED,STOCK_AMBIGUOUS"))
                    .andExpect(json("$.data[0].stance", "NEGATIVE"));
            call(null, get("/api/youtube-opinions/stocks/111111"))
                    .andExpect(json("$.data.status", YoutubeOpinionQueryService.NO_OPINION));
            call("runs-history", get(BASE + "/videos/" + VIDEO + "/runs")).andExpect(json("$.data.length()", 4));
        }

        @Test
        @DisplayName("기능이 꺼져 있으면 조회는 enabled=false, 관리 작업은 503 — 화면이 이유를 보여 줄 수 있게")
        void disabled() throws Exception {
            useSettings(settings(false, 20, 60, ""));
            build();
            call("config-disabled", get(BASE + "/config")).andExpect(status(200)).andExpect(json("$.data.enabled", false));
            call("stock-disabled", get("/api/youtube-opinions/stocks/005930")).andExpect(json("$.data.enabled", false));
            call(null, get(BASE + "/review")).andExpect(status(503)).andExpect(json("$.success", false));
        }
    }
}
