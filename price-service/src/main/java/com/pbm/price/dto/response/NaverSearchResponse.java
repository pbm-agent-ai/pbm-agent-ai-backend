package com.pbm.price.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * external-api-service 네이버 쇼핑 검색 응답 래퍼 DTO
 * external-api-service의 NaverSearchResponse 스키마와 1:1 매핑
 *
 * @param total   전체 검색 결과 수
 * @param start   검색 시작 위치
 * @param display 표시할 결과 수
 * @param items   상품 목록
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverSearchResponse(
        int total,
        int start,
        int display,
        List<NaverShoppingItem> items
) {
}