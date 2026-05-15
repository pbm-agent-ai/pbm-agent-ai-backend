package com.pbm.command.controller;

import com.pbm.command.common.ApiResponse;
import com.pbm.command.dto.request.CommandClarificationRequest;
import com.pbm.command.dto.request.ProductSelectionRequest;
import com.pbm.command.dto.response.CommandParseResponse;
import com.pbm.command.dto.response.CommandSessionResponse;
import com.pbm.command.service.CommandExecutionService;
import com.pbm.command.service.CommandSessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 명령 세션 조회/보완 API 컨트롤러.
 *
 * 역할: 프론트가 특정 commandId의 세션 상태를 폴링할 수 있도록 GET 엔드포인트를 제공한다.
 *       pre-search/post-search 보완 이후 상태 변경 감지에 사용된다.
 *       또한 사용자가 보완 입력을 제출하면 POST /clarifications로 재파싱을 트리거한다.
 * 동작:
 *   - `GET  /api/v1/commands/{commandId}` : 세션 상태 조회
 *   - `POST /api/v1/commands/{commandId}/clarifications` : 보완 입력 제출 및 재파싱
 * 연관: CommandSessionService, CommandExecutionService, CommandClarificationRequest.
 */
@RestController
@RequestMapping("/api/v1/commands")
public class CommandSessionController {

    private final CommandSessionService commandSessionService;
    private final CommandExecutionService commandExecutionService;

    public CommandSessionController(
            CommandSessionService commandSessionService,
            CommandExecutionService commandExecutionService
    ) {
        this.commandSessionService = commandSessionService;
        this.commandExecutionService = commandExecutionService;
    }

    /**
     * commandId로 명령 세션 상태를 조회한다.
     * 프론트에서 pre-search/post-search 보완 후 폴링 용도로 사용한다.
     *
     * @param commandId UUID 형식의 세션 식별자
     * @return 세션 상태 응답 DTO
     */
    @GetMapping("/{commandId}")
    public ResponseEntity<ApiResponse<CommandSessionResponse>> getCommandSession(
            @PathVariable String commandId
    ) {
        CommandSessionResponse response = commandSessionService.getByCommandId(commandId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 보완(Clarification) 입력을 제출하고 재파싱을 실행한다.
     * <p>
     * 역할: PRE_SEARCH_CLARIFICATION 또는 PRODUCT_SELECTION_REQUIRED 상태에서
     *       사용자가 누락된 정보를 추가로 입력했을 때 호출한다.
     *       free-text(clarificationInput)와 structured answers(answers)를 모두 지원한다.
     *       서비스 계층에서 originalCommand + 보완 입력 병합 후 재파싱한다.
     *
     * @param commandId   UUID 형식의 세션 식별자 (path param)
     * @param request     보완 입력 요청 DTO (clarificationInput + answers)
     * @return 재파싱 결과 응답 DTO
     */
    @PostMapping("/{commandId}/clarifications")
    public ResponseEntity<ApiResponse<CommandParseResponse>> submitClarification(
            @PathVariable String commandId,
            @RequestBody CommandClarificationRequest request
    ) {
        CommandParseResponse response = commandExecutionService.handleClarification(
                commandId, request
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 후보 상품 다중 선택을 제출하고 실시간 검증을 시작한다.
     *
     * @param commandId UUID 형식의 세션 식별자
     * @param request   선택한 productId 목록 요청 DTO
     * @return PRICE_VALIDATING 상태로 전환된 세션 응답 DTO
     */
    @PostMapping("/{commandId}/selection")
    public ResponseEntity<ApiResponse<CommandSessionResponse>> submitSelection(
            @PathVariable String commandId,
            @RequestBody ProductSelectionRequest request
    ) {
        CommandSessionResponse response = commandExecutionService.handleProductSelection(commandId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
