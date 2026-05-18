package com.pbm.command.controller;

import com.pbm.command.common.ApiResponse;
import com.pbm.command.dto.request.AgentRunAbortRequest;
import com.pbm.command.dto.request.AgentRunApproveRequest;
import com.pbm.command.dto.request.AgentRunStepRequest;
import com.pbm.command.dto.response.AgentRunCreatedResponse;
import com.pbm.command.dto.response.AssignedRunResponse;
import com.pbm.command.dto.response.AgentRunResponse;
import com.pbm.command.dto.response.AgentRunStepResponse;
import com.pbm.command.exception.AgentRunAccessDeniedException;
import com.pbm.command.service.AgentRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AgentRun API 컨트롤러.
 *
 * 역할: 웹 앱의 run 생성/승인/중단 요청과 extension의 pending 조회/복구 요청을 처리한다.
 * 동작: 인증 주체에 따라 X-User-Id 또는 X-Device-Id 헤더를 받아 서비스로 위임한다.
 * 연관: AgentRunService.
 */
@Tag(name = "AgentRun", description = "브라우저 에이전트 run 생성, step 처리, 승인/중단 API")
@RestController
@RequestMapping("/api/v1")
public class AgentRunController {

    private final AgentRunService agentRunService;

    public AgentRunController(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Run 생성", description = "commandId 기준으로 새 브라우저 run을 생성하고 paired device에 할당합니다.")
    @PostMapping("/commands/{commandId}/runs")
    public ResponseEntity<ApiResponse<AgentRunCreatedResponse>> createRun(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable String commandId
    ) {
        AgentRunCreatedResponse response = agentRunService.createRun(userId, commandId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Pending run 조회", description = "extension이 현재 디바이스에 할당된 대기 중 run이 있는지 확인합니다.")
    @GetMapping("/runs/pending")
    public ResponseEntity<ApiResponse<AssignedRunResponse>> getPendingRun(
            @RequestHeader("X-Device-Id") String deviceId
    ) {
        AssignedRunResponse response = agentRunService.getPendingRunForDevice(deviceId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Run 상세 조회", description = "runId 기준 현재 run 상태와 메타데이터를 조회합니다.")
    @GetMapping("/runs/{runId}")
    public ResponseEntity<ApiResponse<AgentRunResponse>> getRun(
            @Parameter(description = "브라우저 run 식별자", example = "b83e5be8-9c49-401c-bf1d-6cfd454d2e0e")
            @PathVariable String runId
    ) {
        AgentRunResponse response = agentRunService.getRun(runId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Run 시작", description = "extension이 자신에게 할당된 run의 실행을 시작합니다.")
    @PostMapping("/runs/{runId}/start")
    public ResponseEntity<ApiResponse<AgentRunResponse>> startRun(
            @RequestHeader("X-Run-Id") String authenticatedRunId,
            @RequestHeader("X-Device-Id") String deviceId,
            @PathVariable String runId
    ) {
        if (!authenticatedRunId.equals(runId)) {
            throw new AgentRunAccessDeniedException("인증된 Run과 요청 경로의 runId가 일치하지 않습니다. runId=" + runId);
        }

        AgentRunResponse response = agentRunService.startRun(runId, deviceId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Run 복구", description = "중단/새로고침 이후 extension이 기존 run 실행을 복구하기 위한 agent token을 재발급받습니다.")
    @PostMapping("/runs/{runId}/recover")
    public ResponseEntity<ApiResponse<String>> recover(
            @RequestHeader("X-Device-Id") String deviceId,
            @PathVariable String runId
    ) {
        String agentToken = agentRunService.recover(runId, deviceId);
        return ResponseEntity.ok(new ApiResponse<>(true, agentToken, "성공"));
    }

    @Operation(summary = "Step 처리", description = "extension이 현재 페이지 snapshot과 이전 action 결과를 보내고, backend가 다음 브라우저 액션을 결정합니다.")
    @PostMapping("/runs/{runId}/steps")
    public ResponseEntity<ApiResponse<AgentRunStepResponse>> processStep(
            @RequestHeader("X-Run-Id") String authenticatedRunId,
            @RequestHeader("X-Device-Id") String deviceId,
            @PathVariable String runId,
            @RequestBody AgentRunStepRequest request
    ) {
        if (!authenticatedRunId.equals(runId)) {
            throw new AgentRunAccessDeniedException("인증된 Run과 요청 경로의 runId가 일치하지 않습니다. runId=" + runId);
        }

        AgentRunStepResponse response = agentRunService.processStep(runId, deviceId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Run 승인/거부", description = "사용자가 approval 대기 중인 run을 승인하거나 거부합니다.")
    @PostMapping("/runs/{runId}/approve")
    public ResponseEntity<ApiResponse<AgentRunResponse>> approve(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable String runId,
            @RequestBody AgentRunApproveRequest request
    ) {
        AgentRunResponse response = agentRunService.approve(runId, userId, request.approved());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Run 중단", description = "사용자 또는 extension이 현재 run을 중단합니다.")
    @PostMapping("/runs/{runId}/abort")
    public ResponseEntity<ApiResponse<AgentRunResponse>> abortByUser(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @PathVariable String runId,
            @RequestBody AgentRunAbortRequest request
    ) {
        AgentRunResponse response = agentRunService.abort(runId, request.reason(), deviceId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
