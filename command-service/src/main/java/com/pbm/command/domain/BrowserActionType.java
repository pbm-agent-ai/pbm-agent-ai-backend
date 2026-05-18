package com.pbm.command.domain;

/**
 * 브라우저 에이전트가 실행할 액션 타입 enum.
 *
 * 역할: Backend가 Extension에 어떤 브라우저 동작을 지시하는지 표현한다.
 * 동작: MVP에서는 1 step = 1 action 규칙을 따르므로 step 응답마다 하나의 action만 내려간다.
 * 연관: AgentRunStepResponse, AgentRunActionResultRequest.
 */
public enum BrowserActionType {
    NAVIGATE,
    CLICK,
    INPUT,
    SELECT,
    SCROLL,
    USE_TOOL,
    WAIT,
    AWAIT_APPROVAL,
    COMPLETE,
    ABORT
}
