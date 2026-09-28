package com.myplatform.backend.youtubeopinion;

import com.myplatform.backend.service.AutoTradingBotService;
import com.myplatform.backend.service.RecommendationService;
import com.myplatform.backend.service.SignalOutcomeService;
import com.myplatform.backend.service.StockCatalystService;
import com.myplatform.backend.service.StockConclusionService;
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
 * 유튜브 의견은 <b>외부 참고 표시</b>일 뿐이다 — 추천 점수·순위·BUY 판정·손절/익절·봇 주문·시그널 평가·재료 태그가
 * 이 패키지에 닿지 않는다는 것을 <b>컴파일된 바이트코드</b>로 확인한다(클래스 상수 풀에 패키지 이름이 있으면 참조가 있는 것).
 *
 * <p>소스 import 검색과 달리 완전한 이름으로 쓴 참조·람다·내부 클래스까지 잡는다. 이 패키지 밖에서 참조하는 클래스가
 * 하나라도 생기면 실패한다 — 새 소비처가 정말 필요하면 {@link #ALLOWED} 에 이유와 함께 넣되,
 * 추천·결론·봇·시그널·재료 쪽은 넣지 말 것(산식 미편입 불변식).
 */
class YoutubeOpinionIsolationTest {

    private static final String PACKAGE = "com/myplatform/backend/youtubeopinion/";
    private static final byte[] NEEDLE = "com/myplatform/backend/youtubeopinion".getBytes(StandardCharsets.UTF_8);

    /** 이 패키지를 참조해도 되는 바깥 클래스(내부 이름). 지금은 없다. */
    private static final Set<String> ALLOWED = Set.of();

    @Test
    @DisplayName("패키지 밖 어떤 클래스도 유튜브 의견 패키지를 참조하지 않는다 — 추천·결론·봇·시그널·재료 포함")
    void noClassOutsideReferencesThePackage() throws Exception {
        Set<String> scanned = new TreeSet<>();
        List<String> offenders = new ArrayList<>();
        for (URL root : roots()) {
            scan(root, (name, bytes) -> {
                if (!name.startsWith("com/myplatform/backend/") || name.startsWith(PACKAGE)) return;
                scanned.add(name);
                if (contains(bytes, NEEDLE) && !ALLOWED.contains(name)) offenders.add(name);
            });
        }

        // 스캔이 실제로 대상 클래스를 봤는지 — 빈 스캔으로 통과하지 않게
        assertThat(scanned).contains(
                internal(RecommendationService.class), internal(StockConclusionService.class),
                internal(AutoTradingBotService.class), internal(SignalOutcomeService.class),
                internal(StockCatalystService.class));
        assertThat(offenders).as("유튜브 의견 패키지를 참조하는 바깥 클래스").isEmpty();
    }

    @Test
    @DisplayName("검사 자체가 동작한다 — 패키지 안 클래스에는 이름이 들어 있다")
    void needleIsDetectable() throws Exception {
        try (InputStream in = YoutubeOpinionQueryService.class.getResourceAsStream("YoutubeOpinionQueryService.class")) {
            assertThat(in).isNotNull();
            assertThat(contains(in.readAllBytes(), NEEDLE)).isTrue();
        }
    }

    private static String internal(Class<?> c) {
        return c.getName().replace('.', '/') + ".class";
    }

    /** 운영 클래스가 들어 있는 위치 — 로컬·CI 모두 디렉터리(또는 jar). 중복 제거. */
    private static Set<URL> roots() {
        Set<URL> out = new LinkedHashSet<>();
        for (Class<?> anchor : List.of(RecommendationService.class, StockConclusionService.class,
                AutoTradingBotService.class, SignalOutcomeService.class, StockCatalystService.class,
                YoutubeOpinionQueryService.class)) {
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

    static boolean contains(byte[] hay, byte[] needle) {
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
