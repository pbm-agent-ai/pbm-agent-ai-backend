package com.pbm.command.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.domain.CommandIntent;
import com.pbm.command.domain.ProductCategory;
import com.pbm.command.dto.request.CommandParseRequest;
import com.pbm.command.dto.response.CommandParseResponse;
import com.pbm.command.dto.response.ParsedCommand;
import com.pbm.command.exception.ExternalApiProxyException;
import com.pbm.command.exception.GlobalExceptionHandler;
import com.pbm.command.service.CommandExecutionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CommandParseController 단위 테스트.
 *
 * 역할: parse API가 공통 응답 형식으로 파싱 결과를 반환하는지 검증한다.
 *       CommandExecutionService를 통해 파싱 후 조건부 Kafka 발행이 이뤄진다.
 * 동작: CommandExecutionService를 Mock으로 대체하고 MockMvc로 HTTP 요청/응답을 확인한다.
 * 연관: CommandParseController, CommandExecutionService.
 */
@WebMvcTest(CommandParseController.class)
@Import(GlobalExceptionHandler.class)
class CommandParseControllerTest {

    @SpringBootApplication
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CommandExecutionService commandExecutionService;

    @Test
    @DisplayName("자연어 파싱 요청 - 공통 응답 형식으로 parse 결과 반환")
    void parseCommand_returnsSuccessResponse() throws Exception {
        CommandParseRequest request = new CommandParseRequest("나이키 조던 20만원 이하면 결제해줘");
        CommandParseResponse response = new CommandParseResponse(
                CommandIntent.AUTO_PURCHASE,
                new ParsedCommand(
                        ProductCategory.SHOES,
                        "나이키 조던",
                        "나이키",
                        "조던",
                        null,
                        null,
                        null,
                        null,
                        200000,
                        null,
                        "KRW"
                ),
                List.of("size", "platform"),
                List.of("color", "model"),
                true,
                0.55,
                "test-command-uuid"
        );

        when(commandExecutionService.parseAndPublishIfReady(any(CommandParseRequest.class), eq(1L))).thenReturn(response);

        mockMvc.perform(post("/api/v1/commands/parse")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.intent").value("AUTO_PURCHASE"))
                .andExpect(jsonPath("$.data.parsedCommand.productCategory").value("SHOES"))
                .andExpect(jsonPath("$.data.missingRequiredFields[0]").value("size"))
                .andExpect(jsonPath("$.data.missingRequiredFields[1]").value("platform"))
                .andExpect(jsonPath("$.data.ambiguousFields[0]").value("color"))
                .andExpect(jsonPath("$.data.needsClarification").value(true))
                .andExpect(jsonPath("$.data.commandId").value("test-command-uuid"))
                .andExpect(jsonPath("$.message").value("성공"));
    }

    @Test
    @DisplayName("자연어 파싱 요청 - external-api-service 장애 시 503 응답 반환")
    void parseCommand_returnsServiceUnavailableWhenProxyFails() throws Exception {
        CommandParseRequest request = new CommandParseRequest("나이키 조던 20만원 이하면 결제해줘");

        when(commandExecutionService.parseAndPublishIfReady(any(CommandParseRequest.class), eq(1L)))
                .thenThrow(new ExternalApiProxyException("external-api-service 장애"));

        mockMvc.perform(post("/api/v1/commands/parse")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("외부 AI 파싱 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해주세요."));
    }
}
