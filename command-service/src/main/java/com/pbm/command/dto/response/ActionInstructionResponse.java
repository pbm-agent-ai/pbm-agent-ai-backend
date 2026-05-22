package com.pbm.command.dto.response;

import com.pbm.command.domain.BrowserActionType;
import com.pbm.command.dto.request.InteractiveElementRequest;

import java.util.Map;

/**
 * Backend가 Extension에 내려주는 다음 액션 응답 DTO.
 *
 * 역할: 현재 step에서 Extension이 무엇을 실행해야 하는지 1개의 instruction으로 전달한다.
 * 동작: 계약상 1 step = 1 action 이므로 stepIndex마다 instruction 하나만 존재한다.
 * 연관: AgentRunStepResponse, AgentStepPlannerService.
 */
public record ActionInstructionResponse(
        Integer stepIndex,
        String actionId,
        BrowserActionType action,
        ActionTargetResponse target,
        ToolRequestResponse toolRequest,
        String value,
        Integer waitMs,
        Integer timeoutMs,
        ApprovalContextResponse approvalContext
) {
    public static ActionInstructionResponse navigate(Integer stepIndex, String actionId, String targetUrl, Integer timeoutMs) {
        return new ActionInstructionResponse(stepIndex, actionId, BrowserActionType.NAVIGATE, null, null, targetUrl, null, timeoutMs, null);
    }

    public static ActionInstructionResponse click(Integer stepIndex, String actionId, InteractiveElementRequest element) {
        return new ActionInstructionResponse(stepIndex, actionId, BrowserActionType.CLICK, ActionTargetResponse.from(element), null, null, null, null, null);
    }

    public static ActionInstructionResponse input(Integer stepIndex, String actionId, InteractiveElementRequest element, String value) {
        return new ActionInstructionResponse(stepIndex, actionId, BrowserActionType.INPUT, ActionTargetResponse.from(element), null, value, null, null, null);
    }

    public static ActionInstructionResponse select(Integer stepIndex, String actionId, ActionTargetResponse target, String value) {
        return new ActionInstructionResponse(stepIndex, actionId, BrowserActionType.SELECT, target, null, value, null, null, null);
    }

    public static ActionInstructionResponse waitAction(Integer stepIndex, String actionId, Integer waitMs, Integer timeoutMs) {
        return new ActionInstructionResponse(stepIndex, actionId, BrowserActionType.WAIT, null, null, null, waitMs, timeoutMs, null);
    }

    public static ActionInstructionResponse scroll(Integer stepIndex, String actionId, String scrollOffset) {
        return new ActionInstructionResponse(stepIndex, actionId, BrowserActionType.SCROLL, null, null, scrollOffset, null, null, null);
    }

    public static ActionInstructionResponse useTool(Integer stepIndex, String actionId, String toolName, Map<String, Object> input) {
        return new ActionInstructionResponse(
                stepIndex,
                actionId,
                BrowserActionType.USE_TOOL,
                null,
                new ToolRequestResponse(toolName, input),
                null,
                null,
                null,
                null
        );
    }

    public static ActionInstructionResponse visionClick(Integer stepIndex, String actionId, Double viewportX, Double viewportY, String labelText) {
        return new ActionInstructionResponse(
                stepIndex,
                actionId,
                BrowserActionType.CLICK,
                ActionTargetResponse.vision(viewportX, viewportY, labelText),
                null,
                null,
                null,
                null,
                null
        );
    }

    public static ActionInstructionResponse complete(Integer stepIndex, String actionId) {
        return new ActionInstructionResponse(stepIndex, actionId, BrowserActionType.COMPLETE, null, null, null, null, null, null);
    }

    public static ActionInstructionResponse abort(Integer stepIndex, String actionId, String reason) {
        return new ActionInstructionResponse(stepIndex, actionId, BrowserActionType.ABORT, null, null, reason, null, null, null);
    }

    public static ActionInstructionResponse awaitApproval(Integer stepIndex, String actionId, String summaryText, Integer timeoutMs) {
        return new ActionInstructionResponse(
                stepIndex,
                actionId,
                BrowserActionType.AWAIT_APPROVAL,
                null,
                null,
                null,
                null,
                null,
                new ApprovalContextResponse(summaryText, timeoutMs)
        );
    }

    public record ToolRequestResponse(
            String name,
            Map<String, Object> input
    ) {
    }
}
