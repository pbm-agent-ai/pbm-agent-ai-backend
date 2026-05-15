package com.pbm.price.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * external-api-service AliExpress 검색 응답 개별 상품 항목 DTO
 * external-api-service의 AliExpress 상품 스키마와 1:1 매핑
 *
 * @param product_title        상품명
 * @param sale_price            원래 판매가 (USD 등 원화폐)
 * @param target_sale_price     KRW 변환 판매가 (target_currency 기준)
 * @param target_original_price KRW 변환 원가 (최고가 매핑용)
 * @param shop_name             쇼핑몰(스토어) 이름
 * @param product_detail_url    상품 상세 링크
 * @param product_id            상품 ID
 * @param product_main_image_url 상품 대표 이미지 URL
 * @param evaluate_rate          평점 (0~100)
 * @param first_level_category_id 1차 카테고리 ID
 * @param first_level_category_name 1차 카테고리명
 * @param second_level_category_id 카테고리 ID
 * @param second_level_category_name 카테고리명
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AliExpressShoppingItem(
        String product_title,
        String sale_price,
        String target_sale_price,
        String target_original_price,
        String shop_name,
        String product_detail_url,
        String product_id,
        String product_main_image_url,
        String evaluate_rate,
        String first_level_category_id,
        String first_level_category_name,
        String second_level_category_id,
        String second_level_category_name
) {
}