package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KIS 종목마스터(.mst) 파싱 — 순수 함수 테스트.
 *
 * <p><b>지키는 것 1 (§4c)</b>: 실패를 "종목 0건"으로 위장하지 않는다. 파싱이 빈 집합을 돌려주면
 * 호출측 시장별 게이트와 &lt;100 게이트가 동기화를 취소하고 기존 목록을 유지한다.
 * 죽은 피드 위에서 activeStockCodes 가 오염되면 안 된다.
 *
 * <p><b>지키는 것 2</b>: 집합이 <b>축소되지 않는다</b>. 이 게이트는 "목록에 없으면 제외"라
 * 소스가 조금이라도 좁아지면 그 종목들이 무음으로 사라진다(fail-CLOSED). 2026-08-31 에
 * KIND corpList 대신 KIS 마스터를 고른 이유가 <b>우선주 포함</b>이었으므로, 우선주가 계속
 * 파싱되는지를 여기서 고정한다.
 *
 * <p><b>지키는 것 3</b>: 한글 종목명이 EUC-KR 가변 바이트라도 앞 9바이트(ASCII 단축코드) 파싱이
 * 깨지지 않는다 — 바이트 오프셋으로 뒤쪽 필드를 세려다 틀린 전례가 있다.
 */
class StockStatusServiceTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    /** 실제 마스터 레코드 모양: 단축코드(9, 좌측정렬·공백패딩) + 표준코드(12) + 한글명 + 뒤쪽 고정폭 필드. */
    private static byte[] record(String shortCode, String standardCode, String koreanName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] padded = String.format("%-9s", shortCode).getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(padded);
        out.writeBytes(standardCode.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(koreanName.getBytes(EUC_KR));       // ← 가변 바이트
        out.writeBytes("            ST1010101".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes("\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static byte[] mst(byte[]... records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] r : records) out.writeBytes(r);
        return out.toByteArray();
    }

    private static Set<String> parse(byte[] mst) throws IOException {
        return StockStatusService.parseMasterCodes(new ByteArrayInputStream(mst));
    }

    @Test
    @DisplayName("6자리 숫자 단축코드만 수집 — 한글명이 EUC-KR 가변 바이트여도 어긋나지 않는다")
    void parsesSixDigitCodes() throws IOException {
        byte[] file = mst(
                record("005930", "KR7005930003", "삼성전자"),
                record("000660", "KR7000660001", "SK하이닉스"),
                record("068270", "KR7068270008", "셀트리온"));

        assertThat(parse(file)).containsExactlyInAnyOrder("005930", "000660", "068270");
    }

    @Test
    @DisplayName("우선주가 빠지지 않는다 — 이 소스를 고른 이유이자 fail-CLOSED 방지선")
    void keepsPreferredShares() throws IOException {
        byte[] file = mst(
                record("005930", "KR7005930003", "삼성전자"),
                record("005935", "KR7005931001", "삼성전자우"),
                record("00088K", "KR7000885003", "한화3우B"),     // 영문 섞인 종류주식 코드
                record("0000D0", "KR7000004D06", "종류주식"));

        // 옛 KRX 파서는 \d{6} 만 받아 영문 섞인 381개를 조용히 버렸다 — 이 게이트에서
        // "목록에 없음"은 곧 제외(fail-CLOSED)라, 넉넉히 받는 쪽이 안전하다.
        assertThat(parse(file))
                .containsExactlyInAnyOrder("005930", "005935", "00088K", "0000D0");
    }

    @Test
    @DisplayName("6자리가 아닌 코드(ELW 의 9자리 F… 등)는 버린다")
    void dropsNonNumericCodes() throws IOException {
        byte[] file = mst(
                record("005930", "KR7005930003", "삼성전자"),
                record("F7010003", "KR5701000303", "한국ELW"),
                record("12345", "KR7123450000", "다섯자리"));

        assertThat(parse(file)).containsExactly("005930");
    }

    @Test
    @DisplayName("빈 줄·짧은 줄은 예외 없이 건너뛴다")
    void skipsShortLines() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("\n".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes("abc\n".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(record("005930", "KR7005930003", "삼성전자"));

        assertThat(parse(out.toByteArray())).containsExactly("005930");
    }

    @Test
    @DisplayName("내용 없는 마스터 → 빈 집합 (§4c: 호출측이 '수집 실패'로 처리해 동기화를 취소한다)")
    void emptyMasterYieldsEmptySet() throws IOException {
        assertThat(parse(new byte[0])).isEmpty();
    }

    @Test
    @DisplayName("zip 을 풀어 첫 엔트리를 파싱한다")
    void readsZippedMaster() throws IOException {
        byte[] file = mst(
                record("005930", "KR7005930003", "삼성전자"),
                record("005935", "KR7005931001", "삼성전자우"));

        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipped)) {
            zos.putNextEntry(new ZipEntry("kospi_code.mst"));
            zos.write(file);
            zos.closeEntry();
        }

        assertThat(StockStatusService.readZippedMaster(zipped.toByteArray()))
                .containsExactlyInAnyOrder("005930", "005935");
    }

    @Test
    @DisplayName("빈 zip → 빈 집합 (죽은 응답을 '상장 0건'으로 위장하지 않는다)")
    void emptyZipYieldsEmptySet() throws IOException {
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipped)) {
            zos.putNextEntry(new ZipEntry("placeholder/"));   // 디렉터리 엔트리만
            zos.closeEntry();
        }

        assertThat(StockStatusService.readZippedMaster(zipped.toByteArray())).isEmpty();
    }
}
