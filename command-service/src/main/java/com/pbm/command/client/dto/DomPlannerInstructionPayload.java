package com.pbm.command.client.dto;

/**
 * external-api-service DOM planner가 반환하는 정규화 payload.
 *
 * 역할: GPT-5.4-mini가 선택한 브라우저 액션과 target/value/confidence를
 *       command-service 내부 DTO로 역직렬화한다.
 * 연관: AiDomPlannerClient, AgentStepPlannerService.
 */
public record DomPlannerInstructionPayload(
        String action,
        PlannerTargetPayload target,
        String value,
        Double confidence,
        String reason
) {

    public record PlannerTargetPayload(
            String nodeId,
            String role,
            String labelText,
            String selector
    ) {
    }
}
