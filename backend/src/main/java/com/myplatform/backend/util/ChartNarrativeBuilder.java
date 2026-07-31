package com.myplatform.backend.util;

import com.myplatform.backend.util.PullbackEntryCalculator.Metrics;
import com.myplatform.backend.util.PullbackEntryCalculator.OverheadSupply;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 차트 해설 문장 생성 — 순수 함수(테스트 대상).
 *
 * <p>{@link PullbackEntryCalculator} 가 뽑은 <b>사실</b>을 재량 트레이더가 말하는 순서
 * (지금 위치 → 반등의 성격 → 위쪽 저항 → 결론)로 엮어 문장으로 만든다.
 * LLM 을 쓰지 않는다 — 같은 입력이면 같은 문장이라 테스트 가능하고, 없는 근거를 지어내지 않는다.
 *
 * <p>⚠ <b>관찰용 해설 — 매수 신호가 아니다.</b> 판정({@link Verdict})은 점수/추천/봇 어디에도
 * 편입하지 않는다(차트기법 스코어러 분리 불변식 + P2-12 교훈). 결론 어휘에 '매수'를 쓰지 않는 것도
 * 같은 이유다 — 거르는 관점('관망/회피')만 말한다.
 *
 * <p>⚠ 판정 규칙과 임계는 <b>잠정</b>이다. {@code signal_outcome} 스냅샷으로 조건부 적중률을
 * 검증하기 전까지 문구의 단정 강도를 높이지 말 것.
 *
 * <p>결측 규약(§4c): 근거가 없는 섹션은 <b>조용히 생략</b>한다(결론 카드와 같은 규약).
 * 지표 자체가 없으면 {@link Verdict#UNKNOWN}(판단보류) — 그럴듯한 결론을 만들지 않는다.
 */
public final class ChartNarrativeBuilder {

    /** [잠정] 하단 터치가 이 봉 수 이내면 "최근 눌림을 받아냈다"로 본다. */
    public static final int RECENT_TOUCH_BARS = 5;
    /** [잠정] %B 가 이 값을 넘으면 상단 이탈(과열) 경계. */
    public static final double OVERHEAT_PERCENT_B = 1.0;
    /** [잠정] 반등 회차가 이 이상이면 숫자 대신 "여러 차례 반복"으로 설명한다(숫자가 의미를 잃는 구간). */
    public static final int MANY_BOUNCES = 3;
    /** [잠정] 머리 위 매물벽이 이 거리(%) 안에 있으면 "가깝다"로 본다. */
    public static final double NEAR_WALL_PCT = 5.0;
    /** [잠정] 매물벽 두께가 이 비중(%) 이상이면 "두껍다"로 본다. */
    public static final double THICK_WALL_PCT = 12.0;
    /** [잠정] 이 거리(%)보다 먼 매물벽은 지금 자리의 저항이 아니라고 보고 언급하지 않는다. */
    public static final double MAX_WALL_DISTANCE_PCT = 30.0;

    private ChartNarrativeBuilder() {}

    /** 해설 결론. 어휘에 '매수'를 두지 않는다 — 거르는 관점만. */
    public enum Verdict {
        /** 근거 부족 — 판단하지 않는다. */
        UNKNOWN("판단보류"),
        /** 자리가 아니다 — 기다린다. */
        WAIT("관망"),
        /** 이미 많이 올라온 자리 — 추격 경계. */
        OVERHEATED("과열 경계"),
        /** 눌림을 받아낸 흔적이 있다 — 관심 두고 지켜볼 만한 자리. */
        WATCH("조건부 관심");

        private final String label;

        Verdict(String label) { this.label = label; }

        public String label() { return label; }
    }

    /** 해설 한 덩어리 — 근거 없으면 아예 만들지 않는다. */
    public record Section(String title, List<String> lines) {}

    /**
     * @param sections      본문 섹션(빈 리스트 가능)
     * @param verdict       결론
     * @param verdictReason 결론 한 줄 근거
     */
    public record Narrative(List<Section> sections, Verdict verdict, String verdictReason) {}

    /**
     * @param metrics       {@link PullbackEntryCalculator#compute} 결과. null 이면 판단보류
     * @param overhead      머리 위 매물벽. null 이면 해당 섹션 생략
     * @param disparityPct  20일선 이격도(%). null 이면 해당 문장 생략
     */
    public static Narrative build(Metrics metrics, OverheadSupply overhead, Double disparityPct) {
        if (metrics == null) {
            return new Narrative(List.of(), Verdict.UNKNOWN,
                    "일봉 데이터가 충분하지 않아 차트 판단을 하지 않습니다.");
        }

        List<Section> sections = new ArrayList<>();
        addPosition(sections, metrics, disparityPct);
        addBounce(sections, metrics);
        addOverhead(sections, overhead);
        addCandle(sections, metrics);

        Decision decision = decide(metrics);
        return new Narrative(sections, decision.verdict(), decision.reason());
    }

    // ========== ① 지금 위치 ==========

    private static void addPosition(List<Section> sections, Metrics m, Double disparityPct) {
        List<String> lines = new ArrayList<>();
        double pb = m.percentB();

        if (pb > OVERHEAT_PERCENT_B) {
            lines.add(String.format(Locale.KOREA,
                    "볼린저 상단을 이탈한 상태입니다(%%B %.2f).", pb));
        } else if (pb < 0) {
            lines.add(String.format(Locale.KOREA,
                    "볼린저 하단 아래에 있습니다(%%B %.2f).", pb));
        } else {
            lines.add(String.format(Locale.KOREA,
                    "볼린저 밴드 안쪽입니다(%%B %.2f, 0=하단·1=상단).", pb));
        }

        Integer touch = m.lowerTouchBarsAgo();
        if (touch == null) {
            lines.add("최근 " + PullbackEntryCalculator.LOWER_TOUCH_LOOKBACK
                    + "봉 안에 하단을 터치한 적이 없습니다.");
        } else if (touch == 0) {
            lines.add("직전 봉에서 하단을 터치했습니다.");
        } else if (touch <= RECENT_TOUCH_BARS) {
            lines.add(touch + "봉 전에 하단을 터치하고 올라온 자리입니다.");
        } else {
            lines.add(touch + "봉 전에 하단을 터치했고, 이후로는 밴드 안에서 움직였습니다.");
        }

        if (disparityPct != null && Double.isFinite(disparityPct)) {
            lines.add(String.format(Locale.KOREA, "20일선 대비 %+.1f%% 이격입니다.", disparityPct));
        }

        sections.add(new Section("지금 위치", lines));
    }

    // ========== ② 반등의 성격 ==========

    private static void addBounce(List<Section> sections, Metrics m) {
        int ordinal = m.bounceOrdinal();
        List<String> lines = new ArrayList<>();

        if (ordinal == 0) {
            lines.add("아직 반등이 성립하지 않았습니다. 저점을 낮추는 흐름입니다.");
        } else if (ordinal == 1) {
            lines.add("바닥 이후 첫 반등 구간입니다.");
            lines.add("첫 반등은 저점을 다시 깨는 경우가 많아 신뢰도가 낮습니다.");
        } else if (ordinal < MANY_BOUNCES) {
            lines.add(ordinal + "번째 반등 구간입니다.");
            lines.add("한 번 되밀린 뒤 다시 올라온 흐름이라 첫 반등보다는 근거가 있습니다.");
        } else {
            // 회차 자체가 커지면 숫자는 의미를 잃는다 — 성격(등락 반복)으로 설명한다
            lines.add("바닥 이후 반등과 되밀림이 여러 차례 반복된 구간입니다.");
            lines.add("방향성 있는 반등이라기보다 등락이 잦은 흐름으로 보는 편이 맞습니다.");
        }

        sections.add(new Section("반등의 성격", lines));
    }

    // ========== ③ 위쪽 저항 ==========

    private static void addOverhead(List<Section> sections, OverheadSupply os) {
        if (os == null) return;                       // 근거 없으면 섹션 자체를 만들지 않는다
        // 한참 위(예 +55%)의 벽은 지금 자리의 저항이 아니다 — 말하지 않는 편이 정직하다
        if (os.distancePct() > MAX_WALL_DISTANCE_PCT) return;

        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.KOREA,
                "%+.1f%% 지점에 매물벽이 있습니다(전체 거래의 %.0f%%).",
                os.distancePct(), os.wallPct()));

        // 거리·두께 조합을 빠짐없이 설명한다 — 사실만 던지고 해석을 비워두지 않는다
        if (os.distancePct() > NEAR_WALL_PCT) {
            lines.add("당장 부딪히는 거리는 아닙니다.");
        } else if (os.wallPct() >= THICK_WALL_PCT) {
            lines.add("가깝고 두꺼워 한 번에 통과하기 어려운 구간입니다.");
        } else {
            lines.add("가깝지만 두께가 크지 않아 결정적인 저항으로 보기는 어렵습니다.");
        }

        sections.add(new Section("위쪽 저항", lines));
    }

    // ========== ④ 최근 캔들 ==========

    private static void addCandle(List<Section> sections, Metrics m) {
        Integer ago = m.bigBearBarsAgo();
        if (ago == null) return;                      // 장대음봉이 없으면 언급하지 않는다

        List<String> lines = new ArrayList<>();
        lines.add(ago == 0 ? "직전 봉이 꽉 찬 장대음봉입니다."
                : ago + "봉 전에 꽉 찬 장대음봉이 나왔습니다.");
        if (PullbackEntryCalculator.isWithinBigBearCooldown(ago)) {
            lines.add("이 음봉의 매물이 소화되기까지 통상 "
                    + PullbackEntryCalculator.BIG_BEAR_COOLDOWN_BARS + "거래일 정도는 지켜봅니다.");
        } else {
            // 쿨다운이 끝났다는 사실도 말해준다 — "나왔습니다"만 던지면 그래서 어쩌라는 건지가 없다
            lines.add("통상 지켜보는 " + PullbackEntryCalculator.BIG_BEAR_COOLDOWN_BARS
                    + "거래일은 지난 시점입니다.");
        }

        sections.add(new Section("최근 캔들", lines));
    }

    // ========== 결론 ==========

    /**
     * 결론과 근거를 <b>한 곳에서</b> 결정한다 — 판정과 문장이 따로 분기하면 어긋난다
     * (실측에서 "관망"인데 근거는 "지켜볼 만합니다"가 나온 회귀의 원인).
     *
     * <p>우선순위: 과열 → 장대음봉 쿨다운 → 첫 반등 → 반등 미성립 → 하단 터치 유무.
     */
    private static Decision decide(Metrics m) {
        if (m.percentB() > OVERHEAT_PERCENT_B) {
            return new Decision(Verdict.OVERHEATED,
                    "밴드 상단을 벗어난 자리라 지금 올라타면 추격이 됩니다.");
        }
        if (PullbackEntryCalculator.isWithinBigBearCooldown(m.bigBearBarsAgo())) {
            return new Decision(Verdict.WAIT, "장대음봉 매물이 아직 소화되지 않았습니다.");
        }
        if (m.bounceOrdinal() == 1) {
            return new Decision(Verdict.WAIT, m.lowerTouchBarsAgo() == null
                    ? "하단 터치 없이 첫 반등에 올라타는 자리입니다."
                    : "첫 반등이라 저점 재이탈 가능성을 남겨둔 자리입니다.");
        }
        if (m.bounceOrdinal() == 0) {
            return new Decision(Verdict.WAIT,
                    "반등이 성립하지 않아 아직 바닥을 확인하는 단계입니다.");
        }
        if (m.lowerTouchBarsAgo() == null) {
            return new Decision(Verdict.WAIT,
                    "하단을 받아낸 흔적 없이 올라온 자리라 눌림목으로 보기 어렵습니다.");
        }
        return new Decision(Verdict.WATCH, "하단을 한 번 받아낸 뒤의 재반등이라 지켜볼 만합니다.");
    }

    private record Decision(Verdict verdict, String reason) {}
}
