package com.pbm.price.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * external-api-service AliExpress 카테고리 API 응답 래퍼 DTO
 * external-api-service의 AliExpress 카테고리 엔드포인트 응답 스키마와 1:1 매핑
 *
 * @param total 전체 카테고리 수
 * @param items 카테고리 항목 목록
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AliExpressCategoryResponse(
        int total,
        List<AliExpressCategoryItem> items
) {
}
