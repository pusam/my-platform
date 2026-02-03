package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.LottoAnalysisDto;
import com.myplatform.backend.dto.LottoAnalysisDto.NumberStatDto;
import com.myplatform.backend.dto.LottoAnalysisDto.StatisticsSummaryDto;
import com.myplatform.backend.dto.LottoDrawDto;
import com.myplatform.backend.dto.LottoRecommendationDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 통계 기반 로또 번호 추출기 (Lotto Quant Analyzer)
 *
 * Hot/Cold 번호 분석 + 하이브리드 필터링 시스템을 통한 번호 추천
 */
@Service
@Slf4j
public class LottoAnalyzerService {

    private static final String LOTTO_API_URL = "https://www.dhlottery.co.kr/common.do?method=getLottoNumber&drwNo=";
    private static final int MAX_NUMBER = 45;
    private static final int PICK_COUNT = 6;
    private static final int GAME_COUNT = 5;

    // 필터링 조건
    private static final int MIN_SUM = 120;
    private static final int MAX_SUM = 180;
    private static final int LOW_HIGH_BOUNDARY = 22;  // 1~22: 저, 23~45: 고
    private static final int MAX_CONSECUTIVE = 2;     // 연속 번호 최대 2개까지 허용

    // Hot/Cold 비율
    private static final double HOT_RATIO = 0.7;
    private static final double COLD_RATIO = 0.3;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom random;

    // 캐시
    private final Map<Integer, LottoDrawDto> drawCache = new ConcurrentHashMap<>();
    private volatile Integer latestDrawNo = null;

    // 금주의 추천 번호
    private volatile LottoAnalysisDto weeklyRecommendation = null;
    private volatile LocalDate weeklyRecommendationDate = null;

