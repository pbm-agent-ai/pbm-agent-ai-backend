package com.pbm.price.service;

import com.pbm.price.common.PriceCurrencyConverter;
import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.event.PriceValidationResultEvent;
import com.pbm.price.dto.event.PriceValidationResultEventPayload;
import com.pbm.price.dto.request.UrlPriceReportRequest;
import com.pbm.price.dto.response.UrlActiveTaskResponse;
import com.pbm.price.exception.SubscriptionNotFoundException;
import com.pbm.price.publisher.PriceValidationResultEventPublisher;
import com.pbm.price.repository.MonitorTargetRepository;
import com.pbm.price.repository.MonitoringSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * URL 기반 모니터링 가격 보고 처리 서비스.
 *
 * 역할: 익스텐션이 URL 페이지에서 추출한 가격을 받아 구독의 목표 가격과 비교하고,
 *       조건 충족 시 기존 구매/알림 흐름과 동일하게 PriceValidationResultEvent를 발행한다.
 *       ANY 조건인 경우 같은 commandId 그룹의 나머지 구독을 COMPLETED로 처리한다.
 *
 * 변경 이력:
 *   - subscription.next_check_at 기반 스케줄링에서 monitor_targets.next_fetch_at 기반으로 전환
 *   - 가격 보고 시 price_history에 시계열 이력 저장 추가
 *
 * 연관: UrlMonitoringController, MonitoringSubscriptionRepository,
 *       MonitorTargetRepository, ProductPersistenceService,
 *       PriceValidationResultEventPublisher
 */
@Slf4j
@Service
@Transactional
public class UrlMonitoringService {

    @Value("${app.monitoring.default-fetch-interval-minutes:10}")
    private int defaultFetchIntervalMinutes;

    private final MonitoringSubscriptionRepository monitoringSubscriptionRepository;
    private final MonitorTargetRepository monitorTargetRepository;
    private final PriceValidationResultEventPublisher priceValidationResultEventPublisher;
    private final PriceCurrencyConverter priceCurrencyConverter;
    private final ProductPersistenceService productPersistenceService;
    public UrlMonitoringService(
            MonitoringSubscriptionRepository monitoringSubscriptionRepository,
            MonitorTargetRepository monitorTargetRepository,
            PriceValidationResultEventPublisher priceValidationResultEventPublisher,
            PriceCurrencyConverter priceCurrencyConverter,
            ProductPersistenceService productPersistenceService
    ) {
        this.monitoringSubscriptionRepository = monitoringSubscriptionRepository;
        this.monitorTargetRepository = monitorTargetRepository;
        this.priceValidationResultEventPublisher = priceValidationResultEventPublisher;
        this.priceCurrencyConverter = priceCurrencyConverter;
        this.productPersistenceService = productPersistenceService;
    }

    /**
     * URL 모니터링 구독을 생성한다.
     * price-topic의 URL_MONITOR_REQUEST 이벤트를 소비하는 consumer가 호출한다.
     * 구독 생성 시 MonitorTarget도 함께 생성/활성화하여 공유 스케줄링 기반으로 통합한다.
     *
     * @param userId        사용자 ID
     * @param commandId     명령 세션 ID
     * @param productUrl    모니터링 대상 URL
     * @param targetPrice   목표 가격
     * @param currency      통화
     * @param urlCondition  "ALL" | "ANY"
     * @param intent        사용자 의도
     * @return 생성된 MonitoringSubscription
     */
    public MonitoringSubscription createSubscription(
            Long userId,
            String commandId,
            String productUrl,
            Integer targetPrice,
            String currency,
            String urlCondition,
            String intent
    ) {
        final CurrencyType currencyType = parseCurrencyType(currency);

        // 동일 사용자 + URL 기준 기존 구독 확인
        String productId = MonitoringSubscription.hashUrl(productUrl);
        MonitoringSubscription subscription = monitoringSubscriptionRepository
                .findByUserIdAndPlatformAndProductId(userId, Platform.URL, productId)
                .map(existing -> {
                    // 기존 구독 갱신
                    existing.updateSelectionSnapshot(
                            commandId, productUrl, null,
                            targetPrice != null ? BigDecimal.valueOf(targetPrice) : null,
                            null, null,
                            targetPrice != null ? BigDecimal.valueOf(targetPrice) : null,
                            intent, currencyType
                    );
                    existing.changeStatus(MonitoringSubscriptionStatus.ACTIVE);
                    existing.resetMissCount();
                    log.info("URL 모니터링 구독 갱신 - subscriptionId: {}, url: {}", existing.getId(), productUrl);
                    return monitoringSubscriptionRepository.save(existing);
                })
                .orElseGet(() -> {
                    // 신규 구독 생성
                    MonitoringSubscription sub = MonitoringSubscription.createUrl(
                            userId, commandId, productUrl,
                            targetPrice != null ? BigDecimal.valueOf(targetPrice) : null,
                            currencyType, intent,
                            MonitoringSubscriptionStatus.ACTIVE,
                            defaultFetchIntervalMinutes,
                            Instant.now().plus(7, ChronoUnit.DAYS),
                            urlCondition
                    );
                    MonitoringSubscription saved = monitoringSubscriptionRepository.save(sub);
                    log.info("URL 모니터링 구독 생성 - subscriptionId: {}, url: {}, condition: {}",
                            saved.getId(), productUrl, urlCondition);
                    return saved;
                });

        // MonitorTarget 생성/활성화 (공유 스케줄링 기반 통합)
        ensureMonitorTarget(productId, productUrl);

        return subscription;
    }

