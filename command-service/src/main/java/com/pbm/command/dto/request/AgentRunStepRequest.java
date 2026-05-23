package com.pbm.command.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * run step 처리 요청 DTO.
 *
 * 역할: Extension이 현재 stepIndex, 직전 action 결과, 최신 snapshot을 함께 보내도록 강제한다.
 * 동작: 첫 요청에서는 previousActionResult가 null이고, 이후 요청에서는 성공/실패 결과에 따라 같은 step 또는 다음 step으로 진행한다.
 * 연관: AgentRunController, AgentRunService.
 */
@Schema(description = "extension step 처리 요청 DTO")
public record AgentRunStepRequest(
        @Schema(description = "extension이 현재 인식하는 step index", example = "3")
        Integer stepIndex,
        @Schema(description = "직전 action 실행 결과. 첫 호출이면 null")
        AgentRunActionResultRequest previousActionResult,
        @Schema(description = "현재 탭 스냅샷 정보")
        PageSnapshotRequest snapshot
) {
}
