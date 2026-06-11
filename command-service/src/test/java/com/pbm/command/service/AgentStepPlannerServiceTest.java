package com.pbm.command.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.client.AiDomPlannerClient;
import com.pbm.command.client.AiVisionPlannerClient;
import com.pbm.command.client.dto.DomPlannerInstructionPayload;
import com.pbm.command.client.dto.VisionPlannerInstructionPayload;
import com.pbm.command.domain.BrowserActionType;
import com.pbm.command.domain.CommandSession;
import com.pbm.command.domain.ExternalStoreVisionStage;
import com.pbm.command.domain.CommandSessionStatus;
import com.pbm.command.domain.ActionErrorCode;
import com.pbm.command.domain.ActionExecutionStatus;
import com.pbm.command.domain.PlatformConfig;
import com.pbm.command.domain.PlatformType;
import com.pbm.command.dto.request.AgentRunActionResultRequest;
import com.pbm.command.dto.request.ScreenshotArtifactRequest;
import com.pbm.command.dto.request.ToolResultRequest;
import com.pbm.command.dto.request.InteractiveElementRequest;
import com.pbm.command.dto.request.PageSnapshotRequest;
import com.pbm.command.dto.response.ActionInstructionResponse;
import com.pbm.command.dto.response.ProductCandidateResponse;
import com.pbm.command.dto.response.SelectionValidationResultResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * AgentStepPlannerService 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class AgentStepPlannerServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private AiDomPlannerClient aiDomPlannerClient;

    @Mock
    private AiVisionPlannerClient aiVisionPlannerClient;

    @Mock
    private PlatformConfigService platformConfigService;

    private AgentStepPlannerService agentStepPlannerService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        PlatformConfig aliExpressConfig = new PlatformConfig(
                PlatformType.ALIEXPRESS, "알리익스프레스", "aliexpress.com",
                "https://www.aliexpress.com/wholesale?SearchText={keyword}",
                true, false, "https://www.aliexpress.com/"
        );
        lenient().when(platformConfigService.findByPlatform(PlatformType.ALIEXPRESS)).thenReturn(Optional.of(aliExpressConfig));

        agentStepPlannerService = new AgentStepPlannerService(aiDomPlannerClient, aiVisionPlannerClient, platformConfigService);
    }

    @Test
    @DisplayName("AliExpress 도메인이 아니면 NAVIGATE 액션을 반환한다")
    void planNextAction_returnsNavigateWhenOutsideAliExpress() {
        CommandSession commandSession = CommandSession.createSearching(1L, "무선 이어폰 검색");
        lenient().when(aiDomPlannerClient.plan(any(), any(), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("fallback"));
        PageSnapshotRequest snapshot = new PageSnapshotRequest(
                "https://www.google.com",
                "Google",
                "검색 페이지",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                LocalDateTime.now()
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-1", 0, commandSession, snapshot);

        assertThat(response.action()).isEqualTo(BrowserActionType.NAVIGATE);
        assertThat(response.value()).contains("aliexpress.com/wholesale");
    }

    @Test
    @DisplayName("구매 버튼이 보이면 CLICK 액션을 반환한다")
    void planNextAction_returnsClickWhenPurchaseButtonExists() {
        CommandSession commandSession = CommandSession.createSearching(1L, "무선 이어폰 검색");
        given(aiDomPlannerClient.plan(any(), any(), any(), any(), any(), any(), any())).willThrow(new RuntimeException("fallback"));
        PageSnapshotRequest snapshot = new PageSnapshotRequest(
                "https://www.aliexpress.com/wholesale?SearchText=test",
                "검색결과",
                "지금 구매 버튼 있음",
                List.of(new InteractiveElementRequest("node-1", "button", "지금 구매", null, null, true, false)),
                List.of(),
                List.of(),
                List.of(),
                "",
                LocalDateTime.now()
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-1", 1, commandSession, snapshot);

        assertThat(response.action()).isEqualTo(BrowserActionType.CLICK);
        assertThat(response.target().nodeId()).isEqualTo("node-1");
    }

    @Test
    @DisplayName("PRE_SEARCH_CLARIFICATION 상태면 웹앱 보완 대기 액션을 반환한다")
    void planNextAction_returnsAwaitApprovalForClarification() {
        CommandSession commandSession = CommandSession.createPreSearchClarification(1L, "무선 이어폰", null, "색상 정보가 필요합니다.");

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-1", 0, commandSession, null);

        assertThat(response.action()).isEqualTo(BrowserActionType.AWAIT_APPROVAL);
        assertThat(response.approvalContext().summaryText()).contains("색상 정보가 필요합니다");
    }

    @Test
    @DisplayName("PRODUCT_SELECTION_REQUIRED 상태면 후보 선택 대기 액션을 반환한다")
    void planNextAction_returnsAwaitApprovalForProductSelection() {
        CommandSession commandSession = CommandSession.createSearching(1L, "무선 이어폰 검색");
        commandSession.toProductSelectionRequired(
                null,
                "후보 상품을 선택해주세요.",
                null,
                toCandidatesJson(List.of(
                        new ProductCandidateResponse("p1", "이어폰 A", "10000", "AliExpress", "https://www.aliexpress.com/item/1.html", null, "KRW", "ALIEXPRESS", "이어폰"),
                        new ProductCandidateResponse("p2", "이어폰 B", "12000", "AliExpress", "https://www.aliexpress.com/item/2.html", null, "KRW", "ALIEXPRESS", "이어폰")
                )),
                10000,
                "AUTO_PURCHASE"
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-1", 0, commandSession, null);

        assertThat(response.action()).isEqualTo(BrowserActionType.AWAIT_APPROVAL);
        assertThat(response.approvalContext().summaryText()).contains("이어폰 A");
    }

    @Test
    @DisplayName("PRICE_VALIDATING 상태면 결과를 기다리는 WAIT 액션을 반환한다")
    void planNextAction_returnsWaitForPriceValidating() {
        CommandSession commandSession = CommandSession.createSearching(1L, "무선 이어폰 검색");
        commandSession.toProductSelectionRequired(null, null, null, toCandidatesJson(List.of(
                new ProductCandidateResponse("p1", "이어폰 A", "10000", "AliExpress", "https://www.aliexpress.com/item/1.html", null, "KRW", "ALIEXPRESS", "이어폰")
        )), 10000, "AUTO_PURCHASE");
        commandSession.toPriceValidating("[\"p1\"]");

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-1", 1, commandSession, null);

        assertThat(response.action()).isEqualTo(BrowserActionType.WAIT);
    }

    @Test
    @DisplayName("검증 결과에 triggeredProducts가 있으면 해당 상품 URL로 NAVIGATE 한다")
    void planNextAction_navigatesToTriggeredProduct() {
        CommandSession commandSession = CommandSession.createSearching(1L, "무선 이어폰 검색");
        commandSession.toProductSelectionRequired(null, null, null, toCandidatesJson(List.of()), 10000, "AUTO_PURCHASE");
        commandSession.completeValidation(
                CommandSessionStatus.SEARCHING,
                toValidationResultJson(new SelectionValidationResultResponse(
                        List.of(new ProductCandidateResponse("p1", "이어폰 A", "10000", "AliExpress", "https://www.aliexpress.com/item/99.html", null, "KRW", "ALIEXPRESS", "이어폰")),
                        List.of(),
                        null,
                        "triggered",
                        false,
                        List.of(),
                        null
                ))
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-1", 2, commandSession, null);

        assertThat(response.action()).isEqualTo(BrowserActionType.NAVIGATE);
        assertThat(response.value()).isEqualTo("https://www.aliexpress.com/item/99.html");
    }

    @Test
    @DisplayName("BROWSER_PURCHASE_IN_PROGRESS 상태면 타겟 상품 URL로 NAVIGATE 한다 (non-completed)")
    void planNextAction_navigatesToTriggeredProductForBrowserPurchaseInProgress() {
        CommandSession commandSession = CommandSession.createSearching(1L, "무선 이어폰 구매");
        commandSession.toProductSelectionRequired(null, null, null, toCandidatesJson(List.of()), 10000, "AUTO_PURCHASE");
        commandSession.toPriceValidating("[\"p1\"]");
        commandSession.completeValidation(
                CommandSessionStatus.BROWSER_PURCHASE_IN_PROGRESS,
                toValidationResultJson(new SelectionValidationResultResponse(
                        List.of(new ProductCandidateResponse("p1", "이어폰 A", "10000", "AliExpress", "https://www.aliexpress.com/item/99.html", null, "KRW", "ALIEXPRESS", "이어폰")),
                        List.of(),
                        null,
                        "triggered",
                        false,
                        List.of(),
                        null
                ))
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-1", 2, commandSession, null);

        assertThat(response.action()).isEqualTo(BrowserActionType.NAVIGATE);
        assertThat(response.value()).isEqualTo("https://www.aliexpress.com/item/99.html");
    }

    @Test
    @DisplayName("MONITORING_STARTED 상태면 브라우저 제어를 완료 처리한다 (completed)")
    void planNextAction_completesForMonitoringStarted() {
        CommandSession commandSession = CommandSession.createSearching(1L, "가격만 확인해줘");
        commandSession.toProductSelectionRequired(null, null, null, toCandidatesJson(List.of()), 10000, "PRICE_CHECK");
        commandSession.toPriceValidating("[\"p1\"]");
        commandSession.completeValidation(
                CommandSessionStatus.MONITORING_STARTED,
                toValidationResultJson(new SelectionValidationResultResponse(
                        List.of(),
                        List.of(),
                        null,
                        "monitoring started",
                        false,
                        List.of(),
                        null
                ))
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-1", 0, commandSession, null);

        assertThat(response.action()).isEqualTo(BrowserActionType.COMPLETE);
    }

    @Test
    @DisplayName("AUTO_PURCHASE가 아닌 intent면 브라우저 제어를 완료 처리한다")
    void planNextAction_completesWhenNotAutoPurchaseIntent() {
        CommandSession commandSession = CommandSession.createSearching(1L, "가격만 확인해줘");
        commandSession.toProductSelectionRequired(null, null, null, null, 10000, "PRICE_CHECK");
        commandSession.toSearching();

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-1", 0, commandSession, null);

        assertThat(response.action()).isEqualTo(BrowserActionType.COMPLETE);
    }

    @Test
    @DisplayName("optionGroups에 명령문과 일치하는 옵션이 있으면 SELECT 액션을 반환한다 (비PRODUCT_DETAIL)")
    void planNextAction_returnsSelectForMatchingOptionGroup() {
        CommandSession commandSession = CommandSession.createSearching(1L, "검정색 270 사이즈 운동화 구매");
        given(aiDomPlannerClient.plan(any(), any(), any(), any(), any(), any(), any())).willThrow(new RuntimeException("fallback"));
        // PRODUCT_DETAIL 페이지는 Vision AI 전용 → rule-based SELECT는 비PRODUCT_DETAIL 페이지에서 동작
        PageSnapshotRequest snapshot = new PageSnapshotRequest(
                "https://www.aliexpress.com/wholesale?SearchText=운동화",
                "검색결과",
                "색상과 사이즈 선택 가능",
                List.of(),
                List.of(
                        new com.pbm.command.dto.request.OptionGroupRequest("색상", "select-color", "#color", List.of("빨강", "검정색", "파랑"), null),
                        new com.pbm.command.dto.request.OptionGroupRequest("사이즈", "select-size", "#size", List.of("260", "270", "280"), null)
                ),
                List.of(),
                List.of(),
                "",
                LocalDateTime.now()
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-1", 0, commandSession, snapshot);

        assertThat(response.action()).isEqualTo(BrowserActionType.SELECT);
        assertThat(response.target().nodeId()).isEqualTo("select-color");
        assertThat(response.value()).isEqualTo("검정색");
    }

    @Test
    @DisplayName("AI planner가 유효한 target을 반환하면 rule-based보다 우선 적용한다 (비PRODUCT_DETAIL)")
    void planNextAction_prefersAiPlannerInstruction() {
        CommandSession commandSession = CommandSession.createSearching(1L, "무선 이어폰 검색");
        // PRODUCT_DETAIL은 Vision AI 전용 → DOM AI 우선순위 테스트는 비PRODUCT_DETAIL 페이지로 수행
        PageSnapshotRequest snapshot = new PageSnapshotRequest(
                "https://www.aliexpress.com/wholesale?SearchText=이어폰",
                "검색결과",
                "구매 버튼 있음",
                List.of(new InteractiveElementRequest("node-1", "button", "지금 구매", null, null, true, false)),
                List.of(),
                List.of(),
                List.of(),
                "",
                LocalDateTime.now()
        );
        given(aiDomPlannerClient.plan(any(), any(), any(), any(), any(), any(), any())).willReturn(
                new DomPlannerInstructionPayload(
                        "CLICK",
                        new DomPlannerInstructionPayload.PlannerTargetPayload("node-1", "button", "지금 구매", null),
                        null,
                        0.91,
                        "AI planner가 지금 구매 버튼을 선택함"
                )
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-1", 0, commandSession, snapshot);

        assertThat(response.action()).isEqualTo(BrowserActionType.CLICK);
        assertThat(response.target().nodeId()).isEqualTo("node-1");
    }

    @Test
    @DisplayName("AI planner confidence가 너무 낮으면 rule-based 결과로 fallback 한다")
    void planNextAction_fallsBackWhenAiConfidenceTooLow() {
        CommandSession commandSession = CommandSession.createSearching(1L, "무선 이어폰 검색");
        PageSnapshotRequest snapshot = new PageSnapshotRequest(
                "https://www.aliexpress.com/wholesale?SearchText=test",
                "검색결과",
                "구매 버튼 있음",
                List.of(new InteractiveElementRequest("node-1", "button", "지금 구매", null, null, true, false)),
                List.of(),
                List.of(),
                List.of(),
                "",
                LocalDateTime.now()
        );
        given(aiDomPlannerClient.plan(any(), any(), any(), any(), any(), any(), any())).willReturn(
                new DomPlannerInstructionPayload(
                        "WAIT",
                        null,
                        null,
                        0.21,
                        "confidence 낮음"
                )
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-1", 0, commandSession, snapshot);

        assertThat(response.action()).isEqualTo(BrowserActionType.CLICK);
        assertThat(response.target().nodeId()).isEqualTo("node-1");
    }

    @Test
    @DisplayName("AI planner가 SCROLL을 반환하면 scroll instruction을 생성한다 (비PRODUCT_DETAIL)")
    void planNextAction_returnsScrollWhenAiPlannerRequestsIt() {
        CommandSession commandSession = CommandSession.createSearching(1L, "무선 이어폰 검색");
        // PRODUCT_DETAIL은 Vision AI 전용 → DOM AI SCROLL 테스트는 비PRODUCT_DETAIL 페이지로 수행
        PageSnapshotRequest snapshot = new PageSnapshotRequest(
                "https://www.aliexpress.com/wholesale?SearchText=이어폰",
                "검색결과",
                "버튼이 아직 안 보임",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                LocalDateTime.now()
        );
        given(aiDomPlannerClient.plan(any(), any(), any(), any(), any(), any(), any())).willReturn(
                new DomPlannerInstructionPayload(
                        "SCROLL",
                        null,
                        "600",
                        0.83,
                        "하단 탐색 필요"
                )
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-1", 0, commandSession, snapshot);

        assertThat(response.action()).isEqualTo(BrowserActionType.SCROLL);
        assertThat(response.value()).isEqualTo("600");
    }

    @Test
    @DisplayName("PRODUCT_DETAIL 페이지에서 이전 캡처 스크린샷이 있으면 vision planner로 옵션 토글 CLICK을 반환한다")
    void planNextAction_usesVisionFallbackWhenScreenshotExists() {
        CommandSession commandSession = CommandSession.createSearching(1L, "무선 이어폰 구매");
        PageSnapshotRequest snapshot = new PageSnapshotRequest(
                "https://www.aliexpress.com/item/1234567890.html",
                "상품 상세",
                "구매 버튼 탐색 실패",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                LocalDateTime.now()
        );
        AgentRunActionResultRequest previousActionResult = new AgentRunActionResultRequest(
                "run-1",
                0,
                "act-0",
                BrowserActionType.CLICK,
                ActionExecutionStatus.FAILURE,
                ActionErrorCode.ELEMENT_NOT_FOUND,
                "버튼 탐색 실패",
                new ToolResultRequest(
                        "CAPTURE_VISIBLE_TAB",
                        true,
                        new ScreenshotArtifactRequest("data:image/png;base64,ZmFrZQ==", "image/png", 8),
                        null
                ),
                snapshot,
                LocalDateTime.now()
        );
        // optionPresent=true 로 설정 → resolveExternalOptionPresenceInstruction이 visionClick 반환
        given(aiVisionPlannerClient.analyze(any(), any(), any(), any(), any(), any(), any(), any())).willReturn(
                new VisionPlannerInstructionPayload("CLICK", 0.45, 0.82, "지금 구매", true, List.of(), 0.88, "vision fallback 성공")
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-1", 0, commandSession, snapshot, previousActionResult);

        assertThat(response.action()).isEqualTo(BrowserActionType.CLICK);
        assertThat(response.target().viewportX()).isEqualTo(0.45);
        assertThat(response.target().viewportY()).isEqualTo(0.82);
    }

    @Test
    @DisplayName("OPTION_PRESENCE에서 옵션 미발견 + 스크롤 0회 → SCROLL 반환 (최대 2회 재확인)")
    void planNextAction_optionPresenceFalse_scrollsBeforePurchase() {
        CommandSession commandSession = CommandSession.createSearching(1L, "무선 이어폰 구매");
        PageSnapshotRequest snapshot = new PageSnapshotRequest(
                "https://www.coupang.com/vp/products/123456",
                "상품 상세",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                LocalDateTime.now()
        );
        AgentRunActionResultRequest previousActionResult = new AgentRunActionResultRequest(
                "run-1", 1, "act-1", BrowserActionType.CLICK,
                ActionExecutionStatus.FAILURE, ActionErrorCode.ELEMENT_NOT_FOUND, "버튼 탐색 실패",
                new ToolResultRequest("CAPTURE_VISIBLE_TAB", true,
                        new ScreenshotArtifactRequest("data:image/png;base64,ZmFrZQ==", "image/png", 8), null),
                snapshot, LocalDateTime.now()
        );
        // optionPresent=false → 스크롤 횟수 0이므로 SCROLL 반환
        given(aiVisionPlannerClient.analyze(any(), any(), any(), any(), any(), any(), any(), any())).willReturn(
                new VisionPlannerInstructionPayload("CLICK", null, null, null, false, List.of(), 0.90, "옵션 없음")
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction(
                "run-1", 1, commandSession, snapshot, previousActionResult, null, null,
                ExternalStoreVisionStage.OPTION_PRESENCE, 0);

        assertThat(response.action()).isEqualTo(BrowserActionType.SCROLL);
    }

    @Test
    @DisplayName("OPTION_PRESENCE에서 옵션 미발견 + 스크롤 2회 완료 → CAPTURE_VISIBLE_TAB(PURCHASE) 반환")
    void planNextAction_optionPresenceFalse_afterMaxScroll_proceedsToPurchase() {
        CommandSession commandSession = CommandSession.createSearching(1L, "무선 이어폰 구매");
        PageSnapshotRequest snapshot = new PageSnapshotRequest(
                "https://www.coupang.com/vp/products/123456",
                "상품 상세",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                LocalDateTime.now()
        );
        AgentRunActionResultRequest previousActionResult = new AgentRunActionResultRequest(
                "run-1", 3, "act-3", BrowserActionType.CLICK,
                ActionExecutionStatus.FAILURE, ActionErrorCode.ELEMENT_NOT_FOUND, "버튼 탐색 실패",
                new ToolResultRequest("CAPTURE_VISIBLE_TAB", true,
                        new ScreenshotArtifactRequest("data:image/png;base64,ZmFrZQ==", "image/png", 8), null),
                snapshot, LocalDateTime.now()
        );
        // optionPresent=false → 스크롤 2회 완료 → PURCHASE 단계로 전환
        given(aiVisionPlannerClient.analyze(any(), any(), any(), any(), any(), any(), any(), any())).willReturn(
                new VisionPlannerInstructionPayload("CLICK", null, null, null, false, List.of(), 0.90, "옵션 없음")
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction(
                "run-1", 3, commandSession, snapshot, previousActionResult, null, null,
                ExternalStoreVisionStage.OPTION_PRESENCE, 2);

        assertThat(response.action()).isEqualTo(BrowserActionType.USE_TOOL);
        assertThat(response.toolRequest().name()).isEqualTo("CAPTURE_VISIBLE_TAB");
    }

    private String toCandidatesJson(List<ProductCandidateResponse> candidates) {
        try {
            return OBJECT_MAPPER.writeValueAsString(candidates);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private String toValidationResultJson(SelectionValidationResultResponse validationResult) {
        try {
            return OBJECT_MAPPER.writeValueAsString(validationResult);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 결제 페이지 감지 테스트
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("네이버 결제 페이지(ordersheet) URL이면 COMPLETE를 반환한다")
    void planNextAction_naverCheckoutPage_returnsComplete() {
        CommandSession commandSession = CommandSession.createSearching(1L, "아이폰 16 프로 구매해줘");
        PageSnapshotRequest snapshot = new PageSnapshotRequest(
                "https://orders.pay.naver.com/ordersheet/seller/a1a75329-4969?backUrl=https://brand.naver.com",
                "네이버 결제",
                "결제하기 주문상품 아이폰 16 프로",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                LocalDateTime.now()
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-checkout-1", 5, commandSession, snapshot);

        assertThat(response.action()).isEqualTo(BrowserActionType.COMPLETE);
    }

    @Test
    @DisplayName("알리익스프레스 결제 페이지(trade/confirm) URL이면 COMPLETE를 반환한다")
    void planNextAction_aliExpressCheckoutPage_returnsComplete() {
        CommandSession commandSession = CommandSession.createSearching(1L, "무선 이어폰 구매해줘");
        PageSnapshotRequest snapshot = new PageSnapshotRequest(
                "https://www.aliexpress.com/p/trade/confirm.html?objectId=1005005073493513&from=aliexpress",
                "AliExpress - Confirm Order",
                "Place Order Total Amount",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                LocalDateTime.now()
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-checkout-2", 7, commandSession, snapshot);

        assertThat(response.action()).isEqualTo(BrowserActionType.COMPLETE);
    }

    @Test
    @DisplayName("일반 상품 페이지 URL이면 COMPLETE를 반환하지 않는다")
    void planNextAction_normalProductPage_doesNotReturnComplete() {
        CommandSession commandSession = CommandSession.createSearching(1L, "무선 이어폰 구매해줘");
        lenient().when(aiDomPlannerClient.plan(any(), any(), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("fallback"));
        PageSnapshotRequest snapshot = new PageSnapshotRequest(
                "https://www.aliexpress.com/item/1005005073493513.html",
                "AliExpress - Product Detail",
                "무선 이어폰 상품 상세",
                List.of(new InteractiveElementRequest("node-1", "button", "구매하기", null, null, true, false)),
                List.of(),
                List.of(),
                List.of(),
                "",
                LocalDateTime.now()
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-normal-1", 3, commandSession, snapshot);

        // 일반 상품 페이지에서는 COMPLETE가 아닌 CLICK 등의 액션이 반환되어야 한다
        assertThat(response.action()).isNotEqualTo(BrowserActionType.COMPLETE);
    }

    @Test
    @DisplayName("쿠팡 vp 상품 URL은 메인페이지로 회귀하지 않고 외부 스토어 캡처 플로우를 시작한다")
    void planNextAction_coupangVpProduct_startsExternalCaptureFlow() {
        CommandSession commandSession = CommandSession.createSearching(1L, "갤럭시 버즈 구매");
        PageSnapshotRequest snapshot = new PageSnapshotRequest(
                "https://www.coupang.com/vp/products/9309807813?itemId=27585107825&vendorItemId=94548723294&redirect=landing",
                "쿠팡! 갤럭시 버즈",
                "상품 상세 옵션 구매하기 장바구니",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                LocalDateTime.now()
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-coupang-1", 0, commandSession, snapshot);

        assertThat(response.action()).isEqualTo(BrowserActionType.USE_TOOL);
        assertThat(response.toolRequest().name()).isEqualTo("CAPTURE_VISIBLE_TAB");
    }

    @Test
    @DisplayName("쿠팡 모바일 상품 URL도 PRODUCT_DETAIL로 보고 메인페이지 fallback을 막는다")
    void planNextAction_coupangMobileProduct_startsExternalCaptureFlow() {
        CommandSession commandSession = CommandSession.createSearching(1L, "갤럭시 버즈 구매");
        PageSnapshotRequest snapshot = new PageSnapshotRequest(
                "https://m.coupang.com/vm/products/9309807813?itemId=27585107825&vendorItemId=94548723294",
                "쿠팡 모바일 상품",
                "옵션 선택 구매하기",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                LocalDateTime.now()
        );

        ActionInstructionResponse response = agentStepPlannerService.planNextAction("run-coupang-2", 0, commandSession, snapshot);

        assertThat(response.action()).isEqualTo(BrowserActionType.USE_TOOL);
        assertThat(response.toolRequest().name()).isEqualTo("CAPTURE_VISIBLE_TAB");
    }
}
