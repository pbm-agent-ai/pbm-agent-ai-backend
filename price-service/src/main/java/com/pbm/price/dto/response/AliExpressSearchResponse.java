package com.pbm.price.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * external-api-service AliExpress 검색 응답 래퍼 DTO
 * external-api-service의 AliExpress 검색 엔드포인트 응답 스키마와 1:1 매핑
 *
 * @param total   전체 검색 결과 수
 * @param page_no 현재 페이지 번호
 * @param page_size 페이지당 결과 수
 * @param items   상품 목록
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AliExpressSearchResponse(
        int total,
        int page_no,
        int page_size,
        List<AliExpressShoppingItem> items
) {
}