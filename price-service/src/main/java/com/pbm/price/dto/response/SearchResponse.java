package com.pbm.price.dto.response;

/**
 * 네이버 쇼핑 검색 결과 응답 DTO
 * record 클래스로 불변 객체 보장
 *
 * @param title    상품명 (HTML 태그 포함 가능)
 * @param lprice   최저가 (원)
 * @param hprice   최고가 (원, 없을 수 있음)
 * @param mallName 쇼핑몰 이름
 * @param link     상품 상세 링크
 */
public record SearchResponse(
        String title,
        String lprice,
        String hprice,
        String mallName,
        String link
) {
}
