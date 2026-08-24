package com.myplatform.backend.controlroom;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 에렌 결론의 {@code "액션: A | B"} 줄 파서 — 순수 함수.
 *
 * <p><b>버튼은 새 지시를 보내는 것뿐이고 아무것도 실행하지 않는다.</b> 여기서 뽑는 건 다음 지시문
 * 후보이지 실행 명령이 아니다.
 *
 * <p>파싱에 실패하면 빈 목록이다 — 버튼이 안 뜰 뿐 결론 텍스트는 그대로 보인다.
 */
public final class CrewActionParser {

    private CrewActionParser() {}

    private static final Pattern ACTION_LINE =
            Pattern.compile("^\\s*액션\\s*[:：]\\s*(.+)$", Pattern.MULTILINE);

    /** 최대 2개 — 목업과 동일. 그 이상은 결론이 아니라 목록이 된다. */
    private static final int MAX_ACTIONS = 2;

    public static List<String> extract(String text) {
        if (text == null || text.isBlank()) return List.of();
        Matcher m = ACTION_LINE.matcher(text);
        if (!m.find()) return List.of();

        return Arrays.stream(m.group(1).split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(MAX_ACTIONS)
                .toList();
    }

    /** 액션 줄을 걷어낸 본문 — 화면은 버튼으로 따로 그리므로 텍스트에서 중복 노출하지 않는다. */
    public static String stripActionLine(String text) {
        if (text == null) return null;
        return ACTION_LINE.matcher(text).replaceAll("").stripTrailing();
    }
}
