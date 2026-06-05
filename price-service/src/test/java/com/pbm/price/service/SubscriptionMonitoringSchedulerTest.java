package com.pbm.price.service;

import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import com.pbm.price.publisher.SubscriptionTerminationEventPublisher;
import com.pbm.price.repository.MonitoringSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubscriptionMonitoringScheduler 단위 테스트.
 *
 * 검증 내용:
 * - 수집 예정 시각이 도래한 ACTIVE 구독만 조회하여 순차 처리하는지 확인
 * - 처리할 구독이 없으면 process를 호출하지 않는지 확인
 * - 한 건 실패가 전체 배치를 중단시키지 않는지 확인
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionMonitoringSchedulerTest {

    @Mock
    private MonitoringSubscriptionRepository monitoringSubscriptionRepository;

    @Mock
    private SubscriptionMonitoringService subscriptionMonitoringService;

    @Mock
    private SubscriptionTerminationEventPublisher subscriptionTerminationEventPublisher;

    @InjectMocks
    private SubscriptionMonitoringScheduler scheduler;

    private Instant baseTime;

    @BeforeEach
    void setUp() {
        baseTime = Instant.now();
    }

    /**
     * 테스트용 MonitoringSubscription 엔티티를 생성한다.
     * ID는 ReflectionTestUtils로 설정한다.
     */
    private MonitoringSubscription createSubscription(Long id, Platform platform, String productId) {
        MonitoringSubscription sub = MonitoringSubscription.create(
                1L,                               // userId
                UUID.randomUUID().toString(),     // commandId
                platform,                         // platform
                productId,                        // productId
                "https://example.com/p/" + productId, // productUrl
                "테스트 상품 " + productId,         // snapshotTitle
                BigDecimal.valueOf(50000),         // snapshotPrice
                null,                             // snapshotImageUrl
                "테스트 키워드",                    // searchKeyword
                BigDecimal.valueOf(30000),         // targetPrice
                CurrencyType.KRW,                  // currency
                "PRICE_TRACK",                     // intent
                MonitoringSubscriptionStatus.ACTIVE, // status
                0,                                 // consecutiveMissCount
                5,                                 // checkIntervalMinutes
                null                               // scheduledEndAt
        );
        ReflectionTestUtils.setField(sub, "id", id);
        sub.markChecked(baseTime.minusSeconds(600)); // 10분 전에 체크 완료
        return sub;
    }

    @Test
    @DisplayName("처리할 구독이 없으면 process를 호출하지 않는다")
    void processDueSubscriptions_noSubscriptions_doesNotCallProcess() {
        // given: 수집 예정 대상이 없음
        when(monitoringSubscriptionRepository.findByStatusAndNextCheckAtBefore(any(), any()))
                .thenReturn(List.of());

        // when: 스케줄러 실행
        scheduler.processDueSubscriptions();

        // then: process가 호출되지 않음
        verify(subscriptionMonitoringService, never()).process(anyLong());
    }

    @Test
    @DisplayName("여러 구독이 있으면 각각 process를 호출한다")
    void processDueSubscriptions_multipleSubscriptions_callsProcessForEach() {
        // given: 2건의 구독 대상
        MonitoringSubscription sub1 = createSubscription(1L, Platform.NAVER, "prod-001");
        MonitoringSubscription sub2 = createSubscription(2L, Platform.ALIEXPRESS, "prod-002");

        when(monitoringSubscriptionRepository.findByStatusAndNextCheckAtBefore(any(), any()))
                .thenReturn(List.of(sub1, sub2));

        // when: 스케줄러 실행
        scheduler.processDueSubscriptions();

        // then: 각 구독에 대해 process가 호출됨
        verify(subscriptionMonitoringService, times(1)).process(1L);
        verify(subscriptionMonitoringService, times(1)).process(2L);
    }

    @Test
    @DisplayName("한 구독 처리 실패해도 나머지 구독은 계속 처리한다")
    void processDueSubscriptions_partialFailure_continuesProcessing() {
        // given: 2건의 구독 대상 중 첫 번째가 실패
        MonitoringSubscription sub1 = createSubscription(1L, Platform.NAVER, "prod-001");
        MonitoringSubscription sub2 = createSubscription(2L, Platform.ALIEXPRESS, "prod-002");

        when(monitoringSubscriptionRepository.findByStatusAndNextCheckAtBefore(any(), any()))
                .thenReturn(List.of(sub1, sub2));
        doThrow(new RuntimeException("process 처리 실패")).when(subscriptionMonitoringService).process(1L);

        // when: 스케줄러 실행
        scheduler.processDueSubscriptions();

        // then: 첫 번째 실패 후에도 두 번째 구독은 정상 처리됨
        verify(subscriptionMonitoringService).process(1L);  // 실패했지만 호출은 됨
        verify(subscriptionMonitoringService).process(2L);  // 정상 처리
    }
}
