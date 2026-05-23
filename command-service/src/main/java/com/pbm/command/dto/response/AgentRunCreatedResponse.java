package com.pbm.command.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * AgentRun 생성 응답 DTO.
 */
@Schema(description = "run 생성 응답 DTO")
public record AgentRunCreatedResponse(
        @Schema(description = "생성된 run 식별자", example = "90595c65-213d-444d-a470-8df3d4d873c2")
        String runId,
        @Schema(description = "연결된 commandId", example = "97314885-3dfb-4bca-be21-742d2155b098")
        String commandId,
        @Schema(description = "생성 직후 run 상태", example = "QUEUED")
        String status,
        @Schema(description = "run 생성 시각")
        LocalDateTime createdAt
) {
}
