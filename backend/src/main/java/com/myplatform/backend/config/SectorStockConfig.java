package com.myplatform.backend.config;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 섹터별 종목 매핑 설정
 * - 테마주 섹터 정의 및 관련 종목코드 관리
 */
@Component
public class SectorStockConfig {

    /**
     * 섹터 정보
     */
    public static class SectorInfo {
        private final String code;
        private final String name;
        private final String color;
        private final List<String> stockCodes;

        public SectorInfo(String code, String name, String color, List<String> stockCodes) {
            this.code = code;
            this.name = name;
            this.color = color;
            this.stockCodes = stockCodes;
        }

        public String getCode() { return code; }
        public String getName() { return name; }
        public String getColor() { return color; }
        public List<String> getStockCodes() { return stockCodes; }
    }

    private final Map<String, SectorInfo> sectors = new LinkedHashMap<>();

    // 종목코드 -> 종목명 매핑
    private final Map<String, String> stockNameMap = new HashMap<>();

    public SectorStockConfig() {
        initStockNames();
        initSectors();
    }

    /**
     * 종목명 매핑 초기화
     */
    private void initStockNames() {
        // 반도체
        stockNameMap.put("005930", "삼성전자");
        stockNameMap.put("000660", "SK하이닉스");
        stockNameMap.put("402340", "SK스퀘어");
        stockNameMap.put("042700", "한미반도체");
        stockNameMap.put("000990", "DB하이텍");
        stockNameMap.put("058470", "리노공업");
        stockNameMap.put("036930", "주성엔지니어링");
        stockNameMap.put("403870", "HPSP");
        stockNameMap.put("357780", "솔브레인");
        stockNameMap.put("240810", "원익IPS");

        // 2차전지
        stockNameMap.put("373220", "LG에너지솔루션");
        stockNameMap.put("006400", "삼성SDI");
        stockNameMap.put("051910", "LG화학");
        stockNameMap.put("086520", "에코프로");
        stockNameMap.put("247540", "에코프로비엠");
        stockNameMap.put("003670", "포스코퓨처엠");
        stockNameMap.put("005387", "현대차2우B");
        stockNameMap.put("012330", "현대모비스");
        stockNameMap.put("096770", "SK이노베이션");
        stockNameMap.put("064350", "현대로템");

        // 로봇/AI
        stockNameMap.put("267260", "HD현대일렉트릭");
        stockNameMap.put("336370", "솔트룩스");
        stockNameMap.put("090460", "비에이치");
        stockNameMap.put("443060", "레인보우로보틱스");
        stockNameMap.put("377300", "카카오페이");
        stockNameMap.put("035420", "NAVER");
        stockNameMap.put("298040", "효성중공업");
        stockNameMap.put("281820", "케이씨텍");
        stockNameMap.put("078600", "대주전자재료");
        stockNameMap.put("042660", "한화오션");

        // 원전/SMR
        stockNameMap.put("009540", "한국조선해양");
        stockNameMap.put("034020", "두산에너빌리티");
        stockNameMap.put("112610", "씨에스윈드");
        stockNameMap.put("010120", "LS ELECTRIC");
        stockNameMap.put("003490", "대한항공");
        stockNameMap.put("011790", "SKC");
        stockNameMap.put("006260", "LS");

        // 조선
        stockNameMap.put("010140", "삼성중공업");
        stockNameMap.put("267250", "HD현대");
        stockNameMap.put("009830", "한화솔루션");
        stockNameMap.put("010620", "HD현대미포");
        stockNameMap.put("003280", "흥아해운");
        stockNameMap.put("011200", "HMM");
        stockNameMap.put("028670", "팬오션");

        // 방산
        stockNameMap.put("012450", "한화에어로스페이스");
        stockNameMap.put("047810", "한국항공우주");
        stockNameMap.put("000880", "한화");
        stockNameMap.put("103140", "풍산");
        stockNameMap.put("079550", "LIG넥스원");
        stockNameMap.put("272210", "한화시스템");
        stockNameMap.put("004490", "세방전지");

        // 바이오
        stockNameMap.put("207940", "삼성바이오로직스");
        stockNameMap.put("068270", "셀트리온");
        stockNameMap.put("326030", "SK바이오팜");
        stockNameMap.put("091990", "셀트리온헬스케어");
        stockNameMap.put("128940", "한미약품");
        stockNameMap.put("006280", "녹십자");
        stockNameMap.put("000100", "유한양행");
        stockNameMap.put("302440", "SK바이오사이언스");
        stockNameMap.put("145020", "휴젤");
        stockNameMap.put("950160", "코오롱티슈진");

        // 엔터
        stockNameMap.put("352820", "하이브");
        stockNameMap.put("041510", "SM");
        stockNameMap.put("122870", "YG엔터테인먼트");
        stockNameMap.put("035900", "JYP Ent.");
        stockNameMap.put("035720", "카카오");
        stockNameMap.put("053800", "안랩");
        stockNameMap.put("293490", "카카오게임즈");
        stockNameMap.put("263750", "펄어비스");

        // 자동차/운송장비
        stockNameMap.put("005380", "현대차");
        stockNameMap.put("000270", "기아");
        stockNameMap.put("018880", "한온시스템");
        stockNameMap.put("011210", "현대위아");
        stockNameMap.put("204320", "만도");
        stockNameMap.put("161390", "한국타이어앤테크놀로지");
        stockNameMap.put("001740", "SK네트웍스");
        stockNameMap.put("004490", "세방전지");
        stockNameMap.put("010060", "OCI홀딩스");
        stockNameMap.put("214370", "케어젠");

        // 금융
        stockNameMap.put("105560", "KB금융");
        stockNameMap.put("055550", "신한지주");
        stockNameMap.put("086790", "하나금융지주");
        stockNameMap.put("316140", "우리금융지주");
        stockNameMap.put("138930", "BNK금융지주");
        stockNameMap.put("032830", "삼성생명");
        stockNameMap.put("000810", "삼성화재");
        stockNameMap.put("005830", "DB손해보험");
        stockNameMap.put("139130", "DGB금융지주");
        stockNameMap.put("175330", "JB금융지주");

        // 건설/인프라
        stockNameMap.put("028260", "삼성물산");
        stockNameMap.put("000720", "현대건설");
        stockNameMap.put("006360", "GS건설");
        stockNameMap.put("047040", "대우건설");
        stockNameMap.put("294870", "HDC현대산업개발");
        stockNameMap.put("000210", "DL");
        stockNameMap.put("001120", "LX인터내셔널");
        stockNameMap.put("034730", "SK");
        stockNameMap.put("375500", "DL이앤씨");
        stockNameMap.put("009410", "태영건설");

        // 화학/소재
        stockNameMap.put("011170", "롯데케미칼");
        stockNameMap.put("011780", "금호석유");
        stockNameMap.put("006120", "SK디스커버리");
        stockNameMap.put("009150", "삼성전기");
        stockNameMap.put("010950", "S-Oil");
        stockNameMap.put("078930", "GS");
        stockNameMap.put("051630", "진에어");
        stockNameMap.put("003550", "LG");
        stockNameMap.put("003490", "대한항공");
        stockNameMap.put("180640", "한진칼");

        // 통신
        stockNameMap.put("017670", "SK텔레콤");
        stockNameMap.put("030200", "KT");
        stockNameMap.put("032640", "LG유플러스");
        stockNameMap.put("036570", "엔씨소프트");
        stockNameMap.put("259960", "크래프톤");
        stockNameMap.put("251270", "넷마블");

        // 유통/소비재
        stockNameMap.put("004170", "신세계");
        stockNameMap.put("139480", "이마트");
        stockNameMap.put("097950", "CJ제일제당");
        stockNameMap.put("051900", "LG생활건강");
        stockNameMap.put("090430", "아모레퍼시픽");
        stockNameMap.put("282330", "BGF리테일");
        stockNameMap.put("069960", "현대백화점");
        stockNameMap.put("023530", "롯데쇼핑");
        stockNameMap.put("005300", "롯데칠성");
        stockNameMap.put("033780", "KT&G");

        // 철강/금속
        stockNameMap.put("005490", "POSCO홀딩스");
        stockNameMap.put("004020", "현대제철");
        stockNameMap.put("010130", "고려아연");
        stockNameMap.put("001230", "동국제강");
        stockNameMap.put("103590", "일진머티리얼즈");
        stockNameMap.put("058430", "포스코스틸리온");
        stockNameMap.put("008350", "남선알미늄");
        stockNameMap.put("014680", "한솔케미칼");
        stockNameMap.put("016380", "KG동부제철");
        stockNameMap.put("004890", "동일산업");

        // 게임
        stockNameMap.put("259960", "크래프톤");
        stockNameMap.put("251270", "넷마블");
        stockNameMap.put("036570", "엔씨소프트");
        stockNameMap.put("263750", "펄어비스");
        stockNameMap.put("293490", "카카오게임즈");
        stockNameMap.put("112040", "위메이드");
        stockNameMap.put("078340", "컴투스");
        stockNameMap.put("194480", "데브시스터즈");
        stockNameMap.put("041140", "넥슨지티");
        stockNameMap.put("069080", "웹젠");

        // 기타 주요 종목
        stockNameMap.put("066570", "LG전자");
    }

