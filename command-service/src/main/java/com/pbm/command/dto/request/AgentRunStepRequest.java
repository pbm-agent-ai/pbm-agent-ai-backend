package com.pbm.command.dto.request;

/**
 * run step 처리 요청 DTO.
 *
 * 역할: Extension이 현재 stepIndex, 직전 action 결과, 최신 snapshot을 함께 보내도록 강제한다.
 * 동작: 첫 요청에서는 previousActionResult가 null이고, 이후 요청에서는 성공/실패 결과에 따라 같은 step 또는 다음 step으로 진행한다.
 * 연관: AgentRunController, AgentRunService.
 */
public record AgentRunStepRequest(
        Integer stepIndex,
        AgentRunActionResultRequest previousActionResult,
        PageSnapshotRequest snapshot
) {
}
