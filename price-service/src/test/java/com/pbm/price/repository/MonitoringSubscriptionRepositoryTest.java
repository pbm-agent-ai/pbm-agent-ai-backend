package com.pbm.price.repository;

import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MonitoringSubscriptionRepository의 JPA 쿼리 동작을 검증하는 슬라이스 테스트.
 *
 * 역할: 실제 DB(H2)에 데이터를 저장/조회해 Repository 메서드의 결과를 검증한다.
 * 동작: 테스트 데이터 저장 -> Repository 메서드 호출 -> 필드 값/컬렉션 크기 확인.
 * 연관: MonitoringSubscription, MonitoringSubscriptionRepository.
 */
@DataJpaTest
class MonitoringSubscriptionRepositoryTest {

    @Autowired
    private MonitoringSubscriptionRepository repository;

    @Test
    @DisplayName("save: 구독 저장 시 ID가 생성되고 모든 필드가 영속화된다")
    void save_persistsSubscriptionAndGeneratesId() {
        // given
        MonitoringSubscription subscription = createSubscription(1L, "prod-1");

        // when
        MonitoringSubscription saved = repository.save(subscription);

        // then: ID 자동 생성 및 필드 값 보존
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getCommandId()).isNotNull();
        assertThat(saved.getPlatform()).isEqualTo(Platform.NAVER);
        assertThat(saved.getProductId()).isEqualTo("prod-1");
        assertThat(saved.getProductUrl()).isEqualTo("https://example.com/product/prod-1");
        assertThat(saved.getSnapshotTitle()).isEqualTo("테스트 상품");
        assertThat(saved.getSnapshotPrice()).isEqualByComparingTo(BigDecimal.valueOf(50000));
        assertThat(saved.getSnapshotImageUrl()).isEqualTo("https://example.com/image/prod-1.jpg");
        assertThat(saved.getSearchKeyword()).isEqualTo("테스트 키워드");
        assertThat(saved.getTargetPrice()).isEqualByComparingTo(BigDecimal.valueOf(45000));
        assertThat(saved.getCurrency()).isEqualTo(CurrencyType.KRW);
        assertThat(saved.getIntent()).isEqualTo("PRICE_TRACK");
        assertThat(saved.getStatus()).isEqualTo(MonitoringSubscriptionStatus.ACTIVE);
        assertThat(saved.getConsecutiveMissCount()).isZero();
        assertThat(saved.getCheckIntervalMinutes()).isEqualTo(10);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findByUserId: 특정 사용자의 구독 목록을 반환한다")
    void findByUserId_returnsSubscriptionsForUser() {
        // given
        repository.save(createSubscription(1L, "prod-1"));
        repository.save(createSubscription(1L, "prod-2"));
        repository.save(createSubscription(2L, "prod-3"));

        // when
        List<MonitoringSubscription> user1Subs = repository.findByUserId(1L);

        // then
        assertThat(user1Subs).hasSize(2);
        assertThat(user1Subs).allMatch(s -> s.getUserId().equals(1L));
    }

