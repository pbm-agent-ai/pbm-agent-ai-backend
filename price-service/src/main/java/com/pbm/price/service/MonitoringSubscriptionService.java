package com.pbm.price.service;

import com.pbm.price.client.PaymentServiceClient;
import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.event.ProductCandidateDto;
import com.pbm.price.dto.event.SessionKeyRegistrationEvent;
import com.pbm.price.dto.event.SessionKeyRegistrationEventPayload;
import com.pbm.price.dto.event.SubscriptionTerminationEvent;
import com.pbm.price.dto.event.SubscriptionTerminationEventPayload;
import com.pbm.price.dto.request.MonitoringSubscriptionUpdateRequest;
import com.pbm.price.exception.SubscriptionAccessDeniedException;
import com.pbm.price.exception.SubscriptionNotFoundException;
import com.pbm.price.publisher.SessionKeyRegistrationEventPublisher;
import com.pbm.price.publisher.SubscriptionTerminationEventPublisher;
import com.pbm.price.repository.MonitorTargetRepository;
import com.pbm.price.repository.MonitoringSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 모니터링 구독 생성/갱신 서비스.
 *
 * 역할: 사용자가 선택한 후보 상품 정보를 ProductCandidateDto로 받아
 *       users_monitoring_subscriptions 레코드를 생성하거나 기존 구독을 갱신한다.
 * 동작:
 *   1. payload에서 platform/currency/가격을 파싱한다.
 *   2. intent가 AUTO_PURCHASE이면 지갑 한도를 사전 검증한다 (한도 초과 시 생성 차단).
 *   3. 동일 사용자 + 동일 플랫폼 + 동일 productId 구독이 있는지 조회한다.
 *   4. 기존 구독이 있으면 최신 선택 정보로 갱신한다.
 *   5. 기존 구독이 없으면 ACTIVE 상태의 새 구독을 생성한다.
 *   6. markChecked(now)로 초기 수집 시각과 다음 수집 예정 시각을 설정한다.
 * 연관: MonitoringSubscriptionRepository, MonitoringSubscription, ProductCandidateDto.
 */
