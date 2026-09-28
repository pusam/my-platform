package com.myplatform.backend.youtubeopinion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 발언 원문 속 금액 표기 → 숫자. 순수 함수. 목표가격이 <b>원문에 실제로 있는 숫자</b>인지 대조할 때만 쓴다
 * (모델이 원문에 없는 목표가를 채워 넣는 것을 막는다).
 * 읽는 표기: "85,000원" · "85000" · "8만 5천원" · "8.5만" · "85만원".
 */
public final class KoreanAmounts {

    private KoreanAmounts() {}

    private static final Pattern MAN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*만(?:\\s*(\\d+)\\s*천)?");
    private static final Pattern PLAIN = Pattern.compile("(?<![\\d.])(\\d{1,3}(?:,\\d{3})+|\\d{3,})(?![\\d.]|\\s*만)");

    public static List<BigDecimal> extract(String text) {
        List<BigDecimal> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = MAN.matcher(text);
        while (m.find()) {
            BigDecimal v = new BigDecimal(m.group(1)).multiply(BigDecimal.valueOf(10_000));
            if (m.group(2) != null) v = v.add(new BigDecimal(m.group(2)).multiply(BigDecimal.valueOf(1_000)));
            out.add(v);
        }
        Matcher p = PLAIN.matcher(text);
        while (p.find()) out.add(new BigDecimal(p.group(1).replace(",", "")));
        return out;
    }

    /** 원문 금액 중 하나와 0.5% 이내로 같으면 true. */
    public static boolean appearsIn(BigDecimal target, String text) {
        if (target == null || target.signum() <= 0) return false;
        for (BigDecimal v : extract(text)) {
            if (v.signum() <= 0) continue;
            BigDecimal diff = v.subtract(target).abs();
            if (diff.compareTo(target.multiply(new BigDecimal("0.005"))) <= 0) return true;
        }
        return false;
    }
}
