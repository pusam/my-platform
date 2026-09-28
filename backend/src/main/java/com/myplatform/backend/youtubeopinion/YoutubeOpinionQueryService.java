package com.myplatform.backend.youtubeopinion;

import com.myplatform.backend.util.StockCodeFormat;
import com.myplatform.core.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.myplatform.backend.youtubeopinion.YoutubeOpinionDtos.*;

/**
 * 유튜브 의견 조회 — <b>저장된 결과만 읽는다</b>. 화면을 열 때 자막 수집·Gemini 분석을 부르지 않는다.
 *
 * <p>여러 종목은 한 번에 조회한다(목록에서 종목마다 반복 조회 금지). 조회 실패는 예외 대신 {@code dataAvailable=false}
 * 로 돌려 종목 상세·추천 화면을 막지 않고, "의견 없음"과도 구분된다(§4c).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class YoutubeOpinionQueryService {

    /** 창 밖 "지난 의견"을 얼마나 거슬러 볼지 — 날짜를 달고 따로 보여 준다. */
    static final int OLDER_HORIZON_DAYS = 60;
    static final int MAX_SUMMARY_CODES = 20;

    public static final String NO_ANALYZED_VIDEOS = "NO_ANALYZED_VIDEOS";
    public static final String NO_OPINION = "NO_OPINION";
    public static final String HAS_OPINIONS = "HAS_OPINIONS";

    private final YoutubeOpinionSettings settings;
    private final YtVideoRepository videoRepo;
    private final YtOpinionRepository opinionRepo;
    private final YtPersonRepository personRepo;
    private final YtAnalysisRunRepository runRepo;

    // 트랜잭션을 걸지 않는다 — 저장소 예외가 바깥 트랜잭션을 rollback-only 로 만들면 여기서 잡아도 커밋 때 500 이 된다.
    public StockViewDto stockView(String stockCode) {
        if (!StockCodeFormat.isValid(stockCode)) throw new IllegalArgumentException("종목코드 형식이 아닙니다.");
        int days = settings.getWindowDays();
        if (!settings.isEnabled()) {
            return new StockViewDto(false, true, null, SCOPE, days, null, null, null, null, List.of(), List.of(), List.of());
        }
        LocalDateTime now = DateTimeUtil.kstNow();
        try {
            CoverageDto coverage = coverage(now.minusDays(days));
            List<OpinionAggregator.OpinionItem> items =
                    toItems(opinionRepo.findCurrentByStockCodes(List.of(stockCode), now.minusDays(OLDER_HORIZON_DAYS)));
            OpinionAggregator.View view = OpinionAggregator.aggregate(items, now, days);
            return new StockViewDto(true, true, status(coverage, view), SCOPE, days, view.windowFrom(), view.windowTo(),
                    coverage, toSummaryDto(view.summary()),
                    view.persons().stream().map(YoutubeOpinionQueryService::person).toList(),
                    view.current().stream().map(YoutubeOpinionQueryService::opinion).toList(),
                    view.older().stream().map(YoutubeOpinionQueryService::opinion).toList());
        } catch (RuntimeException e) {
            log.warn("[유튜브의견] 종목 조회 실패 {} — dataAvailable=false 로 응답: {}", stockCode, e.getMessage());
            return new StockViewDto(true, false, null, SCOPE, days, null, null, null, null, List.of(), List.of(), List.of());
        }
    }

    /** 여러 종목 요약 — 쿼리 몇 번으로 끝낸다('오늘' 매수 후보용). */
    public SummaryViewDto summary(List<String> codes) {
        List<String> clean = codes == null ? List.of() : codes.stream()
                .map(String::trim).filter(c -> !c.isEmpty()).distinct().toList();
        if (clean.size() > MAX_SUMMARY_CODES) throw new IllegalArgumentException("한 번에 최대 " + MAX_SUMMARY_CODES + "종목까지 조회합니다.");
        for (String c : clean) {
            if (!StockCodeFormat.isValid(c)) throw new IllegalArgumentException("종목코드 형식이 아닙니다: " + c);
        }
        int days = settings.getWindowDays();
        if (!settings.isEnabled()) return new SummaryViewDto(false, true, SCOPE, days, null, Map.of());
        LocalDateTime now = DateTimeUtil.kstNow();
        try {
            CoverageDto coverage = coverage(now.minusDays(days));
            Map<String, SummaryItemDto> out = new LinkedHashMap<>();
            if (!clean.isEmpty()) {
                Map<String, List<OpinionAggregator.OpinionItem>> byCode = new HashMap<>();
                List<YtOpinion> rows = opinionRepo.findCurrentByStockCodes(clean, now.minusDays(days));
                Map<Long, OpinionAggregator.OpinionItem> items = toItems(rows).stream()
                        .collect(Collectors.toMap(OpinionAggregator.OpinionItem::id, Function.identity()));
                for (YtOpinion o : rows) {
                    OpinionAggregator.OpinionItem item = items.get(o.getId());
                    if (item != null) byCode.computeIfAbsent(o.getStockCode(), k -> new ArrayList<>()).add(item);
                }
                for (String code : clean) {
                    OpinionAggregator.View view = OpinionAggregator.aggregate(byCode.getOrDefault(code, List.of()), now, days);
                    out.put(code, new SummaryItemDto(status(coverage, view), toSummaryDto(view.summary())));
                }
            }
            return new SummaryViewDto(true, true, SCOPE, days, coverage, out);
        } catch (RuntimeException e) {
            log.warn("[유튜브의견] 요약 조회 실패 — dataAvailable=false 로 응답: {}", e.getMessage());
            return new SummaryViewDto(true, false, SCOPE, days, null, Map.of());
        }
    }

    static String status(CoverageDto coverage, OpinionAggregator.View view) {
        if (view.hasCurrent()) return HAS_OPINIONS;
        return coverage.analyzedVideos() == 0 ? NO_ANALYZED_VIDEOS : NO_OPINION;
    }

    CoverageDto coverage(LocalDateTime since) {
        int analyzed = 0, analyzing = 0, failed = 0, awaiting = 0, noTranscript = 0;
        for (YtVideo v : videoRepo.findByPublishedAtGreaterThanEqual(since)) {
            if (v.getDuplicateOf() != null) continue;
            if (v.getCurrentRunId() != null) analyzed++;
            switch (v.getStatus()) {
                case ANALYZING -> analyzing++;
                case FAILED -> failed++;
                case REGISTERED -> noTranscript++;
                case TRANSCRIPT_READY -> { if (v.getCurrentRunId() == null) awaiting++; }
                default -> { }
            }
        }
        return new CoverageDto(analyzed, analyzing, failed, awaiting, noTranscript);
    }

    /** 의견 행 → 집계 입력. 영상·인물·실행을 묶음 조회(행마다 조회하지 않는다). */
    List<OpinionAggregator.OpinionItem> toItems(List<YtOpinion> rows) {
        if (rows.isEmpty()) return List.of();
        Map<String, YtVideo> videos = videoRepo.findByVideoIdIn(rows.stream().map(YtOpinion::getVideoId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(YtVideo::getVideoId, Function.identity()));
        Set<Long> personIds = rows.stream().map(YtOpinion::getPersonId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, YtPerson> persons = personIds.isEmpty() ? Map.of()
                : personRepo.findAllById(personIds).stream().collect(Collectors.toMap(YtPerson::getId, Function.identity()));
        Map<Long, YtAnalysisRun> runs = runRepo.findAllById(rows.stream().map(YtOpinion::getRunId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(YtAnalysisRun::getId, Function.identity()));
        List<OpinionAggregator.OpinionItem> out = new ArrayList<>();
        for (YtOpinion o : rows) {
            YtVideo v = videos.get(o.getVideoId());
            if (v == null) continue;
            YtPerson p = o.getPersonId() == null ? null : persons.get(o.getPersonId());
            YtAnalysisRun r = runs.get(o.getRunId());
            out.add(new OpinionAggregator.OpinionItem(o.getId(), o.getVideoId(), v.getTitle(), v.getChannelName(),
                    v.getPublishedAt(), o.getAnalyzedAt(),
                    p == null ? null : p.getId(), p == null ? null : p.getDisplayName(), p == null ? null : o.getSpeakerRole(),
                    o.getStance(), o.getStatementType(), o.getReviewStatus(),
                    Objects.equals(v.getCurrentRunId(), o.getRunId()), v.getDuplicateOf() != null,
                    o.getClaimSummary(), o.getRationale(), o.getConditions(), o.getHorizon(), o.getTargetPrice(),
                    o.getEvidenceQuote(), o.getStartSec(), o.getEndSec(),
                    YoutubeUrlParser.timestampUrl(o.getVideoId(), o.getStartSec()),
                    r == null ? null : r.getModel(), r == null ? null : r.getPromptVersion()));
        }
        return out;
    }

    static SummaryDto toSummaryDto(OpinionAggregator.Summary s) {
        return new SummaryDto(s.statementCounts(), s.personCounts(), s.totalPersons(), s.totalStatements(),
                s.unknownSpeakerStatements(), s.latestPublishedAt());
    }

    static PersonDto person(OpinionAggregator.PersonStance p) {
        return new PersonDto(p.personId(), p.name(), p.role() == null ? null : p.role().name(), p.stance(),
                p.sequence(), p.statements(), p.lastPublishedAt());
    }

    static OpinionDto opinion(OpinionAggregator.OpinionItem o) {
        return new OpinionDto(o.id(), o.videoId(), o.videoTitle(), o.channelName(), o.publishedAt(), o.analyzedAt(),
                o.speakerName(), o.speakerRole() == null ? null : o.speakerRole().name(), o.stance().name(),
                o.claimSummary(), o.rationale(), o.conditions(), o.horizon(), o.targetPrice(), o.evidenceQuote(),
                o.startSec(), TranscriptParser.formatClock(o.startSec()), o.sourceUrl(), o.model(), o.promptVersion());
    }
}