@Service
@Transactional
public class MonitoringSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringSubscriptionService.class);

    private static final int DEFAULT_CHECK_INTERVAL_MINUTES = 10;
    /** 모니터링 기본 유지 기간 (7일) */
    private static final int DEFAULT_MONITORING_DURATION_DAYS = 7;

    /**
     * payload 파싱 결과를 한 번에 묶어 서비스 내부에서만 전달하기 위한 보조 타입.
     */
    private record ParsedSelectionContext(
            Platform platform,
            CurrencyType currency,
            BigDecimal snapshotPrice,
            BigDecimal targetPrice,
            Instant now,
            Instant resolvedEndAt
    ) {
    }

    private final MonitoringSubscriptionRepository monitoringSubscriptionRepository;
    private final MonitorTargetRepository monitorTargetRepository;
    private final SessionKeyRegistrationEventPublisher sessionKeyRegistrationEventPublisher;
    private final SubscriptionTerminationEventPublisher subscriptionTerminationEventPublisher;
    private final PaymentServiceClient paymentServiceClient;

    public MonitoringSubscriptionService(
            MonitoringSubscriptionRepository monitoringSubscriptionRepository,
            MonitorTargetRepository monitorTargetRepository,
            SessionKeyRegistrationEventPublisher sessionKeyRegistrationEventPublisher,
            SubscriptionTerminationEventPublisher subscriptionTerminationEventPublisher,
            PaymentServiceClient paymentServiceClient
    ) {
        this.monitoringSubscriptionRepository = monitoringSubscriptionRepository;
        this.monitorTargetRepository = monitorTargetRepository;
        this.sessionKeyRegistrationEventPublisher = sessionKeyRegistrationEventPublisher;
        this.subscriptionTerminationEventPublisher = subscriptionTerminationEventPublisher;
        this.paymentServiceClient = paymentServiceClient;
    }

    /**
     * 선택된 후보 상품 1건을 바탕으로 모니터링 구독을 생성하거나 기존 구독을 갱신한다.
     * <p>
     * 동작 흐름:
     * 1. payload에서 platform/currency/가격을 파싱
     * 2. 동일 사용자 + 플랫폼 + 상품 기준 기존 구독 조회
     * 3. 있으면 기존 구독 갱신
     * 4. 없으면 새 구독 생성
     * 5. 저장 후 엔티티 반환
     *
     * @param userId          사용자 ID
     * @param commandId       명령 세션 ID
     * @param targetPriceValue 목표 가격
     * @param intent          사용자 의도
     * @param selectedProduct 선택한 후보 상품 DTO
     * @return 생성 또는 갱신된 MonitoringSubscription
     */
    public MonitoringSubscription createOrUpdateFromSelection(
            Long userId,
            String commandId,
            Integer targetPriceValue,
            String intent,
            ProductCandidateDto selectedProduct
    ) {
        return createOrUpdateFromSelection(userId, commandId, targetPriceValue, intent, selectedProduct, null, false);
    }

    public MonitoringSubscription createOrUpdateFromSelection(
            Long userId,
            String commandId,
            Integer targetPriceValue,
            String intent,
            ProductCandidateDto selectedProduct,
            Instant scheduledEndAt
    ) {
        return createOrUpdateFromSelection(userId, commandId, targetPriceValue, intent, selectedProduct, scheduledEndAt, false);
    }

    /**
     * 모니터링 구독을 생성/갱신한다.
     *
     * @param immediateFullfillment true이면 즉시 충족 시나리오로, 세션키 Kafka 이벤트를 발행하지 않는다.
     *                              (ProductSelectionConsumer에서 동기 REST로 별도 등록)
     */
    public MonitoringSubscription createOrUpdateFromSelection(
            Long userId,
            String commandId,
            Integer targetPriceValue,
            String intent,
            ProductCandidateDto selectedProduct,
            Instant scheduledEndAt,
            boolean immediateFullfillment
    ) {
        ParsedSelectionContext context = parseSelectionContext(selectedProduct, targetPriceValue, scheduledEndAt);

        // AUTO_PURCHASE intent인 경우 구독 생성 전에 지갑 한도를 먼저 검증한다.
        // 이를 통해 한도 초과 시 구독이 ACTIVE 상태로 생성되는 것을 사전에 방지하고,
        // 나중에 결제 시점에서 "insufficient funds" 오류가 발생하는 문제를 차단한다.
        if ("AUTO_PURCHASE".equals(intent) && targetPriceValue != null) {
            validateWalletLimit(userId, targetPriceValue.longValue());
        }

        return monitoringSubscriptionRepository.findByUserIdAndPlatformAndProductId(
                        userId,
                        context.platform(),
                        selectedProduct.productId()
                )
                .map(existing -> updateExistingSubscription(existing, commandId, intent, selectedProduct, context, immediateFullfillment))
                .orElseGet(() -> createNewSubscription(userId, commandId, intent, selectedProduct, context, immediateFullfillment));
    }

    /** 허용되는 intent 값 목록 */
    private static final java.util.Set<String> ALLOWED_INTENTS =
            java.util.Set.of("AUTO_PURCHASE", "PRICE_TRACK");

    /**
     * 모니터링 구독의 조건을 부분 수정한다.
     * <p>
     * 동작:
     * 1. subscriptionId로 구독을 조회한다. 없으면 SubscriptionNotFoundException.
     * 2. 요청 userId와 구독 소유자가 다르면 SubscriptionAccessDeniedException.
     * 3. 요청의 세 필드가 모두 null이면 수정할 내용이 없으므로 IllegalArgumentException.
     * 4. null이 아닌 필드만 적용하여 저장한다.
     *
     * @param userId         요청 사용자 ID (JWT에서 추출)
     * @param subscriptionId 수정할 구독 ID
     * @param request        수정 요청 DTO (null 필드는 변경하지 않음)
     * @return 수정된 MonitoringSubscription 엔티티
     */
    @Transactional
    public MonitoringSubscription updateSubscription(
            Long userId,
            Long subscriptionId,
            MonitoringSubscriptionUpdateRequest request
    ) {
        // 1. 구독 존재 여부 확인
        MonitoringSubscription subscription = monitoringSubscriptionRepository
                .findById(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException(subscriptionId));

        // 2. 소유자 검증
        if (!subscription.getUserId().equals(userId)) {
            throw new SubscriptionAccessDeniedException(subscriptionId, userId);
        }

        // 3. 수정할 필드가 하나도 없으면 거부
        if (request.intent() == null && request.targetPrice() == null && request.scheduledEndAt() == null) {
            throw new IllegalArgumentException("수정할 항목이 없습니다. intent, targetPrice, scheduledEndAt 중 하나 이상을 입력해주세요.");
        }

        // 4. null이 아닌 필드만 선택적 적용
        if (request.intent() != null) {
            if (!ALLOWED_INTENTS.contains(request.intent())) {
                throw new IllegalArgumentException(
                        "허용되지 않는 intent 값입니다. 허용값: " + ALLOWED_INTENTS + ", 입력값: " + request.intent());
            }
            subscription.updateIntent(request.intent());
        }

        if (request.targetPrice() != null) {
            if (request.targetPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("목표 가격은 0보다 커야 합니다.");
            }
            subscription.updateTargetPrice(request.targetPrice());
        }

        if (request.scheduledEndAt() != null) {
            if (request.scheduledEndAt().isBefore(java.time.Instant.now())) {
                throw new IllegalArgumentException("모니터링 종료 예정 시각은 현재 시각 이후여야 합니다.");
            }
            subscription.updateScheduledEndAt(request.scheduledEndAt());
        }

        return monitoringSubscriptionRepository.save(subscription);
    }

    /**
     * 사용자가 직접 모니터링 구독을 취소한다.
     * <p>
     * 동작:
     * 1. subscriptionId로 구독을 조회한다. 없으면 SubscriptionNotFoundException.
     * 2. 요청 userId와 구독 소유자가 다르면 SubscriptionAccessDeniedException.
     * 3. 구독 상태를 CANCELLED로 변경하고 저장한다.
     *
     * @param userId         요청 사용자 ID (JWT에서 추출)
     * @param subscriptionId 취소할 구독 ID
     */
    @Transactional
    public void cancelSubscription(Long userId, Long subscriptionId) {
        MonitoringSubscription subscription = monitoringSubscriptionRepository
                .findById(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException(subscriptionId));

        if (!subscription.getUserId().equals(userId)) {
            throw new SubscriptionAccessDeniedException(subscriptionId, userId);
        }

        subscription.changeStatus(MonitoringSubscriptionStatus.CANCELLED);
        monitoringSubscriptionRepository.save(subscription);

        log.info("모니터링 구독 취소 완료 - subscriptionId: {}, userId: {}", subscriptionId, userId);

        // ACTIVE 구독이 0건이면 MonitorTarget 폴링 비활성화
        deactivateMonitorTargetIfOrphaned(subscription.getPlatform(), subscription.getProductId());

        subscriptionTerminationEventPublisher.publish(new SubscriptionTerminationEvent(
                java.util.UUID.randomUUID().toString(),
                "SUBSCRIPTION_TERMINATED",
                java.time.Instant.now(),
                "price-service",
                new SubscriptionTerminationEventPayload(subscriptionId, userId, "CANCELLED")
        ));
    }

    /**
     * 특정 사용자의 ACTIVE 상태 모니터링 구독 목록을 반환한다.
     * <p>
     * 동작: userId로 전체 구독을 조회한 뒤 ACTIVE 상태인 건만 필터링하여 반환한다.
     *
     * @param userId 사용자 식별자
     * @return ACTIVE 상태의 MonitoringSubscription 목록
     */
    @Transactional(readOnly = true)
    public List<MonitoringSubscription> findActiveByUserId(Long userId) {
        return monitoringSubscriptionRepository.findByUserId(userId).stream()
                .filter(sub -> sub.getStatus() == MonitoringSubscriptionStatus.ACTIVE)
                .toList();
    }

    /**
     * 선택된 후보 상품들 중 기존 구독이 이미 존재하는 상품 목록을 조회한다.
     * <p>
     * 역할: command-service가 사용자에게 "기존 모니터링을 갱신/재시작할지" 물어보기 전에
     *       어떤 상품이 중복 대상인지 판별할 수 있도록 한다.
     *
     * @param userId            사용자 ID
     * @param selectedProducts  사용자가 선택한 후보 상품 목록
     * @return 기존 구독이 존재하는 후보 상품 목록
     */
    @Transactional(readOnly = true)
    public List<ProductCandidateDto> findDuplicateSelections(Long userId, List<ProductCandidateDto> selectedProducts) {
        if (selectedProducts == null || selectedProducts.isEmpty()) {
            return List.of();
        }

        return selectedProducts.stream()
                .filter(selectedProduct -> monitoringSubscriptionRepository.findByUserIdAndPlatformAndProductId(
                        userId,
                        parsePlatform(selectedProduct.platform()),
                        selectedProduct.productId()
                ).isPresent())
                .toList();
    }

    /**
     * 기존 구독이 있는 경우 최신 선택 정보로 갱신한다.
     *
     * @param existing 기존 구독 엔티티
     * @param payload  선택 이벤트 payload
     * @param context  파싱된 보조 컨텍스트
     * @return 저장된 MonitoringSubscription
     */
    private MonitoringSubscription updateExistingSubscription(
            MonitoringSubscription existing,
            String commandId,
            String intent,
            ProductCandidateDto selectedProduct,
            ParsedSelectionContext context,
            boolean immediateFullfillment
    ) {
        existing.updateSelectionSnapshot(
                commandId,
                selectedProduct.productUrl(),
                selectedProduct.title(),
                context.snapshotPrice(),
                selectedProduct.imageUrl(),
                selectedProduct.searchKeyword(),
                context.targetPrice(),
                intent,
                context.currency()
        );
        existing.changeStatus(MonitoringSubscriptionStatus.ACTIVE);
        existing.resetMissCount();
        // 사용자 지정 마감일 또는 기본 7일 후로 리셋한다.
        existing.updateScheduledEndAt(context.resolvedEndAt());
        existing.markChecked(context.now());

        MonitoringSubscription saved = monitoringSubscriptionRepository.save(existing);

        // 공유 MonitorTarget 활성화/갱신
        activateMonitorTarget(
                context.platform(),
                selectedProduct.productId(),
                selectedProduct.searchKeyword(),
                selectedProduct.productUrl()
        );

        // AUTO_PURCHASE 재등록 시 기존 세션키가 만료/취소 상태일 수 있으므로 새 키페어를 발급한다.
        // immediateFullfillment=true: Kafka 이벤트 발행 생략 (동기 REST로 별도 등록)
        if ("AUTO_PURCHASE".equals(intent)) {
            registerSessionKey(saved, context, !immediateFullfillment);
        }

        return saved;
    }

    /**
     * 기존 구독이 없는 경우 새 구독을 생성한다.
     *
     * @param payload 선택 이벤트 payload
     * @param context 파싱된 보조 컨텍스트
     * @return 저장된 MonitoringSubscription
     */
    private MonitoringSubscription createNewSubscription(
            Long userId,
            String commandId,
            String intent,
            ProductCandidateDto selectedProduct,
            ParsedSelectionContext context,
            boolean immediateFullfillment
    ) {
        // 사용자 지정 마감일 또는 기본 7일 후
        Instant scheduledEndAt = context.resolvedEndAt();

        MonitoringSubscription subscription = MonitoringSubscription.create(
                userId,
                commandId,
                context.platform(),
                selectedProduct.productId(),
                selectedProduct.productUrl(),
                selectedProduct.title(),
                context.snapshotPrice(),
                selectedProduct.imageUrl(),
                selectedProduct.searchKeyword(),
                context.targetPrice(),
                context.currency(),
                intent,
                MonitoringSubscriptionStatus.ACTIVE,
                0,
                DEFAULT_CHECK_INTERVAL_MINUTES,
                scheduledEndAt
        );

        subscription.markChecked(context.now());

        MonitoringSubscription saved = monitoringSubscriptionRepository.save(subscription);

        // 공유 MonitorTarget 활성화/갱신
        activateMonitorTarget(
                context.platform(),
                selectedProduct.productId(),
                selectedProduct.searchKeyword(),
                selectedProduct.productUrl()
        );

        // AUTO_PURCHASE intent인 경우 AI 에이전트 키페어를 생성하고 세션키 등록 이벤트를 발행한다.
        // immediateFullfillment=true: Kafka 이벤트 발행 생략 (동기 REST로 별도 등록)
        if ("AUTO_PURCHASE".equals(intent)) {
            registerSessionKey(saved, context, !immediateFullfillment);
        }

        return saved;
    }

    /**
     * 모니터링 구독 생성/갱신 시 해당 상품의 MonitorTarget을 찾거나 생성하고 공유 폴링을 활성화한다.
     * <p>
     * 공유 폴링 대상은 (platform, productId) 기준으로 유일하며, 여러 사용자가 같은 상품을 구독해도
     * 하나의 MonitorTarget만 유지된다. 검색 결과 저장 시에는 nextFetchAt이 null로 생성되지만,
     * 이 메서드에서 markFetched를 호출하여 정기 수집이 시작된다.
     *
     * @param platform      플랫폼
     * @param productId     상품 ID
     * @param searchKeyword 검색 키워드
     * @param productUrl    상품 URL
     */
    private void activateMonitorTarget(Platform platform, String productId, String searchKeyword, String productUrl) {
        MonitorTarget target = monitorTargetRepository
                .findByPlatformAndProductId(platform, productId)
                .orElseGet(() -> MonitorTarget.create(platform, productId, searchKeyword, productUrl, DEFAULT_CHECK_INTERVAL_MINUTES));

        target.updateSearchContext(searchKeyword, productUrl);
        target.markFetched(Instant.now());
        monitorTargetRepository.save(target);

        log.debug("MonitorTarget 활성화 완료 - platform: {}, productId: {}", platform, productId);
    }

    /**
     * AUTO_PURCHASE 구독에 대한 AI 에이전트 키페어를 생성하고 DB에 저장한 뒤
     * payment-service로 세션키 등록 이벤트를 발행한다.
     * <p>
     * 키페어 생성: Web3j ECKeyPair.create()를 통해 secp256k1 기반 키페어를 생성한다.
     * 세션키 한도: 목표 가격(targetPrice)을 한도로 사용한다.
     * 유효 기간: 모니터링 종료 예정 시각(scheduledEndAt) 기준으로 계산한다.
     *
     * @param subscription 저장된 신규 모니터링 구독
     * @param context      파싱된 선택 컨텍스트
     */
    /**
     * 세션키를 등록한다 (키페어 생성 + Kafka 이벤트 발행).
     * 모니터링 시나리오(비동기)에서 사용된다.
     */
    private void registerSessionKey(MonitoringSubscription subscription, ParsedSelectionContext context) {
        registerSessionKey(subscription, context, true);
    }

    /**
     * 세션키를 등록한다.
     * <p>
     * publishKafkaEvent=true: 키페어 생성 + DB 저장 + Kafka 비동기 이벤트 발행 (모니터링 시나리오)
     * publishKafkaEvent=false: 키페어 생성 + DB 저장만 수행 (즉시 충족 시나리오 — 동기 REST로 별도 등록)
     *
     * @param subscription     저장된 모니터링 구독
     * @param context          파싱된 선택 컨텍스트
     * @param publishKafkaEvent Kafka 세션키 등록 이벤트 발행 여부
     */
    private void registerSessionKey(MonitoringSubscription subscription, ParsedSelectionContext context, boolean publishKafkaEvent) {
        try {
            // 지갑 한도 검증은 createOrUpdateFromSelection()에서 사전에 수행되었으므로
            // 여기서는 중복 검증 없이 세션키 등록만 진행한다.

            // 1. ECKeyPair 생성 (secp256k1, 무작위 보안 키)
            ECKeyPair keyPair = Keys.createEcKeyPair();
            String privateKeyHex = Numeric.toHexStringNoPrefixZeroPadded(keyPair.getPrivateKey(), 64);
            // Keys.getAddress()는 40자리 hex → "0x" 접두어 추가
            String address = "0x" + Keys.getAddress(keyPair);

            // 2. 구독 엔티티에 키 저장
            subscription.assignSessionKey(address, privateKeyHex);
            monitoringSubscriptionRepository.save(subscription);

            if (!publishKafkaEvent) {
                // 즉시 충족 시나리오: 키페어 생성 + DB 저장만 수행.
                // 블록체인 등록은 ProductSelectionConsumer에서 동기 REST로 처리한다.
                log.info("AI 에이전트 키페어 생성 완료 (Kafka 발행 생략, 동기 REST 등록 예정) - " +
                                "subscriptionId: {}, aiAgent: {}",
                        subscription.getId(), address);
                return;
            }

            // 3. 유효 기간(초) 계산: scheduledEndAt이 없으면 DEFAULT_MONITORING_DURATION_DAYS 적용
            long validSeconds;
            if (subscription.getScheduledEndAt() != null) {
                validSeconds = subscription.getScheduledEndAt().getEpochSecond() - context.now().getEpochSecond();
            } else {
                validSeconds = (long) DEFAULT_MONITORING_DURATION_DAYS * 24 * 3600;
            }

            // 4. 세션키 등록 이벤트 발행 (개인키 포함 — payment-service가 DB에 저장하여 결제 서명에 사용)
            SessionKeyRegistrationEventPayload payload = new SessionKeyRegistrationEventPayload(
                    subscription.getUserId(),
                    subscription.getId(),
                    address,
                    privateKeyHex,
                    subscription.getTargetPrice().longValue(),
                    validSeconds,
                    subscription.getPlatform().name()
            );
            SessionKeyRegistrationEvent event = new SessionKeyRegistrationEvent(
                    UUID.randomUUID().toString(),
                    "SESSION_KEY_REGISTRATION",
                    context.now(),
                    "price-service",
                    payload
            );
            sessionKeyRegistrationEventPublisher.publish(event);

            log.info("AI 에이전트 키페어 생성 및 세션키 등록 이벤트 발행 완료 - " +
                            "subscriptionId: {}, aiAgent: {}, limit: {} KRW, validSeconds: {}",
                    subscription.getId(), address, subscription.getTargetPrice().longValue(), validSeconds);

        } catch (Exception e) {
            log.error("AI 에이전트 키페어 생성 실패 - subscriptionId: {}, 원인: {}",
                    subscription.getId(), e.getMessage(), e);
            // 키페어 생성 실패는 모니터링 구독 자체를 실패시키지 않는다.
            // 세션키 없이 PRICE_TRACK 모드로 동작 가능하다.
        }
    }

    /**
     * AUTO_PURCHASE 조건 생성 시 사용자의 지갑 한도를 사전 검증한다.
     * <p>
     * 목표 가격이 지갑 한도를 초과하면 즉시 예외를 발생시켜
     * 구독 생성 자체를 막는다. 이를 통해 나중에 결제 시점에서
     * "insufficient funds" 오류가 발생하는 것을 방지한다.
     *
     * @param userId        사용자 ID
     * @param targetPriceKrw 목표 가격 (KRW)
     * @throws IllegalArgumentException 지갑 한도를 초과하는 경우
     */
    private void validateWalletLimit(Long userId, long targetPriceKrw) {
        java.math.BigDecimal walletLimit = paymentServiceClient.getWalletLimit(userId);

        // 지갑이 없는 경우는 여기서 검증하지 않음 (지갑 생성은 별도 플로우)
        if (walletLimit == null) {
            log.debug("지갑 한도 조회 결과 null - 지갑이 없거나 조회 실패, userId: {}", userId);
            return;
        }

        if (targetPriceKrw > walletLimit.longValue()) {
            log.warn("지갑 한도 초과로 조건 생성 거부 - userId: {}, targetPrice: {} KRW, walletLimit: {} KRW",
                    userId, targetPriceKrw, walletLimit);
            throw new IllegalArgumentException(
                    String.format("목표 가격(%,d KRW)이 지갑 한도(%s KRW)를 초과합니다. 지갑 한도를 늘리거나 목표 가격을 낮춰주세요.",
                            targetPriceKrw, walletLimit.toPlainString()));
        }

        log.info("지갑 한도 검증 통과 - userId: {}, targetPrice: {} KRW, walletLimit: {} KRW",
                userId, targetPriceKrw, walletLimit);
    }

    /**
     * 선택한 후보 상품 DTO에서 구독 생성/갱신에 필요한 값을 안전하게 파싱한다.
     *
     * @param selectedProduct  선택 상품 DTO
     * @param targetPriceValue 목표 가격
     * @return 파싱된 보조 컨텍스트
     */
    private ParsedSelectionContext parseSelectionContext(ProductCandidateDto selectedProduct, Integer targetPriceValue) {
        return parseSelectionContext(selectedProduct, targetPriceValue, null);
    }

    private ParsedSelectionContext parseSelectionContext(ProductCandidateDto selectedProduct,
                                                         Integer targetPriceValue,
                                                         Instant customScheduledEndAt) {
        Platform platform = parsePlatform(selectedProduct.platform());
        CurrencyType currency = parseCurrency(selectedProduct.currency());
        BigDecimal snapshotPrice = parseBigDecimal(selectedProduct.lprice(), "lprice");
        BigDecimal targetPrice = targetPriceValue != null
                ? BigDecimal.valueOf(targetPriceValue)
                : snapshotPrice;
        Instant now = Instant.now();
        // 사용자가 마감일을 지정했으면 우선 적용, 없으면 기본 7일 후
        Instant resolvedEndAt = (customScheduledEndAt != null && customScheduledEndAt.isAfter(now))
                ? customScheduledEndAt
                : now.plus(DEFAULT_MONITORING_DURATION_DAYS, ChronoUnit.DAYS);
        return new ParsedSelectionContext(platform, currency, snapshotPrice, targetPrice, now, resolvedEndAt);
    }

    /**
     * 문자열 platform 값을 Platform enum으로 변환한다.
     *
     * @param platformValue payload에 담긴 플랫폼 문자열
     * @return Platform enum
     * @throws IllegalArgumentException 지원하지 않는 플랫폼값인 경우
     */
    private Platform parsePlatform(String platformValue) {
        try {
            return Platform.valueOf(platformValue);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("지원하지 않는 플랫폼값입니다: " + platformValue, e);
        }
    }

    /**
     * 문자열 currency 값을 CurrencyType enum으로 변환한다.
     *
     * @param currencyValue payload에 담긴 통화 문자열
     * @return CurrencyType enum
     * @throws IllegalArgumentException 지원하지 않는 currency 값인 경우
     */
    private CurrencyType parseCurrency(String currencyValue) {
        try {
            return CurrencyType.valueOf(currencyValue);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("지원하지 않는 currency 값입니다: " + currencyValue, e);
        }
    }

    private BigDecimal parseBigDecimal(String value, String fieldName) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException("유효하지 않은 " + fieldName + " 값입니다: " + value, e);
        }
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
