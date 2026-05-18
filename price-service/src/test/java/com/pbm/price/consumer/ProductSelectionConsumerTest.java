package com.pbm.price.consumer;

import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.event.PriceValidationResultEvent;
import com.pbm.price.dto.event.ProductCandidateDto;
import com.pbm.price.dto.event.ProductSelectionEvent;
import com.pbm.price.dto.event.ProductSelectionEventPayload;
import com.pbm.price.publisher.PriceValidationResultEventPublisher;
import com.pbm.price.service.MonitoringSubscriptionService;
import com.pbm.price.service.SubscriptionMonitoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProductSelectionConsumer 단위 테스트.
 *
 * 역할: 다중 선택 이벤트 수신 시 intent별 분기 처리(PRICE_CHECK / PRICE_TRACK / AUTO_PURCHASE)와
 *       검증 결과 이벤트 발행을 검증한다.
 * 연관: ProductSelectionConsumer, MonitoringSubscriptionService, SubscriptionMonitoringService,
 *       PriceValidationResultEventPublisher.
 */
@ExtendWith(MockitoExtension.class)
class ProductSelectionConsumerTest {

    @Mock
    private MonitoringSubscriptionService monitoringSubscriptionService;

    @Mock
    private SubscriptionMonitoringService subscriptionMonitoringService;

    @Mock
    private PriceValidationResultEventPublisher priceValidationResultEventPublisher;

    @Captor
    private ArgumentCaptor<PriceValidationResultEvent> resultEventCaptor;

    private ProductSelectionConsumer productSelectionConsumer;

    private static final String COMMAND_ID = "cmd-001";
    private static final long USER_ID = 1L;
    private static final int TARGET_PRICE = 300000;

    @BeforeEach
    void setUp() {
        productSelectionConsumer = new ProductSelectionConsumer(
                monitoringSubscriptionService,
                subscriptionMonitoringService,
                priceValidationResultEventPublisher
        );
    }

    /**
     * 선택한 상품 1건에 대해 재조회 결과가 KRW + 목표 가격 이하인 스냅샷을 mock으로 설정한다.
     */
    private void mockRefreshedFound(String productId, String title, String lprice, String productUrl) {
        var snapshot = new SubscriptionMonitoringService.NormalizedProductSnapshot(
                true, productId, productUrl, title,
                new BigDecimal(lprice), CurrencyType.KRW
        );
        when(subscriptionMonitoringService.refreshSelectedProduct(any(ProductCandidateDto.class)))
                .thenReturn(snapshot);
    }

    /**
     * 선택한 상품 1건에 대해 재조회 결과를 특정 값으로 mock 설정한다.
     */
    private void mockRefreshedWith(ProductCandidateDto candidate,
                                   boolean found, BigDecimal price, CurrencyType currency) {
        var snapshot = new SubscriptionMonitoringService.NormalizedProductSnapshot(
                found, candidate.productId(), candidate.productUrl(),
                candidate.title(), price, currency
        );
        when(subscriptionMonitoringService.refreshSelectedProduct(candidate))
                .thenReturn(snapshot);
    }

    /**
     * 테스트용 MonitoringSubscription을 생성한다.
     */
    private MonitoringSubscription createSubscription(Long id, String productId) {
        MonitoringSubscription subscription = MonitoringSubscription.create(
                USER_ID, COMMAND_ID, Platform.NAVER,
                productId, "https://example.com/" + productId,
                "테스트 상품", BigDecimal.valueOf(250000),
                "테스트 키워드", BigDecimal.valueOf(TARGET_PRICE),
                CurrencyType.KRW, "PRICE_CHECK",
                MonitoringSubscriptionStatus.ACTIVE, 0, 5
        );
        ReflectionTestUtils.setField(subscription, "id", id);
        return subscription;
    }

    /**
     * 공통 이벤트 생성 헬퍼.
     */
    private ProductSelectionEvent createEvent(String intent, List<ProductCandidateDto> selectedProducts) {
        return createEvent(intent, false, selectedProducts);
    }

