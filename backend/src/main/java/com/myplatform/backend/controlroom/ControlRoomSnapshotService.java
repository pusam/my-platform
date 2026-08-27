package com.myplatform.backend.controlroom;

import com.myplatform.backend.dto.JudgmentBoardDto;
import com.myplatform.backend.entity.BotConfig;
import com.myplatform.backend.entity.CrewSession;
import com.myplatform.backend.entity.SignalWeeklyAccuracy;
import com.myplatform.backend.entity.VirtualAccount;
import com.myplatform.backend.repository.BotConfigRepository;
import com.myplatform.backend.repository.CrewSessionRepository;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import com.myplatform.backend.repository.SignalWeeklyAccuracyRepository;
import com.myplatform.backend.repository.VirtualAccountRepository;
import com.myplatform.backend.repository.VirtualTradeHistoryRepository;
import com.myplatform.backend.service.DailyLossBreakerService;
import com.myplatform.backend.service.JudgmentBoardService;
import com.myplatform.backend.service.MarketCalendarService;
import com.myplatform.backend.service.RecommendationService;
import com.myplatform.backend.service.TradingSafetyService;
import com.myplatform.backend.service.VolatilityRegimeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 관제실 좌측 패널 스냅샷 조립 — <b>순수 조회 레이어</b>.
 *
 * <p>여기서 하는 일은 읽기와 산수뿐이다. 봇·게이트·가격 경로·신호 합산 코드는 호출만 하고 건드리지
 * 않는다. 크루(LLM)가 숫자를 새로 만들지 않도록 <b>화면과 프롬프트가 쓸 값을 전부 여기서 확정</b>한다.
 *
 * <p><b>캐시</b>: 문서 파싱 + 종합판단 보드 조립이 매 호출마다 돌면 낭비라 30초 메모리 캐시를 둔다.
 * 크루 가용 상태(진행 중 세션·당일 사용량)만은 캐시하지 않는다 — 동시 1건 가드 표시가 밀리면 안 된다.
 *
 * <p><b>§4c</b>: 못 읽은 블록은 0 이 아니라 {@code dataAvailable=false} 로 내려간다.
 */
@Slf4j
@Service
public class ControlRoomSnapshotService {

    /**
     * 등급 컷 — CLAUDE.md §4 기준(STRONG_BUY 75 / BUY 55). <b>표시 전용이며 산식에 관여하지 않는다.</b>
     * 산식 쪽 값을 바꾸면 여기도 같이 고칠 것(원본은 {@code RecommendationService} private 상수라 참조 불가).
     */
    private static final int STRONG_BUY_CUT = 75;
    private static final int BUY_CUT = 55;

    /**
     * 실전 계좌 id — {@code RealTradeService.REAL_ACCOUNT_ID} 와 같은 값. 원본이 package-private 이고
     * 이미 {@code BotPerformanceService}/{@code TradingDiaryService} 도 각자 들고 있어 같은 관행을 따른다
     * (요청 범위 밖 리팩토링은 하지 않음).
     */
    private static final Long REAL_ACCOUNT_ID = 999999L;

    /** 봇 상태 행 key — 매매 모드(REAL/VIRTUAL) 판별용. */
    private static final String BOT_CONFIG_KEY = "trading_bot";

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final ControlRoomDocumentLoader documents;
    private final JudgmentBoardService judgmentBoardService;
    private final TradingSafetyService tradingSafetyService;
    private final DailyLossBreakerService dailyLossBreakerService;
    private final VolatilityRegimeService volatilityRegimeService;
    private final BotConfigRepository botConfigRepository;
    private final VirtualTradeHistoryRepository tradeHistoryRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final SignalWeeklyAccuracyRepository weeklyRepository;
    private final CrewSessionRepository crewSessionRepository;
    private final RecommendationSnapshotRepository recommendationSnapshotRepository;
    private final com.myplatform.backend.repository.StockFinancialDataRepository financialDataRepository;
    private final com.myplatform.backend.repository.StockQuarterlyFinancialRepository quarterlyRepository;
    private final MarketCalendarService marketCalendar;
    private final RecommendationService recommendationService;
    private final CrewProperties crewProperties;
    private final CrewModelAvailability modelAvailability;
    private final Clock clock;

    @Value("${bot.nxt-routing.enabled:false}")
    private boolean nxtRoutingEnabled;

    @Value("${bot.nxt-liquidation.enabled:false}")
    private boolean nxtLiquidationEnabled;

    private final AtomicReference<Cached> cache = new AtomicReference<>();

    private record Cached(String month, LocalDateTime builtAt, ControlRoomSnapshotDto dto) {}

