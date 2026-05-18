package com.pbm.command.dto.response;

import java.time.LocalDateTime;

/**
 * AgentRun 생성 응답 DTO.
 */
public record AgentRunCreatedResponse(
        String runId,
        String commandId,
        String status,
        LocalDateTime createdAt
) {
}
