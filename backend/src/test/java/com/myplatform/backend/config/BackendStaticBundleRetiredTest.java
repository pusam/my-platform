package com.myplatform.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백엔드 소스에 프론트 빌드를 두지 않는다(2026-10-02).
 *
 * <p><b>무엇이 있었나</b>: {@code backend/src/main/resources/static/} 에 2026-03-16 프론트 빌드 59파일(index.html + assets)이
 * 손으로 커밋돼 있었다. 빌드 파이프라인이 만든 것이 아니다 — Gradle {@code copyFrontend} 는 새 빌드를
 * {@code build/resources/main/static} 으로 넣고, CI 는 {@code -PskipFrontend} 라 그것조차 안 한다. 그래서 운영 jar 에는
 * 반년 묵은 화면만 실려 {@code localhost:8080} 의 비-API 경로로 서빙되고 있었다(사용자 화면은 nginx 의 {@code frontend/dist}).
 * 오늘 은퇴한 기간 수집 버튼 같은 지운 코드도 그 안에 그대로 있었다.
 *
 * <p>⚠ 이 테스트가 깨지면 <b>그게 의도다.</b> 화면 출처가 두 벌이 되면 어느 쪽이 지금 화면인지 다시 갈린다.
 * 로컬에서 백엔드 하나로 화면까지 띄우려면 {@code -PskipFrontend} 없이 빌드할 것(새 빌드가 build/ 로 들어간다).
 */
class BackendStaticBundleRetiredTest {

    @Test
    @DisplayName("백엔드 소스 리소스에 프론트 빌드 폴더가 없다")
    void noFrontendBuildInBackendSources() {
        // 테스트 작업 디렉터리 = backend 모듈(ControlRoomFlagParserTest 가 ../docs 로 읽는 것과 같다)
        assertThat(Path.of("src", "main", "resources", "static")).doesNotExist();
    }

    @Test
    @DisplayName(".gitignore 가 그 폴더의 재커밋을 막는다")
    void gitignoreBlocksRecommit() throws Exception {
        String gitignore = Files.readString(Path.of("..", ".gitignore"), StandardCharsets.UTF_8);

        assertThat(gitignore).contains("backend/src/main/resources/static/");
    }
}
