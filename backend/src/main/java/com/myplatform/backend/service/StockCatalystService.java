package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.RiskAnalysisDto.NewsItem;
import com.myplatform.backend.entity.StockCatalyst;
import com.myplatform.backend.entity.StockCatalyst.CatalystType;
import com.myplatform.backend.entity.StockCatalyst.Direction;
import com.myplatform.backend.repository.StockCatalystRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 종목 재료(catalyst) 분류 — V31.
 *
 * 파이프라인: 네이버 뉴스(NaverSearchService, 최근 7일) → Gemini 분류(유형+방향+한줄요약)
 * → stock_catalyst 일캐시. 같은 (종목, 날짜)는 하루 1회만 Gemini 를 호출한다.
 *
 * 설계 원칙:
 *  - 산식 미편입 — 배지 표시 + 시그널 스냅샷(재료별 적중률 검증)용. 검증 후 편입 검토.
 *  - best-effort — 네이버/Gemini 미설정·장애 시 null 반환(캐시 안 함), 호출측은 배지 생략.
 *  - "재료 없음"(NONE)도 캐시 — 뉴스가 잠잠한 종목에 반복 Gemini 호출 방지.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StockCatalystService {

    private final StockCatalystRepository repository;
    // 미설정 환경(로컬 테스트 등)에서도 컨텍스트 로딩 가능하도록 ObjectProvider.
    private final ObjectProvider<NaverSearchService> naverProvider;
    private final ObjectProvider<GeminiService> geminiProvider;
    private final ObjectProvider<TelegramNotificationService> telegramProvider;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_NEWS_FOR_PROMPT = 5;
    /** 배치 1콜당 종목 수 — 너무 크면 프롬프트↑·종목 혼동 품질↓. 초과분은 5씩 청킹. */
    private static final int BATCH_SIZE = 5;

    /**
     * 오늘 기준 재료 조회 — 캐시 우선, 없으면 뉴스 수집 + Gemini 분류.
     *
     * @param stockName 뉴스 검색용 종목명. blank 면 캐시 lookup 만 수행 (신규 분류 불가).
     * @return 분류 결과. 분류 불가(뉴스/Gemini 미가용)면 null — 호출측은 배지 생략.
     */
    @Transactional
    public StockCatalyst getCatalyst(String stockCode, String stockName) {
        if (stockCode == null || stockCode.isBlank()) return null;
        LocalDate today = LocalDate.now();

        Optional<StockCatalyst> cached = repository.findByStockCodeAndCatalystDate(stockCode, today);
        if (cached.isPresent()) return cached.get();
        if (stockName == null || stockName.isBlank()) return null;

        NaverSearchService naver = naverProvider.getIfAvailable();
        GeminiService gemini = geminiProvider.getIfAvailable();
        if (naver == null || gemini == null) return null;
        // 뉴스 소스(네이버) 미가용 = 입력 파이프라인 다운. 이때 "뉴스 0건"을 NONE(재료 없음)으로
        // 캐시하면 데이터 없음을 그럴듯한 값으로 위장(§4c) + 소스 복구돼도 하루종일 재시도 못 함.
        // → null 반환(캐시 안 함)으로 다음 기회에 재분류. (2026-07-01 배선/인코딩 장애로 7일 NONE 재발 방지)
        if (!naver.isAvailable()) {
            log.debug("[Catalyst] 뉴스 소스 미가용 → 분류 스킵(캐시 안 함) ({})", stockCode);
            return null;
        }

        try {
            List<NewsItem> news = naver.searchStockNews(stockName);
            if (news == null || news.isEmpty()) {
                // 뉴스 0건 = 재료 없음 — NONE 캐시 (재호출 방지). 소스는 가용(위 가드 통과)이라 진짜 '뉴스 없음'.
                return save(stockCode, stockName, today, CatalystType.NONE, Direction.NONE, null, null);
            }

            String response = gemini.chat(buildPrompt(stockName, news));
            if (response == null) return null; // Gemini 장애(circuit open) — 캐시 없이 다음 기회에 재시도

            ParsedCatalyst parsed = parseCatalystResponse(response);
            if (parsed == null) {
                log.debug("[Catalyst] Gemini 응답 파싱 실패 ({}): {}", stockCode,
                        response.length() > 120 ? response.substring(0, 120) : response);
                return null;
            }
            StockCatalyst saved = save(stockCode, stockName, today, parsed.type, parsed.direction,
                    truncate(parsed.headline, 300), truncate(parsed.summary, 500));
            // 신규 분류 시에만 알림 — 일캐시 덕에 종목당 하루 최대 1회 (캐시 히트 경로는 알림 없음).
            notifyIfMeaningful(saved);
            return saved;
        } catch (Exception e) {
            log.debug("[Catalyst] 분류 실패 ({}): {}", stockCode, e.getMessage());
            return null;
        }
    }

    /** 배치 분류 참조 — 종목코드 + 종목명(뉴스 검색용). */
    public record StockRef(String code, String name) {}
    /** 배치 중간표현 — 종목명 + 수집된 뉴스. */
    static record StockNews(String name, List<NewsItem> news) {}

    /**
     * 여러 종목 재료를 <b>1 Gemini 콜/배치</b>로 분류(워밍·일괄용, P2-CAT1). 단건 {@link #getCatalyst} 는
     * on-demand 그대로. RPM 실질 1/N(무료 티어 병목 완화). 5종목 초과는 {@value #BATCH_SIZE}씩 청킹(각 1콜).
     * @return 신규 저장(분류)된 종목 수.
     */
    /** 배치 분류(워밍용) — <b>알림 억제</b>. 워밍은 선제 캐시 채움이라 일괄 알림(최대 25건)은 스팸 → 온디맨드 단건({@link #getCatalyst})만 알림. */
    public int classifyBatch(List<StockRef> refs) {
        return classifyBatch(refs, false);
    }

    /** @param notify 신규 유의미 재료 텔레그램 알림 여부(워밍=false / 사용자 발견=true). */
    public int classifyBatch(List<StockRef> refs, boolean notify) {
        if (refs == null || refs.isEmpty()) return 0;
        int classified = 0, geminiCalls = 0;
        for (int i = 0; i < refs.size(); i += BATCH_SIZE) {
            Counts c = classifyOneBatch(refs.subList(i, Math.min(i + BATCH_SIZE, refs.size())), notify);
            classified += c.classified();
            geminiCalls += c.geminiCalls();
        }
        // 실제 Gemini 콜 수 정직 표기 — 전원 캐시 히트면 0콜. (추정치 아님)
        log.info("[Catalyst] 배치 분류 총계 — 요청 {}종목, Gemini {}콜, 신규분류 {}건", refs.size(), geminiCalls, classified);
        return classified;
    }

    /** 배치 처리 결과 — 신규분류 수 + 실제 Gemini 콜 수(로그 정직용). */
    private record Counts(int classified, int geminiCalls) {}

    /** 한 배치(≤{@value #BATCH_SIZE}종목) 처리 — 캐시미스+뉴스수집 → 1 Gemini 콜 → 배열 파싱 → 개별 저장. */
    private Counts classifyOneBatch(List<StockRef> refs, boolean notify) {
        LocalDate today = LocalDate.now();
        NaverSearchService naver = naverProvider.getIfAvailable();
        GeminiService gemini = geminiProvider.getIfAvailable();
        // §4c: 소스(네이버) 다운이면 NONE 위장 캐시 금지 — 스킵(다음 기회 재시도).
        if (naver == null || gemini == null || !naver.isAvailable()) return new Counts(0, 0);

        // 1. 캐시 미스 + 뉴스 수집. 뉴스 있는 종목만 배치 프롬프트로. 뉴스 0건은 개별 NONE(소스 가용=진짜 없음).
        LinkedHashMap<String, StockNews> pending = new LinkedHashMap<>();
        for (StockRef ref : refs) {
            if (ref == null || ref.code() == null || ref.code().isBlank()
                    || ref.name() == null || ref.name().isBlank()) continue;
            if (repository.findByStockCodeAndCatalystDate(ref.code(), today).isPresent()) continue;
            List<NewsItem> news;
            try { news = naver.searchStockNews(ref.name()); }
            catch (Exception e) { continue; }   // 뉴스 조회 실패 → 미캐시(재시도)
            if (news == null || news.isEmpty()) {
                save(ref.code(), ref.name(), today, CatalystType.NONE, Direction.NONE, null, null);
                continue;
            }
            pending.put(ref.code(), new StockNews(ref.name(), news));
        }
        if (pending.isEmpty()) return new Counts(0, 0);   // 전원 캐시히트/뉴스없음 → Gemini 콜 0

        // 2. 1 Gemini 콜(rate limiter 1 슬롯)로 배치 분류 — 아래부터 실제 콜 1회 발생.
        String response;
        try { response = gemini.chat(buildBatchPrompt(pending)); }
        catch (Exception e) { log.debug("[Catalyst] 배치 Gemini 실패: {}", e.getMessage()); return new Counts(0, 1); }
        if (response == null) return new Counts(0, 1);   // circuit open → 미캐시(재시도)

        // 3. 배열 파싱 → 유효 원소만 개별 저장(실패 원소는 미캐시=재시도)
        Map<String, ParsedCatalyst> parsed = parseBatchResponse(response, pending.keySet());
        int saved = 0;
        for (Map.Entry<String, ParsedCatalyst> e : parsed.entrySet()) {
            StockNews sn = pending.get(e.getKey());
            if (sn == null) continue;
            ParsedCatalyst p = e.getValue();
            StockCatalyst rec = save(e.getKey(), sn.name(), today, p.type(), p.direction(),
                    truncate(p.headline(), 300), truncate(p.summary(), 500));
            if (notify) notifyIfMeaningful(rec);   // 워밍(false)은 알림 억제 — 온디맨드만 알림
            saved++;
        }
        log.info("[Catalyst] 배치 분류 — 요청 {}건, 뉴스有 {}건, 저장 {}건 (Gemini 1콜)",
                refs.size(), pending.size(), saved);
        return new Counts(saved, 1);
    }

    private StockCatalyst save(String stockCode, String stockName, LocalDate date,
                               CatalystType type, Direction direction, String headline, String summary) {
        try {
            return repository.save(StockCatalyst.builder()
                    .stockCode(stockCode)
                    .stockName(stockName)
                    .catalystDate(date)
                    .catalystType(type)
                    .direction(direction)
                    .headline(headline)
                    .summary(summary)
                    .build());
        } catch (Exception e) {
            // 동시 요청 unique 충돌 등 — 기존 행 반환 시도
            return repository.findByStockCodeAndCatalystDate(stockCode, date).orElse(null);
        }
    }

    /**
     * 재료 텔레그램 알림 — 호재는 시그널 채널, 악재는 리스크 채널. 중립/없음은 무알림.
     * best-effort: 텔레그램 미설정/발송 실패가 분류 저장에 영향 주지 않음.
     */
    private void notifyIfMeaningful(StockCatalyst catalyst) {
        if (catalyst == null) return;
        Direction d = catalyst.getDirection();
        if (d != Direction.POSITIVE && d != Direction.NEGATIVE) return;
        TelegramNotificationService telegram = telegramProvider.getIfAvailable();
        if (telegram == null) return;
        try {
            String message = buildAlertMessage(catalyst);
            if (d == Direction.POSITIVE) {
                telegram.sendSignal(message);
            } else {
                telegram.sendRisk(message);
            }
        } catch (Exception e) {
            log.debug("[Catalyst] 텔레그램 알림 실패 ({}): {}", catalyst.getStockCode(), e.getMessage());
        }
    }

    /** 알림 메시지 — 순수 함수 (테스트 대상). 기존 알림 포맷(HTML + 구분선 + 봇 서명) 준수. */
    static String buildAlertMessage(StockCatalyst c) {
        boolean positive = c.getDirection() == Direction.POSITIVE;
        StringBuilder sb = new StringBuilder();
        sb.append(positive ? "<b>🔥 재료 포착 (호재)</b>\n\n" : "<b>⚠️ 재료 경보 (악재)</b>\n\n");
        sb.append("📊 <b>").append(c.getStockName() != null ? c.getStockName() : c.getStockCode())
                .append("</b> (").append(c.getStockCode()).append(")\n");
        sb.append("🏷 유형: <b>").append(com.myplatform.backend.dto.StockCatalystDto.labelOf(c.getCatalystType()))
                .append("</b>\n");
        if (c.getSummary() != null && !c.getSummary().isBlank()) {
            sb.append("📝 ").append(c.getSummary()).append('\n');
        }
        if (c.getHeadline() != null && !c.getHeadline().isBlank()) {
            sb.append("📰 ").append(c.getHeadline()).append('\n');
        }
        sb.append("\nℹ️ 뉴스 기반 자동 분류 — 산식 미반영, 직접 확인 후 판단하세요.\n");
        sb.append("━━━━━━━━━━━━━━━━\n");
        sb.append("🤖 MyPlatform 재료 알림");
        return sb.toString();
    }

    /**
     * Gemini 분류 프롬프트 고정 프리픽스(지시/JSON형식/어휘) — 변동부(종목·뉴스) 앞에 둬 프롬프트
     * 프리픽스 캐시 후보로 만든다(토큰 비용↓). ⚠ 무료 티어의 병목은 RPM(요청 수)이라 캐시는 비용만
     * 줄이고 호출 수는 안 줄인다 — 429 완화는 전역 rate limiter/호출수 감축이 담당.
     * 유형/방향 어휘는 CatalystType/Direction enum 과 동기.
     */
    private static final String PROMPT_PREFIX =
            "한국 상장사의 최근 뉴스 제목들에서 주가에 영향을 줄 핵심 '재료' 하나를 골라 분류하라. "
            + "유의미한 재료가 없으면 NONE.\n"
            + "반드시 아래 JSON 형식으로만 답하라 (설명 금지):\n"
            + "{\"type\":\"ORDER_WIN|EARNINGS|MNA|NEW_BUSINESS|REGULATION|LITIGATION|GOVERNANCE|OTHER|NONE\","
            + "\"direction\":\"POSITIVE|NEGATIVE|NEUTRAL|NONE\","
            + "\"headline\":\"근거 뉴스 제목 (NONE 이면 빈 문자열)\","
            + "\"summary\":\"한 줄 요약 (한국어, 40자 이내)\"}\n"
            + "type 의미: ORDER_WIN=수주/공급계약, EARNINGS=실적/가이던스, MNA=인수합병/지분, "
            + "NEW_BUSINESS=신사업/신제품/기술, REGULATION=규제/정책, LITIGATION=소송/제재, "
            + "GOVERNANCE=지배구조/자사주/배당, OTHER=기타 재료, NONE=재료 없음.\n";

    /** Gemini 분류 프롬프트 — 고정 프리픽스 + 변동부(종목명 + 뉴스 제목 최대 5건). */
    static String buildPrompt(String stockName, List<NewsItem> news) {
        StringBuilder sb = new StringBuilder(PROMPT_PREFIX);
        sb.append("\n종목: '").append(stockName).append("'\n뉴스 제목:\n");
        int i = 1;
        for (NewsItem item : news) {
            if (i > MAX_NEWS_FOR_PROMPT) break;
            sb.append(i++).append(". ").append(item.getTitle()).append('\n');
        }
        return sb.toString();
    }

    /** 배치 프롬프트 프리픽스 — N종목을 1콜로. code 키로 JSON 배열 반환 요구(RPM 실질↓, P2-CAT1). */
    private static final String BATCH_PROMPT_PREFIX =
            "여러 한국 상장사의 최근 뉴스 제목이다. 종목마다 주가에 영향을 줄 핵심 '재료' 하나를 분류하라(없으면 NONE).\n"
            + "반드시 아래 JSON 배열로만 답하라 (설명 금지). 각 종목당 원소 하나, code 는 각 종목의 code= 값 그대로:\n"
            + "[{\"code\":\"종목코드\",\"type\":\"ORDER_WIN|EARNINGS|MNA|NEW_BUSINESS|REGULATION|LITIGATION|GOVERNANCE|OTHER|NONE\","
            + "\"direction\":\"POSITIVE|NEGATIVE|NEUTRAL|NONE\",\"headline\":\"근거 뉴스 제목 (NONE 이면 빈 문자열)\","
            + "\"summary\":\"한 줄 요약 (한국어, 40자 이내)\"}]\n"
            + "type 의미: ORDER_WIN=수주/공급계약, EARNINGS=실적/가이던스, MNA=인수합병/지분, "
            + "NEW_BUSINESS=신사업/신제품/기술, REGULATION=규제/정책, LITIGATION=소송/제재, "
            + "GOVERNANCE=지배구조/자사주/배당, OTHER=기타 재료, NONE=재료 없음.\n";

    /** 배치 프롬프트 — 고정 프리픽스 + 종목별(code=…) 뉴스 블록. 순수 함수(테스트 대상). */
    static String buildBatchPrompt(Map<String, StockNews> pending) {
        StringBuilder sb = new StringBuilder(BATCH_PROMPT_PREFIX);
        int idx = 1;
        for (Map.Entry<String, StockNews> e : pending.entrySet()) {
            sb.append("\n[").append(idx++).append("] ").append(e.getValue().name())
              .append(" (code=").append(e.getKey()).append(")\n");
            int n = 1;
            for (NewsItem item : e.getValue().news()) {
                if (n > MAX_NEWS_FOR_PROMPT) break;
                sb.append("  ").append(n++).append(". ").append(item.getTitle()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 배치 JSON 배열 응답 파싱 — code→ParsedCatalyst. 순수 함수(테스트 대상).
     * <b>개별 폴백</b>: 유효 원소만 담고 실패/누락/미요청 code 는 스킵(호출측이 미캐시→재시도). 배열 자체가
     * 깨지면 빈 맵(전원 재시도). 같은 code 중복은 첫 것만. requestedCodes 밖 code 는 무시(방어).
     */
    static Map<String, ParsedCatalyst> parseBatchResponse(String response, Set<String> requestedCodes) {
        Map<String, ParsedCatalyst> out = new LinkedHashMap<>();
        if (response == null || response.isBlank()) return out;
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end <= start) return out;
        try {
            JsonNode arr = MAPPER.readTree(response.substring(start, end + 1));
            if (!arr.isArray()) return out;
            for (JsonNode node : arr) {
                String code = node.path("code").asText("").trim();
                if (code.isEmpty() || !requestedCodes.contains(code) || out.containsKey(code)) continue;
                ParsedCatalyst p = parseNode(node);
                if (p != null) out.put(code, p);   // 실패 원소는 스킵 = 미캐시(재시도)
            }
        } catch (Exception e) {
            return new LinkedHashMap<>();   // 배열 파싱 전체 실패 → 전원 재시도
        }
        return out;
    }

    /**
     * Gemini JSON 응답 파싱 — 순수 함수 (테스트 대상).
     * ```json 펜스 허용, 본문 중 첫 { ... } 블록 추출. 유효하지 않은 type/direction 이면 null.
     * type=NONE 이면 direction 도 NONE 으로 강제 (집계 일관성).
     */
    static ParsedCatalyst parseCatalystResponse(String response) {
        if (response == null || response.isBlank()) return null;
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return parseNode(MAPPER.readTree(response.substring(start, end + 1)));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 단일 JSON 노드 → ParsedCatalyst (단건/배치 공유). 유효하지 않은 type/direction 이면 null.
     * type=NONE 이면 direction 도 NONE 으로 강제 (집계 일관성). 빈 문자열 headline/summary 는 null 정규화.
     */
    private static ParsedCatalyst parseNode(JsonNode node) {
        if (node == null) return null;
        try {
            CatalystType type = CatalystType.valueOf(node.path("type").asText().trim());
            Direction direction = type == CatalystType.NONE
                    ? Direction.NONE
                    : Direction.valueOf(node.path("direction").asText().trim());
            String headline = node.path("headline").asText("");
            String summary = node.path("summary").asText("");
            return new ParsedCatalyst(type, direction,
                    headline.isBlank() ? null : headline,
                    summary.isBlank() ? null : summary);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 파싱 결과 홀더 — 엔티티 저장 전 중간 표현. */
    record ParsedCatalyst(CatalystType type, Direction direction, String headline, String summary) {}
}
