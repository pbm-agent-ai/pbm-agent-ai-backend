package com.pbm.price.service;

import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.event.ProductCandidateDto;
import com.pbm.price.repository.MonitoringSubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 모니터링 구독 생성/갱신 서비스.
 *
 * 역할: 사용자가 선택한 후보 상품 정보를 ProductCandidateDto로 받아
 *       monitoring_subscriptions 레코드를 생성하거나 기존 구독을 갱신한다.
 * 동작:
 *   1. payload에서 platform/currency/가격을 파싱한다.
 *   2. 동일 사용자 + 동일 플랫폼 + 동일 productId 구독이 있는지 조회한다.
 *   3. 기존 구독이 있으면 최신 선택 정보로 갱신한다.
 *   4. 기존 구독이 없으면 ACTIVE 상태의 새 구독을 생성한다.
 *   5. markChecked(now)로 초기 수집 시각과 다음 수집 예정 시각을 설정한다.
 * 연관: MonitoringSubscriptionRepository, MonitoringSubscription, ProductCandidateDto.
 */
@Service
@Transactional
public class MonitoringSubscriptionService {

    private static final int DEFAULT_CHECK_INTERVAL_MINUTES = 10;

    /**
     * payload 파싱 결과를 한 번에 묶어 서비스 내부에서만 전달하기 위한 보조 타입.
     */
    private record ParsedSelectionContext(
            Platform platform,
            CurrencyType currency,
            BigDecimal snapshotPrice,
            BigDecimal targetPrice,
            Instant now
    ) {
    }

    private final MonitoringSubscriptionRepository monitoringSubscriptionRepository;

    public MonitoringSubscriptionService(MonitoringSubscriptionRepository monitoringSubscriptionRepository) {
        this.monitoringSubscriptionRepository = monitoringSubscriptionRepository;
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
        ParsedSelectionContext context = parseSelectionContext(selectedProduct, targetPriceValue);

        return monitoringSubscriptionRepository.findByUserIdAndPlatformAndProductId(
                        userId,
                        context.platform(),
                        selectedProduct.productId()
                )
                .map(existing -> updateExistingSubscription(existing, commandId, intent, selectedProduct, context))
                .orElseGet(() -> createNewSubscription(userId, commandId, intent, selectedProduct, context));
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
            ParsedSelectionContext context
    ) {
        existing.updateSelectionSnapshot(
                commandId,
                selectedProduct.productUrl(),
                selectedProduct.title(),
                context.snapshotPrice(),
                selectedProduct.searchKeyword(),
                context.targetPrice(),
                intent,
                context.currency()
        );
        existing.changeStatus(MonitoringSubscriptionStatus.ACTIVE);
        existing.resetMissCount();
        existing.markChecked(context.now());

        return monitoringSubscriptionRepository.save(existing);
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
            ParsedSelectionContext context
    ) {
        MonitoringSubscription subscription = MonitoringSubscription.create(
                userId,
                commandId,
                context.platform(),
                selectedProduct.productId(),
                selectedProduct.productUrl(),
                selectedProduct.title(),
                context.snapshotPrice(),
                selectedProduct.searchKeyword(),
                context.targetPrice(),
                context.currency(),
                intent,
                MonitoringSubscriptionStatus.ACTIVE,
                0,
                DEFAULT_CHECK_INTERVAL_MINUTES
        );

        subscription.markChecked(context.now());

        return monitoringSubscriptionRepository.save(subscription);
    }

    /**
     * 선택한 후보 상품 DTO에서 구독 생성/갱신에 필요한 값을 안전하게 파싱한다.
     *
     * @param selectedProduct  선택 상품 DTO
     * @param targetPriceValue 목표 가격
     * @return 파싱된 보조 컨텍스트
     */
    private ParsedSelectionContext parseSelectionContext(ProductCandidateDto selectedProduct, Integer targetPriceValue) {
        Platform platform = parsePlatform(selectedProduct.platform());
        CurrencyType currency = parseCurrency(selectedProduct.currency());
        BigDecimal snapshotPrice = parseBigDecimal(selectedProduct.lprice(), "lprice");
        BigDecimal targetPrice = BigDecimal.valueOf(targetPriceValue);
        Instant now = Instant.now();
        return new ParsedSelectionContext(platform, currency, snapshotPrice, targetPrice, now);
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
}
