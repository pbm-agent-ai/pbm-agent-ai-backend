package com.pbm.price.service;

import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.dto.event.SubscriptionTerminationEvent;
import com.pbm.price.dto.event.SubscriptionTerminationEventPayload;
import com.pbm.price.publisher.SubscriptionTerminationEventPublisher;
import com.pbm.price.repository.MonitoringSubscriptionRepository;
import com.pbm.price.repository.MonitorTargetRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 모니터링 구독 만료 스케줄러.
 *
 * 역할: 종료 예정 시각(scheduledEndAt)이 지난 ACTIVE 구독을 자동으로 COMPLETED 처리한다.
 *       ACTIVE 구독이 0건이 되면 해당 MonitorTarget의 폴링을 비활성화한다.
 *
 * 변경 이력:
 *   - 기존 processDueSubscriptions() 제거: 조건 평가는 PriceMonitoringScheduler가
 *     가격 수집 직후 인라인으로 수행하므로 별도 스케줄링이 불필요해짐.
 *   - 만료 처리만 유지: 시간 기반 자동 종료는 가격 수집과 무관하므로 독립 스케줄러로 유지.
 *
 * 연관: MonitoringSubscriptionRepository, MonitorTargetRepository,
 *       SubscriptionTerminationEventPublisher.
 */
@Slf4j
@Component
public class SubscriptionMonitoringScheduler {

    private final MonitoringSubscriptionRepository monitoringSubscriptionRepository;
    private final MonitorTargetRepository monitorTargetRepository;
    private final SubscriptionTerminationEventPublisher subscriptionTerminationEventPublisher;

    public SubscriptionMonitoringScheduler(
            MonitoringSubscriptionRepository monitoringSubscriptionRepository,
            MonitorTargetRepository monitorTargetRepository,
            SubscriptionTerminationEventPublisher subscriptionTerminationEventPublisher
    ) {
        this.monitoringSubscriptionRepository = monitoringSubscriptionRepository;
        this.monitorTargetRepository = monitorTargetRepository;
        this.subscriptionTerminationEventPublisher = subscriptionTerminationEventPublisher;
    }

    /**
     * 종료 예정 시각(scheduledEndAt)이 지난 ACTIVE 구독을 자동으로 COMPLETED 처리한다.
     * <p>
     * 매시간 실행되며, 만료된 구독을 조회하여 상태를 COMPLETED로 변경하고 저장한다.
     */
    @Transactional
    @Scheduled(fixedDelayString = "${app.monitoring.expiry-check-interval-ms:3600000}")
    public void expireScheduledSubscriptions() {
        Instant now = Instant.now();

        List<MonitoringSubscription> expired =
                monitoringSubscriptionRepository.findByStatusAndScheduledEndAtBefore(
                        MonitoringSubscriptionStatus.ACTIVE,
                        now
                );

        if (expired.isEmpty()) {
            log.debug("만료 처리할 모니터링 구독 없음 - now: {}", now);
            return;
        }

        log.info("모니터링 구독 만료 처리 시작 - count: {}, now: {}", expired.size(), now);

        for (MonitoringSubscription subscription : expired) {
            subscription.changeStatus(MonitoringSubscriptionStatus.COMPLETED);
            monitoringSubscriptionRepository.save(subscription);
            log.info("모니터링 구독 만료 완료 처리 - subscriptionId: {}, scheduledEndAt: {}",
                    subscription.getId(), subscription.getScheduledEndAt());

            // ACTIVE 구독이 0건이면 MonitorTarget 폴링 비활성화
            long activeCount = monitoringSubscriptionRepository
                    .countByPlatformAndProductIdAndStatus(
                            subscription.getPlatform(),
                            subscription.getProductId(),
                            MonitoringSubscriptionStatus.ACTIVE
                    );
            if (activeCount == 0) {
                monitorTargetRepository.findByPlatformAndProductId(
                        subscription.getPlatform(), subscription.getProductId()
                ).ifPresent(target -> {
                    target.deactivate();
                    monitorTargetRepository.save(target);
                    log.info("MonitorTarget 비활성화 완료 - platform: {}, productId: {} (잔여 ACTIVE 구독 없음)",
                            subscription.getPlatform(), subscription.getProductId());
                });
            }

            subscriptionTerminationEventPublisher.publish(new SubscriptionTerminationEvent(
                    UUID.randomUUID().toString(),
                    "SUBSCRIPTION_TERMINATED",
                    Instant.now(),
                    "price-service",
                    new SubscriptionTerminationEventPayload(
                            subscription.getId(), subscription.getUserId(), "EXPIRED")
            ));
        }

        log.info("모니터링 구독 만료 처리 종료 - count: {}", expired.size());
    }
}
