package com.myplatform.backend.service;

import com.myplatform.backend.entity.ShortSellingBalance;
import com.myplatform.backend.repository.ShortSellingBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 공매도 <b>잔고</b> — 조회 전용(2026-10-02 수집 제거).
 *
 * <p>잔고 출처가 없다: KRX data 포털은 세션 거부({@code LOGOUT}), 네이버 금융 잔고 페이지는 폐지됐고, KIS 에는
 * 공매도 <b>잔고</b> API 가 없다(거래 비중만 있다). 그래서 {@code short_selling_balance} 는 비어 있고, 이 클래스의
 * 소비처 — 봇 고공매도 진입 차단({@link #isHighShortSellingStock}) · 19시 잔고 경보 — 는 데이터가 없어
 * <b>작동하지 않는다</b>(결측=미차단 극성 유지, §4c/§4d). 잔고 출처가 생기면 수집을 여기에 다시 붙인다.
 *
 * <p>⚠ 거래 비중(KIS 공매도 상위종목·일별추이)은 {@code shortselling.ShortSellingTradeService} 가 맡고
 * <b>표시 전용</b>이다 — 이 클래스의 5% 기준(잔고 비율용)에 거래 비중을 넣지 말 것(사용자 결정 2026-10-02).
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ShortSellingService {

    private final ShortSellingBalanceRepository repository;
    private final TelegramNotificationService telegramService;

    private static final BigDecimal HIGH_SHORT_RATIO = new BigDecimal("5.0");    // 5% 이상 = 높은 공매도
    private static final BigDecimal DANGER_SHORT_RATIO = new BigDecimal("10.0"); // 10% 이상 = 위험

    // ================================================================
    // 공개 API (기존 시그니처 유지)
    // ================================================================

    /**
     * 공매도 비율 상위 종목 텔레그램 알림
     */
    public void sendHighShortSellingAlert() {
        try {
            Optional<LocalDate> latestDateOpt = repository.findLatestTradeDate();
            if (latestDateOpt.isEmpty()) {
                log.info("공매도 잔고 데이터 없음 — 알림 생략");
                return;
            }

            LocalDate latestDate = latestDateOpt.get();
            List<ShortSellingBalance> highStocks = repository.findHighShortSellingStocks(latestDate, HIGH_SHORT_RATIO);

            if (highStocks.isEmpty()) {
                log.info("공매도 비율 {}% 이상 종목 없음 — 알림 생략", HIGH_SHORT_RATIO);
                return;
            }

            List<ShortSellingBalance> topStocks = highStocks.stream()
                    .limit(20)
                    .collect(Collectors.toList());

            long dangerCount = topStocks.stream()
                    .filter(s -> s.getShortSellingRatio().compareTo(DANGER_SHORT_RATIO) >= 0)
                    .count();

            StringBuilder sb = new StringBuilder();
            sb.append("<b>\uD83D\uDCC9 공매도 잔고 경보</b>\n\n");
            sb.append(String.format("\uD83D\uDCC5 기준일: <b>%s</b>\n", latestDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))));
            sb.append(String.format("\u26A0\uFE0F 공매도 비율 %s%% 이상: <b>%d종목</b>\n", HIGH_SHORT_RATIO, highStocks.size()));

            if (dangerCount > 0) {
                sb.append(String.format("\uD83D\uDED1 위험 수준 (%s%% 이상): <b>%d종목</b>\n", DANGER_SHORT_RATIO, dangerCount));
            }

            sb.append("\n<b>[ 공매도 비율 TOP ]</b>\n");
            sb.append("━━━━━━━━━━━━━━━━\n");

            for (int i = 0; i < topStocks.size(); i++) {
                ShortSellingBalance stock = topStocks.get(i);
                String dangerIcon = stock.getShortSellingRatio().compareTo(DANGER_SHORT_RATIO) >= 0 ? "\uD83D\uDED1" : "\u26A0\uFE0F";
                sb.append(String.format("%s <b>%s</b> (%s)\n", dangerIcon, stock.getStockName(), stock.getStockCode()));
                sb.append(String.format("   공매도 비율: <b>%.2f%%</b>", stock.getShortSellingRatio()));

                if (stock.getShortSellingVolume() != null) {
                    sb.append(String.format(" | 잔고: %,.0f주", stock.getShortSellingVolume()));
                }
                sb.append("\n");

                if (i < topStocks.size() - 1) {
                    sb.append("\n");
                }
            }

            sb.append("\n━━━━━━━━━━━━━━━━\n");
            sb.append(String.format("\u23F0 %s\n", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
            sb.append("\uD83E\uDD16 MyPlatform 공매도 알림");

            telegramService.sendRisk(sb.toString());
            log.info("공매도 잔고 경보 발송 완료 — {}종목 (위험: {}종목)", highStocks.size(), dangerCount);

        } catch (Exception e) {
            log.warn("공매도 잔고 알림 발송 실패: {}", e.getMessage());
        }
    }

    /**
     * 특정 종목의 공매도 <b>잔고</b> 비율 조회 (봇 연동용)
     *
     * <p>§4c: 결측(데이터 전무·종목 미포함·조회 실패)은 {@code null} — 실측 0% 와 구분한다.
     * ZERO 로 위장하면 死피드 상태에서 전 종목이 "공매도 0% 충족"으로 보인다(AUDIT 2026-07-07 P1-3).
     */
    @Transactional(readOnly = true)
    public BigDecimal getShortSellingRatio(String stockCode) {
        try {
            Optional<LocalDate> latestDateOpt = repository.findLatestTradeDate();
            if (latestDateOpt.isEmpty()) {
                return null;
            }

            Optional<ShortSellingBalance> balance =
                    repository.findByStockCodeAndTradeDate(stockCode, latestDateOpt.get());

            return balance.map(ShortSellingBalance::getShortSellingRatio).orElse(null);
        } catch (Exception e) {
            log.warn("공매도 비율 조회 실패 - {}: {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * 공매도 비율 높은 종목 Set (전략 차단용)
     */
    @Transactional(readOnly = true)
    public Set<String> getHighShortSellingStockCodes() {
        try {
            Optional<LocalDate> latestDateOpt = repository.findLatestTradeDate();
            if (latestDateOpt.isEmpty()) {
                return Collections.emptySet();
            }

            List<ShortSellingBalance> highStocks =
                    repository.findHighShortSellingStocks(latestDateOpt.get(), HIGH_SHORT_RATIO);

            return highStocks.stream()
                    .map(ShortSellingBalance::getStockCode)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("고공매도 종목 조회 실패: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * 고공매도 종목인지 확인 (봇 연동용)
     *
     * <p>결측(null)=미차단(통과) — 결측을 근거로 진입을 막지 않는다
     * (PriceSanityGuard "결측=UNKNOWN=통과" 선례와 동일 극성, §4c/§4d).
     */
    @Transactional(readOnly = true)
    public boolean isHighShortSellingStock(String stockCode) {
        BigDecimal ratio = getShortSellingRatio(stockCode);
        return ratio != null && ratio.compareTo(HIGH_SHORT_RATIO) >= 0;
    }
}