    /**
     * forceResubscribe 여부를 포함한 공통 이벤트 생성 헬퍼.
     */
    private ProductSelectionEvent createEvent(String intent, boolean forceResubscribe, List<ProductCandidateDto> selectedProducts) {
        return new ProductSelectionEvent(
                "evt-select-001",
                "PRODUCTS_SELECTED",
                Instant.now(),
                "command-service",
                new ProductSelectionEventPayload(
                        COMMAND_ID, USER_ID, TARGET_PRICE, intent, forceResubscribe, selectedProducts
                )
        );
    }

    /**
     * 테스트용 ProductCandidateDto 생성 헬퍼.
     */
    private ProductCandidateDto candidate(String productId, String title, String lprice,
                                          String mallName, String productUrl) {
        return new ProductCandidateDto(
                productId, title, lprice, mallName, productUrl,
                null, // imageUrl
                "KRW", "NAVER", "테스트 키워드"
        );
    }

    /**
     * 테스트용 ProductCandidateDto 생성 헬퍼 (기본값 사용).
     */
    private ProductCandidateDto candidate(String productId, String lprice) {
        return candidate(productId, "상품 " + productId, lprice, "스토어A",
                "https://example.com/" + productId);
    }

    /**
     * 테스트용 USD 통화 ProductCandidateDto 생성 헬퍼.
     */
    private ProductCandidateDto candidateUsd(String productId, String title, String lprice,
                                              String mallName, String productUrl) {
        return new ProductCandidateDto(
                productId, title, lprice, mallName, productUrl,
                null, // imageUrl
                "USD", "NAVER", "테스트 키워드"
        );
    }

    /**
     * 테스트용 USD 통화 ProductCandidateDto 생성 헬퍼 (기본값 사용).
     */
    private ProductCandidateDto candidateUsd(String productId, String lprice) {
        return candidateUsd(productId, "상품 " + productId, lprice, "스토어A",
                "https://example.com/" + productId);
    }

    // =========================================================================
    // PRICE_CHECK intent
    // =========================================================================

    @Test
    @DisplayName("PRICE_CHECK - 선택 상품 모두 목표 가격 이하 → triggered 목록에 포함, monitoring 없음")
    void priceCheck_allMatched_returnsTriggered() {
        // given
        List<ProductCandidateDto> products = List.of(
                candidate("p1", "200000"),
                candidate("p2", "250000")
        );
        ProductSelectionEvent event = createEvent("PRICE_CHECK", products);

        // 각 상품이 목표 가격 이하로 조회됨
        for (var prod : products) {
            mockRefreshedWith(prod, true, new BigDecimal(prod.lprice()), CurrencyType.KRW);
        }

        // when
        productSelectionConsumer.consume(event);

        // then
        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        PriceValidationResultEvent resultEvent = resultEventCaptor.getValue();
        assertThat(resultEvent.payload().commandId()).isEqualTo(COMMAND_ID);
        assertThat(resultEvent.payload().nextStatus()).isEqualTo("PRICE_CHECK_COMPLETED");
        assertThat(resultEvent.payload().triggeredProducts()).hasSize(2);
        assertThat(resultEvent.payload().monitoringProducts()).isEmpty();
        assertThat(resultEvent.payload().purchasedProductId()).isNull();
    }

    @Test
    @DisplayName("PRICE_CHECK - 선택 상품 중 일부만 목표 가격 충족 → triggeredProduct만 반환")
    void priceCheck_someExceedPrice_onlyTriggeredReturned() {
        // given
        ProductCandidateDto prod1 = candidate("p1", "200000");  // 충족
        ProductCandidateDto prod2 = candidate("p2", "350000");  // 초과
        ProductCandidateDto prod3 = candidate("p3", "250000");  // not found
        List<ProductCandidateDto> products = List.of(prod1, prod2, prod3);
        ProductSelectionEvent event = createEvent("PRICE_CHECK", products);

        mockRefreshedWith(prod1, true, BigDecimal.valueOf(200000), CurrencyType.KRW);
        mockRefreshedWith(prod2, true, BigDecimal.valueOf(350000), CurrencyType.KRW);
        mockRefreshedWith(prod3, false, BigDecimal.ZERO, CurrencyType.KRW);

        // when
        productSelectionConsumer.consume(event);

        // then: PRICE_CHECK은 targetPrice 기준 충족 상품만 triggeredProducts에 포함하고,
        //       목표 가격 초과/미조회 상품은 별도 모니터링 등록 없이 제외한다.
        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        PriceValidationResultEvent resultEvent = resultEventCaptor.getValue();
        assertThat(resultEvent.payload().triggeredProducts()).hasSize(1);   // p1만 충족
        assertThat(resultEvent.payload().monitoringProducts()).isEmpty();
        assertThat(resultEvent.payload().nextStatus()).isEqualTo("PRICE_CHECK_COMPLETED");
    }