    /**
     * 익스텐션이 보고한 가격을 처리한다.
     *
     * 동작:
     * 1. subscriptionId로 구독 조회
     * 2. 현재가를 KRW 기준으로 환산
     * 3. MonitorTarget에 수집 완료 시각 갱신 (공유 스케줄링)
     * 4. price_history에 가격 이력 저장
     * 5. 상품명/이미지 스냅샷 저장 (최초 크롤링 시)
     * 6. 목표가 충족 여부 확인
     * 7. 충족 시 → PriceValidationResultEvent 발행 + 구독 TRIGGERED 처리
     *           → ANY 조건이면 같은 commandId 그룹 전체 COMPLETED 처리
     * 8. 미충족 시 → 구독 성공 처리 (연속 실패 카운트 초기화)
     *
     * @param request 가격 보고 요청
     */
    public void processReport(UrlPriceReportRequest request) {
        MonitoringSubscription sub = monitoringSubscriptionRepository
                .findById(request.subscriptionId())
                .orElseThrow(() -> new SubscriptionNotFoundException(request.subscriptionId()));

        if (sub.getStatus() != MonitoringSubscriptionStatus.ACTIVE) {
            log.info("URL 모니터링 구독이 ACTIVE 상태가 아니라 보고를 무시합니다. subscriptionId: {}, status: {}",
                    sub.getId(), sub.getStatus());
            return;
        }

        // 현재가를 KRW 기준으로 환산
        CurrencyType reportCurrency = parseCurrencyType(request.currency());
        BigDecimal currentPriceKrw = priceCurrencyConverter.toKrw(request.currentPrice(), reportCurrency);

        Instant now = Instant.now();

        // 구독 성공 처리 (연속 실패 카운트 초기화 + lastCheckedAt 갱신)
        sub.markSuccess(now);

        // MonitorTarget 수집 완료 시각 갱신 (next_fetch_at 재설정 → 공유 스케줄링)
        monitorTargetRepository.findByPlatformAndProductId(Platform.URL, sub.getProductId())
                .ifPresent(target -> {
                    target.markFetched(now);
                    monitorTargetRepository.save(target);

                    // price_history에 시계열 이력 저장
                    productPersistenceService.saveUrlPriceReport(target, currentPriceKrw, now);
                });

        // 최초 보고 시에만 snapshot_price 저장 (등록 시점 기준가 보존)
        sub.updateSnapshotPrice(currentPriceKrw);

        // 최초 크롤링 시 상품명/이미지 스냅샷 저장 (null인 경우에만 반영)
        if (request.productName() != null || request.imageUrl() != null) {
            sub.updateSnapshotFromUrl(request.productName(), request.imageUrl());
            log.debug("URL 스냅샷 갱신 - subscriptionId: {}, productName: {}, imageUrl: {}",
                    sub.getId(), request.productName(), request.imageUrl());
        }

        log.info("URL 가격 보고 처리 - subscriptionId: {}, url: {}, currentPrice: {} KRW, targetPrice: {}",
                sub.getId(), sub.getProductUrl(), currentPriceKrw, sub.getTargetPrice());

        // 목표가 충족 여부 확인
        boolean conditionMet = sub.getTargetPrice() != null
                && currentPriceKrw != null
                && currentPriceKrw.compareTo(sub.getTargetPrice()) <= 0;

        if (!conditionMet) {
            log.info("URL 가격 미충족 - subscriptionId: {}, currentPrice: {}, targetPrice: {}",
                    sub.getId(), currentPriceKrw, sub.getTargetPrice());
            return;
        }

        // 조건 충족 → TRIGGERED 처리
        log.info("URL 가격 조건 충족! - subscriptionId: {}, url: {}, price: {} KRW",
                sub.getId(), sub.getProductUrl(), currentPriceKrw);
        sub.changeStatus(MonitoringSubscriptionStatus.TRIGGERED);

        // ANY 조건이면 같은 commandId 그룹 나머지 구독도 COMPLETED 처리
        if ("ANY".equalsIgnoreCase(sub.getUrlCondition())) {
            List<MonitoringSubscription> group = monitoringSubscriptionRepository
                    .findAllByCommandId(sub.getCommandId());
            group.stream()
                    .filter(other -> !other.getId().equals(sub.getId()))
                    .filter(other -> other.getStatus() == MonitoringSubscriptionStatus.ACTIVE)
                    .forEach(other -> {
                        other.changeStatus(MonitoringSubscriptionStatus.COMPLETED);
                        log.info("ANY 조건 - 나머지 URL 구독 COMPLETED 처리. subscriptionId: {}", other.getId());
                    });
        }

        // MonitorTarget 비활성화 (ACTIVE 구독 0건이면 더 이상 크롤링 불필요)
        deactivateMonitorTargetIfOrphaned(sub.getProductId());

        // 기존 PriceValidationResult 흐름으로 연결 (AgentRun 생성 → 구매 진행)
        publishTriggerEvent(sub, currentPriceKrw);
    }

