package com.pbm.command.dto.response;

import com.pbm.command.domain.AgentRun;
import com.pbm.command.domain.AgentRunStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * AgentRun 응답 DTO.
 */
@Schema(description = "브라우저 agent run 상태 응답 DTO")
public record AgentRunResponse(
        @Schema(description = "run 식별자", example = "90595c65-213d-444d-a470-8df3d4d873c2")
        String runId,
        @Schema(description = "run 소유 사용자 ID", example = "6")
        Long userId,
        @Schema(description = "연결된 commandId", example = "97314885-3dfb-4bca-be21-742d2155b098")
        String commandId,
        @Schema(description = "현재 할당된 디바이스 ID", example = "c8d06fbe-1a30-4075-9a08-d482d1589928")
        String assignedDeviceId,
        @Schema(description = "디바이스 할당 시각")
        LocalDateTime assignedAt,
        @Schema(description = "현재 run 상태", example = "RUNNING")
        AgentRunStatus status,
        @Schema(description = "현재 step index", example = "3")
        Integer currentStepIndex,
        @Schema(description = "승인 대기 시작 시각")
        LocalDateTime approvalRequestedAt,
        @Schema(description = "중단/실패 사유")
        String abortReason,
        @Schema(description = "run 생성 시각")
        LocalDateTime createdAt,
        @Schema(description = "run 마지막 갱신 시각")
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
