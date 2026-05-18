package com.pbm.command.domain;

/**
 * 브라우저 액션 실패 사유 코드 enum.
 *
 * 역할: Extension이 실패 원인을 구조화해서 Backend에 보고할 때 사용한다.
 * 동작: 문자열 상수 오타를 줄이고, 재계획/중단 판단 기준을 일관되게 맞춘다.
 * 연관: AgentRunActionResultRequest.
 */
public enum ActionErrorCode {
    ELEMENT_NOT_FOUND,
    ELEMENT_NOT_CLICKABLE,
    NAVIGATION_TIMEOUT,
    NAVIGATION_FAILED,
    UNSUPPORTED_PAGE_STATE,
    VISION_FALLBACK_REQUESTED,
    AI_PLAN_UNAVAILABLE,
    APPROVAL_REJECTED,
    APPROVAL_EXPIRED,
    NETWORK_ERROR,
    UNEXPECTED_ERROR
}
