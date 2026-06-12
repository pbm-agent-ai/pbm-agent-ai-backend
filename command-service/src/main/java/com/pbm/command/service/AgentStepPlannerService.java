package com.pbm.command.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.client.AiDomPlannerClient;
import com.pbm.command.client.AiVisionPlannerClient;
import com.pbm.command.client.dto.DomPlannerInstructionPayload;
import com.pbm.command.client.dto.VisionPlannerInstructionPayload;
import com.pbm.command.domain.*;
import com.pbm.command.dto.request.AgentRunActionResultRequest;
import com.pbm.command.dto.request.InteractiveElementRequest;
import com.pbm.command.dto.request.OptionGroupRequest;
import com.pbm.command.dto.request.PageSnapshotRequest;
import com.pbm.command.dto.response.ActionTargetResponse;
import com.pbm.command.dto.response.ActionInstructionResponse;
import com.pbm.command.dto.response.ProductCandidateResponse;
import com.pbm.command.dto.response.SelectionValidationResultResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Agent step planner 서비스.
 *
 * 역할: 현재 run 상태와 snapshot을 바탕으로 Extension이 다음에 실행할 액션을 결정한다.
 * 동작: MVP 단계에서는 복잡한 AI planning 대신 command 상태와 화면 요소를 기준으로
 *       최소한의 deterministic rule 기반 instruction을 생성한다.
 * 연관: AgentRunService, CommandSession.
 */
@Service
public class AgentStepPlannerService {

