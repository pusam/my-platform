package com.myplatform.core.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class DateTimeUtil {

    public static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd";
    public static final String DEFAULT_DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final String ISO_DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";

    /**
     * KST (Asia/Seoul) — KIS API/한국 거래소 timezone 단일 출처.
     * 컨테이너 TZ 변경에 영향받지 않도록 명시 사용.
     */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * KST 기준 현재 시각. JVM/컨테이너 default zone 의존성 제거.
     * 사용처: KIS 시세 비교, 장 시간 판정, DB 저장 등 모든 한국 시간 의존 로직.
     */
    public static LocalDateTime kstNow() {
        return LocalDateTime.now(KST);
    }

    /**
     * KST 기준 오늘 날짜.
     */
    public static LocalDate kstToday() {
        return LocalDate.now(KST);
    }

    public static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern(DEFAULT_DATETIME_FORMAT));
    }

    public static String format(LocalDateTime dateTime, String pattern) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DateTimeFormatter.ofPattern(pattern));
    }

    public static String format(LocalDateTime dateTime) {
        return format(dateTime, DEFAULT_DATETIME_FORMAT);
    }

    public static LocalDateTime parse(String dateTimeStr, String pattern) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern(pattern));
    }

    public static LocalDateTime parse(String dateTimeStr) {
        return parse(dateTimeStr, DEFAULT_DATETIME_FORMAT);
    }
}

