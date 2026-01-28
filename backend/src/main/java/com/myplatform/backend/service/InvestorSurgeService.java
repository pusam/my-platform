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
import java.time.LocalTime;
import java.util.*;
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

    // 급증 기준값 (억원)
    private static final BigDecimal SURGE_THRESHOLD_HOT = new BigDecimal("100");   // 100억 이상
    private static final BigDecimal SURGE_THRESHOLD_WARM = new BigDecimal("50");   // 50억 이상

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
            collectAndSaveSnapshot("FOREIGN");
            Thread.sleep(1000); // API 호출 간격
            collectAndSaveSnapshot("INSTITUTION");

            log.info("장중 스냅샷 수집 완료");
        } catch (Exception e) {
            log.error("장중 스냅샷 수집 실패", e);
        }
    }

    /**
     * 특정 투자자 유형의 스냅샷 수집 및 저장
     */
    private void collectAndSaveSnapshot(String investorType) {
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
            List<InvestorIntradaySnapshot> snapshots = fetchCurrentRanking(investorType, today, snapshotTime);

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

            // 저장
            snapshotRepository.saveAll(snapshots);
            log.info("스냅샷 저장 완료: {} - {}건", investorType, snapshots.size());

        } catch (Exception e) {
            log.error("스냅샷 수집 실패: {}", investorType, e);
        }
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

        return surgeSnapshots.stream()
                .map(this::toSurgeDto)
                .collect(Collectors.toList());
    }

    /**
     * 전체 투자자 수급 급증 종목 조회
     */
    @Transactional(readOnly = true)
    public Map<String, List<InvestorSurgeDto>> getAllSurgeStocks(BigDecimal minChange) {
        Map<String, List<InvestorSurgeDto>> result = new HashMap<>();

        result.put("FOREIGN", getSurgeStocks("FOREIGN", minChange));
        result.put("INSTITUTION", getSurgeStocks("INSTITUTION", minChange));

        return result;
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
                .build();
    }

    private String getInvestorTypeName(String investorType) {
        switch (investorType) {
            case "FOREIGN": return "외국인";
            case "INSTITUTION": return "기관";
            default: return investorType;
        }
    }
}
