package com.pbm.price.service;

import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.repository.MonitoringSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
public class SubscriptionMonitoringScheduler {

    private final MonitoringSubscriptionRepository monitoringSubscriptionRepository;
    private final SubscriptionMonitoringService subscriptionMonitoringService;

    public SubscriptionMonitoringScheduler(
            MonitoringSubscriptionRepository monitoringSubscriptionRepository,
            SubscriptionMonitoringService subscriptionMonitoringService
    ) {
        this.monitoringSubscriptionRepository = monitoringSubscriptionRepository;
        this.subscriptionMonitoringService = subscriptionMonitoringService;
    }

    /**
     * 수집 예정 시각이 도래한 ACTIVE 구독을 조회하여 순차 처리한다.
     *
     * fixedDelay를 사용하는 이유:
     * - 이전 배치가 끝난 뒤 일정 시간 후 다음 배치를 시작하기 위함
     * - 외부 API 호출 시간이 길어져도 배치 중복 실행을 피하기 쉬움
     *
     * application.yml에서:
     * app:
     *  monitoring:
     *      scheduler-interval-ms: 60000
     */
    @Scheduled(fixedDelayString = "${app.monitoring.scheduler-interval-ms:60000}")
    public void processDueSubscriptions() {
        Instant now = Instant.now();

        List<MonitoringSubscription> dueSubscriptions =
                monitoringSubscriptionRepository.findByStatusAndNextCheckAtBefore(
                        MonitoringSubscriptionStatus.ACTIVE,
                        now
                );

        if (dueSubscriptions.isEmpty()) {
            log.debug("처리할 모니터링 구독 없음 - now: {}", now);
            return;
        }

        log.info("모니터링 구독 배치 시작 - count: {}, now: {}", dueSubscriptions.size(), now);

        for (MonitoringSubscription subscription : dueSubscriptions) {
            try {
                log.info("모니터링 구독 처리 시작 - subscriptionId: {}, commandId: {}, productId: {}, platform: {}",
                        subscription.getId(),
                        subscription.getCommandId(),
                        subscription.getProductId(),
                        subscription.getPlatform());

                // 실제 재조회/가격 비교/상태 전이/이벤트 발행은 전용 서비스에 위임한다.
                subscriptionMonitoringService.process(subscription.getId());

                log.info("모니터링 구독 처리 완료 - subscriptionId: {}", subscription.getId());
            } catch (Exception e) {
                // 한 건 실패가 전체 배치를 중단시키지 않도록 개별 예외를 먹고 다음 건으로 진행함.
                log.error("모니터링 구독 처리 실패 - subscriptionId: {}, commandId: {}",
                        subscription.getId(),
                        subscription.getCommandId(),
                        e);
            }
        }

        log.info("모니터링 구독 배치 종료 - count: {}", dueSubscriptions.size());
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
        }

        log.info("모니터링 구독 만료 처리 종료 - count: {}", expired.size());
    }
}
