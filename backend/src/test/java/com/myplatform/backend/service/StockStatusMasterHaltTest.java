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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 거래정지·정리매매는 KIS 종목마스터의 칸으로 잡는다(2026-11-04).
 *
 * <p>재현: 게이트는 '최근 봉 전부 거래량 0'으로만 정지를 알았는데, 일봉은 종합추천 후보 풀(약 650종목)만 받는다. 11/4 운영:
 * 마스터의 거래정지 110종목 중 최근 7일 봉이 있는 건 7종목뿐이라 103종목이 정상으로 통과했다 — 그중 60종목은 재무 행이 있고
 * 골드앤에스(PER 1.4·PBR 0.14)·세진티에스(PER 3.0)처럼 싸 보인다. 9/7 이오플로우가 마법의 공식 1위로 나간 사고와 같은 구멍이다.
 * 마스터 파일 앞쪽만 읽어서 '마스터는 상폐만 잡는다'고 적어 두었는데, 파일 끝 고정폭 구역에 거래정지·정리매매 칸이 있었다(공식 샘플
 * {@code kis_kospi_code_mst.py}·{@code kis_kosdaq_code_mst.py} 의 field_specs).
 *
 * <p>꼬리는 2026-11-04 실제 파일의 그 종목 줄이다.
 */
class StockStatusMasterHaltTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    /** 실제 꼬리(ASCII 227자) — STX: 거래정지 Y · 정리매매 N · 관리종목 Y. */
    private static final String KOSPI_HALTED = "ST3001600000000 NN0NNN NN NNNNNN0NNNNNNNN0000035300000100001YNY00NNN000000100N0900000000000000000000025001990091200000000003101700000000007754481750012       0 NNY000001024-00000288-00001012-1021-3610000020260630000001094   NNN";
    /** 실제 꼬리 — 삼성전자: 거래정지 N, 기준가 219,500(11/3 KRX 종가), 전일거래량 13,391,670. */
    private static final String KOSPI_NORMAL = "ST1002700130000 NN5YYY YY NNNNNN0NNNNNNNY0002195000000100001NNN00NNN000000040Y0900000133916700000000001001975061100000000584627800000000077804668500012       0 NYY00305372900146725200153268211884000031.3920260630012832581511NNY";
    /** 실제 꼬리(ASCII 221자) — 멤레이비티: 거래정지 Y. */
    private static final String KOSDAQ_HALTED = "ST3000000000000NNN N NNNNNN0NNNNNNNN0000006120000100001YNN00NNN000000100N0900000000000000000000010002007072500000000004151200000000004151209800012       0 NN000000352-00000023-00000038-0038-0000003920260630000000254   NNN";
    /** 실제 꼬리 — 알테오젠: 거래정지 N. */
    private static final String KOSDAQ_NORMAL = "ST1100600000000NNY Y NYNNNN0NNNNNNNY0002135000000100001NNN00NNN000000040Y0900000002895680000000005002014121200000000006965500000000003482756650012       0 NY0000014050000007350000011640101300000004420260630000148713   NNY";

    /** 한 줄 — 코드 9바이트 + 표준코드 12바이트 + 한글명(가변 바이트) + 꼬리 + CRLF. */
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

    /** 꼬리의 거래정지·정리매매 칸을 바꾼다. */
    private static String withFlags(String tail, int haltOffset, char halt, char liquidation) {
        StringBuilder b = new StringBuilder(tail);
        b.setCharAt(haltOffset, halt);
        b.setCharAt(haltOffset + 1, liquidation);
        return b.toString();
    }

    private static Set<String> halts(byte[] mst, StockStatusService.MasterLayout layout) throws IOException {
        return StockStatusService.parseMasterHalts(new ByteArrayInputStream(mst), layout);
    }

    @Test
    @DisplayName("재현: 코스피 실제 줄 — STX 는 거래정지, 삼성전자는 아니다")
    void kospiRealLines() throws IOException {
        byte[] mst = file(line("011810", "STX", KOSPI_HALTED), line("005930", "삼성전자", KOSPI_NORMAL));

        assertThat(halts(mst, StockStatusService.KOSPI_LAYOUT)).containsExactly("011810");
    }

    @Test
    @DisplayName("재현: 코스닥은 꼬리 길이·칸 위치가 다르다 — 멤레이비티는 거래정지, 알테오젠은 아니다")
    void kosdaqRealLines() throws IOException {
        byte[] mst = file(line("072770", "멤레이비티", KOSDAQ_HALTED), line("196170", "알테오젠", KOSDAQ_NORMAL));

        assertThat(halts(mst, StockStatusService.KOSDAQ_LAYOUT)).containsExactly("072770");
    }

    @Test
    @DisplayName("한글 이름 길이가 달라도 꼬리는 줄 끝에서 재므로 칸이 밀리지 않는다")
    void nameLengthDoesNotShiftFlags() throws IOException {
        byte[] mst = file(line("011810", "아주아주긴이름의회사주식회사", KOSPI_HALTED));

        assertThat(halts(mst, StockStatusService.KOSPI_LAYOUT)).containsExactly("011810");
    }

    @Test
    @DisplayName("정리매매도 뺀다 — 곧 상장폐지될 종목을 추천 풀에 두지 않는다")
    void liquidationCountsAsHalted() throws IOException {
        String liquidating = withFlags(KOSPI_NORMAL, StockStatusService.KOSPI_LAYOUT.haltOffset(), 'N', 'Y');

        assertThat(halts(file(line("004415", "서울식품우", liquidating)), StockStatusService.KOSPI_LAYOUT))
                .containsExactly("004415");
    }

    @Test
    @DisplayName("꼬리가 없는 짧은 줄은 판정하지 않는다 — 모르는 것을 정지로 보지 않는다")
    void shortLineIsIgnored() throws IOException {
        byte[] mst = file("005930   KR7005930003삼성전자\r\n".getBytes(EUC_KR));

        assertThat(halts(mst, StockStatusService.KOSPI_LAYOUT)).isEmpty();
    }

    // ==================== 게이트 연결 ====================

    private RestTemplate restTemplate;
    private StockPriceHistoryRepository history;
    private StockStatusService service;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        history = mock(StockPriceHistoryRepository.class);
        when(history.findCodesWithAllZeroVolumeSince(any(), anyLong())).thenReturn(List.of());
        service = new StockStatusService(restTemplate, mock(TelegramNotificationService.class),
                mock(SectorStockConfig.class), history, mock(StockPriceRepository.class));
    }

    /** 코스피·코스닥 각 {@code count} 종목 — 앞 {@code haltedEach} 종목만 거래정지. */
    private void stubMasters(int count, int haltedEach) throws IOException {
        stubMaster("kospi", 100_000, count, haltedEach, KOSPI_NORMAL, KOSPI_HALTED);
        stubMaster("kosdaq", 300_000, count, haltedEach, KOSDAQ_NORMAL, KOSDAQ_HALTED);
    }

    private void stubMaster(String market, int base, int count, int halted, String normal, String stopped) throws IOException {
        List<byte[]> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(line(String.format("%06d", base + i), "회사", i < halted ? stopped : normal));
        }
        when(restTemplate.exchange(contains(market + "_code"), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(zip(file(lines.toArray(new byte[0][]))), HttpStatus.OK));
    }

    @Test
    @DisplayName("재현: 봉이 없는 정지 종목도 게이트가 막는다 — 예전엔 봉이 있어야만 알았다")
    void gateBlocksHaltedWithoutBars() throws IOException {
        stubMasters(60, 1);

        service.syncListedStocks();

        assertThat(service.isActive("100000")).as("코스피 정지").isFalse();
        assertThat(service.isActive("300000")).as("코스닥 정지").isFalse();
        assertThat(service.activeStatus("100000")).isEqualTo(StockStatusService.ActiveStatus.HALTED);
        assertThat(service.isActive("100001")).isTrue();
        assertThat(service.activeStatus("300001")).isEqualTo(StockStatusService.ActiveStatus.ACTIVE);
    }

    @Test
    @DisplayName("거래가 재개되면 다음 동기화에서 풀린다")
    void resumedStockIsReleased() throws IOException {
        stubMasters(60, 1);
        service.syncListedStocks();
        assertThat(service.isActive("100000")).isFalse();

        stubMasters(60, 0);
        service.syncListedStocks();

        assertThat(service.isActive("100000")).isTrue();
    }

    @Test
    @DisplayName("상폐 판정엔 쓰지 않는다 — 정지 종목은 상장 유지라 재무 수집이 계속돼야 재개 때 바로 다룬다")
    void haltIsNotDelisting() throws IOException {
        stubMasters(60, 1);

        service.syncListedStocks();

        assertThat(service.isKnownDelisted("100000")).isFalse();
    }

    @Test
    @DisplayName("정지가 비정상적으로 많으면 칸 위치가 바뀐 것으로 보고 쓰지 않는다 — 멀쩡한 종목을 통째로 막는 게 더 나쁘다")
    void implausibleShareIsIgnored() throws IOException {
        stubMasters(60, 0);
        service.syncListedStocks();
        stubMasters(60, 40);   // 절반 이상이 정지

        service.syncListedStocks();

        assertThat(service.isActive("100000")).as("이전 목록 유지").isTrue();
        assertThat(service.isActive("100039")).isTrue();
    }

    @Test
    @DisplayName("상식 범위 판정은 순수 함수다 — 11/4 실측 120/3,963 은 통과")
    void plausibleShare() {
        assertThat(StockStatusService.plausibleHaltShare(120, 3963)).isTrue();
        assertThat(StockStatusService.plausibleHaltShare(0, 3963)).isTrue();
        assertThat(StockStatusService.plausibleHaltShare(1000, 3963)).isFalse();
        assertThat(StockStatusService.plausibleHaltShare(1, 0)).isFalse();
    }
}
