package com.pbm.command.dto.request;

/**
 * AgentRun 승인/거부 요청 DTO.
 */
public record AgentRunApproveRequest(
        boolean approved
) {
}
