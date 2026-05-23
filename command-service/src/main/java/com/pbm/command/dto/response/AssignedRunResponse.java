package com.pbm.command.dto.response;

import com.pbm.command.domain.AgentRun;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 디바이스에 할당된 run 정보 응답 DTO.
 *
 * 역할: heartbeat 응답 또는 pending 조회에서 extension이 실행할 수 있는 run의 최소 정보를 전달한다.
 * 연관: BrowserHeartbeatResponse.
 */
@Schema(description = "디바이스에 할당된 pending run 정보")
public record AssignedRunResponse(
        @Schema(description = "브라우저 run 식별자", example = "90595c65-213d-444d-a470-8df3d4d873c2")
        String runId,
        @Schema(description = "extension이 /start, /steps 호출에 사용하는 agent token")
        String agentToken,
        @Schema(description = "연결된 commandId", example = "97314885-3dfb-4bca-be21-742d2155b098")
        String commandId,
        @Schema(description = "이번 run이 목표로 하는 플랫폼", example = "NAVER")
        String platform
) {

    // 주의: platform 정보를 포함하려면 CommandSession을 함께 조회해야 한다.
    // 현재 이 메서드는 사용되지 않으며, platform=null로 내려가면 extension이 ALIEXPRESS 기본값을 사용한다.
    @Deprecated
    public static AssignedRunResponse from(AgentRun agentRun, String agentToken) {
        return new AssignedRunResponse(
                agentRun.getRunId(),
                agentToken,
                agentRun.getCommandId(),
                null
        );
    }
}
