package com.myplatform.backend.service;

import com.myplatform.backend.controller.QuantScreenerController;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockMasterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 순매수 상위 재무 재수집(23:00) 은퇴 가드(2026-09-28).
 *
 * <p><b>무엇이 있었나</b>: {@code StockFinancialDataService.collectDailyFinancialData}(23:00, MON-FRI)가
 * 외국인·기관 순매수 상위 약 51종목을 <b>옛 수집기 복사본</b>({@code collectStockFinancialData}, "완전판")으로
 * 다시 수집해, 원버튼 배치가 그날 쓴 행을 덮었다. 그 복사본은 {@code hts_avls}(이미 억원)를 1e8 로 또 나눠
 * scale 0 반올림 → <b>시총 항상 0</b>, 성장률 배치가 채운 PEG 를 자기 계산값(null)으로 지웠다. 운영 실측
 * (2026-09-23 행): 덮인 51종목 시총 채움 0%·PEG 0% vs 나머지 97.7%·40.5% — 삼성전자·SK하이닉스가 매일 포함.
 * 시총 필터를 쓰는 퀀트 스크리너·AI 전략이 밤 23:00 부터 다음 배치 전까지 대형주를 뺐다.
 *
 * <p>배치가 전 종목을 이미 수집하므로 이 잡은 덮어쓰기만 했다 → 은퇴. 같은 함수를 쓰던 수동 재수집
 * ({@code POST /api/screener/collect})과 <b>전체 삭제 후 51종목 재수집</b>({@code POST /api/screener/recollect},
 * 누르면 재무 테이블 11만 행이 51종목으로 줄어드는 함정)도 같이 지웠다. 단일 종목 수동 수집은 배치 경로로.
 *
 * <p>⚠ 이 테스트가 깨지면 <b>그게 의도다.</b> 수집 경로가 두 벌이 되면 어느 쪽 값이 맞는지 다시 갈린다.
 */
class NightlyFinancialRecollectRetiredTest {

    private static List<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).toList();
    }

    @Test
    @DisplayName("재무 데이터 서비스에 순매수 상위 재수집·전체삭제 재수집이 다시 붙지 않는다")
    void serviceHasNoRecollectPaths() {
        assertThat(methodNames(StockFinancialDataService.class))
                .doesNotContain("collectDailyFinancialData", "collectFinancialDataFromTopStocks",
                        "collectManually", "deleteAndRecollect", "deleteAllFinancialData");
    }

    @Test
    @DisplayName("재무 데이터 서비스에 스케줄 잡이 없다 — 정기 수집은 원버튼 배치(08:30·15:38)만")
    void serviceHasNoScheduledJob() {
        assertThat(Arrays.stream(StockFinancialDataService.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .map(Method::getName)
                .toList()).isEmpty();
    }

    @Test
    @DisplayName("수집기의 옛 완전판은 지워졌다 — 수집 경로는 간소화판 하나")
    void collectorHasSingleCollectPath() {
        assertThat(methodNames(StockFinancialDataCollector.class))
                .doesNotContain("collectStockFinancialData", "fetchNaverListedShares")
                .contains("collectStockFinancialDataSimple");
    }

    @Test
    @DisplayName("스크리너 컨트롤러에 /collect·/recollect 매핑이 없다")
    void controllerHasNoRecollectEndpoints() {
        List<String> postPaths = Arrays.stream(QuantScreenerController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PostMapping.class))
                .flatMap(m -> Arrays.stream(m.getAnnotation(PostMapping.class).value()))
                .toList();
        assertThat(postPaths).doesNotContain("/collect", "/recollect")
                .contains("/collect/{stockCode}", "/collect-all-in-one", "/collect-all");
    }

    @Test
    @DisplayName("단일 종목 수동 수집은 배치와 같은 수집기 경로를 탄다")
    void singleStockUsesBatchCollectPath() {
        StockFinancialDataCollector collector = mock(StockFinancialDataCollector.class);
        when(collector.collectStockFinancialDataSimple("005930")).thenReturn(true);
        StockFinancialDataService service = new StockFinancialDataService(
                mock(StockFinancialDataRepository.class), mock(StockMasterRepository.class),
                collector, mock(SseEmitterService.class), mock(StockStatusService.class));

        assertThat(service.collectSingleStock("005930")).isTrue();

        verify(collector).collectStockFinancialDataSimple("005930");
        verifyNoMoreInteractions(collector);
    }
}
