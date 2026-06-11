package com.pbm.command.service;

import com.pbm.command.domain.CommandIntent;
import com.pbm.command.domain.CommandSession;
import com.pbm.command.domain.CommandSessionStatus;
import com.pbm.command.domain.PlatformType;
import com.pbm.command.domain.ProductCategory;
import com.pbm.command.dto.event.ProductCandidateDto;
import com.pbm.command.dto.event.ProductSelectionEvent;
import com.pbm.command.dto.request.CommandClarificationRequest;
import com.pbm.command.dto.request.CommandParseRequest;
import com.pbm.command.dto.request.ProductUrlSubmitRequest;
import com.pbm.command.dto.request.ProductSelectionRequest;
import com.pbm.command.dto.response.CommandParseResponse;
import com.pbm.command.dto.response.CommandSessionResponse;
import com.pbm.command.dto.response.ParsedCommand;
import com.pbm.command.exception.InvalidProductSelectionException;
import com.pbm.command.publisher.ProductSelectionEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CommandExecutionService 단위 테스트.
 *
 * 역할: 파싱 결과의 needsClarification 여부에 따라
 *       PriceRequestService.publishParsedCommandRequest 호출 여부를 검증한다.
 * 동작: CommandParsingService와 PriceRequestService를 Mock으로 대체하여
 *       오케스트레이션 로직만 단위 테스트한다.
 * 연관: CommandExecutionService, CommandParsingService, PriceRequestService.
 */
@ExtendWith(MockitoExtension.class)
class CommandExecutionServiceTest {

    @Mock
    private CommandParsingService commandParsingService;

    @Mock
    private PriceRequestService priceRequestService;

    @Mock
    private CommandSessionService commandSessionService;

    @Mock
    private ProductSelectionEventPublisher productSelectionEventPublisher;

    @Mock
    private CommandFieldEvaluationService commandFieldEvaluationService;

    @InjectMocks
    private CommandExecutionService commandExecutionService;

    @Test
    @DisplayName("needsClarification이 false이면 SEARCHING 세션 생성 + 가격 요청 발행 + commandId 반환")
    void parseAndPublishIfReady_noClarificationNeeded_publishesPriceRequest() {
        // given
        String expectedCommandId = "cmd-uuid-7890";
        Long userId = 1L;
        CommandParseRequest request = new CommandParseRequest("나이키 에어맥스 270 네이버에서 15만원 이하 가격 알려줘");

        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.SHOES,
                "나이키 에어맥스",
                "나이키",
                "에어맥스",
                null,
                null,
                "270",
                null,
                150000,
                null,
                "KRW"
        );

        CommandParseResponse response = new CommandParseResponse(
                CommandIntent.PRICE_CHECK,
                parsedCommand,
                List.of(),     // missingRequiredFields 비어있음
                List.of(),     // ambiguousFields 비어있음
                false,         // needsClarification == false
                0.92,
                null          // 아직 commandId 없음
        );

        when(commandParsingService.parse(any(CommandParseRequest.class))).thenReturn(response);

        // SEARCHING 세션 생성 mock
        CommandSessionResponse sessionResponse = new CommandSessionResponse(
                expectedCommandId, 1L, request.commandText(), null, null, null, null,
                List.of(), null, null, null, null, "NAVER", null, null
        );
        given(commandSessionService.createSearchingSession(userId, request.commandText(), null))
                .willReturn(sessionResponse);

        // when
        CommandParseResponse actual = commandExecutionService.parseAndPublishIfReady(request, userId);

