package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.dto.response.AliExpressShoppingItem;
import com.pbm.price.dto.response.SearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AliExpress 쇼핑 검색 API 연동 서비스
 * 키워드 기반 상품 검색 기능 제공
 *
 * external-api-service를 통해 AliExpress 검색 API를 호출한다.
 * 게이트웨이를 거치지 않고 external-api-service에 직접 호출한다.
 * 검색 결과는 공통 SearchResponse 형식으로 반환한다.
 */
@Slf4j
@Service
public class AliExpressShoppingService {

    private final ExternalApiClient externalApiClient;
    private final ProductPersistenceService productPersistenceService;

    public AliExpressShoppingService(ExternalApiClient externalApiClient,
                                     ProductPersistenceService productPersistenceService) {
        this.externalApiClient = externalApiClient;
        this.productPersistenceService = productPersistenceService;
    }

    /**
     * 키워드로 AliExpress 상품 검색
     * external-api-service를 통해 AliExpress 검색 API를 호출한다.
     *
     * @param keyword        검색 키워드 (필수)
     * @param pageNo         페이지 번호 (기본 1)
     * @param pageSize       페이지당 결과 수 (기본 10)
     * @param sort           정렬 기준 (선택)
     * @param targetCurrency 통화 (기본 KRW)
     * @param targetLanguage 언어 (기본 KO)
     * @param shipToCountry  배송 국가 (기본 KR)
     * @param trackingId     트래킹 ID (선택)
     * @return 검색된 상품 목록 (공통 SearchResponse 형식)
     */
    public List<SearchResponse> searchProducts(
            String keyword,
            int pageNo,
            int pageSize,
            String sort,
            String targetCurrency,
            String targetLanguage,
            String shipToCountry,
            String trackingId
    ) {
        log.info("AliExpress 상품 검색 요청 - 키워드: {}, 페이지: {}/{}, 정렬: {} (external-api-service 경유)",
                keyword, pageNo, pageSize, sort);

        List<AliExpressShoppingItem> items = externalApiClient.searchAliExpressProductItems(
                keyword, pageNo, pageSize, sort, targetCurrency, targetLanguage, shipToCountry, trackingId
        );
        productPersistenceService.saveAliExpressSearchResults(keyword, targetCurrency, items);

        return items.stream()
                .map(item -> {
                    String lprice = (item.target_sale_price() != null && !item.target_sale_price().isBlank())
                            ? item.target_sale_price()
                            : item.sale_price();

                    return new SearchResponse(
                            item.product_title(),
                            lprice,
                            item.target_original_price(),
                            item.shop_name(),
                            item.product_detail_url()
                    );
                })
                .toList();
    }
}
