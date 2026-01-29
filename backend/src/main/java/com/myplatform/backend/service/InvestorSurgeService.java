package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.InvestorSurgeDto;
import com.myplatform.backend.entity.InvestorIntradaySnapshot;
import com.myplatform.backend.repository.InvestorIntradaySnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 외국인/기관 수급 급증 감지 서비스
 * - 장중 10분 단위로 순매수 데이터 수집
 * - 이전 스냅샷 대비 변화량 계산
 * - 급증 종목 감지
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class InvestorSurgeService {

    private final InvestorIntradaySnapshotRepository snapshotRepository;
    private final KoreaInvestmentService koreaInvestmentService;
    private final TelegramNotificationService telegramService;

    // 급증 기준값 (억원)
    private static final BigDecimal SURGE_THRESHOLD_HOT = new BigDecimal("100");   // 100억 이상
    private static final BigDecimal SURGE_THRESHOLD_WARM = new BigDecimal("50");   // 50억 이상
    private static final BigDecimal COMMON_SURGE_THRESHOLD = new BigDecimal("30"); // 쌍끌이 합산 30억 이상

    // 알림 재발송 금지 (30분 내 동일 종목)
    private static final long ALERT_COOLDOWN_MINUTES = 30;
    private final Map<String, LocalDateTime> alertSentMap = new ConcurrentHashMap<>();

    /**
     * 장중 10분마다 외국인/기관 순매수 데이터 수집
     * 평일 09:10 ~ 15:20 사이에만 실행
     */
    @Scheduled(cron = "0 0/10 9-15 * * MON-FRI")
    public void collectIntradaySnapshot() {
        LocalTime now = LocalTime.now();

        // 09:00 이전, 15:30 이후는 수집하지 않음
        if (now.isBefore(LocalTime.of(9, 5)) || now.isAfter(LocalTime.of(15, 25))) {
            return;
        }

        log.info("장중 스냅샷 수집 시작: {}", now);

        try {
            // 외국인, 기관 순서로 수집
            List<InvestorIntradaySnapshot> foreignSnapshots = collectAndSaveSnapshot("FOREIGN");
            Thread.sleep(1000); // API 호출 간격
            List<InvestorIntradaySnapshot> institutionSnapshots = collectAndSaveSnapshot("INSTITUTION");

            log.info("장중 스냅샷 수집 완료");

            // HOT 등급 또는 쌍끌이 종목 알림 발송
            sendSurgeAlerts(foreignSnapshots, institutionSnapshots);
        } catch (Exception e) {
            log.error("장중 스냅샷 수집 실패", e);
        }
    }

    /**
     * 특정 투자자 유형의 스냅샷 수집 및 저장
     * @return 저장된 스냅샷 리스트 (알림 발송용)
     */
    private List<InvestorIntradaySnapshot> collectAndSaveSnapshot(String investorType) {
        List<InvestorIntradaySnapshot> snapshots = new ArrayList<>();
        try {
            LocalDate today = LocalDate.now();
            // 10분 단위로 정규화
            LocalTime snapshotTime = LocalTime.now()
                    .withSecond(0).withNano(0)
                    .withMinute((LocalTime.now().getMinute() / 10) * 10);

            // 직전 스냅샷 시간 조회
            Optional<LocalTime> prevTimeOpt = snapshotRepository.findPreviousSnapshotTime(
                    today, investorType, snapshotTime);

            // 직전 스냅샷 데이터 맵 (종목코드 -> 스냅샷)
            Map<String, InvestorIntradaySnapshot> prevSnapshots = new HashMap<>();
            if (prevTimeOpt.isPresent()) {
                List<InvestorIntradaySnapshot> prevList = snapshotRepository
                        .findBySnapshotDateAndSnapshotTimeAndInvestorTypeOrderByRankNumAsc(
                                today, prevTimeOpt.get(), investorType);
                prevSnapshots = prevList.stream()
                        .collect(Collectors.toMap(InvestorIntradaySnapshot::getStockCode, s -> s, (a, b) -> a));
            }

            // API 호출하여 현재 데이터 수집
            snapshots = fetchCurrentRanking(investorType, today, snapshotTime);

            // 변화량 계산
            for (InvestorIntradaySnapshot snapshot : snapshots) {
                InvestorIntradaySnapshot prev = prevSnapshots.get(snapshot.getStockCode());
                if (prev != null) {
                    BigDecimal amountChange = snapshot.getNetBuyAmount()
                            .subtract(prev.getNetBuyAmount());
                    snapshot.setAmountChange(amountChange);

                    Integer rankChange = prev.getRankNum() - snapshot.getRankNum(); // 양수면 순위 상승
                    snapshot.setRankChange(rankChange);
                } else {
                    // 첫 번째 스냅샷은 비교 대상이 없으므로 변화량 0
                    snapshot.setAmountChange(BigDecimal.ZERO);
                    snapshot.setRankChange(0);
                }
            }

            // 중복 방지: 동일 시간대 기존 데이터 삭제 후 저장
            snapshotRepository.deleteBySnapshotDateAndSnapshotTimeAndInvestorType(today, snapshotTime, investorType);
            snapshotRepository.saveAll(snapshots);
            log.info("스냅샷 저장 완료: {} - {}건", investorType, snapshots.size());

        } catch (Exception e) {
            log.error("스냅샷 수집 실패: {}", investorType, e);
        }
        return snapshots;
    }

    /**
     * 현재 순매수 순위 조회 (KoreaInvestmentService 사용)
     */
    private List<InvestorIntradaySnapshot> fetchCurrentRanking(
            String investorType, LocalDate date, LocalTime time) {

        List<InvestorIntradaySnapshot> snapshots = new ArrayList<>();

        try {
            String investorCode = "FOREIGN".equals(investorType) ? "1" : "2";

            log.info("스냅샷 수집 API 호출: investorType={}, investorCode={}", investorType, investorCode);

            // KoreaInvestmentService를 통해 API 호출
            JsonNode response = koreaInvestmentService.getForeignInstitutionTotal(investorCode, true, true);

            if (response == null) {
                log.error("스냅샷 수집 API 응답이 null입니다: {}", investorType);
                return snapshots;
            }

            String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText() : "";
            if (!"0".equals(rtCd)) {
                log.warn("API 오류: {} - {}", rtCd, response.has("msg1") ? response.get("msg1").asText() : "");
                return snapshots;
            }

            JsonNode output = response.get("output");
            if (output != null && output.isArray()) {
                log.info("스냅샷 파싱할 데이터 개수: {}", output.size());
                int rank = 1;

                for (JsonNode item : output) {
                    if (rank > 50) break;

                    String stockCode = item.has("mksc_shrn_iscd") ? item.get("mksc_shrn_iscd").asText() : null;
                    String stockName = item.has("hts_kor_isnm") ? item.get("hts_kor_isnm").asText() : null;

                    if (stockCode == null || stockName == null) continue;

                    // 순매수 금액 - 투자자 유형에 따라 다른 필드 사용 (백만원 단위)
                    String netBuyField = "FOREIGN".equals(investorType) ? "frgn_ntby_tr_pbmn" : "orgn_ntby_tr_pbmn";
                    BigDecimal netBuyAmount = BigDecimal.ZERO;
                    if (item.has(netBuyField)) {
                        try {
                            netBuyAmount = new BigDecimal(item.get(netBuyField).asText().replace(",", ""))
                                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP); // 백만원 -> 억원
                        } catch (Exception e) {
                            log.debug("순매수 금액 파싱 실패: {}", item.get(netBuyField));
                        }
                    }

                    BigDecimal currentPrice = BigDecimal.ZERO;
                    if (item.has("stck_prpr")) {
                        try {
                            currentPrice = new BigDecimal(item.get("stck_prpr").asText().replace(",", ""));
                        } catch (Exception ignored) {}
                    }

                    BigDecimal changeRate = BigDecimal.ZERO;
                    if (item.has("prdy_ctrt")) {
                        try {
                            changeRate = new BigDecimal(item.get("prdy_ctrt").asText().replace(",", ""));
                        } catch (Exception ignored) {}
                    }

                    InvestorIntradaySnapshot snapshot = InvestorIntradaySnapshot.builder()
                            .snapshotDate(date)
                            .snapshotTime(time)
                            .stockCode(stockCode)
                            .stockName(stockName)
                            .investorType(investorType)
                            .netBuyAmount(netBuyAmount)
                            .currentPrice(currentPrice)
                            .changeRate(changeRate)
                            .rankNum(rank)
                            .build();

                    snapshots.add(snapshot);
                    rank++;
                }

                log.info("스냅샷 수집 완료: {} - {}건", investorType, snapshots.size());
            }
        } catch (Exception e) {
            log.error("스냅샷 순위 조회 실패: {}", investorType, e);
        }

        return snapshots;
    }

    /**
     * 수급 급증 종목 조회
     */
    @Transactional(readOnly = true)
    public List<InvestorSurgeDto> getSurgeStocks(String investorType, BigDecimal minChange) {
        LocalDate today = LocalDate.now();

        // 주말이면 금요일 데이터 조회
        if (today.getDayOfWeek() == DayOfWeek.SATURDAY) {
            today = today.minusDays(1);
        } else if (today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            today = today.minusDays(2);
        }

        Optional<LocalTime> latestTimeOpt = snapshotRepository.findLatestSnapshotTime(today, investorType);

        // 오늘 데이터가 없으면 최근 영업일 데이터 조회
        if (latestTimeOpt.isEmpty()) {
            // 최대 7일 전까지 데이터 찾기
            for (int i = 1; i <= 7; i++) {
                LocalDate prevDate = today.minusDays(i);
                // 주말 스킵
                if (prevDate.getDayOfWeek() == DayOfWeek.SATURDAY ||
                    prevDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    continue;
                }
                latestTimeOpt = snapshotRepository.findLatestSnapshotTime(prevDate, investorType);
                if (latestTimeOpt.isPresent()) {
                    today = prevDate;
                    break;
                }
            }
        }

        if (latestTimeOpt.isEmpty()) {
            log.info("스냅샷 데이터 없음: investorType={}", investorType);
            return Collections.emptyList();
        }

        LocalTime latestTime = latestTimeOpt.get();
        log.info("최신 스냅샷 조회: date={}, time={}, investorType={}", today, latestTime, investorType);

        // 전체 스냅샷을 가져와서 금액 기준 정렬
        List<InvestorIntradaySnapshot> surgeSnapshots = snapshotRepository.findLatestSnapshots(
                today, latestTime, investorType);

        log.info("스냅샷 조회 결과: {} 건, date={}, time={}, investorType={}",
                surgeSnapshots.size(), today, latestTime, investorType);

        // minChange 필터 적용 (선택적)
        if (minChange != null && minChange.compareTo(BigDecimal.ZERO) > 0) {
            final BigDecimal filterAmount = minChange;
            surgeSnapshots = surgeSnapshots.stream()
                    .filter(s -> (s.getNetBuyAmount() != null && s.getNetBuyAmount().compareTo(filterAmount) >= 0))
                    .collect(Collectors.toList());
        }

        // 종목코드 기준 중복 제거 (가장 최신 데이터만 유지)
        Map<String, InvestorIntradaySnapshot> uniqueSnapshots = new LinkedHashMap<>();
        for (InvestorIntradaySnapshot snapshot : surgeSnapshots) {
            String key = snapshot.getStockCode();
            if (!uniqueSnapshots.containsKey(key)) {
                uniqueSnapshots.put(key, snapshot);
            }
        }

        return uniqueSnapshots.values().stream()
                .map(this::toSurgeDto)
                .collect(Collectors.toList());
    }

    /**
     * 전체 투자자 수급 급증 종목 조회
     */
    @Transactional(readOnly = true)
    public Map<String, List<InvestorSurgeDto>> getAllSurgeStocks(BigDecimal minChange) {
        Map<String, List<InvestorSurgeDto>> result = new HashMap<>();

        List<InvestorSurgeDto> foreignStocks = getSurgeStocks("FOREIGN", minChange);
        List<InvestorSurgeDto> institutionStocks = getSurgeStocks("INSTITUTION", minChange);

        result.put("FOREIGN", foreignStocks);
        result.put("INSTITUTION", institutionStocks);
        result.put("COMMON", getCommonStocks(foreignStocks, institutionStocks));

        return result;
    }

    /**
     * 외국인과 기관 공통 순매수 종목 조회
     */
    @Transactional(readOnly = true)
    public List<InvestorSurgeDto> getCommonSurgeStocks(BigDecimal minChange) {
        List<InvestorSurgeDto> foreignStocks = getSurgeStocks("FOREIGN", minChange);
        List<InvestorSurgeDto> institutionStocks = getSurgeStocks("INSTITUTION", minChange);
        return getCommonStocks(foreignStocks, institutionStocks);
    }

    /**
     * 두 리스트에서 공통 종목 추출
     * - Null Safety: BigDecimal 연산 시 null을 ZERO로 처리
     * - Map 중복 키: 최신 값으로 덮어쓰기
     * - 순위 재산정: 합산 금액 기준 내림차순 정렬 후 1위부터 순위 부여
     */
    private List<InvestorSurgeDto> getCommonStocks(List<InvestorSurgeDto> foreignStocks,
                                                    List<InvestorSurgeDto> institutionStocks) {
        // Null Safety: 입력 리스트가 null이면 빈 리스트로 처리
        if (foreignStocks == null || institutionStocks == null) {
            return Collections.emptyList();
        }

        // 기관 종목 코드 Set 생성
        Set<String> institutionCodes = institutionStocks.stream()
                .map(InvestorSurgeDto::getStockCode)
                .filter(code -> code != null)
                .collect(Collectors.toSet());

        // 기관 데이터 Map으로 변환 (중복 키 발생 시 최신 값으로 덮어쓰기)
        Map<String, InvestorSurgeDto> institutionMap = institutionStocks.stream()
                .filter(s -> s.getStockCode() != null)
                .collect(Collectors.toMap(
                        InvestorSurgeDto::getStockCode,
                        s -> s,
                        (existing, replacement) -> replacement
                ));

        // 외국인 종목 중 기관에도 있는 종목만 필터링하고, 합산 정보로 DTO 생성
        List<InvestorSurgeDto> commonStocks = foreignStocks.stream()
                .filter(foreign -> foreign.getStockCode() != null)
                .filter(foreign -> institutionCodes.contains(foreign.getStockCode()))
                .map(foreign -> {
                    InvestorSurgeDto institution = institutionMap.get(foreign.getStockCode());

                    // Null Safety: BigDecimal 연산 시 null을 ZERO로 처리
                    BigDecimal foreignNetBuy = foreign.getNetBuyAmount() != null
                            ? foreign.getNetBuyAmount() : BigDecimal.ZERO;
                    BigDecimal instNetBuy = institution.getNetBuyAmount() != null
                            ? institution.getNetBuyAmount() : BigDecimal.ZERO;
                    BigDecimal foreignChange = foreign.getAmountChange() != null
                            ? foreign.getAmountChange() : BigDecimal.ZERO;
                    BigDecimal instChange = institution.getAmountChange() != null
                            ? institution.getAmountChange() : BigDecimal.ZERO;

                    // 외국인 + 기관 합산
                    BigDecimal totalNetBuy = foreignNetBuy.add(instNetBuy);
                    BigDecimal totalChange = foreignChange.add(instChange);

                    // surgeLevel 결정
                    String surgeLevel = "NORMAL";
                    if (totalNetBuy.compareTo(SURGE_THRESHOLD_HOT) >= 0) {
                        surgeLevel = "HOT";
                    } else if (totalNetBuy.compareTo(SURGE_THRESHOLD_WARM) >= 0) {
                        surgeLevel = "WARM";
                    }

                    // trendStatus 계산
                    String trendStatus = calculateTrendStatus(totalNetBuy, totalChange);
                    String trendStatusName = getTrendStatusName(trendStatus);

                    // 공통 종목용 DTO 생성 (currentRank는 나중에 재산정)
                    return InvestorSurgeDto.builder()
                            .stockCode(foreign.getStockCode())
                            .stockName(foreign.getStockName())
                            .investorType("COMMON")
                            .investorTypeName("외국인+기관")
                            .snapshotTime(foreign.getSnapshotTime())
                            .netBuyAmount(totalNetBuy)
                            .amountChange(totalChange)
                            .currentPrice(foreign.getCurrentPrice())
                            .changeRate(foreign.getChangeRate())
                            .currentRank(0) // 임시값, 아래에서 재산정
                            .rankChange(0)
                            .foreignNetBuy(foreignNetBuy)
                            .institutionNetBuy(instNetBuy)
                            .surgeLevel(surgeLevel)
                            .trendStatus(trendStatus)
                            .trendStatusName(trendStatusName)
                            .build();
                })
                .sorted((a, b) -> b.getNetBuyAmount().compareTo(a.getNetBuyAmount())) // 합산 금액 내림차순 정렬
                .collect(Collectors.toList());

        // 순위 재산정: 정렬된 리스트 기준으로 1위부터 순위 부여
        for (int i = 0; i < commonStocks.size(); i++) {
            commonStocks.get(i).setCurrentRank(i + 1);
        }

        return commonStocks;
    }

    /**
     * 특정 종목의 당일 수급 추이 조회
     */
    @Transactional(readOnly = true)
    public List<InvestorSurgeDto> getStockIntradayTrend(String stockCode, String investorType) {
        LocalDate today = LocalDate.now();

        // 주말 처리
        if (today.getDayOfWeek() == DayOfWeek.SATURDAY) {
            today = today.minusDays(1);
        } else if (today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            today = today.minusDays(2);
        }

        // 오늘 해당 종목 데이터가 있는지 확인
        List<InvestorIntradaySnapshot> snapshots = snapshotRepository
                .findByStockCodeAndSnapshotDateAndInvestorTypeOrderBySnapshotTimeAsc(
                        stockCode, today, investorType);

        // 오늘 데이터가 없으면 최근 7일간 데이터 찾기
        if (snapshots.isEmpty()) {
            for (int i = 1; i <= 7; i++) {
                LocalDate prevDate = today.minusDays(i);
                // 주말 스킵
                if (prevDate.getDayOfWeek() == DayOfWeek.SATURDAY ||
                    prevDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    continue;
                }
                snapshots = snapshotRepository
                        .findByStockCodeAndSnapshotDateAndInvestorTypeOrderBySnapshotTimeAsc(
                                stockCode, prevDate, investorType);
                if (!snapshots.isEmpty()) {
                    log.info("종목 수급 추이 조회: stockCode={}, investorType={}, date={} ({}건)",
                            stockCode, investorType, prevDate, snapshots.size());
                    break;
                }
            }
        } else {
            log.info("종목 수급 추이 조회: stockCode={}, investorType={}, date={} ({}건)",
                    stockCode, investorType, today, snapshots.size());
        }

        return snapshots.stream()
                .map(this::toSurgeDto)
                .collect(Collectors.toList());
    }

    /**
     * 수동으로 스냅샷 수집 트리거
     */
    public Map<String, Integer> collectSnapshotManually() {
        Map<String, Integer> result = new HashMap<>();

        try {
            collectAndSaveSnapshot("FOREIGN");
            result.put("FOREIGN", 1);

            Thread.sleep(1000);

            collectAndSaveSnapshot("INSTITUTION");
            result.put("INSTITUTION", 1);
        } catch (Exception e) {
            log.error("수동 스냅샷 수집 실패", e);
        }

        return result;
    }

    /**
     * 오래된 스냅샷 정리 (7일 이전)
     */
    @Scheduled(cron = "0 0 6 * * *")
    public void cleanupOldSnapshots() {
        LocalDate cutoffDate = LocalDate.now().minusDays(7);
        snapshotRepository.deleteBySnapshotDateBefore(cutoffDate);
        log.info("오래된 스냅샷 정리 완료: {} 이전", cutoffDate);
    }

    private InvestorSurgeDto toSurgeDto(InvestorIntradaySnapshot snapshot) {
        // null 체크 및 기본값 설정
        BigDecimal netBuyAmount = snapshot.getNetBuyAmount() != null ? snapshot.getNetBuyAmount() : BigDecimal.ZERO;
        BigDecimal amountChange = snapshot.getAmountChange() != null ? snapshot.getAmountChange() : BigDecimal.ZERO;

        // surgeLevel 계산 - netBuyAmount 또는 amountChange 기준
        String surgeLevel = "NORMAL";
        BigDecimal checkAmount = amountChange.compareTo(BigDecimal.ZERO) > 0 ? amountChange : netBuyAmount;
        if (checkAmount.compareTo(SURGE_THRESHOLD_HOT) >= 0) {
            surgeLevel = "HOT";
        } else if (checkAmount.compareTo(SURGE_THRESHOLD_WARM) >= 0) {
            surgeLevel = "WARM";
        }

        Double changePercent = null;
        if (amountChange.compareTo(BigDecimal.ZERO) != 0 && netBuyAmount.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal prevAmount = netBuyAmount.subtract(amountChange);
            if (prevAmount.compareTo(BigDecimal.ZERO) != 0) {
                changePercent = amountChange
                        .divide(prevAmount.abs(), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .doubleValue();
            }
        }

        // trendStatus 계산 (누적금액 + 변화량 조합)
        String trendStatus = calculateTrendStatus(netBuyAmount, amountChange);
        String trendStatusName = getTrendStatusName(trendStatus);

        return InvestorSurgeDto.builder()
                .stockCode(snapshot.getStockCode())
                .stockName(snapshot.getStockName())
                .investorType(snapshot.getInvestorType())
                .investorTypeName(getInvestorTypeName(snapshot.getInvestorType()))
                .snapshotTime(snapshot.getSnapshotTime())
                .netBuyAmount(netBuyAmount)
                .amountChange(amountChange)
                .changePercent(changePercent)
                .currentRank(snapshot.getRankNum() != null ? snapshot.getRankNum() : 0)
                .rankChange(snapshot.getRankChange() != null ? snapshot.getRankChange() : 0)
                .currentPrice(snapshot.getCurrentPrice())
                .changeRate(snapshot.getChangeRate())
                .surgeLevel(surgeLevel)
                .trendStatus(trendStatus)
                .trendStatusName(trendStatusName)
                .build();
    }

    /**
     * 추세 상태 계산
     * - 누적 순매수 금액(currentTotal)과 변화량(changeAmount) 조합으로 결정
     * - ACCUMULATING: 누적 양수 + 변화 양수 → 초록색 '매수 집중' (★최고의 매수 타이밍)
     * - PROFIT_TAKING: 누적 양수 + 변화 음수 → 주황색 '차익 실현'
     * - TURNAROUND: 누적 음수 + 변화 양수 → 회색 '수급 유입' (반등 시도)
     * - NORMAL: 그 외 케이스
     */
    private String calculateTrendStatus(BigDecimal currentTotal, BigDecimal changeAmount) {
        boolean isPositiveTotal = currentTotal.compareTo(BigDecimal.ZERO) > 0;
        boolean isNegativeTotal = currentTotal.compareTo(BigDecimal.ZERO) < 0;
        boolean isPositiveChange = changeAmount.compareTo(BigDecimal.ZERO) > 0;
        boolean isNegativeChange = changeAmount.compareTo(BigDecimal.ZERO) < 0;

        if (isPositiveTotal && isPositiveChange) {
            return "ACCUMULATING";  // ★최고의 매수 타이밍 - 계속 사는 중
        } else if (isPositiveTotal && isNegativeChange) {
            return "PROFIT_TAKING"; // 많이 샀는데 차익 실현 중
        } else if (isNegativeTotal && isPositiveChange) {
            return "TURNAROUND";    // 반등 시도 - 수급 유입
        } else {
            return "NORMAL";        // 그 외 케이스
        }
    }

    /**
     * 추세 상태 한글명
     */
    private String getTrendStatusName(String trendStatus) {
        switch (trendStatus) {
            case "ACCUMULATING": return "매수 집중";
            case "PROFIT_TAKING": return "차익 실현";
            case "TURNAROUND": return "수급 유입";
            case "NORMAL": return "";
            default: return "";
        }
    }

    private String getInvestorTypeName(String investorType) {
        switch (investorType) {
            case "FOREIGN": return "외국인";
            case "INSTITUTION": return "기관";
            default: return investorType;
        }
    }

    // ============================================================
    // 텔레그램 급등 알림 관련
    // ============================================================

    /**
     * 수급 급증 종목 알림 발송 (고도화 버전)
     *
     * 알림 발송 조건:
     * 1. 쌍끌이 (COMMON): 외국인 > 0 AND 기관 > 0, 합산 30억 이상 → 최우선 발송
     * 2. 단독 (FOREIGN/INSTITUTION): 100억 이상(HOT)만 알림 → 쌍끌이 종목은 제외
     */
    private void sendSurgeAlerts(List<InvestorIntradaySnapshot> foreignSnapshots,
                                  List<InvestorIntradaySnapshot> institutionSnapshots) {
        if (!telegramService.isEnabled()) {
            return;
        }

        // 만료된 알림 기록 정리
        cleanupExpiredAlerts();

        // 스냅샷을 Map으로 변환
        Map<String, InvestorIntradaySnapshot> foreignMap = foreignSnapshots.stream()
                .filter(s -> s.getNetBuyAmount() != null)
                .collect(Collectors.toMap(InvestorIntradaySnapshot::getStockCode, s -> s, (a, b) -> a));
        Map<String, InvestorIntradaySnapshot> institutionMap = institutionSnapshots.stream()
                .filter(s -> s.getNetBuyAmount() != null)
                .collect(Collectors.toMap(InvestorIntradaySnapshot::getStockCode, s -> s, (a, b) -> a));

        // 쌍끌이 종목 추출: 외국인 > 0 AND 기관 > 0 AND 합산 >= 30억
        Set<String> commonCodes = extractCommonBuyingStocks(foreignMap, institutionMap);

        // 1. 쌍끌이 종목 알림 (최우선)
        for (String stockCode : commonCodes) {
            if (canSendAlert(stockCode, "COMMON")) {
                InvestorIntradaySnapshot foreign = foreignMap.get(stockCode);
                InvestorIntradaySnapshot institution = institutionMap.get(stockCode);
                sendCommonSurgeAlert(foreign, institution);
                markAlertSent(stockCode, "COMMON");
                // 쌍끌이 알림 발송 시 개별 알림도 쿨다운 적용 (중복 알림 방지)
                markAlertSent(stockCode, "FOREIGN");
                markAlertSent(stockCode, "INSTITUTION");
            }
        }

        // 2. 외국인 단독 HOT 종목 알림 (100억 이상, 쌍끌이 제외)
        for (Map.Entry<String, InvestorIntradaySnapshot> entry : foreignMap.entrySet()) {
            String stockCode = entry.getKey();
            InvestorIntradaySnapshot snapshot = entry.getValue();

            // 쌍끌이 종목은 제외
            if (commonCodes.contains(stockCode)) {
                continue;
            }

            // 100억 이상(HOT)만 알림
            if (snapshot.getNetBuyAmount().compareTo(SURGE_THRESHOLD_HOT) >= 0
                    && canSendAlert(stockCode, "FOREIGN")) {
                sendSingleSurgeAlert(snapshot, "FOREIGN");
                markAlertSent(stockCode, "FOREIGN");
            }
        }

        // 3. 기관 단독 HOT 종목 알림 (100억 이상, 쌍끌이 제외)
        for (Map.Entry<String, InvestorIntradaySnapshot> entry : institutionMap.entrySet()) {
            String stockCode = entry.getKey();
            InvestorIntradaySnapshot snapshot = entry.getValue();

            // 쌍끌이 종목은 제외
            if (commonCodes.contains(stockCode)) {
                continue;
            }

            // 100억 이상(HOT)만 알림
            if (snapshot.getNetBuyAmount().compareTo(SURGE_THRESHOLD_HOT) >= 0
                    && canSendAlert(stockCode, "INSTITUTION")) {
                sendSingleSurgeAlert(snapshot, "INSTITUTION");
                markAlertSent(stockCode, "INSTITUTION");
            }
        }
    }

    /**
     * 쌍끌이 종목 추출
     * 조건: 외국인 순매수 > 0 AND 기관 순매수 > 0 AND 합산 >= 30억
     */
    private Set<String> extractCommonBuyingStocks(
            Map<String, InvestorIntradaySnapshot> foreignMap,
            Map<String, InvestorIntradaySnapshot> institutionMap) {

        Set<String> commonCodes = new HashSet<>();

        for (String stockCode : foreignMap.keySet()) {
            // 기관에도 있는지 확인
            if (!institutionMap.containsKey(stockCode)) {
                continue;
            }

            InvestorIntradaySnapshot foreign = foreignMap.get(stockCode);
            InvestorIntradaySnapshot institution = institutionMap.get(stockCode);

            BigDecimal foreignNetBuy = foreign.getNetBuyAmount();
            BigDecimal instNetBuy = institution.getNetBuyAmount();

            // 둘 다 순매수 > 0 확인
            if (foreignNetBuy.compareTo(BigDecimal.ZERO) <= 0
                    || instNetBuy.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // 합산 30억 이상 확인
            BigDecimal totalNetBuy = foreignNetBuy.add(instNetBuy);
            if (totalNetBuy.compareTo(COMMON_SURGE_THRESHOLD) >= 0) {
                commonCodes.add(stockCode);
            }
        }

        return commonCodes;
    }

    /**
     * 쌍끌이 종목 알림 발송
     */
    private void sendCommonSurgeAlert(InvestorIntradaySnapshot foreign, InvestorIntradaySnapshot institution) {
        BigDecimal foreignNetBuy = foreign.getNetBuyAmount() != null ? foreign.getNetBuyAmount() : BigDecimal.ZERO;
        BigDecimal instNetBuy = institution.getNetBuyAmount() != null ? institution.getNetBuyAmount() : BigDecimal.ZERO;
        BigDecimal totalNetBuy = foreignNetBuy.add(instNetBuy);

        // 강도 레벨 결정
        String intensity;
        String intensityEmoji;
        if (totalNetBuy.compareTo(SURGE_THRESHOLD_HOT) >= 0) {
            intensity = "HOT";
            intensityEmoji = "🔥🔥";
        } else if (totalNetBuy.compareTo(SURGE_THRESHOLD_WARM) >= 0) {
            intensity = "WARM";
            intensityEmoji = "🔥";
        } else {
            intensity = "ACTIVE";
            intensityEmoji = "✨";
        }

        String message = String.format(
            """
            <b>🚨 [강력 매수] %s (쌍끌이)</b>

            %s 강도: <b>%s</b>
            🤝 외국인+기관 동시 순매수!

            💰 합산 순매수: <b>%s억</b>
               🌍 외국인: %s억
               🏢 기관: %s억

            💵 현재가: <b>%s원</b> (%s)

            ⏰ %s
            ━━━━━━━━━━━━━━━━
            🤖 MyPlatform 수급 알림
            """,
            foreign.getStockName(),
            intensityEmoji,
            intensity,
            formatAmount(totalNetBuy),
            formatAmount(foreignNetBuy),
            formatAmount(instNetBuy),
            formatPrice(foreign.getCurrentPrice()),
            formatChangeRate(foreign.getChangeRate()),
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        );

        telegramService.sendMessage(message);
        log.info("🚨 쌍끌이 알림 발송: {} ({}) - 합산 {}억 (외국인 {}억 + 기관 {}억)",
                foreign.getStockName(), foreign.getStockCode(),
                formatAmount(totalNetBuy), formatAmount(foreignNetBuy), formatAmount(instNetBuy));
    }

    /**
     * 단독 수급 HOT 종목 알림 발송 (100억 이상만)
     */
    private void sendSingleSurgeAlert(InvestorIntradaySnapshot snapshot, String investorType) {
        String investorEmoji = "FOREIGN".equals(investorType) ? "🌍" : "🏢";
        String investorName = "FOREIGN".equals(investorType) ? "외국인" : "기관";
        BigDecimal netBuy = snapshot.getNetBuyAmount();

        String message = String.format(
            """
            <b>🌊 [수급 쏠림] %s (%s %s억↑)</b>

            🔥 강도: <b>HOT</b>
            %s 주체: <b>%s 단독 집중 매수</b>

            💰 순매수: <b>%s억</b>
            💵 현재가: <b>%s원</b> (%s)

            ⏰ %s
            ━━━━━━━━━━━━━━━━
            🤖 MyPlatform 수급 알림
            """,
            snapshot.getStockName(),
            investorName,
            formatAmount(netBuy),
            investorEmoji,
            investorName,
            formatAmount(netBuy),
            formatPrice(snapshot.getCurrentPrice()),
            formatChangeRate(snapshot.getChangeRate()),
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        );

        telegramService.sendMessage(message);
        log.info("🌊 {} 단독 HOT 알림 발송: {} ({}) - {}억",
                investorName, snapshot.getStockName(), snapshot.getStockCode(), formatAmount(netBuy));
    }

    /**
     * 알림 발송 가능 여부 확인 (30분 내 재발송 금지)
     */
    private boolean canSendAlert(String stockCode, String investorType) {
        String key = stockCode + "_" + investorType;
        LocalDateTime lastSent = alertSentMap.get(key);

        if (lastSent == null) {
            return true;
        }

        return LocalDateTime.now().isAfter(lastSent.plusMinutes(ALERT_COOLDOWN_MINUTES));
    }

    /**
     * 알림 발송 기록
     */
    private void markAlertSent(String stockCode, String investorType) {
        String key = stockCode + "_" + investorType;
        alertSentMap.put(key, LocalDateTime.now());
    }

    /**
     * 만료된 알림 기록 정리 (1시간 이상 지난 기록 삭제)
     */
    private void cleanupExpiredAlerts() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        alertSentMap.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }

    /**
     * 금액 포맷팅 (억원 단위)
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "0";
        return String.format("%,.0f", amount);
    }

    /**
     * 가격 포맷팅
     */
    private String formatPrice(BigDecimal price) {
        if (price == null) return "N/A";
        return String.format("%,.0f", price);
    }

    /**
     * 등락률 포맷팅
     */
    private String formatChangeRate(BigDecimal rate) {
        if (rate == null) return "0.00%";
        String sign = rate.compareTo(BigDecimal.ZERO) > 0 ? "+" : "";
        return String.format("%s%.2f%%", sign, rate);
    }
}
