package com.pbm.command.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.domain.AgentRunStatus;
import com.pbm.command.dto.request.AgentRunAbortRequest;
import com.pbm.command.dto.request.AgentRunApproveRequest;
import com.pbm.command.dto.request.AgentRunStepRequest;
import com.pbm.command.dto.request.PageSnapshotRequest;
import com.pbm.command.dto.response.AgentRunCreatedResponse;
import com.pbm.command.dto.response.ActionInstructionResponse;
import com.pbm.command.dto.response.AssignedRunResponse;
import com.pbm.command.dto.response.AgentRunResponse;
import com.pbm.command.dto.response.AgentRunStepResponse;
import com.pbm.command.exception.AgentRunNotFoundException;
import com.pbm.command.exception.GlobalExceptionHandler;
import com.pbm.command.service.AgentRunService;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AgentRunController 단위 테스트.
 */
@WebMvcTest(AgentRunController.class)
@Import(GlobalExceptionHandler.class)
class AgentRunControllerTest {

    @SpringBootApplication
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentRunService agentRunService;

    @Test
    @DisplayName("웹 앱이 commandId 기준으로 AgentRun을 생성한다")
    void createRun_returnsSuccess() throws Exception {
        AgentRunCreatedResponse response = new AgentRunCreatedResponse(
                "run-1",
                "cmd-1",
                "QUEUED",
                LocalDateTime.of(2026, 5, 16, 15, 30)
        );
        given(agentRunService.createRun(1L, "cmd-1")).willReturn(response);

        mockMvc.perform(post("/api/v1/commands/{commandId}/runs", "cmd-1")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    @DisplayName("디바이스가 pending run을 조회한다")
    void getPendingRun_returnsSuccess() throws Exception {
        AssignedRunResponse response = new AssignedRunResponse("run-1", "agent-token", "cmd-1", "ALIEXPRESS");
        given(agentRunService.getPendingRunForDevice("device-1")).willReturn(response);

        mockMvc.perform(get("/api/v1/runs/pending")
                        .header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.agentToken").value("agent-token"));
    }

    @Test
    @DisplayName("웹 앱이 승인 결과를 전달한다")
    void approve_returnsSuccess() throws Exception {
        AgentRunResponse response = new AgentRunResponse(
                "run-1", 1L, "cmd-1", "device-1",
                LocalDateTime.of(2026, 5, 16, 15, 31),
                AgentRunStatus.RUNNING, 0, null, null,
                LocalDateTime.of(2026, 5, 16, 15, 30),
                LocalDateTime.of(2026, 5, 16, 15, 31)
        );
        given(agentRunService.approve(eq("run-1"), eq(1L), eq(true))).willReturn(response);

        mockMvc.perform(post("/api/v1/runs/{runId}/approve", "run-1")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AgentRunApproveRequest(true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    @DisplayName("디바이스가 run 복구를 요청한다")
    void recover_returnsAgentToken() throws Exception {
        given(agentRunService.recover("run-1", "device-1")).willReturn("agent-token");

        mockMvc.perform(post("/api/v1/runs/{runId}/recover", "run-1")
                        .header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("agent-token"));
    }

    @Test
    @DisplayName("에이전트 토큰으로 run 시작을 요청한다")
    void startRun_returnsSuccess() throws Exception {
        AgentRunResponse response = new AgentRunResponse(
                "run-1", 1L, "cmd-1", "device-1",
                LocalDateTime.of(2026, 5, 16, 15, 31),
                AgentRunStatus.RUNNING, 0, null, null,
                LocalDateTime.of(2026, 5, 16, 15, 30),
                LocalDateTime.of(2026, 5, 16, 15, 32)
        );
        given(agentRunService.startRun("run-1", "device-1")).willReturn(response);

        mockMvc.perform(post("/api/v1/runs/{runId}/start", "run-1")
                        .header("X-Run-Id", "run-1")
                        .header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    @DisplayName("에이전트 토큰으로 step 실행 결과를 보고하고 다음 액션을 받는다")
    void processStep_returnsSuccess() throws Exception {
        AgentRunStepRequest request = new AgentRunStepRequest(
                0,
                null,
                new PageSnapshotRequest("https://www.aliexpress.com", "AliExpress", "", java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), "", LocalDateTime.of(2026, 5, 16, 16, 0))
        );
        AgentRunStepResponse response = new AgentRunStepResponse(
                "run-1",
                AgentRunStatus.RUNNING,
                0,
                ActionInstructionResponse.waitAction(0, "act-0", 1000, 15000)
        );
        given(agentRunService.processStep(eq("run-1"), eq("device-1"), eq(request))).willReturn(response);

        mockMvc.perform(post("/api/v1/runs/{runId}/steps", "run-1")
                        .header("X-Run-Id", "run-1")
                        .header("X-Device-Id", "device-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.instruction.action").value("WAIT"));
    }

    @Test
    @DisplayName("존재하지 않는 runId 조회 시 404를 반환한다")
    void getRun_withUnknownId_returns404() throws Exception {
        given(agentRunService.getRun("missing-run"))
                .willThrow(new AgentRunNotFoundException("AgentRun을 찾을 수 없습니다. runId=missing-run"));

        mockMvc.perform(get("/api/v1/runs/{runId}", "missing-run"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("run 중단 요청을 처리한다")
    void abort_returnsSuccess() throws Exception {
        AgentRunResponse response = new AgentRunResponse(
                "run-1", 1L, "cmd-1", "device-1",
                LocalDateTime.of(2026, 5, 16, 15, 31),
                AgentRunStatus.ABORTED, 0, null, "USER_CANCELLED",
                LocalDateTime.of(2026, 5, 16, 15, 30),
                LocalDateTime.of(2026, 5, 16, 15, 31)
        );
        given(agentRunService.abort(eq("run-1"), eq("USER_CANCELLED"), eq("device-1"), eq(null))).willReturn(response);

        mockMvc.perform(post("/api/v1/runs/{runId}/abort", "run-1")
                        .header("X-Device-Id", "device-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AgentRunAbortRequest("USER_CANCELLED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ABORTED"));
    }
}
