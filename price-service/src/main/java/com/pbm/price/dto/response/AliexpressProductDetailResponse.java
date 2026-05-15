package com.pbm.price.dto.response;

/**
 * AliExpress 상품 단건 조회 응답 DTO.
 *
 * 역할: external-api-service의 /api/v1/aliexpress/products/{productId} 응답을
 *       price-service에서 역직렬화할 때 사용한다.
 * 동작: product가 null이면 해당 상품을 찾지 못한 것으로 간주한다.
 * 연관: ExternalApiClient, SubscriptionMonitoringService.
 */
public record AliexpressProductDetailResponse(
        AliExpressShoppingItem product
) {
}
