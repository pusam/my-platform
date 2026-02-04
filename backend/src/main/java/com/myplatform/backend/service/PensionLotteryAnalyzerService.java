package com.myplatform.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.PensionLotteryAnalysisDto;
import com.myplatform.backend.dto.PensionLotteryAnalysisDto.DigitStatDto;
import com.myplatform.backend.dto.PensionLotteryAnalysisDto.GroupStatDto;
import com.myplatform.backend.dto.PensionLotteryAnalysisDto.StatisticsSummaryDto;
import com.myplatform.backend.dto.PensionLotteryDrawDto;
import com.myplatform.backend.dto.PensionLotteryRecommendationDto;
import com.myplatform.backend.entity.PensionLotteryDraw;
import com.myplatform.backend.entity.PensionLotteryWeeklyRecommendation;
import com.myplatform.backend.repository.PensionLotteryDrawRepository;
import com.myplatform.backend.repository.PensionLotteryWeeklyRecommendationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.*;
import java.util.stream.Collectors;

/**
 * 연금복권 720+ 통계 기반 번호 추출기
 * - DB 기반 데이터 저장 및 조회
 * - 금요일 06:00 배치로 데이터 수집 및 추천번호 생성 (목요일 추첨 후)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PensionLotteryAnalyzerService {

    private static final String PENSION_API_URL = "https://www.dhlottery.co.kr/common.do?method=get720Number&drwNo=";
    private static final int DIGIT_COUNT = 6;
    private static final int GAME_COUNT = 5;
    private static final int MAX_GROUP = 5;

    private final PensionLotteryDrawRepository drawRepository;
    private final PensionLotteryWeeklyRecommendationRepository weeklyRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();
    private final SecureRandom random = new SecureRandom();

    // ==================== 스케줄러 ====================

    /**
     * 매주 금요일 06:00에 연금복권 데이터 수집 및 추천번호 생성
     * (목요일 추첨 후 금요일에 결과 반영)
     */
    @Scheduled(cron = "0 0 6 * * FRI", zone = "Asia/Seoul")
    @Transactional
    public void weeklyBatchJob() {
        log.info("[연금복권배치] ===== 주간 배치 시작 =====");

        try {
            // 1. 최신 당첨 데이터 수집
            int collected = collectLatestDraws();
            log.info("[연금복권배치] 당첨 데이터 수집 완료: {}건", collected);

            // 2. 금주의 추천 번호 생성
            generateAndSaveWeeklyRecommendation();
            log.info("[연금복권배치] 금주의 추천 번호 생성 완료");

        } catch (Exception e) {
            log.error("[연금복권배치] 배치 실행 오류: {}", e.getMessage(), e);
        }

        log.info("[연금복권배치] ===== 주간 배치 완료 =====");
    }

    /**
     * 서버 시작 시 데이터가 없으면 초기 데이터 수집
     */
    @Transactional
    public void initializeDataIfEmpty() {
        if (drawRepository.count() == 0) {
            log.info("[연금복권초기화] DB에 데이터가 없어 초기 수집 시작...");
            collectLatestDraws();
            generateAndSaveWeeklyRecommendation();
            log.info("[연금복권초기화] 초기화 완료");
        }
    }

    // ==================== 데이터 수집 ====================

    /**
     * 최신 당첨 데이터 수집 (최대 100회차)
     */
    @Transactional
    public int collectLatestDraws() {
        int collected = 0;
        int latestInDb = drawRepository.findMaxDrawNo().orElse(0);
        int estimatedLatest = estimateLatestDrawNo();

        log.info("[연금복권수집] DB 최신: {}회, 추정 최신: {}회", latestInDb, estimatedLatest);

        // 새 회차부터 수집
        for (int drawNo = estimatedLatest; drawNo > Math.max(latestInDb, estimatedLatest - 100) && drawNo > 0; drawNo--) {
            if (!drawRepository.existsByDrawNo(drawNo)) {
                PensionLotteryDraw draw = fetchAndSaveDraw(drawNo);
                if (draw != null) {
                    collected++;
                }
            }
        }

        return collected;
    }

    /**
     * 외부 API에서 회차 데이터 조회 및 저장
     */
    private PensionLotteryDraw fetchAndSaveDraw(int drawNo) {
        try {
            String url = PENSION_API_URL + drawNo;

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.set("Accept", "application/json, text/plain, */*");
            headers.set("Referer", "https://www.dhlottery.co.kr/");
            headers.set("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> responseEntity = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String response = responseEntity.getBody();

            if (response == null || response.isEmpty()) {
                return null;
            }

            JsonNode json = objectMapper.readTree(response);
            if (!"success".equals(json.path("returnValue").asText())) {
                return null;
            }

            // 1등 번호 파싱
            int firstGroup = json.path("pensionGroup").asInt();
            StringBuilder firstNumber = new StringBuilder();
            for (int i = 1; i <= 6; i++) {
                firstNumber.append(json.path("pensionNum" + i).asInt());
            }

            // 보너스 번호 파싱
            int bonusGroup = json.path("bonusGroup").asInt();
            StringBuilder bonusNumber = new StringBuilder();
            for (int i = 1; i <= 6; i++) {
                bonusNumber.append(json.path("bonusNum" + i).asInt());
            }

            PensionLotteryDraw draw = PensionLotteryDraw.builder()
                    .drawNo(drawNo)
                    .firstGroup(firstGroup)
                    .firstNumber(firstNumber.toString())
                    .bonusGroup(bonusGroup)
                    .bonusNumber(bonusNumber.toString())
                    .build();

            PensionLotteryDraw saved = drawRepository.save(draw);
            log.debug("[연금복권수집] {}회차 저장 완료", drawNo);
            return saved;

        } catch (Exception e) {
            log.warn("[연금복권수집] {}회차 수집 실패: {}", drawNo, e.getMessage());
            return null;
        }
    }

    /**
     * 최신 회차 추정
     */
    private int estimateLatestDrawNo() {
        LocalDate firstDraw = LocalDate.of(2011, 9, 1);
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

        if (weeklyRepository.existsByGeneratedDate(today)) {
            log.info("[연금복권추천] 오늘({}) 이미 추천 번호가 생성됨", today);
            return;
        }

        List<PensionLotteryDraw> draws = drawRepository.findRecentDraws(100);
        if (draws.isEmpty()) {
            log.error("[연금복권추천] 분석할 데이터가 없습니다");
            return;
        }

        List<PensionLotteryDrawDto> drawDtos = draws.stream()
                .map(this::toDrawDto)
                .collect(Collectors.toList());

        PensionLotteryAnalysisDto analysis = performAnalysis(drawDtos);
        if (analysis == null) {
            return;
        }

        int nextDrawNo = draws.get(0).getDrawNo() + 1;

        try {
            PensionLotteryWeeklyRecommendation recommendation = PensionLotteryWeeklyRecommendation.builder()
                    .generatedDate(today)
                    .targetDrawNo(nextDrawNo)
                    .latestAnalyzedDrawNo(draws.get(0).getDrawNo())
                    .analyzedDrawCount(draws.size())
                    .recommendations(objectMapper.writeValueAsString(analysis.getRecommendations()))
                    .statisticsSummary(objectMapper.writeValueAsString(analysis.getStatistics()))
                    .hotDigits(objectMapper.writeValueAsString(analysis.getHotDigits()))
                    .coldDigits(objectMapper.writeValueAsString(analysis.getColdDigits()))
                    .groupStats(objectMapper.writeValueAsString(analysis.getGroupStats()))
                    .build();

            weeklyRepository.save(recommendation);
            log.info("[연금복권추천] 추천 번호 저장 완료 - 대상 회차: {}회", nextDrawNo);

        } catch (JsonProcessingException e) {
            log.error("[연금복권추천] JSON 직렬화 오류: {}", e.getMessage());
        }
    }

    // ==================== API 조회 메서드 ====================

    @Transactional(readOnly = true)
    public PensionLotteryAnalysisDto getWeeklyRecommendation() {
        Optional<PensionLotteryWeeklyRecommendation> optional = weeklyRepository.findLatestRecommendation();

        if (optional.isEmpty()) {
            log.info("[연금복권조회] 저장된 추천 없음, 새로 생성...");
            initializeDataIfEmpty();
            optional = weeklyRepository.findLatestRecommendation();
        }

        if (optional.isEmpty()) {
            log.error("[연금복권조회] 추천 번호 생성 실패");
            return null;
        }

        return convertToAnalysisDto(optional.get());
    }

    @Transactional
    public PensionLotteryAnalysisDto refreshWeeklyRecommendation() {
        LocalDate today = LocalDate.now();
        weeklyRepository.findByGeneratedDate(today).ifPresent(weeklyRepository::delete);
        generateAndSaveWeeklyRecommendation();
        return getWeeklyRecommendation();
    }

    @Transactional(readOnly = true)
    public LocalDate getWeeklyRecommendationDate() {
        return weeklyRepository.findLatestRecommendation()
                .map(PensionLotteryWeeklyRecommendation::getGeneratedDate)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Integer getLatestDrawNo() {
        return drawRepository.findMaxDrawNo().orElse(null);
    }

    @Transactional(readOnly = true)
    public PensionLotteryDrawDto getDrawData(int drawNo) {
        return drawRepository.findByDrawNo(drawNo)
                .map(this::toDrawDto)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<PensionLotteryDrawDto> getRecentDraws(int count) {
        return drawRepository.findRecentDraws(count).stream()
                .map(this::toDrawDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PensionLotteryAnalysisDto analyzeAndRecommend() {
        List<PensionLotteryDraw> draws = drawRepository.findRecentDraws(100);
        if (draws.isEmpty()) {
            log.error("[연금복권분석] 분석할 데이터가 없습니다");
            return null;
        }

        List<PensionLotteryDrawDto> drawDtos = draws.stream()
                .map(this::toDrawDto)
                .collect(Collectors.toList());

        return performAnalysis(drawDtos);
    }

    // ==================== 내부 분석 로직 ====================

    private PensionLotteryAnalysisDto performAnalysis(List<PensionLotteryDrawDto> draws) {
        if (draws.isEmpty()) {
            return null;
        }

        int latestDrawNo = draws.get(0).getDrawNo();

        Map<Integer, GroupStatDto> groupStats = analyzeGroupStats(draws);
        List<DigitStatDto> digitStats = analyzeDigitStats(draws);
        List<DigitStatDto> hotDigits = extractHotDigits(digitStats, 15);
        List<DigitStatDto> coldDigits = extractColdDigits(digitStats, 15);
        StatisticsSummaryDto statistics = generateStatisticsSummary(draws);
        List<PensionLotteryRecommendationDto> recommendations =
            generateRecommendations(groupStats, digitStats, hotDigits, coldDigits);

        return PensionLotteryAnalysisDto.builder()
                .latestDrawNo(latestDrawNo)
                .analyzedDrawCount(draws.size())
                .analysisTime(LocalDateTime.now())
                .recommendations(recommendations)
                .groupStats(groupStats)
                .digitStats(digitStats)
                .hotDigits(hotDigits)
                .coldDigits(coldDigits)
                .statistics(statistics)
                .build();
    }

    private Map<Integer, GroupStatDto> analyzeGroupStats(List<PensionLotteryDrawDto> draws) {
        Map<Integer, GroupStatDto> stats = new LinkedHashMap<>();

        for (int g = 1; g <= MAX_GROUP; g++) {
            stats.put(g, GroupStatDto.builder()
                    .group(g)
                    .frequency(0)
                    .percentage(0.0)
                    .lastAppearance(999)
                    .build());
        }

        for (int i = 0; i < draws.size(); i++) {
            int group = draws.get(i).getFirstGroup();
            if (group >= 1 && group <= MAX_GROUP) {
                GroupStatDto stat = stats.get(group);
                stat.setFrequency(stat.getFrequency() + 1);
                if (stat.getLastAppearance() == 999) {
                    stat.setLastAppearance(i);
                }
            }
        }

        int total = draws.size();
        for (GroupStatDto stat : stats.values()) {
            stat.setPercentage(Math.round(stat.getFrequency() * 1000.0 / total) / 10.0);
        }

        return stats;
    }

    private List<DigitStatDto> analyzeDigitStats(List<PensionLotteryDrawDto> draws) {
        List<DigitStatDto> statsList = new ArrayList<>();

        for (int pos = 1; pos <= DIGIT_COUNT; pos++) {
            for (int digit = 0; digit <= 9; digit++) {
                DigitStatDto stat = DigitStatDto.builder()
                        .position(pos)
                        .digit(digit)
                        .frequency10(0)
                        .frequency50(0)
                        .frequency100(0)
                        .lastAppearance(999)
                        .weight(0.0)
                        .category("WARM")
                        .build();

                for (int i = 0; i < draws.size(); i++) {
                    String number = draws.get(i).getFirstNumber();
                    if (number != null && number.length() >= pos) {
                        int drawDigit = Character.getNumericValue(number.charAt(pos - 1));
                        if (drawDigit == digit) {
                            if (stat.getLastAppearance() == 999) {
                                stat.setLastAppearance(i);
                            }
                            if (i < 10) stat.setFrequency10(stat.getFrequency10() + 1);
                            if (i < 50) stat.setFrequency50(stat.getFrequency50() + 1);
                            if (i < 100) stat.setFrequency100(stat.getFrequency100() + 1);
                        }
                    }
                }

                double weight = (stat.getFrequency10() * 3.0) +
                               (stat.getFrequency50() * 2.0) +
                               (stat.getFrequency100() * 1.0) -
                               (stat.getLastAppearance() * 0.3);
                stat.setWeight(Math.max(0, weight));

                if (stat.getFrequency10() >= 2 || stat.getLastAppearance() <= 3) {
                    stat.setCategory("HOT");
                } else if (stat.getLastAppearance() >= 15 || stat.getFrequency50() <= 3) {
                    stat.setCategory("COLD");
                }

                statsList.add(stat);
            }
        }

        return statsList;
    }

    private List<DigitStatDto> extractHotDigits(List<DigitStatDto> stats, int count) {
        return stats.stream()
                .filter(s -> "HOT".equals(s.getCategory()))
                .sorted((a, b) -> Double.compare(b.getWeight(), a.getWeight()))
                .limit(count)
                .collect(Collectors.toList());
    }

    private List<DigitStatDto> extractColdDigits(List<DigitStatDto> stats, int count) {
        return stats.stream()
                .filter(s -> "COLD".equals(s.getCategory()))
                .sorted((a, b) -> Integer.compare(b.getLastAppearance(), a.getLastAppearance()))
                .limit(count)
                .collect(Collectors.toList());
    }

    private StatisticsSummaryDto generateStatisticsSummary(List<PensionLotteryDrawDto> draws) {
        Map<Integer, Integer> groupDist = new HashMap<>();
        for (int g = 1; g <= MAX_GROUP; g++) {
            groupDist.put(g, 0);
        }
        for (PensionLotteryDrawDto draw : draws) {
            int group = draw.getFirstGroup();
            if (group >= 1 && group <= MAX_GROUP) {
                groupDist.merge(group, 1, Integer::sum);
            }
        }

        Map<String, Integer> oddEvenDist = new HashMap<>();
        for (PensionLotteryDrawDto draw : draws) {
            String pattern = getOddEvenPattern(draw.getFirstNumber());
            oddEvenDist.merge(pattern, 1, Integer::sum);
        }

        double avgSum = draws.stream()
                .mapToInt(d -> getDigitSum(d.getFirstNumber()))
                .average().orElse(0);

        Map<Integer, Map<Integer, Integer>> posDigitFreq = new HashMap<>();
        for (int pos = 1; pos <= DIGIT_COUNT; pos++) {
            Map<Integer, Integer> digitFreq = new HashMap<>();
            for (int d = 0; d <= 9; d++) {
                digitFreq.put(d, 0);
            }
            posDigitFreq.put(pos, digitFreq);
        }

        for (PensionLotteryDrawDto draw : draws) {
            String number = draw.getFirstNumber();
            if (number != null) {
                for (int pos = 1; pos <= Math.min(number.length(), DIGIT_COUNT); pos++) {
                    int digit = Character.getNumericValue(number.charAt(pos - 1));
                    posDigitFreq.get(pos).merge(digit, 1, Integer::sum);
                }
            }
        }

        return StatisticsSummaryDto.builder()
                .groupDistribution(groupDist)
                .oddEvenDistribution(oddEvenDist)
                .avgDigitSum(Math.round(avgSum * 10) / 10.0)
                .positionDigitFrequency(posDigitFreq)
                .build();
    }

    private String getOddEvenPattern(String number) {
        if (number == null) return "";
        StringBuilder pattern = new StringBuilder();
        for (char c : number.toCharArray()) {
            int d = Character.getNumericValue(c);
            pattern.append(d % 2 == 1 ? "홀" : "짝");
        }
        return pattern.toString();
    }

    private int getDigitSum(String number) {
        if (number == null) return 0;
        return number.chars()
                .map(Character::getNumericValue)
                .sum();
    }

    private List<PensionLotteryRecommendationDto> generateRecommendations(
            Map<Integer, GroupStatDto> groupStats,
            List<DigitStatDto> digitStats,
            List<DigitStatDto> hotDigits,
            List<DigitStatDto> coldDigits) {

        List<PensionLotteryRecommendationDto> recommendations = new ArrayList<>();
        Set<String> generatedCombinations = new HashSet<>();

        String[] strategies = {
            "Hot 70% + Cold 30% 하이브리드",
            "자리별 빈도 기반",
            "가중치 상위 숫자 우선",
            "균형 잡힌 홀짝 분포",
            "Cold 숫자 역발상 전략"
        };

        int gameNo = 1;
        int attempts = 0;
        int maxAttempts = 500;

        while (recommendations.size() < GAME_COUNT && attempts < maxAttempts) {
            attempts++;

            int group = selectGroup(groupStats);
            List<Integer> digits;
            String strategy = strategies[Math.min(gameNo - 1, strategies.length - 1)];

            switch (gameNo) {
                case 1:
                    digits = generateHotColdHybrid(digitStats, hotDigits, coldDigits);
                    break;
                case 2:
                    digits = generateFrequencyBased(digitStats);
                    break;
                case 3:
                    digits = generateWeightBased(digitStats);
                    break;
                case 4:
                    digits = generateBalancedOddEven(digitStats);
                    break;
                case 5:
                    digits = generateColdFocused(digitStats, coldDigits);
                    break;
                default:
                    digits = generateWeightBased(digitStats);
            }

            String number = digits.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining());

            String key = group + "-" + number;
            if (generatedCombinations.contains(key)) {
                continue;
            }
            generatedCombinations.add(key);

            double confidence = calculateConfidence(digits, digitStats);

            PensionLotteryRecommendationDto rec = PensionLotteryRecommendationDto.builder()
                    .gameNo(gameNo)
                    .group(group)
                    .number(number)
                    .digits(digits)
                    .strategy(strategy)
                    .confidence(Math.round(confidence * 10) / 10.0)
                    .oddEvenPattern(getOddEvenPattern(number))
                    .highLowPattern(getHighLowPattern(number))
                    .digitSum(getDigitSum(number))
                    .build();

            recommendations.add(rec);
            gameNo++;
        }

        return recommendations;
    }

    private int selectGroup(Map<Integer, GroupStatDto> groupStats) {
        List<Integer> pool = new ArrayList<>();
        int maxFreq = groupStats.values().stream()
                .mapToInt(GroupStatDto::getFrequency)
                .max().orElse(1);

        for (int g = 1; g <= MAX_GROUP; g++) {
            int freq = groupStats.get(g).getFrequency();
            int weight = Math.max(1, maxFreq - freq + 5);
            for (int i = 0; i < weight; i++) {
                pool.add(g);
            }
        }

        return pool.get(random.nextInt(pool.size()));
    }

    private List<Integer> generateHotColdHybrid(
            List<DigitStatDto> digitStats,
            List<DigitStatDto> hotDigits,
            List<DigitStatDto> coldDigits) {

        List<Integer> result = new ArrayList<>();

        for (int pos = 1; pos <= DIGIT_COUNT; pos++) {
            final int position = pos;

            List<Integer> hotPool = hotDigits.stream()
                    .filter(d -> d.getPosition() == position)
                    .map(DigitStatDto::getDigit)
                    .collect(Collectors.toList());

            List<Integer> coldPool = coldDigits.stream()
                    .filter(d -> d.getPosition() == position)
                    .map(DigitStatDto::getDigit)
                    .collect(Collectors.toList());

            int digit;
            if (random.nextDouble() < 0.7 && !hotPool.isEmpty()) {
                digit = hotPool.get(random.nextInt(hotPool.size()));
            } else if (!coldPool.isEmpty()) {
                digit = coldPool.get(random.nextInt(coldPool.size()));
            } else {
                digit = random.nextInt(10);
            }

            result.add(digit);
        }

        return result;
    }

    private List<Integer> generateFrequencyBased(List<DigitStatDto> digitStats) {
        List<Integer> result = new ArrayList<>();

        for (int pos = 1; pos <= DIGIT_COUNT; pos++) {
            final int position = pos;

            List<DigitStatDto> posStats = digitStats.stream()
                    .filter(d -> d.getPosition() == position)
                    .sorted((a, b) -> Integer.compare(b.getFrequency50(), a.getFrequency50()))
                    .limit(5)
                    .collect(Collectors.toList());

            if (!posStats.isEmpty()) {
                result.add(posStats.get(random.nextInt(posStats.size())).getDigit());
            } else {
                result.add(random.nextInt(10));
            }
        }

        return result;
    }

    private List<Integer> generateWeightBased(List<DigitStatDto> digitStats) {
        List<Integer> result = new ArrayList<>();

        for (int pos = 1; pos <= DIGIT_COUNT; pos++) {
            final int position = pos;

            List<DigitStatDto> posStats = digitStats.stream()
                    .filter(d -> d.getPosition() == position)
                    .sorted((a, b) -> Double.compare(b.getWeight(), a.getWeight()))
                    .limit(5)
                    .collect(Collectors.toList());

            if (!posStats.isEmpty()) {
                result.add(posStats.get(random.nextInt(posStats.size())).getDigit());
            } else {
                result.add(random.nextInt(10));
            }
        }

        return result;
    }

    private List<Integer> generateBalancedOddEven(List<DigitStatDto> digitStats) {
        List<Integer> result = new ArrayList<>();
        int oddCount = 0;
        int evenCount = 0;

        for (int pos = 1; pos <= DIGIT_COUNT; pos++) {
            final int position = pos;

            List<DigitStatDto> posStats = digitStats.stream()
                    .filter(d -> d.getPosition() == position)
                    .sorted((a, b) -> Double.compare(b.getWeight(), a.getWeight()))
                    .collect(Collectors.toList());

            List<DigitStatDto> filtered;
            if (oddCount < 3 && evenCount >= 3) {
                filtered = posStats.stream()
                        .filter(d -> d.getDigit() % 2 == 1)
                        .limit(5)
                        .collect(Collectors.toList());
            } else if (evenCount < 3 && oddCount >= 3) {
                filtered = posStats.stream()
                        .filter(d -> d.getDigit() % 2 == 0)
                        .limit(5)
                        .collect(Collectors.toList());
            } else {
                filtered = posStats.stream().limit(5).collect(Collectors.toList());
            }

            int digit;
            if (!filtered.isEmpty()) {
                digit = filtered.get(random.nextInt(filtered.size())).getDigit();
            } else {
                digit = random.nextInt(10);
            }

            if (digit % 2 == 1) oddCount++;
            else evenCount++;

            result.add(digit);
        }

        return result;
    }

    private List<Integer> generateColdFocused(List<DigitStatDto> digitStats, List<DigitStatDto> coldDigits) {
        List<Integer> result = new ArrayList<>();

        for (int pos = 1; pos <= DIGIT_COUNT; pos++) {
            final int position = pos;

            List<Integer> coldPool = coldDigits.stream()
                    .filter(d -> d.getPosition() == position)
                    .map(DigitStatDto::getDigit)
                    .collect(Collectors.toList());

            int digit;
            if (!coldPool.isEmpty() && random.nextDouble() < 0.6) {
                digit = coldPool.get(random.nextInt(coldPool.size()));
            } else {
                List<DigitStatDto> posStats = digitStats.stream()
                        .filter(d -> d.getPosition() == position)
                        .sorted((a, b) -> Double.compare(b.getWeight(), a.getWeight()))
                        .limit(5)
                        .collect(Collectors.toList());

                if (!posStats.isEmpty()) {
                    digit = posStats.get(random.nextInt(posStats.size())).getDigit();
                } else {
                    digit = random.nextInt(10);
                }
            }

            result.add(digit);
        }

        return result;
    }

    private String getHighLowPattern(String number) {
        if (number == null) return "";
        StringBuilder pattern = new StringBuilder();
        for (char c : number.toCharArray()) {
            int d = Character.getNumericValue(c);
            pattern.append(d <= 4 ? "저" : "고");
        }
        return pattern.toString();
    }

    private double calculateConfidence(List<Integer> digits, List<DigitStatDto> digitStats) {
        double score = 50.0;

        for (int pos = 1; pos <= digits.size(); pos++) {
            final int position = pos;
            final int digit = digits.get(pos - 1);

            Optional<DigitStatDto> statOpt = digitStats.stream()
                    .filter(d -> d.getPosition() == position && d.getDigit() == digit)
                    .findFirst();

            if (statOpt.isPresent()) {
                DigitStatDto stat = statOpt.get();
                if (stat.getWeight() > 10) {
                    score += 3;
                } else if (stat.getWeight() > 5) {
                    score += 2;
                }
                if ("HOT".equals(stat.getCategory())) {
                    score += 2;
                }
            }
        }

        long oddCount = digits.stream().filter(d -> d % 2 == 1).count();
        if (oddCount >= 2 && oddCount <= 4) {
            score += 5;
        }

        return Math.min(score, 95);
    }

    // ==================== 변환 메서드 ====================

    private PensionLotteryDrawDto toDrawDto(PensionLotteryDraw entity) {
        return PensionLotteryDrawDto.builder()
                .drawNo(entity.getDrawNo())
                .firstGroup(entity.getFirstGroup())
                .firstNumber(entity.getFirstNumber())
                .bonusGroup(entity.getBonusGroup())
                .bonusNumber(entity.getBonusNumber())
                .build();
    }

    private PensionLotteryAnalysisDto convertToAnalysisDto(PensionLotteryWeeklyRecommendation entity) {
        try {
            List<PensionLotteryRecommendationDto> recommendations = objectMapper.readValue(
                    entity.getRecommendations(),
                    new TypeReference<List<PensionLotteryRecommendationDto>>() {}
            );

            StatisticsSummaryDto statistics = objectMapper.readValue(
                    entity.getStatisticsSummary(),
                    StatisticsSummaryDto.class
            );

            List<DigitStatDto> hotDigits = objectMapper.readValue(
                    entity.getHotDigits(),
                    new TypeReference<List<DigitStatDto>>() {}
            );

            List<DigitStatDto> coldDigits = objectMapper.readValue(
                    entity.getColdDigits(),
                    new TypeReference<List<DigitStatDto>>() {}
            );

            Map<Integer, GroupStatDto> groupStats = objectMapper.readValue(
                    entity.getGroupStats(),
                    new TypeReference<Map<Integer, GroupStatDto>>() {}
            );

            return PensionLotteryAnalysisDto.builder()
                    .latestDrawNo(entity.getLatestAnalyzedDrawNo())
                    .analyzedDrawCount(entity.getAnalyzedDrawCount())
                    .analysisTime(entity.getCreatedAt())
                    .recommendations(recommendations)
                    .groupStats(groupStats)
                    .hotDigits(hotDigits)
                    .coldDigits(coldDigits)
                    .statistics(statistics)
                    .build();

        } catch (JsonProcessingException e) {
            log.error("[연금복권변환] JSON 파싱 오류: {}", e.getMessage());
            return null;
        }
    }
}
