package com.pbm.command.dto.response;

import com.pbm.command.domain.BrowserDevice;
import com.pbm.command.domain.BrowserDeviceStatus;

import java.time.LocalDateTime;

/**
 * 내 디바이스 단건 응답 DTO.
 *
 * 역할: GET /api/v1/devices/my 응답에서 각 디바이스 정보를 담는다.
 * 연관: MyDevicesResponse, BrowserDeviceController.
 */
public record MyDeviceResponse(
        String deviceId,
        BrowserDeviceStatus status,
        String platform,
        String extensionVersion,
        String browserInfo,
        LocalDateTime lastSeenAt,
        LocalDateTime createdAt
) {
    public static MyDeviceResponse from(BrowserDevice device) {
        return new MyDeviceResponse(
                device.getDeviceId(),
                device.getStatus(),
                device.getPlatform(),
                device.getExtensionVersion(),
                device.getBrowserInfo(),
                device.getLastSeenAt(),
                device.getCreatedAt()
        );
    }
}
