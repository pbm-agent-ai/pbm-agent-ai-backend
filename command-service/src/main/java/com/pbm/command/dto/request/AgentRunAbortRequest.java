package com.pbm.command.dto.request;

/**
 * AgentRun 중단 요청 DTO.
 */
public record AgentRunAbortRequest(
        String reason
) {
    public AgentRunAbortRequest {
        reason = reason == null ? null : reason.trim();
    }
}
