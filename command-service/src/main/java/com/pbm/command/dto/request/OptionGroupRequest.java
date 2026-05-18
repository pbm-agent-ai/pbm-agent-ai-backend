package com.pbm.command.dto.request;

import java.util.List;

/**
 * 페이지 옵션 그룹 요청 DTO.
 *
 * 역할: 색상, 사이즈 같은 선택 옵션 묶음을 snapshot에 담아 Backend planner가 참고할 수 있게 한다.
 * 동작: options는 null 대신 빈 리스트로 보정하여 downstream에서 null 체크를 줄인다.
 * 연관: PageSnapshotRequest.
 */
public record OptionGroupRequest(
        String groupName,
        String nodeId,
        String selector,
        List<String> options,
        String selectedOption
) {
    public OptionGroupRequest {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
