package com.pbm.command.dto.response;

import com.pbm.command.domain.BrowserDevice;
import com.pbm.command.domain.BrowserDeviceStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 브라우저 디바이스 heartbeat 응답 DTO.
 *
 * 역할: heartbeat 처리 결과로 현재 디바이스의 상태와 마지막 확인 시각을 반환한다.
 * 연관: BrowserDeviceController, BrowserDevice.
 */
@Schema(description = "브라우저 디바이스 heartbeat 응답 DTO")
public record BrowserHeartbeatResponse(
        @Schema(description = "디바이스 식별자", example = "c8d06fbe-1a30-4075-9a08-d482d1589928")
        String deviceId,
        @Schema(description = "현재 디바이스 상태", example = "ONLINE")
        BrowserDeviceStatus deviceStatus,
        @Schema(description = "마지막 heartbeat 수신 시각")
        LocalDateTime lastSeenAt,
        @Schema(description = "현재 디바이스에 할당된 pending run 정보. 없으면 null")
        AssignedRunResponse assignedRun
) {
    public static BrowserHeartbeatResponse from(BrowserDevice browserDevice, AssignedRunResponse assignedRun) {
        return new BrowserHeartbeatResponse(
                browserDevice.getDeviceId(),
                browserDevice.getStatus(),
                browserDevice.getLastSeenAt(),
                assignedRun
        );
    }
}
