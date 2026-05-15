package com.pbm.price.service;

import com.pbm.price.dto.response.SearchResponse;
import java.util.List;

/**
 * post-search 검증 결과 DTO.
 *
 * 역할: 상품 검색 후 첫 번째 결과를 신뢰하기 전에 수행하는 최소 안전 검증의 결과를 담는다.
 *       검증 결과 PRODUCT_SELECTION_REQUIRED 시, 사용자 선택이 필요한 후보 상품 목록
 *       (candidates)을 함께 전달할 수 있다.
 * 동작: PostSearchValidationService.validate()가 이 결과를 반환하며,
 *       호출 측(PriceTopicConsumer)은 status에 따라 이후 동작을 결정한다.
 *
 * 연관: PostSearchValidationService, PriceTopicConsumer, SearchResponse.
 */
public record PostSearchValidationResult(
        /** 검증 상태 */
        Status status,
        /** 누락된 필드 목록 (PRODUCT_SELECTION_REQUIRED 시 원인 필드명) */
        List<String> missingFields,
        /** 사람이 읽을 수 있는 검증 메시지 */
        String message,
        /** 후보 상품 목록 (PRODUCT_SELECTION_REQUIRED 시 사용자 선택을 위해 제공, 없으면 null) */
        List<SearchResponse> candidates
) {

    /**
     * post-search 검증 상태.
     * PROCEED: 검증 통과, 정상 진행
     * NO_MATCH: 검색 결과 없음
     * PRODUCT_SELECTION_REQUIRED: 후보 상품 선택이 필요한 상태 (Kafka 이벤트 발행)
     */
    public enum Status {
        PROCEED,
        NO_MATCH,
        PRODUCT_SELECTION_REQUIRED
    }

    // ── 편의 팩토리 메서드 ──────────────────────────────────────────────

    /** 검증 통과 - 정상 진행 */
    public static PostSearchValidationResult proceed() {
        return new PostSearchValidationResult(Status.PROCEED, List.of(), "검증 통과 - 정상 진행", null);
    }

    /** 검색 결과 없음 */
    public static PostSearchValidationResult noMatch(String message) {
        return new PostSearchValidationResult(Status.NO_MATCH, List.of(), message, null);
    }

    /** 상품 선택 필요 (누락 필드와 사유 포함, candidates null) */
    public static PostSearchValidationResult selectionRequired(List<String> missingFields, String message) {
        return new PostSearchValidationResult(Status.PRODUCT_SELECTION_REQUIRED, missingFields, message, null);
    }

    /** 상품 선택 필요 (누락 필드, 사유, 후보 상품 목록 포함) */
    public static PostSearchValidationResult selectionRequired(
            List<String> missingFields, String message, List<SearchResponse> candidates) {
        return new PostSearchValidationResult(Status.PRODUCT_SELECTION_REQUIRED, missingFields, message, candidates);
    }
}
