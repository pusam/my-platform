package com.myplatform.backend.service;

import com.myplatform.backend.dto.PaperTradingDto.PortfolioItemDto;
import com.myplatform.backend.dto.RiskAnalysisDto.DartDisclosure;
import com.myplatform.backend.entity.BotTradingPosition;
import com.myplatform.backend.entity.StockWatchlist;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 보유/관심 종목의 중대 DART 공시를 즉시 감지해 텔레그램 리스크 채널로 발송.
 *
 * 흐름:
 *   1) 평일 장중 5분마다 + 장외 시간대 1시간마다 실행 (공시는 장외 시간에도 발표됨)
 *   2) <b>실잔고 &gt; 봇 포지션 &gt; 관심종목(전체 활성)</b> 우선순위로 종목명 집합 생성
 *      (2026-07-07 관심/포지션 확장 — 상한 {@value #TARGET_MAX_NAMES}, 초과분 로그)
 *   3) 각 종목에 대해 DartService.searchDisclosuresByName → 위험(기존 DANGER 키워드) +
 *      주요(NOTABLE 키워드 — 소송·계약해지 등, <b>중립 톤</b> §4c) 분류
 *   4) 이미 본 공시(rceptNo 기준)는 Redis 에 3일 TTL 로 저장 — 중복 방지
 *   5) 신규 공시만 텔레그램 리스크 채널 전송
 *
 * <p><b>DART rate 계산(상한 근거)</b>: 실행 횟수/일 ≈ 장중 144(5분×12h) + 야간 4 + 새벽 8 = 156.
 * 종목당 1콜이므로 {@value #TARGET_MAX_NAMES}종목 × 156 ≈ 4,700콜/일 &lt; DART 한도 10,000(헤드룸 2배).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DartDisclosureMonitorService {

    private static final String CACHE_SEEN = "dartSeen";
    private static final Duration SEEN_TTL = Duration.ofDays(3);
    /** 감시 대상 상한 — DART 일 10,000콜 한도 기준(클래스 javadoc rate 계산). 초과분은 로그로 가시화. */
    static final int TARGET_MAX_NAMES = 30;

    /**
     * 주요(부정 가능) 공시 키워드 — 기존 DANGER_KEYWORDS(유상증자·거래정지 등 명백 위험) 밖의
     * 관심/보유 종목용 최소 셋. <b>§4c: 키워드 매칭만으로 악재 단정 불가 → '주요 공시' 중립 톤</b>
     * (예: '소송' 은 승소 공시일 수도, '해지' 는 자사주 신탁 해지일 수도 있음).
     * ⚠ '정정' 은 제외 — 공시 대부분이 [기재정정] 류라 스팸. 위험한 정정(감사의견 등)은 DANGER 가 잡음.
     */
    static final List<String> NOTABLE_KEYWORDS = List.of(
            "소송", "계약해지", "계약 해지", "판매중단", "영업정지", "손해배상");

    private final DartService dartService;
    private final RealTradeService realTradeService;
    private final KoreaInvestmentService kisService;
    private final TelegramNotificationService telegramService;
    private final RedisCacheService redisCacheService;
    private final SchedulerLockService schedulerLockService;
    private final StockWatchlistRepository watchlistRepository;
    private final BotTradingPositionRepository botPositionRepository;

    /**
     * 거래시간(NXT 8-20 + KRX) 5분마다.
     */
    @Scheduled(scheduler = "cacheScheduler", cron = "0 */5 8-19 * * MON-FRI", zone = "Asia/Seoul")
    public void checkIntraday() {
        runCheck("장중");
    }

    /**
     * 장외 야간 (20시~23시). 평일 야간 공시 대응.
     */
    @Scheduled(scheduler = "cacheScheduler", cron = "0 0 20-23 * * MON-FRI", zone = "Asia/Seoul")
    public void checkAfterHoursEvening() {
        runCheck("장외-야간");
    }

    /**
     * 장외 새벽 (00시~07시) — 다음날 새벽이므로 화/수/목/금/토 실행.
     * 기존 0-8 with MON-FRI 패턴은 토요일 새벽도 잘못 포함되는 cross-day 버그가 있어 분리.
     */
    @Scheduled(scheduler = "cacheScheduler", cron = "0 0 0-7 * * TUE-SAT", zone = "Asia/Seoul")
    public void checkAfterHoursDawn() {
        runCheck("장외-새벽");
    }

    private void runCheck(String mode) {
        if (!dartService.isAvailable()) return;
        // 가장 짧은 cron(5분)에 맞춰 TTL 4분. dartSeen 캐시가 텔레그램 중복은 이미 막지만,
        // DART API 호출/loop 자체가 두 번 도는 부담을 줄임.
        if (!schedulerLockService.tryLock("dart-disclosure.check", Duration.ofMinutes(4))) {
            log.debug("[DartMonitor] {} 다른 인스턴스에서 진행 중 — 스킵", mode);
            return;
        }

        try {
            Set<String> stockNames = collectTargetStockNames();
            if (stockNames.isEmpty()) return;

            int newCount = 0;
            for (String name : stockNames) {
                newCount += processStock(name);
            }
            if (newCount > 0) {
                log.info("[DART monitor:{}] 대상 {}개 중 신규 공시 {}건 알림",
                        mode, stockNames.size(), newCount);
            }
        } catch (Exception e) {
            log.warn("[DART monitor:{}] 실패: {}", mode, e.getMessage());
        }
    }

    /**
     * 감시 대상 종목명 집합 — <b>실잔고 &gt; 봇 포지션 &gt; 관심종목(전체 활성)</b> 우선순위 병합
     * (2026-07-07 확장: 기존 실잔고 단독 → 봇 포지션·관심 추가), 상한 {@value #TARGET_MAX_NAMES}
     * (초과 시 뒤 그룹부터 잘림 + 로그 — DART rate 근거는 클래스 javadoc). 각 소스 best-effort.
     */
    private Set<String> collectTargetStockNames() {
        List<String> kisNames = new ArrayList<>();
        try {
            if (kisService.isRealTradingConfigured()) {
                List<PortfolioItemDto> portfolio = realTradeService.getPortfolio();
                if (portfolio != null) {
                    for (PortfolioItemDto p : portfolio) {
                        if (p.getStockName() != null && !p.getStockName().isBlank()) {
                            kisNames.add(p.getStockName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[DART monitor] 실전 포트폴리오 조회 실패: {}", e.getMessage());
        }

        List<String> positionNames = new ArrayList<>();
        try {
            for (BotTradingPosition p : botPositionRepository.findAll()) {
                if (p != null && p.getStockName() != null && !p.getStockName().isBlank()) {
                    positionNames.add(p.getStockName());
                }
            }
        } catch (Exception e) {
            log.debug("[DART monitor] 봇 포지션 조회 실패: {}", e.getMessage());
        }

        List<String> watchNames = new ArrayList<>();
        try {
            for (StockWatchlist w : watchlistRepository.findByIsActiveTrue()) {
                if (w != null && w.getStockName() != null && !w.getStockName().isBlank()) {
                    watchNames.add(w.getStockName());
                }
            }
        } catch (Exception e) {
            log.debug("[DART monitor] 관심종목 조회 실패: {}", e.getMessage());
        }

        Set<String> merged = mergeTargetNames(kisNames, positionNames, watchNames, TARGET_MAX_NAMES);
        int candidates = new LinkedHashSet<>(concat(kisNames, positionNames, watchNames)).size();
        if (candidates > merged.size()) {   // 상한 컷 가시화(§ no silent caps)
            log.info("[DART monitor] 대상 상한 컷 — 후보 {}종목 중 {}종목만 감시 (상한 {}, 우선순위 실잔고>포지션>관심)",
                    candidates, merged.size(), TARGET_MAX_NAMES);
        }
        return merged;
    }

    private static List<String> concat(List<String> a, List<String> b, List<String> c) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        all.addAll(c);
        return all;
    }

    /** 우선순위 병합 — <b>순수 함수(테스트 대상)</b>. 실잔고 > 봇 포지션 > 관심, dedup, max 컷. */
    static Set<String> mergeTargetNames(List<String> kisHoldings, List<String> botPositions,
                                        List<String> watchlist, int max) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (List<String> group : List.of(
                kisHoldings == null ? List.<String>of() : kisHoldings,
                botPositions == null ? List.<String>of() : botPositions,
                watchlist == null ? List.<String>of() : watchlist)) {
            for (String name : group) {
                if (out.size() >= max) return out;
                if (name == null || name.isBlank()) continue;
                out.add(name);
            }
        }
        return out;
    }

    /**
     * 특정 종목의 신규 공시를 알림 — 위험(기존 DANGER, 경고 톤) 우선, 그 외 주요(NOTABLE) 공시는
     * <b>중립 톤</b>(§4c: 키워드만으로 악재 단정 금지). 반환: 새로 알린 건수.
     */
    private int processStock(String stockName) {
        try {
            List<DartDisclosure> all = dartService.searchDisclosuresByName(stockName);
            if (all == null || all.isEmpty()) return 0;

            int sent = 0;
            for (DartDisclosure d : all) {
                if (d == null) continue;
                String rceptNo = d.getRceptNo();
                if (rceptNo == null || rceptNo.isBlank()) continue;

                String notableKeyword = d.isDangerous() ? null : matchNotableKeyword(d.getReportNm());
                if (!d.isDangerous() && notableKeyword == null) continue;   // 위험도 주요도 아님

                // 이미 알린 공시면 스킵 (위험/주요 공용 — 공시당 1회)
                String seen = redisCacheService.get(CACHE_SEEN, rceptNo, String.class);
                if (seen != null) continue;

                telegramService.sendRisk(d.isDangerous()
                        ? buildMessage(stockName, d)
                        : buildNotableMessage(stockName, d, notableKeyword));
                redisCacheService.put(CACHE_SEEN, rceptNo, "1", SEEN_TTL);
                sent++;
            }
            return sent;
        } catch (Exception e) {
            log.debug("[DART monitor] {} 처리 실패: {}", stockName, e.getMessage());
            return 0;
        }
    }

    /**
     * 주요 공시 키워드 매칭 — <b>순수 함수(테스트 대상)</b>. 매칭 키워드 또는 null.
     * 기존 DANGER 와 별개(위험은 dartService 가 이미 플래깅) — 여긴 관심/보유 종목용 보조 필터.
     */
    static String matchNotableKeyword(String reportNm) {
        if (reportNm == null || reportNm.isBlank()) return null;
        for (String keyword : NOTABLE_KEYWORDS) {
            if (reportNm.contains(keyword)) return keyword;
        }
        return null;
    }

    /**
     * 주요 공시 메시지 — <b>중립 톤 순수 함수(테스트 대상)</b>. §4c: 키워드 매칭은 분류 불확실
     * → '중대/악재' 단정 없이 "주요 공시" + 직접 확인 유도만.
     */
    static String buildNotableMessage(String stockName, DartDisclosure d, String keyword) {
        StringBuilder sb = new StringBuilder();
        sb.append("📌 <b>주요 공시 (관심/보유 종목)</b>\n\n");
        sb.append(String.format("<b>%s</b>", stockName));
        if (d.getCorpName() != null && !d.getCorpName().equals(stockName)) {
            sb.append(String.format(" (%s)", d.getCorpName()));
        }
        sb.append("\n");
        sb.append(String.format("📄 %s\n", d.getReportNm()));
        if (keyword != null) {
            sb.append(String.format("🔑 키워드: %s\n", keyword));
        }
        if (d.getRceptDt() != null) {
            sb.append(String.format("🕐 접수일: %s\n", d.getRceptDt()));
        }
        if (d.getRceptNo() != null) {
            sb.append(String.format("🔗 https://dart.fss.or.kr/dsaf001/main.do?rcpNo=%s\n", d.getRceptNo()));
        }
        sb.append("\nℹ️ 키워드 자동 감지 — 호재/악재 판단 아님, 공시 원문을 직접 확인하세요.");
        return sb.toString();
    }

    private String buildMessage(String stockName, DartDisclosure d) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ <b>중대 공시 감지</b>\n\n");
        sb.append(String.format("<b>%s</b>", stockName));
        if (d.getCorpName() != null && !d.getCorpName().equals(stockName)) {
            sb.append(String.format(" (%s)", d.getCorpName()));
        }
        sb.append("\n");
        sb.append(String.format("📄 %s\n", d.getReportNm()));
        if (d.getMatchedKeyword() != null) {
            sb.append(String.format("🔑 키워드: %s\n", d.getMatchedKeyword()));
        }
        if (d.getRceptDt() != null) {
            sb.append(String.format("🕐 접수일: %s\n", d.getRceptDt()));
        }
        if (d.getFlrNm() != null) {
            sb.append(String.format("📋 제출인: %s\n", d.getFlrNm()));
        }
        if (d.getRceptNo() != null) {
            sb.append(String.format("\n🔗 https://dart.fss.or.kr/dsaf001/main.do?rcpNo=%s",
                    d.getRceptNo()));
        }
        return sb.toString();
    }
}
