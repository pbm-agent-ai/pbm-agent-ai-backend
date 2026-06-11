package com.pbm.command.dto.response;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.pbm.command.domain.PlatformType;
import com.pbm.command.domain.ProductCategory;

import java.util.List;

/**
 * 자연어 명령에서 추출한 구조화 결과 DTO.
 *
 * 역할: GPT가 자유 문장을 분석해 뽑아낸 상품/가격/옵션 정보를 정형화한다.
 * 동작: 값이 확실하지 않은 필드는 null로 유지하고,
 *       이후 백엔드 규칙이 missingRequiredFields / ambiguousFields를 계산할 때 입력값으로 사용한다.
 * 연관: CommandParseResponse, ProductCategory, PlatformType.
 *
 * platforms: 비어있거나 null이면 "모든 플랫폼 대상" 으로 해석된다.
 *            PRICE_CHECK intent에서 비어있으면 NAVER + ALIEXPRESS 모두 검색한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParsedCommand(
        ProductCategory productCategory,
        String productName,
        String brand,
        String line,
        String model,
        String color,
        String size,
        @JsonAlias("platform") @JsonDeserialize(using = PlatformListDeserializer.class) List<PlatformType> platforms,
        Integer maxPrice,
        Integer minPrice,
        String currency,
        String searchCategoryHint,
        /** URL_MONITOR intent 전용: 사용자가 직접 입력한 상품 URL 목록 */
        List<String> productUrls,
        /** URL_MONITOR intent 전용: "ALL"(모두 충족) | "ANY"(하나라도 충족) */
        String monitorCondition,
        /**
         * URL_MONITOR intent 전용: 실제 구매/추적 의도.
         * GPT가 문장에서 판단: "구매해줘" → "AUTO_PURCHASE", "알려줘/추적해줘" → "PRICE_TRACK"
         */
        String purchaseIntent
) {

    /**
     * searchCategoryHint가 없는 호출부와의 호환성을 위한 보조 생성자.
     */
    public ParsedCommand(
            ProductCategory productCategory,
            String productName,
            String brand,
            String line,
            String model,
            String color,
            String size,
            List<PlatformType> platforms,
            Integer maxPrice,
            Integer minPrice,
            String currency
    ) {
        this(productCategory, productName, brand, line, model, color, size, platforms,
                maxPrice, minPrice, currency, null, null, null, null);
    }

    /**
     * searchCategoryHint만 있고 URL 필드는 없는 호출부와의 호환성을 위한 보조 생성자.
     */
    public ParsedCommand(
            ProductCategory productCategory,
            String productName,
            String brand,
            String line,
            String model,
            String color,
            String size,
            List<PlatformType> platforms,
            Integer maxPrice,
            Integer minPrice,
            String currency,
            String searchCategoryHint
    ) {
        this(productCategory, productName, brand, line, model, color, size, platforms,
                maxPrice, minPrice, currency, searchCategoryHint, null, null, null);
    }
}
