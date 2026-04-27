package com.pbm.price.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * external-api-service 네이버 쇼핑 검색 응답 개별 상품 항목 DTO
 * external-api-service의 NaverShoppingItem 스키마와 1:1 매핑
 *
 * @param title     상품명
 * @param lprice    최저가 (원)
 * @param hprice    최고가 (원, 빈 문자열 가능)
 * @param mallName  쇼핑몰 이름
 * @param link      상품 상세 링크
 * @param productId 상품 ID
 * @param image     상품 이미지 URL
 * @param maker     제조사
 * @param brand     브랜드
 * @param category1 대분류
 * @param category2 중분류
 * @param category3 소분류
 * @param category4 세분류
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverShoppingItem(
        String title,
        String lprice,
        String hprice,
        String mallName,
        String link,
        String productId,
        String image,
        String maker,
        String brand,
        String category1,
        String category2,
        String category3,
        String category4
) {
}