    /**
     * 크롤링 기한이 도래한 URL 모니터링 구독 목록을 반환한다.
     * monitor_targets.next_fetch_at 기준으로 스케줄링을 판단한다.
     *
     * @param userId 사용자 ID (heartbeat 디바이스 소유자)
     * @return 크롤링 대상 URL 태스크 응답 목록
     */
    @Transactional(readOnly = true)
    public List<UrlActiveTaskResponse> getActiveUrlTasks(Long userId) {
        // 1. 사용자의 ACTIVE URL 구독 전체 조회
        List<MonitoringSubscription> activeSubs =
                monitoringSubscriptionRepository.findActiveUrlSubscriptionsByUserId(userId);

        if (activeSubs.isEmpty()) {
            return List.of();
        }

        // 2. 해당 productId들의 monitor_target 중 next_fetch_at이 도래한 것 필터
        List<String> productIds = activeSubs.stream()
                .map(MonitoringSubscription::getProductId)
                .distinct()
                .toList();

        Instant now = Instant.now();
        Set<String> dueProductIds = monitorTargetRepository
                .findDueUrlTargetsByProductIds(productIds, now)
                .stream()
                .map(MonitorTarget::getProductId)
                .collect(Collectors.toSet());

        // 3. 크롤링 기한이 도래한 구독만 응답
        return activeSubs.stream()
                .filter(sub -> dueProductIds.contains(sub.getProductId()))
                .map(sub -> new UrlActiveTaskResponse(
                        sub.getId(),
                        sub.getProductUrl(),
                        sub.getTargetPrice() != null ? sub.getTargetPrice().intValue() : null,
                        sub.getCurrency().name(),
                        sub.getUrlCondition() != null ? sub.getUrlCondition() : "ALL",
                        sub.getIntent(),
                        sub.getCommandId()
                ))
                .toList();
    }

    /**
     * URL 구독에 대응하는 MonitorTarget을 생성하거나 활성화한다.
     * PLATFORM 타입과 동일하게 monitor_targets 기반 공유 스케줄링을 사용한다.
     *
     * @param productId  URL의 MD5 해시 (구독의 productId와 동일)
     * @param productUrl 원본 URL
     */
    private void ensureMonitorTarget(String productId, String productUrl) {
        MonitorTarget target = monitorTargetRepository
                .findByPlatformAndProductId(Platform.URL, productId)
                .orElseGet(() -> {
                    MonitorTarget newTarget = MonitorTarget.create(
                            Platform.URL,
                            productId,
                            productUrl,  // searchKeyword 대신 URL 자체를 저장
                            productUrl,
                            defaultFetchIntervalMinutes
                    );
                    log.info("URL MonitorTarget 생성 - productId: {}, url: {}", productId, productUrl);
                    return newTarget;
                });

        // nextFetchAt을 null로 설정하여 첫 heartbeat에서 즉시 크롤링 대상에 포함
        // (이미 활성화 상태라면 기존 스케줄 유지)
        if (target.getNextFetchAt() == null) {
            // nextFetchAt이 null이면 findDueUrlTargetsByProductIds에서 조건 (nextFetchAt IS NULL) 충족
            log.debug("URL MonitorTarget 초기 활성화 - productId: {}", productId);
        }

        monitorTargetRepository.save(target);
    }

