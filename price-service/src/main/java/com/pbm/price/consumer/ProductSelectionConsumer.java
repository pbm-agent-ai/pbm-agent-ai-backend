package com.pbm.price.consumer;

import com.pbm.price.client.PaymentServiceClient;
import com.pbm.price.common.PriceCurrencyConverter;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.dto.event.PriceValidationResultEvent;
import com.pbm.price.dto.event.PriceValidationResultEventPayload;
import com.pbm.price.dto.event.ProductCandidateDto;
import com.pbm.price.dto.event.ProductSelectionEvent;
import com.pbm.price.publisher.PriceValidationResultEventPublisher;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.service.MonitoringSubscriptionService;
import com.pbm.price.service.SubscriptionMonitoringService;
import com.pbm.price.service.UrlMonitoringService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * product-selection 토픽 메시지 소비 컴포넌트.
 *
 * 역할: command-service가 PRODUCT_SELECTION_REQUIRED 단계에서 사용자가 선택한
 *       ProductSelectionEvent를 수신하여 각 상품을 실시간 단건 재조회로 검증하고,
 *       즉시 구매/모니터링 등록 결과를 분기 처리한다.
 * 동작:
 *   1. product-selection 토픽에서 ProductSelectionEvent 수신
 *   2. 선택된 상품 각각을 단건 재조회하여 현재 가격을 검증
 *   3. intent에 따라 즉시 구매 후보 / 모니터링 등록 후보를 분리
 *   4. 즉시 충족 시 동기적으로 세션키 등록
 *   5. 결과를 price-validation-result 토픽으로 command-service에 반환
 * 연관: ProductSelectionEvent, MonitoringSubscriptionService, SubscriptionMonitoringService, PaymentServiceClient.
 */
@Slf4j
@Component
public class ProductSelectionConsumer {

    private final MonitoringSubscriptionService monitoringSubscriptionService;
    private final SubscriptionMonitoringService subscriptionMonitoringService;
    private final UrlMonitoringService urlMonitoringService;
    private final PriceValidationResultEventPublisher priceValidationResultEventPublisher;
    private final PriceCurrencyConverter priceCurrencyConverter;
    private final PaymentServiceClient paymentServiceClient;

    public ProductSelectionConsumer(
            MonitoringSubscriptionService monitoringSubscriptionService,
            SubscriptionMonitoringService subscriptionMonitoringService,
            UrlMonitoringService urlMonitoringService,
            PriceValidationResultEventPublisher priceValidationResultEventPublisher,
            PriceCurrencyConverter priceCurrencyConverter,
            PaymentServiceClient paymentServiceClient
    ) {
        this.monitoringSubscriptionService = monitoringSubscriptionService;
        this.subscriptionMonitoringService = subscriptionMonitoringService;
        this.urlMonitoringService = urlMonitoringService;
        this.priceValidationResultEventPublisher = priceValidationResultEventPublisher;
        this.priceCurrencyConverter = priceCurrencyConverter;
        this.paymentServiceClient = paymentServiceClient;
    }

    /**
     * product-selection 토픽 메시지 수신 및 구독 등록/즉시 1회 처리 위임.
     * <p>
     * 선택 이벤트를 받은 즉시 구독을 생성/갱신한 뒤,
     * 스케줄러가 사용하는 공통 처리 로직을 재사용하여 첫 가격 비교를 즉시 수행한다.
     *
     * @param event 수신한 product-selection 이벤트
     */
    @KafkaListener(
            topics = "${app.kafka.topics.product-selection-topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "productSelectionContainerFactory"
    )
    public void consume(ProductSelectionEvent event) {
        log.info("product-selection 메시지 수신 - eventId: {}, commandId: {}, selectedCount: {}",
                event.eventId(), event.payload().commandId(),
                event.payload().selectedProducts() != null ? event.payload().selectedProducts().size() : 0);

        SelectionProcessingResult result = processByIntent(event);
        publishValidationResult(event, result);
    }

