package com.pbm.command.dto.event;

/**
 * 옵션 선택 응답 이벤트 Payload.
 *
 * @param userId        사용자 ID
 * @param runId         AgentRun 식별자 (어떤 run의 옵션인지 매칭)
 * @param selectedValue 사용자가 선택한 옵션 값 (예: "블랙", "M")
 */
public record OptionSelectionResponsePayload(
        Long userId,
        String runId,
        String selectedValue
) {
}
