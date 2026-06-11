package com.pbm.command.dto.event;

import java.util.List;

/**
 * 옵션 선택 요청 이벤트 Payload.
 *
 * @param userId       사용자 ID (텔레그램 chatId 조회에 사용)
 * @param runId        AgentRun 식별자 (응답 시 매칭용)
 * @param productName  상품명 (텔레그램 메시지에 표시)
 * @param optionGroups 상품 옵션 목록 (색상, 사이즈 등)
 */
public record OptionSelectionRequestPayload(
        Long userId,
        String runId,
        String productName,
        List<OptionGroupPayload> optionGroups
) {

    /**
     * 개별 옵션 그룹 (예: 색상 그룹, 사이즈 그룹).
     *
     * @param groupName 옵션 그룹명 (예: "색상", "사이즈")
     * @param options   선택 가능한 옵션 값 목록 (예: ["블랙", "화이트", "실버"])
     */
    public record OptionGroupPayload(
            String groupName,
            List<String> options
    ) {
    }
}
