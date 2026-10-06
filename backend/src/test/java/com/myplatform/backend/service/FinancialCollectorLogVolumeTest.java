package com.myplatform.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dartfinancial.DartCompanyRepository;
import com.myplatform.backend.dartfinancial.DartControllingFinancialRepository;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockQuarterlyFinancialRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 재무 수집기의 종목별 로그는 DEBUG, 회차에는 상태별 종목 수 한 줄(2026-10-06, 사용자 요청).
 *
 * <p>재현: 08:30·15:38 회차마다 종목당 INFO 8줄 남짓(손익계산서 RAW 4·재무상태표 3·TTM·분기 적재·EPS·저장)이 쌓여 한 회차가
 * 2만~2만8천 줄이었다(10/6 15:38 회차 20,579줄 — 평소 분당 5줄인 로그가 분당 900줄). 같은 종목들이 매 회차 같은 '데이터 상태'
 * (연속 4분기 부족 137·재무상태표 응답 비어 있음 65·손익계산서 응답 비어 있음 31 …)도 종목마다 WARN 이었다. 정상 로그가 그렇게
 * 쌓이면 사고 때 "무슨 일이 있었나"를 못 찾는다(CLAUDE.md §5 — 루프 안은 DEBUG, 요약 한 줄만 INFO). <b>실패(API 오류·응답 없음)는
 * 그대로 WARN</b> — 경보를 끄는 게 아니라 반복만 없앤다.
 */
class FinancialCollectorLogVolumeTest {

    private static final String CODE = "023590";

    private RestTemplate rest;
    private StockFinancialDataCollector collector;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() throws Exception {
        StockFinancialDataRepository dailyRepo = mock(StockFinancialDataRepository.class);
        KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
        rest = mock(RestTemplate.class);
        collector = new StockFinancialDataCollector(dailyRepo, mock(StockQuarterlyFinancialRepository.class), kis, rest,
                new ObjectMapper(), mock(StockMasterService.class), mock(DartControllingFinancialRepository.class),
                mock(DartCompanyRepository.class));
        when(dailyRepo.findByStockCodeAndReportDate(anyString(), any())).thenReturn(Optional.of(new StockFinancialData()));
        when(kis.getAccessToken()).thenReturn("token");
        when(kis.getStockPrice(CODE)).thenReturn(new ObjectMapper().readTree(
                "{\"rt_cd\":\"0\",\"output\":{\"hts_kor_isnm\":\"다우기술\",\"stck_prpr\":\"36500\",\"hts_avls\":\"15800\","
                        + "\"per\":\"3.24\",\"pbr\":\"0.46\",\"eps\":\"11260\",\"bps\":\"79869\",\"lstn_stcn\":\"43287312\"}}"));

        logger = (Logger) LoggerFactory.getLogger(StockFinancialDataCollector.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        collector.drainStateSummary();   // 다른 테스트가 남긴 집계를 비운다
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    /** KIS 재무 API 3종 응답 — URL 의 경로로 가른다. income 은 분기 행 JSON 배열. */
    private void kisAnswers(String incomeRtCd, String incomeQuarters) {
        when(rest.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class))).thenAnswer(inv -> {
            String url = inv.getArgument(0, String.class);
            if (url.contains("/finance/financial-ratio")) {
                return ResponseEntity.ok("{\"rt_cd\":\"0\",\"output\":[{\"roe_val\":\"5.00\",\"lblt_rate\":\"66.70\"}]}");
            }
            if (url.contains("/finance/income-statement")) {
                return ResponseEntity.ok("{\"rt_cd\":\"" + incomeRtCd + "\",\"msg1\":\"테스트 오류\",\"output\":" + incomeQuarters + "}");
            }
            if (url.contains("/finance/balance-sheet")) {
                return ResponseEntity.ok("{\"rt_cd\":\"0\",\"output\":[{\"total_aset\":\"1000\",\"total_cptl\":\"600\",\"total_lblt\":\"400\"}]}");
            }
            return ResponseEntity.ok("{}");
        });
    }

    private static String quarters(int n) {
        String[] periods = {"202606", "202603", "202512", "202509", "202506", "202503", "202412", "202409"};
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"stac_yymm\":\"").append(periods[i])
                    .append("\",\"sale_account\":\"100\",\"bsop_prti\":\"10\",\"thtr_ntin\":\"8\"}");
        }
        return sb.append(']').toString();
    }

    private List<ILoggingEvent> atLeast(Level level) {
        return appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(level)).toList();
    }

    @Test
    @DisplayName("재현: 정상 종목 하나를 수집해도 INFO 이상 로그를 남기지 않는다(종목별 줄은 DEBUG)")
    void normalStockLeavesNoInfoLines() {
        kisAnswers("0", quarters(8));

        assertThat(collector.collectStockFinancialDataSimple(CODE)).isTrue();

        assertThat(atLeast(Level.INFO)).extracting(ILoggingEvent::getFormattedMessage).isEmpty();
    }

    @Test
    @DisplayName("재현: 되풀이되는 데이터 상태(연속 4분기 부족)는 종목별 WARN 대신 회차 집계로 센다")
    void recurringDataStateIsCountedNotWarned() {
        kisAnswers("0", quarters(2));

        collector.collectStockFinancialDataSimple(CODE);

        assertThat(atLeast(Level.WARN)).isEmpty();
        assertThat(collector.drainStateSummary()).contains("TTM 미산출(연속 4분기 부족) 1");
        assertThat(collector.drainStateSummary()).isEqualTo("없음");   // 꺼내면 비워진다
    }

    @Test
    @DisplayName("실패(API 오류)는 그대로 WARN — 경보를 끄지 않는다")
    void apiFailureStillWarns() {
        kisAnswers("1", "[]");

        collector.collectStockFinancialDataSimple(CODE);

        assertThat(atLeast(Level.WARN)).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("[손익계산서]") && m.contains("API 오류"));
    }

    @Test
    @DisplayName("회차 요약은 많은 순 · 0 은 빼고 · 없으면 '없음'")
    void summaryFormat() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("손익계산서 응답 비어 있음", 31L);
        counts.put("TTM 미산출(연속 4분기 부족)", 137L);
        counts.put("재무상태표 자본총계 없음(분기·연간)", 65L);
        counts.put("손익계산서 없음(손익 없이 저장)", 0L);

        assertThat(StockFinancialDataCollector.formatStateSummary(counts))
                .isEqualTo("TTM 미산출(연속 4분기 부족) 137 · 재무상태표 자본총계 없음(분기·연간) 65 · 손익계산서 응답 비어 있음 31");
        assertThat(StockFinancialDataCollector.formatStateSummary(Map.of())).isEqualTo("없음");
    }
}
