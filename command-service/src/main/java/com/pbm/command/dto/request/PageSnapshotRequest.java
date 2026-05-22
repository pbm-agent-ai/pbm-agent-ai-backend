package com.pbm.command.dto.request;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Extension 현재 페이지 snapshot 요청 DTO.
 *
 * 역할: Backend planner가 다음 브라우저 액션을 결정할 때 사용할 현재 페이지 요약 정보를 전달한다.
 * 동작: interactiveElements 전처리 외에 rawHtml(전체 DOM)도 함께 전달해 AI가 직접 파싱할 수 있게 한다.
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
        /** 전체 페이지 HTML (전처리 없이 AI에게 직접 전달, 최대 80KB) */
        String rawHtml,
        LocalDateTime capturedAt
) {
    public PageSnapshotRequest {
        interactiveElements = interactiveElements == null ? List.of() : List.copyOf(interactiveElements);
        optionGroups = optionGroups == null ? List.of() : List.copyOf(optionGroups);
        priceCandidates = priceCandidates == null ? List.of() : List.copyOf(priceCandidates);
        currencyCandidates = currencyCandidates == null ? List.of() : List.copyOf(currencyCandidates);
    }
}
