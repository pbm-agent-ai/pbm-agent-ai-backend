package com.pbm.price.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * external-api-service AliExpress 카테고리 응답 개별 항목 DTO
 * external-api-service의 AliExpress 카테고리 스키마와 1:1 매핑
 *
 * 외부 API가 내려주는 category_id는 String 타입으로,
 * parent_category_id가 null/blank/"0"이면 최상위(root) 카테고리로 간주한다.
 *
 * @param category_id        카테고리 고유 ID (String)
 * @param category_name      카테고리명
 * @param parent_category_id 부모 카테고리 ID (최상위면 null, blank, 또는 "0")
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AliExpressCategoryItem(
        String category_id,
        String category_name,
        String parent_category_id
) {
}
