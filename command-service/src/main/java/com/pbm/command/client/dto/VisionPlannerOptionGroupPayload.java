package com.pbm.command.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * vision planner가 추출한 옵션 그룹 DTO.
 *
 * 역할: 스크린샷만 보고 파악한 옵션 목록을 command-service로 전달한다.
 * 동작: groupName/selectedOption/options를 record로 불변 전달한다.
 * 연관: VisionPlannerInstructionPayload, OptionSelectionRequestPayload.
 */
public record VisionPlannerOptionGroupPayload(
        @JsonProperty("group_name") String groupName,
        @JsonProperty("selected_option") String selectedOption,
        List<String> options
) {
    public VisionPlannerOptionGroupPayload {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
