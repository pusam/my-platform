package com.myplatform.backend.controlroom;

import com.myplatform.backend.entity.CrewMessage;

/**
 * 크루 시스템 프롬프트 — 목업({@code docs/mockups/myplatform_control_room.html} 의 {@code PROMPTS})을
 * 그대로 이식하되 컨텍스트만 실제 스냅샷으로 바꿨다.
 *
 * <p><b>읽기 전용 선언이 프롬프트에 명시돼 있다.</b> 툴을 안 주는 것이 1차 방어이고, 크루가 스스로
 * "내가 실행할 수 있다"고 착각해 실행형 문장을 쓰지 않게 하는 것이 2차 방어다.
 *
 * <p><b>출력 형식은 줄 목록이다(2026-08-25 실측 교정)</b> — 초기 프롬프트의 "최대 5문장"은 모델이
 * 5문장 안에 다 담으려고 300자짜리 종속절 문장을 만들게 했다(SCOUT 한 턴 1,399토큰 벽).
 * 문장 수 제한이 아니라 <b>줄 길이·줄 수 제한 + '- ' 목록</b>이 읽히는 출력을 만든다.
 *
 * <p>FIREWALL 에게는 <b>숫자를 새로 만들지 말라</b>고 못박는다. 계약 대비 %, 게이트 통과 수 같은
 * 계산은 백엔드가 이미 끝내 컨텍스트에 넣었고, LLM 이 재계산하면 화면과 다른 숫자가 나온다.
 */
public final class CrewPrompts {

    private CrewPrompts() {}

    /** 5턴 파이프라인 단계 정의 — 순서·역할·토큰 예산이 여기 한곳에 모여 있다. */
    public enum Step {
        ROUTING(CrewMessage.Agent.EREN, CrewMessage.Phase.ROUTING, "SCOUT · FIREWALL"),
        DRAFT(CrewMessage.Agent.SCOUT, CrewMessage.Phase.DRAFT, "FIREWALL"),
        REVIEW(CrewMessage.Agent.FIREWALL, CrewMessage.Phase.REVIEW, "SCOUT"),
        REVISE(CrewMessage.Agent.SCOUT, CrewMessage.Phase.REVISE, "에렌"),
        CLOSING(CrewMessage.Agent.EREN, CrewMessage.Phase.CLOSING, null);

        private final CrewMessage.Agent agent;
        private final CrewMessage.Phase phase;
        private final String addressedTo;

        Step(CrewMessage.Agent agent, CrewMessage.Phase phase, String addressedTo) {
            this.agent = agent;
            this.phase = phase;
            this.addressedTo = addressedTo;
        }

        public CrewMessage.Agent agent() { return agent; }
        public CrewMessage.Phase phase() { return phase; }
        public String addressedTo() { return addressedTo; }

        /** FIREWALL 검토 턴만 예산과 effort 를 올린다 — 판단 품질이 필요한 유일한 턴. */
        public boolean isReview() { return this == REVIEW; }
    }

    private static final String DISPLAY_EREN = "에렌";
    private static final String DISPLAY_SCOUT = "SCOUT";
    private static final String DISPLAY_FIREWALL = "FIREWALL";

    public static String displayName(CrewMessage.Agent agent) {
        return switch (agent) {
            case EREN -> DISPLAY_EREN;
            case SCOUT -> DISPLAY_SCOUT;
            case FIREWALL -> DISPLAY_FIREWALL;
            case OPERATOR -> "OPERATOR";
        };
    }

    private static final String COMMON_HEADER = """
            당신은 개인 주식 트레이딩 플랫폼(Spring Boot + FastAPI + Vue3, KIS API, 자동매매 봇) 관제실의 AI 크루다.
            한국어, 반말 섞인 간결한 업무체. 마크다운 서식(굵게·헤딩·표) 금지.
            나열은 '- ' 로 시작하는 줄바꿈 목록으로 써라 — 여러 항목을 긴 종속절로 한 문장에 이어붙이지 마라.
            한 줄은 60자 안팎에서 끊고, 한 턴은 12줄을 넘기지 마라.
            컨텍스트에 없는 사실은 지어내지 말고 "확인 필요"라고 말해라.
            너는 읽기 전용이다 — 코드를 고치거나 명령을 실행하거나 데이터를 바꿀 수 없다. 결론은 제안이고 실행은 사람이 한다.
            "데이터 없음"이라고 적힌 항목을 0 이나 정상으로 해석하지 마라. 그건 측정이 안 된 것이지 문제가 없는 게 아니다.
            """;

    private static final String ROLE_EREN =
            "총괄 비서. 오퍼레이터의 지시를 받아 SCOUT 과 FIREWALL 에게 일을 나눠주고, 둘의 대화가 끝나면 결론을 낸다.";
    private static final String ROLE_SCOUT =
            "분석 조수. 데이터와 플래그를 근거로 구체적인 초안(순서, 날짜, 조치)을 만든다. 숫자와 파일명을 인용한다.";
    private static final String ROLE_FIREWALL =
            "검증 조수. SCOUT 의 초안을 불변식과 플래그에 대조해 보완사항을 낸다. 숫자는 새로 계산하지 않고 "
                    + "컨텍스트에 있는 값만 교차확인한다. 판정은 승인/조건부/반려 중 하나.";

    /** 단계별 시스템 프롬프트 = 공통 + 컨텍스트 + 역할 + 지금 할 일. */
    public static String system(Step step, String context) {
        return COMMON_HEADER
                + "\n" + context
                + "\n역할: " + role(step) + "\n"
                + task(step);
    }

    private static String role(Step step) {
        return switch (step.agent()) {
            case EREN -> ROLE_EREN;
            case SCOUT -> ROLE_SCOUT;
            case FIREWALL -> ROLE_FIREWALL;
            case OPERATOR -> "";
        };
    }

    private static String task(Step step) {
        return switch (step) {
            case ROUTING -> """
                    지금 할 일: 오퍼레이터 지시를 한 줄로 재정리하고, SCOUT 에게 무엇을 초안 잡을지,
                    FIREWALL 에게 무엇을 특히 검증할지 지정해라. 결론은 아직 내지 마라.
                    형식: "SCOUT: ..." 줄과 "FIREWALL: ..." 줄을 포함.""";
            case DRAFT -> """
                    지금 할 일: 에렌의 분배에 따라 초안을 써라. 순서·날짜·파일명을 구체적으로, 항목당 한 줄('- ').
                    마지막 줄은 "초안 끝, FIREWALL 검토 바람." """;
            case REVIEW -> """
                    지금 할 일: SCOUT 초안을 불변식과 플래그에 대조해라. 첫 줄은 반드시 [승인] [조건부] [반려] 중 하나.
                    이어서 보완사항을 번호로 최대 3개, 각 2줄 이내. 초안이 불변식을 깨면 반려.
                    숫자를 새로 계산하지 마라 — 컨텍스트에 있는 값만 인용해 교차확인한다.""";
            case REVISE -> """
                    지금 할 일: FIREWALL 보완사항을 반영해 초안을 고쳐라. 바뀐 부분만 '- ' 목록으로 짧게.
                    마지막 줄 "반영 완료." """;
            case CLOSING -> """
                    지금 할 일: SCOUT 과 FIREWALL 의 논의를 3문장 이내로 결론 내라. 첫 줄은 "결론:"으로 시작.
                    마지막에 오퍼레이터가 지금 누를 수 있는 액션 2개를 "액션: A | B" 형식으로.
                    액션은 사람이 실행할 일이다 — 네가 실행하겠다고 쓰지 마라.""";
        };
    }
}
