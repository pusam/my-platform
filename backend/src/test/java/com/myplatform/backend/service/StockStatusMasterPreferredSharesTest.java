package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.StockPriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 우선주 주식 수는 KIS 종목마스터의 상장주수·우선주 칸에서 읽는다(2026-11-05). 꼬리는 11/5 실제 파일의 그 종목 줄이다
 * ({@code kis_kospi_code_mst.py}·{@code kis_kosdaq_code_mst.py} 의 field_specs: 코스피 113·158, 코스닥 108·153).
 */
class StockStatusMasterPreferredSharesTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    private static final String HYUNDAI = "ST1002700150000 NN8YYY YY NNNNNN0NNNNNNNN0002835000000100001NNN00NNN000000020Y0900000006633280000000050001974062800000000020346600000000115798239500012       0 NYY00095154200005365600007167354729000007.0120260630000576827BH1NNY";
    private static final String HYUNDAI_PREF = "ST0002700150000 NN0NNN NN NNNNNN0NNNNNNNN0001502000000100001NNN00NNN000000040Y0900000000698270000000050001989091100000000002283900000000012554991000012       1 NNN00000000000000000000000000000000000000.00        000034304   NNY";
    private static final String HYUNDAI_2B = "ST0002700150000 NN0NNN NN NNNNNN0NNNNNNNN0001518000000100001NNN00NNN000000020Y0900000001249870000000050001998120200000000003425700000000019306932500012       2 NNN00000000000000000000000000000000000000.00        000052002   NNY";
    private static final String HYUNDAI_3B = "ST0002700150000 NN0NNN NN NNNNNN0NNNNNNNN0001494000000100001NNN00NNN000000040Y0900000000097320000000050001999041500000000000232100000000001239149500012       2 NNN00000000000000000000000000000000000000.00        000003468   NNN";
    private static final String KODEX200 = "EF 000000000000NNN0NNN2NN NNNNNN0NNYNNNNN0000897150000100001NNN00NNN000000030Y0900000344086040000000000002002101400000000024790000000000000000000000012       0 NNN00000000000000000000000000000000000000.00        000222403   NNY";
    private static final String HANWHA_MS = "ST2002100000000 N 4NNY NN    N  0  N     0000075600000100001NNN00NNN000000100N0900000004395900000000010002026082500000000008451500000000008451553500012       0 NYY00000000000000000000000000000000000000.00        000006389   NNN";
    private static final String HANWHA_MS_3B = "ST0002100000000 N 0NNN NN    N  0  N     0000021800000100001NNN00NNN000000100N0900000002184110000000010002026082500000000002363900000000002363914000012       9 NNN00000000000000000000000000000000000000.00        000000515   NNN";

    private static final String HAESUNG = "ST3101400000000NNN N NNNNNN0NNNNNNNN0000056000000100001NNN00NNN000000060Y0900000000162470000000005001999041600000000003158000000000001579007600012       0 NN000012584000000455-00000070-0195-0000000820260630000001768   NNN";
    private static final String HAESUNG_1 = "ST0101400000000 NN N    N  0  N    N0000056700000100001NNN00NNN000000100N0900000000018430000000005002021120200000000000137700000000000068872650012       9 NN00000000000000000000000000000000000000000        000000078   NNN";
    private static final String ALTEOGEN = "ST1100600000000NNY Y NYNNNN0NNNNNNNY0002040000000100001NNN00NNN000000040Y0900000007533710000000005002014121200000000006965500000000003482756650012       0 NY0000014050000007350000011640101300000004420260630000142096   NNY";

    private static byte[] line(String code, String koreanName, String tail) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(String.format("%-9s", code).getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(("KR7" + code + "000").getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(koreanName.getBytes(EUC_KR));
        out.writeBytes(tail.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static byte[] file(byte[]... lines) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] l : lines) out.writeBytes(l);
        return out.toByteArray();
    }

    private static byte[] zip(byte[] mst) throws IOException {
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipped)) {
            zos.putNextEntry(new ZipEntry("x_code.mst"));
            zos.write(mst);
            zos.closeEntry();
        }
        return zipped.toByteArray();
    }

    private static Map<String, StockStatusService.ListedShares> parse(byte[] mst, StockStatusService.MasterLayout layout) throws IOException {
        return StockStatusService.parseMasterPreferredShares(new ByteArrayInputStream(mst), layout);
    }

    @Test
    @DisplayName("코스피 실제 줄 — 현대차 우선주 3종을 보통주에 모은다(주식 수 칸은 천 주)")
    void kospiRealLines() throws IOException {
        byte[] mst = file(line("005380", "현대차", HYUNDAI), line("005385", "현대차우", HYUNDAI_PREF),
                line("005387", "현대차2우B", HYUNDAI_2B), line("005389", "현대차3우B", HYUNDAI_3B));

        assertThat(parse(mst, StockStatusService.KOSPI_LAYOUT)).containsExactly(
                Map.entry("005380", new StockStatusService.ListedShares(203_466_000L, 59_417_000L)));
    }

    @Test
    @DisplayName("코스닥 실제 줄 — 끝자리가 영문이어도 앞 5자리가 같으면 짝이다, 우선주 없는 회사는 없다")
    void kosdaqRealLines() throws IOException {
        byte[] mst = file(line("034810", "해성산업", HAESUNG), line("03481K", "해성산업1우", HAESUNG_1),
                line("196170", "알테오젠", ALTEOGEN));

        assertThat(parse(mst, StockStatusService.KOSDAQ_LAYOUT)).containsExactly(
                Map.entry("034810", new StockStatusService.ListedShares(31_580_000L, 1_377_000L)));
    }

    @Test
    @DisplayName("영문이 섞인 보통주 코드도 — 0220W0 · 0220WL, 우선주 칸 9 도 우선주다")
    void alphanumericCodes() throws IOException {
        byte[] mst = file(line("0220W0", "한화머시너리앤서비스홀딩스", HANWHA_MS), line("0220WL", "한화머시너리앤서비스홀딩스3우B", HANWHA_MS_3B));

        assertThat(parse(mst, StockStatusService.KOSPI_LAYOUT)).containsExactly(
                Map.entry("0220W0", new StockStatusService.ListedShares(84_515_000L, 23_639_000L)));
    }

    @Test
    @DisplayName("짝인 보통주가 없으면 넣지 않는다 · ETF 는 주권이 아니다 · 짧은 줄은 건너뛴다")
    void orphansAndOthersAreIgnored() throws IOException {
        byte[] mst = file(line("005385", "현대차우", HYUNDAI_PREF), line("069500", "KODEX 200", KODEX200),
                "005930   KR7005930003삼성전자\r\n".getBytes(EUC_KR));

        assertThat(parse(mst, StockStatusService.KOSPI_LAYOUT)).isEmpty();
    }

    @Test
    @DisplayName("회사 수가 이상하면 칸 위치가 바뀐 것이다 — 11/5 실측 98")
    void plausibleRange() {
        assertThat(StockStatusService.plausiblePreferredCompanies(98)).isTrue();
        assertThat(StockStatusService.plausiblePreferredCompanies(0)).isFalse();
        assertThat(StockStatusService.plausiblePreferredCompanies(29)).isFalse();
        assertThat(StockStatusService.plausiblePreferredCompanies(1500)).isFalse();
    }

    // ==================== 동기화 연결 ====================

    private RestTemplate restTemplate;
    private StockStatusService service;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        StockPriceHistoryRepository history = mock(StockPriceHistoryRepository.class);
        when(history.findCodesWithAllZeroVolumeSince(any(), anyLong())).thenReturn(List.of());
        service = new StockStatusService(restTemplate, mock(TelegramNotificationService.class),
                mock(SectorStockConfig.class), history, mock(StockPriceRepository.class));
    }

    /** 코스피 {@code companies} 개 회사 — 앞 칸 회사마다 우선주 1종을 붙인다. 코스닥은 일반 종목만. */
    private void stubMasters(int companies, boolean withPreferred) throws IOException {
        List<byte[]> kospi = new ArrayList<>();
        kospi.add(line("005380", "현대차", HYUNDAI));
        kospi.add(line("005385", "현대차우", HYUNDAI_PREF));
        for (int i = 0; i < 120; i++) {
            int base = 100_000 + i * 10;
            kospi.add(line(String.format("%06d", base), "회사", HYUNDAI));
            if (withPreferred && i < companies) kospi.add(line(String.format("%06d", base + 5), "회사우", HYUNDAI_PREF));
        }
        List<byte[]> kosdaq = new ArrayList<>();
        for (int i = 0; i < 120; i++) kosdaq.add(line(String.format("%06d", 300_000 + i * 10), "회사", ALTEOGEN));
        when(restTemplate.exchange(contains("kospi_code"), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(zip(file(kospi.toArray(new byte[0][]))), HttpStatus.OK));
        when(restTemplate.exchange(contains("kosdaq_code"), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(zip(file(kosdaq.toArray(new byte[0][]))), HttpStatus.OK));
    }

    @Test
    @DisplayName("동기화하기 전에는 모른다 — 0 과 1, 그리고 '아직'")
    void beforeSync() {
        assertThat(service.isPreferredSharesLoaded()).isFalse();
        assertThat(service.preferredListedShares("005380")).isZero();
        assertThat(service.shareCountRatio("005380")).isEqualByComparingTo("1");
        assertThat(service.preferredListedShares(null)).isZero();
        assertThat(service.shareCountRatio(null)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("동기화하면 묻는 대로 답한다 — 우선주가 없는 회사는 0·1")
    void afterSync() throws IOException {
        stubMasters(60, true);

        service.syncListedStocks();

        assertThat(service.isPreferredSharesLoaded()).isTrue();
        assertThat(service.preferredListedShares("005380")).isEqualTo(22_839_000L);
        assertThat(service.shareCountRatio("005380")).isEqualByComparingTo(
                new BigDecimal("226305000").divide(new BigDecimal("203466000"), 6, java.math.RoundingMode.HALF_UP));
        assertThat(service.preferredListedShares("300000")).as("코스닥 우선주 없는 회사").isZero();
        assertThat(service.shareCountRatio("300000")).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("다음 회차 파일에서 칸을 못 읽으면 이전 값을 유지한다 — 틀린 분모보다 낫다")
    void brokenFileKeepsPrevious() throws IOException {
        stubMasters(60, true);
        service.syncListedStocks();
        assertThat(service.preferredListedShares("005380")).isEqualTo(22_839_000L);

        stubMasters(0, false);
        // 칸을 못 읽은 파일 — 우선주 줄이 사라져 회사 수가 1 개다
        service.syncListedStocks();

        assertThat(service.preferredListedShares("005380")).as("이전 값 유지").isEqualTo(22_839_000L);
        assertThat(service.isPreferredSharesLoaded()).isTrue();
    }
}