    public LottoAnalyzerService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.random = new SecureRandom();
    }

    /**
     * 로또 번호 분석 및 추천
     */
    public LottoAnalysisDto analyzeAndRecommend() {
        log.info("[로또분석] 분석 시작...");

        // 1. 최신 회차 확인 및 데이터 수집
        updateLatestDrawNo();
        List<LottoDrawDto> recentDraws = collectRecentDraws(100);

        if (recentDraws.isEmpty()) {
            log.error("[로또분석] 당첨 데이터 수집 실패");
            return null;
        }

        log.info("[로또분석] {}개 회차 데이터 수집 완료", recentDraws.size());

        // 2. 번호별 통계 분석
        Map<Integer, NumberStatDto> numberStats = analyzeNumberStats(recentDraws);

        // 3. Hot/Cold 번호 분류
        List<NumberStatDto> hotNumbers = extractHotNumbers(numberStats, 10);
        List<NumberStatDto> coldNumbers = extractColdNumbers(numberStats, 10);

        // 4. 통계 요약 생성
        StatisticsSummaryDto statistics = generateStatisticsSummary(recentDraws);

        // 5. 추천 번호 생성 (5게임)
        List<LottoRecommendationDto> recommendations = generateRecommendations(numberStats, hotNumbers, coldNumbers);

        log.info("[로또분석] 분석 완료 - {}게임 추천", recommendations.size());

        return LottoAnalysisDto.builder()
                .latestDrawNo(latestDrawNo)
                .analyzedDrawCount(recentDraws.size())
                .analysisTime(LocalDateTime.now())
                .recommendations(recommendations)
                .numberStats(numberStats)
                .hotNumbers(hotNumbers)
                .coldNumbers(coldNumbers)
                .statistics(statistics)
                .build();
    }

    /**
     * 최신 회차 번호 업데이트
     */
    private void updateLatestDrawNo() {
        // 2002년 12월 7일 1회차 기준으로 현재 회차 추정
        LocalDate firstDraw = LocalDate.of(2002, 12, 7);
        LocalDate today = LocalDate.now();
        long weeks = java.time.temporal.ChronoUnit.WEEKS.between(firstDraw, today);
        int estimatedDrawNo = (int) weeks + 1;

        // 추정 회차부터 실제 데이터가 있는지 확인
        for (int i = estimatedDrawNo; i > estimatedDrawNo - 5; i--) {
            LottoDrawDto draw = fetchDrawData(i);
            if (draw != null) {
                latestDrawNo = i;
                log.info("[로또분석] 최신 회차: {}회", latestDrawNo);
                return;
            }
        }

        // 못 찾으면 이전 값 유지 또는 기본값
        if (latestDrawNo == null) {
            latestDrawNo = estimatedDrawNo - 1;
        }
    }

    /**
     * 최근 N회차 데이터 수집
     */
    private List<LottoDrawDto> collectRecentDraws(int count) {
        List<LottoDrawDto> draws = new ArrayList<>();

        if (latestDrawNo == null) {
            updateLatestDrawNo();
        }

        for (int i = latestDrawNo; i > latestDrawNo - count && i > 0; i--) {
            LottoDrawDto draw = fetchDrawData(i);
            if (draw != null) {
                draws.add(draw);
            }
        }

        return draws;
    }

    /**
     * 동행복권 API에서 회차 데이터 조회
     */
    private LottoDrawDto fetchDrawData(int drawNo) {
        // 캐시 확인
        if (drawCache.containsKey(drawNo)) {
            return drawCache.get(drawNo);
        }

        try {
            String url = LOTTO_API_URL + drawNo;
            String response = restTemplate.getForObject(url, String.class);

            if (response == null || response.isEmpty()) {
                return null;
            }

            JsonNode json = objectMapper.readTree(response);

            if (!"success".equals(json.path("returnValue").asText())) {
                return null;
            }

            List<Integer> numbers = new ArrayList<>();
            numbers.add(json.path("drwtNo1").asInt());
            numbers.add(json.path("drwtNo2").asInt());
            numbers.add(json.path("drwtNo3").asInt());
            numbers.add(json.path("drwtNo4").asInt());
            numbers.add(json.path("drwtNo5").asInt());
            numbers.add(json.path("drwtNo6").asInt());
            Collections.sort(numbers);

            String dateStr = json.path("drwNoDate").asText();
            LocalDate drawDate = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);

            LottoDrawDto draw = LottoDrawDto.builder()
                    .drawNo(drawNo)
                    .drawDate(drawDate)
                    .numbers(numbers)
                    .bonusNo(json.path("bnusNo").asInt())
                    .totalPrize(json.path("totSellamnt").asLong())
                    .firstWinnerCount(json.path("firstPrzwnerCo").asLong())
                    .firstWinAmount(json.path("firstWinamnt").asLong())
                    .build();

            // 캐시 저장
            drawCache.put(drawNo, draw);

            return draw;

        } catch (Exception e) {
            log.debug("[로또분석] {}회차 데이터 조회 실패: {}", drawNo, e.getMessage());
            return null;
        }
    }

    /**
     * 번호별 통계 분석
     */
    private Map<Integer, NumberStatDto> analyzeNumberStats(List<LottoDrawDto> draws) {
        Map<Integer, NumberStatDto> stats = new LinkedHashMap<>();

        // 각 번호별 초기화
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

        // 빈도 계산
        for (int i = 0; i < draws.size(); i++) {
            LottoDrawDto draw = draws.get(i);
            for (Integer num : draw.getNumbers()) {
                NumberStatDto stat = stats.get(num);

                // 마지막 출현
                if (stat.getLastAppearance() == 999) {
                    stat.setLastAppearance(i);
                }

                // 구간별 빈도
                if (i < 10) stat.setFrequency10(stat.getFrequency10() + 1);
                if (i < 50) stat.setFrequency50(stat.getFrequency50() + 1);
                if (i < 100) stat.setFrequency100(stat.getFrequency100() + 1);
            }
        }

        // 가중치 계산 및 카테고리 분류
        for (NumberStatDto stat : stats.values()) {
            // 가중치 = (최근 10회 빈도 * 3) + (최근 50회 빈도 * 2) + (최근 100회 빈도) - (안 나온 회차 * 0.5)
            double weight = (stat.getFrequency10() * 3.0) +
                           (stat.getFrequency50() * 2.0) +
                           (stat.getFrequency100() * 1.0) -
                           (stat.getLastAppearance() * 0.5);
            stat.setWeight(Math.max(0, weight));

            // 카테고리 분류
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

    /**
     * Hot Numbers 추출 (가중치 상위)
     */
    private List<NumberStatDto> extractHotNumbers(Map<Integer, NumberStatDto> stats, int count) {
        return stats.values().stream()
                .filter(s -> "HOT".equals(s.getCategory()))
                .sorted((a, b) -> Double.compare(b.getWeight(), a.getWeight()))
                .limit(count)
                .collect(Collectors.toList());
    }

    /**
     * Cold Numbers 추출 (오랫동안 안 나온 번호)
     */
    private List<NumberStatDto> extractColdNumbers(Map<Integer, NumberStatDto> stats, int count) {
        return stats.values().stream()
                .filter(s -> "COLD".equals(s.getCategory()))
                .sorted((a, b) -> Integer.compare(b.getLastAppearance(), a.getLastAppearance()))
                .limit(count)
                .collect(Collectors.toList());
    }

    /**
     * 통계 요약 생성
     */
    private StatisticsSummaryDto generateStatisticsSummary(List<LottoDrawDto> draws) {
        // 합계 통계
        List<Integer> sums = draws.stream()
                .map(d -> d.getNumbers().stream().mapToInt(Integer::intValue).sum())
                .collect(Collectors.toList());

        double avgSum = sums.stream().mapToInt(Integer::intValue).average().orElse(0);
        int minSum = sums.stream().mapToInt(Integer::intValue).min().orElse(0);
        int maxSum = sums.stream().mapToInt(Integer::intValue).max().orElse(0);

        // 홀짝 분포
        Map<String, Integer> oddEvenDist = new HashMap<>();
        for (LottoDrawDto draw : draws) {
            int oddCount = (int) draw.getNumbers().stream().filter(n -> n % 2 == 1).count();
            int evenCount = PICK_COUNT - oddCount;
            String key = oddCount + ":" + evenCount;
            oddEvenDist.merge(key, 1, Integer::sum);
        }

        // 고저 분포
        Map<String, Integer> highLowDist = new HashMap<>();
        for (LottoDrawDto draw : draws) {
            int lowCount = (int) draw.getNumbers().stream().filter(n -> n <= LOW_HIGH_BOUNDARY).count();
            int highCount = PICK_COUNT - lowCount;
            String key = lowCount + ":" + highCount;
            highLowDist.merge(key, 1, Integer::sum);
        }

        // 평균 연속 번호 개수
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

    /**
     * 연속 번호 개수 계산
     */
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

    /**
     * 추천 번호 5게임 생성
     */
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

            // 전략별 번호 생성
            switch (gameNo) {
                case 1:
                    numbers = generateHotColdHybrid(hotNumbers, coldNumbers, numberStats);
                    break;
                case 2:
                    numbers = generateFrequencyBased(numberStats, "frequency10");
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

            // 필터링 통과 확인
            if (!passesAllFilters(numbers)) {
                continue;
            }

            // 중복 조합 체크
            Collections.sort(numbers);
            String key = numbers.toString();
            if (generatedCombinations.contains(key)) {
                continue;
            }
            generatedCombinations.add(key);

            // 추천 생성
            int sum = numbers.stream().mapToInt(Integer::intValue).sum();
            int oddCount = (int) numbers.stream().filter(n -> n % 2 == 1).count();
            int lowCount = (int) numbers.stream().filter(n -> n <= LOW_HIGH_BOUNDARY).count();
            int consecCount = countConsecutiveInList(numbers);

            // 신뢰도 점수 계산
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

            log.info("[로또분석] 게임 {} 생성: {} (합:{}, 홀짝:{}, 신뢰도:{}%)",
                    rec.getGameNo(), rec.getNumbers(), rec.getSum(),
                    rec.getOddEvenRatio(), rec.getConfidence());
        }

        return recommendations;
    }

    /**
     * Hot/Cold 하이브리드 전략 (7:3)
     */
    private List<Integer> generateHotColdHybrid(
            List<NumberStatDto> hotNumbers,
            List<NumberStatDto> coldNumbers,
            Map<Integer, NumberStatDto> numberStats) {

        Set<Integer> selected = new HashSet<>();

        // Hot 번호에서 4~5개
        int hotCount = random.nextInt(2) + 4; // 4 or 5
        List<Integer> hotPool = hotNumbers.stream()
                .map(NumberStatDto::getNumber)
                .collect(Collectors.toList());

        while (selected.size() < hotCount && !hotPool.isEmpty()) {
            int idx = random.nextInt(hotPool.size());
            selected.add(hotPool.remove(idx));
        }

        // Cold 번호에서 1~2개
        List<Integer> coldPool = coldNumbers.stream()
                .map(NumberStatDto::getNumber)
                .collect(Collectors.toList());

        while (selected.size() < PICK_COUNT && !coldPool.isEmpty()) {
            int idx = random.nextInt(coldPool.size());
            selected.add(coldPool.remove(idx));
        }

        // 부족하면 WARM에서 채우기
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

    /**
     * 빈도 기반 전략
     */
    private List<Integer> generateFrequencyBased(Map<Integer, NumberStatDto> stats, String frequencyType) {
        List<NumberStatDto> sorted = new ArrayList<>(stats.values());
        sorted.sort((a, b) -> {
            int freqA = "frequency10".equals(frequencyType) ? a.getFrequency10() :
                       "frequency50".equals(frequencyType) ? a.getFrequency50() : a.getFrequency100();
            int freqB = "frequency10".equals(frequencyType) ? b.getFrequency10() :
                       "frequency50".equals(frequencyType) ? b.getFrequency50() : b.getFrequency100();
            return Integer.compare(freqB, freqA);
        });

        // 상위 15개 중에서 랜덤 선택
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

    /**
     * 가중치 기반 전략
     */
    private List<Integer> generateWeightBased(Map<Integer, NumberStatDto> stats) {
        List<NumberStatDto> sorted = new ArrayList<>(stats.values());
        sorted.sort((a, b) -> Double.compare(b.getWeight(), a.getWeight()));

        // 가중치 상위 20개 중에서 선택
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

    /**
     * 균형 분포 전략
     */
    private List<Integer> generateBalanced(Map<Integer, NumberStatDto> stats) {
        Set<Integer> selected = new HashSet<>();

        // 번호대별로 1~2개씩 선택 (1~10, 11~20, 21~30, 31~40, 41~45)
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

        // 나머지 채우기
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

    /**
     * Cold 중심 역발상 전략
     */
    private List<Integer> generateColdFocused(List<NumberStatDto> coldNumbers, Map<Integer, NumberStatDto> stats) {
        Set<Integer> selected = new HashSet<>();

        // Cold 번호에서 3~4개
        int coldCount = random.nextInt(2) + 3;
        List<Integer> coldPool = coldNumbers.stream()
                .map(NumberStatDto::getNumber)
                .collect(Collectors.toList());

        while (selected.size() < coldCount && !coldPool.isEmpty()) {
            int idx = random.nextInt(coldPool.size());
            selected.add(coldPool.remove(idx));
        }

        // 나머지는 WARM에서
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

    /**
     * 모든 필터 통과 확인
     */
    private boolean passesAllFilters(List<Integer> numbers) {
        if (numbers == null || numbers.size() != PICK_COUNT) {
            return false;
        }

        // 중복 체크
        if (new HashSet<>(numbers).size() != PICK_COUNT) {
            return false;
        }

        // 1. 합계 필터 (120 ~ 180)
        int sum = numbers.stream().mapToInt(Integer::intValue).sum();
        if (sum < MIN_SUM || sum > MAX_SUM) {
            return false;
        }

        // 2. 홀짝 비율 필터 (6:0, 0:6, 5:1, 1:5 제외)
        int oddCount = (int) numbers.stream().filter(n -> n % 2 == 1).count();
        if (oddCount == 0 || oddCount == 6 || oddCount == 5 || oddCount == 1) {
            return false;
        }

        // 3. 고저 비율 필터 (균형)
        int lowCount = (int) numbers.stream().filter(n -> n <= LOW_HIGH_BOUNDARY).count();
        if (lowCount == 0 || lowCount == 6 || lowCount == 5 || lowCount == 1) {
            return false;
        }

        // 4. 연속 번호 제한 (3개 이상 연속 제외)
        Collections.sort(numbers);
        int maxConsec = countConsecutiveInList(numbers);
        if (maxConsec > MAX_CONSECUTIVE) {
            return false;
        }

        return true;
    }

    /**
     * 리스트에서 연속 번호 개수 계산
     */
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

    /**
     * 신뢰도 점수 계산
     */
    private double calculateConfidence(List<Integer> numbers, Map<Integer, NumberStatDto> stats) {
        double score = 50.0; // 기본 점수

        // 가중치 합계 기반 점수
        double totalWeight = numbers.stream()
                .mapToDouble(n -> stats.get(n).getWeight())
                .sum();
        score += Math.min(totalWeight / 2, 20);

        // 합계가 최적 범위(140~160)에 있으면 가점
        int sum = numbers.stream().mapToInt(Integer::intValue).sum();
        if (sum >= 140 && sum <= 160) {
            score += 10;
        }

        // 홀짝 3:3이면 가점
        int oddCount = (int) numbers.stream().filter(n -> n % 2 == 1).count();
        if (oddCount == 3) {
            score += 5;
        }

        // 고저 균형이면 가점
        int lowCount = (int) numbers.stream().filter(n -> n <= LOW_HIGH_BOUNDARY).count();
        if (lowCount >= 2 && lowCount <= 4) {
            score += 5;
        }

        // Hot 번호 포함 비율
        long hotCount = numbers.stream()
                .filter(n -> "HOT".equals(stats.get(n).getCategory()))
                .count();
        score += hotCount * 2;

        return Math.min(score, 95); // 최대 95%
    }

    /**
     * 특정 회차 데이터 조회 (API)
     */
    public LottoDrawDto getDrawData(int drawNo) {
        return fetchDrawData(drawNo);
    }

    /**
     * 최근 N회차 데이터 조회 (API)
     */
    public List<LottoDrawDto> getRecentDraws(int count) {
        updateLatestDrawNo();
        return collectRecentDraws(count);
    }

    /**
     * 최신 회차 번호 조회
     */
    public Integer getLatestDrawNo() {
        if (latestDrawNo == null) {
            updateLatestDrawNo();
        }
        return latestDrawNo;
    }

    // ==================== 금주의 추천 번호 ====================

    /**
     * 매주 월요일 06:00에 금주의 추천 번호 생성
     */
    @Scheduled(cron = "0 0 6 * * MON", zone = "Asia/Seoul")
    public void generateWeeklyRecommendation() {
        log.info("[로또분석] 금주의 추천 번호 생성 시작...");

        try {
            LottoAnalysisDto analysis = analyzeAndRecommend();
            if (analysis != null) {
                weeklyRecommendation = analysis;
                weeklyRecommendationDate = LocalDate.now();
                log.info("[로또분석] 금주의 추천 번호 생성 완료 - {} 기준", weeklyRecommendationDate);
            }
        } catch (Exception e) {
            log.error("[로또분석] 금주의 추천 번호 생성 실패: {}", e.getMessage());
        }
    }

    /**
     * 금주의 추천 번호 조회
     * - 저장된 금주 추천이 없거나 1주일이 지났으면 새로 생성
     */
    public LottoAnalysisDto getWeeklyRecommendation() {
        // 금주 추천이 없거나 7일이 지났으면 새로 생성
        if (weeklyRecommendation == null || weeklyRecommendationDate == null ||
            weeklyRecommendationDate.plusDays(7).isBefore(LocalDate.now())) {
            generateWeeklyRecommendation();
        }
        return weeklyRecommendation;
    }

    /**
     * 금주 추천 번호 생성일 조회
     */
    public LocalDate getWeeklyRecommendationDate() {
        return weeklyRecommendationDate;
    }

    /**
     * 금주 추천 번호 강제 갱신
     */
    public LottoAnalysisDto refreshWeeklyRecommendation() {
        generateWeeklyRecommendation();
        return weeklyRecommendation;
    }
}
