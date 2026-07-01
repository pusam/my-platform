package com.myplatform.backend.service;

import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.dto.WatchlistDto;
import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 모닝 브리핑 서비스
 * - 매일 07:30 장 시작 전 텔레그램으로 전일 시장 요약 발송
 * - 시장 상태, 외국인/기관 연속매수, 관심종목, 마법의 공식 종목 정보 포함
 * - 브리핑 발송 후 재료 분류 워밍 (V31) — 최신 추천 BUY 컷 이상 종목의 뉴스 재료를
 *   미리 분류해 호재/악재 알림이 장 시작 전 도착하게 함 (화면 조회 없이도).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MorningBriefingService {

    private final InvestorTradeService investorTradeService;
    private final WatchlistService watchlistService;
    private final MarketTimingService marketTimingService;
    private final QuantScreenerService quantScreenerService;
    private final TelegramNotificationService telegramNotificationService;
    private final RecommendationSnapshotRepository snapshotRepository;
    private final StockCatalystService stockCatalystService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String DEFAULT_USERNAME = "admin";

    /** 재료 워밍 대상 컷 — 종합추천 BUY 임계(55)와 동일. */
    static final int CATALYST_WARM_SCORE_CUT = 55;
    /** 워밍 상한 — 종목당 네이버+Gemini 1회씩이라 Gemini quota 보호 위해 제한. */
    static final int CATALYST_WARM_MAX = 5;

    /**
     * 모닝 브리핑 발송
     */
    public void sendMorningBriefing() {
        log.info("=== 모닝 브리핑 생성 시작 ===");

        StringBuilder sb = new StringBuilder();
        String today = LocalDate.now().format(DATE_FORMATTER);

        sb.append(String.format("<b>\uD83C\uDF05 모닝 브리핑 (%s)</b>\n\n", today));

        // 1. 시장 상태 요약
        appendMarketStatus(sb);

        // 2. 외국인 연속 매수 TOP 5
        appendConsecutiveBuys(sb, "FOREIGN", "외국인", "\uD83C\uDFE6");

        // 3. 기관 연속 매수 TOP 5
        appendConsecutiveBuys(sb, "INSTITUTION", "기관", "\uD83C\uDFDB\uFE0F");

        // 4. 관심종목 현황
        appendWatchlist(sb);

        // 5. 마법의 공식 TOP 3
        appendMagicFormula(sb);

        // 푸터
        sb.append("━━━━━━━━━━━━━━━━\n");
        sb.append("\uD83E\uDD16 MyPlatform 모닝 브리핑");

        String message = sb.toString();
        telegramNotificationService.sendMessage(message);

        log.info("=== 모닝 브리핑 발송 완료 ===");

        // 6. 재료 분류 워밍 (V31) — 브리핑 발송 후 실행. 실패해도 브리핑에는 영향 없음.
        //    분류 과정에서 호재→시그널 / 악재→리스크 채널 알림이 자동 발송된다.
        warmCatalysts();
    }

    /**
     * 최신 추천 스냅샷의 BUY 컷(55점) 이상 종목 재료를 미리 분류 (최대 5종목).
     *
     * 기존엔 사용자가 오늘 탭/결론 카드를 열어야 분류가 트리거됐는데, 이 워밍으로
     * 장 시작 전(07:30 브리핑 직후)에 분류 + 호재/악재 알림이 선제 도착한다.
     * 일캐시 구조라 이미 오늘 분류된 종목은 스킵되고, 사용자가 나중에 화면을 열면
     * 캐시 히트로 즉시 배지가 뜬다.
     */
    void warmCatalysts() {
        try {
            List<RecommendationSnapshot> latest = snapshotRepository.findLatestSnapshot();
            if (latest == null || latest.isEmpty()) {
                log.debug("[모닝브리핑] 재료 워밍 — 추천 스냅샷 없음, 스킵");
                return;
            }
            // 상한 CATALYST_WARM_MAX 종목을 모아 배치 1콜로 분류(P2-CAT1) — RPM 실질 1/N.
            Set<String> seen = new HashSet<>();
            List<StockCatalystService.StockRef> refs = new ArrayList<>();
            for (RecommendationSnapshot s : latest) {
                if (refs.size() >= CATALYST_WARM_MAX) break;
                if (s.getTotalScore() < CATALYST_WARM_SCORE_CUT) continue;
                if (!seen.add(s.getStockCode())) continue;
                refs.add(new StockCatalystService.StockRef(s.getStockCode(), s.getStockName()));
            }
            int warmed = stockCatalystService.classifyBatch(refs);
            log.info("[모닝브리핑] 재료 워밍 완료 — 요청 {}종목 → {}건 저장 (BUY 컷 {}점 이상, 상한 {}, Gemini 1콜/배치)",
                    refs.size(), warmed, CATALYST_WARM_SCORE_CUT, CATALYST_WARM_MAX);
        } catch (Exception e) {
            log.warn("[모닝브리핑] 재료 워밍 실패: {}", e.getMessage());
        }
    }

    /**
     * 시장 상태 요약 섹션
     */
    private void appendMarketStatus(StringBuilder sb) {
        try {
            MarketTimingDto timing = marketTimingService.getCurrentMarketTiming();
            sb.append("\uD83D\uDCCA <b>시장 상태</b>\n");

            if (timing != null && timing.getCombinedAdr() != null) {
                String conditionLabel = timing.getOverallCondition() != null
                        ? timing.getOverallCondition().getLabel()
                        : "데이터 없음";
                sb.append(String.format("ADR: %.1f | 상태: %s\n",
                        timing.getCombinedAdr(), conditionLabel));

                if (timing.getDiagnosis() != null) {
                    sb.append(String.format("💬 %s\n", timing.getDiagnosis()));
                }
            } else {
                sb.append("데이터 없음\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.warn("모닝 브리핑 - 시장 상태 조회 실패: {}", e.getMessage());
            sb.append("\uD83D\uDCCA <b>시장 상태</b>\n");
            sb.append("조회 실패\n\n");
        }
    }

    /**
     * 연속 매수 종목 섹션
     */
    private void appendConsecutiveBuys(StringBuilder sb, String investorType, String investorLabel, String emoji) {
        try {
            List<ConsecutiveBuyDto> stocks = investorTradeService.getConsecutiveBuyStocks(investorType, 3);
            sb.append(String.format("%s <b>%s 연속 매수 TOP 5</b>\n", emoji, investorLabel));

            if (stocks != null && !stocks.isEmpty()) {
                int count = Math.min(stocks.size(), 5);
                for (int i = 0; i < count; i++) {
                    ConsecutiveBuyDto stock = stocks.get(i);
                    sb.append(String.format("%d. %s (%s) - %d일 연속",
                            i + 1, stock.getStockName(), stock.getStockCode(), stock.getConsecutiveDays()));

                    if (stock.getTotalNetBuyAmount() != null) {
                        sb.append(String.format(" (누적 %s억)", formatAmount(stock.getTotalNetBuyAmount())));
                    }
                    sb.append("\n");
                }
            } else {
                sb.append("해당 종목 없음\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.warn("모닝 브리핑 - {} 연속매수 조회 실패: {}", investorLabel, e.getMessage());
            sb.append(String.format("%s <b>%s 연속 매수 TOP 5</b>\n", emoji, investorLabel));
            sb.append("조회 실패\n\n");
        }
    }

    /**
     * 관심종목 현황 섹션
     */
    private void appendWatchlist(StringBuilder sb) {
        try {
            List<WatchlistDto.WatchlistItem> items = watchlistService.getWatchlist(DEFAULT_USERNAME);
            sb.append("⭐ <b>관심종목</b>\n");

            if (items != null && !items.isEmpty()) {
                for (WatchlistDto.WatchlistItem item : items) {
                    sb.append(String.format("• %s (%s)", item.getStockName(), item.getStockCode()));

                    if (item.getCurrentPrice() != null) {
                        sb.append(String.format(" %s원", formatPrice(item.getCurrentPrice())));
                    }
                    if (item.getTargetPrice() != null) {
                        sb.append(String.format(" (목표가: %s)", formatPrice(item.getTargetPrice())));
                    }
                    if (item.getChangeRate() != null) {
                        sb.append(String.format(" %+.2f%%", item.getChangeRate()));
                    }
                    sb.append("\n");
                }
            } else {
                sb.append("등록된 관심종목 없음\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.warn("모닝 브리핑 - 관심종목 조회 실패: {}", e.getMessage());
            sb.append("⭐ <b>관심종목</b>\n");
            sb.append("조회 실패\n\n");
        }
    }

    /**
     * 마법의 공식 TOP 3 섹션
     */
    private void appendMagicFormula(StringBuilder sb) {
        try {
            List<ScreenerResultDto> stocks = quantScreenerService.getMagicFormulaStocks(3, null);
            sb.append("✨ <b>마법의 공식 TOP 3</b>\n");

            if (stocks != null && !stocks.isEmpty()) {
                for (int i = 0; i < stocks.size(); i++) {
                    ScreenerResultDto stock = stocks.get(i);
                    sb.append(String.format("%d. %s (%s)", i + 1, stock.getStockName(), stock.getStockCode()));

                    if (stock.getPer() != null) {
                        sb.append(String.format(" PER %.1f", stock.getPer()));
                    }
                    if (stock.getRoe() != null) {
                        sb.append(String.format(" ROE %.1f%%", stock.getRoe()));
                    }
                    sb.append("\n");
                }
            } else {
                sb.append("해당 종목 없음\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.warn("모닝 브리핑 - 마법의 공식 조회 실패: {}", e.getMessage());
            sb.append("✨ <b>마법의 공식 TOP 3</b>\n");
            sb.append("조회 실패\n\n");
        }
    }

    /**
     * 가격 포맷팅 (천 단위 콤마)
     */
    private String formatPrice(BigDecimal price) {
        if (price == null) return "N/A";
        return String.format("%,.0f", price);
    }

    /**
     * 금액 포맷팅 (소수점 1자리)
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "0";
        return String.format("%,.1f", amount);
    }
}