    @Test
    @DisplayName("PRICE_CHECK - USD/KRW 혼합 상품, 환산 가격 기준으로 triggered/monitoring 분리")
    void priceCheck_mixedUsdAndKrw_separatedByConvertedPrice() {
        // given: KRW p1(200,000) 충족, USD p2($10→15,000KRW) 충족, USD p3($250→375,000KRW) 초과
        ProductCandidateDto prod1 = candidate("p1", "200000");
        ProductCandidateDto prod2 = candidateUsd("p2", "10");
        ProductCandidateDto prod3 = candidateUsd("p3", "250");
        List<ProductCandidateDto> products = List.of(prod1, prod2, prod3);
        ProductSelectionEvent event = createEvent("PRICE_CHECK", products);

        mockRefreshedWith(prod1, true, BigDecimal.valueOf(200000), CurrencyType.KRW);
        mockRefreshedWith(prod2, true, BigDecimal.valueOf(10), CurrencyType.USD);
        mockRefreshedWith(prod3, true, BigDecimal.valueOf(250), CurrencyType.USD);

        // when
        productSelectionConsumer.consume(event);

        // then: p1(200,000KRW) + p2(환산15,000KRW)만 PRICE_CHECK 충족 목록에 포함된다.
        // PRICE_CHECK은 목표 가격 초과 상품을 모니터링 등록하지 않으므로 p3는 결과에서 제외된다.
        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        PriceValidationResultEvent resultEvent = resultEventCaptor.getValue();
        assertThat(resultEvent.payload().triggeredProducts()).hasSize(2);
        assertThat(resultEvent.payload().monitoringProducts()).isEmpty();
    }

    // =========================================================================
    // PRICE_TRACK intent
    // =========================================================================

    @Test
    @DisplayName("PRICE_TRACK - 즉시 충족 상품은 구독 생성+process, 미충족은 모니터링 등록")
    void priceTrack_matchedAndMonitoring_bothRegistered() {
        // given
        ProductCandidateDto prod1 = candidate("p1", "200000");  // 충족
        ProductCandidateDto prod2 = candidate("p2", "350000");  // 초과 → 모니터링
        List<ProductCandidateDto> products = List.of(prod1, prod2);
        ProductSelectionEvent event = createEvent("PRICE_TRACK", products);

        mockRefreshedWith(prod1, true, BigDecimal.valueOf(200000), CurrencyType.KRW);
        mockRefreshedWith(prod2, true, BigDecimal.valueOf(350000), CurrencyType.KRW);

        MonitoringSubscription sub1 = createSubscription(100L, "p1");
        MonitoringSubscription sub2 = createSubscription(200L, "p2");

        // 충족 상품 → createOrUpdateFromSelection + process
        when(monitoringSubscriptionService.createOrUpdateFromSelection(
                eq(USER_ID), eq(COMMAND_ID), eq(TARGET_PRICE), eq("PRICE_TRACK"),
                eq(prod1)
        )).thenReturn(sub1);
        // 미충족 상품 → createOrUpdateFromSelection (모니터링 등록)
        when(monitoringSubscriptionService.createOrUpdateFromSelection(
                eq(USER_ID), eq(COMMAND_ID), eq(TARGET_PRICE), eq("PRICE_TRACK"),
                eq(prod2)
        )).thenReturn(sub2);

        // when
        productSelectionConsumer.consume(event);

        // then
        verify(monitoringSubscriptionService, times(2)).createOrUpdateFromSelection(
                anyLong(), anyString(), anyInt(), anyString(), any()
        );
        verify(subscriptionMonitoringService).process(100L);   // 충족 상품만 process
        verify(subscriptionMonitoringService, never()).process(200L);  // 미충족은 process X

        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        PriceValidationResultEvent resultEvent = resultEventCaptor.getValue();
        assertThat(resultEvent.payload().triggeredProducts()).hasSize(1);
        assertThat(resultEvent.payload().monitoringProducts()).hasSize(1);
        assertThat(resultEvent.payload().nextStatus()).isEqualTo("MONITORING_STARTED");
    }

