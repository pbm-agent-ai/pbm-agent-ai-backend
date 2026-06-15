package com.pbm.price.consumer;

import com.pbm.price.client.PaymentServiceClient;
import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.dto.event.ParsedCommandSnapshot;
import com.pbm.price.dto.event.PriceRequestEvent;
import com.pbm.price.dto.event.PriceRequestEventPayload;
import com.pbm.price.dto.event.PriceValidationResultEvent;
import com.pbm.price.dto.event.ProductSelectionRequiredEvent;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PriceTopicConsumer 단위 테스트.
 *
 * 역할: price-topic 수신 후 가격 비교 대신 후보 상품 목록을 생성하여
 *       상품 선택 필요(product-selection-required) 이벤트를 발행하는 흐름을 검증한다.
 * 동작: NaverShoppingService, AliExpressShoppingService를 Mock으로 대체하고
 *       ProductSelectionRequiredEventPublisher 발행 페이로드를 확인한다.
 * 연관: PriceTopicConsumer, ProductSelectionRequiredEventPublisher.
 */
@ExtendWith(MockitoExtension.class)
class PriceTopicConsumerTest {

    @Mock
    private NaverShoppingService naverShoppingService;

    @Mock
    private AliExpressShoppingService aliExpressShoppingService;

    @Mock
    private ProductSelectionRequiredEventPublisher productSelectionRequiredEventPublisher;

    @Mock
    private AliExpressCategoryIdResolver aliExpressCategoryIdResolver;

    @Mock
    private AliExpressProductUrlService aliExpressProductUrlService;

    @Mock
    private NaverProductUrlService naverProductUrlService;

    @Mock
    private UrlMonitoringService urlMonitoringService;

    @Mock
    private MonitoringSubscriptionService monitoringSubscriptionService;

    @Mock
    private PaymentServiceClient paymentServiceClient;

    @Mock
    private PriceValidationResultEventPublisher priceValidationResultEventPublisher;

    private PriceTopicConsumer priceTopicConsumer;

    @BeforeEach
    void setUp() {
        priceTopicConsumer = new PriceTopicConsumer(
                naverShoppingService,
                aliExpressShoppingService,
                aliExpressCategoryIdResolver,
                aliExpressProductUrlService,
                naverProductUrlService,
                productSelectionRequiredEventPublisher,
                urlMonitoringService,
                monitoringSubscriptionService,
                paymentServiceClient,
                priceValidationResultEventPublisher
        );
    }

    /**
     * 테스트용 PriceRequestEvent 생성 헬퍼.
     */
    private PriceRequestEvent createRequestEvent(Long userId, String keyword, Integer targetPrice,
                                                 String platform, String currency) {
        return createRequestEvent(userId, keyword, targetPrice, platform, currency, null, null);
    }

    /**
     * 테스트용 PriceRequestEvent 생성 헬퍼 (intent/snapshot 지정 가능).
     */
    private PriceRequestEvent createRequestEvent(Long userId, String keyword, Integer targetPrice,
                                                 String platform, String currency,
                                                 String intent, ParsedCommandSnapshot snapshot) {
        return new PriceRequestEvent(
                "evt-001",
                "PRICE_CHECK_REQUEST",
                Instant.now(),
                "command-service",
                new PriceRequestEventPayload(
                        userId, keyword, targetPrice, platform, currency,
                        null, intent, snapshot,
                        null, null, null
                )
        );
    }

