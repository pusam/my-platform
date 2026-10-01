package com.myplatform.backend.youtubeopinion;

import com.myplatform.backend.entity.StockMaster;
import com.myplatform.backend.service.GeminiService;
import com.myplatform.backend.service.StockMasterService;
import com.myplatform.backend.util.StockCodeFormat;
import com.myplatform.core.util.DateTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.myplatform.backend.youtubeopinion.YoutubeOpinionDtos.*;

/**
 * 유튜브 의견 관리 — 영상 등록·자막 등록·검토. 관리자 전용(SecurityConfig {@code /api/admin/**} → ADMIN).
 *
 * <p><b>서버는 영상 페이지·자막을 가져오지 않는다</b> — 공개 영상 자막은 YouTube Data API 로 받을 수 없다
 * (captions.download 는 영상 편집 권한 필요). 관리자가 출처가 있는 타임스탬프 자막을 등록하는 것이 현재 유일한 경로이고,
 * 자동 수집 제공자를 나중에 붙이더라도 {@link #uploadTranscript} 와 같은 적재 경로로 들어오게 한다.
 */
@Service
@Slf4j
public class YoutubeOpinionAdminService {

    static final int MAX_PARTICIPANTS = 10;
    static final int MAX_UPLOADS_PER_HOUR = 30;
    static final int REVIEW_PAGE = 100;
    private static final LocalDateTime EARLIEST_PUBLISHED = LocalDateTime.of(2005, 1, 1, 0, 0);

    private final YoutubeOpinionSettings settings;
    private final YtVideoRepository videoRepo;
    private final YtPersonRepository personRepo;
    private final YtVideoParticipantRepository participantRepo;
    private final YtTranscriptRepository transcriptRepo;
    private final YtAnalysisRunRepository runRepo;
    private final YtOpinionRepository opinionRepo;
    private final StockMasterService stockMasterService;
    private final ObjectProvider<GeminiService> geminiProvider;
    private final HourlyThroughputLimit uploadLimit;

    public YoutubeOpinionAdminService(YoutubeOpinionSettings settings, YtVideoRepository videoRepo,
                                      YtPersonRepository personRepo, YtVideoParticipantRepository participantRepo,
                                      YtTranscriptRepository transcriptRepo, YtAnalysisRunRepository runRepo,
                                      YtOpinionRepository opinionRepo, StockMasterService stockMasterService,
                                      ObjectProvider<GeminiService> geminiProvider) {
        this.settings = settings;
        this.videoRepo = videoRepo;
        this.personRepo = personRepo;
        this.participantRepo = participantRepo;
        this.transcriptRepo = transcriptRepo;
        this.runRepo = runRepo;
        this.opinionRepo = opinionRepo;
        this.stockMasterService = stockMasterService;
        this.geminiProvider = geminiProvider;
        this.uploadLimit = new HourlyThroughputLimit(MAX_UPLOADS_PER_HOUR, Clock.systemUTC());
    }

    void requireEnabled() {
        if (!settings.isEnabled()) throw new FeatureDisabledException();
    }

    // ------------------------------------------------------------------ 영상 등록