    private static final Logger log = LoggerFactory.getLogger(AgentStepPlannerService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_NAVIGATION_TIMEOUT_MS = 15000;
    private static final int DEFAULT_WAIT_MS = 1500;
    private static final int DEFAULT_APPROVAL_TIMEOUT_MS = 600000;
    private static final double MIN_AI_CONFIDENCE = 0.55d;
    private static final double MIN_VISION_CONFIDENCE = 0.60d;
    private static final int MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS = 2;
    private static final int SEARCH_RESULTS_DOM_FAILURE_BEFORE_VISION = 2;
    private static final int SEARCH_RESULTS_MAX_VISION_MISSES = 5;
    /** 잘못된 도메인 감지 후 플랫폼 이동을 시도하는 최대 횟수 (초과 시 ABORT) */
    private static final int MAX_DOMAIN_REDIRECT_ATTEMPTS = 10;

    private final AiDomPlannerClient aiDomPlannerClient;
    private final AiVisionPlannerClient aiVisionPlannerClient;
    private final PlatformConfigService platformConfigService;

    public AgentStepPlannerService(
            AiDomPlannerClient aiDomPlannerClient,
            AiVisionPlannerClient aiVisionPlannerClient,
            PlatformConfigService platformConfigService
    ) {
        this.aiDomPlannerClient = aiDomPlannerClient;
        this.aiVisionPlannerClient = aiVisionPlannerClient;
        this.platformConfigService = platformConfigService;
    }

    /**
     * 현재 step에 대한 다음 action instruction을 생성한다.
     */
    public ActionInstructionResponse planNextAction(
            String runId,
            int stepIndex,
            CommandSession commandSession,
            PageSnapshotRequest snapshot
    ) {
        return planNextAction(runId, stepIndex, commandSession, snapshot, null, null);
    }

    public ActionInstructionResponse planNextAction(
            String runId,
            int stepIndex,
            CommandSession commandSession,
            PageSnapshotRequest snapshot,
            AgentRunActionResultRequest previousActionResult,
            String selectedOptionValue
    ) {
        return planNextAction(
                runId,
                stepIndex,
                commandSession,
                snapshot,
                previousActionResult,
                selectedOptionValue,
                0
        );
    }

    public ActionInstructionResponse planNextAction(
            String runId,
            int stepIndex,
            CommandSession commandSession,
            PageSnapshotRequest snapshot,
            AgentRunActionResultRequest previousActionResult,
            String selectedOptionValue,
            int optionPresenceScrollCount
    ) {
        return planNextAction(
                runId,
                stepIndex,
                commandSession,
                snapshot,
                previousActionResult,
                selectedOptionValue,
                null,
                ExternalStoreVisionStage.NONE,
                optionPresenceScrollCount
        );
    }

    public ActionInstructionResponse planNextAction(
            String runId,
            int stepIndex,
            CommandSession commandSession,
            PageSnapshotRequest snapshot,
            AgentRunActionResultRequest previousActionResult,
            String selectedOptionValue,
            ProductCandidateResponse explicitTargetProduct,
            ExternalStoreVisionStage externalStoreVisionStage,
            int optionPresenceScrollCount
    ) {
        ProductCandidateResponse targetProduct = explicitTargetProduct != null
                ? explicitTargetProduct
                : resolveTargetProduct(commandSession);

        String actionId = buildActionId(runId, stepIndex);
        String currentUrl = snapshot == null ? null : snapshot.currentUrl();
        // 현재 페이지 타입을 미리 판단 (rule-based fallback에서도 활용)
        PageType currentPageType = snapshot != null ? detectPageType(snapshot) : PageType.MAIN_PAGE;

        // 사용자 입력 필요
        if (commandSession.getStatus() == CommandSessionStatus.PRE_SEARCH_CLARIFICATION) {
            return ActionInstructionResponse.awaitApproval(
                    stepIndex,
                    actionId,
                    buildClarificationSummary(commandSession),
                    DEFAULT_APPROVAL_TIMEOUT_MS
            );
        }

        // 상품 선택 필요
        if (commandSession.getStatus() == CommandSessionStatus.PRODUCT_SELECTION_REQUIRED) {
            return ActionInstructionResponse.awaitApproval(
                    stepIndex,
                    actionId,
                    buildProductSelectionSummary(commandSession),
                    DEFAULT_APPROVAL_TIMEOUT_MS
            );
        }

        // 가격 검증 중 -> 잠시 대기
        if (commandSession.getStatus() == CommandSessionStatus.RESUBSCRIBE_CONFIRMATION_REQUIRED) {
            return ActionInstructionResponse.awaitApproval(
                    stepIndex,
                    actionId,
                    buildResubscribeConfirmationSummary(commandSession),
                    DEFAULT_APPROVAL_TIMEOUT_MS
            );
        }

        // 로그인 페이지
        if (currentPageType == PageType.LOGIN_PAGE){
            // "로그인"버튼 찾아서 클릭
            Optional<InteractiveElementRequest> loginBtn = snapshot.interactiveElements().stream().filter(el -> el.labelText() != null && el.labelText().contains("로그인")).findFirst();
            if (loginBtn.isPresent()){
                log.info("[AgentStepPlannerService] 로그인 페이지 감지 -> 로그인 버튼 클릭 - runId={}", runId);

                return ActionInstructionResponse.click(stepIndex, actionId, loginBtn.get());
            }
            // 버튼 못 찾으면 잠시 대기 후 재시도
            log.warn("[AgentStepPlannerService] 로그인 페이지 - 로그인 버튼 미발견 -> WAIT - runId = {}", runId);
            return ActionInstructionResponse.waitAction(stepIndex, actionId, DEFAULT_WAIT_MS, DEFAULT_NAVIGATION_TIMEOUT_MS);
        }

        // 결제/주문서 페이지 → 즉시 COMPLETE (AgentRunService.isCheckoutUrl()에서 결제 이벤트 발행)
        if (currentPageType == PageType.CHECKOUT_PAGE) {
            log.info("[AgentStepPlannerService] 결제 페이지 감지 → COMPLETE - runId={}, url={}",
                    runId, currentUrl);
            return ActionInstructionResponse.complete(stepIndex, actionId);
        }

        // 이미 완료
        if (commandSession.getStatus() == CommandSessionStatus.PRICE_VALIDATING) {
            return ActionInstructionResponse.waitAction(stepIndex, actionId, DEFAULT_WAIT_MS, DEFAULT_NAVIGATION_TIMEOUT_MS);
        }

        if (isCompletedStatus(commandSession.getStatus())) {
            return ActionInstructionResponse.complete(stepIndex, actionId);
        }

        if (!isAutoPurchaseIntent(commandSession)) {
            return ActionInstructionResponse.complete(stepIndex, actionId);
        }

        ActionInstructionResponse smartstoreOptionPresenceInstruction = buildSmartstoreOptionPresenceInstruction(
                runId,
                stepIndex,
                commandSession,
                snapshot,
                previousActionResult,
                selectedOptionValue,
                currentPageType,
                optionPresenceScrollCount
        );
        if (smartstoreOptionPresenceInstruction != null) {
            return smartstoreOptionPresenceInstruction;
        }

        if (selectedOptionValue != null && !selectedOptionValue.isBlank()) {
            ActionInstructionResponse selectedOptionInstruction = buildExplicitSmartstoreOptionInstruction(
                    runId,
                    stepIndex,
                    commandSession,
                    snapshot,
                    previousActionResult,
                    selectedOptionValue,
                    optionPresenceScrollCount
            );
            if (selectedOptionInstruction != null) {
                return selectedOptionInstruction;
            }
        }

        boolean selectedOptionAlreadyApplied = selectedOptionValue != null
                && !selectedOptionValue.isBlank()
                && isSelectedOptionAlreadyApplied(snapshot, selectedOptionValue);

        ActionInstructionResponse searchResultsVisionInstruction = buildSearchResultsVisionFallbackInstruction(
                runId,
                stepIndex,
                commandSession,
                snapshot,
                previousActionResult,
                targetProduct,
                externalStoreVisionStage,
                optionPresenceScrollCount
        );
        if (searchResultsVisionInstruction != null) {
            return searchResultsVisionInstruction;
        }

        if (!selectedOptionAlreadyApplied) {
            ActionInstructionResponse visionInstruction = buildVisionFallbackInstruction(
                    runId,
                    stepIndex,
                    commandSession,
                    snapshot,
                    previousActionResult,
                    selectedOptionValue
            );
            if (visionInstruction != null) {
                return visionInstruction;
            }
        }

        // vision이 스크린샷 기반으로 시도됐으나 실패(confidence 부족 or 오류)한 경우:
        // 같은 스냅샷으로 DOM AI를 재호출해도 결과가 동일하므로 건너뛰고 SCROLL/ABORT로 직행한다.
        boolean visionAttempted = previousActionResult != null
                && previousActionResult.toolResult() != null
                && previousActionResult.toolResult().screenshot() != null;

        if (!visionAttempted) {
            ActionInstructionResponse aiInstruction = buildAiDomInstruction(runId, stepIndex, commandSession, snapshot, targetProduct);
            if (aiInstruction != null) {
                return aiInstruction;
            }

            if (selectedOptionValue == null || selectedOptionValue.isBlank()) {
                Optional<ActionInstructionResponse> optionInstruction = buildOptionSelectionInstruction(
                        stepIndex,
                        actionId,
                        commandSession,
                        snapshot
                );
                if (optionInstruction.isPresent()) {
                    return optionInstruction.get();
                }
            }
        } else {
            log.info("[AgentStepPlannerService] Vision 시도 후 실패 → DOM AI 재시도 건너뜀, SCROLL/ABORT로 직행 - runId={}, stepIndex={}", runId, stepIndex);
        }

        // PRODUCT_DETAIL 페이지: AI가 버튼을 못 찾아도 메인 페이지로 이동하지 않는다.
        // 순서: DOM AI 실패 → Vision AI 1회 시도 → SCROLL → ... → ABORT
        if (currentPageType == PageType.PRODUCT_DETAIL) {
            if (stepIndex >= MAX_DOMAIN_REDIRECT_ATTEMPTS) {
                log.error("[AgentStepPlannerService] PRODUCT_DETAIL 탐색 {}회 초과 → ABORT - runId={}", MAX_DOMAIN_REDIRECT_ATTEMPTS, runId);
                return ActionInstructionResponse.abort(stepIndex, actionId,
                        "구매 버튼을 " + MAX_DOMAIN_REDIRECT_ATTEMPTS + "회 이상 탐색 실패 - 판매처 페이지를 확인해주세요.");
            }

            // 직전 액션이 CLICK이면 Vision AI가 이미 시도된 것이므로 SCROLL로 탐색한다.
            // (CAPTURE_VISIBLE_TAB → Vision AI → visionClick 사이클 완료 후 진입)
            BrowserActionType prevAction = previousActionResult != null ? previousActionResult.action() : null;
            ActionExecutionStatus prevStatus = previousActionResult != null ? previousActionResult.status() : null;

            boolean previousWasSuccessfulClick = prevAction == BrowserActionType.CLICK && prevStatus == ActionExecutionStatus.SUCCESS;

            log.debug("[AgentStepPlannerService] PRODUCT_DETAIL 분기 결정 - runId={}, stepIndex={}, visionAttempted={}, prevAction={}, previousWasClick={}",
                    runId, stepIndex, visionAttempted, prevAction, previousWasSuccessfulClick);

            if (!visionAttempted && !previousWasSuccessfulClick) {
                Optional<InteractiveElementRequest> purchaseButton = findPurchaseButton(snapshot);
                if (isOptionSelectionNotRequired(snapshot) && purchaseButton.isPresent()) {
                    log.info("[AgentStepPlannerService] PRODUCT_DETAIL rule-based 구매 버튼 클릭 - runId={}, stepIndex={}, nodeId={}, label={}",
                            runId, stepIndex, purchaseButton.get().nodeId(), purchaseButton.get().labelText());
                    return ActionInstructionResponse.click(stepIndex, actionId, purchaseButton.get());
                }

                // DOM AI 실패 → Vision AI fallback: 스크린샷 요청
                log.info("[AgentStepPlannerService] PRODUCT_DETAIL DOM AI 실패 → Vision AI fallback (CAPTURE_VISIBLE_TAB) - runId={}, stepIndex={}", runId, stepIndex);
                return ActionInstructionResponse.useTool(stepIndex, actionId, "CAPTURE_VISIBLE_TAB", java.util.Map.of("format", "png"));
            }

            log.info("[AgentStepPlannerService] PRODUCT_DETAIL Vision 시도 후 또는 성공한 CLICK 이후에만 SCROLL 재시도 - runId={}, prevAction={}", runId, prevAction);
            return ActionInstructionResponse.scroll(stepIndex, actionId, "500");
        }

        // 플랫폼 설정 조회 (URL 이동 전략 결정에 사용)
        PlatformConfig platformConfig = resolvePlatformConfig(commandSession);

        if (targetProduct != null && targetProduct.productUrl() != null && !targetProduct.productUrl().isBlank()) {
            if (!isSamePage(currentUrl, targetProduct.productUrl())) {
                if (platformConfig.isPreferSearchNavigation()) {
                    if (currentPageType == PageType.SEARCH_RESULTS) {
                        // 검색 결과 페이지에서 AI가 상품을 못 찾은 경우:
                        // 메인 페이지로 돌아가면 무한 루프 발생 → WAIT 후 재시도
                        // (스크롤은 ELEMENT_NOT_FOUND 시 buildVisionFallbackInstruction에서 처리)
                        log.info("[AgentStepPlannerService] 검색 결과 AI 미발견 → WAIT 후 재시도 - runId={}", runId);
                        return ActionInstructionResponse.waitAction(stepIndex, actionId, DEFAULT_WAIT_MS, DEFAULT_NAVIGATION_TIMEOUT_MS);
                    }
                    // 검색 결과가 아닌 다른 페이지에 있는 경우: 메인 페이지 경유
                    // (네이버: 상품 URL 직접 접근 금지 → 봇 차단 위험)
                    String mainPageUrl = platformConfig.buildMainPageUrl();
                    log.info("검색창 경유 전략 적용 - platform: {}, 메인 페이지: {}", platformConfig.getPlatform(), mainPageUrl);
                    return ActionInstructionResponse.navigate(
                            stepIndex,
                            actionId,
                            mainPageUrl,
                            DEFAULT_NAVIGATION_TIMEOUT_MS
                    );
                }
                // 알리익스프레스 등: 상품 URL 직접 이동
                return ActionInstructionResponse.navigate(
                        stepIndex,
                        actionId,
                        targetProduct.productUrl(),
                        DEFAULT_NAVIGATION_TIMEOUT_MS
                );
            }
        }

        // 현재 URL이 세션의 플랫폼 도메인이 아니면 해당 플랫폼으로 이동
        if (currentUrl == null || !platformConfig.matchesDomain(currentUrl)) {
            if (stepIndex >= MAX_DOMAIN_REDIRECT_ATTEMPTS) {
                log.error("[AgentStepPlannerService] 잘못된 도메인 이동 {}회 초과 → ABORT - runId={}, url={}",
                        MAX_DOMAIN_REDIRECT_ATTEMPTS, runId, currentUrl);
                return ActionInstructionResponse.abort(stepIndex, actionId,
                        "플랫폼 이동 " + MAX_DOMAIN_REDIRECT_ATTEMPTS + "회 초과 실패 - 브라우저 상태를 확인해주세요.");
            }
            String targetUrl = platformConfig.isPreferSearchNavigation()
                    ? platformConfig.buildMainPageUrl()  // 네이버: 메인 페이지에서 검색창 탐색
                    : buildSearchUrl(platformConfig, commandSession, targetProduct); // 알리: 검색 URL 직접
            return ActionInstructionResponse.navigate(
                    stepIndex,
                    actionId,
                    targetUrl,
                    DEFAULT_NAVIGATION_TIMEOUT_MS
            );
        }

        Optional<InteractiveElementRequest> purchaseButton = findElement(snapshot, "button", "구매");
        if (purchaseButton.isPresent()) {
            return ActionInstructionResponse.click(stepIndex, actionId, purchaseButton.get());
        }

        Optional<InteractiveElementRequest> addToCartButton = findElement(snapshot, "button", "장바구니");
        if (addToCartButton.isPresent()) {
            return ActionInstructionResponse.click(stepIndex, actionId, addToCartButton.get());
        }

        if (snapshot != null && !snapshot.interactiveElements().isEmpty()) {
            return ActionInstructionResponse.waitAction(stepIndex, actionId, DEFAULT_WAIT_MS, DEFAULT_NAVIGATION_TIMEOUT_MS);
        }

        return ActionInstructionResponse.complete(stepIndex, actionId);
    }

    private ActionInstructionResponse buildSmartstoreOptionPresenceInstruction(
            String runId,
            int stepIndex,
            CommandSession commandSession,
            PageSnapshotRequest snapshot,
            AgentRunActionResultRequest previousActionResult,
            String selectedOptionValue,
            PageType currentPageType,
            int optionPresenceScrollCount
    ) {
        if (currentPageType != PageType.PRODUCT_DETAIL) {
            return null;
        }
        if (selectedOptionValue != null && !selectedOptionValue.isBlank()) {
            return null;
        }

        boolean optionGroupsVisible = snapshot != null
                && snapshot.optionGroups() != null
                && !snapshot.optionGroups().isEmpty();
        if (optionGroupsVisible) {
            return null;
        }

        BrowserActionType prevAction = previousActionResult != null ? previousActionResult.action() : null;
        ActionExecutionStatus prevStatus = previousActionResult != null ? previousActionResult.status() : null;
        boolean previousScrollSuccess = prevAction == BrowserActionType.SCROLL && prevStatus == ActionExecutionStatus.SUCCESS;
        boolean previousClickSuccess = prevAction == BrowserActionType.CLICK && prevStatus == ActionExecutionStatus.SUCCESS;
        boolean previousScreenshotCaptured = previousActionResult != null
                && previousActionResult.toolResult() != null
                && previousActionResult.toolResult().screenshot() != null;

        String actionId = buildActionId(runId, stepIndex);

        if (previousScreenshotCaptured) {
            try {
                VisionPlannerInstructionPayload payload = aiVisionPlannerClient.analyze(
                        runId,
                        stepIndex,
                        commandSession.getOriginalCommand(),
                        snapshot == null ? null : snapshot.currentUrl(),
                        previousActionResult,
                        previousActionResult.toolResult().screenshot(),
                        "SMARTSTORE_OPTION_PRESENCE",
                        null
                );

                log.info("[AgentStepPlannerService] Smartstore 옵션 존재 확인 Vision 결과 - runId={}, stepIndex={}, action={}, x={}, y={}, confidence={}, label={}",
                        runId, stepIndex, payload.action(), payload.viewportX(), payload.viewportY(),
                        payload.confidence(), payload.targetLabel());

                if (payload.confidence() != null && payload.confidence() < MIN_VISION_CONFIDENCE) {
                    log.info("[AgentStepPlannerService] Smartstore 옵션 존재 확인 Vision confidence 부족 - runId={}, stepIndex={}, confidence={}",
                            runId, stepIndex, payload.confidence());
                    return optionPresenceScrollCount < MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS
                            ? ActionInstructionResponse.scroll(stepIndex, actionId, "500")
                            : null;
                }

                if ("CLICK".equals(payload.action()) && payload.viewportX() != null && payload.viewportY() != null) {
                    return ActionInstructionResponse.visionClick(
                            stepIndex,
                            actionId,
                            payload.viewportX(),
                            payload.viewportY(),
                            payload.targetLabel()
                    );
                }

                if (optionPresenceScrollCount < MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS) {
                    log.info("[AgentStepPlannerService] Smartstore 옵션 존재 확인 Vision 미탐지 → 추가 SCROLL - runId={}, stepIndex={}, nextScrollCount={}/{}",
                            runId, stepIndex, optionPresenceScrollCount + 1, MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS);
                    return ActionInstructionResponse.scroll(stepIndex, actionId, "500");
                }
                return null;
            } catch (Exception e) {
                log.warn("[AgentStepPlannerService] Smartstore 옵션 존재 확인 Vision 실패 - runId={}, stepIndex={}, error={}",
                        runId, stepIndex, e.getMessage());
                return optionPresenceScrollCount < MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS
                        ? ActionInstructionResponse.scroll(stepIndex, actionId, "500")
                        : null;
            }
        }

        if (previousScrollSuccess) {
            log.info("[AgentStepPlannerService] Smartstore 옵션 존재 확인 캡처 요청 - runId={}, stepIndex={}, scrollCount={}/{}",
                    runId, stepIndex, optionPresenceScrollCount, MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS);
            return ActionInstructionResponse.useTool(
                    stepIndex,
                    actionId,
                    "CAPTURE_VISIBLE_TAB",
                    java.util.Map.of(
                            "format", "png",
                            "mode", "SMARTSTORE_OPTION_PRESENCE"
                    )
            );
        }

        if ((previousActionResult == null || previousClickSuccess)
                && optionPresenceScrollCount < MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS) {
            log.info("[AgentStepPlannerService] Smartstore 옵션 존재 확인 초기/후속 SCROLL - runId={}, stepIndex={}, nextScrollCount={}/{}",
                    runId, stepIndex, optionPresenceScrollCount + 1, MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS);
            return ActionInstructionResponse.scroll(stepIndex, actionId, "500");
        }

        return null;
    }

    private ActionInstructionResponse buildVisionFallbackInstruction(
            String runId,
            int stepIndex,
            CommandSession commandSession,
            PageSnapshotRequest snapshot,
            AgentRunActionResultRequest previousActionResult,
            String selectedOptionValue
    ) {
        if (snapshot != null && detectPageType(snapshot) == PageType.SEARCH_RESULTS) {
            return null;
        }

        if (previousActionResult == null) {
            return null;
        }

        // toolResult가 없거나, 스크린샷이 없을 경우
        if (previousActionResult.toolResult() == null || previousActionResult.toolResult().screenshot() == null) {
            // 이전 액션이 "요소 못 찾음" 실패였을 경우
            if (previousActionResult.status() == com.pbm.command.domain.ActionExecutionStatus.FAILURE
                    && previousActionResult.errorCode() == com.pbm.command.domain.ActionErrorCode.ELEMENT_NOT_FOUND) {

                // 스크린샷을 찍어달라고 익스텐션에게 요청
                return ActionInstructionResponse.useTool(stepIndex, buildActionId(runId, stepIndex), "CAPTURE_VISIBLE_TAB", java.util.Map.of("format", "png"));
            }
            return null;
        }

        try {
            // 스크린샷 결과를 받음
            VisionPlannerInstructionPayload payload = aiVisionPlannerClient.analyze(
                    runId,
                    stepIndex,
                    commandSession.getOriginalCommand(),
                    snapshot == null ? null : snapshot.currentUrl(),
                    previousActionResult,
                    previousActionResult.toolResult().screenshot(),  // 스크린샷 내용
                    selectedOptionValue == null || selectedOptionValue.isBlank()
                            ? "GENERAL"
                            : "SMARTSTORE_OPTION_SELECTION",
                    selectedOptionValue
            );

            log.info("[AgentStepPlannerService] Vision planner 결과 - runId={}, stepIndex={}, action={}, x={}, y={}, confidence={}, label={}",
                    runId, stepIndex, payload.action(), payload.viewportX(), payload.viewportY(),
                    payload.confidence(), payload.targetLabel());

            // 확신도가 0.6 미만이면 다음 방법으로
            if (payload.confidence() != null && payload.confidence() < MIN_VISION_CONFIDENCE) {
                log.info("[AgentStepPlannerService] Vision planner confidence 부족 - runId={}, stepIndex={}, confidence={}",
                        runId, stepIndex, payload.confidence());
                return null;
            }

            if ("CLICK".equals(payload.action()) && payload.viewportX() != null && payload.viewportY() != null) {
                return ActionInstructionResponse.visionClick(
                        stepIndex,
                        buildActionId(runId, stepIndex),
                        payload.viewportX(),
                        payload.viewportY(),
                        payload.targetLabel()
                );
            }

            // Vision AI가 WAIT를 반환한 경우:
            // WAIT 액션을 그대로 반환하면 다음 step에서 previousWasClick=false, visionAttempted=false가 되어
            // CAPTURE_VISIBLE_TAB이 반복 호출되는 무한루프가 발생한다.
            // null을 반환하면 visionAttempted=true 상태가 유지되어 PRODUCT_DETAIL 블록에서 SCROLL로 진행된다.
            if ("WAIT".equals(payload.action())) {
                log.info("[AgentStepPlannerService] Vision planner WAIT 반환 → SCROLL로 대체 (무한루프 방지) - runId={}, stepIndex={}", runId, stepIndex);
                return null;
            }
        } catch (Exception e) {
            log.warn("[AgentStepPlannerService] Vision planner 실패 - runId={}, stepIndex={}, error={}", runId, stepIndex, e.getMessage());
        }

        return null;
    }

    private ActionInstructionResponse buildSearchResultsVisionFallbackInstruction(
            String runId,
            int stepIndex,
            CommandSession commandSession,
            PageSnapshotRequest snapshot,
            AgentRunActionResultRequest previousActionResult,
            ProductCandidateResponse targetProduct,
            ExternalStoreVisionStage externalStoreVisionStage,
            int searchResultsAttemptCount
    ) {
        if (snapshot == null || previousActionResult == null) {
            return null;
        }

        if (externalStoreVisionStage != ExternalStoreVisionStage.SEARCH_RESULTS_PRODUCT) {
            return null;
        }

        if (detectPageType(snapshot) != PageType.SEARCH_RESULTS) {
            return null;
        }

        PlatformConfig platformConfig = resolvePlatformConfig(commandSession);
        boolean supportsSearchResultsVisionFallback = platformConfig.isPreferSearchNavigation()
                || platformConfig.getPlatform() == PlatformType.ALIEXPRESS;
        if (!supportsSearchResultsVisionFallback) {
            return null;
        }

        if (targetProduct == null || targetProduct.productUrl() == null || targetProduct.productUrl().isBlank()) {
            return null;
        }

        if (isSamePage(snapshot.currentUrl(), targetProduct.productUrl())) {
            return null;
        }

        BrowserActionType previousAction = previousActionResult.action();
        ActionExecutionStatus previousStatus = previousActionResult.status();
        String actionId = buildActionId(runId, stepIndex);

        if (previousActionResult.toolResult() == null || previousActionResult.toolResult().screenshot() == null) {
            boolean readyToCapture = previousStatus == ActionExecutionStatus.SUCCESS
                    && (previousAction == BrowserActionType.WAIT || previousAction == BrowserActionType.SCROLL);
            boolean retryAfterMiss = previousStatus == ActionExecutionStatus.FAILURE
                    && previousActionResult.errorCode() == ActionErrorCode.ELEMENT_NOT_FOUND;

            if (readyToCapture || retryAfterMiss) {
                log.info("[AgentStepPlannerService] SEARCH_RESULTS Vision 캡처 요청 - runId={}, stepIndex={}, previousAction={}, attemptCount={}",
                        runId, stepIndex, previousAction, searchResultsAttemptCount);
                return ActionInstructionResponse.useTool(stepIndex, actionId, "CAPTURE_VISIBLE_TAB", java.util.Map.of("format", "png"));
            }
            return null;
        }

        try {
            VisionPlannerInstructionPayload payload = aiVisionPlannerClient.analyze(
                    runId,
                    stepIndex,
                    commandSession.getOriginalCommand(),
                    snapshot.currentUrl(),
                    previousActionResult,
                    previousActionResult.toolResult().screenshot(),
                    "SEARCH_RESULTS_PRODUCT",
                    buildSearchResultsVisionTargetDescriptor(targetProduct)
            );

            log.info("[AgentStepPlannerService] SEARCH_RESULTS Vision 결과 - runId={}, stepIndex={}, action={}, x={}, y={}, confidence={}, label={}, attemptCount={}",
                    runId, stepIndex, payload.action(), payload.viewportX(), payload.viewportY(),
                    payload.confidence(), payload.targetLabel(), searchResultsAttemptCount);

            if ("CLICK".equals(payload.action())
                    && payload.viewportX() != null
                    && payload.viewportY() != null
                    && (payload.confidence() == null || payload.confidence() >= MIN_VISION_CONFIDENCE)) {
                return ActionInstructionResponse.visionClick(
                        stepIndex,
                        actionId,
                        payload.viewportX(),
                        payload.viewportY(),
                        payload.targetLabel()
                );
            }

            if (hasExceededSearchResultsVisionMissLimit(searchResultsAttemptCount)) {
                log.warn("[AgentStepPlannerService] SEARCH_RESULTS Vision 한도 초과 → ABORT - runId={}, stepIndex={}, attemptCount={}, action={}, confidence={}",
                        runId, stepIndex, searchResultsAttemptCount, payload.action(), payload.confidence());
                return ActionInstructionResponse.abort(
                        stepIndex,
                        actionId,
                        "검색 결과 페이지에서 대상 상품을 찾지 못했습니다. 상품 목록을 확인해주세요."
                );
            }

            log.info("[AgentStepPlannerService] SEARCH_RESULTS Vision 미탐지/저신뢰도 → SCROLL 재시도 - runId={}, stepIndex={}, attemptCount={}",
                    runId, stepIndex, searchResultsAttemptCount);
            return ActionInstructionResponse.scroll(stepIndex, actionId, "500");
        } catch (Exception e) {
            if (hasExceededSearchResultsVisionMissLimit(searchResultsAttemptCount)) {
                log.warn("[AgentStepPlannerService] SEARCH_RESULTS Vision 실패 + 한도 초과 → ABORT - runId={}, stepIndex={}, attemptCount={}, error={}",
                        runId, stepIndex, searchResultsAttemptCount, e.getMessage());
                return ActionInstructionResponse.abort(
                        stepIndex,
                        actionId,
                        "검색 결과 페이지에서 대상 상품을 찾지 못했습니다. 상품 목록을 확인해주세요."
                );
            }

            log.warn("[AgentStepPlannerService] SEARCH_RESULTS Vision 실패 → SCROLL 재시도 - runId={}, stepIndex={}, attemptCount={}, error={}",
                    runId, stepIndex, searchResultsAttemptCount, e.getMessage());
            return ActionInstructionResponse.scroll(stepIndex, actionId, "500");
        }
    }

    private boolean hasExceededSearchResultsVisionMissLimit(int searchResultsAttemptCount) {
        return searchResultsAttemptCount
                >= SEARCH_RESULTS_DOM_FAILURE_BEFORE_VISION + SEARCH_RESULTS_MAX_VISION_MISSES - 1;
    }

    private String buildSearchResultsVisionTargetDescriptor(ProductCandidateResponse targetProduct) {
        String title = stripHtmlTags(targetProduct.title());
        String productId = targetProduct.productId();
        String price = targetProduct.lprice();
        String mallName = targetProduct.mallName();

        return String.format(
                "{\"title\":\"%s\",\"price\":\"%s\",\"productId\":\"%s\",\"mallName\":\"%s\"}",
                escapeJsonString(title),
                escapeJsonString(price),
                escapeJsonString(productId),
                escapeJsonString(mallName)
        );
    }

    private String stripHtmlTags(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private String escapeJsonString(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private ActionInstructionResponse buildAiDomInstruction(
            String runId,
            int stepIndex,
            CommandSession commandSession,
            PageSnapshotRequest snapshot,
            ProductCandidateResponse targetProduct
    ) {
        if (snapshot == null) {
            return null;
        }

        // 플랫폼 설정에서 platform 이름과 navigationStrategy를 추출해 AI에게 전달한다.
        PlatformConfig platformConfig = resolvePlatformConfig(commandSession);
        String platformName = platformConfig.getPlatform().name();
        String navigationStrategy = platformConfig.isPreferSearchNavigation() ? "SEARCH" : "DIRECT";

        // 페이지 타입을 룰 기반으로 판단하여 전문 AI 에이전트 타입을 결정한다.
        // (planNextAction 에서 이미 계산한 값이 있으나, 여기서는 독립적으로 재계산)
        PageType pageType = detectPageType(snapshot);
        String agentType = resolveAgentType(pageType, platformConfig);

        log.info("[AgentStepPlannerService] 페이지 타입 판단 - runId={}, stepIndex={}, pageType={}, agentType={}",
                runId, stepIndex, pageType, agentType);

        // 검색 결과 페이지 + preferSearchNavigation 플랫폼:
        // targetProduct의 productUrl에서 productId를 추출해 href로 매칭 → AI 없이 deterministic CLICK
        // 네이버 상품 링크는 target="_blank"(새 탭)로 열리므로,
        // extension이 새 탭 감지 후 targetTabId를 교체하는 방식으로 처리한다.
        boolean supportsDeterministicSearchResultClick = platformConfig.isPreferSearchNavigation()
                || platformConfig.getPlatform() == PlatformType.ALIEXPRESS;
        if (pageType == PageType.SEARCH_RESULTS
                && supportsDeterministicSearchResultClick
                && targetProduct != null
                && targetProduct.productUrl() != null) {
            Optional<InteractiveElementRequest> matchedProduct =
                    findProductByUrlId(snapshot, targetProduct);
            if (matchedProduct.isPresent()) {
                log.info("[AgentStepPlannerService] productId href 매칭 성공 → CLICK - runId={}, selector={}, href={}",
                        runId, matchedProduct.get().selector(), matchedProduct.get().href());
                return ActionInstructionResponse.click(stepIndex, buildActionId(runId, stepIndex), matchedProduct.get());
            }
            log.info("[AgentStepPlannerService] productId href 매칭 실패 → AI fallback - runId={}", runId);
        }


        // 봇 차단/CAPTCHA 페이지: AI 호출 없이 사용자 개입 요청
        if (pageType == PageType.BLOCKED) {
            String actionId = buildActionId(runId, stepIndex);
            log.warn("[AgentStepPlannerService] 봇 차단/캡챠 페이지 감지 → 사용자 개입 요청 - runId={}, url={}", runId, snapshot.currentUrl());
            return ActionInstructionResponse.awaitApproval(
                    stepIndex,
                    actionId,
                    "네이버 캡챠 보안 인증이 필요합니다. 브라우저에서 직접 캡챠를 해결하면 자동으로 재개됩니다.",
                    DEFAULT_APPROVAL_TIMEOUT_MS
            );
        }

        // 결제 페이지 도달 → COMPLETE 반환 (AgentRunService에서 checkout 이벤트 처리)
        if (pageType == PageType.CHECKOUT_PAGE) {
            String actionId = buildActionId(runId, stepIndex);
            log.info("[AgentStepPlannerService] 결제 페이지 감지 → COMPLETE - runId={}, url={}",
                    runId, snapshot.currentUrl());
            return ActionInstructionResponse.complete(stepIndex, actionId);
        }

        // 플랫폼 메인 페이지 처리
        if (pageType == PageType.MAIN_PAGE) {
            String actionId = buildActionId(runId, stepIndex);
            String currentUrlLower = snapshot.currentUrl() != null ? snapshot.currentUrl().toLowerCase() : "";

            // 현재 URL이 대상 플랫폼 도메인이 아니면 올바른 플랫폼으로 먼저 이동
            // (예: 네이버 명령인데 알리익스프레스 메인에 있는 경우)
            if (!platformConfig.matchesDomain(currentUrlLower)) {
                if (stepIndex >= MAX_DOMAIN_REDIRECT_ATTEMPTS) {
                    log.error("[AgentStepPlannerService] 잘못된 도메인 이동 {}회 초과 → ABORT - runId={}, url={}",
                            MAX_DOMAIN_REDIRECT_ATTEMPTS, runId, currentUrlLower);
                    return ActionInstructionResponse.abort(stepIndex, actionId,
                            "플랫폼 이동 " + MAX_DOMAIN_REDIRECT_ATTEMPTS + "회 초과 실패 - 브라우저 상태를 확인해주세요.");
                }
                String targetUrl = platformConfig.isPreferSearchNavigation()
                        ? platformConfig.buildMainPageUrl()  // 네이버: 항상 메인 페이지로
                        : buildSearchUrl(platformConfig, commandSession, targetProduct); // 알리: 검색 URL
                log.warn("[AgentStepPlannerService] 잘못된 도메인에서 MAIN_PAGE 감지 → 플랫폼 이동 - runId={}, from={}, to={}",
                        runId, currentUrlLower, targetUrl);
                return ActionInstructionResponse.navigate(stepIndex, actionId, targetUrl, DEFAULT_NAVIGATION_TIMEOUT_MS);
            }

            if (platformConfig.isPreferSearchNavigation()) {
                // 네이버 등: 검색창을 찾아 직접 타이핑 → Enter로 검색 (자연스러운 Referer 체인 형성)
                Optional<InteractiveElementRequest> searchInput = findSearchInput(snapshot);
                if (searchInput.isPresent()) {
                    String searchKeyword = buildSearchKeyword(commandSession, targetProduct);
                    log.info("[AgentStepPlannerService] 메인 페이지 검색창 INPUT - runId={}, keyword={}", runId, searchKeyword);
                    // 값 끝에 '\n' 추가 → Extension이 타이핑 후 Enter 키로 검색 실행
                    return ActionInstructionResponse.input(stepIndex, actionId, searchInput.get(), searchKeyword + "\n");
                }
                // 검색창 못 찾으면 메인 페이지 재진입 (검색 URL 직접 이동 금지 — 봇 차단 위험)
                String mainPageUrl = platformConfig.buildMainPageUrl();
                log.warn("[AgentStepPlannerService] 검색창 미발견 → 메인 페이지 재진입 - runId={}, url={}", runId, mainPageUrl);
                return ActionInstructionResponse.navigate(stepIndex, actionId, mainPageUrl, DEFAULT_NAVIGATION_TIMEOUT_MS);
            } else {
                // 알리익스프레스 등: 검색 URL로 직접 이동
                String searchUrl = buildSearchUrl(platformConfig, commandSession, targetProduct);
                log.info("[AgentStepPlannerService] 메인 페이지 → 검색 URL 직접 이동 - runId={}, url={}", runId, searchUrl);
                return ActionInstructionResponse.navigate(stepIndex, actionId, searchUrl, DEFAULT_NAVIGATION_TIMEOUT_MS);
            }
        }

        try {
            DomPlannerInstructionPayload payload = aiDomPlannerClient.plan(
                    commandSession, snapshot, targetProduct, platformName, navigationStrategy, agentType);
            if (payload.confidence() != null && payload.confidence() < MIN_AI_CONFIDENCE) {
                log.info("[AgentStepPlannerService] AI planner confidence 부족으로 rule-based fallback - runId={}, stepIndex={}, confidence={}, reason={}",
                        runId, stepIndex, payload.confidence(), payload.reason());
                return null;
            }

            log.info("[AgentStepPlannerService] AI payload - action={}, nodeId={}, role={}, labelText={}, selector={}, confidence={}",
                    payload.action(),
                    payload.target() != null ? payload.target().nodeId() : "null",
                    payload.target() != null ? payload.target().role() : "null",
                    payload.target() != null ? payload.target().labelText() : "null",
                    payload.target() != null ? payload.target().selector() : "null",
                    payload.confidence());

            ActionInstructionResponse aiResult = convertAiInstruction(runId, stepIndex, payload, snapshot);
            log.info("[AgentStepPlannerService] AI planner 결과 - runId={}, stepIndex={}, pageType={}, agentType={}, action={}, confidence={}, reason={}",
                    runId, stepIndex, pageType, agentType,
                    aiResult != null ? aiResult.action() : "null",
                    payload.confidence(), payload.reason());

            log.info("[AgentStepPlannerService] PURCHASE_EXECUTOR guard 진단 - runId={}, stepIndex={}, aiAction={}, optionGroupCount={}, interactiveElementCount={}, hasVisiblePurchaseButton={}, visibleTextHasPurchase={}, rawHtmlHasPurchase={}",
                    runId,
                    stepIndex,
                    aiResult != null ? aiResult.action() : null,
                    snapshot != null && snapshot.optionGroups() != null ? snapshot.optionGroups().size() : -1,
                    snapshot != null && snapshot.interactiveElements() != null ? snapshot.interactiveElements().size() : -1,
                    hasVisiblePurchaseButton(snapshot),
                    snapshot != null && snapshot.visibleTextSummary() != null && snapshot.visibleTextSummary().contains("구매하기"),
                    snapshot != null && snapshot.rawHtml() != null && snapshot.rawHtml().contains("구매하기"));

            if (pageType == PageType.PRODUCT_DETAIL
                    && "PURCHASE_EXECUTOR".equals(agentType)
                    && aiResult != null) {
                if (aiResult.action() == BrowserActionType.WAIT
                        && isOptionSelectionNotRequired(snapshot)
                        && hasVisiblePurchaseButton(snapshot)) {
                    log.warn("[AgentStepPlannerService] 구매 버튼이 보이고 optionGroups가 비어 있는데 WAIT 반환 → 거부 후 재탐색 - runId={}, stepIndex={}",
                            runId, stepIndex);
                    aiResult = null;
                }

                if (aiResult != null
                        && aiResult.action() == BrowserActionType.COMPLETE
                        && hasVisiblePurchaseButton(snapshot)) {
                    log.warn("[AgentStepPlannerService] 구매 버튼이 아직 보이는데 COMPLETE 반환 → 거부 후 재탐색 - runId={}, stepIndex={}",
                            runId, stepIndex);
                    aiResult = null;
                }
            }

            // 네이버 등 preferSearchNavigation 플랫폼에서 AI가 NAVIGATE를 반환한 경우:
            // 상품 URL 직접 접근은 봇 차단 위험 → WAIT으로 대체
            // 단, CATALOG_NAVIGATOR가 adcr 판매처 URL로 NAVIGATE를 반환한 경우는 허용
            // (cr.shopping.naver.com/adcr → 판매처 상세페이지로 리다이렉트되는 네이버 추적 URL)
            if (aiResult != null
                    && aiResult.action() == BrowserActionType.NAVIGATE
                    && platformConfig.isPreferSearchNavigation()
                    && (pageType == PageType.SEARCH_RESULTS || pageType == PageType.CATALOG_PAGE)) {
                String navigateValue = aiResult.value();
                // CATALOG_PAGE에서 adcr URL로의 이동은 판매처 상세페이지 진입이므로 허용
                if (pageType == PageType.CATALOG_PAGE
                        && navigateValue != null
                        && navigateValue.contains("cr.shopping.naver.com/adcr")) {
                    log.info("[AgentStepPlannerService] CATALOG_NAVIGATOR → adcr 판매처 URL NAVIGATE 허용 - runId={}, url={}",
                            runId, navigateValue);
                    return aiResult;
                }
                log.warn("[AgentStepPlannerService] AI가 검색결과/카탈로그에서 NAVIGATE 반환 → 봇 차단/무한루프 위험, WAIT으로 대체 - runId={}, pageType={}", runId, pageType);
                return ActionInstructionResponse.waitAction(stepIndex, buildActionId(runId, stepIndex), DEFAULT_WAIT_MS, DEFAULT_NAVIGATION_TIMEOUT_MS);
            }

            return aiResult;
        } catch (Exception e) {
            log.warn("[AgentStepPlannerService] AI planner 실패로 rule-based fallback - runId={}, stepIndex={}, error={}",
                    runId, stepIndex, e.getMessage());
            return null;
        }
    }

    public boolean hasUnmatchedOptions(
            CommandSession commandSession,
            PageSnapshotRequest snapshot,
            String selectedOptionValue
    ) {
        if (commandSession == null || snapshot == null || snapshot.optionGroups() == null || snapshot.optionGroups().isEmpty()) {
            return false;
        }

        String normalizedCommand = normalize(commandSession.getOriginalCommand());
        for (OptionGroupRequest optionGroup : snapshot.optionGroups()) {
            String desiredOption = findDesiredOption(normalizedCommand, optionGroup);
            if (desiredOption == null) {
                continue;
            }

            if (selectedOptionValue != null
                    && normalize(selectedOptionValue).contains(normalize(desiredOption))) {
                continue;
            }

            if (optionGroup.selectedOption() != null
                    && normalize(optionGroup.selectedOption()).contains(normalize(desiredOption))) {
                continue;
            }

            return true;
        }

        return false;
    }

    /**
     * 사용자가 명령어에서 옵션을 전혀 지정하지 않았는데,
     * 페이지에 선택 가능한 옵션 그룹이 존재하는지 확인한다.
     * (예: 색상, 사이즈 옵션이 있는데 사용자가 "버즈4 구매해줘"만 입력한 경우)
     *
     * hasUnmatchedOptions()는 "지정했는데 매칭 안 된 경우"만 감지하므로,
     * "아예 지정하지 않은 경우"를 별도로 감지하기 위한 메서드이다.
     */
    public boolean hasAnyUnspecifiedOptions(
            CommandSession commandSession,
            PageSnapshotRequest snapshot,
            String selectedOptionValue
    ) {
        // optionGroups가 없으면 false
        if (commandSession == null || snapshot == null
                || snapshot.optionGroups() == null || snapshot.optionGroups().isEmpty()) {
            return false;
        }
        // 이미 텔레그램에서 선택값을 받았으면 false
        if (selectedOptionValue != null && !selectedOptionValue.isBlank()) {
            return false;
        }
        String normalizedCommand = normalize(commandSession.getOriginalCommand());
        // 옵션 그룹 중 하나라도 사용자가 지정하지 않은 것이 있으면 true
        for (OptionGroupRequest optionGroup : snapshot.optionGroups()) {
            String desiredOption = findDesiredOption(normalizedCommand, optionGroup);
            if (desiredOption == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 페이지 타입 열거형.
     * Java 룰 기반으로 분류하여 적절한 전문 AI를 선택하는 데 사용한다.
     */
    private enum PageType {
        /** 검색 결과 목록 페이지 → SearchNavigatorAI */
        SEARCH_RESULTS,
        /** 카탈로그 페이지: 여러 판매처를 보여주는 중간 페이지 (네이버 catalog)
         *  → "최저가 사러가기" 버튼 클릭으로 실제 판매 페이지로 이동 */
        CATALOG_PAGE,
        /** 상품 상세 페이지 → PurchaseExecutorAI */
        PRODUCT_DETAIL,
        /** 플랫폼 메인 페이지 → preferSearchNavigation=true: 검색창 INPUT, false: 검색 URL 직접 이동 */
        MAIN_PAGE,
        /** 봇 차단/CAPTCHA/에러 페이지 → 사용자 개입 요청 (AWAIT_APPROVAL) */
        BLOCKED,
        /** 로그인 페이지 판단 */
        LOGIN_PAGE,
        /** 결제/주문서 페이지 (orders.pay.naver.com 등) → COMPLETE 반환 */
        CHECKOUT_PAGE
    }

    /**
     * URL 패턴 + 페이지 텍스트로 현재 페이지 타입을 판단한다.
     * AI 호출 없이 룰 기반으로 처리하여 비용과 지연을 최소화한다.
     */
    private PageType detectPageType(PageSnapshotRequest snapshot) {
        if (snapshot == null) {
            return PageType.MAIN_PAGE;
        }

        String url = snapshot.currentUrl() != null ? snapshot.currentUrl().toLowerCase() : "";
        String title = snapshot.title() != null ? snapshot.title().toLowerCase() : "";
        String visibleText = snapshot.visibleTextSummary() != null ? snapshot.visibleTextSummary().toLowerCase() : "";

        // 1. 알려진 플랫폼 메인 페이지 URL이면 BLOCKED 체크 없이 바로 MAIN_PAGE 반환
        // 캡챠 해결 후 메인으로 돌아올 때 스냅샷 텍스트에 캡챠 잔여 문구가 남아
        // BLOCKED로 오판하는 것을 방지한다.
        // 단, 캡챠 페이지는 제외: 네이버 캡챠 오버레이가 뜰 경우 <head title="captcha">로 표시됨
        if (!title.contains("captcha")
                && (url.contains("search.shopping.naver.com/home")
                    || url.equals("https://www.aliexpress.com/")
                    || url.equals("https://www.aliexpress.com"))) {
            return PageType.MAIN_PAGE;
        }

        // 2. 봇 차단/CAPTCHA/에러 페이지 우선 체크
        if (isBlockedOrErrorPage(url, title, visibleText)) {
            return PageType.BLOCKED;
        }

        // 로그인 페이지 (nid.naver.com)
        if (url.contains("nid.naver.com")) {
            return PageType.LOGIN_PAGE;
        }

        // 결제/주문서 페이지 (orders.pay.naver.com 등)
        if (isCheckoutPage(url)) {
            return PageType.CHECKOUT_PAGE;
        }

        // 2. 카탈로그 페이지: 여러 판매처 목록 (네이버 search.shopping.naver.com/catalog/)
        if (isCatalogPage(url)) {
            return PageType.CATALOG_PAGE;
        }

        // 3. 상품 상세 페이지
        if (isProductDetailPage(url)) {
            return PageType.PRODUCT_DETAIL;
        }

        // 4. 검색 결과 페이지
        if (isSearchResultsPage(url, title)) {
            return PageType.SEARCH_RESULTS;
        }

        // 4. 그 외 → 플랫폼 메인 페이지로 간주
        return PageType.MAIN_PAGE;
    }

    /**
     * 봇 차단, CAPTCHA, 에러 페이지 여부를 판단한다.
     */
    private boolean isBlockedOrErrorPage(String url, String title, String visibleText) {
        // 네이버 캡챠 페이지 URL 직접 감지 (ncpt.naver.com)
        if (url.contains("ncpt.naver.com") || url.contains("/captcha")) {
            return true;
        }
        // 네이버/일반 봇 차단 패턴
        if (visibleText.contains("비정상적인 접근")
                || visibleText.contains("로봇이 아님을 확인")
                || visibleText.contains("보안 확인을 완료해 주세요")
                || visibleText.contains("captcha")
                || visibleText.contains("robot")
                || title.contains("captcha")
                || title.contains("접근 제한")
                || title.contains("access denied")
                || title.contains("403")
                || title.contains("차단")) {
            return true;
        }
        // 일반 에러 페이지
        if (title.contains("404") || title.contains("not found") || title.contains("오류") || title.contains("error")) {
            return true;
        }
        return false;
    }

    /**
     * 카탈로그 페이지 URL 패턴을 확인한다.
     * 네이버: search.shopping.naver.com/catalog/{id} → 여러 판매처를 보여주는 중간 페이지
     * "최저가 사러가기" 버튼을 클릭해야 실제 판매 페이지로 이동한다.
     */
    /**
     * 결제/주문서 페이지 URL 패턴을 확인한다.
     * AgentRunService.isCheckoutUrl()과 동일한 패턴을 사용한다.
     */
    private boolean isCheckoutPage(String url) {
        return url.contains("orders.pay.naver.com")
                || url.contains("order.pay.naver.com")
                || url.contains("checkout.coupang.com")
                || url.contains("cart.coupang.com")
                || url.contains("order.auction.co.kr")
                || url.contains("order.gmarket.co.kr")
                || url.contains("order.11st.co.kr")
                || url.contains("/trade/confirm");
    }

    private boolean isCatalogPage(String url) {
        return url.contains("search.shopping.naver.com/catalog/")
                || url.contains("shopping.naver.com/catalog/");
    }

    /**
     * 상품 상세 페이지 URL 패턴을 확인한다.
     */
    private boolean isProductDetailPage(String url) {
        return url.contains("/item/")
                || url.contains("/product/")
                || url.contains("/goods/")
                || url.contains("/detail/")
                || url.contains("/p/")
                || url.contains("itemid=")
                || url.matches(".*smartstore\\.naver\\.com/[^/]+/products/.*");
    }

    /**
     * 검색 결과 페이지 URL 패턴을 확인한다.
     * 주의: search.shopping.naver.com/home 은 URL에 "search"가 포함되지만 메인 페이지이므로 제외
     */
    private boolean isSearchResultsPage(String url, String title) {
        // 네이버 쇼핑 메인 페이지는 검색결과 아님
        if (url.contains("search.shopping.naver.com/home")
                || url.endsWith("shopping.naver.com/ns/home")) {
            return false;
        }
        return url.contains("/search/")
                || url.contains("query=")
                || url.contains("searchtext")
                || url.contains("wholesale")
                || url.contains("q=")
                || url.contains("keyword=")
                || title.contains("검색결과")
                || title.contains("search result");
    }

    /**
     * 페이지 타입과 플랫폼 설정으로 AI 에이전트 타입을 결정한다.
     */
    private String resolveAgentType(PageType pageType, PlatformConfig platformConfig) {
        return switch (pageType) {
            case SEARCH_RESULTS -> "SEARCH_NAVIGATOR";
            case PRODUCT_DETAIL -> "PURCHASE_EXECUTOR";
            case CATALOG_PAGE -> "CATALOG_NAVIGATOR"; // 카탈로그: AI가 판매처 링크 선택
            case MAIN_PAGE, BLOCKED, LOGIN_PAGE, CHECKOUT_PAGE -> null; // AI 호출 안 함 → Java 룰로 처리
        };
    }

    /**
     * targetProduct URL에서 productId를 추출해 interactiveElements의 href와 매칭한다.
     *
     * 예) targetProduct.productUrl = "https://smartstore.naver.com/main/products/12487456679?..."
     *     → productId = "12487456679"
     *     → href에 "12487456679" 포함된 <a> 요소 반환
     *
     * AI 없이 deterministic하게 상품 카드를 찾을 수 있어 신뢰도가 높다.
     */
    private Optional<InteractiveElementRequest> findProductByUrlId(PageSnapshotRequest snapshot, ProductCandidateResponse targetProduct) {
        if (snapshot == null || targetProduct == null) {
            return Optional.empty();
        }

        String productId = extractProductIdForSearchResultMatch(targetProduct);
        if (productId.isBlank() || productId.length() < 4) {
            return Optional.empty();
        }

        log.info("[AgentStepPlannerService] productId 추출 - candidateProductId={}, productUrl={}, normalizedProductId={}",
                targetProduct.productId(), targetProduct.productUrl(), productId);

        // 수집된 href 목록 출력 (매칭 실패 원인 파악용)
        List<String> collectedHrefs = snapshot.interactiveElements().stream()
                .map(InteractiveElementRequest::href)
                .filter(h -> h != null && !h.isBlank())
                .limit(10)
                .toList();
        log.info("[AgentStepPlannerService] 수집된 href 샘플 (최대 10개): {}", collectedHrefs);

        // productId를 href에 포함한 <a> 요소를 찾고, selector를 CSS로 덮어쓴다.
        // nodeId는 DOM 변화에 취약(좌석 번호가 밀림)하므로 CSS selector를 우선 탐색 경로로 설정한다.
        // content script의 locateByFallback이 selector → document.querySelector()로 정확히 찾는다.
        return snapshot.interactiveElements().stream()
                .filter(el -> el.href() != null && el.href().contains(productId))
                .findFirst()
                .map(el -> new InteractiveElementRequest(
                        null,
                        el.role(),
                        el.labelText(),
                        buildProductHrefSelector(productId),
                        el.href(),
                        el.isVisible(),
                        el.disabled()
                ));
    }

    private String extractProductIdForSearchResultMatch(ProductCandidateResponse targetProduct) {
        if (targetProduct.productId() != null && targetProduct.productId().matches("\\d{4,}")) {
            return targetProduct.productId();
        }

        String productUrl = targetProduct.productUrl();
        if (productUrl == null || productUrl.isBlank()) {
            return "";
        }

        java.util.regex.Matcher pathMatcher = java.util.regex.Pattern
                .compile("/(?:item|i)/(\\d+)(?:\\.html)?", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(productUrl);
        if (pathMatcher.find()) {
            return pathMatcher.group(1);
        }

        try {
            URI uri = URI.create(productUrl);
            String query = uri.getQuery();
            if (query != null && !query.isBlank()) {
                for (String pair : query.split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length == 2
                            && ("productId".equalsIgnoreCase(parts[0]) || "id".equalsIgnoreCase(parts[0]))
                            && parts[1].matches("\\d{4,}")) {
                        return parts[1];
                    }
                }
            }
        } catch (Exception ignored) {
            // productUrl 파싱 실패 시 아래 마지막 세그먼트 fallback 사용
        }

        String path = productUrl.split("\\?")[0];
        String[] segments = path.split("/");
        if (segments.length == 0) {
            return "";
        }
        String lastSegment = segments[segments.length - 1];
        java.util.regex.Matcher digitsMatcher = java.util.regex.Pattern.compile("(\\d{4,})").matcher(lastSegment);
        return digitsMatcher.find() ? digitsMatcher.group(1) : lastSegment;
    }

    private String buildProductHrefSelector(String productId) {
        return String.join(", ",
                "a[href*=\"/item/" + productId + ".html\"]",
                "a[href*=\"/item/" + productId + "\"]",
                "a[href*=\"/i/" + productId + ".html\"]",
                "a[href*=\"/i/" + productId + "\"]",
                "a[href*=\"productId=" + productId + "\"]",
                "a[href*=\"id=" + productId + "\"]");
    }

    /**
     * 페이지 스냅샷에서 검색창 input 요소를 찾는다.
     * 우선순위: searchbox role → "검색" 레이블 input → 기타 활성화된 input/textbox
     */
    private Optional<InteractiveElementRequest> findSearchInput(PageSnapshotRequest snapshot) {
        if (snapshot == null || snapshot.interactiveElements().isEmpty()) {
            return Optional.empty();
        }

        // 1. role이 searchbox인 요소 (네이버 쇼핑 검색창)
        Optional<InteractiveElementRequest> searchBox = snapshot.interactiveElements().stream()
                .filter(InteractiveElementRequest::isEnabled)
                .filter(InteractiveElementRequest::isVisible)
                .filter(el -> "searchbox".equalsIgnoreCase(el.role()) || "search".equalsIgnoreCase(el.role()))
                .findFirst();
        if (searchBox.isPresent()) {
            return searchBox;
        }

        // 2. "검색" 관련 레이블을 가진 input/textbox
        Optional<InteractiveElementRequest> labeledSearch = snapshot.interactiveElements().stream()
                .filter(InteractiveElementRequest::isEnabled)
                .filter(InteractiveElementRequest::isVisible)
                .filter(el -> "input".equalsIgnoreCase(el.role()) || "textbox".equalsIgnoreCase(el.role()))
                .filter(el -> {
                    String label = el.labelText() != null ? el.labelText().toLowerCase() : "";
                    return label.contains("검색") || label.contains("search") || label.contains("query");
                })
                .findFirst();
        if (labeledSearch.isPresent()) {
            return labeledSearch;
        }

        // 3. 활성화된 첫 번째 input/textbox (폴백)
        return snapshot.interactiveElements().stream()
                .filter(InteractiveElementRequest::isEnabled)
                .filter(InteractiveElementRequest::isVisible)
                .filter(el -> "input".equalsIgnoreCase(el.role()) || "textbox".equalsIgnoreCase(el.role()))
                .findFirst();
    }

    /**
     * 검색 키워드를 결정한다.
     * 우선순위: 상품 title(HTML 태그 제거) > searchKeyword > 원본 명령어
     * 예: "<b>로지텍 MX MASTER 3S</b> bluetooth edition" → "로지텍 MX MASTER 3S bluetooth edition"
     */
    private String buildSearchKeyword(CommandSession commandSession, ProductCandidateResponse targetProduct) {
        if (targetProduct != null
                && targetProduct.title() != null
                && !targetProduct.title().isBlank()) {
            // <b>, </b> 등 HTML 태그 제거
            String cleanTitle = targetProduct.title().replaceAll("<[^>]+>", "").trim();
            if (!cleanTitle.isBlank()) {
                return cleanTitle;
            }
        }
        if (targetProduct != null
                && targetProduct.searchKeyword() != null
                && !targetProduct.searchKeyword().isBlank()) {
            return targetProduct.searchKeyword();
        }
        return commandSession.getOriginalCommand();
    }

    /**
     * 스마트스토어 토글/드롭다운형 옵션 선택 instruction을 생성한다.
     *
     * 플로우:
     * 1) 이전 클릭이 opener(토글 버튼)가 아니었으면 → opener DOM 클릭 (드롭다운 열기)
     * 2) opener 클릭 후(드롭다운 열린 상태) → Vision 스크린샷 요청 (옵션 항목 좌표 획득)
     *
     * 옵션 항목은 DOM 클릭하지 않고 반드시 Vision(Gemini)으로 좌표를 찍어서 CDP 클릭한다.
     * DOM 클릭은 스마트스토어 SPA의 이벤트 핸들러를 제대로 트리거하지 못하는 문제가 있다.
     */
    private ActionInstructionResponse buildExplicitSmartstoreOptionInstruction(
            String runId,
            int stepIndex,
            CommandSession commandSession,
            PageSnapshotRequest snapshot,
            AgentRunActionResultRequest previousActionResult,
            String selectedOptionValue,
            int optionPresenceScrollCount
    ) {
        if (snapshot == null
                || selectedOptionValue == null
                || selectedOptionValue.isBlank()) {
            return null;
        }

        String actionId = buildActionId(runId, stepIndex);
        String normalizedSelected = normalize(selectedOptionValue);
        boolean previousScreenshotCaptured = previousActionResult != null
                && previousActionResult.toolResult() != null
                && previousActionResult.toolResult().screenshot() != null;
        BrowserActionType previousAction = previousActionResult != null ? previousActionResult.action() : null;
        ActionExecutionStatus previousStatus = previousActionResult != null ? previousActionResult.status() : null;
        boolean previousScrollSuccess = previousAction == BrowserActionType.SCROLL
                && previousStatus == ActionExecutionStatus.SUCCESS;

        // optionGroups가 없으면 바로 Vision으로 전체 화면 분석
        if (snapshot.optionGroups() == null || snapshot.optionGroups().isEmpty()) {
            if (previousScreenshotCaptured) {
                return analyzeSmartstoreOptionSelectionVision(
                        runId,
                        stepIndex,
                        actionId,
                        commandSession,
                        snapshot,
                        previousActionResult,
                        selectedOptionValue,
                        optionPresenceScrollCount,
                        null
                );
            }
            if (previousScrollSuccess) {
                log.info("[AgentStepPlannerService] optionGroups 없음 + 스크롤 완료 → Vision 옵션 선택 캡처 요청 - selectedOption={}",
                        selectedOptionValue);
                return ActionInstructionResponse.useTool(
                        stepIndex,
                        actionId,
                        "CAPTURE_VISIBLE_TAB",
                        java.util.Map.of(
                                "format", "png",
                                "mode", "SMARTSTORE_OPTION_SELECTION",
                                "selectedOptionValue", selectedOptionValue
                        )
                );
            }
            if (optionPresenceScrollCount < MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS) {
                log.info("[AgentStepPlannerService] optionGroups 없음 → 옵션 선택 대상 탐색 SCROLL - selectedOption={}, scrollCount={}/{}",
                        selectedOptionValue, optionPresenceScrollCount + 1, MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS);
                return ActionInstructionResponse.scroll(stepIndex, actionId, "500");
            }
            log.warn("[AgentStepPlannerService] optionGroups 없음 + 스크롤 한도 도달 → 옵션 선택 중단 - selectedOption={}", selectedOptionValue);
            return null;
        }

        // 이전 클릭이 opener(토글 열기)였는지 확인
        boolean previousClickWasOpener = previousActionResult != null
                && previousActionResult.status() == ActionExecutionStatus.SUCCESS
                && previousActionResult.action() == BrowserActionType.CLICK;

        for (OptionGroupRequest optionGroup : snapshot.optionGroups()) {
            if (!isRelevantSmartstoreOptionGroup(optionGroup, normalizedSelected)) {
                continue;
            }

            // 이미 선택된 옵션이면 건너뜀
            if (optionGroup.selectedOption() != null
                    && normalize(optionGroup.selectedOption()).contains(normalizedSelected)) {
                log.info("[AgentStepPlannerService] Smartstore 옵션이 이미 선택된 상태 확인 - group={}, selectedOption={}",
                        optionGroup.groupName(), selectedOptionValue);
                return null;
            }

            Optional<InteractiveElementRequest> visibleOption = findVisibleSmartstoreOptionElement(snapshot, optionGroup, selectedOptionValue);
            if (visibleOption.isPresent()) {
                log.info("[AgentStepPlannerService] Smartstore 옵션 DOM 클릭 - group={}, selectedOption={}, nodeId={}, label={}",
                        optionGroup.groupName(), selectedOptionValue, visibleOption.get().nodeId(), visibleOption.get().labelText());
                return ActionInstructionResponse.click(stepIndex, actionId, visibleOption.get());
            }

            // Step 1: opener(토글 버튼) DOM 클릭으로 드롭다운 열기
            // 이전 클릭이 성공한 CLICK이 아닐 때만 opener 시도 (이미 열려있으면 Vision으로 진행)
            if (!previousClickWasOpener) {
                Optional<InteractiveElementRequest> opener = findSmartstoreOptionOpener(snapshot, optionGroup, selectedOptionValue);
                if (opener.isPresent()) {
                    log.info("[AgentStepPlannerService] Smartstore 옵션 opener 클릭 (드롭다운 열기) - group={}, selectedOption={}, nodeId={}, label={}",
                            optionGroup.groupName(), selectedOptionValue, opener.get().nodeId(), opener.get().labelText());
                    return ActionInstructionResponse.click(stepIndex, actionId, opener.get());
                }
            }

            if (previousScreenshotCaptured) {
                return analyzeSmartstoreOptionSelectionVision(
                        runId,
                        stepIndex,
                        actionId,
                        commandSession,
                        snapshot,
                        previousActionResult,
                        selectedOptionValue,
                        optionPresenceScrollCount,
                        optionGroup.groupName()
                );
            }

            if (previousScrollSuccess || previousClickWasOpener) {
                log.info("[AgentStepPlannerService] Smartstore 옵션 Vision 좌표 요청 - group={}, selectedOption={}",
                        optionGroup.groupName(), selectedOptionValue);
                return ActionInstructionResponse.useTool(
                        stepIndex,
                        actionId,
                        "CAPTURE_VISIBLE_TAB",
                        java.util.Map.of(
                                "format", "png",
                                "mode", "SMARTSTORE_OPTION_SELECTION",
                                "selectedOptionValue", selectedOptionValue,
                                "optionGroup", optionGroup.groupName()
                        )
                );
            }

            if (optionPresenceScrollCount < MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS) {
                log.info("[AgentStepPlannerService] Smartstore 옵션 미노출 → SCROLL 후 재탐색 - group={}, selectedOption={}, scrollCount={}/{}",
                        optionGroup.groupName(), selectedOptionValue, optionPresenceScrollCount + 1, MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS);
                return ActionInstructionResponse.scroll(stepIndex, actionId, "500");
            }

            log.warn("[AgentStepPlannerService] Smartstore 옵션 탐색 한도 도달 - group={}, selectedOption={}",
                    optionGroup.groupName(), selectedOptionValue);
            return null;
        }

        if (previousScreenshotCaptured) {
            return analyzeSmartstoreOptionSelectionVision(
                    runId,
                    stepIndex,
                    actionId,
                    commandSession,
                    snapshot,
                    previousActionResult,
                    selectedOptionValue,
                    optionPresenceScrollCount,
                    null
            );
        }

        if (previousScrollSuccess) {
            log.info("[AgentStepPlannerService] 매칭 그룹 없음 + 스크롤 완료 → Vision 옵션 선택 캡처 요청 - selectedOption={}", selectedOptionValue);
            return ActionInstructionResponse.useTool(
                    stepIndex,
                    actionId,
                    "CAPTURE_VISIBLE_TAB",
                    java.util.Map.of(
                            "format", "png",
                            "mode", "SMARTSTORE_OPTION_SELECTION",
                            "selectedOptionValue", selectedOptionValue
                    )
            );
        }

        if (optionPresenceScrollCount < MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS) {
            log.info("[AgentStepPlannerService] 매칭 그룹 없음 → SCROLL 후 옵션 재탐색 - selectedOption={}, scrollCount={}/{}",
                    selectedOptionValue, optionPresenceScrollCount + 1, MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS);
            return ActionInstructionResponse.scroll(stepIndex, actionId, "500");
        }

        log.warn("[AgentStepPlannerService] 매칭 그룹 없음 + 스크롤 한도 도달 - selectedOption={}", selectedOptionValue);
        return null;
    }

    private ActionInstructionResponse analyzeSmartstoreOptionSelectionVision(
            String runId,
            int stepIndex,
            String actionId,
            CommandSession commandSession,
            PageSnapshotRequest snapshot,
            AgentRunActionResultRequest previousActionResult,
            String selectedOptionValue,
            int optionPresenceScrollCount,
            String optionGroupName
    ) {
        try {
            VisionPlannerInstructionPayload payload = aiVisionPlannerClient.analyze(
                    runId,
                    stepIndex,
                    commandSession.getOriginalCommand(),
                    snapshot == null ? null : snapshot.currentUrl(),
                    previousActionResult,
                    previousActionResult.toolResult().screenshot(),
                    "SMARTSTORE_OPTION_SELECTION",
                    selectedOptionValue
            );

            log.info("[AgentStepPlannerService] Vision planner 결과 - runId={}, stepIndex={}, action={}, x={}, y={}, confidence={}, label={}",
                    runId, stepIndex, payload.action(), payload.viewportX(), payload.viewportY(),
                    payload.confidence(), payload.targetLabel());

            if (payload.confidence() != null && payload.confidence() < MIN_VISION_CONFIDENCE) {
                log.info("[AgentStepPlannerService] Smartstore 옵션 Vision confidence 부족 → SCROLL 재시도 - runId={}, stepIndex={}, confidence={}",
                        runId, stepIndex, payload.confidence());
                return optionPresenceScrollCount < MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS
                        ? ActionInstructionResponse.scroll(stepIndex, actionId, "500")
                        : null;
            }

            if ("CLICK".equals(payload.action()) && payload.viewportX() != null && payload.viewportY() != null) {
                return ActionInstructionResponse.visionClick(
                        stepIndex,
                        actionId,
                        payload.viewportX(),
                        payload.viewportY(),
                        payload.targetLabel()
                );
            }

            if ("WAIT".equals(payload.action())) {
                if (optionPresenceScrollCount < MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS) {
                    log.info("[AgentStepPlannerService] Smartstore 옵션 Vision WAIT → SCROLL 후 재캡처 - runId={}, stepIndex={}, nextScrollCount={}/{}",
                            runId, stepIndex, optionPresenceScrollCount + 1, MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS);
                    return ActionInstructionResponse.scroll(stepIndex, actionId, "500");
                }
                log.warn("[AgentStepPlannerService] Smartstore 옵션 Vision WAIT + 스크롤 한도 도달 - runId={}, stepIndex={}, group={}, selectedOption={}",
                        runId, stepIndex, optionGroupName, selectedOptionValue);
                return null;
            }
        } catch (Exception e) {
            log.warn("[AgentStepPlannerService] Smartstore 옵션 Vision 실패 - runId={}, stepIndex={}, error={}",
                    runId, stepIndex, e.getMessage());
            return optionPresenceScrollCount < MAX_OPTION_PRESENCE_SCROLL_ATTEMPTS
                    ? ActionInstructionResponse.scroll(stepIndex, actionId, "500")
                    : null;
        }
        return null;
    }

    private boolean isSelectedOptionAlreadyApplied(PageSnapshotRequest snapshot, String selectedOptionValue) {
        if (snapshot == null || snapshot.optionGroups() == null || snapshot.optionGroups().isEmpty()
                || selectedOptionValue == null || selectedOptionValue.isBlank()) {
            return false;
        }
        String normalizedSelected = normalize(selectedOptionValue);
        return snapshot.optionGroups().stream()
                .filter(group -> isRelevantSmartstoreOptionGroup(group, normalizedSelected))
                .anyMatch(group -> group.selectedOption() != null
                        && normalize(group.selectedOption()).contains(normalizedSelected));
    }

    private boolean isRelevantSmartstoreOptionGroup(OptionGroupRequest optionGroup, String normalizedSelected) {
        if (optionGroup == null || normalizedSelected == null || normalizedSelected.isBlank()) {
            return false;
        }

        if (optionGroup.options() == null || optionGroup.options().isEmpty()) {
            return false;
        }

        return optionGroup.options().stream()
                .filter(option -> option != null && !option.isBlank())
                .map(this::normalize)
                .anyMatch(normalizedOption -> normalizedOption.contains(normalizedSelected)
                        || normalizedSelected.contains(normalizedOption));
    }

    private Optional<InteractiveElementRequest> findVisibleSmartstoreOptionElement(
            PageSnapshotRequest snapshot,
            OptionGroupRequest optionGroup,
            String selectedOptionValue
    ) {
        if (snapshot == null || snapshot.interactiveElements() == null || selectedOptionValue == null || selectedOptionValue.isBlank()) {
            return Optional.empty();
        }

        String normalizedSelected = normalize(selectedOptionValue);
        String normalizedGroupName = normalize(optionGroup.groupName());

        // 정확한 매칭(exact/startsWith) 우선, 부분 매칭(contains) 후순위
        // 부분 매칭만 하면 "[1]올검(블랙)" 검색 시 "[2]올백(화이트)"도 매칭될 수 있음
        Optional<InteractiveElementRequest> exactMatch = snapshot.interactiveElements().stream()
                .filter(InteractiveElementRequest::isEnabled)
                .filter(InteractiveElementRequest::isVisible)
                .filter(element -> {
                    String label = normalize(element.labelText());
                    if (label == null || label.isBlank()) return false;
                    boolean roleMatch = element.role() == null
                            || element.role().isBlank()
                            || List.of("button", "a", "option", "radio", "link").contains(element.role().toLowerCase());
                    // 정확 매칭: label이 선택값과 동일하거나 선택값으로 시작/끝나는 경우
                    boolean exactLabelMatch = label.equals(normalizedSelected)
                            || label.startsWith(normalizedSelected)
                            || label.endsWith(normalizedSelected);
                    return roleMatch && exactLabelMatch;
                })
                .findFirst();
        if (exactMatch.isPresent()) {
            return exactMatch;
        }

        // 부분 매칭 fallback (그룹명과 겹치지 않는 경우만)
        return snapshot.interactiveElements().stream()
                .filter(InteractiveElementRequest::isEnabled)
                .filter(InteractiveElementRequest::isVisible)
                .filter(element -> {
                    String label = normalize(element.labelText());
                    if (label == null || label.isBlank()) return false;
                    boolean roleMatch = element.role() == null
                            || element.role().isBlank()
                            || List.of("button", "a", "option", "radio", "link").contains(element.role().toLowerCase());
                    boolean partialMatch = label.contains(normalizedSelected)
                            && (normalizedGroupName == null || normalizedGroupName.isBlank() || !label.contains(normalizedGroupName));
                    return roleMatch && partialMatch;
                })
                .findFirst();
    }

    private Optional<InteractiveElementRequest> findSmartstoreOptionOpener(
            PageSnapshotRequest snapshot,
            OptionGroupRequest optionGroup,
            String selectedOptionValue
    ) {
        if (snapshot == null || snapshot.interactiveElements() == null || optionGroup == null) {
            return Optional.empty();
        }

        String normalizedGroupName = normalize(optionGroup.groupName());
        String normalizedSelected = normalize(selectedOptionValue);

        if (normalizedGroupName == null || normalizedGroupName.isBlank()) {
            return Optional.empty();
        }

        return snapshot.interactiveElements().stream()
                .filter(InteractiveElementRequest::isEnabled)
                .filter(InteractiveElementRequest::isVisible)
                .filter(element -> {
                    String role = element.role() == null ? "" : element.role().toLowerCase();
                    if (!(role.equals("button") || role.equals("a") || role.equals("link"))) {
                        return false;
                    }
                    String label = normalize(element.labelText());
                    if (label == null || label.isBlank()) {
                        return false;
                    }
                    return label.contains(normalizedGroupName)
                            && (normalizedSelected == null || normalizedSelected.isBlank() || !label.contains(normalizedSelected));
                })
                .findFirst();
    }

    private Optional<ActionInstructionResponse> buildOptionSelectionInstruction(
            int stepIndex,
            String actionId,
            CommandSession commandSession,
            PageSnapshotRequest snapshot
    ) {
        if (snapshot == null || snapshot.optionGroups().isEmpty()) {
            return Optional.empty();
        }

        String normalizedCommand = normalize(commandSession.getOriginalCommand());

        for (OptionGroupRequest optionGroup : snapshot.optionGroups()) {
            String desiredOption = findDesiredOption(normalizedCommand, optionGroup);

            if (desiredOption == null) {
                continue;
            }

            if (optionGroup.selectedOption() != null
                    && normalize(optionGroup.selectedOption()).contains(normalize(desiredOption))) {
                continue;
            }

            if (optionGroup.nodeId() != null || optionGroup.selector() != null) {
                return Optional.of(ActionInstructionResponse.select(
                        stepIndex,
                        actionId,
                        new ActionTargetResponse(optionGroup.nodeId(), "select", optionGroup.groupName(), optionGroup.selector(), null, null),
                        desiredOption
                ));
            }

            Optional<InteractiveElementRequest> optionElement = findElement(snapshot, null, desiredOption);
            if (optionElement.isPresent()) {
                return Optional.of(ActionInstructionResponse.click(stepIndex, actionId, optionElement.get()));
            }
        }

        return Optional.empty();
    }

    private ActionInstructionResponse convertAiInstruction(
            String runId,
            int stepIndex,
            DomPlannerInstructionPayload payload,
            PageSnapshotRequest snapshot
    ) {
        if (payload == null || payload.action() == null || payload.action().isBlank()) {
            return null;
        }

        String actionId = buildActionId(runId, stepIndex);
        BrowserActionType actionType = BrowserActionType.valueOf(payload.action());

        return switch (actionType) {
            case NAVIGATE -> payload.value() == null || payload.value().isBlank()
                    ? null
                    : ActionInstructionResponse.navigate(stepIndex, actionId, payload.value(), DEFAULT_NAVIGATION_TIMEOUT_MS);
            case CLICK -> resolveAiClickInstruction(stepIndex, actionId, payload, snapshot);
            case INPUT -> resolveAiValueInstruction(stepIndex, actionId, payload, snapshot, BrowserActionType.INPUT);
            case SELECT -> resolveAiValueInstruction(stepIndex, actionId, payload, snapshot, BrowserActionType.SELECT);
            case SCROLL -> ActionInstructionResponse.scroll(stepIndex, actionId, payload.value());
            case WAIT -> ActionInstructionResponse.waitAction(stepIndex, actionId, DEFAULT_WAIT_MS, DEFAULT_NAVIGATION_TIMEOUT_MS);
            case COMPLETE -> ActionInstructionResponse.complete(stepIndex, actionId);
            default -> null;
        };
    }

    private ActionInstructionResponse resolveAiClickInstruction(
            int stepIndex,
            String actionId,
            DomPlannerInstructionPayload payload,
            PageSnapshotRequest snapshot
    ) {
        Optional<InteractiveElementRequest> targetElement = resolveAiTargetElement(payload, snapshot);

        // AI가 CLICK을 반환했지만 nodeId/labelText가 모두 null인 경우:
        // role=a → 카탈로그 판매처 adcr 링크 중 첫 번째를 fallback으로 사용
        if (targetElement.isEmpty()
                && payload.target() != null
                && "a".equalsIgnoreCase(payload.target().role())
                && snapshot != null) {
            targetElement = snapshot.interactiveElements().stream()
                    .filter(InteractiveElementRequest::isEnabled)
                    .filter(InteractiveElementRequest::isVisible)
                    .filter(el -> "a".equalsIgnoreCase(el.role()))
                    .filter(el -> el.href() != null && el.href().contains("cr.shopping.naver.com/adcr"))
                    .findFirst();
            if (targetElement.isPresent()) {
                log.info("[AgentStepPlannerService] AI nodeId/labelText null → adcr href fallback 매칭 - nodeId={}",
                        targetElement.get().nodeId());
            }
        }

        return targetElement.map(element -> ActionInstructionResponse.click(stepIndex, actionId, element)).orElse(null);
    }

    private ActionInstructionResponse resolveAiValueInstruction(
            int stepIndex,
            String actionId,
            DomPlannerInstructionPayload payload,
            PageSnapshotRequest snapshot,
            BrowserActionType actionType
    ) {
        if (payload.value() == null || payload.value().isBlank()) {
            return null;
        }

        Optional<InteractiveElementRequest> targetElement = resolveAiTargetElement(payload, snapshot);
        if (targetElement.isEmpty()) {
            return null;
        }

        ActionTargetResponse target = ActionTargetResponse.from(targetElement.get());
        return new ActionInstructionResponse(stepIndex, actionId, actionType, target, null, payload.value(), null, null, null);
    }

    private Optional<InteractiveElementRequest> resolveAiTargetElement(
            DomPlannerInstructionPayload payload,
            PageSnapshotRequest snapshot
    ) {
        if (payload.target() == null || snapshot == null) {
            return Optional.empty();
        }

        if (payload.target().nodeId() != null && !payload.target().nodeId().isBlank()) {
            Optional<InteractiveElementRequest> byNodeId = snapshot.interactiveElements().stream()
                    .filter(element -> payload.target().nodeId().equals(element.nodeId()))
                    .findFirst();
            if (byNodeId.isPresent()) {
                return byNodeId;
            }
        }

        if (payload.target().labelText() != null && !payload.target().labelText().isBlank()) {
            // role은 AI가 반환한 값("link")과 실제 DOM role("a")이 다를 수 있으므로
            // labelText만으로 매칭한다 (role 필터 제거)
            return findElement(snapshot, null, payload.target().labelText());
        }

        // rawHtml 기반으로 AI가 CSS selector를 반환한 경우: 합성 요소를 생성해 content script가 찾을 수 있게 한다.
        // (interactiveElements에 없는 .blind 요소, 구매버튼 등도 selector로 직접 접근 가능)
        if (payload.target().selector() != null && !payload.target().selector().isBlank()) {
            String selector = payload.target().selector().trim();
            // 단독 태그명만 있는 범용 selector는 거부한다.
            // (예: "button", "a", "div" → document.querySelector('button')은 엉뚱한 첫 번째 요소를 클릭함)
            if (selector.matches("[a-zA-Z]+")) {
                log.warn("[AgentStepPlannerService] AI가 bare tag selector 반환 → 거부 - selector={}", selector);
                return Optional.empty();
            }
            log.info("[AgentStepPlannerService] AI selector 반환 → 합성 InteractiveElementRequest 생성 - selector={}",
                    selector);
            return Optional.of(new InteractiveElementRequest(
                    null,
                    payload.target().role(),
                    payload.target().labelText(),
                    selector,
                    null,
                    true,
                    false
            ));
        }

        return Optional.empty();
    }

    private boolean isAutoPurchaseIntent(CommandSession commandSession) {
        return commandSession.getCommandIntent() == null
                || CommandIntent.AUTO_PURCHASE.name().equals(commandSession.getCommandIntent());
    }

    private boolean isCompletedStatus(CommandSessionStatus status) {
        return List.of(
                CommandSessionStatus.MONITORING_STARTED,
                CommandSessionStatus.PRICE_CHECK_COMPLETED,
                CommandSessionStatus.AUTO_PURCHASE_COMPLETED
        ).contains(status);
    }

    private Optional<InteractiveElementRequest> findElement(PageSnapshotRequest snapshot, String role, String keyword) {
        if (snapshot == null) {
            return Optional.empty();
        }

        return snapshot.interactiveElements().stream()
                .filter(InteractiveElementRequest::isEnabled)
                .filter(InteractiveElementRequest::isVisible)
                .filter(element -> role == null || role.isBlank() || role.equalsIgnoreCase(element.role()))
                .filter(element -> keyword == null || keyword.isBlank()
                        || (element.labelText() != null && element.labelText().contains(keyword)))
                .findFirst();
    }

    private boolean isOptionSelectionNotRequired(PageSnapshotRequest snapshot) {
        return snapshot == null
                || snapshot.optionGroups() == null
                || snapshot.optionGroups().isEmpty();
    }

    private boolean hasVisiblePurchaseButton(PageSnapshotRequest snapshot) {
        return findPurchaseButton(snapshot).isPresent();
    }

    private Optional<InteractiveElementRequest> findPurchaseButton(PageSnapshotRequest snapshot) {
        if (snapshot == null || snapshot.interactiveElements() == null) {
            return Optional.empty();
        }

        log.info("[AgentStepPlannerService] findPurchaseButton 후보 수집 - total={}",
                snapshot.interactiveElements().size());

        List<String> purchaseCandidates = snapshot.interactiveElements().stream()
                .map(element -> String.format("nodeId=%s, role=%s, label=%s, visible=%s, enabled=%s",
                        element.nodeId(),
                        element.role(),
                        element.labelText(),
                        element.isVisible(),
                        element.isEnabled()))
                .filter(text -> text.contains("구매") || text.toLowerCase().contains("buy"))
                .limit(10)
                .toList();

        log.info("[AgentStepPlannerService] findPurchaseButton 구매 유사 후보={}", purchaseCandidates);

        snapshot.interactiveElements().stream()
                .limit(20)
                .forEach(element -> {
                    String role = element.role() == null ? "" : element.role().toLowerCase();
                    String label = element.labelText() == null ? "" : element.labelText().toLowerCase();
                    boolean clickableRole = role.equals("button") || role.equals("a") || role.equals("link");
                    boolean includeKeyword = label.contains("구매하기")
                            || label.contains("바로구매")
                            || label.contains("buy now")
                            || label.contains("지금 구매");
                    boolean excludeKeyword = label.contains("결제하기")
                            || label.contains("주문하기")
                            || label.contains("pay")
                            || label.contains("결제완료")
                            || label.contains("장바구니")
                            || label.contains("add to cart");

                    if (includeKeyword || label.contains("구매")) {
                        log.info("[AgentStepPlannerService] findPurchaseButton 후보 판정 - nodeId={}, role={}, label={}, clickableRole={}, includeKeyword={}, excludeKeyword={}, visible={}, enabled={}",
                                element.nodeId(), role, label, clickableRole, includeKeyword, excludeKeyword, element.isVisible(), element.isEnabled());
                    }
                });

        return snapshot.interactiveElements().stream()
                .filter(InteractiveElementRequest::isEnabled)
                .filter(InteractiveElementRequest::isVisible)
                .filter(element -> {
                    String role = element.role() == null ? "" : element.role().toLowerCase();
                    String label = element.labelText() == null ? "" : element.labelText().toLowerCase();

                    boolean clickableRole = role.equals("button") || role.equals("a") || role.equals("link");
                    boolean includeKeyword = label.contains("구매하기")
                            || label.contains("바로구매")
                            || label.contains("buy now")
                            || label.contains("지금 구매");
                    boolean excludeKeyword = label.contains("결제하기")
                            || label.contains("주문하기")
                            || label.contains("pay")
                            || label.contains("결제완료")
                            || label.contains("장바구니")
                            || label.contains("add to cart");

                    return clickableRole && includeKeyword && !excludeKeyword;
                })
                .findFirst();
    }

    private String findDesiredOption(String normalizedCommand, OptionGroupRequest optionGroup) {
        if (normalizedCommand == null || normalizedCommand.isBlank()) {
            return null;
        }

        return optionGroup.options().stream()
                .filter(option -> option != null && !option.isBlank())
                .filter(this::isMeaningfulOption)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .filter(option -> normalizedCommand.contains(normalize(option)))
                .findFirst()
                .orElse(null);
    }

    private boolean isMeaningfulOption(String option) {
        String normalized = normalize(option);
        if (normalized == null || normalized.isBlank()) {
            return false;
        }

        return normalized.length() > 1 || normalized.chars().allMatch(Character::isDigit);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        return value.replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * 세션의 platform 필드를 보고 DB에서 PlatformConfig를 조회한다.
     * platform이 null이거나 설정이 없으면 ALIEXPRESS를 기본값으로 사용한다.
     */
    private PlatformConfig resolvePlatformConfig(CommandSession commandSession) {
        PlatformType platformType = PlatformType.ALIEXPRESS; // 기본값
        if (commandSession.getCommandIntent() != null) {
            // parsedCommand의 platform은 CommandSession에 직접 저장되지 않으므로
            // validationResult의 triggered 상품 플랫폼으로 추론한다
        }
        // commandSession에 platform 필드가 있으면 우선 사용
        try {
            String parsedPlatform = extractPlatformFromSession(commandSession);
            if (parsedPlatform != null) {
                platformType = PlatformType.valueOf(parsedPlatform);
            }
        } catch (IllegalArgumentException ignored) {
            // 알 수 없는 platform 값이면 기본값 사용
        }

        PlatformType finalPlatformType = platformType;
        return platformConfigService.findByPlatform(platformType)
                .orElseGet(() -> {
                    log.warn("플랫폼 설정을 찾을 수 없습니다. platform={}, ALIEXPRESS로 폴백합니다.", finalPlatformType);
                    return platformConfigService.findByPlatform(PlatformType.ALIEXPRESS)
                            .orElseThrow(() -> new IllegalStateException("ALIEXPRESS 플랫폼 설정이 DB에 없습니다."));
                });
    }

    /**
     * CommandSession의 validationResult에서 플랫폼 정보를 추출한다.
     * triggered 상품의 platform 필드를 우선 사용한다.
     */
    private String extractPlatformFromSession(CommandSession commandSession) {
        if (commandSession.getPlatform() != null && !commandSession.getPlatform().isBlank()) {
            return commandSession.getPlatform();
        }

        SelectionValidationResultResponse validationResult = parseValidationResult(commandSession.getValidationResultJson());
        if (validationResult == null) {
            return null;
        }
        // triggered 상품이 있으면 그 플랫폼 사용
        if (validationResult.triggeredProducts() != null && !validationResult.triggeredProducts().isEmpty()) {
            return validationResult.triggeredProducts().get(0).platform();
        }
        // monitoring 상품 플랫폼 사용
        if (validationResult.monitoringProducts() != null && !validationResult.monitoringProducts().isEmpty()) {
            return validationResult.monitoringProducts().get(0).platform();
        }
        return null;
    }

    /**
     * DB에서 가져온 플랫폼 설정으로 검색 URL을 생성한다.
     * 하드코딩된 aliexpress URL을 대체한다.
     */
    private String buildSearchUrl(PlatformConfig platformConfig, CommandSession commandSession, ProductCandidateResponse targetProduct) {
        String searchKeyword = targetProduct != null
                && targetProduct.searchKeyword() != null
                && !targetProduct.searchKeyword().isBlank()
                ? targetProduct.searchKeyword()
                : commandSession.getOriginalCommand();
        return platformConfig.buildSearchUrl(searchKeyword);
    }

    private ProductCandidateResponse resolveTargetProduct(CommandSession commandSession) {
        SelectionValidationResultResponse validationResult = parseValidationResult(commandSession.getValidationResultJson());

        if (validationResult != null && !validationResult.triggeredProducts().isEmpty()) {
            return validationResult.triggeredProducts().get(0);
        }

        List<ProductCandidateResponse> candidates = parseCandidates(commandSession.getCandidatesJson());
        List<String> selectedProductIds = parseSelectedProductIds(commandSession.getSelectedProductIdsJson());

        if (!selectedProductIds.isEmpty()) {
            return candidates.stream()
                    .filter(candidate -> selectedProductIds.contains(candidate.productId()))
                    .findFirst()
                    .orElse(candidates.isEmpty() ? null : candidates.get(0));
        }

        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private String buildClarificationSummary(CommandSession commandSession) {
        if (commandSession.getClarificationMessage() != null && !commandSession.getClarificationMessage().isBlank()) {
            return commandSession.getClarificationMessage();
        }
        return "웹 앱에서 누락 정보를 입력한 뒤 다시 실행할 수 있습니다.";
    }

    private String buildProductSelectionSummary(CommandSession commandSession) {
        List<ProductCandidateResponse> candidates = parseCandidates(commandSession.getCandidatesJson());
        String candidateSummary = candidates.stream()
                .limit(3)
                .map(ProductCandidateResponse::title)
                .filter(title -> title != null && !title.isBlank())
                .collect(Collectors.joining(", "));

        if (!candidateSummary.isBlank()) {
            return "웹 앱에서 후보 상품을 선택해주세요. 예: " + candidateSummary;
        }

        if (commandSession.getClarificationMessage() != null && !commandSession.getClarificationMessage().isBlank()) {
            return commandSession.getClarificationMessage();
        }

        return "웹 앱에서 후보 상품 선택 후 다음 단계를 진행할 수 있습니다.";
    }

    private String buildResubscribeConfirmationSummary(CommandSession commandSession) {
        SelectionValidationResultResponse validationResult = parseValidationResult(commandSession.getValidationResultJson());
        if (validationResult != null) {
            if (validationResult.confirmationMessage() != null && !validationResult.confirmationMessage().isBlank()) {
                return validationResult.confirmationMessage();
            }
            if (validationResult.summaryMessage() != null && !validationResult.summaryMessage().isBlank()) {
                return validationResult.summaryMessage();
            }
        }
        return "웹 앱에서 기존 구독 갱신 여부를 확인한 뒤 다시 진행할 수 있습니다.";
    }

    private boolean isSamePage(String currentUrl, String targetUrl) {
        if (currentUrl == null || targetUrl == null) {
            return false;
        }
        return currentUrl.equals(targetUrl) || currentUrl.contains(extractUrlHint(targetUrl));
    }

    private String extractUrlHint(String targetUrl) {
        int queryIndex = targetUrl.indexOf('?');
        return queryIndex >= 0 ? targetUrl.substring(0, queryIndex) : targetUrl;
    }

    private List<ProductCandidateResponse> parseCandidates(String candidatesJson) {
        if (candidatesJson == null || candidatesJson.isBlank()) {
            return List.of();
        }

        try {
            return OBJECT_MAPPER.readValue(candidatesJson, new TypeReference<List<ProductCandidateResponse>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private List<String> parseSelectedProductIds(String selectedProductIdsJson) {
        if (selectedProductIdsJson == null || selectedProductIdsJson.isBlank()) {
            return List.of();
        }

        try {
            return OBJECT_MAPPER.readValue(selectedProductIdsJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private SelectionValidationResultResponse parseValidationResult(String validationResultJson) {
        if (validationResultJson == null || validationResultJson.isBlank()) {
            return null;
        }

        try {
            return OBJECT_MAPPER.readValue(validationResultJson, SelectionValidationResultResponse.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String buildActionId(String runId, int stepIndex) {
        return runId + "-step-" + stepIndex;
    }
}
