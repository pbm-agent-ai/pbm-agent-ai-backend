package com.pbm.price.service;

import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.Platform;
import com.pbm.price.publisher.SubscriptionTerminationEventPublisher;
import com.pbm.price.repository.MonitorTargetRepository;
import com.pbm.price.repository.MonitoringSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubscriptionMonitoringScheduler 단위 테스트.
 *
 * 검증 내용:
 * - 만료 예정 시각이 지난 ACTIVE 구독을 COMPLETED 처리하는지 확인
 * - 만료 처리 후 ACTIVE 구독이 0건이면 MonitorTarget 비활성화하는지 확인
 * - 만료 시 SubscriptionTerminationEvent 이벤트를 발행하는지 확인
 * - 만료할 구독이 없으면 아무 동작도 하지 않는지 확인
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionMonitoringSchedulerTest {

    @Mock
    private MonitoringSubscriptionRepository monitoringSubscriptionRepository;

    @Mock
    private MonitorTargetRepository monitorTargetRepository;

    @Mock
    private SubscriptionTerminationEventPublisher subscriptionTerminationEventPublisher;

    private SubscriptionMonitoringScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SubscriptionMonitoringScheduler(
                monitoringSubscriptionRepository,
                monitorTargetRepository,
                subscriptionTerminationEventPublisher
        );
    }

    private MonitoringSubscription createExpiredSubscription(Long id, Platform platform, String productId) {
        MonitoringSubscription sub = MonitoringSubscription.create(
                1L, UUID.randomUUID().toString(), platform, productId,
                "https://example.com/p/" + productId,
                "테스트 상품", BigDecimal.valueOf(50000), null,
                "테스트 키워드", BigDecimal.valueOf(30000), CurrencyType.KRW,
                "PRICE_TRACK", MonitoringSubscriptionStatus.ACTIVE, 0, 10,
                Instant.now().minus(1, ChronoUnit.HOURS)  // 1시간 전 만료 예정
        );
        ReflectionTestUtils.setField(sub, "id", id);
        return sub;
    }

    @Test
    @DisplayName("만료할 구독이 없으면 아무 동작도 하지 않는다")
    void 만료대상_없으면_미처리() {
        // given
        when(monitoringSubscriptionRepository.findByStatusAndScheduledEndAtBefore(any(), any()))
                .thenReturn(List.of());

        // when
        scheduler.expireScheduledSubscriptions();

        // then
        verify(monitoringSubscriptionRepository, never()).save(any());
        verify(subscriptionTerminationEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("만료 예정 시각이 지난 구독을 COMPLETED 처리한다")
    void 만료구독_COMPLETED_처리() {
        // given
        MonitoringSubscription sub = createExpiredSubscription(1L, Platform.NAVER, "prod-001");
        when(monitoringSubscriptionRepository.findByStatusAndScheduledEndAtBefore(any(), any()))
                .thenReturn(List.of(sub));
        when(monitoringSubscriptionRepository.countByPlatformAndProductIdAndStatus(
                Platform.NAVER, "prod-001", MonitoringSubscriptionStatus.ACTIVE)).thenReturn(0L);
        when(monitorTargetRepository.findByPlatformAndProductId(Platform.NAVER, "prod-001"))
                .thenReturn(Optional.of(MonitorTarget.create(Platform.NAVER, "prod-001", "키워드", null, 10)));

        // when
        scheduler.expireScheduledSubscriptions();

        // then
        assertThat(sub.getStatus()).isEqualTo(MonitoringSubscriptionStatus.COMPLETED);
        verify(monitoringSubscriptionRepository).save(sub);
        verify(subscriptionTerminationEventPublisher).publish(any());
    }

    @Test
    @DisplayName("만료 후 ACTIVE 구독이 0건이면 MonitorTarget을 비활성화한다")
    void 만료후_MonitorTarget_비활성화() {
        // given
        MonitoringSubscription sub = createExpiredSubscription(1L, Platform.NAVER, "prod-001");
        MonitorTarget target = MonitorTarget.create(Platform.NAVER, "prod-001", "키워드", null, 10);
        target.markFetched(Instant.now()); // nextFetchAt 설정됨

        when(monitoringSubscriptionRepository.findByStatusAndScheduledEndAtBefore(any(), any()))
                .thenReturn(List.of(sub));
        when(monitoringSubscriptionRepository.countByPlatformAndProductIdAndStatus(
                Platform.NAVER, "prod-001", MonitoringSubscriptionStatus.ACTIVE)).thenReturn(0L);
        when(monitorTargetRepository.findByPlatformAndProductId(Platform.NAVER, "prod-001"))
                .thenReturn(Optional.of(target));

        // when
        scheduler.expireScheduledSubscriptions();

        // then: MonitorTarget.deactivate() 호출됨 (nextFetchAt = null)
        assertThat(target.getNextFetchAt()).isNull();
        verify(monitorTargetRepository).save(target);
    }

    @Test
    @DisplayName("다른 사용자가 같은 상품을 모니터링 중이면 MonitorTarget을 비활성화하지 않는다")
    void 잔여구독있으면_MonitorTarget_유지() {
        // given
        MonitoringSubscription sub = createExpiredSubscription(1L, Platform.NAVER, "prod-001");

        when(monitoringSubscriptionRepository.findByStatusAndScheduledEndAtBefore(any(), any()))
                .thenReturn(List.of(sub));
        // 잔여 ACTIVE 구독 1건 존재
        when(monitoringSubscriptionRepository.countByPlatformAndProductIdAndStatus(
                Platform.NAVER, "prod-001", MonitoringSubscriptionStatus.ACTIVE)).thenReturn(1L);

        // when
        scheduler.expireScheduledSubscriptions();

        // then: MonitorTarget 조회/비활성화가 수행되지 않음
        verify(monitorTargetRepository, never()).findByPlatformAndProductId(any(), any());
    }
}