    public ControlRoomSnapshotService(ControlRoomDocumentLoader documents,
                                      JudgmentBoardService judgmentBoardService,
                                      TradingSafetyService tradingSafetyService,
                                      DailyLossBreakerService dailyLossBreakerService,
                                      VolatilityRegimeService volatilityRegimeService,
                                      BotConfigRepository botConfigRepository,
                                      VirtualTradeHistoryRepository tradeHistoryRepository,
                                      VirtualAccountRepository virtualAccountRepository,
                                      SignalWeeklyAccuracyRepository weeklyRepository,
                                      CrewSessionRepository crewSessionRepository,
                                      RecommendationSnapshotRepository recommendationSnapshotRepository,
                                      com.myplatform.backend.repository.StockFinancialDataRepository financialDataRepository,
                                      com.myplatform.backend.repository.StockQuarterlyFinancialRepository quarterlyRepository,
                                      MarketCalendarService marketCalendar,
                                      RecommendationService recommendationService,
                                      CrewProperties crewProperties,
                                      CrewModelAvailability modelAvailability,
                                      Clock clock) {
        this.documents = documents;
        this.judgmentBoardService = judgmentBoardService;
        this.tradingSafetyService = tradingSafetyService;
        this.dailyLossBreakerService = dailyLossBreakerService;
        this.volatilityRegimeService = volatilityRegimeService;
        this.botConfigRepository = botConfigRepository;
        this.tradeHistoryRepository = tradeHistoryRepository;
        this.virtualAccountRepository = virtualAccountRepository;
        this.weeklyRepository = weeklyRepository;
        this.crewSessionRepository = crewSessionRepository;
        this.recommendationSnapshotRepository = recommendationSnapshotRepository;
        this.financialDataRepository = financialDataRepository;
        this.quarterlyRepository = quarterlyRepository;
        this.marketCalendar = marketCalendar;
        this.recommendationService = recommendationService;
        this.crewProperties = crewProperties;
        this.modelAvailability = modelAvailability;
        this.clock = clock;
    }

    /**
     * 스냅샷 조립.
     *
     * @param month {@code YYYY-MM}. null 이면 이번 달 — 캘린더 표시 범위와 주간 피드백 계산에만 쓰인다
     */
    public ControlRoomSnapshotDto snapshot(String month) {
        LocalDate today = LocalDate.now(clock);
        String targetMonth = normalizeMonth(month, today);

        Cached hit = cache.get();
        ControlRoomSnapshotDto core;
        if (hit != null && hit.month().equals(targetMonth)
                && Duration.between(hit.builtAt(), LocalDateTime.now(clock)).compareTo(CACHE_TTL) < 0) {
            core = hit.dto();
        } else {
            core = build(today, targetMonth);
            cache.set(new Cached(targetMonth, LocalDateTime.now(clock), core));
        }

        // 크루 상태만 항상 새로 — 동시 1건 가드/일일 상한 표시가 캐시로 밀리면 안 된다.
        return new ControlRoomSnapshotDto(core.today(), core.generatedAt(), core.month(),
                core.kpis(), core.calendar(), core.flagged(), core.anomalies(),
                core.invariants(), crewStatus(today));
    }

    /** 크루 프롬프트 주입용 — 캐시된 스냅샷을 그대로 재사용한다(세션 시작 시 새로 계산하지 않음). */
    public ControlRoomSnapshotDto currentForCrew() {
        return snapshot(null);
    }

    // ==================== 조립 ====================

    private ControlRoomSnapshotDto build(LocalDate today, String targetMonth) {
        DecisionCalendarParser.Result decisions =
                DecisionCalendarParser.parse(documents.load(ControlRoomDocumentLoader.SCHEDULE_DECISIONS));
        ControlRoomFlagParser.Result flags =
                ControlRoomFlagParser.parse(documents.load(ControlRoomDocumentLoader.CONTROL_ROOM_FLAGS));
        InvariantParser.Result invariants =
                InvariantParser.parse(documents.load(ControlRoomDocumentLoader.CLAUDE_MD));

        return new ControlRoomSnapshotDto(
                today,
                LocalDateTime.now(clock),
                targetMonth,
                new ControlRoomSnapshotDto.Kpis(
                        candidates(),
                        gates(),
                        lossBreaker(),
                        volRegime(),
                        undecided(decisions),
                        financialInput()),
                calendar(decisions, targetMonth, today),
                flagged(flags, decisions, today),
                anomalies(today),
                new ControlRoomSnapshotDto.Invariants(invariants.dataAvailable(), invariants.invariants()),
                null);   // 크루 상태는 snapshot() 이 붙인다
    }

    // ==================== KPI ====================

