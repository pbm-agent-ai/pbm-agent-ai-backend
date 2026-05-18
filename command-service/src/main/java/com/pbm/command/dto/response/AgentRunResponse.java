package com.pbm.command.dto.response;

import com.pbm.command.domain.AgentRun;
import com.pbm.command.domain.AgentRunStatus;

import java.time.LocalDateTime;

/**
 * AgentRun 응답 DTO.
 */
public record AgentRunResponse(
        String runId,
        Long userId,
        String commandId,
        String assignedDeviceId,
        LocalDateTime assignedAt,
        AgentRunStatus status,
        Integer currentStepIndex,
        LocalDateTime approvalRequestedAt,
        String abortReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AgentRunResponse from(AgentRun agentRun) {
        return new AgentRunResponse(
                agentRun.getRunId(),
                agentRun.getUserId(),
                agentRun.getCommandId(),
                agentRun.getAssignedDeviceId(),
                agentRun.getAssignedAt(),
                agentRun.getStatus(),
                agentRun.getCurrentStepIndex(),
                agentRun.getApprovalRequestedAt(),
                agentRun.getAbortReason(),
                agentRun.getCreatedAt(),
                agentRun.getUpdatedAt()
        );
    }
}
