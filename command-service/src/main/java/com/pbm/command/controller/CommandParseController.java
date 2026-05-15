package com.pbm.command.controller;

import com.pbm.command.common.ApiResponse;
import com.pbm.command.dto.request.CommandParseRequest;
import com.pbm.command.dto.response.CommandParseResponse;
import com.pbm.command.service.CommandExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자연어 명령 파싱 API 컨트롤러.
 *
 * 역할: 프론트가 보낸 자연어 명령을 받아 오케스트레이션 서비스로 전달하고,
 *       모달 보완에 필요한 응답 구조를 공통 응답 래퍼로 감싸 반환한다.
 * 동작: `POST /api/v1/commands/parse` 요청 수신 → CommandExecutionService.parseAndPublishIfReady() 호출 →
 *       ApiResponse 반환. 추가 확인이 필요 없으면 price-topic으로 가격 요청이 자동 발행된다.
 * 연관: CommandExecutionService, CommandParseRequest, CommandParseResponse.
 */
@RestController
@RequestMapping("/api/v1/commands")
public class CommandParseController {

    private final CommandExecutionService commandExecutionService;

    public CommandParseController(CommandExecutionService commandExecutionService) {
        this.commandExecutionService = commandExecutionService;
    }

    /**
     * 자연어 명령을 파싱하여 구조화된 응답으로 반환한다.
     * 파싱 결과에 추가 확인이 필요 없으면 자동으로 price-topic에 가격 요청을 발행한다.
     *
     * @param request 사용자 자연어 명령 요청 DTO
     * @return 파싱 결과 응답 DTO
     */
    @PostMapping("/parse")
    public ResponseEntity<ApiResponse<CommandParseResponse>> parseCommand(
            @RequestBody CommandParseRequest request
    ) {
        CommandParseResponse response = commandExecutionService.parseAndPublishIfReady(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
