package com.myplatform.backend.service;

import com.myplatform.backend.dto.RiskAnalysisDto.NewsItem;
import com.myplatform.backend.entity.StockCatalyst;
import com.myplatform.backend.entity.StockCatalyst.CatalystType;
import com.myplatform.backend.entity.StockCatalyst.Direction;
import com.myplatform.backend.repository.StockCatalystRepository;
import com.myplatform.backend.service.StockCatalystService.ParsedCatalyst;
import com.myplatform.backend.service.StockCatalystService.StockNews;
import com.myplatform.backend.service.StockCatalystService.StockRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 재료 분류 (V31) — Gemini 응답 파싱 순수 함수 + 텔레그램 알림 라우팅 테스트.
 *
 * Gemini 는 JSON 펜스/설명 문구를 섞어 답하는 경우가 있어 파서가 관용적이어야 하고,
 * enum 어휘 밖 응답은 null (캐시 안 함 → 다음 기회 재시도) 이어야 한다.
 * 알림: 호재→시그널 채널 / 악재→리스크 채널 / 중립·없음→무알림 / 캐시 히트→무알림.
 */
@ExtendWith(MockitoExtension.class)
class StockCatalystServiceTest {

    @Mock private StockCatalystRepository repository;
    @Mock private ObjectProvider<NaverSearchService> naverProvider;
    @Mock private ObjectProvider<GeminiService> geminiProvider;
    @Mock private ObjectProvider<TelegramNotificationService> telegramProvider;
    @Mock private NaverSearchService naver;
    @Mock private GeminiService gemini;
    @Mock private TelegramNotificationService telegram;

    @Test
    @DisplayName("정상 JSON → 유형/방향/제목/요약 파싱")
    void parse_validJson() {
        ParsedCatalyst p = StockCatalystService.parseCatalystResponse(
                "{\"type\":\"ORDER_WIN\",\"direction\":\"POSITIVE\"," +
                "\"headline\":\"OO전자 2조원 수주\",\"summary\":\"대형 공급계약 체결\"}");

        assertThat(p).isNotNull();
        assertThat(p.type()).isEqualTo(CatalystType.ORDER_WIN);
        assertThat(p.direction()).isEqualTo(Direction.POSITIVE);
        assertThat(p.headline()).isEqualTo("OO전자 2조원 수주");
        assertThat(p.summary()).isEqualTo("대형 공급계약 체결");
    }

    @Test
    @DisplayName("```json 펜스 + 설명 문구 섞인 응답 → 첫 { } 블록 추출")
    void parse_fencedJson() {
        ParsedCatalyst p = StockCatalystService.parseCatalystResponse(
                "분류 결과입니다:\n```json\n{\"type\":\"EARNINGS\",\"direction\":\"NEGATIVE\"," +
                "\"headline\":\"실적 쇼크\",\"summary\":\"영업이익 컨센 하회\"}\n```");

        assertThat(p).isNotNull();
        assertThat(p.type()).isEqualTo(CatalystType.EARNINGS);
        assertThat(p.direction()).isEqualTo(Direction.NEGATIVE);
    }

    @Test
    @DisplayName("type=NONE 이면 direction 도 NONE 으로 강제 (집계 일관성)")
    void parse_noneForcesDirectionNone() {
        ParsedCatalyst p = StockCatalystService.parseCatalystResponse(
                "{\"type\":\"NONE\",\"direction\":\"NEUTRAL\",\"headline\":\"\",\"summary\":\"\"}");

        assertThat(p).isNotNull();
        assertThat(p.type()).isEqualTo(CatalystType.NONE);
        assertThat(p.direction()).isEqualTo(Direction.NONE);
        assertThat(p.headline()).isNull();   // 빈 문자열 → null 정규화
    }

    @Test
    @DisplayName("enum 어휘 밖 type / JSON 아님 / null → null (캐시 안 함)")
    void parse_invalidInputs() {
        assertThat(StockCatalystService.parseCatalystResponse(
                "{\"type\":\"MOON_SHOT\",\"direction\":\"POSITIVE\"}")).isNull();
        assertThat(StockCatalystService.parseCatalystResponse("재료가 없습니다.")).isNull();
        assertThat(StockCatalystService.parseCatalystResponse(null)).isNull();
        assertThat(StockCatalystService.parseCatalystResponse("")).isNull();
    }

