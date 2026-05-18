package com.pbm.command.dto.response;

import com.pbm.command.domain.BrowserDevice;
import com.pbm.command.domain.BrowserDeviceStatus;

import java.time.LocalDateTime;

/**
 * 브라우저 디바이스 heartbeat 응답 DTO.
 *
 * 역할: heartbeat 처리 결과로 현재 디바이스의 상태와 마지막 확인 시각을 반환한다.
 * 연관: BrowserDeviceController, BrowserDevice.
 */
public record BrowserHeartbeatResponse(
        String deviceId,
        BrowserDeviceStatus deviceStatus,
        LocalDateTime lastSeenAt,
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