    @Test
    @DisplayName("PRICE_TRACK - 충족 상품 없음 → triggered 없음, monitoring만 등록")
    void priceTrack_noMatch_onlyMonitoring() {
        // given
        ProductCandidateDto prod1 = candidate("p1", "350000");  // 초과
        ProductCandidateDto prod2 = candidate("p2", "400000");  // 초과
        List<ProductCandidateDto> products = List.of(prod1, prod2);
        ProductSelectionEvent event = createEvent("PRICE_TRACK", products);

        mockRefreshedWith(prod1, true, BigDecimal.valueOf(350000), CurrencyType.KRW);
        mockRefreshedWith(prod2, true, BigDecimal.valueOf(400000), CurrencyType.KRW);

        MonitoringSubscription sub1 = createSubscription(100L, "p1");
        MonitoringSubscription sub2 = createSubscription(200L, "p2");

        when(monitoringSubscriptionService.createOrUpdateFromSelection(
                anyLong(), anyString(), anyInt(), anyString(), any()
        )).thenReturn(sub1, sub2);

        // when
        productSelectionConsumer.consume(event);

        // then
        verify(subscriptionMonitoringService, never()).process(anyLong());

        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        assertThat(resultEventCaptor.getValue().payload().triggeredProducts()).isEmpty();
        assertThat(resultEventCaptor.getValue().payload().monitoringProducts()).hasSize(2);
    }

    @Test
    @DisplayName("PRICE_TRACK - USD 상품 환산 가격($10→15,000KRW) 목표 이하 → triggered + process")
    void priceTrack_usdConvertedBelowTarget_triggersAndProcesses() {
        // given: USD $10 → 15,000 KRW ≤ 300,000 (TARGET_PRICE)
        ProductCandidateDto prod1 = candidateUsd("p1", "10");
        ProductSelectionEvent event = createEvent("PRICE_TRACK", List.of(prod1));

        mockRefreshedWith(prod1, true, BigDecimal.valueOf(10), CurrencyType.USD);

        MonitoringSubscription sub1 = createSubscription(100L, "p1");
        when(monitoringSubscriptionService.createOrUpdateFromSelection(
                anyLong(), anyString(), anyInt(), anyString(), any()
        )).thenReturn(sub1);

        // when
        productSelectionConsumer.consume(event);

        // then
        verify(subscriptionMonitoringService).process(100L);
        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        assertThat(resultEventCaptor.getValue().payload().triggeredProducts()).hasSize(1);
        assertThat(resultEventCaptor.getValue().payload().monitoringProducts()).isEmpty();
    }

    @Test
    @DisplayName("PRICE_TRACK - USD 상품 환산 가격($250→375,000KRW) 목표 초과 → monitoring만 등록")
    void priceTrack_usdConvertedAboveTarget_onlyMonitoring() {
        // given: USD $250 → 375,000 KRW > 300,000 (TARGET_PRICE)
        ProductCandidateDto prod1 = candidateUsd("p1", "250");
        ProductSelectionEvent event = createEvent("PRICE_TRACK", List.of(prod1));

        mockRefreshedWith(prod1, true, BigDecimal.valueOf(250), CurrencyType.USD);

        MonitoringSubscription sub1 = createSubscription(100L, "p1");
        when(monitoringSubscriptionService.createOrUpdateFromSelection(
                anyLong(), anyString(), anyInt(), anyString(), any()
        )).thenReturn(sub1);

        // when
        productSelectionConsumer.consume(event);

        // then
        verify(subscriptionMonitoringService, never()).process(anyLong());
        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        assertThat(resultEventCaptor.getValue().payload().triggeredProducts()).isEmpty();
        assertThat(resultEventCaptor.getValue().payload().monitoringProducts()).hasSize(1);
    }