    @Test
    @DisplayName("프롬프트 — 종목명 + 뉴스 제목 최대 5건 + JSON 형식 지시 포함")
    void buildPrompt_containsTitlesAndFormat() {
        List<NewsItem> news = List.of(
                NewsItem.builder().title("뉴스1").build(),
                NewsItem.builder().title("뉴스2").build(),
                NewsItem.builder().title("뉴스3").build(),
                NewsItem.builder().title("뉴스4").build(),
                NewsItem.builder().title("뉴스5").build(),
                NewsItem.builder().title("뉴스6 (제외)").build());

        String prompt = StockCatalystService.buildPrompt("삼성전자", news);

        assertThat(prompt).contains("삼성전자").contains("뉴스1").contains("뉴스5");
        assertThat(prompt).doesNotContain("뉴스6");
        assertThat(prompt).contains("ORDER_WIN").contains("\"direction\"");
    }

    // ================================================================
    // 텔레그램 재료 알림 — 호재→시그널 / 악재→리스크 / 중립→무알림
    // ================================================================

    private StockCatalystService service() {
        return new StockCatalystService(repository, naverProvider, geminiProvider, telegramProvider);
    }

    /** 신규 분류 경로 공통 셋업 — 캐시 미스 + 뉴스 1건 + Gemini 응답 주입. */
    private void setupFreshClassification(String geminiJson) {
        when(repository.findByStockCodeAndCatalystDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(naverProvider.getIfAvailable()).thenReturn(naver);
        when(geminiProvider.getIfAvailable()).thenReturn(gemini);
        when(naver.isAvailable()).thenReturn(true);   // 소스 가용 — §4c 가드 통과
        when(naver.searchStockNews("삼성전자"))
                .thenReturn(List.of(NewsItem.builder().title("삼성전자 대형 수주").build()));
        when(gemini.chat(anyString())).thenReturn(geminiJson);
        when(repository.save(any(StockCatalyst.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("알림: 호재(POSITIVE) 신규 분류 → 시그널 채널 발송")
    void alert_positiveToSignalChannel() {
        setupFreshClassification(
                "{\"type\":\"ORDER_WIN\",\"direction\":\"POSITIVE\",\"headline\":\"대형 수주\",\"summary\":\"2조원 계약\"}");
        when(telegramProvider.getIfAvailable()).thenReturn(telegram);

        service().getCatalyst("005930", "삼성전자");

        verify(telegram).sendSignal(contains("호재"));
        verify(telegram, never()).sendRisk(anyString());
    }

    @Test
    @DisplayName("알림: 악재(NEGATIVE) → 리스크 채널 발송")
    void alert_negativeToRiskChannel() {
        setupFreshClassification(
                "{\"type\":\"LITIGATION\",\"direction\":\"NEGATIVE\",\"headline\":\"소송 피소\",\"summary\":\"대규모 손배소\"}");
        when(telegramProvider.getIfAvailable()).thenReturn(telegram);

        service().getCatalyst("005930", "삼성전자");

        verify(telegram).sendRisk(contains("악재"));
        verify(telegram, never()).sendSignal(anyString());
    }

    @Test
    @DisplayName("알림: 중립(NEUTRAL) → 무알림")
    void alert_neutralNoAlert() {
        setupFreshClassification(
                "{\"type\":\"OTHER\",\"direction\":\"NEUTRAL\",\"headline\":\"동향\",\"summary\":\"업계 동향\"}");

        service().getCatalyst("005930", "삼성전자");

        verifyNoInteractions(telegram);
    }

    @Test
    @DisplayName("§4c: 뉴스 소스 미가용 → null 반환 + NONE 캐시/뉴스조회 안 함 (7일 NONE 재발 방지)")
    void sourceUnavailable_doesNotCacheNone() {
        when(repository.findByStockCodeAndCatalystDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(naverProvider.getIfAvailable()).thenReturn(naver);
        when(geminiProvider.getIfAvailable()).thenReturn(gemini);
        when(naver.isAvailable()).thenReturn(false);   // 뉴스 소스 다운(키 미주입/장애)

        StockCatalyst result = service().getCatalyst("005930", "삼성전자");

        assertThat(result).isNull();
        verify(repository, never()).save(any(StockCatalyst.class));   // NONE 위장 캐시 금지(§4c)
        verify(naver, never()).searchStockNews(anyString());          // 소스 다운이면 조회 자체 안 함
    }

    @Test
    @DisplayName("알림: 캐시 히트(이미 오늘 분류됨) → Gemini/알림 모두 미호출")
    void alert_cacheHitNoAlert() {
        when(repository.findByStockCodeAndCatalystDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.of(StockCatalyst.builder()
                        .stockCode("005930").stockName("삼성전자")
                        .catalystDate(LocalDate.now())
                        .catalystType(CatalystType.ORDER_WIN)
                        .direction(Direction.POSITIVE)
                        .build()));

        StockCatalyst result = service().getCatalyst("005930", "삼성전자");

        assertThat(result).isNotNull();
        verifyNoInteractions(gemini, telegram, naver);
    }

    @Test
    @DisplayName("알림: 텔레그램 미설정(null) → 분류/저장은 정상 동작")
    void alert_telegramUnavailableStillSaves() {
        setupFreshClassification(
                "{\"type\":\"EARNINGS\",\"direction\":\"POSITIVE\",\"headline\":\"호실적\",\"summary\":\"컨센 상회\"}");
        when(telegramProvider.getIfAvailable()).thenReturn(null);

        StockCatalyst result = service().getCatalyst("005930", "삼성전자");

        assertThat(result).isNotNull();
        assertThat(result.getCatalystType()).isEqualTo(CatalystType.EARNINGS);
        verify(repository).save(any(StockCatalyst.class));
    }

    @Test
    @DisplayName("알림 메시지 — 종목/유형 라벨/요약/면책 문구 포함 (HTML 포맷)")
    void buildAlertMessage_format() {
        String msg = StockCatalystService.buildAlertMessage(StockCatalyst.builder()
                .stockCode("005930").stockName("삼성전자")
                .catalystDate(LocalDate.now())
                .catalystType(CatalystType.ORDER_WIN)
                .direction(Direction.POSITIVE)
                .headline("삼성전자 2조원 수주")
                .summary("대형 공급계약 체결")
                .build());

        assertThat(msg).contains("재료 포착 (호재)");
        assertThat(msg).contains("<b>삼성전자</b> (005930)");
        assertThat(msg).contains("유형: <b>수주</b>");
        assertThat(msg).contains("대형 공급계약 체결");
        assertThat(msg).contains("산식 미반영");
    }

    // ================================================================
    // P2-CAT1 배치 분류 — N종목 1콜(RPM 실질↓) + 개별 폴백
    // ================================================================

    @Test
    @DisplayName("배치 프롬프트 — code= 키·종목명·뉴스 제목·JSON 배열 지시 포함")
    void buildBatchPrompt_containsCodesAndArrayFormat() {
        var pending = new LinkedHashMap<String, StockNews>();
        pending.put("005930", new StockNews("삼성전자", List.of(NewsItem.builder().title("삼성전자 수주").build())));
        pending.put("000660", new StockNews("SK하이닉스", List.of(NewsItem.builder().title("하이닉스 실적").build())));

        String p = StockCatalystService.buildBatchPrompt(pending);

        assertThat(p).contains("code=005930").contains("code=000660");
        assertThat(p).contains("삼성전자").contains("삼성전자 수주").contains("SK하이닉스");
        assertThat(p).contains("JSON 배열").contains("\"code\"");
    }

    @Test
    @DisplayName("배치 파싱 — 유효 원소만 저장, 실패 원소는 스킵(개별 폴백), NONE→direction NONE")
    void parseBatchResponse_partialFallback() {
        Set<String> req = Set.of("005930", "000660", "035420");
        String resp = "[{\"code\":\"005930\",\"type\":\"ORDER_WIN\",\"direction\":\"POSITIVE\",\"headline\":\"수주\",\"summary\":\"계약\"},"
                + "{\"code\":\"000660\",\"type\":\"BADTYPE\",\"direction\":\"POSITIVE\"},"   // 파싱 실패 → 스킵
                + "{\"code\":\"035420\",\"type\":\"NONE\",\"direction\":\"NEUTRAL\",\"headline\":\"\",\"summary\":\"\"}]";

        var m = StockCatalystService.parseBatchResponse(resp, req);

        assertThat(m).containsOnlyKeys("005930", "035420");   // 000660 은 실패 → 미저장(재시도)
        assertThat(m.get("005930").type()).isEqualTo(CatalystType.ORDER_WIN);
        assertThat(m.get("035420").type()).isEqualTo(CatalystType.NONE);
        assertThat(m.get("035420").direction()).isEqualTo(Direction.NONE);   // NONE → direction 강제 NONE
    }

    @Test
    @DisplayName("배치 파싱 — 미요청 code 무시 / 중복 code 첫 것만 / 배열 아님·null → 빈 맵(전원 재시도)")
    void parseBatchResponse_defensive() {
        // 미요청 code
        assertThat(StockCatalystService.parseBatchResponse(
                "[{\"code\":\"999999\",\"type\":\"EARNINGS\",\"direction\":\"POSITIVE\"}]", Set.of("005930"))).isEmpty();
        // 중복 code → 첫 것만
        var dup = StockCatalystService.parseBatchResponse(
                "[{\"code\":\"005930\",\"type\":\"ORDER_WIN\",\"direction\":\"POSITIVE\"},"
                + "{\"code\":\"005930\",\"type\":\"EARNINGS\",\"direction\":\"NEGATIVE\"}]", Set.of("005930"));
        assertThat(dup).hasSize(1);
        assertThat(dup.get("005930").type()).isEqualTo(CatalystType.ORDER_WIN);
        // 배열 아님 / null
        assertThat(StockCatalystService.parseBatchResponse("죄송합니다. JSON 아님.", Set.of("005930"))).isEmpty();
        assertThat(StockCatalystService.parseBatchResponse(null, Set.of("005930"))).isEmpty();
    }

    @Test
    @DisplayName("classifyBatch — 2종목이 Gemini 1콜(RPM 1/N) + 종목별 저장, 뉴스 0건은 개별 NONE")
    void classifyBatch_oneGeminiCallPerBatch() {
        when(naverProvider.getIfAvailable()).thenReturn(naver);
        when(geminiProvider.getIfAvailable()).thenReturn(gemini);
        when(naver.isAvailable()).thenReturn(true);
        when(repository.findByStockCodeAndCatalystDate(anyString(), any(LocalDate.class))).thenReturn(Optional.empty());
        when(naver.searchStockNews("삼성전자")).thenReturn(List.of(NewsItem.builder().title("삼성전자 수주").build()));
        when(naver.searchStockNews("SK하이닉스")).thenReturn(List.of(NewsItem.builder().title("하이닉스 실적").build()));
        when(naver.searchStockNews("뉴스없는종목")).thenReturn(List.of());   // 뉴스 0건 → 개별 NONE
        when(gemini.chat(anyString())).thenReturn(
                "[{\"code\":\"005930\",\"type\":\"ORDER_WIN\",\"direction\":\"POSITIVE\",\"headline\":\"수주\",\"summary\":\"계약\"},"
                + "{\"code\":\"000660\",\"type\":\"EARNINGS\",\"direction\":\"POSITIVE\",\"headline\":\"실적\",\"summary\":\"호실적\"}]");
        when(repository.save(any(StockCatalyst.class))).thenAnswer(inv -> inv.getArgument(0));

        int saved = service().classifyBatch(List.of(
                new StockRef("005930", "삼성전자"),
                new StockRef("000660", "SK하이닉스"),
                new StockRef("999999", "뉴스없는종목")));

        assertThat(saved).isEqualTo(2);                       // 뉴스 있는 2종목 분류 저장
        verify(gemini, times(1)).chat(anyString());           // ★ 2종목 = Gemini 1콜 (RPM 1/N)
        verify(repository, times(3)).save(any(StockCatalyst.class));   // 분류 2 + NONE 1
    }

    @Test
    @DisplayName("classifyBatch(워밍) — 유의미 재료여도 텔레그램 알림 억제 (온디맨드 단건만 알림)")
    void classifyBatch_warmingSuppressesAlerts() {
        when(naverProvider.getIfAvailable()).thenReturn(naver);
        when(geminiProvider.getIfAvailable()).thenReturn(gemini);
        when(naver.isAvailable()).thenReturn(true);
        when(repository.findByStockCodeAndCatalystDate(anyString(), any(LocalDate.class))).thenReturn(Optional.empty());
        when(naver.searchStockNews("삼성전자")).thenReturn(List.of(NewsItem.builder().title("삼성전자 수주").build()));
        when(gemini.chat(anyString())).thenReturn(
                "[{\"code\":\"005930\",\"type\":\"ORDER_WIN\",\"direction\":\"POSITIVE\",\"headline\":\"수주\",\"summary\":\"계약\"}]");
        when(repository.save(any(StockCatalyst.class))).thenAnswer(inv -> inv.getArgument(0));

        int saved = service().classifyBatch(List.of(new StockRef("005930", "삼성전자")));   // notify 기본 false(워밍)

        assertThat(saved).isEqualTo(1);
        verifyNoInteractions(telegram);              // 워밍은 알림 안 함
        verify(telegramProvider, never()).getIfAvailable();
    }

    @Test
    @DisplayName("classifyBatch — 뉴스 소스 미가용(§4c) → 0건 저장·Gemini 미호출")
    void classifyBatch_sourceUnavailable() {
        when(naverProvider.getIfAvailable()).thenReturn(naver);
        when(geminiProvider.getIfAvailable()).thenReturn(gemini);
        when(naver.isAvailable()).thenReturn(false);

        int saved = service().classifyBatch(List.of(new StockRef("005930", "삼성전자")));

        assertThat(saved).isZero();
        verify(repository, never()).save(any(StockCatalyst.class));
        verify(gemini, never()).chat(anyString());
    }
}
