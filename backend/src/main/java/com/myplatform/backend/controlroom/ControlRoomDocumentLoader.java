package com.myplatform.backend.controlroom;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 관제실이 읽는 레포 문서 로더 — <b>읽기 전용</b>.
 *
 * <p>운영 이미지엔 {@code app.jar} 하나만 들어가고 레포 체크아웃이 없다. 그래서 빌드 시
 * {@code processResources} 가 문서를 {@code classpath:control-room/} 로 복사하고 여기서 그걸 읽는다.
 * 결과적으로 문서는 <b>이미지와 함께 버전이 고정</b>된다 — 내용을 고치려면 커밋 후 재배포다.
 *
 * <p>해석 순서:
 * <ol>
 *   <li>{@code control-room.docs-dir} 로 지정한 외부 디렉터리 — 서버에서 재배포 없이 고쳐야 할 때 마운트</li>
 *   <li>classpath {@code control-room/} — 운영 기본 경로</li>
 *   <li>레포 상대 경로 — 로컬 개발(IDE·테스트)에서 편집 즉시 반영</li>
 * </ol>
 *
 * <p>§4c: 어디서도 못 찾으면 빈 문자열이 아니라 {@code null} 을 돌려준다. 호출부가 "0건"이 아니라
 * "데이터 없음"으로 표시해야 하기 때문이다.
 */
@Slf4j
@Component
public class ControlRoomDocumentLoader {

    public static final String SCHEDULE_DECISIONS = "SCHEDULE_DECISIONS.md";
    public static final String CONTROL_ROOM_FLAGS = "CONTROL_ROOM_FLAGS.md";
    public static final String CLAUDE_MD = "CLAUDE.md";

    /** 외부 오버라이드 디렉터리. 비어 있으면 classpath → 레포 상대 순으로 찾는다. */
    private final String docsDir;

    public ControlRoomDocumentLoader(@Value("${control-room.docs-dir:}") String docsDir) {
        this.docsDir = docsDir;
    }

    /** 문서 원문. 못 찾으면 null(§4c — 빈 문자열로 위장하지 않는다). */
    public String load(String fileName) {
        if (docsDir != null && !docsDir.isBlank()) {
            String fromDir = readFile(Path.of(docsDir, fileName));
            if (fromDir != null) return fromDir;
            log.warn("[관제실] docs-dir 에 {} 없음 — classpath 로 폴백", fileName);
        }

        String fromClasspath = readClasspath("control-room/" + fileName);
        if (fromClasspath != null) return fromClasspath;

        // 로컬 개발 폴백 — 실행 위치가 레포 루트일 수도, backend/ 일 수도 있다.
        for (Path candidate : devCandidates(fileName)) {
            String s = readFile(candidate);
            if (s != null) return s;
        }

        log.warn("[관제실] 문서를 찾지 못함: {} (docs-dir='{}') — 해당 패널은 '데이터 없음'으로 표시된다",
                fileName, docsDir);
        return null;
    }

    private static List<Path> devCandidates(String fileName) {
        if (CLAUDE_MD.equals(fileName)) {
            return List.of(Path.of(fileName), Path.of("..", fileName));
        }
        return List.of(Path.of("docs", fileName), Path.of("..", "docs", fileName));
    }

    private static String readFile(Path path) {
        try {
            if (!Files.isRegularFile(path)) return null;
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[관제실] 문서 읽기 실패 {}: {}", path, e.getMessage());
            return null;
        }
    }

    private static String readClasspath(String location) {
        ClassPathResource resource = new ClassPathResource(location);
        if (!resource.exists()) return null;
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[관제실] classpath 문서 읽기 실패 {}: {}", location, e.getMessage());
            return null;
        }
    }
}