    /**
     * 데이터 이상 점검 — 결정적 규칙({@link DataAnomalyRules})을 돌린다.
     *
     * <p>크루는 툴이 없어 스스로 데이터를 뒤질 수 없다(§7). 탐지는 여기서 하고 크루는 결과를 읽는다.
     * 규칙은 전부 <b>실제로 겪은 사고</b>에서 나왔다 — 2026-08-27 누적 오판(96%)처럼
     * "어느 화면에도 안 보였던" 부류를 다음엔 자동으로 잡기 위한 것이다.
     *
     * <p>조회 실패는 {@code dataAvailable=false} — "이상 없음"으로 위장하면 이 점검이
     * 잡으려던 바로 그 실패를 자기가 저지르는 셈이다(§4c).
     */
    private ControlRoomSnapshotDto.Anomalies anomalies(LocalDate today) {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            List<DataAnomalyRules.Anomaly> found = new ArrayList<>();
            found.add(DataAnomalyRules.cumulativeSkew(
                    quarterlyRepository.countDistinctCumulativeStocks(),
                    quarterlyRepository.countDistinctStocks()));
            found.add(DataAnomalyRules.futureDatedRows(
                    financialDataRepository.countFutureDatedRows()));
            found.add(DataAnomalyRules.staleQuarterlyData(
                    quarterlyRepository.findMaxPeriodEnd().orElse(null), today));
            found.add(DataAnomalyRules.staleCollection(
                    quarterlyRepository.findMaxCollectedAt().orElse(null), now));

            List<DataAnomalyRules.Anomaly> items = DataAnomalyRules.sortBySeverity(found);
            return new ControlRoomSnapshotDto.Anomalies(true, items, now,
                    items.isEmpty() ? null : items.size() + "건 — 규칙별 detail 에 확인 방법이 있다");
        } catch (Exception e) {
            log.warn("[관제실] 데이터 이상 점검 실패: {}", e.getMessage());
            return new ControlRoomSnapshotDto.Anomalies(false, List.of(), now,
                    "점검이 예외로 실패했다 — '이상 없음'이 아니라 '모름'이다(§4c)");
        }
    }


    /**
     * 재무 입력층 건강 — 최신 일별 스냅샷의 필드 충전율.
     *
     * <p>세 묶음이 서로 다른 KIS 호출에서 오므로 어느 쪽이 죽었는지 갈린다.
     * 2026-08-26 실측이 이 카드의 존재 이유다 — 비율 434/434, 손익계산서 0/434,
     * 재무상태표 0/434 였는데 몇 달 동안 어느 화면에도 안 보였다.
     *
     * <p>조회 실패는 {@code dataAvailable=false} 로 정직하게(§4c) — "0건"으로 위장하면
     * 이 카드가 잡으려던 바로 그 실패를 자기가 저지르는 셈이다.
     */
    private ControlRoomSnapshotDto.FinancialInput financialInput() {
        try {
            List<Object[]> rows = financialDataRepository.findLatestSnapshotFieldCoverage();
            if (rows == null || rows.isEmpty()) {
                return new ControlRoomSnapshotDto.FinancialInput(true, null, 0, 0, 0, 0,
                        "행 없음", financialInputNoteDetail(0, 0, 0, 0));
            }
            Object[] r = rows.get(0);
            LocalDate asOf = toLocalDate(r[0]);
            long total = toLong(r[1]);
            long withRatios = toLong(r[2]);
            long withStatement = toLong(r[3]);
            long withBalance = toLong(r[4]);
            return new ControlRoomSnapshotDto.FinancialInput(true, asOf, total,
                    withRatios, withStatement, withBalance,
                    financialInputNote(total, withRatios, withStatement, withBalance),
                    financialInputNoteDetail(total, withRatios, withStatement, withBalance));
        } catch (Exception e) {
            log.warn("[관제실] 재무 입력층 조회 실패: {}", e.getMessage());
            return new ControlRoomSnapshotDto.FinancialInput(false, null, 0, 0, 0, 0,
                    "조회 실패", "재무 입력층 조회가 예외로 실패했다 — '결측 0건'이 아니라 '모름'이다(§4c).");
        }
    }

    /**
     * 카드 표면용 <b>짧은</b> 진단 — 정상이면 null(카드가 조용하다).
     *
     * <p>정상일 때 시끄러우면 사람이 경고를 무시하게 되고, 그러면 다음 사망도 똑같이 지나간다.
     * 긴 설명은 {@link #financialInputNoteDetail} 이 맡고 화면에선 툴팁으로 간다
     * (후보 카드의 note/noteDetail 과 같은 규약).
     */
    static String financialInputNote(long total, long withRatios, long withStatement, long withBalance) {
        if (total <= 0) return "행 없음";
        boolean ratiosDead = withRatios == 0;
        boolean statementDead = withStatement == 0;
        boolean balanceDead = withBalance == 0;

        if (ratiosDead && statementDead && balanceDead) return "KIS 재무 3종 전멸 — 토큰·키 장애 의심";
        if (statementDead && balanceDead) return "손익·재무상태표 0/" + total + " — 응답 필드 불일치 의심";
        if (statementDead) return "손익계산서 0/" + total;
        if (balanceDead) return "재무상태표 0/" + total;
        if (ratiosDead) return "재무비율 0/" + total;

        // 부분 결손도 절반을 넘으면 알린다 — 조용한 퇴화를 놓치지 않기 위해.
        long worst = Math.min(withRatios, Math.min(withStatement, withBalance));
        if (worst * 2 < total) return "충전율 절반 미만 (최저 " + worst + "/" + total + ")";
        return null;
    }

    /**
     * 툴팁·크루 컨텍스트용 <b>긴</b> 진단 — 무엇을 해야 하는지까지 적는다.
     *
     * <p>크루에게는 이 쪽을 준다. 근거가 잘리면 판단이 얕아진다(후보 카드와 같은 이유).
     */
    static String financialInputNoteDetail(long total, long withRatios, long withStatement, long withBalance) {
        if (total <= 0) {
            return "재무 스냅샷 행이 없다 — 수집 배치(08:30/15:38)가 한 번도 안 돌았거나 전면 실패.";
        }
        boolean ratiosDead = withRatios == 0;
        boolean statementDead = withStatement == 0;
        boolean balanceDead = withBalance == 0;

        if (ratiosDead && statementDead && balanceDead) {
            return "KIS 재무 호출 3종(비율·손익계산서·재무상태표)이 전부 값을 못 채웠다 — "
                    + "개별 필드가 아니라 토큰·앱키 수준 장애를 먼저 의심할 것.";
        }
        if (statementDead && balanceDead) {
            return "손익계산서·재무상태표가 0/" + total + " — 매출·영업이익·순이익·자본총계 전멸. "
                    + "비율(per/pbr/roe)은 " + withRatios + "/" + total + " 로 살아 있으므로 "
                    + "API 사망이 아니라 응답 필드명 불일치를 의심할 것(2026-08-26 실제 사례: "
                    + "sale_account/bsop_prti/thtr_ntin 이 응답에 없어 빈 문자열→0 으로 조용히 흘렀다). "
                    + "배치 로그의 [손익계산서 스키마] 줄에 실제 응답 키가 찍힌다.";
        }
        if (statementDead) {
            return "손익계산서가 0/" + total + " — 매출·영업이익·순이익 전멸. "
                    + "earnings 채점과 분기 원본(V55) 적재가 함께 멈춘다.";
        }
        if (balanceDead) {
            return "재무상태표가 0/" + total + " — 자본총계 전멸. ROE 직접계산과 "
                    + "composite 가치 축(scoreValueStability)이 영향을 받는다.";
        }
        if (ratiosDead) {
            return "재무비율이 0/" + total + " — per/pbr/roe 전멸. 저평가 트랙이 통째로 멈춘다.";
        }
        long worst = Math.min(withRatios, Math.min(withStatement, withBalance));
        if (worst * 2 < total) {
            return "세 호출 중 하나가 절반 미만만 채웠다(최저 " + worst + "/" + total + "). "
                    + "전멸이 아니라 조용한 퇴화라 로그로는 잘 안 보인다 — 며칠 추세를 볼 것.";
        }
        return null;
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof LocalDate d) return d;
        return LocalDate.parse(String.valueOf(v));
    }

    private static long toLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }


    /**
     * 종합판단 보드(momentum) 후보를 등급별로 센다. 보드 조회 실패는 0 이 아니라 데이터 없음이다.
     *
     * <p><b>0 건일 때 이유를 함께 싣는다.</b> {@code JudgmentBoardService.getBoard} 는 후보 조회가
     * 실패해도 예외를 삼키고 빈 목록을 돌려주기 때문에, 여기서 보면 "진짜 0건"·"조회 실패"·"입력 노후로
     * 미채점"이 전부 같은 0 이다. 2026-08-24 에 실제로 그 상황이 났다 — 서버가 4일 다운돼 수급·가격이
     * 8/20 에 멈췄고, 노후 가드가 §4c 대로 채점을 거부해 후보가 0 이 됐는데 화면엔 그냥 "0종목"만 떴다.
     * 근본 해결(보드가 실패와 0건을 구분해 내려주기)은 그 서비스를 고쳐야 하므로 범위 밖이고,
     * 여기서는 <b>추천 스냅샷 신선도를 곁들여 "왜 0인지"를 읽을 수 있게</b> 한다.
     */
    private ControlRoomSnapshotDto.Candidates candidates() {
        try {
            JudgmentBoardDto board = judgmentBoardService.getBoard("momentum");
            List<JudgmentBoardDto.Row> rows = board.getRows() == null ? List.of() : board.getRows();

            int strongBuy = 0;
            int buy = 0;
            int watch = 0;
            for (JudgmentBoardDto.Row row : rows) {
                if (!row.isScored()) {
                    watch++;                                  // 4-cat 미채점 = 등급 판정 불가
                } else if (row.getTotalScore() >= STRONG_BUY_CUT) {
                    strongBuy++;
                } else if (row.getTotalScore() >= BUY_CUT) {
                    buy++;
                } else {
                    watch++;
                }
            }

            LocalDateTime latestSnapshotAt = latestSnapshotAt();
            Boolean stale = snapshotStale(latestSnapshotAt);
            boolean empty = rows.isEmpty();
            Origin origin = candidatesOrigin();
            return new ControlRoomSnapshotDto.Candidates(true, rows.size(), strongBuy, buy, watch,
                    latestSnapshotAt, stale, origin.asOf(), origin.realtime(),
                    emptyReasonShort(empty, stale, latestSnapshotAt),
                    emptyReason(empty, latestSnapshotAt, stale));
        } catch (Exception e) {
            log.warn("[관제실] 종합판단 보드 조회 실패: {}", e.getMessage());
            return new ControlRoomSnapshotDto.Candidates(false, 0, 0, 0, 0, null, null, null, null,
                    "보드 조회 실패", "종합판단 보드 조회가 예외로 실패했다. 0건이 아니라 측정 불가다.");
        }
    }

    /**
     * 카드 표면용 <b>한 줄</b> 사유. 전체 설명은 {@link #emptyReason}(툴팁·크루 컨텍스트)에 있다.
     *
     * <p>1/5 폭 KPI 카드에 §4c 설명 문단을 그대로 넣었더니 텍스트 벽돌이 됐다(2026-08-25 실측).
     * 표면은 "무슨 일이 일어났는지"만, 근거는 툴팁으로 미룬다 — 짧게 줄이되 근거를 없애지는 않는다.
     */
    static String emptyReasonShort(boolean empty, Boolean stale, LocalDateTime latestSnapshotAt) {
        if (!empty) return null;
        if (Boolean.TRUE.equals(stale)) return "입력 노후 — 가드가 채점 거부";
        if (latestSnapshotAt == null) return "스냅샷 없음 — 미계산과 구분 불가";
        return "빈 결과 — 조회 실패와 구분 불가";
    }

    /** 후보 수의 출처 — 실시간 계산인지 어제 스냅샷 폴백인지. */
    private record Origin(String asOf, Boolean realtime) {}

    /**
     * 보드 숫자의 출처를 읽는다.
     *
     * <p>{@code getTop5()} 는 캐시가 비었거나 장외면 <b>DB 스냅샷으로 폴백</b>하고 그 사실을
     * {@code realtime=false} 로 알려준다. 화면이 그걸 안 보여주면 "어제 1건"을 "오늘 1건"으로 읽는다 —
     * 2026-08-26 에 실제로 그랬다(09:00 화면 1종목 / 09:30 재계산 0건).
     *
     * <p>보드가 이미 같은 호출을 했으므로 메모리 캐시 hit 이라 비용은 사실상 0이다.
     */
    private Origin candidatesOrigin() {
        try {
            RecommendationService.Top5Response top5 = recommendationService.getTop5();
            return new Origin(top5.getDataTime(), top5.isRealtime());
        } catch (Exception e) {
            log.warn("[관제실] 후보 출처 조회 실패: {}", e.getMessage());
            return new Origin(null, null);
        }
    }

    /** 후보가 0 건일 때의 전체 설명(툴팁·크루 컨텍스트). 0 건이 아니면 null. */
    static String emptyReason(boolean empty, LocalDateTime latestSnapshotAt, Boolean stale) {
        if (!empty) return null;
        if (Boolean.TRUE.equals(stale)) {
            return "후보 0건 — 추천 스냅샷이 " + latestSnapshotAt.toLocalDate()
                    + " 로 노후. 입력이 노후하면 노후 가드가 채점을 거부하므로 0 으로 보인다(§4c 정상 동작).";
        }
        if (latestSnapshotAt == null) {
            return "후보 0건 — 추천 스냅샷이 아예 없다. '후보 없음'과 '아직 계산 안 됨'을 구분할 수 없다.";
        }
        return "후보 0건 — 보드가 빈 결과를 반환했다. 보드는 조회 실패도 빈 목록으로 돌려주므로 "
                + "'진짜 0건'과 '조회 실패'가 구분되지 않는다.";
    }

    private LocalDateTime latestSnapshotAt() {
        try {
            return recommendationSnapshotRepository.findMaxSnapshotAt().orElse(null);
        } catch (Exception e) {
            log.warn("[관제실] 추천 스냅샷 최신 시각 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    /** 최신 스냅샷이 직전 거래일보다 오래됐는가. 판정 불가면 null(false 로 위장하지 않는다). */
    private Boolean snapshotStale(LocalDateTime latestSnapshotAt) {
        if (latestSnapshotAt == null) return null;
        try {
            LocalDate previousTradingDay = marketCalendar.minusTradingDays(LocalDate.now(clock), 1);
            return latestSnapshotAt.toLocalDate().isBefore(previousTradingDay);
        } catch (Exception e) {
            log.warn("[관제실] 직전 거래일 계산 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 봇 게이트 5종.
     *
     * <p><b>이 목록이 게이트의 정의다.</b> 늘리거나 줄이려면 여기만 고친다 — 전부 설정/DB 읽기이며
     * KIS 호출이 없다(관제실 폴링이 외부 API 를 두드리지 않게).
     *
     * <p>state 는 "진입을 막고 있는가" 기준이다: OPEN=허용 / CLOSED=차단 / UNKNOWN=판정 불가.
     * flag OFF 인 NXT 게이트는 <b>의도된 fail-CLOSED</b> 라 CLOSED 로 표시된다(고장이 아님).
     */
    private ControlRoomSnapshotDto.Gates gates() {
        List<ControlRoomSnapshotDto.Gate> items = new ArrayList<>();

        items.add(gate("kill-switch", "킬스위치", () -> {
            boolean killed = tradingSafetyService.isKilled();
            return new String[]{killed ? "CLOSED" : "OPEN",
                    killed ? "발동 상태 — 수동 해제 전까지 매매 차단" : "정상"};
        }));

        items.add(gate("daily-loss-breaker", "일일손실 서킷", () -> {
            boolean tripped = dailyLossBreakerService.isTrippedToday();
            return new String[]{tripped ? "CLOSED" : "OPEN",
                    tripped ? "오늘 발동 — 신규 진입만 차단(청산은 계속)" : "미발동"};
        }));

        items.add(gate("vol-regime", "VKOSPI 변동성 게이트", () -> {
            VolatilityRegimeService.GateMode mode = volatilityRegimeService.gateMode();
            VolatilityRegimeService.VolRegime regime = volatilityRegimeService.currentVolRegime();
            if (mode == VolatilityRegimeService.GateMode.OFF) {
                return new String[]{"OPEN", "mode=OFF (게이트 무효, 봇 동작 불변) · 국면 " + regime};
            }
            if (regime == VolatilityRegimeService.VolRegime.UNKNOWN) {
                return new String[]{"UNKNOWN", "mode=" + mode + " 이나 VKOSPI 미수집 — skip(fail-open)"};
            }
            boolean blocking = mode == VolatilityRegimeService.GateMode.BLOCK
                    && regime == VolatilityRegimeService.VolRegime.HIGH_VOL;
            return new String[]{blocking ? "CLOSED" : "OPEN", "mode=" + mode + " · 국면 " + regime};
        }));

        items.add(gate("nxt-routing", "NXT 주문 라우팅", () -> new String[]{
                nxtRoutingEnabled ? "OPEN" : "CLOSED",
                nxtRoutingEnabled ? "활성" : "flag OFF — 의도된 fail-CLOSED(거래소구분 파라미터 미확정)"}));

        items.add(gate("nxt-liquidation", "NXT 연장장 청산", () -> new String[]{
                nxtLiquidationEnabled ? "OPEN" : "CLOSED",
                nxtLiquidationEnabled ? "활성" : "flag OFF — 정규장 청산만"}));

        int open = (int) items.stream().filter(g -> "OPEN".equals(g.state())).count();
        boolean anyKnown = items.stream().anyMatch(g -> !"UNKNOWN".equals(g.state()));
        return new ControlRoomSnapshotDto.Gates(anyKnown, open, items.size(), items);
    }

    private ControlRoomSnapshotDto.Gate gate(String key, String label, GateProbe probe) {
        try {
            String[] r = probe.read();
            return new ControlRoomSnapshotDto.Gate(key, label, r[0], r[1]);
        } catch (Exception e) {
            log.warn("[관제실] 게이트 조회 실패 {}: {}", key, e.getMessage());
            return new ControlRoomSnapshotDto.Gate(key, label, "UNKNOWN", "조회 실패: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface GateProbe {
        String[] read() throws Exception;
    }

    /**
     * 일일 손실 서킷 — 원(KRW) 단위. 자산 대비 % 킬스위치(-3%/-1.5%)와는 <b>별개 장치</b>라 섞지 않는다.
     *
     * <p>당일 실현손익은 브레이커가 쓰는 것과 <b>같은 리포지토리 메서드·같은 인자</b>로 읽는다
     * (계산 중복이 아니라 동일 쿼리 재사용). 봇 코드는 건드리지 않는다.
     */
    private ControlRoomSnapshotDto.LossBreaker lossBreaker() {
        BotConfig config;
        try {
            config = dailyLossBreakerService.getBreakerConfig();
        } catch (Exception e) {
            log.warn("[관제실] 브레이커 설정 조회 실패: {}", e.getMessage());
            return new ControlRoomSnapshotDto.LossBreaker(false, null, null, null, null, null,
                    "브레이커 설정 조회 실패");
        }

        boolean realMode = isRealMode();
        Long pnl = null;
        String note = null;
        try {
            Long accountId = realMode ? REAL_ACCOUNT_ID
                    : virtualAccountRepository.findFirstByIsActiveTrueOrderByIdDesc()
                            .map(VirtualAccount::getId).orElse(null);
            if (!realMode && accountId == null) {
                pnl = 0L;
                note = "활성 가상계좌 없음 — 당일 거래 0";
            } else {
                LocalDateTime start = LocalDate.now(clock).atStartOfDay();
                BigDecimal sum = tradeHistoryRepository.sumRealizedPnlBetween(
                        accountId, start, start.plusDays(1));
                pnl = sum == null ? 0L : sum.longValue();
            }
        } catch (Exception e) {
            // §4c: 조회 실패를 0 원으로 위장하지 않는다 — null 로 두고 화면이 "조회 실패"를 보여준다.
            log.warn("[관제실] 당일 실현손익 조회 실패: {}", e.getMessage());
            note = "당일 실현손익 조회 실패";
        }

        Long limit = config.getDailyLossLimitKrw() == null ? null : config.getDailyLossLimitKrw().longValue();
        Boolean enabled = config.getDailyLossBreakerEnabled() == null || config.getDailyLossBreakerEnabled();
        boolean tripped = safeTrippedToday();

        return new ControlRoomSnapshotDto.LossBreaker(true, pnl, limit, enabled, tripped,
                realMode ? "REAL" : "VIRTUAL", note);
    }

    private boolean safeTrippedToday() {
        try {
            return dailyLossBreakerService.isTrippedToday();
        } catch (Exception e) {
            log.warn("[관제실] 브레이커 발동 여부 조회 실패: {}", e.getMessage());
            return false;
        }
    }

    private boolean isRealMode() {
        try {
            return botConfigRepository.findByConfigKey(BOT_CONFIG_KEY)
                    .map(c -> "REAL".equalsIgnoreCase(c.getTradingMode()))
                    .orElse(false);
        } catch (Exception e) {
            log.warn("[관제실] 봇 매매 모드 조회 실패 — VIRTUAL 로 표시: {}", e.getMessage());
            return false;
        }
    }

    /** VKOSPI 국면. 목업의 "streak Nd" 는 소스가 없어 담지 않는다(§4c). */
    private ControlRoomSnapshotDto.VolRegime volRegime() {
        try {
            VolatilityRegimeService.VolRegime regime = volatilityRegimeService.currentVolRegime();
            VolatilityRegimeService.GateMode mode = volatilityRegimeService.gateMode();
            boolean known = regime != VolatilityRegimeService.VolRegime.UNKNOWN;
            return new ControlRoomSnapshotDto.VolRegime(known, regime.name(), mode.name(),
                    known ? null : "VKOSPI 미수집 — 가짜 NORMAL 로 채우지 않음");
        } catch (Exception e) {
            log.warn("[관제실] 변동성 국면 조회 실패: {}", e.getMessage());
            return new ControlRoomSnapshotDto.VolRegime(false, null, null, "국면 조회 실패");
        }
    }

    private ControlRoomSnapshotDto.Undecided undecided(DecisionCalendarParser.Result decisions) {
        return new ControlRoomSnapshotDto.Undecided(
                decisions.recordTableFound(), decisions.undecidedCount(), decisions.rosterSize());
    }

    // ==================== 캘린더 ====================

    private ControlRoomSnapshotDto.Calendar calendar(DecisionCalendarParser.Result decisions,
                                                     String targetMonth, LocalDate today) {
        List<ControlRoomSnapshotDto.CalendarEntry> entries = decisions.entries().stream()
                .map(e -> new ControlRoomSnapshotDto.CalendarEntry(
                        e.id(), e.title(), e.due(), e.status(), e.decidedOn(), e.result(),
                        e.trigger(), e.kind(), e.isOverdue(today)))
                .sorted(Comparator.comparing(ControlRoomSnapshotDto.CalendarEntry::due))
                .toList();

        List<ControlRoomSnapshotDto.CalendarEntry> overdue =
                entries.stream().filter(ControlRoomSnapshotDto.CalendarEntry::overdue).toList();
        List<ControlRoomSnapshotDto.CalendarEntry> conditionWaiting = entries.stream()
                .filter(e -> e.trigger() != null && !e.trigger().isBlank())
                .toList();

        // D-day = 오늘 이후 가장 가까운 due. 지난 건 OVERDUE 로 따로 세므로 제외한다.
        LocalDate nextDue = entries.stream()
                .map(ControlRoomSnapshotDto.CalendarEntry::due)
                .filter(d -> d != null && !d.isBefore(today))
                .min(Comparator.naturalOrder())
                .orElse(null);
        Integer dDay = nextDue == null ? null : (int) ChronoUnit.DAYS.between(today, nextDue);

        return new ControlRoomSnapshotDto.Calendar(decisions.dataAvailable(), entries, overdue,
                conditionWaiting, weeklyFeedback(YearMonth.parse(targetMonth), today), nextDue, dDay);
    }

    /**
     * 주간 신호 정확도 피드백 — 판정이 아니라 크론({@code 0 0 18 * * SUN})에서 유도한다.
     *
     * <p>§4c: <b>지난 일요일은 실제 리포트 존재 여부로 실행/미실행을 가른다.</b> 안 돌았는데 초록 점을
     * 찍으면 "피드백이 돌고 있다"는 거짓 안심을 준다. 조회 창(최근 12주) 밖이라 알 수 없으면 UNKNOWN.
     */
    private List<ControlRoomSnapshotDto.WeeklyFeedback> weeklyFeedback(YearMonth month, LocalDate today) {
        List<SignalWeeklyAccuracy> rows;
        try {
            rows = weeklyRepository.findTop12ByOrderByWeekStartDesc();
        } catch (Exception e) {
            log.warn("[관제실] 주간 리포트 이력 조회 실패: {}", e.getMessage());
            rows = List.of();
        }

        Set<LocalDate> ranDates = new HashSet<>();
        LocalDate oldestKnown = null;
        for (SignalWeeklyAccuracy row : rows) {
            if (row.getGeneratedAt() == null) continue;
            LocalDate d = row.getGeneratedAt().toLocalDate();
            ranDates.add(d);
            if (oldestKnown == null || d.isBefore(oldestKnown)) oldestKnown = d;
        }
        boolean noReportsAtAll = rows.isEmpty();

        List<ControlRoomSnapshotDto.WeeklyFeedback> result = new ArrayList<>();
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY) continue;

            String state;
            if (d.isAfter(today)) {
                state = "SCHEDULED";
            } else if (ranDates.contains(d)) {
                state = "RAN";
            } else if (noReportsAtAll || (oldestKnown != null && !d.isBefore(oldestKnown))) {
                state = "MISSED";
            } else {
                state = "UNKNOWN";   // 조회 창 밖 — 미실행으로 단정하지 않는다
            }
            result.add(new ControlRoomSnapshotDto.WeeklyFeedback(d, state));
        }
        return result;
    }

    // ==================== FLAGGED ====================

    /**
     * 파일 플래그 + 시스템 유도 플래그.
     *
     * <p>유도 항목 2종: <b>파싱 오류</b>(조용히 건너뛰지 않기 위해)와 <b>미등록 판정</b>(판정 기록 표엔
     * 있는데 캘린더 YAML 에 없어 화면에서 사라지는 안건). 둘 다 {@code derived=true} 로 표시해
     * 사람이 적은 항목과 구분한다.
     */
    private ControlRoomSnapshotDto.Flagged flagged(ControlRoomFlagParser.Result flags,
                                                   DecisionCalendarParser.Result decisions,
                                                   LocalDate today) {
        List<ControlRoomSnapshotDto.FlagItem> items = new ArrayList<>();

        for (ControlRoomFlagParser.Flag f : flags.flags()) {
            Integer age = f.recordedOn() == null ? null
                    : (int) ChronoUnit.DAYS.between(f.recordedOn(), today);
            items.add(new ControlRoomSnapshotDto.FlagItem(f.id(), f.severity(), f.title(), f.key(),
                    f.body(), f.recordedOn(), age, f.ref(), false));
        }

        if (!decisions.unregisteredTitles().isEmpty()) {
            items.add(new ControlRoomSnapshotDto.FlagItem(
                    "unregistered-decisions",
                    ControlRoomFlagParser.SEVERITY_WARNING,
                    "미등록 판정 " + decisions.unregisteredTitles().size() + "건",
                    "SCHEDULE_DECISIONS.md",
                    "판정 기록 표엔 있는데 캘린더 YAML 블록에 없어 캘린더에 뜨지 않는 안건: "
                            + String.join(", ", decisions.unregisteredTitles()),
                    today, 0, "docs/SCHEDULE_DECISIONS.md 관제실 기계 판독 블록", true));
        }

        List<String> parseErrors = new ArrayList<>(decisions.parseErrors());
        parseErrors.addAll(flags.parseErrors());
        for (String error : parseErrors) {
            items.add(new ControlRoomSnapshotDto.FlagItem(
                    "parse-error", ControlRoomFlagParser.SEVERITY_CRITICAL, error, "파싱",
                    "문서 항목을 읽지 못해 화면에서 누락됐다. 해당 항목을 고치기 전까지 관제실 수치는 불완전하다.",
                    today, 0, null, true));
        }

        long critical = items.stream()
                .filter(f -> ControlRoomFlagParser.SEVERITY_CRITICAL.equals(f.severity()))
                .count();
        // 파일을 못 읽었어도 유도 플래그는 남을 수 있으므로 dataAvailable 은 파일 기준 그대로 전달한다.
        return new ControlRoomSnapshotDto.Flagged(flags.dataAvailable(), items, critical);
    }

    // ==================== 크루 상태 ====================

    private ControlRoomSnapshotDto.CrewStatus crewStatus(LocalDate today) {
        long usedToday;
        boolean running;
        try {
            LocalDateTime start = today.atStartOfDay();
            usedToday = crewSessionRepository.countByStartedAtBetween(start, start.plusDays(1));
            running = crewSessionRepository.countByStatus(CrewSession.Status.RUNNING) > 0;
        } catch (Exception e) {
            log.warn("[관제실] 크루 세션 집계 실패: {}", e.getMessage());
            usedToday = 0;
            running = false;
        }

        Optional<String> blocked = modelAvailability.disabledReason();
        return new ControlRoomSnapshotDto.CrewStatus(
                blocked.isEmpty(),
                blocked.orElse(null),
                crewProperties.getModel(),
                crewProperties.getDailyLimit(),
                usedToday,
                running);
    }

    private static String normalizeMonth(String month, LocalDate today) {
        if (month == null || month.isBlank()) return YearMonth.from(today).toString();
        try {
            return YearMonth.parse(month.trim()).toString();
        } catch (RuntimeException e) {
            return YearMonth.from(today).toString();
        }
    }
}
