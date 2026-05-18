package com.pbm.command.dto.response;

import com.pbm.command.domain.AgentRunStatus;

/**
 * run step 처리 응답 DTO.
 *
 * 역할: Backend가 현재 run 상태와 다음 action instruction을 함께 내려준다.
 * 동작: run이 종료 상태이면 instruction은 null일 수 있다.
 * 연관: AgentRunController, AgentRunService.
 */
public record AgentRunStepResponse(
        String runId,
        AgentRunStatus status,
        Integer currentStepIndex,
        ActionInstructionResponse instruction
) {
}
