package com.pbm.price.dto.response;

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
public record SearchResponse(
        String title,
        String lprice,
        String hprice,
        String mallName,
        String productUrl,
        String imageUrl,
        String currency,
        String productId
) {
}