    private SelectionProcessingResult processByIntent(ProductSelectionEvent event) {
        if (event.payload().selectedProducts() == null || event.payload().selectedProducts().isEmpty()) {
            throw new IllegalArgumentException("selectedProducts는 최소 1개 이상이어야 합니다.");
        }

        if (!Boolean.TRUE.equals(event.payload().forceResubscribe())) {
            List<ProductCandidateDto> duplicateProducts = monitoringSubscriptionService.findDuplicateSelections(
                    event.payload().userId(),
                    event.payload().selectedProducts()
            );
            if (duplicateProducts != null && !duplicateProducts.isEmpty()) {
                return buildDuplicateConfirmationResult(duplicateProducts);
            }
        }

        List<EvaluatedProduct> matchedProducts = new ArrayList<>();
        List<ProductCandidateDto> monitoringProducts = new ArrayList<>();
        BigDecimal targetPrice = event.payload().targetPrice() == null
                ? null
                : BigDecimal.valueOf(event.payload().targetPrice());

        for (ProductCandidateDto selectedProduct : event.payload().selectedProducts()) {
            SubscriptionMonitoringService.NormalizedProductSnapshot snapshot =
                    subscriptionMonitoringService.refreshSelectedProduct(selectedProduct);

            // 선택 직후 검증에서도 USD 상품을 바로 탈락시키지 않고,
            // 수출입은행 실시간 환율을 적용해 KRW 기준으로 비교한다.
            BigDecimal currentPriceInKrw = priceCurrencyConverter.toKrw(snapshot.currentPrice(), snapshot.currency());

            if (!snapshot.found() || currentPriceInKrw == null) {
                monitoringProducts.add(selectedProduct);
                continue;
            }

            ProductCandidateDto refreshedCandidate = new ProductCandidateDto(
                    snapshot.productId(),
                    snapshot.title(),
                    currentPriceInKrw.toPlainString(),
                    selectedProduct.mallName(),
                    snapshot.productUrl(),
                    selectedProduct.imageUrl(),
                    "KRW",
                    selectedProduct.platform(),
                    selectedProduct.searchKeyword()
            );

            if (targetPrice != null && currentPriceInKrw.compareTo(targetPrice) <= 0) {
                matchedProducts.add(new EvaluatedProduct(refreshedCandidate, currentPriceInKrw));
            } else {
                monitoringProducts.add(refreshedCandidate);
            }
        }

        return switch (event.payload().intent()) {
            case "PRICE_CHECK" -> handlePriceCheck(matchedProducts);
            case "PRICE_TRACK" -> handlePriceTrack(event, matchedProducts, monitoringProducts);
            case "AUTO_PURCHASE" -> handleAutoPurchase(event, matchedProducts, monitoringProducts);
            default -> throw new IllegalArgumentException("지원하지 않는 intent입니다: " + event.payload().intent());
        };
    }

    private SelectionProcessingResult handlePriceCheck(List<EvaluatedProduct> matchedProducts) {
        return new SelectionProcessingResult(
                matchedProducts.stream().map(EvaluatedProduct::candidate).toList(),
                List.of(),
                null,
                "PRICE_CHECK 검증 완료",
                "PRICE_CHECK_COMPLETED",
                false,
                List.of(),
                null,
                null,
                null
        );
    }

    private SelectionProcessingResult handlePriceTrack(
            ProductSelectionEvent event,
            List<EvaluatedProduct> matchedProducts,
            List<ProductCandidateDto> monitoringProducts
    ) {
        List<ProductCandidateDto> triggeredProducts = matchedProducts.stream()
                .map(EvaluatedProduct::candidate)
                .toList();

        for (ProductCandidateDto triggeredProduct : triggeredProducts) {
            MonitoringSubscription subscription = monitoringSubscriptionService.createOrUpdateFromSelection(
                    event.payload().userId(),
                    event.payload().commandId(),
                    event.payload().targetPrice(),
                    event.payload().intent(),
                    triggeredProduct,
                    event.payload().scheduledEndAt()
            );
            // AliExpress: API 재검증 불가 → process() 스킵
            if (!"ALIEXPRESS".equals(triggeredProduct.platform())) {
                subscriptionMonitoringService.process(subscription.getId());
            }
        }

        List<ProductCandidateDto> registeredMonitoringProducts = registerMonitoringProducts(event, monitoringProducts);
        return new SelectionProcessingResult(
                triggeredProducts,
                registeredMonitoringProducts,
                null,
                "즉시 충족 " + triggeredProducts.size() + "건, 모니터링 등록 " + registeredMonitoringProducts.size() + "건",
                "MONITORING_STARTED",
                false,
                List.of(),
                null,
                null,
                null
        );
    }

