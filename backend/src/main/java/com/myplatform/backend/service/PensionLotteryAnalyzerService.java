package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.PensionLotteryAnalysisDto;
import com.myplatform.backend.dto.PensionLotteryAnalysisDto.DigitStatDto;
import com.myplatform.backend.dto.PensionLotteryAnalysisDto.GroupStatDto;
import com.myplatform.backend.dto.PensionLotteryAnalysisDto.StatisticsSummaryDto;
import com.myplatform.backend.dto.PensionLotteryDrawDto;
import com.myplatform.backend.dto.PensionLotteryRecommendationDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 연금복권 720+ 통계 기반 번호 추출기
 *
 * 자리별 숫자 빈도 분석 + Hot/Cold 하이브리드 필터링
 */
@Service
@Slf4j
public class PensionLotteryAnalyzerService {

    private static final String PENSION_API_URL = "https://www.dhlottery.co.kr/common.do?method=get720Number&drwNo=";
    private static final int DIGIT_COUNT = 6;
    private static final int GAME_COUNT = 5;
    private static final int MAX_GROUP = 5;

    // Hot/Cold 비율
    private static final double HOT_RATIO = 0.7;
    private static final double COLD_RATIO = 0.3;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom random;

    // 캐시
    private final Map<Integer, PensionLotteryDrawDto> drawCache = new ConcurrentHashMap<>();
    private volatile Integer latestDrawNo = null;

    // 금주의 추천 번호
    private volatile PensionLotteryAnalysisDto weeklyRecommendation = null;
    private volatile LocalDate weeklyRecommendationDate = null;