    /**
     * 해당 URL의 ACTIVE 구독이 0건이면 MonitorTarget 폴링을 비활성화한다.
     *
     * @param productId URL의 MD5 해시
     */
    private void deactivateMonitorTargetIfOrphaned(String productId) {
        long activeCount = monitoringSubscriptionRepository
                .countByPlatformAndProductIdAndStatus(Platform.URL, productId, MonitoringSubscriptionStatus.ACTIVE);

        if (activeCount == 0) {
            monitorTargetRepository.findByPlatformAndProductId(Platform.URL, productId)
                    .ifPresent(target -> {
                        target.deactivate();
                        monitorTargetRepository.save(target);
                        log.info("URL MonitorTarget 비활성화 - productId: {} (잔여 ACTIVE 구독 없음)", productId);
                    });
        }
    }

    private CurrencyType parseCurrencyType(String currency) {
        try {
            return CurrencyType.valueOf(currency != null ? currency : "KRW");
        } catch (IllegalArgumentException e) {
            return CurrencyType.KRW;
        }
    }

    /**
     * 조건 충족 시 PriceValidationResultEvent를 발행하여 기존 구매 흐름으로 연결한다.
     */
    private void publishTriggerEvent(MonitoringSubscription sub, BigDecimal triggerPrice) {
        String nextStatus = "AUTO_PURCHASE".equalsIgnoreCase(sub.getIntent())
                ? "BROWSER_PURCHASE_IN_PROGRESS"
                : "MONITORING_STARTED";

        // URL 구독의 실질 플랫폼 추론 (AliExpress URL → "ALIEXPRESS" 보정)
        String effectivePlatform = resolveEffectivePlatform(sub);

        com.pbm.price.dto.event.ProductCandidateDto candidateDto =
                new com.pbm.price.dto.event.ProductCandidateDto(
                        sub.getProductId(),
                        sub.getSnapshotTitle() != null ? sub.getSnapshotTitle() : sub.getProductUrl(),
                        triggerPrice != null ? triggerPrice.toPlainString() : null,
                        null,
                        sub.getProductUrl(),
                        sub.getSnapshotImageUrl(),
                        "KRW",
                        effectivePlatform,
                        null
                );

        PriceValidationResultEvent event = new PriceValidationResultEvent(
                UUID.randomUUID().toString(),
                "PRICE_VALIDATION_COMPLETED",
                Instant.now(),
                "price-service",
                new PriceValidationResultEventPayload(
                        sub.getId(),
                        sub.getCommandId(),
                        nextStatus,
                        List.of(candidateDto),  // triggeredProducts
                        List.of(),              // monitoringProducts
                        null,                   // purchasedProductId
                        "URL 모니터링 가격 조건 충족",
                        false,
                        List.of(),
                        null,
                        triggerPrice != null ? triggerPrice.intValue() : null,
                        sub.getAiAgentPrivateKey()  // AI 에이전트 개인키
                )
        );

        priceValidationResultEventPublisher.publish(event);
        log.info("URL 모니터링 트리거 이벤트 발행 - commandId: {}, subscriptionId: {}, intent: {}, " +
                        "nextStatus: {}, effectivePlatform: {}",
                sub.getCommandId(), sub.getId(), sub.getIntent(), nextStatus, effectivePlatform);
    }

    /**
     * URL 구독의 실질 플랫폼을 URL 패턴으로 추론한다.
     * <p>
     * AliExpress 상품이 URL 모니터링으로 전환된 경우, 구독의 platform은 URL이지만
     * 다운스트림(command-service)의 네비게이션과 PlatformConfig 매핑을 위해
     * 실제 플랫폼을 URL 패턴으로 판별하여 반환한다.
     *
     * @param sub 모니터링 구독
     * @return 실질 플랫폼 문자열 ("ALIEXPRESS", "URL" 등)
     */
    private String resolveEffectivePlatform(MonitoringSubscription sub) {
        if (sub.getPlatform() != Platform.URL) {
            return sub.getPlatform().name();
        }
        String url = sub.getProductUrl();
        if (url != null && url.contains("aliexpress.com")) {
            return "ALIEXPRESS";
        }
        return sub.getPlatform().name();
    }
}
