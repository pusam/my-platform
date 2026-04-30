package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.dto.WatchlistDto;
import com.myplatform.backend.entity.StockWatchlist;
import com.myplatform.backend.repository.StockWatchlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WatchlistService {

    private final StockWatchlistRepository watchlistRepository;
    private final StockPriceService stockPriceService;
    private final TelegramNotificationService telegramNotificationService;

    @Transactional(readOnly = true)
    public List<WatchlistDto.WatchlistItem> getWatchlist(String username) {
        List<StockWatchlist> list = watchlistRepository.findByUsernameOrderByCreatedAtDesc(username);
        if (list.isEmpty()) return List.of();

        int priceOk = 0;
        int priceFail = 0;
        List<WatchlistDto.WatchlistItem> result = new ArrayList<>(list.size());
        for (StockWatchlist w : list) {
            WatchlistDto.WatchlistItem item = toDto(w);
            try {
                StockPriceDto price = stockPriceService.getStockPrice(w.getStockCode());
                if (price != null && price.getCurrentPrice() != null) {
                    item.setCurrentPrice(price.getCurrentPrice());
                    item.setChangeRate(price.getChangeRate());
                    priceOk++;
                } else {
                    priceFail++;
                }
            } catch (Exception e) {
                priceFail++;
                log.warn("[Watchlist] 시세 조회 실패: {} ({}) - {}",
                        w.getStockName(), w.getStockCode(), e.getMessage());
            }
            result.add(item);
        }
        if (priceFail > 0) {
            // 한 번이라도 실패가 있을 때만 통계 노출 (성공만 있으면 조용히)
            log.warn("[Watchlist] {} - {}건 (시세 OK {} / 실패 {})",
                    username, list.size(), priceOk, priceFail);
        }
        return result;
    }

    @Transactional
    public WatchlistDto.WatchlistItem addWatchlist(String username, WatchlistDto.AddRequest request) {
        if (watchlistRepository.existsByUsernameAndStockCode(username, request.getStockCode())) {
            throw new IllegalArgumentException("이미 관심종목에 등록된 종목입니다.");
        }

        StockWatchlist entity = StockWatchlist.builder()
                .username(username)
                .stockCode(request.getStockCode())
                .stockName(request.getStockName())
                .isActive(true)
                .alertTriggered(false)
                .build();

        watchlistRepository.save(entity);
        log.info("관심종목 추가: {} ({}) - user: {}", request.getStockName(), request.getStockCode(), username);

        return toDto(entity);
    }

    @Transactional
    public WatchlistDto.WatchlistItem updateAlert(Long id, WatchlistDto.AlertRequest request) {
        StockWatchlist entity = watchlistRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("관심종목을 찾을 수 없습니다."));

        entity.setTargetPrice(request.getTargetPrice());
        entity.setAlertCondition(request.getAlertCondition());
        entity.setAlertTriggered(false);
        entity.setIsActive(true);

        watchlistRepository.save(entity);
        log.info("관심종목 알림 설정: {} - {} {}원",
                entity.getStockName(), request.getAlertCondition(), request.getTargetPrice());

        return toDto(entity);
    }

    @Transactional
    public void deleteWatchlist(Long id) {
        StockWatchlist entity = watchlistRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("관심종목을 찾을 수 없습니다."));
        log.info("관심종목 삭제: {} ({})", entity.getStockName(), entity.getStockCode());
        watchlistRepository.delete(entity);
    }

    @Transactional(readOnly = true)
    public boolean isBookmarked(String username, String stockCode) {
        return watchlistRepository.existsByUsernameAndStockCode(username, stockCode);
    }

    /**
     * 목표가 알림 체크 (스케줄러에서 호출 - 전체 사용자 대상)
     *
     * 알림 재발화 정책:
     * - 한 번 발동되면 중복 알림 방지 (alertTriggered=true)
     * - 단, 가격이 목표가 반대 방향으로 돌아가면 자동 재무장(알림 플래그 리셋)
     *   예) BELOW 9000원 알림 발동 → 가격 10000원 상승 → 재무장 → 다시 9000 하락시 알림
     */
    @Transactional
    public void checkWatchlistAlerts() {
        // 1. 발동 대기 상태 (미발동 + 활성) — 알림 발송 대상
        List<StockWatchlist> pendingAlerts = watchlistRepository
                .findByIsActiveAndAlertTriggeredAndTargetPriceIsNotNull(true, false);
        // 2. 이미 발동된 상태 — 재무장(리셋) 대상 체크
        List<StockWatchlist> triggeredAlerts = watchlistRepository
                .findByIsActiveAndAlertTriggeredAndTargetPriceIsNotNull(true, true);

        // 재무장: 가격이 목표가 반대로 돌아갔으면 alertTriggered=false로 리셋
        for (StockWatchlist item : triggeredAlerts) {
            try {
                StockPriceDto price = stockPriceService.getStockPrice(item.getStockCode());
                if (price == null || price.getCurrentPrice() == null) continue;

                BigDecimal currentPrice = price.getCurrentPrice();
                boolean shouldRearm = false;
                // ABOVE 알림: 가격이 목표가 아래로 내려가면 재무장
                if ("ABOVE".equals(item.getAlertCondition())
                        && currentPrice.compareTo(item.getTargetPrice()) < 0) {
                    shouldRearm = true;
                // BELOW 알림: 가격이 목표가 위로 올라가면 재무장
                } else if ("BELOW".equals(item.getAlertCondition())
                        && currentPrice.compareTo(item.getTargetPrice()) > 0) {
                    shouldRearm = true;
                }

                if (shouldRearm) {
                    item.setAlertTriggered(false);
                    watchlistRepository.save(item);
                    log.info("관심종목 알림 재무장: {} - 현재가 {} / 목표가 {} {}",
                            item.getStockName(), currentPrice, item.getTargetPrice(), item.getAlertCondition());
                }
            } catch (Exception e) {
                log.debug("알림 재무장 체크 실패: {} - {}", item.getStockCode(), e.getMessage());
            }
        }

        if (pendingAlerts.isEmpty()) return;

        log.info("관심종목 알림 체크: {}건", pendingAlerts.size());

        for (StockWatchlist item : pendingAlerts) {
            try {
                StockPriceDto price = stockPriceService.getStockPrice(item.getStockCode());
                if (price == null || price.getCurrentPrice() == null) continue;

                BigDecimal currentPrice = price.getCurrentPrice();
                boolean triggered = false;

                if ("ABOVE".equals(item.getAlertCondition())
                        && currentPrice.compareTo(item.getTargetPrice()) >= 0) {
                    triggered = true;
                } else if ("BELOW".equals(item.getAlertCondition())
                        && currentPrice.compareTo(item.getTargetPrice()) <= 0) {
                    triggered = true;
                }

                if (triggered) {
                    item.setAlertTriggered(true);
                    watchlistRepository.save(item);

                    String condition = "ABOVE".equals(item.getAlertCondition()) ? "이상 돌파" : "이하 하락";
                    String message = String.format(
                            """
                            <b>🔔 목표가 알림!</b>

                            📊 <b>%s</b> (%s)
                            💰 현재가: <b>%s원</b>
                            🎯 목표가: %s원 %s

                            ━━━━━━━━━━━━━━━━
                            🤖 MyPlatform 관심종목 알림
                            """,
                            item.getStockName(), item.getStockCode(),
                            formatPrice(currentPrice),
                            formatPrice(item.getTargetPrice()), condition
                    );

                    telegramNotificationService.sendMessage(message);
                    log.info("목표가 알림 발송: {} - 현재가 {} / 목표가 {} {}",
                            item.getStockName(), currentPrice, item.getTargetPrice(), condition);
                }
            } catch (Exception e) {
                log.warn("관심종목 알림 체크 실패: {} - {}", item.getStockCode(), e.getMessage());
            }
        }
    }

    private WatchlistDto.WatchlistItem toDto(StockWatchlist entity) {
        return WatchlistDto.WatchlistItem.builder()
                .id(entity.getId())
                .stockCode(entity.getStockCode())
                .stockName(entity.getStockName())
                .targetPrice(entity.getTargetPrice())
                .alertCondition(entity.getAlertCondition())
                .isActive(entity.getIsActive())
                .alertTriggered(entity.getAlertTriggered())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) return "N/A";
        return String.format("%,.0f", price);
    }
}
