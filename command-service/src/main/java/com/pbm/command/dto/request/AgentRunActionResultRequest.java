package com.pbm.command.dto.request;

import com.pbm.command.domain.ActionErrorCode;
import com.pbm.command.domain.ActionExecutionStatus;
import com.pbm.command.domain.BrowserActionType;

import java.time.LocalDateTime;

/**
 * Extension이 직전 step 실행 결과를 보고할 때 사용하는 요청 DTO.
 *
 * 역할: 같은 step 재계획 또는 다음 step 진행 여부를 Backend가 판단할 수 있게 한다.
 * 동작: 성공이면 stepIndex를 1 증가시키고, 실패면 같은 stepIndex 기준으로 재계획할 수 있다.
 * 연관: AgentRunStepRequest, AgentRunService.
 */
public record AgentRunActionResultRequest(
        String runId,
        Integer stepIndex,
        String actionId,
        BrowserActionType action,
        ActionExecutionStatus status,
        ActionErrorCode errorCode,
        String errorMessage,
        ToolResultRequest toolResult,
        PageSnapshotRequest snapshot,
        LocalDateTime completedAt
) {
}
