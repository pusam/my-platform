package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.entity.NewsSummary;
import com.myplatform.backend.repository.NewsSummaryRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.StockPriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * '오늘 강세 섹터' 카드의 키워드(2026-11-10).
 *
 * <p>재현: 11/10 08:09 시장 탭 — 전력기기·반도체 카드에 똑같이 '키워드: 소액주주 · 상장사 · 24 · 국내'가 붙었다. 아침이라 그 섹터
 * 종목 이름이 나온 오늘 기사가 한두 개뿐인데, 단어 '등장 횟수'를 세어 여러 회사를 나열한 기사 하나 안에서 두 번 나온 단어가
 * '최소 2번' 기준을 넘었다. 이제 단어가 나온 <b>기사 수</b>를 세어 서로 다른 기사 2개 이상일 때만, 숫자만인 토큰은 빼고 보인다.
 */
class QuantTaSectorKeywordsTest {

    private NewsSummaryRepository news;
    private QuantTaService service;

    @BeforeEach
    void setUp() {
        SectorStockConfig sectors = mock(SectorStockConfig.class);
        when(sectors.getSector("POWER")).thenReturn(
                new SectorStockConfig.SectorInfo("POWER", "전력기기", "#000", List.of("298040", "010120")));
        StockMasterService master = mock(StockMasterService.class);
        when(master.getNameOrDefault("298040", "298040")).thenReturn("효성중공업");
        when(master.getNameOrDefault("010120", "010120")).thenReturn("LS ELECTRIC");
        when(master.getNameOrDefault(anyString(), anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            return "298040".equals(code) ? "효성중공업" : "010120".equals(code) ? "LS ELECTRIC" : code;
        });
        news = mock(NewsSummaryRepository.class);
        service = new QuantTaService(mock(StockPriceHistoryRepository.class), mock(StockPriceRepository.class),
                mock(TechnicalIndicatorService.class), mock(StockAnalysisService.class), sectors, master,
                mock(StockPriceService.class), news);
    }

    private static NewsSummary article(String title, String summary) {
        NewsSummary n = new NewsSummary();
        n.setTitle(title);
        n.setSummary(summary);
        return n;
    }

    @Test
    @DisplayName("재현(11/10 08:09): 기사 하나 안에서 두 번 나온 단어는 키워드가 아니다 — '소액주주·상장사·24·국내'가 붙었다")
    void wordsFromASingleArticleAreNotKeywords() {
        when(news.findTodayNews(any())).thenReturn(List.of(article(
                "24개 상장사 소액주주 권리 강화…효성중공업·삼성전자 등 국내 상장사",
                "국내 상장사 24곳의 소액주주 보호 조치")));

        assertThat(service.getSectorKeywords("POWER", 8)).isEmpty();
    }

    @Test
    @DisplayName("서로 다른 기사 2개에 나온 단어는 키워드 — 숫자만인 토큰은 빼고, 빈도는 기사 수")
    void wordsAcrossTwoArticlesAreKeywords() {
        when(news.findTodayNews(any())).thenReturn(List.of(
                article("효성중공업, 북미 변압기 수주 2026년 최대", "변압기 공급 계약 24건"),
                article("LS ELECTRIC 변압기 증설", "변압기 생산 24 라인 확대")));

        List<QuantTaService.KeywordDto> keywords = service.getSectorKeywords("POWER", 8);

        assertThat(keywords).extracting(QuantTaService.KeywordDto::keyword).containsExactly("변압기");
        assertThat(keywords.get(0).frequency()).isEqualTo(2);   // 기사 수(등장 횟수 4 가 아니다)
    }
}
