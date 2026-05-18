package com.pbm.command.domain;

/**
 * 브라우저 자동화 실행 단위(AgentRun) 상태 enum.
 *
 * 역할: 웹 앱에서 생성된 실행 요청이 디바이스 할당 → 실행 → 승인 대기 → 완료/중단으로
 *       이어지는 생명주기를 명시적으로 표현한다.
 * 연관: AgentRun, AgentRunService.
 */
public enum AgentRunStatus {
    QUEUED,
    ASSIGNED,
    RUNNING,
    AWAITING_APPROVAL,
    APPROVAL_EXPIRED,
    INTERRUPTED,
    RECOVERING,
    COMPLETED,
    FAILED,
    ABORTED
}
