package com.pbm.command.domain;

/**
 * 파싱 결과에서 사용하는 상품 카테고리(enum).
 *
 * 역할: GPT가 추출한 상품 종류를 미리 정의한 값으로 제한하여,
 *       이후 카테고리별 필수 필드 정책을 안정적으로 적용할 수 있게 한다.
 * 동작: 현재 MVP에서는 대표적인 상품군만 먼저 정의하고,
 *       분류가 애매한 경우에는 UNKNOWN으로 처리할 수 있게 열어둔다.
 * 연관: ParsedCommand, CommandParseResponse, 카테고리별 필수값 정책.
 */
public enum ProductCategory {
    SHOES,
    ELECTRONICS,
    APPAREL,
    UNKNOWN
}