    @Test
    @DisplayName("forceResubscribe가 true이면 중복 구독 확인을 건너뛰고 정상 처리한다")
    void duplicateSelection_withForceResubscribeTrue_skipsDuplicateCheck() {
        // given
        ProductCandidateDto prod1 = candidate("p1", "200000");
        List<ProductCandidateDto> products = List.of(prod1);
        ProductSelectionEvent event = createEvent("PRICE_CHECK", true, products);

        // when — forceResubscribe=true이므로 findDuplicateSelections가 호출되지 않음
        mockRefreshedFound("p1", "상품 p1", "200000", "https://example.com/p1");
        productSelectionConsumer.consume(event);

        // then
        verify(monitoringSubscriptionService, never()).findDuplicateSelections(anyLong(), any());
        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        assertThat(resultEventCaptor.getValue().payload().confirmationRequired()).isFalse();
    }

    @Test
    @DisplayName("forceResubscribe가 false이고 중복 상품이 없으면 정상 처리한다")
    void duplicateSelection_noDuplicates_proceedsNormally() {
        // given
        ProductCandidateDto prod1 = candidate("p1", "200000");
        List<ProductCandidateDto> products = List.of(prod1);
        ProductSelectionEvent event = createEvent("AUTO_PURCHASE", false, List.of(prod1));

        when(monitoringSubscriptionService.findDuplicateSelections(USER_ID, List.of(prod1)))
                .thenReturn(List.of());

        mockRefreshedFound("p1", "상품 p1", "200000", "https://example.com/p1");
        MonitoringSubscription sub = createSubscription(100L, "p1");
        when(monitoringSubscriptionService.createOrUpdateFromSelection(
                anyLong(), anyString(), anyInt(), anyString(), any()
        )).thenReturn(sub);

        // when
        productSelectionConsumer.consume(event);

        // then
        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        PriceValidationResultEvent resultEvent = resultEventCaptor.getValue();
        assertThat(resultEvent.payload().confirmationRequired()).isFalse();
        assertThat(resultEvent.payload().nextStatus()).isEqualTo("BROWSER_PURCHASE_IN_PROGRESS");
    }

    @Test
    @DisplayName("forceResubscribe가 false이고 기존 구독이 있으면 사용자 확인 필요 상태를 반환한다")
    void duplicateSelection_requiresConfirmation_whenForceResubscribeFalse() {
        // given
        ProductCandidateDto prod1 = candidate("p1", "350000");
        ProductCandidateDto prod2 = candidate("p2", "400000");
        ProductSelectionEvent event = createEvent("AUTO_PURCHASE", false, List.of(prod1, prod2));

        when(monitoringSubscriptionService.findDuplicateSelections(USER_ID, List.of(prod1, prod2)))
                .thenReturn(List.of(prod1));

        // when
        productSelectionConsumer.consume(event);

        // then
        verify(subscriptionMonitoringService, never()).refreshSelectedProduct(any());
        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        PriceValidationResultEvent resultEvent = resultEventCaptor.getValue();
        assertThat(resultEvent.payload().confirmationRequired()).isTrue();
        assertThat(resultEvent.payload().duplicateProducts()).containsExactly(prod1);
        assertThat(resultEvent.payload().nextStatus()).isEqualTo("RESUBSCRIBE_CONFIRMATION_REQUIRED");
    }

    // =========================================================================
    // AUTO_PURCHASE intent
    // =========================================================================

