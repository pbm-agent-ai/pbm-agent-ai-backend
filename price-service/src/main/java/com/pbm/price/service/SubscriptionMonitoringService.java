package com.pbm.price.service;

import com.pbm.price.dto.response.AliExpressShoppingItem;
import com.pbm.price.dto.response.AliexpressProductDetailResponse;
import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.event.ProductCandidateDto;
import com.pbm.price.dto.event.PaymentRequestEvent;
import com.pbm.price.dto.event.PaymentRequestEventPayload;
import com.pbm.price.dto.event.PriceAlertEvent;
import com.pbm.price.dto.event.PriceAlertEventPayload;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.publisher.PaymentRequestEventPublisher;
import com.pbm.price.publisher.PriceAlertEventPublisher;
import com.pbm.price.repository.MonitoringSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 구독 기반 모니터링 처리 서비스
 *
 * 역할: 스케줄러가 남긴 monitoring subscription 1건을 실제로 재조회하고,
 *      현재 가격과 목표 가격을 비교하여 상태를 갱신하고 후속 이벤트를 발행한다.
 *
 * 동작:
 *      1. subscriptionId로 구독을 조회
 *      2. ACTIVE 상태가 아니면 처리하지 않음
 *      3. 플랫폼별 재조회 로직으로 최신 상품 정보를 가져옴
 *      4. 상품을 찾지 못하면 miss 처리하고, 연속 실패 임계치 이상이면 FAILED 처리
 *      5. 상품을 찾으면 현재가/통화를 검증하고 목표 가격과 비교
 *      6. 목표 가격 충족 시 TRIGGERED 상태로 전환하고 price-alert/payment 이벤트를 발행
 *      7. 미충족 시 ACTIVE 상태를 유지하고 nextCheckAt을 갱신
 * 연관: MonitoringSubscriptionRepository, PriceAlertEventPublisher,
 *      PaymentRequestEventPublisher, SubscriptionMonitoringScheduler
 */
@Slf4j
@Service
@Transactional
public class SubscriptionMonitoringService {

    /** 연속 실패 허용 최대 횟수 (이 값 초과 시 FAILED 상태로 전환) */
    private static final int MAX_CONSECUTIVE_MISS_COUNT = 2;

    /**
     * 재조회 결과를 내부에서만 전달하기 위한 보조 타입.
     *
     * @param found        상품을 찾았는지 여부
     * @param productId    상품 식별자
     * @param productUrl   상품 상세 페이지 URL
     * @param title        상품명
     * @param currentPrice 현재 가격
     * @param currency     통화 구분
     */
    public record RefreshedProductSnapshot(
            boolean found,
            String productId,
            String productUrl,
            String title,
            BigDecimal currentPrice,
            CurrencyType currency
    ) {
    }

    private final MonitoringSubscriptionRepository monitoringSubscriptionRepository;
    private final PriceAlertEventPublisher priceAlertEventPublisher;
    private final PaymentRequestEventPublisher paymentRequestEventPublisher;
    private final ExternalApiClient externalApiClient;

    public SubscriptionMonitoringService(
            MonitoringSubscriptionRepository monitoringSubscriptionRepository,
            PriceAlertEventPublisher priceAlertEventPublisher,
            PaymentRequestEventPublisher paymentRequestEventPublisher,
            ExternalApiClient externalApiClient
    ) {
        this.monitoringSubscriptionRepository = monitoringSubscriptionRepository;
        this.priceAlertEventPublisher = priceAlertEventPublisher;
        this.paymentRequestEventPublisher = paymentRequestEventPublisher;
        this.externalApiClient = externalApiClient;
    }

    /**
     * 구독 1건을 재조회하여 상태를 갱신하고 필요 시 알림/결제 이벤트를 발행한다.
     * <p>
     * 상태 전이 다이어그램:
     * <pre>
     * ACTIVE + found=false → markMiss → (missCount >= 2 ? FAILED : ACTIVE)
     * ACTIVE + found=true + currency!=KRW → FAILED
     * ACTIVE + found=true + currency=KRW + currentPrice <= targetPrice → TRIGGERED + 이벤트 발행
     * ACTIVE + found=true + currency=KRW + currentPrice > targetPrice → ACTIVE (유지, nextCheckAt 갱신)
     * </pre>
     *
     * @param subscriptionId 처리할 모니터링 구독 ID
     */
    public void process(Long subscriptionId) {
        MonitoringSubscription subscription = monitoringSubscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("모니터링 구독을 찾을 수 없습니다. id=" + subscriptionId));