    /** 같은 영상 ID 가 이미 있으면 그대로 돌려준다(새 행·변경 없음). */
    @Transactional
    public RegisterResult register(RegisterRequest req, String admin) {
        requireEnabled();
        if (req == null) throw new IllegalArgumentException("요청 본문이 없습니다.");
        String videoId = YoutubeUrlParser.extractVideoId(req.url())
                .orElseThrow(() -> new IllegalArgumentException(
                        "YouTube 영상 주소가 아닙니다 — youtube.com/watch?v=…, youtu.be/…, /shorts/…, /live/… 형식만 받습니다."));
        Optional<YtVideo> existing = videoRepo.findByVideoId(videoId);
        if (existing.isPresent()) return new RegisterResult(true, videoRow(existing.get()));

        String title = requireText(req.title(), "제목", 1, 300);
        YoutubeOpinionSettings.Channel channel = settings.channel(req.channelId() == null ? "" : req.channelId().trim())
                .orElseThrow(() -> new IllegalArgumentException("등록 허용 채널(설정 youtube-opinion.channels)이 아닙니다."));
        LocalDateTime publishedAt = parsePublishedAt(req.publishedAt());
        String sourceNote = requireText(req.sourceNote(), "자막 출처", 3, 300);
        String duplicateOf = resolveDuplicateOf(req.duplicateOf(), videoId);
        List<ParticipantInput> inputs = req.participants() == null ? List.of() : req.participants();
        if (inputs.size() > MAX_PARTICIPANTS) throw new IllegalArgumentException("출연자는 최대 " + MAX_PARTICIPANTS + "명입니다.");
        // 쓰기 전에 전부 검증한다 — 중간에 거절돼도 반쯤 등록된 영상이 남지 않게(트랜잭션 롤백에만 기대지 않는다)
        List<ValidParticipant> participants = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ParticipantInput in : inputs) {
            String name = requireText(in == null ? null : in.name(), "출연자 이름", 1, 50);
            YtVideoParticipant.Role role = parseRole(in.role());
            String key = NameKeys.key(name);
            if (key == null || key.length() > 50) throw new IllegalArgumentException("출연자 이름을 확인하세요: " + name);
            if (!seen.add(key)) throw new IllegalArgumentException("같은 출연자가 두 번 들어 있습니다: " + name);
            participants.add(new ValidParticipant(name, key, role));
        }

