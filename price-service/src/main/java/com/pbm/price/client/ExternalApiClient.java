package com.pbm.price.client;

import com.pbm.price.dto.response.AliExpressSearchResponse;
import com.pbm.price.dto.response.AliExpressShoppingItem;
import com.pbm.price.dto.response.NaverSearchResponse;
import com.pbm.price.dto.response.SearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * external-api-service 호출 클라이언트
 * 네이버 쇼핑, AliExpress 등 외부 API 요청을 external-api-service로 직접 프록시한다.
 * 게이트웨이를 거치지 않고 external-api-service에 직접 호출한다.
 */
@Slf4j
@Component
public class ExternalApiClient {

    private final WebClient externalApiWebClient;

    public ExternalApiClient(WebClient externalApiWebClient) {
        this.externalApiWebClient = externalApiWebClient;
    }

    /**
     * external-api-service를 통해 네이버 쇼핑 상품 검색
     * external-api-service의 래퍼 응답(NaverSearchResponse)에서 items를 추출하여 반환한다.
     *
     * @param keyword 검색 키워드
     * @param display 검색 결과 개수
     * @return 검색된 상품 목록
     */
    public List<SearchResponse> searchNaverProducts(String keyword, int display) {
        log.info("external-api-service 네이버 쇼핑 검색 요청 - 키워드: {}, 개수: {}", keyword, display);

        try {
            // external-api-service의 네이버 쇼핑 엔드포인트 호출
            // 경로: GET /api/v1/naver/search?keyword={keyword}&display={display}
            // 응답: NaverSearchResponse 래퍼 (total, start, display, items)
            NaverSearchResponse response = externalApiWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/naver/search")
                            .queryParam("keyword", keyword)
                            .queryParam("display", display)
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<NaverSearchResponse>() {})
                    .block();

            if (response == null || response.items() == null) {
                log.warn("external-api-service 응답이 null이거나 items가 없음");
                return List.of();
            }

            // NaverShoppingItem → SearchResponse 로 매핑
            List<SearchResponse> results = response.items().stream()
                    .map(item -> new SearchResponse(
                            item.title(),
                            item.lprice(),
                            item.hprice(),
                            item.mallName(),
                            item.link()
                    ))
                    .toList();

            log.info("external-api-service 응답 수신 완료 - {}건 (전체: {})", results.size(), response.total());
            return results;

        } catch (Exception e) {
            log.error("external-api-service 호출 실패 - 키워드: {}, 에러: {}", keyword, e.getMessage());
            throw new RuntimeException("external-api-service 네이버 쇼핑 API 호출 중 오류가 발생했습니다.", e);
        }
    }

    /**
     * external-api-service를 통해 AliExpress 상품 검색
     * external-api-service의 래퍼 응답(AliExpressSearchResponse)에서 items를 추출하여
     * 공통 SearchResponse 목록으로 매핑하여 반환한다.
     *
     * 매핑 규칙:
     *   title    ← product_title
     *   lprice   ← target_sale_price (없으면 sale_price)
     *   hprice   ← target_original_price
     *   mallName ← shop_name
     *   link     ← product_detail_url
     *
     * @param keyword           검색 키워드 (필수)
     * @param pageNo            페이지 번호 (기본 1)
     * @param pageSize          페이지당 결과 수 (기본 10)
     * @param sort              정렬 기준 (선택, 예: "SALE_PRICE_ASC")
     * @param targetCurrency    통화 (기본 KRW)
     * @param targetLanguage    언어 (기본 KO)
     * @param shipToCountry     배송 국가 (기본 KR)
     * @param trackingId        트래킹 ID (선택)
     * @return 검색된 상품 목록 (공통 SearchResponse 형식)
     */
    public List<SearchResponse> searchAliExpressProducts(
            String keyword,
            int pageNo,
            int pageSize,
            String sort,
            String targetCurrency,
            String targetLanguage,
            String shipToCountry,
            String trackingId
    ) {
        log.info("external-api-service AliExpress 검색 요청 - 키워드: {}, 페이지: {}/{}, 정렬: {}",
                keyword, pageNo, pageSize, sort);

        try {
            // external-api-service의 AliExpress 검색 엔드포인트 호출
            // 경로: GET /api/v1/aliexpress/search
            // 필수: keyword, 선택: page_no, page_size, sort, target_currency, target_language, ship_to_country, tracking_id
            AliExpressSearchResponse response = externalApiWebClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder
                                .path("/api/v1/aliexpress/search")
                                .queryParam("keyword", keyword)
                                .queryParam("page_no", pageNo)
                                .queryParam("page_size", pageSize);
                        // 선택 파라미터는 값이 있을 때만 추가 (null이면 쿼리 파라미터에서 제외)
                        if (sort != null && !sort.isBlank()) {
                            builder = builder.queryParam("sort", sort);
                        }
                        if (targetCurrency != null && !targetCurrency.isBlank()) {
                            builder = builder.queryParam("target_currency", targetCurrency);
                        }
                        if (targetLanguage != null && !targetLanguage.isBlank()) {
                            builder = builder.queryParam("target_language", targetLanguage);
                        }
                        if (shipToCountry != null && !shipToCountry.isBlank()) {
                            builder = builder.queryParam("ship_to_country", shipToCountry);
                        }
                        if (trackingId != null && !trackingId.isBlank()) {
                            builder = builder.queryParam("tracking_id", trackingId);
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<AliExpressSearchResponse>() {})
                    .block();

            if (response == null || response.items() == null) {
                log.warn("external-api-service AliExpress 응답이 null이거나 items가 없음");
                return List.of();
            }

            // AliExpressShoppingItem → SearchResponse 로 매핑
            List<SearchResponse> results = response.items().stream()
                    .map(this::mapAliExpressItemToSearchResponse)
                    .toList();

            log.info("external-api-service AliExpress 응답 수신 완료 - {}건 (전체: {})", results.size(), response.total());
            return results;

        } catch (Exception e) {
            log.error("external-api-service AliExpress 호출 실패 - 키워드: {}, 에러: {}", keyword, e.getMessage());
            throw new RuntimeException("external-api-service AliExpress API 호출 중 오류가 발생했습니다.", e);
        }
    }

    /**
     * AliExpressShoppingItem을 공통 SearchResponse로 매핑
     * - lprice: target_sale_price가 있으면 사용, 없으면 sale_price 사용
     * - hprice: target_original_price 사용
     * - mallName: shop_name 사용
     * - link: product_detail_url 사용
     *
     * @param item AliExpress 상품 항목
     * @return 공통 검색 응답 DTO
     */
    private SearchResponse mapAliExpressItemToSearchResponse(AliExpressShoppingItem item) {
        // target_sale_price가 있으면 KRW 변환 판매가를 최저가로 사용, 없으면 원래 sale_price 사용
        String lprice = (item.target_sale_price() != null && !item.target_sale_price().isBlank())
                ? item.target_sale_price()
                : item.sale_price();

        return new SearchResponse(
                item.product_title(),       // title ← product_title
                lprice,                      // lprice ← target_sale_price 또는 sale_price
                item.target_original_price(),// hprice ← target_original_price
                item.shop_name(),            // mallName ← shop_name
                item.product_detail_url()    // link ← product_detail_url
        );
    }
}