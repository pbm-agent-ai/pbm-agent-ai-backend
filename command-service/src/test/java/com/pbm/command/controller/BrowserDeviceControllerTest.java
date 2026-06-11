package com.pbm.command.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.domain.BrowserDeviceStatus;
import com.pbm.command.dto.request.BrowserDeviceRegisterRequest;
import com.pbm.command.dto.response.AssignedRunResponse;
import com.pbm.command.dto.response.BrowserDeviceRegisterResponse;
import com.pbm.command.dto.response.BrowserHeartbeatResponse;
import com.pbm.command.exception.BrowserDeviceNotFoundException;
import com.pbm.command.exception.GlobalExceptionHandler;
import com.pbm.command.service.BrowserDeviceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BrowserDeviceController 단위 테스트.
 *
 * 역할: 브라우저 디바이스 register/heartbeat API의 정상 응답과 예외 응답을 검증한다.
 * 동작: BrowserDeviceService를 Mock으로 대체하고 MockMvc로 HTTP 요청/응답을 확인한다.
 * 연관: BrowserDeviceController, BrowserDeviceService.
 */
@WebMvcTest(BrowserDeviceController.class)
@Import(GlobalExceptionHandler.class)
class BrowserDeviceControllerTest {

    @SpringBootApplication
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BrowserDeviceService browserDeviceService;

    @Test
    @DisplayName("브라우저 디바이스 등록 - 200 OK + 공통 응답 형식 반환")
    void register_returnsSuccessResponse() throws Exception {
        // given
        BrowserDeviceRegisterRequest request = new BrowserDeviceRegisterRequest(
                "pairing-token",
                "device-123",
                "CHROME",
                "1.0.0",
                "macOS Chrome"
        );
        BrowserDeviceRegisterResponse response = new BrowserDeviceRegisterResponse(
                "device-123",
                "device-token",
                BrowserDeviceStatus.ONLINE,
                LocalDateTime.of(2026, 5, 16, 13, 0)
        );

        given(browserDeviceService.register(any(BrowserDeviceRegisterRequest.class)))
                .willReturn(response);

        // when & then
        mockMvc.perform(post("/api/v1/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deviceId").value("device-123"))
                .andExpect(jsonPath("$.data.deviceToken").value("device-token"))
                .andExpect(jsonPath("$.data.deviceStatus").value("ONLINE"))
                .andExpect(jsonPath("$.message").value("브라우저 디바이스 등록 성공"));
    }

    @Test
    @DisplayName("브라우저 디바이스 heartbeat - 200 OK + 공통 응답 형식 반환")
    void heartbeat_returnsSuccessResponse() throws Exception {
        // given
        BrowserHeartbeatResponse response = new BrowserHeartbeatResponse(
                "device-123",
                BrowserDeviceStatus.ONLINE,
                LocalDateTime.of(2026, 5, 16, 13, 5),
                new AssignedRunResponse("run-1", "agent-token", "cmd-1", "ALIEXPRESS"),
                java.util.List.of()
        );

        given(browserDeviceService.heartbeat("device-123")).willReturn(response);

        // when & then
        mockMvc.perform(post("/api/v1/devices/{deviceId}/heartbeat", "device-123")
                        .header("X-Device-Id", "device-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deviceId").value("device-123"))
                .andExpect(jsonPath("$.data.deviceStatus").value("ONLINE"))
                .andExpect(jsonPath("$.data.assignedRun.runId").value("run-1"))
                .andExpect(jsonPath("$.message").value("브라우저 디바이스 heartbeat 수신 성공"));
    }

    @Test
    @DisplayName("브라우저 디바이스 heartbeat - 존재하지 않으면 404 반환")
    void heartbeat_withUnknownDevice_returns404() throws Exception {
        // given
        given(browserDeviceService.heartbeat("missing-device"))
                .willThrow(new BrowserDeviceNotFoundException("브라우저 디바이스를 찾을 수 없습니다. deviceId=missing-device"));

        // when & then
        mockMvc.perform(post("/api/v1/devices/{deviceId}/heartbeat", "missing-device")
                        .header("X-Device-Id", "missing-device"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("브라우저 디바이스를 찾을 수 없습니다. deviceId=missing-device"));
    }

    @Test
    @DisplayName("브라우저 디바이스 heartbeat - 인증된 deviceId와 path deviceId가 다르면 404 반환")
    void heartbeat_withMismatchedDeviceId_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/devices/{deviceId}/heartbeat", "device-123")
                        .header("X-Device-Id", "device-999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }
}
