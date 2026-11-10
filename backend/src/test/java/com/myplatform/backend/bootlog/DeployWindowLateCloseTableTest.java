package com.myplatform.backend.bootlog;

import com.myplatform.backend.service.MarketCalendarService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배포 가드 스크립트의 '늦게 닫는 날' 표 ↔ 달력(2026-11-10).
 *
 * <p>{@code scripts/deploy-window.sh} 는 달력을 모르는 셸이라 늦게 닫는 날(보류 창 끝이 16:25 → 16:49)을 자기 표로 든다.
 * 그 표가 {@link MarketCalendarService#closesLate} 와 어긋나면 그날 장중(16:26~16:49)에 배포·재시작된다 — 달력에 날짜를
 * 넣을 때 스크립트도 같이 고치게 여기서 대조한다(저장소 파일을 읽는 선례: NginxExternalBlockTest).
 */
class DeployWindowLateCloseTableTest {

    private static final Pattern TABLE = Pattern.compile("^LATE_CLOSE_DAYS=\"([^\"]*)\"", Pattern.MULTILINE);

    @Test
    @DisplayName("스크립트의 늦게 닫는 날 = 달력의 closesLate 날짜(2025~2028)")
    void scriptTableMatchesCalendar() throws Exception {
        String script = Files.readString(Path.of("..", "scripts", "deploy-window.sh"), StandardCharsets.UTF_8);
        Matcher m = TABLE.matcher(script);
        assertThat(m.find()).as("deploy-window.sh 에 LATE_CLOSE_DAYS=\"…\" 줄이 있어야 한다").isTrue();
        Set<LocalDate> fromScript = Arrays.stream(m.group(1).trim().split("\\s+"))
                .filter(s -> !s.isBlank())
                .map(LocalDate::parse)
                .collect(Collectors.toCollection(TreeSet::new));

        MarketCalendarService calendar = new MarketCalendarService();
        Set<LocalDate> fromCalendar = new TreeSet<>();
        for (LocalDate d = LocalDate.of(2025, 1, 1); d.isBefore(LocalDate.of(2029, 1, 1)); d = d.plusDays(1)) {
            if (calendar.closesLate(d)) fromCalendar.add(d);
        }

        assertThat(fromCalendar).as("달력에 늦게 닫는 날이 하나는 있어야 이 대조가 의미 있다").isNotEmpty();
        assertThat(fromScript).isEqualTo(fromCalendar);
    }
}
