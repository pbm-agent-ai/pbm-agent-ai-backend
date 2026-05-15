package com.pbm.price.dto.event;

/**
 * 파싱된 명령의 구조화된 스냅샷 DTO.
 *
 * 역할: price-topic 이벤트 페이로드에 포함되어, price-service가
 *       가격 검증/비교 시 원본 명령 컨텍스트를 참조할 수 있게 한다.
 * 동작: ParsedCommand의 필드를 String/Integer 타입으로 평탄화하여,
 *       서비스 간 Kafka 메시지로 안전하게 직렬화한다.
 * 연관: PriceRequestEventPayload, ParsedCommand.
 */
public record ParsedCommandSnapshot(
        /** 상품 카테고리 (enum name 문자열) */
        String productCategory,
        /** 상품명 */
        String productName,
        /** 브랜드명 */
        String brand,
        /** 라인명 */
        String line,
        /** 모델명 */
        String model,
        /** 색상 */
        String color,
        /** 사이즈 */
        String size,
        /** 플랫폼 (enum name 문자열) */
        String platform,
        /** 최고 가격 */
        Integer maxPrice,
        /** 최저 가격 */
        Integer minPrice,
        /** 통화 코드 */
        String currency,
        /** 상품 상세 페이지 URL */
        String productUrl,
        /** 검색 키워드 */
        String searchKeyword,
        /** AliExpress 검색용 세부 카테고리 힌트 */
        String searchCategoryHint
) {

    /**
     * 기존 스냅샷 생성 코드와의 호환성을 위한 보조 생성자.
     */
    public ParsedCommandSnapshot(
            String productCategory,
            String productName,
            String brand,
            String line,
            String model,
            String color,
            String size,
            String platform,
            Integer maxPrice,
            Integer minPrice,
            String currency,
            String productUrl,
            String searchKeyword
    ) {
        this(productCategory, productName, brand, line, model, color, size, platform, maxPrice, minPrice, currency, productUrl, searchKeyword, null);
    }
}