        YtVideo video = videoRepo.save(YtVideo.builder()
                .videoId(videoId).title(title).channelId(channel.id()).channelName(channel.name())
                .publishedAt(publishedAt).sourceNote(sourceNote).duplicateOf(duplicateOf)
                .status(YtVideo.Status.REGISTERED).registeredBy(admin).build());
        for (ValidParticipant p : participants) {
            YtPerson person = personRepo.findByNameKey(p.key())
                    .orElseGet(() -> personRepo.save(YtPerson.builder().displayName(p.name()).nameKey(p.key()).build()));
            participantRepo.save(YtVideoParticipant.builder().videoId(videoId).personId(person.getId()).role(p.role()).build());
        }
        log.info("[유튜브의견] 영상 등록 {} ({}), 출연자 {}명 — by {}", videoId, channel.name(), participants.size(), admin);
        return new RegisterResult(false, videoRow(video));
    }

    // ------------------------------------------------------------------ 자막 등록

    /** 같은 내용(정규화 해시)이면 새 버전을 만들지 않는다. 분석 중에는 바꿀 수 없다. */
    @Transactional
    public TranscriptResult uploadTranscript(String videoId, TranscriptRequest req, String admin) {
        requireEnabled();
        if (!YoutubeUrlParser.isValidVideoId(videoId)) throw new IllegalArgumentException("영상 ID 형식이 아닙니다.");
        if (req == null) throw new IllegalArgumentException("요청 본문이 없습니다.");
        if (!uploadLimit.tryAcquire()) {
            throw new TooManyRequestsException("자막 등록은 한 시간에 " + uploadLimit.maxPerHour() + "건까지입니다.");
        }
        YtVideo video = videoRepo.findByVideoId(videoId)
                .orElseThrow(() -> new NoSuchElementException("등록되지 않은 영상입니다: " + videoId));
        if (video.getStatus() == YtVideo.Status.ANALYZING) {
            throw new IllegalStateException("분석 중에는 자막을 바꿀 수 없습니다 — 분석이 끝난 뒤 다시 등록하세요.");
        }
        YtTranscript.Format declared = parseFormat(req.format());
        TranscriptParser.Parsed parsed = TranscriptParser.parse(req.content(), declared);
        List<String> names = participantNames(videoId);
        List<TranscriptParser.Cue> cues = TranscriptParser.applyParticipants(parsed.cues(), TranscriptParser.participantKeyMap(names));
        int labeled = (int) cues.stream().filter(c -> c.speaker() != null).count();
        String json = TranscriptParser.toJson(cues);
        String sha = OpinionValidator.sha256(json);

        Optional<YtTranscript> same = transcriptRepo.findByVideoIdAndContentSha256(videoId, sha);
        if (same.isPresent()) {
            return new TranscriptResult(true, same.get().getVersion(), cues.size(), parsed.durationSec(), labeled,
                    same.get().getSourceFormat().name());
        }
        int version = transcriptRepo.findTopByVideoIdOrderByVersionDesc(videoId).map(t -> t.getVersion() + 1).orElse(1);
        transcriptRepo.save(YtTranscript.builder().videoId(videoId).version(version).sourceFormat(parsed.format())
                .contentSha256(sha).cueCount(cues.size()).durationSec(parsed.durationSec()).cuesJson(json)
                .uploadedBy(admin).build());
        // 이전 분석 결과(current_run_id)는 재분석 전까지 그대로 보인다 — 상태만 '분석 대기'로
        video.setStatus(YtVideo.Status.TRANSCRIPT_READY);
        videoRepo.save(video);
        log.info("[유튜브의견] 자막 v{} 등록 {} — 큐 {}개, 화자 표기 {}개, by {}", version, videoId, cues.size(), labeled, admin);
        return new TranscriptResult(false, version, cues.size(), parsed.durationSec(), labeled, parsed.format().name());
    }

    // ------------------------------------------------------------------ 목록·이력·설정

    public List<VideoRowDto> videos() {
        List<YtVideo> videos = videoRepo.findTop50ByOrderByCreatedAtDesc();
        if (videos.isEmpty()) return List.of();
        List<String> ids = videos.stream().map(YtVideo::getVideoId).toList();
        Map<String, Long> versions = new HashMap<>();
        for (Object[] row : transcriptRepo.countByVideoIds(ids)) versions.put((String) row[0], ((Number) row[1]).longValue());
        Map<String, List<ParticipantDto>> participants = participantsByVideo(ids);
        return videos.stream().map(v -> videoRow(v, versions.getOrDefault(v.getVideoId(), 0L),
                participants.getOrDefault(v.getVideoId(), List.of()))).toList();
    }

    public List<RunDto> runs(String videoId) {
        if (!YoutubeUrlParser.isValidVideoId(videoId)) throw new IllegalArgumentException("영상 ID 형식이 아닙니다.");
        YtVideo video = videoRepo.findByVideoId(videoId)
                .orElseThrow(() -> new NoSuchElementException("등록되지 않은 영상입니다: " + videoId));
        return runRepo.findTop20ByVideoIdOrderByStartedAtDesc(videoId).stream()
                .map(r -> new RunDto(r.getId(), r.getTranscriptId(), r.getModel(), r.getPromptVersion(), r.getStatus().name(),
                        r.getChunkCount(), r.getStatementCount(), r.getDroppedCount(), r.getError(), r.getRequestedBy(),
                        r.getStartedAt(), r.getFinishedAt(), Objects.equals(r.getId(), video.getCurrentRunId()),
                        r.getAnalyzer(), r.getRequestedModel(), r.getAttempts() == null ? 0 : r.getAttempts(),
                        r.getWaitReason(), r.getNextAttemptAt()))
                .toList();
    }

    public ConfigDto config() {
        GeminiService gemini = geminiProvider.getIfAvailable();
        long used = settings.isEnabled() ? runRepo.sumChunksSince(DateTimeUtil.kstNow().toLocalDate().atStartOfDay()) : 0;
        return new ConfigDto(settings.isEnabled(), false, settings.getChannels(), gemini != null && gemini.isAvailable(),
                settings.getWindowDays(), settings.getMaxChunksPerRun(), settings.getDailyCallLimit(), used,
                settings.quietWindowLabel(), TranscriptParser.MAX_BYTES / 1024, settings.getModel(), OpinionPrompt.VERSION,
                analyzer().analyzer().name(), analyzer().claudeModel());
    }

    /** 분석기 설정 — 선택 주입(테스트에서 없으면 기본 GEMINI). 생성자를 바꾸지 않으려고 setter 로 받는다. */
    private OpinionAnalyzerSettings analyzerSettings;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAnalyzerSettings(OpinionAnalyzerSettings analyzerSettings) {
        this.analyzerSettings = analyzerSettings;
    }

    private OpinionAnalyzerSettings analyzer() {
        return analyzerSettings == null ? OpinionAnalyzerSettings.gemini() : analyzerSettings;
    }

    // ------------------------------------------------------------------ 검토

    public List<ReviewRowDto> reviewQueue() {
        requireEnabled();
        List<YtOpinion> rows = opinionRepo.findCurrentByReviewStatus(YtOpinion.ReviewStatus.NEEDS_REVIEW,
                PageRequest.of(0, REVIEW_PAGE));
        Map<String, YtVideo> videos = videoRepo.findByVideoIdIn(rows.stream().map(YtOpinion::getVideoId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(YtVideo::getVideoId, Function.identity()));
        return rows.stream().map(o -> reviewRow(o, videos.get(o.getVideoId()))).toList();
    }

    /**
     * APPROVE — 종목이 확인되지 않은 발언은 관리자가 마스터에 있는 코드를 지정해야 한다(MANUAL). REJECT — 화면에서 뺀다.
     */
    @Transactional
    public ReviewRowDto review(long opinionId, ReviewRequest req, String admin) {
        requireEnabled();
        if (req == null || req.decision() == null) throw new IllegalArgumentException("decision(APPROVE/REJECT)이 필요합니다.");
        YtOpinion o = opinionRepo.findById(opinionId)
                .orElseThrow(() -> new NoSuchElementException("발언을 찾을 수 없습니다: " + opinionId));
        String decision = req.decision().trim().toUpperCase(Locale.ROOT);
        String code = req.stockCode() == null ? null : req.stockCode().trim().toUpperCase(Locale.ROOT);
        switch (decision) {
            case "REJECT" -> o.setReviewStatus(YtOpinion.ReviewStatus.REJECTED);
            case "APPROVE" -> {
                boolean needsCode = o.getStockCode() == null;
                if (needsCode || (code != null && !code.isEmpty() && !code.equals(o.getStockCode()))) {
                    if (code == null || !StockCodeFormat.isValid(code)) {
                        throw new IllegalArgumentException("승인하려면 종목 마스터에 있는 종목코드를 지정하세요.");
                    }
                    StockMaster m = stockMasterService.findByCode(code)
                            .orElseThrow(() -> new IllegalArgumentException("종목 마스터에 없는 코드입니다: " + code));
                    o.setStockCode(m.getStockCode());
                    o.setMappingStatus(YtOpinion.MappingStatus.MANUAL);
                }
                o.setReviewStatus(YtOpinion.ReviewStatus.APPROVED);
            }
            default -> throw new IllegalArgumentException("decision 은 APPROVE 또는 REJECT 입니다.");
        }
        o.setReviewedBy(admin);
        o.setReviewedAt(DateTimeUtil.kstNow());
        YtOpinion saved = opinionRepo.save(o);
        return reviewRow(saved, videoRepo.findByVideoId(saved.getVideoId()).orElse(null));
    }

    // ------------------------------------------------------------------ 내부

    private record ValidParticipant(String name, String key, YtVideoParticipant.Role role) {}

    private VideoRowDto videoRow(YtVideo v) {
        return videoRow(v, transcriptRepo.countByVideoId(v.getVideoId()),
                participantsByVideo(List.of(v.getVideoId())).getOrDefault(v.getVideoId(), List.of()));
    }

    private static VideoRowDto videoRow(YtVideo v, long versions, List<ParticipantDto> participants) {
        return new VideoRowDto(v.getVideoId(), v.getTitle(), v.getChannelId(), v.getChannelName(), v.getPublishedAt(),
                v.getCreatedAt(), v.getSourceNote(), v.getDuplicateOf(), v.getStatus().name(), v.getCurrentRunId(),
                v.getLastAttemptAt(), v.getLastSuccessAt(), v.getLastError(), versions, participants,
                YoutubeUrlParser.timestampUrl(v.getVideoId(), 0));
    }

    private Map<String, List<ParticipantDto>> participantsByVideo(List<String> ids) {
        List<YtVideoParticipant> rows = participantRepo.findByVideoIdIn(ids);
        if (rows.isEmpty()) return Map.of();
        Map<Long, YtPerson> persons = personRepo.findAllById(rows.stream().map(YtVideoParticipant::getPersonId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(YtPerson::getId, Function.identity()));
        Map<String, List<ParticipantDto>> out = new HashMap<>();
        for (YtVideoParticipant p : rows) {
            YtPerson person = persons.get(p.getPersonId());
            if (person == null) continue;
            out.computeIfAbsent(p.getVideoId(), k -> new ArrayList<>())
                    .add(new ParticipantDto(person.getId(), person.getDisplayName(), p.getRole().name()));
        }
        return out;
    }

    private List<String> participantNames(String videoId) {
        return participantsByVideo(List.of(videoId)).getOrDefault(videoId, List.of()).stream().map(ParticipantDto::name).toList();
    }

    private static ReviewRowDto reviewRow(YtOpinion o, YtVideo v) {
        return new ReviewRowDto(o.getId(), o.getVideoId(), v == null ? null : v.getTitle(), o.getSpeakerLabel(),
                o.getStockNameRaw(), o.getStockCode(), o.getMappingStatus().name(), o.getStance().name(),
                o.getStatementType().name(), o.getClaimSummary(), o.getConditions(), o.getEvidenceQuote(),
                o.getReviewStatus().name(), o.getReviewReasons(), o.getStartSec(),
                YoutubeUrlParser.timestampUrl(o.getVideoId(), o.getStartSec()));
    }

    private String resolveDuplicateOf(String raw, String videoId) {
        if (raw == null || raw.isBlank()) return null;
        String id = YoutubeUrlParser.isValidVideoId(raw.trim()) ? raw.trim()
                : YoutubeUrlParser.extractVideoId(raw).orElseThrow(() -> new IllegalArgumentException("원본 영상 주소·ID 형식이 아닙니다."));
        if (id.equals(videoId)) throw new IllegalArgumentException("원본 영상이 자기 자신일 수 없습니다.");
        if (videoRepo.findByVideoId(id).isEmpty()) throw new IllegalArgumentException("원본 영상이 먼저 등록돼 있어야 합니다: " + id);
        return id;
    }

    static LocalDateTime parsePublishedAt(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("게시 시각이 필요합니다(예: 2026-09-27T20:00).");
        LocalDateTime t;
        try {
            t = LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("게시 시각 형식이 아닙니다(예: 2026-09-27T20:00).");
        }
        if (t.isBefore(EARLIEST_PUBLISHED) || t.isAfter(DateTimeUtil.kstNow().plusMinutes(10))) {
            throw new IllegalArgumentException("게시 시각이 범위를 벗어났습니다(미래 시각 불가).");
        }
        return t;
    }

    static YtVideoParticipant.Role parseRole(String raw) {
        YtVideoParticipant.Role role = OpinionValidator.parseEnum(YtVideoParticipant.Role.class, raw);
        if (role == null) throw new IllegalArgumentException("출연자 역할은 HOST(채널 운영자) 또는 GUEST(출연자)입니다.");
        return role;
    }

    static YtTranscript.Format parseFormat(String raw) {
        if (raw == null || raw.isBlank() || raw.trim().equalsIgnoreCase("AUTO")) return null;
        YtTranscript.Format f = OpinionValidator.parseEnum(YtTranscript.Format.class, raw);
        if (f == null) throw new IllegalArgumentException("자막 형식은 SRT·VTT·TEXT·AUTO 중 하나입니다.");
        return f;
    }

    static String requireText(String raw, String label, int min, int max) {
        String s = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (s.length() < min) throw new IllegalArgumentException(label + "이(가) 필요합니다.");
        if (s.length() > max) throw new IllegalArgumentException(label + "은(는) " + max + "자 이내입니다.");
        return s;
    }
}
