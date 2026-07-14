package com.myplatform.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.InvestorSurgeDto;
import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.dto.NewsSummaryDto;
import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.entity.AiStrategySnapshot;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Google Gemini AI 서비스
 * - 스크리너 결과 분석 및 AI 추천 제공
 * - Rate Limit 처리 (지수 백오프 재시도)
 */
@Slf4j
@Service
public class GeminiService {

    @Value("${gemini.api.key:}")
    private String apiKey;

    // gemini-2.0-flash 종료 → gemini-2.5-flash-lite (무료 티어 ≈ 15 RPM, generateContent 규격 동일).
    @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent}")
    private String apiUrl;

    private final RestTemplate restTemplate;

    private static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");

    // Rate Limit 관리
    private static final int MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 20000; // 20초
    // 무료 티어 gemini-2.5-flash-lite ≈ 15 RPM. 4.5초 간격 = 최대 ~13 RPM(여유 확보).
    private static final long MIN_REQUEST_INTERVAL_MS = 4500;
    private static final Pattern RETRY_DELAY_PATTERN = Pattern.compile("retry in ([\\d.]+)s");

    // 전역 rate limiter — 모든 호출자(재료·AI전략·StockDetail 등)를 한 게이트로 직렬화해 동시 버스트를
    // 물리적으로 차단. 이전 volatile check-then-act 는 비원자적이라 동시 스레드가 같이 통과 → 429 유발.
    private final RateLimiter rateLimiter = new RateLimiter(MIN_REQUEST_INTERVAL_MS);
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private volatile LocalDateTime quotaResetTime = null;

    // Forecast 캐시 (10분)
    private static final long FORECAST_CACHE_MINUTES = 10;
    private volatile Map<String, Object> forecastCache = null;  // 읽기 전용 교체 방식이므로 volatile로 충분
    private volatile LocalDateTime forecastCacheTime = null;

    // 캐시 갱신 동기화
    private final Object forecastCacheLock = new Object();

    // 호출 카운터 — Prometheus 노출. 비용 추적 / quota 모니터링.
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final io.micrometer.core.instrument.Counter callOk;
    private final io.micrometer.core.instrument.Counter callFail;
    private final io.micrometer.core.instrument.Counter callRateLimit;

    // 일일 호출 한도 임계 알림 — 80% / 90% 도달 시 텔레그램 risk 채널 1회 알림.
    // 자정 (KST) 에 카운터 리셋. quota 소진 직전에 사용자에게 경고해 비핵심 호출 차단 결정 가능.
    @Value("${gemini.usage.daily-limit:${news.gemini.daily-limit:500}}")
    private int dailyLimit;
    private final AtomicInteger dailyCount = new AtomicInteger(0);
    private volatile LocalDate dailyCountDate = LocalDate.now();
    private volatile boolean alerted80 = false;
    private volatile boolean alerted90 = false;
    // 순환 의존성 회피용 — TelegramNotificationService 가 직접 Gemini 를 안 부르지만 안전 위해 ObjectProvider.
    private final org.springframework.beans.factory.ObjectProvider<TelegramNotificationService> telegramProvider;
    // 시장 예측 KOSPI 실지수 폴백용(§16 지수 단일경로 getIndexPrice("0001")) — 순환/미가용 안전 위해 ObjectProvider.
    private final org.springframework.beans.factory.ObjectProvider<KoreaInvestmentService> kisProvider;

    public GeminiService(io.micrometer.core.instrument.MeterRegistry meterRegistry,
                         org.springframework.beans.factory.ObjectProvider<TelegramNotificationService> telegramProvider,
                         org.springframework.beans.factory.ObjectProvider<KoreaInvestmentService> kisProvider) {
        this.meterRegistry = meterRegistry;
        this.telegramProvider = telegramProvider;
        this.kisProvider = kisProvider;
        this.callOk = io.micrometer.core.instrument.Counter.builder("gemini.api.calls")
                .description("Gemini API 호출 성공")
                .tag("outcome", "success").register(meterRegistry);
        this.callFail = io.micrometer.core.instrument.Counter.builder("gemini.api.calls")
                .description("Gemini API 호출 실패 (rate limit 제외)")
                .tag("outcome", "fail").register(meterRegistry);
        this.callRateLimit = io.micrometer.core.instrument.Counter.builder("gemini.api.calls")
                .description("Gemini API 호출 rate limit / quota")
                .tag("outcome", "rate_limit").register(meterRegistry);
        // Gemini API 전용 RestTemplate (타임아웃 설정)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);   // 연결 5초
        factory.setReadTimeout(30000);     // 응답 30초 (AI 응답 대기)
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 범용 Gemini AI 호출 (뉴스 요약 등).
     * CircuitBreaker — Gemini quota 초과/일시 장애에 cascading 호출 차단 + fallback.
     */
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(
            name = "geminiApi", fallbackMethod = "chatFallback")
    public String chat(String prompt) {
        return callWithFallback(prompt, "범용 AI 호출");
    }

    @SuppressWarnings("unused")
    private String chatFallback(String prompt, Throwable t) {
        log.warn("[Gemini CircuitBreaker] chat fallback — cause: {}",
                t.getClass().getSimpleName() + ": " + t.getMessage());
        return null;
    }

    /**
     * 마법의 공식 스크리너 결과 분석.
     * 1시간 캐시 — 종목 리스트는 일 단위 변경. 사용자 새로고침마다 Gemini 호출 방지.
     */
    @org.springframework.cache.annotation.Cacheable(
            value = "aiScreenerAnalysis",
            key = "'magic:' + #stocks.![stockCode]",
            condition = "#stocks != null && !#stocks.isEmpty()")
    public String analyzeMagicFormula(List<ScreenerResultDto> stocks) {
        if (stocks == null || stocks.isEmpty()) {
            return "분석할 종목이 없습니다.";
        }

        String stockData = stocks.stream()
                .limit(10)
                .map(s -> String.format(
                        "- %s(%s): PER %.1f, PBR %.1f, ROE %.1f%%, 영업이익률 %.1f%%, 순위 %d위",
                        s.getStockName(), s.getStockCode(),
                        s.getPer() != null ? s.getPer().doubleValue() : 0,
                        s.getPbr() != null ? s.getPbr().doubleValue() : 0,
                        s.getRoe() != null ? s.getRoe().doubleValue() : 0,
                        s.getOperatingMargin() != null ? s.getOperatingMargin().doubleValue() : 0,
                        s.getMagicFormulaRank()
                ))
                .collect(Collectors.joining("\n"));

        String prompt = String.format("""
                당신은 한국 주식시장 전문 애널리스트입니다.

                아래는 '마법의 공식' 스크리너로 선별된 저평가 우량주 목록입니다.
                마법의 공식은 ROE(자기자본이익률)와 영업이익률이 높으면서 PER이 낮은 종목을 찾는 전략입니다.

                [스크리닝 결과 - 상위 종목]
                %s

                위 종목들을 분석하여 다음 내용을 포함해 간결하게 추천해주세요:
                1. 상위 3개 종목에 대한 간단한 투자 포인트
                2. 주의해야 할 리스크 요인
                3. 전반적인 시장 관점에서의 조언

                반드시 한국어로 답변하고, 300자 이내로 요약해주세요.
                """, stockData);

        return callWithFallback(prompt, "마법의 공식 분석");
    }

    /**
     * PEG 스크리너 결과 분석. 1시간 캐시 (analyzeMagicFormula 와 동일 정책).
     */
    @org.springframework.cache.annotation.Cacheable(
            value = "aiScreenerAnalysis",
            key = "'peg:' + #stocks.![stockCode]",
            condition = "#stocks != null && !#stocks.isEmpty()")
    public String analyzePegStocks(List<ScreenerResultDto> stocks) {
        if (stocks == null || stocks.isEmpty()) {
            return "분석할 종목이 없습니다.";
        }

        String stockData = stocks.stream()
                .limit(10)
                .map(s -> String.format(
                        "- %s(%s): PEG %.2f, PER %.1f, EPS성장률 %.1f%%, ROE %.1f%%",
                        s.getStockName(), s.getStockCode(),
                        s.getPeg() != null ? s.getPeg().doubleValue() : 0,
                        s.getPer() != null ? s.getPer().doubleValue() : 0,
                        s.getEpsGrowth() != null ? s.getEpsGrowth().doubleValue() : 0,
                        s.getRoe() != null ? s.getRoe().doubleValue() : 0
                ))
                .collect(Collectors.joining("\n"));

        String prompt = String.format("""
                당신은 한국 주식시장 전문 애널리스트입니다.

                아래는 PEG(PER/EPS성장률) 기준 저평가 성장주 목록입니다.
                PEG가 1 미만이면 성장률 대비 저평가된 종목으로 간주합니다.

                [PEG 스크리닝 결과]
                %s

                위 종목들을 분석하여 다음 내용을 포함해 간결하게 추천해주세요:
                1. 성장성 대비 가장 저평가된 Top 3 종목과 투자 포인트
                2. 성장주 투자 시 주의사항
                3. 포트폴리오 구성 조언

                반드시 한국어로 답변하고, 300자 이내로 요약해주세요.
                """, stockData);

        return callWithFallback(prompt, "PEG 분석");
    }

    /**
     * 턴어라운드 스크리너 결과 분석. 1시간 캐시.
     */
    @org.springframework.cache.annotation.Cacheable(
            value = "aiScreenerAnalysis",
            key = "'turnaround:' + #stocks.![stockCode]",
            condition = "#stocks != null && !#stocks.isEmpty()")
    public String analyzeTurnaroundStocks(List<ScreenerResultDto> stocks) {
        if (stocks == null || stocks.isEmpty()) {
            return "분석할 종목이 없습니다.";
        }

        String stockData = stocks.stream()
                .limit(10)
                .map(s -> String.format(
                        "- %s(%s): %s, 이전순이익 %.0f억 → 현재순이익 %.0f억, PER %.1f",
                        s.getStockName(), s.getStockCode(),
                        "LOSS_TO_PROFIT".equals(s.getTurnaroundType()) ? "흑자전환" : "이익급증",
                        s.getPreviousNetIncome() != null ? s.getPreviousNetIncome().doubleValue() : 0,
                        s.getCurrentNetIncome() != null ? s.getCurrentNetIncome().doubleValue() : 0,
                        s.getPer() != null ? s.getPer().doubleValue() : 0
                ))
                .collect(Collectors.joining("\n"));

        String prompt = String.format("""
                당신은 한국 주식시장 전문 애널리스트입니다.

                아래는 실적 턴어라운드(적자→흑자 전환 또는 이익 급증) 종목 목록입니다.

                [턴어라운드 스크리닝 결과]
                %s

                위 종목들을 분석하여 다음 내용을 포함해 간결하게 추천해주세요:
                1. 가장 주목할 만한 턴어라운드 종목 Top 3와 이유
                2. 턴어라운드 지속 가능성 판단 기준
                3. 턴어라운드 종목 투자 시 주의사항

                반드시 한국어로 답변하고, 300자 이내로 요약해주세요.
                """, stockData);

        return callWithFallback(prompt, "턴어라운드 분석");
    }

    /**
     * AI 종목 추천 분석 (AI 대시보드용)
     * @param stockDataSummary 종목 데이터 요약 문자열
     * @return AI 분석 코멘트
     */
    public String analyzeStockRecommendation(String stockDataSummary) {
        if (stockDataSummary == null || stockDataSummary.isEmpty()) {
            return "분석할 데이터가 없습니다.";
        }

        String prompt = String.format("""
                당신은 한국 주식시장 전문 AI 애널리스트입니다.

                아래는 외국인/기관 연속 순매수 및 실적 데이터 기반으로 선별된 종목입니다.

                [분석 대상 종목]
                %s

                위 종목에 대해 다음을 분석해주세요:
                1. 투자 매력도 (매수/관망/매도 중 하나)
                2. 핵심 매수 근거 (2-3가지)
                3. 주의해야 할 리스크
                4. 적정 목표가 또는 투자 전략

                반드시 한국어로, 200자 이내로 간결하게 답변해주세요.
                """, stockDataSummary);

        return callWithFallback(prompt, "AI 종목 추천");
    }

    /**
     * 종목 상세 페이지 전용 AI 분석
     * 가격/수급/재무/리스크 데이터를 종합하여 매매 전략 리포트 생성
     *
     * @param stockDataSummary 종목 데이터 요약 텍스트
     * @return AI 분석 리포트 (실패 시 null → 규칙기반 폴백)
     */
    public String analyzeStockDetail(String stockDataSummary) {
        if (stockDataSummary == null || stockDataSummary.isEmpty()) {
            return null;
        }

        String prompt = String.format("""
                당신은 한국 주식시장 전문 AI 애널리스트입니다.
                개인 투자자에게 실질적으로 도움이 되는 매매 전략을 제시합니다.

                [종목 실시간 데이터]
                %s

                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                ★ 반드시 지켜야 할 분석 원칙 (위반 시 분석 무효) ★

                ◆ 0. 뉴스 연관성 필터링 (★ 최우선 적용 — 모든 분석의 전제):
                   - 뉴스 목록에서 "분석 대상 종목"과 직접 관련 있는 뉴스만 분석에 사용하라.
                   - 직접 관련 = 종목명, 회사명, 자회사/계열사/모회사명, 해당 종목의 사업 섹터가 뉴스 제목·본문에 등장하는 경우.
                   - 예: 현대건설 분석 시 → "현대건설 수주", "건설업 호황", "현대그룹 계열사" 뉴스 = 관련 뉴스.
                   - 예: 현대건설 분석 시 → "증권주 순익 증가", "반도체 수출 호조", "제약 바이오 임상" 뉴스 = 무관 뉴스 → 분석에서 완전히 제외.
                   - 무관 뉴스를 매수/매도 근거로 사용하면 분석 무효.

                1. 뉴스 감성(Sentiment) 정밀 분류 원칙:
                   - 연관 뉴스를 분석에 사용하기 전, 각 뉴스를 아래 기준으로 감성 분류하라:
                     ▸ 호재: 수주 성공, 매출/영업이익 증가, 흑자 전환, 투자 유치, 신사업 확대, 신소재 개발, 해외 수출 계약, 재건축/재개발 수주, 합병, 배당 확대, 목표가 상향, 신고가 돌파
                     ▸ 악재: 적자, 매출 감소, 소송, 규제 강화, 감사의견 거절, 유상증자(희석), 부채 급증, 신용등급 하락, 대규모 환차손, 목표가 하향
                     ▸ 중립: 인사 이동, 단순 업계 동향, 컨퍼런스 참가 등 주가 영향이 불분명한 뉴스
                   - 분류한 감성을 뒤집지 마라. 호재로 분류했으면 리스크/매도 근거에 절대 넣지 마라.

                2. 객관적 분리 원칙:
                   - "수급 데이터"(외인/기관 매매량)와 "뉴스/이벤트 데이터"는 반드시 독립적으로 평가하라.
                   - 수급이 부정적이더라도 그것을 뉴스의 해석에 영향을 주지 마라.
                   - 뉴스가 긍정적이더라도 그것을 수급 해석에 영향을 주지 마라.

                3. 논리적 모순(Hallucination) 방지:
                   - "투자 유치 → 불확실성 증가", "신사업 확대 → 리스크", "수주 성공 → 매도 근거", "신소재 개발 → 부정적" 같은 호재를 악재로 뒤집는 논리적 모순을 절대 생성하지 마라.
                   - 절대 금지 패턴: 기관/외인이 매도 중이라는 이유만으로 호재 뉴스를 매도 근거에 넣는 행위.

                4. 종합 판단 균형 원칙 (★ 핵심):
                   - 종합 판단은 "수급 신호"와 "뉴스/펀더멘털 신호" 양쪽을 모두 반영해야 한다.
                   - 수급이 부정적이라도 뉴스가 명확한 호재이면 → [매도]가 아닌 [관망] 또는 [매수] 판단을 내려라.
                   - 수급 일시 매도 + 뉴스 호재 = "수급은 단기 매도세이나, 뉴스상 모멘텀은 긍정적이므로 관망 또는 조정 시 매수 기회" 형태로 균형 분석.
                   - [매도] 판단 조건: 수급도 부정적이고 AND 뉴스도 악재일 때만 가능.
                   - 수급만으로 [매도] 판정하고 호재 뉴스를 무시하는 것은 분석 무효.

                5. 긍정 신호 해석 원칙:
                   - "신고가 돌파", "52주 신고가" 등은 강한 매수 신호로 해석하라.
                   - 외인/기관 대량 순매수는 긍정적 수급 신호로 해석하라.
                   - 단, 단기 급등 시 과열 여부(RSI, 괴리율)만 리스크 요인으로 언급 가능하다.

                6. 자회사/계열사 뉴스 연동:
                   - 뉴스 중 자회사/계열사/모회사 관련 내용이 있으면, "자회사(또는 계열사) OO의 호재(또는 악재) 연동"이라고 명시하라.

                7. 비즈니스 확장 뉴스 해석 원칙:
                   - 수주, 신사업, 투자 유치, 해외 계약 등은 수급이 안 좋아도 '호재성 모멘텀'으로 분류하라.
                   - "단기 수급은 부진하나 중장기 수주 모멘텀은 존재함" 형태로 균형 분석.

                8. 시장 지수 상승 뉴스 해석 원칙:
                   - "코스피 신고가", "증시 랠리" 등은 해당 종목에 긍정적 시장 환경으로 해석하라.
                   - 시장 상승 자체를 "과열 우려", "고점 리스크"로 해석하는 것은 금지.
                   - 해당 종목 자체의 과열 지표(RSI 80 이상)만 리스크로 언급 가능.

                9. 날짜 원칙 (★ 환각 방지):
                   - 날짜를 언급할 때는 반드시 위 데이터의 "분석 기준일"을 사용하라. 임의의 과거 날짜를 만들어내지 마라.
                   - "장전 미거래"로 표시된 거래량/시가/고가/저가는 "당일 아직 거래 없음"이라는 뜻이다. 이를 "거래량 0 = 관심 저조"로 해석하지 마라.
                   - 수급이 "최근 N거래일 누적"으로 제공되면 그 값으로 수급을 판단하라(당일 미거래를 수급 부재로 오독 금지).
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                위 원칙을 준수하면서 아래 형식으로 분석해주세요:

                ■ 종합 판단: (매수/관망/매도 중 하나)와 그 이유를 1문장으로
                  → 수급 신호와 뉴스 신호를 모두 고려하여 판단. 한쪽만으로 결론 내리지 말 것.

                ■ 수급 해석: (수급 데이터만 기반으로 작성)
                - 외국인/기관 매매 동향이 의미하는 것
                - 주가 방향과 수급이 일치하는지, 괴리가 있는지

                ■ 뉴스/이벤트 해석: (연관 뉴스만 사용, 무관 뉴스 제외)
                - 각 뉴스의 감성(호재/악재/중립) 명시
                - 종목에 미치는 영향

                ■ 리스크 요인: (실제 악재만 기재)
                - 주의해야 할 핵심 위험 (2-3가지)
                - 수급 매도세는 기재 가능. 호재 뉴스(매출 증가, 수주 성공 등)는 절대 여기에 넣지 마라

                ■ 차트 해석: (기술적 지표 데이터만 기반, 뉴스/수급과 분리)
                - MA5/MA20/MA60, MA 괴리율, 볼린저 범위, 추세 채널(방향·채널 내 위치), 감지된 시그널
                  (예: "정배열", "RSI 과매도")을 종합해 2~3문장으로 해석
                - 추세(상승/횡보/하락) · 이동평균 지지 여부 · 과열/침체 구간 언급
                - 추세 채널 위치가 제공되면 활용: 상단(80%% 이상)=저항권 근접·추격 주의, 하단(20%% 이하)=지지 시험
                  (상승 채널의 하단은 눌림목 관찰 지점). 채널 데이터가 없으면 채널을 언급하지 마라.
                - 단기(1~2주) 관점에서 주의할 레벨(예: "MA20 160,000 지지 확인 필요")

                ■ 매매 전략:
                - 진입/청산 시점 제안
                - 개인 투자자 대응 방안

                반드시 한국어로 작성하세요.
                핵심만 간결하게, 총 900자 이내로 답변해주세요.
                수급과 가격의 괴리(예: 주가 상승인데 외인/기관 매도)가 있으면 반드시 경고해주세요.
                """, stockDataSummary);

        // Gemini API 호출
        String result = callGeminiApiWithRetry(prompt);
        if (result != null && !result.startsWith("Rate Limit") && !result.startsWith("AI 서버")) {
            consecutiveErrors.set(0);
            return result;
        }

        log.warn("[StockDetail AI] Gemini 분석 실패 → null 반환 (규칙기반 폴백 사용)");
        return null;
    }

    /**
     * 주간 매매 일지 분석 — 통계/거래 요약을 받아 마크다운 리포트 생성.
     * 다른 실시간 Gemini 호출과 쿼터 충돌이 잦으므로 전용 patient 호출 사용.
     */
    public String generateWeeklyTradingReport(String tradingSummary) {
        if (tradingSummary == null || tradingSummary.isBlank()) {
            return null;
        }
        String prompt = String.format("""
                당신은 개인 투자자의 매매 코치입니다. 아래 일주일치 한국 주식 매매 데이터를 분석하여
                **냉정하지만 건설적인 코칭 리포트**를 작성하세요.

                === 매매 데이터 ===
                %s

                === 작성 규칙 ===
                - 마크다운 형식 (### 제목, - 불릿)
                - 한국어, 1500자 이내
                - 다음 5개 섹션 순서로:
                  ### 📊 성과 요약
                  ### ✅ 잘한 점 (계속 유지)
                  ### ❌ 못한 점 (개선 필요)
                  ### 📈 종목별 / 시간대별 패턴
                  ### 💡 다음주 행동 제안 (3가지 구체적으로)
                - 데이터로 뒷받침되는 사실만 (가짜 통계 금지)
                - 매매 횟수가 적으면 "데이터 부족" 솔직히 표시
                - 자동매매와 수동 매매가 섞여있으면 별도로 평가
                - 차단된 주문 (blocked) 이 있으면 안전장치 동작 여부 평가
                """, tradingSummary);

        // 전역 quotaResetTime / consecutiveErrors 영향 받지 않는 patient 호출.
        // 주 1회 호출이라 길게 기다려도 OK.
        return callGeminiApiPatient(prompt, 5, 60_000L);
    }

    /**
     * 배치/스케줄 작업용 호출 — 전역 쿼터 카운터 변경 없이 길게 기다리며 재시도.
     * @param maxAttempts 최대 시도 횟수
     * @param baseWaitMs  Rate Limit 시 기본 대기 (지수 백오프 적용)
     */
    private String callGeminiApiPatient(String prompt, int maxAttempts, long baseWaitMs) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("[Gemini patient] API 키 미설정");
            return null;
        }
        // 전체 대기시간 상한 — 호출 스레드가 너무 오래 블록되는 것 방지.
        // 2026-04-24 감사에서 발견: 기존엔 최악 40분+ 블록 가능 → 주간 리포트 스케줄러가
        // 40분 점유하면 그동안 다른 스케줄 틱이 밀려 장애 유발 가능.
        final long MAX_TOTAL_WAIT_MS = 5 * 60 * 1000L; // 5분 상한
        long totalWaited = 0L;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                String result = callGeminiApi(prompt);
                if (result != null) return result;
            } catch (HttpClientErrorException.TooManyRequests e) {
                // 서버가 Retry-After 헤더로 알려준 값이 있으면 그걸 우선, 없으면 지수 백오프.
                // 단 상한을 넘진 않도록 min 처리.
                long serverHint = parseRetryDelay(e);
                long expBackoff = baseWaitMs * (1L << Math.min(attempt, 3)); // 1x, 2x, 4x, 8x, 8x
                long retryDelay = Math.min(serverHint, expBackoff);
                if (totalWaited + retryDelay > MAX_TOTAL_WAIT_MS) {
                    log.warn("[Gemini patient] 누적 대기 상한({}ms) 도달 — 포기",
                            MAX_TOTAL_WAIT_MS);
                    return null;
                }
                log.warn("[Gemini patient] Rate Limit 시도 {}/{} — {}ms 후 재시도 (누적 {}ms, server hint {}ms)",
                        attempt + 1, maxAttempts, retryDelay, totalWaited, serverHint);
                try { Thread.sleep(retryDelay); totalWaited += retryDelay; }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
            } catch (Exception e) {
                log.error("[Gemini patient] 호출 실패 시도 {}/{}: {}",
                        attempt + 1, maxAttempts, e.getMessage());
                // 일시적 네트워크 에러일 수 있어 한 번 더 기다렸다 시도
                if (totalWaited + baseWaitMs > MAX_TOTAL_WAIT_MS) {
                    log.warn("[Gemini patient] 누적 대기 상한 도달 — 포기");
                    return null;
                }
                try { Thread.sleep(baseWaitMs); totalWaited += baseWaitMs; }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
            }
        }
        log.warn("[Gemini patient] {} 회 시도 후에도 실패", maxAttempts);
        return null;
    }

    // ========== AI 시장 예측 (Market Forecast) ==========

    /**
     * AI 시장 예측 생성
     * 현재 시장 데이터 + 수급 + 뉴스를 종합하여 향후 5일간 KOSPI 지수 예측
     *
     * @param marketStatus 현재 시장 상태 DTO
     * @param foreignBuys 외국인 순매수 상위 종목
     * @param instBuys 기관 순매수 상위 종목
     * @param recentNews 최근 뉴스 요약
     * @return 예측 결과 Map (forecasts, scenarios, summary 포함)
     */
    public Map<String, Object> generateMarketForecast(
            MarketTimingDto marketStatus,
            List<InvestorSurgeDto> foreignBuys,
            List<InvestorSurgeDto> instBuys,
            List<NewsSummaryDto> recentNews) {

        // 캐시 확인 (10분 이내)
        if (forecastCache != null && forecastCacheTime != null
                && forecastCacheTime.plusMinutes(FORECAST_CACHE_MINUTES).isAfter(LocalDateTime.now())) {
            log.info("[Market Forecast] 캐시 반환 (캐시 시간: {})", forecastCacheTime);
            return forecastCache;
        }

        if (marketStatus == null || marketStatus.getKospi() == null) {
            throw new RuntimeException("시장 데이터 없음 - KOSPI 데이터를 먼저 수집해주세요");
        }

        // 실지수 주입(§4c: 2700 하드코딩 제거) — indexClose 결측이면 KIS 실지수(0001, §16 지수 단일경로) 폴백, 그마저 없으면 예측 불가.
        double currentIndex = resolveForecastIndex(marketStatus.getKospi().getIndexClose());

        log.info("[Market Forecast] 예측 시작 - KOSPI: {}, 외국인수급: {}건, 기관수급: {}건, 뉴스: {}건",
                currentIndex,
                foreignBuys != null ? foreignBuys.size() : 0,
                instBuys != null ? instBuys.size() : 0,
                recentNews != null ? recentNews.size() : 0);

        String prompt = buildForecastPrompt(marketStatus, currentIndex, foreignBuys, instBuys, recentNews);
        Map<String, Object> schema = buildForecastResponseSchema();

        // 1차: JSON 모드 + responseSchema 호출 (quota 바이패스)
        String response = callGeminiApiForJson(prompt, schema, true);
        if (response != null && !response.isBlank()) {
            log.info("[Market Forecast] Gemini JSON 응답 수신 ({}자): {}", response.length(),
                    response.length() > 200 ? response.substring(0, 200) + "..." : response);
            Map<String, Object> parsed = parseMarketForecast(response, currentIndex);
            if (parsed != null) {
                log.info("[Market Forecast] Gemini JSON 파싱 성공");
                consecutiveErrors.set(0);
                forecastCache = parsed;
                forecastCacheTime = LocalDateTime.now();
                return parsed;
            }
            log.warn("[Market Forecast] JSON 파싱 실패 - 텍스트 모드 재시도");
        } else {
            log.warn("[Market Forecast] Gemini JSON 모드 응답 없음 (null/blank) - 텍스트 모드 재시도");
        }

        // 2차: 텍스트 모드 (responseMimeType 없이) 재시도 - JSON 스키마 없이 호출
        String textResponse = callGeminiApiWithRetry(prompt);
        if (textResponse != null && !textResponse.isBlank()
                && !textResponse.startsWith("Rate Limit") && !textResponse.startsWith("AI 서버")
                && !textResponse.startsWith("AI 분석") && textResponse.contains("{")) {
            log.info("[Market Forecast] Gemini 텍스트 응답 수신 ({}자): {}", textResponse.length(),
                    textResponse.length() > 200 ? textResponse.substring(0, 200) + "..." : textResponse);
            Map<String, Object> parsed = parseMarketForecast(textResponse, currentIndex);
            if (parsed != null) {
                log.info("[Market Forecast] 텍스트 모드 파싱 성공");
                consecutiveErrors.set(0);
                forecastCache = parsed;
                forecastCacheTime = LocalDateTime.now();
                return parsed;
            }
            log.warn("[Market Forecast] 텍스트 모드 파싱도 실패");
        } else {
            log.warn("[Market Forecast] Gemini 텍스트 모드도 응답 없음/비JSON: {}",
                    textResponse != null ? textResponse.substring(0, Math.min(100, textResponse.length())) : "null");
        }

        // Gemini 완전 실패 → 기계적 fallback 예측 반환
        log.warn("[Market Forecast] Gemini 실패 → 기계적 fallback 예측 반환 (KOSPI: {})", currentIndex);
        Map<String, Object> fallback = buildFallbackForecast(currentIndex);
        forecastCache = fallback;
        forecastCacheTime = LocalDateTime.now();
        return fallback;
    }

    /**
     * 시장 예측 기준 KOSPI 지수 해석 — indexClose 실측 우선, 결측이면 KIS 실지수(0001) 폴백.
     * 둘 다 없으면 예외(2700 하드코딩 폴백 제거, §4c: 데이터 없음을 그럴듯한 값으로 위장 금지).
     */
    private double resolveForecastIndex(BigDecimal indexClose) {
        if (indexClose != null) return indexClose.doubleValue();
        BigDecimal kisIndex = fetchKospiIndexQuiet();
        if (kisIndex != null) {
            log.warn("[Market Forecast] indexClose 결측 → KIS 실지수(0001) 폴백: {}", kisIndex);
            return kisIndex.doubleValue();
        }
        throw new RuntimeException("KOSPI 실지수 미수집 — 하드코딩 없이 예측 불가(§4c)");
    }

    /** KOSPI 종합지수(0001) 현재가 — §16 지수 단일경로 getIndexPrice 재사용. 미설정/오류 시 null(호출부가 판단). */
    private BigDecimal fetchKospiIndexQuiet() {
        KoreaInvestmentService kis = kisProvider.getIfAvailable();
        if (kis == null || !kis.isConfigured()) return null;
        try {
            JsonNode resp = kis.getIndexPrice("0001");
            if (resp == null || !resp.has("output")) return null;
            JsonNode out = resp.get("output");
            JsonNode p = out.has("bstp_nmix_prpr") ? out.get("bstp_nmix_prpr") : null;
            if (p == null || p.asText().isEmpty()) return null;
            return new BigDecimal(p.asText().replace(",", ""));
        } catch (Exception e) {
            log.debug("[Market Forecast] KOSPI 실지수 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    /** 시장 예측 기준일 줄 — LLM 날짜 발명 방지(§4c). 순수(테스트 대상). */
    static String forecastDateLine(LocalDate today) {
        return String.format("분석 기준일: %s (향후 5거래일 KOSPI 예측)\n", today);
    }

    /**
     * [시장 현황] 블록 — 장전/결측 0값을 raw 로 주입하지 않고 "미집계"로 정직 표기(§4c).
     * 지수는 실측만(호출부가 KIS 실지수 폴백 보장). 순수(테스트 대상).
     */
    static String buildMarketStatusBlock(double currentIndex, BigDecimal changeRate, BigDecimal adr,
                                         String condition, Integer advCount, Integer decCount, BigDecimal tradingValue) {
        String changeRateStr = changeRate != null ? String.format("%+.2f%%", changeRate.doubleValue()) : "미집계";
        String adrStr = adr != null ? String.format("%.1f", adr.doubleValue()) : "미집계";
        boolean breadthKnown = advCount != null && decCount != null && (advCount > 0 || decCount > 0);
        String breadthStr = breadthKnown
                ? String.format("상승 종목: %d개 / 하락 종목: %d개", advCount, decCount)
                : "상승/하락 종목: 미집계(장전 미거래 또는 미수집)";
        String tradingValueStr = (tradingValue != null && tradingValue.signum() > 0)
                ? String.format("%.0f억원", tradingValue.doubleValue())
                : "미집계(장전 미거래 또는 미수집)";
        return String.format("""
                - KOSPI 지수: %.2f
                - 등락률: %s
                - ADR (등락비율): %s
                - 시장 상태: %s
                - %s
                - 거래대금: %s""",
                currentIndex, changeRateStr, adrStr, condition, breadthStr, tradingValueStr);
    }

    private String buildForecastPrompt(
            MarketTimingDto marketStatus, double currentIndex,
            List<InvestorSurgeDto> foreignBuys, List<InvestorSurgeDto> instBuys,
            List<NewsSummaryDto> recentNews) {

        MarketTimingDto.MarketStatusDto kospi = marketStatus.getKospi();
        String condition = marketStatus.getOverallCondition() != null
                ? marketStatus.getOverallCondition().name() : "NORMAL";
        // [시장 현황] — 장전 0/결측은 raw 0 대신 "미집계"로 정직 표기(§4c). 지수만 실측.
        String marketStatusBlock = buildMarketStatusBlock(
                currentIndex, kospi.getIndexChangeRate(), marketStatus.getCombinedAdr(),
                condition, kospi.getAdvancingCount(), kospi.getDecliningCount(), kospi.getTradingValue());

        // 외국인 수급 텍스트
        String foreignText = "데이터 없음";
        if (foreignBuys != null && !foreignBuys.isEmpty()) {
            foreignText = foreignBuys.stream()
                    .map(s -> String.format("%s %+.0f억",
                            s.getStockName(),
                            s.getNetBuyAmount() != null ? s.getNetBuyAmount().doubleValue() : 0))
                    .collect(Collectors.joining(", "));
        }

        // 기관 수급 텍스트
        String instText = "데이터 없음";
        if (instBuys != null && !instBuys.isEmpty()) {
            instText = instBuys.stream()
                    .map(s -> String.format("%s %+.0f억",
                            s.getStockName(),
                            s.getNetBuyAmount() != null ? s.getNetBuyAmount().doubleValue() : 0))
                    .collect(Collectors.joining(", "));
        }

        // 뉴스 센티먼트 텍스트
        String newsText = "데이터 없음";
        if (recentNews != null && !recentNews.isEmpty()) {
            newsText = recentNews.stream()
                    .map(n -> String.format("[%s] %s",
                            n.getSentimentLabel() != null ? n.getSentimentLabel() : "중립",
                            n.getTitle()))
                    .collect(Collectors.joining("\n"));
        }

        return forecastDateLine(LocalDate.now(KST)) + String.format("""
                당신은 한국 주식시장 전문 애널리스트입니다.
                현재 시장 데이터, 수급 동향, 뉴스 센티먼트를 종합하여 향후 5거래일간 KOSPI 지수 예측을 JSON으로 작성하세요.

                [시장 현황]
                %s

                [외국인 수급 - 순매수 상위]
                %s

                [기관 수급 - 순매수 상위]
                %s

                [뉴스 센티먼트 - 최근 헤드라인]
                %s

                [예측 지침]
                1. 현재 지수(%.2f)를 기준으로 현실적 변동폭 (일일 ±0.5~1.5%%) 적용
                2. 외국인/기관 수급이 강세(대량 순매수)이면 Bull 확률을 높게 설정
                3. 뉴스 센티먼트가 부정적이면 Bear 확률을 높게 설정
                4. Bull/Base/Bear 3개 시나리오 확률 합계는 반드시 100
                5. 근거(reason)는 수급/뉴스 데이터를 인용하여 한국어 50자 이내로 작성
                6. summary는 핵심 판단과 근거를 한국어 100자 이내로 작성
                7. forecasts의 bull/base/bear 값은 반드시 소수점 없는 정수(예: 2750)로 작성
                8. baseIndex는 %.2f로 설정
                9. "미집계"로 표시된 항목은 데이터 없음을 의미하므로 값을 추정/발명하지 말 것

                반드시 JSON만 출력하세요. 설명이나 마크다운 없이 순수 JSON만 출력하세요.
                예시:
                {"baseIndex": 2750.00, "forecasts": [{"day": 1, "bull": 2770, "base": 2755, "bear": 2735}, {"day": 2, "bull": 2790, "base": 2758, "bear": 2720}, {"day": 3, "bull": 2810, "base": 2760, "bear": 2705}, {"day": 4, "bull": 2825, "base": 2762, "bear": 2690}, {"day": 5, "bull": 2840, "base": 2765, "bear": 2680}], "scenarios": {"bull": {"probability": 35, "reason": "외국인 순매수 지속 기대"}, "base": {"probability": 45, "reason": "박스권 등락 전망"}, "bear": {"probability": 20, "reason": "글로벌 리스크 확대 우려"}}, "summary": "외국인 수급 개선과 기관 매수세로 단기 상승 여력 존재하나 글로벌 변동성에 유의 필요"}
                """, marketStatusBlock,
                foreignText, instText, newsText,
                currentIndex, currentIndex);
    }

    /**
     * Gemini responseSchema: 시장 예측 JSON 구조 정의
     */
    private Map<String, Object> buildForecastResponseSchema() {
        // day/bull/base/bear 속성 정의
        Map<String, Object> forecastItemProps = new LinkedHashMap<>();
        forecastItemProps.put("day", Map.of("type", "INTEGER"));
        forecastItemProps.put("bull", Map.of("type", "NUMBER"));
        forecastItemProps.put("base", Map.of("type", "NUMBER"));
        forecastItemProps.put("bear", Map.of("type", "NUMBER"));

        Map<String, Object> forecastItem = new LinkedHashMap<>();
        forecastItem.put("type", "OBJECT");
        forecastItem.put("properties", forecastItemProps);
        forecastItem.put("required", List.of("day", "bull", "base", "bear"));

        // scenario (probability + reason)
        Map<String, Object> scenarioProps = new LinkedHashMap<>();
        scenarioProps.put("probability", Map.of("type", "INTEGER"));
        scenarioProps.put("reason", Map.of("type", "STRING"));

        Map<String, Object> scenarioItem = new LinkedHashMap<>();
        scenarioItem.put("type", "OBJECT");
        scenarioItem.put("properties", scenarioProps);
        scenarioItem.put("required", List.of("probability", "reason"));

        // scenarios (bull/base/bear)
        Map<String, Object> scenariosProps = new LinkedHashMap<>();
        scenariosProps.put("bull", scenarioItem);
        scenariosProps.put("base", scenarioItem);
        scenariosProps.put("bear", scenarioItem);

        Map<String, Object> scenarios = new LinkedHashMap<>();
        scenarios.put("type", "OBJECT");
        scenarios.put("properties", scenariosProps);
        scenarios.put("required", List.of("bull", "base", "bear"));

        // root schema
        Map<String, Object> rootProps = new LinkedHashMap<>();
        rootProps.put("baseIndex", Map.of("type", "NUMBER"));
        rootProps.put("forecasts", Map.of("type", "ARRAY", "items", forecastItem));
        rootProps.put("scenarios", scenarios);
        rootProps.put("summary", Map.of("type", "STRING"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");
        schema.put("properties", rootProps);
        schema.put("required", List.of("baseIndex", "forecasts", "scenarios", "summary"));

        return schema;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMarketForecast(String jsonResponse, double currentIndex) {
        try {
            String json = jsonResponse.trim();

            // ```json ... ``` 마크다운 코드블록 처리
            if (json.startsWith("```")) {
                int startIdx = json.indexOf('{');
                int endIdx = json.lastIndexOf('}');
                if (startIdx >= 0 && endIdx > startIdx) {
                    json = json.substring(startIdx, endIdx + 1);
                }
            }
            // JSON 시작 위치 찾기
            if (!json.startsWith("{")) {
                int startIdx = json.indexOf('{');
                int endIdx = json.lastIndexOf('}');
                if (startIdx >= 0 && endIdx > startIdx) {
                    json = json.substring(startIdx, endIdx + 1);
                } else {
                    log.warn("[Market Forecast] JSON 객체를 찾을 수 없음 - 원본: {}",
                            jsonResponse.substring(0, Math.min(300, jsonResponse.length())));
                    return null;
                }
            }

            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});

            log.info("[Market Forecast] 파싱 결과 키: {}", result.keySet());

            // 기본 검증
            if (!result.containsKey("forecasts") || !result.containsKey("scenarios")) {
                log.warn("[Market Forecast] JSON 필수 필드 누락 - 존재하는 키: {}", result.keySet());
                return null;
            }

            // baseIndex가 없으면 현재 지수 사용
            if (!result.containsKey("baseIndex")) {
                result.put("baseIndex", currentIndex);
            }

            // forecasts 검증 및 정규화 (최소 1개, 숫자 타입 보정)
            Object forecastsObj = result.get("forecasts");
            if (forecastsObj instanceof List) {
                List<?> forecastsList = (List<?>) forecastsObj;
                if (forecastsList.isEmpty()) {
                    log.warn("[Market Forecast] forecasts 배열이 비어있음");
                    return null;
                }
                // 숫자 타입 보정 (Integer → Number로 프론트 호환 보장)
                List<Map<String, Object>> normalized = new ArrayList<>();
                for (Object item : forecastsList) {
                    if (item instanceof Map) {
                        Map<?, ?> m = (Map<?, ?>) item;
                        Map<String, Object> norm = new HashMap<>();
                        norm.put("day", toNumber(m.get("day")));
                        norm.put("bull", toNumber(m.get("bull")));
                        norm.put("base", toNumber(m.get("base")));
                        norm.put("bear", toNumber(m.get("bear")));
                        normalized.add(norm);
                    }
                }
                result.put("forecasts", normalized);
                log.info("[Market Forecast] forecasts {}일 파싱+정규화 완료", normalized.size());
            }

            // scenarios 검증
            Object scenariosObj = result.get("scenarios");
            if (scenariosObj instanceof Map) {
                Map<?, ?> scenariosMap = (Map<?, ?>) scenariosObj;
                log.info("[Market Forecast] scenarios 키: {}", scenariosMap.keySet());
                // probability가 bull+base+bear = 100이 아닌 경우 보정
                try {
                    Map<String, Object> normalizedScenarios = new LinkedHashMap<>();
                    for (String key : List.of("bull", "base", "bear")) {
                        Object s = scenariosMap.get(key);
                        if (s instanceof Map) {
                            Map<?, ?> sm = (Map<?, ?>) s;
                            Map<String, Object> ns = new HashMap<>();
                            ns.put("probability", toNumber(sm.get("probability")));
                            ns.put("reason", sm.get("reason") != null ? sm.get("reason").toString() : "");
                            normalizedScenarios.put(key, ns);
                        }
                    }
                    if (normalizedScenarios.size() == 3) {
                        result.put("scenarios", normalizedScenarios);
                    }
                } catch (Exception e) {
                    log.warn("[Market Forecast] scenarios 정규화 실패 (무시): {}", e.getMessage());
                }
            }

            return result;
        } catch (Exception e) {
            log.error("[Market Forecast] JSON 파싱 예외: {} - 원본(앞 300자): {}",
                    e.getMessage(),
                    jsonResponse != null ? jsonResponse.substring(0, Math.min(300, jsonResponse.length())) : "null");
            return null;
        }
    }

    private Number toNumber(Object obj) {
        if (obj instanceof Number) return (Number) obj;
        if (obj instanceof String) {
            try { return Double.parseDouble((String) obj); }
            catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private Map<String, Object> buildFallbackForecast(double currentIndex) {
        Map<String, Object> result = new HashMap<>();
        result.put("baseIndex", currentIndex);

        List<Map<String, Object>> forecasts = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            Map<String, Object> f = new HashMap<>();
            f.put("day", day);
            f.put("bull", Math.round(currentIndex * (1 + 0.005 * day)));
            f.put("base", Math.round(currentIndex * (1 + 0.001 * day)));
            f.put("bear", Math.round(currentIndex * (1 - 0.005 * day)));
            forecasts.add(f);
        }
        result.put("forecasts", forecasts);

        Map<String, Object> scenarios = new HashMap<>();
        scenarios.put("bull", Map.of("probability", 30, "reason", "외국인 매수 유입 시 상승 가능"));
        scenarios.put("base", Map.of("probability", 50, "reason", "현재 추세 유지 전망"));
        scenarios.put("bear", Map.of("probability", 20, "reason", "글로벌 리스크 확대 시 하락"));
        result.put("scenarios", scenarios);

        result.put("summary", "AI 분석 데이터 부족으로 기본 예측을 제공합니다. 현재 지수 기반 기계적 산출입니다.");
        result.put("fallback", true);

        return result;
    }

    // ========== AI 스코어링 (전략 대시보드용) ==========

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiScoreResult {
        private String stockCode;
        private int aiScore;
        private String aiComment;
        private List<String> themes;
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 전략별 후보 종목 AI 스코어링 (배치)
     * - 10개 후보를 하나의 프롬프트로 전송 → 전략당 1회 API 호출
     * - 실패 시 빈 Map 반환 (graceful degradation)
     *
     * @param candidates 후보 스냅샷 목록 (최대 10개)
     * @param strategyType 전략 유형 (SCALPING, SWING, TURNAROUND, VALUE)
     * @return stockCode → AiScoreResult 매핑
     */
    public Map<String, AiScoreResult> scoreStockCandidates(
            List<AiStrategySnapshot> candidates, String strategyType) {

        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyMap();
        }

        if (apiKey == null || apiKey.isEmpty()) {
            log.debug("[AI Scoring] Gemini API 키 미설정 - AI 스코어링 스킵");
            return Collections.emptyMap();
        }

        // P2-CAT2: AI전략 스냅샷은 저우선(배경 작업) — quota 압박 시 자발적 양보(스킵→기존 폴백).
        // 고우선(재료 분류·StockDetail 사용자 트리거)은 게이트 없이 진행해 rate 슬롯을 양보받는다.
        if (shouldYieldLowPriority(quotaResetTime, consecutiveErrors.get(), LocalDateTime.now())) {
            log.info("[AI Scoring] {} - quota 압박(리셋: {}, 연속에러: {}) → 저우선 양보 (코멘트 없이 진행)",
                    strategyType, quotaResetTime, consecutiveErrors.get());
            return Collections.emptyMap();
        }

        // 전략별 평가 맥락
        String strategyContext = switch (strategyType) {
            case "SCALPING" -> "모멘텀/단기 급등 가능성 (거래량 급증, 체결강도, 단기 수급)";
            case "SWING" -> "밸류에이션 매력도 (ROE, 영업이익률, PER, 재무 건전성)";
            case "TURNAROUND" -> "실적 개선 지속 가능성 (흑자전환 신뢰도, 이익 성장 추세)";
            case "VALUE" -> "성장 잠재력 대비 저평가 정도 (PEG, EPS성장률, ROE)";
            default -> "종합 투자 매력도";
        };

        // 종목 데이터 구성
        StringBuilder stockData = new StringBuilder();
        for (AiStrategySnapshot s : candidates) {
            stockData.append(String.format(
                    "- %s(%s): 현재가 %s원, 등락률 %s%%, 알고리즘점수 %d",
                    s.getStockName(), s.getStockCode(),
                    s.getCurrentPrice() != null ? s.getCurrentPrice().toPlainString() : "N/A",
                    s.getChangeRate() != null ? s.getChangeRate().toPlainString() : "N/A",
                    s.getScore() != null ? s.getScore() : 0
            ));
            if (s.getPer() != null) stockData.append(String.format(", PER %.1f", s.getPer().doubleValue()));
            if (s.getPbr() != null) stockData.append(String.format(", PBR %.1f", s.getPbr().doubleValue()));
            if (s.getRoe() != null) stockData.append(String.format(", ROE %.1f%%", s.getRoe().doubleValue()));
            if (s.getVolumeRatio() != null) stockData.append(String.format(", 거래량비율 %.0f%%", s.getVolumeRatio().doubleValue()));
            if (s.getPeg() != null) stockData.append(String.format(", PEG %.2f", s.getPeg().doubleValue()));
            if (s.getEpsGrowth() != null) stockData.append(String.format(", EPS성장률 %.1f%%", s.getEpsGrowth().doubleValue()));
            if (s.getOperatingMargin() != null) stockData.append(String.format(", 영업이익률 %.1f%%", s.getOperatingMargin().doubleValue()));
            if (s.getTurnaroundType() != null) stockData.append(", 턴어라운드유형: ").append(s.getTurnaroundType());
            stockData.append("\n");
        }

        String prompt = String.format("""
                당신은 한국 주식시장 전문 AI 애널리스트입니다.

                아래 종목들의 '%s' 전략 관점에서 AI 매력도 점수(0~100)와 한줄평을 작성해주세요.

                [평가 기준: %s]

                [점수 스케일]
                - 0~30: 낮음 (투자 매력 부족)
                - 31~50: 보통 (조건부 관심)
                - 51~70: 매력적 (적극 관심)
                - 71~100: 매우 매력적 (강력 추천)

                [후보 종목]
                %s

                반드시 아래 JSON 배열 형식으로만 응답하세요. 다른 텍스트를 포함하지 마세요.
                코멘트는 한국어로 40자 이내로 작성하세요.
                themes는 해당 종목의 핵심 투자 테마를 2~3개 한국어 키워드로 작성하세요 (예: "AI반도체", "수급우량", "실적턴어라운드").

                [{"stockCode": "종목코드", "aiScore": 점수, "aiComment": "한줄평", "themes": ["테마1","테마2"]}, ...]
                """, strategyType, strategyContext, stockData.toString());

        try {
            String response = callGeminiApiForJson(prompt);
            if (response == null || response.isBlank()) {
                log.warn("[AI Scoring] {} - Gemini 응답 없음", strategyType);
                return Collections.emptyMap();
            }

            // JSON 파싱
            String jsonStr = extractJsonArray(response);
            List<AiScoreResult> results = objectMapper.readValue(
                    jsonStr, new TypeReference<List<AiScoreResult>>() {});

            Map<String, AiScoreResult> resultMap = new HashMap<>();
            for (AiScoreResult result : results) {
                if (result.getStockCode() != null && !result.getStockCode().isBlank()) {
                    // 점수 범위 보정
                    result.setAiScore(Math.max(0, Math.min(100, result.getAiScore())));
                    resultMap.put(result.getStockCode(), result);
                }
            }

            log.info("[AI Scoring] {} - {}개 종목 스코어링 완료", strategyType, resultMap.size());
            return resultMap;

        } catch (Exception e) {
            log.warn("[AI Scoring] {} - 스코어링 실패 (graceful degradation): {}", strategyType, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * JSON 배열 추출 (응답에 불필요한 텍스트가 포함된 경우 처리)
     */
    private String extractJsonArray(String response) {
        String trimmed = response.trim();
        // ```json ... ``` 마크다운 코드블록 처리
        if (trimmed.startsWith("```")) {
            int startIdx = trimmed.indexOf('[');
            int endIdx = trimmed.lastIndexOf(']');
            if (startIdx >= 0 && endIdx > startIdx) {
                return trimmed.substring(startIdx, endIdx + 1);
            }
        }
        // 순수 JSON 배열인 경우
        if (trimmed.startsWith("[")) {
            return trimmed;
        }
        // 다른 텍스트가 앞뒤에 있는 경우
        int startIdx = trimmed.indexOf('[');
        int endIdx = trimmed.lastIndexOf(']');
        if (startIdx >= 0 && endIdx > startIdx) {
            return trimmed.substring(startIdx, endIdx + 1);
        }
        return trimmed;
    }

    /**
     * Gemini API 호출 (JSON 응답 전용) - 스키마 없는 버전
     */
    private String callGeminiApiForJson(String prompt) {
        return callGeminiApiForJson(prompt, null, false);
    }

    /**
     * Gemini API 호출 (JSON 응답 전용) - 스키마 지정, quota 체크 포함
     */
    private String callGeminiApiForJson(String prompt, Map<String, Object> responseSchema) {
        return callGeminiApiForJson(prompt, responseSchema, false);
    }

    /**
     * Gemini API 호출 (JSON 응답 전용)
     * - temperature: 0.3 (일관성 높임)
     * - responseMimeType: application/json
     * - responseSchema: 선택적 JSON 스키마 (구조 강제)
     * - bypassQuota: true이면 quotaResetTime 체크 건너뜀 (forecast 등 독립 호출용)
     */
    private String callGeminiApiForJson(String prompt, Map<String, Object> responseSchema, boolean bypassQuota) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("[Gemini JSON] API 키 미설정 - 호출 스킵");
            return null;
        }

        // 쿼터 리셋 시간 체크 (bypassQuota이면 건너뜀)
        if (!bypassQuota && quotaResetTime != null && LocalDateTime.now().isBefore(quotaResetTime)) {
            log.warn("[Gemini JSON] 쿼터 제한 중 (리셋: {}) - 호출 스킵", quotaResetTime);
            return null;
        }

        enforceRateLimit();

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                String url = apiUrl + "?key=" + apiKey;

                Map<String, Object> requestBody = new HashMap<>();

                List<Map<String, Object>> contents = new ArrayList<>();
                Map<String, Object> content = new HashMap<>();
                List<Map<String, String>> parts = new ArrayList<>();
                parts.add(Map.of("text", prompt));
                content.put("parts", parts);
                contents.add(content);
                requestBody.put("contents", contents);

                // JSON 전용 generation config
                Map<String, Object> generationConfig = new HashMap<>();
                generationConfig.put("temperature", 0.3);
                generationConfig.put("maxOutputTokens", 2048);
                generationConfig.put("responseMimeType", "application/json");
                if (responseSchema != null) {
                    generationConfig.put("responseSchema", responseSchema);
                }
                requestBody.put("generationConfig", generationConfig);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

                log.info("[Gemini JSON] API 호출 시작 (시도 {}/{}, 프롬프트 {}자)", attempt + 1, MAX_RETRIES, prompt.length());
                // 슬롯 예약은 enforceRateLimit()/RateLimiter 가 담당(전역 직렬화).

                ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

                log.info("[Gemini JSON] 응답 상태: {}", response.getStatusCode());

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    Map body = response.getBody();
                    List<Map> candidates = (List<Map>) body.get("candidates");
                    if (candidates == null || candidates.isEmpty()) {
                        // finishReason 등 에러 정보 확인
                        log.warn("[Gemini JSON] candidates 비어있음 - 응답 body keys: {}", body.keySet());
                        if (body.containsKey("promptFeedback")) {
                            log.warn("[Gemini JSON] promptFeedback: {}", body.get("promptFeedback"));
                        }
                        return null;
                    }
                    Map candidate = candidates.get(0);
                    if (candidate == null) {
                        log.warn("[Gemini JSON] candidate[0]이 null");
                        return null;
                    }

                    // finishReason 확인
                    String finishReason = candidate.get("finishReason") != null
                            ? candidate.get("finishReason").toString() : "UNKNOWN";
                    log.info("[Gemini JSON] finishReason: {}", finishReason);

                    Map contentMap = (Map) candidate.get("content");
                    if (contentMap != null) {
                        List<Map> partsList = (List<Map>) contentMap.get("parts");
                        if (partsList != null && !partsList.isEmpty()) {
                            String text = (String) partsList.get(0).get("text");
                            if (text != null && !text.isBlank()) {
                                consecutiveErrors.set(0);
                                return text;
                            }
                            log.warn("[Gemini JSON] parts[0].text가 null 또는 빈 문자열");
                        } else {
                            log.warn("[Gemini JSON] parts 리스트 비어있음");
                        }
                    } else {
                        log.warn("[Gemini JSON] content가 null - candidate keys: {}", candidate.keySet());
                    }
                } else {
                    log.warn("[Gemini JSON] 비정상 응답 - 상태: {}, body null: {}",
                            response.getStatusCode(), response.getBody() == null);
                }
                return null;

            } catch (HttpClientErrorException.TooManyRequests e) {
                long baseDelay = parseRetryDelay(e);  // Retry-After 헤더 우선
                // 백오프 cap 60s — 운영 로그에서 시도 3 시 240s 까지 늘어 thread 6분 점유 발견.
                // 서버 hint 가 길면 어차피 같은 사이클에 재시도해도 fail 가능성 높아 cap 적용.
                long retryDelay = Math.min(baseDelay * (1L << attempt), 60_000L);
                consecutiveErrors.incrementAndGet();
                log.warn("[Gemini JSON] Rate Limit (시도 {}/{}) - {}ms 후 재시도",
                        attempt + 1, MAX_RETRIES, retryDelay);

                if (consecutiveErrors.get() >= 3) {
                    quotaResetTime = LocalDateTime.now().plusMinutes(1);
                    log.warn("[Gemini JSON] 연속 Rate Limit 3회 → 1분간 중단");
                    return null;
                }

                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }

            } catch (HttpClientErrorException e) {
                log.error("[Gemini JSON] HTTP 에러 {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
                return null;
            } catch (Exception e) {
                log.error("[Gemini JSON] API 호출 실패: {} - {}", e.getClass().getSimpleName(), e.getMessage());
                return null;
            }
        }

        return null;
    }

    /**
     * Gemini API 호출 (Rate Limit 처리)
     */
    private String callWithFallback(String prompt, String analysisType) {
        // 1. 쿼터 리셋 시간 체크
        if (quotaResetTime != null && LocalDateTime.now().isBefore(quotaResetTime)) {
            log.warn("Gemini 쿼터 제한 중 (리셋: {})", quotaResetTime);
            return "AI 분석 서비스가 일시적으로 사용 불가능합니다. (Gemini Rate Limit)";
        }

        // 1-1. 일일 호출 카운트 + 임계 알림 (호출 직전).
        trackDailyUsage();

        // 2. Gemini API 호출 시도 (재시도 로직 포함)
        String result = callGeminiApiWithRetry(prompt);

        return result;
    }

    /**
     * 일일 호출 카운트 + 80% / 90% 임계 도달 시 텔레그램 risk 알림.
     * 자정 (KST) 에 카운터 + 알림 플래그 리셋. dailyLimit 0/음수면 비활성.
     */
    private void trackDailyUsage() {
        if (dailyLimit <= 0) return;
        LocalDate today = LocalDate.now();
        if (!today.equals(dailyCountDate)) {
            synchronized (this) {
                if (!today.equals(dailyCountDate)) {
                    dailyCount.set(0);
                    dailyCountDate = today;
                    alerted80 = false;
                    alerted90 = false;
                }
            }
        }
        int count = dailyCount.incrementAndGet();
        int ratio = (count * 100) / dailyLimit;
        if (!alerted80 && ratio >= 80) {
            alerted80 = true;
            sendUsageAlert(count, ratio, "⚠️ Gemini 일일 한도 80% 도달");
        }
        if (!alerted90 && ratio >= 90) {
            alerted90 = true;
            sendUsageAlert(count, ratio, "🚨 Gemini 일일 한도 90% 도달 — 비핵심 호출 차단 권장");
        }
    }

    private void sendUsageAlert(int count, int ratio, String title) {
        try {
            TelegramNotificationService telegram = telegramProvider.getIfAvailable();
            if (telegram == null || !telegram.isEnabled()) return;
            telegram.sendRisk(
                    "<b>" + title + "</b>\n\n" +
                    "호출 수: <b>" + count + " / " + dailyLimit + "</b> (" + ratio + "%)\n" +
                    "남은 호출: " + Math.max(0, dailyLimit - count) + "회\n" +
                    "자정(KST) 카운터 리셋. 한도 초과 시 AI 시그널이 빈값으로 응답합니다.");
        } catch (Exception e) {
            log.debug("[Gemini Usage] 텔레그램 알림 실패: {}", e.getMessage());
        }
    }

    /** 외부 노출 — 디버그/통계용. */
    public int getDailyCallCount() { return dailyCount.get(); }
    public int getDailyLimit() { return dailyLimit; }

    /**
     * Gemini API 호출 (지수 백오프 재시도)
     */
    private String callGeminiApiWithRetry(String prompt) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Gemini API 키가 설정되지 않았습니다.");
            return null;
        }

        // 요청 간 최소 간격 유지
        enforceRateLimit();

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                String result = callGeminiApi(prompt);
                consecutiveErrors.set(0); // 성공 시 에러 카운터 리셋
                callOk.increment();
                return result;

            } catch (HttpClientErrorException.TooManyRequests e) {
                long baseDelay = parseRetryDelay(e);  // Retry-After 헤더 우선
                // 지수 백오프: baseDelay * 2^attempt (1x, 2x, 4x). cap 60s 적용 — thread 점유 최소화.
                long retryDelay = Math.min(baseDelay * (1L << attempt), 60_000L);
                consecutiveErrors.incrementAndGet();
                callRateLimit.increment();

                log.warn("Gemini Rate Limit (시도 {}/{}) - {}ms 후 재시도 (지수 백오프)",
                        attempt + 1, MAX_RETRIES, retryDelay);

                // 연속 에러가 많으면 쿼터 리셋 시간 설정 (1분)
                if (consecutiveErrors.get() >= 3) {
                    quotaResetTime = LocalDateTime.now().plusMinutes(1);
                    log.warn("연속 Rate Limit 3회 → {}까지 Gemini 일시 중단", quotaResetTime);
                    return null;
                }

                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }

            } catch (Exception e) {
                callFail.increment();
                log.error("Gemini API 호출 실패: {}", e.getMessage());
                return null;
            }
        }

        return "Rate Limit 초과로 분석을 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요.";
    }

    /**
     * Gemini API 직접 호출
     */
    private String callGeminiApi(String prompt) {
        String url = apiUrl + "?key=" + apiKey;

        // Request body 구성
        Map<String, Object> requestBody = new HashMap<>();

        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> content = new HashMap<>();
        List<Map<String, String>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        content.put("parts", parts);
        contents.add(content);
        requestBody.put("contents", contents);

        // Generation config
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("temperature", 0.7);
        generationConfig.put("maxOutputTokens", 1024);
        requestBody.put("generationConfig", generationConfig);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        log.info("Gemini API 호출 시작");
        // 슬롯 예약은 enforceRateLimit()/RateLimiter 가 담당(전역 직렬화).

        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            Map body = response.getBody();
            List<Map> candidates = (List<Map>) body.get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                Map candidate = candidates.get(0);
                Map contentMap = (Map) candidate.get("content");
                if (contentMap != null) {
                    List<Map> partsList = (List<Map>) contentMap.get("parts");
                    if (partsList != null && !partsList.isEmpty()) {
                        String text = (String) partsList.get(0).get("text");
                        log.info("Gemini API 응답 수신 완료");
                        return text;
                    }
                }
            }
        }

        log.warn("Gemini API 응답 파싱 실패");
        return "AI 분석 결과를 가져오는데 실패했습니다.";
    }


    /**
     * 요청 간 최소 간격 유지 — 전역 직렬화 게이트 경유(모든 호출자 공유).
     */
    private void enforceRateLimit() {
        rateLimiter.acquire();
    }

    /**
     * 저우선(배경 스냅샷) 호출의 quota 압박 시 양보 판정 — <b>순수 함수(테스트 대상)</b>.
     * 압박 = quotaResetTime 활성(아직 리셋 전) OR 최근 rate limit 에러 잔존(consecutiveErrors &gt; 0).
     * 저우선만 이 판정으로 스킵하고, 고우선(재료·사용자 트리거)은 기존 quotaResetTime 게이트만 탄다.
     * 압박 해제(리셋 경과 + 에러 0)면 false — 저우선도 정상 진행(영구 차단 아님).
     */
    static boolean shouldYieldLowPriority(LocalDateTime quotaResetTime, int consecutiveErrors, LocalDateTime now) {
        if (quotaResetTime != null && now.isBefore(quotaResetTime)) return true;
        return consecutiveErrors > 0;
    }

    /**
     * 전역 요청 간격 게이트 — 순수 계산({@link #computeWaitMs}) + synchronized 슬롯 예약.
     * acquire() 가 synchronized 라 여러 스레드가 동시에 통과 못 하고 FIFO 로 직렬화되며 각자 최소 간격만큼
     * 벌어진다(무료 티어 RPM 초과 버스트 방지). 락을 쥔 채 sleep 하는 게 곧 스로틀(의도된 동작).
     */
    static final class RateLimiter {
        private final long minIntervalMs;
        private long lastAcquiredMs = 0L;

        RateLimiter(long minIntervalMs) { this.minIntervalMs = minIntervalMs; }

        synchronized void acquire() {
            long wait = computeWaitMs(lastAcquiredMs, System.currentTimeMillis(), minIntervalMs);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastAcquiredMs = System.currentTimeMillis();
        }

        /** 마지막 획득 이후 경과와 최소 간격으로 대기 ms 계산(순수). 첫 호출(last=0)은 0. */
        static long computeWaitMs(long lastAcquiredMs, long nowMs, long minIntervalMs) {
            if (lastAcquiredMs <= 0L) return 0L;
            long elapsed = nowMs - lastAcquiredMs;
            return elapsed >= minIntervalMs ? 0L : minIntervalMs - elapsed;
        }
    }

    /**
     * 에러 메시지에서 retry delay 파싱
     */
    private long parseRetryDelay(String errorMessage) {
        if (errorMessage != null) {
            Matcher matcher = RETRY_DELAY_PATTERN.matcher(errorMessage);
            if (matcher.find()) {
                try {
                    double seconds = Double.parseDouble(matcher.group(1));
                    return (long) (seconds * 1000) + 1000; // 여유 1초 추가
                } catch (NumberFormatException e) {
                    // 파싱 실패 시 기본값 사용
                }
            }
        }
        return DEFAULT_RETRY_DELAY_MS;
    }

    /**
     * 429 응답에서 retry 대기 시간 계산.
     * 우선순위:
     *   1) HTTP Retry-After 헤더 (RFC 7231 — 초 단위 숫자 지원, HTTP-date 는 스킵)
     *   2) Gemini 응답 본문의 retryDelay 필드 (기존 parseRetryDelay(String))
     *   3) DEFAULT_RETRY_DELAY_MS
     * 헤더를 우선하는 이유: 서버가 정확히 알려주는 값이라 고정 백오프보다 효율적.
     */
    private long parseRetryDelay(org.springframework.web.client.HttpClientErrorException e) {
        org.springframework.http.HttpHeaders headers = e.getResponseHeaders();
        if (headers != null) {
            String retryAfter = headers.getFirst(org.springframework.http.HttpHeaders.RETRY_AFTER);
            if (retryAfter != null && !retryAfter.isBlank()) {
                try {
                    long seconds = Long.parseLong(retryAfter.trim());
                    // 0 이하/비정상 값 방어 + 여유 500ms
                    return Math.max(1000L, seconds * 1000L) + 500L;
                } catch (NumberFormatException ignored) {
                    // HTTP-date 형식이면 본문 파싱으로 fallback
                }
            }
        }
        return parseRetryDelay(e.getMessage());
    }

    /**
     * API 키 설정 여부 확인
     */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty();
    }

    /**
     * 현재 Rate Limit 상태 조회
     */
    public Map<String, Object> getRateLimitStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("available", isAvailable());
        status.put("consecutiveErrors", consecutiveErrors.get());
        status.put("quotaResetTime", quotaResetTime);
        status.put("minRequestIntervalMs", MIN_REQUEST_INTERVAL_MS);   // 전역 rate 게이트 간격
        return status;
    }
}
