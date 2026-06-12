package com.pbm.price.consumer;

import com.pbm.price.client.PaymentServiceClient;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.event.PriceRequestEvent;
import com.pbm.price.dto.event.PriceValidationResultEvent;
import com.pbm.price.dto.event.PriceValidationResultEventPayload;
import com.pbm.price.dto.event.ProductCandidateDto;
import com.pbm.price.dto.event.ProductSelectionRequiredEvent;
import com.pbm.price.dto.event.ProductSelectionRequiredEventPayload;
import com.pbm.price.dto.response.SearchResponse;
import com.pbm.price.publisher.PriceValidationResultEventPublisher;
import com.pbm.price.publisher.ProductSelectionRequiredEventPublisher;
import com.pbm.price.service.AliExpressCategoryIdResolver;
import com.pbm.price.service.AliExpressProductUrlService;
import com.pbm.price.service.AliExpressShoppingService;
import com.pbm.price.service.MonitoringSubscriptionService;
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
    private final MonitoringSubscriptionService monitoringSubscriptionService;
    private final PaymentServiceClient paymentServiceClient;
    private final PriceValidationResultEventPublisher priceValidationResultEventPublisher;

    public PriceTopicConsumer(NaverShoppingService naverShoppingService,
                              AliExpressShoppingService aliExpressShoppingService,
                              AliExpressCategoryIdResolver aliExpressCategoryIdResolver,
                              AliExpressProductUrlService aliExpressProductUrlService,
                              NaverProductUrlService naverProductUrlService,
                              ProductSelectionRequiredEventPublisher productSelectionRequiredEventPublisher,
                              UrlMonitoringService urlMonitoringService,
                              MonitoringSubscriptionService monitoringSubscriptionService,
                              PaymentServiceClient paymentServiceClient,
                              PriceValidationResultEventPublisher priceValidationResultEventPublisher) {
        this.naverShoppingService = naverShoppingService;
        this.aliExpressShoppingService = aliExpressShoppingService;
        this.aliExpressCategoryIdResolver = aliExpressCategoryIdResolver;
        this.aliExpressProductUrlService = aliExpressProductUrlService;
        this.naverProductUrlService = naverProductUrlService;
        this.productSelectionRequiredEventPublisher = productSelectionRequiredEventPublisher;
        this.urlMonitoringService = urlMonitoringService;
        this.monitoringSubscriptionService = monitoringSubscriptionService;
        this.paymentServiceClient = paymentServiceClient;
        this.priceValidationResultEventPublisher = priceValidationResultEventPublisher;
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
     * <p>
     * 즉시 충족 시나리오 (currentPrice != null && AUTO_PURCHASE && currentPrice <= targetPrice):
     *   - 세션키: 키페어 생성 + DB 저장 후, PaymentServiceClient로 동기 REST 등록
     *   - 구독 상태: TRIGGERED로 즉시 전환
     *   - PriceValidationResultEvent 발행하여 command-service가 브라우저 구매 흐름 시작
     * <p>
     * 모니터링 시나리오 (그 외):
     *   - 세션키: 키페어 생성 + Kafka 비동기 이벤트 발행
     *   - 구독 상태: ACTIVE 유지, 스케줄러가 주기적으로 가격 확인
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

            Integer currentPrice = event.payload().currentPrice();
            Integer targetPrice = event.payload().targetPrice();
            String intent = event.payload().intent();
            boolean isImmediateFulfillment = currentPrice != null
                    && "AUTO_PURCHASE".equals(intent)
                    && targetPrice != null
                    && currentPrice <= targetPrice;

            if (isImmediateFulfillment) {
                // ── 즉시 충족: 동기 REST로 세션키 등록 + TRIGGERED + PriceValidationResultEvent ──
                handleUrlImmediateFulfillment(event, productUrl, urlCondition, currentPrice);
            } else {
                // ── 모니터링: 기존 비동기 Kafka 흐름 ──
                handleUrlMonitoring(event, productUrl, urlCondition);
            }

            log.info("URL 모니터링 구독 처리 완료 - commandId: {}, url: {}, intent: {}, immediate: {}",
                    event.payload().commandId(), productUrl, intent, isImmediateFulfillment);
        } catch (Exception e) {
            log.error("URL_MONITOR_REQUEST 처리 실패 - eventId: {}, 원인: {}",
                    event.eventId(), e.getMessage(), e);
        }
    }

    /**
     * URL 즉시 충족 처리.
     * 현재 가격이 이미 목표 가격 이하이므로 모니터링 없이 바로 구매 흐름으로 진입한다.
     * ProductSelectionConsumer.handleAutoPurchase()의 즉시 충족 패턴과 동일한 흐름.
     *
     * @param event        원본 이벤트
     * @param productUrl   상품 URL
     * @param urlCondition ALL | ANY
     * @param currentPrice 익스텐션이 추출한 현재 가격
     */
    private void handleUrlImmediateFulfillment(
            PriceRequestEvent event, String productUrl, String urlCondition, int currentPrice
    ) {
        log.info("URL 즉시 충족 시작 - commandId: {}, currentPrice: {}, targetPrice: {}, url: {}",
                event.payload().commandId(), currentPrice, event.payload().targetPrice(), productUrl);

        // 1. 구독 생성
        MonitoringSubscription subscription = urlMonitoringService.createSubscription(
                event.payload().userId(),
                event.payload().commandId(),
                productUrl,
                event.payload().targetPrice(),
                event.payload().currency(),
                urlCondition,
                event.payload().intent()
        );

        // 2. 세션키 등록 — 키페어 생성 + DB 저장만 (Kafka 발행 생략, 동기 REST로 등록)
        monitoringSubscriptionService.registerSessionKeyForSubscription(
                subscription, null, false  // publishKafkaEvent=false
        );

        // 3. 동기 REST로 세션키 등록 (블록체인 트랜잭션 완료까지 대기)
        String aiAgentPrivateKey = null;
        if (subscription.getAiAgentPrivateKey() != null) {
            boolean sessionKeyRegistered = paymentServiceClient.registerSessionKey(
                    event.payload().userId(),
                    subscription.getId(),
                    subscription.getAiAgentAddress(),
                    subscription.getAiAgentPrivateKey(),
                    subscription.getTargetPrice().longValue(),
                    calculateValidSeconds(subscription.getScheduledEndAt()),
                    resolveEffectivePlatform(productUrl)
            );
            if (sessionKeyRegistered) {
                aiAgentPrivateKey = subscription.getAiAgentPrivateKey();
                log.info("URL 즉시 충족 - 세션키 동기 등록 성공 - subscriptionId: {}", subscription.getId());
            } else {
                log.warn("URL 즉시 충족 - 세션키 동기 등록 실패 - subscriptionId: {}", subscription.getId());
            }
        }

        // 4. 구독 상태 TRIGGERED 전환
        subscription.changeStatus(MonitoringSubscriptionStatus.TRIGGERED);

        // 5. PriceValidationResultEvent 발행 → command-service가 브라우저 구매 흐름 시작
        String effectivePlatform = resolveEffectivePlatform(productUrl);
        ProductCandidateDto candidateDto = new ProductCandidateDto(
                subscription.getProductId(),
                subscription.getSnapshotTitle() != null ? subscription.getSnapshotTitle() : productUrl,
                String.valueOf(currentPrice),
                null,
                productUrl,
                subscription.getSnapshotImageUrl(),
                "KRW",
                effectivePlatform,
                null
        );

        PriceValidationResultEvent resultEvent = new PriceValidationResultEvent(
                UUID.randomUUID().toString(),
                "PRICE_VALIDATION_COMPLETED",
                Instant.now(),
                "price-service",
                new PriceValidationResultEventPayload(
                        subscription.getId(),
                        event.payload().commandId(),
                        "BROWSER_PURCHASE_IN_PROGRESS",
                        List.of(candidateDto),   // triggeredProducts
                        List.of(),               // monitoringProducts
                        null,                    // purchasedProductId
                        "URL 모니터링 즉시 충족 - 현재가 " + currentPrice + "원 ≤ 목표가 " + event.payload().targetPrice() + "원",
                        false,
                        List.of(),
                        null,
                        currentPrice,            // triggerPrice
                        aiAgentPrivateKey        // AI 에이전트 개인키
                )
        );
        priceValidationResultEventPublisher.publish(resultEvent);

        log.info("URL 즉시 충족 완료 - subscriptionId: {}, effectivePlatform: {}, triggerPrice: {}",
                subscription.getId(), effectivePlatform, currentPrice);
    }

    /**
     * URL 모니터링 등록 처리 (기존 비동기 흐름).
     * 목표 가격 미달이거나 현재 가격 정보가 없는 경우, 주기적 모니터링을 등록한다.
     */
    private void handleUrlMonitoring(PriceRequestEvent event, String productUrl, String urlCondition) {
        MonitoringSubscription subscription = urlMonitoringService.createSubscription(
                event.payload().userId(),
                event.payload().commandId(),
                productUrl,
                event.payload().targetPrice(),
                event.payload().currency(),
                urlCondition,
                event.payload().intent()
        );

        // AUTO_PURCHASE intent면 세션키 등록 (Kafka 비동기, 결제 시 PBM 토큰 차감에 필요)
        if ("AUTO_PURCHASE".equals(event.payload().intent())) {
            monitoringSubscriptionService.registerSessionKeyForSubscription(
                    subscription, null  // scheduledEndAt null → 기본 7일, publishKafkaEvent=true (기본값)
            );
            log.info("URL 모니터링 AUTO_PURCHASE 세션키 등록 완료 - subscriptionId: {}, url: {}",
                    subscription.getId(), productUrl);
        }
    }

    /**
     * 모니터링 종료 예정 시각까지의 유효 기간(초)을 계산한다.
     */
    private long calculateValidSeconds(Instant scheduledEndAt) {
        if (scheduledEndAt != null) {
            return Math.max(0, scheduledEndAt.getEpochSecond() - Instant.now().getEpochSecond());
        }
        // 기본값: 7일
        return 7L * 24 * 3600;
    }

    /**
     * URL의 실질 플랫폼을 URL 패턴으로 추론한다.
     * AliExpress URL이면 "ALIEXPRESS", 그 외면 "URL" 반환.
     */
    private String resolveEffectivePlatform(String productUrl) {
        if (productUrl != null && productUrl.contains("aliexpress.com")) {
            return "ALIEXPRESS";
        }
        return "URL";
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
