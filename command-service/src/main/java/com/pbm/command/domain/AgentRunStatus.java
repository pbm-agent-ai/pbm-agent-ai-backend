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
    /** 상품 옵션 선택 대기 중 (텔레그램으로 사용자에게 옵션 목록 전송, 3분 타임아웃) */
    AWAITING_OPTION_SELECTION,
    /** 로그인 아이디/비밀번호 입력 대기 중 (텔레그램으로 자격증명 요청, 3분 타임아웃) */
    AWAITING_LOGIN_CREDENTIALS,
    APPROVAL_EXPIRED,
    INTERRUPTED,
    RECOVERING,
    COMPLETED,
    FAILED,
    ABORTED
}
