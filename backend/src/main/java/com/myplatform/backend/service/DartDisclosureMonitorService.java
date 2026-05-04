package com.myplatform.backend.service;

import com.myplatform.backend.dto.PaperTradingDto.PortfolioItemDto;
import com.myplatform.backend.dto.RiskAnalysisDto.DartDisclosure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 보유/관심 종목의 중대 DART 공시를 즉시 감지해 텔레그램 리스크 채널로 발송.
 *
 * 흐름:
 *   1) 평일 장중 5분마다 + 장외 시간대 1시간마다 실행 (공시는 장외 시간에도 발표됨)
 *   2) 실전 보유 종목 + 관심종목 리스트 합쳐서 종목명 집합 생성
 *   3) 각 종목에 대해 DartService.searchDisclosuresByName → filterDangerousDisclosures
 *   4) 이미 본 공시(rceptNo 기준)는 Redis 에 3일 TTL 로 저장 — 중복 방지
 *   5) 신규 중대 공시만 텔레그램 전송
 *
 * DART API Rate: 일 10,000회 (기업별 3개월 = 1 call). 종목 수 × 12회/시간 = 충분.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DartDisclosureMonitorService {

    private static final String CACHE_SEEN = "dartSeen";
    private static final Duration SEEN_TTL = Duration.ofDays(3);

    private final DartService dartService;
    private final RealTradeService realTradeService;
    private final KoreaInvestmentService kisService;
    private final TelegramNotificationService telegramService;
    private final RedisCacheService redisCacheService;

    /**
     * 거래시간(NXT 8-20 + KRX) 5분마다.
     */
    @Scheduled(cron = "0 */5 8-19 * * MON-FRI", zone = "Asia/Seoul")
    public void checkIntraday() {
        runCheck("장중");
    }

    /**
     * 장외 야간 (20시~23시). 평일 야간 공시 대응.
     */
    @Scheduled(cron = "0 0 20-23 * * MON-FRI", zone = "Asia/Seoul")
    public void checkAfterHoursEvening() {
        runCheck("장외-야간");
    }

    /**
     * 장외 새벽 (00시~07시) — 다음날 새벽이므로 화/수/목/금/토 실행.
     * 기존 0-8 with MON-FRI 패턴은 토요일 새벽도 잘못 포함되는 cross-day 버그가 있어 분리.
     */
    @Scheduled(cron = "0 0 0-7 * * TUE-SAT", zone = "Asia/Seoul")
    public void checkAfterHoursDawn() {
        runCheck("장외-새벽");
    }

    private void runCheck(String mode) {
        if (!dartService.isAvailable()) return;

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
     * 보유 + 관심 종목 이름 집합 구성.
     * WatchlistService 는 사용자별이라 여기선 "전체 관심종목" 대신 실전 보유만 써도 무방.
     */
    private Set<String> collectTargetStockNames() {
        Set<String> names = new LinkedHashSet<>();
        try {
            if (kisService.isRealTradingConfigured()) {
                List<PortfolioItemDto> portfolio = realTradeService.getPortfolio();
                if (portfolio != null) {
                    for (PortfolioItemDto p : portfolio) {
                        if (p.getStockName() != null && !p.getStockName().isBlank()) {
                            names.add(p.getStockName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[DART monitor] 실전 포트폴리오 조회 실패: {}", e.getMessage());
        }
        // 관심종목 — 현재 WatchlistService 는 username 파라미터를 받는 구조라
        // 전체 사용자의 관심종목 집계 API 가 없으면 우선 보유만 사용.
        // 추후 확장 포인트.
        return names;
    }

    /**
     * 특정 종목의 신규 중대 공시를 알림. 반환: 새로 알린 건수.
     */
    private int processStock(String stockName) {
        try {
            List<DartDisclosure> all = dartService.searchDisclosuresByName(stockName);
            if (all == null || all.isEmpty()) return 0;
            List<DartDisclosure> dangerous = dartService.filterDangerousDisclosures(all);
            if (dangerous.isEmpty()) return 0;

            int sent = 0;
            for (DartDisclosure d : dangerous) {
                String rceptNo = d.getRceptNo();
                if (rceptNo == null || rceptNo.isBlank()) continue;

                // 이미 알린 공시면 스킵
                String seen = redisCacheService.get(CACHE_SEEN, rceptNo, String.class);
                if (seen != null) continue;

                // 알림 발송
                telegramService.sendRisk(buildMessage(stockName, d));
                redisCacheService.put(CACHE_SEEN, rceptNo, "1", SEEN_TTL);
                sent++;
            }
            return sent;
        } catch (Exception e) {
            log.debug("[DART monitor] {} 처리 실패: {}", stockName, e.getMessage());
            return 0;
        }
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