    private void initSectors() {
        // 1. 반도체
        sectors.put("SEMICONDUCTOR", new SectorInfo(
            "SEMICONDUCTOR",
            "반도체",
            "#4F46E5",
            Arrays.asList(
                "005930", // 삼성전자
                "000660", // SK하이닉스
                "402340", // SK스퀘어
                "042700", // 한미반도체
                "000990", // DB하이텍
                "058470", // 리노공업
                "036930", // 주성엔지니어링
                "403870", // HPSP
                "357780", // 솔브레인
                "240810"  // 원익IPS
            )
        ));

        // 2. 2차전지 (전고체)
        sectors.put("BATTERY", new SectorInfo(
            "BATTERY",
            "2차전지/전고체",
            "#10B981",
            Arrays.asList(
                "373220", // LG에너지솔루션
                "006400", // 삼성SDI
                "051910", // LG화학
                "086520", // 에코프로
                "247540", // 에코프로비엠
                "003670", // 포스코퓨처엠
                "005387", // 현대차2우B
                "012330", // 현대모비스
                "096770", // SK이노베이션
                "064350"  // 현대로템
            )
        ));

        // 3. 로봇/AI
        sectors.put("ROBOT", new SectorInfo(
            "ROBOT",
            "로봇/AI",
            "#F59E0B",
            Arrays.asList(
                "267260", // HD현대일렉트릭
                "336370", // 솔트룩스
                "090460", // 비에이치
                "443060", // 레인보우로보틱스
                "377300", // 카카오페이
                "035420", // NAVER
                "298040", // 효성중공업
                "281820", // 케이씨텍
                "078600", // 대주전자재료
                "042660"  // 한화오션
            )
        ));

        // 4. 원전/SMR
        sectors.put("NUCLEAR", new SectorInfo(
            "NUCLEAR",
            "원전/SMR",
            "#EF4444",
            Arrays.asList(
                "009540", // 한국조선해양
                "267260", // HD현대일렉트릭
                "034020", // 두산에너빌리티
                "112610", // 씨에스윈드
                "298040", // 효성중공업
                "042660", // 한화오션
                "010120", // LS ELECTRIC
                "003490", // 대한항공
                "011790", // SKC
                "006260"  // LS
            )
        ));

        // 5. 조선
        sectors.put("SHIPBUILDING", new SectorInfo(
            "SHIPBUILDING",
            "조선",
            "#3B82F6",
            Arrays.asList(
                "009540", // 한국조선해양
                "010140", // 삼성중공업
                "042660", // 한화오션
                "267250", // HD현대
                "267260", // HD현대일렉트릭
                "009830", // 한화솔루션
                "010620", // HD현대미포
                "003280", // 흥아해운
                "011200", // HMM
                "028670"  // 팬오션
            )
        ));

        // 6. 방산
        sectors.put("DEFENSE", new SectorInfo(
            "DEFENSE",
            "방산",
            "#8B5CF6",
            Arrays.asList(
                "012450", // 한화에어로스페이스
                "047810", // 한국항공우주
                "064350", // 현대로템
                "000880", // 한화
                "103140", // 풍산
                "079550", // LIG넥스원
                "272210", // 한화시스템
                "042660", // 한화오션
                "009540", // 한국조선해양
                "004490"  // 세방전지
            )
        ));

        // 7. 바이오/헬스케어
        sectors.put("BIO", new SectorInfo(
            "BIO",
            "바이오/헬스케어",
            "#EC4899",
            Arrays.asList(
                "207940", // 삼성바이오로직스
                "068270", // 셀트리온
                "326030", // SK바이오팜
                "091990", // 셀트리온헬스케어
                "128940", // 한미약품
                "006280", // 녹십자
                "000100", // 유한양행
                "302440", // SK바이오사이언스
                "145020", // 휴젤
                "950160"  // 코오롱티슈진
            )
        ));

        // 8. 엔터/미디어
        sectors.put("ENTERTAINMENT", new SectorInfo(
            "ENTERTAINMENT",
            "엔터/미디어",
            "#F97316",
            Arrays.asList(
                "352820", // 하이브
                "041510", // SM
                "122870", // YG엔터테인먼트
                "035900", // JYP Ent.
                "035420", // NAVER
                "035720", // 카카오
                "068270", // 셀트리온
                "053800", // 안랩
                "293490", // 카카오게임즈
                "263750"  // 펄어비스
            )
        ));

        // 9. 자동차/운송장비
        sectors.put("AUTOMOTIVE", new SectorInfo(
            "AUTOMOTIVE",
            "자동차/운송장비",
            "#06B6D4",
            Arrays.asList(
                "005380", // 현대차
                "000270", // 기아
                "012330", // 현대모비스
                "018880", // 한온시스템
                "011210", // 현대위아
                "204320", // 만도
                "161390", // 한국타이어앤테크놀로지
                "064350", // 현대로템
                "001740", // SK네트웍스
                "004490"  // 세방전지
            )
        ));

        // 10. 금융
        sectors.put("FINANCE", new SectorInfo(
            "FINANCE",
            "금융",
            "#14B8A6",
            Arrays.asList(
                "105560", // KB금융
                "055550", // 신한지주
                "086790", // 하나금융지주
                "316140", // 우리금융지주
                "138930", // BNK금융지주
                "032830", // 삼성생명
                "000810", // 삼성화재
                "005830", // DB손해보험
                "139130", // DGB금융지주
                "175330"  // JB금융지주
            )
        ));

        // 11. 건설/인프라
        sectors.put("CONSTRUCTION", new SectorInfo(
            "CONSTRUCTION",
            "건설/인프라",
            "#78716C",
            Arrays.asList(
                "028260", // 삼성물산
                "000720", // 현대건설
                "006360", // GS건설
                "047040", // 대우건설
                "294870", // HDC현대산업개발
                "000210", // DL
                "001120", // LX인터내셔널
                "034730", // SK
                "375500", // DL이앤씨
                "009410"  // 태영건설
            )
        ));

        // 12. 화학/소재
        sectors.put("CHEMICAL", new SectorInfo(
            "CHEMICAL",
            "화학/소재",
            "#A855F7",
            Arrays.asList(
                "051910", // LG화학
                "011170", // 롯데케미칼
                "011780", // 금호석유
                "009830", // 한화솔루션
                "006120", // SK디스커버리
                "009150", // 삼성전기
                "010950", // S-Oil
                "078930", // GS
                "003550", // LG
                "011790"  // SKC
            )
        ));

        // 13. 통신
        sectors.put("TELECOM", new SectorInfo(
            "TELECOM",
            "통신",
            "#0EA5E9",
            Arrays.asList(
                "017670", // SK텔레콤
                "030200", // KT
                "032640", // LG유플러스
                "035420", // NAVER
                "035720", // 카카오
                "377300", // 카카오페이
                "066570", // LG전자
                "009150", // 삼성전기
                "005930", // 삼성전자
                "000660"  // SK하이닉스
            )
        ));

        // 14. 유통/소비재
        sectors.put("RETAIL", new SectorInfo(
            "RETAIL",
            "유통/소비재",
            "#F43F5E",
            Arrays.asList(
                "004170", // 신세계
                "139480", // 이마트
                "097950", // CJ제일제당
                "051900", // LG생활건강
                "090430", // 아모레퍼시픽
                "282330", // BGF리테일
                "069960", // 현대백화점
                "023530", // 롯데쇼핑
                "005300", // 롯데칠성
                "033780"  // KT&G
            )
        ));

        // 15. 철강/금속
        sectors.put("STEEL", new SectorInfo(
            "STEEL",
            "철강/금속",
            "#64748B",
            Arrays.asList(
                "005490", // POSCO홀딩스
                "004020", // 현대제철
                "010130", // 고려아연
                "001230", // 동국제강
                "103590", // 일진머티리얼즈
                "003670", // 포스코퓨처엠
                "008350", // 남선알미늄
                "014680", // 한솔케미칼
                "016380", // KG동부제철
                "004890"  // 동일산업
            )
        ));

        // 16. 게임
        sectors.put("GAME", new SectorInfo(
            "GAME",
            "게임",
            "#84CC16",
            Arrays.asList(
                "259960", // 크래프톤
                "251270", // 넷마블
                "036570", // 엔씨소프트
                "263750", // 펄어비스
                "293490", // 카카오게임즈
                "112040", // 위메이드
                "078340", // 컴투스
                "194480", // 데브시스터즈
                "041140", // 넥슨지티
                "069080"  // 웹젠
            )
        ));
    }

    /**
     * 모든 섹터 목록 조회
     */
    public List<SectorInfo> getAllSectors() {
        return new ArrayList<>(sectors.values());
    }

    /**
     * 특정 섹터 조회
     */
    public SectorInfo getSector(String sectorCode) {
        return sectors.get(sectorCode);
    }

    /**
     * 섹터 코드 목록
     */
    public Set<String> getSectorCodes() {
        return sectors.keySet();
    }

    /**
     * 종목코드로 종목명 조회
     */
    public String getStockName(String stockCode) {
        return stockNameMap.getOrDefault(stockCode, stockCode);
    }

    /**
     * 전체 종목명 맵 조회
     */
    public Map<String, String> getStockNameMap() {
        return stockNameMap;
    }
}
