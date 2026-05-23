package com.pbm.command.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 내 디바이스 목록 응답 DTO.
 *
 * 역할: GET /api/v1/devices/my 응답 래퍼.
 * 연관: BrowserDeviceController.
 */
@Schema(description = "로그인 사용자의 브라우저 디바이스 목록 응답 DTO")
public record MyDevicesResponse(
        @Schema(description = "페어링된 디바이스 목록")
        List<MyDeviceResponse> devices
) {
    public static MyDevicesResponse of(List<MyDeviceResponse> devices) {
        return new MyDevicesResponse(devices);
    }
}
