package com.pbm.command.dto.response;

import com.pbm.command.domain.AgentRun;

/**
 * 디바이스에 할당된 run 정보 응답 DTO.
 *
 * 역할: heartbeat 응답 또는 pending 조회에서 extension이 실행할 수 있는 run의 최소 정보를 전달한다.
 * 연관: BrowserHeartbeatResponse.
 */
public record AssignedRunResponse(
        String runId,
        String agentToken,
        String commandId,
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
