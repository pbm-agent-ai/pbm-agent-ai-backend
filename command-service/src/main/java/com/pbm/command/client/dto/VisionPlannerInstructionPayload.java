package com.pbm.command.client.dto;

/**
 * Gemini vision planner가 반환하는 정규화 payload.
 */
public record VisionPlannerInstructionPayload(
        String action,
        Double viewportX,
        Double viewportY,
        String targetLabel,
        Double confidence,
        String reason
) {
}