    @Test
    @DisplayName("AUTO_PURCHASE - 즉시 충족 상품 중 최저가 1개만 결제 대상 + 나머지 monitoring")
    void autoPurchase_multipleMatched_onlyCheapestPurchased() {
        // given
        ProductCandidateDto prod1 = candidate("p1", "200000");  // 충족 (비쌈)
        ProductCandidateDto prod2 = candidate("p2", "180000");  // 충족 (최저가)
        ProductCandidateDto prod3 = candidate("p3", "350000");  // 초과 → 모니터링
        List<ProductCandidateDto> products = List.of(prod1, prod2, prod3);
        ProductSelectionEvent event = createEvent("AUTO_PURCHASE", products);

        mockRefreshedWith(prod1, true, BigDecimal.valueOf(200000), CurrencyType.KRW);
        mockRefreshedWith(prod2, true, BigDecimal.valueOf(180000), CurrencyType.KRW);
        mockRefreshedWith(prod3, true, BigDecimal.valueOf(350000), CurrencyType.KRW);

        MonitoringSubscription subPurchased = createSubscription(100L, "p2");
        MonitoringSubscription subMonitoring = createSubscription(200L, "p3");

        // 최저가 p2만 createOrUpdateFromSelection + process
        when(monitoringSubscriptionService.createOrUpdateFromSelection(
                eq(USER_ID), eq(COMMAND_ID), eq(TARGET_PRICE), eq("AUTO_PURCHASE"),
                eq(prod2)
        )).thenReturn(subPurchased);
        // 미충족 상품 모니터링 등록
        when(monitoringSubscriptionService.createOrUpdateFromSelection(
                eq(USER_ID), eq(COMMAND_ID), eq(TARGET_PRICE), eq("AUTO_PURCHASE"),
                eq(prod3)
        )).thenReturn(subMonitoring);

        // when
        productSelectionConsumer.consume(event);

        // then
        verify(monitoringSubscriptionService, times(2)).createOrUpdateFromSelection(
                anyLong(), anyString(), anyInt(), anyString(), any()
        );
        verify(subscriptionMonitoringService).process(100L);    // 최저가 p2만 process

        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        PriceValidationResultEvent resultEvent = resultEventCaptor.getValue();
        assertThat(resultEvent.payload().triggeredProducts()).hasSize(2);
        assertThat(resultEvent.payload().triggeredProducts().get(0).productId()).isEqualTo("p2");
        assertThat(resultEvent.payload().triggeredProducts().get(1).productId()).isEqualTo("p1");
        assertThat(resultEvent.payload().purchasedProductId()).isEqualTo("p2");
        assertThat(resultEvent.payload().monitoringProducts()).hasSize(1);  // p3
        assertThat(resultEvent.payload().nextStatus()).isEqualTo("BROWSER_PURCHASE_IN_PROGRESS");
    }

    @Test
    @DisplayName("AUTO_PURCHASE - 충족 상품 없음 → purchasedProductId null, 전부 monitoring")
    void autoPurchase_noMatch_noPurchase() {
        // given
        ProductCandidateDto prod1 = candidate("p1", "350000");  // 초과
        ProductCandidateDto prod2 = candidate("p2", null, null, null, "https://example.com/p2");  // not found
        List<ProductCandidateDto> products = List.of(prod1, prod2);
        ProductSelectionEvent event = createEvent("AUTO_PURCHASE", products);

        mockRefreshedWith(prod1, true, BigDecimal.valueOf(350000), CurrencyType.KRW);
        mockRefreshedWith(prod2, false, BigDecimal.ZERO, CurrencyType.KRW);

        MonitoringSubscription sub1 = createSubscription(100L, "p1");
        MonitoringSubscription sub2 = createSubscription(200L, "p2");

        when(monitoringSubscriptionService.createOrUpdateFromSelection(
                anyLong(), anyString(), anyInt(), anyString(), any()
        )).thenReturn(sub1, sub2);

        // when
        productSelectionConsumer.consume(event);

        // then
        verify(subscriptionMonitoringService, never()).process(anyLong());

        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        PriceValidationResultEvent resultEvent = resultEventCaptor.getValue();
        assertThat(resultEvent.payload().triggeredProducts()).isEmpty();
        assertThat(resultEvent.payload().purchasedProductId()).isNull();
        assertThat(resultEvent.payload().monitoringProducts()).hasSize(2);
        assertThat(resultEvent.payload().nextStatus()).isEqualTo("MONITORING_STARTED");
    }

    @Test
    @DisplayName("AUTO_PURCHASE - triggeredProducts가 존재하면 monitoringProducts 유무와 관계없이 BROWSER_PURCHASE_IN_PROGRESS 반환")
    void autoPurchase_withTriggeredProducts_returnsBrowserPurchaseStatus() {
        // given
        ProductCandidateDto prod1 = candidate("p1", "200000");  // 충족 (최저가)
        List<ProductCandidateDto> products = List.of(prod1);
        ProductSelectionEvent event = createEvent("AUTO_PURCHASE", products);

        mockRefreshedWith(prod1, true, BigDecimal.valueOf(200000), CurrencyType.KRW);

        MonitoringSubscription sub = createSubscription(100L, "p1");
        when(monitoringSubscriptionService.createOrUpdateFromSelection(
                anyLong(), anyString(), anyInt(), anyString(), any()
        )).thenReturn(sub);

        // when
        productSelectionConsumer.consume(event);

        // then
        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        assertThat(resultEventCaptor.getValue().payload().nextStatus()).isEqualTo("BROWSER_PURCHASE_IN_PROGRESS");
        assertThat(resultEventCaptor.getValue().payload().triggeredProducts()).hasSize(1);
        assertThat(resultEventCaptor.getValue().payload().monitoringProducts()).isEmpty();
    }

