package com.pbm.command.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.domain.CommandIntent;
import com.pbm.command.domain.CommandSessionStatus;
import com.pbm.command.domain.PlatformType;
import com.pbm.command.domain.ProductCategory;
import com.pbm.command.dto.request.CommandClarificationRequest;
import com.pbm.command.dto.request.ProductUrlSubmitRequest;
import com.pbm.command.dto.request.ProductSelectionRequest;
import com.pbm.command.dto.response.CommandParseResponse;
import com.pbm.command.dto.response.CommandSessionResponse;
import com.pbm.command.dto.response.ParsedCommand;
import com.pbm.command.dto.response.ProductCandidateResponse;
import com.pbm.command.exception.CommandSessionNotFoundException;
import com.pbm.command.exception.GlobalExceptionHandler;
import com.pbm.command.service.CommandExecutionService;
import com.pbm.command.service.CommandSessionService;
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
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CommandSessionController 단위 테스트.
 *
 * 역할: GET /api/v1/commands/{commandId} API가 정상 응답/404 응답을 반환하는지 검증한다.
 * 동작: CommandSessionService를 Mock으로 대체하고 MockMvc로 HTTP 요청/응답을 확인한다.
 * 연관: CommandSessionController, CommandSessionService.
 */
@WebMvcTest(CommandSessionController.class)
@Import(GlobalExceptionHandler.class)
class CommandSessionControllerTest {

    @SpringBootApplication
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CommandSessionService commandSessionService;

    @MockBean
    private CommandExecutionService commandExecutionService;

