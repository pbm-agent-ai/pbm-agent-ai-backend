package com.pbm.command.dto.request;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Extension 현재 페이지 snapshot 요청 DTO.
 *
 * 역할: Backend planner가 다음 브라우저 액션을 결정할 때 사용할 현재 페이지 요약 정보를 전달한다.
 * 동작: 전체 DOM 대신 현재 URL, 요약 텍스트, interactiveElements, 옵션 정보만 전달한다.
 * 연관: AgentRunStepRequest, AgentStepPlannerService.
 */
public record PageSnapshotRequest(
        String currentUrl,
        String title,
        String visibleTextSummary,
        List<InteractiveElementRequest> interactiveElements,
        List<OptionGroupRequest> optionGroups,
        List<String> priceCandidates,
        List<String> currencyCandidates,
        LocalDateTime capturedAt
) {
    public PageSnapshotRequest {
        interactiveElements = interactiveElements == null ? List.of() : List.copyOf(interactiveElements);
        optionGroups = optionGroups == null ? List.of() : List.copyOf(optionGroups);
        priceCandidates = priceCandidates == null ? List.of() : List.copyOf(priceCandidates);
        currencyCandidates = currencyCandidates == null ? List.of() : List.copyOf(currencyCandidates);
    }
}
