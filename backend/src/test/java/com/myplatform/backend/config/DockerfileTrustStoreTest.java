package com.myplatform.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 개인키가 유출된 인증기관(eMudhra)의 뿌리 인증서는 백엔드 이미지의 자바 신뢰 목록에서 뺀다 (2026-11-17).
 *
 * <p>OpenJDK 권고(openjdk.org/emudhra, CVE-2026-114820): eMudhra 중간 인증서 8개의 개인키가 유출됐다. 고친 런타임이
 * 아직 없으면 그 회사의 뿌리 인증서를 신뢰 목록에서 지우라고 한다. 이미지의 Temurin 17.0.21+9 는 그 권고보다 먼저
 * 나왔고, Temurin 의 신뢰 목록은 모질라 기반이라 별칭이 OpenJDK 안내({@code emsignrootcag1 [jdk]} 등)와 다르다 —
 * 그래서 별칭이 아니라 소유자 이름으로 찾아 지운다.
 *
 * <p>이 테스트는 Dockerfile 에서 그 단계가 빠지거나 실패해도 조용히 넘어가는 모양으로 바뀌는 것을 막는다. 실제 동작은
 * 이미지 빌드(남아 있으면 빌드가 멈춘다)와 배포 뒤 컨테이너의 신뢰 목록으로 확인한다.
 */
class DockerfileTrustStoreTest {

    private static String dockerfile;
    private static String step;

    @BeforeAll
    static void load() throws IOException {
        dockerfile = Files.readString(Path.of("..", "Dockerfile"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        step = runStepContaining("keytool");
    }

    @Test
    @DisplayName("재현: eMudhra 소유 뿌리 인증서를 자바 신뢰 목록에서 지우는 RUN 단계가 있다")
    void distrustStepExists() {
        assertThat(step).as("keytool 을 쓰는 RUN 단계").isNotNull();
        assertThat(step).contains("-delete").contains("-cacerts");
        // 별칭이 아니라 인증서 소유자 이름으로 찾는다 — Temurin 별칭은 OpenJDK 안내와 다르다
        assertThat(step).contains("tolower($0) ~ /^owner: .*(emudhra|emsign)/");
    }

    @Test
    @DisplayName("목록을 못 읽거나 지운 뒤에도 남아 있으면 빌드를 멈춘다 — 조용히 통과하지 않는다")
    void buildStopsWhenStillTrusted() {
        assertThat(step).as("명령 하나가 실패하면 멈춘다").contains("set -eu");
        assertThat(step).as("지운 뒤 다시 읽어 남았는지 본다").contains("grep -qiE '^owner: .*(emudhra|emsign)'");
        assertThat(step).as("목록을 못 읽은 채 '없음'으로 통과하지 않는다").contains("grep -c '^Alias name: ')\" -gt 50");
        assertThat(step).contains("exit 1");
    }

    @Test
    @DisplayName("keytool 출력은 영어로 고정한다 — 'Alias name:'·'Owner:' 줄을 읽기 때문")
    void keytoolOutputLocaleIsPinned() {
        assertThat(step).contains("-J-Duser.language=en");
    }

    @Test
    @DisplayName("신뢰 목록은 root 일 때 고친다 — USER spring 보다 앞")
    void runsBeforeDroppingRoot() {
        int keytool = dockerfile.indexOf("keytool");
        int dropRoot = dockerfile.indexOf("USER spring");
        assertThat(keytool).isPositive();
        assertThat(dropRoot).isPositive();
        assertThat(keytool).isLessThan(dropRoot);
    }

    /** 주어진 글자가 든 RUN 명령 전체(줄 끝 역슬래시로 이어진 줄까지). 없으면 null. */
    private static String runStepContaining(String needle) {
        int at = dockerfile.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int start = dockerfile.lastIndexOf("\nRUN ", at);
        if (start < 0) {
            return null;
        }
        int end = start + 1;
        while (true) {
            int nl = dockerfile.indexOf('\n', end);
            if (nl < 0) {
                return dockerfile.substring(start + 1);
            }
            if (dockerfile.charAt(nl - 1) != '\\') {
                return nl > at ? dockerfile.substring(start + 1, nl) : null;
            }
            end = nl + 1;
        }
    }
}
