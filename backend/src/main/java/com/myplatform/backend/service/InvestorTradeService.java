package com.myplatform.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.InvestorDataStatusDto;
import com.myplatform.backend.dto.InvestorTradeDto;
import com.myplatform.backend.dto.StockInvestorDetailDto;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 투자자별 매매 정보 조회 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvestorTradeService {

    private final InvestorDailyTradeRepository investorTradeRepository;
    private final KisInvestorDataCollector kisInvestorDataCollector;
    private final KoreaInvestmentService koreaInvestmentService;
    private final RedisCacheService redisCacheService;
    private final InvestorDailyTradeService investorDailyTradeService;
    private final MarketCalendarService marketCalendarService;
    /** 자기 자신(프록시) — @Transactional/@CacheEvict 가 붙은 메서드를 내부에서 호출할 때 사용.
     *  같은 클래스 내부 직접 호출은 프록시를 안 거쳐 어노테이션이 통째로 무시된다. */
    private final org.springframework.beans.factory.ObjectProvider<InvestorTradeService> selfProvider;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime MARKET_OPEN = LocalTime.of(8, 0);   // 프리마켓 포함
    private static final LocalTime MARKET_CLOSE = LocalTime.of(20, 0);  // 애프터마켓 포함

    /**
     * 투자자 유형별 상위 매수/매도 종목 조회 (최대 50개)
     *
     * [성능 최적화] DB 쿼리 단계에서 tradeType 필터링 + Pageable로 limit 처리
     * - 기존: 전체 조회 → 메모리 filter → limit (비효율적)
     * - 개선: WHERE tradeType + ORDER BY rankNum + LIMIT (DB 레벨 처리)
     *
     * @param investorType FOREIGN(외국인), INSTITUTION(기관), INDIVIDUAL(개인)
     * @param tradeType BUY(매수), SELL(매도)
     * @param limit 조회할 종목 수 (기본 50)
     */
    /** 수급 테이블 최신 거래일(없으면 null) — 소비자 측 노후(staleness) 가드용. */
    public LocalDate getLatestTradeDate() {
        return investorTradeRepository.findLatestTradeDate();
    }

    public List<InvestorTradeDto> getTopTradesByInvestor(String investorType, String tradeType, Integer limit) {
        if (limit == null || limit <= 0) {
            limit = 50;
        }

        // 가장 최근 거래일의 데이터 조회
        LocalDate latestDate = investorTradeRepository.findLatestTradeDate();
        if (latestDate == null) {
            log.warn("투자자별 거래 데이터가 없습니다.");
            return Collections.emptyList();
        }

        // [최적화] DB 쿼리에서 tradeType 필터 + Pageable로 limit 처리
        // 중복 종목 가능성을 고려하여 limit보다 여유있게 조회
        Pageable pageable = PageRequest.of(0, limit * 2);
        List<InvestorDailyTrade> trades = investorTradeRepository
                .findTopTradesByInvestorAndTradeType(investorType, tradeType.toUpperCase(), latestDate, pageable);

        // 중복 종목 제거 (stockCode 기준, 첫 번째만 유지) 후 limit 적용
        Set<String> seenStocks = new HashSet<>();
        final int finalLimit = limit;
        return trades.stream()
                .filter(t -> seenStocks.add(t.getStockCode())) // 중복 제거
                .limit(finalLimit)
                .map(this::entityToDto)
                .collect(Collectors.toList());
    }

    /**
     * 장중 실시간 투자자별 상위 매매 종목 조회 (KIS API 직접 호출)
     * - 장중(09:00~15:30): KIS API 실시간 호출
     * - 장외: DB 조회 폴백
     */
    public List<InvestorTradeDto> getTopTradesRealtime(String investorType, int limit) {
        // Redis L2 캐시 조회 (워머가 30초마다 갱신)
        List<InvestorTradeDto> cached = redisCacheService.get(
                "smartMoneyRealtime", investorType,
                new TypeReference<List<InvestorTradeDto>>() {});
        if (cached != null && !cached.isEmpty()) {
            log.debug("[SmartMoney] Redis L2 HIT - {} {}건", investorType, cached.size());
            return cached.size() > limit ? cached.subList(0, limit) : cached;
        }

        // Redis MISS 시 KIS 직접 호출은 하지 않는다 — 프론트 트래픽이 KIS 를 때리지 않게.
        // 워머가 주기적으로 Redis 를 갱신하므로 Redis MISS 는 정상 상태가 아님. DB 폴백만 사용.
        // (2026-04-23 장애 재발 방지 — 프론트 요청 × EGW00201 백오프 누적으로 서버 다운)
        return getTopTradesByInvestor(investorType, "BUY", limit);
    }

    /**
     * KIS API 직접 호출로 외국인/기관 실시간 순매수 상위 종목 조회 (워머 전용).
     *
     * <p>공개 API {@link #getTopTradesRealtime} 는 Redis-only + DB 폴백을 유지해 프론트 트래픽이
     * KIS 를 직접 때리지 않게 한다(2026-04-23 장애 재발 방지). 이 메서드는
     * {@code MarketCacheWarmerService} 에서만 호출되어 Redis L2 를 KIS 실시간 데이터로 채운다.
     */
    public List<InvestorTradeDto> refreshSmartMoneyFromKis(String investorType, int limit) {
        String kisInvestorCode = "FOREIGN".equals(investorType) ? "1" : "2";
        JsonNode response = koreaInvestmentService.getForeignInstitutionTotal(kisInvestorCode, true, true);
        if (response == null) {
            log.warn("[SmartMoney KIS] 응답 없음 - {}", investorType);
            return Collections.emptyList();
        }
        return parseKisRealtimeResponse(response, investorType, limit);
    }

    /**
     * KIS API 실시간 응답을 InvestorTradeDto로 변환
     */
    private List<InvestorTradeDto> parseKisRealtimeResponse(JsonNode response, String investorType, int limit) {
        List<InvestorTradeDto> result = new ArrayList<>();

        String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText() : "";
        if (!"0".equals(rtCd)) return result;

        JsonNode output = response.get("output");
        if (output == null || !output.isArray()) return result;

        String netBuyField = "FOREIGN".equals(investorType) ? "frgn_ntby_tr_pbmn" : "orgn_ntby_tr_pbmn";
        int rank = 1;

        for (JsonNode item : output) {
            if (rank > limit) break;

            String stockCode = getJsonText(item, "mksc_shrn_iscd");
            String stockName = getJsonText(item, "hts_kor_isnm");
            if (stockCode.isEmpty() || stockName.isEmpty()) continue;

            BigDecimal netBuyRaw = getJsonBigDecimal(item, netBuyField);
            // 백만원 → 억원
            BigDecimal netBuyAmount = netBuyRaw.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            BigDecimal currentPrice = getJsonBigDecimal(item, "stck_prpr");
            BigDecimal changeRate = getJsonBigDecimal(item, "prdy_ctrt");

            InvestorTradeDto dto = new InvestorTradeDto();
            dto.setStockCode(stockCode);
            dto.setStockName(stockName);
            dto.setNetBuyAmount(netBuyAmount);
            dto.setCurrentPrice(currentPrice);
            dto.setChangeRate(changeRate);
            dto.setInvestorType(investorType);
            dto.setRankNum(rank);
            dto.setTradeDate(LocalDate.now());

            result.add(dto);
            rank++;
        }

        return result;
    }

    private String getJsonText(JsonNode node, String field) {
        if (node == null || !node.has(field)) return "";
        return node.get(field).asText("");
    }

    private BigDecimal getJsonBigDecimal(JsonNode node, String field) {
        if (node == null || !node.has(field)) return BigDecimal.ZERO;
        try {
            return new BigDecimal(node.get(field).asText("0").replace(",", ""));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 전체 투자자의 상위 매매 종목 조회 (외국인, 기관, 연기금, 개인 각각 50개씩)
     *
     * [개선] PENSION(연기금) 투자자 추가
     */
    public Map<String, List<InvestorTradeDto>> getAllInvestorTopTrades(String tradeType, Integer limit) {
        Map<String, List<InvestorTradeDto>> result = new LinkedHashMap<>();  // 순서 유지

        result.put("FOREIGN", getTopTradesByInvestor("FOREIGN", tradeType, limit));
        result.put("INSTITUTION", getTopTradesByInvestor("INSTITUTION", tradeType, limit));
        result.put("PENSION", getTopTradesByInvestor("PENSION", tradeType, limit));  // 연기금 추가
        result.put("INDIVIDUAL", getTopTradesByInvestor("INDIVIDUAL", tradeType, limit));

        return result;
    }

    /**
     * 특정 종목의 투자자별 매매 이력 조회 (최근 30일)
     */
    public StockInvestorDetailDto getStockInvestorDetail(String stockCode, Integer days) {
        if (days == null || days <= 0) {
            days = 30;
        }

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        List<InvestorDailyTrade> trades = investorTradeRepository
                .findByStockCodeAndDateRange(stockCode, startDate, endDate);

        if (trades.isEmpty()) {
            return null;
        }

        // 종목 정보
        InvestorDailyTrade firstTrade = trades.get(0);
        String stockName = firstTrade.getStockName();

        // 일자별로 그룹화
        Map<LocalDate, List<InvestorDailyTrade>> tradesByDate = trades.stream()
                .collect(Collectors.groupingBy(InvestorDailyTrade::getTradeDate));

        // 일자별 투자자별 매매 데이터 생성
        List<StockInvestorDetailDto.DailyInvestorTrade> dailyTrades = tradesByDate.entrySet().stream()
                .sorted(Map.Entry.<LocalDate, List<InvestorDailyTrade>>comparingByKey().reversed())
                .map(entry -> {
                    LocalDate date = entry.getKey();
                    List<InvestorDailyTrade> dayTrades = entry.getValue();

                    // 투자자별로 그룹화
                    Map<String, List<InvestorDailyTrade>> tradesByInvestor = dayTrades.stream()
                            .collect(Collectors.groupingBy(InvestorDailyTrade::getInvestorType));

                    // 종가 추출 (외국인/기관/연기금 중 하나에서)
                    BigDecimal closePrice = dayTrades.stream()
                            .filter(t -> t.getCurrentPrice() != null && t.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0)
                            .map(InvestorDailyTrade::getCurrentPrice)
                            .findFirst()
                            .orElse(null);

                    return StockInvestorDetailDto.DailyInvestorTrade.builder()
                            .tradeDate(date)
                            .closePrice(closePrice)
                            .foreign(buildInvestorSummary(tradesByInvestor.get("FOREIGN")))
                            .institution(buildInvestorSummary(tradesByInvestor.get("INSTITUTION")))
                            .individual(buildInvestorSummary(tradesByInvestor.get("INDIVIDUAL")))
                            .pension(buildInvestorSummary(tradesByInvestor.get("PENSION")))
                            .build();
                })
                .collect(Collectors.toList());

        return StockInvestorDetailDto.builder()
                .stockCode(stockCode)
                .stockName(stockName)
                .dailyTrades(dailyTrades)
                .build();
    }

    /**
     * 투자자별 매매 요약 — <b>행이 없으면 null</b>(2026-08-27 표시층 감사 A-1).
     *
     * <p><b>왜 0 이 아니라 null 인가</b>: 이 데이터의 원천은 KIS <b>순매수 상위 20위</b> API 라
     * ({@code InvestorDailyTradeService:226} 의 {@code rank > 20 break}) 그 종목이 그날 상위권에
     * 못 들면 <b>행 자체가 없다</b>. 그걸 {@code ZERO} 로 채우면 화면이 "순매수 0억"으로 그리고
     * 사용자는 <b>"그날 외국인이 사지도 팔지도 않았다"</b>로 읽는다 — 사실은 "데이터가 없다"이다.
     * 투자 판단에서 둘은 정반대 의미다.
     *
     * <p>프론트는 이미 §4c 를 지키게 돼 있었다({@code InvestorTrendTab.vue:303-319} 의
     * {@code value == null → '-'}). 그 가드가 <b>여기서 0 을 만들어 보내는 바람에</b>
     * 한 번도 발동하지 못했다. null 을 돌려주면 그쪽이 저절로 작동한다.
     *
     * <p>실측 0(그날 상위권에 들었고 순매수가 정확히 0)은 그대로 0 으로 나간다 — 구분되는 상태다.
     *
     * <p>package-private = 테스트 가시성({@code InvestorSummaryNullTest}).
     */
    StockInvestorDetailDto.InvestorTradeSummary buildInvestorSummary(List<InvestorDailyTrade> trades) {
        if (trades == null || trades.isEmpty()) {
            return null;   // 미수집 — "순매수 0억"으로 위장하지 않는다(§4c)
        }

        BigDecimal totalBuy = BigDecimal.ZERO;
        BigDecimal totalSell = BigDecimal.ZERO;
        BigDecimal totalNetBuy = BigDecimal.ZERO;

        for (InvestorDailyTrade trade : trades) {
            // netBuyAmount를 직접 사용 (가장 신뢰할 수 있는 데이터)
            if (trade.getNetBuyAmount() != null) {
                totalNetBuy = totalNetBuy.add(trade.getNetBuyAmount());
            }
            if (trade.getBuyAmount() != null) {
                totalBuy = totalBuy.add(trade.getBuyAmount());
            }
            if (trade.getSellAmount() != null) {
                totalSell = totalSell.add(trade.getSellAmount());
            }
        }

        // netBuyAmount가 있으면 그것을 사용, 없으면 buy-sell 계산
        BigDecimal netBuy = totalNetBuy.compareTo(BigDecimal.ZERO) != 0
                ? totalNetBuy
                : totalBuy.subtract(totalSell);

        return StockInvestorDetailDto.InvestorTradeSummary.builder()
                .buyAmount(totalBuy)
                .sellAmount(totalSell)
                .netBuyAmount(netBuy)
                .build();
    }

    /**
     * Entity -> DTO 변환
     */
    private InvestorTradeDto entityToDto(InvestorDailyTrade entity) {
        return InvestorTradeDto.builder()
                .stockCode(entity.getStockCode())
                .stockName(entity.getStockName())
                .tradeDate(entity.getTradeDate())
                .investorType(entity.getInvestorType())
                .investorTypeName(getInvestorTypeName(entity.getInvestorType()))
                .netBuyAmount(entity.getNetBuyAmount())
                .buyAmount(entity.getBuyAmount())
                .sellAmount(entity.getSellAmount())
                .currentPrice(entity.getCurrentPrice())
                .changeRate(entity.getChangeRate())
                .tradeVolume(entity.getTradeVolume())
                .rankNum(entity.getRankNum())
                .build();
    }

    /**
     * 투자자 유형 한글명 반환
     */
    private String getInvestorTypeName(String investorType) {
        switch (investorType) {
            case "FOREIGN":
                return "외국인";
            case "INSTITUTION":
                return "기관";
            case "INDIVIDUAL":
                return "개인";
            case "PENSION":
                return "연기금";
            case "INVEST_TRUST":
                return "투신";
            default:
                return investorType;
        }
    }

    /**
     * 특정 일자의 투자자별 매매 데이터 수집 (한국투자증권 API 호출)
     *
     * [중복 방지] 수집 전 해당 날짜 기존 데이터 삭제 후 재수집
     * - Duplicate entry 에러 방지
     * - 삭제와 저장이 하나의 트랜잭션에서 처리됨
     *
     * [캐시 초기화] 새 데이터가 수집되면 연속 매수 캐시 전체 초기화
     * - 신규 데이터가 들어오면 연속 매수 패턴이 변경될 수 있으므로
     */
    @Transactional
    @CacheEvict(value = "consecutiveBuys", allEntries = true)
    public Map<String, Integer> collectInvestorTradeData(LocalDate tradeDate) {
        log.info("투자자별 매매 데이터 수집 시작: {} (consecutiveBuys 캐시 초기화)", tradeDate);

        // 기존 데이터 삭제 (중복 방지) - Native Query로 즉시 삭제
        boolean hasExistingData = investorTradeRepository.existsByTradeDate(tradeDate);
        if (hasExistingData) {
            log.info("기존 데이터 삭제 시작: {}", tradeDate);
            investorTradeRepository.deleteByTradeDate(tradeDate);
            log.info("기존 데이터 삭제 완료: {} (Native DELETE 실행됨)", tradeDate);
        }

        // 삭제 확인
        boolean stillExists = investorTradeRepository.existsByTradeDate(tradeDate);
        if (stillExists) {
            log.error("데이터 삭제 실패! 여전히 데이터가 존재합니다: {}", tradeDate);
        } else {
            log.info("데이터 삭제 확인: {} 데이터 없음", tradeDate);
        }

        Map<String, Integer> result = kisInvestorDataCollector.collectDailyInvestorTrades(tradeDate);

        // [KRX 보충] 연기금 데이터 안전망
        // - KIS의 fund_ntby_tr_pbmn 추출 경로가 빈 응답을 줄 때 PENSION이 0건이 되는 사고 방지
        // - 보유 시장: KIS는 KOSPI만, KRX는 KOSPI+KOSDAQ — KOSDAQ은 항상 보충 가치 있음
        // - 중복 방지: collectPensionFromKrx 내부에서 existsByMarketTypeAndInvestorTypeAndTradeDate 체크 후 스킵
        try {
            boolean kisPensionEmpty = !investorTradeRepository
                    .existsByInvestorTypeAndTradeDate("PENSION", tradeDate);
            if (kisPensionEmpty) {
                log.warn("KIS 연기금 수집 결과 0건 — KRX KOSPI 보충 시도: {}", tradeDate);
                int kospiCount = investorDailyTradeService.collectPensionFromKrx("KOSPI", tradeDate);
                result.put("KOSPI_PENSION_KRX_FALLBACK", kospiCount);
            }
            // KOSDAQ 연기금은 KIS가 커버하지 않으므로 항상 KRX로 보충
            int kosdaqCount = investorDailyTradeService.collectPensionFromKrx("KOSDAQ", tradeDate);
            result.put("KOSDAQ_PENSION_KRX", kosdaqCount);
        } catch (Exception e) {
            log.warn("KRX 연기금 보충 수집 실패: {} - {}", tradeDate, e.getMessage());
        }

        return result;
    }

    /**
     * 당일 데이터 수집
     * 주의: KIS API는 항상 당일 실시간 데이터만 반환합니다.
     * 과거 날짜를 지정해도 당일 데이터가 반환되므로, 오늘 날짜로만 수집합니다.
     */
    @Transactional
    public Map<String, Object> collectRecentData(int days) {
        Map<String, Object> result = new HashMap<>();

        LocalDate today = LocalDate.now();

        // 주말이면 수집하지 않음
        if (today.getDayOfWeek().getValue() >= 6) {
            result.put("message", "주말에는 데이터를 수집하지 않습니다.");
            result.put("totalCollected", 0);
            return result;
        }

        // 오늘 날짜로만 수집 (KIS API는 당일 데이터만 반환)
        Map<String, Integer> dayResult = collectInvestorTradeData(today);
        result.put(today.toString(), dayResult);

        int totalCollected = dayResult.values().stream().mapToInt(Integer::intValue).sum();
        result.put("totalCollected", totalCollected);
        log.info("당일 데이터 수집 완료: {} - 총 {}건", today, totalCollected);

        return result;
    }

    /**
     * 전체 데이터 삭제 후 재수집
     */
    public Map<String, Object> deleteAllAndRecollect() {
        Map<String, Object> result = new HashMap<>();
        // 내부 직접 호출은 프록시 미경유라 deleteAllData 의 @Transactional 과
        // collectInvestorTradeData 의 @CacheEvict(consecutiveBuys) 가 모두 무시됐다
        // (전량 삭제 후에도 연속매수 캐시가 옛 데이터를 계속 서빙). self 프록시 경유로 교정.
        InvestorTradeService self = selfProvider.getObject();

        // 1. 기존 데이터 전체 삭제
        long deletedCount = self.deleteAllData();
        log.info("기존 데이터 삭제 완료: {}건", deletedCount);
        result.put("deletedCount", deletedCount);

        // 2. 새로 수집
        LocalDate today = LocalDate.now();
        if (today.getDayOfWeek().getValue() >= 6) {
            result.put("message", "주말에는 데이터를 수집하지 않습니다.");
            result.put("collectedCount", 0);
            return result;
        }

        Map<String, Integer> collectResult = self.collectInvestorTradeData(today);
        int collectedCount = collectResult.values().stream().mapToInt(Integer::intValue).sum();
        result.put("collectResult", collectResult);
        result.put("collectedCount", collectedCount);
        log.info("재수집 완료: {}건", collectedCount);

        return result;
    }

    /**
     * 전체 데이터 삭제 (별도 트랜잭션)
     */
    // ==================== 백테스트 정밀 수급 CSV export (P-백로그: 수량×종가 근사 → 실금액 대체) ====================

    /**
     * InvestorDailyTrade → 백테스트 flows CSV.
     * 스키마: date,stock_code,frgn_net_eok,inst_net_eok (억원) — python-backend
     * app/backtest/investor_flows.load_flows_csv 가 소비하는 정확한 형식.
     */
    @Transactional(readOnly = true)
    public String exportFlowsCsv(LocalDate from, LocalDate to) {
        return buildFlowsCsv(investorTradeRepository.aggregateNetByDateStockInvestor(from, to));
    }

    /**
     * (일자,종목,투자자,순매수합) 집계 행 → CSV 피벗. 순수 함수(테스트 대상).
     * 한쪽 투자자만 랭킹에 있는 (일자,종목)은 없는 쪽을 빈칸으로 — 0(균형)으로 위장하지 않는다(§4c,
     * pandas read_csv 는 빈칸을 NaN 으로 읽어 미수집으로 처리).
     */
    static String buildFlowsCsv(List<Object[]> rows) {
        StringBuilder sb = new StringBuilder("date,stock_code,frgn_net_eok,inst_net_eok\n");
        java.util.LinkedHashMap<String, BigDecimal[]> pivot = new java.util.LinkedHashMap<>();
        for (Object[] r : rows) {
            String key = r[0] + "," + r[1];   // date,stockCode
            BigDecimal[] cell = pivot.computeIfAbsent(key, k -> new BigDecimal[2]);
            if ("FOREIGN".equals(r[2])) cell[0] = (BigDecimal) r[3];
            else if ("INSTITUTION".equals(r[2])) cell[1] = (BigDecimal) r[3];
        }
        for (Map.Entry<String, BigDecimal[]> e : pivot.entrySet()) {
            sb.append(e.getKey()).append(',')
              .append(e.getValue()[0] != null ? e.getValue()[0].stripTrailingZeros().toPlainString() : "").append(',')
              .append(e.getValue()[1] != null ? e.getValue()[1].stripTrailingZeros().toPlainString() : "").append('\n');
        }
        return sb.toString();
    }

    @Transactional
    public long deleteAllData() {
        long count = investorTradeRepository.count();
        // deleteAll() 은 전 엔티티를 영속성 컨텍스트에 로드한 뒤 행마다 DELETE 를 발행한다 —
        // investor_daily_trade 는 (거래일×시장×투자자×매매구분×순위) 누적이라 수십만 행 규모라 OOM 위험.
        investorTradeRepository.deleteAllInBatch();
        return count;
    }

    /**
     * 연속 매수 종목 조회
     * 특정 투자자가 N일 연속으로 순매수 상위에 오른 종목 찾기
     *
     * [성능 최적화]
     * - 기존: 30일간 등장한 모든 종목을 전수 조사 (비효율적)
     * - 개선: 가장 최근 거래일에 매수한 종목만 후보군으로 추려서 분석
     *         (오늘 매수하지 않은 종목은 이미 연속이 끊긴 것이므로 제외)
     *
     * [캐싱] 장 마감 후 하루에 한 번 변경되므로 1시간 TTL 캐시 적용
     * - Cache Key: investorType + "_" + minDays (예: "FOREIGN_5")
     * - 데이터 수집 시 캐시 자동 초기화
     *
     * @param investorType 투자자 유형 (FOREIGN, INSTITUTION, INDIVIDUAL)
     * @param minDays 최소 연속 일수 (기본 3일)
     */
    @Cacheable(
            value = "consecutiveBuys",
            key = "#investorType + '_' + (#minDays != null ? #minDays : 3)",
            unless = "#result == null || #result.isEmpty()"
    )
    public List<ConsecutiveBuyDto> getConsecutiveBuyStocks(String investorType, Integer minDays) {
        if (minDays == null || minDays < 1) {
            minDays = 3;
        }

        // 최근 거래일 목록 조회 (최대 30일)
        List<LocalDate> tradeDates = investorTradeRepository.findDistinctTradeDates(investorType);
        if (tradeDates.isEmpty()) {
            log.info("거래 데이터가 없습니다. 투자자: {}", investorType);
            return Collections.emptyList();
        }

        log.info("거래일 수: {} (투자자: {})", tradeDates.size(), investorType);

        // 데이터가 minDays보다 적으면 빈 결과 반환 (사용자가 요청한 기준을 낮추지 않음)
        if (tradeDates.size() < minDays) {
            log.info("데이터가 {}일뿐이므로 {}일 연속 매수 종목을 찾을 수 없습니다.", tradeDates.size(), minDays);
            return Collections.emptyList();
        }

        // 최근 30일 데이터만 분석
        int daysToAnalyze = Math.min(tradeDates.size(), 30);
        LocalDate latestDate = tradeDates.get(0);  // 가장 최근 거래일
        LocalDate startDate = tradeDates.get(daysToAnalyze - 1);

        // 해당 기간의 매수 데이터 조회
        List<InvestorDailyTrade> buyTrades = investorTradeRepository
                .findBuyTradesForConsecutiveAnalysis(investorType, startDate, latestDate);

        if (buyTrades.isEmpty()) {
            log.info("매수 데이터가 없습니다. 투자자: {}, 기간: {} ~ {}", investorType, startDate, latestDate);
            return Collections.emptyList();
        }

        // ========== [개선 1] 후보군 추출: 가장 최근 거래일에 매수한 종목만 ==========
        // 오늘(latestDate) 매수하지 않은 종목은 이미 연속이 끊긴 것이므로 분석 대상에서 제외
        Set<String> candidateStocks = buyTrades.stream()
                .filter(t -> t.getTradeDate().equals(latestDate))
                .map(InvestorDailyTrade::getStockCode)
                .collect(Collectors.toSet());

        log.debug("후보 종목 수: {} (최근 거래일 {} 매수 종목)", candidateStocks.size(), latestDate);

        // 일자별로 종목 코드 집합 만들기
        Map<LocalDate, Set<String>> dailyStocks = new LinkedHashMap<>();

        // ========== [개선 3] 날짜 비교로 확실하게 최신 데이터 저장 ==========
        Map<String, InvestorDailyTrade> latestTradeByStock = new HashMap<>();

        for (InvestorDailyTrade trade : buyTrades) {
            dailyStocks.computeIfAbsent(trade.getTradeDate(), k -> new HashSet<>())
                    .add(trade.getStockCode());

            // merge로 날짜 비교하여 확실하게 최신 데이터 저장
            latestTradeByStock.merge(
                    trade.getStockCode(),
                    trade,
                    (existing, newTrade) -> {
                        if (newTrade.getTradeDate() == null) return existing;
                        if (existing.getTradeDate() == null) return newTrade;
                        return newTrade.getTradeDate().isAfter(existing.getTradeDate())
                                ? newTrade : existing;
                    }
            );
        }

        // 종목별 일별 순매수 금액 맵 (null 처리)
        Map<String, Map<LocalDate, BigDecimal>> stockDailyAmounts = buyTrades.stream()
                .collect(Collectors.groupingBy(
                        InvestorDailyTrade::getStockCode,
                        Collectors.toMap(
                                InvestorDailyTrade::getTradeDate,
                                t -> t.getNetBuyAmount() != null ? t.getNetBuyAmount() : BigDecimal.ZERO,
                                (a, b) -> a
                        )
                ));

        // 연속 매수 종목 찾기
        List<ConsecutiveBuyDto> result = new ArrayList<>();

        // ========== [개선 1] 후보군만 순회 (성능 최적화) ==========
        for (String stockCode : candidateStocks) {
            // 거래일 달력 기준 연속 매수일 — 수집 결측일은 연속을 끊는다(§4c, resolveConsecutiveBuyDates)
            List<LocalDate> streak = resolveConsecutiveBuyDates(
                    stockCode, latestDate, startDate, dailyStocks, marketCalendarService::isMarketClosed);

            int consecutiveDays = streak.size();
            LocalDate consecutiveEndDate = streak.isEmpty() ? null : streak.get(0);
            LocalDate consecutiveStartDate = streak.isEmpty() ? null : streak.get(streak.size() - 1);
            BigDecimal totalAmount = BigDecimal.ZERO;

            Map<LocalDate, BigDecimal> amounts = stockDailyAmounts.get(stockCode);
            if (amounts != null) {
                for (LocalDate date : streak) {
                    BigDecimal amt = amounts.get(date);
                    if (amt != null) {
                        totalAmount = totalAmount.add(amt);
                    }
                }
            }

            if (consecutiveDays >= minDays) {
                InvestorDailyTrade latestTrade = latestTradeByStock.get(stockCode);

                BigDecimal avgAmount = consecutiveDays > 0
                        ? totalAmount.divide(BigDecimal.valueOf(consecutiveDays), 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                ConsecutiveBuyDto dto = ConsecutiveBuyDto.builder()
                        .stockCode(stockCode)
                        .stockName(latestTrade.getStockName())
                        .investorType(investorType)
                        .investorTypeName(getInvestorTypeName(investorType))
                        .consecutiveDays(consecutiveDays)
                        .totalNetBuyAmount(totalAmount)
                        .avgDailyAmount(avgAmount)
                        .startDate(consecutiveStartDate)
                        .endDate(consecutiveEndDate)
                        .currentPrice(latestTrade.getCurrentPrice())
                        .changeRate(latestTrade.getChangeRate())
                        .build();

                result.add(dto);
            }
        }

        // 연속 일수 내림차순, 누적 금액 내림차순 정렬
        result.sort((a, b) -> {
            int dayCompare = b.getConsecutiveDays().compareTo(a.getConsecutiveDays());
            if (dayCompare != 0) return dayCompare;
            return b.getTotalNetBuyAmount().compareTo(a.getTotalNetBuyAmount());
        });

        log.info("연속 매수 종목 조회 완료: {} - {}개 (후보군: {}, 최소 {}일)",
                investorType, result.size(), candidateStocks.size(), minDays);

        return result;
    }

    /**
     * 거래일 달력 기준 연속 매수일 계산 — streak 에 속한 날짜를 최신→과거 순으로 반환하는 순수 함수.
     *
     * <p>latestDate 부터 역행하며 주말·공휴일은 건너뛴다. 단 <b>거래일인데 dailyStocks 에
     * 그 날짜 행이 통째로 없으면 수집 결측</b>(그날 매수 여부를 알 수 없음)이므로 연속을 끊는다 —
     * 이전 구현은 "데이터가 있는 날짜"만 이어 세어 결측일을 인접일처럼 붙여 consecutiveDays 를
     * 부풀렸다(§4c 위반). 임시공휴일 등 달력 미수록 휴일은 결측으로 보여 끊길 수 있으나
     * 과소 판정 방향이라 안전한 열화다.
     */
    static List<LocalDate> resolveConsecutiveBuyDates(String stockCode,
                                                      LocalDate latestDate,
                                                      LocalDate earliestDate,
                                                      Map<LocalDate, Set<String>> dailyStocks,
                                                      java.util.function.Predicate<LocalDate> isMarketClosed) {
        List<LocalDate> streak = new ArrayList<>();
        LocalDate d = latestDate;
        while (!d.isBefore(earliestDate)) {
            if (isMarketClosed.test(d)) {
                d = d.minusDays(1);
                continue;
            }
            Set<String> stocksOnDate = dailyStocks.get(d);
            if (stocksOnDate == null || !stocksOnDate.contains(stockCode)) {
                break;
            }
            streak.add(d);
            d = d.minusDays(1);
        }
        return streak;
    }

    /**
     * 전체 투자자의 연속 매수 종목 조회
     *
     * [개선] PENSION(연기금) 투자자 추가
     */
    public Map<String, List<ConsecutiveBuyDto>> getAllConsecutiveBuyStocks(Integer minDays) {
        Map<String, List<ConsecutiveBuyDto>> result = new LinkedHashMap<>();  // 순서 유지

        result.put("FOREIGN", getConsecutiveBuyStocks("FOREIGN", minDays));
        result.put("INSTITUTION", getConsecutiveBuyStocks("INSTITUTION", minDays));
        result.put("PENSION", getConsecutiveBuyStocks("PENSION", minDays));  // 연기금 추가
        result.put("INDIVIDUAL", getConsecutiveBuyStocks("INDIVIDUAL", minDays));

        return result;
    }

    /**
     * 연속 매수 캐시 초기화
     * - 수동으로 캐시를 비우고 싶을 때 사용
     * - 장 마감(16:00) 스케줄러에서 자동 호출
     */
    @CacheEvict(value = "consecutiveBuys", allEntries = true)
    public void clearConsecutiveBuysCache() {
        log.info("연속 매수 캐시 초기화 완료 (consecutiveBuys)");
    }

    /**
     * 장 마감 후 캐시 자동 초기화 (매일 15:55)
     * - 15:50 InvestorTradeScheduler.collectAfterMarketClose 직후, 16:05 warmConsecutiveBuys 직전에 실행
     * - 순서: 15:50 수집(@CacheEvict 포함) → 15:55 evict(보강) → 16:05 warm(최종 캐시 채우기)
     * - 기존 16:10은 16:05 warm 결과를 도로 비워버려 워밍이 무의미해지는 버그였음
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 55 15 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledCacheEvict() {
        clearConsecutiveBuysCache();
        log.info("장 마감 후 연속 매수 캐시 스케줄 초기화 완료 (15:55)");
    }

    /**
     * 데이터 수집 상태 조회
     */
    public InvestorDataStatusDto getDataStatus() {
        // 외국인 거래일 수
        List<LocalDate> foreignDates = investorTradeRepository.findDistinctTradeDates("FOREIGN");
        LocalDate foreignLatestDate = foreignDates.isEmpty() ? null : foreignDates.get(0);
        LocalDate foreignOldestDate = foreignDates.isEmpty() ? null : foreignDates.get(foreignDates.size() - 1);

        // 기관 거래일 수
        List<LocalDate> instDates = investorTradeRepository.findDistinctTradeDates("INSTITUTION");
        LocalDate institutionLatestDate = instDates.isEmpty() ? null : instDates.get(0);

        // 전체 최근 거래일
        LocalDate latestDate = investorTradeRepository.findLatestTradeDate();

        // 데이터 충분 여부 (최소 3일)
        boolean hasEnoughData = foreignDates.size() >= 3 && instDates.size() >= 3;
        String message = hasEnoughData
                ? "충분한 데이터가 있습니다. (" + foreignDates.size() + "일치)"
                : "데이터 수집 중입니다. 매일 15:50에 자동 수집되며, 3일 이상 누적되면 연속 매수 패턴 분석이 가능합니다. (현재 " + foreignDates.size() + "일치)";

        return InvestorDataStatusDto.builder()
                .foreignTradeDays(foreignDates.size())
                .foreignLatestDate(foreignLatestDate)
                .foreignOldestDate(foreignOldestDate)
                .institutionTradeDays(instDates.size())
                .institutionLatestDate(institutionLatestDate)
                .latestTradeDate(latestDate)
                .hasEnoughData(hasEnoughData)
                .message(message)
                .build();
    }
}
