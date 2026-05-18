package com.pbm.price.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 쇼핑 검색 결과 응답 DTO
 * record 클래스로 불변 객체 보장
 *
 * @param title      상품명 (HTML 태그 포함 가능)
 * @param lprice     최저가 (원 또는 USD)
 * @param hprice     최고가 (원 또는 USD, 없을 수 있음)
 * @param mallName   쇼핑몰 이름
 * @param productUrl 상품 상세 링크
 * @param imageUrl   대표 상품 이미지 URL
 * @param currency   통화 코드 ("KRW", "USD" 등)
 * @param productId  플랫폼 상품 식별자 (예: 네이버 productId, AliExpress product_id)
 */
@Schema(description = "플랫폼 검색 결과 공통 응답 DTO")
public record SearchResponse(
        @Schema(description = "상품명", example = "QCY T13 ANC 블루투스 무선 이어폰")
        String title,
        @Schema(description = "최저가", example = "24300")
        String lprice,
        @Schema(description = "원가 또는 최고가", example = "29900")
        String hprice,
        @Schema(description = "쇼핑몰 이름", example = "QCY Official Store")
        String mallName,
        @Schema(description = "상품 상세 URL", example = "https://ko.aliexpress.com/item/1005006918061844.html")
        String productUrl,
        @Schema(description = "상품 대표 이미지 URL")
        String imageUrl,
        @Schema(description = "통화 코드", example = "KRW")
        String currency,
        @Schema(description = "플랫폼 상품 식별자", example = "1005006918061844")
        String productId
) {
}