    @Test
    @DisplayName("commandId로 세션 조회 - 200 OK + 공통 응답 형식 반환")
    void getCommandSession_returnsSuccessResponse() throws Exception {
        // given
        String commandId = "test-uuid-1234";
        CommandSessionResponse response = new CommandSessionResponse(
                commandId,
                1L,
                "나이키 조던 20만원 이하면 결제해줘",
                CommandSessionStatus.SEARCHING,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                "NAVER",
                LocalDateTime.of(2026, 5, 11, 10, 0),
                LocalDateTime.of(2026, 5, 11, 10, 0)
        );

        given(commandSessionService.getByCommandId(commandId, 0, 10)).willReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/commands/{commandId}", commandId))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.commandId").value(commandId))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.status").value("SEARCHING"))
                .andExpect(jsonPath("$.data.originalCommand").value("나이키 조던 20만원 이하면 결제해줘"))
                .andExpect(jsonPath("$.data.missingFields").isEmpty())
                .andExpect(jsonPath("$.data.candidates").isEmpty())
                .andExpect(jsonPath("$.message").value("성공"));
    }

    @Test
    @DisplayName("commandId로 세션 조회 - page/size 파라미터를 전달한다")
    void getCommandSession_withPaginationParams_returnsPagedCandidates() throws Exception {
        String commandId = "test-uuid-page-1";
        CommandSessionResponse response = new CommandSessionResponse(
                commandId,
                1L,
                "테스트",
                CommandSessionStatus.PRODUCT_SELECTION_REQUIRED,
                List.of(),
                "검색 결과를 확인하고 상품을 선택해주세요.",
                null,
                List.of(
                        new ProductCandidateResponse("naver-11", "상품 11", "11000", "스토어11", "https://example.com/11", null, "KRW", "NAVER", "키보드"),
                        new ProductCandidateResponse("naver-12", "상품 12", "12000", "스토어12", "https://example.com/12", null, "KRW", "NAVER", "키보드")
                ),
                List.of(),
                null,
                100000,
                "AUTO_PURCHASE",
                "NAVER",
                LocalDateTime.of(2026, 5, 11, 10, 0),
                LocalDateTime.of(2026, 5, 11, 10, 0)
        );

        given(commandSessionService.getByCommandId(commandId, 1, 10)).willReturn(response);

        mockMvc.perform(get("/api/v1/commands/{commandId}", commandId)
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.candidates[0].productId").value("naver-11"))
                .andExpect(jsonPath("$.data.candidates[1].productId").value("naver-12"));
    }

    @Test
    @DisplayName("보완 제출 POST /clarifications - 200 OK + 재파싱 결과 공통 응답 반환 (free-text)")
    void submitClarification_returnsSuccessResponse() throws Exception {
        // given
        String commandId = "test-uuid-1234";
        CommandClarificationRequest request = new CommandClarificationRequest("검은색 270mm", null);
        CommandParseResponse parseResponse = new CommandParseResponse(
                CommandIntent.PRICE_CHECK,
                new ParsedCommand(
                        ProductCategory.SHOES,
                        "나이키 에어맥스",
                        "나이키",
                        "에어맥스",
                        null,
                        "검은색",
                        "270",
                        null,
                        150000,
                        null,
                        "KRW"
                ),
                List.of(),
                List.of(),
                false,
                0.95,
                commandId
        );

        given(commandExecutionService.handleClarification(eq(commandId), any(CommandClarificationRequest.class)))
                .willReturn(parseResponse);

        // when & then
        mockMvc.perform(post("/api/v1/commands/{commandId}/clarifications", commandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.intent").value("PRICE_CHECK"))
                .andExpect(jsonPath("$.data.parsedCommand.productName").value("나이키 에어맥스"))
                .andExpect(jsonPath("$.data.needsClarification").value(false))
                .andExpect(jsonPath("$.message").value("성공"));
    }

    @Test
    @DisplayName("보완 제출 POST /clarifications - structured answers 제출 시 200 OK")
    void submitClarification_withStructuredAnswers_returnsSuccessResponse() throws Exception {
        // given
        String commandId = "test-uuid-5678";
        CommandClarificationRequest request = new CommandClarificationRequest(
                null,
                Map.of("size", "270", "platform", "NAVER")
        );
        CommandParseResponse parseResponse = new CommandParseResponse(
                CommandIntent.PRICE_CHECK,
                new ParsedCommand(
                        ProductCategory.SHOES,
                        "나이키 에어맥스",
                        "나이키",
                        "에어맥스",
                        null,
                        null,
                        "270",
                        PlatformType.NAVER,
                        null,
                        null,
                        "KRW"
                ),
                List.of(),
                List.of(),
                false,
                0.94,
                commandId
        );

        given(commandExecutionService.handleClarification(eq(commandId), any(CommandClarificationRequest.class)))
                .willReturn(parseResponse);

        // when & then
        mockMvc.perform(post("/api/v1/commands/{commandId}/clarifications", commandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.intent").value("PRICE_CHECK"))
                .andExpect(jsonPath("$.data.parsedCommand.size").value("270"))
                .andExpect(jsonPath("$.data.parsedCommand.platform").value("NAVER"))
                .andExpect(jsonPath("$.data.needsClarification").value(false))
                .andExpect(jsonPath("$.message").value("성공"));
    }

    @Test
    @DisplayName("상품 선택 제출 POST /selection - 200 OK + PRICE_VALIDATING 상태 반환")
    void submitSelection_returnsSuccessResponse() throws Exception {
        // given
        String commandId = "test-uuid-selection";
        ProductSelectionRequest request = new ProductSelectionRequest(List.of("naver-1", "naver-2"));
        CommandSessionResponse sessionResponse = new CommandSessionResponse(
                commandId, 1L, "test",
                CommandSessionStatus.PRICE_VALIDATING,
                null, null, null, List.of(),
                List.of("naver-1", "naver-2"), null, null, null,
                null, null
        );

        given(commandExecutionService.handleProductSelection(eq(commandId), any(ProductSelectionRequest.class)))
                .willReturn(sessionResponse);

        // when & then
        mockMvc.perform(post("/api/v1/commands/{commandId}/selection", commandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PRICE_VALIDATING"))
                .andExpect(jsonPath("$.data.selectedProductIds").isArray())
                .andExpect(jsonPath("$.data.selectedProductIds[0]").value("naver-1"))
                .andExpect(jsonPath("$.data.selectedProductIds[1]").value("naver-2"))
                .andExpect(jsonPath("$.message").value("성공"));
    }

    @Test
    @DisplayName("상품 URL 제출 POST /product-links - 200 OK + SEARCHING 상태 반환")
    void submitProductUrls_returnsSuccessResponse() throws Exception {
        String commandId = "test-uuid-ali-links";
        ProductUrlSubmitRequest request = new ProductUrlSubmitRequest(List.of(
                "https://ko.aliexpress.com/item/1005006782975346.html",
                "https://ko.aliexpress.com/item/1005010633549414.html"
        ));
        CommandSessionResponse sessionResponse = new CommandSessionResponse(
                commandId, 1L, "test", CommandSessionStatus.SEARCHING,
                List.of(), null, null, List.of(), List.of(), null, 100000, "AUTO_PURCHASE", null, null
        );

        given(commandExecutionService.handleProductUrlSubmission(eq(commandId), any(ProductUrlSubmitRequest.class)))
                .willReturn(sessionResponse);

        mockMvc.perform(post("/api/v1/commands/{commandId}/product-links", commandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SEARCHING"))
                .andExpect(jsonPath("$.message").value("성공"));
    }

    @Test
    @DisplayName("commandId로 세션 조회 - 누락 필드가 있으면 JSON 배열로 반환")
    void getCommandSession_withMissingFields_returnsArray() throws Exception {
        // given
        String commandId = "test-uuid-5678";
        CommandSessionResponse response = new CommandSessionResponse(
                commandId,
                1L,
                "나이키 조던 20만원 이하면 결제해줘",
                CommandSessionStatus.PRE_SEARCH_CLARIFICATION,
                List.of("size", "platform"),
                "사이즈와 플랫폼을 알려주세요.",
                null,
                List.of(),
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 5, 11, 10, 0),
                LocalDateTime.of(2026, 5, 11, 10, 0)
        );

        given(commandSessionService.getByCommandId(commandId, 0, 10)).willReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/commands/{commandId}", commandId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missingFields").isArray())
                .andExpect(jsonPath("$.data.missingFields[0]").value("size"))
                .andExpect(jsonPath("$.data.missingFields[1]").value("platform"))
                .andExpect(jsonPath("$.data.missingFields.length()").value(2));
    }

    @Test
    @DisplayName("PRODUCT_SELECTION_REQUIRED 상태 조회 - candidates, targetPrice, commandIntent 포함")
    void getCommandSession_withProductSelectionRequired_returnsCandidatesAndMetadata() throws Exception {
        // given
        String commandId = "test-uuid-post-search";
        List<ProductCandidateResponse> candidates = List.of(
                new ProductCandidateResponse(
                        "naver-1", "나이키 에어포스 1", "120000",
                        "스토어A", "https://example.com/1", null, "KRW", "NAVER", "나이키 에어포스"),
                new ProductCandidateResponse(
                        "naver-2", "나이키 에어포스 2", "130000",
                        "스토어B", "https://example.com/2", null, "KRW", "NAVER", "나이키 에어포스")
        );
        CommandSessionResponse response = new CommandSessionResponse(
                commandId,
                1L,
                "나이키 에어포스 20만원 이하면 결제해줘",
                CommandSessionStatus.PRODUCT_SELECTION_REQUIRED,
                List.of("searchResultsCount"),
                "AUTO_PURCHASE 의도이나 검색 결과가 2개로 명확하지 않습니다",
                null,
                candidates,
                null,
                null,
                200000,
                "AUTO_PURCHASE",
                LocalDateTime.of(2026, 5, 11, 10, 0),
                LocalDateTime.of(2026, 5, 11, 10, 0)
        );

        given(commandSessionService.getByCommandId(commandId, 0, 10)).willReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/commands/{commandId}", commandId))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.commandId").value(commandId))
                .andExpect(jsonPath("$.data.status").value("PRODUCT_SELECTION_REQUIRED"))
                .andExpect(jsonPath("$.data.candidates").isArray())
                .andExpect(jsonPath("$.data.candidates.length()").value(2))
                .andExpect(jsonPath("$.data.candidates[0].productId").value("naver-1"))
                .andExpect(jsonPath("$.data.candidates[0].title").value("나이키 에어포스 1"))
                .andExpect(jsonPath("$.data.candidates[0].lprice").value("120000"))
                .andExpect(jsonPath("$.data.candidates[0].mallName").value("스토어A"))
                .andExpect(jsonPath("$.data.candidates[0].productUrl").value("https://example.com/1"))
                .andExpect(jsonPath("$.data.candidates[0].currency").value("KRW"))
                .andExpect(jsonPath("$.data.candidates[0].searchKeyword").value("나이키 에어포스"))
                .andExpect(jsonPath("$.data.candidates[1].productId").value("naver-2"))
                .andExpect(jsonPath("$.data.targetPrice").value(200000))
                .andExpect(jsonPath("$.data.commandIntent").value("AUTO_PURCHASE"))
                .andExpect(jsonPath("$.data.missingFields[0]").value("searchResultsCount"))
                .andExpect(jsonPath("$.message").value("성공"));
    }

    @Test
    @DisplayName("존재하지 않는 commandId로 세션 조회 - 404 Not Found 응답 반환")
    void getCommandSession_withNonExistentId_returnsNotFound() throws Exception {
        // given
        String invalidCommandId = "non-existent-uuid";

        given(commandSessionService.getByCommandId(invalidCommandId, 0, 10))
                .willThrow(new CommandSessionNotFoundException(
                        "세션을 찾을 수 없습니다. commandId: " + invalidCommandId));

        // when & then
        mockMvc.perform(get("/api/v1/commands/{commandId}", invalidCommandId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        "세션을 찾을 수 없습니다. commandId: " + invalidCommandId));
    }
}
