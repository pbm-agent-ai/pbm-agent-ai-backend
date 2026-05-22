package com.pbm.command.dto.request;

/**
 * snapshot의 상호작용 가능 요소 요청 DTO.
 *
 * 역할: Extension이 현재 화면의 버튼/입력창 등 실행 가능한 요소를 Backend에 전달한다.
 * 동작: nodeId는 해당 snapshot 시점에서만 유효한 식별자이며, Backend는 이 값을 target 지시에 재사용한다.
 * 연관: PageSnapshotRequest.
 */
public record InteractiveElementRequest(
        String nodeId,
        String role,
        String labelText,
        String selector,
        String href,       // <a> 태그의 href (상품 URL productId 매칭에 사용)
        boolean isVisible,
        Boolean disabled
) {
    public boolean isEnabled() {
        return !Boolean.TRUE.equals(disabled);
    }
}
