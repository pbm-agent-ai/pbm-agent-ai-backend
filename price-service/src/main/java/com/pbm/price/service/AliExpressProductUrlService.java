package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.dto.response.AliExpressShoppingItem;
import com.pbm.price.dto.response.AliexpressProductDetailResponse;
import com.pbm.price.dto.response.SearchResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AliExpress 상품 URL 목록을 공식 detail API 검증 대상으로 변환하는 서비스.
 *
 * 역할: 사용자가 직접 입력한 AliExpress 링크에서 productId를 추출하고,
 *       공식 detail API로 조회 가능한 상품만 후보로 선별한다.
 * 동작:
 * 1. URL에서 productId를 추출한다.
 * 2. 각 productId를 official detail API로 조회한다.
 * 3. 조회 성공한 상품만 SearchResponse 후보 목록으로 반환한다.
 * 연관: ExternalApiClient, PriceTopicConsumer.
 */
@Service
public class AliExpressProductUrlService {

    private static final Pattern PRODUCT_ID_PATTERN = Pattern.compile("(?:/item/|productId=)(\\d{10,})");

    private final ExternalApiClient externalApiClient;

    public AliExpressProductUrlService(ExternalApiClient externalApiClient) {
        this.externalApiClient = externalApiClient;
    }

    /**
     * AliExpress URL 목록을 검증 가능한 후보 상품 목록으로 변환한다.
     *
     * @param productUrls    사용자가 입력한 URL 목록
     * @param targetCurrency 목표 통화
     * @param targetLanguage 목표 언어
     * @param shipToCountry  배송 국가
     * @return detail API 조회에 성공한 상품 후보 목록
     */
    public List<SearchResponse> resolveProductsByUrls(
            List<String> productUrls,
            String targetCurrency,
            String targetLanguage,
            String shipToCountry
    ) {
        if (productUrls == null || productUrls.isEmpty()) {
            return List.of();
        }

        Map<String, SearchResponse> resolvedProducts = new LinkedHashMap<>();

        for (String productUrl : productUrls) {
            String productId = extractProductId(productUrl);
            if (productId == null) {
                continue;
            }

            AliexpressProductDetailResponse response = externalApiClient.getAliExpressProductDetail(
                    productId,
                    targetCurrency,
                    targetLanguage,
                    shipToCountry
            );
            AliExpressShoppingItem product = response.product();
            if (product == null) {
                continue;
            }

            resolvedProducts.put(product.product_id(), mapToSearchResponse(product, targetCurrency));
        }

        return new ArrayList<>(resolvedProducts.values());
    }

    private String extractProductId(String productUrl) {
        if (productUrl == null || productUrl.isBlank()) {
            return null;
        }

        Matcher matcher = PRODUCT_ID_PATTERN.matcher(productUrl.trim());
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private SearchResponse mapToSearchResponse(AliExpressShoppingItem product, String targetCurrency) {
        String lprice = product.target_sale_price() != null && !product.target_sale_price().isBlank()
                ? product.target_sale_price()
                : product.sale_price();
        String hprice = product.target_original_price() != null && !product.target_original_price().isBlank()
                ? product.target_original_price()
                : null;
        String currency = product.target_sale_price() != null && !product.target_sale_price().isBlank()
                ? targetCurrency
                : "USD";

        return new SearchResponse(
                product.product_title(),
                lprice,
                hprice,
                product.shop_name(),
                product.product_detail_url(),
                currency == null ? "KRW" : currency.toUpperCase(Locale.ROOT),
                product.product_id()
        );
    }
}