        // ACTIVE 상태가 아니면 아무것도 하지 않고 반환
        if (subscription.getStatus() != MonitoringSubscriptionStatus.ACTIVE) {
            log.debug("ACTIVE 상태가 아니므로 process 건너뜀 - subscriptionId: {}, status: {}",
                    subscriptionId, subscription.getStatus());
            return;
        }

        Instant now = Instant.now();
        log.info("모니터링 구독 재조회 시작 - subscriptionId: {}, platform: {}, productId: {}",
                subscriptionId, subscription.getPlatform(), subscription.getProductId());

        RefreshedProductSnapshot snapshot = refreshSubscription(subscription);

        // ──────────────────────────────────────────────
        // 1) 상품을 찾지 못한 경우: miss 처리
        // ──────────────────────────────────────────────
        if (!snapshot.found()) {
            subscription.markMiss(now);
            log.warn("상품 재조회 실패 (miss) - subscriptionId: {}, missCount: {}",
                    subscriptionId, subscription.getConsecutiveMissCount());

            if (subscription.getConsecutiveMissCount() >= MAX_CONSECUTIVE_MISS_COUNT) {
                subscription.changeStatus(MonitoringSubscriptionStatus.FAILED);
                log.warn("연속 miss 임계치 초과로 FAILED 처리 - subscriptionId: {}, missCount: {}",
                        subscriptionId, subscription.getConsecutiveMissCount());
            }

            monitoringSubscriptionRepository.save(subscription);
            return;
        }

        // ──────────────────────────────────────────────
        // 2) 상품을 찾았지만 KRW 통화가 아닌 경우: FAILED
        // ──────────────────────────────────────────────
        if (snapshot.currency() != CurrencyType.KRW) {
            subscription.changeStatus(MonitoringSubscriptionStatus.FAILED);
            log.warn("통화가 KRW가 아니므로 FAILED 처리 - subscriptionId: {}, currency: {}",
                    subscriptionId, snapshot.currency());
            monitoringSubscriptionRepository.save(subscription);
            return;
        }

        // ──────────────────────────────────────────────
        // 3) 통화가 KRW인 경우: 목표 가격과 비교
        // ──────────────────────────────────────────────
        int comparison = snapshot.currentPrice().compareTo(subscription.getTargetPrice());
        log.info("가격 비교 결과 - subscriptionId: {}, currentPrice: {}, targetPrice: {}, 비교값: {}",
                subscriptionId, snapshot.currentPrice(), subscription.getTargetPrice(), comparison);

