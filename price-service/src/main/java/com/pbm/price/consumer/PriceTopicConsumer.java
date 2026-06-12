package com.pbm.price.consumer;

import com.pbm.price.domain.Platform;
import com.pbm.price.dto.event.PriceRequestEvent;
import com.pbm.price.dto.event.ProductCandidateDto;
import com.pbm.price.dto.event.ProductSelectionRequiredEvent;
import com.pbm.price.dto.event.ProductSelectionRequiredEventPayload;
import com.pbm.price.dto.response.SearchResponse;
import com.pbm.price.publisher.ProductSelectionRequiredEventPublisher;
import com.pbm.price.service.AliExpressCategoryIdResolver;
import com.pbm.price.service.AliExpressProductUrlService;
import com.pbm.price.service.AliExpressShoppingService;
import com.pbm.price.service.NaverProductUrlService;
import com.pbm.price.service.NaverShoppingService;
import com.pbm.price.service.UrlMonitoringService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * price-topic 메시지 소비 컴포넌트.
 *
 * 역할: command-service 등에서 발행한 가격 확인 요청(PriceRequestEvent)을 수신하여
 *       플랫폼(NAVER, ALIEXPRESS)에 따라 해당 쇼핑 API로 상품을 검색하고,
 *       상위 후보 상품 목록을 command-service로 전달한다.
 * 동작:
 *   1. price-topic에서 PriceRequestEvent 수신
 *   2. payload.platform에 따라 네이버 또는 AliExpress 쇼핑 검색 호출 (최대 30건 저장)
 *   3. 검색 결과가 없으면 빈 후보 목록과 안내 메시지를 포함한 후보 선택 이벤트를 발행
 *   4. 검색 결과가 있으면 상위 후보 목록을 후보 선택 이벤트로 발행
 *   5. 이후 실제 가격 비교/모니터링 등록은 사용자의 상품 선택 후 downstream consumer가 담당
 * 연관: PriceRequestEvent, ProductSelectionRequiredEvent, NaverShoppingService,
 *       AliExpressShoppingService, Platform, ProductSelectionRequiredEventPublisher.
 */
@Slf4j
@Component
public class PriceTopicConsumer {

    private final NaverShoppingService naverShoppingService;
    private final AliExpressShoppingService aliExpressShoppingService;
    private final AliExpressCategoryIdResolver aliExpressCategoryIdResolver;
    private final AliExpressProductUrlService aliExpressProductUrlService;
    private final NaverProductUrlService naverProductUrlService;
    private final ProductSelectionRequiredEventPublisher productSelectionRequiredEventPublisher;
    private final UrlMonitoringService urlMonitoringService;

    public PriceTopicConsumer(NaverShoppingService naverShoppingService,
                              AliExpressShoppingService aliExpressShoppingService,
                              AliExpressCategoryIdResolver aliExpressCategoryIdResolver,
                              AliExpressProductUrlService aliExpressProductUrlService,
                              NaverProductUrlService naverProductUrlService,
                              ProductSelectionRequiredEventPublisher productSelectionRequiredEventPublisher,
                              UrlMonitoringService urlMonitoringService) {
        this.naverShoppingService = naverShoppingService;
        this.aliExpressShoppingService = aliExpressShoppingService;
        this.aliExpressCategoryIdResolver = aliExpressCategoryIdResolver;
        this.aliExpressProductUrlService = aliExpressProductUrlService;
        this.naverProductUrlService = naverProductUrlService;
        this.productSelectionRequiredEventPublisher = productSelectionRequiredEventPublisher;
        this.urlMonitoringService = urlMonitoringService;
    }

