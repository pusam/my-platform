package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.dto.TechnicalIndicatorsDto;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.StockPriceRepository;
import com.myplatform.backend.util.StockNameResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashSet;
import java.util.Set;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 기술적 분석 기반 퀀트 서비스
 * - TA 스크리너: RSI, 골든크로스, 볼린저, 거래량 등 조건 필터링
 * - 상관관계 매트릭스: 종가 기반 피어슨 상관계수
 *
 * AI / 외부 API 호출 0건 — 모두 stock_price_history DB 캐시 기반.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QuantTaService {

    private final StockPriceHistoryRepository priceHistoryRepository;
    private final StockPriceRepository stockPriceRepository;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final StockAnalysisService stockAnalysisService;
    @Autowired private SectorStockConfig sectorStockConfig;
    @Autowired private StockMasterService stockMasterService;
    @Autowired private StockPriceService stockPriceService;
    @Autowired private com.myplatform.backend.repository.NewsSummaryRepository newsSummaryRepository;

    private static final int MIN_HISTORY_DAYS = 25;       // 최소 일봉 수 (MA20 + RSI 안정성)
    private static final int LOAD_WINDOW_DAYS = 130;      // 로드 창 (영업일 기준 약 6개월)
    private static final int CORRELATION_DAYS = 60;       // 상관관계 기본 윈도우
    private static final int MAX_CORRELATION_STOCKS = 30; // 매트릭스 상한 (n^2 폭증 방지)
    private static final int BULK_COLLECT_RATE_MS = 450;  // KIS 호출 간격 (Rate Limit 보호)
    private static final int BULK_COLLECT_MAX = 1000;     // 단일 작업 상한

    // ==================== 일괄 수집 진행 상태 ====================
    private final AtomicBoolean bulkRunning = new AtomicBoolean(false);
    private final AtomicInteger bulkTotal = new AtomicInteger(0);
    private final AtomicInteger bulkProcessed = new AtomicInteger(0);
    private final AtomicInteger bulkSucceeded = new AtomicInteger(0);
    private final AtomicInteger bulkFailed = new AtomicInteger(0);
    private volatile LocalDateTime bulkStartedAt;
    private volatile LocalDateTime bulkFinishedAt;
    private volatile String bulkLastMessage;

    // ==================== 1. TA 스크리너 ====================

    /**
     * 조건 조합으로 종목 필터링.
     * 모든 조건은 AND. null 또는 false인 조건은 무시.
     */
    public Map<String, Object> screen(ScreenerFilter filter, int limit) {
        if (filter == null) filter = new ScreenerFilter();
        int finalLimit = limit > 0 ? Math.min(limit, 200) : 50;

        // 1. universe 추출 (history 충분한 종목)
        List<String> universe = priceHistoryRepository.findStockCodesWithMinHistory(MIN_HISTORY_DAYS);
        log.info("[TA스크리너] universe: {} 종목 / 조건: {}", universe.size(), filter);

        if (universe.isEmpty()) {
            return Map.of("results", List.of(), "universeSize", 0, "matchedCount", 0);
        }

        // 2. 일봉 일괄 로드
        LocalDate since = LocalDate.now().minusDays(LOAD_WINDOW_DAYS);
        List<StockPriceHistory> all = priceHistoryRepository.findByStockCodesSince(universe, since);
        Map<String, List<StockPriceHistory>> byCode = all.stream()
                .collect(Collectors.groupingBy(StockPriceHistory::getStockCode));

        // 3. 종목별 지표 계산 + 조건 필터
        List<ScreenerHit> hits = new ArrayList<>();
        for (Map.Entry<String, List<StockPriceHistory>> e : byCode.entrySet()) {
            List<StockPriceHistory> rows = e.getValue();
            if (rows.size() < MIN_HISTORY_DAYS) continue;

            ScreenerHit hit = evaluate(e.getKey(), rows, filter);
            if (hit != null) hits.add(hit);
        }

        // 4. 정렬 — 점수 내림차순 (조건 통과 강도)
        hits.sort(Comparator.comparingInt(ScreenerHit::getMatchScore).reversed());
        List<ScreenerHit> top = hits.stream().limit(finalLimit).collect(Collectors.toList());

        // 5. 종목명 누락 보정 — history에 stockName 없는 종목은 stock_price fallback
        List<String> missingNameCodes = top.stream()
                .filter(h -> h.stockName == null || h.stockName.isBlank())
                .map(ScreenerHit::getStockCode)
                .collect(Collectors.toList());
        if (!missingNameCodes.isEmpty()) {
            Map<String, String> resolved = resolveNames(missingNameCodes);
            for (ScreenerHit h : top) {
                if (h.stockName == null || h.stockName.isBlank()) {
                    String name = resolved.get(h.stockCode);
                    if (name != null && !name.equals(h.stockCode)) h.stockName = name;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("results", top);
        result.put("universeSize", universe.size());
        result.put("matchedCount", hits.size());
        result.put("filter", filter);
        return result;
    }

    private ScreenerHit evaluate(String stockCode, List<StockPriceHistory> rowsDesc, ScreenerFilter f) {
        // rowsDesc: tradeDate DESC. 지표 계산용으로 종가 추출 (최신 = index 0).
        List<BigDecimal> prices = rowsDesc.stream()
                .map(StockPriceHistory::getClosePrice)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (prices.size() < MIN_HISTORY_DAYS) return null;

        StockPriceHistory latest = rowsDesc.get(0);
        BigDecimal currentPrice = latest.getClosePrice();
        if (currentPrice == null || currentPrice.signum() <= 0) return null;

        TechnicalIndicatorsDto ind = technicalIndicatorService.calculate(prices);
        if (ind == null) return null;

        // 거래량 비율: 최근 거래량 / 직전 20일 평균
        BigDecimal volRatio = computeVolumeRatio(rowsDesc, 20);

        // 볼린저 — calculate()로 안 채워지는 항목이 있어 별도 호출
        TechnicalIndicatorService.BollingerBandsResult bb =
                technicalIndicatorService.calculateBollingerBands(prices);

        int score = 0;
        List<String> matchedTags = new ArrayList<>();

        // -------- 조건 평가 --------
        if (f.rsiBelow != null) {
            BigDecimal rsi = ind.getRsi14();
            if (rsi == null || rsi.compareTo(BigDecimal.valueOf(f.rsiBelow)) >= 0) return null;
            score += 20;
            matchedTags.add("RSI<" + f.rsiBelow);
        }
        if (f.rsiAbove != null) {
            BigDecimal rsi = ind.getRsi14();
            if (rsi == null || rsi.compareTo(BigDecimal.valueOf(f.rsiAbove)) <= 0) return null;
            score += 15;
            matchedTags.add("RSI>" + f.rsiAbove);
        }
        if (Boolean.TRUE.equals(f.goldenCross)) {
            if (!Boolean.TRUE.equals(ind.getIsGoldenCross())) return null;
            score += 25;
            matchedTags.add("골든크로스");
        }
        if (Boolean.TRUE.equals(f.arrangedUp)) {
            if (!Boolean.TRUE.equals(ind.getIsArrangedUp())) return null;
            score += 15;
            matchedTags.add("정배열");
        }
        if (Boolean.TRUE.equals(f.aboveMa20)) {
            if (!Boolean.TRUE.equals(ind.getIsAboveMa20())) return null;
            score += 5;
            matchedTags.add("MA20위");
        }
        if (Boolean.TRUE.equals(f.belowMa20)) {
            if (Boolean.TRUE.equals(ind.getIsAboveMa20())) return null;
            score += 5;
            matchedTags.add("MA20아래");
        }
        if (f.volumeRatioMin != null) {
            if (volRatio == null || volRatio.compareTo(BigDecimal.valueOf(f.volumeRatioMin)) < 0) return null;
            score += 15;
            matchedTags.add("거래량x" + f.volumeRatioMin);
        }
        if (Boolean.TRUE.equals(f.bollingerLowerTouch)) {
            if (bb == null || bb.getLowerBand() == null) return null;
            // 종가가 하단 밴드의 102% 이하면 터치/근접으로 판단
            BigDecimal threshold = bb.getLowerBand().multiply(BigDecimal.valueOf(1.02));
            if (currentPrice.compareTo(threshold) > 0) return null;
            score += 20;
            matchedTags.add("볼린저하단터치");
        }
        if (Boolean.TRUE.equals(f.bollingerSqueeze)) {
            if (bb == null || !Boolean.TRUE.equals(bb.getIsSqueeze())) return null;
            score += 15;
            matchedTags.add("볼린저스퀴즈");
        }
        if (f.changeRateMin != null) {
            BigDecimal cr = latest.getChangeRate();
            if (cr == null || cr.compareTo(BigDecimal.valueOf(f.changeRateMin)) < 0) return null;
            score += 5;
        }
        if (f.changeRateMax != null) {
            BigDecimal cr = latest.getChangeRate();
            if (cr == null || cr.compareTo(BigDecimal.valueOf(f.changeRateMax)) > 0) return null;
            score += 5;
        }

        // 조건이 하나도 안 걸린 경우(=빈 필터) → 결과 안 반환
        if (matchedTags.isEmpty() && score == 0) return null;

        ScreenerHit hit = new ScreenerHit();
        hit.stockCode = stockCode;
        hit.stockName = latest.getStockName();
        hit.tradeDate = latest.getTradeDate();
        hit.closePrice = currentPrice;
        hit.changeRate = latest.getChangeRate();
        hit.rsi14 = ind.getRsi14();
        hit.ma5 = ind.getMa5();
        hit.ma20 = ind.getMa20();
        hit.ma60 = ind.getMa60();
        hit.isGoldenCross = ind.getIsGoldenCross();
        hit.isArrangedUp = ind.getIsArrangedUp();
        hit.volumeRatio = volRatio;
        hit.bollingerLower = bb != null ? bb.getLowerBand() : null;
        hit.bollingerUpper = bb != null ? bb.getUpperBand() : null;
        hit.matchedTags = matchedTags;
        hit.matchScore = score;
        return hit;
    }

    /**
     * 최근 거래량 / 직전 N일 평균 거래량.
     * rowsDesc: tradeDate DESC, 0번이 최신.
     */
    private BigDecimal computeVolumeRatio(List<StockPriceHistory> rowsDesc, int window) {
        if (rowsDesc.size() < window + 1) return null;
        BigDecimal latest = rowsDesc.get(0).getVolume();
        if (latest == null || latest.signum() <= 0) return null;

        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (int i = 1; i <= window && i < rowsDesc.size(); i++) {
            BigDecimal v = rowsDesc.get(i).getVolume();
            if (v != null && v.signum() > 0) {
                sum = sum.add(v);
                n++;
            }
        }
        if (n == 0) return null;
        BigDecimal avg = sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
        if (avg.signum() == 0) return null;
        return latest.divide(avg, 2, RoundingMode.HALF_UP);
    }

    // ==================== 2-1. 관련 종목 (correlation 기반) ====================

    /**
     * 종목 → 함께 움직이는 관련 종목 top N.
     * 같은 섹터 종목들과 60일 종가 correlation 계산 → 0.5+ desc 정렬.
     *
     * Universe 결정:
     *  - SectorStockConfig 의 모든 섹터 중 입력 종목이 속한 섹터 합집합
     *  - 자기 자신 제외, 중복 제거, 최대 25개 (correlation 30개 cap 안에서)
     */
    public List<RelatedStockDto> getRelatedStocks(String stockCode, int limit) {
        if (stockCode == null || stockCode.isEmpty()) return Collections.emptyList();

        // 같은 섹터 종목 universe
        Set<String> universe = new LinkedHashSet<>();
        for (SectorStockConfig.SectorInfo sector : sectorStockConfig.getAllSectors()) {
            if (sector.getStockCodes().contains(stockCode)) {
                for (String code : sector.getStockCodes()) {
                    if (!code.equals(stockCode)) universe.add(code);
                }
            }
        }
        if (universe.isEmpty()) return Collections.emptyList();

        // correlation 호출용 universe = 자기 + 비교 후보 (자기 row 가 있어야 매트릭스에서 추출 가능)
        List<String> codes = new ArrayList<>();
        codes.add(stockCode);
        codes.addAll(universe.stream().limit(MAX_CORRELATION_STOCKS - 1).toList());

        Map<String, Object> corrResult = correlation(codes, CORRELATION_DAYS);
        double[][] matrix = (double[][]) corrResult.get("matrix");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stocksList = (List<Map<String, Object>>) corrResult.get("stocks");
        if (matrix == null || stocksList == null || matrix.length == 0) return Collections.emptyList();

        // stockCode 의 row 인덱스 찾기
        int myIdx = -1;
        for (int i = 0; i < stocksList.size(); i++) {
            if (stockCode.equals(stocksList.get(i).get("stockCode"))) { myIdx = i; break; }
        }
        if (myIdx < 0 || myIdx >= matrix.length) return Collections.emptyList();

        // 자기 row 의 다른 종목 correlation 추출 → 0.5+ desc top N
        List<RelatedStockDto> results = new ArrayList<>();
        double[] myRow = matrix[myIdx];
        for (int j = 0; j < stocksList.size() && j < myRow.length; j++) {
            if (j == myIdx) continue;
            double corr = myRow[j];
            if (corr < 0.5) continue;
            Map<String, Object> peer = stocksList.get(j);
            String code = (String) peer.get("stockCode");
            String name = (String) peer.get("stockName");
            if (name == null || name.isBlank()) name = stockMasterService.getNameOrDefault(code, code);
            results.add(new RelatedStockDto(code, name,
                    BigDecimal.valueOf(corr).setScale(3, RoundingMode.HALF_UP)));
        }
        results.sort((a, b) -> b.correlation().compareTo(a.correlation()));
        return results.stream().limit(Math.max(1, Math.min(limit, 10))).toList();
    }

    /** 관련 종목 결과 DTO. */
    public record RelatedStockDto(String stockCode, String stockName, BigDecimal correlation) {}

    // ==================== 2-2. 강세 섹터 ====================

    /** 강세 판정 임계 — 섹터 평균 등락률 +0.5% 이상. */
    private static final BigDecimal STRONG_SECTOR_MIN = new BigDecimal("0.5");

    /**
     * 오늘 강세 섹터 — 섹터 종목들 평균 등락률 desc top N.
     * 각 섹터의 강세 종목(개별 등락률 desc) top 3 함께 반환.
     * 30분 캐시 (장중 변동 빠름).
     */
    @org.springframework.cache.annotation.Cacheable(value = "chartPatterns", key = "'ss:all'")
    public List<StrongSectorDto> getStrongSectors() {
        // 모든 섹터의 종목 모음 (중복 제거)
        Set<String> allCodes = new LinkedHashSet<>();
        for (SectorStockConfig.SectorInfo sector : sectorStockConfig.getAllSectors()) {
            allCodes.addAll(sector.getStockCodes());
        }
        if (allCodes.isEmpty()) return Collections.emptyList();

        // batch 시세 조회 — KIS 비용 큼. 캐시 활용.
        Map<String, com.myplatform.backend.dto.StockPriceDto> priceMap;
        try {
            priceMap = stockPriceService.getStockPrices(new ArrayList<>(allCodes));
        } catch (Exception e) {
            log.warn("[강세섹터] 시세 batch 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (priceMap == null || priceMap.isEmpty()) return Collections.emptyList();

        // 섹터별 평균 등락률 계산
        List<StrongSectorDto> sectors = new ArrayList<>();
        for (SectorStockConfig.SectorInfo sector : sectorStockConfig.getAllSectors()) {
            List<StrongSectorDto.SectorStock> stocks = new ArrayList<>();
            BigDecimal sumChange = BigDecimal.ZERO;
            int count = 0;
            for (String code : sector.getStockCodes()) {
                com.myplatform.backend.dto.StockPriceDto p = priceMap.get(code);
                if (p == null || p.getChangeRate() == null) continue;
                stocks.add(new StrongSectorDto.SectorStock(
                        code,
                        p.getStockName() != null ? p.getStockName()
                                : stockMasterService.getNameOrDefault(code, code),
                        p.getChangeRate()));
                sumChange = sumChange.add(p.getChangeRate());
                count++;
            }
            if (count == 0) continue;
            BigDecimal avg = sumChange.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
            // 강세 섹터만 (+0.5% 이상)
            if (avg.compareTo(STRONG_SECTOR_MIN) < 0) continue;

            // 섹터 안 강세 종목 top 3 (개별 등락률 desc)
            stocks.sort((a, b) -> b.changeRate().compareTo(a.changeRate()));
            sectors.add(new StrongSectorDto(
                    sector.getCode(), sector.getName(), sector.getColor(),
                    avg, count, stocks.stream().limit(3).toList()));
        }

        // 평균 등락률 desc 정렬
        sectors.sort((a, b) -> b.avgChangeRate().compareTo(a.avgChangeRate()));
        return sectors;
    }

    /** 강세 섹터 결과 DTO. */
    public record StrongSectorDto(String sectorCode, String sectorName, String color,
                                  BigDecimal avgChangeRate, int stockCount,
                                  List<SectorStock> topStocks) {
        public record SectorStock(String stockCode, String stockName, BigDecimal changeRate) {}
    }

    // ==================== 2-3. 섹터 공통 키워드 (co-occurrence, AI 없이) ====================

    /** 한국어 주식 도메인 stopword — 자주 등장하지만 의미 없는 단어. */
    private static final Set<String> KEYWORD_STOPWORDS = Set.of(
            "오늘", "내일", "어제", "이번", "지난", "다음", "최근", "올해", "작년",
            "있다", "없다", "되다", "한다", "하는", "있는", "없는", "되는", "위해",
            "기업", "회사", "관련", "전망", "예상", "가능", "기대", "확대", "확보",
            "발생", "진행", "통해", "특히", "정도", "수준", "가운데",
            "증권", "주식", "종목", "투자", "시장", "거래", "주가", "투자자",
            "코스피", "코스닥", "지수", "달러", "원화", "환율",
            "이날", "이번주", "지난주", "이번달", "지난달",
            "으로", "에서", "보다", "함께", "대한", "대해", "통해",
            "보고", "분석", "발표", "공시", "공개", "결정", "추진", "계획"
    );
    /** 단어 정의 — 2~10글자 한글/영문/숫자/하이픈. 조사 어절 끝에 붙은 것 후처리. */
    private static final java.util.regex.Pattern WORD_PATTERN =
            java.util.regex.Pattern.compile("[가-힣A-Za-z0-9-]{2,10}");
    /** 어절 끝 흔한 조사 — 추출 후 제거. */
    private static final java.util.regex.Pattern KOREAN_PARTICLE_TAIL =
            java.util.regex.Pattern.compile("(은|는|이|가|을|를|에|의|와|과|도|만|로|으로|에서|에게|부터|까지|라고|이라고|이라는|라는)$");

    /**
     * 강세 섹터의 공통 키워드 추출 (AI 0건).
     * 알고리즘:
     *  1. 오늘 NewsSummary 모두 가져옴
     *  2. 섹터 안 종목명이 제목/요약에 등장한 뉴스 = "관련 뉴스"
     *  3. 그 뉴스들의 단어 빈도 → stopword + 종목명 자체 제외 → top N
     *
     * 1시간 캐시 (뉴스는 자주 변하지 않음).
     */
    @org.springframework.cache.annotation.Cacheable(value = "chartPatterns",
            key = "'kw:' + #sectorCode")
    public List<KeywordDto> getSectorKeywords(String sectorCode, int limit) {
        SectorStockConfig.SectorInfo sector = sectorStockConfig.getSector(sectorCode);
        if (sector == null) return Collections.emptyList();

        // 섹터 안 종목명 모음 (소문자 비교용 X — 한글이라 case 무관)
        Map<String, String> codeToName = new HashMap<>();
        for (String code : sector.getStockCodes()) {
            codeToName.put(code, stockMasterService.getNameOrDefault(code, code));
        }
        Set<String> stockNames = new HashSet<>(codeToName.values());
        if (stockNames.isEmpty()) return Collections.emptyList();

        // 오늘 뉴스 가져옴
        java.time.LocalDateTime startOfDay = java.time.LocalDate.now().atStartOfDay();
        List<com.myplatform.backend.entity.NewsSummary> todayNews;
        try {
            todayNews = newsSummaryRepository.findTodayNews(startOfDay);
        } catch (Exception e) {
            log.warn("[키워드] 오늘 뉴스 조회 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (todayNews.isEmpty()) return Collections.emptyList();

        // 종목명 매칭 뉴스만 필터
        List<String> relevantTexts = new ArrayList<>();
        for (var news : todayNews) {
            String text = (news.getTitle() != null ? news.getTitle() : "")
                    + " " + (news.getSummary() != null ? news.getSummary() : "");
            for (String name : stockNames) {
                if (name.length() >= 2 && text.contains(name)) {
                    relevantTexts.add(text);
                    break;
                }
            }
        }
        if (relevantTexts.isEmpty()) return Collections.emptyList();

        // 단어 빈도 카운트
        Map<String, Integer> wordCount = new HashMap<>();
        for (String text : relevantTexts) {
            java.util.regex.Matcher m = WORD_PATTERN.matcher(text);
            while (m.find()) {
                String word = stripParticle(m.group());
                if (word.length() < 2) continue;
                if (KEYWORD_STOPWORDS.contains(word)) continue;
                if (stockNames.contains(word)) continue;       // 종목명 자체 제외
                if (sector.getName().contains(word)) continue; // 섹터명도 제외
                wordCount.merge(word, 1, Integer::sum);
            }
        }
        if (wordCount.isEmpty()) return Collections.emptyList();

        // top N (빈도 desc, 동점이면 단어 길이 desc — 더 구체적인 단어 선호)
        return wordCount.entrySet().stream()
                .filter(e -> e.getValue() >= 2)  // 최소 2번 등장
                .sorted((a, b) -> {
                    int cmp = b.getValue().compareTo(a.getValue());
                    return cmp != 0 ? cmp : Integer.compare(b.getKey().length(), a.getKey().length());
                })
                .limit(Math.max(1, Math.min(limit, 15)))
                .map(e -> new KeywordDto(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    /** 한국어 어절 끝의 조사 제거 (간단 휴리스틱). */
    private static String stripParticle(String word) {
        java.util.regex.Matcher m = KOREAN_PARTICLE_TAIL.matcher(word);
        if (m.find() && word.length() - m.group().length() >= 2) {
            return word.substring(0, m.start());
        }
        return word;
    }

    /** 키워드 결과 DTO. */
    public record KeywordDto(String keyword, int frequency) {}

    // ==================== 2. 상관관계 매트릭스 ====================

    /**
     * 종목 리스트에 대한 일봉 종가 기반 피어슨 상관계수 매트릭스.
     * - 입력: stockCodes (최대 30개)
     * - days: 비교 기간 (기본 60)
     * - 일변화율(returns) 기반으로 계산 (raw price보다 표준 관행)
     */
    public Map<String, Object> correlation(List<String> stockCodes, int days) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return Map.of("matrix", List.of(), "stocks", List.of(), "warnings", List.of("종목이 비어있음"));
        }
        // 중복 제거 + 상한
        List<String> codes = stockCodes.stream().distinct().limit(MAX_CORRELATION_STOCKS).collect(Collectors.toList());
        int window = days > 0 ? Math.min(days, 250) : CORRELATION_DAYS;

        LocalDate since = LocalDate.now().minusDays(window + 30L); // 휴일 여유
        List<StockPriceHistory> rows = priceHistoryRepository.findByStockCodesSince(codes, since);
        Map<String, List<StockPriceHistory>> byCode = rows.stream()
                .collect(Collectors.groupingBy(StockPriceHistory::getStockCode));

        // 공통 거래일 집합 — 모든 종목에 존재하는 날짜만 사용
        // tradeDate별 종가 매핑
        Map<String, Map<LocalDate, BigDecimal>> closeMap = new HashMap<>();
        Map<String, String> nameMap = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        for (String code : codes) {
            List<StockPriceHistory> hs = byCode.get(code);
            if (hs == null || hs.size() < 10) {
                warnings.add(code + ": 데이터 부족");
                continue;
            }
            Map<LocalDate, BigDecimal> m = new HashMap<>();
            for (StockPriceHistory h : hs) {
                if (h.getClosePrice() != null) m.put(h.getTradeDate(), h.getClosePrice());
            }
            closeMap.put(code, m);
            nameMap.put(code, hs.get(0).getStockName());
        }

        // 데이터 부족 종목도 종목명을 찾아서 warning 메시지에 포함
        for (int i = 0; i < warnings.size(); i++) {
            String w = warnings.get(i);
            int colonIdx = w.indexOf(':');
            if (colonIdx > 0) {
                String code = w.substring(0, colonIdx).trim();
                List<StockPriceHistory> hs = byCode.get(code);
                if (hs != null && !hs.isEmpty() && hs.get(0).getStockName() != null) {
                    warnings.set(i, code + " " + hs.get(0).getStockName() + w.substring(colonIdx));
                }
            }
        }

        List<String> validCodes = codes.stream().filter(closeMap::containsKey).collect(Collectors.toList());
        if (validCodes.size() < 2) {
            return Map.of("matrix", List.of(), "stocks", List.of(), "warnings", warnings);
        }

        // 공통 날짜 — validCodes 모두에 존재하는 거래일
        List<LocalDate> commonDates = closeMap.get(validCodes.get(0)).keySet().stream()
                .filter(d -> validCodes.stream().allMatch(c -> closeMap.get(c).containsKey(d)))
                .sorted()
                .collect(Collectors.toList());
        if (commonDates.size() > window + 1) {
            commonDates = commonDates.subList(commonDates.size() - (window + 1), commonDates.size());
        }
        if (commonDates.size() < 10) {
            warnings.add("공통 거래일 부족: " + commonDates.size() + "일");
            return Map.of("matrix", List.of(), "stocks", List.of(), "warnings", warnings);
        }

        // 일별 수익률 (returns) — log return 대신 simple return 사용
        Map<String, double[]> returnsMap = new HashMap<>();
        for (String code : validCodes) {
            Map<LocalDate, BigDecimal> m = closeMap.get(code);
            double[] r = new double[commonDates.size() - 1];
            for (int i = 1; i < commonDates.size(); i++) {
                double prev = m.get(commonDates.get(i - 1)).doubleValue();
                double curr = m.get(commonDates.get(i)).doubleValue();
                r[i - 1] = prev == 0 ? 0 : (curr - prev) / prev;
            }
            returnsMap.put(code, r);
        }

        // 종목명 누락 보정 — history에 없는 종목은 stock_price fallback
        List<String> missingForName = validCodes.stream()
                .filter(c -> nameMap.get(c) == null || nameMap.get(c).isBlank())
                .collect(Collectors.toList());
        if (!missingForName.isEmpty()) {
            Map<String, String> resolved = resolveNames(missingForName);
            resolved.forEach((k, v) -> { if (v != null && !v.equals(k)) nameMap.put(k, v); });
        }

        // 매트릭스 계산
        List<Map<String, Object>> stocks = new ArrayList<>();
        for (String code : validCodes) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("stockCode", code);
            s.put("stockName", nameMap.getOrDefault(code, code));
            stocks.add(s);
        }
        double[][] matrix = new double[validCodes.size()][validCodes.size()];
        for (int i = 0; i < validCodes.size(); i++) {
            for (int j = 0; j < validCodes.size(); j++) {
                if (i == j) {
                    matrix[i][j] = 1.0;
                } else if (j > i) {
                    matrix[i][j] = pearson(returnsMap.get(validCodes.get(i)), returnsMap.get(validCodes.get(j)));
                } else {
                    matrix[i][j] = matrix[j][i];
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stocks", stocks);
        result.put("matrix", matrix);
        result.put("daysUsed", commonDates.size() - 1);
        result.put("warnings", warnings);
        return result;
    }

    private double pearson(double[] x, double[] y) {
        if (x == null || y == null || x.length != y.length || x.length < 2) return 0;
        int n = x.length;
        double sumX = 0, sumY = 0;
        for (int i = 0; i < n; i++) { sumX += x[i]; sumY += y[i]; }
        double meanX = sumX / n, meanY = sumY / n;
        double num = 0, dx2 = 0, dy2 = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX, dy = y[i] - meanY;
            num += dx * dy;
            dx2 += dx * dx;
            dy2 += dy * dy;
        }
        double denom = Math.sqrt(dx2 * dy2);
        if (denom == 0) return 0;
        double r = num / denom;
        return Math.round(r * 1000.0) / 1000.0;
    }

    // ==================== 종목명 해석 ====================

    /**
     * 종목 코드 → 종목명 매핑.
     * 1. stock_price_history 에서 가장 최근 stockName 사용
     * 2. 없으면 stock_price (실시간 시세) fallback
     * 3. 그래도 없으면 코드 자체를 name으로
     */
    public Map<String, String> resolveNames(List<String> codes) {
        if (codes == null || codes.isEmpty()) return Map.of();
        Map<String, String> nameMap = new LinkedHashMap<>();

        // 1차: history 에서 (빈 문자열은 무효 처리)
        try {
            LocalDate since = LocalDate.now().minusDays(LOAD_WINDOW_DAYS);
            List<StockPriceHistory> rows = priceHistoryRepository.findByStockCodesSince(codes, since);
            for (StockPriceHistory r : rows) {
                String n = r.getStockName();
                if (n != null && !n.isBlank() && !nameMap.containsKey(r.getStockCode())) {
                    nameMap.put(r.getStockCode(), n);
                }
            }
        } catch (Exception e) {
            log.debug("history 종목명 조회 실패: {}", e.getMessage());
        }

        // 2차: 미해결 코드 → stock_price (실시간 시세 캐시)
        for (String code : codes) {
            if (nameMap.containsKey(code)) continue;
            try {
                stockPriceRepository.findTopByStockCodeOrderByFetchedAtDesc(code).ifPresent(p -> {
                    String n = p.getStockName();
                    if (n != null && !n.isBlank()) nameMap.put(code, n);
                });
            } catch (Exception ignore) {}
        }

        // 3차: 하드코딩된 주요 종목 매핑 (StockNameResolver)
        for (String code : codes) {
            if (nameMap.containsKey(code)) continue;
            String n = StockNameResolver.getName(code);
            if (n != null) nameMap.put(code, n);
        }

        // 미해결 → 코드 그대로
        for (String code : codes) nameMap.putIfAbsent(code, code);
        return nameMap;
    }

    /**
     * stockName이 빈/NULL인 history 행에 종목명을 일괄 보정.
     * stock_price → 하드코딩 매핑 순으로 해석 후 UPDATE.
     */
    @Transactional
    public Map<String, Object> backfillMissingNames() {
        List<String> codes = priceHistoryRepository.findStockCodesWithMissingName();
        if (codes.isEmpty()) {
            return Map.of("totalCodes", 0, "updated", 0, "stillMissing", 0);
        }
        Map<String, String> resolved = resolveNames(codes);
        int updatedCodes = 0;
        int updatedRows = 0;
        int stillMissing = 0;
        for (String code : codes) {
            String n = resolved.get(code);
            if (n == null || n.isBlank() || n.equals(code)) {
                stillMissing++;
                continue;
            }
            int rows = priceHistoryRepository.updateStockNameByCode(code, n);
            if (rows > 0) {
                updatedCodes++;
                updatedRows += rows;
            }
        }
        log.info("[종목명보정] 대상 {}종목 / 보정 {}종목 ({} 행) / 미해결 {}",
                codes.size(), updatedCodes, updatedRows, stillMissing);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalCodes", codes.size());
        r.put("updatedCodes", updatedCodes);
        r.put("updatedRows", updatedRows);
        r.put("stillMissing", stillMissing);
        return r;
    }

    // ==================== 3. 데이터 상태 + 일괄 수집 ====================

    /**
     * 현재 universe 현황 — 스크리너 결과의 신뢰성 판단용.
     */
    public Map<String, Object> getUniverseStatus() {
        long ready = priceHistoryRepository.findStockCodesWithMinHistory(MIN_HISTORY_DAYS).size();
        long anyHistory = priceHistoryRepository.findStockCodesWithMinHistory(1).size();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("readyCount", ready);          // ≥ MIN_HISTORY_DAYS 일 보유한 종목 (스크리너 universe)
        r.put("anyHistoryCount", anyHistory); // 1일이라도 데이터가 있는 종목
        r.put("minHistoryDays", MIN_HISTORY_DAYS);
        return r;
    }

    /**
     * 거래량 상위 N개 종목의 일봉을 KIS API로 일괄 수집.
     * - 비동기 백그라운드 실행. {@link #getBulkProgress()}로 진행률 폴링.
     * - 동시에 1건만 실행 가능.
     * - rate limit 보호: 호출당 BULK_COLLECT_RATE_MS 대기.
     */
    public Map<String, Object> startBulkCollection(int requestedTopN) {
        if (!bulkRunning.compareAndSet(false, true)) {
            return Map.of(
                    "success", false,
                    "message", "이미 수집 작업이 실행 중입니다. 진행 상태를 확인하세요."
            );
        }

        int topN = Math.min(Math.max(requestedTopN, 10), BULK_COLLECT_MAX);
        List<String> codes;
        try {
            codes = stockPriceRepository.findTopVolumeStockCodes(PageRequest.of(0, topN));
        } catch (Exception e) {
            bulkRunning.set(false);
            log.error("[일괄수집] universe 조회 실패", e);
            return Map.of("success", false, "message", "종목 추출 실패: " + e.getMessage());
        }

        if (codes == null || codes.isEmpty()) {
            bulkRunning.set(false);
            return Map.of("success", false, "message", "거래량 데이터가 없습니다");
        }

        bulkTotal.set(codes.size());
        bulkProcessed.set(0);
        bulkSucceeded.set(0);
        bulkFailed.set(0);
        bulkStartedAt = LocalDateTime.now();
        bulkFinishedAt = null;
        bulkLastMessage = null;

        log.info("[일괄수집] 시작 - {}종목, KIS rate {}ms", codes.size(), BULK_COLLECT_RATE_MS);

        Thread worker = new Thread(() -> runBulkCollection(codes), "quant-ta-bulk-collect");
        worker.setDaemon(true);
        worker.start();

        long etaSec = (long) Math.ceil(codes.size() * (BULK_COLLECT_RATE_MS / 1000.0));
        return Map.of(
                "success", true,
                "started", true,
                "total", codes.size(),
                "etaSeconds", etaSec,
                "message", String.format("%d종목 수집 시작 (예상 %d초)", codes.size(), etaSec)
        );
    }

    private void runBulkCollection(List<String> codes) {
        try {
            for (String code : codes) {
                try {
                    stockAnalysisService.collectPriceHistory(code);
                    bulkSucceeded.incrementAndGet();
                } catch (Exception e) {
                    bulkFailed.incrementAndGet();
                    log.debug("[일괄수집] 실패 {}: {}", code, e.getMessage());
                } finally {
                    bulkProcessed.incrementAndGet();
                }
                try {
                    Thread.sleep(BULK_COLLECT_RATE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    bulkLastMessage = "중단됨";
                    return;
                }
            }
            bulkLastMessage = String.format("완료 — 성공 %d / 실패 %d", bulkSucceeded.get(), bulkFailed.get());
            log.info("[일괄수집] {}", bulkLastMessage);
        } finally {
            bulkFinishedAt = LocalDateTime.now();
            bulkRunning.set(false);
        }
    }

    public Map<String, Object> getBulkProgress() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("running", bulkRunning.get());
        r.put("total", bulkTotal.get());
        r.put("processed", bulkProcessed.get());
        r.put("succeeded", bulkSucceeded.get());
        r.put("failed", bulkFailed.get());
        r.put("startedAt", bulkStartedAt);
        r.put("finishedAt", bulkFinishedAt);
        r.put("message", bulkLastMessage);
        int total = bulkTotal.get();
        int processed = bulkProcessed.get();
        r.put("percent", total == 0 ? 0 : (int) Math.round(processed * 100.0 / total));
        return r;
    }

    // ==================== DTOs ====================

    @lombok.Data
    public static class ScreenerFilter {
        private Double rsiBelow;          // RSI < value
        private Double rsiAbove;          // RSI > value
        private Boolean goldenCross;      // 5일선이 20일선 상향돌파
        private Boolean arrangedUp;       // 정배열 (5>20>60)
        private Boolean aboveMa20;        // 종가 > MA20
        private Boolean belowMa20;        // 종가 < MA20
        private Double volumeRatioMin;    // 거래량 / 20일평균 ≥ value
        private Boolean bollingerLowerTouch; // 종가 ≤ 볼린저 하단 * 1.02
        private Boolean bollingerSqueeze;    // 밴드폭 < 평균 * 0.7
        private Double changeRateMin;     // 등락률 ≥ value
        private Double changeRateMax;     // 등락률 ≤ value
    }

    @lombok.Data
    public static class ScreenerHit {
        private String stockCode;
        private String stockName;
        private LocalDate tradeDate;
        private BigDecimal closePrice;
        private BigDecimal changeRate;
        private BigDecimal rsi14;
        private BigDecimal ma5;
        private BigDecimal ma20;
        private BigDecimal ma60;
        private Boolean isGoldenCross;
        private Boolean isArrangedUp;
        private BigDecimal volumeRatio;
        private BigDecimal bollingerLower;
        private BigDecimal bollingerUpper;
        private List<String> matchedTags;
        private int matchScore;
    }
}
