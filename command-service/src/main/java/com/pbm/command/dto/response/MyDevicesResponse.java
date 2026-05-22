package com.pbm.command.dto.response;

import java.util.List;

/**
 * 내 디바이스 목록 응답 DTO.
 *
 * 역할: GET /api/v1/devices/my 응답 래퍼.
 * 연관: BrowserDeviceController.
 */
public record MyDevicesResponse(
        List<MyDeviceResponse> devices
) {
    public static MyDevicesResponse of(List<MyDeviceResponse> devices) {
        return new MyDevicesResponse(devices);
    }
}
