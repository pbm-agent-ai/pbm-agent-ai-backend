package com.pbm.command.dto.response;

import java.util.List;

/**
 * 다중 선택 상품 실시간 검증 결과 응답 DTO.
 *
 * 역할: price-service가 선택 상품들을 단건 재조회한 뒤, 즉시 조건 충족 상품과
 *       모니터링 등록 상품을 구분한 결과를 command-session 응답에 담아 노출한다.
 * 동작: 각 리스트는 null 대신 빈 리스트로 보정하여 프론트가 바로 렌더링할 수 있게 한다.
 * 연관: CommandSessionResponse, PriceValidationResultConsumer.
 */
public record SelectionValidationResultResponse(
        List<ProductCandidateResponse> triggeredProducts,
        List<ProductCandidateResponse> monitoringProducts,
        String purchasedProductId,
        String summaryMessage,
        boolean confirmationRequired,
        List<ProductCandidateResponse> duplicateProducts,
        String confirmationMessage
) {
    public SelectionValidationResultResponse {
        triggeredProducts = triggeredProducts == null ? List.of() : List.copyOf(triggeredProducts);
        monitoringProducts = monitoringProducts == null ? List.of() : List.copyOf(monitoringProducts);
        duplicateProducts = duplicateProducts == null ? List.of() : List.copyOf(duplicateProducts);
    }
}
