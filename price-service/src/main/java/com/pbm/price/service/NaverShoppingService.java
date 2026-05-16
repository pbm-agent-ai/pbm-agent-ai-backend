package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.dto.response.SearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 네이버 쇼핑 API 연동 서비스
 * 키워드 기반 상품 검색 기능 제공
 *
 * 이전: 네이버 API를 직접 호출 (RestTemplate + 네이버 인증 헤더)
 * 현재: external-api-service를 통해 직접 호출 (WebClient)
 *       네이버 API 키 관리 및 호출 로직이 external-api-service로 이관됨
 *       게이트웨이를 거치지 않고 external-api-service에 직접 호출
 */
@Slf4j
@Service
public class NaverShoppingService {

    private final ExternalApiClient externalApiClient;
    private final ProductPersistenceService productPersistenceService;

    public NaverShoppingService(ExternalApiClient externalApiClient,
                                ProductPersistenceService productPersistenceService) {
        this.externalApiClient = externalApiClient;
        this.productPersistenceService = productPersistenceService;
    }

    /**
     * 키워드로 네이버 쇼핑 상품 검색
     * external-api-service를 통해 네이버 쇼핑 API를 호출한다.
     *
     * @param keyword 검색 키워드
     * @param display 검색 결과 개수 (기본 10, 최대 100)
     * @return 검색된 상품 목록
     */
    public List<SearchResponse> searchProducts(String keyword, int display) {
        return searchProducts(keyword, display, 1);
    }

    /**
     * 키워드로 네이버 쇼핑 상품 검색 (start 파라미터 지원 오버로드)
     * 기존 searchProducts(keyword, display)와의 호환성을 유지하면서
     * start 파라미터를 추가한 오버로드 메서드.
     *
     * @param keyword 검색 키워드
     * @param display 검색 결과 개수 (최대 100)
     * @param start   검색 시작 위치 (최대 1000)
     * @return 검색된 상품 목록
     */
    public List<SearchResponse> searchProducts(String keyword, int display, int start) {
        log.info("상품 검색 요청 - 키워드: {}, 개수: {}, 시작: {} (external-api-service 경유)", keyword, display, start);

        List<NaverShoppingItem> items = externalApiClient.searchNaverProductItems(keyword, display, start);
        productPersistenceService.saveNaverSearchResults(keyword, items);

        return items.stream()
                .map(item -> new SearchResponse(
                        item.title(),
                        item.lprice(),
                        item.hprice(),
                        item.mallName(),
                        item.link(),
                        item.image(),
                        "KRW",
                        item.productId()
                ))
                .toList();
    }
}
