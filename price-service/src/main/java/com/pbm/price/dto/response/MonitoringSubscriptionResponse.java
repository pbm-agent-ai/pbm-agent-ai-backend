package com.pbm.price.dto.response;

import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 모니터링 구독 조회 응답 DTO.
 *
 * 역할: MonitoringSubscription 엔티티를 클라이언트에 노출할 정보만 담아 전달한다.
 * 동작: from() 팩토리 메서드로 엔티티 → DTO 변환을 캡슐화한다.
 * 연관: MonitoringController, MonitoringSubscriptionService.
 */
public record MonitoringSubscriptionResponse(
        Long id,
        String commandId,
        String platform,
        String productId,
        String productUrl,
        String snapshotTitle,
        BigDecimal snapshotPrice,
        String snapshotImageUrl,
        String searchKeyword,
        BigDecimal targetPrice,
        String currency,
        String intent,
        MonitoringSubscriptionStatus status,
        Integer checkIntervalMinutes,
        Instant lastCheckedAt,
        Instant nextCheckAt,
        Instant scheduledEndAt,
        Instant createdAt
) {

    /**
     * MonitoringSubscription 엔티티를 응답 DTO로 변환한다.
     *
     * @param subscription 변환할 엔티티
     * @return MonitoringSubscriptionResponse DTO
     */
    public static MonitoringSubscriptionResponse from(MonitoringSubscription subscription) {
        return new MonitoringSubscriptionResponse(
                subscription.getId(),
                subscription.getCommandId(),
                subscription.getPlatform().name(),
                subscription.getProductId(),
                subscription.getProductUrl(),
                subscription.getSnapshotTitle(),
                subscription.getSnapshotPrice(),
                subscription.getSnapshotImageUrl(),
                subscription.getSearchKeyword(),
                subscription.getTargetPrice(),
                subscription.getCurrency().name(),
                subscription.getIntent(),
                subscription.getStatus(),
                subscription.getCheckIntervalMinutes(),
                subscription.getLastCheckedAt(),
                subscription.getNextCheckAt(),
                subscription.getScheduledEndAt(),
                subscription.getCreatedAt()
        );
    }
}
