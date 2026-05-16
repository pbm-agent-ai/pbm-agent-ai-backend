package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.dto.response.SearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 네이버 상품 URL 목록을 검색 결과 후보로 재해석하는 서비스.
 *
 * 역할: 사용자가 직접 입력한 네이버 상품 URL을 기반으로,
 *       현재 네이버 쇼핑 검색 결과에서 해당 상품을 다시 찾아 후보로 복원한다.
 * 동작:
 * 1. searchKeyword로 네이버 쇼핑을 재검색한다.
 * 2. URL에서 productId를 추출한다.
 * 3. 추출한 productId와 검색 결과의 productId를 비교해 후보를 찾는다.
 * 4. productId를 추출할 수 없는 URL은 현재 버전에서 후보 복원 대상에서 제외한다.
 * 연관: ExternalApiClient, PriceTopicConsumer, SubscriptionMonitoringService.
 */
@Service
@Slf4j
public class NaverProductUrlService {

    // search.shopping.naver.com/catalog/{productId} 형태 URL에서 네이버 상품 ID를 추출한다.
    private static final Pattern CATALOG_PRODUCT_ID_PATTERN = Pattern.compile("/catalog/(\\d+)");

    private final ExternalApiClient externalApiClient;

    public NaverProductUrlService(ExternalApiClient externalApiClient) {
        this.externalApiClient = externalApiClient;
    }

    /**
     * 네이버 상품 URL 목록을 검색 결과 후보 목록으로 변환한다.
     *
     * @param searchKeyword 네이버 재검색 키워드
     * @param productUrls   사용자가 입력한 네이버 상품 URL 목록
     * @return 검색 결과에서 다시 찾은 상품 후보 목록
     */
    public List<SearchResponse> resolveProductsByUrls(String searchKeyword, List<String> productUrls) {
        if (searchKeyword == null || searchKeyword.isBlank() || productUrls == null || productUrls.isEmpty()) {
            log.info("네이버 URL fallback 스킵 - searchKeyword 또는 productUrls 비어 있음. keyword: {}, urlCount: {}",
                    searchKeyword, productUrls == null ? 0 : productUrls.size());
            return List.of();
        }

        List<NaverShoppingItem> items = new ArrayList<>();
        items.addAll(externalApiClient.searchNaverProductItems(searchKeyword, 100, 1));
        if (items.size() >= 100) {
            items.addAll(externalApiClient.searchNaverProductItems(searchKeyword, 100, 101));
        }

        log.info("네이버 URL fallback 검색 완료 - keyword: {}, inputUrls: {}, fetchedItemCount: {}",
                searchKeyword, productUrls, items.size());

        Map<String, SearchResponse> matchedProducts = new LinkedHashMap<>();
        for (String productUrl : productUrls) {
            String productId = extractProductId(productUrl);
            if (productId == null || productId.isBlank()) {
                log.info("네이버 URL fallback 대상 제외 - productId 추출 실패, url: {}", productUrl);
                continue;
            }

            boolean containsProductId = items.stream().anyMatch(item -> productId.equals(item.productId()));
            log.info("네이버 URL fallback productId 확인 - url: {}, extractedProductId: {}, containsInFetchedItems: {}",
                    productUrl, productId, containsProductId);

            items.stream()
                    .filter(item -> productId.equals(item.productId()))
                    .findFirst()
                    .ifPresent(item -> {
                        log.info("네이버 URL fallback 매칭 성공 - productId: {}, title: {}",
                                item.productId(), item.title());
                        matchedProducts.put(item.productId(), mapToSearchResponse(item));
                    });
        }

        log.info("네이버 URL fallback 최종 매칭 결과 - matchedCount: {}, matchedProductIds: {}",
                matchedProducts.size(), matchedProducts.keySet());

        return new ArrayList<>(matchedProducts.values());
    }

    private String extractProductId(String productUrl) {
        if (productUrl == null || productUrl.isBlank()) {
            return null;
        }

        Matcher matcher = CATALOG_PRODUCT_ID_PATTERN.matcher(productUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private SearchResponse mapToSearchResponse(NaverShoppingItem item) {
        return new SearchResponse(
                item.title(),
                item.lprice(),
                item.hprice(),
                item.mallName(),
                item.link(),
                item.image(),
                "KRW",
                item.productId()
        );
    }
}
