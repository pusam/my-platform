package com.myplatform.backend.crosscheck;

import com.myplatform.backend.service.AutoTradingBotService;
import com.myplatform.backend.service.RecommendationService;
import com.myplatform.backend.service.SignalD3EvaluationService;
import com.myplatform.backend.service.SignalOutcomeService;
import com.myplatform.backend.service.StockPriceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 대조는 <b>측정 전용</b>이다 — 추천·봇·시그널·D+3 평가·시세 경로가 이 패키지에 닿지 않는다는 것을 컴파일된 바이트코드로
 * 확인한다(클래스 상수 풀에 패키지 이름이 있으면 참조가 있는 것 — {@code YoutubeOpinionIsolationTest} 와 같은 방식).
 *
 * <p>이 패키지를 참조해도 되는 바깥은 관제실({@code controlroom/})뿐이다 — 결과를 읽어 이상 점검으로 올린다. 새 소비처가 정말
 * 필요하면 {@link #ALLOWED_PREFIXES} 에 이유와 함께 넣되, 추천·봇·시그널·시세 쪽은 넣지 말 것(산식 미편입, §3 미보정).
 */
class CrosscheckIsolationTest {

    private static final String PACKAGE = "com/myplatform/backend/crosscheck/";
    private static final byte[] NEEDLE = "com/myplatform/backend/crosscheck".getBytes(StandardCharsets.UTF_8);

    /** 관제실 — 결과를 읽기만 한다. */
    private static final Set<String> ALLOWED_PREFIXES = Set.of("com/myplatform/backend/controlroom/");

    @Test
    @DisplayName("관제실 밖 어떤 클래스도 외부 대조 패키지를 참조하지 않는다 — 추천·봇·시그널·D+3·시세 포함")
    void onlyControlRoomReferencesThePackage() throws Exception {
        Set<String> scanned = new TreeSet<>();
        List<String> offenders = new ArrayList<>();
        List<String> controlRoomUsers = new ArrayList<>();
        for (URL root : roots()) {
            scan(root, (name, bytes) -> {
                if (!name.startsWith("com/myplatform/backend/") || name.startsWith(PACKAGE)) return;
                scanned.add(name);
                if (!contains(bytes, NEEDLE)) return;
                if (ALLOWED_PREFIXES.stream().anyMatch(name::startsWith)) controlRoomUsers.add(name);
                else offenders.add(name);
            });
        }
        assertThat(scanned).contains(internal(RecommendationService.class), internal(AutoTradingBotService.class),
                internal(SignalOutcomeService.class), internal(SignalD3EvaluationService.class), internal(StockPriceService.class));
        assertThat(offenders).as("외부 대조 패키지를 참조하는 바깥 클래스").isEmpty();
        assertThat(controlRoomUsers).as("관제실은 실제로 읽는다 — 검사가 헛돌지 않게").isNotEmpty();
    }

    @Test
    @DisplayName("검사 자체가 동작한다 — 패키지 안 클래스에는 이름이 들어 있다")
    void needleIsDetectable() throws Exception {
        try (InputStream in = ExternalCrosscheckService.class.getResourceAsStream("ExternalCrosscheckService.class")) {
            assertThat(in).isNotNull();
            assertThat(contains(in.readAllBytes(), NEEDLE)).isTrue();
        }
    }

    private static String internal(Class<?> c) {
        return c.getName().replace('.', '/') + ".class";
    }

    private static Set<URL> roots() {
        Set<URL> out = new LinkedHashSet<>();
        for (Class<?> anchor : List.of(RecommendationService.class, AutoTradingBotService.class, SignalOutcomeService.class,
                ExternalCrosscheckService.class)) {
            out.add(anchor.getProtectionDomain().getCodeSource().getLocation());
        }
        return out;
    }

    private interface ClassVisitor {
        void visit(String internalName, byte[] bytes) throws IOException;
    }

    private static void scan(URL root, ClassVisitor visitor) throws IOException, URISyntaxException {
        Path path = Path.of(root.toURI());
        if (Files.isDirectory(path)) {
            try (Stream<Path> files = Files.walk(path)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                    visitor.visit(path.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
                }
            }
        } else {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                    if (!e.getName().endsWith(".class")) continue;
                    String name = e.getName().startsWith("BOOT-INF/classes/")
                            ? e.getName().substring("BOOT-INF/classes/".length()) : e.getName();
                    try (InputStream in = zip.getInputStream(e)) {
                        visitor.visit(name, in.readAllBytes());
                    }
                }
            }
        }
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
