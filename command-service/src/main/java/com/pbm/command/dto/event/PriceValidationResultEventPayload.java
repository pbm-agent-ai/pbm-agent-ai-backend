package com.pbm.command.dto.event;

import java.util.List;

/**
 * 선택 상품 실시간 검증 결과 페이로드 DTO.
 *
 * 역할: 즉시 조건 충족 상품(triggered)과 이후 모니터링 등록 상품(monitoring)을
 *       분리하여 command-service 세션에 기록할 수 있게 한다.
 * 동작: nextStatus는 command-session이 최종적으로 도달해야 할 상태를 문자열로 전달한다.
 * 연관: PriceValidationResultEvent, ProductCandidateDto.
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
