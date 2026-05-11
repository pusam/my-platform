package com.myplatform.backend.util;

import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * 업로드 파일 보안 검증 — 실행 가능 파일 / 서버 코드 / XSS 위험 형식 차단.
 *
 * 블랙리스트 방식: 첨부 파일 다양성(이미지·문서·압축·hwp·csv 등) 을 보존하면서 명백한 위험만 차단.
 * 호출처 BoardService.saveFile, FileManagementService.uploadFile, UserController 프로필 업로드.
 */
public final class FileUploadValidator {

    /** 차단 확장자 — 모두 lowercase 비교. */
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            // 실행 파일
            ".exe", ".bat", ".cmd", ".sh", ".com", ".msi", ".dll", ".scr", ".app", ".bin",
            // 서버 사이드 코드
            ".jsp", ".jspx", ".php", ".phtml", ".asp", ".aspx", ".cgi", ".pl", ".py", ".rb",
            ".jar", ".war", ".ear", ".class",
            // 스크립트
            ".js", ".mjs", ".vbs", ".vbe", ".ps1", ".psm1",
            // 브라우저 렌더 시 XSS / 스크립트 실행 위험
            ".html", ".htm", ".xhtml", ".svg", ".svgz",
            // 매크로 / 단축 / 레지스트리
            ".chm", ".lnk", ".url", ".reg", ".inf"
    );

    private FileUploadValidator() {}

    /**
     * 업로드 검증 — 차단 확장자면 IllegalArgumentException 던짐.
     * 호출자가 이 예외를 받아 사용자에게 친절한 메시지로 전달해야 함.
     */
    public static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("파일이 비어있습니다.");
        }
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("파일 이름이 없습니다.");
        }
        // 경로 traversal 방어 — saveFile 측에서 UUID 로 storedFilename 만들지만 originalFilename 도 sanity 체크.
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("파일 이름에 허용되지 않는 문자가 포함되었습니다.");
        }
        // NULL byte injection 방어 — 일부 파일시스템/OS API 가 NULL 에서 잘림 → 확장자 우회 가능
        if (name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("파일 이름에 잘못된 문자가 포함되었습니다.");
        }
        // dotfile (.htaccess, .env 등) 차단 — 점으로 시작 = 확장자만 있는 형태
        if (name.startsWith(".")) {
            throw new IllegalArgumentException("점(.)으로 시작하는 파일은 업로드할 수 없습니다.");
        }
        if (!name.contains(".")) {
            throw new IllegalArgumentException("파일 확장자가 없습니다.");
        }
        String ext = name.substring(name.lastIndexOf(".")).toLowerCase();
        if (BLOCKED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("업로드가 차단된 파일 형식입니다: " + ext);
        }
        // 이중 확장자 (예: malicious.jpg.exe) 의 마지막 확장자만 검사하지만, 정상 이름엔 점이 1~2개.
        // 점이 3개 이상이면 의심스러우니 명시적으로 거부 — 사용자 친화 메시지.
        long dotCount = name.chars().filter(c -> c == '.').count();
        if (dotCount > 2) {
            throw new IllegalArgumentException("파일 이름에 점(.) 이 너무 많습니다.");
        }
    }
}