    /**
     * price-topic 메시지 수신 및 후보 상품 조회 처리.
     * payload.platform에 따라 해당 플랫폼 쇼핑 API를 호출하고,
     * 검색 결과 최대 30건을 command-service로 전달한다.
     *
     * @param event 수신한 가격 확인 요청 이벤트
     */
    @KafkaListener(topics = "${app.kafka.topics.price-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(PriceRequestEvent event) {
        log.info("price-topic 메시지 수신 - eventId: {}, keyword: {}, targetPrice: {}, platform: {}, currency: {}",
                event.eventId(), event.payload().keyword(), event.payload().targetPrice(),
                event.payload().platform(), event.payload().currency());

        // URL_MONITOR_REQUEST: URL 기반 MonitoringSubscription 생성
        if ("URL_MONITOR_REQUEST".equals(event.eventType())) {
            handleUrlMonitorRequest(event);
            return;
        }

        // 플랫폼별 쇼핑 API로 상품 검색 (최대 30건 저장)
        List<SearchResponse> results = searchProductsByPlatform(event);

        if (results.isEmpty()) {
            log.info("검색 결과 없음 - commandId: {}, keyword: {}",
                    event.payload().commandId(), event.payload().keyword());

            ProductSelectionRequiredEvent selectionRequiredEvent = createSelectionRequiredEvent(
                    event,
                    List.of(),
                    "검색 결과가 없습니다. 검색어를 바꿔 다시 시도해주세요.",
                    List.of()
            );
            productSelectionRequiredEventPublisher.publish(selectionRequiredEvent);
            return;
        }

        ProductSelectionRequiredEvent selectionRequiredEvent = createSelectionRequiredEvent(
                event,
                List.of(),
                "검색 결과를 확인하고 상품을 선택해주세요.",
                results
        );
        productSelectionRequiredEventPublisher.publish(selectionRequiredEvent);

        log.info("후보 상품 선택 요청 이벤트 발행 완료 - commandId: {}, candidateCount: {}",
                event.payload().commandId(), Math.min(results.size(), 30));
    }

