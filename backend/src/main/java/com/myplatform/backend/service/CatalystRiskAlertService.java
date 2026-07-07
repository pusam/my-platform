package com.myplatform.backend.service;

import com.myplatform.backend.entity.AlertHistory;
import com.myplatform.backend.entity.StockCatalyst;
import com.myplatform.backend.entity.StockCatalyst.Direction;
import com.myplatform.backend.repository.AlertHistoryRepository;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 관심/보유 종목 악재 조기경보 (2026-07-07) — 재료 분류가 <b>악재(NEGATIVE)로 저장되는 시점</b>에
 * StockCatalystService 가 호출하는 훅. 대상(관심 watchlist / 봇 포지션 / KIS 실잔고)이면 텔레그램 발송.
 *
 * <p><b>채널</b>: 관심 = 시그널 채널 / <b>보유(포지션·실잔고) = 시그널 + 리스크 병행</b>(더 높은 긴급도).
 * <b>중복 방지</b>: 종목×일자 1회 — {@link AlertHistory}(alertKey=CATNEG_코드_일자) 멱등.
 * <b>분류 자체는 기존 일캐시 흐름 그대로</b> — 알림만 부착, classify 추가 호출 없음.
 *
 * <p>대상 아님/호재/중립이면 no-op(false) — 기존 일반 재료 알림(notifyIfMeaningful)이 그대로 담당.
 * KIS 실잔고 조회는 10분 캐시 + best-effort(실패=미보유 취급 — 관심/봇 판정은 영향 없음, §4c).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CatalystRiskAlertService {

    static final String ALERT_TYPE = "CATALYST_NEG";
    private static final long KIS_HOLDINGS_TTL_MS = 10 * 60 * 1000L;

    private final StockWatchlistRepository watchlistRepository;
    private final BotTradingPositionRepository botPositionRepository;
    private final AlertHistoryRepository alertHistoryRepository;
    private final ObjectProvider<TelegramNotificationService> telegramProvider;
    private final ObjectProvider<RealTradeService> realTradeProvider;
    private final ObjectProvider<KoreaInvestmentService> kisProvider;

    private volatile Set<String> kisHoldingsCache = Set.of();
    private volatile long kisHoldingsFetchedAt = 0L;

    /** 발송 판정 결과 — 순수 함수 {@link #decide} 출력. */
    enum Action { NONE, SIGNAL, SIGNAL_AND_RISK }

    /**
     * 악재 저장 훅. @return true = 관심/보유 대상 악재라 이 서비스가 처리(발송 또는 당일 중복 억제)
     * — 호출측은 true 면 일반 악재 알림을 생략해 리스크 채널 이중 발송을 막는다. false = 대상 아님(기존 흐름).
     */
    public boolean onCatalystSaved(StockCatalyst saved, String newsLink) {
        if (saved == null || saved.getStockCode() == null || saved.getStockCode().isBlank()) return false;
        if (saved.getDirection() != Direction.NEGATIVE) return false;

        boolean watched = isWatched(saved.getStockCode());
        boolean held = isHeld(saved.getStockCode());
        if (!watched && !held) return false;

        LocalDate date = saved.getCatalystDate() != null ? saved.getCatalystDate() : LocalDate.now();
        String key = alertKey(saved.getStockCode(), date);
        boolean alreadySent;
        try {
            alreadySent = alertHistoryRepository.findLatestByAlertKey(key).isPresent();
        } catch (Exception e) {
            // 이력 조회 실패 시 발송 강행보다 억제 — 알림은 보조 기능, 스팸이 더 해롭다.
            log.warn("[악재경보] 이력 조회 실패({}) — 이번 회차 억제: {}", key, e.getMessage());
            return true;
        }

        Action action = decide(saved.getDirection(), watched, held, alreadySent);
        if (action == Action.NONE) return true;   // 대상이지만 당일 기발송 → 처리됨(중복 억제)

        TelegramNotificationService telegram = telegramProvider.getIfAvailable();
        if (telegram == null) return true;
        try {
            String message = buildTargetAlertMessage(saved, newsLink, held);
            telegram.sendSignal(message);
            if (action == Action.SIGNAL_AND_RISK) telegram.sendRisk(message);
            recordSent(saved, key);
        } catch (Exception e) {
            log.warn("[악재경보] 발송 실패({}): {}", saved.getStockCode(), e.getMessage());
        }
        return true;
    }

    /**
     * 발송 판정 — <b>순수 함수(테스트 대상)</b>.
     * 악재 아님/기발송 = NONE. 보유(봇·실잔고) = 시그널+리스크 병행, 관심만 = 시그널.
     */
    static Action decide(Direction direction, boolean watched, boolean held, boolean alreadySentToday) {
        if (direction != Direction.NEGATIVE) return Action.NONE;
        if (alreadySentToday) return Action.NONE;
        if (held) return Action.SIGNAL_AND_RISK;
        if (watched) return Action.SIGNAL;
        return Action.NONE;
    }

    /** 중복 방지 키 — 종목×일자 1회. 순수 함수(테스트 대상). alert_history.alertKey(50자) 내. */
    static String alertKey(String stockCode, LocalDate date) {
        return "CATNEG_" + stockCode + "_" + date;
    }

    /**
     * 관심/보유 악재 경보 메시지 — 순수 함수(테스트 대상). 종목명·분류 요약·대표 뉴스 제목/링크 1건.
     * 기존 알림 포맷(HTML + 구분선 + 봇 서명) 준수. 링크 null 이면 링크 줄 생략(§4c).
     */
    static String buildTargetAlertMessage(StockCatalyst c, String newsLink, boolean held) {
        StringBuilder sb = new StringBuilder();
        sb.append(held ? "<b>🚨 보유 종목 악재 경보</b>\n\n" : "<b>⚠️ 관심 종목 악재 경보</b>\n\n");
        sb.append("📊 <b>").append(c.getStockName() != null ? c.getStockName() : c.getStockCode())
                .append("</b> (").append(c.getStockCode()).append(")\n");
        sb.append("🏷 유형: <b>").append(com.myplatform.backend.dto.StockCatalystDto.labelOf(c.getCatalystType()))
                .append("</b> (악재)\n");
        if (c.getSummary() != null && !c.getSummary().isBlank()) {
            sb.append("📝 ").append(c.getSummary()).append('\n');
        }
        if (c.getHeadline() != null && !c.getHeadline().isBlank()) {
            sb.append("📰 ").append(c.getHeadline()).append('\n');
        }
        if (newsLink != null && !newsLink.isBlank()) {
            sb.append("🔗 ").append(newsLink).append('\n');
        }
        sb.append("\nℹ️ 뉴스 기반 자동 분류 — 산식 미반영, 직접 확인 후 판단하세요.\n");
        sb.append("━━━━━━━━━━━━━━━━\n");
        sb.append("🤖 MyPlatform 악재 경보");
        return sb.toString();
    }

    private void recordSent(StockCatalyst c, String key) {
        try {
            AlertHistory h = new AlertHistory();
            h.setAlertKey(key);
            h.setStockCode(c.getStockCode());
            h.setStockName(c.getStockName());
            h.setInvestorType("COMMON");
            h.setAlertType(ALERT_TYPE);
            alertHistoryRepository.save(h);
        } catch (Exception e) {
            log.debug("[악재경보] 이력 저장 실패({}): {}", key, e.getMessage());
        }
    }

    private boolean isWatched(String stockCode) {
        try {
            return watchlistRepository.existsByStockCodeAndIsActiveTrue(stockCode);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isHeld(String stockCode) {
        try {
            if (botPositionRepository.existsByStockCode(stockCode)) return true;
        } catch (Exception e) { /* best-effort */ }
        return kisHoldings().contains(stockCode);
    }

    /** KIS 실잔고 종목코드 — 10분 캐시, best-effort(미설정/실패=빈 집합, §4c: 미확인을 보유로 위장 안 함). */
    private Set<String> kisHoldings() {
        long now = System.currentTimeMillis();
        if (now - kisHoldingsFetchedAt < KIS_HOLDINGS_TTL_MS) return kisHoldingsCache;
        Set<String> codes = new HashSet<>();
        try {
            KoreaInvestmentService kis = kisProvider.getIfAvailable();
            RealTradeService rts = realTradeProvider.getIfAvailable();
            if (kis != null && rts != null && kis.isRealTradingConfigured()) {
                List<com.myplatform.backend.dto.PaperTradingDto.PortfolioItemDto> portfolio = rts.getPortfolio();
                if (portfolio != null) {
                    for (var p : portfolio) {
                        if (p != null && p.getStockCode() != null && !p.getStockCode().isBlank()) {
                            codes.add(p.getStockCode());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[악재경보] KIS 실잔고 조회 실패(빈 집합 폴백): {}", e.getMessage());
        }
        kisHoldingsCache = Set.copyOf(codes);
        kisHoldingsFetchedAt = now;
        return kisHoldingsCache;
    }
}
