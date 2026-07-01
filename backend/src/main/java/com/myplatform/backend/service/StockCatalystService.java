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
import java.util.List;
import java.util.Optional;

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

    /** Gemini 분류 프롬프트 — JSON 강제. 유형/방향 어휘는 CatalystType/Direction enum 과 동기. */
    static String buildPrompt(String stockName, List<NewsItem> news) {
        StringBuilder sb = new StringBuilder();
        sb.append("다음은 한국 상장사 '").append(stockName).append("' 의 최근 뉴스 제목들이다.\n");
        sb.append("이 중 주가에 영향을 줄 핵심 '재료' 하나를 골라 분류하라. 유의미한 재료가 없으면 NONE.\n\n");
        int i = 1;
        for (NewsItem item : news) {
            if (i > MAX_NEWS_FOR_PROMPT) break;
            sb.append(i++).append(". ").append(item.getTitle()).append('\n');
        }
        sb.append("\n반드시 아래 JSON 형식으로만 답하라 (설명 금지):\n");
        sb.append("{\"type\":\"ORDER_WIN|EARNINGS|MNA|NEW_BUSINESS|REGULATION|LITIGATION|GOVERNANCE|OTHER|NONE\",");
        sb.append("\"direction\":\"POSITIVE|NEGATIVE|NEUTRAL|NONE\",");
        sb.append("\"headline\":\"근거 뉴스 제목 (NONE 이면 빈 문자열)\",");
        sb.append("\"summary\":\"한 줄 요약 (한국어, 40자 이내)\"}\n");
        sb.append("type 의미: ORDER_WIN=수주/공급계약, EARNINGS=실적/가이던스, MNA=인수합병/지분, ");
        sb.append("NEW_BUSINESS=신사업/신제품/기술, REGULATION=규제/정책, LITIGATION=소송/제재, ");
        sb.append("GOVERNANCE=지배구조/자사주/배당, OTHER=기타 재료, NONE=재료 없음.");
        return sb.toString();
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
            JsonNode node = MAPPER.readTree(response.substring(start, end + 1));
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
