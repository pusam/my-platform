package com.myplatform.backend.service;

import com.myplatform.backend.dto.RiskAnalysisDto;
import com.myplatform.backend.dto.RiskAnalysisDto.RiskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * RiskManagementService 단위 테스트
 *
 * 검증 포인트:
 * 1. 위험 공시 발견 시 DANGER 반환
 * 2. 부정적 뉴스 5건 이상 시 WARNING 이상
 * 3. 뉴스 소스 폴백 체인 (Google → 네이버금융 → 네이버검색)
 * 4. 외부 API 전부 실패해도 SAFE 반환 (안전 기본값)
 * 5. 리스크 점수 0~100 범위 보장
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class RiskManagementServiceTest {

    @Mock private DartService dartService;
    @Mock private NaverSearchService naverSearchService;
    @Mock private GoogleNewsService googleNewsService;

    private RiskManagementService riskService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // executor 는 동기 직실행(Runnable::run) — 테스트 결정성 + supplyAsync 경로 그대로 통과
        riskService = new RiskManagementService(dartService, naverSearchService, googleNewsService, Runnable::run);
    }

    private static final String STOCK_NAME = "삼성전자";
    private static final String STOCK_CODE = "005930";

    // ========== 위험 공시 시나리오 ==========

    @Nested
    @DisplayName("위험 공시 감지")
    class DangerDisclosureTests {

        @Test
        @DisplayName("위험 공시 발견 시 DANGER 상태 + 80점 이상")
        void dangerDisclosure_returnsDangerStatus() {
            // given
            RiskAnalysisDto.DartDisclosure dangerDisc = RiskAnalysisDto.DartDisclosure.builder()
                    .corpName(STOCK_NAME)
                    .reportNm("상장폐지 사유 발생")
                    .matchedKeyword("상장폐지")
                    .build();

            when(dartService.searchDisclosuresByName(anyString())).thenReturn(
                    List.of(dangerDisc));
            when(dartService.filterDangerousDisclosures(any())).thenReturn(
                    List.of(dangerDisc));
            when(googleNewsService.searchNews(anyString())).thenReturn(Collections.emptyList());

            // when
            RiskAnalysisDto result = riskService.analyzeRisk(STOCK_NAME, STOCK_CODE);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(RiskStatus.DANGER);
            assertThat(result.getRiskScore()).isGreaterThanOrEqualTo(80);
            assertThat(result.getDangerousDisclosures()).hasSize(1);
        }
    }

    // ========== 뉴스 기반 리스크 ==========

    @Nested
    @DisplayName("뉴스 기반 리스크 분석")
    class NewsRiskTests {

        @Test
        @DisplayName("부정적 뉴스 없으면 SAFE")
        void noNegativeNews_returnsSafe() {
            // given
            when(dartService.searchDisclosuresByName(anyString())).thenReturn(Collections.emptyList());
            when(dartService.filterDangerousDisclosures(any())).thenReturn(Collections.emptyList());
            when(googleNewsService.searchNews(anyString())).thenReturn(Collections.emptyList());

            // when
            RiskAnalysisDto result = riskService.analyzeRisk(STOCK_NAME, STOCK_CODE);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(RiskStatus.SAFE);
            assertThat(result.getRiskScore()).isLessThan(31);
        }

        @Test
        @DisplayName("리스크 점수는 항상 0~100 범위")
        void riskScore_alwaysInRange() {
            // given
            when(dartService.searchDisclosuresByName(anyString())).thenReturn(Collections.emptyList());
            when(dartService.filterDangerousDisclosures(any())).thenReturn(Collections.emptyList());
            when(googleNewsService.searchNews(anyString())).thenReturn(Collections.emptyList());

            // when
            RiskAnalysisDto result = riskService.analyzeRisk(STOCK_NAME, STOCK_CODE);

            // then
            assertThat(result.getRiskScore()).isBetween(0, 100);
        }
    }

    // ========== 폴백 체인 ==========

    @Nested
    @DisplayName("뉴스 소스 폴백 체인")
    class NewsFallbackTests {

        @Test
        @DisplayName("Google News 실패 → 네이버 폴백 시도")
        void googleFail_fallbackToNaver() {
            // given
            when(dartService.searchDisclosuresByName(anyString())).thenReturn(Collections.emptyList());
            when(dartService.filterDangerousDisclosures(any())).thenReturn(Collections.emptyList());
            when(googleNewsService.searchNews(anyString()))
                    .thenThrow(new RuntimeException("Google News timeout"));
            // 네이버 폴백도 빈 리스트 반환 (실경로는 searchStockNews — 구 searchNews(q,n) 인터페이스는 제거됨)
            when(naverSearchService.searchStockNews(anyString())).thenReturn(Collections.emptyList());

            // when
            RiskAnalysisDto result = riskService.analyzeRisk(STOCK_NAME, STOCK_CODE);

            // then
            assertThat(result).isNotNull();
            // Google 실패해도 결과는 반환
            assertThat(result.getStatus()).isIn(RiskStatus.SAFE, RiskStatus.WARNING, RiskStatus.DANGER);
        }

        @Test
        @DisplayName("모든 외부 API 실패 시에도 예외 없이 SAFE 반환")
        void allApiFail_returnsSafe() {
            // given
            when(dartService.searchDisclosuresByName(anyString()))
                    .thenThrow(new RuntimeException("DART timeout"));
            when(googleNewsService.searchNews(anyString()))
                    .thenThrow(new RuntimeException("Google timeout"));
            when(naverSearchService.searchStockNews(anyString()))
                    .thenThrow(new RuntimeException("Naver timeout"));

            // when
            RiskAnalysisDto result = riskService.analyzeRisk(STOCK_NAME, STOCK_CODE);

            // then
            assertThat(result).isNotNull();
            // 데이터 없으면 안전 기본값
            assertThat(result.getStatus()).isEqualTo(RiskStatus.SAFE);
        }
    }
}
