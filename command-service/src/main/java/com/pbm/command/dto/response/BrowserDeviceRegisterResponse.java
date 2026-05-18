package com.pbm.command.dto.response;

import com.pbm.command.domain.BrowserDevice;
import com.pbm.command.domain.BrowserDeviceStatus;

import java.time.LocalDateTime;

/**
 * 브라우저 디바이스 등록 응답 DTO.
 *
 * 역할: register 완료 후 클라이언트가 자신의 식별자와 현재 상태를
 *       바로 확인할 수 있도록 최소 정보를 반환한다.
 * 연관: BrowserDeviceController, BrowserDevice.
 */
public record BrowserDeviceRegisterResponse(
        String deviceId,
        String deviceToken,
        BrowserDeviceStatus deviceStatus,
        LocalDateTime lastSeenAt
) {
    public static BrowserDeviceRegisterResponse from(BrowserDevice browserDevice, String deviceToken) {
        return new BrowserDeviceRegisterResponse(
                browserDevice.getDeviceId(),
                deviceToken,
                browserDevice.getStatus(),
                browserDevice.getLastSeenAt()
        );
    }
}
