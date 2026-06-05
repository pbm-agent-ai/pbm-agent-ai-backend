package com.pbm.command.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Gemini vision planner가 반환하는 정규화 payload.
 * Python(FastAPI)은 snake_case로 응답하므로 @JsonProperty로 명시적 매핑 필요.
 */
public record VisionPlannerInstructionPayload(
        String action,
        @JsonProperty("viewport_x") Double viewportX,
        @JsonProperty("viewport_y") Double viewportY,
        @JsonProperty("target_label") String targetLabel,
        Double confidence,
        String reason
) {
}
