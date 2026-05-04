package com.pbm.price.client;

import com.pbm.price.dto.response.AliExpressSearchResponse;
import com.pbm.price.dto.response.AliExpressShoppingItem;
import com.pbm.price.dto.response.NaverSearchResponse;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.dto.response.SearchResponse;
import com.pbm.price.exception.ExternalApiException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * external-api-service 호출 클라이언트
 * 네이버 쇼핑, AliExpress 등 외부 API 요청을 external-api-service로 직접 프록시한다.
 * 게이트웨이를 거치지 않고 external-api-service에 직접 호출한다.
 *
 * 장애 격리 전략:
 * - @CircuitBreaker: 외부 API 장애 시 Circuit Breaker가 열려 빠른 실패(Fail-Fast)를 유도한다.
 *   slidingWindowSize=10, minimumNumberOfCalls=5, failureRateThreshold=50%,
 *   waitDurationInOpenState=30s, permittedNumberOfCallsInHalfOpenState=3
 * - @Retry: 일시적 장애 시 자동 재시도한다. maxAttempts=3, waitDuration=1s
 * - WebClient 타임아웃: 연결 3초, 응답 5초 (WebClientConfig 참조)
 *   @TimeLimiter는 비동기(CompletionStage) 메서드에만 적용 가능하므로,
 *   동기 block() 방식에서는 WebClient 레벨에서 타임아웃을 설정함
 * - Fallback: Circuit Breaker OPEN 또는 재시도 모두 실패 시 ExternalApiException을 발생시켜
 *   호출자가 명확하게 장애를 인지할 수 있도록 한다.
 *   빈 결과를 반환하는 대신 예외를 전파하여, 사용자에게 장애 상황을 명확히 알린다.
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
        return searchNaverProductItems(keyword, display).stream()
                .map(item -> new SearchResponse(
                        item.title(),
                        item.lprice(),
                        item.hprice(),
                        item.mallName(),
                        item.link()
                ))
                .toList();
    }

    /**
     * external-api-service를 통해 네이버 쇼핑 원본 상품 항목을 조회한다.
     * Circuit Breaker와 Retry 보호가 적용된 핵심 외부 API 호출 메서드이다.
     *
     * 장애 발생 시:
     * - 일시적 장애: 최대 3회 자동 재시도 (1초 간격)
     * - 지속적 장애: Circuit Breaker가 OPEN 상태로 전환되어 빠른 실패 유도
     * - 모든 재시도 실패 또는 Circuit OPEN: fallback 메서드가 ExternalApiException 발생
     *
     * DB 저장에는 productId, image 등 SearchResponse에 없는 추가 필드가 필요하므로
     * 원본 DTO를 반환하는 메서드를 별도로 제공한다.
     *
     * @param keyword 검색 키워드
     * @param display 검색 결과 개수
     * @return 네이버 원본 상품 목록
     */
    @Retry(name = "externalApiService")
    @CircuitBreaker(name = "externalApiService", fallbackMethod = "naverSearchFallback")
    public List<NaverShoppingItem> searchNaverProductItems(String keyword, int display) {
        log.info("external-api-service 네이버 쇼핑 검색 요청 - 키워드: {}, 개수: {}", keyword, display);

        // external-api-service의 네이버 쇼핑 엔드포인트 호출
        // 경로: GET /api/v1/naver/search?keyword={keyword}&display={display}
        // 응답: NaverSearchResponse 래퍼 (total, start, display, items)
        // DTO 폴더에 있는 NaverSearchResponse라는 DTO객체를 이용해서 응답 변수 생성함
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

        log.info("external-api-service 응답 수신 완료 - {}건 (전체: {})", response.items().size(), response.total());
        return response.items();
    }

    /**
     * 네이버 쇼핑 API 호출 실패 시 fallback 메서드
     * Circuit Breaker가 OPEN 상태이거나 재시도가 모두 실패한 경우 호출된다.
     * 빈 결과를 반환하지 않고 명확한 예외를 전파하여 장애 상황을 인지할 수 있도록 한다.
     *
     * @param keyword 검색 키워드
     * @param display 검색 결과 개수
     * @param t 발생한 예외 (Circuit Breaker CallNotPermittedException 또는 원본 예외)
     * @throws ExternalApiException 외부 API 호출 불가 예외
     */
    List<NaverShoppingItem> naverSearchFallback(String keyword, int display, Throwable t) {
        log.error("네이버 쇼핑 API 호출 실패 (Circuit Breaker fallback) - 키워드: {}, 원인: {}", keyword, t.getMessage());
        throw new ExternalApiException(
                "external-api-service 네이버 쇼핑 API 호출 불가 (Circuit Breaker OPEN 또는 오류)", t);
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
        return searchAliExpressProductItems(keyword, pageNo, pageSize, sort, targetCurrency, targetLanguage, shipToCountry, trackingId)
                .stream()
                .map(this::mapAliExpressItemToSearchResponse)
                .toList();
    }

    /**
     * external-api-service를 통해 AliExpress 원본 상품 항목을 조회한다.
     * Circuit Breaker와 Retry 보호가 적용된 핵심 외부 API 호출 메서드이다.
     *
     * 장애 발생 시:
     * - 일시적 장애: 최대 3회 자동 재시도 (1초 간격)
     * - 지속적 장애: Circuit Breaker가 OPEN 상태로 전환되어 빠른 실패 유도
     * - 모든 재시도 실패 또는 Circuit OPEN: fallback 메서드가 ExternalApiException 발생
     *
     * DB 저장 시에는 product_id, 대표 이미지 URL, shop_name 등 SearchResponse에 없는 필드가 필요하므로
     * 원본 DTO를 별도로 반환한다.
     */
    /**
     @Retry와 @CircuitBreaker가 붙어있을 땐 실행 순서가 중요하다.
     요청 -> @Retry(외부 래퍼) -> @CircuitBreaker(내부 래퍼) -> 실제 HTTP 호출(WebClient)
     Retry가 바깥을 감싸기 때문에, Circuit Breaker가 Open되기 전까지는 재시도를 반복한다.
     Circuit이 Open되면 재시도 자체를 시도하지 안혹 바로 fallback으로 넘어간다.
     */
    @Retry(name = "externalApiService")
    @CircuitBreaker(name = "externalApiService", fallbackMethod = "aliExpressSearchFallback")
    public List<AliExpressShoppingItem> searchAliExpressProductItems(
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

        log.info("external-api-service AliExpress 응답 수신 완료 - {}건 (전체: {})", response.items().size(), response.total());
        return response.items();
    }

    /**
     * AliExpress API 호출 실패 시 fallback 메서드
     * Circuit Breaker가 OPEN 상태이거나 재시도가 모두 실패한 경우 호출된다.
     * 빈 결과를 반환하지 않고 명확한 예외를 전파하여 장애 상황을 인지할 수 있도록 한다.
     *
     * @param keyword        검색 키워드
     * @param pageNo         페이지 번호
     * @param pageSize       페이지당 결과 수
     * @param sort           정렬 기준
     * @param targetCurrency 통화
     * @param targetLanguage 언어
     * @param shipToCountry  배송 국가
     * @param trackingId     트래킹 ID
     * @param t              발생한 예외 (Circuit Breaker CallNotPermittedException 또는 원본 예외)
     * @throws ExternalApiException 외부 API 호출 불가 예외
     */
    List<AliExpressShoppingItem> aliExpressSearchFallback(
            String keyword, int pageNo, int pageSize, String sort,
            String targetCurrency, String targetLanguage, String shipToCountry,
            String trackingId, Throwable t) {
        log.error("AliExpress API 호출 실패 (Circuit Breaker fallback) - 키워드: {}, 원인: {}", keyword, t.getMessage());
        throw new ExternalApiException(
                "external-api-service AliExpress API 호출 불가 (Circuit Breaker OPEN 또는 오류)", t);
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