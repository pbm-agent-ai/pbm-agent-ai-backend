package com.pbm.command.dto.response;

/**
 * post-search clarification 시 프론트에 노출할 후보 상품 응답 DTO.
 *
 * 역할: CommandSessionResponse에 포함되어 GET /commands/{commandId} API를 통해
 *       클라이언트에 후보 상품 목록을 제공한다.
 * 동작: CommandSessionResponse.from()에서 candidatesJson을 파싱하여
 *       List&lt;ProductCandidateResponse&gt;로 변환한다.
 * 연관: CommandSessionResponse, CommandSession.
 */
public record ProductCandidateResponse(
        /** 상품 식별자 (플랫폼 상품 ID 또는 productUrl 기반 폴백 식별자) */
        String productId,
        /** 상품명 */
        String title,
        /** 최저가 문자열 */
        String lprice,
        /** 쇼핑몰 이름 */
        String mallName,
        /** 상품 상세 링크 */
        String productUrl,
        /** 대표 상품 이미지 URL */
        String imageUrl,
        /** 통화 코드 */
        String currency,
        /** 플랫폼 코드 */
        String platform,
        /** 검색 키워드 */
        String searchKeyword
) {
}
