package com.myplatform.backend.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 종목명 조회 유틸리티
 * - API나 DB에서 종목명을 가져오지 못했을 때 최후의 폴백으로 사용
 * - 주요 종목 50개+ 하드코딩
 */
public class StockNameResolver {

    private static final Map<String, String> STOCK_NAME_MAP = new HashMap<>();

    static {
        // KOSPI 대형주
        STOCK_NAME_MAP.put("005930", "삼성전자");
        STOCK_NAME_MAP.put("000660", "SK하이닉스");
        STOCK_NAME_MAP.put("373220", "LG에너지솔루션");
        STOCK_NAME_MAP.put("207940", "삼성바이오로직스");
        STOCK_NAME_MAP.put("005380", "현대차");
        STOCK_NAME_MAP.put("006400", "삼성SDI");
        STOCK_NAME_MAP.put("035420", "NAVER");
        STOCK_NAME_MAP.put("035720", "카카오");
        STOCK_NAME_MAP.put("051910", "LG화학");
        STOCK_NAME_MAP.put("068270", "셀트리온");
        STOCK_NAME_MAP.put("012620", "삼성물산");
        STOCK_NAME_MAP.put("028260", "삼성물산");
        STOCK_NAME_MAP.put("105560", "KB금융");
        STOCK_NAME_MAP.put("055550", "신한지주");
        STOCK_NAME_MAP.put("012330", "현대모비스");
        STOCK_NAME_MAP.put("066570", "LG전자");
        STOCK_NAME_MAP.put("003550", "LG");
        STOCK_NAME_MAP.put("017670", "SK텔레콤");
        STOCK_NAME_MAP.put("034730", "SK");
        STOCK_NAME_MAP.put("096770", "SK이노베이션");
        STOCK_NAME_MAP.put("003670", "포스코퓨처엠");
        STOCK_NAME_MAP.put("000270", "기아");
        STOCK_NAME_MAP.put("086790", "하나금융지주");
        STOCK_NAME_MAP.put("032830", "삼성생명");
        STOCK_NAME_MAP.put("018260", "삼성에스디에스");
        STOCK_NAME_MAP.put("010130", "고려아연");
        STOCK_NAME_MAP.put("011200", "HMM");
        STOCK_NAME_MAP.put("009150", "삼성전기");
        STOCK_NAME_MAP.put("024110", "기업은행");
        STOCK_NAME_MAP.put("033780", "KT&G");
        STOCK_NAME_MAP.put("030200", "KT");
        STOCK_NAME_MAP.put("316140", "우리금융지주");
        STOCK_NAME_MAP.put("009540", "한국조선해양");
        STOCK_NAME_MAP.put("047050", "포스코인터내셔널");
        STOCK_NAME_MAP.put("000810", "삼성화재");
        STOCK_NAME_MAP.put("010950", "S-Oil");
        STOCK_NAME_MAP.put("015760", "한국전력");
        STOCK_NAME_MAP.put("034020", "두산에너빌리티");
        STOCK_NAME_MAP.put("329180", "현대중공업");
        STOCK_NAME_MAP.put("352820", "하이브");
        STOCK_NAME_MAP.put("259960", "크래프톤");
        STOCK_NAME_MAP.put("402340", "SK스퀘어");
        STOCK_NAME_MAP.put("000100", "유한양행");
        STOCK_NAME_MAP.put("003490", "대한항공");
        STOCK_NAME_MAP.put("004020", "현대제철");
        STOCK_NAME_MAP.put("005490", "POSCO홀딩스");
        STOCK_NAME_MAP.put("010140", "삼성중공업");
        STOCK_NAME_MAP.put("011170", "롯데케미칼");
        STOCK_NAME_MAP.put("036460", "한국가스공사");
        STOCK_NAME_MAP.put("047810", "한국항공우주");
        STOCK_NAME_MAP.put("078930", "GS");
        STOCK_NAME_MAP.put("090430", "아모레퍼시픽");
        STOCK_NAME_MAP.put("097950", "CJ제일제당");
        STOCK_NAME_MAP.put("139480", "이마트");

        // KOSDAQ
        STOCK_NAME_MAP.put("036570", "엔씨소프트");
        STOCK_NAME_MAP.put("247540", "에코프로비엠");
        STOCK_NAME_MAP.put("091990", "셀트리온헬스케어");
        STOCK_NAME_MAP.put("357780", "솔브레인");
        STOCK_NAME_MAP.put("068760", "셀트리온제약");
        STOCK_NAME_MAP.put("035900", "JYP Ent.");
        STOCK_NAME_MAP.put("293490", "카카오게임즈");
        STOCK_NAME_MAP.put("263750", "펄어비스");
        STOCK_NAME_MAP.put("112040", "위메이드");
        STOCK_NAME_MAP.put("041510", "에스엠");
        STOCK_NAME_MAP.put("086520", "에코프로");
        STOCK_NAME_MAP.put("328130", "루닛");
        STOCK_NAME_MAP.put("145020", "휴젤");
        STOCK_NAME_MAP.put("196170", "알테오젠");
        STOCK_NAME_MAP.put("383220", "F&F");
        STOCK_NAME_MAP.put("377300", "카카오페이");
        STOCK_NAME_MAP.put("035760", "CJ ENM");
        STOCK_NAME_MAP.put("251270", "넷마블");
        STOCK_NAME_MAP.put("323410", "카카오뱅크");
    }

    /**
     * 종목코드로 종목명 조회
     *
     * @param stockCode 종목코드 (6자리)
     * @return 종목명 (없으면 null)
     */
    public static String getName(String stockCode) {
        if (stockCode == null || stockCode.isEmpty()) {
            return null;
        }
        return STOCK_NAME_MAP.get(stockCode);
    }

    /**
     * 종목코드로 종목명 조회 (없으면 기본값 반환)
     *
     * @param stockCode 종목코드 (6자리)
     * @param defaultValue 기본값 (보통 stockCode)
     * @return 종목명 (없으면 defaultValue)
     */
    public static String getNameOrDefault(String stockCode, String defaultValue) {
        String name = getName(stockCode);
        return name != null ? name : defaultValue;
    }

    /**
     * 종목명이 누락되었는지 확인
     * - null, 빈 문자열, 또는 종목코드와 동일하면 누락으로 판단
     */
    public static boolean isNameMissing(String stockName, String stockCode) {
        return stockName == null || stockName.isEmpty() || stockName.equals(stockCode);
    }

    /**
     * 종목명 보정 (누락 시 맵에서 조회)
     */
    public static String resolveStockName(String stockName, String stockCode) {
        if (isNameMissing(stockName, stockCode)) {
            String resolved = getName(stockCode);
            return resolved != null ? resolved : stockCode;
        }
        return stockName;
    }
}