    @Test
    @DisplayName("findByUserIdAndPlatformAndProductId: 특정 사용자의 특정 상품 구독을 반환한다")
    void findByUserIdAndPlatformAndProductId_returnsMatchingSubscription() {
        // given
        repository.save(createSubscription(1L, "naver-prod-1"));

        // when
        Optional<MonitoringSubscription> found = repository
                .findByUserIdAndPlatformAndProductId(1L, Platform.NAVER, "naver-prod-1");

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getProductId()).isEqualTo("naver-prod-1");
    }

    @Test
    @DisplayName("findByUserIdAndPlatformAndProductId: 존재하지 않는 조합이면 빈 Optional을 반환한다")
    void findByUserIdAndPlatformAndProductId_withNonExistent_returnsEmpty() {
        // when
        Optional<MonitoringSubscription> found = repository
                .findByUserIdAndPlatformAndProductId(999L, Platform.NAVER, "nonexistent");

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findByCommandId: 명령 세션 ID로 구독을 조회한다")
    void findByCommandId_returnsMatchingSubscription() {
        // given
        String commandId = UUID.randomUUID().toString();
        MonitoringSubscription subscription = MonitoringSubscription.create(
                1L, commandId, Platform.NAVER, "prod-1",
                "https://example.com/product/1", "테스트 상품",
                BigDecimal.valueOf(50000), "https://example.com/image/prod-1.jpg",
                "키워드", BigDecimal.valueOf(45000),
                CurrencyType.KRW, "PRICE_TRACK", MonitoringSubscriptionStatus.ACTIVE,
                0, 10, null
        );
        MonitoringSubscription saved = repository.save(subscription);

        // when
        Optional<MonitoringSubscription> found = repository.findByCommandId(commandId);

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("findByCommandId: 존재하지 않는 commandId면 빈 Optional을 반환한다")
    void findByCommandId_withNonExistentId_returnsEmpty() {
        // when
        Optional<MonitoringSubscription> found = repository.findByCommandId("non-existent-uuid");

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findByStatusAndNextCheckAtBefore: 수집 예정 시각이 도래한 구독 목록을 반환한다")
    void findByStatusAndNextCheckAtBefore_returnsDueSubscriptions() {
        // given: 과거 nextCheckAt을 가진 ACTIVE 구독
        MonitoringSubscription dueSub = MonitoringSubscription.create(
                1L, UUID.randomUUID().toString(), Platform.NAVER, "prod-due",
                null, null, null, null, null, null,
                CurrencyType.KRW, null, MonitoringSubscriptionStatus.ACTIVE,
                0, 10, null
        );
        dueSub.markChecked(Instant.now().minusSeconds(3600)); // 1시간 전 수집 -> nextCheckAt 과거
        repository.save(dueSub);

        // 미래 nextCheckAt을 가진 ACTIVE 구독 (수집 대상 아님)
        MonitoringSubscription futureSub = MonitoringSubscription.create(
                2L, UUID.randomUUID().toString(), Platform.ALIEXPRESS, "prod-future",
                null, null, null, null, null, null,
                CurrencyType.USD, null, MonitoringSubscriptionStatus.ACTIVE,
                0, 10, null
        );
        futureSub.markChecked(Instant.now()); // 방금 수집 -> nextCheckAt 미래
        repository.save(futureSub);

        // PAUSED 상태 구독 (수집 대상 아님)
        MonitoringSubscription pausedSub = MonitoringSubscription.create(
                3L, UUID.randomUUID().toString(), Platform.NAVER, "prod-paused",
                null, null, null, null, null, null,
                CurrencyType.KRW, null, MonitoringSubscriptionStatus.PAUSED,
                0, 10, null
        );
        pausedSub.markChecked(Instant.now().minusSeconds(3600)); // nextCheckAt 과거지만 PAUSED
        repository.save(pausedSub);

        // when
        List<MonitoringSubscription> dueSubs = repository
                .findByStatusAndNextCheckAtBefore(MonitoringSubscriptionStatus.ACTIVE, Instant.now());

        // then: ACTIVE + nextCheckAt이 과거인 구독만 조회되어야 함
        assertThat(dueSubs).hasSize(1);
        assertThat(dueSubs.get(0).getProductId()).isEqualTo("prod-due");
    }

    @Test
    @DisplayName("markChecked: 수집 시각과 다음 수집 예정 시각이 갱신된다")
    void markChecked_updatesTimestamps() {
        // given
        MonitoringSubscription sub = repository.save(createSubscription(1L, "prod-1"));
        Instant now = Instant.now();

        // when
        sub.markChecked(now);
        MonitoringSubscription updated = repository.save(sub);

        // then
        assertThat(updated.getLastCheckedAt()).isNotNull();
        assertThat(updated.getNextCheckAt()).isAfter(now); // 10분 후
    }

    @Test
    @DisplayName("markSuccess: 연속 실패 횟수가 초기화되고 수집 시각이 갱신된다")
    void markSuccess_resetsMissCountAndUpdatesTimestamps() {
        // given
        MonitoringSubscription sub = createSubscription(1L, "prod-1");
        // 연속 실패 3회 상태로 설정
        Instant past = Instant.now().minusSeconds(3600);
        sub.markMiss(past);
        sub.markMiss(past);
        sub.markMiss(past);
        repository.save(sub);

        // when
        Instant now = Instant.now();
        sub.markSuccess(now);
        MonitoringSubscription updated = repository.save(sub);

        // then
        assertThat(updated.getConsecutiveMissCount()).isZero();
        assertThat(updated.getLastCheckedAt()).isNotNull();
    }

    @Test
    @DisplayName("changeStatus: 구독 상태가 변경된다")
    void changeStatus_updatesStatus() {
        // given
        MonitoringSubscription sub = repository.save(createSubscription(1L, "prod-1"));

        // when
        sub.changeStatus(MonitoringSubscriptionStatus.PAUSED);
        MonitoringSubscription updated = repository.save(sub);

        // then
        assertThat(updated.getStatus()).isEqualTo(MonitoringSubscriptionStatus.PAUSED);
    }

    @Test
    @DisplayName("updateTargetPrice: 목표 가격이 갱신된다")
    void updateTargetPrice_updatesTargetPrice() {
        // given
        MonitoringSubscription sub = repository.save(createSubscription(1L, "prod-1"));

        // when
        sub.updateTargetPrice(BigDecimal.valueOf(30000));
        MonitoringSubscription updated = repository.save(sub);

        // then
        assertThat(updated.getTargetPrice()).isEqualByComparingTo(BigDecimal.valueOf(30000));
    }

    private MonitoringSubscription createSubscription(Long userId, String productId) {
        return MonitoringSubscription.create(
                userId,
                UUID.randomUUID().toString(),
                Platform.NAVER,
                productId,
                "https://example.com/product/" + productId,
                "테스트 상품",
                BigDecimal.valueOf(50000),
                "https://example.com/image/" + productId + ".jpg",
                "테스트 키워드",
                BigDecimal.valueOf(45000),
                CurrencyType.KRW,
                "PRICE_TRACK",
                MonitoringSubscriptionStatus.ACTIVE,
                0,
                10,
                null
        );
    }

}
