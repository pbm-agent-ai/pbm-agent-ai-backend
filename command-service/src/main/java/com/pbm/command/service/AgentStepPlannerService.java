package com.pbm.command.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.client.AiDomPlannerClient;
import com.pbm.command.client.AiVisionPlannerClient;
import com.pbm.command.client.dto.DomPlannerInstructionPayload;
import com.pbm.command.client.dto.VisionPlannerInstructionPayload;
import com.pbm.command.domain.CommandIntent;
import com.pbm.command.domain.BrowserActionType;
import com.pbm.command.domain.CommandSession;
import com.pbm.command.domain.CommandSessionStatus;
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

    private final AiDomPlannerClient aiDomPlannerClient;
    private final AiVisionPlannerClient aiVisionPlannerClient;

    public AgentStepPlannerService(AiDomPlannerClient aiDomPlannerClient, AiVisionPlannerClient aiVisionPlannerClient) {
        this.aiDomPlannerClient = aiDomPlannerClient;
        this.aiVisionPlannerClient = aiVisionPlannerClient;
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
        return planNextAction(runId, stepIndex, commandSession, snapshot, null);
    }

    public ActionInstructionResponse planNextAction(
            String runId,
            int stepIndex,
            CommandSession commandSession,
            PageSnapshotRequest snapshot,
            AgentRunActionResultRequest previousActionResult
    ) {
        ProductCandidateResponse targetProduct = resolveTargetProduct(commandSession);

        String actionId = buildActionId(runId, stepIndex);
        String currentUrl = snapshot == null ? null : snapshot.currentUrl();

        if (commandSession.getStatus() == CommandSessionStatus.PRE_SEARCH_CLARIFICATION) {
            return ActionInstructionResponse.awaitApproval(
                    stepIndex,
                    actionId,
                    buildClarificationSummary(commandSession),
                    DEFAULT_APPROVAL_TIMEOUT_MS
            );
        }

        if (commandSession.getStatus() == CommandSessionStatus.PRODUCT_SELECTION_REQUIRED) {
            return ActionInstructionResponse.awaitApproval(
                    stepIndex,
                    actionId,
                    buildProductSelectionSummary(commandSession),
                    DEFAULT_APPROVAL_TIMEOUT_MS
            );
        }

        if (commandSession.getStatus() == CommandSessionStatus.RESUBSCRIBE_CONFIRMATION_REQUIRED) {
            return ActionInstructionResponse.awaitApproval(
                    stepIndex,
                    actionId,
                    buildResubscribeConfirmationSummary(commandSession),
                    DEFAULT_APPROVAL_TIMEOUT_MS
            );
        }

        if (commandSession.getStatus() == CommandSessionStatus.PRICE_VALIDATING) {
            return ActionInstructionResponse.waitAction(stepIndex, actionId, DEFAULT_WAIT_MS, DEFAULT_NAVIGATION_TIMEOUT_MS);
        }

        if (isCompletedStatus(commandSession.getStatus())) {
            return ActionInstructionResponse.complete(stepIndex, actionId);
        }

        if (!isAutoPurchaseIntent(commandSession)) {
            return ActionInstructionResponse.complete(stepIndex, actionId);
        }

        ActionInstructionResponse visionInstruction = buildVisionFallbackInstruction(
                runId,
                stepIndex,
                commandSession,
                snapshot,
                previousActionResult
        );
        if (visionInstruction != null) {
            return visionInstruction;
        }

        ActionInstructionResponse aiInstruction = buildAiDomInstruction(runId, stepIndex, commandSession, snapshot, targetProduct);
        if (aiInstruction != null) {
            return aiInstruction;
        }

        Optional<ActionInstructionResponse> optionInstruction = buildOptionSelectionInstruction(
                stepIndex,
                actionId,
                commandSession,
                snapshot
        );
        if (optionInstruction.isPresent()) {
            return optionInstruction.get();
        }

        if (targetProduct != null && targetProduct.productUrl() != null && !targetProduct.productUrl().isBlank()) {
            if (!isSamePage(currentUrl, targetProduct.productUrl())) {
                return ActionInstructionResponse.navigate(
                        stepIndex,
                        actionId,
                        targetProduct.productUrl(),
                        DEFAULT_NAVIGATION_TIMEOUT_MS
                );
            }
        }

        if (currentUrl == null || !currentUrl.contains("aliexpress.com")) {
            return ActionInstructionResponse.navigate(
                    stepIndex,
                    actionId,
                    buildAliExpressSearchUrl(commandSession, targetProduct),
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

    private ActionInstructionResponse buildVisionFallbackInstruction(
            String runId,
            int stepIndex,
            CommandSession commandSession,
            PageSnapshotRequest snapshot,
            AgentRunActionResultRequest previousActionResult
    ) {
        if (previousActionResult == null) {
            return null;
        }

        if (previousActionResult.toolResult() == null || previousActionResult.toolResult().screenshot() == null) {
            if (previousActionResult.status() == com.pbm.command.domain.ActionExecutionStatus.FAILURE
                    && previousActionResult.errorCode() == com.pbm.command.domain.ActionErrorCode.ELEMENT_NOT_FOUND) {
                return ActionInstructionResponse.useTool(stepIndex, buildActionId(runId, stepIndex), "CAPTURE_VISIBLE_TAB", java.util.Map.of("format", "png"));
            }
            return null;
        }

        try {
            VisionPlannerInstructionPayload payload = aiVisionPlannerClient.analyze(
                    commandSession.getOriginalCommand(),
                    snapshot == null ? null : snapshot.currentUrl(),
                    previousActionResult,
                    previousActionResult.toolResult().screenshot()
            );

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

            if ("WAIT".equals(payload.action())) {
                return ActionInstructionResponse.waitAction(stepIndex, buildActionId(runId, stepIndex), DEFAULT_WAIT_MS, DEFAULT_NAVIGATION_TIMEOUT_MS);
            }
        } catch (Exception e) {
            log.warn("[AgentStepPlannerService] Vision planner 실패 - runId={}, stepIndex={}, error={}", runId, stepIndex, e.getMessage());
        }

        return null;
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

        try {
            DomPlannerInstructionPayload payload = aiDomPlannerClient.plan(commandSession, snapshot, targetProduct);
            if (payload.confidence() != null && payload.confidence() < MIN_AI_CONFIDENCE) {
                log.info("[AgentStepPlannerService] AI planner confidence 부족으로 rule-based fallback - runId={}, stepIndex={}, confidence={}, reason={}",
                        runId, stepIndex, payload.confidence(), payload.reason());
                return null;
            }

            return convertAiInstruction(runId, stepIndex, payload, snapshot);
        } catch (Exception e) {
            log.warn("[AgentStepPlannerService] AI planner 실패로 rule-based fallback - runId={}, stepIndex={}, error={}",
                    runId, stepIndex, e.getMessage());
            return null;
        }
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
            return findElement(snapshot, payload.target().role(), payload.target().labelText());
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

    private String buildAliExpressSearchUrl(CommandSession commandSession, ProductCandidateResponse targetProduct) {
        String searchKeyword = targetProduct != null && targetProduct.searchKeyword() != null && !targetProduct.searchKeyword().isBlank()
                ? targetProduct.searchKeyword()
                : commandSession.getOriginalCommand();
        String keyword = searchKeyword == null ? "" : searchKeyword.trim().replace(" ", "+");
        return "https://www.aliexpress.com/wholesale?SearchText=" + keyword;
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
