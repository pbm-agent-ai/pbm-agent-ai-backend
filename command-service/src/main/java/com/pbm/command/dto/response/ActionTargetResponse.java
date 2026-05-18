package com.pbm.command.dto.response;

import com.pbm.command.dto.request.InteractiveElementRequest;

/**
 * Backend가 Extension에 내려주는 액션 타겟 응답 DTO.
 *
 * 역할: click/input/select 액션이 어떤 요소를 대상으로 해야 하는지 알려준다.
 * 동작: nodeId를 기준으로 하고, role/labelText/selector는 fallback 탐색을 위한 보조 정보다.
 * 연관: ActionInstructionResponse.
 */
public record ActionTargetResponse(
        String nodeId,
        String role,
        String labelText,
        String selector,
        Double viewportX,
        Double viewportY
) {
    public static ActionTargetResponse from(InteractiveElementRequest element) {
        return new ActionTargetResponse(
                element.nodeId(),
                element.role(),
                element.labelText(),
                element.selector(),
                null,
                null
        );
    }

    public static ActionTargetResponse vision(Double viewportX, Double viewportY, String labelText) {
        return new ActionTargetResponse(null, "vision", labelText, null, viewportX, viewportY);
    }
}
