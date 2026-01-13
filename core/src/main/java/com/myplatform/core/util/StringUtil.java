package com.myplatform.core.util;

import java.util.UUID;

public class StringUtil {

    public static boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }

    public static String generateUUID() {
        return UUID.randomUUID().toString();
    }

    public static String generateUUIDWithoutHyphen() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String trim(String str) {
        return str == null ? null : str.trim();
    }

    public static String truncate(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength);
    }

    public static String mask(String str, int startIndex, int endIndex) {
        if (isEmpty(str) || startIndex >= endIndex || endIndex > str.length()) {
            return str;
        }

        StringBuilder masked = new StringBuilder(str);
        for (int i = startIndex; i < endIndex; i++) {
            masked.setCharAt(i, '*');
        }
        return masked.toString();
    }
}

