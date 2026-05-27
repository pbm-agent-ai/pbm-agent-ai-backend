package com.pbm.price.client;

import com.pbm.price.dto.response.AliexpressProductDetailResponse;
import com.pbm.price.dto.response.AliExpressCategoryItem;
import com.pbm.price.dto.response.AliExpressCategoryResponse;
import com.pbm.price.dto.response.AliExpressSearchResponse;
import com.pbm.price.dto.response.AliExpressShoppingItem;
import com.pbm.price.dto.response.NaverSearchResponse;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.dto.response.SearchResponse;
import com.pbm.price.dto.response.YoutubeReviewAnalysisExternalResponse;
import com.pbm.price.dto.request.YoutubeReviewAnalysisRequest;
import com.pbm.price.exception.ExternalApiException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.protocol.types.Field;
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
                        item.link(),
                        item.image(),
                        "KRW",
                        item.productId()
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
     * 네이버 쇼핑 검색 (start 파라미터 지원 오버로드)
     * 기존 searchNaverProductItems(keyword, display)와의 호환성을 유지하면서
     * start 파라미터를 추가한 오버로드 메서드.
     * 주로 구독 기반 재조회(SubscriptionMonitoringService.refreshNaver)에서 사용된다.
     *
     * @param keyword 검색 키워드
     * @param display 검색 결과 개수 (최대 100)
     * @param start   검색 시작 위치 (최대 1000)
     * @return 네이버 원본 상품 목록
     */
    @Retry(name = "externalApiService")
    @CircuitBreaker(name = "externalApiService", fallbackMethod = "naverSearchFallback")
    public List<NaverShoppingItem> searchNaverProductItems(String keyword, int display, int start) {
        log.info("external-api-service 네이버 쇼핑 검색 요청 - 키워드: {}, 개수: {}, 시작: {}", keyword, display, start);

        NaverSearchResponse response = externalApiWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/naver/search")
                        .queryParam("keyword", keyword)
                        .queryParam("display", display)
                        .queryParam("start", start)
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
     * 네이버 쇼핑 검색 결과를 공통 SearchResponse로 매핑 (start 파라미터 지원 오버로드)
     * searchNaverProductItems(keyword, display, start)를 호출하고
     * 결과를 SearchResponse 목록으로 변환한다.
     *
     * @param keyword 검색 키워드
     * @param display 검색 결과 개수
     * @param start   검색 시작 위치
     * @return 검색된 상품 목록 (공통 SearchResponse 형식)
     */
    public List<SearchResponse> searchNaverProducts(String keyword, int display, int start) {
        return searchNaverProductItems(keyword, display, start).stream()
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

    /**
     * 네이버 쇼핑 API 호출 실패 시 fallback 메서드 (start 파라미터 오버로드)
     * searchNaverProductItems(keyword, display, start)에 대응하는 fallback.
     *
     * @param keyword 검색 키워드
     * @param display 검색 결과 개수
     * @param start   검색 시작 위치
     * @param t       발생한 예외 (Circuit Breaker CallNotPermittedException 또는 원본 예외)
     * @throws ExternalApiException 외부 API 호출 불가 예외
     */
    List<NaverShoppingItem> naverSearchFallback(String keyword, int display, int start, Throwable t) {
        log.error("네이버 쇼핑 API 호출 실패 (Circuit Breaker fallback) - 키워드: {}, 시작: {}, 원인: {}", keyword, start, t.getMessage());
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
     * @param categoryIds       카테고리 ID 목록 (선택, 콤마 구분)
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
            String categoryIds,
            String trackingId
    ) {
        return searchAliExpressProductItems(keyword, pageNo, pageSize, sort, targetCurrency, targetLanguage, shipToCountry, categoryIds, trackingId)
                .stream()
                .map(item -> mapAliExpressItemToSearchResponse(item, targetCurrency))
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
            String categoryIds,
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
                    if (categoryIds != null && !categoryIds.isBlank()) {
                        builder = builder.queryParam("category_ids", categoryIds);
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
            String categoryIds, String trackingId, Throwable t) {
        log.error("AliExpress API 호출 실패 (Circuit Breaker fallback) - 키워드: {}, 원인: {}", keyword, t.getMessage());
        throw new ExternalApiException(
                "external-api-service AliExpress API 호출 불가 (Circuit Breaker OPEN 또는 오류)", t);
    }

    /**
     * external-api-service를 통해 AliExpress 전체 카테고리 목록을 조회한다.
     * Circuit Breaker와 Retry 보호가 적용된 핵심 외부 API 호출 메서드이다.
     *
     * 응답으로 전체 카테고리 수(total)와 개별 항목(items)을 받아 items만 반환한다.
     * AliExpressCategorySyncService에서 이 목록을 받아 CategoryNode로 upsert한다.
     *
     * 장애 발생 시:
     * - 일시적 장애: 최대 3회 자동 재시도 (1초 간격)
     * - 지속적 장애: Circuit Breaker가 OPEN 상태로 전환되어 빠른 실패 유도
     * - 모든 재시도 실패 또는 Circuit OPEN: fallback 메서드가 ExternalApiException 발생
     *
     * @return AliExpress 카테고리 항목 목록 (빈 목록 가능)
     */
    @Retry(name = "externalApiService")
    @CircuitBreaker(name = "externalApiService", fallbackMethod = "aliExpressCategoryFallback")
    public List<AliExpressCategoryItem> fetchAliExpressCategories() {
        log.info("external-api-service AliExpress 카테고리 조회 요청");

        // external-api-service의 AliExpress 카테고리 엔드포인트 호출
        // 경로: GET /api/v1/aliexpress/categories
        // 응답: AliExpressCategoryResponse 래퍼 (total, items)
        AliExpressCategoryResponse response = externalApiWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/aliexpress/categories")
                        .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<AliExpressCategoryResponse>() {})
                .block();

        if (response == null || response.items() == null) {
            log.warn("external-api-service 카테고리 응답이 null이거나 items가 없음");
            return List.of();
        }

        log.info("external-api-service 카테고리 응답 수신 완료 - {}건 (전체: {})", response.items().size(), response.total());
        return response.items();
    }

    /**
     * AliExpress 카테고리 API 호출 실패 시 fallback 메서드
     * Circuit Breaker가 OPEN 상태이거나 재시도가 모두 실패한 경우 호출된다.
     * 빈 결과를 반환하지 않고 명확한 예외를 전파하여 장애 상황을 인지할 수 있도록 한다.
     *
     * @param t 발생한 예외 (Circuit Breaker CallNotPermittedException 또는 원본 예외)
     * @throws ExternalApiException 외부 API 호출 불가 예외
     */
    List<AliExpressCategoryItem> aliExpressCategoryFallback(Throwable t) {
        log.error("AliExpress 카테고리 API 호출 실패 (Circuit Breaker fallback) - 원인: {}", t.getMessage());
        throw new ExternalApiException(
                "external-api-service AliExpress 카테고리 API 호출 불가 (Circuit Breaker OPEN 또는 오류)", t);
    }

    /**
     * AliExpressShoppingItem을 공통 SearchResponse로 매핑
     * - lprice: target_sale_price가 있으면 사용, 없으면 sale_price 사용
     * - currency: target_sale_price 사용 시 targetCurrency (없으면 "KRW"), sale_price 사용 시 "USD"
     * - hprice: target_original_price 사용
     * - mallName: shop_name 사용
     * - link: product_detail_url 사용
     *
     * @param item AliExpress 상품 항목
     * @param targetCurrency 요청 시 지정된 통화
     * @return 공통 검색 응답 DTO
     */
    private SearchResponse mapAliExpressItemToSearchResponse(AliExpressShoppingItem item, String targetCurrency) {
        // target_sale_price가 있으면 KRW 변환 판매가를 최저가로 사용, 없으면 원래 sale_price 사용
        String lprice;
        String currency;
        if (item.target_sale_price() != null && !item.target_sale_price().isBlank()) {
            lprice = item.target_sale_price();
            currency = (targetCurrency != null && !targetCurrency.isBlank()) ? targetCurrency : "KRW";
        } else {
            lprice = item.sale_price();
            currency = "USD";
        }

        return new SearchResponse(
                item.product_title(),       // title ← product_title
                lprice,                      // lprice ← target_sale_price 또는 sale_price
                item.target_original_price(),// hprice ← target_original_price
                item.shop_name(),            // mallName ← shop_name
                item.product_detail_url(),   // productUrl ← product_detail_url
                item.product_main_image_url(), // imageUrl ← product_main_image_url
                currency,                    // 통화 코드
                item.product_id()            // productId ← product_id
        );
    }

    /**
     * external-api-service를 통해 AliExpress 상품 단건 상세를 조회한다.
     *
     * @param productId 조회할 AliExpress 상품 ID
     * @return 단건 조회 결과 DTO (없으면 product == null)
     */
    @Retry(name = "externalApiService")
    @CircuitBreaker(name = "externalApiService", fallbackMethod = "aliExpressProductDetailFallback")
    public AliexpressProductDetailResponse getAliExpressProductDetail(
            String productId,
            String targetCurrency,
            String targetLanguage,
            String shipToCountry
    ) {
        log.info("external-api-service AliExpress 단건 조회 요청 - productId: {}, targetCurrency: {}, targetLanguage: {}, shipToCountry: {}",
                productId, targetCurrency, targetLanguage, shipToCountry);

        AliexpressProductDetailResponse response = externalApiWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/aliexpress/products/{productId}")
                        .queryParam("target_currency", targetCurrency)
                        .queryParam("target_language", targetLanguage)
                        .queryParam("ship_to_country", shipToCountry)
                        .build(productId))
                .retrieve()
                .bodyToMono(AliexpressProductDetailResponse.class)
                .block();

        if (response == null) {
            log.warn("external-api-service AliExpress 단건 조회 응답이 null - productId: {}", productId);
            return new AliexpressProductDetailResponse(null);
        }

        return response;
    }

    AliexpressProductDetailResponse aliExpressProductDetailFallback(String productId,
                                                                    String targetCurrency,
                                                                    String targetLanguage,
                                                                    String shipToCountry,
                                                                    Throwable t) {
        log.error("AliExpress 단건 조회 실패 (Circuit Breaker fallback) - productId: {}, targetCurrency: {}, targetLanguage: {}, shipToCountry: {}, 원인: {}",
                productId, targetCurrency, targetLanguage, shipToCountry, t.getMessage());
        throw new ExternalApiException(
                "external-api-service AliExpress 단건 조회 불가 (Circuit Breaker OPEN 또는 오류)", t
        );
    }

    /**
     * external-api-service를 통해 YouTube 리뷰 영상 자막을 수집하고 AI 분석을 수행한다.
     * <p>
     * 내부 동작:
     * 1. external-api-service가 youtube-transcript-api로 자막 수집
     * 2. GPT로 상품 순위·장단점·총평 추출
     * 3. 분석 결과를 구조화된 JSON으로 반환
     * <p>
     * AI 분석 시간이 길 수 있으므로 timeout은 WebClientConfig의 responseTimeout을 따른다.
     *
     * @param request 영상 URL, 유튜버 이름, 언어 설정 등
     * @return AI 분석 결과 (상품 순위·장단점·총평)
     */
    @Retry(name = "externalApiService")
    @CircuitBreaker(name = "externalApiService", fallbackMethod = "youtubeAnalyzeFallback")
    public YoutubeReviewAnalysisExternalResponse analyzeYoutubeReview(YoutubeReviewAnalysisRequest request) {
        log.info("external-api-service YouTube 리뷰 분석 요청 - videoUrl: {}, youtuber: {}",
                request.videoUrl(), request.youtuberName());

        // external-api-service의 YouTube 분석 엔드포인트 호출
        // 경로: POST /api/v1/youtube/analyze-review
        YoutubeReviewAnalysisExternalResponse response = externalApiWebClient.post()
                .uri("/api/v1/youtube/analyze-review")
                .bodyValue(java.util.Map.of(
                        "video_id", request.videoUrl(),
                        "youtuber_name", request.youtuberName(),
                        "languages", request.languages() != null
                                ? request.languages()
                                : java.util.List.of("ko", "ko-KR", "en"),
                        "conclusion_ratio", request.conclusionRatio() != null
                                ? request.conclusionRatio()
                                : 0.3
                ))
                .retrieve()
                .bodyToMono(YoutubeReviewAnalysisExternalResponse.class)
                .block();

        if (response == null) {
            log.warn("external-api-service YouTube 분석 응답이 null - videoUrl: {}", request.videoUrl());
            throw new ExternalApiException("YouTube 분석 응답이 null입니다.", null);
        }

        log.info("external-api-service YouTube 분석 완료 - videoId: {}, category: {}, products: {}건",
                response.videoId(), response.category(), response.products().size());
        return response;
    }

    YoutubeReviewAnalysisExternalResponse youtubeAnalyzeFallback(YoutubeReviewAnalysisRequest request, Throwable t) {
        log.error("YouTube 리뷰 분석 실패 (Circuit Breaker fallback) - videoUrl: {}, 원인: {}",
                request.videoUrl(), t.getMessage());
        throw new ExternalApiException(
                "external-api-service YouTube 분석 불가 (Circuit Breaker OPEN 또는 오류)", t);
    }
}