    // =========================================================================
    // USD → KRW 변환 (고정 환율 1500)
    // =========================================================================

    @Test
    @DisplayName("AUTO_PURCHASE - USD 상품 중 환산 최저가($10→15,000KRW) 목표 이하 → 구매 대상")
    void autoPurchase_usdProducts_cheapestConvertedBelowTarget_purchased() {
        // given: p1=$10(→15,000KRW) 충족, p2=$20(→30,000KRW) 충족 → p1이 최저가
        ProductCandidateDto prod1 = candidateUsd("p1", "10");
        ProductCandidateDto prod2 = candidateUsd("p2", "20");
        List<ProductCandidateDto> products = List.of(prod1, prod2);
        ProductSelectionEvent event = createEvent("AUTO_PURCHASE", products);

        mockRefreshedWith(prod1, true, BigDecimal.valueOf(10), CurrencyType.USD);
        mockRefreshedWith(prod2, true, BigDecimal.valueOf(20), CurrencyType.USD);

        MonitoringSubscription subPurchased = createSubscription(100L, "p1");
        when(monitoringSubscriptionService.createOrUpdateFromSelection(
                eq(USER_ID), eq(COMMAND_ID), eq(TARGET_PRICE), eq("AUTO_PURCHASE"),
                any()
        )).thenReturn(subPurchased);

        // when
        productSelectionConsumer.consume(event);

        // then: p1이 최저가로 구매 대상 선정, triggered 존재 → BROWSER_PURCHASE_IN_PROGRESS
        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        PriceValidationResultEvent resultEvent = resultEventCaptor.getValue();
        assertThat(resultEvent.payload().purchasedProductId()).isEqualTo("p1");
        assertThat(resultEvent.payload().triggeredProducts()).hasSize(2);
        assertThat(resultEvent.payload().triggeredProducts().get(0).productId()).isEqualTo("p1");
        assertThat(resultEvent.payload().nextStatus()).isEqualTo("BROWSER_PURCHASE_IN_PROGRESS");
    }

    @Test
    @DisplayName("AUTO_PURCHASE - USD 상품 환산 가격 모두 목표 초과 → 구매 없음, 전부 monitoring")
    void autoPurchase_usdAllConvertedAboveTarget_noPurchase() {
        // given: p1=$250(→375,000KRW) 초과, p2=$300(→450,000KRW) 초과
        ProductCandidateDto prod1 = candidateUsd("p1", "250");
        ProductCandidateDto prod2 = candidateUsd("p2", "300");
        List<ProductCandidateDto> products = List.of(prod1, prod2);
        ProductSelectionEvent event = createEvent("AUTO_PURCHASE", products);

        mockRefreshedWith(prod1, true, BigDecimal.valueOf(250), CurrencyType.USD);
        mockRefreshedWith(prod2, true, BigDecimal.valueOf(300), CurrencyType.USD);

        MonitoringSubscription sub1 = createSubscription(100L, "p1");
        MonitoringSubscription sub2 = createSubscription(200L, "p2");
        when(monitoringSubscriptionService.createOrUpdateFromSelection(
                anyLong(), anyString(), anyInt(), anyString(), any()
        )).thenReturn(sub1, sub2);

        // when
        productSelectionConsumer.consume(event);

        // then
        verify(subscriptionMonitoringService, never()).process(anyLong());
        verify(priceValidationResultEventPublisher).publish(resultEventCaptor.capture());
        PriceValidationResultEvent resultEvent = resultEventCaptor.getValue();
        assertThat(resultEvent.payload().triggeredProducts()).isEmpty();
        assertThat(resultEvent.payload().purchasedProductId()).isNull();
        assertThat(resultEvent.payload().monitoringProducts()).hasSize(2);
        assertThat(resultEvent.payload().nextStatus()).isEqualTo("MONITORING_STARTED");
    }
}
