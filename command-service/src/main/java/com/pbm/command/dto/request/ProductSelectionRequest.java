package com.pbm.command.dto.request;

import java.util.List;

/**
 * 후보 상품 다중 선택 요청 DTO.
 *
 * 역할: PRODUCT_SELECTION_REQUIRED 상태에서 사용자가 선택한 여러 상품의 productId 목록을
 *       command-service로 전달한다.
 * 동작: 빈 리스트나 null 입력은 허용하지 않으며, 각 productId는 trim 후 불변 리스트로 보관한다.
 * 연관: CommandSessionController, CommandExecutionService.
 */
public record ProductSelectionRequest(
        List<String> selectedProductIds,
        Boolean forceResubscribe
) {
    public ProductSelectionRequest(List<String> selectedProductIds) {
        this(selectedProductIds, false);
    }

    public ProductSelectionRequest {
        if (selectedProductIds == null || selectedProductIds.isEmpty()) {
            throw new IllegalArgumentException("selectedProductIds는 최소 1개 이상이어야 합니다.");
        }
        selectedProductIds = selectedProductIds.stream()
                .map(productId -> productId == null ? null : productId.trim())
                .filter(productId -> productId != null && !productId.isBlank())
                .distinct()
                .toList();

        if (selectedProductIds.isEmpty()) {
            throw new IllegalArgumentException("selectedProductIds는 최소 1개 이상이어야 합니다.");
        }

        forceResubscribe = Boolean.TRUE.equals(forceResubscribe);
    }
}
