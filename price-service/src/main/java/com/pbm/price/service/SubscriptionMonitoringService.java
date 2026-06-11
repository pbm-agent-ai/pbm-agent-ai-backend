package com.pbm.price.service;

import com.pbm.price.common.PriceCurrencyConverter;
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
import com.pbm.price.dto.event.PriceValidationResultEvent;
import com.pbm.price.dto.event.PriceValidationResultEventPayload;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.publisher.PaymentRequestEventPublisher;
import com.pbm.price.publisher.PriceAlertEventPublisher;
import com.pbm.price.publisher.PriceValidationResultEventPublisher;
import com.pbm.price.repository.MonitoringSubscriptionRepository;
import com.pbm.price.repository.MonitorTargetRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
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
 *      5. 상품을 찾으면 현재가를 KRW 기준으로 정규화한 뒤 목표 가격과 비교
 *         (USD → KRW 변환: 한국수출입은행 실시간 환율 사용)
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
     * 플랫폼별 상품 정보를 정규화된 형태로 전달하기 위한 보조 타입.
     *
     * @param found        상품을 찾았는지 여부
     * @param productId    상품 식별자
     * @param productUrl   상품 상세 페이지 URL
     * @param title        상품명
     * @param currentPrice 현재 가격
     * @param currency     통화 구분
     */
    public record NormalizedProductSnapshot(
            boolean found,
            String productId,
            String productUrl,
            String title,
            BigDecimal currentPrice,
            CurrencyType currency
    ) {
    }

    private final MonitoringSubscriptionRepository monitoringSubscriptionRepository;
    private final MonitorTargetRepository monitorTargetRepository;
    private final PriceAlertEventPublisher priceAlertEventPublisher;
    private final PaymentRequestEventPublisher paymentRequestEventPublisher;
    private final PriceValidationResultEventPublisher priceValidationResultEventPublisher;
    private final ExternalApiClient externalApiClient;
    private final PriceCurrencyConverter priceCurrencyConverter;

    public SubscriptionMonitoringService(
            MonitoringSubscriptionRepository monitoringSubscriptionRepository,
            MonitorTargetRepository monitorTargetRepository,
            PriceAlertEventPublisher priceAlertEventPublisher,
            PaymentRequestEventPublisher paymentRequestEventPublisher,
            PriceValidationResultEventPublisher priceValidationResultEventPublisher,
            ExternalApiClient externalApiClient,
            PriceCurrencyConverter priceCurrencyConverter
    ) {
        this.monitoringSubscriptionRepository = monitoringSubscriptionRepository;
        this.monitorTargetRepository = monitorTargetRepository;
        this.priceAlertEventPublisher = priceAlertEventPublisher;
        this.paymentRequestEventPublisher = paymentRequestEventPublisher;
        this.priceValidationResultEventPublisher = priceValidationResultEventPublisher;
        this.externalApiClient = externalApiClient;
        this.priceCurrencyConverter = priceCurrencyConverter;
    }

    /**
     * 구독 1건을 재조회하여 상태를 갱신하고 필요 시 알림/결제 이벤트를 발행한다.
     * <p>
     * 상태 전이 다이어그램:
     * <pre>
     * ACTIVE + found=false → markMiss → (missCount >= 2 ? FAILED : ACTIVE)
     * ACTIVE + found=true + currency=USD → 한국수출입은행 실시간 환율로 KRW 환산 후 비교
     * ACTIVE + found=true + normalizedPrice <= targetPrice → TRIGGERED + 이벤트 발행
     * ACTIVE + found=true + normalizedPrice > targetPrice → ACTIVE (유지, nextCheckAt 갱신)
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

        NormalizedProductSnapshot snapshot = refreshSubscription(subscription);

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
                monitoringSubscriptionRepository.save(subscription);
                deactivateMonitorTargetIfOrphaned(subscription.getPlatform(), subscription.getProductId());
                return;
            }

            monitoringSubscriptionRepository.save(subscription);
            return;
        }

        // ──────────────────────────────────────────────
        // 2) 재조회 가격을 KRW 기준 금액으로 정규화
        // ──────────────────────────────────────────────
        BigDecimal currentPriceInKrw = priceCurrencyConverter.toKrw(snapshot.currentPrice(), snapshot.currency());
        if (currentPriceInKrw == null) {
            subscription.changeStatus(MonitoringSubscriptionStatus.FAILED);
            log.warn("가격 또는 통화 정보가 없어 FAILED 처리 - subscriptionId: {}, currency: {}, currentPrice: {}",
                    subscriptionId, snapshot.currency(), snapshot.currentPrice());
            monitoringSubscriptionRepository.save(subscription);
            deactivateMonitorTargetIfOrphaned(subscription.getPlatform(), subscription.getProductId());
            return;
        }

        // ──────────────────────────────────────────────
        // 3) KRW 기준 환산 금액으로 목표 가격과 비교
        // ──────────────────────────────────────────────
        int comparison = currentPriceInKrw.compareTo(subscription.getTargetPrice());
        log.info("가격 비교 결과 - subscriptionId: {}, rawPrice: {} {}, convertedPriceKrw: {}, targetPrice: {}, 비교값: {}",
                subscriptionId,
                snapshot.currentPrice(),
                snapshot.currency(),
                currentPriceInKrw,
                subscription.getTargetPrice(),
                comparison);

        if (comparison <= 0) {
            // 목표 가격 이하 → TRIGGERED 상태로 전환
            subscription.changeStatus(MonitoringSubscriptionStatus.TRIGGERED);
            subscription.markSuccess(now);
            monitoringSubscriptionRepository.save(subscription);

            log.info("목표 가격 충족! TRIGGERED 전환 및 이벤트 발행 - subscriptionId: {}, convertedPriceKrw: {}, targetPrice: {}",
                    subscriptionId, currentPriceInKrw, subscription.getTargetPrice());

            deactivateMonitorTargetIfOrphaned(subscription.getPlatform(), subscription.getProductId());
            publishTriggeredEvents(subscription, snapshot, currentPriceInKrw);
        } else {
            // 목표 가격 초과 → ACTIVE 유지
            subscription.markSuccess(now);
            monitoringSubscriptionRepository.save(subscription);

            log.info("목표 가격 미충족, ACTIVE 유지 - subscriptionId: {}, convertedPriceKrw: {}, targetPrice: {}",
                    subscriptionId, currentPriceInKrw, subscription.getTargetPrice());
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
    NormalizedProductSnapshot refreshSubscription(MonitoringSubscription subscription) {
        return switch (subscription.getPlatform()) {
            case NAVER -> refreshNaver(subscription);
            case ALIEXPRESS -> refreshAliExpress(subscription);
            // URL 타입은 익스텐션이 직접 가격을 수집하므로 서버 재조회가 없다.
            // 스케줄러에서 이미 필터링되지만 안전을 위해 found=false 반환
            case URL -> new NormalizedProductSnapshot(false, null, null, null, null, null);
        };
    }

    /**
     * 선택된 후보 상품 1건을 즉시 단건 재조회한다.
     *
     * @param candidate 선택한 후보 상품 DTO
     * @return 재조회 결과 스냅샷
     */
    public NormalizedProductSnapshot refreshSelectedProduct(ProductCandidateDto candidate) {
        MonitoringSubscription temporarySubscription = MonitoringSubscription.create(
                0L,
                UUID.randomUUID().toString(),
                Platform.valueOf(candidate.platform()),
                candidate.productId(),
                candidate.productUrl(),
                candidate.title(),
                BigDecimal.ZERO,
                candidate.imageUrl(),
                candidate.searchKeyword(),
                BigDecimal.ZERO,
                CurrencyType.valueOf(candidate.currency()),
                "PRICE_CHECK",
                MonitoringSubscriptionStatus.ACTIVE,
                0,
                5,
                null  // 임시 단건 조회용이므로 종료 예정 시각 없음
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
    private NormalizedProductSnapshot refreshNaver(MonitoringSubscription subscription) {
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
            return new NormalizedProductSnapshot(
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
            return new NormalizedProductSnapshot(
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
            return new NormalizedProductSnapshot(
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
     * NormalizedProductSnapshot: 플랫폼별 응답을 공통 정규화된 형식으로 표준화하는 역할
     */
    private NormalizedProductSnapshot refreshAliExpress(MonitoringSubscription subscription) {
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
            log.warn("AliExpress detail 재조회 실패, search fallback 시도 - subscriptionId: {}, productId: {}",
                    subscription.getId(), subscription.getProductId());
            return refreshAliExpressBySearchFallback(subscription);
        }

        return toAliExpressSnapshot(product, subscription);
    }

    /**
     * subscription.getSearchKeyword()로 search 호출
     * 결과 목록에서 subscription.getProductId()와 같은 상품 찾기
     * 있으면 price/currency 파싱해서 snapshot 반환
     * 없으면 found = false
     */
    private NormalizedProductSnapshot refreshAliExpressBySearchFallback(MonitoringSubscription subscription){
        List<AliExpressShoppingItem> items = externalApiClient.searchAliExpressProductItems(
                subscription.getSearchKeyword(),
                1,
                30,
                null,
                "KRW",
                "KO",
                "KR",
                null,
                null
        );

        AliExpressShoppingItem matched = matchAliExpressItem(
                items,
                subscription.getProductId(),
                subscription.getProductUrl()
        );

        if (matched == null){
            return new NormalizedProductSnapshot(
                    false,
                    subscription.getProductId(),
                    subscription.getProductUrl(),
                    subscription.getSnapshotTitle(),
                    BigDecimal.ZERO,
                    subscription.getCurrency()
            );
        }

        log.info("AliExpress search fallback 매칭 성공 - subscriptionId: {}, productId: {}, title: {}",
                subscription.getId(), matched.product_id(), matched.product_title());

        return toAliExpressSnapshot(matched, subscription);
    }

    private AliExpressShoppingItem matchAliExpressItem(
            List<AliExpressShoppingItem> items,
            String targetProductId,
            String targetProductUrl
    ){
        if (items == null || items.isEmpty()){
            return null;
        }

        if(targetProductId != null && !targetProductId.isBlank()){
            for (AliExpressShoppingItem item : items){
                if (targetProductId.equals(item.product_id())){
                    return item;
                }
            }
        }

        if (targetProductUrl != null && !targetProductUrl.isBlank()){
            for (AliExpressShoppingItem item : items){
                if (targetProductUrl.equals(item.product_detail_url())) {
                    return item;
                }
            }
        }

        if (targetProductId != null && !targetProductId.isBlank()){
            String productIdToken = "/item/" + targetProductId + ".html";
            for (AliExpressShoppingItem item : items){
                if (item.product_detail_url() != null && item.product_detail_url().contains(productIdToken)){
                    return item;
                }
            }
        }

        return null;
    }

    /**
     * ALiExpress 상품 응답을 공통 NormalizedProductSnapshot으로 변환한다.
     *
     * target_sale_price 있으면 KRW
     * 위에가 없고 sale_price가 있으면 USD
     * 둘 다 없으면 found = false
     */
    private NormalizedProductSnapshot toAliExpressSnapshot(
            AliExpressShoppingItem product,
            MonitoringSubscription subscription
    ) {
        String rawPrice;
        CurrencyType currency;

        if (product.target_sale_price() != null && !product.target_sale_price().isBlank()){
            rawPrice = product.target_sale_price();
            currency = CurrencyType.KRW;
        } else if (product.sale_price() != null && !product.sale_price().isBlank()){
            rawPrice = product.sale_price();
            currency = CurrencyType.USD;
        } else {
            return new NormalizedProductSnapshot(
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
            return new NormalizedProductSnapshot(
                    true,
                    product.product_id(),
                    product.product_detail_url(),
                    product.product_title(),
                    currentPrice,
                    currency
            );
        } catch (NumberFormatException e){
            log.warn("AliExpress 가격 파싱 실패 - productId: {}, rawPrice: {}",
                    product.product_id(), rawPrice);
            return new NormalizedProductSnapshot(
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
     * @param snapshot           재조회 결과 스냅샷
     * @param currentPriceInKrw  KRW 기준 비교/이벤트 발행 금액
     */
    private void publishTriggeredEvents(
            MonitoringSubscription subscription,
            NormalizedProductSnapshot snapshot,
            BigDecimal currentPriceInKrw
    ) {
        Instant now = Instant.now();

        // price-alert 이벤트 발행 (알림용)
        // intent에 따라 eventType을 다르게 설정:
        //   - "AUTO_PURCHASE" → "AUTO_PAYMENT_START" (결제 파이프라인 트리거)
        //   - 그 외            → "PRICE_CONDITION_MET" (일반 조건 충족 알림)
        String intent = subscription.getIntent();
        String eventType = "AUTO_PURCHASE".equals(intent) ? "AUTO_PAYMENT_START" : "PRICE_CONDITION_MET";

        PriceAlertEventPayload alertPayload = new PriceAlertEventPayload(
                subscription.getUserId(),
                snapshot.title(),
                currentPriceInKrw.intValue(),
                subscription.getTargetPrice().intValue(),
                snapshot.productUrl(),
                subscription.getSearchKeyword(),
                intent
        );
        PriceAlertEvent alertEvent = new PriceAlertEvent(
                UUID.randomUUID().toString(),
                eventType,
                now,
                "price-service",
                alertPayload
        );
        priceAlertEventPublisher.publish(alertEvent);
        log.info("price-alert 이벤트 발행 완료 - subscriptionId: {}, eventId: {}, eventType: {}, intent: {}",
                subscription.getId(), alertEvent.eventId(), eventType, intent);

        if (!"AUTO_PURCHASE".equals(intent)) {
            return;
        }

        // price-validation-result 이벤트 발행 (command-service → AgentRun 생성용)
        // 모니터링 트리거 시점의 실제 가격(triggerPrice)을 함께 전달해야
        // CATALOG_NAVIGATOR가 올바른 기준가로 판매처를 탐색할 수 있다.
        // aiAgentPrivateKey: command-service가 결제 이벤트 발행 시 사용
        ProductCandidateDto triggeredProduct = new ProductCandidateDto(
                subscription.getProductId(),
                snapshot.title(),
                currentPriceInKrw.toPlainString(),  // 트리거 시점의 실제 가격으로 업데이트
                null,                                // mallName: MonitoringSubscription에 미저장
                snapshot.productUrl(),
                subscription.getSnapshotImageUrl(),
                CurrencyType.KRW.name(),
                subscription.getPlatform().name(),
                subscription.getSearchKeyword()
        );
        PriceValidationResultEventPayload browserPurchasePayload = new PriceValidationResultEventPayload(
                subscription.getId(),
                subscription.getCommandId(),
                "BROWSER_PURCHASE_IN_PROGRESS",
                List.of(triggeredProduct),
                List.of(),
                subscription.getProductId(),
                "모니터링 조건 충족 - 브라우저 자동 구매 시작",
                false,
                List.of(),
                null,
                currentPriceInKrw.intValue(),  // triggerPrice: 모니터링 트리거 시점의 실제 KRW 가격
                subscription.getAiAgentPrivateKey()  // AI 에이전트 개인키 (결제 이벤트 발행용)
        );
        PriceValidationResultEvent browserPurchaseEvent = new PriceValidationResultEvent(
                UUID.randomUUID().toString(),
                "MONITORING_TRIGGERED_BROWSER_PURCHASE",
                now,
                "price-service",
                browserPurchasePayload
        );
        priceValidationResultEventPublisher.publish(browserPurchaseEvent);
        log.info("모니터링 트리거 브라우저 구매 이벤트 발행 완료 - subscriptionId: {}, commandId: {}, triggerPrice: {}",
                subscription.getId(), subscription.getCommandId(), currentPriceInKrw.intValue());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PriceMonitoringScheduler 연동: 가격 수집 후 인라인 조건 평가
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 수집된 가격으로 해당 상품의 모든 ACTIVE 구독 조건을 평가한다.
     * PriceMonitoringScheduler가 API로 가격을 수집한 직후 호출한다.
     * 기존 process()와 달리 API를 다시 호출하지 않고, 이미 수집된 가격으로 바로 비교한다.
     *
     * @param platform     플랫폼 구분
     * @param productId    플랫폼 내 상품 식별자
     * @param currentPrice 수집된 현재 가격 (원본 통화)
     * @param currency     수집된 가격의 통화
     * @param title        상품명 (이벤트 발행용)
     * @param productUrl   상품 URL (이벤트 발행용)
     */
    public void evaluateSubscriptionsForTarget(Platform platform, String productId,
                                                BigDecimal currentPrice, CurrencyType currency,
                                                String title, String productUrl) {
        List<MonitoringSubscription> activeSubs = monitoringSubscriptionRepository
                .findByPlatformAndProductIdAndStatus(platform, productId, MonitoringSubscriptionStatus.ACTIVE);

        if (activeSubs.isEmpty()) {
            return;
        }

        BigDecimal priceInKrw = priceCurrencyConverter.toKrw(currentPrice, currency);
        if (priceInKrw == null) {
            log.warn("가격 환산 실패 - platform: {}, productId: {}, price: {} {}", platform, productId, currentPrice, currency);
            return;
        }

        Instant now = Instant.now();

        for (MonitoringSubscription sub : activeSubs) {
            try {
                // 수집 성공 → 연속 실패 카운트 초기화
                sub.resetMissCount();

                if (sub.getTargetPrice() != null && priceInKrw.compareTo(sub.getTargetPrice()) <= 0) {
                    // 목표 가격 충족 → TRIGGERED
                    sub.changeStatus(MonitoringSubscriptionStatus.TRIGGERED);
                    monitoringSubscriptionRepository.save(sub);

                    log.info("인라인 조건 평가 - 목표가 충족! subscriptionId: {}, priceKrw: {}, targetPrice: {}",
                            sub.getId(), priceInKrw, sub.getTargetPrice());

                    NormalizedProductSnapshot snapshot = new NormalizedProductSnapshot(
                            true, productId, productUrl, title, currentPrice, currency
                    );
                    publishTriggeredEvents(sub, snapshot, priceInKrw);
                } else {
                    // 목표 가격 미충족 → ACTIVE 유지
                    monitoringSubscriptionRepository.save(sub);
                    log.debug("인라인 조건 평가 - 미충족. subscriptionId: {}, priceKrw: {}, targetPrice: {}",
                            sub.getId(), priceInKrw, sub.getTargetPrice());
                }
            } catch (Exception e) {
                log.error("인라인 조건 평가 실패 - subscriptionId: {}", sub.getId(), e);
            }
        }

        // 트리거된 구독으로 인해 ACTIVE가 0건이면 MonitorTarget 비활성화
        deactivateMonitorTargetIfOrphaned(platform, productId);
    }

    /**
     * 상품 수집 실패 시 해당 상품의 모든 ACTIVE 구독에 miss를 기록한다.
     * PriceMonitoringScheduler가 API 매칭 실패 시 호출한다.
     *
     * @param platform  플랫폼 구분
     * @param productId 플랫폼 내 상품 식별자
     */
    public void handleCollectionMiss(Platform platform, String productId) {
        List<MonitoringSubscription> activeSubs = monitoringSubscriptionRepository
                .findByPlatformAndProductIdAndStatus(platform, productId, MonitoringSubscriptionStatus.ACTIVE);

        if (activeSubs.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        for (MonitoringSubscription sub : activeSubs) {
            sub.markMiss(now);
            log.warn("수집 miss 기록 - subscriptionId: {}, missCount: {}", sub.getId(), sub.getConsecutiveMissCount());

            if (sub.getConsecutiveMissCount() >= MAX_CONSECUTIVE_MISS_COUNT) {
                sub.changeStatus(MonitoringSubscriptionStatus.FAILED);
                log.warn("연속 miss 임계치 초과 FAILED 처리 - subscriptionId: {}", sub.getId());
            }
            monitoringSubscriptionRepository.save(sub);
        }

        deactivateMonitorTargetIfOrphaned(platform, productId);
    }

    /**
     * 해당 상품(platform + productId)의 ACTIVE 구독이 0건이면 MonitorTarget 폴링을 비활성화한다.
     * 여러 사용자가 같은 상품을 모니터링하는 경우 마지막 구독이 종료될 때만 비활성화된다.
     *
     * @param platform  플랫폼
     * @param productId 플랫폼 내 상품 식별자
     */
    private void deactivateMonitorTargetIfOrphaned(Platform platform, String productId) {
        long activeCount = monitoringSubscriptionRepository
                .countByPlatformAndProductIdAndStatus(platform, productId, MonitoringSubscriptionStatus.ACTIVE);

        if (activeCount == 0) {
            monitorTargetRepository.findByPlatformAndProductId(platform, productId)
                    .ifPresent(target -> {
                        target.deactivate();
                        monitorTargetRepository.save(target);
                        log.info("MonitorTarget 비활성화 완료 - platform: {}, productId: {} (잔여 ACTIVE 구독 없음)",
                                platform, productId);
                    });
        } else {
            log.debug("MonitorTarget 유지 - platform: {}, productId: {}, 잔여 ACTIVE 구독: {}건",
                    platform, productId, activeCount);
        }
    }
}
