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
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WatchlistService {

    private final StockWatchlistRepository watchlistRepository;
    private final StockPriceService stockPriceService;
    private final TelegramNotificationService telegramNotificationService;

    private static final String DEFAULT_USERNAME = "default";

    @Transactional(readOnly = true)
    public List<WatchlistDto.WatchlistItem> getWatchlist() {
        List<StockWatchlist> list = watchlistRepository.findByUsernameOrderByCreatedAtDesc(DEFAULT_USERNAME);

        return list.stream().map(w -> {
            WatchlistDto.WatchlistItem item = toDto(w);
            // 현재가 조회
            try {
                StockPriceDto price = stockPriceService.getStockPrice(w.getStockCode());
                if (price != null) {
                    item.setCurrentPrice(price.getCurrentPrice());
                    item.setChangeRate(price.getChangeRate());
                }
            } catch (Exception e) {
                log.debug("관심종목 현재가 조회 실패: {} - {}", w.getStockCode(), e.getMessage());
            }
            return item;
        }).collect(Collectors.toList());
    }

    @Transactional
    public WatchlistDto.WatchlistItem addWatchlist(WatchlistDto.AddRequest request) {
        if (watchlistRepository.existsByUsernameAndStockCode(DEFAULT_USERNAME, request.getStockCode())) {
            throw new IllegalArgumentException("이미 관심종목에 등록된 종목입니다.");
        }

        StockWatchlist entity = StockWatchlist.builder()
                .username(DEFAULT_USERNAME)
                .stockCode(request.getStockCode())
                .stockName(request.getStockName())
                .isActive(true)
                .alertTriggered(false)
                .build();

        watchlistRepository.save(entity);
        log.info("관심종목 추가: {} ({})", request.getStockName(), request.getStockCode());

        return toDto(entity);
    }

    @Transactional
    public WatchlistDto.WatchlistItem updateAlert(Long id, WatchlistDto.AlertRequest request) {
        StockWatchlist entity = watchlistRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("관심종목을 찾을 수 없습니다."));

        entity.setTargetPrice(request.getTargetPrice());
        entity.setAlertCondition(request.getAlertCondition());
        entity.setAlertTriggered(false); // 알림 재설정 시 리셋
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
    public boolean isBookmarked(String stockCode) {
        return watchlistRepository.existsByUsernameAndStockCode(DEFAULT_USERNAME, stockCode);
    }

    /**
     * 목표가 알림 체크 (스케줄러에서 호출)
     */
    @Transactional
    public void checkWatchlistAlerts() {
        List<StockWatchlist> activeAlerts = watchlistRepository
                .findByIsActiveAndAlertTriggeredAndTargetPriceIsNotNull(true, false);

        if (activeAlerts.isEmpty()) return;

        log.info("관심종목 알림 체크: {}건", activeAlerts.size());

        for (StockWatchlist item : activeAlerts) {
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
