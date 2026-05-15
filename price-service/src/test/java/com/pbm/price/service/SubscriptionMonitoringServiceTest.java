package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.publisher.PaymentRequestEventPublisher;
import com.pbm.price.publisher.PriceAlertEventPublisher;
import com.pbm.price.repository.MonitoringSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * SubscriptionMonitoringService 단위 테스트.
 *
 * 검증 내용:
 * - ACTIVE 상태가 아니면 process를 건너뛰는지 확인
 * - 상품 미조회 시 miss 처리 및 연속 실패 임계치 도달 시 FAILED 전환 확인
 * - KRW가 아닌 통화로 조회 시 FAILED 전환 확인
 * - 목표 가격 이하 시 TRIGGERED 전환 + 이벤트 발행 확인
 * - 목표 가격 초과 시 ACTIVE 유지 + 이벤트 미발행 확인
 * - 존재하지 않는 subscriptionId 조회 시 예외 발생 확인
 *
 * refreshSubscription 결과를 제어하기 위해 익명 하위 클래스 패턴을 사용한다.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionMonitoringServiceTest {

    @Mock
    private MonitoringSubscriptionRepository monitoringSubscriptionRepository;

    @Mock
    private PriceAlertEventPublisher priceAlertEventPublisher;

    @Mock
    private PaymentRequestEventPublisher paymentRequestEventPublisher;

    @Mock
    private ExternalApiClient externalApiClient;

    /** 테스트에서 제어할 refreshSubscription 결과 */
    private SubscriptionMonitoringService.RefreshedProductSnapshot controlledSnapshot;

    /** refreshSubscription이 제어된 서비스 인스턴스 */
    private SubscriptionMonitoringService service;

    @BeforeEach
    void setUp() {
        // 익명 하위 클래스로 refreshSubscription을 오버라이드하여 테스트 가능하게 함
        service = new SubscriptionMonitoringService(
                monitoringSubscriptionRepository,
                priceAlertEventPublisher,
                paymentRequestEventPublisher,
                externalApiClient
        ) {
            @Override
            SubscriptionMonitoringService.RefreshedProductSnapshot refreshSubscription(
                    MonitoringSubscription subscription
            ) {
                return controlledSnapshot;
            }
        };
    }

    /**
     * 테스트용 MonitoringSubscription 엔티티를 생성한다.
     * ID는 ReflectionTestUtils로 설정한다.
     */
    private MonitoringSubscription createSubscription(
            Long id,
            MonitoringSubscriptionStatus status,
            Platform platform,
            CurrencyType currency,
            int consecutiveMissCount,
            BigDecimal targetPrice,
            String intent
    ) {
        MonitoringSubscription sub = MonitoringSubscription.create(
                1L,                               // userId
                UUID.randomUUID().toString(),     // commandId
                platform,                         // platform
                "prod-001",                       // productId
                "https://example.com/p/prod-001", // productUrl
                "테스트 상품",                     // snapshotTitle
                BigDecimal.valueOf(50000),         // snapshotPrice
                "테스트 키워드",                   // searchKeyword
                targetPrice,                      // targetPrice
                currency,                         // currency
                intent,                           // intent
                status,                           // status
                consecutiveMissCount,             // consecutiveMissCount
                5                                 // checkIntervalMinutes
        );
        ReflectionTestUtils.setField(sub, "id", id);
        return sub;
    }

    /**
     * 테스트용 RefreshedProductSnapshot을 생성한다.
     */
    private SubscriptionMonitoringService.RefreshedProductSnapshot createSnapshot(
            boolean found,
            BigDecimal currentPrice,
            CurrencyType currency
    ) {
        return new SubscriptionMonitoringService.RefreshedProductSnapshot(
                found,
                "prod-001",
                "https://example.com/p/prod-001",
                "테스트 상품",
                currentPrice,
                currency
        );
    }

    // =========================================================================
    // 비-ACTIVE 상태 처리
    // =========================================================================

    @Test
    @DisplayName("COMPLETED 상태의 구독은 process를 건너뛴다")
    void process_nonActiveStatus_completed_returnsEarly() {
        // given
        MonitoringSubscription sub = createSubscription(
                100L, MonitoringSubscriptionStatus.COMPLETED,
                Platform.NAVER, CurrencyType.KRW, 0, BigDecimal.valueOf(30000),
                "PRICE_TRACK"
        );
        when(monitoringSubscriptionRepository.findById(100L)).thenReturn(Optional.of(sub));

        // when
        service.process(100L);

        // then: save/publish가 호출되지 않음
        assertThat(sub.getStatus()).isEqualTo(MonitoringSubscriptionStatus.COMPLETED);
        verify(monitoringSubscriptionRepository, never()).save(any());
        verify(priceAlertEventPublisher, never()).publish(any());
        verify(paymentRequestEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("PAUSED 상태의 구독은 process를 건너뛴다")
    void process_nonActiveStatus_paused_returnsEarly() {
        // given
        MonitoringSubscription sub = createSubscription(
                100L, MonitoringSubscriptionStatus.PAUSED,
                Platform.NAVER, CurrencyType.KRW, 0, BigDecimal.valueOf(30000),
                "PRICE_TRACK"
        );
        when(monitoringSubscriptionRepository.findById(100L)).thenReturn(Optional.of(sub));

        // when
        service.process(100L);

        // then
        assertThat(sub.getStatus()).isEqualTo(MonitoringSubscriptionStatus.PAUSED);
        verify(monitoringSubscriptionRepository, never()).save(any());
    }

    // =========================================================================
    // 상품 미조회 (found=false) 처리
    // =========================================================================

    @Test
    @DisplayName("상품을 찾지 못하면 miss 카운트가 증가하고 ACTIVE를 유지한다")
    void process_notFound_missCountIncremented_activeMaintained() {
        // given: missCount=0, 임계치(2) 미만
        MonitoringSubscription sub = createSubscription(
                100L, MonitoringSubscriptionStatus.ACTIVE,
                Platform.NAVER, CurrencyType.KRW, 0, BigDecimal.valueOf(30000),
                "PRICE_TRACK"
        );
        when(monitoringSubscriptionRepository.findById(100L)).thenReturn(Optional.of(sub));
        when(monitoringSubscriptionRepository.save(sub)).thenReturn(sub);

        controlledSnapshot = createSnapshot(false, BigDecimal.ZERO, CurrencyType.KRW);

        // when
        service.process(100L);

        // then: miss 카운트 1 증가, ACTIVE 유지
        assertThat(sub.getConsecutiveMissCount()).isEqualTo(1);
        assertThat(sub.getStatus()).isEqualTo(MonitoringSubscriptionStatus.ACTIVE);
        assertThat(sub.getLastCheckedAt()).isNotNull();
        assertThat(sub.getNextCheckAt()).isAfter(sub.getLastCheckedAt());
        verify(monitoringSubscriptionRepository).save(sub);
    }

    @Test
    @DisplayName("연속 miss 횟수가 임계치(2)를 초과하면 FAILED가 된다")
    void process_notFound_exceedsThreshold_marksFailed() {
        // given: missCount=2 (이미 임계치에 도달), 한 번 더 miss → FAILED
        MonitoringSubscription sub = createSubscription(
                100L, MonitoringSubscriptionStatus.ACTIVE,
                Platform.NAVER, CurrencyType.KRW, 2, BigDecimal.valueOf(30000),
                "PRICE_TRACK"
        );
        when(monitoringSubscriptionRepository.findById(100L)).thenReturn(Optional.of(sub));
        when(monitoringSubscriptionRepository.save(sub)).thenReturn(sub);

        controlledSnapshot = createSnapshot(false, BigDecimal.ZERO, CurrencyType.KRW);

        // when
        service.process(100L);

        // then: FAILED로 전환
        assertThat(sub.getConsecutiveMissCount()).isEqualTo(3);
        assertThat(sub.getStatus()).isEqualTo(MonitoringSubscriptionStatus.FAILED);
        verify(monitoringSubscriptionRepository).save(sub);
    }

    // =========================================================================
    // 통화 검증 (currency != KRW)
    // =========================================================================

    @Test
    @DisplayName("조회된 상품의 통화가 KRW가 아니면 FAILED가 된다")
    void process_foundNonKrwCurrency_marksFailed() {
        // given: USD로 조회됨
        MonitoringSubscription sub = createSubscription(
                100L, MonitoringSubscriptionStatus.ACTIVE,
                Platform.ALIEXPRESS, CurrencyType.USD, 0, BigDecimal.valueOf(30000),
                "PRICE_TRACK"
        );
        when(monitoringSubscriptionRepository.findById(100L)).thenReturn(Optional.of(sub));
        when(monitoringSubscriptionRepository.save(sub)).thenReturn(sub);

        controlledSnapshot = createSnapshot(true, BigDecimal.valueOf(25000), CurrencyType.USD);

        // when
        service.process(100L);

        // then: FAILED로 전환, 이벤트 미발행
        assertThat(sub.getStatus()).isEqualTo(MonitoringSubscriptionStatus.FAILED);
        verify(monitoringSubscriptionRepository).save(sub);
        verify(priceAlertEventPublisher, never()).publish(any());
        verify(paymentRequestEventPublisher, never()).publish(any());
    }

    // =========================================================================
    // 목표 가격 충족 (currentPrice <= targetPrice)
    // =========================================================================

    @Test
    @DisplayName("목표 가격 이하로 조회되면 TRIGGERED로 전환하고 이벤트를 발행한다")
    void process_foundBelowTargetPrice_triggersAndPublishesEvents() {
        // given: currentPrice=25000, targetPrice=30000 → 충족
        MonitoringSubscription sub = createSubscription(
                100L, MonitoringSubscriptionStatus.ACTIVE,
                Platform.NAVER, CurrencyType.KRW, 0, BigDecimal.valueOf(30000),
                "AUTO_PURCHASE"
        );
        when(monitoringSubscriptionRepository.findById(100L)).thenReturn(Optional.of(sub));
        when(monitoringSubscriptionRepository.save(sub)).thenReturn(sub);

        controlledSnapshot = createSnapshot(true, BigDecimal.valueOf(25000), CurrencyType.KRW);

        // when
        service.process(100L);

        // then: TRIGGERED, 마지막 체크 시각 갱신, 이벤트 발행
        assertThat(sub.getStatus()).isEqualTo(MonitoringSubscriptionStatus.TRIGGERED);
        assertThat(sub.getConsecutiveMissCount()).isZero();
        assertThat(sub.getLastCheckedAt()).isNotNull();
        verify(monitoringSubscriptionRepository).save(sub);
        verify(priceAlertEventPublisher).publish(any());
        verify(paymentRequestEventPublisher).publish(any());
    }

    @Test
    @DisplayName("목표 가격과 같은 가격으로 조회되어도 TRIGGERED로 전환된다 (동일 가격)")
    void process_foundEqualToTargetPrice_triggersAndPublishesEvents() {
        // given: currentPrice=30000, targetPrice=30000 → 동일 (충족)
        MonitoringSubscription sub = createSubscription(
                100L, MonitoringSubscriptionStatus.ACTIVE,
                Platform.NAVER, CurrencyType.KRW, 0, BigDecimal.valueOf(30000),
                "AUTO_PURCHASE"
        );
        when(monitoringSubscriptionRepository.findById(100L)).thenReturn(Optional.of(sub));
        when(monitoringSubscriptionRepository.save(sub)).thenReturn(sub);

        controlledSnapshot = createSnapshot(true, BigDecimal.valueOf(30000), CurrencyType.KRW);

        // when
        service.process(100L);

        // then: TRIGGERED
        assertThat(sub.getStatus()).isEqualTo(MonitoringSubscriptionStatus.TRIGGERED);
        verify(priceAlertEventPublisher).publish(any());
        verify(paymentRequestEventPublisher).publish(any());
    }

    // =========================================================================
    // 목표 가격 미충족 (currentPrice > targetPrice)
    // =========================================================================

    @Test
    @DisplayName("목표 가격을 초과하면 ACTIVE를 유지하고 이벤트를 발행하지 않는다")
    void process_foundAboveTargetPrice_maintainsActiveAndNoEvents() {
        // given: currentPrice=35000, targetPrice=30000 → 초과
        MonitoringSubscription sub = createSubscription(
                100L, MonitoringSubscriptionStatus.ACTIVE,
                Platform.NAVER, CurrencyType.KRW, 0, BigDecimal.valueOf(30000),
                "PRICE_TRACK"
        );
        when(monitoringSubscriptionRepository.findById(100L)).thenReturn(Optional.of(sub));
        when(monitoringSubscriptionRepository.save(sub)).thenReturn(sub);

        controlledSnapshot = createSnapshot(true, BigDecimal.valueOf(35000), CurrencyType.KRW);

        // when
        service.process(100L);

        // then: ACTIVE 유지, 이벤트 없음, missCount 리셋, 체크 시각 갱신
        assertThat(sub.getStatus()).isEqualTo(MonitoringSubscriptionStatus.ACTIVE);
        assertThat(sub.getConsecutiveMissCount()).isZero();
        assertThat(sub.getLastCheckedAt()).isNotNull();
        verify(monitoringSubscriptionRepository).save(sub);
        verify(priceAlertEventPublisher, never()).publish(any());
        verify(paymentRequestEventPublisher, never()).publish(any());
    }

    // =========================================================================
    // PRICE_TRACK intent — payment 이벤트 미발행 검증
    // =========================================================================

    @Test
    @DisplayName("PRICE_TRACK intent로 목표 가격 충족 시 price-alert는 발행하지만 payment는 발행하지 않는다")
    void process_priceTrackIntent_doesNotPublishPaymentEvent() {
        // given: currentPrice=25000, targetPrice=30000 → 충족, intent=PRICE_TRACK
        MonitoringSubscription sub = createSubscription(
                100L, MonitoringSubscriptionStatus.ACTIVE,
                Platform.NAVER, CurrencyType.KRW, 0, BigDecimal.valueOf(30000),
                "PRICE_TRACK"
        );
        when(monitoringSubscriptionRepository.findById(100L)).thenReturn(Optional.of(sub));
        when(monitoringSubscriptionRepository.save(sub)).thenReturn(sub);

        controlledSnapshot = createSnapshot(true, BigDecimal.valueOf(25000), CurrencyType.KRW);

        // when
        service.process(100L);

        // then: TRIGGERED, price-alert는 발행되지만 payment는 발행되지 않음
        assertThat(sub.getStatus()).isEqualTo(MonitoringSubscriptionStatus.TRIGGERED);
        verify(monitoringSubscriptionRepository).save(sub);
        verify(priceAlertEventPublisher).publish(any());
        verify(paymentRequestEventPublisher, never()).publish(any());
    }

    // =========================================================================
    // 예외 처리
    // =========================================================================

    @Test
    @DisplayName("존재하지 않는 subscriptionId로 조회 시 IllegalArgumentException이 발생한다")
    void process_subscriptionNotFound_throwsException() {
        // given
        when(monitoringSubscriptionRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.process(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("모니터링 구독을 찾을 수 없습니다");
    }

    // =========================================================================
    // 네이버 재조회 (refreshNaver) 상세 검증
    // =========================================================================

    /** refreshNaver의 실제 로직을 테스트하기 위한 서비스 인스턴스 생성 */
    private SubscriptionMonitoringService createRealService() {
        return new SubscriptionMonitoringService(
                monitoringSubscriptionRepository,
                priceAlertEventPublisher,
                paymentRequestEventPublisher,
                externalApiClient
        );
    }

    /**
     * 테스트용 네이버 쇼핑 아이템을 생성한다.
     */
    private NaverShoppingItem createNaverItem(String productId, String link, String lprice, String title) {
        return new NaverShoppingItem(
                title,          // title
                lprice,         // lprice
                "0",            // hprice
                "테스트몰",     // mallName
                link,           // link
                productId,      // productId
                "",             // image
                "",             // maker
                "",             // brand
                "",             // category1
                "",             // category2
                "",             // category3
                ""              // category4
        );
    }

    @Nested
    @DisplayName("refreshNaver - 네이버 상품 재조회")
    class NaverRefreshTest {

        private MonitoringSubscription subscription;
        private final Long SUBSCRIPTION_ID = 200L;
        private final String PRODUCT_ID = "naver-prod-001";
        private final String PRODUCT_URL = "https://shop.example.com/item/naver-prod-001";
        private final String SEARCH_KEYWORD = "테스트 상품";

        @BeforeEach
        void setUp() {
            subscription = MonitoringSubscription.create(
                    1L,                              // userId
                    UUID.randomUUID().toString(),    // commandId
                    Platform.NAVER,                  // platform
                    PRODUCT_ID,                      // productId
                    PRODUCT_URL,                     // productUrl
                    "테스트 상품명",                  // snapshotTitle
                    BigDecimal.valueOf(50000),        // snapshotPrice
                    SEARCH_KEYWORD,                  // searchKeyword
                    BigDecimal.valueOf(30000),        // targetPrice
                    CurrencyType.KRW,                // currency
                    "PRICE_TRACK",                   // intent
                    MonitoringSubscriptionStatus.ACTIVE, // status
                    0,                               // consecutiveMissCount
                    5                                // checkIntervalMinutes
            );
            ReflectionTestUtils.setField(subscription, "id", SUBSCRIPTION_ID);
        }

        @Test
        @DisplayName("productId로 매칭 성공 시 found=true, KRW 가격 반환")
        void matchByProductId_returnsFound() {
            // given
            NaverShoppingItem matchedItem = createNaverItem(PRODUCT_ID, PRODUCT_URL, "25000", "테스트 상품명");
            when(externalApiClient.searchNaverProductItems(SEARCH_KEYWORD, 100, 1))
                    .thenReturn(List.of(matchedItem));

            SubscriptionMonitoringService realService = createRealService();

            // when
            SubscriptionMonitoringService.RefreshedProductSnapshot snapshot =
                    realService.refreshSubscription(subscription);

            // then
            assertThat(snapshot.found()).isTrue();
            assertThat(snapshot.currentPrice()).isEqualByComparingTo(BigDecimal.valueOf(25000));
            assertThat(snapshot.currency()).isEqualTo(CurrencyType.KRW);
            assertThat(snapshot.productId()).isEqualTo(PRODUCT_ID);
            assertThat(snapshot.productUrl()).isEqualTo(PRODUCT_URL);
            assertThat(snapshot.title()).isEqualTo("테스트 상품명");
            verify(externalApiClient, times(1)).searchNaverProductItems(SEARCH_KEYWORD, 100, 1);
        }

        @Test
        @DisplayName("productId 매칭 실패 시 productUrl(link)로 fallback 매칭 성공")
        void matchByProductUrlFallback_returnsFound() {
            // given - productId는 다르지만 link가 일치하는 아이템
            NaverShoppingItem matchedItem = createNaverItem("other-id", PRODUCT_URL, "35000", "다른 상품명");
            when(externalApiClient.searchNaverProductItems(SEARCH_KEYWORD, 100, 1))
                    .thenReturn(List.of(matchedItem));

            SubscriptionMonitoringService realService = createRealService();

            // when
            SubscriptionMonitoringService.RefreshedProductSnapshot snapshot =
                    realService.refreshSubscription(subscription);

            // then - link(productUrl)로 매칭되어 found=true
            assertThat(snapshot.found()).isTrue();
            assertThat(snapshot.currentPrice()).isEqualByComparingTo(BigDecimal.valueOf(35000));
            assertThat(snapshot.productId()).isEqualTo("other-id");
            assertThat(snapshot.productUrl()).isEqualTo(PRODUCT_URL);
        }

        @Test
        @DisplayName("1차 조회에서 못 찾고 100건 미만이면 2차 조회 없이 found=false")
        void notFound_firstPageLessThan100_noSecondPage() {
            // given - 99건만 반환 (100 미만이므로 추가 페이지 없음)
            NaverShoppingItem unrelatedItem = createNaverItem("unrelated", "https://other.url", "10000", "다른상품");
            List<NaverShoppingItem> page1 = java.util.stream.IntStream.range(0, 99)
                    .mapToObj(i -> createNaverItem("item-" + i, "https://url/" + i, "10000", "상품" + i))
                    .collect(java.util.stream.Collectors.toList());
            // 첫 번째 아이템을 unrelated로 교체
            page1.set(0, unrelatedItem);

            when(externalApiClient.searchNaverProductItems(SEARCH_KEYWORD, 100, 1))
                    .thenReturn(page1);

            SubscriptionMonitoringService realService = createRealService();

            // when
            SubscriptionMonitoringService.RefreshedProductSnapshot snapshot =
                    realService.refreshSubscription(subscription);

            // then - 2차 조회 없이 found=false
            assertThat(snapshot.found()).isFalse();
            verify(externalApiClient, times(1)).searchNaverProductItems(SEARCH_KEYWORD, 100, 1);
            verify(externalApiClient, never()).searchNaverProductItems(anyString(), anyInt(), eq(101));
        }

        @Test
        @DisplayName("1차 조회 100건, 2차 조회에서 매칭 성공")
        void matchOnSecondPage_returnsFound() {
            // given - 1차 페이지 100건, 2차 페이지에서 매칭
            List<NaverShoppingItem> page1 = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(i -> createNaverItem("item-" + i, "https://url/" + i, "10000", "상품" + i))
                    .collect(java.util.stream.Collectors.toList());

            NaverShoppingItem matchedItem = createNaverItem(PRODUCT_ID, PRODUCT_URL, "15000", "찾은상품");
            List<NaverShoppingItem> page2 = List.of(matchedItem);

            when(externalApiClient.searchNaverProductItems(SEARCH_KEYWORD, 100, 1))
                    .thenReturn(page1);
            when(externalApiClient.searchNaverProductItems(SEARCH_KEYWORD, 100, 101))
                    .thenReturn(page2);

            SubscriptionMonitoringService realService = createRealService();

            // when
            SubscriptionMonitoringService.RefreshedProductSnapshot snapshot =
                    realService.refreshSubscription(subscription);

            // then - 2차 페이지에서 매칭 성공
            assertThat(snapshot.found()).isTrue();
            assertThat(snapshot.currentPrice()).isEqualByComparingTo(BigDecimal.valueOf(15000));
            assertThat(snapshot.productId()).isEqualTo(PRODUCT_ID);
            verify(externalApiClient, times(1)).searchNaverProductItems(SEARCH_KEYWORD, 100, 1);
            verify(externalApiClient, times(1)).searchNaverProductItems(SEARCH_KEYWORD, 100, 101);
        }

        @Test
        @DisplayName("lprice 파싱 실패 시 found=false 반환")
        void lpriceParseFailure_returnsNotFound() {
            // given - lprice가 숫자가 아님
            NaverShoppingItem matchedItem = createNaverItem(PRODUCT_ID, PRODUCT_URL, "가격없음", "테스트 상품명");
            when(externalApiClient.searchNaverProductItems(SEARCH_KEYWORD, 100, 1))
                    .thenReturn(List.of(matchedItem));

            SubscriptionMonitoringService realService = createRealService();

            // when
            SubscriptionMonitoringService.RefreshedProductSnapshot snapshot =
                    realService.refreshSubscription(subscription);

            // then - found=false, 가격은 0
            assertThat(snapshot.found()).isFalse();
            assertThat(snapshot.currentPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("검색 결과가 빈 리스트면 found=false")
        void emptySearchResults_returnsNotFound() {
            // given
            when(externalApiClient.searchNaverProductItems(SEARCH_KEYWORD, 100, 1))
                    .thenReturn(List.of());

            SubscriptionMonitoringService realService = createRealService();

            // when
            SubscriptionMonitoringService.RefreshedProductSnapshot snapshot =
                    realService.refreshSubscription(subscription);

            // then
            assertThat(snapshot.found()).isFalse();
            verify(externalApiClient, never()).searchNaverProductItems(anyString(), anyInt(), eq(101));
        }

        @Test
        @DisplayName("productId가 null/blank면 productUrl로만 매칭")
        void nullProductId_fallsBackToProductUrl() {
            // given - 구독의 productId가 null
            ReflectionTestUtils.setField(subscription, "productId", "");

            NaverShoppingItem matchedItem = createNaverItem("any-id", PRODUCT_URL, "20000", "URL매칭상품");
            when(externalApiClient.searchNaverProductItems(SEARCH_KEYWORD, 100, 1))
                    .thenReturn(List.of(matchedItem));

            SubscriptionMonitoringService realService = createRealService();

            // when
            SubscriptionMonitoringService.RefreshedProductSnapshot snapshot =
                    realService.refreshSubscription(subscription);

            // then - URL로 매칭 성공
            assertThat(snapshot.found()).isTrue();
            assertThat(snapshot.productUrl()).isEqualTo(PRODUCT_URL);
        }
    }
}