    public PensionLotteryAnalyzerService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.random = new SecureRandom();
    }

    /**
     * 연금복권 번호 분석 및 추천
     */
    public PensionLotteryAnalysisDto analyzeAndRecommend() {
        log.info("[연금복권분석] 분석 시작...");

        // 1. 최신 회차 확인 및 데이터 수집
        updateLatestDrawNo();
        List<PensionLotteryDrawDto> recentDraws = collectRecentDraws(100);

        if (recentDraws.isEmpty()) {
            log.error("[연금복권분석] 당첨 데이터 수집 실패");
            return null;
        }

        log.info("[연금복권분석] {}개 회차 데이터 수집 완료", recentDraws.size());

        // 2. 조 번호 통계 분석
        Map<Integer, GroupStatDto> groupStats = analyzeGroupStats(recentDraws);

        // 3. 자리별 숫자 통계 분석
        List<DigitStatDto> digitStats = analyzeDigitStats(recentDraws);

        // 4. Hot/Cold 숫자 분류
        List<DigitStatDto> hotDigits = extractHotDigits(digitStats, 15);
        List<DigitStatDto> coldDigits = extractColdDigits(digitStats, 15);

        // 5. 통계 요약 생성
        StatisticsSummaryDto statistics = generateStatisticsSummary(recentDraws);

        // 6. 추천 번호 생성 (5게임)
        List<PensionLotteryRecommendationDto> recommendations =
            generateRecommendations(groupStats, digitStats, hotDigits, coldDigits);

        log.info("[연금복권분석] 분석 완료 - {}게임 추천", recommendations.size());

        return PensionLotteryAnalysisDto.builder()
                .latestDrawNo(latestDrawNo)
                .analyzedDrawCount(recentDraws.size())
                .analysisTime(LocalDateTime.now())
                .recommendations(recommendations)
                .groupStats(groupStats)
                .digitStats(digitStats)
                .hotDigits(hotDigits)
                .coldDigits(coldDigits)
                .statistics(statistics)
                .build();
    }

    /**
     * 최신 회차 번호 업데이트
     */
    private void updateLatestDrawNo() {
        // 연금복권은 2011년 9월 시작, 매주 목요일 추첨
        LocalDate firstDraw = LocalDate.of(2011, 9, 1);
        LocalDate today = LocalDate.now();
        long weeks = java.time.temporal.ChronoUnit.WEEKS.between(firstDraw, today);
        int estimatedDrawNo = (int) weeks + 1;

        log.info("[연금복권분석] 추정 최신 회차: {}회 (검색 범위: {}-{})", estimatedDrawNo, estimatedDrawNo, estimatedDrawNo - 10);

        // 추정 회차부터 실제 데이터가 있는지 확인
        for (int i = estimatedDrawNo; i > estimatedDrawNo - 10; i--) {
            PensionLotteryDrawDto draw = fetchDrawData(i);
            if (draw != null) {
                latestDrawNo = i;
                log.info("[연금복권분석] 최신 회차 확인: {}회", latestDrawNo);
                return;
            }
        }

        // 못 찾으면 알려진 최근 회차 사용 (2024년 1월 기준 약 650회차)
        if (latestDrawNo == null) {
            latestDrawNo = 650;
            log.warn("[연금복권분석] 최신 회차 조회 실패, 기본값 사용: {}회", latestDrawNo);
        }
    }

    /**
     * 최근 N회차 데이터 수집
     */
    private List<PensionLotteryDrawDto> collectRecentDraws(int count) {
        List<PensionLotteryDrawDto> draws = new ArrayList<>();

        if (latestDrawNo == null) {
            updateLatestDrawNo();
        }

        for (int i = latestDrawNo; i > latestDrawNo - count && i > 0; i--) {
            PensionLotteryDrawDto draw = fetchDrawData(i);
            if (draw != null) {
                draws.add(draw);
            }
        }

        return draws;
    }

    /**
     * 동행복권 API에서 회차 데이터 조회
     */
    private PensionLotteryDrawDto fetchDrawData(int drawNo) {
        // 캐시 확인
        if (drawCache.containsKey(drawNo)) {
            return drawCache.get(drawNo);
        }

        try {
            String url = PENSION_API_URL + drawNo;

            // User-Agent 헤더 설정 (API 차단 방지)
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.set("Accept", "application/json, text/plain, */*");
            headers.set("Referer", "https://www.dhlottery.co.kr/");
            headers.set("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            log.info("[연금복권분석] {}회차 API 호출 중...", drawNo);

            ResponseEntity<String> responseEntity = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String response = responseEntity.getBody();

            if (response == null || response.isEmpty()) {
                log.warn("[연금복권분석] {}회차 응답 없음", drawNo);
                return null;
            }

            log.info("[연금복권분석] {}회차 응답 수신 (길이: {})", drawNo, response.length());

            JsonNode json = objectMapper.readTree(response);

            String returnValue = json.path("returnValue").asText();
            if (!"success".equals(returnValue)) {
                log.warn("[연금복권분석] {}회차 returnValue: {} (응답: {})", drawNo, returnValue,
                    response.length() > 200 ? response.substring(0, 200) + "..." : response);
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

            PensionLotteryDrawDto draw = PensionLotteryDrawDto.builder()
                    .drawNo(drawNo)
                    .firstGroup(firstGroup)
                    .firstNumber(firstNumber.toString())
                    .bonusGroup(bonusGroup)
                    .bonusNumber(bonusNumber.toString())
                    .build();

            // 캐시 저장
            drawCache.put(drawNo, draw);

            log.debug("[연금복권분석] {}회차 데이터 수집 성공", drawNo);
            return draw;

        } catch (Exception e) {
            log.warn("[연금복권분석] {}회차 데이터 조회 실패: {} - {}", drawNo, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 조 번호 통계 분석
     */
    private Map<Integer, GroupStatDto> analyzeGroupStats(List<PensionLotteryDrawDto> draws) {
        Map<Integer, GroupStatDto> stats = new LinkedHashMap<>();

        // 초기화 (1~5조)
        for (int g = 1; g <= MAX_GROUP; g++) {
            stats.put(g, GroupStatDto.builder()
                    .group(g)
                    .frequency(0)
                    .percentage(0.0)
                    .lastAppearance(999)
                    .build());
        }

        // 빈도 계산
        for (int i = 0; i < draws.size(); i++) {
            PensionLotteryDrawDto draw = draws.get(i);
            int group = draw.getFirstGroup();

            if (group >= 1 && group <= MAX_GROUP) {
                GroupStatDto stat = stats.get(group);
                stat.setFrequency(stat.getFrequency() + 1);

                if (stat.getLastAppearance() == 999) {
                    stat.setLastAppearance(i);
                }
            }
        }

        // 비율 계산
        int total = draws.size();
        for (GroupStatDto stat : stats.values()) {
            stat.setPercentage(Math.round(stat.getFrequency() * 1000.0 / total) / 10.0);
        }

        return stats;
    }

    /**
     * 자리별 숫자 통계 분석
     */
    private List<DigitStatDto> analyzeDigitStats(List<PensionLotteryDrawDto> draws) {
        List<DigitStatDto> statsList = new ArrayList<>();

        // 각 자리(1~6)별, 각 숫자(0~9)별 통계
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

                // 빈도 계산
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

                // 가중치 계산
                double weight = (stat.getFrequency10() * 3.0) +
                               (stat.getFrequency50() * 2.0) +
                               (stat.getFrequency100() * 1.0) -
                               (stat.getLastAppearance() * 0.3);
                stat.setWeight(Math.max(0, weight));

                // 카테고리 분류
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

    /**
     * Hot Digits 추출
     */
    private List<DigitStatDto> extractHotDigits(List<DigitStatDto> stats, int count) {
        return stats.stream()
                .filter(s -> "HOT".equals(s.getCategory()))
                .sorted((a, b) -> Double.compare(b.getWeight(), a.getWeight()))
                .limit(count)
                .collect(Collectors.toList());
    }

    /**
     * Cold Digits 추출
     */
    private List<DigitStatDto> extractColdDigits(List<DigitStatDto> stats, int count) {
        return stats.stream()
                .filter(s -> "COLD".equals(s.getCategory()))
                .sorted((a, b) -> Integer.compare(b.getLastAppearance(), a.getLastAppearance()))
                .limit(count)
                .collect(Collectors.toList());
    }

    /**
     * 통계 요약 생성
     */
    private StatisticsSummaryDto generateStatisticsSummary(List<PensionLotteryDrawDto> draws) {
        // 조 번호 분포
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

        // 홀짝 패턴 분포
        Map<String, Integer> oddEvenDist = new HashMap<>();
        for (PensionLotteryDrawDto draw : draws) {
            String pattern = getOddEvenPattern(draw.getFirstNumber());
            oddEvenDist.merge(pattern, 1, Integer::sum);
        }

        // 평균 자리수 합계
        double avgSum = draws.stream()
                .mapToInt(d -> getDigitSum(d.getFirstNumber()))
                .average().orElse(0);

        // 자리별 숫자 빈도
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

    /**
     * 홀짝 패턴 생성
     */
    private String getOddEvenPattern(String number) {
        if (number == null) return "";
        StringBuilder pattern = new StringBuilder();
        for (char c : number.toCharArray()) {
            int d = Character.getNumericValue(c);
            pattern.append(d % 2 == 1 ? "홀" : "짝");
        }
        return pattern.toString();
    }

    /**
     * 자리수 합계 계산
     */
    private int getDigitSum(String number) {
        if (number == null) return 0;
        return number.chars()
                .map(Character::getNumericValue)
                .sum();
    }

    /**
     * 추천 번호 5게임 생성
     */
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

            // 조 번호 선택 (빈도 기반 가중 랜덤)
            int group = selectGroup(groupStats);

            // 6자리 번호 생성
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

            // 중복 체크
            String key = group + "-" + number;
            if (generatedCombinations.contains(key)) {
                continue;
            }
            generatedCombinations.add(key);

            // 신뢰도 계산
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

            log.info("[연금복권분석] 게임 {} 생성: {}조 {} (신뢰도:{}%)",
                    rec.getGameNo(), rec.getGroup(), rec.getNumber(), rec.getConfidence());
        }

        return recommendations;
    }

    /**
     * 조 번호 선택 (빈도 기반)
     */
    private int selectGroup(Map<Integer, GroupStatDto> groupStats) {
        // 빈도가 낮은 조에 가중치를 더 주어 균형 맞춤
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

    /**
     * Hot/Cold 하이브리드 전략
     */
    private List<Integer> generateHotColdHybrid(
            List<DigitStatDto> digitStats,
            List<DigitStatDto> hotDigits,
            List<DigitStatDto> coldDigits) {

        List<Integer> result = new ArrayList<>();

        for (int pos = 1; pos <= DIGIT_COUNT; pos++) {
            final int position = pos;

            // 해당 자리의 Hot/Cold 숫자 필터링
            List<Integer> hotPool = hotDigits.stream()
                    .filter(d -> d.getPosition() == position)
                    .map(DigitStatDto::getDigit)
                    .collect(Collectors.toList());

            List<Integer> coldPool = coldDigits.stream()
                    .filter(d -> d.getPosition() == position)
                    .map(DigitStatDto::getDigit)
                    .collect(Collectors.toList());

            // 70% 확률로 Hot, 30% 확률로 Cold
            int digit;
            if (random.nextDouble() < HOT_RATIO && !hotPool.isEmpty()) {
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

    /**
     * 빈도 기반 전략
     */
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

    /**
     * 가중치 기반 전략
     */
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

    /**
     * 홀짝 균형 전략
     */
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

            // 홀짝 균형 맞추기 (3:3 목표)
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

    /**
     * Cold 중심 역발상 전략
     */
    private List<Integer> generateColdFocused(List<DigitStatDto> digitStats, List<DigitStatDto> coldDigits) {
        List<Integer> result = new ArrayList<>();

        for (int pos = 1; pos <= DIGIT_COUNT; pos++) {
            final int position = pos;

            // 해당 자리의 Cold 숫자 우선
            List<Integer> coldPool = coldDigits.stream()
                    .filter(d -> d.getPosition() == position)
                    .map(DigitStatDto::getDigit)
                    .collect(Collectors.toList());

            int digit;
            if (!coldPool.isEmpty() && random.nextDouble() < 0.6) {
                digit = coldPool.get(random.nextInt(coldPool.size()));
            } else {
                // 그 외는 일반 가중치 기반
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

    /**
     * 고저 패턴 생성 (0-4: 저, 5-9: 고)
     */
    private String getHighLowPattern(String number) {
        if (number == null) return "";
        StringBuilder pattern = new StringBuilder();
        for (char c : number.toCharArray()) {
            int d = Character.getNumericValue(c);
            pattern.append(d <= 4 ? "저" : "고");
        }
        return pattern.toString();
    }

    /**
     * 신뢰도 계산
     */
    private double calculateConfidence(List<Integer> digits, List<DigitStatDto> digitStats) {
        double score = 50.0;

        // 각 자리별 가중치 합산
        for (int pos = 1; pos <= digits.size(); pos++) {
            final int position = pos;
            final int digit = digits.get(pos - 1);

            Optional<DigitStatDto> statOpt = digitStats.stream()
                    .filter(d -> d.getPosition() == position && d.getDigit() == digit)
                    .findFirst();

            if (statOpt.isPresent()) {
                DigitStatDto stat = statOpt.get();
                // 가중치 기반 점수
                if (stat.getWeight() > 10) {
                    score += 3;
                } else if (stat.getWeight() > 5) {
                    score += 2;
                }

                // Hot 숫자 보너스
                if ("HOT".equals(stat.getCategory())) {
                    score += 2;
                }
            }
        }

        // 홀짝 균형 보너스
        long oddCount = digits.stream().filter(d -> d % 2 == 1).count();
        if (oddCount >= 2 && oddCount <= 4) {
            score += 5;
        }

        return Math.min(score, 95);
    }

    // ==================== 공개 API 메서드 ====================

    public PensionLotteryDrawDto getDrawData(int drawNo) {
        return fetchDrawData(drawNo);
    }

    public List<PensionLotteryDrawDto> getRecentDraws(int count) {
        updateLatestDrawNo();
        return collectRecentDraws(count);
    }

    public Integer getLatestDrawNo() {
        if (latestDrawNo == null) {
            updateLatestDrawNo();
        }
        return latestDrawNo;
    }

    // ==================== 금주의 추천 번호 ====================

    @Scheduled(cron = "0 0 6 * * MON", zone = "Asia/Seoul")
    public void generateWeeklyRecommendation() {
        log.info("[연금복권분석] 금주의 추천 번호 생성 시작...");

        try {
            PensionLotteryAnalysisDto analysis = analyzeAndRecommend();
            if (analysis != null) {
                weeklyRecommendation = analysis;
                weeklyRecommendationDate = LocalDate.now();
                log.info("[연금복권분석] 금주의 추천 번호 생성 완료 - {} 기준", weeklyRecommendationDate);
            }
        } catch (Exception e) {
            log.error("[연금복권분석] 금주의 추천 번호 생성 실패: {}", e.getMessage());
        }
    }

    public PensionLotteryAnalysisDto getWeeklyRecommendation() {
        if (weeklyRecommendation == null || weeklyRecommendationDate == null ||
            weeklyRecommendationDate.plusDays(7).isBefore(LocalDate.now())) {
            generateWeeklyRecommendation();
        }
        return weeklyRecommendation;
    }

    public LocalDate getWeeklyRecommendationDate() {
        return weeklyRecommendationDate;
    }

    public PensionLotteryAnalysisDto refreshWeeklyRecommendation() {
        generateWeeklyRecommendation();
        return weeklyRecommendation;
    }
}