    @Test
    @DisplayName("NAVER - 검색 결과가 있으면 후보 선택 이벤트를 발행한다")
    void consume_naverResults_publishCandidateSelectionEvent() {
        // given
        PriceRequestEvent event = createRequestEvent(1L, "에어팟 프로", 300000, "NAVER", "KRW");
        List<SearchResponse> results = List.of(
                new SearchResponse("에어팟 프로 1", "250000", "350000", "애플스토어", "https://example.com/1", "https://img.example.com/1.jpg", "KRW", "naver-1"),
                new SearchResponse("에어팟 프로 2", "260000", "360000", "애플스토어", "https://example.com/2", "https://img.example.com/2.jpg", "KRW", "naver-2")
        );
        when(naverShoppingService.searchProducts("에어팟 프로", 30)).thenReturn(results);

        // when
        priceTopicConsumer.consume(event);

        // then
        ArgumentCaptor<ProductSelectionRequiredEvent> captor =
                ArgumentCaptor.forClass(ProductSelectionRequiredEvent.class);
        verify(productSelectionRequiredEventPublisher, times(1)).publish(captor.capture());

        ProductSelectionRequiredEvent published = captor.getValue();
        assertThat(published.eventType()).isEqualTo("PRODUCT_SELECTION_REQUIRED");
        assertThat(published.payload().message()).isEqualTo("검색 결과를 확인하고 상품을 선택해주세요.");
        assertThat(published.payload().missingFields()).isEmpty();
        assertThat(published.payload().candidates()).hasSize(2);
        assertThat(published.payload().candidates().get(0).productId()).isEqualTo("naver-1");
        assertThat(published.payload().candidates().get(0).platform()).isEqualTo("NAVER");
        assertThat(published.payload().candidates().get(0).searchKeyword()).isEqualTo("에어팟 프로");
        assertThat(published.payload().targetPrice()).isEqualTo(300000);
    }

    @Test
    @DisplayName("NAVER - 검색 결과가 없으면 빈 후보 목록과 안내 메시지를 발행한다")
    void consume_noResults_publishEmptyCandidateEvent() {
        // given
        PriceRequestEvent event = createRequestEvent(1L, "존재하지않는상품", 100000, "NAVER", "KRW");
        when(naverShoppingService.searchProducts("존재하지않는상품", 30)).thenReturn(List.of());

        // when
        priceTopicConsumer.consume(event);

        // then
        ArgumentCaptor<ProductSelectionRequiredEvent> captor =
                ArgumentCaptor.forClass(ProductSelectionRequiredEvent.class);
        verify(productSelectionRequiredEventPublisher, times(1)).publish(captor.capture());

        ProductSelectionRequiredEvent published = captor.getValue();
        assertThat(published.payload().message()).isEqualTo("검색 결과가 없습니다. 검색어를 바꿔 다시 시도해주세요.");
        assertThat(published.payload().candidates()).isEmpty();
        assertThat(published.payload().missingFields()).isEmpty();
    }

    @Test
    @DisplayName("ALIEXPRESS - 일반 검색이면 공식 API 검색 후 후보 선택 이벤트를 발행한다")
    void consume_aliExpressResults_publishCandidateSelectionEvent() {
        PriceRequestEvent event = createRequestEvent(1L, "무선 이어폰", 50000, "ALIEXPRESS", "USD");
        when(aliExpressShoppingService.searchProducts(
                eq("무선 이어폰"), eq(1), eq(20), eq(null), eq("USD"), eq("KO"), eq("KR"), eq(null), eq(null)
        )).thenReturn(List.of(
                new SearchResponse(
                        "무선 이어폰 A",
                        "49000",
                        "55000",
                        "AliExpress",
                        "https://ko.aliexpress.com/item/1005000000000001.html",
                        "https://img.example.com/ali-1.jpg",
                        "USD",
                        "1005000000000001"
                )
        ));

        priceTopicConsumer.consume(event);

        verify(aliExpressShoppingService, times(1)).searchProducts(
                eq("무선 이어폰"), eq(1), eq(20), eq(null), eq("USD"), eq("KO"), eq("KR"), eq(null), eq(null)
        );

        ArgumentCaptor<ProductSelectionRequiredEvent> captor =
                ArgumentCaptor.forClass(ProductSelectionRequiredEvent.class);
        verify(productSelectionRequiredEventPublisher, times(1)).publish(captor.capture());

        ProductSelectionRequiredEvent published = captor.getValue();
        assertThat(published.payload().message()).isEqualTo("검색 결과를 확인하고 상품을 선택해주세요.");
        assertThat(published.payload().candidates()).hasSize(1);
        assertThat(published.payload().candidates().get(0).productId()).isEqualTo("1005000000000001");
        assertThat(published.payload().candidates().get(0).platform()).isEqualTo("ALIEXPRESS");
    }

