package com.pbm.price.dto.event;

import java.util.List;

/**
 * price-topic에서 수신하는 가격 확인 요청의 Payload DTO.
 *
 * 역할: command-service 등에서 가격 비교를 요청할 때 필요한 최소 정보를 담는다.
 * 동작: 사용자가 설정한 목표 가격과 검색 키워드를 포함하여 price-service가
 *       상품 검색 후 가격 비교를 수행할 수 있도록 한다.
 *       platform과 currency는 가격 비교 대상 플랫폼과 통화 정보를 전달한다.
 *       commandId, intent, parsedCommandSnapshot은 추후 post-search 검증에서
 *       원본 명령 컨텍스트로 활용된다.
 * 연관: PriceRequestEvent, PriceTopicConsumer, ParsedCommandSnapshot.
 */
public record PriceRequestEventPayload(
        /** 요청 사용자 ID */
        Long userId,
        /** 검색 키워드 (상품명 등) */
        String keyword,
        /** 목표 가격 (원), 현재가가 이 값 이하일 때 알림 발생 */
        Integer targetPrice,
        /** 가격 비교 대상 플랫폼 (예: "NAVER", "ALIEXPRESS") */
        String platform,
        /** 통화 코드 (예: "KRW", "USD") */
        String currency,
        /** 명령 고유 식별자 (UUID) */
        String commandId,
        /** 사용자 의도 (CommandIntent name 문자열, 예: "PRICE_CHECK") */
        String intent,
        /** 파싱된 명령 스냅샷 (post-search 검증용) */
        ParsedCommandSnapshot parsedCommandSnapshot,
        /** 상품 상세 페이지 URL */
        String productUrl,
        /** 검색 키워드 (Phase 1 이후 명시적 전달용) */
        String searchKeyword,
        /** 사용자가 직접 입력한 상품 URL 목록 */
        List<String> productUrls,
        /** URL_MONITOR 전용: "ALL" | "ANY" 모니터링 조건 */
        String urlCondition,
        /** URL_MONITOR 전용: 익스텐션이 DOM에서 추출한 현재 가격 (즉시 충족 판단용, KRW 기준) */
        Integer currentPrice
) {

    /**
     * 기존 10개 필드 payload와의 호환성을 위한 보조 생성자.
     */
    public PriceRequestEventPayload(
            Long userId,
            String keyword,
            Integer targetPrice,
            String platform,
            String currency,
            String commandId,
            String intent,
            ParsedCommandSnapshot parsedCommandSnapshot,
            String productUrl,
            String searchKeyword
    ) {
        this(userId, keyword, targetPrice, platform, currency, commandId, intent,
                parsedCommandSnapshot, productUrl, searchKeyword, null, null, null);
    }

    /**
     * productUrls는 있고 urlCondition/currentPrice는 없는 호출부와의 호환성을 위한 보조 생성자.
     */
    public PriceRequestEventPayload(
            Long userId,
            String keyword,
            Integer targetPrice,
            String platform,
            String currency,
            String commandId,
            String intent,
            ParsedCommandSnapshot parsedCommandSnapshot,
            String productUrl,
            String searchKeyword,
            List<String> productUrls
    ) {
        this(userId, keyword, targetPrice, platform, currency, commandId, intent,
                parsedCommandSnapshot, productUrl, searchKeyword, productUrls, null, null);
    }

    /**
     * currentPrice 없는 12개 필드 호출부와의 호환성을 위한 보조 생성자.
     */
    public PriceRequestEventPayload(
            Long userId,
            String keyword,
            Integer targetPrice,
            String platform,
            String currency,
            String commandId,
            String intent,
            ParsedCommandSnapshot parsedCommandSnapshot,
            String productUrl,
            String searchKeyword,
            List<String> productUrls,
            String urlCondition
    ) {
        this(userId, keyword, targetPrice, platform, currency, commandId, intent,
                parsedCommandSnapshot, productUrl, searchKeyword, productUrls, urlCondition, null);
    }
}
