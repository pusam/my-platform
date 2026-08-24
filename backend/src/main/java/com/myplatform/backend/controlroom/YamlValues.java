package com.myplatform.backend.controlroom;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Date;

/**
 * 관제실 YAML 블록에서 읽은 원시 값을 안전한 타입으로 바꾸는 헬퍼.
 *
 * <p><b>왜 필요한가</b>: snakeyaml 은 따옴표 없는 {@code 2026-09-16} 을 YAML 1.1 timestamp 로 보고
 * {@link java.util.Date} 로 <b>자동 변환</b>한다. 그대로 {@code String.valueOf} 하면
 * {@code "Wed Sep 16 09:00:00 KST 2026"} 이 되어 날짜 파싱이 전부 실패한다. 문서 작성자에게
 * "날짜에 따옴표를 붙여라"고 요구하는 대신 여기서 흡수한다.
 *
 * <p>Date 를 되돌릴 때는 <b>UTC 기준</b>으로 읽는다 — snakeyaml 이 날짜만 있는 값을 UTC 자정으로
 * 만들기 때문에, 서버 타임존이 무엇이든 원래 적힌 날짜가 그대로 나온다(KST 서버에서 하루 밀리는 것 방지).
 */
final class YamlValues {

    private YamlValues() {}

    /** 문자열 값. 빈 문자열은 null 로 접는다(미기입과 공백을 같게 취급). */
    static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 날짜 값. String / {@link java.util.Date} / {@link java.time.LocalDate} 를 받는다.
     *
     * @throws IllegalArgumentException 근사 표현이거나 형식이 어긋날 때 — 호출부가 파싱 오류로 보고한다
     */
    static LocalDate date(Object raw, String field) {
        if (raw == null) {
            throw new IllegalArgumentException(field + " 누락");
        }
        if (raw instanceof LocalDate d) {
            return d;
        }
        if (raw instanceof Date d) {
            // snakeyaml 이 이미 날짜로 해석한 값 — UTC 자정 기준으로 되돌린다.
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        String s = String.valueOf(raw).trim();
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field
                    + " 는 YYYY-MM-DD 확정 날짜여야 함 (근사 표현 금지, 받은 값: " + s + ")");
        }
    }
}
