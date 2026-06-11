package com.pbm.price.service;

import com.pbm.price.common.PriceCurrencyConverter;
import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.request.UrlPriceReportRequest;
import com.pbm.price.dto.response.UrlActiveTaskResponse;
import com.pbm.price.exception.SubscriptionNotFoundException;
import com.pbm.price.publisher.PriceValidationResultEventPublisher;
import com.pbm.price.repository.MonitorTargetRepository;
import com.pbm.price.repository.MonitoringSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UrlMonitoringService 단위 테스트.
 *
 * 검증 내용:
 * - subscriptionId 미존재 시 SubscriptionNotFoundException 발생 확인
 * - ACTIVE 아닌 구독 보고 시 이벤트 미발행 확인
 * - 목표가 미달 시 trigger 이벤트 미발행 확인
 * - 목표가 충족 시 trigger 이벤트 발행 + 구독 TRIGGERED 처리 확인
 * - 상품명/이미지 최초 크롤링 스냅샷 저장 확인
 * - snapshotPrice 최초 보고만 저장 확인
 * - MonitorTarget 연동 (price_history 저장, next_fetch_at 갱신) 확인
 * - heartbeat에서 monitor_targets 기반 스케줄링 확인
 */
@ExtendWith(MockitoExtension.class)
class UrlMonitoringServiceTest {

    @Mock
    private MonitoringSubscriptionRepository monitoringSubscriptionRepository;

    @Mock
    private MonitorTargetRepository monitorTargetRepository;

    @Mock
    private PriceValidationResultEventPublisher priceValidationResultEventPublisher;

    @Mock
    private PriceCurrencyConverter priceCurrencyConverter;

    @Mock
    private ProductPersistenceService productPersistenceService;

    private UrlMonitoringService urlMonitoringService;

    @BeforeEach
    void setUp() {
        urlMonitoringService = new UrlMonitoringService(
                monitoringSubscriptionRepository,
                monitorTargetRepository,
                priceValidationResultEventPublisher,
                priceCurrencyConverter,
                productPersistenceService
        );
        ReflectionTestUtils.setField(urlMonitoringService, "defaultFetchIntervalMinutes", 10);
    }

    /** 테스트용 ACTIVE MonitoringSubscription 생성 헬퍼 */
    private MonitoringSubscription createActiveSubscription(Long id, BigDecimal targetPrice) {
        MonitoringSubscription sub = MonitoringSubscription.createUrl(
                1L,
                "cmd-test-001",
                "https://ko.aliexpress.com/item/test.html",
                targetPrice,
                CurrencyType.KRW,
                "AUTO_PURCHASE",
                MonitoringSubscriptionStatus.ACTIVE,
                10,
                Instant.now().plusSeconds(604800),
                "ANY"
        );
        ReflectionTestUtils.setField(sub, "id", id);
        return sub;
    }

    /** 테스트용 MonitorTarget 생성 헬퍼 */
    private MonitorTarget createUrlMonitorTarget(String productId) {
        MonitorTarget target = MonitorTarget.create(
                Platform.URL,
                productId,
                "https://ko.aliexpress.com/item/test.html",
                "https://ko.aliexpress.com/item/test.html",
                10
        );
        ReflectionTestUtils.setField(target, "id", 1L);
        return target;
    }

    @Nested
    @DisplayName("processReport - 예외 흐름")
    class ExceptionFlow {

        @Test
        @DisplayName("존재하지 않는 subscriptionId로 보고하면 SubscriptionNotFoundException 발생")
        void 존재하지않는_subscriptionId_예외() {
            // given
            when(monitoringSubscriptionRepository.findById(999L)).thenReturn(Optional.empty());
            UrlPriceReportRequest request = new UrlPriceReportRequest(999L, BigDecimal.valueOf(50000), "KRW");

            // when & then
            assertThatThrownBy(() -> urlMonitoringService.processReport(request))
                    .isInstanceOf(SubscriptionNotFoundException.class);
        }

        @Test
        @DisplayName("ACTIVE가 아닌 구독 보고 시 이벤트 미발행 후 즉시 반환")
        void 비활성_구독_보고_무시() {
            // given
            MonitoringSubscription sub = createActiveSubscription(1L, BigDecimal.valueOf(100000));
            sub.changeStatus(MonitoringSubscriptionStatus.COMPLETED);
            when(monitoringSubscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));

            UrlPriceReportRequest request = new UrlPriceReportRequest(1L, BigDecimal.valueOf(50000), "KRW");

            // when
            urlMonitoringService.processReport(request);