    /**
     * 이벤트의 platform 필드에 따라 적절한 쇼핑 검색 서비스를 호출한다.
     * platform이 null/blank이거나 지원하지 않는 값이면 빈 리스트를 반환한다.
     *
     * @param event 가격 확인 요청 이벤트
     * @return 검색 결과 목록 (실패 시 빈 리스트)
     */
    private List<SearchResponse> searchProductsByPlatform(PriceRequestEvent event) {
        if (hasDirectNaverUrls(event)) {
            return naverProductUrlService.resolveProductsByUrls(
                    event.payload().searchKeyword(),
                    event.payload().productUrls()
            );
        }

        if (hasDirectAliExpressUrls(event)) {
            return aliExpressProductUrlService.resolveProductsByUrls(
                    event.payload().productUrls(),
                    "KRW",
                    "KO",
                    "KR"
            );
        }

        String platform = event.payload().platform();
        if (platform == null || platform.isBlank()) {
            log.warn("요청에 플랫폼 정보가 없습니다 - eventId: {}, 검색을 건너뜁니다.", event.eventId());
            return Collections.emptyList();
        }

        Platform platformEnum;
        try {
            platformEnum = Platform.valueOf(platform.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("지원하지 않는 플랫폼입니다 - eventId: {}, platform: {}, 검색을 건너뜁니다.",
                    event.eventId(), platform);
            return Collections.emptyList();
        }

        String keyword = event.payload().keyword();
        String currencyOrDefault = (event.payload().currency() != null && !event.payload().currency().isBlank())
                ? event.payload().currency() : "KRW";

        switch (platformEnum) {
            case NAVER:
                log.info("네이버 쇼핑 검색 실행 - keyword: {}, platform: {}", keyword, platform);
                return naverShoppingService.searchProducts(keyword, 30);
            case ALIEXPRESS:
                String categoryIds = resolveAliExpressCategoryIds(event);
                log.info("AliExpress 쇼핑 검색 실행 - keyword: {}, platform: {}, currency: {}",
                        keyword, platform, currencyOrDefault);
                return aliExpressShoppingService.searchProducts(
                        keyword, 1, 20, null, currencyOrDefault, "KO", "KR", categoryIds, null
                );
            default:
                log.warn("지원하지 않는 플랫폼입니다 - eventId: {}, platform: {}, 검색을 건너뜁니다.",
                        event.eventId(), platform);
                return Collections.emptyList();
        }
    }

    /**
     * ProductSelectionRequiredEvent 객체를 생성한다.
     * eventId는 새로 생성하고, eventType은 PRODUCT_SELECTION_REQUIRED로 설정한다.
     * payload에는 commandId, categoryPath, missingFields, message와 함께
     * 후보 상품 목록(candidates), 목표 가격(targetPrice), 사용자 의도(intent)를 담는다.
     *
     * @param candidates 후보 상품 목록 (null 가능, null이면 빈 리스트로 처리)
     */
    private ProductSelectionRequiredEvent createSelectionRequiredEvent(
            PriceRequestEvent requestEvent,
            List<String> missingFields,
            String message,
            List<SearchResponse> candidates) {
        return new ProductSelectionRequiredEvent(
                UUID.randomUUID().toString(),
                "PRODUCT_SELECTION_REQUIRED",
                Instant.now(),
                "price-service",
                new ProductSelectionRequiredEventPayload(
                        requestEvent.payload().commandId(),
                        null, // categoryPath — 현재 흐름에서는 아직 신뢰할 수 없어 null
                        missingFields,
                        message,
                        mapToCandidateDtos(
                                candidates,
                                requestEvent.payload().platform(),
                                requestEvent.payload().keyword()
                        ),
                        requestEvent.payload().targetPrice(),
                        requestEvent.payload().intent(),
                        requestEvent.payload().keyword() // searchKeyword
                )
        );
    }

    /**
     * SearchResponse 목록을 ProductCandidateDto 목록으로 매핑한다.
     * productId는 플랫폼이 반환한 실제 식별자를 우선 사용하고,
     * 없을 때만 productUrl로 폴백한다.
     *
     * @param results 검색 결과 목록 (null 가능)
     * @return 후보 상품 DTO 목록 (null 입력 시 빈 리스트)
     */
    private List<ProductCandidateDto> mapToCandidateDtos(List<SearchResponse> results,
                                                         String platform,
                                                         String searchKeyword) {
        if (results == null) {
            return List.of();
        }
        return results.stream()
                .limit(30)
                .map(r -> new ProductCandidateDto(
                        r.productId() != null && !r.productId().isBlank()
                                ? r.productId()
                                : (r.productUrl() != null ? r.productUrl() : UUID.randomUUID().toString()),
                        r.title(),
                        r.lprice(),
                        r.mallName(),
                        r.productUrl(),
                        r.imageUrl(),
                        r.currency(),
                        platform,
                        searchKeyword
                ))
                .toList();
    }

    private boolean hasDirectAliExpressUrls(PriceRequestEvent event) {
        return event.payload().productUrls() != null
                && !event.payload().productUrls().isEmpty()
                && "ALIEXPRESS".equalsIgnoreCase(event.payload().platform());
    }

    /**
     * URL_MONITOR_REQUEST 이벤트를 처리하여 URL 기반 MonitoringSubscription을 생성한다.
     * payload.productUrl에 단건 URL이 담겨 있다.
     */
    private void handleUrlMonitorRequest(PriceRequestEvent event) {
        try {
            String productUrl = event.payload().productUrl();
            if (productUrl == null || productUrl.isBlank()) {
                log.warn("URL_MONITOR_REQUEST에 productUrl이 없습니다. eventId: {}", event.eventId());
                return;
            }

            // Kafka 이벤트에서 urlCondition 추출 (없으면 기본값 ALL)
            String urlCondition = (event.payload().urlCondition() != null && !event.payload().urlCondition().isBlank())
                    ? event.payload().urlCondition()
                    : "ALL";

            urlMonitoringService.createSubscription(
                    event.payload().userId(),
                    event.payload().commandId(),
                    productUrl,
                    event.payload().targetPrice(),
                    event.payload().currency(),
                    urlCondition,
                    event.payload().intent()
            );

            log.info("URL 모니터링 구독 생성 완료 - commandId: {}, url: {}",
                    event.payload().commandId(), productUrl);
        } catch (Exception e) {
            log.error("URL_MONITOR_REQUEST 처리 실패 - eventId: {}, 원인: {}",
                    event.eventId(), e.getMessage(), e);
        }
    }

    private boolean hasDirectNaverUrls(PriceRequestEvent event) {
        return event.payload().productUrls() != null
                && !event.payload().productUrls().isEmpty()
                && "NAVER".equalsIgnoreCase(event.payload().platform());
    }

    private String resolveAliExpressCategoryIds(PriceRequestEvent event) {
        if (event.payload().parsedCommandSnapshot() == null) {
            return null;
        }

        return aliExpressCategoryIdResolver.resolveCategoryIds(
                event.payload().parsedCommandSnapshot().searchCategoryHint()
        ).orElse(null);
    }
}
