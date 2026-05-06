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
import java.util.Map;
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

        // N+1 제거: 종목별 1쿼리 → 일괄 배치 조회 (50 종목 → 50쿼리에서 1쿼리로)
        List<String> codes = list.stream()
                .map(StockWatchlist::getStockCode)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        Map<String, StockPriceDto> priceMap;
        try {
            priceMap = stockPriceService.getStockPrices(codes);
        } catch (Exception e) {
            log.warn("[Watchlist] 시세 일괄 조회 실패 - {}", e.getMessage());
            priceMap = java.util.Collections.emptyMap();
        }

        int priceOk = 0;
        int priceFail = 0;
        List<WatchlistDto.WatchlistItem> result = new ArrayList<>(list.size());
        for (StockWatchlist w : list) {
            WatchlistDto.WatchlistItem item = toDto(w);
            StockPriceDto price = priceMap.get(w.getStockCode());
            if (price != null && price.getCurrentPrice() != null) {
                item.setCurrentPrice(price.getCurrentPrice());
                item.setChangeRate(price.getChangeRate());
                priceOk++;
            } else {
                priceFail++;
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

        // N+1 제거: 두 리스트 종목코드 합쳐 1회 배치 조회
        List<String> allCodes = new ArrayList<>();
        triggeredAlerts.forEach(w -> allCodes.add(w.getStockCode()));
        pendingAlerts.forEach(w -> allCodes.add(w.getStockCode()));
        Map<String, StockPriceDto> priceMap;
        try {
            priceMap = stockPriceService.getStockPrices(
                    allCodes.stream().distinct().collect(Collectors.toList()));
        } catch (Exception e) {
            log.warn("[Watchlist] 시세 일괄 조회 실패: {}", e.getMessage());
            priceMap = java.util.Collections.emptyMap();
        }

        // 재무장: 가격이 목표가 반대로 돌아갔으면 alertTriggered=false로 리셋 (saveAll 배치)
        List<StockWatchlist> toRearm = new ArrayList<>();
        for (StockWatchlist item : triggeredAlerts) {
            StockPriceDto price = priceMap.get(item.getStockCode());
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
                toRearm.add(item);
                log.info("관심종목 알림 재무장: {} - 현재가 {} / 목표가 {} {}",
                        item.getStockName(), currentPrice, item.getTargetPrice(), item.getAlertCondition());
            }
        }
        if (!toRearm.isEmpty()) watchlistRepository.saveAll(toRearm);

        if (pendingAlerts.isEmpty()) return;

        log.info("관심종목 알림 체크: {}건", pendingAlerts.size());

        // 발동: 텔레그램은 개별 발송하되 DB save는 한 번에 모아서
        List<StockWatchlist> toTrigger = new ArrayList<>();
        for (StockWatchlist item : pendingAlerts) {
            try {
                StockPriceDto price = priceMap.get(item.getStockCode());
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
                    toTrigger.add(item);

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
        if (!toTrigger.isEmpty()) watchlistRepository.saveAll(toTrigger);
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