        if (comparison <= 0) {
            // 목표 가격 이하 → TRIGGERED 상태로 전환
            subscription.changeStatus(MonitoringSubscriptionStatus.TRIGGERED);
            subscription.markSuccess(now);
            monitoringSubscriptionRepository.save(subscription);

            log.info("목표 가격 충족! TRIGGERED 전환 및 이벤트 발행 - subscriptionId: {}, currentPrice: {}, targetPrice: {}",
                    subscriptionId, snapshot.currentPrice(), subscription.getTargetPrice());

            publishTriggeredEvents(subscription, snapshot);
        } else {
            // 목표 가격 초과 → ACTIVE 유지
            subscription.markSuccess(now);
            monitoringSubscriptionRepository.save(subscription);

            log.info("목표 가격 미충족, ACTIVE 유지 - subscriptionId: {}, currentPrice: {}, targetPrice: {}",
                    subscriptionId, snapshot.currentPrice(), subscription.getTargetPrice());
        }
    }

    /**
     * 플랫폼별 재조회를 수행한다.
     * <p>
     * 현재는 placeholder 구현으로 found=false를 반환한다.
     * 추후 각 플랫폼별 실제 API 호출 로직으로 교체해야 한다.
     *
     * @param subscription 재조회 대상 구독
     * @return 재조회 결과 스냅샷
     */
    RefreshedProductSnapshot refreshSubscription(MonitoringSubscription subscription) {
        return switch (subscription.getPlatform()) {
            case NAVER -> refreshNaver(subscription);
            case ALIEXPRESS -> refreshAliExpress(subscription);
        };
    }

    /**
     * 선택된 후보 상품 1건을 즉시 단건 재조회한다.
     *
     * @param candidate 선택한 후보 상품 DTO
     * @return 재조회 결과 스냅샷
     */
    public RefreshedProductSnapshot refreshSelectedProduct(ProductCandidateDto candidate) {
        MonitoringSubscription temporarySubscription = MonitoringSubscription.create(
                0L,
                UUID.randomUUID().toString(),
                Platform.valueOf(candidate.platform()),
                candidate.productId(),
                candidate.productUrl(),
                candidate.title(),
                BigDecimal.ZERO,
                candidate.searchKeyword(),
                BigDecimal.ZERO,
                CurrencyType.valueOf(candidate.currency()),
                "PRICE_CHECK",
                MonitoringSubscriptionStatus.ACTIVE,
                0,
                5
        );
        return refreshSubscription(temporarySubscription);
    }

    /**
     * 네이버 쇼핑 상품을 searchKeyword 기반 재조회하여 매칭한다.
     *
     * 매칭 전략:
     * 1. display=100, start=1로 1차 조회 (네이버 API 1회 호출당 최대 100건)
     * 2. productId 우선 매칭, 없으면 productUrl(link) fallback 매칭
     * 3. 1차에서 못 찾았고 결과가 100건(최대)이면 start=101로 2차 조회 후 재매칭
     * 4. lprice 파싱 실패 시 found=false 처리
     *
     * @param subscription 네이버 구독
     * @return 재조회 결과 스냅샷
     */
    private RefreshedProductSnapshot refreshNaver(MonitoringSubscription subscription) {
        String keyword = subscription.getSearchKeyword();
        String targetProductId = subscription.getProductId();
        String targetProductUrl = subscription.getProductUrl();

        log.debug("네이버 상품 재조회 시작 - subscriptionId: {}, productId: {}, keyword: {}",
                subscription.getId(), targetProductId, keyword);

        // 1차 조회: display=100, start=1
        List<NaverShoppingItem> itemsPage1 = externalApiClient.searchNaverProductItems(keyword, 100, 1);

        // productId 우선 매칭, 없으면 productUrl fallback 매칭
        NaverShoppingItem matched = matchNaverItem(itemsPage1, targetProductId, targetProductUrl);

        // 1차 조회에서 못 찾았고, 100건이 모두 채워졌으면 2차 조회 (start=101)
        if (matched == null && itemsPage1.size() >= 100) {
            log.debug("1차 조회에서 미매칭, 2차 조회 시도 - subscriptionId: {}", subscription.getId());
            List<NaverShoppingItem> itemsPage2 = externalApiClient.searchNaverProductItems(keyword, 100, 101);

            matched = matchNaverItem(itemsPage2, targetProductId, targetProductUrl);
        }

        if (matched == null) {
            log.warn("네이버 상품 매칭 실패 - subscriptionId: {}, productId: {}", subscription.getId(), targetProductId);
            return new RefreshedProductSnapshot(
                    false,
                    targetProductId,
                    targetProductUrl,
                    subscription.getSnapshotTitle(),
                    BigDecimal.ZERO,
                    CurrencyType.KRW
            );
        }

        // lprice 파싱
        try {
            BigDecimal currentPrice = new BigDecimal(matched.lprice());
            log.info("네이버 상품 재조회 성공 - subscriptionId: {}, title: {}, price: {}",
                    subscription.getId(), matched.title(), currentPrice);
            return new RefreshedProductSnapshot(
                    true,
                    matched.productId(),
                    matched.link(),
                    matched.title(),
                    currentPrice,
                    CurrencyType.KRW
            );
        } catch (NumberFormatException e) {
            log.warn("네이버 가격 파싱 실패 - subscriptionId: {}, productId: {}, lprice: '{}'",
                    subscription.getId(), matched.productId(), matched.lprice());
            return new RefreshedProductSnapshot(
                    false,
                    matched.productId(),
                    matched.link(),
                    matched.title(),
                    BigDecimal.ZERO,
                    CurrencyType.KRW
            );
        }
    }

    /**
     * 네이버 상품 목록에서 targetProductId 또는 targetProductUrl로 매칭한다.
     * productId가 우선 매칭되며, 없으면 productUrl(link)로 fallback 매칭한다.
     *
     * @param items            네이버 쇼핑 검색 결과 목록
     * @param targetProductId  매칭 대상 productId
     * @param targetProductUrl 매칭 대상 productUrl (link)
     * @return 매칭된 상품 또는 null (매칭 실패)
     */
    private NaverShoppingItem matchNaverItem(List<NaverShoppingItem> items,
                                              String targetProductId,
                                              String targetProductUrl) {
        if (items == null || items.isEmpty()) {
            return null;
        }

        // productId 우선 매칭
        if (targetProductId != null && !targetProductId.isBlank()) {
            for (NaverShoppingItem item : items) {
                if (targetProductId.equals(item.productId())) {
                    return item;
                }
            }
        }

        // productUrl fallback 매칭 (link 필드와 비교)
        if (targetProductUrl != null && !targetProductUrl.isBlank()) {
            for (NaverShoppingItem item : items) {
                if (targetProductUrl.equals(item.link())) {
                    return item;
                }
            }
        }

        return null;
    }

    /**
     * 알리익스프레스 상품을 productId 기반으로 단건 재조회한다.
     *
     * @param subscription 알리익스프레스 구독
     * @return 재조회 결과 스냅샷
     * sale_price: 원본 판매가로 USD 성격을 가짐
     * target_sale_price: 타겟 통화로 변환된 판매가로 KRW 기준 조회 시 원화 가격이 들어온다.
     * RefreshedProductSnapshot: 플랫폼별 응답을 공통 형식으로 표준화하는 역할
     */
    private RefreshedProductSnapshot refreshAliExpress(MonitoringSubscription subscription) {
        log.debug("알리익스프레스 상품 재조회 - subscriptionId: {}, productId: {}",
                subscription.getId(), subscription.getProductId());
        AliexpressProductDetailResponse response =
                externalApiClient.getAliExpressProductDetail(subscription.getProductId(),
                        "KRW",
                        "KO",
                        "KR"
                );
        AliExpressShoppingItem product = response.product();
        if (product == null) {
            return new RefreshedProductSnapshot(
                    false,
                    subscription.getProductId(),
                    subscription.getProductUrl(),
                    subscription.getSnapshotTitle(),
                    BigDecimal.ZERO,
                    subscription.getCurrency()
            );
        }
        String rawPrice = null;
        CurrencyType currency;
        // target_sale_price가 있으면 우선 사용
        if (product.target_sale_price() != null && !product.target_sale_price().isBlank()) {
            rawPrice = product.target_sale_price();
            currency = CurrencyType.KRW;
        } else if (product.sale_price() != null && !product.sale_price().isBlank()) {
            rawPrice = product.sale_price();
            currency = CurrencyType.USD;
        } else {
            return new RefreshedProductSnapshot(
                    false,
                    product.product_id(),
                    product.product_detail_url(),
                    product.product_title(),
                    BigDecimal.ZERO,
                    subscription.getCurrency()
            );
        }
        try {
            BigDecimal currentPrice = new BigDecimal(rawPrice);
            return new RefreshedProductSnapshot(
                    true,
                    product.product_id(),
                    product.product_detail_url(),
                    product.product_title(),
                    currentPrice,
                    currency
            );
        } catch (NumberFormatException e) {
            log.warn("알리익스프레스 가격 파싱 실패 - subscriptionId: {}, productId: {}, rawPrice: {}",
                    subscription.getId(), subscription.getProductId(), rawPrice);
            return new RefreshedProductSnapshot(
                    false,
                    product.product_id(),
                    product.product_detail_url(),
                    product.product_title(),
                    BigDecimal.ZERO,
                    currency
            );
        }
    }

    /**
     * 목표 가격 충족 시 price-alert와 payment-topic으로 이벤트를 발행한다.
     * <p>
     * price-alert: notification-service가 사용자에게 알림을 보내기 위한 이벤트
     * payment-topic: payment-service가 자동 결제를 실행하기 위한 이벤트
     *
     * @param subscription 트리거된 구독
     * @param snapshot     재조회 결과 스냅샷
     */
    private void publishTriggeredEvents(MonitoringSubscription subscription, RefreshedProductSnapshot snapshot) {
        Instant now = Instant.now();

        // price-alert 이벤트 발행 (알림용)
        PriceAlertEventPayload alertPayload = new PriceAlertEventPayload(
                subscription.getUserId(),
                snapshot.title(),
                snapshot.currentPrice().intValue(),
                subscription.getTargetPrice().intValue(),
                snapshot.productUrl(),
                subscription.getSearchKeyword()
        );
        PriceAlertEvent alertEvent = new PriceAlertEvent(
                UUID.randomUUID().toString(),
                "PRICE_ALERT",
                now,
                "price-service",
                alertPayload
        );
        priceAlertEventPublisher.publish(alertEvent);
        log.info("price-alert 이벤트 발행 완료 - subscriptionId: {}, eventId: {}",
                subscription.getId(), alertEvent.eventId());

        if (!"AUTO_PURCHASE".equals(subscription.getIntent())) {
            return;
        }

        // payment-topic 이벤트 발행 (결제 요청용)
        PaymentRequestEventPayload paymentPayload = new PaymentRequestEventPayload(
                subscription.getUserId(),
                snapshot.title(),
                snapshot.productUrl(),
                snapshot.currentPrice().intValue(),
                subscription.getCurrency().name(),
                subscription.getSearchKeyword()
        );
        PaymentRequestEvent paymentEvent = new PaymentRequestEvent(
                UUID.randomUUID().toString(),
                "PAYMENT_REQUESTED",
                now,
                "price-service",
                paymentPayload
        );
        paymentRequestEventPublisher.publish(paymentEvent);
        log.info("payment-topic 이벤트 발행 완료 - subscriptionId: {}, eventId: {}",
                subscription.getId(), paymentEvent.eventId());
    }
}