        // then
        assertThat(actual.commandId()).isEqualTo(expectedCommandId);
        assertThat(actual.intent()).isEqualTo(response.intent());
        assertThat(actual.needsClarification()).isFalse();
        verify(priceRequestService).publishParsedCommandRequest(
                userId, "PRICE_CHECK", parsedCommand, expectedCommandId);
    }

    @Test
    @DisplayName("AUTO_PURCHASE + needsClarification=true이면 PRE_SEARCH_CLARIFICATION 세션 생성 + 발행 보류")
    void parseAndPublishIfReady_autoPurchase_clarificationNeeded_goesToClarification() {
        // given
        String expectedCommandId = "cmd-uuid-auto-clarify";
        Long userId = 1L;
        CommandParseRequest request = new CommandParseRequest("나이키 조던 결제해줘");

        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.SHOES,
                "나이키 조던",
                "나이키",
                "조던",
                null, null, null, null,
                null,   // maxPrice 없음 → clarification 필요
                null,
                "KRW",
                null, null, null, null
        );

        CommandParseResponse response = new CommandParseResponse(
                CommandIntent.AUTO_PURCHASE,
                parsedCommand,
                List.of("maxPrice"),    // maxPrice 누락
                List.of(),
                true,                   // needsClarification = true
                0.91,
                null
        );

        when(commandParsingService.parse(any(CommandParseRequest.class))).thenReturn(response);

        CommandSessionResponse sessionResponse = new CommandSessionResponse(
                expectedCommandId, 1L, request.commandText(), null, null, null, null,
                List.of(), null, null, null, null, null, null, null
        );
        given(commandSessionService.createPreSearchClarificationSession(
                eq(userId), eq(request.commandText()), eq(List.of("maxPrice")), anyString(), eq(null)
        )).willReturn(sessionResponse);

        // when
        CommandParseResponse actual = commandExecutionService.parseAndPublishIfReady(request, userId);

        // then
        assertThat(actual.commandId()).isEqualTo(expectedCommandId);
        assertThat(actual.intent()).isEqualTo(CommandIntent.AUTO_PURCHASE);
        assertThat(actual.needsClarification()).isTrue();
        assertThat(actual.missingRequiredFields()).containsExactly("maxPrice");
        // Kafka 발행은 일어나지 않아야 함
        verify(priceRequestService, never()).publishParsedCommandRequest(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("AUTO_PURCHASE + needsClarification=false이면 SEARCHING 세션 생성 + 가격 요청 발행")
    void parseAndPublishIfReady_autoPurchaseUnknownCategory_goesToClarification() {
        String expectedCommandId = "cmd-uuid-auto-unknown";
        Long userId = 6L;
        CommandParseRequest request = new CommandParseRequest("네이버에서 칠성사이다 210ml 30개가 50000원 이하면 구매해줘");

        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.UNKNOWN,
                "칠성사이다 210ml 30개",
                "칠성사이다",
                null, null, null,
                "210ml 30개",
                java.util.List.of(PlatformType.NAVER),
                50000,
                null,
                "KRW",
                null, null, null, null
        );

        CommandParseResponse response = new CommandParseResponse(
                CommandIntent.AUTO_PURCHASE,
                parsedCommand,
                List.of(),
                List.of(),
                false,  // needsClarification=false → SEARCHING 경로
                0.98,
                null
        );

        when(commandParsingService.parse(any(CommandParseRequest.class))).thenReturn(response);

        CommandSessionResponse sessionResponse = new CommandSessionResponse(
                expectedCommandId, userId, request.commandText(), null, null, null, null,
                List.of(), null, null, null, null, "NAVER", null, null
        );
        given(commandSessionService.createSearchingSession(
                eq(userId), eq(request.commandText()), eq("NAVER")
        )).willReturn(sessionResponse);

        CommandParseResponse actual = commandExecutionService.parseAndPublishIfReady(request, userId);

        // then: needsClarification=false → SEARCHING 경로
        assertThat(actual.commandId()).isEqualTo(expectedCommandId);
        assertThat(actual.needsClarification()).isFalse();
        assertThat(actual.parsedCommand().productName()).isEqualTo("칠성사이다 210ml 30개");
        assertThat(actual.parsedCommand().platforms()).containsExactly(PlatformType.NAVER);
        assertThat(actual.parsedCommand().maxPrice()).isEqualTo(50000);
        // SEARCHING 세션 생성 + Kafka 발행
        verify(priceRequestService).publishParsedCommandRequest(eq(userId), anyString(), any(), eq(expectedCommandId));
        verify(commandSessionService, never()).createPreSearchClarificationSession(anyLong(), anyString(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("PRICE_CHECK은 needsClarification 여부에 따라 기존 분기를 유지한다 (AUTO_PURCHASE가 아닌 경우)")
    void parseAndPublishIfReady_priceCheck_usesExistingClarificationLogic() {
        // given - PRICE_CHECK + needsClarification=true
        String expectedCommandId = "cmd-uuid-price-clarify";
        Long userId = 2L;
        CommandParseRequest request = new CommandParseRequest("아이폰 가격 알려줘");

        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.ELECTRONICS,
                "아이폰",
                "애플",
                null,
                null,
                null,
                null,
                null,           // platform 누락
                null,           // maxPrice 누락 (PRICE_CHECK이므로 skip)
                null,
                "KRW"
        );

        CommandParseResponse response = new CommandParseResponse(
                CommandIntent.PRICE_CHECK,
                parsedCommand,
                List.of("platform"),
                List.of(),
                true,
                0.85,
                null
        );

        when(commandParsingService.parse(any(CommandParseRequest.class))).thenReturn(response);

        CommandSessionResponse sessionResponse = new CommandSessionResponse(
                expectedCommandId, userId, request.commandText(), null, null, null, null,
                List.of(), null, null, null, null, null, null, null
        );
        given(commandSessionService.createPreSearchClarificationSession(
                eq(userId), eq(request.commandText()), eq(List.of("platform")), anyString(), eq(null)
        )).willReturn(sessionResponse);

        // when
        CommandParseResponse actual = commandExecutionService.parseAndPublishIfReady(request, userId);

        // then - PRICE_CHECK의 needsClarification=true는 기존처럼 PRE_SEARCH_CLARIFICATION
        assertThat(actual.commandId()).isEqualTo(expectedCommandId);
        assertThat(actual.intent()).isEqualTo(CommandIntent.PRICE_CHECK);
        assertThat(actual.needsClarification()).isTrue();
        assertThat(actual.missingRequiredFields()).containsExactly("platform");
        verify(priceRequestService, never()).publishParsedCommandRequest(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("PRICE_CHECK은 needsClarification=false이면 기존처럼 SEARCHING 세션 생성 후 발행한다")
    void parseAndPublishIfReady_priceCheckNoClarification_publishes() {
        // given - PRICE_CHECK + needsClarification=false
        String expectedCommandId = "cmd-uuid-price-search";
        Long userId = 3L;
        CommandParseRequest request = new CommandParseRequest("아이폰 15 프로 140만원 NAVER 가격 알려줘");

        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.ELECTRONICS,
                "아이폰 15 프로",
                "애플",
                "아이폰",
                "15 프로",
                null,
                null,
                java.util.List.of(PlatformType.NAVER),
                1400000,
                null,
                "KRW"
        );

        CommandParseResponse response = new CommandParseResponse(
                CommandIntent.PRICE_CHECK,
                parsedCommand,
                List.of(),
                List.of(),
                false,
                0.95,
                null
        );

        when(commandParsingService.parse(any(CommandParseRequest.class))).thenReturn(response);

        CommandSessionResponse sessionResponse = new CommandSessionResponse(
                expectedCommandId, userId, request.commandText(), null, null, null, null,
                List.of(), null, null, null, null, "NAVER", null, null
        );
        given(commandSessionService.createSearchingSession(eq(userId), eq(request.commandText()), eq("NAVER")))
                .willReturn(sessionResponse);

        // when
        CommandParseResponse actual = commandExecutionService.parseAndPublishIfReady(request, userId);

        // then - PRICE_CHECK의 needsClarification=false는 기존처럼 SEARCHING + 발행
        assertThat(actual.commandId()).isEqualTo(expectedCommandId);
        assertThat(actual.intent()).isEqualTo(CommandIntent.PRICE_CHECK);
        assertThat(actual.needsClarification()).isFalse();
        verify(priceRequestService).publishParsedCommandRequest(userId, "PRICE_CHECK", parsedCommand, expectedCommandId);
        verify(commandSessionService, never()).createPreSearchClarificationSession(anyLong(), anyString(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("handleClarification - 재파싱 결과 여전히 보완 필요 → PRE_SEARCH_CLARIFICATION 업데이트, 발행 안 함 (free-text)")
    void handleClarification_stillNeedsClarification_updatesToPreSearch() {
        // given
        String commandId = "test-uuid-clarification";
        CommandClarificationRequest request = new CommandClarificationRequest("검은색", null);

        // 세션 엔티티 mock
        CommandSession session = CommandSession.builder()
                .commandId(commandId)
                .userId(1L)
                .originalCommand("나이키 에어맥스")
                .status(CommandSessionStatus.PRE_SEARCH_CLARIFICATION)
                .build();

        given(commandSessionService.getSessionEntityByCommandId(commandId)).willReturn(session);

        // 재파싱 결과: 여전히 보완 필요
        ParsedCommand reparsedCommand = new ParsedCommand(
                ProductCategory.SHOES, "나이키 에어맥스", "나이키", null, null, "검은색", null, null, null, null, null
        );
        CommandParseResponse reparseResponse = new CommandParseResponse(
                CommandIntent.PRICE_CHECK,
                reparsedCommand,
                List.of("size", "platform"),   // 여전히 누락
                List.of("color"),               // 여전히 모호
                true,
                0.65,
                null                            // 아직 commandId 없음 (세션에서 주입됨)
        );

        when(commandParsingService.parse(any(CommandParseRequest.class))).thenReturn(reparseResponse);
        // applyStructuredAnswers(answers=null) → no-op → 동일한 parsedCommand로 재평가
        when(commandFieldEvaluationService.evaluate(eq(CommandIntent.PRICE_CHECK), any(ParsedCommand.class)))
                .thenReturn(new FieldEvaluationResult(List.of("size", "platform"), List.of("color"), true));

        // when
        CommandParseResponse actual = commandExecutionService.handleClarification(commandId, request);

        // then
        assertThat(actual.commandId()).isEqualTo(commandId);
        assertThat(actual.needsClarification()).isTrue();
        // updateToPreSearchClarification이 호출되었는지 확인 (4-param overload, platform=null)
        verify(commandSessionService).updateToPreSearchClarification(
                eq(commandId),
                eq(List.of("size", "platform")),
                anyString(),
                isNull()
        );
        // 발행은 일어나지 않아야 함
        verify(priceRequestService, never()).publishParsedCommandRequest(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("handleClarification - 재파싱 결과 보완 불필요 → SEARCHING 전환 + commandId 재사용 발행 (free-text)")
    void handleClarification_noLongerNeedsClarification_publishesAndUpdatesToSearching() {
        // given
        String commandId = "test-uuid-ready";
        CommandClarificationRequest request = new CommandClarificationRequest("검은색 270", null);

        // 세션 엔티티 mock
        CommandSession session = CommandSession.builder()
                .commandId(commandId)
                .userId(1L)
                .originalCommand("나이키 에어맥스")
                .status(CommandSessionStatus.PRE_SEARCH_CLARIFICATION)
                .build();

        given(commandSessionService.getSessionEntityByCommandId(commandId)).willReturn(session);

        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.SHOES,
                "나이키 에어맥스",
                "나이키",
                null,
                null,
                "검은색",
                "270",
                null,
                150000,
                null,
                "KRW"
        );

        // 재파싱 결과: 보완 불필요
        CommandParseResponse reparseResponse = new CommandParseResponse(
                CommandIntent.PRICE_CHECK,
                parsedCommand,
                List.of(),       // 누락 없음
                List.of(),       // 모호 없음
                false,
                0.92,
                null             // 아직 commandId 없음 (세션에서 주입됨)
        );

        when(commandParsingService.parse(any(CommandParseRequest.class))).thenReturn(reparseResponse);
        // applyStructuredAnswers(answers=null) → no-op → 동일한 parsedCommand로 재평가
        when(commandFieldEvaluationService.evaluate(eq(CommandIntent.PRICE_CHECK), any(ParsedCommand.class)))
                .thenReturn(new FieldEvaluationResult(List.of(), List.of(), false));

        // when
        CommandParseResponse actual = commandExecutionService.handleClarification(commandId, request);

        // then
        assertThat(actual.commandId()).isEqualTo(commandId);
        assertThat(actual.needsClarification()).isFalse();
        // 세션 originalCommand가 병합되었는지 확인
        assertThat(session.getOriginalCommand()).isEqualTo("나이키 에어맥스 검은색 270");
        // updateToSearching이 platform=null로 호출되었는지 확인 (parsedCommand.platform이 null이므로)
        verify(commandSessionService).updateToSearching(eq(commandId), isNull());
        // 가격 요청이 세션의 commandId로 발행되었는지 확인
        verify(priceRequestService).publishParsedCommandRequest(1L, "PRICE_CHECK", parsedCommand, commandId);
        // updateToPreSearchClarification은 호출되지 않아야 함
        verify(commandSessionService, never()).updateToPreSearchClarification(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("handleProductUrlSubmission - URL 목록을 발행하고 SEARCHING으로 전환한다")
    void handleProductUrlSubmission_publishesUrlRequest() {
        String commandId = "cmd-url-001";
        ProductUrlSubmitRequest request = new ProductUrlSubmitRequest(List.of(
                "https://ko.aliexpress.com/item/1005006782975346.html",
                "https://ko.aliexpress.com/item/1005010633549414.html"
        ));

        CommandSession session = CommandSession.builder()
                .commandId(commandId)
                .userId(1L)
                .originalCommand("mx master 3s 블랙 알리익스프레스에서 100000원 이하면 결제해줘")
                .status(CommandSessionStatus.PRODUCT_SELECTION_REQUIRED)
                .candidatesJson("[{\"productId\":\"1005006782975346\",\"title\":\"테스트 상품\",\"lprice\":\"139000\",\"mallName\":\"스토어\",\"productUrl\":\"https://ko.aliexpress.com/item/1005006782975346.html\",\"currency\":\"KRW\",\"platform\":\"ALIEXPRESS\",\"searchKeyword\":\"로지텍 mx master 3s black\"}]")
                .targetPrice(100000)
                .commandIntent("AUTO_PURCHASE")
                .build();

        CommandSessionResponse searchingResponse = new CommandSessionResponse(
                commandId, 1L, session.getOriginalCommand(), CommandSessionStatus.SEARCHING,
                List.of(), null, null, List.of(), List.of(), null, 100000, "AUTO_PURCHASE", null, null
        );

        given(commandSessionService.getSessionEntityByCommandId(commandId)).willReturn(session);
        given(commandSessionService.updateToSearching(eq(commandId), isNull())).willReturn(searchingResponse);
        given(commandSessionService.getByCommandId(commandId)).willReturn(searchingResponse);

        CommandSessionResponse response = commandExecutionService.handleProductUrlSubmission(commandId, request);

        assertThat(response.status()).isEqualTo(CommandSessionStatus.SEARCHING);
        verify(commandSessionService).updateToSearching(eq(commandId), isNull());
        verify(priceRequestService).publishProductUrlRequest(
                eq(1L),
                eq("AUTO_PURCHASE"),
                eq(100000),
                eq(commandId),
                eq(request.productUrls()),
                eq("로지텍 mx master 3s black"),
                eq("ALIEXPRESS")
        );
    }

    @Test
    @DisplayName("handleClarification - structured answers만 제출 → 재파싱 결과 보완 불필요 → SEARCHING 전환 + 발행")
    void handleClarification_withAnswersOnly_publishesAndUpdatesToSearching() {
        // given
        String commandId = "test-uuid-answers";
        CommandClarificationRequest request = new CommandClarificationRequest(
                null,
                Map.of("size", "270", "platform", "NAVER")
        );

        CommandSession session = CommandSession.builder()
                .commandId(commandId)
                .userId(1L)
                .originalCommand("나이키 에어맥스")
                .status(CommandSessionStatus.PRE_SEARCH_CLARIFICATION)
                .build();

        given(commandSessionService.getSessionEntityByCommandId(commandId)).willReturn(session);

        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.SHOES,
                "나이키 에어맥스",
                "나이키",
                null,
                null,
                null,
                "270",
                java.util.List.of(PlatformType.NAVER),
                null,
                null,
                "KRW"
        );

        CommandParseResponse reparseResponse = new CommandParseResponse(
                CommandIntent.PRICE_CHECK,
                parsedCommand,
                List.of(),
                List.of(),
                false,
                0.93,
                null
        );

        when(commandParsingService.parse(any(CommandParseRequest.class))).thenReturn(reparseResponse);
        // answers에 platform=NAVER 있어도 parsedCommand 이미 NAVER → 재평가 후 누락 없음
        when(commandFieldEvaluationService.evaluate(eq(CommandIntent.PRICE_CHECK), any(ParsedCommand.class)))
                .thenReturn(new FieldEvaluationResult(List.of(), List.of(), false));

        // when
        CommandParseResponse actual = commandExecutionService.handleClarification(commandId, request);

        // then
        assertThat(actual.commandId()).isEqualTo(commandId);
        assertThat(actual.needsClarification()).isFalse();
        // mergedText = "나이키 에어맥스 platform: NAVER, size: 270" (TreeMap으로 key 정렬되어 platform이 size보다 먼저)
        assertThat(session.getOriginalCommand()).isEqualTo("나이키 에어맥스 platform: NAVER, size: 270");
        // updateToSearching이 platform=NAVER로 호출되었는지 확인 (parsedCommand.platform이 NAVER이므로)
        verify(commandSessionService).updateToSearching(eq(commandId), eq("NAVER"));
        verify(priceRequestService).publishParsedCommandRequest(1L, "PRICE_CHECK", parsedCommand, commandId);
        verify(commandSessionService, never()).updateToPreSearchClarification(anyString(), anyList(), anyString());
    }

    // ── handleProductSelection (다중 선택) 테스트 ────────────────────────────────

    @Test
    @DisplayName("handleProductSelection - 유효한 다중 선택 → PRICE_VALIDATING 전환 + batch ProductSelectionEvent 발행")
    void handleProductSelection_success_publishesBatchEvent() throws Exception {
        // given
        String commandId = "test-uuid-selection-success";
        List<String> selectedProductIds = List.of("naver-1", "naver-3");
        ProductSelectionRequest request = new ProductSelectionRequest(selectedProductIds);

        List<ProductCandidateDto> candidates = List.of(
                new ProductCandidateDto("naver-1", "테스트 상품 1", "250000",
                        "테스트몰", "https://example.com/product-1", null, "KRW", "NAVER", "나이키 에어포스"),
                new ProductCandidateDto("naver-2", "테스트 상품 2", "300000",
                        "테스트몰2", "https://example.com/product-2", null, "KRW", "NAVER", "나이키 에어포스"),
                new ProductCandidateDto("naver-3", "테스트 상품 3", "200000",
                        "테스트몰3", "https://example.com/product-3", null, "KRW", "NAVER", "나이키 에어포스")
        );
        String candidatesJson = new ObjectMapper().writeValueAsString(candidates);

        CommandSession session = CommandSession.builder()
                .commandId(commandId)
                .userId(1L)
                .originalCommand("테스트 상품 검색")
                .status(CommandSessionStatus.PRODUCT_SELECTION_REQUIRED)
                .targetPrice(200000)
                .commandIntent("AUTO_PURCHASE")
                .candidatesJson(candidatesJson)
                .build();

        given(commandSessionService.getSessionEntityByCommandId(commandId)).willReturn(session);

        CommandSessionResponse validatingResponse = new CommandSessionResponse(
                commandId, 1L, "테스트 상품 검색", CommandSessionStatus.PRICE_VALIDATING,
                null, null, null, List.of(), selectedProductIds, null, 200000, "AUTO_PURCHASE",
                null, null
        );
        given(commandSessionService.updateToPriceValidating(commandId, selectedProductIds))
                .willReturn(validatingResponse);
        given(commandSessionService.getByCommandId(commandId))
                .willReturn(validatingResponse);

        // when
        CommandSessionResponse actual = commandExecutionService.handleProductSelection(commandId, request);

        // then
        assertThat(actual.commandId()).isEqualTo(commandId);
        assertThat(actual.status()).isEqualTo(CommandSessionStatus.PRICE_VALIDATING);
        assertThat(actual.selectedProductIds()).containsExactly("naver-1", "naver-3");

        // updateToPriceValidating 호출 확인
        verify(commandSessionService).updateToPriceValidating(commandId, selectedProductIds);

        // ProductSelectionEventPublisher가 batch 이벤트로 호출되었는지 확인
        ArgumentCaptor<ProductSelectionEvent> captor = ArgumentCaptor.forClass(ProductSelectionEvent.class);
        verify(productSelectionEventPublisher).publish(captor.capture());

        ProductSelectionEvent publishedEvent = captor.getValue();
        assertThat(publishedEvent.eventType()).isEqualTo("PRODUCTS_SELECTED");
        assertThat(publishedEvent.producer()).isEqualTo("command-service");
        assertThat(publishedEvent.payload().commandId()).isEqualTo(commandId);
        assertThat(publishedEvent.payload().userId()).isEqualTo(1L);
        assertThat(publishedEvent.payload().targetPrice()).isEqualTo(200000);
        assertThat(publishedEvent.payload().intent()).isEqualTo("AUTO_PURCHASE");
        assertThat(publishedEvent.payload().forceResubscribe()).isFalse();
        assertThat(publishedEvent.payload().selectedProducts()).hasSize(2);
        assertThat(publishedEvent.payload().selectedProducts().get(0).productId()).isEqualTo("naver-1");
        assertThat(publishedEvent.payload().selectedProducts().get(1).productId()).isEqualTo("naver-3");

        // 일반 재파싱 경로는 호출되지 않아야 함
        verify(priceRequestService, never()).publishParsedCommandRequest(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("handleProductSelection - 유효하지 않은 productId → InvalidProductSelectionException")
    void handleProductSelection_invalidProductId_throwsException() throws Exception {
        // given
        String commandId = "test-uuid-selection-invalid";
        ProductSelectionRequest request = new ProductSelectionRequest(List.of("naver-nonexistent"));

        List<ProductCandidateDto> candidates = List.of(
                new ProductCandidateDto("naver-1", "테스트 상품 1", "250000",
                        "테스트몰", "https://example.com/product-1", null, "KRW", "NAVER", "나이키 에어포스")
        );
        String candidatesJson = new ObjectMapper().writeValueAsString(candidates);

        CommandSession session = CommandSession.builder()
                .commandId(commandId)
                .userId(1L)
                .originalCommand("테스트 상품 검색")
                .status(CommandSessionStatus.PRODUCT_SELECTION_REQUIRED)
                .targetPrice(200000)
                .commandIntent("AUTO_PURCHASE")
                .candidatesJson(candidatesJson)
                .build();

        given(commandSessionService.getSessionEntityByCommandId(commandId)).willReturn(session);

        // when & then
        assertThatThrownBy(() -> commandExecutionService.handleProductSelection(commandId, request))
                .isInstanceOf(InvalidProductSelectionException.class)
                .hasMessageContaining("후보 목록에 존재하지 않습니다");

        // Publisher는 호출되지 않아야 함
        verify(productSelectionEventPublisher, never()).publish(any(ProductSelectionEvent.class));
        // 세션 상태는 변경되지 않아야 함 (before updateToPriceValidating)
        verify(commandSessionService, never()).updateToPriceValidating(anyString(), anyList());
    }

    @Test
    @DisplayName("handleProductSelection - RESUBSCRIBE_CONFIRMATION_REQUIRED 상태도 허용한다")
    void handleProductSelection_withResubscribeConfirmationRequiredStatus_succeeds() throws Exception {
        // given
        String commandId = "test-uuid-resubscribe-status";
        List<String> selectedProductIds = List.of("naver-1");
        ProductSelectionRequest request = new ProductSelectionRequest(selectedProductIds);

        List<ProductCandidateDto> candidates = List.of(
                new ProductCandidateDto("naver-1", "테스트 상품 1", "250000",
                        "테스트몰", "https://example.com/product-1", null, "KRW", "NAVER", "나이키 에어포스")
        );
        String candidatesJson = new ObjectMapper().writeValueAsString(candidates);

        CommandSession session = CommandSession.builder()
                .commandId(commandId)
                .userId(1L)
                .originalCommand("테스트 상품 검색")
                .status(CommandSessionStatus.RESUBSCRIBE_CONFIRMATION_REQUIRED)
                .targetPrice(200000)
                .commandIntent("AUTO_PURCHASE")
                .candidatesJson(candidatesJson)
                .build();

        given(commandSessionService.getSessionEntityByCommandId(commandId)).willReturn(session);

        CommandSessionResponse validatingResponse = new CommandSessionResponse(
                commandId, 1L, "테스트 상품 검색", CommandSessionStatus.PRICE_VALIDATING,
                null, null, null, List.of(), selectedProductIds, null, 200000, "AUTO_PURCHASE",
                null, null
        );
        given(commandSessionService.updateToPriceValidating(commandId, selectedProductIds))
                .willReturn(validatingResponse);
        given(commandSessionService.getByCommandId(commandId))
                .willReturn(validatingResponse);

        // when
        CommandSessionResponse actual = commandExecutionService.handleProductSelection(commandId, request);

        // then
        assertThat(actual.status()).isEqualTo(CommandSessionStatus.PRICE_VALIDATING);
        verify(commandSessionService).updateToPriceValidating(commandId, selectedProductIds);

        ArgumentCaptor<ProductSelectionEvent> captor = ArgumentCaptor.forClass(ProductSelectionEvent.class);
        verify(productSelectionEventPublisher).publish(captor.capture());
        assertThat(captor.getValue().payload().forceResubscribe()).isFalse();
    }

    @Test
    @DisplayName("handleProductSelection - forceResubscribe가 true면 이벤트 payload에도 true로 전달된다")
    void handleProductSelection_withForceResubscribeTrue_passesToEventPayload() throws Exception {
        // given
        String commandId = "test-uuid-force-resubscribe";
        List<String> selectedProductIds = List.of("naver-1");
        ProductSelectionRequest request = new ProductSelectionRequest(selectedProductIds, true, null);

        List<ProductCandidateDto> candidates = List.of(
                new ProductCandidateDto("naver-1", "테스트 상품 1", "250000",
                        "테스트몰", "https://example.com/product-1", null, "KRW", "NAVER", "나이키 에어포스")
        );
        String candidatesJson = new ObjectMapper().writeValueAsString(candidates);

        CommandSession session = CommandSession.builder()
                .commandId(commandId)
                .userId(1L)
                .originalCommand("테스트 상품 검색")
                .status(CommandSessionStatus.RESUBSCRIBE_CONFIRMATION_REQUIRED)
                .targetPrice(200000)
                .commandIntent("AUTO_PURCHASE")
                .candidatesJson(candidatesJson)
                .build();

        given(commandSessionService.getSessionEntityByCommandId(commandId)).willReturn(session);

        CommandSessionResponse validatingResponse = new CommandSessionResponse(
                commandId, 1L, "테스트 상품 검색", CommandSessionStatus.PRICE_VALIDATING,
                null, null, null, List.of(), selectedProductIds, null, 200000, "AUTO_PURCHASE",
                null, null
        );
        given(commandSessionService.updateToPriceValidating(commandId, selectedProductIds))
                .willReturn(validatingResponse);
        given(commandSessionService.getByCommandId(commandId))
                .willReturn(validatingResponse);

        // when
        commandExecutionService.handleProductSelection(commandId, request);

        // then
        ArgumentCaptor<ProductSelectionEvent> captor = ArgumentCaptor.forClass(ProductSelectionEvent.class);
        verify(productSelectionEventPublisher).publish(captor.capture());
        assertThat(captor.getValue().payload().forceResubscribe()).isTrue();
    }

    @Test
    @DisplayName("handleProductSelection - PRODUCT_SELECTION_REQUIRED가 아닌 상태 → InvalidProductSelectionException")
    void handleProductSelection_wrongStatus_throwsException() {
        // given
        String commandId = "test-uuid-selection-wrong-status";
        ProductSelectionRequest request = new ProductSelectionRequest(List.of("naver-1"));

        CommandSession session = CommandSession.builder()
                .commandId(commandId)
                .userId(1L)
                .originalCommand("테스트 상품 검색")
                .status(CommandSessionStatus.PRE_SEARCH_CLARIFICATION)  // 잘못된 상태
                .build();

        given(commandSessionService.getSessionEntityByCommandId(commandId)).willReturn(session);

        // when & then
        assertThatThrownBy(() -> commandExecutionService.handleProductSelection(commandId, request))
                .isInstanceOf(InvalidProductSelectionException.class)
                .hasMessageContaining("PRODUCT_SELECTION_REQUIRED 또는 RESUBSCRIBE_CONFIRMATION_REQUIRED 상태에서만 사용할 수 있습니다");

        verify(productSelectionEventPublisher, never()).publish(any(ProductSelectionEvent.class));
        verify(commandSessionService, never()).updateToPriceValidating(anyString(), anyList());
    }

    @Test
    @DisplayName("handleClarification - PRE_SEARCH_CLARIFICATION가 아닌 상태 → InvalidProductSelectionException")
    void handleClarification_wrongStatus_throwsException() {
        // given
        String commandId = "test-uuid-clarify-wrong-status";
        CommandClarificationRequest request = new CommandClarificationRequest("검은색", null);

        CommandSession session = CommandSession.builder()
                .commandId(commandId)
                .userId(1L)
                .originalCommand("나이키 에어맥스")
                .status(CommandSessionStatus.SEARCHING)  // handleClarification은 PRE_SEARCH_CLARIFICATION만 허용
                .build();

        given(commandSessionService.getSessionEntityByCommandId(commandId)).willReturn(session);

        // when & then
        assertThatThrownBy(() -> commandExecutionService.handleClarification(commandId, request))
                .isInstanceOf(InvalidProductSelectionException.class)
                .hasMessageContaining("clarifications API는 PRE_SEARCH_CLARIFICATION 상태에서만 사용할 수 있습니다");

        verify(priceRequestService, never()).publishParsedCommandRequest(anyLong(), anyString(), any(), anyString());
    }
}