    @Test
    @DisplayName("지원하지 않는 플랫폼이면 검색을 건너뛰고 빈 후보 이벤트를 발행한다")
    void consume_invalidPlatform_publishEmptyCandidateEvent() {
        // given
        PriceRequestEvent event = createRequestEvent(1L, "상품명", 100000, "INVALID", "KRW");

        // when
        priceTopicConsumer.consume(event);

        // then
        verify(naverShoppingService, never()).searchProducts(anyString(), anyInt());
        verify(aliExpressShoppingService, never()).searchProducts(
                anyString(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any()
        );
        verify(productSelectionRequiredEventPublisher, times(1)).publish(any(ProductSelectionRequiredEvent.class));
    }

    @Test
    @DisplayName("ALIEXPRESS - searchCategoryHint가 있으면 category_ids를 붙여 공식 API 검색을 수행한다")
    void consume_aliExpressWithCategoryHint_searchesWithResolvedCategoryIds() {
        ParsedCommandSnapshot snapshot = new ParsedCommandSnapshot(
                "ELECTRONICS",
                "mx master 3s",
                "로지텍",
                "MX Master",
                "3S",
                "black",
                null,
                "ALIEXPRESS",
                100000,
                null,
                "KRW",
                null,
                null,
                "MOUSE"
        );
        PriceRequestEvent event = createRequestEvent(1L, "로지텍 mx master 3s black", 100000, "ALIEXPRESS", "KRW", "AUTO_PURCHASE", snapshot);
        when(aliExpressCategoryIdResolver.resolveCategoryIds("MOUSE")).thenReturn(Optional.of("10,20"));
        when(aliExpressShoppingService.searchProducts(
                eq("로지텍 mx master 3s black"), eq(1), eq(20), eq(null), eq("KRW"), eq("KO"), eq("KR"), eq("10,20"), eq(null)
        )).thenReturn(List.of(
                new SearchResponse(
                        "로지텍 MX Master 3S",
                        "99000",
                        "120000",
                        "AliExpress",
                        "https://ko.aliexpress.com/item/1005000000000002.html",
                        "https://img.example.com/ali-2.jpg",
                        "KRW",
                        "1005000000000002"
                )
        ));

        priceTopicConsumer.consume(event);

        verify(aliExpressCategoryIdResolver, times(1)).resolveCategoryIds("MOUSE");
        verify(aliExpressShoppingService, times(1)).searchProducts(
                eq("로지텍 mx master 3s black"), eq(1), eq(20), eq(null), eq("KRW"), eq("KO"), eq("KR"), eq("10,20"), eq(null)
        );
    }

    @Test
    @DisplayName("후보 상품이 30개를 넘어도 상위 30개만 저장 대상으로 전달한다")
    void consume_moreThanThirtyResults_onlyPublishesTopThirtyCandidates() {
        // given
        PriceRequestEvent event = createRequestEvent(1L, "키보드", 100000, "NAVER", "KRW");
        List<SearchResponse> results = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(i -> new SearchResponse(
                        "키보드 " + i,
                        String.valueOf(10000 + i),
                        String.valueOf(20000 + i),
                        "스토어" + i,
                        "https://example.com/" + i,
                        "https://img.example.com/" + i + ".jpg",
                        "KRW",
                        "naver-" + i
                ))
                .toList();
        when(naverShoppingService.searchProducts("키보드", 30)).thenReturn(results);

        // when
        priceTopicConsumer.consume(event);

        // then
        ArgumentCaptor<ProductSelectionRequiredEvent> captor =
                ArgumentCaptor.forClass(ProductSelectionRequiredEvent.class);
        verify(productSelectionRequiredEventPublisher, times(1)).publish(captor.capture());

        ProductSelectionRequiredEvent published = captor.getValue();
        assertThat(published.payload().candidates()).hasSize(30);
        assertThat(published.payload().candidates().get(0).productId()).isEqualTo("naver-1");
        assertThat(published.payload().candidates().get(29).productId()).isEqualTo("naver-30");
    }

    @Test
    @DisplayName("ALIEXPRESS - productUrls가 있으면 검색 대신 단건 상세 확인 결과를 후보로 발행한다")
    void consume_directAliExpressUrls_resolvesCandidatesFromDetailApi() {
        PriceRequestEvent event = new PriceRequestEvent(
                "evt-url-001",
                "PRICE_CHECK_REQUEST",
                Instant.now(),
                "command-service",
                new PriceRequestEventPayload(
                        1L,
                        "mx master 3s 블랙 알리익스프레스에서 100000원 이하면 결제해줘",
                        100000,
                        "ALIEXPRESS",
                        "KRW",
                        "cmd-url-001",
                        "AUTO_PURCHASE",
                        null,
                        null,
                        "mx master 3s 블랙 알리익스프레스에서 100000원 이하면 결제해줘",
                        List.of(
                                "https://ko.aliexpress.com/item/1005006782975346.html",
                                "https://ko.aliexpress.com/item/1005010633549414.html"
                        )
                )
        );

        when(aliExpressProductUrlService.resolveProductsByUrls(
                eq(List.of(
                        "https://ko.aliexpress.com/item/1005006782975346.html",
                        "https://ko.aliexpress.com/item/1005010633549414.html"
                )),
                eq("KRW"), eq("KO"), eq("KR")
        )).thenReturn(List.of(
                new SearchResponse(
                        "로지텍 MX 마스터 무선 블루투스 마우스, 하이 엔드 크로스 스크린 노트북, 3S",
                        "136200",
                        "289787",
                        "Stone's Store",
                        "https://ko.aliexpress.com/item/1005006782975346.html",
                        "https://img.example.com/1005006782975346.jpg",
                        "KRW",
                        "1005006782975346"
                )
        ));

        priceTopicConsumer.consume(event);

        verify(aliExpressProductUrlService).resolveProductsByUrls(any(), eq("KRW"), eq("KO"), eq("KR"));
        verify(aliExpressShoppingService, never()).searchProducts(anyString(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any());

        ArgumentCaptor<ProductSelectionRequiredEvent> captor = ArgumentCaptor.forClass(ProductSelectionRequiredEvent.class);
        verify(productSelectionRequiredEventPublisher).publish(captor.capture());
        assertThat(captor.getValue().payload().candidates()).hasSize(1);
        assertThat(captor.getValue().payload().candidates().get(0).productId()).isEqualTo("1005006782975346");
    }

    @Test
    @DisplayName("NAVER - productUrls가 있으면 검색 결과 재매칭으로 후보를 발행한다")
    void consume_directNaverUrls_resolvesCandidatesFromSearchResult() {
        PriceRequestEvent event = new PriceRequestEvent(
                "evt-url-naver-001",
                "PRICE_CHECK_REQUEST",
                Instant.now(),
                "command-service",
                new PriceRequestEventPayload(
                        1L,
                        "mx master 3s 블랙 네이버에서 100000원 이하면 결제해줘",
                        100000,
                        "NAVER",
                        "KRW",
                        "cmd-url-naver-001",
                        "AUTO_PURCHASE",
                        null,
                        null,
                        "로지텍 mx master 3s black",
                        List.of("https://search.shopping.naver.com/catalog/57981069328")
                )
        );

        when(naverProductUrlService.resolveProductsByUrls(
                eq("로지텍 mx master 3s black"),
                eq(List.of("https://search.shopping.naver.com/catalog/57981069328"))
        )).thenReturn(List.of(
                new SearchResponse(
                        "로지텍 MX MASTER 3S bluetooth edition, 블랙",
                        "139000",
                        "",
                        "네이버",
                        "https://search.shopping.naver.com/catalog/57981069328",
                        "https://img.example.com/57981069328.jpg",
                        "KRW",
                        "57981069328"
                )
        ));

        priceTopicConsumer.consume(event);

        verify(naverProductUrlService).resolveProductsByUrls(eq("로지텍 mx master 3s black"), any());
        verify(naverShoppingService, never()).searchProducts(anyString(), anyInt());

        ArgumentCaptor<ProductSelectionRequiredEvent> captor = ArgumentCaptor.forClass(ProductSelectionRequiredEvent.class);
        verify(productSelectionRequiredEventPublisher).publish(captor.capture());
        assertThat(captor.getValue().payload().candidates()).hasSize(1);
        assertThat(captor.getValue().payload().candidates().get(0).productId()).isEqualTo("57981069328");
    }

    // ===== URL_MONITOR_REQUEST 테스트 =====

    /**
     * URL_MONITOR_REQUEST 전용 PriceRequestEvent 생성 헬퍼 (currentPrice 없음 → 모니터링 시나리오).
     */
    private PriceRequestEvent createUrlMonitorEvent(Long userId, String productUrl,
                                                     Integer targetPrice, String intent,
                                                     String urlCondition) {
        return createUrlMonitorEvent(userId, productUrl, targetPrice, intent, urlCondition, null);
    }

    /**
     * URL_MONITOR_REQUEST 전용 PriceRequestEvent 생성 헬퍼 (currentPrice 지정 가능).
     */
    private PriceRequestEvent createUrlMonitorEvent(Long userId, String productUrl,
                                                     Integer targetPrice, String intent,
                                                     String urlCondition, Integer currentPrice) {
        return new PriceRequestEvent(
                "evt-url-mon-001",
                "URL_MONITOR_REQUEST",
                Instant.now(),
                "command-service",
                new PriceRequestEventPayload(
                        userId, null, targetPrice, "URL", "KRW",
                        "cmd-url-mon-001", intent, null,
                        productUrl, null, null, urlCondition, currentPrice
                )
        );
    }

    @Test
    @DisplayName("URL_MONITOR_REQUEST - AUTO_PURCHASE이면 구독 생성 후 세션키를 등록한다")
    void consume_urlMonitorAutoPurchase_registersSessionKey() {
        // given: AliExpress 상품 URL로 AUTO_PURCHASE 모니터링 요청
        String productUrl = "https://ko.aliexpress.com/item/1005011805791975.html";
        PriceRequestEvent event = createUrlMonitorEvent(1L, productUrl, 300000, "AUTO_PURCHASE", "ALL");

        MonitoringSubscription mockSubscription = MonitoringSubscription.createUrl(
                1L, "cmd-url-mon-001", productUrl,
                BigDecimal.valueOf(300000), CurrencyType.KRW,
                "AUTO_PURCHASE", MonitoringSubscriptionStatus.ACTIVE,
                10, Instant.now().plusSeconds(604800), "ALL"
        );
        when(urlMonitoringService.createSubscription(
                eq(1L), eq("cmd-url-mon-001"), eq(productUrl),
                eq(300000), eq("KRW"), eq("ALL"), eq("AUTO_PURCHASE")
        )).thenReturn(mockSubscription);

        // when: 이벤트 소비
        priceTopicConsumer.consume(event);

        // then: URL 구독 생성 + 세션키 등록 호출 확인
        verify(urlMonitoringService).createSubscription(
                eq(1L), eq("cmd-url-mon-001"), eq(productUrl),
                eq(300000), eq("KRW"), eq("ALL"), eq("AUTO_PURCHASE")
        );
        verify(monitoringSubscriptionService).registerSessionKeyForSubscription(
                eq(mockSubscription), eq(null)
        );
    }

    @Test
    @DisplayName("URL_MONITOR_REQUEST - PRICE_TRACK이면 세션키를 등록하지 않는다")
    void consume_urlMonitorPriceTrack_doesNotRegisterSessionKey() {
        // given: PRICE_TRACK intent (가격 추적만, 자동 구매 아님)
        String productUrl = "https://ko.aliexpress.com/item/1005011805791975.html";
        PriceRequestEvent event = createUrlMonitorEvent(1L, productUrl, 300000, "PRICE_TRACK", "ALL");

        MonitoringSubscription mockSubscription = MonitoringSubscription.createUrl(
                1L, "cmd-url-mon-001", productUrl,
                BigDecimal.valueOf(300000), CurrencyType.KRW,
                "PRICE_TRACK", MonitoringSubscriptionStatus.ACTIVE,
                10, Instant.now().plusSeconds(604800), "ALL"
        );
        when(urlMonitoringService.createSubscription(
                eq(1L), eq("cmd-url-mon-001"), eq(productUrl),
                eq(300000), eq("KRW"), eq("ALL"), eq("PRICE_TRACK")
        )).thenReturn(mockSubscription);

        // when: 이벤트 소비
        priceTopicConsumer.consume(event);

        // then: URL 구독 생성만 호출, 세션키 등록은 호출 안 됨
        verify(urlMonitoringService).createSubscription(
                eq(1L), eq("cmd-url-mon-001"), eq(productUrl),
                eq(300000), eq("KRW"), eq("ALL"), eq("PRICE_TRACK")
        );
        verify(monitoringSubscriptionService, never()).registerSessionKeyForSubscription(any(), any());
    }

    @Test
    @DisplayName("URL_MONITOR_REQUEST - productUrl이 없으면 구독을 생성하지 않는다")
    void consume_urlMonitorNoUrl_doesNotCreateSubscription() {
        // given: productUrl이 null인 URL_MONITOR_REQUEST
        PriceRequestEvent event = createUrlMonitorEvent(1L, null, 300000, "AUTO_PURCHASE", "ALL");

        // when: 이벤트 소비
        priceTopicConsumer.consume(event);

        // then: 구독 생성도 세션키 등록도 호출되지 않음
        verify(urlMonitoringService, never()).createSubscription(
                any(), any(), any(), any(), any(), any(), any()
        );
        verify(monitoringSubscriptionService, never()).registerSessionKeyForSubscription(any(), any());
        verify(monitoringSubscriptionService, never()).registerSessionKeyForSubscription(any(), any(), anyBoolean());
    }

    // ===== URL 즉시 충족 테스트 =====

    @Test
    @DisplayName("URL_MONITOR_REQUEST - 즉시 충족 시 동기 REST 세션키 등록 + TRIGGERED + PriceValidationResultEvent 발행")
    void consume_urlMonitorImmediateFulfillment_registersSessionKeySyncAndPublishesEvent() {
        // given: 현재가 280000원 ≤ 목표가 300000원 → 즉시 충족
        String productUrl = "https://ko.aliexpress.com/item/1005011805791975.html";
        PriceRequestEvent event = createUrlMonitorEvent(
                1L, productUrl, 300000, "AUTO_PURCHASE", "ALL", 280000
        );

        MonitoringSubscription mockSubscription = MonitoringSubscription.createUrl(
                1L, "cmd-url-mon-001", productUrl,
                BigDecimal.valueOf(300000), CurrencyType.KRW,
                "AUTO_PURCHASE", MonitoringSubscriptionStatus.ACTIVE,
                10, Instant.now().plusSeconds(604800), "ALL"
        );
        // assignSessionKey 시뮬레이션 (registerSessionKeyForSubscription이 내부에서 설정)
        mockSubscription.assignSessionKey("0xTestAddress", "testPrivateKey");

        when(urlMonitoringService.createSubscription(
                eq(1L), eq("cmd-url-mon-001"), eq(productUrl),
                eq(300000), eq("KRW"), eq("ALL"), eq("AUTO_PURCHASE")
        )).thenReturn(mockSubscription);

        when(paymentServiceClient.registerSessionKey(
                eq(1L), any(), eq("0xTestAddress"), eq("testPrivateKey"),
                eq(300000L), anyLong(), eq("ALIEXPRESS")
        )).thenReturn(true);

        // when
        priceTopicConsumer.consume(event);

        // then: 즉시 충족 흐름 확인
        // 1) 구독 생성
        verify(urlMonitoringService).createSubscription(
                eq(1L), eq("cmd-url-mon-001"), eq(productUrl),
                eq(300000), eq("KRW"), eq("ALL"), eq("AUTO_PURCHASE")
        );
        // 2) 세션키 등록 — publishKafkaEvent=false (즉시 충족이므로 Kafka 발행 생략)
        verify(monitoringSubscriptionService).registerSessionKeyForSubscription(
                eq(mockSubscription), eq(null), eq(false)
        );
        // 3) 동기 REST 세션키 등록
        verify(paymentServiceClient).registerSessionKey(
                eq(1L), any(), eq("0xTestAddress"), eq("testPrivateKey"),
                eq(300000L), anyLong(), eq("ALIEXPRESS")
        );
        // 4) PriceValidationResultEvent 발행
        ArgumentCaptor<PriceValidationResultEvent> captor =
                ArgumentCaptor.forClass(PriceValidationResultEvent.class);
        verify(priceValidationResultEventPublisher).publish(captor.capture());

        PriceValidationResultEvent resultEvent = captor.getValue();
        assertThat(resultEvent.payload().nextStatus()).isEqualTo("BROWSER_PURCHASE_IN_PROGRESS");
        assertThat(resultEvent.payload().triggerPrice()).isEqualTo(280000);
        assertThat(resultEvent.payload().aiAgentPrivateKey()).isEqualTo("testPrivateKey");
        assertThat(resultEvent.payload().triggeredProducts()).hasSize(1);
        assertThat(resultEvent.payload().triggeredProducts().get(0).platform()).isEqualTo("ALIEXPRESS");
    }

    @Test
    @DisplayName("URL_MONITOR_REQUEST - 현재가 > 목표가이면 즉시 충족이 아닌 모니터링으로 처리한다")
    void consume_urlMonitorCurrentPriceExceedsTarget_fallsBackToMonitoring() {
        // given: 현재가 350000원 > 목표가 300000원 → 모니터링
        String productUrl = "https://ko.aliexpress.com/item/1005011805791975.html";
        PriceRequestEvent event = createUrlMonitorEvent(
                1L, productUrl, 300000, "AUTO_PURCHASE", "ALL", 350000
        );

        MonitoringSubscription mockSubscription = MonitoringSubscription.createUrl(
                1L, "cmd-url-mon-001", productUrl,
                BigDecimal.valueOf(300000), CurrencyType.KRW,
                "AUTO_PURCHASE", MonitoringSubscriptionStatus.ACTIVE,
                10, Instant.now().plusSeconds(604800), "ALL"
        );
        when(urlMonitoringService.createSubscription(
                eq(1L), eq("cmd-url-mon-001"), eq(productUrl),
                eq(300000), eq("KRW"), eq("ALL"), eq("AUTO_PURCHASE")
        )).thenReturn(mockSubscription);

        // when
        priceTopicConsumer.consume(event);

        // then: 모니터링 흐름 (Kafka 비동기 세션키 등록)
        verify(monitoringSubscriptionService).registerSessionKeyForSubscription(
                eq(mockSubscription), eq(null)
        );
        // 동기 REST 세션키 등록은 호출되지 않아야 함
        verify(paymentServiceClient, never()).registerSessionKey(
                anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyLong(), anyString()
        );
        // PriceValidationResultEvent는 발행되지 않아야 함
        verify(priceValidationResultEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("URL_MONITOR_REQUEST - PRICE_TRACK + currentPrice 있어도 즉시 충족이 아닌 모니터링으로 처리한다")
    void consume_urlMonitorPriceTrackWithCurrentPrice_doesNotTriggerImmediateFulfillment() {
        // given: 현재가 280000 ≤ 목표가 300000이지만 intent=PRICE_TRACK → 모니터링
        String productUrl = "https://ko.aliexpress.com/item/1005011805791975.html";
        PriceRequestEvent event = createUrlMonitorEvent(
                1L, productUrl, 300000, "PRICE_TRACK", "ALL", 280000
        );

        MonitoringSubscription mockSubscription = MonitoringSubscription.createUrl(
                1L, "cmd-url-mon-001", productUrl,
                BigDecimal.valueOf(300000), CurrencyType.KRW,
                "PRICE_TRACK", MonitoringSubscriptionStatus.ACTIVE,
                10, Instant.now().plusSeconds(604800), "ALL"
        );
        when(urlMonitoringService.createSubscription(
                eq(1L), eq("cmd-url-mon-001"), eq(productUrl),
                eq(300000), eq("KRW"), eq("ALL"), eq("PRICE_TRACK")
        )).thenReturn(mockSubscription);

        // when
        priceTopicConsumer.consume(event);

        // then: PRICE_TRACK이므로 세션키도 등록하지 않고 PriceValidationResultEvent도 발행하지 않음
        verify(monitoringSubscriptionService, never()).registerSessionKeyForSubscription(any(), any());
        verify(monitoringSubscriptionService, never()).registerSessionKeyForSubscription(any(), any(), anyBoolean());
        verify(paymentServiceClient, never()).registerSessionKey(
                anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyLong(), anyString()
        );
        verify(priceValidationResultEventPublisher, never()).publish(any());
    }
}
