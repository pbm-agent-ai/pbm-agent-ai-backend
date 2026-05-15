package com.pbm.command.domain;

/**
 * 파싱 결과에서 관리하는 필드 종류(enum).
 *
 * 역할: 누락 필드와 모호 필드를 문자열 상수 대신 enum으로 일관되게 관리한다.
 * 동작: 프론트 응답에는 lowerCamelCase 문자열로 내려주고,
 *       백엔드 내부에서는 어떤 필드를 검사하는지 명확하게 표현한다.
 * 연관: CommandFieldPolicyService, CommandFieldEvaluationService.
 */
public enum CommandFieldType {
    PRODUCT_CATEGORY("productCategory"),
    PRODUCT_NAME("productName"),
    BRAND("brand"),
    LINE("line"),
    MODEL("model"),
    COLOR("color"),
    SIZE("size"),
    PLATFORM("platform"),
    MAX_PRICE("maxPrice"),
    MIN_PRICE("minPrice"),
    CURRENCY("currency"),
    SEARCH_CATEGORY_HINT("searchCategoryHint");

    private final String fieldKey;

    CommandFieldType(String fieldKey) {
        this.fieldKey = fieldKey;
    }

    /**
     * 프론트와 API 응답에서 사용할 필드 키를 반환한다.
     *
     * @return lowerCamelCase 형식의 필드명
     */
    public String fieldKey() {
        return fieldKey;
    }
}
