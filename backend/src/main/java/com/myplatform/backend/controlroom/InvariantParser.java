package com.myplatform.backend.controlroom;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code CLAUDE.md} 설계 불변식 목록 파서 — 순수 함수(파일 I/O 없음).
 *
 * <p>크루(특히 FIREWALL)가 초안을 대조할 기준이다. 목업처럼 불변식을 코드에 하드코딩하면 CLAUDE.md 가
 * 바뀔 때 조용히 어긋나므로, <b>실제 문서의 소제목만</b> 뽑아 쓴다. 본문은 너무 길어 컨텍스트 예산을
 * 잡아먹으므로 제외한다 — 제목만으로도 "이 초안이 어떤 불변식과 충돌하는가"는 판단 가능하다.
 *
 * <p>§4c: 섹션을 못 찾으면 빈 목록이 아니라 {@code dataAvailable=false} 로 돌려준다. 크루에게
 * "불변식 없음"을 주면 FIREWALL 이 아무 제약 없이 승인해 버린다.
 */
public final class InvariantParser {

    private InvariantParser() {}

    /** 불변식 섹션을 식별하는 문구 — CLAUDE.md 의 h2 제목에 포함된 고정 표현. */
    private static final String SECTION_MARKER = "설계 불변식";

    public record Result(List<String> invariants, boolean dataAvailable) {}

    public static Result parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new Result(List.of(), false);
        }

        List<String> invariants = new ArrayList<>();
        boolean inSection = false;
        boolean sectionFound = false;

        for (String rawLine : markdown.split("\\R")) {
            String line = rawLine.trim();

            if (line.startsWith("## ")) {
                // h2 를 만나면 섹션 진입/이탈이 갈린다.
                inSection = line.contains(SECTION_MARKER);
                if (inSection) sectionFound = true;
                continue;
            }
            if (!inSection) continue;

            if (line.startsWith("### ")) {
                String title = clean(line.substring(4));
                if (!title.isEmpty()) invariants.add(title);
            }
        }
        return new Result(List.copyOf(invariants), sectionFound && !invariants.isEmpty());
    }

    /** 마크다운 장식 제거 — 프롬프트에 그대로 들어가므로 기호 노이즈를 줄인다. */
    private static String clean(String raw) {
        return raw.replace("**", "").replace("`", "").replaceAll("\\s+", " ").trim();
    }
}
