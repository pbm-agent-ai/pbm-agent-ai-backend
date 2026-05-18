package com.pbm.command.controller;

import com.pbm.command.common.ApiResponse;
import com.pbm.command.dto.request.BrowserDeviceRegisterRequest;
import com.pbm.command.dto.response.BrowserDeviceRegisterResponse;
import com.pbm.command.dto.response.BrowserHeartbeatResponse;
import com.pbm.command.exception.BrowserDeviceNotFoundException;
import com.pbm.command.service.BrowserDeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브라우저 디바이스 등록/heartbeat API 컨트롤러.
 *
 * 역할: pairing token으로 현재 브라우저 디바이스를 등록하고,
 *       device token으로 인증된 heartbeat를 수신한다.
 * 동작: 모든 응답은 ApiResponse<T> 래퍼로 감싸 반환한다.
 * 연관: BrowserDeviceService, BrowserDeviceRegisterRequest.
 */
@Tag(name = "BrowserDevice", description = "브라우저 extension 디바이스 등록 및 heartbeat API")
@RestController
@RequestMapping("/api/v1/devices")
public class BrowserDeviceController {

    private final BrowserDeviceService browserDeviceService;

    public BrowserDeviceController(BrowserDeviceService browserDeviceService) {
        this.browserDeviceService = browserDeviceService;
    }

    /**
     * 웹 앱이 발급한 pairing token을 사용해 브라우저 디바이스를 등록한다.
     *
     * @param request 디바이스 등록 요청 DTO
     * @return 등록 결과 응답 DTO
     */
    @Operation(summary = "브라우저 디바이스 등록", description = "pairing token으로 현재 extension 인스턴스를 브라우저 디바이스로 등록합니다.")
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<BrowserDeviceRegisterResponse>> register(
            @RequestBody BrowserDeviceRegisterRequest request
    ) {
        BrowserDeviceRegisterResponse response = browserDeviceService.register(request);
        return ResponseEntity.ok(new ApiResponse<>(true, response, "브라우저 디바이스 등록 성공"));
    }

    /**
     * 특정 브라우저 디바이스의 heartbeat를 수신하여 online TTL을 갱신한다.
     *
     * @param authenticatedDeviceId device token에서 gateway가 추출해 내려준 인증 디바이스 ID
     * @param deviceId heartbeat를 보낸 디바이스 식별자
     * @return heartbeat 처리 결과 DTO
     */
    @Operation(summary = "브라우저 heartbeat", description = "디바이스 online 상태를 갱신하고 현재 할당된 pending run 정보를 함께 반환합니다.")
    @PostMapping("/{deviceId}/heartbeat")
    public ResponseEntity<ApiResponse<BrowserHeartbeatResponse>> heartbeat(
            @RequestHeader("X-Device-Id") String authenticatedDeviceId,
            @PathVariable String deviceId
    ) {
        if (!authenticatedDeviceId.equals(deviceId)) {
            throw new BrowserDeviceNotFoundException("인증된 디바이스와 요청 경로의 deviceId가 일치하지 않습니다. deviceId=" + deviceId);
        }

        BrowserHeartbeatResponse response = browserDeviceService.heartbeat(deviceId);
        return ResponseEntity.ok(new ApiResponse<>(true, response, "브라우저 디바이스 heartbeat 수신 성공"));
    }
}
