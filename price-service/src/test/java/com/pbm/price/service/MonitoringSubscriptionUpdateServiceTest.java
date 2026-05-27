package com.pbm.price.service;

import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.request.MonitoringSubscriptionUpdateRequest;
import com.pbm.price.exception.SubscriptionAccessDeniedException;
import com.pbm.price.exception.SubscriptionNotFoundException;
import com.pbm.price.repository.MonitoringSubscriptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MonitoringSubscriptionService.updateSubscription() 단위 테스트.
 *
 * 역할: 모니터링 구독 조건 수정 로직 (intent, targetPrice, scheduledEndAt)을 검증한다.
 * 동작: @DataJpaTest + @Import로 실제 JPA를 사용하여 저장/조회 흐름을 통합 검증한다.
 * 연관: MonitoringSubscriptionService, MonitoringSubscriptionRepository.
 */
@DataJpaTest
@Import(MonitoringSubscriptionService.class)
class MonitoringSubscriptionUpdateServiceTest {

    @Autowired
    private MonitoringSubscriptionService monitoringSubscriptionService;

    @Autowired
    private MonitoringSubscriptionRepository monitoringSubscriptionRepository;

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    @Test
    @DisplayName("intent 수정: AUTO_PURCHASE → PRICE_TRACK으로 변경된다")
    void updateSubscription_updatesIntent_successfully() {
        // given
        MonitoringSubscription saved = saveSubscription(USER_ID, "AUTO_PURCHASE");
        MonitoringSubscriptionUpdateRequest request =
                new MonitoringSubscriptionUpdateRequest("PRICE_TRACK", null, null);

        // when
        MonitoringSubscription result = monitoringSubscriptionService.updateSubscription(
                USER_ID, saved.getId(), request
        );

        // then
        assertThat(result.getIntent()).isEqualTo("PRICE_TRACK");
        assertThat(result.getTargetPrice()).isEqualByComparingTo(saved.getTargetPrice()); // 변경 없음
    }

    @Test
    @DisplayName("targetPrice 수정: 목표 가격이 새 값으로 변경된다")
    void updateSubscription_updatesTargetPrice_successfully() {
        // given
        MonitoringSubscription saved = saveSubscription(USER_ID, "AUTO_PURCHASE");
        BigDecimal newPrice = new BigDecimal("150000");
        MonitoringSubscriptionUpdateRequest request =
                new MonitoringSubscriptionUpdateRequest(null, newPrice, null);

        // when
        MonitoringSubscription result = monitoringSubscriptionService.updateSubscription(
                USER_ID, saved.getId(), request
        );

        // then
        assertThat(result.getTargetPrice()).isEqualByComparingTo(newPrice);
        assertThat(result.getIntent()).isEqualTo("AUTO_PURCHASE"); // 변경 없음
    }

    @Test
    @DisplayName("scheduledEndAt 수정: 종료 예정 시각이 새 값으로 변경된다")
    void updateSubscription_updatesScheduledEndAt_successfully() {
        // given
        MonitoringSubscription saved = saveSubscription(USER_ID, "AUTO_PURCHASE");
        Instant newEndAt = Instant.now().plus(30, ChronoUnit.DAYS);
        MonitoringSubscriptionUpdateRequest request =
                new MonitoringSubscriptionUpdateRequest(null, null, newEndAt);

        // when
        MonitoringSubscription result = monitoringSubscriptionService.updateSubscription(
                USER_ID, saved.getId(), request
        );

        // then
        assertThat(result.getScheduledEndAt()).isEqualTo(newEndAt);
    }

    @Test
    @DisplayName("세 필드 동시 수정: 모두 한 번에 변경된다")
    void updateSubscription_updatesAllFields_simultaneously() {
        // given
        MonitoringSubscription saved = saveSubscription(USER_ID, "AUTO_PURCHASE");
        Instant newEndAt = Instant.now().plus(14, ChronoUnit.DAYS);
        MonitoringSubscriptionUpdateRequest request =
                new MonitoringSubscriptionUpdateRequest("PRICE_TRACK", new BigDecimal("120000"), newEndAt);

        // when
        MonitoringSubscription result = monitoringSubscriptionService.updateSubscription(
                USER_ID, saved.getId(), request
        );

        // then
        assertThat(result.getIntent()).isEqualTo("PRICE_TRACK");
        assertThat(result.getTargetPrice()).isEqualByComparingTo(new BigDecimal("120000"));
        assertThat(result.getScheduledEndAt()).isEqualTo(newEndAt);
    }

