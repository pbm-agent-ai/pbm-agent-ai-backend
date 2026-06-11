package com.pbm.notification.dto;

import com.pbm.notification.dto.event.OptionSelectionRequestEvent;

import java.time.Instant;
import java.util.List;

/**
 * 텔레그램 옵션 선택 응답을 기다리는 대기 요청 정보.
 * PendingOptionStore에 chatId 기준으로 저장되며 3분 후 자동 만료된다.
 *
 * @param runId        AgentRun 식별자
 * @param userId       사용자 ID
 * @param optionGroups 옵션 그룹 목록 (번호 매칭에 사용)
 * @param expiresAt    만료 시각
 */
public record PendingOptionSelection(
        String runId,
        Long userId,
        List<OptionSelectionRequestEvent.OptionGroup> optionGroups,
        Instant expiresAt
) {
}
