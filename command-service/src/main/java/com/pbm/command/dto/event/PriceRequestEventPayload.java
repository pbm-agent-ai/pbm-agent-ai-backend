package com.pbm.command.dto.event;

import java.util.List;

/**
 * price-topic 토픽의 실제 가격 요청 데이터를 담는 Payload DTO.
 *
 * 역할: price-service가 가격 조회 또는 모니터링 등록을 시작할 때 필요한 최소 입력값을 전달한다.
 * 동작: command-service가 자연어 파싱 결과를 정리한 뒤 공통 이벤트 Envelope 안에 담아 발행한다.
 *       platform과 currency는 가격 비교 대상 플랫폼과 통화 정보를 전달한다.
 *       commandId, intent, parsedCommandSnapshot은 추후 post-search 검증에서
 *       원본 명령 컨텍스트로 활용된다.
 * 연관: PriceRequestEvent, command-service parser, price-service consumer, ParsedCommandSnapshot.
 */
public record PriceRequestEventPayload(
        /** 요청 사용자 ID */
        Long userId,
        /** 검색 키워드 (상품명 등) */
        String keyword,
        /** 목표 가격 */
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
        List<String> productUrls
) {

    /**
     * 기존 10개 필드 호출부와의 호환성을 위한 보조 생성자.
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
                parsedCommandSnapshot, productUrl, searchKeyword, null);
    }
}