    @Test
    @DisplayName("존재하지 않는 subscriptionId면 SubscriptionNotFoundException을 던진다")
    void updateSubscription_throwsNotFoundException_whenNotFound() {
        // given
        Long nonExistentId = 999L;
        MonitoringSubscriptionUpdateRequest request =
                new MonitoringSubscriptionUpdateRequest("PRICE_TRACK", null, null);

        // when & then
        assertThatThrownBy(() ->
                monitoringSubscriptionService.updateSubscription(USER_ID, nonExistentId, request)
        ).isInstanceOf(SubscriptionNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("다른 사용자의 구독이면 SubscriptionAccessDeniedException을 던진다")
    void updateSubscription_throwsAccessDeniedException_whenNotOwner() {
        // given
        MonitoringSubscription saved = saveSubscription(USER_ID, "AUTO_PURCHASE");
        MonitoringSubscriptionUpdateRequest request =
                new MonitoringSubscriptionUpdateRequest("PRICE_TRACK", null, null);

        // when & then
        assertThatThrownBy(() ->
                monitoringSubscriptionService.updateSubscription(OTHER_USER_ID, saved.getId(), request)
        ).isInstanceOf(SubscriptionAccessDeniedException.class)
                .hasMessageContaining(String.valueOf(saved.getId()));
    }

    @Test
    @DisplayName("세 필드가 모두 null이면 IllegalArgumentException을 던진다")
    void updateSubscription_throwsIllegalArgument_whenAllFieldsNull() {
        // given
        MonitoringSubscription saved = saveSubscription(USER_ID, "AUTO_PURCHASE");
        MonitoringSubscriptionUpdateRequest request =
                new MonitoringSubscriptionUpdateRequest(null, null, null);

        // when & then
        assertThatThrownBy(() ->
                monitoringSubscriptionService.updateSubscription(USER_ID, saved.getId(), request)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("수정할 항목이 없습니다");
    }

    @Test
    @DisplayName("허용되지 않는 intent 값이면 IllegalArgumentException을 던진다")
    void updateSubscription_throwsIllegalArgument_whenInvalidIntent() {
        // given
        MonitoringSubscription saved = saveSubscription(USER_ID, "AUTO_PURCHASE");
        MonitoringSubscriptionUpdateRequest request =
                new MonitoringSubscriptionUpdateRequest("INVALID_INTENT", null, null);

        // when & then
        assertThatThrownBy(() ->
                monitoringSubscriptionService.updateSubscription(USER_ID, saved.getId(), request)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("허용되지 않는 intent");
    }

    @Test
    @DisplayName("scheduledEndAt이 과거 시각이면 IllegalArgumentException을 던진다")
    void updateSubscription_throwsIllegalArgument_whenScheduledEndAtIsPast() {
        // given
        MonitoringSubscription saved = saveSubscription(USER_ID, "AUTO_PURCHASE");
        MonitoringSubscriptionUpdateRequest request =
                new MonitoringSubscriptionUpdateRequest(null, null, Instant.now().minusSeconds(3600));

        // when & then
        assertThatThrownBy(() ->
                monitoringSubscriptionService.updateSubscription(USER_ID, saved.getId(), request)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("현재 시각 이후");
    }

    private MonitoringSubscription saveSubscription(Long userId, String intent) {
        MonitoringSubscription sub = MonitoringSubscription.create(
                userId,
                UUID.randomUUID().toString(),
                Platform.NAVER,
                "prod-" + UUID.randomUUID(),
                "https://smartstore.naver.com/products/123",
                "테스트 상품",
                new BigDecimal("189000"),
                "테스트 키워드",
                new BigDecimal("200000"),
                CurrencyType.KRW,
                intent,
                MonitoringSubscriptionStatus.ACTIVE,
                0,
                10,
                Instant.now().plus(7, ChronoUnit.DAYS)
        );
        sub.markChecked(Instant.now());
        return monitoringSubscriptionRepository.save(sub);
    }
}