            // then: 어떤 이벤트도 발행되지 않아야 한다
            verify(priceValidationResultEventPublisher, never()).publish(any());
            verify(productPersistenceService, never()).saveUrlPriceReport(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("processReport - 정상 흐름")
    class NormalFlow {

        @Test
        @DisplayName("목표가 미달 시 price_history 저장 + trigger 이벤트 미발행")
        void 목표가_미달_이력저장_트리거미발행() {
            // given
            MonitoringSubscription sub = createActiveSubscription(1L, BigDecimal.valueOf(100000));
            String productId = sub.getProductId();
            MonitorTarget target = createUrlMonitorTarget(productId);

            when(monitoringSubscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
            when(priceCurrencyConverter.toKrw(any(), any())).thenReturn(BigDecimal.valueOf(150000));
            when(monitorTargetRepository.findByPlatformAndProductId(Platform.URL, productId))
                    .thenReturn(Optional.of(target));

            UrlPriceReportRequest request = new UrlPriceReportRequest(1L, BigDecimal.valueOf(150000), "KRW");

            // when
            urlMonitoringService.processReport(request);

            // then
            verify(priceValidationResultEventPublisher, never()).publish(any());
            // price_history 저장 확인
            verify(productPersistenceService).saveUrlPriceReport(eq(target), eq(BigDecimal.valueOf(150000)), any());
            // monitor_target 스케줄 갱신 확인
            verify(monitorTargetRepository).save(target);
        }

        @Test
        @DisplayName("목표가 충족 시 trigger 이벤트 발행 + 구독 TRIGGERED 처리")
        void 목표가_충족_트리거_발행() {
            // given
            MonitoringSubscription sub = createActiveSubscription(1L, BigDecimal.valueOf(100000));
            String productId = sub.getProductId();
            MonitorTarget target = createUrlMonitorTarget(productId);

            when(monitoringSubscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
            when(priceCurrencyConverter.toKrw(any(), any())).thenReturn(BigDecimal.valueOf(80000));
            when(monitorTargetRepository.findByPlatformAndProductId(Platform.URL, productId))
                    .thenReturn(Optional.of(target));
            when(monitoringSubscriptionRepository.findAllByCommandId(any())).thenReturn(List.of(sub));
            when(monitoringSubscriptionRepository.countByPlatformAndProductIdAndStatus(
                    Platform.URL, productId, MonitoringSubscriptionStatus.ACTIVE)).thenReturn(0L);

            UrlPriceReportRequest request = new UrlPriceReportRequest(1L, BigDecimal.valueOf(80000), "KRW");

            // when
            urlMonitoringService.processReport(request);

            // then
            verify(priceValidationResultEventPublisher).publish(any());
            assertThat(sub.getStatus()).isEqualTo(MonitoringSubscriptionStatus.TRIGGERED);
            verify(productPersistenceService).saveUrlPriceReport(eq(target), eq(BigDecimal.valueOf(80000)), any());
        }

        @Test
        @DisplayName("목표가 정확히 일치(경계값)하는 경우도 충족으로 처리")
        void 목표가_경계값_충족() {
            // given
            MonitoringSubscription sub = createActiveSubscription(1L, BigDecimal.valueOf(100000));
            String productId = sub.getProductId();
            MonitorTarget target = createUrlMonitorTarget(productId);

            when(monitoringSubscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
            when(priceCurrencyConverter.toKrw(any(), any())).thenReturn(BigDecimal.valueOf(100000));
            when(monitorTargetRepository.findByPlatformAndProductId(Platform.URL, productId))
                    .thenReturn(Optional.of(target));
            when(monitoringSubscriptionRepository.findAllByCommandId(any())).thenReturn(List.of(sub));
            when(monitoringSubscriptionRepository.countByPlatformAndProductIdAndStatus(
                    Platform.URL, productId, MonitoringSubscriptionStatus.ACTIVE)).thenReturn(0L);

            UrlPriceReportRequest request = new UrlPriceReportRequest(1L, BigDecimal.valueOf(100000), "KRW");

            // when
            urlMonitoringService.processReport(request);

            // then
            verify(priceValidationResultEventPublisher).publish(any());
            assertThat(sub.getStatus()).isEqualTo(MonitoringSubscriptionStatus.TRIGGERED);
        }

        @Test
        @DisplayName("snapshotPrice는 최초 보고 시에만 저장되고 이후 덮어쓰지 않는다")
        void snapshotPrice_최초_보고만_저장() {
            // given: snapshotPrice가 null인 신규 구독
            MonitoringSubscription sub = createActiveSubscription(1L, BigDecimal.valueOf(100000));
            String productId = sub.getProductId();
            MonitorTarget target = createUrlMonitorTarget(productId);
            assertThat(sub.getSnapshotPrice()).isNull();

            when(monitoringSubscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
            when(priceCurrencyConverter.toKrw(any(), any()))
                    .thenReturn(BigDecimal.valueOf(120000))
                    .thenReturn(BigDecimal.valueOf(110000));
            when(monitorTargetRepository.findByPlatformAndProductId(Platform.URL, productId))
                    .thenReturn(Optional.of(target));

            UrlPriceReportRequest firstRequest = new UrlPriceReportRequest(1L, BigDecimal.valueOf(120000), "KRW");
            UrlPriceReportRequest secondRequest = new UrlPriceReportRequest(1L, BigDecimal.valueOf(110000), "KRW");

            // when: 첫 번째 보고
            urlMonitoringService.processReport(firstRequest);
            assertThat(sub.getSnapshotPrice()).isEqualByComparingTo(BigDecimal.valueOf(120000));

            // when: 두 번째 보고
            urlMonitoringService.processReport(secondRequest);

            // then: snapshotPrice는 최초 값 유지
            assertThat(sub.getSnapshotPrice()).isEqualByComparingTo(BigDecimal.valueOf(120000));
        }

        @Test
        @DisplayName("상품명/이미지 포함 보고 시 구독 스냅샷에 저장됨")
        void 스냅샷_최초_저장() {
            // given
            MonitoringSubscription sub = createActiveSubscription(1L, BigDecimal.valueOf(100000));
            String productId = sub.getProductId();
            MonitorTarget target = createUrlMonitorTarget(productId);

            when(monitoringSubscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
            when(priceCurrencyConverter.toKrw(any(), any())).thenReturn(BigDecimal.valueOf(200000));
            when(monitorTargetRepository.findByPlatformAndProductId(Platform.URL, productId))
                    .thenReturn(Optional.of(target));

            UrlPriceReportRequest request = new UrlPriceReportRequest(
                    1L, BigDecimal.valueOf(200000), "KRW",
                    "JBL 사운드바", "https://cdn.aliexpress.com/img/test.jpg"
            );

            // when
            urlMonitoringService.processReport(request);

            // then
            assertThat(sub.getSnapshotTitle()).isEqualTo("JBL 사운드바");
            assertThat(sub.getSnapshotImageUrl()).isEqualTo("https://cdn.aliexpress.com/img/test.jpg");
        }
    }

    @Nested
    @DisplayName("getActiveUrlTasks - heartbeat 스케줄링")
    class HeartbeatFlow {

        @Test
        @DisplayName("monitor_target의 nextFetchAt이 도래한 구독만 반환한다")
        void 도래한_타겟만_반환() {
            // given: sub1과 sub2는 서로 다른 URL(다른 productId)
            MonitoringSubscription sub1 = createActiveSubscription(1L, BigDecimal.valueOf(100000));

            // sub2는 다른 URL로 별도 생성 (다른 productId를 갖도록)
            MonitoringSubscription sub2 = MonitoringSubscription.createUrl(
                    1L, "cmd-test-002",
                    "https://other.com/item/different.html",
                    BigDecimal.valueOf(200000), CurrencyType.KRW, "PRICE_TRACK",
                    MonitoringSubscriptionStatus.ACTIVE, 10,
                    Instant.now().plusSeconds(604800), "ALL"
            );
            ReflectionTestUtils.setField(sub2, "id", 2L);

            String productId1 = sub1.getProductId();
            MonitorTarget dueTarget = createUrlMonitorTarget(productId1);

            when(monitoringSubscriptionRepository.findActiveUrlSubscriptionsByUserId(1L))
                    .thenReturn(List.of(sub1, sub2));
            // sub1의 monitor_target만 도래한 상태
            when(monitorTargetRepository.findDueUrlTargetsByProductIds(any(), any()))
                    .thenReturn(List.of(dueTarget));

            // when
            List<UrlActiveTaskResponse> result = urlMonitoringService.getActiveUrlTasks(1L);

            // then: sub1만 반환 (sub2의 monitor_target은 아직 도래하지 않음)
            assertThat(result).hasSize(1);
            assertThat(result.get(0).subscriptionId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("ACTIVE URL 구독이 없으면 빈 리스트 반환")
        void 구독없으면_빈리스트() {
            // given
            when(monitoringSubscriptionRepository.findActiveUrlSubscriptionsByUserId(1L))
                    .thenReturn(List.of());

            // when
            List<UrlActiveTaskResponse> result = urlMonitoringService.getActiveUrlTasks(1L);

            // then
            assertThat(result).isEmpty();
            verify(monitorTargetRepository, never()).findDueUrlTargetsByProductIds(any(), any());
        }
    }
}
