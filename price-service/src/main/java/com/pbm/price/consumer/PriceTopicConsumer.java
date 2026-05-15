package com.pbm.price.consumer;

import com.pbm.price.domain.Platform;
import com.pbm.price.dto.event.PriceRequestEvent;
import com.pbm.price.dto.event.ProductCandidateDto;
import com.pbm.price.dto.event.ProductSelectionRequiredEvent;
import com.pbm.price.dto.event.ProductSelectionRequiredEventPayload;
import com.pbm.price.dto.response.SearchResponse;
import com.pbm.price.publisher.ProductSelectionRequiredEventPublisher;
import com.pbm.price.service.AliExpressCategoryIdResolver;
import com.pbm.price.service.AliExpressShoppingService;
import com.pbm.price.service.NaverShoppingService;
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
 *   2. payload.platform에 따라 네이버 또는 AliExpress 쇼핑 검색 호출 (최대 10건)
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
    private final ProductSelectionRequiredEventPublisher productSelectionRequiredEventPublisher;

    public PriceTopicConsumer(NaverShoppingService naverShoppingService,
                              AliExpressShoppingService aliExpressShoppingService,
                              AliExpressCategoryIdResolver aliExpressCategoryIdResolver,
                              ProductSelectionRequiredEventPublisher productSelectionRequiredEventPublisher) {
        this.naverShoppingService = naverShoppingService;
        this.aliExpressShoppingService = aliExpressShoppingService;
        this.aliExpressCategoryIdResolver = aliExpressCategoryIdResolver;
        this.productSelectionRequiredEventPublisher = productSelectionRequiredEventPublisher;
    }

    /**
     * price-topic 메시지 수신 및 후보 상품 조회 처리.
     * payload.platform에 따라 해당 플랫폼 쇼핑 API를 호출하고,
     * 검색 결과 상위 후보를 command-service로 전달한다.
     *
     * @param event 수신한 가격 확인 요청 이벤트
     */
    @KafkaListener(topics = "${app.kafka.topics.price-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(PriceRequestEvent event) {
        log.info("price-topic 메시지 수신 - eventId: {}, keyword: {}, targetPrice: {}, platform: {}, currency: {}",
                event.eventId(), event.payload().keyword(), event.payload().targetPrice(),
                event.payload().platform(), event.payload().currency());

        // 플랫폼별 쇼핑 API로 상품 검색 (상위 10건)
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
                event.payload().commandId(), Math.min(results.size(), 10));
    }

    /**
     * 이벤트의 platform 필드에 따라 적절한 쇼핑 검색 서비스를 호출한다.
     * platform이 null/blank이거나 지원하지 않는 값이면 빈 리스트를 반환한다.
     *
     * @param event 가격 확인 요청 이벤트
     * @return 검색 결과 목록 (실패 시 빈 리스트)
     */
    private List<SearchResponse> searchProductsByPlatform(PriceRequestEvent event) {
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
                return naverShoppingService.searchProducts(keyword, 10);
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
                .limit(10)
                .map(r -> new ProductCandidateDto(
                        r.productId() != null && !r.productId().isBlank()
                                ? r.productId()
                                : (r.productUrl() != null ? r.productUrl() : UUID.randomUUID().toString()),
                        r.title(),
                        r.lprice(),
                        r.mallName(),
                        r.productUrl(),
                        r.currency(),
                        platform,
                        searchKeyword
                ))
                .toList();
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