    private SelectionProcessingResult handleAutoPurchase(
            ProductSelectionEvent event,
            List<EvaluatedProduct> matchedProducts,
            List<ProductCandidateDto> monitoringProducts
    ) {
        List<ProductCandidateDto> triggeredProducts = new ArrayList<>();
        String purchasedProductId = null;
        Integer triggerPrice = null;
        String aiAgentPrivateKey = null;

        // 즉시 구매 조건을 만족하는 상품이 있을 경우 그중 최저가 1건을 실제 브라우저 구매 진행 대상으로 잡음
        if (!matchedProducts.isEmpty()) {
            EvaluatedProduct cheapest = matchedProducts.stream()
                    .min(Comparator.comparing(EvaluatedProduct::currentPrice))
                    .orElseThrow();
            triggeredProducts.add(cheapest.candidate());
            purchasedProductId = cheapest.candidate().productId();
            triggerPrice = cheapest.currentPrice().intValue();  // 현재 가격을 triggerPrice로 설정

            // 선택 시점의 상품 정보를 구독에도 반영. 이후 price-alert/payment/모니터링 공통 흐름과 연결하기 위함.
            // immediateFullfillment=true: 즉시 충족이므로 Kafka 세션키 이벤트 발행 생략 (동기 REST로 등록)
            MonitoringSubscription subscription = monitoringSubscriptionService.createOrUpdateFromSelection(
                    event.payload().userId(),
                    event.payload().commandId(),
                    event.payload().targetPrice(),
                    event.payload().intent(),
                    cheapest.candidate(),
                    event.payload().scheduledEndAt(),
                    true  // immediateFullfillment: Kafka 이벤트 발행 생략
            );

            // 즉시 충족 시 동기적으로 세션키 등록
            if (subscription.getAiAgentPrivateKey() != null) {
                boolean sessionKeyRegistered = paymentServiceClient.registerSessionKey(
                        event.payload().userId(),
                        subscription.getId(),
                        subscription.getAiAgentAddress(),
                        subscription.getAiAgentPrivateKey(),
                        subscription.getTargetPrice().longValue(),
                        calculateValidSeconds(subscription.getScheduledEndAt()),
                        subscription.getPlatform().name()
                );
                if (sessionKeyRegistered) {
                    aiAgentPrivateKey = subscription.getAiAgentPrivateKey();
                    log.info("즉시 충족 - 세션키 등록 성공 (동기) - subscriptionId: {}", subscription.getId());
                } else {
                    log.warn("즉시 충족 - 세션키 등록 실패 - subscriptionId: {}", subscription.getId());
                }
            }

            // AliExpress: API가 깨져있으므로 process()(API 재검증) 건너뛰고 직접 TRIGGERED 전환
            // 다른 플랫폼: 기존 process()로 API 재검증 수행
            if ("ALIEXPRESS".equals(cheapest.candidate().platform())) {
                subscription.changeStatus(MonitoringSubscriptionStatus.TRIGGERED);
                subscriptionMonitoringService.publishAutoPaymentStartAlert(
                        subscription,
                        new SubscriptionMonitoringService.NormalizedProductSnapshot(
                                true,
                                cheapest.candidate().productId(),
                                cheapest.candidate().productUrl(),
                                cheapest.candidate().title(),
                                cheapest.currentPrice(),
                                com.pbm.price.domain.CurrencyType.KRW
                        ),
                        cheapest.currentPrice()
                );
                log.info("AliExpress 즉시 충족 - process() 스킵, 직접 TRIGGERED 전환 - subscriptionId: {}",
                        subscription.getId());
            } else {
                subscriptionMonitoringService.process(subscription.getId());
            }
        }

        // 최저가로 선택되지 않은 즉시 충족 상품도 결과 응답에는 남겨두어 사용자가 확인할 수 있게 한다.
        matchedProducts.stream()
                .map(EvaluatedProduct::candidate)
                .filter(candidate -> !triggeredProducts.contains(candidate))
                .forEach(triggeredProducts::add);

        // 즉시 구매 대상이 아닌 상품은 모니터링으로 등록한다.
        List<ProductCandidateDto> registeredMonitoringProducts = registerMonitoringProducts(event, monitoringProducts);

        // 상태 의미를 분리
        // 1) triggeredProducts가 있으면 -> 브라우저 구매 진행 상태
        // 2) triggerdProducts는 없고 monitoring만 있으면 -> 모니터링 시작 상태
        // 3) 둘 다 없으면 -> 자동 구매 완료 상태
        String nextStatus;
        if (!triggeredProducts.isEmpty()) {
            nextStatus = "BROWSER_PURCHASE_IN_PROGRESS";
        } else if (!registeredMonitoringProducts.isEmpty()) {
            nextStatus = "MONITORING_STARTED";
        } else {
            nextStatus = "AUTO_PURCHASE_COMPLETED";
        }

        String summaryMessage = "즉시 구매 후보 " + triggeredProducts.size()
                + "건, 모니터링 등록 " + registeredMonitoringProducts.size() + "건";

        return new SelectionProcessingResult(
                triggeredProducts,
                registeredMonitoringProducts,
                purchasedProductId,
                summaryMessage,
                nextStatus,
                false,
                List.of(),
                null,
                triggerPrice,
                aiAgentPrivateKey
        );
    }

    /**
     * 모니터링 종료 예정 시각까지의 유효 기간(초)을 계산한다.
     */
    private long calculateValidSeconds(java.time.Instant scheduledEndAt) {
        if (scheduledEndAt != null) {
            return Math.max(0, scheduledEndAt.getEpochSecond() - java.time.Instant.now().getEpochSecond());
        }
        // 기본값: 7일
        return 7L * 24 * 3600;
    }

