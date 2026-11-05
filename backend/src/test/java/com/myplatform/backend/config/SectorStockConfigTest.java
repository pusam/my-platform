package com.myplatform.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 섹터 표는 시장 탭 지도·종합판단 보드의 종목 섹터·섹터 상대강도, 그리고 시장 국면(섹터 평균의 평균)의 입력이다.
 * 2026-11-05 네이버 업종과 133종목을 전부 대조해서 나온 것들을 고정한다 — 코드가 틀려 다른 회사가 든 칸 3, 사업이 다른 회사 다수.
 * 로봇/AI 섹터엔 로봇주가 하나도 없었다(레인보우로보틱스 칸의 코드가 HD현대마린솔루션이었다).
 */
class SectorStockConfigTest {

    private final SectorStockConfig config = new SectorStockConfig();

    private List<String> codes(String sector) {
        return config.getSector(sector).getStockCodes();
    }

    @Test
    @DisplayName("재현: 코드가 틀려 다른 회사가 들어 있었다 — 솔트룩스 칸=솔루스첨단소재, 레인보우로보틱스 칸=HD현대마린솔루션, 일진머티리얼즈 칸=일진전기")
    void codesPointToTheNamedCompany() {
        assertThat(codes("ROBOT")).doesNotContain("336370", "443060").contains("304100", "277810");
        assertThat(codes("STEEL")).doesNotContain("103590");
        assertThat(codes("SHIPBUILDING")).as("HD현대마린솔루션은 조선이다").contains("443060");
        assertThat(codes("POWER")).as("일진전기는 전력기기다").contains("103590");
    }

    @Test
    @DisplayName("재현: 로봇/AI 에 전력기기·조선·금융 종목이 들어 있었다")
    void robotSectorHoldsRobotAndAiStocks() {
        assertThat(codes("ROBOT")).doesNotContain("267260", "298040", "042660", "377300", "281820", "078600", "090460", "035420");
    }

    @Test
    @DisplayName("재현: 원전/SMR 에 원전 회사가 두산에너빌리티 하나였다 — 대한항공·SKC·조선사가 들어 있었다")
    void nuclearSectorHoldsNuclearStocks() {
        assertThat(codes("NUCLEAR")).doesNotContain("003490", "011790", "009540", "042660", "112610", "267260", "298040", "010120", "006260");
        assertThat(codes("NUCLEAR")).contains("034020", "052690", "051600");
    }

    @Test
    @DisplayName("전력기기는 따로 둔다 — 원전 칸에 섞여 있던 대형주가 표에서 사라지지 않게")
    void powerEquipmentHasItsOwnSector() {
        assertThat(codes("POWER")).contains("267260", "298040", "010120", "006260");
        assertThat(config.getSector("POWER").getName()).isEqualTo("전력기기");
    }

    @Test
    @DisplayName("재현: 사업이 다른 회사 — 엔터의 셀트리온·안랩, 화학의 삼성전기, 철강의 한솔케미칼, 방산의 세방전지, 자동차의 SK네트웍스, 건설의 SK·LX인터내셔널")
    void sectorsDoNotHoldUnrelatedCompanies() {
        assertThat(codes("ENTERTAINMENT")).doesNotContain("068270", "053800");
        assertThat(codes("CHEMICAL")).doesNotContain("009150", "003550");
        assertThat(codes("STEEL")).doesNotContain("014680", "003670");
        assertThat(codes("DEFENSE")).doesNotContain("004490");
        assertThat(codes("AUTOMOTIVE")).doesNotContain("001740");
        assertThat(codes("CONSTRUCTION")).doesNotContain("034730", "001120");
    }

    @Test
    @DisplayName("재현: 2차전지에 현대차 우선주·현대모비스·현대로템이 들어 있었다")
    void batterySectorHoldsBatteryStocks() {
        assertThat(codes("BATTERY")).doesNotContain("005387", "012330", "064350").contains("066970", "450080");
    }

    @Test
    @DisplayName("조선/해운 — 전력기기·태양광 회사를 빼고, 해운사는 이름표에 맞게 남긴다")
    void shipbuildingNameMatchesMembers() {
        assertThat(config.getSector("SHIPBUILDING").getName()).isEqualTo("조선/해운");
        assertThat(codes("SHIPBUILDING")).doesNotContain("267260", "009830").contains("011200");
    }

    @Test
    @DisplayName("게임은 게임 섹터에만 — 엔터 칸에 겹쳐 있던 카카오게임즈·펄어비스가 같은 거래대금을 두 번 세지 않게")
    void gameStocksStayInGame() {
        assertThat(codes("ENTERTAINMENT")).doesNotContain("293490", "263750");
        assertThat(codes("GAME")).contains("293490", "263750");
    }

    @Test
    @DisplayName("모든 코드에 이름이 있고 한 섹터 안에 중복이 없다")
    void everyCodeHasNameAndNoDuplicateWithinSector() {
        for (SectorStockConfig.SectorInfo s : config.getAllSectors()) {
            assertThat(new HashSet<>(s.getStockCodes())).as(s.getName()).hasSameSizeAs(s.getStockCodes());
            assertThat(config.getStockNameMap().keySet()).as(s.getName()).containsAll(s.getStockCodes());
        }
    }

    @Test
    @DisplayName("이름표 — 코드가 가리키는 회사 이름으로 적는다(옛 이름표가 틀렸던 칸)")
    void fallbackNamesMatchTheCode() {
        assertThat(config.getStockNameMap()).containsEntry("336370", "솔루스첨단소재")
                .containsEntry("443060", "HD현대마린솔루션")
                .containsEntry("103590", "일진전기")
                .containsEntry("051630", "진양화학");
    }
}
