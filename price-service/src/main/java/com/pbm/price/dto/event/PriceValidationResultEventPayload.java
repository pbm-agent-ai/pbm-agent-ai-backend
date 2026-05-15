package com.pbm.price.dto.event;

import java.util.List;

/**
 * 선택 상품 검증 결과 페이로드 DTO.
 *
 * 역할: 즉시 조건 충족 상품 목록과 스케줄링 등록 상품 목록을 분리해서
 *       command-service에 전달한다.
 * 동작: nextStatus는 command-session이 어떤 상태로 완료되어야 하는지를 문자열로 담는다.
 * 연관: PriceValidationResultEvent.
 */
public record PriceValidationResultEventPayload(
        String commandId,
        String nextStatus,
        List<ProductCandidateDto> triggeredProducts,
        List<ProductCandidateDto> monitoringProducts,
        String purchasedProductId,
        String summaryMessage,
        boolean confirmationRequired,
        List<ProductCandidateDto> duplicateProducts,
        String confirmationMessage
) {
}