    private List<ProductCandidateDto> registerMonitoringProducts(
            ProductSelectionEvent event,
            List<ProductCandidateDto> monitoringProducts
    ) {
        List<ProductCandidateDto> registered = new ArrayList<>();
        for (ProductCandidateDto monitoringProduct : monitoringProducts) {
            MonitoringSubscription subscription;

            if ("ALIEXPRESS".equals(monitoringProduct.platform())) {
                // AliExpress → URL 모니터링으로 전환 (기존 URL 인프라 재활용)
                subscription = urlMonitoringService.createSubscription(
                        event.payload().userId(),
                        event.payload().commandId(),
                        monitoringProduct.productUrl(),
                        event.payload().targetPrice(),
                        monitoringProduct.currency(),
                        "ALL",  // 단일 상품이므로 ALL
                        event.payload().intent()
                );
                // AUTO_PURCHASE면 세션키 등록 (URL 구독은 기본적으로 세션키 미등록)
                if ("AUTO_PURCHASE".equals(event.payload().intent())) {
                    monitoringSubscriptionService.registerSessionKeyForSubscription(
                            subscription,
                            event.payload().scheduledEndAt()
                    );
                }
                log.info("AliExpress 모니터링 → URL 타입 구독 생성 완료 - subscriptionId: {}, url: {}",
                        subscription.getId(), monitoringProduct.productUrl());
            } else {
                // NAVER 등 기존 플랫폼 로직
                subscription = monitoringSubscriptionService.createOrUpdateFromSelection(
                        event.payload().userId(),
                        event.payload().commandId(),
                        event.payload().targetPrice(),
                        event.payload().intent(),
                        monitoringProduct,
                        event.payload().scheduledEndAt()
                );
                log.info("모니터링 구독 생성/갱신 완료 - subscriptionId: {}, commandId: {}, productId: {}",
                        subscription.getId(), subscription.getCommandId(), monitoringProduct.productId());
            }

            registered.add(monitoringProduct);
        }
        return registered;
    }

    private void publishValidationResult(ProductSelectionEvent event, SelectionProcessingResult result) {
        PriceValidationResultEvent validationResultEvent = new PriceValidationResultEvent(
                UUID.randomUUID().toString(),
                "PRICE_VALIDATION_COMPLETED",
                Instant.now(),
                "price-service",
                new PriceValidationResultEventPayload(
                        null,
                        event.payload().commandId(),
                        result.nextStatus(),
                        result.triggeredProducts(),
                        result.monitoringProducts(),
                        result.purchasedProductId(),
                        result.summaryMessage(),
                        result.confirmationRequired(),
                        result.duplicateProducts(),
                        result.confirmationMessage(),
                        result.triggerPrice(),      // 즉시 충족 시 현재 가격, 아니면 null
                        result.aiAgentPrivateKey()  // 즉시 충족 시 세션키 등록 후 개인키, 아니면 null
                )
        );
        priceValidationResultEventPublisher.publish(validationResultEvent);
    }

    /**
     * 중복 구독이 감지된 경우 사용자 확인을 요청하는 결과를 생성한다.
     * <p>
     * 역할: 기존 모니터링이 이미 존재하는 상품을 다시 선택했을 때,
     *       즉시 등록하지 않고 command-service가 polling 응답으로 확인 메시지를 보여주게 한다.
     */
    private SelectionProcessingResult buildDuplicateConfirmationResult(List<ProductCandidateDto> duplicateProducts) {
        String confirmationMessage = duplicateProducts.size() == 1
                ? "이미 이 상품을 모니터링한 이력이 있습니다. 기존 모니터링을 갱신하거나 다시 시작할까요?"
                : "선택한 상품 중 기존 모니터링 이력이 있는 상품이 있습니다. 기존 모니터링을 갱신하거나 다시 시작할까요?";
        return new SelectionProcessingResult(
                List.of(),
                List.of(),
                null,
                "중복 모니터링 확인 필요",
                "RESUBSCRIBE_CONFIRMATION_REQUIRED",
                true,
                duplicateProducts,
                confirmationMessage,
                null,
                null
        );
    }

    private record EvaluatedProduct(ProductCandidateDto candidate, BigDecimal currentPrice) {
    }

    private record SelectionProcessingResult(
            List<ProductCandidateDto> triggeredProducts,
            List<ProductCandidateDto> monitoringProducts,
            String purchasedProductId,
            String summaryMessage,
            String nextStatus,
            boolean confirmationRequired,
            List<ProductCandidateDto> duplicateProducts,
            String confirmationMessage,
            Integer triggerPrice,
            String aiAgentPrivateKey
    ) {
    }
}
