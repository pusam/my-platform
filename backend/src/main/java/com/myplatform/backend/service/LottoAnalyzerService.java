package com.myplatform.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.LottoAnalysisDto;
import com.myplatform.backend.dto.LottoAnalysisDto.NumberStatDto;
import com.myplatform.backend.dto.LottoAnalysisDto.StatisticsSummaryDto;
import com.myplatform.backend.dto.LottoDrawDto;
import com.myplatform.backend.dto.LottoRecommendationDto;
import com.myplatform.backend.entity.LottoDraw;
import com.myplatform.backend.entity.LottoWeeklyRecommendation;
import com.myplatform.backend.repository.LottoDrawRepository;
import com.myplatform.backend.repository.LottoWeeklyRecommendationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 통계 기반 로또 번호 추출기 (Lotto Quant Analyzer)
 * - DB 기반 데이터 저장 및 조회
 * - 일요일 06:00 배치로 데이터 수집 및 추천번호 생성
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LottoAnalyzerService {

    private static final String LOTTO_API_URL = "https://www.dhlottery.co.kr/common.do?method=getLottoNumber&drwNo=";
    private final com.myplatform.backend.util.DhLotteryClient dhLotteryClient;
    private static final int MAX_NUMBER = 45;
    private static final int PICK_COUNT = 6;
    private static final int GAME_COUNT = 5;

    // 필터링 조건
    private static final int MIN_SUM = 120;
    private static final int MAX_SUM = 180;
    private static final int LOW_HIGH_BOUNDARY = 22;
    private static final int MAX_CONSECUTIVE = 2;

    private final LottoDrawRepository drawRepository;
    private final LottoWeeklyRecommendationRepository weeklyRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = createTimeoutRestTemplate();

    private static RestTemplate createTimeoutRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }
    private final SecureRandom random = new SecureRandom();

    // ==================== 스케줄러 ====================

    /**
     * 매주 일요일 06:00에 로또 데이터 수집 및 추천번호 생성
     * (토요일 추첨 후 일요일에 결과 반영)
     */
    @Scheduled(cron = "0 0 6 * * SUN", zone = "Asia/Seoul")
    @Transactional
    public void weeklyBatchJob() {
        log.info("[로또배치] ===== 주간 배치 시작 =====");

        try {
            // 1. 최신 당첨 데이터 수집
            int collected = collectLatestDraws();
            log.info("[로또배치] 당첨 데이터 수집 완료: {}건", collected);

            // 2. 금주의 추천 번호 생성
            generateAndSaveWeeklyRecommendation();
            log.info("[로또배치] 금주의 추천 번호 생성 완료");

        } catch (Exception e) {
            log.error("[로또배치] 배치 실행 오류: {}", e.getMessage(), e);
        }

        log.info("[로또배치] ===== 주간 배치 완료 =====");
    }

    /**
     * 서버 시작 시 데이터가 없으면 초기 데이터 수집
     * - 90초 지연으로 다른 초기화 작업과 리소스 경합 방지
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    @Transactional
    public void initializeDataIfEmpty() {
        try {
            Thread.sleep(90000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (drawRepository.count() == 0) {
            log.info("[로또초기화] DB에 데이터가 없어 초기 수집 시작...");
            collectLatestDraws();
            generateAndSaveWeeklyRecommendation();
            log.info("[로또초기화] 초기화 완료");
        }
    }

    // ==================== 데이터 수집 ====================

    /**
     * 최신 당첨 데이터 수집 (최대 100회차)
     * - 연속 5회 실패 시 조기 중단 (API 장애/차단 대응)
     */
    @Transactional
    public int collectLatestDraws() {
        int collected = 0;
        int consecutiveFailures = 0;
        int latestInDb = drawRepository.findMaxDrawNo().orElse(0);
        int estimatedLatest = estimateLatestDrawNo();

        log.info("[로또수집] DB 최신: {}회, 추정 최신: {}회", latestInDb, estimatedLatest);

        // 새 회차부터 수집
        for (int drawNo = estimatedLatest; drawNo > Math.max(latestInDb, estimatedLatest - 100) && drawNo > 0; drawNo--) {
            if (!drawRepository.existsByDrawNo(drawNo)) {
                LottoDraw draw = fetchAndSaveDraw(drawNo);
                if (draw != null) {
                    collected++;
                    consecutiveFailures = 0;
                } else {
                    consecutiveFailures++;
                    if (consecutiveFailures >= 5) {
                        log.warn("[로또수집] 연속 {}회 실패 - 수집 중단 (API 장애 가능성)", consecutiveFailures);
                        break;
                    }
                }
            }
        }

        return collected;
    }

    /**
     * 외부 API에서 회차 데이터 조회 및 저장
     */
    private LottoDraw fetchAndSaveDraw(int drawNo) {
        try {
            String url = LOTTO_API_URL + drawNo;
            String response = dhLotteryClient.callApi(url);

            if (response == null || response.isEmpty()) {
                return null;
            }

            JsonNode json = objectMapper.readTree(response);
            if (!"success".equals(json.path("returnValue").asText())) {
                return null;
            }

            String dateStr = json.path("drwNoDate").asText();
            LocalDate drawDate = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);

            LottoDraw draw = LottoDraw.builder()
                    .drawNo(drawNo)
                    .drawDate(drawDate)
                    .num1(json.path("drwtNo1").asInt())
                    .num2(json.path("drwtNo2").asInt())
                    .num3(json.path("drwtNo3").asInt())
                    .num4(json.path("drwtNo4").asInt())
                    .num5(json.path("drwtNo5").asInt())
                    .num6(json.path("drwtNo6").asInt())
                    .bonusNo(json.path("bnusNo").asInt())
                    .totalPrize(json.path("totSellamnt").asLong())
                    .firstWinnerCount(json.path("firstPrzwnerCo").asLong())
                    .firstWinAmount(json.path("firstWinamnt").asLong())
                    .build();

            LottoDraw saved = drawRepository.save(draw);
            log.debug("[로또수집] {}회차 저장 완료", drawNo);
            return saved;

        } catch (Exception e) {
            log.warn("[로또수집] {}회차 수집 실패: {}", drawNo, e.getMessage());
            return null;
        }
    }

    /**
     * 최신 회차 추정
     */
    private int estimateLatestDrawNo() {
        LocalDate firstDraw = LocalDate.of(2002, 12, 7);
        LocalDate today = LocalDate.now();
        long weeks = java.time.temporal.ChronoUnit.WEEKS.between(firstDraw, today);
        return (int) weeks + 1;
    }

    // ==================== 금주의 추천 번호 ====================

    /**
     * 금주의 추천 번호 생성 및 저장
     */
    @Transactional
    public void generateAndSaveWeeklyRecommendation() {
        LocalDate today = LocalDate.now();

        // 오늘 이미 생성된 추천이 있으면 스킵
        if (weeklyRepository.existsByGeneratedDate(today)) {
            log.info("[로또추천] 오늘({}) 이미 추천 번호가 생성됨", today);
            return;
        }

        // DB에서 최근 100회차 데이터 조회
        List<LottoDraw> draws = drawRepository.findRecentDraws(100);
        if (draws.isEmpty()) {
            log.error("[로또추천] 분석할 데이터가 없습니다");
            return;
        }

        // DTO로 변환
        List<LottoDrawDto> drawDtos = draws.stream()
                .map(this::toDrawDto)
                .collect(Collectors.toList());

        // 분석 실행
        LottoAnalysisDto analysis = performAnalysis(drawDtos);
        if (analysis == null) {
            return;
        }

        // 다음 회차 번호 계산
        int nextDrawNo = draws.get(0).getDrawNo() + 1;

        // DB 저장
        try {
            LottoWeeklyRecommendation recommendation = LottoWeeklyRecommendation.builder()
                    .generatedDate(today)
                    .targetDrawNo(nextDrawNo)
                    .latestAnalyzedDrawNo(draws.get(0).getDrawNo())
                    .analyzedDrawCount(draws.size())
                    .recommendations(objectMapper.writeValueAsString(analysis.getRecommendations()))
                    .statisticsSummary(objectMapper.writeValueAsString(analysis.getStatistics()))
                    .hotNumbers(objectMapper.writeValueAsString(analysis.getHotNumbers()))
                    .coldNumbers(objectMapper.writeValueAsString(analysis.getColdNumbers()))
                    .build();

            weeklyRepository.save(recommendation);
            log.info("[로또추천] 추천 번호 저장 완료 - 대상 회차: {}회", nextDrawNo);

        } catch (JsonProcessingException e) {
            log.error("[로또추천] JSON 직렬화 오류: {}", e.getMessage());
        }
    }

    // ==================== API 조회 메서드 ====================

    /**
     * 금주의 추천 번호 조회 (DB에서 빠르게)
     */
    @Transactional
    public LottoAnalysisDto getWeeklyRecommendation() {
        Optional<LottoWeeklyRecommendation> optional = weeklyRepository.findLatestRecommendation();

        if (optional.isEmpty()) {
            // DB에 없으면 즉시 생성 (첫 실행 시)
            log.info("[로또조회] 저장된 추천 없음, 새로 생성...");
            initializeDataIfEmpty();
            optional = weeklyRepository.findLatestRecommendation();
        }

        if (optional.isEmpty()) {
            log.error("[로또조회] 추천 번호 생성 실패");
            return null;
        }

        return convertToAnalysisDto(optional.get());
    }

    /**
     * 새 추천 번호 생성 (강제 갱신)
     */
    @Transactional
    public LottoAnalysisDto refreshWeeklyRecommendation() {
        // 오늘 날짜로 새로 생성
        LocalDate today = LocalDate.now();

        // 기존 오늘 데이터 삭제
        weeklyRepository.findByGeneratedDate(today).ifPresent(weeklyRepository::delete);

        // 새로 생성
        generateAndSaveWeeklyRecommendation();

        return getWeeklyRecommendation();
    }

    /**
     * 금주 추천 생성일 조회
     */
    @Transactional(readOnly = true)
    public LocalDate getWeeklyRecommendationDate() {
        return weeklyRepository.findLatestRecommendation()
                .map(LottoWeeklyRecommendation::getGeneratedDate)
                .orElse(null);
    }

    /**
     * 최신 회차 번호 조회
     */
    @Transactional(readOnly = true)
    public Integer getLatestDrawNo() {
        return drawRepository.findMaxDrawNo().orElse(null);
    }

    /**
     * 특정 회차 데이터 조회
     */
    @Transactional(readOnly = true)
    public LottoDrawDto getDrawData(int drawNo) {
        return drawRepository.findByDrawNo(drawNo)
                .map(this::toDrawDto)
                .orElse(null);
    }

    /**
     * 최근 N회차 데이터 조회
     */
    @Transactional(readOnly = true)
    public List<LottoDrawDto> getRecentDraws(int count) {
        return drawRepository.findRecentDraws(count).stream()
                .map(this::toDrawDto)
                .collect(Collectors.toList());
    }

    /**
     * 실시간 분석 (새 추천 번호 생성)
     */
    @Transactional
    public LottoAnalysisDto analyzeAndRecommend() {
        List<LottoDraw> draws = drawRepository.findRecentDraws(100);
        if (draws.isEmpty()) {
            log.info("[로또분석] DB에 데이터가 없어 자동 수집 시작...");
            collectLatestDraws();
            draws = drawRepository.findRecentDraws(100);
            if (draws.isEmpty()) {
                log.error("[로또분석] 데이터 수집 후에도 분석할 데이터가 없습니다");
                return null;
            }
        }

        List<LottoDrawDto> drawDtos = draws.stream()
                .map(this::toDrawDto)
                .collect(Collectors.toList());

        return performAnalysis(drawDtos);
    }

    // ==================== 내부 분석 로직 ====================

    private LottoAnalysisDto performAnalysis(List<LottoDrawDto> draws) {
        if (draws.isEmpty()) {
            return null;
        }

        int latestDrawNo = draws.get(0).getDrawNo();

        // 번호별 통계 분석
        Map<Integer, NumberStatDto> numberStats = analyzeNumberStats(draws);

        // Hot/Cold 번호 분류
        List<NumberStatDto> hotNumbers = extractHotNumbers(numberStats, 10);
        List<NumberStatDto> coldNumbers = extractColdNumbers(numberStats, 10);

        // 통계 요약 생성
        StatisticsSummaryDto statistics = generateStatisticsSummary(draws);

        // 추천 번호 생성 (5게임)
        List<LottoRecommendationDto> recommendations = generateRecommendations(numberStats, hotNumbers, coldNumbers);

        return LottoAnalysisDto.builder()
                .latestDrawNo(latestDrawNo)
                .analyzedDrawCount(draws.size())
                .analysisTime(LocalDateTime.now())
                .recommendations(recommendations)
                .numberStats(numberStats)
                .hotNumbers(hotNumbers)
                .coldNumbers(coldNumbers)
                .statistics(statistics)
                .build();
    }

    /**
     * 번호별 통계 분석
     */
    private Map<Integer, NumberStatDto> analyzeNumberStats(List<LottoDrawDto> draws) {
        Map<Integer, NumberStatDto> stats = new LinkedHashMap<>();

        for (int num = 1; num <= MAX_NUMBER; num++) {
            stats.put(num, NumberStatDto.builder()
                    .number(num)
                    .frequency10(0)
                    .frequency50(0)
                    .frequency100(0)
                    .lastAppearance(999)
                    .weight(0.0)
                    .category("WARM")
                    .build());
        }

        for (int i = 0; i < draws.size(); i++) {
            LottoDrawDto draw = draws.get(i);
            for (Integer num : draw.getNumbers()) {
                NumberStatDto stat = stats.get(num);

                if (stat.getLastAppearance() == 999) {
                    stat.setLastAppearance(i);
                }

                if (i < 10) stat.setFrequency10(stat.getFrequency10() + 1);
                if (i < 50) stat.setFrequency50(stat.getFrequency50() + 1);
                if (i < 100) stat.setFrequency100(stat.getFrequency100() + 1);
            }
        }

        for (NumberStatDto stat : stats.values()) {
            double weight = (stat.getFrequency10() * 3.0) +
                           (stat.getFrequency50() * 2.0) +
                           (stat.getFrequency100() * 1.0) -
                           (stat.getLastAppearance() * 0.5);
            stat.setWeight(Math.max(0, weight));

            if (stat.getFrequency10() >= 2 || stat.getLastAppearance() <= 3) {
                stat.setCategory("HOT");
            } else if (stat.getLastAppearance() >= 15 || stat.getFrequency50() <= 4) {
                stat.setCategory("COLD");
            } else {
                stat.setCategory("WARM");
            }
        }

        return stats;
    }

    private List<NumberStatDto> extractHotNumbers(Map<Integer, NumberStatDto> stats, int count) {
        return stats.values().stream()
                .filter(s -> "HOT".equals(s.getCategory()))
                .sorted((a, b) -> Double.compare(b.getWeight(), a.getWeight()))
                .limit(count)
                .collect(Collectors.toList());
    }

    private List<NumberStatDto> extractColdNumbers(Map<Integer, NumberStatDto> stats, int count) {
        return stats.values().stream()
                .filter(s -> "COLD".equals(s.getCategory()))
                .sorted((a, b) -> Integer.compare(b.getLastAppearance(), a.getLastAppearance()))
                .limit(count)
                .collect(Collectors.toList());
    }

    private StatisticsSummaryDto generateStatisticsSummary(List<LottoDrawDto> draws) {
        List<Integer> sums = draws.stream()
                .map(d -> d.getNumbers().stream().mapToInt(Integer::intValue).sum())
                .collect(Collectors.toList());

        double avgSum = sums.stream().mapToInt(Integer::intValue).average().orElse(0);
        int minSum = sums.stream().mapToInt(Integer::intValue).min().orElse(0);
        int maxSum = sums.stream().mapToInt(Integer::intValue).max().orElse(0);

        Map<String, Integer> oddEvenDist = new HashMap<>();
        for (LottoDrawDto draw : draws) {
            int oddCount = (int) draw.getNumbers().stream().filter(n -> n % 2 == 1).count();
            int evenCount = PICK_COUNT - oddCount;
            String key = oddCount + ":" + evenCount;
            oddEvenDist.merge(key, 1, Integer::sum);
        }

        Map<String, Integer> highLowDist = new HashMap<>();
        for (LottoDrawDto draw : draws) {
            int lowCount = (int) draw.getNumbers().stream().filter(n -> n <= LOW_HIGH_BOUNDARY).count();
            int highCount = PICK_COUNT - lowCount;
            String key = lowCount + ":" + highCount;
            highLowDist.merge(key, 1, Integer::sum);
        }

        double avgConsec = draws.stream()
                .mapToInt(this::countConsecutive)
                .average().orElse(0);

        return StatisticsSummaryDto.builder()
                .avgSum(Math.round(avgSum * 10) / 10.0)
                .minSum(minSum)
                .maxSum(maxSum)
                .oddEvenDistribution(oddEvenDist)
                .highLowDistribution(highLowDist)
                .avgConsecutive(Math.round(avgConsec * 10) / 10.0)
                .build();
    }

    private int countConsecutive(LottoDrawDto draw) {
        List<Integer> numbers = new ArrayList<>(draw.getNumbers());
        Collections.sort(numbers);

        int maxConsec = 1;
        int currentConsec = 1;

        for (int i = 1; i < numbers.size(); i++) {
            if (numbers.get(i) - numbers.get(i - 1) == 1) {
                currentConsec++;
                maxConsec = Math.max(maxConsec, currentConsec);
            } else {
                currentConsec = 1;
            }
        }

        return maxConsec;
    }

    private List<LottoRecommendationDto> generateRecommendations(
            Map<Integer, NumberStatDto> numberStats,
            List<NumberStatDto> hotNumbers,
            List<NumberStatDto> coldNumbers) {

        List<LottoRecommendationDto> recommendations = new ArrayList<>();
        Set<String> generatedCombinations = new HashSet<>();

        String[] strategies = {
            "Hot 70% + Cold 30% 하이브리드",
            "최근 10회차 빈도 기반",
            "가중치 상위 번호 우선",
            "균형 잡힌 분포",
            "Cold 번호 역발상 전략"
        };

        int gameNo = 1;
        int attempts = 0;
        int maxAttempts = 1000;

        while (recommendations.size() < GAME_COUNT && attempts < maxAttempts) {
            attempts++;

            List<Integer> numbers;
            String strategy = strategies[Math.min(gameNo - 1, strategies.length - 1)];

            switch (gameNo) {
                case 1:
                    numbers = generateHotColdHybrid(hotNumbers, coldNumbers, numberStats);
                    break;
                case 2:
                    numbers = generateFrequencyBased(numberStats);
                    break;
                case 3:
                    numbers = generateWeightBased(numberStats);
                    break;
                case 4:
                    numbers = generateBalanced(numberStats);
                    break;
                case 5:
                    numbers = generateColdFocused(coldNumbers, numberStats);
                    break;
                default:
                    numbers = generateWeightBased(numberStats);
            }

            if (!passesAllFilters(numbers)) {
                continue;
            }

            Collections.sort(numbers);
            String key = numbers.toString();
            if (generatedCombinations.contains(key)) {
                continue;
            }
            generatedCombinations.add(key);

            int sum = numbers.stream().mapToInt(Integer::intValue).sum();
            int oddCount = (int) numbers.stream().filter(n -> n % 2 == 1).count();
            int lowCount = (int) numbers.stream().filter(n -> n <= LOW_HIGH_BOUNDARY).count();
            int consecCount = countConsecutiveInList(numbers);
            double confidence = calculateConfidence(numbers, numberStats);

            LottoRecommendationDto rec = LottoRecommendationDto.builder()
                    .gameNo(gameNo)
                    .numbers(numbers)
                    .sum(sum)
                    .oddEvenRatio(oddCount + ":" + (PICK_COUNT - oddCount))
                    .highLowRatio(lowCount + ":" + (PICK_COUNT - lowCount))
                    .consecutiveCount(consecCount)
                    .strategy(strategy)
                    .confidence(Math.round(confidence * 10) / 10.0)
                    .build();

            recommendations.add(rec);
            gameNo++;
        }

        return recommendations;
    }

    private List<Integer> generateHotColdHybrid(
            List<NumberStatDto> hotNumbers,
            List<NumberStatDto> coldNumbers,
            Map<Integer, NumberStatDto> numberStats) {

        Set<Integer> selected = new HashSet<>();

        int hotCount = random.nextInt(2) + 4;
        List<Integer> hotPool = hotNumbers.stream()
                .map(NumberStatDto::getNumber)
                .collect(Collectors.toList());

        while (selected.size() < hotCount && !hotPool.isEmpty()) {
            int idx = random.nextInt(hotPool.size());
            selected.add(hotPool.remove(idx));
        }

        List<Integer> coldPool = coldNumbers.stream()
                .map(NumberStatDto::getNumber)
                .collect(Collectors.toList());

        while (selected.size() < PICK_COUNT && !coldPool.isEmpty()) {
            int idx = random.nextInt(coldPool.size());
            selected.add(coldPool.remove(idx));
        }

        if (selected.size() < PICK_COUNT) {
            List<Integer> warmPool = numberStats.values().stream()
                    .filter(s -> "WARM".equals(s.getCategory()))
                    .map(NumberStatDto::getNumber)
                    .filter(n -> !selected.contains(n))
                    .collect(Collectors.toList());

            while (selected.size() < PICK_COUNT && !warmPool.isEmpty()) {
                int idx = random.nextInt(warmPool.size());
                selected.add(warmPool.remove(idx));
            }
        }

        return new ArrayList<>(selected);
    }

    private List<Integer> generateFrequencyBased(Map<Integer, NumberStatDto> stats) {
        List<NumberStatDto> sorted = new ArrayList<>(stats.values());
        sorted.sort((a, b) -> Integer.compare(b.getFrequency10(), a.getFrequency10()));

        List<Integer> pool = sorted.stream()
                .limit(15)
                .map(NumberStatDto::getNumber)
                .collect(Collectors.toList());

        Set<Integer> selected = new HashSet<>();
        while (selected.size() < PICK_COUNT && !pool.isEmpty()) {
            int idx = random.nextInt(pool.size());
            selected.add(pool.remove(idx));
        }

        return new ArrayList<>(selected);
    }

    private List<Integer> generateWeightBased(Map<Integer, NumberStatDto> stats) {
        List<NumberStatDto> sorted = new ArrayList<>(stats.values());
        sorted.sort((a, b) -> Double.compare(b.getWeight(), a.getWeight()));

        List<Integer> pool = sorted.stream()
                .limit(20)
                .map(NumberStatDto::getNumber)
                .collect(Collectors.toList());

        Set<Integer> selected = new HashSet<>();
        while (selected.size() < PICK_COUNT && !pool.isEmpty()) {
            int idx = random.nextInt(pool.size());
            selected.add(pool.remove(idx));
        }

        return new ArrayList<>(selected);
    }

    private List<Integer> generateBalanced(Map<Integer, NumberStatDto> stats) {
        Set<Integer> selected = new HashSet<>();

        int[][] ranges = {{1, 10}, {11, 20}, {21, 30}, {31, 40}, {41, 45}};

        for (int[] range : ranges) {
            List<Integer> pool = stats.values().stream()
                    .filter(s -> s.getNumber() >= range[0] && s.getNumber() <= range[1])
                    .sorted((a, b) -> Double.compare(b.getWeight(), a.getWeight()))
                    .limit(5)
                    .map(NumberStatDto::getNumber)
                    .collect(Collectors.toList());

            if (!pool.isEmpty() && selected.size() < PICK_COUNT) {
                selected.add(pool.get(random.nextInt(Math.min(pool.size(), 3))));
            }
        }

        List<Integer> allNumbers = new ArrayList<>();
        for (int i = 1; i <= MAX_NUMBER; i++) {
            if (!selected.contains(i)) allNumbers.add(i);
        }

        while (selected.size() < PICK_COUNT && !allNumbers.isEmpty()) {
            int idx = random.nextInt(allNumbers.size());
            selected.add(allNumbers.remove(idx));
        }

        return new ArrayList<>(selected);
    }

    private List<Integer> generateColdFocused(List<NumberStatDto> coldNumbers, Map<Integer, NumberStatDto> stats) {
        Set<Integer> selected = new HashSet<>();

        int coldCount = random.nextInt(2) + 3;
        List<Integer> coldPool = coldNumbers.stream()
                .map(NumberStatDto::getNumber)
                .collect(Collectors.toList());

        while (selected.size() < coldCount && !coldPool.isEmpty()) {
            int idx = random.nextInt(coldPool.size());
            selected.add(coldPool.remove(idx));
        }

        List<Integer> warmPool = stats.values().stream()
                .filter(s -> "WARM".equals(s.getCategory()))
                .map(NumberStatDto::getNumber)
                .filter(n -> !selected.contains(n))
                .collect(Collectors.toList());

        while (selected.size() < PICK_COUNT && !warmPool.isEmpty()) {
            int idx = random.nextInt(warmPool.size());
            selected.add(warmPool.remove(idx));
        }

        return new ArrayList<>(selected);
    }

    private boolean passesAllFilters(List<Integer> numbers) {
        if (numbers == null || numbers.size() != PICK_COUNT) {
            return false;
        }

        if (new HashSet<>(numbers).size() != PICK_COUNT) {
            return false;
        }

        int sum = numbers.stream().mapToInt(Integer::intValue).sum();
        if (sum < MIN_SUM || sum > MAX_SUM) {
            return false;
        }

        int oddCount = (int) numbers.stream().filter(n -> n % 2 == 1).count();
        if (oddCount == 0 || oddCount == 6 || oddCount == 5 || oddCount == 1) {
            return false;
        }

        int lowCount = (int) numbers.stream().filter(n -> n <= LOW_HIGH_BOUNDARY).count();
        if (lowCount == 0 || lowCount == 6 || lowCount == 5 || lowCount == 1) {
            return false;
        }

        Collections.sort(numbers);
        int maxConsec = countConsecutiveInList(numbers);
        if (maxConsec > MAX_CONSECUTIVE) {
            return false;
        }

        return true;
    }

    private int countConsecutiveInList(List<Integer> numbers) {
        List<Integer> sorted = new ArrayList<>(numbers);
        Collections.sort(sorted);

        int maxConsec = 1;
        int currentConsec = 1;

        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i) - sorted.get(i - 1) == 1) {
                currentConsec++;
                maxConsec = Math.max(maxConsec, currentConsec);
            } else {
                currentConsec = 1;
            }
        }

        return maxConsec;
    }

    private double calculateConfidence(List<Integer> numbers, Map<Integer, NumberStatDto> stats) {
        double score = 50.0;

        double totalWeight = numbers.stream()
                .mapToDouble(n -> stats.get(n).getWeight())
                .sum();
        score += Math.min(totalWeight / 2, 20);

        int sum = numbers.stream().mapToInt(Integer::intValue).sum();
        if (sum >= 140 && sum <= 160) {
            score += 10;
        }

        int oddCount = (int) numbers.stream().filter(n -> n % 2 == 1).count();
        if (oddCount == 3) {
            score += 5;
        }

        int lowCount = (int) numbers.stream().filter(n -> n <= LOW_HIGH_BOUNDARY).count();
        if (lowCount >= 2 && lowCount <= 4) {
            score += 5;
        }

        long hotCount = numbers.stream()
                .filter(n -> "HOT".equals(stats.get(n).getCategory()))
                .count();
        score += hotCount * 2;

        return Math.min(score, 95);
    }

    // ==================== 변환 메서드 ====================

    private LottoDrawDto toDrawDto(LottoDraw entity) {
        List<Integer> numbers = Arrays.asList(
                entity.getNum1(), entity.getNum2(), entity.getNum3(),
                entity.getNum4(), entity.getNum5(), entity.getNum6()
        );
        Collections.sort(numbers);

        return LottoDrawDto.builder()
                .drawNo(entity.getDrawNo())
                .drawDate(entity.getDrawDate())
                .numbers(numbers)
                .bonusNo(entity.getBonusNo())
                .totalPrize(entity.getTotalPrize())
                .firstWinnerCount(entity.getFirstWinnerCount())
                .firstWinAmount(entity.getFirstWinAmount())
                .build();
    }

    private LottoAnalysisDto convertToAnalysisDto(LottoWeeklyRecommendation entity) {
        try {
            List<LottoRecommendationDto> recommendations = objectMapper.readValue(
                    entity.getRecommendations(),
                    new TypeReference<List<LottoRecommendationDto>>() {}
            );

            StatisticsSummaryDto statistics = objectMapper.readValue(
                    entity.getStatisticsSummary(),
                    StatisticsSummaryDto.class
            );

            List<NumberStatDto> hotNumbers = objectMapper.readValue(
                    entity.getHotNumbers(),
                    new TypeReference<List<NumberStatDto>>() {}
            );

            List<NumberStatDto> coldNumbers = objectMapper.readValue(
                    entity.getColdNumbers(),
                    new TypeReference<List<NumberStatDto>>() {}
            );

            return LottoAnalysisDto.builder()
                    .latestDrawNo(entity.getLatestAnalyzedDrawNo())
                    .analyzedDrawCount(entity.getAnalyzedDrawCount())
                    .analysisTime(entity.getCreatedAt())
                    .recommendations(recommendations)
                    .hotNumbers(hotNumbers)
                    .coldNumbers(coldNumbers)
                    .statistics(statistics)
                    .build();

        } catch (JsonProcessingException e) {
            log.error("[로또변환] JSON 파싱 오류: {}", e.getMessage());
            return null;
        }
    }
}
