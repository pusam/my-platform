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

        // 기타 주요 종목 (수급탐지기 기본 종목)
        stockNameMap.put("005380", "현대차");
        stockNameMap.put("000270", "기아");
        stockNameMap.put("066570", "LG전자");
        stockNameMap.put("055550", "신한지주");
        stockNameMap.put("105560", "KB금융");
        stockNameMap.put("051900", "LG생활건강");
        stockNameMap.put("017670", "SK텔레콤");
        stockNameMap.put("030200", "KT");
        stockNameMap.put("032830", "삼성생명");
        stockNameMap.put("086790", "하나금융지주");
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
